(ns laser-show.views.components.preview
  "Preview panel: a grid of cells, each drawing the show clock's newest tick
   on each screen refresh (clock-view), for one target or for everything. A
   group's cell shows what is sent to that group, as drawn; a laser's cell
   (:auto.laser/<id>) what that laser draws, its halves fitted, before its
   calibration (zones plan Z3). The cells' canvases are square and as big as
   the pane lets them be. Also the rig cards' thumbnails: the frame each
   laser's driver was passed in its last call that returned normally, after
   calibration and safety, which says nothing of what the device drew
   (output-thumbnail).

   Features:
   - Zone group filtering per cell (the label opens a zone picker)
   - Layout selector in the header

   LaserPoints are vectors [x y r g b] with NORMALIZED values: x, y in -1..1,
   r, g, b in 0..1."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.animation.show-time :as show-time]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.common.timing :as timing]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]
            [laser-show.subs :as subs]
            [laser-show.common.util :as u]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.target-control :as target-control]
            [laser-show.views.components.zone-chips :as zone-chips]
            [laser-show.views.components.measured :as measured])
  (:import [javafx.scene Node]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.control Labeled]
           [javafx.scene.layout Pane]
           [javafx.scene.paint Color]
           [javafx.scene.text Font]
           [javafx.util StringConverter]))


;; Frame Drawing
;;
;; One indexed pass over the frame for the lines and one for the dots, on the
;; tick's point vectors: no seqs or maps per point, and a new Color only when
;; the colour changes (review 01 M2). The cells redraw on every tick.


(def ^:private grid-color (Color/rgb 40 40 40))

(def ^:private message-color (Color/rgb 80 80 80))

(def ^:private message-font
  ;; A Font needs the FX toolkit, so it's made on first use
  (delay (Font. "System" 14.0)))

(defn- value
  "Point p's i-th value, or NaN if it isn't a number."
  ^double [p ^long i]
  (let [v (nth p i nil)]
    (if (number? v) (double v) Double/NaN)))

(defn- on-canvas? [^double x ^double y]
  (and (Double/isFinite x) (Double/isFinite y)))

(defn- lit?
  "Is a colour drawn: every channel finite and one of them lit? A point whose
   channels are all below 1e-6 is blanked (as t/blanked?)."
  [^double r ^double g ^double b]
  (and (Double/isFinite r) (Double/isFinite g) (Double/isFinite b)
       (or (>= r 1e-6) (>= g 1e-6) (>= b 1e-6))))

(defn- clamp01 ^double [^double v]
  (Math/max 0.0 (Math/min 1.0 v)))

(defn- draw-background
  [^GraphicsContext gc ^double width ^double height]
  (.setFill gc Color/BLACK)
  (.fillRect gc 0 0 width height)
  (.setStroke gc grid-color)
  (.setLineWidth gc 0.5)
  (.strokeLine gc 0 (/ height 2) width (/ height 2))
  (.strokeLine gc (/ width 2) 0 (/ width 2) height))

(defn- draw-lines!
  "Each segment between two drawn points, in the colour of the first, as the
   laser draws it."
  [^GraphicsContext gc points ^double w ^double h]
  (.setLineWidth gc 2.0)
  (let [n (count points)]
    (loop [i 0
           before? false            ; was the point before drawn?
           x0 0.0 y0 0.0            ; where it is on the canvas
           r0 0.0 g0 0.0 b0 0.0     ; its colour
           sr -1.0 sg -1.0 sb -1.0] ; the stroke colour set last
      (when (< i n)
        (let [p (nth points i)
              x (value p 0) y (value p 1) r (value p 2) g (value p 3) b (value p 4)
              drawn? (and (on-canvas? x y) (lit? r g b))
              cx (* (+ x 1.0) 0.5 w)
              cy (* (- 1.0 y) 0.5 h)]
          (if (and drawn? before?)
            (if (and (== r0 sr) (== g0 sg) (== b0 sb))
              (do (.strokeLine gc x0 y0 cx cy)
                  (recur (inc i) drawn? cx cy r g b sr sg sb))
              (do (.setStroke gc (Color/color (clamp01 r0) (clamp01 g0) (clamp01 b0)))
                  (.strokeLine gc x0 y0 cx cy)
                  (recur (inc i) drawn? cx cy r g b r0 g0 b0)))
            (recur (inc i) drawn? cx cy r g b sr sg sb)))))))

