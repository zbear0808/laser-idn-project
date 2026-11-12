(ns laser-show.views.components.visual-editors.timeline-editor
  "Timeline editor component for sequencing Cue Chain items.
   
   Provides a multi-track timeline view where items are organized by
   explicit Track definitions on the CueChain. Each Track maps to a
   zone group and items are assigned to tracks via :track-id.
   
   Architecture:
   - timeline-editor      : Top-level wrapper (BorderPane)
   - timeline-toolbar     : Zoom, snap, grid controls
   - timeline-headers     : Track names / zone indicators (left sidebar)"
  (:require [cljfx.api :as fx]
            [clojure.tools.logging :as log]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.presets :as presets]
            [laser-show.common.util :as u]
            [laser-show.events.core :as events]
            [laser-show.routing.zone-effects :as ze]
            [laser-show.subs :as subs]
            [laser-show.views.components.effect-bank :as effect-bank]
            [laser-show.views.components.list :as list]
            [laser-show.views.components.preset-bank :as preset-bank]
            [laser-show.views.components.visual-editors.canvas-interaction :as ci]
            [laser-show.views.components.visual-editors.timeline.track-logic :as tl])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.paint Color]
           [javafx.scene.control ScrollPane]
           [javafx.scene.text Font TextAlignment]
           [javafx.scene.input MouseButton KeyCode ScrollEvent]
           [javafx.event EventHandler]))


;; ============================================================
;; Constants
;; ============================================================


(def ^:private track-height 32)
(def ^:private ruler-height 24)
(def ^:private header-width 180)
(def ^:private min-clip-width 4)
(def ^:private edge-grab-px 6)
(def ^:private default-zoom 50.0)  ; pixels per beat


;; ============================================================
;; Color Helpers
;; ============================================================


(def ^:private zone-color-fallback "#808080")

(def ^:private clip-color-default (Color/web "#3A7BD5" 0.85))
(def ^:private clip-color-selected (Color/web "#5BA5F5" 0.95))
(def ^:private grid-line-color (Color/web "#333333"))
(def ^:private grid-line-major-color (Color/web "#555555"))
(def ^:private ruler-bg-color (Color/web "#1A1A1A"))
(def ^:private ruler-text-color (Color/web "#999999"))
(def ^:private track-bg-even (Color/web "#1E1E1E"))
(def ^:private track-bg-odd (Color/web "#232323"))
(def ^:private playhead-color (Color/web "#FF4444"))
(def ^:private selection-border-color (Color/web "#FFD700"))
(def ^:private bg-color (Color/web "#121212"))


(defn- zone-group-color
  "Resolve a Color for a zone-group-id, looking it up in zone-groups config."
  [zone-groups zone-group-id]
  (let [hex (or (get-in zone-groups [zone-group-id :color])
                zone-color-fallback)]
    (Color/web hex 0.6)))

(defn- track-color
  "Resolve a Color for a track definition.
   Uses the track's :color if set, otherwise derives from its zone group."
  [track zone-groups]
  (let [hex (or (:color track)
                (get-in zone-groups [(:zone-group-id track) :color])
                zone-color-fallback)]
    (Color/web hex 0.6)))


;; ============================================================
;; Track Building
;; ============================================================


