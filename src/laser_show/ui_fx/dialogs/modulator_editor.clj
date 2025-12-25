(ns laser-show.ui-fx.dialogs.modulator-editor
  "Modulator editor dialog for cljfx.
   Allows configuring modulator parameters for effects."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles]
            [laser-show.ui-fx.components.slider :as slider]
            [laser-show.animation.modulation :as mod])
  (:import [javafx.stage Stage Modality]
           [javafx.scene Scene]
           [javafx.application Platform]))

;; ============================================================================
;; Modulator Types
;; ============================================================================

(def modulator-categories
  [{:id :oscillators :name "Oscillators"
    :types [:sine :triangle :sawtooth :square]}
   {:id :timing :name "Timing"
    :types [:sine-hz :square-hz :exp-decay :linear-decay :halflife-decay]}
   {:id :random :name "Random"
    :types [:random :step]}
   {:id :position :name "Position-based"
    :types [:pos-x :pos-y :radial :angle :pos-wave :pos-scroll]}
   {:id :point :name "Point-based"
    :types [:point-index :point-wave]}
   {:id :input :name "Input"
    :types [:midi :osc]}
   {:id :special :name "Special"
    :types [:rainbow-hue :constant]}])

(def modulator-info
  "Information about each modulator type."
  {:sine {:name "Sine Wave" :description "Smooth oscillation"}
   :triangle {:name "Triangle Wave" :description "Linear ramp up/down"}
   :sawtooth {:name "Sawtooth Wave" :description "Ramp up, instant reset"}
   :square {:name "Square Wave" :description "On/off with duty cycle"}
   :sine-hz {:name "Sine (Hz)" :description "Sine at fixed frequency"}
   :square-hz {:name "Square (Hz)" :description "Square at fixed frequency"}
   :exp-decay {:name "Beat Decay" :description "Decay over each beat"}
   :linear-decay {:name "Linear Decay" :description "Linear decay from trigger"}
   :halflife-decay {:name "Half-life Decay" :description "Exponential decay"}
   :random {:name "Random" :description "Random value changes"}
   :step {:name "Step Sequencer" :description "Cycle through values"}
   :pos-x {:name "Position X" :description "Based on X coordinate"}
   :pos-y {:name "Position Y" :description "Based on Y coordinate"}
   :radial {:name "Radial Distance" :description "Distance from center"}
   :angle {:name "Angle" :description "Angle from center"}
   :pos-wave {:name "Position Wave" :description "Wave along position"}
   :pos-scroll {:name "Position Scroll" :description "Scrolling wave"}
   :point-index {:name "Point Index" :description "Based on point order"}
   :point-wave {:name "Point Wave" :description "Wave along points"}
   :midi {:name "MIDI CC" :description "Control via MIDI"}
   :osc {:name "OSC" :description "Control via OSC"}
   :rainbow-hue {:name "Rainbow Hue" :description "Cycling hue value"}
   :constant {:name "Constant" :description "Fixed value"}})

;; ============================================================================
;; Modulator Parameters by Type
;; ============================================================================

