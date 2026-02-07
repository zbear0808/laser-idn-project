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

(defn- zone-group-id-param?
  "Returns true if the parameter type is :zone-group-id."
  [param-type]
  (= param-type :zone-group-id))


;; Sub-components


(defn- zone-group-value-editor
  "Dropdown for selecting zone-group-id keyframe value.
   Props:
   - :fx/context - cljfx context for zone-groups subscription
   - :zone-groups - List of available zone groups
   - :current-value - Current zone-group-id (keyword like :left, :all)
   - :on-value-change - Event to dispatch when value changes
   - :disabled? - Whether the dropdown is disabled"
  [{:keys [zone-groups current-value on-value-change disabled?]}]
  (let [;; Add :all as special option at the start
        all-option {:id :all :name "All Zones" :color "#888888"}
        all-options (into [all-option] zone-groups)
        ;; Find the selected item
        selected-item (or (first (filter #(= (:id %) current-value) all-options))
                          all-option)]
    {:fx/type :combo-box
     :disable (boolean disabled?)
     :pref-width 150
     :value selected-item
     :items all-options
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
                         (when on-value-change
                           (on-value-change (:id new-item))))}))

(defn- build-zone-group-colors
  "Build a map of zone-group-id to color for timeline visualization."
  [zone-groups]
  (into {:all "#888888"}
        (mapv (fn [zg] [(:id zg) (or (:color zg) "#666666")])
              zone-groups)))


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
   - :param-type - Parameter type (for zone-group-id rendering)
   - :zone-group-colors - Map of zone-group-id to color (for zone-group-id rendering)
   - :param-key - Parameter key name (for zone-group-id keyframes)"
  [{:keys [keyframes selected-idx current-phase
           on-select on-add on-move on-delete enabled?
           param-type zone-group-colors param-key]}]
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
               :on-delete on-delete
               ;; Zone-group-id specific props
               :value-type (if (zone-group-id-param? param-type) :zone-group-id :numeric)
               :zone-group-colors zone-group-colors
               :param-key param-key}]})

(defn- actions-row
  "Row with keyframe info and optional zone-group value editor.
   Props:
   - :keyframes - Vector of keyframe maps
   - :selected-idx - Index of selected keyframe
   - :enabled? - Whether interactions are enabled
   - :param-type - Parameter type (:zone-group-id or other)
   - :zone-groups - Zone groups list (for zone-group-id dropdown)
   - :on-zone-value-change - Event template for zone value changes
   - :param-key - Parameter key for accessing keyframe value"
  [{:keys [keyframes selected-idx enabled? param-type zone-groups on-zone-value-change param-key]}]
  (let [selected-kf (when (and selected-idx
                               (>= selected-idx 0)
                               (< selected-idx (count keyframes)))
                      (nth keyframes selected-idx))
        zone-group-id? (zone-group-id-param? param-type)
        ;; Get current zone value from selected keyframe params
        current-zone-value (when (and zone-group-id? selected-kf)
                             (get-in selected-kf [:params param-key] :all))]
    {:fx/type :h-box
     :alignment :center-left
     :spacing 10
     :style-class "keyframe-panel-actions"
     :disable (not enabled?)
     :children (filterv some?
                [;; Selected keyframe info
                 {:fx/type :label
                  :text (if selected-kf
                          (str "Selected: Keyframe " (inc selected-idx)
                               " @ " (format-position (:position selected-kf)))
                          "Click timeline to select")
                  :style-class "label-secondary"}
                 
                 ;; Zone value editor (only for zone-group-id parameters)
                 (when (and zone-group-id? selected-kf)
                   {:fx/type :h-box
                    :alignment :center-left
                    :spacing 5
                    :children [{:fx/type :label
                                :text "Zone:"
                                :style-class "label-secondary"}
                               {:fx/type zone-group-value-editor
                                :zone-groups zone-groups
                                :current-value current-zone-value
                                :disabled? (not enabled?)
                                :on-value-change (fn [new-zone-id]
                                                   (when on-zone-value-change
                                                     (on-zone-value-change
                                                      {:keyframe-idx selected-idx
                                                       :zone-id new-zone-id})))}]})])}))


