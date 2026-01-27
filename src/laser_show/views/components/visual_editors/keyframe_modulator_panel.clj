(ns laser-show.views.components.visual-editors.keyframe-modulator-panel
  "Panel component for keyframe modulator controls.
   
   Contains:
   - Enable/disable toggle
   - Period, time-unit, loop-mode controls
   - Timeline component for keyframe editing
   - Keyframe management buttons (Add, Delete, Copy Params)
   
   Layout:
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
   └─────────────────────────────────────────────────────────────────┘"
  (:require
   [laser-show.views.components.visual-editors.keyframe-timeline :as timeline]))


;; Helper Functions


(defn- format-position
  "Format keyframe position as percentage string."
  [position]
  (format "%.0f%%" (* 100 position)))


;; Sub-components


(defn- header-row
  "Header with title and enable toggle."
  [{:keys [enabled? on-toggle-event]}]
  {:fx/type :h-box
   :alignment :center-left
   :spacing 10
   :style-class "keyframe-panel-header"
   :children [{:fx/type :label
               :text "KEYFRAME ANIMATION"
               :style-class "header-section"}
              {:fx/type :region :h-box/hgrow :always}
              {:fx/type :check-box
               :text "Enable"
               :selected (boolean enabled?)
               :on-selected-changed (assoc on-toggle-event
                                           :enabled? (not enabled?))}]})

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
   
   Props:
   - :keyframe-modulator - The keyframe modulator config map (or nil if not initialized)
   - :domain - :effect-chains or :cue-chains
   - :entity-key - [col row] or projector-id
   - :effect-path - Path to effect within chain
   - :current-phase - Current playback position for preview (optional)"
  [{:keys [keyframe-modulator domain entity-key effect-path current-phase]}]
  (let [enabled? (:enabled? keyframe-modulator false)
        period (:period keyframe-modulator 4.0)
        time-unit (:time-unit keyframe-modulator :beats)
        loop-mode (:loop-mode keyframe-modulator :loop)
        selected-idx (:selected-keyframe keyframe-modulator 0)
        keyframes (:keyframes keyframe-modulator [])
        
        ;; Base event params
        base-event {:domain domain
                    :entity-key entity-key
                    :effect-path effect-path}]
    
    {:fx/type :v-box
     :spacing 8
     :style-class "keyframe-panel"
     ;; Filter out nil children - (when enabled? ...) returns nil when disabled
     :children (filterv some?
                [;; Header with enable toggle
                 {:fx/type header-row
                  :enabled? enabled?
                  :on-toggle-event (assoc base-event
                                          :event/type :keyframe/toggle-enabled)}
                 
                 (when enabled?
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
                                :enabled? enabled?}]})])}))
