(ns laser-show.views.components.visual-editors.keyframe-modulator-panel
  "Panel component for keyframe modulator controls.
   
   Supports two types of keyframe modulators:
   1. Time-based keyframes (default) - animate effect params over time
   2. Spatial keyframes - modulate params based on point position
   
   Time-based Layout:
   ┌─────────────────────────────────────────────────────────────────┐
   │ KEYFRAME ANIMATION                              [Enable] Toggle │
   ├─────────────────────────────────────────────────────────────────┤
   │ Period: [4.0] beats  ▼     Loop Mode: [Loop] ▼                 │
   ├─────────────────────────────────────────────────────────────────┤
   │ Timeline:                                                       │
   │ ┌─────────────────────────────────────────────────────────────┐│
   │ │  ◆─────────────────◇─────────────────────────────────◆     ││
   │ └─────────────────────────────────────────────────────────────┘│
   │ Selected: Keyframe 1 @ 0%      [+ Add] [- Delete] [Copy Params]│
   └─────────────────────────────────────────────────────────────────┘
   
   Spatial Layout (no enable checkbox - selecting type is activation):
   ┌─────────────────────────────────────────────────────────────────┐
   │ SPATIAL KEYFRAME                                                │
   ├─────────────────────────────────────────────────────────────────┤
   │ Axis: [Point Index ▼]    [✓ Normalize]                          │
   ├─────────────────────────────────────────────────────────────────┤
   │ Timeline Canvas                                                 │
   ├─────────────────────────────────────────────────────────────────┤
   │ Selected: Keyframe 1 @ 0%   Value: [0.0]   Interp: [Linear ▼]  │
   └─────────────────────────────────────────────────────────────────┘"
  (:require
   [laser-show.views.components.visual-editors.keyframe-timeline :as timeline]))


;; Helper Functions


(defn- format-position
  "Format keyframe position as percentage string."
  [position]
  (format "%.0f%%" (* 100 position)))

(defn- spatial-keyframe-modulator?
  "Returns true if the modulator is a spatial keyframe type."
  [modulator-config]
  (= (:type modulator-config) :spatial-keyframe))

(def ^:private axis-options
  "Available axis options for spatial keyframes."
  [:point-index :pos-x :pos-y :radial :angle])

(defn- axis-display-name
  "Convert axis keyword to user-friendly display name."
  [axis]
  (case axis
    :point-index "Point Index"
    :pos-x "Position X"
    :pos-y "Position Y"
    :radial "Radial"
    :angle "Angle"
    (name axis)))

(def ^:private interpolation-modes
  "Available interpolation modes for spatial keyframes."
  [:linear :exp-decay :exp-grow :step])

(defn- interpolation-display-name
  "Convert interpolation keyword to user-friendly display name."
  [mode]
  (case mode
    :linear "Linear"
    :exp-decay "Ease Out"
    :exp-grow "Ease In"
    :step "Step"
    (name mode)))


;; Sub-components


