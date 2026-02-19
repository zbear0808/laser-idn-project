(ns laser-show.views.components.visual-editors.timeline-editor
  "Timeline editor component for sequencing Cue Chain items.
   
   Provides a multi-track timeline view where each cue/preset is a track
   and effects are expandable sub-tracks. Items can be dragged to adjust
   their :timeline/start and resized to adjust :timeline/duration.
   
   Architecture:
   - timeline-editor      : Top-level wrapper (BorderPane)
   - timeline-toolbar     : Zoom, snap, grid controls
   - timeline-headers     : Track names with zone color indicators
   - timeline-canvas      : Interactive canvas for clips, grid, playhead
   
   Uses canvas-interaction/interactive-canvas for stateless mouse handling."
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [laser-show.views.components.visual-editors.canvas-interaction :as ci]
            [laser-show.animation.chains :as chains]
            [laser-show.css.theme :as theme])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.paint Color]
           [javafx.scene.text Font TextAlignment]
           [javafx.scene.input MouseButton KeyCode]))


;; ============================================================
;; Constants
;; ============================================================


(def ^:private track-height 32)
(def ^:private sub-track-height 24)
(def ^:private ruler-height 24)
(def ^:private header-width 180)
(def ^:private min-clip-width 4)
(def ^:private edge-grab-px 6)
(def ^:private default-zoom 50.0)  ; pixels per beat
(def ^:private default-duration 4.0)


;; ============================================================
;; Color Helpers
;; ============================================================


(def ^:private zone-color-fallback "#808080")

(def ^:private clip-color-default (Color/web "#3A7BD5" 0.85))
(def ^:private clip-color-selected (Color/web "#5BA5F5" 0.95))
(def ^:private clip-color-effect (Color/web "#6B5B8C" 0.75))
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
  "Resolve the zone group color for a cue chain.
   Returns a Color."
  [zone-groups destination-zone-id]
  (let [hex (or (get-in zone-groups [destination-zone-id :color])
                zone-color-fallback)]
    (Color/web hex 0.6)))


;; ============================================================
;; Track Building
;; ============================================================


(defn- build-tracks
  "Build a flat list of track descriptors from the cue chain items.
   Each track is {:id uuid :label string :item map :depth int :type :cue/:effect}.
   Sub-tracks (effects) are included only when the parent is expanded."
  [items expanded-tracks]
  (reduce
   (fn [acc item]
     (let [track {:id (:id item)
                  :label (or (:name item)
                             (some-> (:preset-id item) name)
                             (some-> (:effect-id item) name)
                             "???")
                  :item item
                  :depth 0
                  :type :cue}
           with-cue (conj acc track)]
       (if (and (contains? expanded-tracks (:id item))
                (seq (:effects item)))
         ;; Add effect sub-tracks
         (into with-cue
               (mapv (fn [effect]
                       {:id (:id effect)
                        :label (or (some-> (:effect-id effect) name) "effect")
                        :item effect
                        :depth 1
                        :type :effect})
                     (:effects item)))
         with-cue)))
   []
   items))


(defn- track-y
  "Calculate the Y position for a track given its index."
  [track-idx]
  (+ ruler-height (* track-idx track-height)))


(defn- total-canvas-height
  "Calculate total canvas height for the given number of tracks."
  [track-count]
  (+ ruler-height (* track-count track-height) 20))


;; ============================================================
;; Canvas Drawing
;; ============================================================


(defn- draw-ruler!
  "Draw the beat ruler at the top."
  [^GraphicsContext gc width zoom-x scroll-x]
  (.setFill gc ruler-bg-color)
  (.fillRect gc 0 0 width ruler-height)
  ;; Grid lines and beat labels
  (let [start-beat (/ scroll-x zoom-x)
        end-beat (/ (+ scroll-x width) zoom-x)
        ;; Determine grid subdivision based on zoom level
        subdivisions (cond
                       (> zoom-x 100) 0.25
                       (> zoom-x 40)  1.0
                       (> zoom-x 15)  4.0
                       :else          8.0)
        first-beat (* (Math/floor (/ start-beat subdivisions)) subdivisions)]
    (.setFont gc (Font. "Inter" 10))
    (.setTextAlign gc TextAlignment/CENTER)
    (loop [beat first-beat]
      (when (<= beat end-beat)
        (let [x (- (* beat zoom-x) scroll-x)]
          (when (zero? (mod beat 1.0))
            (.setFill gc ruler-text-color)
            (.fillText gc (str (int beat)) x (- ruler-height 6)))
          ;; Tick marks
          (.setStroke gc (if (zero? (mod beat 4.0))
                           grid-line-major-color
                           grid-line-color))
          (.setLineWidth gc (if (zero? (mod beat 4.0)) 1.5 0.5))
          (.strokeLine gc x ruler-height x (- ruler-height 4)))
        (recur (+ beat subdivisions))))))