(defn- get-modulator-params
  "Get parameter definitions for a modulator type."
  [mod-type]
  (case mod-type
    ;; Oscillators - common params: min, max, period, phase
    (:sine :triangle :sawtooth)
    [{:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}
     {:key :period :label "Period (beats)" :type :float :min 0.0625 :max 16.0 :default 1.0}
     {:key :phase :label "Phase" :type :float :min 0.0 :max 1.0 :default 0.0}]
    
    :square
    [{:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}
     {:key :period :label "Period (beats)" :type :float :min 0.0625 :max 16.0 :default 1.0}
     {:key :duty-cycle :label "Duty Cycle" :type :float :min 0.0 :max 1.0 :default 0.5}
     {:key :phase :label "Phase" :type :float :min 0.0 :max 1.0 :default 0.0}]
    
    ;; Hz-based
    (:sine-hz :square-hz)
    [{:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}
     {:key :frequency-hz :label "Frequency (Hz)" :type :float :min 0.1 :max 30.0 :default 1.0}]
    
    ;; Decay
    :exp-decay
    [{:key :min :label "Min" :type :float :min 0.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min 0.0 :max 2.0 :default 1.0}
     {:key :decay-type :label "Type" :type :choice :options [:linear :exp] :default :exp}]
    
    :linear-decay
    [{:key :start :label "Start" :type :float :min 0.0 :max 2.0 :default 1.0}
     {:key :end :label "End" :type :float :min 0.0 :max 2.0 :default 0.0}
     {:key :duration-ms :label "Duration (ms)" :type :int :min 50 :max 10000 :default 1000}]
    
    :halflife-decay
    [{:key :start :label "Start" :type :float :min 0.0 :max 2.0 :default 1.0}
     {:key :end :label "End" :type :float :min 0.0 :max 2.0 :default 0.0}
     {:key :half-life-ms :label "Half-life (ms)" :type :int :min 50 :max 5000 :default 500}]
    
    ;; Random
    :random
    [{:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}
     {:key :changes-per-beat :label "Changes/beat" :type :float :min 0.25 :max 16.0 :default 1.0}]
    
    :step
    [{:key :steps-per-beat :label "Steps/beat" :type :float :min 0.25 :max 16.0 :default 1.0}]
    
    ;; Position-based
    (:pos-x :pos-y :radial :angle)
    [{:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}]
    
    :pos-wave
    [{:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}
     {:key :axis :label "Axis" :type :choice :options [:x :y :radial :angle] :default :x}
     {:key :frequency :label "Frequency" :type :float :min 0.1 :max 10.0 :default 1.0}
     {:key :wave-type :label "Wave" :type :choice :options [:sine :triangle :sawtooth] :default :sine}]
    
    :pos-scroll
    [{:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}
     {:key :axis :label "Axis" :type :choice :options [:x :y] :default :x}
     {:key :speed :label "Speed" :type :float :min 0.1 :max 10.0 :default 1.0}
     {:key :wave-type :label "Wave" :type :choice :options [:sine :triangle :sawtooth] :default :sine}]
    
    ;; Point-based
    :point-index
    [{:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}
     {:key :wrap? :label "Wrap" :type :bool :default false}]
    
    :point-wave
    [{:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}
     {:key :cycles :label "Cycles" :type :float :min 0.25 :max 10.0 :default 1.0}
     {:key :wave-type :label "Wave" :type :choice :options [:sine :triangle :sawtooth] :default :sine}]
    
    ;; Input
    :midi
    [{:key :channel :label "Channel" :type :int :min 1 :max 16 :default 1}
     {:key :cc :label "CC Number" :type :int :min 0 :max 127 :default 1}
     {:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}]
    
    :osc
    [{:key :path :label "OSC Path" :type :string :default "/control"}
     {:key :min :label "Min" :type :float :min -2.0 :max 2.0 :default 0.0}
     {:key :max :label "Max" :type :float :min -2.0 :max 2.0 :default 1.0}]
    
    ;; Special
    :rainbow-hue
    [{:key :axis :label "Axis" :type :choice :options [:x :y :radial :angle] :default :x}
     {:key :speed :label "Speed (deg/s)" :type :float :min 0.0 :max 360.0 :default 60.0}]
    
    :constant
    [{:key :value :label "Value" :type :float :min -2.0 :max 2.0 :default 1.0}]
    
    ;; Default fallback
    [{:key :min :label "Min" :type :float :min 0.0 :max 1.0 :default 0.0}
     {:key :max :label "Max" :type :float :min 0.0 :max 1.0 :default 1.0}]))

;; ============================================================================
;; Dialog State
;; ============================================================================

(defonce ^:private !editor-state
  (atom {:mod-type :sine
         :config {:type :sine :min 0.0 :max 1.0 :period 1.0 :phase 0.0}
         :on-save nil
         :stage nil}))

;; ============================================================================
;; Modulator Type Selector
;; ============================================================================

