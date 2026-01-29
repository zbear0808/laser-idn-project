(ns laser-show.animation.modulators
  "Modulator type registrations.
   
   This namespace registers all modulator types with the central registry,
   combining data from multiple sources into single registration calls:
   - Type metadata (id, name, icon) from modulator-defs
   - Evaluator functions from modulator-evaluators
   - Parameter definitions from modulator-defs
   - Per-point and retrigger flags
   
   Load this namespace to populate the modulator registry."
  (:require
   [laser-show.animation.modulator-registry :as reg]
   [laser-show.animation.modulator-evaluators :as eval]))


;; Parameter Definitions
;; These are copied from modulator-defs to avoid circular dependency


(def ^:private wave-params
  "Common wave modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
   {:key :time-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
   {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
   {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}
   {:key :phase :label "Phase" :type :float :min 0.0 :max 1.0 :default 0.0}])

(def ^:private square-params
  "Square wave parameters (has duty cycle)."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
   {:key :time-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
   {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
   {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}
   {:key :duty-cycle :label "Duty Cycle" :type :float :min 0.0 :max 1.0 :default 0.5}
   {:key :phase :label "Phase" :type :float :min 0.0 :max 1.0 :default 0.0}])

(def ^:private random-params
  "Random modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
   {:key :time-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
   {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
   {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}])

(def ^:private step-params
  "Step modulator parameters."
  [{:key :values :label "Values" :type :text :default "[0 0.5 1]"}
   {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
   {:key :time-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
   {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
   {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}])

(def ^:private decay-params
  "Decay modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :duration :label "Duration" :type :float :min 0.0625 :max 16.0 :default 1.0}
   {:key :time-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
   {:key :decay-curve :label "Curve" :type :choice :choices [:linear :exp :log] :default :exp}])

(def ^:private min-max-params
  "Simple min/max parameters for position-based modulators."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}])

(def ^:private midi-params
  "MIDI CC modulator parameters."
  [{:key :channel :label "Channel" :type :int :min 1 :max 16 :default 1}
   {:key :cc :label "CC #" :type :int :min 0 :max 127 :default 1}
   {:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}])

(def ^:private osc-params
  "OSC modulator parameters."
  [{:key :path :label "Path" :type :text :default "/control"}
   {:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}])

(def ^:private point-index-params
  "Point index modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :wrap? :label "Wrap" :type :boolean :default false}])

(def ^:private radial-params
  "Radial distance modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :normalize? :label "Normalize" :type :boolean :default true}])


;; Wave Modulators


(reg/register-modulator!
 {:id          :sine
  :name        "Sine"
  :icon        "〰️"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :sine)
  :params      wave-params
  :per-point?  false
  :retrigger?  true})

(reg/register-modulator!
 {:id          :triangle
  :name        "Triangle"
  :icon        "△"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :triangle)
  :params      wave-params
  :per-point?  false
  :retrigger?  true})

(reg/register-modulator!
 {:id          :sawtooth
  :name        "Sawtooth"
  :icon        "⟋|"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :sawtooth)
  :params      wave-params
  :per-point?  false
  :retrigger?  true})

(reg/register-modulator!
 {:id          :square
  :name        "Square"
  :icon        "▭"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :square)
  :params      square-params
  :per-point?  false
  :retrigger?  true})

(reg/register-modulator!
 {:id          :random
  :name        "Random"
  :icon        "⚡"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :random)
  :params      random-params
  :per-point?  false
  :retrigger?  true})

(reg/register-modulator!
 {:id          :step
  :name        "Step"
  :icon        "⊟"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :step)
  :params      step-params
  :per-point?  false
  :retrigger?  true})

(reg/register-modulator!
 {:id          :decay
  :name        "Decay"
  :icon        "↘"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :exp-decay)
  :params      decay-params
  :per-point?  false
  :retrigger?  true})


;; Per-Point Modulators


(reg/register-modulator!
 {:id          :pos-x
  :name        "Position X"
  :icon        "↔"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :pos-x)
  :params      min-max-params
  :per-point?  true
  :retrigger?  false})

(reg/register-modulator!
 {:id          :pos-y
  :name        "Position Y"
  :icon        "↕"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :pos-y)
  :params      min-max-params
  :per-point?  true
  :retrigger?  false})

(reg/register-modulator!
 {:id          :radial
  :name        "Radial"
  :icon        "◎"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :radial)
  :params      radial-params
  :per-point?  true
  :retrigger?  false})

(reg/register-modulator!
 {:id          :point-index
  :name        "Point Index"
  :icon        "🔢"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :point-index)
  :params      point-index-params
  :per-point?  true
  :retrigger?  false})


;; External Input Modulators


(reg/register-modulator!
 {:id          :midi
  :name        "MIDI CC"
  :icon        "🎹"
  :category    :external
  :evaluator   (get eval/modulator-evaluators :midi)
  :params      midi-params
  :per-point?  false
  :retrigger?  false
  :description "MIDI CC input modulator"})

(reg/register-modulator!
 {:id          :osc
  :name        "OSC"
  :icon        "📡"
  :category    :external
  :evaluator   (get eval/modulator-evaluators :osc)
  :params      osc-params
  :per-point?  false
  :retrigger?  false
  :description "OSC input modulator"})


;; Registration Summary
;; Total: 13 modulator types registered
;;
;; Wave (7):      :sine, :triangle, :sawtooth, :square, :random, :step, :decay
;; Per-Point (4): :pos-x, :pos-y, :radial, :point-index
;; External (2):  :midi, :osc
