(ns laser-show.animation.modulation
  "Parameter modulation system for effects.
   
   Modulators are represented as pure data configs that can be serialized to EDN.
   The modulator functions are created at runtime when parameters are resolved.
   
   Usage:
   ;; Static value
   {:effect-id :scale :params {:x-scale 1.5}}
   
   ;; Modulated value (pure data config - serializable!)
   {:effect-id :scale :params {:x-scale {:type :sine :min 0.8 :max 1.2 :period 0.5}}}
   
   ;; MIDI controlled
   {:effect-id :scale :params {:x-scale {:type :midi :channel 1 :cc 7 :min 0.5 :max 2.0}}}"
  (:require
   [laser-show.animation.time :as time]
   [laser-show.common.util :as u]))

;; Forward declarations for modulator-config? and evaluators
(declare modulator-config?)
;; Forward declarations for beat calculation helpers
(declare get-beats-from-context get-ms-from-context)



;; Modulation Context


(defn make-context
  "Create a modulation context for parameter resolution.
   
   Parameters:
   - time-ms: Current time in milliseconds
   - bpm: Current BPM
   - trigger-time: Time when the cue/effect was triggered (optional, for once-mode modulators)
   - midi-state: Map of {[channel cc] -> value} (optional)
   - osc-state: Map of {path -> value} (optional)
   
   Beat Accumulation Parameters (for smooth BPM-change animation):
   - accumulated-beats: Running total of beats since cue trigger (incremental)
   - accumulated-ms: Running total of ms since cue trigger (incremental)
   - phase-offset: Current smoothed phase correction offset
   - effective-beats: accumulated-beats + phase-offset (use for looping modulators)"
  [{:keys [time-ms bpm trigger-time midi-state osc-state
           accumulated-beats accumulated-ms phase-offset]
    :or {midi-state {} osc-state {}
         accumulated-beats 0.0 accumulated-ms 0.0 phase-offset 0.0}}]
  {:time-ms time-ms
   :bpm bpm
   :trigger-time trigger-time
   :midi-state midi-state
   :osc-state osc-state
   ;; Beat accumulation fields
   :accumulated-beats (or accumulated-beats 0.0)
   :accumulated-ms (or accumulated-ms 0.0)
   :phase-offset (or phase-offset 0.0)
   :effective-beats (+ (or accumulated-beats 0.0) (or phase-offset 0.0))})



;; Period/Frequency Conversion


(defn- period->frequency
  "Convert period (beats per cycle) to frequency (cycles per beat).
   Period of 0 is treated as infinite frequency (returns a large number)."
  ^double [^double period]
  (if (zero? period)
    1000000.0  ; effectively instant
    (/ 1.0 period)))


;; Once-Mode Helper Functions


(defn- calculate-once-progress
  "Calculate progress (0.0 to 1.0) for once-mode modulators.
   
   Parameters:
   - time-ms: Current time in milliseconds
   - trigger-time: Time when modulator was triggered (falls back to 0 if nil)
   - duration: Duration value
   - time-unit: :beats or :seconds
   - bpm: Current BPM (required for :beats time-unit)
   
   Returns: Progress value clamped to 0.0-1.0"
  [time-ms trigger-time duration time-unit bpm]
  (let [start-time (double (or trigger-time 0.0))
        elapsed (- time-ms start-time)
        duration-ms (if (= time-unit :seconds)
                      (* duration 1000.0)
                      (time/beats->ms duration bpm))]
    (if (pos? duration-ms)
      (u/clamp (/ elapsed duration-ms) 0.0 1.0)
      1.0)))

(defn- resolve-trigger-time
  "Resolve trigger time from either a fixed value or an atom reference.
   Returns the trigger time as a number, or nil if not available."
  [trigger-source]
  (cond
    (instance? clojure.lang.IDeref trigger-source) @trigger-source
    (number? trigger-source) trigger-source
    :else nil))

