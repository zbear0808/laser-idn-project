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
            [laser-show.events.core :as events]
            [laser-show.views.components.visual-editors.canvas-interaction :as ci]
            [laser-show.views.components.visual-editors.timeline.track-logic :as tl]
            [laser-show.views.components.list :as list]
            [laser-show.views.components.preset-bank :as preset-bank]
            [laser-show.views.components.effect-bank :as effect-bank])
  (:import [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.paint Color]
           [javafx.scene.control ScrollPane]
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
   
   Folders are flattened into single rows that act as separators or global effects lines.
   Visible tracks are derived using tl/flatten-visible-tracks."
  [track-defs items expanded-tracks]
  (if (seq track-defs)
    (let [grouped (tl/items-by-track items)
          flat-tracks (tl/flatten-visible-tracks track-defs)
          track-rows (mapv (fn [track]
                             {:id (:id track)
                              :label (:name track)
                              :type (:type track :track)
                              :track track
                              :items (get grouped (:id track) [])
                              :zone-group-id (:zone-group-id track)})
                           flat-tracks)
          unassigned (get grouped ::tl/unassigned)]
      (if (seq unassigned)
        (conj track-rows
              {:id ::unassigned
               :label "Unassigned"
               :type :track
               :track nil
               :items unassigned
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


;; ============================================================
;; Canvas Drawing
;; ============================================================

(def ^:private loop-brace-color-active (Color/web "#00FFFF" 0.8))
(def ^:private loop-brace-color-inactive (Color/web "#00FFFF" 0.3))
(def ^:private loop-brace-height 8)

(defn- draw-loop-brace!
  "Draw the loop brace in the ruler area."
  [^GraphicsContext gc zoom-x scroll-x loop-config]
  (when loop-config
    (let [{:keys [enabled? start duration]} loop-config
          x (- (* start zoom-x) scroll-x)
          w (* duration zoom-x)
          y (- ruler-height loop-brace-height 2)
          color (if enabled? loop-brace-color-active loop-brace-color-inactive)]
      (.setFill gc color)
      ;; Draw a top bar and small side ticks to look like a brace [...]
      (.fillRect gc x y w 4) ;; top bar
      (.fillRect gc x y 4 loop-brace-height) ;; left tick
      (.fillRect gc (- (+ x w) 4) y 4 loop-brace-height))))

(defn- draw-ruler!
  "Draw the beat ruler at the top."
  [^GraphicsContext gc width zoom-x scroll-x loop-config]
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
        (recur (+ beat subdivisions))))
    (draw-loop-brace! gc zoom-x scroll-x loop-config)))


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


(def ^:private group-bg-color (Color/web "#2A2A2A"))

(defn- draw-track-backgrounds!
  "Draw alternating track lane backgrounds.
   Group tracks get a distinct darker background."
  [^GraphicsContext gc width tracks]
  (doseq [[idx track] (map-indexed vector tracks)]
    (let [y (track-y idx)]
      (.setFill gc (if (= :group (:type track))
                     group-bg-color
                     (if (even? idx) track-bg-even track-bg-odd)))
      (.fillRect gc 0 y width track-height))))


(defn- draw-clip!
  "Draw a single clip (item rectangle) on the canvas.
   Uses the track's resolved color as the clip fill."
  [^GraphicsContext gc track-idx item zoom-x scroll-x selection ^Color color]
  (let [start (:timeline/start item 0.0)
        duration (:timeline/duration item default-duration)
        x (- (* start zoom-x) scroll-x)
        w (max min-clip-width (* duration zoom-x))
        y (+ (track-y track-idx) 2)
        h (- track-height 4)
        selected? (contains? selection (:id item))
        body-color (if color
                     (if selected? (.brighter color) color)
                     (if selected? clip-color-selected clip-color-default))]
    ;; Clip body
    (.setFill gc body-color)
    (.fillRect gc x y w h)
    ;; Label
    (.setFill gc Color/WHITE)
    (.setFont gc (Font. "Inter" 10))
    (.setTextAlign gc TextAlignment/LEFT)
    (let [label (or (:name item)
                    (some-> (:preset-id item) name)
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
                beats-elapsed zone-groups loop-config]} value
        gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas)
        height (.getHeight canvas)]
    ;; Clear
    (.setFill gc bg-color)
    (.fillRect gc 0 0 width height)
    ;; Draw layers
    (draw-track-backgrounds! gc width tracks)
    (draw-grid! gc width height zoom-x scroll-x)
    (draw-ruler! gc width zoom-x scroll-x loop-config)
    ;; Draw clips — each track row can have multiple items
    (doseq [[idx {:keys [items track zone-group-id]}] (map-indexed vector tracks)]
      (let [color (if track
                    (track-color track zone-groups)
                    (when zone-group-id
                      (zone-group-color zone-groups zone-group-id)))]
        (doseq [item items]
          (draw-clip! gc idx item zoom-x scroll-x selection color))))
    ;; Playhead
    (draw-playhead! gc height zoom-x scroll-x (or beats-elapsed 0.0))))


;; ============================================================
;; Hit Testing
;; ============================================================


(defn- hit-test
  "Find which clip is under the mouse.
   Returns {:track-idx int :item map :edge :left/:right/:center} or nil.
   Scans all items within the hovered track row."
  [mx my tracks zoom-x scroll-x scroll-y loop-config]
  (let [scroll-y (or scroll-y 0.0)
        scroll-x (or scroll-x 0.0)]
    (if (and loop-config (<= my ruler-height))
      ;; Check loop brace hit
      (let [{:keys [start duration]} loop-config
            x (- (* start zoom-x) scroll-x)
            w (* duration zoom-x)]
        (when (and (>= mx x) (<= mx (+ x w)))
          (let [edge (cond
                       (< mx (+ x edge-grab-px)) :left
                       (> mx (- (+ x w) edge-grab-px)) :right
                       :else :center)]
            {:type :loop-brace
             :edge edge})))
      ;; Otherwise check clips
      (let [track-idx (int (/ (+ (- my ruler-height) scroll-y) track-height))]
        (when (and (>= track-idx 0) (< track-idx (count tracks)))
          (let [{:keys [items]} (nth tracks track-idx)]
            ;; Check each item in this track row for a hit
            (some (fn [item]
                    (let [start (:timeline/start item 0.0)
                          duration (:timeline/duration item default-duration)
                          clip-x (- (* start zoom-x) scroll-x)
                          clip-w (max min-clip-width (* duration zoom-x))
                          clip-end (+ clip-x clip-w)]
                      (when (and (>= mx clip-x) (<= mx clip-end))
                        (let [edge (cond
                                     (< mx (+ clip-x edge-grab-px)) :left
                                     (> mx (- clip-end edge-grab-px)) :right
                                     :else :center)]
                          {:type :clip
                           :track-idx track-idx
                           :item item
                           :edge edge}))))
                  items)))))))


;; ============================================================
;; Interaction Handlers
;; ============================================================


(defn- on-press
  "Handle mouse press. Select item or begin drag."
  [mx my button value drag-info]
  (let [{:keys [tracks zoom-x scroll-x scroll-y col row loop-config]} value
        hit (hit-test mx my tracks zoom-x scroll-x scroll-y loop-config)]
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
                            :original-track-idx track-idx
                            :col col
                            :row row}}))
        ;; Clicked empty space - clear selection
        {:dispatch {:event/type :timeline/clear-selection}}))))


