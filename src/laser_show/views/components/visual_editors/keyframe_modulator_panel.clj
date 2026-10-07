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
   └─────────────────────────────────────────────────────────────────┘
   
   Zone-Group-ID Parameters:
   - When param-type is :zone-group-id, automatically uses :step interpolation
   - Shows zone dropdown instead of numeric spinner for value editing
   - Timeline shows colored segments instead of line curves"
  (:require
   [cljfx.api :as fx]
   [laser-show.subs :as subs]
   [laser-show.common.util :as u]
   [laser-show.views.components.visual-editors.keyframe-timeline :as timeline]))


;; Helper Functions


(defn- format-position
  "Format keyframe position as percentage string."
  [position]
  (format "%.0f%%" (* 100 position)))

;; Driver options for unified keyframe system
(def driver-options
  "Available driver types for keyframe modulation."
  [{:id :time :label "Time"}
   {:id :point-index :label "Point Index"}
   {:id :pos-x :label "Position X"}
   {:id :pos-y :label "Position Y"}
   {:id :radial :label "Radial"}])

(defn driver-display-name
  "Convert driver keyword to user-friendly display name."
  [driver]
  (case driver
    :time "Time"
    :point-index "Point Index"
    :pos-x "Position X"
    :pos-y "Position Y"
    :radial "Radial"
    "Time"))

(def edge-behavior-options
  "Available edge behaviors for spatial drivers."
  [{:id :clamp :label "Clamp (stay at edges)"}
   {:id :wrap :label "Wrap (loop around)"}])

(defn- edge-behavior-display-name
  "Convert edge-behavior keyword to user-friendly display name."
  [behavior]
  (case behavior
    :clamp "Clamp"
    :wrap "Wrap"
    "Clamp"))