(defn- calculate-modulator-phase
  "Calculate the phase for a modulator based on loop-mode and timing settings.
   
   For looping modulators, uses effective-beats (accumulated-beats + phase-offset)
   which enables smooth animation during BPM changes and phase resync on tap tempo.
   
   For once-mode modulators, uses raw accumulated-beats without phase correction
   since these should play through exactly once from trigger without resync effects.
   
   Parameters:
   - context: Modulation context with timing fields
   - period: Beats per cycle (converted to frequency internally)
   - phase-param: Phase offset parameter (0.0-1.0)
   - loop-mode: :loop or :once
   - duration: Duration for once-mode
   - time-unit: :beats or :seconds
   - trigger-override: Optional trigger time to use instead of context's trigger-time
   
   Returns: Phase value for oscillation"
  ([context period phase-param loop-mode duration time-unit]
   (calculate-modulator-phase context period phase-param loop-mode duration time-unit nil))
  ([{:keys [time-ms bpm trigger-time] :as context}
    period phase-param loop-mode duration time-unit trigger-override]
   (let [bpm (double (or bpm 120.0))
         frequency (period->frequency (double period))
         phase-param (double (or phase-param 0.0))]
     (if (= loop-mode :once)
       ;; Once mode: use raw accumulated-beats (no phase correction)
       ;; for predictable one-shot behavior from trigger
       (let [effective-trigger-time (or (resolve-trigger-time trigger-override) trigger-time)
             progress (calculate-once-progress (or time-ms 0) effective-trigger-time duration time-unit bpm)]
         (+ (* progress frequency) phase-param))
       ;; Loop mode: use effective-beats (with phase correction for tap resync)
       ;; Falls back to calculating from time-ms/bpm for backward compatibility
       (let [beats (get-beats-from-context context)]
         (+ (* beats frequency) phase-param))))))


;; Per-Point Context Detection


