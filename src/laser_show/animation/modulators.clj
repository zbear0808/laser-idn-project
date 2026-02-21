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

(def ^:private generic-input-params
  "Generic Input (MIDI/OSC/Keyboard) modulator parameters."
  [{:key :source-key :label "Input Source Key" :type :text :default "[:midi 1 1]"}
   {:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :raw-min :label "Raw Min" :type :float :min -1000.0 :max 1000.0 :default 0.0}
   {:key :raw-max :label "Raw Max" :type :float :min -1000.0 :max 1000.0 :default 1.0}])

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
  :icon        "∿"
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
  ;; :icon        "▭"
  :icon-name   :wave-square
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :square)
  :params      square-params
  :per-point?  false
  :retrigger?  true})

(reg/register-modulator!
 {:id          :random
  :name        "Random"
  ;; :icon        "⚡"
  :icon-name   :shuffle
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :random)
  :params      random-params
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
(reg/register-compiler! :pos-x (get eval/modulator-compilers :pos-x))

(reg/register-modulator!
 {:id          :pos-y
  :name        "Position Y"
  :icon        "↕"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :pos-y)
  :params      min-max-params
  :per-point?  true
  :retrigger?  false})
(reg/register-compiler! :pos-y (get eval/modulator-compilers :pos-y))

(reg/register-modulator!
 {:id          :radial
  :name        "Radial"
  :icon        "◎"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :radial)
  :params      radial-params
  :per-point?  true
  :retrigger?  false})
(reg/register-compiler! :radial (get eval/modulator-compilers :radial))

(reg/register-modulator!
 {:id          :point-index
  :name        "Point Index"
  :icon-name   :arrow-up-9-1
  :category    :special
  :evaluator   (get eval/modulator-evaluators :point-index)
  :params      point-index-params
  :per-point?  true
  :retrigger?  false})
(reg/register-compiler! :point-index (get eval/modulator-compilers :point-index))

(reg/register-modulator!
 {:id          :angle
  :name        "Angle"
  :icon        "∠"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :angle)
  :params      min-max-params
  :per-point?  true
  :retrigger?  false})
(reg/register-compiler! :angle (get eval/modulator-compilers :angle))


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
  :icon-name   :ear-listen
  :category    :external
  :evaluator   (get eval/modulator-evaluators :osc)
  :params      osc-params
  :per-point?  false
  :retrigger?  false
  :description "OSC input modulator"})


;; Unified Keyframe Param Modulator
;; Used internally to wrap spatial keyframe-modulator params as per-point modulators


(reg/register-modulator!
 {:id          :unified-keyframe-param
  :name        "Unified Keyframe Param"
  :icon        "⬡"
  :category    :internal
  :evaluator   (get eval/modulator-evaluators :unified-keyframe-param)
  :params      []  ;; No user-facing params - config is passed directly
  :per-point?  true
  :retrigger?  false
  :description "Internal modulator for per-point keyframe param evaluation"})
(reg/register-compiler! :unified-keyframe-param (get eval/modulator-compilers :unified-keyframe-param))
