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


(def source-labels
  "How the editor names what a reaction follows (a :choice param's :labels)."
  {:own "Its cue's start"
   :hit "HIT"
   :accent "ACCENT"
   :flash "FLASH"
   :vibe "Vibe change"
   :any "HIT, ACCENT or FLASH"
   :cue "Any cue start"})

(def restart-on-param
  "Restart on: count from the newest press of a source (pulses/rebase)
   instead of from the cue's start. :own is today's behaviour. Time-driven
   keyframe animations offer the same (keyframe-modulator-panel)."
  {:key :restart-on :label "Restart on" :type :choice
   :choices [:own :hit :accent :flash :vibe :any :cue] :labels source-labels :default :own})

(defn- source-param
  "The source a pulse modulator follows."
  [default]
  {:key :source :label "Follows" :type :choice
   :choices [:hit :accent :flash :vibe :any :cue] :labels source-labels :default default})

(def ^:private wave-params
  "Common wave modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
   {:key :time-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
   {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
   {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}
   {:key :phase :label "Phase" :type :float :min 0.0 :max 1.0 :default 0.0}
   restart-on-param])

(def ^:private square-params
  "Square wave parameters (has duty cycle)."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
   {:key :time-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
   {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
   {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}
   {:key :duty-cycle :label "Duty Cycle" :type :float :min 0.0 :max 1.0 :default 0.5}
   {:key :phase :label "Phase" :type :float :min 0.0 :max 1.0 :default 0.0}
   restart-on-param])

(def ^:private random-params
  "Random modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :period :label "Period" :type :float :min 0.0625 :max 16.0 :default 1.0}
   {:key :time-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
   {:key :loop-mode :label "Mode" :type :choice :choices [:loop :once] :default :loop}
   {:key :once-periods :label "# Periods" :type :float :min 0.125 :max 8.0 :default 1.0}
   restart-on-param])

(def ^:private decay-params
  "Decay modulator parameters."
  [{:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :duration :label "Duration" :type :float :min 0.0625 :max 16.0 :default 1.0}
   {:key :time-unit :label "Unit" :type :choice :choices [:beats :seconds] :default :beats}
   {:key :decay-curve :label "Curve" :type :choice :choices [:linear :exp :log] :default :exp}
   restart-on-param])

(def ^:private envelope-params
  "Envelope: :min at rest, :max at its peak (it may be below :min, a dip)."
  [(source-param :hit)
   {:key :min :label "Rest" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Peak" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :attack :label "Attack" :type :float :min 0.0 :max 4.0 :default 0.0}
   {:key :release :label "Release" :type :float :min 0.0625 :max 16.0 :default 1.0}
   {:key :curve :label "Curve" :type :choice :choices [:linear :exp :log] :default :exp}
   {:key :hold? :label "Hold while down" :type :boolean :default true}])

(def ^:private step-params
  "Step: even steps from :min to :max, or the palette's values when it has any."
  [(source-param :hit)
   {:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :steps :label "Steps" :type :int :min 2 :max 16 :default 4}
   {:key :order :label "Order" :type :choice :choices [:up :down :ping-pong :scatter] :default :up}
   {:key :wrap? :label "Wrap round" :type :boolean :default false}
   {:key :glide :label "Glide" :type :float :min 0.0 :max 4.0 :default 0.0}
   {:key :values :label "Palette" :type :number-list :default []}])

(def ^:private energy-params
  "Energy: :min at rest, :max when the taps come fast enough."
  [(source-param :any)
   {:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :rise :label "Rise per tap" :type :float :min 0.05 :max 1.0 :default 0.25}
   {:key :fall :label "Fall per beat" :type :float :min 0.01 :max 2.0 :default 0.25}])

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

(def ^:private macro-params
  "Fader (:macro) parameters: the fader it follows, whichever control is
   learned for it (plans/2026-11-18-vibe-mode/05), the range its 0..1 maps
   to, and where it is taken to be (0..1) until it first moves."
  [{:key :slot :label "Fader" :type :choice :choices [:slider-1 :slider-2]
    :labels {:slider-1 "1: Intensity" :slider-2 "2: Morph"} :default :slider-1}
   {:key :min :label "Min" :type :float :min -10.0 :max 10.0 :default 0.0}
   {:key :max :label "Max" :type :float :min -10.0 :max 10.0 :default 1.0}
   {:key :default :label "Until moved" :type :float :min 0.0 :max 1.0 :default 0.0}])

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
  :evaluator   (get eval/modulator-evaluators :decay)
  :params      decay-params
  :per-point?  false
  :retrigger?  true})


;; Pulse Modulators: they follow a press (plans/2026-11-18-vibe-mode/04)


(reg/register-modulator!
 {:id          :envelope
  :name        "Envelope"
  :icon        "⋀"
  :category    :pulse
  :evaluator   (get eval/modulator-evaluators :envelope)
  :params      envelope-params
  :per-point?  false
  :retrigger?  false
  :description "On press: punch on each press, or swell while held"})

(reg/register-modulator!
 {:id          :step
  :name        "Step"
  :icon        "⊓"
  :category    :pulse
  :evaluator   (get eval/modulator-evaluators :step)
  :params      step-params
  :per-point?  false
  :retrigger?  false
  :description "On press: the next value on each press"})

(reg/register-modulator!
 {:id          :energy
  :name        "Energy"
  :icon        "⚡"
  :category    :pulse
  :evaluator   (get eval/modulator-evaluators :energy)
  :params      energy-params
  :per-point?  false
  :retrigger?  false
  :description "On press: rises as you tap, falls when you stop"})


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

(reg/register-modulator!
 {:id          :macro
  :name        "Fader"
  :icon-name   :sliders
  :category    :external
  :evaluator   (get eval/modulator-evaluators :macro)
  :params      macro-params
  :per-point?  false
  :retrigger?  false
  :description "Fader 1 (Intensity) or 2 (Morph): whichever control is learned for it"})


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