;; Main Panel Component


(defn keyframe-modulator-panel
  "Panel containing timeline and keyframe controls.
   
   Supports unified driver-based keyframe modulation system.
   Driver determines what drives the keyframe interpolation:
   - :time - Time-based animation (default)
   - :point-index, :pos-x, :pos-y, :radial - Spatial drivers
   
   For :zone-group-id parameter types:
   - Automatically uses :step interpolation (no blending between zones)
   - Shows zone dropdown instead of numeric value editor
   - Timeline shows colored segments representing active zones
   
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
   - :fx/context - cljfx context (required for zone-group-id params)
   - :keyframe-modulator - The keyframe modulator config map (or nil if not initialized)
   - :domain - :effect-chains or :cue-chains
   - :entity-key - [col row] or projector-id
   - :effect-path - Path to effect within chain
   - :param-key - Parameter key (e.g. :hue, :target-zone)
   - :param-type - Parameter type (e.g. :zone-group-id, :number)
   - :current-phase - Current playback position for preview (optional, time-based only)"
  [{:keys [fx/context keyframe-modulator domain entity-key effect-path param-key param-type current-phase]}]
  (let [driver (or (:driver keyframe-modulator) :time)
        spatial? (spatial-driver? driver)
        zone-group-id? (zone-group-id-param? param-type)
        enabled? (:enabled? keyframe-modulator false)
        selected-idx (:selected-keyframe keyframe-modulator 0)
        keyframes (:keyframes keyframe-modulator [])
        
        ;; Settings from modulator config
        period (:period keyframe-modulator 4.0)
        time-unit (:time-unit keyframe-modulator :beats)
        loop-mode (:loop-mode keyframe-modulator :loop)
        edge-behavior (:edge-behavior keyframe-modulator :clamp)
        normalize? (:normalize? keyframe-modulator false)
        
        ;; Zone groups for zone-group-id parameters
        zone-groups (when zone-group-id?
                      (fx/sub-ctx context subs/zone-groups-list))
        zone-group-colors (when zone-group-id?
                            (build-zone-group-colors zone-groups))
        
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
                               [;; Driver selection row (hide for zone-group-id - always time-based)
                                (when-not zone-group-id?
                                  {:fx/type driver-row
                                   :driver driver
                                   :enabled? enabled?
                                   :on-driver-change (assoc base-event
                                                            :event/type :keyframe/set-driver)})
                                
                                ;; Conditional settings based on driver type
                                ;; Zone-group-id params always use time settings
                                (cond
                                  (and (not zone-group-id?) spatial?)
                                  ;; Spatial driver settings
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
                                 :zone-group-colors zone-group-colors
                                 :param-key param-key
                                 :on-select (assoc base-event
                                                   :event/type :keyframe/select)
                                 :on-add (assoc base-event
                                                :event/type :keyframe/add)
                                 :on-move (assoc base-event
                                                 :event/type :keyframe/move)
                                 :on-delete (assoc base-event
                                                   :event/type :keyframe/delete)}
                                
                                ;; Actions/info row with zone editor for zone-group-id params
                                {:fx/type actions-row
                                 :keyframes keyframes
                                 :selected-idx selected-idx
                                 :enabled? enabled?
                                 :param-type param-type
                                 :param-key param-key
                                 :zone-groups zone-groups
                                 :on-zone-value-change (when zone-group-id?
                                                         (fn [{:keys [keyframe-idx zone-id]}]
                                                           ((requiring-resolve 'laser-show.events.core/dispatch!)
                                                            (assoc base-event
                                                                   :event/type :keyframe/set-zone-value
                                                                   :keyframe-idx keyframe-idx
                                                                   :zone-id zone-id))))}])})])}))