(defn- draw-grid!
  "Draw vertical grid lines across all tracks."
  [^GraphicsContext gc width height zoom-x scroll-x]
  (let [start-beat (/ scroll-x zoom-x)
        end-beat (/ (+ scroll-x width) zoom-x)
        subdivisions (cond
                       (> zoom-x 100) 0.25
                       (> zoom-x 40)  1.0
                       (> zoom-x 15)  4.0
                       :else          8.0)
        first-beat (* (Math/floor (/ start-beat subdivisions)) subdivisions)]
    (loop [beat first-beat]
      (when (<= beat end-beat)
        (let [x (- (* beat zoom-x) scroll-x)]
          (.setStroke gc (if (zero? (mod beat 4.0))
                           grid-line-major-color
                           grid-line-color))
          (.setLineWidth gc (if (zero? (mod beat 4.0)) 1.0 0.3))
          (.strokeLine gc x ruler-height x height))
        (recur (+ beat subdivisions))))))


(defn- draw-track-backgrounds!
  "Draw alternating track lane backgrounds."
  [^GraphicsContext gc width tracks]
  (doseq [[idx _track] (map-indexed vector tracks)]
    (let [y (track-y idx)]
      (.setFill gc (if (even? idx) track-bg-even track-bg-odd))
      (.fillRect gc 0 y width track-height))))


(defn- draw-clip!
  "Draw a single clip (item rectangle) on the canvas."
  [^GraphicsContext gc track-idx item zoom-x scroll-x selection zone-color]
  (let [start (:timeline/start item 0.0)
        duration (:timeline/duration item default-duration)
        x (- (* start zoom-x) scroll-x)
        w (max min-clip-width (* duration zoom-x))
        y (+ (track-y track-idx) 2)
        h (- track-height 4)
        selected? (contains? selection (:id item))]
    ;; Zone color background tint
    (when zone-color
      (.setFill gc zone-color)
      (.fillRect gc x y w h))
    ;; Clip body
    (.setFill gc (if selected? clip-color-selected clip-color-default))
    (.fillRect gc (+ x 1) (+ y 1) (- w 2) (- h 2))
    ;; Label
    (.setFill gc Color/WHITE)
    (.setFont gc (Font. "Inter" 10))
    (.setTextAlign gc TextAlignment/LEFT)
    (let [label (or (some-> (:preset-id item) name)
                    (some-> (:effect-id item) name)
                    "")]
      (when (> w 30)
        (.fillText gc label (+ x 6) (+ y 14))))
    ;; Selection border
    (when selected?
      (.setStroke gc selection-border-color)
      (.setLineWidth gc 1.5)
      (.strokeRect gc x y w h))))


(defn- draw-playhead!
  "Draw the playhead vertical line."
  [^GraphicsContext gc height zoom-x scroll-x beats-elapsed]
  (let [x (- (* beats-elapsed zoom-x) scroll-x)]
    (when (and (>= x 0) (<= x 2000))  ;; Only draw if visible
      (.setStroke gc playhead-color)
      (.setLineWidth gc 2.0)
      (.strokeLine gc x 0 x height))))


(defn- render-timeline!
  "Main canvas render function.
   Called by interactive-canvas with current value and drag-info."
  [^Canvas canvas value drag-info]
  (let [{:keys [tracks zoom-x scroll-x selection
                beats-elapsed zone-groups destination-zone-id]} value
        gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas)
        height (.getHeight canvas)
        zone-color (zone-group-color zone-groups destination-zone-id)]
    ;; Clear
    (.setFill gc bg-color)
    (.fillRect gc 0 0 width height)
    ;; Draw layers
    (draw-track-backgrounds! gc width tracks)
    (draw-grid! gc width height zoom-x scroll-x)
    (draw-ruler! gc width zoom-x scroll-x)
    ;; Draw clips
    (doseq [[idx {:keys [item]}] (map-indexed vector tracks)]
      (draw-clip! gc idx item zoom-x scroll-x selection zone-color))
    ;; Playhead
    (draw-playhead! gc height zoom-x scroll-x (or beats-elapsed 0.0))))


;; ============================================================
;; Hit Testing
;; ============================================================


