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

(def ^:private hz-params
  "Hz-based oscillator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :frequency-hz :label "Frequency (Hz)" :type :float :min 0.1 :max 100.0 :default 1.0}])

(def ^:private square-hz-params
  "Square Hz parameters (has duty cycle)."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :frequency-hz :label "Frequency (Hz)" :type :float :min 0.1 :max 100.0 :default 1.0}
   {:key :duty-cycle :label "Duty Cycle" :type :float :min 0.0 :max 1.0 :default 0.5}])

(def ^:private linear-decay-params
  "Linear decay parameters."
  [{:key :start :label "Start" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :end :label "End" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :duration-ms :label "Duration (ms)" :type :float :min 10 :max 10000 :default 1000}
   {:key :trigger :label "Trigger Time" :type :float :min 0 :max 1000000 :default 0}])

(def ^:private halflife-decay-params
  "Half-life decay parameters."
  [{:key :start :label "Start" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :end :label "End" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :half-life-ms :label "Half-life (ms)" :type :float :min 10 :max 5000 :default 500}
   {:key :trigger :label "Trigger Time" :type :float :min 0 :max 1000000 :default 0}])

(def ^:private exp-decay-params
  "Exponential/beat-synced decay parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :decay-type :label "Type" :type :choice :choices [:linear :exp] :default :linear}])

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

(def ^:private constant-params
  "Constant value parameters."
  [{:key :value :label "Value" :type :float :min -10.0 :max 10.0 :default 0.0}])

(def ^:private point-index-params
  "Point index modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :wrap? :label "Wrap" :type :boolean :default false}])

(def ^:private point-wave-params
  "Point wave modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :cycles :label "Cycles" :type :float :min 0.1 :max 10.0 :default 1.0}
   {:key :wave-type :label "Wave" :type :choice :choices [:sine :triangle :sawtooth] :default :sine}])

(def ^:private pos-wave-params
  "Position wave modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :axis :label "Axis" :type :choice :choices [:x :y :radial :angle] :default :x}
   {:key :frequency :label "Frequency" :type :float :min 0.1 :max 10.0 :default 1.0}
   {:key :wave-type :label "Wave" :type :choice :choices [:sine :triangle :sawtooth] :default :sine}])

(def ^:private pos-scroll-params
  "Position scroll modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :axis :label "Axis" :type :choice :choices [:x :y] :default :x}
   {:key :speed :label "Speed" :type :float :min 0.1 :max 10.0 :default 1.0}
   {:key :wave-type :label "Wave" :type :choice :choices [:sine :triangle :sawtooth] :default :sine}])

(def ^:private rainbow-hue-params
  "Rainbow hue modulator parameters."
  [{:key :axis :label "Axis" :type :choice :choices [:x :y :radial :angle] :default :x}
   {:key :speed :label "Speed" :type :float :min 0.0 :max 360.0 :default 60.0}])

(def ^:private radial-params
  "Radial distance modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :normalize? :label "Normalize" :type :boolean :default true}])


;; Wave Modulators (UI-visible)


(reg/register-modulator!
 {:id          :sine
  :name        "Sine"
  :icon        "〰️"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :sine)
  :params      wave-params
  :per-point?  false
  :retrigger?  true
  :ui-visible? true})

(reg/register-modulator!
 {:id          :triangle
  :name        "Triangle"
  :icon        "△"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :triangle)
  :params      wave-params
  :per-point?  false
  :retrigger?  true
  :ui-visible? true})

(reg/register-modulator!
 {:id          :sawtooth
  :name        "Sawtooth"
  :icon        "⟋|"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :sawtooth)
  :params      wave-params
  :per-point?  false
  :retrigger?  true
  :ui-visible? true})

(reg/register-modulator!
 {:id          :square
  :name        "Square"
  :icon        "▭"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :square)
  :params      square-params
  :per-point?  false
  :retrigger?  true
  :ui-visible? true})

(reg/register-modulator!
 {:id          :random
  :name        "Random"
  :icon        "⚡"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :random)
  :params      random-params
  :per-point?  false
  :retrigger?  true
  :ui-visible? true})

(reg/register-modulator!
 {:id          :step
  :name        "Step"
  :icon        "⊟"
  :category    :wave
  :evaluator   (get eval/modulator-evaluators :step)
  :params      step-params
  :per-point?  false
  :retrigger?  true
  :ui-visible? true})


;; One-Shot Modulators (UI-visible)


(reg/register-modulator!
 {:id          :decay
  :name        "Decay"
  :icon        "↘"
  :category    :one-shot
  :evaluator   (get eval/modulator-evaluators :exp-decay)  ;; Maps to exp-decay evaluator
  :params      decay-params
  :per-point?  false
  :retrigger?  true
  :ui-visible? true})


;; Special/Per-Point Modulators (UI-visible)


(reg/register-modulator!
 {:id          :pos-x
  :name        "Position X"
  :icon        "↔"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :pos-x)
  :params      min-max-params
  :per-point?  true
  :retrigger?  false
  :ui-visible? true})

(reg/register-modulator!
 {:id          :pos-y
  :name        "Position Y"
  :icon        "↕"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :pos-y)
  :params      min-max-params
  :per-point?  true
  :retrigger?  false
  :ui-visible? true})

(reg/register-modulator!
 {:id          :radial
  :name        "Radial"
  :icon        "◎"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :radial)
  :params      radial-params
  :per-point?  true
  :retrigger?  false
  :ui-visible? true})