(defn- on-drag
  "Handle mouse drag. Move or resize the currently-dragged clip or loop brace.
   Dispatches cross-track transfers when dragged vertically to new rows."
  [mx my value drag-info]
  (let [{:keys [zoom-x scroll-y tracks col row]} value
        {:keys [edge start-mx original-start original-duration original-track-idx drag-id]} drag-info
        scroll-y (or scroll-y 0.0)
        delta-px (- mx start-mx)
        delta-beats (/ delta-px zoom-x)]
    (if (= drag-id :loop-brace)
      (case edge
        :center
        {:dispatch [{:event/type :timeline/update-loop-timing
                     :col col :row row
                     :start (+ original-start delta-beats)}]}
        :right
        {:dispatch [{:event/type :timeline/update-loop-timing
                     :col col :row row
                     :duration (+ original-duration delta-beats)}]}
        :left
        (let [new-start (+ original-start delta-beats)
              start-delta (- new-start original-start)
              new-dur (- original-duration start-delta)]
          {:dispatch [{:event/type :timeline/update-loop-timing
                       :col col :row row
                       :start new-start
                       :duration new-dur}]}))
      (let [current-track-idx (int (/ (+ (- my ruler-height) scroll-y) track-height))
            ;; Determine if we crossed into a new valid track row that isn't a folder
            track-changed? (and (not= edge :left)
                                (not= edge :right)
                                (not= current-track-idx original-track-idx)
                                (>= current-track-idx 0)
                                (< current-track-idx (count tracks)))
            destination-track (when track-changed? (nth tracks current-track-idx nil))
            valid-destination? (and destination-track
                                    (not (tl/track-group? destination-track)))
            base-events (case edge
                          :center
                          [{:event/type :timeline/update-item-timing
                            :col col :row row :id drag-id
                            :start (+ original-start delta-beats)}]
                          :right
                          [{:event/type :timeline/update-item-timing
                            :col col :row row :id drag-id
                            :duration (+ original-duration delta-beats)}]
                          :left
                          (let [new-start (+ original-start delta-beats)
                                start-delta (- new-start original-start)
                                new-dur (- original-duration start-delta)]
                            [{:event/type :timeline/update-item-timing
                              :col col :row row :id drag-id
                              :start new-start
                              :duration new-dur}])
                          [])

            events (if valid-destination?
                     (conj base-events {:event/type :timeline/move-item-to-track
                                        :col col :row row
                                        :item-id drag-id
                                        :track-id (:id destination-track)})
                     base-events)]

        (when (seq events)
          {:dispatch events
           ;; Update drag state if track changed so we don't dispatch continuously
           :drag-updates (when valid-destination?
                           {:original-track-idx current-track-idx})})))))


