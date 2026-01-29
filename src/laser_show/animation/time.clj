(ns laser-show.animation.time
  "Time utilities for BPM-synchronized effects.
   Handles BPM conversions, phase calculations, and time-based computations.
   
   Also provides context-aware beat extraction and modulator phase calculation
   utilities used by the modulator evaluation system.")

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
  (let [p (mod (+ phase 0.5) 1.0)]  ;; Shift by 0.5 to start at peak
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
  (- 1.0 (mod phase 1.0)))

(defn square-wave-normalized
  "Generate a normalized square wave value (0.0 or 1.0) at the given phase."
  (^double [^double phase]
   (square-wave-normalized phase 0.5))
  (^double [^double phase ^double duty-cycle]
   (if (< (mod phase 1.0) duty-cycle) 1.0 0.0)))


;; Value Oscillation


(defn oscillate
  "Oscillate a value between min-val and max-val based on phase.
   waveform can be :sine, :triangle, :sawtooth, or :square."
  (^double [^double min-val ^double max-val ^double phase]
   (oscillate min-val max-val phase :sine))
  ([^double min-val ^double max-val ^double phase waveform]
   (let [normalized-phase (mod phase 1.0)
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

(defn get-ms-from-context
  "Get the current milliseconds from context with proper fallback.
   
   Priority:
   1. accumulated-ms (incremental since trigger)
   2. time-ms (absolute timestamp - backward compatibility with tests)
   3. Default to 0.0"
  ^double [{:keys [accumulated-ms time-ms]}]
  (double (or accumulated-ms time-ms 0.0)))


;; Trigger Time Resolution


(defn resolve-trigger-time
  "Resolve trigger time from either a fixed value or an atom reference.
   Returns the trigger time as a number, or nil if not available."
  [trigger-source]
  (cond
    (instance? clojure.lang.IDeref trigger-source) @trigger-source
    (number? trigger-source) trigger-source
    :else nil))


;; Once-Mode Progress Calculation


(defn calculate-once-progress
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
        time-ms' (double (or time-ms 0))
        elapsed (- time-ms' start-time)
        duration' (double (or duration 1.0))
        bpm' (double (or bpm 120.0))
        duration-ms (double (if (= time-unit :seconds)
                              (* duration' 1000.0)
                              (beats->ms duration' bpm')))]
    (if (pos? duration-ms)
      (min 1.0 (max 0.0 (/ elapsed duration-ms)))
      1.0)))


;; Modulator Phase Calculation


(defn calculate-modulator-phase
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
             progress (double (calculate-once-progress (or time-ms 0) effective-trigger-time duration time-unit bpm))]
         (+ (* progress frequency) phase-param))
       ;; Loop mode: use effective-beats (with phase correction for tap resync)
       ;; Falls back to calculating from time-ms/bpm for backward compatibility
       (let [beats (double (get-beats-from-context context))]
         (+ (* beats frequency) phase-param))))))