(defn- header-row
  "Header with title and optional enable toggle.
   For spatial keyframes, no checkbox is shown - selecting the modulator type
   is the activation. For time-based, shows enable toggle."
  [{:keys [enabled? on-toggle-event spatial?]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 10
   :style-class "keyframe-panel-header"
   :children (filterv some?
              [{:fx/type :label
                :text (if spatial? "SPATIAL KEYFRAME" "KEYFRAME ANIMATION")
                :style-class "header-section"}
               {:fx/type :region :h-box/hgrow :always}
               ;; Only show enable checkbox for time-based keyframes
               ;; Spatial keyframes are activated by selecting the type from dropdown
               (when-not spatial?
                 {:fx/type :check-box
                  :text "Enable"
                  :selected (boolean enabled?)
                  :on-selected-changed (assoc on-toggle-event
                                              :enabled? (not enabled?))})])})

(defn- settings-row
  "Row with period, time-unit, and loop-mode controls."
  [{:keys [period time-unit loop-mode on-settings-event enabled?]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 15
   :style-class "keyframe-panel-settings"
   :disable (not enabled?)
   :children [;; Period
              {:fx/type :h-box
               :alignment :center-left
               :spacing 5
               :children [{:fx/type :label
                           :text "Period:"}
                          {:fx/type :spinner
                           :pref-width 80
                           :value-factory {:fx/type :double-spinner-value-factory
                                           :min 0.25
                                           :max 64.0
                                           :amount-to-step-by 0.25
                                           :value (or period 4.0)}
                           :on-value-changed (assoc on-settings-event :setting-key :period)}]}
              
              ;; Time unit
              {:fx/type :h-box
               :alignment :center-left
               :spacing 5
               :children [{:fx/type :combo-box
                           :pref-width 90
                           :value (or time-unit :beats)
                           :items [:beats :seconds]
                           :button-cell (fn [item]
                                          {:text (name item)})
                           :cell-factory {:fx/cell-type :list-cell
                                          :describe (fn [item]
                                                      {:text (name item)})}
                           :on-value-changed (assoc on-settings-event :setting-key :time-unit)}]}
              
              ;; Loop mode
              {:fx/type :h-box
               :alignment :center-left
               :spacing 5
               :children [{:fx/type :label
                           :text "Loop:"}
                          {:fx/type :combo-box
                           :pref-width 90
                           :value (or loop-mode :loop)
                           :items [:loop :once]
                           :button-cell (fn [item]
                                          {:text (name item)})
                           :cell-factory {:fx/cell-type :list-cell
                                          :describe (fn [item]
                                                      {:text (name item)})}
                           :on-value-changed (assoc on-settings-event :setting-key :loop-mode)}]}]})

(defn- spatial-settings-row
  "Settings row for spatial keyframe modulator.
   Shows axis dropdown and conditional normalize checkbox."
  [{:keys [axis normalize? on-axis-change on-normalize-change enabled?]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 15
   :style-class "keyframe-panel-settings"
   :disable (not enabled?)
   :children (filterv some?
              [;; Axis selector
               {:fx/type :h-box
                :alignment :center-left
                :spacing 5
                :children [{:fx/type :label
                            :text "Axis:"}
                           {:fx/type :combo-box
                            :pref-width 120
                            :value (or axis :point-index)
                            :items axis-options
                            :button-cell (fn [item]
                                           {:text (axis-display-name item)})
                            :cell-factory {:fx/cell-type :list-cell
                                           :describe (fn [item]
                                                       {:text (axis-display-name item)})}
                            :on-value-changed on-axis-change}]}
               
               ;; Normalize checkbox - only visible when axis is :radial
               (when (= axis :radial)
                 {:fx/type :check-box
                  :text "Normalize"
                  :selected (boolean normalize?)
                  :on-selected-changed on-normalize-change})])})

(defn- spatial-value-row
  "Row for editing selected spatial keyframe value and interpolation.
   Shows single value spinner and interpolation dropdown."
  [{:keys [keyframes selected-idx on-value-change on-interpolation-change enabled?]}]
  (let [selected-kf (when (and selected-idx
                               (>= selected-idx 0)
                               (< selected-idx (count keyframes)))
                      (nth keyframes selected-idx))]
    {:fx/type :h-box
     :alignment :center-left
     :spacing 15
     :style-class "keyframe-panel-actions"
     :disable (not enabled?)
     :children (if selected-kf
                 [;; Selected keyframe info
                  {:fx/type :label
                   :text (str "Keyframe " (inc selected-idx)
                              " @ " (format-position (:position selected-kf)))
                   :style-class "label-secondary"}
                  
                  ;; Value spinner
                  {:fx/type :h-box
                   :alignment :center-left
                   :spacing 5
                   :children [{:fx/type :label
                               :text "Value:"}
                              {:fx/type :spinner
                               :pref-width 90
                               :editable true
                               :value-factory {:fx/type :double-spinner-value-factory
                                               :min -1000.0
                                               :max 1000.0
                                               :amount-to-step-by 0.1
                                               :value (or (:value selected-kf) 0.0)}
                               :on-value-changed (assoc on-value-change
                                                        :keyframe-idx selected-idx)}]}
                  
                  ;; Interpolation selector
                  {:fx/type :h-box
                   :alignment :center-left
                   :spacing 5
                   :children [{:fx/type :label
                               :text "Interp:"}
                              {:fx/type :combo-box
                               :pref-width 100
                               :value (or (:interpolation selected-kf) :linear)
                               :items interpolation-modes
                               :button-cell (fn [item]
                                              {:text (interpolation-display-name item)})
                               :cell-factory {:fx/cell-type :list-cell
                                              :describe (fn [item]
                                                          {:text (interpolation-display-name item)})}
                               :on-value-changed (assoc on-interpolation-change
                                                        :keyframe-idx selected-idx)}]}]
                 ;; No keyframe selected
                 [{:fx/type :label
                   :text "Click timeline to select"
                   :style-class "label-secondary"}])}))

(defn- timeline-row
  "Row containing the timeline canvas."
  [{:keys [keyframes selected-idx current-phase
           on-select on-add on-move on-delete enabled?]}]
  {:fx/type :v-box
   :style-class "keyframe-panel-timeline"
   :children [{:fx/type :label
               :text "Timeline:"
               :style-class "label-secondary"}
              {:fx/type timeline/keyframe-timeline
               :width 450
               :height 60
               :keyframes keyframes
               :selected-idx selected-idx
               :current-phase current-phase
               :on-select on-select
               :on-add on-add
               :on-move on-move
               :on-delete on-delete}]})

(defn- actions-row
  "Row with keyframe info."
  [{:keys [keyframes selected-idx enabled?]}]
  (let [selected-kf (when (and selected-idx
                               (>= selected-idx 0)
                               (< selected-idx (count keyframes)))
                      (nth keyframes selected-idx))]
    {:fx/type :h-box
     :alignment :center-left
     :spacing 10
     :style-class "keyframe-panel-actions"
     :disable (not enabled?)
     :children [;; Selected keyframe info
                {:fx/type :label
                 :text (if selected-kf
                         (str "Selected: Keyframe " (inc selected-idx)
                              " @ " (format-position (:position selected-kf)))
                         "Click timeline to select")
                 :style-class "label-secondary"}]}))


;; Main Panel Component


(defn keyframe-modulator-panel
  "Panel containing timeline and keyframe controls.
   
   Supports both time-based and spatial keyframe modulators.
   Type is determined by :type key in keyframe-modulator config.
   
   For spatial keyframes:
   - No enable checkbox is shown (selecting the type from dropdown activates it)
   - Controls are always visible
   - Uses :active? key for state
   
   For time-based keyframes:
   - Shows enable checkbox
   - Uses :enabled? key for state
   
   Props:
   - :keyframe-modulator - The keyframe modulator config map (or nil if not initialized)
   - :domain - :effect-chains or :cue-chains
   - :entity-key - [col row] or projector-id
   - :effect-path - Path to effect within chain
   - :param-key - Parameter key (required for spatial keyframes, e.g. :hue)
   - :current-phase - Current playback position for preview (optional, time-based only)"
  [{:keys [keyframe-modulator domain entity-key effect-path param-key current-phase]}]
  (let [spatial? (spatial-keyframe-modulator? keyframe-modulator)
        ;; Spatial uses :active?, time-based uses :enabled?
        ;; For spatial, treat as enabled if the modulator exists (selecting type is activation)
        enabled? (if spatial?
                   (or (:active? keyframe-modulator) true)  ;; Always enabled for spatial
                   (:enabled? keyframe-modulator false))
        selected-idx (:selected-keyframe keyframe-modulator 0)
        keyframes (:keyframes keyframe-modulator [])
        
        ;; Base event params - spatial events need param-key
        base-event (cond-> {:domain domain
                            :entity-key entity-key
                            :effect-path effect-path}
                     spatial? (assoc :param-key param-key))]
    
    {:fx/type :v-box
     :spacing 8
     :style-class "keyframe-panel"
     :children (filterv some?
                [;; Header with enable toggle (only for time-based)
                 {:fx/type header-row
                  :enabled? enabled?
                  :spatial? spatial?
                  :on-toggle-event (assoc base-event
                                          :event/type :keyframe/toggle-enabled)}
                 
                 ;; For spatial: always show controls (no checkbox activation needed)
                 ;; For time-based: only show when enabled
                 (if spatial?
                   ;; Spatial keyframe layout - always shown
                   {:fx/type :v-box
                    :spacing 8
                    :children [;; Spatial settings row
                               {:fx/type spatial-settings-row
                                :axis (:axis keyframe-modulator :point-index)
                                :normalize? (:normalize? keyframe-modulator false)
                                :enabled? true  ;; Always enabled for spatial
                                :on-axis-change (assoc base-event
                                                       :event/type :keyframe/set-axis)
                                :on-normalize-change (assoc base-event
                                                            :event/type :keyframe/update-setting
                                                            :setting-key :normalize?)}
                               
                               ;; Timeline (shared component)
                               {:fx/type timeline-row
                                :keyframes keyframes
                                :selected-idx selected-idx
                                :current-phase nil  ;; No playhead for spatial
                                :enabled? true
                                :on-select (assoc base-event
                                                  :event/type :keyframe/spatial-select)
                                :on-add (assoc base-event
                                               :event/type :keyframe/spatial-add)
                                :on-move (assoc base-event
                                                :event/type :keyframe/spatial-move)
                                :on-delete (assoc base-event
                                                  :event/type :keyframe/spatial-delete)}
                               
                               ;; Spatial value row
                               {:fx/type spatial-value-row
                                :keyframes keyframes
                                :selected-idx selected-idx
                                :enabled? true
                                :on-value-change (assoc base-event
                                                        :event/type :keyframe/set-value)
                                :on-interpolation-change (assoc base-event
                                                                :event/type :keyframe/spatial-set-interpolation)}]}
                   
                   ;; Time-based keyframe layout (only when enabled)
                   (when enabled?
                     (let [period (:period keyframe-modulator 4.0)
                           time-unit (:time-unit keyframe-modulator :beats)
                           loop-mode (:loop-mode keyframe-modulator :loop)]
                       {:fx/type :v-box
                        :spacing 8
                        :children [;; Settings row
                                   {:fx/type settings-row
                                    :period period
                                    :time-unit time-unit
                                    :loop-mode loop-mode
                                    :enabled? enabled?
                                    :on-settings-event (assoc base-event
                                                              :event/type :keyframe/update-setting)}
                                   
                                   ;; Timeline
                                   {:fx/type timeline-row
                                    :keyframes keyframes
                                    :selected-idx selected-idx
                                    :current-phase current-phase
                                    :enabled? enabled?
                                    :on-select (assoc base-event
                                                      :event/type :keyframe/select)
                                    :on-add (assoc base-event
                                                   :event/type :keyframe/add)
                                    :on-move (assoc base-event
                                                    :event/type :keyframe/move)
                                    :on-delete (assoc base-event
                                                      :event/type :keyframe/delete)}
                                   
                                   ;; Actions row
                                   {:fx/type actions-row
                                    :keyframes keyframes
                                    :selected-idx selected-idx
                                    :enabled? enabled?}]})))])}))
