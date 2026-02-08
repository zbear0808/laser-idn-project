(ns laser-show.views.components.visual-editors.custom-param-renderers
  "Custom parameter renderers for effects with specialized UI needs.
   
   Provides visual editors for effects like:
   - Translate: 2D point dragging for X/Y position
   - Corner Pin: 4-corner quadrilateral manipulation
   - Rotation: Circular dial for angle adjustment
   - Scale: Rectangle with edge/corner handles for X/Y scaling
   - RGB Curves: Photoshop-style curve editor for color channel adjustment
   - Zone Reroute: Zone group selector for routing effects
   - Hue Slider: Horizontal gradient for hue selection
   
   All visual editors use the event-template pattern:
   - :event-template - Base event map that will be augmented with parameter values
   - :current-params - Current parameter values for the effect
   - :reset-event - (where applicable) Event for resetting to defaults
   
   Single-parameter visual editors (rotation, hue, hue-shift) support optional
   modulator toggle to enable animation without switching to numeric mode."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.events.core :as events]
            [laser-show.animation.modulator-registry :as reg]
            [laser-show.views.components.visual-editors.spatial-canvas :as spatial-canvas]
            [laser-show.views.components.visual-editors.rotate-canvas :as rotate-canvas]
            [laser-show.views.components.visual-editors.scale-canvas :as scale-canvas]
            [laser-show.views.components.visual-editors.curve-canvas :as curve-canvas]
            [laser-show.views.components.visual-editors.hue-canvas :as hue-canvas]
            [laser-show.views.components.visual-editors.hue-shift-canvas :as hue-shift-canvas]

            [laser-show.views.components.visual-editors.oklab-hue-shift-canvas :as oklab-hue-shift-canvas]
            [laser-show.views.components.tabs :as tabs]
            [laser-show.views.components.zone-chips :as zone-chips]
            [laser-show.views.components.modulator-param-control :as mod-param]
            [clj-font-awesome.core :as fa])
  (:import [javafx.scene.canvas Canvas]
           [javafx.scene.input MouseEvent MouseButton]
           [javafx.event EventHandler]))


;; Translate Effect Visual Editor