(def ^:private per-point-types
  "Modulator types that require per-point context (x, y, point-index, point-count)."
  #{:pos-x :pos-y :radial :angle :point-index :point-wave :pos-wave :pos-scroll :rainbow-hue})

(defn config-requires-per-point?
  "Check if a modulator config requires per-point context.
   Returns true if the config's type is in the per-point-types set."
  [config]
  (and (modulator-config? config)
       (contains? per-point-types (:type config))))

(defn any-param-requires-per-point?
  "Check if any parameter in a params map requires per-point context.
   Recursively checks all values, including nested maps and collections."
  [params]
  (cond
    (modulator-config? params)
    (config-requires-per-point? params)

    (map? params)
    (some any-param-requires-per-point? (vals params))

    (coll? params)
    (some any-param-requires-per-point? params)

    :else
    false))


;; Beat Calculation Helper

(defn- get-beats-from-context
  "Get the current beat count from context with proper fallback.
   
   Priority:
   1. effective-beats (includes phase correction for looping)
   2. accumulated-beats (raw incremental beats)
   3. Calculate from time-ms and bpm (backward compatibility with tests)
   4. Default to 0.0
   
   This ensures backward compatibility with contexts that only have time-ms/bpm."
  ^double [{:keys [effective-beats accumulated-beats time-ms bpm]}]
  (double
   (or effective-beats
       accumulated-beats
       (when (and time-ms bpm (pos? bpm))
         (time/ms->beats time-ms bpm))
       0.0)))

(defn- get-ms-from-context
  "Get the current milliseconds from context with proper fallback.
   
   Priority:
   1. accumulated-ms (incremental since trigger)
   2. time-ms (absolute timestamp - backward compatibility with tests)
   3. Default to 0.0"
  ^double [{:keys [accumulated-ms time-ms]}]
  (double (or accumulated-ms time-ms 0.0)))


;; Internal Modulator Implementations


(defn- eval-sine
  "Evaluate sine wave modulator."
  [{:keys [min max period phase loop-mode duration time-unit]
    :or {min 0.0 max 1.0 period 1.0 phase 0.0 loop-mode :loop duration 1.0 time-unit :beats}}
   context]
  (let [p (calculate-modulator-phase context period phase loop-mode duration time-unit)]
    (time/oscillate (double min) (double max) p :sine)))

(defn- eval-triangle
  "Evaluate triangle wave modulator."
  [{:keys [min max period phase loop-mode duration time-unit]
    :or {min 0.0 max 1.0 period 1.0 phase 0.0 loop-mode :loop duration 1.0 time-unit :beats}}
   context]
  (let [p (calculate-modulator-phase context period phase loop-mode duration time-unit)]
    (time/oscillate (double min) (double max) p :triangle)))

(defn- eval-sawtooth
  "Evaluate sawtooth wave modulator.
   Uses effective-beats for smooth BPM-change animation."
  [{:keys [min max period phase]
    :or {min 0.0 max 1.0 period 1.0 phase 0.0}}
   context]
  (let [freq (period->frequency (double period))
        beats (get-beats-from-context context)
        p (+ (* beats freq) (double phase))]
    (time/oscillate (double min) (double max) p :sawtooth)))

(defn- eval-square
  "Evaluate square wave modulator.
   Uses effective-beats for smooth BPM-change animation."
  [{:keys [min max period duty-cycle phase]
    :or {min 0.0 max 1.0 period 1.0 duty-cycle 0.5 phase 0.0}}
   context]
  (let [freq (period->frequency (double period))
        beats (get-beats-from-context context)
        cycle-phase (mod (+ (* beats freq) (double phase)) 1.0)]
    (if (< cycle-phase (double duty-cycle))
      (double max)
      (double min))))

(defn- eval-sine-hz
  "Evaluate sine wave at fixed Hz frequency.
   Uses accumulated-ms for smooth animation unaffected by BPM changes."
  [{:keys [min max frequency-hz]
    :or {min 0.0 max 1.0 frequency-hz 1.0}}
   context]
  (let [ms (get-ms-from-context context)
        p (* ms (double frequency-hz) 0.001)]  ;; Convert to cycles
    (time/oscillate (double min) (double max) p :sine)))

(defn- eval-square-hz
  "Evaluate square wave at fixed Hz frequency.
   Uses accumulated-ms for smooth animation unaffected by BPM changes."
  [{:keys [min max frequency-hz duty-cycle]
    :or {min 0.0 max 1.0 frequency-hz 1.0 duty-cycle 0.5}}
   context]
  (let [ms (get-ms-from-context context)
        p (mod (* ms (double frequency-hz) 0.001) 1.0)]
    (if (< p (double duty-cycle))
      (double max)
      (double min))))

(defn- eval-linear-decay
  "Evaluate linear decay modulator."
  [{:keys [start end duration-ms trigger]
    :or {start 1.0 end 0.0 duration-ms 1000 trigger 0}}
   {:keys [time-ms]}]
  (let [elapsed (- (double time-ms) (double trigger))
        progress (min 1.0 (/ elapsed (double duration-ms)))
        range-v (- (double end) (double start))]
    (+ (double start) (* progress range-v))))

(defn- eval-halflife-decay
  "Evaluate half-life based exponential decay."
  [{:keys [start end half-life-ms trigger]
    :or {start 1.0 end 0.0 half-life-ms 500 trigger 0}}
   {:keys [time-ms]}]
  (let [elapsed (- (double time-ms) (double trigger))
        range-v (- (double start) (double end))
        ln2 (Math/log 2.0)
        decay-factor (Math/exp (- (/ (* elapsed ln2) (double half-life-ms))))]
    (+ (double end) (* decay-factor range-v))))

(defn- eval-exp-decay
  "Evaluate exponential decay (beat-synced).
   Uses effective-beats for smooth BPM-change animation."
  [{:keys [min max decay-type]
    :or {min 0.0 max 1.0 decay-type :linear}}
   context]
  (let [beats (get-beats-from-context context)
        phase (mod beats 1.0)
        start-v (double max)
        end-v (double min)]
    (case decay-type
      :exp (let [range-exp (- start-v end-v)
                 decay-factor (Math/exp (* (- phase) 3.0))]
             (+ end-v (* decay-factor range-exp)))
      ;; :linear is default
      (let [range-v (- end-v start-v)]
        (+ start-v (* phase range-v))))))

(defn- eval-random
  "Evaluate random modulator.
   Uses effective-beats for smooth BPM-change animation."
  [{:keys [min max changes-per-beat]
    :or {min 0.0 max 1.0 changes-per-beat 1.0}}
   context]
  (let [beats (get-beats-from-context context)
        seed (long (* beats (double changes-per-beat)))
        rng (java.util.Random. seed)
        t (.nextDouble ^java.util.Random rng)
        range-v (- (double max) (double min))]
    (+ (double min) (* t range-v))))

(defn- eval-step
  "Evaluate step modulator.
   Uses effective-beats for smooth BPM-change animation."
  [{:keys [values steps-per-beat]
    :or {values [0 1] steps-per-beat 1.0}}
   context]
  (let [beats (get-beats-from-context context)
        idx (mod (long (* beats (double steps-per-beat))) (count values))]
    (nth values idx)))

(defn- eval-midi
  "Evaluate MIDI CC modulator."
  [{:keys [channel cc min max]
    :or {channel 1 cc 1 min 0.0 max 1.0}}
   {:keys [midi-state]}]
  (let [cc-val (double (get-in midi-state [[channel cc]] 0))
        range-v (- (double max) (double min))]
    (+ (double min) (* (/ cc-val 127.0) range-v))))

(defn- eval-osc
  "Evaluate OSC parameter modulator."
  [{:keys [path min max]
    :or {path "/control" min 0.0 max 1.0}}
   {:keys [osc-state]}]
  (let [osc-val (double (get osc-state path 0.0))
        range-v (- (double max) (double min))]
    (+ (double min) (* osc-val range-v))))

(defn- eval-constant
  "Evaluate constant value modulator."
  [{:keys [value min] :or {value 0.0 min 0.0}} _context]
  (or value min))

(defn- eval-point-index
  "Evaluate point index modulator."
  [{:keys [min max wrap?]
    :or {min 0.0 max 1.0 wrap? false}}
   {:keys [point-index point-count]}]
  (if (and point-index point-count (pos? point-count))
    (let [t (/ (double point-index) (clojure.core/max 1.0 (dec (double point-count))))
          range-v (- (double max) (double min))]
      (+ (double min) (* (if wrap? (mod t 1.0) t) range-v)))
    (double min)))

(defn- eval-point-wave
  "Evaluate point index wave modulator."
  [{:keys [min max cycles wave-type]
    :or {min 0.0 max 1.0 cycles 1.0 wave-type :sine}}
   {:keys [point-index point-count]}]
  (if (and point-index point-count (pos? point-count))
    (let [t (/ (double point-index) (clojure.core/max 1.0 (double point-count)))
          phase (* t (double cycles))]
      (time/oscillate (double min) (double max) phase wave-type))
    (double min)))

(defn- eval-pos-x
  "Evaluate position X modulator."
  [{:keys [min max] :or {min 0.0 max 1.0}}
   {:keys [x]}]
  (if x
    (let [t (/ (+ (double x) 1.0) 2.0)  ; normalize -1..1 to 0..1
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))

(defn- eval-pos-y
  "Evaluate position Y modulator."
  [{:keys [min max] :or {min 0.0 max 1.0}}
   {:keys [y]}]
  (if y
    (let [t (/ (+ (double y) 1.0) 2.0)
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))

(defn- eval-radial
  "Evaluate position radial modulator."
  [{:keys [min max normalize?]
    :or {min 0.0 max 1.0 normalize? true}}
   {:keys [x y]}]
  (if (and x y)
    (let [dist (Math/sqrt (+ (* (double x) (double x))
                             (* (double y) (double y))))
          max-dist (if normalize? (Math/sqrt 2.0) 1.0)
          t (clojure.core/min 1.0 (/ dist max-dist))
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))

(defn- eval-angle
  "Evaluate position angle modulator."
  [{:keys [min max] :or {min 0.0 max 1.0}}
   {:keys [x y]}]
  (if (and x y)
    (let [angle (Math/atan2 (double y) (double x))
          t (/ (+ angle Math/PI) (* 2.0 Math/PI))  ; normalize -π..π to 0..1
          range-v (- (double max) (double min))]
      (+ (double min) (* t range-v)))
    (double min)))

(defn- eval-pos-wave
  "Evaluate position wave modulator."
  [{:keys [min max axis frequency wave-type]
    :or {min 0.0 max 1.0 axis :x frequency 1.0 wave-type :sine}}
   {:keys [x y]}]
  (if (and x y)
    (let [pos-val (case axis
                    :x (double x)
                    :y (double y)
                    :radial (Math/sqrt (+ (* (double x) (double x))
                                          (* (double y) (double y))))
                    :angle (/ (+ (Math/atan2 (double y) (double x)) Math/PI)
                              (* 2.0 Math/PI)))
          phase (* pos-val (double frequency))]
      (time/oscillate (double min) (double max) phase wave-type))
    (double min)))

(defn- eval-pos-scroll
  "Evaluate position scroll modulator.
   Uses effective-beats for smooth BPM-change animation."
  [{:keys [min max axis speed wave-type]
    :or {min 0.0 max 1.0 axis :x speed 1.0 wave-type :sine}}
   {:keys [x y] :as context}]
  (if (and x y)
    (let [pos-val (case axis :x (double x) :y (double y))
          beats (get-beats-from-context context)
          time-offset (* (mod beats 1.0) (double speed))
          phase (+ pos-val time-offset)]
      (time/oscillate (double min) (double max) phase wave-type))
    (double min)))

(defn- eval-rainbow-hue
  "Evaluate rainbow hue modulator.
   Uses accumulated-ms for smooth animation unaffected by BPM changes."
  [{:keys [axis speed] :or {axis :x speed 60.0}}
   {:keys [x y] :as context}]
  (let [ms (get-ms-from-context context)]
    (if (and x y)
      (let [position (case axis
                       :x (/ (+ (double x) 1.0) 2.0)
                       :y (/ (+ (double y) 1.0) 2.0)
                       :radial (Math/sqrt (+ (* (double x) (double x))
                                             (* (double y) (double y))))
                       :angle (/ (+ (Math/atan2 (double y) (double x)) Math/PI)
                                 (* 2.0 Math/PI)))
            time-offset (mod (* (/ ms 1000.0) (double speed)) 360.0)]
        (mod (+ (* position 360.0) time-offset) 360.0))
      0.0)))


;; Modulator Evaluators Registry

;; 
;; This map defines all available modulator types and their evaluation functions.
;; New modulator types should be added here.

(def ^:private modulator-evaluators
  "Map of modulator type keywords to their evaluation functions.
   Each function takes [config context] and returns a value."
  {:sine         eval-sine
   :triangle     eval-triangle
   :sawtooth     eval-sawtooth
   :square       eval-square
   :sine-hz      eval-sine-hz
   :square-hz    eval-square-hz
   :linear-decay eval-linear-decay
   :halflife-decay eval-halflife-decay
   :exp-decay    eval-exp-decay
   :beat-decay   eval-exp-decay  ; alias
   :random       eval-random
   :step         eval-step
   :midi         eval-midi
   :osc          eval-osc
   :constant     eval-constant
   :point-index  eval-point-index
   :point-wave   eval-point-wave
   :pos-x        eval-pos-x
   :pos-y        eval-pos-y
   :radial       eval-radial
   :angle        eval-angle
   :pos-wave     eval-pos-wave
   :pos-scroll   eval-pos-scroll
   :rainbow-hue  eval-rainbow-hue})

(def ^:private modulator-types
  "Set of valid modulator type keywords, derived from evaluators registry."
  (set (keys modulator-evaluators)))

(defn modulator-config?
  "Check if a value is a modulator config map (pure data representation).
   Modulator configs are maps with a :type key that matches a known modulator type."
  [x]
  (and (map? x)
       (contains? x :type)
       (contains? modulator-types (:type x))))


;; Main Evaluation Function


(defn evaluate-modulator
  "Evaluate a modulator config with the given context.
   Uses the modulator-evaluators registry to look up the evaluator fn.
   Returns the calculated value."
  [config context]
  (if-let [eval-fn (get modulator-evaluators (:type config))]
    (eval-fn config context)
    ;; Default fallback for unknown types
    (get config :value (get config :min 0.0))))


;; Parameter Resolution


(defn resolve-param
  "Resolve a parameter value.
   - If the param is a modulator config map (pure data), evaluate it.
   - If it's a static value (number, string, etc.), return it as-is."
  [param context]
  (if (modulator-config? param)
    (evaluate-modulator param context)
    param))

(defn resolve-params
  "Resolve all parameters in a params map."
  [params context]
  (into {}
        (map (fn [[k v]] [k (resolve-param v context)]))
        params))


;; Preset Modulator Configs


(def presets
  "Common modulator preset configs (pure data)."
  {:gentle-pulse   {:type :sine :min 0.7 :max 1.0 :period 1.0}
   :strong-pulse   {:type :sine :min 0.3 :max 1.0 :period 0.5}
   :breathe        {:type :sine :min 0.5 :max 1.0 :period 4.0}
   :strobe-4x      {:type :square :min 0.0 :max 1.0 :period 0.25 :duty-cycle 0.1}
   :strobe-8x      {:type :square :min 0.0 :max 1.0 :period 0.125 :duty-cycle 0.1}
   :beat-flash     {:type :exp-decay :max 2.0 :min 1.0 :decay-type :exp}
   :ramp-up        {:type :sawtooth :min 0.0 :max 1.0 :period 1.0}
   :ramp-down      {:type :sawtooth :min 1.0 :max 0.0 :period 1.0}
   :wobble         {:type :sine :min 0.9 :max 1.1 :period 0.25}
   :fade-x         {:type :pos-x :min 0.0 :max 1.0}
   :fade-y         {:type :pos-y :min 0.0 :max 1.0}
   :fade-radial    {:type :radial :min 1.0 :max 0.0}
   :glow-center    {:type :radial :min 1.0 :max 0.3}
   :rainbow-x      {:type :rainbow-hue :axis :x :speed 60.0}
   :rainbow-y      {:type :rainbow-hue :axis :y :speed 60.0}
   :rainbow-angle  {:type :rainbow-hue :axis :angle :speed 60.0}
   :fade-path      {:type :point-index :min 0.0 :max 1.0}
   :wave-path      {:type :point-wave :min 0.3 :max 1.0 :cycles 2.0}})

(defn preset
  "Get a preset modulator config by keyword.
   Returns the config map or nil if not found."
  [preset-key]
  (get presets preset-key))