(defn- draw-dots!
  "A dot on each drawn point, in its colour, on top of the lines."
  [^GraphicsContext gc points ^double w ^double h]
  (let [n (count points)]
    (loop [i 0
           fr -1.0 fg -1.0 fb -1.0] ; the fill colour set last
      (when (< i n)
        (let [p (nth points i)
              x (value p 0) y (value p 1) r (value p 2) g (value p 3) b (value p 4)]
          (if (and (on-canvas? x y) (lit? r g b))
            (let [same? (and (== r fr) (== g fg) (== b fb))]
              (when-not same?
                (.setFill gc (Color/color (clamp01 r) (clamp01 g) (clamp01 b))))
              (.fillOval gc (- (* (+ x 1.0) 0.5 w) 2.0) (- (* (- 1.0 y) 0.5 h) 2.0) 4.0 4.0)
              (if same?
                (recur (inc i) fr fg fb)
                (recur (inc i) r g b)))
            (recur (inc i) fr fg fb)))))))

(defn- draw-message
  [^GraphicsContext gc ^double width ^double height ^String text]
  (.setFill gc message-color)
  (.setFont gc @message-font)
  (.fillText gc text (- (/ width 2) (* 4.0 (count text))) (/ height 2)))

(defn draw-preview
  "Draw points (a LaserFrame) on canvas, or message (default \"No Preview\")
   when there are none: lines first, then a dot on each point."
  ([canvas points] (draw-preview canvas points "No Preview"))
  ([^Canvas canvas points message]
   (let [gc (.getGraphicsContext2D canvas)
         width (.getWidth canvas)
         height (.getHeight canvas)]
     (draw-background gc width height)
     (if (seq points)
       (let [points (if (vector? points) points (vec points))]
         (draw-lines! gc points width height)
         (draw-dots! gc points width height))
       (draw-message gc width height message)))))

(defn cell-points
  "The points a cell shows of a tick's preview ({:points :ref-frames
   :projector-frames}, frame-service/preview-data), for its config
   ({:target ref}; an old {:zone-group-id ref} reads the same):
   - no target: every cue and zone (the master view);
   - a laser's own ref (:auto.laser/<id>): what that laser draws, composed
     as its engine composes it, before its calibration;
   - any other ref: what is sent to it ([] when nothing is)."
  [preview cell]
  (let [target (if (contains? cell :target) (:target cell) (:zone-group-id cell))]
    (cond
      (nil? target) (:points preview)
      (rig/laser-of target) (or (some-> (get-in preview [:projector-frames (rig/laser-of target)]) deref :frame) [])
      :else (get-in preview [:ref-frames target] []))))

(defn- redraw-cell!
  "A preview cell's canvas from the tick in snapshot (clock-view): what its
   target shows (cell-points), or every zone for the master view (target
   nil). It draws only when that differs from what it drew last: while
   nothing plays, or a cue's frame doesn't change, the canvas isn't touched.
   The canvas's accessible text says what it shows."
  [^Canvas canvas snapshot {:keys [target width height]}]
  (let [stale? (boolean (and snapshot (show-time/stale? snapshot (System/nanoTime))))
        preview (when-not stale? (some-> snapshot :preview deref))
        points (cell-points preview {:target target})
        message (if stale? "Show clock stalled" "No Preview")
        drawn [(not-empty points) message width height]
        props (.getProperties canvas)]
    (when (not= drawn (.get props ::drawn))
      (.put props ::drawn drawn)
      (draw-preview canvas points message)
      (let [text (if (seq points) "Laser preview" message)]
        (when (not= text (.getAccessibleText canvas))
          (.setAccessibleText canvas text))))))