(defn- build-tracks
  "Build a flat vector of display rows from explicit Track tree definitions and items.
   
   Visible tracks come from tl/flatten-visible-tracks. A collapsed folder's row
   shows the clips of every track hidden under it (:summary? true), so
   collapsing never moves clips to \"Unassigned\"."
  [track-defs items]
  (if (seq track-defs)
    (let [grouped (tl/items-by-track items)
          flat-tracks (tl/flatten-visible-tracks track-defs)
          shown-ids (into #{} (mapcat #(cons (:id %) (:hidden-descendants %))) flat-tracks)
          track-rows (mapv (fn [track]
                             (let [hidden (:hidden-descendants track)]
                               (cond-> {:id (:id track)
                                        :label (:name track)
                                        :type (:type track :track)
                                        :track track
                                        :items (if (seq hidden)
                                                 (->> (cons (:id track) hidden)
                                                      (mapcat #(get grouped % []))
                                                      (sort-by #(:timeline/start % 0.0))
                                                      vec)
                                                 (get grouped (:id track) []))
                                        :zone-group-id (:zone-group-id track)}
                                 (seq hidden) (assoc :summary? true))))
                           flat-tracks)
          ;; Items with no track or a track that doesn't exist (only possible
          ;; before normalization)
          unassigned (into (get grouped ::tl/unassigned [])
                           (mapcat (fn [[tid t-items]]
                                     (when (and (not= tid ::tl/unassigned)
                                                (not (contains? shown-ids tid)))
                                       t-items))
                                   grouped))]
      (if (seq unassigned)
        (conj track-rows
              {:id ::unassigned
               :label "Unassigned"
               :type :track
               :track nil
               :items (vec unassigned)
               :zone-group-id :all})
        track-rows))
    ;; Legacy fallback: one row per item (no track definitions)
    (mapv (fn [item]
            {:id (:id item)
             :label (or (:name item)
                        (some-> (:preset-id item) name)
                        (some-> (:effect-id item) name)
                        "???")
             :type :legacy-item
             :track nil
             :items [item]
             :zone-group-id nil})
          items)))


(defn- track-y
  "Calculate the Y position for a track given its index."
  [track-idx]
  (+ ruler-height (* track-idx track-height)))


(defn- total-canvas-height
  "Calculate total canvas height for the given number of tracks."
  [track-count]
  (+ ruler-height (* track-count track-height) 20))


(defn- calculate-canvas-width
  "Calculate canvas width based on maximum item reach, loop boundaries, and zoom level."
  [items loop-config zoom-x]
  (tl/calculate-canvas-width {:items items :loop-config loop-config :zoom-x zoom-x}))


;; ============================================================
;; Canvas Drawing
;; ============================================================

(def ^:private loop-brace-color-active (Color/web "#00FFFF" 0.8))
(def ^:private loop-brace-color-inactive (Color/web "#00FFFF" 0.3))
(def ^:private loop-brace-height 8)
(def ^:private loop-brace-top (- ruler-height loop-brace-height 2))
(def ^:private loop-brace-bottom (- ruler-height 2))
(def ^:private ruler-font (Font. "Inter" 10))
(def ^:private clip-font (Font. "Inter" 10))
(def ^:private grid-line-beat-color (Color/web "#444444"))
(def ^:private clip-border-color (Color/web "#000000" 0.45))
(def ^:private clip-overlap-color (Color/web "#FFFFFF" 0.18))
(def ^:private selected-clip-outline Color/WHITE)
(def ^:private cursor-color (Color/web "#FFB300" 0.9))
(def ^:private insert-marker-color (Color/web "#5BA5F5"))
(def ^:private muted-alpha 0.35)

(defn- crisp
  "Pixel-snap a coordinate so 1 px lines don't blur across two pixels."
  ^double [x]
  (+ (Math/floor (double x)) 0.5))

(defn- draw-loop-brace!
  "Draw the loop brace in the ruler area."
  [^GraphicsContext gc zoom-x loop-config]
  (when loop-config
    (let [{:keys [enabled? start duration]} loop-config
          x (* start zoom-x)
          w (* duration zoom-x)
          y loop-brace-top
          color (if enabled? loop-brace-color-active loop-brace-color-inactive)]
      (.setFill gc color)
      ;; Draw a top bar and small side ticks to look like a brace [...]
      (.fillRect gc x y w 4) ;; top bar
      (.fillRect gc x y 4 loop-brace-height) ;; left tick
      (.fillRect gc (- (+ x w) 4) y 4 loop-brace-height))))

(defn- grid-subdivision [zoom-x]
  (cond
    (>= zoom-x 120) 0.0625
    (>= zoom-x 60)  0.125
    (>= zoom-x 30)  0.25
    (>= zoom-x 15)  1.0
    :else           4.0))

(defn- draw-ruler!
  "Draw the beat ruler at the top."
  [^GraphicsContext gc width zoom-x loop-config]
  (.setFill gc ruler-bg-color)
  (.fillRect gc 0 0 width ruler-height)
  ;; Grid lines and beat labels
  (let [end-beat (/ width zoom-x)
        subdivisions (grid-subdivision zoom-x)]
    (.setFont gc ruler-font)
    (.setTextAlign gc TextAlignment/CENTER)
    (loop [beat 0.0]
      (when (<= beat end-beat)
        (let [x (crisp (* beat zoom-x))
              rounded (Math/round (double beat))
              int-beat? (< (Math/abs (- (double beat) (double rounded))) 1e-4)
              measure? (and int-beat? (zero? (mod rounded 4)))]
          (when int-beat?
            (.setFill gc ruler-text-color)
            (.fillText gc (str (int rounded)) x (- ruler-height 6)))
          ;; Tick marks
          (.setStroke gc (cond
                           measure?  grid-line-major-color
                           int-beat? ruler-text-color
                           :else     grid-line-color))
          (.setLineWidth gc 1.0)
          (let [tick-h (cond
                         measure?  8
                         int-beat? 6
                         :else     3)]
            (.strokeLine gc x ruler-height x (- ruler-height tick-h))))
        (recur (+ beat subdivisions))))
    (draw-loop-brace! gc zoom-x loop-config)))


(defn- draw-grid!
  "Draw vertical grid lines across all tracks."
  [^GraphicsContext gc width height zoom-x]
  (let [end-beat (/ width zoom-x)
        subdivisions (grid-subdivision zoom-x)]
    (.setLineWidth gc 1.0)
    (loop [beat 0.0]
      (when (<= beat end-beat)
        (let [x (crisp (* beat zoom-x))
              rounded (Math/round (double beat))
              int-beat? (< (Math/abs (- (double beat) (double rounded))) 1e-4)
              measure? (and int-beat? (zero? (mod rounded 4)))]
          (.setStroke gc (cond
                           measure?  grid-line-major-color
                           int-beat? grid-line-beat-color
                           :else     grid-line-color))
          (.strokeLine gc x ruler-height x height))
        (recur (+ beat subdivisions))))))


(def ^:private group-bg-color (Color/web "#2A2A2A"))
(def ^:private track-bg-selected (Color/web "#282E38"))

(defn- draw-track-backgrounds!
  "Draw alternating track lane backgrounds.
   Folder rows get a distinct darker background. The lane selected in the
   sidebar (track or folder) is tinted; the lane new content goes to gets a
   thin marker on its left edge."
  [^GraphicsContext gc width tracks selected-track-id insert-track-id]
  (doseq [[idx track] (map-indexed vector tracks)]
    (let [y (track-y idx)
          selected? (and selected-track-id (= (:id track) selected-track-id))]
      (.setFill gc (cond
                     selected?                track-bg-selected
                     (= :group (:type track)) group-bg-color
                     (even? idx)              track-bg-even
                     :else                    track-bg-odd))
      (.fillRect gc 0 y width track-height)
      (when (and insert-track-id (= (:id track) insert-track-id))
        (.setFill gc insert-marker-color)
        (.fillRect gc 0 y 3 track-height)))))


(defn- clip-label
  "Clip text: its own name, else the preset's display name."
  [item]
  (or (:name item)
      (:name (presets/presets-by-id (:preset-id item)))
      (some-> (:preset-id item) name)
      (some-> (:effect-id item) name)
      ""))

(defn- clip-rect
  "[x w] of a clip on the canvas."
  [item zoom-x]
  [(* (double (or (tl/clip-start item) 0.0)) zoom-x)
   (max min-clip-width (* (double (or (tl/clip-length item) 0.0)) zoom-x))])

(defn- draw-clip!
  "Draw a single clip (item rectangle) on the canvas.
   Uses the track's resolved color as the clip fill; disabled clips and clips
   on muted tracks are drawn faded."
  [^GraphicsContext gc track-idx item zoom-x selection ^Color color dimmed?]
  (let [[x w] (clip-rect item zoom-x)
        y (+ (track-y track-idx) 2)
        h (- track-height 4)
        selected? (contains? selection (:id item))
        base (or color clip-color-default)
        body-color (cond-> (if selected? (.brighter base) base)
                     dimmed? (.deriveColor 0 1 1 muted-alpha))]
    ;; Clip body
    (.setFill gc body-color)
    (.fillRect gc x y w h)
    (.setStroke gc clip-border-color)
    (.setLineWidth gc 1.0)
    (.strokeRect gc (crisp x) (crisp y) (dec (Math/floor w)) (dec h))
    ;; Label, clipped to the clip
    (when (> w 30)
      (.save gc)
      (.beginPath gc)
      (.rect gc x y w h)
      (.clip gc)
      (.setFill gc (if dimmed? (Color/web "#FFFFFF" 0.5) Color/WHITE))
      (.setFont gc clip-font)
      (.setTextAlign gc TextAlignment/LEFT)
      (.fillText gc (clip-label item) (+ x 6) (+ y 14))
      (.restore gc))
    ;; Selection outline
    (when selected?
      (.setStroke gc selected-clip-outline)
      (.setLineWidth gc 1.5)
      (.strokeRect gc x y w h))))

(defn- draw-overlaps!
  "Highlight spans where clips on the same row overlap (they all play)."
  [^GraphicsContext gc track-idx items zoom-x]
  (let [spans (->> items
                   (keep (fn [i] (when-let [e (tl/clip-end i)] [(double (tl/clip-start i)) e])))
                   (sort-by first)
                   vec)
        y (+ (track-y track-idx) 2)
        h (- track-height 4)]
    (.setFill gc clip-overlap-color)
    (doseq [i (range (count spans))
            j (range (inc i) (count spans))
            :let [[s1 e1] (spans i) [s2 e2] (spans j)
                  s (max s1 s2) e (min e1 e2)]
            :when (< s e)]
      (.fillRect gc (* s zoom-x) y (* (- e s) zoom-x) h))))

(defn- clip-draw-order
  "Clips in the order they're drawn: by start, selected clips last (on top).
   hit-test walks the reverse of this."
  [items selection]
  (let [{sel true other false} (group-by #(contains? selection (:id %))
                                         (sort-by #(or (tl/clip-start %) 0.0) items))]
    (into (vec other) sel)))


(defn- draw-playhead!
  "Draw the playhead vertical line."
  [^GraphicsContext gc width height zoom-x beats-elapsed]
  (let [x (* beats-elapsed zoom-x)]
    (when (and (>= x 0) (<= x width))
      (.setStroke gc playhead-color)
      (.setLineWidth gc 2.0)
      (.strokeLine gc x 0 x height))))

(defn- draw-cursor!
  "Draw the edit cursor (where new content is placed) as a dashed amber line."
  [^GraphicsContext gc width height zoom-x cursor-beat]
  (let [x (crisp (* cursor-beat zoom-x))]
    (when (and (>= x 0) (<= x width))
      (.save gc)
      (.setStroke gc cursor-color)
      (.setLineWidth gc 1.0)
      (.setLineDashes gc (double-array [4.0 3.0]))
      (.strokeLine gc x 0 x height)
      (.restore gc))))


(defn- render-timeline!
  "Main canvas render function.
   Called by interactive-canvas with current value and drag-info."
  [^Canvas canvas value _]
  (let [{:keys [tracks zoom-x selection muted-track-ids
                beats-elapsed cursor-beat zone-groups loop-config
                selected-track-id insert-track-id]} value
        gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas)
        height (.getHeight canvas)]
    ;; Clear
    (.setFill gc bg-color)
    (.fillRect gc 0 0 width height)
    ;; Draw layers
    (draw-track-backgrounds! gc width tracks selected-track-id insert-track-id)
    (draw-grid! gc width height zoom-x)
    (draw-ruler! gc width zoom-x loop-config)
    ;; Draw clips — each track row can have multiple items
    (doseq [[idx {:keys [items track zone-group-id]}] (map-indexed vector tracks)]
      (let [color (if track
                    (track-color track zone-groups)
                    (when zone-group-id
                      (zone-group-color zone-groups zone-group-id)))]
        (doseq [item (clip-draw-order items selection)]
          (draw-clip! gc idx item zoom-x selection color
                      (or (false? (:enabled? item))
                          (contains? muted-track-ids (:track-id item)))))
        (draw-overlaps! gc idx items zoom-x)))
    ;; Cursor, then the playhead (only while the cue plays) on top
    (draw-cursor! gc width height zoom-x (or cursor-beat 0.0))
    (when beats-elapsed
      (draw-playhead! gc width height zoom-x beats-elapsed))))


;; ============================================================
;; Hit Testing
;; ============================================================


(defn- hit-test
  "Find what's under the mouse: the loop brace (only inside its drawn band)
   or the topmost clip (reverse draw order: selected first, then latest start).
   Returns {:type :loop-brace :edge e} or {:type :clip :track-idx int :item map :edge e}
   with e one of :left/:right/:center, or nil."
  [mx my tracks zoom-x loop-config selection]
  (if (and loop-config (<= loop-brace-top my loop-brace-bottom))
    (let [{:keys [start duration]} loop-config
          x (* start zoom-x)
          w (* duration zoom-x)]
      (when (and (>= mx x) (<= mx (+ x w)))
        {:type :loop-brace
         :edge (cond
                 (< mx (+ x edge-grab-px)) :left
                 (> mx (- (+ x w) edge-grab-px)) :right
                 :else :center)}))
    (when-let [{:keys [track-idx track]} (tl/track-at-y {:y my
                                                         :ruler-height ruler-height
                                                         :track-height track-height
                                                         :track-rows tracks})]
      (some (fn [item]
              (let [[clip-x clip-w] (clip-rect item zoom-x)
                    clip-end (+ clip-x clip-w)]
                (when (and (>= mx clip-x) (<= mx clip-end))
                  {:type :clip
                   :track-idx track-idx
                   :item item
                   :edge (cond
                           (< mx (+ clip-x edge-grab-px)) :left
                           (> mx (- clip-end edge-grab-px)) :right
                           :else :center)})))
            (rseq (clip-draw-order (:items track) (or selection #{})))))))


;; ============================================================
;; Interaction Handlers
;; ============================================================


(defn- on-press
  "Handle mouse press. Select item or begin drag.
   Clicking empty space clears selection, sets cursor beat, and selects clicked track."
  [mx my button value _]
  (let [{:keys [tracks zoom-x col row loop-config selection]} value
        hit (hit-test mx my tracks zoom-x loop-config selection)]
    (when (= button MouseButton/PRIMARY)
      (if hit
        (if (= (:type hit) :loop-brace)
          (let [{:keys [edge]} hit
                {:keys [start duration]} loop-config]
            {:drag-start true
             :drag-id :loop-brace
             :drag-updates {:edge edge
                            :start-mx mx
                            :original-start start
                            :original-duration duration
                            :col col
                            :row row}})
          (let [{:keys [item edge track-idx]} hit
                item-id (:id item)
                clicked-track (when (and (>= track-idx 0) (< track-idx (count tracks)))
                                (nth tracks track-idx nil))]
            {:drag-start true
             :drag-id item-id
             :dispatch (cond-> [{:event/type :timeline/select-items
                                 :ids [item-id]
                                 :mode :replace}]
                         (tl/droppable-track? clicked-track)
                         (conj {:event/type :timeline/select-track
                                :track-id (:id clicked-track)}))
             :drag-updates {:edge edge
                            :start-mx mx
                            :original-start (:timeline/start item 0.0)
                            :original-duration (:timeline/duration item 4.0)
                            :original-track-idx track-idx
                            :col col
                            :row row}}))
        ;; Clicked empty space - clear selection, move cursor, select track if clicked
        (let [clicked-beat (max 0.0 (/ mx zoom-x))
              clicked-track (:track (tl/track-at-y {:y my
                                                    :ruler-height ruler-height
                                                    :track-height track-height
                                                    :track-rows tracks}))
              events (cond-> [{:event/type :timeline/clear-selection}
                              {:event/type :timeline/set-cursor-beat :beat clicked-beat}]
                       (tl/droppable-track? clicked-track)
                       (conj {:event/type :timeline/select-track :track-id (:id clicked-track)}))]
          {:dispatch events})))))


(defn- on-drag
  "Handle mouse drag. Move or resize the currently-dragged clip or loop brace.
   Dispatches cross-track transfers when dragged vertically to new rows."
  [mx my value drag-info]
  (let [{:keys [zoom-x tracks col row]} value
        {:keys [edge start-mx original-start original-duration original-track-idx drag-id]} drag-info
        delta-px (- mx start-mx)
        delta-beats (/ delta-px zoom-x)]
    (if (= drag-id :loop-brace)
      {:dispatch [(case edge
                    :right {:event/type :timeline/update-loop-timing
                            :col col :row row :edge :right
                            :end (+ original-start original-duration delta-beats)}
                    {:event/type :timeline/update-loop-timing
                     :col col :row row :edge edge
                     :start (+ original-start delta-beats)})]}
      (let [{:keys [track-idx track]} (tl/track-at-y {:y my
                                                      :ruler-height ruler-height
                                                      :track-height track-height
                                                      :track-rows tracks})
            track-changed? (and (not= edge :left)
                                (not= edge :right)
                                (some? track-idx)
                                (not= track-idx original-track-idx))
            destination-track (when track-changed? track)
            valid-destination? (tl/droppable-track? destination-track)
            base-events (case edge
                          :right
                          [{:event/type :timeline/update-item-timing
                            :col col :row row :id drag-id :edge :right
                            :end (+ original-start original-duration delta-beats)}]
                          (:center :left)
                          [{:event/type :timeline/update-item-timing
                            :col col :row row :id drag-id :edge edge
                            :start (+ original-start delta-beats)}]
                          [])

            events (if valid-destination?
                     (into base-events
                           [{:event/type :timeline/move-item-to-track
                             :col col :row row
                             :item-id drag-id
                             :track-id (:id destination-track)}
                            {:event/type :timeline/select-track
                             :track-id (:id destination-track)}])
                     base-events)]

        (when (seq events)
          {:dispatch events
           ;; Update drag state if track changed so we don't dispatch continuously
           :drag-updates (when valid-destination?
                           {:original-track-idx track-idx})})))))


(defn- on-hover
  "Handle mouse hover. Update cursor based on edge proximity."
  [mx my value _]
  (let [{:keys [tracks zoom-x loop-config selection]} value
        hit (hit-test mx my tracks zoom-x loop-config selection)]
    (if hit
      {:hover-id (if (= (:type hit) :loop-brace) :loop-brace (:id (:item hit)))
       :cursor (case (:edge hit)
                 :left "w-resize"
                 :right "e-resize"
                 :center (if (= (:type hit) :loop-brace) "hand" "move"))}
      {:hover-id nil
       :cursor "crosshair"})))


(defn- on-key
  "Handle keyboard shortcuts."
  [^KeyCode key-code shift? value _]
  (let [{:keys [col row]} value]
    (case (.getName key-code)
      "Delete" {:dispatch {:event/type :timeline/delete-selection
                           :col col :row row}
                :consumed? true}
      "Left"   {:dispatch {:event/type :timeline/nudge-selection
                           :col col :row row
                           :delta-beats (if shift? -0.25 -1.0)}
                :consumed? true}
      "Right"  {:dispatch {:event/type :timeline/nudge-selection
                           :col col :row row
                           :delta-beats (if shift? 0.25 1.0)}
                :consumed? true}
      nil)))


(def ^:private scroll-filter
  "Event filter attached to the timeline ScrollPane during the capturing phase.
   Intercepts mouse wheel events before ScrollPaneSkin consumes them:
   - Ctrl + Scroll: zooms in/out centered at the mouse cursor beat position.
   - Alt + Scroll: scrolls the timeline horizontally.
   Default scrolling (vertical tracks, or Shift+scroll horizontal) passes through."
  (reify EventHandler
    (handle [_ event]
      (try
        (let [e ^ScrollEvent event
              alt? (.isAltDown e)
              control? (or (.isControlDown e) (.isShortcutDown e))]
          (when (or alt? control?)
            (let [sp ^ScrollPane (.getSource e)
                  content (.getContent sp)]
              (cond
                control?
                (let [{:keys [col row zoom-x]} (.getUserData sp)
                      local (when content (.sceneToLocal content (.getSceneX e) (.getSceneY e)))
                      canvas-x (if local (.getX local) (.getX e))
                      vp-point (.sceneToLocal sp (.getSceneX e) (.getSceneY e))
                      insets (.getInsets sp)
                      insets-left (if insets (.getLeft insets) 0.0)
                      viewport-x (- (if vp-point (.getX vp-point) (.getX e)) insets-left)
                      cur-rendered-zoom (double (or zoom-x default-zoom))
                      focal-beat (/ (double canvas-x) cur-rendered-zoom)
                      delta-y (.getDeltaY e)]
                  (events/dispatch! {:event/type :timeline/zoom-at-cursor
                                     :focal-beat focal-beat
                                     :viewport-x viewport-x
                                     :delta-y delta-y
                                     :col col
                                     :row row})
                  (.consume e))

                alt?
                (let [delta-x (.getDeltaX e)
                      delta-y (.getDeltaY e)
                      raw-delta (if (and (number? delta-x) (not (zero? delta-x)))
                                  (double delta-x)
                                  (double (or delta-y 0.0)))
                      delta-px (- raw-delta)
                      viewport-bounds (.getViewportBounds sp)
                      viewport-width (if viewport-bounds (.getWidth viewport-bounds) 0.0)
                      content-width (if content (.getWidth (.getLayoutBounds content)) 0.0)
                      cur-h (.getHvalue sp)
                      new-h (tl/calculate-horizontal-scroll
                             {:current-hvalue cur-h
                              :delta-px delta-px
                              :content-width content-width
                              :viewport-width viewport-width})]
                  (.setHvalue sp new-h)
                  (events/dispatch! {:event/type :timeline/set-hvalue
                                     :hvalue new-h})
                  (.consume e))))))
        (catch Throwable t
          (log/warn t "Timeline scroll filter failed"))))))


;; ============================================================
;; Sub-Components
;; ============================================================


(defn timeline-toolbar
  "Toolbar component with zoom slider and snap controls."
  [{:keys [zoom-x snap-enabled? snap-value col row loop-config add-panel-open?]}]
  {:fx/type :h-box
   :spacing 12
   :padding {:top 4 :bottom 4 :left 8 :right 8}
   :alignment :center-left
   :style "-fx-background-color: #1A1A1A;"
   :children
   [;; Add buttons
    {:fx/type :button
     :id "timeline-add-track"
     :text "Add Track"
     :style-class "button-primary"
     :on-action {:event/type :timeline/add-track
                 :col col :row row}}
    {:fx/type :button
     :id "timeline-add-folder"
     :text "Add Folder"
     :style-class "button-secondary"
     :on-action {:event/type :timeline/add-folder
                 :col col :row row}}
    {:fx/type :toggle-button
     :id "timeline-add-content"
     :text "Add Content"
     :style-class "button-secondary"
     :selected (boolean add-panel-open?)
     :on-action {:event/type :timeline/toggle-add-panel}}

    ;; Spacer
    {:fx/type :region
     :h-box/hgrow :always}

    ;; Loop toggle
    {:fx/type :toggle-button
     :id "timeline-loop-toggle"
     :text "Loop"
     :selected (boolean (:enabled? loop-config false))
     :on-action {:event/type :timeline/toggle-loop
                 :col col :row row}}

    ;; Snap toggle
    {:fx/type :check-box
     :id "timeline-snap-toggle"
     :text "Snap"
     :selected (boolean snap-enabled?)
     :on-selected-changed {:event/type :timeline/set-snap}}
    ;; Snap value selector
    {:fx/type :combo-box
     :id "timeline-snap-value"
     :value (str snap-value)
     :items ["0.0625" "0.125" "0.25" "0.5" "1.0" "2.0" "4.0"]
     :on-value-changed (fn [v]
                         (when v
                           (events/dispatch!
                            {:event/type :timeline/set-snap
                             :value (Double/parseDouble v)})))
     :pref-width 80}
    ;; Zoom label
    {:fx/type :label
     :text "Zoom"
     :style "-fx-text-fill: #999999;"}
    ;; Zoom slider
    {:fx/type :slider
     :id "timeline-zoom-slider"
     :min 10 :max 200
     :value (or zoom-x default-zoom)
     :pref-width 140
     :on-value-changed {:event/type :timeline/set-zoom}}]})


(defn track-label-renderer
  "Custom list-editor label renderer for tracks.
   Displays the track name and its assigned or inherited zone group."
  [zone-groups track-defs item]
  (let [direct-zone (:zone-group-id item)
        effective-zone (ze/resolve-track-effective-zone track-defs (:id item) :all)
        zone-name (or (get-in zone-groups [effective-zone :name])
                      (when effective-zone (name effective-zone))
                      "All")]
    (cond
      direct-zone
      (str (:name item "Track") " [" zone-name "]")

      (not= effective-zone :all)
      (str (:name item "Track") " [" zone-name " (Folder)]")

      :else
      (str (:name item "Track") " [" zone-name "]"))))

(defn track-group-label-renderer
  "Custom list-editor label renderer for track folders.
   Displays the folder name and its assigned zone group."
  [zone-groups folder]
  (let [direct-zone (:zone-group-id folder)
        zone-name (when direct-zone
                    (or (get-in zone-groups [direct-zone :name])
                        (name direct-zone)))]
    (if zone-name
      (str (:name folder "Folder") " [" zone-name "]")
      (str (:name folder "Folder") " [All]"))))

(defn- track-context-menu
  "Builds a context menu for a track or folder with zone routing options."
  [col row zone-groups track-or-folder]
  (let [track-id (:id track-or-folder)
        current-zone (:zone-group-id track-or-folder)
        is-folder? (or (= :group (:type track-or-folder)) (contains? track-or-folder :items))
        zone-items (mapv
                    (fn [zg]
                      (let [zid (:id zg)
                            active? (= zid current-zone)]
                        {:fx/type :check-menu-item
                         :text (str (if active? "✓ " "   ") (:name zg (name zid)))
                         :selected active?
                         :on-action {:event/type :timeline/set-track-zone
                                     :col col
                                     :row row
                                     :track-id track-id
                                     :zone-group-id zid}}))
                    (vals zone-groups))
        inherit-item (when-not is-folder?
                       [{:fx/type :check-menu-item
                         :text (str (if (nil? current-zone) "✓ " "   ") "Inherit from Folder")
                         :selected (nil? current-zone)
                         :on-action {:event/type :timeline/set-track-zone
                                     :col col
                                     :row row
                                     :track-id track-id
                                     :zone-group-id nil}}
                        {:fx/type :separator-menu-item}])
        all-zone-items (into (vec inherit-item) zone-items)
        muted? (true? (:muted? track-or-folder))]
    {:fx/type :context-menu
     :items [{:fx/type :menu-item
              :text (if muted? "Unmute" "Mute")
              :on-action {:event/type :timeline/set-track-muted
                          :col col
                          :row row
                          :track-id track-id
                          :muted? (not muted?)}}
             {:fx/type :menu
              :text "Assign Zone"
              :items all-zone-items}]}))

(defn timeline-sidebar
  "Left pane: track list managed by list-editor."
  [{:keys [context track-defs col row zone-groups items-path]}]
  {:fx/type :v-box
   :pref-width header-width
   :min-width header-width
   :style "-fx-background-color: #1A1A1A;"
   :children
   [;; Ruler spacer
    {:fx/type :region
     :pref-height ruler-height
     :style "-fx-background-color: #1A1A1A;"}
    {:fx/type list/list-editor
     :v-box/vgrow :always
     :fx/context context
     :items (or track-defs [])
     :component-id :timeline-tracks
     :get-item-label (partial track-label-renderer zone-groups (or track-defs []))
     :get-group-label (partial track-group-label-renderer zone-groups)
     :get-context-menu (partial track-context-menu col row zone-groups)
     :get-group-context-menu (partial track-context-menu col row zone-groups)
     :items-path items-path
     :on-change-event :timeline/update-tracks ;; Requires custom handler or alias
     :on-change-params {:col col :row row :clipboard-type :timeline-tracks}
     :show-enable-toggle? false ;; Mute is in the context menu; :enabled? isn't used for tracks
     :header-label "TRACKS"
     :empty-text "No tracks. Add one to start."
     :allow-groups? true
     :scrollable? false ;; Crucial: Make it non-scrollable here so the wrapper handles scrolling
     :compact? true}]})


(defn- timeline-inspector-panel
  "Inspector panel for the selected clip or selected track in the timeline."
  [{:keys [col row items track-defs zone-groups selected-item-id selected-track-id
           destination-zone-id]}]
  (let [;; Check for selected clip
        selected-clip (when selected-item-id
                        (when-let [path (chains/find-path-by-id items selected-item-id)]
                          (get-in (vec items) path)))
        ;; Check for selected track
        selected-track (when (and (not selected-clip) selected-track-id)
                         (tl/find-track-by-id track-defs selected-track-id))]
    (cond
      ;; 1. Clip Inspector
      selected-clip
      (let [assigned-track (tl/find-track-by-id track-defs (:track-id selected-clip))
            track-zone (when assigned-track
                         (ze/resolve-track-effective-zone track-defs (:id assigned-track) :all))
            track-zone-name (or (get-in zone-groups [track-zone :name])
                                (when track-zone (name track-zone))
                                "All")
            clip-zone (:zone-group-id selected-clip)
            preset-def (presets/presets-by-id (:preset-id selected-clip))
            leaf-tracks (filterv tl/leaf-track? (chains/flatten-chain track-defs))
            ;; Zone combo options
            zone-options (into [{:id :inherit :name (str "Inherit from Track (" track-zone-name ")")}]
                               (mapv (fn [zg] {:id (:id zg) :name (:name zg (name (:id zg)))}) (vals zone-groups)))
            current-zone-val (or (u/seek #(= (:id %) (or clip-zone :inherit)) zone-options)
                                 (first zone-options))
            current-track-val (or (u/seek #(= (:id %) (:id assigned-track)) leaf-tracks)
                                  assigned-track)]
        {:fx/type :v-box
         :pref-width 280
         :min-width 240
         :style "-fx-background-color: #1E1E1E; -fx-border-color: #333333; -fx-border-width: 0 0 0 1;"
         :children
         [{:fx/type :label
           :text "CLIP PROPERTIES"
           :padding {:top 4 :bottom 2 :left 8}
           :style "-fx-text-fill: #777777; -fx-font-size: 10; -fx-font-weight: bold;"}
          {:fx/type :v-box
           :padding 8
           :spacing 8
           :children
           [{:fx/type :label
             :text (str "Preset: " (or (:name selected-clip) (:name preset-def "Preset")))
             :style "-fx-text-fill: #DDDDDD; -fx-font-weight: bold;"}

            ;; Track assignment
            {:fx/type :v-box
             :spacing 2
             :children
             [{:fx/type :label
               :text "Assigned Track"
               :style "-fx-text-fill: #888888; -fx-font-size: 10;"}
              {:fx/type :combo-box
               :items leaf-tracks
               :value current-track-val
               :pref-width 260
               :button-cell (fn [t] {:text (or (:name t) "Unassigned")})
               :cell-factory {:fx/cell-type :list-cell
                              :describe (fn [t] {:text (or (:name t) "Unassigned")})}
               :on-value-changed (fn [t]
                                   (when (and t (not= (:id t) (:track-id selected-clip)))
                                     (events/dispatch!
                                      {:event/type :timeline/move-item-to-track
                                       :col col
                                       :row row
                                       :item-id selected-item-id
                                       :track-id (:id t)})))}]}

            ;; Zone Override
            {:fx/type :v-box
             :spacing 2
             :children
             [{:fx/type :label
               :text "Zone Destination"
               :style "-fx-text-fill: #888888; -fx-font-size: 10;"}
              {:fx/type :combo-box
               :items zone-options
               :value current-zone-val
               :pref-width 260
               :button-cell (fn [opt] {:text (:name opt)})
               :cell-factory {:fx/cell-type :list-cell
                              :describe (fn [opt] {:text (:name opt)})}
               :on-value-changed (fn [opt]
                                   (when opt
                                     (let [new-zone (when-not (= (:id opt) :inherit) (:id opt))]
                                       (when (not= new-zone clip-zone)
                                         (events/dispatch!
                                          {:event/type :timeline/set-clip-zone
                                           :col col
                                           :row row
                                           :item-id selected-item-id
                                           :zone-group-id new-zone})))))}]}

            ;; Attached effects count
            (let [effect-count (count (:effects selected-clip []))]
              {:fx/type :label
               :text (str "Attached Effects: " effect-count)
               :style "-fx-text-fill: #666666; -fx-font-size: 10;"})]}]})

      ;; 2. Track Inspector
      selected-track
      (let [is-folder? (or (= :group (:type selected-track)) (contains? selected-track :items))
            track-zone (:zone-group-id selected-track)
            parent-folder (tl/parent-track track-defs (:id selected-track))
            parent-zone (when parent-folder
                          (ze/resolve-track-effective-zone track-defs (:id parent-folder) :all))
            parent-zone-name (when parent-zone
                               (or (get-in zone-groups [parent-zone :name]) (name parent-zone)))
            default-zone (or destination-zone-id :all)
            default-zone-name (or (get-in zone-groups [default-zone :name]) (name default-zone))
            inherit-opt [{:id :inherit
                          :name (if parent-folder
                                  (str "Inherit from Folder (" (or parent-zone-name "All") ")")
                                  (str "Cue Default Zone (" default-zone-name ")"))}]
            zone-options (into (vec inherit-opt)
                               (mapv (fn [zg] {:id (:id zg) :name (:name zg (name (:id zg)))}) (vals zone-groups)))
            current-zone-val (or (u/seek #(= (:id %) (or track-zone :inherit)) zone-options)
                                 (first zone-options))]
        {:fx/type :v-box
         :pref-width 280
         :min-width 240
         :style "-fx-background-color: #1E1E1E; -fx-border-color: #333333; -fx-border-width: 0 0 0 1;"
         :children
         [{:fx/type :label
           :id "timeline-inspector-title"
           :text (if is-folder? "FOLDER PROPERTIES" "TRACK PROPERTIES")
           :padding {:top 4 :bottom 2 :left 8}
           :style "-fx-text-fill: #777777; -fx-font-size: 10; -fx-font-weight: bold;"}
          {:fx/type :v-box
           :padding 8
           :spacing 8
           :children
           [{:fx/type :label
             :text (:name selected-track (if is-folder? "Folder" "Track"))
             :style "-fx-text-fill: #DDDDDD; -fx-font-weight: bold;"}

            {:fx/type :v-box
             :spacing 2
             :children
             [{:fx/type :label
               :text "Assigned Zone"
               :style "-fx-text-fill: #888888; -fx-font-size: 10;"}
              {:fx/type :combo-box
               :items zone-options
               :value current-zone-val
               :pref-width 260
               :button-cell (fn [opt] {:text (:name opt)})
               :cell-factory {:fx/cell-type :list-cell
                              :describe (fn [opt] {:text (:name opt)})}
               :on-value-changed (fn [opt]
                                   (when opt
                                     (let [new-zone (when-not (= (:id opt) :inherit) (:id opt))]
                                       (when (not= new-zone track-zone)
                                         (events/dispatch!
                                          {:event/type :timeline/set-track-zone
                                           :col col
                                           :row row
                                           :track-id (:id selected-track)
                                           :zone-group-id new-zone})))))}]}]}]})

      ;; 3. No selection placeholder
      :else
      {:fx/type :v-box
       :pref-width 240
       :min-width 200
       :style "-fx-background-color: #1E1E1E; -fx-border-color: #333333; -fx-border-width: 0 0 0 1;"
       :alignment :center
       :children
       [{:fx/type :label
         :text "Select a track or clip\nto inspect routing"
         :style "-fx-text-fill: #555555; -fx-font-size: 10; -fx-text-alignment: center;"}]})))

(defn timeline-add-panel
  "Collapsible bottom panel for adding presets and effects to the timeline,
   and inspecting clip and track properties."
  [{:keys [col row add-panel-open? add-panel-preset-tab add-panel-effect-tab
           selected-item-id inspector-track-id insert-track-id cursor-beat beats-elapsed
           items track-defs zone-groups destination-zone-id]}]
  (when add-panel-open?
    {:fx/type :v-box
     :style "-fx-background-color: #1A1A1A; -fx-border-color: #333333; -fx-border-width: 1 0 0 0;"
     :pref-height 180
     :children
     [{:fx/type :h-box
       :alignment :center-left
       :padding {:top 4 :bottom 4 :left 8 :right 8}
       :style "-fx-background-color: #222222;"
       :children [{:fx/type :label
                   :text "ADD CONTENT & PROPERTIES"
                   :style "-fx-text-fill: #999999; -fx-font-size: 11; -fx-font-weight: bold;"}
                  {:fx/type :region :h-box/hgrow :always}
                  {:fx/type :label
                   :text (if selected-item-id
                           "Clip selected — effect bank & properties enabled"
                           "Select a clip to add effects or inspect routing")
                   :style "-fx-text-fill: #666666; -fx-font-size: 10;"}]}
      {:fx/type :h-box
       :v-box/vgrow :always
       :spacing 1
       :children
       [;; Preset bank (always available)
        {:fx/type :v-box
         :h-box/hgrow :always
         :style "-fx-background-color: #1E1E1E;"
         :children [{:fx/type :label
                     :text "PRESETS"
                     :padding {:top 4 :bottom 2 :left 8}
                     :style "-fx-text-fill: #777777; -fx-font-size: 10; -fx-font-weight: bold;"}
                    {:fx/type preset-bank/preset-bank
                     :cell [col row]
                     :active-tab (or add-panel-preset-tab :geometric)
                     :on-tab-change {:event/type :timeline/set-add-panel-tab
                                     :panel :preset}
                     ;; Override event template to use timeline-specific handler
                     :item-event-template {:event/type :timeline/add-preset-to-track
                                           :col col
                                           :row row
                                           :track-id insert-track-id
                                           :start (or cursor-beat beats-elapsed 0.0)}}]}
        ;; Effect bank (only when a clip is selected)
        {:fx/type :v-box
         :h-box/hgrow :always
         :style "-fx-background-color: #1E1E1E;"
         :children
         (if selected-item-id
           [{:fx/type :label
             :text "EFFECTS"
             :padding {:top 4 :bottom 2 :left 8}
             :style "-fx-text-fill: #777777; -fx-font-size: 10; -fx-font-weight: bold;"}
            {:fx/type effect-bank/effect-bank
             :active-tab (or add-panel-effect-tab :shape)
             :on-tab-change {:event/type :timeline/set-add-panel-tab
                             :panel :effect}
             :item-event-template {:event/type :timeline/add-effect-to-item
                                   :col col
                                   :row row
                                   :target-item-id selected-item-id}
             :include-zone? true}]
           [{:fx/type :v-box
             :v-box/vgrow :always
             :alignment :center
             :children [{:fx/type :label
                         :text "Select a clip to add effects"
                         :style "-fx-text-fill: #555555; -fx-font-size: 11;"}]}])}
        ;; Inspector panel (Clip / Track Properties & Zone Routing)
        {:fx/type timeline-inspector-panel
         :col col
         :row row
         :items items
         :track-defs track-defs
         :zone-groups zone-groups
         :selected-item-id selected-item-id
         :selected-track-id inspector-track-id
         :destination-zone-id destination-zone-id}]}]}))


(defn timeline-canvas
  "The interactive canvas that shows the timeline grid, clips, and playhead."
  [{:keys [col row tracks items zoom-x selection selected-track-id insert-track-id
           muted-track-ids beats-elapsed cursor-beat loop-config zone-groups]}]
  (let [canvas-height (total-canvas-height (count tracks))
        canvas-width (calculate-canvas-width items loop-config (or zoom-x default-zoom))]
    {:fx/type ci/interactive-canvas
     :id "timeline-canvas"
     :style-class ["timeline-canvas"]
     :width canvas-width
     :height canvas-height
     :cursor "crosshair"
     :value {:tracks tracks
             :zoom-x (or zoom-x default-zoom)
             :selection (or selection #{})
             :selected-track-id selected-track-id
             :insert-track-id insert-track-id
             :muted-track-ids (or muted-track-ids #{})
             :beats-elapsed beats-elapsed
             :cursor-beat cursor-beat
             :loop-config loop-config
             :col col
             :row row
             :zone-groups zone-groups}
     :render! render-timeline!
     :on-press on-press
     :on-drag on-drag
     :on-hover on-hover
     :on-key on-key}))

;; ============================================================
;; Main Component
;; ============================================================


(defn timeline-editor
  "Main timeline editor component.
   
   Props:
   - :fx/context    — cljfx context (required for list-editor)
   - :col, :row     — Grid cell coordinate for the cue chain
   - :items         — Cue chain items vector
   - :track-defs    — Explicit Track definitions vector (from CueChain :tracks)
   - :zone-groups   — Map of zone-group-id -> group config
   - :destination-zone-id — The cue's default zone (what top-level tracks inherit)
   - :timeline-ui   — Map from [:ui :timeline] state
   - :beats-elapsed — Current beat position from active cue timing (nil if stopped)
   - :loop-config   — Map with {:enabled? :start :duration}"
  [{:keys [fx/context col row items track-defs zone-groups destination-zone-id
           timeline-ui beats-elapsed loop-config]}]
  (let [{:keys [zoom-x selection snap-enabled? snap-value cursor-beat
                add-panel-open? add-panel-preset-tab add-panel-effect-tab]
         :or {zoom-x default-zoom
              selection #{}
              snap-enabled? true
              snap-value 0.25}} timeline-ui
        tracks (build-tracks (or track-defs []) (or items []))
        muted-track-ids (tl/muted-track-ids track-defs)
        ;; Determine selected item ID for the effect bank
        selected-item-id (when (= 1 (count selection)) (first selection))
        ;; The track or folder selected in the sidebar (nil when none)...
        tracks-ui-state (fx/sub-ctx context subs/list-ui-state :timeline-tracks)
        sidebar-selected-track-id (let [id (first (:selected-ids tracks-ui-state))]
                                    (when (and id (tl/find-track-by-id track-defs id)) id))
        ;; ...and the leaf track new content goes to
        insert-track-id (tl/resolve-active-track
                         {:track-defs track-defs
                          :selected-track-id sidebar-selected-track-id})]
    (cond->
     {:fx/type :border-pane
      :style "-fx-background-color: #121212;"
      :top {:fx/type timeline-toolbar
            :zoom-x zoom-x
            :snap-enabled? snap-enabled?
            :snap-value snap-value
            :loop-config loop-config
            :add-panel-open? add-panel-open?
            :col col
            :row row}
      :center
      {:fx/type :split-pane
       :divider-positions [0.2]
       :items
       [{:fx/type fx/ext-on-instance-lifecycle
         :on-created (fn [^ScrollPane sp]
                       (events/dispatch! {:event/type :timeline/register-scroll-pane
                                          :pane :left :instance sp}))
         :on-deleted (fn [_]
                       (events/dispatch! {:event/type :timeline/register-scroll-pane
                                          :pane :left :instance nil}))
         :desc
         {:fx/type :scroll-pane
          :fit-to-width true
          :fit-to-height true
          :hbar-policy :never
          :vbar-policy :never
          :on-vvalue-changed {:event/type :timeline/sync-scroll :pane :left}
          :content {:fx/type timeline-sidebar
                    :context context
                    :track-defs track-defs
                    :zone-groups zone-groups
                    :col col
                    :row row
                    :items-path [:chains :cue-chains [col row] :tracks]}}}
        {:fx/type fx/ext-on-instance-lifecycle
         :on-created (fn [^ScrollPane sp]
                       (.addEventFilter sp ScrollEvent/SCROLL scroll-filter)
                       (.setUserData sp {:col col :row row :zoom-x zoom-x})
                       (events/dispatch! {:event/type :timeline/register-scroll-pane
                                          :pane :right :instance sp}))
         :on-deleted (fn [^ScrollPane sp]
                       (when sp
                         (.removeEventFilter sp ScrollEvent/SCROLL scroll-filter))
                       (events/dispatch! {:event/type :timeline/register-scroll-pane
                                          :pane :right :instance nil}))
         :desc
         {:fx/type :scroll-pane
          :user-data {:col col :row row :zoom-x zoom-x}
          :fit-to-height true
          :hbar-policy :always
          :vbar-policy :as-needed
          :on-vvalue-changed {:event/type :timeline/sync-scroll :pane :right}
          :on-hvalue-changed {:event/type :timeline/set-hvalue}
          :style "-fx-background-color: transparent; -fx-background: transparent;"
          :content
          {:fx/type timeline-canvas
           :col col
           :row row
           :tracks tracks
           :items items
           :zoom-x zoom-x
           :selection selection
           :selected-track-id sidebar-selected-track-id
           :insert-track-id insert-track-id
           :muted-track-ids muted-track-ids
           :beats-elapsed beats-elapsed
           :cursor-beat cursor-beat
           :loop-config loop-config
           :zone-groups zone-groups}}}]}}
      add-panel-open?
      (assoc :bottom {:fx/type timeline-add-panel
                      :col col
                      :row row
                      :add-panel-open? add-panel-open?
                      :add-panel-preset-tab add-panel-preset-tab
                      :add-panel-effect-tab add-panel-effect-tab
                      :selected-item-id selected-item-id
                      :inspector-track-id sidebar-selected-track-id
                      :insert-track-id insert-track-id
                      :cursor-beat cursor-beat
                      :beats-elapsed beats-elapsed
                      :items items
                      :track-defs track-defs
                      :zone-groups zone-groups
                      :destination-zone-id destination-zone-id}))))