(reg/register-modulator!
 {:id          :point-index
  :name        "Point Index"
  :icon        "🔢"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :point-index)
  :params      point-index-params
  :per-point?  true
  :retrigger?  false
  :ui-visible? true})


;; Internal Modulators (not UI-visible)
;; Hz-based variants for BPM-independent animation


(reg/register-modulator!
 {:id          :sine-hz
  :name        "Sine (Hz)"
  :icon        "〰️"
  :category    :internal
  :evaluator   (get eval/modulator-evaluators :sine-hz)
  :params      hz-params
  :per-point?  false
  :retrigger?  false
  :ui-visible? false
  :description "Hz-based sine wave, BPM-independent"})

(reg/register-modulator!
 {:id          :square-hz
  :name        "Square (Hz)"
  :icon        "▭"
  :category    :internal
  :evaluator   (get eval/modulator-evaluators :square-hz)
  :params      square-hz-params
  :per-point?  false
  :retrigger?  false
  :ui-visible? false
  :description "Hz-based square wave, BPM-independent"})

(reg/register-modulator!
 {:id          :linear-decay
  :name        "Linear Decay"
  :icon        "↘"
  :category    :internal
  :evaluator   (get eval/modulator-evaluators :linear-decay)
  :params      linear-decay-params
  :per-point?  false
  :retrigger?  false
  :ui-visible? false
  :description "Linear decay with ms-based duration"})

(reg/register-modulator!
 {:id          :halflife-decay
  :name        "Half-life Decay"
  :icon        "↘"
  :category    :internal
  :evaluator   (get eval/modulator-evaluators :halflife-decay)
  :params      halflife-decay-params
  :per-point?  false
  :retrigger?  false
  :ui-visible? false
  :description "Exponential decay with half-life parameter"})

(reg/register-modulator!
 {:id          :exp-decay
  :name        "Exponential Decay"
  :icon        "↘"
  :category    :internal
  :evaluator   (get eval/modulator-evaluators :exp-decay)
  :params      exp-decay-params
  :per-point?  false
  :retrigger?  false
  :ui-visible? false
  :description "Beat-synced exponential decay"})

(reg/register-modulator!
 {:id          :beat-decay
  :name        "Beat Decay"
  :icon        "↘"
  :category    :internal
  :evaluator   (get eval/modulator-evaluators :beat-decay)  ;; Alias for exp-decay
  :params      exp-decay-params
  :per-point?  false
  :retrigger?  false
  :ui-visible? false
  :description "Alias for exponential decay (beat-synced)"})

(reg/register-modulator!
 {:id          :constant
  :name        "Constant"
  :icon        "━"
  :category    :internal
  :evaluator   (get eval/modulator-evaluators :constant)
  :params      constant-params
  :per-point?  false
  :retrigger?  false
  :ui-visible? false
  :description "Constant value output"})


;; External Input Modulators (not UI-visible in main dropdown)


(reg/register-modulator!
 {:id          :midi
  :name        "MIDI CC"
  :icon        "🎹"
  :category    :external
  :evaluator   (get eval/modulator-evaluators :midi)
  :params      midi-params
  :per-point?  false
  :retrigger?  false
  :ui-visible? false
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
  :ui-visible? false
  :description "OSC input modulator"})


;; Additional Per-Point Modulators (internal, for programmatic use)


(reg/register-modulator!
 {:id          :angle
  :name        "Angle"
  :icon        "∠"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :angle)
  :params      min-max-params
  :per-point?  true
  :retrigger?  false
  :ui-visible? false
  :description "Position angle from center"})

(reg/register-modulator!
 {:id          :point-wave
  :name        "Point Wave"
  :icon        "〰️"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :point-wave)
  :params      point-wave-params
  :per-point?  true
  :retrigger?  false
  :ui-visible? false
  :description "Wave based on point index"})

(reg/register-modulator!
 {:id          :pos-wave
  :name        "Position Wave"
  :icon        "〰️"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :pos-wave)
  :params      pos-wave-params
  :per-point?  true
  :retrigger?  false
  :ui-visible? false
  :description "Wave based on position"})

(reg/register-modulator!
 {:id          :pos-scroll
  :name        "Position Scroll"
  :icon        "➡️"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :pos-scroll)
  :params      pos-scroll-params
  :per-point?  true
  :retrigger?  false
  :ui-visible? false
  :description "Scrolling effect along an axis"})

(reg/register-modulator!
 {:id          :rainbow-hue
  :name        "Rainbow Hue"
  :icon        "🌈"
  :category    :special
  :evaluator   (get eval/modulator-evaluators :rainbow-hue)
  :params      rainbow-hue-params
  :per-point?  true
  :retrigger?  false
  :ui-visible? false
  :description "Rainbow hue based on position"})


;; Registration Summary
;; Total: 24 modulator types registered
;; 
;; UI-Visible (11):
;;   Wave (6):     :sine, :triangle, :sawtooth, :square, :random, :step
;;   One-Shot (1): :decay
;;   Special (4):  :pos-x, :pos-y, :radial, :point-index
;;
;; Internal (7):
;;   Hz-based (2): :sine-hz, :square-hz
;;   Decay (4):    :linear-decay, :halflife-decay, :exp-decay, :beat-decay
;;   Other (1):    :constant
;;
;; External (2):   :midi, :osc
;;
;; Hidden Special (5): :angle, :point-wave, :pos-wave, :pos-scroll, :rainbow-hue