(defn modulator-type-list
  "List of available modulator types."
  [{:keys [selected-type on-select]}]
  {:fx/type :v-box
   :spacing 4
   :children (mapv (fn [{:keys [id name types]}]
                     {:fx/type :v-box
                      :children [{:fx/type :label
                                  :text name
                                  :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                                             "-fx-font-size: 10px;"
                                             "-fx-font-weight: bold;"
                                             "-fx-padding: 4 0 2 0;")}
                                 {:fx/type :flow-pane
                                  :hgap 4
                                  :vgap 4
                                  :children (mapv (fn [mod-type]
                                                    (let [info (get modulator-info mod-type)
                                                          selected? (= mod-type selected-type)]
                                                      {:fx/type :button
                                                       :text (:name info)
                                                       :style (str "-fx-font-size: 10px;"
                                                                  "-fx-padding: 4 8;"
                                                                  "-fx-background-color: "
                                                                  (if selected?
                                                                    (:accent styles/colors)
                                                                    (:surface-light styles/colors))
                                                                  ";")
                                                       :on-action (fn [_] (on-select mod-type))}))
                                                  types)}]})
                   modulator-categories)})

;; ============================================================================
;; Parameter Editor
;; ============================================================================

(defn param-editor
  "Editor for modulator parameters."
  [{:keys [mod-type config on-param-change]}]
  (let [param-defs (get-modulator-params mod-type)]
    {:fx/type :v-box
     :spacing 8
     :style (str "-fx-padding: 8;")
     :children (mapv (fn [param-def]
                       (let [{:keys [key type]} param-def
                             current-val (get config key)]
                         (if (= type :string)
                           ;; String input
                           {:fx/type :h-box
                            :spacing 8
                            :alignment :center-left
                            :children [{:fx/type :label
                                        :text (str (:label param-def) ":")
                                        :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                                   "-fx-min-width: 100;")}
                                       {:fx/type :text-field
                                        :text (or current-val (:default param-def))
                                        :pref-width 150
                                        :on-text-changed (fn [new-val]
                                                          (on-param-change key new-val))}]}
                           ;; Other types use param-control
                           {:fx/type slider/param-control
                            :param-def param-def
                            :value current-val
                            :on-change (fn [new-val]
                                         (on-param-change key new-val))})))
                     param-defs)}))

;; ============================================================================
;; Preset Selector
;; ============================================================================

(defn preset-selector
  "Selector for modulator presets."
  [{:keys [on-select]}]
  {:fx/type :v-box
   :spacing 4
   :children [{:fx/type :label
               :text "Presets"
               :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                          "-fx-font-size: 10px;"
                          "-fx-font-weight: bold;")}
              {:fx/type :flow-pane
               :hgap 4
               :vgap 4
               :children (mapv (fn [[preset-key _]]
                                 {:fx/type :button
                                  :text (name preset-key)
                                  :style "-fx-font-size: 9px; -fx-padding: 2 6;"
                                  :on-action (fn [_]
                                               (on-select (mod/preset preset-key)))})
                               mod/presets)}]})

;; ============================================================================
;; Main Dialog Content
;; ============================================================================