(defn- hit-test
  "Find which clip is under the mouse.
   Returns {:track-idx int :item map :edge :left/:right/:center} or nil."
  [mx my tracks zoom-x scroll-x]
  (let [track-idx (int (/ (- my ruler-height) track-height))]
    (when (and (>= track-idx 0) (< track-idx (count tracks)))
      (let [{:keys [item]} (nth tracks track-idx)
            start (:timeline/start item 0.0)
            duration (:timeline/duration item default-duration)
            clip-x (- (* start zoom-x) scroll-x)
            clip-w (max min-clip-width (* duration zoom-x))
            clip-end (+ clip-x clip-w)]
        (when (and (>= mx clip-x) (<= mx clip-end))
          (let [edge (cond
                       (< mx (+ clip-x edge-grab-px)) :left
                       (> mx (- clip-end edge-grab-px)) :right
                       :else :center)]
            {:track-idx track-idx
             :item item
             :edge edge}))))))


;; ============================================================
;; Interaction Handlers
;; ============================================================


(defn- on-press
  "Handle mouse press. Select item or begin drag."
  [mx my button value drag-info]
  (let [{:keys [tracks zoom-x scroll-x col row]} value
        hit (hit-test mx my tracks zoom-x scroll-x)]
    (when (= button MouseButton/PRIMARY)
      (if hit
        (let [{:keys [item edge track-idx]} hit
              item-id (:id item)]
          {:drag-start true
           :drag-id item-id
           :dispatch {:event/type :timeline/select-items
                      :ids [item-id]
                      :mode :replace}
           :drag-updates {:edge edge
                          :start-mx mx
                          :original-start (:timeline/start item 0.0)
                          :original-duration (:timeline/duration item default-duration)
                          :col col
                          :row row}})
        ;; Clicked empty space - clear selection
        {:dispatch {:event/type :timeline/clear-selection}}))))


(defn- on-drag
  "Handle mouse drag. Move or resize the currently-dragged clip."
  [mx my value drag-info]
  (let [{:keys [zoom-x col row]} value
        {:keys [edge start-mx original-start original-duration drag-id]} drag-info
        delta-px (- mx start-mx)
        delta-beats (/ delta-px zoom-x)]
    (case edge
      :center
      {:dispatch {:event/type :timeline/update-item-timing
                  :col col :row row :id drag-id
                  :start (+ original-start delta-beats)}}
      :right
      {:dispatch {:event/type :timeline/update-item-timing
                  :col col :row row :id drag-id
                  :duration (+ original-duration delta-beats)}}
      :left
      (let [new-start (+ original-start delta-beats)
            start-delta (- new-start original-start)
            new-dur (- original-duration start-delta)]
        {:dispatch {:event/type :timeline/update-item-timing
                    :col col :row row :id drag-id
                    :start new-start
                    :duration new-dur}})
      nil)))


(defn- on-hover
  "Handle mouse hover. Update cursor based on edge proximity."
  [mx my value drag-info]
  (let [{:keys [tracks zoom-x scroll-x]} value
        hit (hit-test mx my tracks zoom-x scroll-x)]
    (if hit
      {:hover-id (:id (:item hit))
       :cursor (case (:edge hit)
                 :left "w-resize"
                 :right "e-resize"
                 :center "move")}
      {:hover-id nil
       :cursor "crosshair"})))


