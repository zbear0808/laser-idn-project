(ns laser-show.views.components.preview
  "Preview panel: a grid of cells, each drawing the show clock's newest tick
   on each screen refresh (clock-view), for one zone group or for all of them.
   A cell shows what an engine on its zone sends, before projector effects.
   The cells' canvases are square and as big as the pane lets them be.

   Features:
   - Zone group filtering per cell (the label opens a zone picker)
   - Layout selector in the header

   LaserPoints are vectors [x y r g b] with NORMALIZED values: x, y in -1..1,
   r, g, b in 0..1."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.animation.show-time :as show-time]
            [laser-show.subs :as subs]
            [laser-show.common.util :as u]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.zone-chips :as zone-chips]
            [laser-show.views.components.measured :as measured])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
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

(defn- get-zone-filtered-points
  "The points a cell for zone-id shows from a tick's preview
   ({:points :zone-frames}, frame-service/preview-data):
   - nil zone-id: every cue and zone (the master view)
   - a zone-id: that zone's frame, looked up directly ([] when none)"
  [preview zone-id]
  (if (nil? zone-id)
    (:points preview)
    (get (:zone-frames preview) zone-id [])))

(defn- redraw-cell!
  "A preview cell's canvas from the tick in snapshot (clock-view): its zone's
   frame, or every zone's for the master view (zone-id nil). It draws only
   when that differs from what it drew last: while nothing plays, or a cue's
   frame doesn't change, the canvas isn't touched. The canvas's accessible
   text says what it shows."
  [^Canvas canvas snapshot {:keys [zone-id width height]}]
  (let [stale? (boolean (and snapshot (show-time/stale? snapshot (System/nanoTime))))
        preview (when-not stale? (some-> snapshot :preview deref))
        points (get-zone-filtered-points preview zone-id)
        message (if stale? "Show clock stalled" "No Preview")
        drawn [(not-empty points) message width height]
        props (.getProperties canvas)]
    (when (not= drawn (.get props ::drawn))
      (.put props ::drawn drawn)
      (draw-preview canvas points message)
      (let [text (if (seq points) "Laser preview" message)]
        (when (not= text (.getAccessibleText canvas))
          (.setAccessibleText canvas text))))))


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
   automatic Left and a kept \"Left (manual)\" (:left) don't share one."
  [zone-id]
  (if zone-id
    (str/replace (str (when-let [space (namespace zone-id)] (str space "-")) (name zone-id)) "." "-")
    "all"))

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
                      :zone-group-id zone-id}
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

(defn zone-selector-popup
  "Popup menu for selecting zone group for a preview cell.
   Positioned below the label in a stack-pane."
  [{:keys [fx/context cell-index]}]
  (let [zone-groups (fx/sub-ctx context subs/target-groups)
        cell-config (fx/sub-ctx context subs/preview-cell-config cell-index)
        current-zone-id (:zone-group-id cell-config)
        ;; Build items: Master (nil) first, then all zone groups
        items (into [{:id nil :name "Master (All)" :color "#808080"}]
                    zone-groups)]
    {:fx/type :v-box
     ;; Use translate-y to position below the label
     :style "-fx-background-color: #2D2D2D; -fx-padding: 4; -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 8, 0, 0, 2); -fx-translate-y: 22;"
     :on-mouse-exited {:event/type :preview/close-zone-selector}
     :children (vec (for [{:keys [id name color] :as item} items]
                      {:fx/type zone-selector-popup-item
                       :cell-index cell-index
                       :zone-id id
                       :zone-name name
                       :zone-color (or color "#808080")
                       :selected? (= id current-zone-id)
                       :locked? (zone-chips/locked? item)}))}))

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
  "Single preview cell in the grid: the show clock's tick for the cell's zone
   (or every zone), and a clickable zone label that opens the zone picker."
  [{:keys [fx/context cell-index width height]}]
  (let [cell-config (fx/sub-ctx context subs/preview-cell-config cell-index)
        zone-id (:zone-group-id cell-config)
        zone-groups (fx/sub-ctx context subs/target-groups)
        zone-group (u/seek #(= (:id %) zone-id) zone-groups)
        zone-name (or (:name zone-group)
                      (if zone-id (name zone-id) "Master"))
        zone-color (or (:color zone-group) "#808080")
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
                                 :user-data {:zone-id zone-id :width canvas-width :height canvas-height}}}])}))

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