(defn- spatial-driver?
  "Returns true if driver is a spatial type (not time-based)."
  [driver]
  (contains? #{:point-index :pos-x :pos-y :radial} driver))

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


(defn- driver-dropdown
  "Dropdown for selecting the keyframe driver type.
   Props:
   - :driver - Current driver keyword (defaults to :time)
   - :on-driver-change - Event to dispatch when driver changes"
  [{:keys [driver on-driver-change enabled?]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 5
   :disable (not enabled?)
   :children [{:fx/type :label
               :text "Driver:"}
              {:fx/type :combo-box
               :pref-width 130
               :value (or driver :time)
               :items (mapv :id driver-options)
               :button-cell (fn [item]
                              {:text (driver-display-name item)})
               :cell-factory {:fx/cell-type :list-cell
                              :describe (fn [item]
                                          {:text (driver-display-name item)})}
               :on-value-changed on-driver-change}]})

(defn- edge-behavior-dropdown
  "Dropdown for selecting edge behavior for spatial drivers.
   Props:
   - :edge-behavior - Current edge behavior keyword (defaults to :clamp)
   - :on-edge-behavior-change - Event to dispatch when edge behavior changes"
  [{:keys [edge-behavior on-edge-behavior-change enabled?]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 5
   :disable (not enabled?)
   :children [{:fx/type :label
               :text "Edge:"}
              {:fx/type :combo-box
               :pref-width 100
               :value (or edge-behavior :clamp)
               :items (mapv :id edge-behavior-options)
               :button-cell (fn [item]
                              {:text (edge-behavior-display-name item)})
               :cell-factory {:fx/cell-type :list-cell
                              :describe (fn [item]
                                          {:text (edge-behavior-display-name item)})}
               :on-value-changed on-edge-behavior-change}]})

(defn- normalize-checkbox
  "Checkbox for toggling normalize option (for radial driver only).
   Props:
   - :normalize? - Current normalize state
   - :on-normalize-change - Event to dispatch when normalize changes"
  [{:keys [normalize? on-normalize-change enabled?]}]
  {:fx/type :check-box
   :text "Normalize"
   :disable (not enabled?)
   :selected (boolean normalize?)
   :on-selected-changed on-normalize-change})

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

(defn- driver-row
  "Row with driver selection dropdown.
   Always shown regardless of driver type."
  [{:keys [driver on-driver-change enabled?]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 15
   :style-class "keyframe-panel-settings"
   :children [{:fx/type driver-dropdown
               :driver driver
               :enabled? enabled?
               :on-driver-change on-driver-change}]})

(defn- time-settings-row
  "Settings row for time-based keyframe modulator.
   Shows period, time-unit, and loop-mode controls."
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

(defn- spatial-driver-settings-row
  "Settings row for spatial driver keyframe modulator.
   Shows edge-behavior dropdown and optional normalize checkbox (for radial)."
  [{:keys [driver edge-behavior normalize? on-edge-behavior-change on-normalize-change enabled?]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 15
   :style-class "keyframe-panel-settings"
   :disable (not enabled?)
   :children (filterv some?
              [{:fx/type edge-behavior-dropdown
                :edge-behavior edge-behavior
                :enabled? enabled?
                :on-edge-behavior-change on-edge-behavior-change}
               ;; Normalize checkbox - only visible when driver is :radial
               (when (= driver :radial)
                 {:fx/type normalize-checkbox
                  :normalize? normalize?
                  :enabled? enabled?
                  :on-normalize-change on-normalize-change})])})

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

(defn- timeline-row
  "Row containing the timeline canvas.
   Props:
   - :keyframes - Vector of keyframe maps
   - :selected-idx - Index of selected keyframe
   - :current-phase - Current playback position (0.0-1.0)
   - :on-select, :on-add, :on-move, :on-delete - Event templates
   - :enabled? - Whether interactions are enabled
   - :param-type - Type of parameter
   - :param-key - Key of parameter
   - :zone-groups - List of zone groups"
  [{:keys [keyframes selected-idx current-phase
           on-select on-add on-move on-delete enabled?
           param-type param-key zone-groups]}]
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
               :param-type param-type
               :param-key param-key
               :zone-groups zone-groups
               :on-select on-select
               :on-add on-add
               :on-move on-move
               :on-delete on-delete}]})

(defn- actions-row
  "Row with keyframe info.
   Props:
   - :fx/context - cljfx context
   - :keyframes - Vector of keyframe maps
   - :selected-idx - Index of selected keyframe
   - :enabled? - Whether interactions are enabled
   - :param-type - Type of parameter (e.g. :numeric, :zone-group-id)
   - :param-key - Key of the parameter to edit
   - :on-update-param - Event for parameter changes
   - :on-set-interpolation - Event for setting interpolation"
  [{:keys [fx/context keyframes selected-idx enabled? param-type param-key
           on-update-param on-set-interpolation]}]
  (let [selected-kf (when (and selected-idx
                               (>= selected-idx 0)
                               (< selected-idx (count keyframes)))
                      (nth keyframes selected-idx))
        zone-groups (when (= param-type :zone-group-id)
                      (fx/sub-ctx context subs/zone-groups-list))]
    {:fx/type :h-box
     :alignment :center-left
     :spacing 10
     :style-class "keyframe-panel-actions"
     :disable (not enabled?)
     :children (filterv some?
                [{:fx/type :label
                  :text (if selected-kf
                          (str "Selected: Keyframe " (inc selected-idx)
                               " @ " (format-position (:position selected-kf)))
                          "Click timeline to select")
                  :style-class "label-secondary"}
                 
                 ;; Value Editor
                 (when (and selected-kf param-key)
                   (if (= param-type :zone-group-id)
                     ;; Zone Group Value Editor
                     (let [current-val (get-in selected-kf [:params param-key] :all)
                           all-option {:id :all :name "All"}
                           items (into [all-option] zone-groups)
                           selected-item (or (u/seek #(= (:id %) current-val) items) all-option)]
                       {:fx/type :h-box
                        :alignment :center-left
                        :spacing 5
                        :children [{:fx/type :label :text "Value:"}
                                   {:fx/type :combo-box
                                    :value selected-item
                                    :items items
                                    :button-cell (fn [item] {:text (or (:name item) "Select Zone")})
                                    :cell-factory {:fx/cell-type :list-cell
                                                   :describe (fn [item] {:text (or (:name item) "")})}
                                    :on-value-changed (fn [new-item]
                                                        (when new-item
                                                          (assoc on-update-param :value (:id new-item))))}]})
                     
                     ;; Numeric Value Editor
                     {:fx/type :h-box
                      :alignment :center-left
                      :spacing 5
                      :children [{:fx/type :label :text "Value:"}
                                 {:fx/type :spinner
                                  :pref-width 80
                                  :value-factory {:fx/type :double-spinner-value-factory
                                                  :min -9999.0 :max 9999.0
                                                  :amount-to-step-by 0.1
                                                  :value (get-in selected-kf [:params param-key] 0.0)}
                                  :on-value-changed (fn [v] (assoc on-update-param :value v))}]}))
                 
                 ;; Interpolation Dropdown
                 (when (and selected-kf (not= param-type :zone-group-id))
                   {:fx/type :h-box
                    :alignment :center-left
                    :spacing 5
                    :children [{:fx/type :label :text "Interp:"}
                               {:fx/type :combo-box
                                :pref-width 100
                                :value (:interpolation selected-kf :linear)
                                :items interpolation-modes
                                :button-cell (fn [item] {:text (interpolation-display-name item)})
                                :cell-factory {:fx/cell-type :list-cell
                                               :describe (fn [item] {:text (interpolation-display-name item)})}
                                :on-value-changed (fn [mode] (assoc on-set-interpolation :interpolation mode))}]})])}))


;; Main Panel Component


(defn keyframe-modulator-panel
  "Panel containing timeline and keyframe controls.
   
   Supports unified driver-based keyframe modulation system.
   Driver determines what drives the keyframe interpolation:
   - :time - Time-based animation (default)
   - :point-index, :pos-x, :pos-y, :radial - Spatial drivers
   
   Layout:
   ┌─────────────────────────────────────────────────────────────────┐
   │ KEYFRAME ANIMATION                              [Enable] Toggle │
   ├─────────────────────────────────────────────────────────────────┤
   │ Driver: [Time ▼]                                                │
   ├─────────────────────────────────────────────────────────────────┤
   │ Period: [4.0] [seconds▼] [loop▼]    <- Time driver settings    │
   │   OR                                                            │
   │ Edge: [Clamp▼] [✓] Normalize        <- Spatial driver settings │
   ├─────────────────────────────────────────────────────────────────┤
   │ [Timeline visualization]                                        │
   └─────────────────────────────────────────────────────────────────┘
   
   Props:
   - :keyframe-modulator - The keyframe modulator config map (or nil if not initialized)
   - :domain - :effect-chains or :cue-chains
   - :entity-key - [col row] or projector-id
   - :effect-path - Path to effect within chain
   - :param-key - Parameter key (e.g. :hue)
   - :current-phase - Current playback position for preview (optional, time-based only)"
  [{:keys [fx/context effect-def keyframe-modulator domain entity-key effect-path param-key current-phase]}]
  (let [driver (or (:driver keyframe-modulator) :time)
        spatial? (spatial-driver? driver)
        enabled? (:enabled? keyframe-modulator false)
        selected-idx (:selected-keyframe keyframe-modulator 0)
        keyframes (:keyframes keyframe-modulator [])
        
        ;; Settings from modulator config
        period (:period keyframe-modulator 4.0)
        time-unit (:time-unit keyframe-modulator :beats)
        loop-mode (:loop-mode keyframe-modulator :loop)
        edge-behavior (:edge-behavior keyframe-modulator :clamp)
        normalize? (:normalize? keyframe-modulator false)
        
        actual-param-key (or param-key (-> effect-def :parameters first :key))
        param-spec (u/seek #(= (:key %) actual-param-key) (:parameters effect-def))
        param-type (:type param-spec)
        
        ;; Base event params
        base-event {:domain domain
                    :entity-key entity-key
                    :effect-path effect-path
                    :param-key param-key}]
    
    {:fx/type :v-box
     :spacing 8
     :style-class "keyframe-panel"
     :children (filterv some?
                [;; Header with enable toggle
                 {:fx/type header-row
                  :enabled? enabled?
                  :spatial? spatial?
                  :on-toggle-event (assoc base-event
                                          :event/type :keyframe/toggle-enabled)}
                 
                 ;; Only show controls when enabled
                 (when enabled?
                   {:fx/type :v-box
                    :spacing 8
                    :children (filterv some?
                               [{:fx/type driver-row
                                 :driver driver
                                 :enabled? enabled?
                                 :on-driver-change (assoc base-event
                                                          :event/type :keyframe/set-driver)}
                                
                                ;; Conditional settings based on driver type
                                (cond
                                  spatial?
                                  {:fx/type spatial-driver-settings-row
                                   :driver driver
                                   :edge-behavior edge-behavior
                                   :normalize? normalize?
                                   :enabled? enabled?
                                   :on-edge-behavior-change (assoc base-event
                                                                   :event/type :keyframe/set-edge-behavior)
                                   :on-normalize-change (assoc base-event
                                                               :event/type :keyframe/set-normalize)}
                                  
                                  :else
                                  ;; Time driver settings (also used for zone-group-id)
                                  {:fx/type time-settings-row
                                   :period period
                                   :time-unit time-unit
                                   :loop-mode loop-mode
                                   :enabled? enabled?
                                   :on-settings-event (assoc base-event
                                                             :event/type :keyframe/update-setting)})
                                
                                ;; Timeline
                                {:fx/type timeline-row
                                 :keyframes keyframes
                                 :selected-idx selected-idx
                                 :current-phase (when-not spatial? current-phase)
                                 :enabled? enabled?
                                 :param-type param-type
                                 :param-key actual-param-key
                                 :zone-groups (when (= param-type :zone-group-id)
                                                (fx/sub-ctx context subs/zone-groups-list))
                                 :on-select (assoc base-event
                                                   :event/type :keyframe/select)
                                 :on-add (assoc base-event
                                                :event/type :keyframe/add)
                                 :on-move (assoc base-event
                                                 :event/type :keyframe/move)
                                 :on-delete (assoc base-event
                                                   :event/type :keyframe/delete)}
                                
                                {:fx/type actions-row
                                 :fx/context context
                                 :keyframes keyframes
                                 :selected-idx selected-idx
                                 :enabled? enabled?
                                 :param-type param-type
                                 :param-key actual-param-key
                                 :on-update-param (assoc base-event
                                                         :event/type :keyframe/update-param
                                                         :keyframe-idx selected-idx
                                                         :param-key actual-param-key)
                                 :on-set-interpolation (assoc base-event
                                                              :event/type :keyframe/set-interpolation
                                                              :keyframe-idx selected-idx)}])})])}))