;; A rig card's thumbnail: the last driver call that returned normally
;; (zones plan Z3; reactivity plan 18). What it can say is that frame and
;; when the call returned: no device reports what it received or drew.

(def ^:private limit-color
  "The safety limit's outline: dim, so it never reads as light."
  (Color/rgb 70 70 70))

(defn- draw-thumbnail!
  "points (the frame a driver was passed, in its output space) on canvas,
   on black, inside the grey outline of the configured safety limit [x0 y0
   x1 y1] (none for nil)."
  [^Canvas canvas points limit]
  (let [gc (.getGraphicsContext2D canvas)
        w (.getWidth canvas)
        h (.getHeight canvas)]
    (.setFill gc Color/BLACK)
    (.fillRect gc 0 0 w h)
    (when-let [[x0 y0 x1 y1] limit]
      (.setStroke gc limit-color)
      (.setLineWidth gc 1.0)
      (.strokeRect gc (* (+ (double x0) 1.0) 0.5 w) (* (- 1.0 (double y1)) 0.5 h)
                   (* (- (double x1) (double x0)) 0.5 w) (* (- (double y1) (double y0)) 0.5 h)))
    (when (seq points)
      (draw-lines! gc (if (vector? points) points (vec points)) w h))))

(def ^:const RETURN_FRESH_MS
  "How long a driver call's normal return keeps a thumbnail fresh: from this
   age on it is Stale."
  250)

(def ^:private thumbnail-looks
  "Each kind of thumbnail presentation's caption and its canvas's accessible
   text."
  {:frame {:caption "Driver frame" :text "Last successful driver call: frame"}
   :empty {:caption "Empty frame" :text "Last successful driver call: empty frame"}
   :empty-retry {:caption "Empty (retry)" :text "Last successful driver call: empty recovery frame"}
   :unknown {:caption "Unknown" :text "Output unknown: no current driver result"}
   :stale {:caption "Stale" :text "No recent successful driver return"}
   :off {:caption "Not streaming" :text "Not streaming"}})

(def ^:private thumbnail-tooltip
  "What a thumbnail's caption says it shows."
  "Frame passed to the last driver call that returned normally. Device delivery is not reported.")

(defn thumbnail-presentation
  "What a rig card's thumbnail shows of observation
   (output-runtime/thumbnail-observation) at now-ns, on the clock its
   returns are stamped on (timing/nanotime): {:kind :caption :text :points
   :owner}. :kind is
   - :frame, a fresh return of a nonempty frame (an all-dark one too),
     which is :points;
   - :empty or :empty-retry, a fresh return of an empty frame: a regular
     one, or the one sent to recover from an error;
   - :stale, the last return is RETURN_FRESH_MS old or more;
   - :unknown, an output with no return known now: opening, after an error
     until the next return, or a stamp that is missing or in the future;
   - :off, no output (observation :off).
   Fresh is 0 <= now-ns - :at-ns < RETURN_FRESH_MS. :points is nil but for
   :frame, so no other kind keeps a frame; :owner is the observation's
   [run-token session-token], nil without one. Pure."
  [observation now-ns]
  (let [{:keys [knowledge last-ok run-token session-token]} (when (map? observation) observation)
        {:keys [frame at-ns source]} last-ok
        age (when (and (int? at-ns) (int? now-ns)) (- (long now-ns) (long at-ns)))
        kind (cond
               (= :off observation) :off
               (or (not= :returned knowledge) (nil? age) (neg? age)) :unknown
               (>= age (* RETURN_FRESH_MS 1000000)) :stale
               (seq frame) :frame
               (= :recovery source) :empty-retry
               :else :empty)]
    (assoc (thumbnail-looks kind)
           :kind kind
           :points (when (= :frame kind) frame)
           :owner (when (map? observation) [run-token session-token]))))