(defn translate-visual-editor
  "Visual editor for translate effect - single draggable center point.
   
   Props:
   - :current-params - Current parameter values {:x ... :y ...} (or :params as alias)
   - :event-template - Base event map for on-point-drag (will add :param-map)
   - :param-specs - (optional) Parameter specifications for extracting bounds
   - :fx-key - (optional) Unique key for spatial canvas
   - :width, :height - (optional) Canvas dimensions, default 280x280
   - :hint-text - (optional) Hint text shown above canvas
   - :bounds - (optional) {:x-min :x-max :y-min :y-max}, defaults from param-specs or ±2.0"
  [{:keys [current-params params param-specs
           event-template fx-key width height hint-text bounds]
    :or {width 280 height 280}
    :as props}]
  {:pre [(some? event-template)]}
  (let [;; Support both :current-params and :params (alias)
        params-map (or current-params params {})

        ;; Get x/y values - could be numbers or modulator configs
        x-value (get params-map :x 0.0)
        y-value (get params-map :y 0.0)
        ;; Extract static numeric values for display and canvas (handles modulators)
        x (if (number? x-value) x-value (reg/get-static-value x-value 0.0))
        y (if (number? y-value) y-value (reg/get-static-value y-value 0.0))

        ;; Get bounds - prefer explicit :bounds, then param-specs, then defaults
        {:keys [x-min x-max y-min y-max]}
        (or bounds
            (when param-specs
              (let [x-spec (first (filter #(= :x (:key %)) param-specs))
                    y-spec (first (filter #(= :y (:key %)) param-specs))]
                {:x-min (or (:min x-spec) -2.0)
                 :x-max (or (:max x-spec) 2.0)
                 :y-min (or (:min y-spec) -2.0)
                 :y-max (or (:max y-spec) 2.0)}))
            {:x-min -2.0 :x-max 2.0 :y-min -2.0 :y-max 2.0})

        ;; Build on-point-drag event from event-template
        param-map {:center {:x :x :y :y}}
        on-point-drag-event (assoc event-template :param-map param-map)

        ;; Determine fx/key for spatial canvas
        canvas-key (or fx-key :visual-editor)

        ;; Hint text
        actual-hint (or hint-text "Drag the point to adjust translation")]

    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["card"]
     :children [{:fx/type :label
                 :text actual-hint
                 :style-class ["label-hint"]}

                {:fx/type spatial-canvas/spatial-canvas
                 :fx/key canvas-key
                 :width width
                 :height height
                 :bounds {:x-min x-min :x-max x-max
                          :y-min y-min :y-max y-max}
                 :points [{:id :center
                           :x x
                           :y y
                           :color "#4CAF50"
                           :label ""}]
                 :on-point-drag on-point-drag-event
                 :show-grid true
                 :show-axes true
                 :show-labels true}

                {:fx/type :h-box
                 :spacing 12
                 :alignment :center
                 :children [{:fx/type :label
                             :text (format "X: %.3f" x)
                             :style-class ["text-monospace"]}
                            {:fx/type :label
                             :text (format "Y: %.3f" y)
                             :style-class ["text-monospace"]}]}]}))


;; Corner Pin Effect Visual Editor


(defn corner-pin-visual-editor
  "Visual editor for corner pin effect - 4 draggable corners.
   
   Props:
   - :current-params - Current parameter values {:tl-x :tl-y :tr-x ...} (or :params as alias)
   - :event-template - Base event map for on-point-drag (will add :param-map)
   - :reset-event - (optional) Event to dispatch on reset button click
   - :param-specs - (optional) Parameter specifications for extracting bounds
   - :fx-key - (optional) Unique key for spatial canvas
   - :width, :height - (optional) Canvas dimensions, default 280x280
   - :hint-text - (optional) Hint text shown above canvas
   - :bounds - (optional) {:x-min :x-max :y-min :y-max}, defaults to ±1.0 or from param-specs"
  [{:keys [current-params params param-specs
           event-template reset-event fx-key width height hint-text bounds]
    :or {width 280 height 280}
    :as props}]
  {:pre [(some? event-template)]}
  (let [;; Support both :current-params and :params (alias)
        params-map (or current-params params {})

        ;; Get corner positions
        tl-x (get params-map :tl-x -1.0)
        tl-y (get params-map :tl-y 1.0)
        tr-x (get params-map :tr-x 1.0)
        tr-y (get params-map :tr-y 1.0)
        bl-x (get params-map :bl-x -1.0)
        bl-y (get params-map :bl-y -1.0)
        br-x (get params-map :br-x 1.0)
        br-y (get params-map :br-y -1.0)

        ;; Get bounds - prefer explicit :bounds, then param-specs, then defaults
        {:keys [x-min x-max y-min y-max]}
        (or bounds
            (when param-specs
              (let [x-spec (first (filter #(= :tl-x (:key %)) param-specs))
                    y-spec (first (filter #(= :tl-y (:key %)) param-specs))]
                {:x-min (or (:min x-spec) -2.0)
                 :x-max (or (:max x-spec) 2.0)
                 :y-min (or (:min y-spec) -2.0)
                 :y-max (or (:max y-spec) 2.0)}))
            {:x-min -1.0 :x-max 1.0 :y-min -1.0 :y-max 1.0})

        ;; Build on-point-drag event from event-template
        corner-param-map {:tl {:x :tl-x :y :tl-y}
                          :tr {:x :tr-x :y :tr-y}
                          :bl {:x :bl-x :y :bl-y}
                          :br {:x :br-x :y :br-y}}
        on-point-drag-event (assoc event-template :param-map corner-param-map)

        ;; Determine fx/key for spatial canvas
        canvas-key (or fx-key :visual-editor)

        ;; Hint text
        actual-hint (or hint-text "Drag corners to adjust perspective mapping")]

    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["visual-editor-padded"]
     :children (vec
                (concat
                 [{:fx/type :label
                   :text actual-hint
                   :style-class ["visual-editor-hint"]}

                  {:fx/type spatial-canvas/spatial-canvas
                   :fx/key canvas-key
                   :width width
                   :height height
                   :bounds {:x-min x-min :x-max x-max
                            :y-min y-min :y-max y-max}
                   :points [{:id :tl :x tl-x :y tl-y :color "#FF5722" :label "TL"}
                            {:id :tr :x tr-x :y tr-y :color "#4CAF50" :label "TR"}
                            {:id :bl :x bl-x :y bl-y :color "#2196F3" :label "BL"}
                            {:id :br :x br-x :y br-y :color "#FFC107" :label "BR"}]
                   :lines [{:from :tl :to :tr :color "#7AB8FF" :line-width 2}
                           {:from :tr :to :br :color "#7AB8FF" :line-width 2}
                           {:from :br :to :bl :color "#7AB8FF" :line-width 2}
                           {:from :bl :to :tl :color "#7AB8FF" :line-width 2}]
                   :polygon {:points [:tl :tr :br :bl] :color "#4A6FA520"}
                   :on-point-drag on-point-drag-event
                   :show-grid true
                   :show-axes true
                   :show-labels true}

                  {:fx/type :v-box
                   :spacing 4
                   :children [{:fx/type :h-box
                               :spacing 12
                               :alignment :center
                               :children [{:fx/type :label
                                           :text (format "TL: (%.2f, %.2f)" tl-x tl-y)
                                           :style-class ["visual-editor-coord-tl"]}
                                          {:fx/type :label
                                           :text (format "TR: (%.2f, %.2f)" tr-x tr-y)
                                           :style-class ["visual-editor-coord-tr"]}]}
                              {:fx/type :h-box
                               :spacing 12
                               :alignment :center
                               :children [{:fx/type :label
                                           :text (format "BL: (%.2f, %.2f)" bl-x bl-y)
                                           :style-class ["visual-editor-coord-bl"]}
                                          {:fx/type :label
                                           :text (format "BR: (%.2f, %.2f)" br-x br-y)
                                           :style-class ["visual-editor-coord-br"]}]}]}]

                 ;; Optional reset button when reset-event is provided
                 (when reset-event
                   [{:fx/type :button
                     :text "Reset to Defaults"
                     :style-class ["visual-editor-reset-btn"]
                     :on-action reset-event}])))}))


;; Rotation Effect Visual Editor


(defn rotate-visual-editor
  "Visual editor for rotation effect - circular dial.
  
  Props:
  - :current-params - Current parameter values {:angle ...} (or :params as alias)
  - :event-template - Base event map for on-angle-change (will add :param-key :value)
  - :reset-event - Event to dispatch on right-click reset
  - :fx-key - (optional) Unique key for canvas
  - :width, :height - (optional) Canvas dimensions, default 280x280
  - :hint-text - (optional) Hint text shown above canvas
  
  Modulator support (optional):
  - :enable-modulator? - Show modulator toggle button (default false)
  - :param-spec - Parameter spec for :angle (used by modulator)
  - :modulator-event-base - Base event for modulator operations"
  [{:keys [current-params params
           event-template reset-event fx-key width height hint-text
           enable-modulator? param-spec modulator-event-base]
    :or {width 280 height 280}
    :as props}]
  {:pre [(some? event-template) (some? reset-event)]}
  (let [;; Support both :current-params and :params (alias)
        params-map (or current-params params {})

        ;; Get angle value - could be number or modulator config
        angle-value (get params-map :angle 0.0)
        is-modulated? (reg/active-modulator? angle-value)
        ;; Always use get-static-value - handles both plain numbers and modulator configs
        ;; (including inactive modulators where :active? is false)
        static-angle (reg/get-static-value angle-value 0.0)

        ;; Determine fx/key for canvas
        canvas-key (or fx-key :visual-editor)

        ;; Hint text
        actual-hint (or hint-text "Drag dial to adjust rotation • Right-click to reset")

        ;; Default param spec for angle if not provided
        angle-param-spec (or param-spec {:min -360.0 :max 360.0 :default 0.0 :label "Angle"})]

    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["visual-editor-padded"]
     :children (filterv some?
                        [;; Modulator header (optional)
                         (when (and enable-modulator? modulator-event-base)
                           {:fx/type mod-param/visual-editor-modulator-header
                            :param-key :angle
                            :param-spec angle-param-spec
                            :current-value angle-value
                            :modulator-event-base modulator-event-base})

                         ;; Modulator params editor (shown ABOVE visual when modulated)
                         (when is-modulated?
                           {:fx/type mod-param/visual-editor-modulator-params
                            :param-key :angle
                            :param-spec angle-param-spec
                            :current-value angle-value
                            :modulator-event-base modulator-event-base})

                         ;; Hint text (only show when NOT modulated - modulator params replace this)
                         (when-not is-modulated?
                           {:fx/type :label
                            :text actual-hint
                            :style-class ["visual-editor-hint"]})

                         ;; Visual dial editor - always shown but disabled/preview when modulated
                         {:fx/type rotate-canvas/rotate-canvas
                          :fx/key canvas-key
                          :width width
                          :height height
                          :angle static-angle
                          :on-angle-change (when-not is-modulated? event-template)
                          :on-reset (when-not is-modulated? reset-event)}

                         ;; Value display
                         {:fx/type :h-box
                          :spacing 12
                          :alignment :center
                          :children [{:fx/type :label
                                      :text (if is-modulated?
                                              "Angle: (modulated)"
                                              (format "Angle: %.1f°" (double static-angle)))
                                      :style-class ["text-monospace"]}]}])}))


;; Scale Effect Visual Editor


(defn scale-visual-editor
  "Visual editor for scale effect - centered rectangle with handles.
  
  Props:
  - :current-params - Current parameter values {:x-scale :y-scale :uniform? ...} (or :params as alias)
  - :event-template - Base event map for on-scale-change
  - :reset-event - Event to dispatch on right-click reset
  - :fx-key - (optional) Unique key for canvas
  - :width, :height - (optional) Canvas dimensions, default 280x280
  - :hint-text - (optional) Hint text shown above canvas"
  [{:keys [current-params params
           event-template reset-event fx-key width height hint-text]
    :or {width 280 height 280}
    :as props}]
  {:pre [(some? event-template) (some? reset-event)]}
  (let [;; Support both :current-params and :params (alias)
        params-map (or current-params params {})

        ;; Get scale values
        x-scale (get params-map :x-scale 1.0)
        y-scale (get params-map :y-scale 1.0)
        uniform? (get params-map :uniform? false)

        ;; Build uniform toggle event from event-template
        on-uniform-change-event (assoc event-template :param-key :uniform?)

        ;; Determine fx/key for canvas
        canvas-key (or fx-key :visual-editor)

        ;; Hint text
        actual-hint (or hint-text "Drag handles to scale • Right-click to reset")]

    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["visual-editor-padded"]
     :children [{:fx/type :label
                 :text actual-hint
                 :style-class ["visual-editor-hint"]}

                {:fx/type scale-canvas/scale-canvas
                 :fx/key canvas-key
                 :width width
                 :height height
                 :x-scale x-scale
                 :y-scale y-scale
                 :uniform? uniform?
                 :on-scale-change event-template
                 :on-reset reset-event}

                {:fx/type :h-box
                 :spacing 12
                 :alignment :center
                 :children [{:fx/type :label
                             :text (format "X: %.2f" x-scale)
                             :style-class ["visual-editor-coord-tl"]}
                            {:fx/type :label
                             :text (format "Y: %.2f" y-scale)
                             :style-class ["visual-editor-coord-tr"]}]}

                {:fx/type :check-box
                 :text "Uniform Scale"
                 :selected uniform?
                 :style-class ["scale-uniform-checkbox"]
                 :on-selected-changed (assoc on-uniform-change-event :value (not uniform?))}]}))


;; RGB Curves Visual Editor


(def ^:private curve-tab-definitions
  "Tab definitions for the R/G/B channel tabs."
  [{:id :r :label "Red"}
   {:id :g :label "Green"}
   {:id :b :label "Blue"}])

(defn- curve-channel-color
  "Get the color for a curve channel."
  [channel]
  (case channel
    :r "#FF5555"
    :g "#55FF55"
    :b "#5555FF"
    "#FFFFFF"))

(defn- curve-editor-for-channel
  "Single curve editor for one color channel.
   
   Props:
   - :event-template - Base event map containing :domain, :entity-key, :effect-path
   - :channel - Channel keyword (:r, :g, or :b)
   - :current-points - Control points for this channel"
  [{:keys [event-template channel current-points]}]
  (let [{:keys [domain entity-key effect-path]} event-template
        color (curve-channel-color channel)
        points (or current-points [[0.0 0.0] [1.0 1.0]])
        add-event (assoc event-template
                         :event/type :chain/add-curve-point
                         :channel channel)
        update-event (assoc event-template
                            :event/type :chain/update-curve-point
                            :channel channel)
        remove-event (assoc event-template
                            :event/type :chain/remove-curve-point
                            :channel channel)
        canvas-key [domain entity-key effect-path channel]]
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :children [{:fx/type curve-canvas/curve-canvas
                 :fx/key canvas-key
                 :width 280
                 :height 280
                 :color color
                 :control-points points
                 :on-add-point add-event
                 :on-update-point update-event
                 :on-remove-point remove-event}
                {:fx/type :label
                 :text "Click to add point • Drag to move • Right-click to delete"
                 :style-class ["visual-editor-hint"]}]}))

(defn rgb-curves-visual-editor
  "Visual editor for RGB curves effect with tabbed R/G/B interface.
    
    Props:
    - :current-params - Current parameter values
    - :event-template - Base event map containing :domain, :entity-key, :effect-path
    - :dialog-data - Dialog state containing UI modes and active channel"
  [{:keys [current-params event-template dialog-data]}]
  (let [{:keys [domain entity-key effect-path]} event-template

        ;; Get active channel from dialog state
        active-channel (get-in dialog-data [:ui-modes effect-path :active-curve-channel] :r)

        ;; Get control points for each channel (normalized 0.0-1.0)
        r-points (get current-params :r-curve-points [[0.0 0.0] [1.0 1.0]])
        g-points (get current-params :g-curve-points [[0.0 0.0] [1.0 1.0]])
        b-points (get current-params :b-curve-points [[0.0 0.0] [1.0 1.0]])

        ;; Get points for active channel
        active-points (case active-channel
                        :r r-points
                        :g g-points
                        :b b-points
                        r-points)

        tab-change-event (assoc event-template
                                :event/type :chain/set-active-curve-channel)]
    {:fx/type :v-box
     :spacing 0
     :style-class ["visual-editor"]
     :children [{:fx/type tabs/styled-tab-bar
                 :tabs curve-tab-definitions
                 :active-tab active-channel
                 :on-tab-change tab-change-event}
                {:fx/type curve-editor-for-channel
                 :event-template event-template
                 :channel active-channel
                 :current-points active-points}]}))
;; Zone Selector Visual Editor - Keyframeable Zone Selection


(defn- beat-to-x
  "Convert beat position to canvas X coordinate."
  ^double [^double beat ^double loop-length ^double width ^double padding]
  (let [usable-width (- width (* 2 padding))]
    (+ padding (* (/ beat loop-length) usable-width))))

(defn- x-to-beat
  "Convert canvas X coordinate to beat position."
  ^double [^double x ^double loop-length ^double width ^double padding]
  (let [usable-width (- width (* 2 padding))
        clamped-x (max padding (min (- width padding) x))]
    (* (/ (- clamped-x padding) usable-width) loop-length)))

(defn- zone-color
  "Get the color for a zone-id from zone groups list."
  [zone-groups zone-id]
  (or (some #(when (= (:id %) zone-id) (:color %)) zone-groups)
      "#666666"))

(defn- zone-name
  "Get the name for a zone-id from zone groups list."
  [zone-groups zone-id]
  (or (some #(when (= (:id %) zone-id) (:name %)) zone-groups)
      (if (= zone-id :all) "All" (str zone-id))))

(defn- compute-zone-segments
  "Compute zone segments from keyframes for rendering.
   Each segment spans from one keyframe beat to the next.
   Returns: [{:start-beat :end-beat :zone-id :keyframe-idx} ...]"
  [keyframes base-zone loop-length]
  (if (empty? keyframes)
    ;; No keyframes - single segment covering entire timeline
    [{:start-beat 0.0
      :end-beat loop-length
      :zone-id base-zone
      :keyframe-idx nil}]
    ;; Build segments from keyframes
    (let [sorted-kfs (vec (sort-by :beat keyframes))
          first-kf (first sorted-kfs)
          ;; Add base segment before first keyframe if it doesn't start at 0
          initial-segments (when (> (:beat first-kf) 0.0)
                             [{:start-beat 0.0
                               :end-beat (:beat first-kf)
                               :zone-id base-zone
                               :keyframe-idx nil}])
          ;; Create segments for each keyframe
          kf-segments (for [i (range (count sorted-kfs))]
                        (let [kf (nth sorted-kfs i)
                              next-beat (if (< i (dec (count sorted-kfs)))
                                          (:beat (nth sorted-kfs (inc i)))
                                          loop-length)]
                          {:start-beat (:beat kf)
                           :end-beat next-beat
                           :zone-id (:value kf)
                           :keyframe-idx i}))]
      (vec (concat initial-segments kf-segments)))))

(defn- find-segment-at-x
  "Find which segment contains the given x coordinate."
  [segments x loop-length width padding]
  (let [beat (x-to-beat x loop-length width padding)]
    (first (filter #(and (>= beat (:start-beat %))
                         (< beat (:end-beat %)))
                   segments))))

(defn zone-timeline-canvas
  "Canvas showing zone keyframes as colored segments over time.
   
   Unlike the standard keyframe-timeline which shows diamond markers,
   this shows filled rectangular segments because zone values use
   step interpolation - the zone holds until the next keyframe.
   
   Props:
   - :width, :height - Canvas dimensions (default 400x80)
   - :zone-groups - List of zone groups with :id, :name, :color
   - :keyframes - [{:beat 0.0 :value :left} ...]
   - :base-value - Default zone when no keyframe applies
   - :loop-length - Total timeline length in beats
   - :selected-idx - Index of selected keyframe segment
   - :on-segment-click - Event when segment is clicked
   - :on-add-keyframe - Event to add keyframe at beat
   - :on-delete-keyframe - Event to delete keyframe
   - :on-select - Event when keyframe is selected"
  [{:keys [width height zone-groups keyframes base-value loop-length
           selected-idx on-segment-click on-add-keyframe on-delete-keyframe on-select]
    :or {width 400 height 80 loop-length 8.0}}]
  (let [padding 20.0
        bar-y 30.0
        bar-height 30.0
        segments (compute-zone-segments keyframes base-value loop-length)]

    {:fx/type fx/ext-on-instance-lifecycle
     :on-created
     (fn [^Canvas canvas]
       (let [gc (.getGraphicsContext2D canvas)
             ;; Internal state
             hover-segment-atom (atom nil)
             selected-atom (atom selected-idx)]

         (letfn [(render! []
                   (let [hover-seg @hover-segment-atom
                         sel-idx @selected-atom]
                     ;; Background
                     (.setFill gc (javafx.scene.paint.Color/web "#1E1E1E"))
                     (.fillRect gc 0 0 width height)

                     ;; Draw beat markers
                     (.setStroke gc (javafx.scene.paint.Color/web "#444444"))
                     (.setLineWidth gc 1.0)
                     (.setFill gc (javafx.scene.paint.Color/web "#888888"))
                     (.setFont gc (javafx.scene.text.Font/font "System" 9.0))
                     (.setTextAlign gc javafx.scene.text.TextAlignment/CENTER)

                     (doseq [beat (range (inc (int loop-length)))]
                       (let [x (beat-to-x beat loop-length width padding)]
                         (.strokeLine gc x (- bar-y 5) x (+ bar-y bar-height 5))
                         (.fillText gc (str beat) x (- bar-y 10))))

                     ;; Draw zone segments
                     (doseq [seg segments]
                       (let [x1 (beat-to-x (:start-beat seg) loop-length width padding)
                             x2 (beat-to-x (:end-beat seg) loop-length width padding)
                             seg-width (- x2 x1)
                             color (zone-color zone-groups (:zone-id seg))
                             is-selected? (and (:keyframe-idx seg)
                                               (= (:keyframe-idx seg) sel-idx))
                             is-hovered? (= seg hover-seg)]
                         ;; Segment background
                         (.setFill gc (javafx.scene.paint.Color/web color (if is-hovered? 0.9 0.7)))
                         (.fillRoundRect gc x1 bar-y seg-width bar-height 4 4)

                         ;; Selection border
                         (when is-selected?
                           (.setStroke gc (javafx.scene.paint.Color/web "#FFFFFF"))
                           (.setLineWidth gc 2.0)
                           (.strokeRoundRect gc x1 bar-y seg-width bar-height 4 4))

                         ;; Zone name label (if segment wide enough)
                         (when (> seg-width 40)
                           (let [name (zone-name zone-groups (:zone-id seg))
                                 center-x (+ x1 (/ seg-width 2))
                                 center-y (+ bar-y (/ bar-height 2) 4)]
                             (.setFill gc (javafx.scene.paint.Color/web "#FFFFFF"))
                             (.setFont gc (javafx.scene.text.Font/font "System" javafx.scene.text.FontWeight/BOLD 10.0))
                             (.fillText gc name center-x center-y)))))

                     ;; Draw keyframe markers (small triangles above segments)
                     (doseq [[idx kf] (map-indexed vector (or keyframes []))]
                       (let [x (beat-to-x (:beat kf) loop-length width padding)
                             is-selected? (= idx sel-idx)]
                         (.setFill gc (javafx.scene.paint.Color/web (if is-selected? "#FFD700" "#FFFFFF")))
                         (.fillPolygon gc
                                       (double-array [(- x 5) x (+ x 5)])
                                       (double-array [(- bar-y 2) (- bar-y 8) (- bar-y 2)])
                                       3)))))]

           ;; Mouse click handler
           (.setOnMouseClicked
            canvas
            (reify EventHandler
              (handle [_ e]
                (let [mx (.getX e)
                      my (.getY e)
                      button (.getButton e)
                      seg (find-segment-at-x segments mx loop-length width padding)]
                  (cond
                    ;; Right-click on segment with keyframe - delete it
                    (and (= button MouseButton/SECONDARY)
                         seg
                         (:keyframe-idx seg)
                         on-delete-keyframe)
                    (do
                      (events/dispatch! (assoc on-delete-keyframe :keyframe-idx (:keyframe-idx seg)))
                      (render!))

                    ;; Left-click on segment - select and open zone picker
                    (and (= button MouseButton/PRIMARY) seg)
                    (do
                      (when (:keyframe-idx seg)
                        (reset! selected-atom (:keyframe-idx seg))
                        (when on-select
                          (events/dispatch! (assoc on-select :keyframe-idx (:keyframe-idx seg)))))
                      (when on-segment-click
                        (events/dispatch! (assoc on-segment-click
                                                 :beat (:start-beat seg)
                                                 :keyframe-idx (:keyframe-idx seg)
                                                 :current-zone (:zone-id seg))))
                      (render!))

                    ;; Double-click to add keyframe
                    (and (= button MouseButton/PRIMARY)
                         (= (.getClickCount e) 2)
                         on-add-keyframe)
                    (let [beat (x-to-beat mx loop-length width padding)]
                      (events/dispatch! (assoc on-add-keyframe :beat beat))
                      (render!)))))))

           ;; Mouse move for hover
           (.setOnMouseMoved
            canvas
            (reify EventHandler
              (handle [_ e]
                (let [mx (.getX e)
                      my (.getY e)
                      seg (when (and (>= my bar-y) (<= my (+ bar-y bar-height)))
                            (find-segment-at-x segments mx loop-length width padding))]
                  (when (not= seg @hover-segment-atom)
                    (reset! hover-segment-atom seg)
                    (render!))
                  (if seg
                    (.setStyle canvas "-fx-cursor: hand;")
                    (.setStyle canvas "-fx-cursor: default;"))))))

           ;; Mouse exit - clear hover
           (.setOnMouseExited
            canvas
            (reify EventHandler
              (handle [_ e]
                (when @hover-segment-atom
                  (reset! hover-segment-atom nil)
                  (render!)))))

           ;; Initial render
           (render!))))

     :desc {:fx/type :canvas
            :width width
            :height height
            :style "-fx-cursor: default;"}}))

(defn- zone-selector-dropdown
  "Dropdown for selecting default/base zone.
   Props:
   - :zone-groups - List of available zone groups
   - :selected-zone - Currently selected zone-group-id
   - :on-change - Event to dispatch on selection change"
  [{:keys [zone-groups selected-zone on-change]}]
  (let [;; Build items list with :all option first
        all-option {:id :all :name "All" :color "#888888"}
        items (into [all-option] zone-groups)
        selected-item (or (first (filter #(= (:id %) selected-zone) items))
                          all-option)]
    {:fx/type :combo-box
     :value selected-item
     :items items
     :button-cell (fn [item]
                    {:text (or (:name item) "Select Zone")
                     :graphic (when item
                                {:fx/type :circle
                                 :radius 6
                                 :fill (or (:color item) "#666666")})})
     :cell-factory {:fx/cell-type :list-cell
                    :describe (fn [item]
                                {:text (or (:name item) "")
                                 :graphic (when item
                                            {:fx/type :circle
                                             :radius 6
                                             :fill (or (:color item) "#666666")})})}
     :on-value-changed (fn [new-item]
                         (when (and on-change new-item)
                           (events/dispatch! (assoc on-change :value (:id new-item)))))}))

(defn zone-selector-visual-editor
  "Visual editor for zone-selector effect with keyframeable zone selection.
   
   Props:
   - :fx/context - cljfx context (required for zone groups subscription)
   - :current-params - {:target-zone :all, :keyframes [...], :loop-length 8.0}
   - :event-template - Base event with :domain, :entity-key, :effect-path"
  [{:keys [fx/context current-params event-template]}]
  (let [{:keys [domain entity-key effect-path]} event-template
        zone-groups (fx/sub-ctx context subs/zone-groups-list)

        ;; Extract params
        target-zone (get current-params :target-zone :all)
        keyframes (get current-params :keyframes [])
        loop-length (get current-params :loop-length 8.0)

        ;; Build event templates for sub-components
        base-zone-change-event {:event/type :chain/update-param
                                :domain domain
                                :entity-key entity-key
                                :effect-path effect-path
                                :param-key :target-zone}

        add-keyframe-event {:event/type :keyframe/add-zone-keyframe
                            :domain domain
                            :entity-key entity-key
                            :effect-path effect-path}

        update-zone-event {:event/type :keyframe/update-zone-value
                           :domain domain
                           :entity-key entity-key
                           :effect-path effect-path}

        delete-keyframe-event {:event/type :keyframe/delete-zone-keyframe
                               :domain domain
                               :entity-key entity-key
                               :effect-path effect-path}

        select-keyframe-event {:event/type :keyframe/select-zone-keyframe
                               :domain domain
                               :entity-key entity-key
                               :effect-path effect-path}

        loop-length-event {:event/type :keyframe/set-zone-loop-length
                           :domain domain
                           :entity-key entity-key
                           :effect-path effect-path}]

    {:fx/type :v-box
     :spacing 12
     :padding 12
     :style-class ["card"]
     :children [;; Header
                {:fx/type :label
                 :text "Zone Selector"
                 :style-class ["label-section-header"]}

                ;; Default zone dropdown
                {:fx/type :h-box
                 :spacing 8
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text "Default Zone:"
                             :style-class ["label-hint"]}
                            {:fx/type zone-selector-dropdown
                             :zone-groups zone-groups
                             :selected-zone target-zone
                             :on-change base-zone-change-event}]}

                ;; Timeline section
                {:fx/type :v-box
                 :spacing 8
                 :children [{:fx/type :h-box
                             :spacing 8
                             :alignment :center-left
                             :children [{:fx/type :label
                                         :text "Timeline:"
                                         :style-class ["label-hint"]}
                                        {:fx/type :region
                                         :h-box/hgrow :always}
                                        {:fx/type :label
                                         :text "Loop:"
                                         :style-class ["label-hint"]}
                                        {:fx/type :spinner
                                         :style-class ["spinner-small"]
                                         :value-factory {:fx/type :double-spinner-value-factory
                                                         :min 1.0
                                                         :max 64.0
                                                         :value loop-length
                                                         :amount-to-step-by 1.0}
                                         :editable true
                                         :pref-width 70
                                         :on-value-changed (assoc loop-length-event :fx/event-key :value)}
                                        {:fx/type :label
                                         :text "beats"
                                         :style-class ["label-hint"]}]}

                            ;; Zone timeline canvas
                            {:fx/type zone-timeline-canvas
                             :width 380
                             :height 80
                             :zone-groups zone-groups
                             :keyframes keyframes
                             :base-value target-zone
                             :loop-length loop-length
                             :selected-idx nil
                             :on-segment-click update-zone-event
                             :on-add-keyframe add-keyframe-event
                             :on-delete-keyframe delete-keyframe-event
                             :on-select select-keyframe-event}]}

                ;; Add keyframe controls
                {:fx/type :h-box
                 :spacing 8
                 :alignment :center-left
                 :children [{:fx/type :button
                             :text "Add Keyframe"
                             :style-class ["btn-secondary" "btn-small"]
                             :graphic {:fx/type fa/icon :name :plus :size 10}
                             :on-action (assoc add-keyframe-event :beat 0.0 :value target-zone)}
                            {:fx/type :region
                             :h-box/hgrow :always}
                            {:fx/type :label
                             :text "Click timeline to add • Right-click to delete"
                             :style-class ["label-hint"]
                             :style "-fx-font-size: 9;"}]}]}))




;; Hue Slider Visual Editor (for Set Hue effect - 0-360 range)


(defn hue-visual-editor
  "Visual editor for set-hue effect - horizontal gradient slider.
   
   Shows a horizontal bar with the full hue spectrum from 0° to 360°.
   Dragging adjusts the hue value in real-time.
   
   Props:
   - :current-params - Current parameter values {:hue ...}
   - :event-template - Base event for on-drag (will add :param-key :value)
   - :fx-key - (optional) Unique key for canvas (should NOT include current value)
   - :hint-text - (optional) Hint text above canvas
   
   Modulator support (optional):
   - :enable-modulator? - Show modulator toggle button (default false)
   - :param-spec - Parameter spec for :hue (used by modulator)
   - :modulator-event-base - Base event for modulator operations"
  [{:keys [current-params event-template fx-key hint-text
           enable-modulator? param-spec modulator-event-base]}]
  (let [params-map (or current-params {})
        ;; Get hue value - could be number or modulator config
        hue-value (get params-map :hue 0.0)
        is-modulated? (reg/active-modulator? hue-value)
        ;; Always use get-static-value - handles both plain numbers and modulator configs
        ;; (including inactive modulators where :active? is false)
        static-hue (reg/get-static-value hue-value 0.0)
        actual-hint (or hint-text "Drag to select hue")
        ;; Use a stable key that does NOT change based on current value
        ;; This prevents canvas recreation during dragging
        canvas-key (or fx-key [:hue-editor])
        ;; Default param spec for hue if not provided
        hue-param-spec (or param-spec {:min 0.0 :max 360.0 :default 0.0 :label "Hue"})]
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["visual-editor-padded"]
     :children (filterv some?
                        [;; Modulator header (optional)
                         (when (and enable-modulator? modulator-event-base)
                           {:fx/type mod-param/visual-editor-modulator-header
                            :param-key :hue
                            :param-spec hue-param-spec
                            :current-value hue-value
                            :modulator-event-base modulator-event-base})

                         ;; Modulator params editor (shown ABOVE visual when modulated)
                         (when is-modulated?
                           {:fx/type mod-param/visual-editor-modulator-params
                            :param-key :hue
                            :param-spec hue-param-spec
                            :current-value hue-value
                            :modulator-event-base modulator-event-base})

                         ;; Hint text (only when NOT modulated)
                         (when-not is-modulated?
                           {:fx/type :label
                            :text actual-hint
                            :style-class ["visual-editor-hint"]})

                         ;; Visual hue slider - always shown (disabled when modulated)
                         {:fx/type hue-canvas/hue-canvas
                          :fx/key canvas-key
                          :hue static-hue
                          :on-hue-change (when-not is-modulated? event-template)}

                         ;; Value display
                         {:fx/type :h-box
                          :spacing 12
                          :alignment :center
                          :children [{:fx/type :label
                                      :text (if is-modulated?
                                              "Hue: (modulated)"
                                              (format "Hue: %.1f°" (double static-hue)))
                                      :style-class ["text-monospace"]}]}])}))


;; Hue Shift Strip Visual Editor (for Hue Shift effect - shows input/output transformation)


(defn hue-shift-strip-visual-editor
  "Visual editor for hue shift effect - shows input/output hue transformation.
   
   Displays two horizontal strips:
   - Top: Static input hue spectrum (0° to 360°)
   - Bottom: Shifted output hue spectrum
   
   Drag left/right to adjust the shift amount (-180° to +180°).
   
   Props:
   - :current-params - Current parameter values {:degrees ...}
   - :event-template - Base event for on-drag (will add :param-key :value)
   - :fx-key - (optional) Unique key for canvas (should NOT include current value)
   - :hint-text - (optional) Hint text above canvas
   
   Modulator support (optional):
   - :enable-modulator? - Show modulator toggle button (default false)
   - :param-spec - Parameter spec for :degrees (used by modulator)
   - :modulator-event-base - Base event for modulator operations"
  [{:keys [current-params event-template fx-key hint-text
           enable-modulator? param-spec modulator-event-base]}]
  (let [params-map (or current-params {})
        ;; Get degrees value - could be number or modulator config
        degrees-value (get params-map :degrees 0.0)
        is-modulated? (reg/active-modulator? degrees-value)
        ;; Always use get-static-value - handles both plain numbers and modulator configs
        ;; (including inactive modulators where :active? is false)
        static-degrees (reg/get-static-value degrees-value 0.0)
        actual-hint (or hint-text "Drag left/right to shift hue")
        ;; Use a stable key that does NOT change based on current value
        ;; This prevents canvas recreation during dragging
        canvas-key (or fx-key [:hue-shift-editor])
        ;; Default param spec for degrees if not provided
        degrees-param-spec (or param-spec {:min -180.0 :max 180.0 :default 0.0 :label "Degrees"})]
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["visual-editor-padded"]
     :children (filterv some?
                        [;; Modulator header (optional)
                         (when (and enable-modulator? modulator-event-base)
                           {:fx/type mod-param/visual-editor-modulator-header
                            :param-key :degrees
                            :param-spec degrees-param-spec
                            :current-value degrees-value
                            :modulator-event-base modulator-event-base})

                         ;; Hint text (only when NOT modulated)
                         (when-not is-modulated?
                           {:fx/type :label
                            :text actual-hint
                            :style-class ["visual-editor-hint"]})

                         ;; Visual hue shift strips - always shown (disabled when modulated)
                         {:fx/type hue-shift-canvas/hue-shift-canvas
                          :fx/key canvas-key
                          :degrees static-degrees
                          :on-degrees-change (when-not is-modulated? event-template)}

                         ;; Modulator params editor (shown below visual when modulated)
                         (when is-modulated?
                           {:fx/type mod-param/visual-editor-modulator-params
                            :param-key :degrees
                            :param-spec degrees-param-spec
                            :current-value degrees-value
                            :modulator-event-base modulator-event-base})

                         ;; Value display
                         {:fx/type :h-box
                          :spacing 12
                          :alignment :center
                          :children [{:fx/type :label
                                      :text (if is-modulated?
                                              "Shift: (modulated)"
                                              (format "Shift: %.1f°" (double static-degrees)))
                                      :style-class ["text-monospace"]}]}])}))


;; Set Color Picker Visual Editor


(defn set-color-picker-visual-editor
  "Visual editor for set-color effect - color picker with preview swatch.
  
  Presents the three separate :red, :green, :blue parameters as a unified
  color picker interface. When the color is changed, dispatches three
  separate events to update each channel.
  
  Props:
  - :current-params - Current parameter values {:red :green :blue ...}
  - :event-template - Base event for param changes (will add :param-key :value for each channel)
  - :fx-key - (optional) Unique key for color picker
  - :hint-text - (optional) Hint text above picker"
  [{:keys [current-params event-template fx-key hint-text]}]
  (let [params-map (or current-params {})
        red (get params-map :red 1.0)
        green (get params-map :green 1.0)
        blue (get params-map :blue 1.0)
        actual-hint (or hint-text "Click to select color")
        ;; Create JavaFX Color from normalized RGB values
        current-color (javafx.scene.paint.Color/color
                       (max 0.0 (min 1.0 (double red)))
                       (max 0.0 (min 1.0 (double green)))
                       (max 0.0 (min 1.0 (double blue)))
                       1.0)
        picker-key (or fx-key [:set-color-picker])]
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["visual-editor-padded"]
     :children [{:fx/type :label
                 :text actual-hint
                 :style-class ["visual-editor-hint"]}

                ;; Preview swatch showing current color
                {:fx/type :h-box
                 :spacing 12
                 :alignment :center-left
                 :children [{:fx/type :region
                             :style (str "-fx-background-color: rgb("
                                         (int (* red 255)) ","
                                         (int (* green 255)) ","
                                         (int (* blue 255)) ");"
                                         "-fx-min-width: 60;"
                                         "-fx-min-height: 40;"
                                         "-fx-max-width: 60;"
                                         "-fx-max-height: 40;"
                                         "-fx-border-color: #555555;"
                                         "-fx-border-width: 1;")}
                            {:fx/type :v-box
                             :spacing 2
                             :children [{:fx/type :label
                                         :text (format "R: %.0f%%" (* red 100))
                                         :style-class ["text-monospace" "text-small"]}
                                        {:fx/type :label
                                         :text (format "G: %.0f%%" (* green 100))
                                         :style-class ["text-monospace" "text-small"]}
                                        {:fx/type :label
                                         :text (format "B: %.0f%%" (* blue 100))
                                         :style-class ["text-monospace" "text-small"]}]}]}

                ;; Color picker - when changed, dispatches events for each channel
                {:fx/type :color-picker
                 :fx/key picker-key
                 :value current-color
                 :style "-fx-color-label-visible: false;"
                 :on-action event-template}]}))





(defn oklab-hue-shift-visual-editor
  "Visual editor for Oklab hue shift effect.
   
   Displays input/output strips in Oklab space.
   
   Props:
   - :current-params - {:degrees ...}
   - :event-template - Base event for on-drag
   - :fx-key - (optional) Unique key
   - :hint-text - (optional) Hint text
   
   Modulator support:
   - :enable-modulator?
   - :param-spec
   - :modulator-event-base"
  [{:keys [current-params event-template fx-key hint-text
           enable-modulator? param-spec modulator-event-base]}]
  (let [params-map (or current-params {})
        degrees-value (get params-map :degrees 0.0)
        is-modulated? (reg/active-modulator? degrees-value)
        static-degrees (reg/get-static-value degrees-value 0.0)
        actual-hint (or hint-text "Drag left/right to shift hue")
        canvas-key (or fx-key [:oklab-hue-shift-editor])
        degrees-param-spec (or param-spec {:min -180.0 :max 180.0 :default 0.0 :label "Degrees"})]
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["visual-editor-padded"]
     :children (filterv some?
                        [(when (and enable-modulator? modulator-event-base)
                           {:fx/type mod-param/visual-editor-modulator-header
                            :param-key :degrees
                            :param-spec degrees-param-spec
                            :current-value degrees-value
                            :modulator-event-base modulator-event-base})

                         (when-not is-modulated?
                           {:fx/type :label
                            :text actual-hint
                            :style-class ["visual-editor-hint"]})

                         {:fx/type oklab-hue-shift-canvas/oklab-hue-shift-canvas
                          :fx/key canvas-key
                          :degrees static-degrees
                          :on-degrees-change (when-not is-modulated? event-template)}

                         (when is-modulated?
                           {:fx/type mod-param/visual-editor-modulator-params
                            :param-key :degrees
                            :param-spec degrees-param-spec
                            :current-value degrees-value
                            :modulator-event-base modulator-event-base})

                         {:fx/type :h-box
                          :spacing 12
                          :alignment :center
                          :children [{:fx/type :label
                                      :text (if is-modulated?
                                              "Shift: (modulated)"
                                              (format "Shift: %.1f°" (double static-degrees)))
                                      :style-class ["text-monospace"]}]}])}))