(defn dialog-content
  "Main content for the modulator editor dialog."
  [{:keys [mod-type config on-type-change on-param-change on-preset-select on-save on-cancel]}]
  {:fx/type :border-pane
   :style (str "-fx-background-color: " (:background styles/colors) ";")
   
   ;; Header
   :top {:fx/type :h-box
         :style (str "-fx-background-color: " (:surface styles/colors) ";"
                    "-fx-padding: 12 16;")
         :alignment :center-left
         :children [{:fx/type :label
                     :text "Configure Modulator"
                     :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                "-fx-font-size: 14px;"
                                "-fx-font-weight: bold;")}]}
   
   ;; Main content
   :center {:fx/type :split-pane
            :style (str "-fx-background-color: " (:background styles/colors) ";")
            :divider-positions [0.5]
            :items [;; Left - Type selection
                    {:fx/type :v-box
                     :style (str "-fx-background-color: " (:surface styles/colors) ";"
                                "-fx-padding: 8;")
                     :children [{:fx/type :label
                                 :text "Modulator Type"
                                 :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                            "-fx-font-weight: bold;"
                                            "-fx-padding: 0 0 8 0;")}
                                {:fx/type :scroll-pane
                                 :fit-to-width true
                                 :v-box/vgrow :always
                                 :content {:fx/type modulator-type-list
                                           :selected-type mod-type
                                           :on-select on-type-change}}
                                {:fx/type preset-selector
                                 :on-select on-preset-select}]}
                    
                    ;; Right - Parameters
                    {:fx/type :v-box
                     :style (str "-fx-background-color: " (:surface-light styles/colors) ";"
                                "-fx-padding: 8;")
                     :children [{:fx/type :label
                                 :text (str "Parameters: " (:name (get modulator-info mod-type)))
                                 :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                            "-fx-font-weight: bold;"
                                            "-fx-padding: 0 0 8 0;")}
                                {:fx/type :scroll-pane
                                 :fit-to-width true
                                 :v-box/vgrow :always
                                 :content {:fx/type param-editor
                                           :mod-type mod-type
                                           :config config
                                           :on-param-change on-param-change}}]}]}
   
   ;; Footer
   :bottom {:fx/type :h-box
            :style (str "-fx-background-color: " (:surface styles/colors) ";"
                       "-fx-padding: 8 16;")
            :alignment :center-right
            :spacing 8
            :children [{:fx/type :button
                        :text "Cancel"
                        :on-action (fn [_] (on-cancel))}
                       {:fx/type :button
                        :text "Apply"
                        :style-class ["button" "primary"]
                        :on-action (fn [_] (on-save config))}]}})

;; ============================================================================
;; Public API
;; ============================================================================

(defn show-modulator-editor!
  "Show the modulator editor dialog.
   
   Parameters:
   - initial-config - Initial modulator config (or nil for default)
   - on-save - Callback (fn [config]) called when user clicks Apply
   
   Returns: The dialog stage"
  [initial-config on-save]
  (let [initial-type (or (:type initial-config) :sine)
        initial-cfg (or initial-config {:type :sine :min 0.0 :max 1.0 :period 1.0 :phase 0.0})]
    
    ;; Initialize state
    (reset! !editor-state {:mod-type initial-type
                           :config initial-cfg
                           :on-save on-save
                           :stage nil})
    
    (Platform/runLater
     (fn []
       (let [stage (Stage.)
             
             close-fn (fn []
                        (.close stage)
                        (swap! !editor-state assoc :stage nil))
             
             render-fn (fn []
                         (let [state @!editor-state]
                           {:fx/type dialog-content
                            :mod-type (:mod-type state)
                            :config (:config state)
                            :on-type-change (fn [new-type]
                                              (swap! !editor-state
                                                     (fn [s]
                                                       (-> s
                                                           (assoc :mod-type new-type)
                                                           (assoc-in [:config :type] new-type)))))
                            :on-param-change (fn [key value]
                                               (swap! !editor-state assoc-in [:config key] value))
                            :on-preset-select (fn [preset-config]
                                                (swap! !editor-state
                                                       (fn [s]
                                                         (-> s
                                                             (assoc :mod-type (:type preset-config))
                                                             (assoc :config preset-config)))))
                            :on-save (fn [config]
                                       (when-let [save-fn (:on-save @!editor-state)]
                                         (save-fn config))
                                       (close-fn))
                            :on-cancel close-fn}))
             
             component (fx/create-component (render-fn))
             scene (Scene. (fx/instance component) 700 500)]
         
         (.setTitle stage "Modulator Editor")
         (.setScene stage scene)
         (.initModality stage Modality/APPLICATION_MODAL)
         
         ;; Update on state changes
         (add-watch !editor-state :mod-editor-render
                    (fn [_ _ _ new-state]
                      (when (:stage new-state)
                        (Platform/runLater
                         (fn []
                           (let [new-comp (fx/create-component (render-fn))]
                             (.setRoot scene (fx/instance new-comp))))))))
         
         (swap! !editor-state assoc :stage stage)
         (.showAndWait stage)
         
         ;; Cleanup
         (remove-watch !editor-state :mod-editor-render))))))

(comment
  ;; Example usage from REPL:
  (show-modulator-editor!
   {:type :sine :min 0.0 :max 1.0 :period 1.0}
   (fn [config]
     (println "User selected:" config))))
