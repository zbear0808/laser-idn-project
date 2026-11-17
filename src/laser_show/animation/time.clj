(ns laser-show.animation.time
  "Time utilities for BPM-synchronized effects.
   Handles BPM conversions, phase calculations, and time-based computations.
   
   Also provides context-aware beat extraction and modulator phase calculation
   utilities used by the modulator evaluation system." 
  (:require
    [laser-show.common.util :as u]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defn bpm->ms-per-beat
  "Convert BPM to milliseconds per beat."
  ^double [^double bpm]
  (/ 60000.0 bpm))

(defn beats->ms
  "Convert a number of beats to milliseconds at the given BPM."
  ^double [^double beats ^double bpm]
  (* beats (bpm->ms-per-beat bpm)))

(defn ms->beats
  "Convert milliseconds to beats at the given BPM."
  ^double [^double ms ^double bpm]
  (/ ms (bpm->ms-per-beat bpm)))


;; Waveform Generation


(defn sine-wave
  "Generate a cosine wave value (-1.0 to 1.0) at the given phase (0.0 to 1.0).
   Starts at peak (1.0) when phase=0 for intuitive visual behavior."
  ^double [^double phase]
  (Math/cos (* phase 2.0 Math/PI)))

(defn sine-wave-normalized
  "Generate a normalized cosine wave value (0.0 to 1.0) at the given phase.
   Starts at peak (1.0) when phase=0."
  ^double [^double phase]
  (* 0.5 (+ 1.0 (sine-wave phase))))

(defn triangle-wave
  "Generate a triangle wave value (-1.0 to 1.0) at the given phase (0.0 to 1.0).
   Starts at peak (1.0) when phase=0 for intuitive visual behavior."
  ^double [^double phase]
  (let [p (u/fmod (+ phase 0.5) 1.0)]  ;; Shift by 0.5 to start at peak
    (if (< p 0.5)
      (- (* 4.0 p) 1.0)
      (- 3.0 (* 4.0 p)))))

(defn triangle-wave-normalized
  "Generate a normalized triangle wave value (0.0 to 1.0) at the given phase.
   Starts at peak (1.0) when phase=0."
  ^double [^double phase]
  (* 0.5 (+ 1.0 (triangle-wave phase))))

(defn sawtooth-wave-normalized
  "Generate a normalized sawtooth wave value (0.0 to 1.0) at the given phase.
   Starts at peak (1.0) when phase=0, ramps down to 0.0."
  ^double [^double phase]
  (- 1.0 (u/fmod phase 1.0)))

(defn square-wave-normalized
  "Generate a normalized square wave value (0.0 or 1.0) at the given phase."
  (^double [^double phase]
   (square-wave-normalized phase 0.5))
  (^double [^double phase ^double duty-cycle]
   (if (< (u/fmod phase 1.0) duty-cycle) 1.0 0.0)))


;; Value Oscillation


(defn oscillate
  "Oscillate a value between min-val and max-val based on phase.
   waveform can be :sine, :triangle, :sawtooth, or :square."
  (^double [^double min-val ^double max-val ^double phase]
   (oscillate min-val max-val phase :sine))
  ([^double min-val ^double max-val ^double phase waveform]
   (let [normalized-phase (u/fmod phase 1.0)
         t (case waveform
             :sine (sine-wave-normalized normalized-phase)
             :triangle (triangle-wave-normalized normalized-phase)
             :sawtooth (sawtooth-wave-normalized normalized-phase)
             :square (square-wave-normalized normalized-phase)
             (sine-wave-normalized normalized-phase))]
     (+ min-val (* t (- max-val min-val))))))


;; Period/Frequency Conversion


(defn period->frequency
  "Convert period (beats per cycle) to frequency (cycles per beat).
   Period of 0 is treated as infinite frequency (returns a large number)."
  ^double [^double period]
  (if (zero? period)
    1000000.0  ; effectively instant
    (/ 1.0 period)))


;; Context-Aware Beat/Time Extraction


(defn get-beats-from-context
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
       (when (and time-ms bpm (pos? (double bpm)))
         (ms->beats time-ms bpm))
       0.0)))