(defn- on-key
  "Handle keyboard shortcuts."
  [^KeyCode key-code shift? value drag-info]
  (let [{:keys [col row]} value]
    (case (.getName key-code)
      "Delete" {:dispatch {:event/type :timeline/clear-selection}
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


;; ============================================================
;; Sub-Components
;; ============================================================


(defn timeline-toolbar
  "Toolbar component with zoom slider and snap controls."
  [{:keys [zoom-x snap-enabled? snap-value]}]
  {:fx/type :h-box
   :spacing 12
   :padding {:top 4 :bottom 4 :left 8 :right 8}
   :alignment :center-left
   :style "-fx-background-color: #1A1A1A;"
   :children
   [;; Snap toggle
    {:fx/type :check-box
     :text "Snap"
     :selected (boolean snap-enabled?)
     :on-selected-changed {:event/type :timeline/set-snap
                           :enabled? (not snap-enabled?)}}
    ;; Snap value selector
    {:fx/type :combo-box
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
     :min 10 :max 500
     :value (or zoom-x default-zoom)
     :pref-width 140
     :on-value-changed {:event/type :timeline/set-zoom
                        :zoom (or zoom-x default-zoom)}}]})


(defn track-header
  "Single track header row with name and zone indicator."
  [{:keys [label depth zone-color expanded? has-effects? id]}]
  {:fx/type :h-box
   :pref-height track-height
   :min-height track-height
   :max-height track-height
   :alignment :center-left
   :spacing 4
   :padding {:left (+ 4 (* depth 16)) :right 4}
   :style (str "-fx-background-color: #1E1E1E; "
               "-fx-border-color: #333333; "
               "-fx-border-width: 0 0 1 0;")
   :children
   (cond-> []
     ;; Zone color indicator (left bar)
     zone-color
     (conj {:fx/type :region
            :pref-width 4
            :pref-height (- track-height 6)
            :style (str "-fx-background-color: " zone-color "; "
                        "-fx-background-radius: 2;")})
     ;; Expand arrow (if applicable)
     has-effects?
     (conj {:fx/type :button
            :text (if expanded? "▼" "▶")
            :style "-fx-background-color: transparent; -fx-text-fill: #999; -fx-padding: 0 4;"
            :on-action {:event/type :timeline/toggle-track-expand
                        :id id}})
     ;; Label
     true
     (conj {:fx/type :label
            :text (or label "")
            :style "-fx-text-fill: #CCCCCC; -fx-font-size: 11;"}))})


(defn timeline-headers
  "Left pane: track header list."
  [{:keys [tracks zone-groups destination-zone-id expanded-tracks]}]
  (let [zone-hex (or (get-in zone-groups [destination-zone-id :color])
                     zone-color-fallback)]
    {:fx/type :v-box
     :pref-width header-width
     :min-width header-width
     :style "-fx-background-color: #1A1A1A;"
     :children
     (into
      ;; Ruler spacer
      [{:fx/type :region
        :pref-height ruler-height
        :style "-fx-background-color: #1A1A1A;"}]
      (mapv (fn [{:keys [id label depth type item]}]
              {:fx/type track-header
               :label label
               :depth depth
               :zone-color (when (= type :cue) zone-hex)
               :expanded? (contains? expanded-tracks id)
               :has-effects? (and (= type :cue) (seq (:effects item)))
               :id id})
            tracks))}))


(defn timeline-canvas
  "The interactive canvas that shows the timeline grid, clips, and playhead."
  [{:keys [col row items tracks zoom-x scroll-x selection
           beats-elapsed zone-groups destination-zone-id]}]
  (let [canvas-height (total-canvas-height (count tracks))
        canvas-width (max 800 (* 32 (or zoom-x default-zoom)))]
    {:fx/type ci/interactive-canvas
     :width canvas-width
     :height canvas-height
     :cursor "crosshair"
     :value {:tracks tracks
             :zoom-x (or zoom-x default-zoom)
             :scroll-x (or scroll-x 0.0)
             :selection (or selection #{})
             :beats-elapsed (or beats-elapsed 0.0)
             :col col
             :row row
             :zone-groups zone-groups
             :destination-zone-id destination-zone-id}
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
   - :col, :row     — Grid cell coordinate for the cue chain
   - :items         — Cue chain items vector
   - :zone-groups   — Map of zone-group-id -> group config
   - :destination-zone-id — The cue chain's :destination-zone :zone-group-id
   - :timeline-ui   — Map from [:ui :timeline] state
   - :beats-elapsed — Current beat position from active cue timing"
  [{:keys [col row items zone-groups destination-zone-id
           timeline-ui beats-elapsed]}]
  (let [{:keys [zoom-x scroll-x selection snap-enabled?
                snap-value expanded-tracks]
         :or {zoom-x default-zoom
              scroll-x 0.0
              selection #{}
              snap-enabled? true
              snap-value 0.25
              expanded-tracks #{}}} timeline-ui
        tracks (build-tracks (or items []) (or expanded-tracks #{}))]
    {:fx/type :border-pane
     :style "-fx-background-color: #121212;"
     :top {:fx/type timeline-toolbar
           :zoom-x zoom-x
           :snap-enabled? snap-enabled?
           :snap-value snap-value}
     :center
     {:fx/type :split-pane
      :divider-positions [0.2]
      :items
      [{:fx/type timeline-headers
        :tracks tracks
        :zone-groups zone-groups
        :destination-zone-id destination-zone-id
        :expanded-tracks (or expanded-tracks #{})}
       {:fx/type :scroll-pane
        :fit-to-height true
        :hbar-policy :always
        :vbar-policy :as-needed
        :style "-fx-background-color: transparent; -fx-background: transparent;"
        :content
        {:fx/type timeline-canvas
         :col col
         :row row
         :items items
         :tracks tracks
         :zoom-x zoom-x
         :scroll-x scroll-x
         :selection selection
         :beats-elapsed beats-elapsed
         :zone-groups zone-groups
         :destination-zone-id destination-zone-id}}]}}))