(defn- thumbnail-parts
  "The canvas, the caption and the meter slot of a thumbnail row
   (output-thumbnail). A Parent's children aren't a list nth reads."
  [^Pane row]
  (let [children (.getChildren row)
        info (.getChildren ^Pane (.get children 1))]
    [(.get children 0) (.get info 0) (.get info 1)]))

(defn- redraw-thumbnail!
  "One sample of a rig card's thumbnail row (output-thumbnail): its
   projector's observation, read once, then the time, and from that one
   presentation (thumbnail-presentation) the canvas, its accessible text,
   the caption and the meter slot. The canvas is drawn again only when what
   it shows changed: the projector, the owner, the kind, the limit, the
   canvas's size, and the very frame shown (identical?), so a fresh return
   of the same frame keeps it fresh without a repaint. Expiry is decided
   here, when it is read; nothing is cleared at the source. The caption's
   text and the meter slot's visibility (none while off) are this
   painter's alone."
  [^Pane row _snapshot {:keys [projector-id limit]}]
  (let [observation (output-runtime/thumbnail-observation projector-id)
        {:keys [kind caption text points owner]} (thumbnail-presentation observation (timing/nanotime))
        [^Canvas canvas ^Labeled label ^Node slot] (thumbnail-parts row)
        props (.getProperties canvas)
        k [projector-id owner kind limit (.getWidth canvas) (.getHeight canvas)]
        {drawn-key :key drawn-points :points} (.get props ::drawn)]
    (when-not (and (= k drawn-key) (identical? points drawn-points))
      (.put props ::drawn {:key k :points points})
      (draw-thumbnail! canvas points limit))
    (when (not= text (.getAccessibleText canvas))
      (.setAccessibleText canvas text))
    (when (not= caption (.getText label))
      (.setText label caption))
    (let [meter? (not= :off kind)]
      (when (not= meter? (.isVisible slot))
        (.setVisible slot meter?)
        (.setManaged slot meter?)))))

(defn output-thumbnail
  "A rig card's thumbnail row: on a size × size canvas, the frame
   projector-id's driver was passed in its last call that returned
   normally, after calibration and safety (C-10), inside the grey outline
   of its configured safety limit; beside it a one-line caption of what that
   is (thumbnail-presentation), with meter (a description, or nil) under
   it, hidden while the output is off. One timer samples it at most 10
   times a second (redraw-thumbnail!), whatever the show clock does. It
   says nothing of what the device received or drew."
  [{:keys [projector-id limit size meter]}]
  (let [id (name projector-id)]
    {:fx/type clock-view/ext-clock-redraw
     :redraw! #'redraw-thumbnail!
     :every-ms 100
     :sample-every-interval? true
     :desc {:fx/type :h-box
            :id (str "rig-card-output-" id)
            :spacing 6
            :alignment :top-left
            :user-data {:projector-id projector-id :limit limit}
            :children [{:fx/type :canvas
                        :id (str "rig-card-thumbnail-" id)
                        :width size
                        :height size}
                       {:fx/type :v-box
                        :spacing 3
                        :children [{:fx/type :label
                                    :id (str "rig-card-return-" id)
                                    :style-class ["text-small" "label-hint"]
                                    :tooltip {:fx/type :tooltip :text thumbnail-tooltip}}
                                   {:fx/type :v-box
                                    :id (str "rig-card-meter-" id)
                                    :children (if meter [meter] [])}]}]}}))


;; Zone Group Dropdown Helpers


(defn- zone-filter-converter
  "StringConverter for zone filter combo-box items."
  []
  (proxy [StringConverter] []
    (toString [item]
      (cond
        (nil? item) "Select Zone"
        (nil? (:id item)) "All Content"
        (keyword? (:id item)) (str "Zone: " (name (:id item)))
        :else (str item)))
    (fromString [_s] nil)))