(defn get-seconds-from-context
  "Get elapsed real time in seconds from context.

   Prefers accumulated-ms (time since cue trigger / clock start), falling back
   to time-ms for contexts without accumulators."
  ^double [{:keys [accumulated-ms time-ms]}]
  (/ (double (or accumulated-ms time-ms 0.0)) 1000.0))

(defn get-loop-time-from-context
  "Get the loop-mode time base in the given unit: beats (effective-beats, BPM
   synced) for :beats, real seconds for :seconds."
  ^double [context time-unit]
  (if (= time-unit :seconds)
    (get-seconds-from-context context)
    (get-beats-from-context context)))

(defn elapsed
  "Time since the cue started (since the clip started, in a clip's context),
   in time-unit: seconds from :accumulated-ms, otherwise beats from
   :accumulated-beats, the raw count without the phase glide. A context
   without accumulators (built by hand) falls back to time-ms - trigger-time,
   as beats at its BPM."
  ^double [{:keys [accumulated-beats accumulated-ms time-ms trigger-time bpm]} time-unit]
  (let [fallback-ms (- (double (or time-ms 0.0)) (double (or trigger-time 0.0)))]
    (if (= time-unit :seconds)
      (/ (double (or accumulated-ms fallback-ms)) 1000.0)
      (double (or accumulated-beats
                  (ms->beats fallback-ms (double (or bpm 120.0))))))))


;; Once-Mode Progress Calculation


(defn calculate-once-progress
  "Progress (0.0 to 1.0) of a once-mode modulator that lasts duration
   (time-unit :beats or :seconds): its elapsed time in context over duration.
   Beats are counted as beats, so a tempo change doesn't move a beats-unit
   modulator. A duration of 0 or less is done (1.0)."
  ^double [context duration time-unit]
  (let [duration' (double (or duration 1.0))]
    (if (pos? duration')
      (min 1.0 (max 0.0 (/ (elapsed context time-unit) duration')))
      1.0)))

(defn calculate-once-mode-phase-data
  "Calculate phase data for once-mode modulators with rich metadata.
   
   Parameters:
   - config: Map with :period, :phase, :once-periods, :time-unit
   - context: Modulation context with the cue's accumulators (see elapsed)
   
   Returns map with:
   - :final-phase - the phase value to use for oscillation (0.0-1.0)
   - :total-phase - raw total phase progression (can exceed 1.0)
   - :done? - true if animation has completed all cycles
   - :num-cycles - number of cycles to complete
   - :phase - the phase offset parameter"
  [{:keys [period phase once-periods time-unit]
    :or {period 1.0 phase 0.0 once-periods 1.0 time-unit :beats}}
   context]
  (let [num-cycles (double (or once-periods 1.0))
        total-duration (* (double period) num-cycles)
        progress (calculate-once-progress context total-duration time-unit)
        phase (double phase)
        total-phase (+ (* progress num-cycles) phase)
        cycle-phase (u/fmod total-phase 1.0)
        done? (>= progress 1.0)
        final-phase (if done?
                      (u/fmod (+ num-cycles phase) 1.0)
                      cycle-phase)]
    (u/->map final-phase total-phase done? num-cycles phase)))


;; Modulator Phase Calculation


(defn calculate-loop-phase
  "Phase of a looping modulator: the loop time base in time-unit (effective
   beats, which follow tap-tempo resyncs, or real seconds) times the
   frequency (1 / period), plus the phase parameter."
  ^double [context period phase-param time-unit]
  (+ (* (get-loop-time-from-context context time-unit)
        (period->frequency (double period)))
     (double (or phase-param 0.0))))

(set! *warn-on-reflection* false)
(set! *unchecked-math* false)



