(ns laser-show.views.components.visual-editors.timeline.track-logic
  "Canvas geometry for the timeline editor: row hit-testing, drop targets,
   canvas width, scroll and zoom maths. The timeline document itself lives in
   laser-show.timeline.model. Phase 3 replaces this with a layout namespace."
  (:require [laser-show.timeline.model :as model]))


(defn droppable-track?
  "Returns true if a canvas track row or track definition is a valid drop target
   for clips and track selection (i.e. is an actual leaf track, not a group folder
   or synthetic unassigned row)."
  [track-or-row]
  (boolean
   (and (some? track-or-row)
        (= :track (:type track-or-row :track))
        (not= ::model/unassigned (:id track-or-row))
        (some? (:id track-or-row))
        (if-let [t (:track track-or-row)]
          (model/leaf-track? t)
          (not= :group (:type track-or-row))))))

(defn track-at-y
  "Resolve the track row and index at a canvas vertical coordinate y.
   
   Parameters map:
   - :y            - Canvas vertical coordinate (Number)
   - :ruler-height - Top ruler height in pixels (Number, default 24)
   - :track-height - Height per track lane in pixels (Number, default 32)
   - :track-rows   - Vector of canvas track display rows
   
   Returns {:track-idx int :track row} or nil if y is in the ruler or out of bounds."
  [{:keys [y ruler-height track-height track-rows]
    :or {ruler-height 24 track-height 32}}]
  (when (and (some? y) (>= y ruler-height))
    (let [idx (int (/ (- y ruler-height) track-height))]
      (when (and (>= idx 0) (< idx (count track-rows)))
        {:track-idx idx
         :track (nth track-rows idx)}))))


;; ============================================================
;; Timeline Geometry & Scrolling Math
;; ============================================================


(defn calculate-canvas-width
  "Calculate canvas width based on maximum item reach, loop boundaries, and zoom level.
   Clamped between 800.0 and 8192.0 to stay within GPU hardware texture bounds."
  [{:keys [items loop-config zoom-x]}]
  (let [max-item-end (reduce (fn [m item]
                               (max m (+ (double (:timeline/start item 0.0))
                                         (double (:timeline/duration item 4.0)))))
                             0.0
                             items)
        loop-end (if (:enabled? loop-config true)
                   (+ (double (:start loop-config 0.0))
                      (double (:duration loop-config 4.0)))
                   0.0)
        total-beats (max 32.0 (+ (max max-item-end loop-end) 8.0))
        computed-width (* total-beats (double (or zoom-x 50.0)))]
    (-> computed-width
        (max 800.0)
        (min 8192.0))))


(defn calculate-horizontal-scroll
  "Calculate new normalized horizontal scroll position (0.0 to 1.0)
   given a pixel delta and viewport/content dimensions."
  [{:keys [current-hvalue delta-px content-width viewport-width]
    :or {current-hvalue 0.0 delta-px 0.0}}]
  (let [scrollable-width (- (double (or content-width 0.0))
                            (double (or viewport-width 0.0)))]
    (if (pos? scrollable-width)
      (let [delta-h (/ (double delta-px) scrollable-width)
            new-h (+ (double current-hvalue) delta-h)]
        (-> new-h (max 0.0) (min 1.0)))
      0.0)))


(defn calculate-zoom-at-cursor
  "Calculate new zoom-x and new hvalue so that the focal-beat remains at
   viewport-x in the visible ScrollPane viewport.
   
   Supports both:
   - Modern pinning: :focal-beat and :viewport-x (immune to stale canvas rendering).
   - Canvas-x pinning fallback: :cursor-x and :current-hvalue.
   
   Returns {:new-zoom Double :new-hvalue Double}."
  [{:keys [current-zoom zoom-factor focal-beat viewport-x cursor-x current-hvalue
           content-width viewport-width new-content-width min-zoom max-zoom]
    :or {current-hvalue 0.0 min-zoom 10.0 max-zoom 200.0}}]
  (let [cur-zoom (double (or current-zoom 50.0))
        new-zoom (-> (* cur-zoom (double zoom-factor))
                     (max (double min-zoom))
                     (min (double max-zoom)))]
    (if (== new-zoom cur-zoom)
      {:new-zoom cur-zoom
       :new-hvalue (double current-hvalue)}
      (let [vp-w (double (or viewport-width 0.0))
            scrollable-old (max 0.0 (- (double (or content-width 0.0)) vp-w))
            scroll-px-old (* (double current-hvalue) scrollable-old)
            beat (double (or focal-beat
                             (if (and (number? cursor-x) (pos? cur-zoom))
                               (/ (double cursor-x) cur-zoom)
                               0.0)))
            vp-x (double (or viewport-x
                             (if (number? cursor-x)
                               (- (double cursor-x) scroll-px-old)
                               (/ vp-w 2.0))))
            new-canvas-x (* beat new-zoom)
            target-scroll-px (- new-canvas-x vp-x)
            new-scrollable (max 0.0 (- (double (or new-content-width 0.0)) vp-w))
            new-hvalue (if (pos? new-scrollable)
                         (-> (/ target-scroll-px new-scrollable)
                             (max 0.0)
                             (min 1.0))
                         0.0)]
        {:new-zoom new-zoom
         :new-hvalue new-hvalue}))))