(defn- on-hover
  "Handle mouse hover. Update cursor based on edge proximity."
  [mx my value drag-info]
  (let [{:keys [tracks zoom-x scroll-x scroll-y loop-config]} value
        hit (hit-test mx my tracks zoom-x scroll-x scroll-y loop-config)]
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
  [{:keys [zoom-x snap-enabled? snap-value col row loop-config add-panel-open?]}]
  {:fx/type :h-box
   :spacing 12
   :padding {:top 4 :bottom 4 :left 8 :right 8}
   :alignment :center-left
   :style "-fx-background-color: #1A1A1A;"
   :children
   [;; Add buttons
    {:fx/type :button
     :text "Add Track"
     :style-class "button-primary"
     :on-action {:event/type :timeline/add-track
                 :col col :row row}}
    {:fx/type :button
     :text "Add Folder"
     :style-class "button-secondary"
     :on-action {:event/type :timeline/add-folder
                 :col col :row row}}
    {:fx/type :toggle-button
     :text "Add Content"
     :style-class "button-secondary"
     :selected (boolean add-panel-open?)
     :on-action {:event/type :timeline/toggle-add-panel}}

    ;; Spacer
    {:fx/type :region
     :h-box/hgrow :always}

    ;; Loop toggle
    {:fx/type :toggle-button
     :text "Loop"
     :selected (boolean (:enabled? loop-config true))
     :on-action {:event/type :timeline/toggle-loop
                 :col col :row row}}

    ;; Snap toggle
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


(defn track-label-renderer
  "Custom list-editor label renderer for tracks.
   Displays the track name and its assigned zone group."
  [zone-groups item]
  (let [zone-hex (or (:color item)
                     (get-in zone-groups [(:zone-group-id item) :color])
                     zone-color-fallback)
        zone-name (or (get-in zone-groups [(:zone-group-id item) :name])
                      (when-let [gid (:zone-group-id item)] (name gid)))]
    (if zone-name
      (str (:name item "Track") " [" zone-name "]")
      (:name item "Track"))))

(defn timeline-sidebar
  "Left pane: track list managed by list-editor."
  [{:keys [context track-defs col row zone-groups list-props items-path]}]
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
     :get-item-label (partial track-label-renderer zone-groups)
     :items-path items-path
     :on-change-event :timeline/update-tracks ;; Requires custom handler or alias
     :on-change-params {:col col :row row}
     :header-label "TRACKS"
     :empty-text "No tracks. Add one to start."
     :allow-groups? true
     :scrollable? false ;; Crucial: Make it non-scrollable here so the wrapper handles scrolling
     :compact? true}]})


(defn timeline-add-panel
  "Collapsible bottom panel for adding presets and effects to the timeline.
   Shows preset-bank (always) and effect-bank (when a clip is selected)."
  [{:keys [col row add-panel-open? add-panel-preset-tab add-panel-effect-tab
           selected-item-id selected-track-id]}]
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
                   :text "ADD CONTENT"
                   :style "-fx-text-fill: #999999; -fx-font-size: 11; -fx-font-weight: bold;"}
                  {:fx/type :region :h-box/hgrow :always}
                  {:fx/type :label
                   :text (if selected-item-id
                           "Clip selected — effect bank enabled"
                           "Select a clip to add effects")
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
                                           :track-id selected-track-id}}]}
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
                         :style "-fx-text-fill: #555555; -fx-font-size: 11;"}]}])}]}]}))