(defn- build-zone-filter-items
  "Build items for zone filter combo-box.
   Returns vector of maps with :id and :name keys."
  [zone-groups]
  (into [{:id nil :name "All Content"}]  ;; nil = master view (show all)
        (mapv (fn [zg]
                {:id (:id zg)
                 :name (str "Zone: " (:name zg))})
              zone-groups)))

(defn- find-selected-item
  "Find the selected item in the list based on zone-filter value."
  [items zone-filter]
  (or (u/seek #(= (:id %) zone-filter) items)
      (first items)))


;; Zone Selector Components


(defn- zone-id-text
  "A ref as part of a node id: its namespace and name, dots and slashes as
   dashes (:auto/left is auto-left, :auto.laser/p1 auto-laser-p1), so the
   automatic Left and a kept \"Left (manual)\" (:left) don't share one;
   master for the master view, so it isn't All's."
  [zone-id]
  (if zone-id
    (str/replace (str (when-let [space (namespace zone-id)] (str space "-")) (name zone-id)) "." "-")
    "master"))

(defn zone-selector-popup-item
  "Single item in the zone selector popup menu; a built-in group (locked?)
   shows a lock."
  [{:keys [cell-index zone-id zone-name zone-color selected? locked?]}]
  {:fx/type :h-box
   :id (str "preview-zone-option-" cell-index "-" (zone-id-text zone-id))
   :spacing 6
   :alignment :center-left
   :padding {:left 8 :right 8 :top 6 :bottom 6}
   :style (str "-fx-cursor: hand;"
               (when selected? " -fx-background-color: #404040;"))
   :on-mouse-clicked {:event/type :preview/set-cell-zone
                      :cell-index cell-index
                      :target zone-id}
   :children (filterv some?
                      [{:fx/type :circle
                        :radius 5
                        :fill zone-color}
                       {:fx/type :label
                        :text zone-name
                        :style "-fx-text-fill: white; -fx-font-size: 11;"}
                       (when locked?
                         (zone-chips/lock-graphic {:kind :auto}))
                       (when selected?
                         {:fx/type :label
                          :text "✓"
                          :style "-fx-text-fill: #4AD94A; -fx-font-size: 11;"})])})

(def ^:private zone-fill
  "A zone's colour in the picker and on its cell (zones have none of their
   own)."
  "#4992db")

(defn- popup-header [text]
  {:fx/type :label
   :text text
   :style "-fx-text-fill: #808080; -fx-font-size: 9; -fx-font-weight: bold; -fx-padding: 4 8 2 8;"})

(defn zone-selector-popup
  "Popup menu for what a preview cell shows, the Target menu's rows in the
   :preview scope (views/components/target_control.clj): the master view,
   All, the zones the show uses (zones plan Z4: what is drawn into each),
   then everything else a cue can be sent to (subs/target-groups: the
   automatic groups, each laser, your groups); a laser's cell shows what
   that laser draws. Positioned below the label in a stack-pane."
  [{:keys [fx/context cell-index]}]
  (let [groups (fx/sub-ctx context subs/target-groups-by-id)
        {:keys [names] :as ctx} (target-control/menu-context context)
        cell-config (fx/sub-ctx context subs/preview-cell-config cell-index)
        current (:target cell-config)
        rows (target-control/menu-model (assoc ctx :scope :preview :own current))
        item (fn [target text color locked?]
               {:fx/type zone-selector-popup-item
                :cell-index cell-index
                :zone-id target
                :zone-name text
                :zone-color color
                :selected? (= target current)
                :locked? locked?})]
    {:fx/type :v-box
     ;; Use translate-y to position below the label
     :style "-fx-background-color: #2D2D2D; -fx-padding: 4; -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 8, 0, 0, 2); -fx-translate-y: 22;"
     :on-mouse-exited {:event/type :preview/close-zone-selector}
     :children (let [zones? (some #(= :zone (:kind %)) rows)]
                 (into []
                       (keep (fn [{:keys [kind text target]}]
                               (case kind
                                 :master (item nil text "#808080" false)
                                 ;; ZONES heads the zones the show uses, when it uses any
                                 :header (if (str/starts-with? text "ZONES")
                                           (when zones? (popup-header "ZONES"))
                                           (popup-header text))
                                 :zone (item target (targets/ref-name names target) zone-fill false)
                                 (:ref :group) (let [g (get groups target)]
                                                 (item target (or (:name g) text) (or (:color g) "#808080") (zone-chips/locked? g)))
                                 nil)))
                       rows))}))

(defn zone-selector-label
  "Clickable zone label that opens the zone selector popup."
  [{:keys [fx/context cell-index zone-name zone-color]}]
  {:fx/type :h-box
   :id (str "preview-zone-label-" cell-index)
   :alignment :center-left
   :spacing 4
   :padding {:left 4 :right 4 :top 2 :bottom 2}
   :style (str "-fx-background-color: " zone-color "40; -fx-cursor: hand;")
   :on-mouse-clicked {:event/type :preview/open-zone-selector
                      :cell-index cell-index}
   :children [{:fx/type :circle
               :radius 4
               :fill zone-color}
              {:fx/type :label
               :text zone-name
               :style "-fx-text-fill: white; -fx-font-size: 10;"}
              {:fx/type :label
               :text "▼"
               :style "-fx-text-fill: #808080; -fx-font-size: 8;"}]})


;; Preview Grid Components


(def ^:private zone-label-height 20)

(def ^:private cell-spacing 2)

(def ^:private grid-padding 8)

(def ^:private cell-border 2)

(defn canvas-side
  "Side in px of each preview cell's canvas, for a cols × rows layout in a
   pane of width × height with label-height px for the zone label above
   each canvas: square, as the laser's x and y span the same -1..1, and as
   big as the pane lets every cell be. Before the pane is laid out (no
   sizes), as if the cells shared 400×400, as they did from the config."
  [cols rows width height label-height]
  (let [inner-w (if width (- width (* 2 grid-padding)) 400)
        inner-h (if height (- height (* 2 grid-padding)) 400)
        cell-w (/ (- inner-w (* cell-spacing (dec cols))) cols)
        cell-h (/ (- inner-h (* cell-spacing (dec rows))) rows)]
    (max 0 (long (Math/floor (double (min (- cell-w (* 2 cell-border))
                                          (- cell-h label-height (* 2 cell-border)))))))))

(defn preview-cell
  "Single preview cell in the grid: the show clock's tick for the cell's
   target (cell-points: what is sent to a group, what a laser draws, or
   everything), and a clickable label that opens the picker."
  [{:keys [fx/context cell-index width height]}]
  (let [cell-config (fx/sub-ctx context subs/preview-cell-config cell-index)
        zone-id (:target cell-config)
        zone-groups (fx/sub-ctx context subs/target-groups)
        zone-group (u/seek #(= (:id %) zone-id) zone-groups)
        zone? (targets/zone-ref? zone-id)
        zone-name (cond
                    zone? (targets/ref-name (fx/sub-ctx context subs/names-ctx) zone-id)
                    (:name zone-group) (:name zone-group)
                    zone-id (name zone-id)
                    :else "Master")
        zone-color (or (:color zone-group) (when zone? zone-fill) "#808080")
        show-labels? (fx/sub-ctx context subs/preview-show-labels?)
        popup-open-cell (fx/sub-ctx context subs/preview-zone-selector-open)
        popup-open? (= popup-open-cell cell-index)
        label-height (if show-labels? zone-label-height 0)
        canvas-width (- width (* 2 cell-border))
        canvas-height (- height label-height (* 2 cell-border))]
    {:fx/type :v-box
     :style (str "-fx-border-color: " zone-color "; -fx-border-width: 2; -fx-background-color: #121212;")
     :children (filterv some?
                        [(when show-labels?
                           {:fx/type :stack-pane
                            :alignment :top-left
                            :children (filterv some?
                                               [{:fx/type zone-selector-label
                                                 :cell-index cell-index
                                                 :zone-name zone-name
                                                 :zone-color zone-color}
                                                ;; Overlay dropdown menu when open
                                                (when popup-open?
                                                  {:fx/type zone-selector-popup
                                                   :cell-index cell-index})])})
                         ;; Draws the show clock's newest tick on each screen
                         ;; refresh; the cell re-renders only for its own props
                         {:fx/type clock-view/ext-clock-redraw
                          :redraw! #'redraw-cell!
                          :desc {:fx/type :canvas
                                 :id (str "preview-canvas-" cell-index)
                                 :width canvas-width
                                 :height canvas-height
                                 :user-data {:target zone-id :width canvas-width :height canvas-height}}}])}))

(defn preview-grid
  "Grid of preview cells, as big as the pane under the header lets them be
   (:width and :height, from measured/measured)."
  [{:keys [fx/context width height]}]
  (let [[cols rows] (fx/sub-ctx context subs/preview-grid-layout)
        label-height (if (fx/sub-ctx context subs/preview-show-labels?) zone-label-height 0)
        side (canvas-side cols rows width height label-height)]
    {:fx/type :v-box
     :min-width 0
     :min-height 0
     :padding grid-padding
     :spacing cell-spacing
     :alignment :top-center
     :children (vec
                (for [row (range rows)]
                  {:fx/type :h-box
                   :spacing cell-spacing
                   :alignment :center
                   :children (vec
                              (for [col (range cols)]
                                (let [idx (+ col (* row cols))]
                                  {:fx/type preview-cell
                                   :fx/key idx
                                   :cell-index idx
                                   :width (+ side (* 2 cell-border))
                                   :height (+ side label-height (* 2 cell-border))})))}))}))

(defn frame-stats-label
  "Header text for the preview frame stats.
   Frame stats carry generation latency, not a frame rate, so show that."
  [{:keys [avg-latency-us]}]
  (if avg-latency-us
    (format "%dµs/frame" (long avg-latency-us))
    ""))

(defn preview-grid-header
  "Header for the preview grid panel with layout selector, and in dev mode
   the frame time."
  [{:keys [fx/context]}]
  (let [dev-mode? (fx/sub-ctx context subs/dev-mode?)
        [cols rows] (fx/sub-ctx context subs/preview-grid-layout)]
    {:fx/type :h-box
     :alignment :center-left
     :spacing 8
     :padding {:left 8 :right 8 :top 4 :bottom 4}
     :children (cond-> [{:fx/type :label
                         :text "Preview Grid"
                         :style "-fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold;"}
                        {:fx/type :region :h-box/hgrow :always}
                        {:fx/type :combo-box
                         :id "preview-layout-selector"
                         :value (str cols "x" rows)
                         :items ["1x1" "2x1" "1x2" "2x2" "3x2"]
                         :on-value-changed {:event/type :preview/set-grid-layout}
                         :pref-width 80
                         :style "-fx-font-size: 10;"}]
                 dev-mode? (conj {:fx/type :label
                                  :id "preview-frame-stats"
                                  :text (frame-stats-label (fx/sub-ctx context subs/frame-stats))
                                  :style "-fx-text-fill: #808080; -fx-font-size: 10;"}))}))

(defn preview-grid-panel
  "Complete preview panel with grid header and cells, which fill the space
   under the header."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :id "preview-panel"
   :style "-fx-background-color: #121212;"
   :children [{:fx/type preview-grid-header}
              {:fx/type measured/measured
               :v-box/vgrow :always
               :desc {:fx/type preview-grid}}]})