(defn timeline-canvas
  "The interactive canvas that shows the timeline grid, clips, and playhead."
  [{:keys [col row tracks zoom-x scroll-x selection
           beats-elapsed loop-config zone-groups]}]
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
   - :destination-zone-id — The cue chain's :destination-zone :zone-group-id
   - :timeline-ui   — Map from [:ui :timeline] state
   - :beats-elapsed — Current beat position from active cue timing
   - :loop-config   — Map with {:enabled? :start :duration}
   - :list-props    — Map of props to forward to list-editor sidebar"
  [{:keys [fx/context col row items track-defs zone-groups destination-zone-id
           timeline-ui beats-elapsed loop-config list-props]}]
  (let [{:keys [zoom-x scroll-x selection snap-enabled?
                snap-value expanded-tracks sync-scroll-y
                add-panel-open? add-panel-preset-tab add-panel-effect-tab]
         :or {zoom-x default-zoom
              scroll-x 0.0
              selection #{}
              snap-enabled? true
              snap-value 0.25
              expanded-tracks #{}}} timeline-ui
        tracks (build-tracks (or track-defs []) (or items []) (or expanded-tracks #{}))
        ;; Determine selected item ID for the effect bank
        selected-item-id (when (= 1 (count selection)) (first selection))
        ;; Determine selected track-id: from the selected clip or first non-group track
        selected-track-id (or (when selected-item-id
                                (let [found-track (some (fn [t]
                                                          (when (some (fn [it] (= (:id it) selected-item-id)) (:items t))
                                                            t))
                                                        tracks)]
                                  (:id found-track)))
                              (:id (first (remove #(= :group (:type %)) tracks))))]
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
     :bottom {:fx/type timeline-add-panel
              :col col
              :row row
              :add-panel-open? add-panel-open?
              :add-panel-preset-tab add-panel-preset-tab
              :add-panel-effect-tab add-panel-effect-tab
              :selected-item-id selected-item-id
              :selected-track-id selected-track-id}
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
         :vvalue (or sync-scroll-y 0.0)
         :on-vvalue-changed {:event/type :timeline/sync-scroll :y 0.0} ;; Fallback, usually bound
         :content {:fx/type timeline-sidebar
                   :context context
                   :track-defs track-defs
                   :zone-groups zone-groups
                   :col col
                   :row row
                   :items-path [:grid :cues col row :tracks]
                   :list-props list-props}}}
       {:fx/type fx/ext-on-instance-lifecycle
        :on-created (fn [^ScrollPane sp]
                      (events/dispatch! {:event/type :timeline/register-scroll-pane
                                         :pane :right :instance sp}))
        :on-deleted (fn [_]
                      (events/dispatch! {:event/type :timeline/register-scroll-pane
                                         :pane :right :instance nil}))
        :desc
        {:fx/type :scroll-pane
         :fit-to-height true
         :hbar-policy :always
         :vbar-policy :as-needed
         :vvalue (or sync-scroll-y 0.0)
         :on-vvalue-changed {:event/type :timeline/sync-scroll :pane :right}
         :style "-fx-background-color: transparent; -fx-background: transparent;"
         :content
         {:fx/type timeline-canvas
          :col col
          :row row
          :tracks tracks
          :zoom-x zoom-x
          :scroll-x scroll-x
          :selection selection
          :beats-elapsed beats-elapsed
          :loop-config loop-config
          :zone-groups zone-groups}}}]}}))
