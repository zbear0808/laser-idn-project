(ns laser-show.animation.time
  "Time utilities for BPM-synchronized effects.
   Handles BPM conversions, phase calculations, and time-based computations.")

;; ============================================================================
;; Global BPM State
;; ============================================================================

(defonce !global-bpm (atom 120))

(defn set-global-bpm!
  "Set the global BPM value."
  [bpm]
  {:pre [(number? bpm) (pos? bpm)]}
  (reset! !global-bpm (double bpm)))

(defn get-global-bpm
  "Get the current global BPM value."
  []
  @!global-bpm)

;; ============================================================================
;; BPM Conversions
;; ============================================================================

(defn bpm->ms-per-beat
  "Convert BPM to milliseconds per beat."
  [bpm]
  (/ 60000.0 bpm))

(defn bpm->ms-per-bar
  "Convert BPM to milliseconds per bar.
   beats-per-bar defaults to 4 (standard 4/4 time)."
  ([bpm]
   (bpm->ms-per-bar bpm 4))
  ([bpm beats-per-bar]
   (* (bpm->ms-per-beat bpm) beats-per-bar)))

(defn beats->ms
  "Convert a number of beats to milliseconds at the given BPM."
  [beats bpm]
  (* beats (bpm->ms-per-beat bpm)))

(defn ms->beats
  "Convert milliseconds to beats at the given BPM."
  [ms bpm]
  (/ ms (bpm->ms-per-beat bpm)))

(defn bars->ms
  "Convert a number of bars to milliseconds at the given BPM."
  ([bars bpm]
   (bars->ms bars bpm 4))
  ([bars bpm beats-per-bar]
   (* bars (bpm->ms-per-bar bpm beats-per-bar))))

(defn ms->bars
  "Convert milliseconds to bars at the given BPM."
  ([ms bpm]
   (ms->bars ms bpm 4))
  ([ms bpm beats-per-bar]
   (/ ms (bpm->ms-per-bar bpm beats-per-bar))))

;; ============================================================================
;; Phase Calculations
;; ============================================================================

(defn time->phase
  "Calculate oscillation phase (0.0 to 1.0) from time.
   frequency-hz is cycles per second."
  [time-ms frequency-hz]
  (let [period-ms (/ 1000.0 frequency-hz)
        phase (mod (/ time-ms period-ms) 1.0)]
    phase))

(defn beat->phase
  "Calculate phase (0.0 to 1.0) within a beat cycle.
   beat-count is the fractional beat position."
  [beat-count]
  (mod beat-count 1.0))

(defn bar->phase
  "Calculate phase (0.0 to 1.0) within a bar cycle."
  ([beat-count]
   (bar->phase beat-count 4))
  ([beat-count beats-per-bar]
   (mod (/ beat-count beats-per-bar) 1.0)))

(defn time->beat-phase
  "Calculate phase within a beat at the given BPM."
  [time-ms bpm]
  (beat->phase (ms->beats time-ms bpm)))

(defn time->bar-phase
  "Calculate phase within a bar at the given BPM."
  ([time-ms bpm]
   (time->bar-phase time-ms bpm 4))
  ([time-ms bpm beats-per-bar]
   (bar->phase (ms->beats time-ms bpm) beats-per-bar)))

;; ============================================================================
;; Waveform Generation
;; ============================================================================

(defn sine-wave
  "Generate a sine wave value (-1.0 to 1.0) at the given phase (0.0 to 1.0)."
  [phase]
  (Math/sin (* phase 2.0 Math/PI)))

(defn sine-wave-normalized
  "Generate a normalized sine wave value (0.0 to 1.0) at the given phase."
  [phase]
  (/ (+ 1.0 (sine-wave phase)) 2.0))

(defn triangle-wave
  "Generate a triangle wave value (-1.0 to 1.0) at the given phase (0.0 to 1.0)."
  [phase]
  (let [p (mod phase 1.0)]
    (if (< p 0.5)
      (- (* 4.0 p) 1.0)
      (- 3.0 (* 4.0 p)))))

(defn triangle-wave-normalized
  "Generate a normalized triangle wave value (0.0 to 1.0) at the given phase."
  [phase]
  (/ (+ 1.0 (triangle-wave phase)) 2.0))

(defn sawtooth-wave
  "Generate a sawtooth wave value (-1.0 to 1.0) at the given phase (0.0 to 1.0)."
  [phase]
  (- (* 2.0 (mod phase 1.0)) 1.0))

(defn sawtooth-wave-normalized
  "Generate a normalized sawtooth wave value (0.0 to 1.0) at the given phase."
  [phase]
  (mod phase 1.0))

(defn square-wave
  "Generate a square wave value (-1.0 or 1.0) at the given phase (0.0 to 1.0).
   duty-cycle controls the on/off ratio (default 0.5)."
  ([phase]
   (square-wave phase 0.5))
  ([phase duty-cycle]
   (if (< (mod phase 1.0) duty-cycle) 1.0 -1.0)))

(defn square-wave-normalized
  "Generate a normalized square wave value (0.0 or 1.0) at the given phase."
  ([phase]
   (square-wave-normalized phase 0.5))
  ([phase duty-cycle]
   (if (< (mod phase 1.0) duty-cycle) 1.0 0.0)))

;; ============================================================================
;; Value Oscillation
;; ============================================================================

(defn oscillate
  "Oscillate a value between min-val and max-val based on phase.
   waveform can be :sine, :triangle, :sawtooth, or :square."
  ([min-val max-val phase]
   (oscillate min-val max-val phase :sine))
  ([min-val max-val phase waveform]
   (let [normalized-phase (mod phase 1.0)
         wave-fn (case waveform
                   :sine sine-wave-normalized
                   :triangle triangle-wave-normalized
                   :sawtooth sawtooth-wave-normalized
                   :square square-wave-normalized
                   sine-wave-normalized)
         t (wave-fn normalized-phase)]
     (+ min-val (* t (- max-val min-val))))))

(defn oscillate-bpm
  "Oscillate a value at a BPM-synchronized rate.
   frequency is cycles per beat."
  [min-val max-val time-ms bpm frequency & {:keys [waveform] :or {waveform :sine}}]
  (let [beat-count (ms->beats time-ms bpm)
        phase (* beat-count frequency)]
    (oscillate min-val max-val phase waveform)))

(defn oscillate-hz
  "Oscillate a value at a fixed frequency (Hz).
   frequency is cycles per second."
  [min-val max-val time-ms frequency & {:keys [waveform] :or {waveform :sine}}]
  (let [phase (time->phase time-ms frequency)]
    (oscillate min-val max-val phase waveform)))

;; ============================================================================
;; Quantization
;; ============================================================================

(defn quantize-to-beat
  "Quantize a time value to the nearest beat."
  [time-ms bpm]
  (let [ms-per-beat (bpm->ms-per-beat bpm)
        beat-num (Math/round (double (/ time-ms ms-per-beat)))]
    (* beat-num ms-per-beat)))

(defn quantize-to-bar
  "Quantize a time value to the nearest bar."
  ([time-ms bpm]
   (quantize-to-bar time-ms bpm 4))
  ([time-ms bpm beats-per-bar]
   (let [ms-per-bar (bpm->ms-per-bar bpm beats-per-bar)
         bar-num (Math/round (double (/ time-ms ms-per-bar)))]
     (* bar-num ms-per-bar))))

(defn quantize-to-subdivision
  "Quantize a time value to a beat subdivision (e.g., 2 for half beats, 4 for quarter beats)."
  [time-ms bpm subdivision]
  (let [ms-per-subdivision (/ (bpm->ms-per-beat bpm) subdivision)
        sub-num (Math/round (double (/ time-ms ms-per-subdivision)))]
    (* sub-num ms-per-subdivision)))

;; ============================================================================
;; Easing Functions
;; ============================================================================

(defn ease-in-quad
  "Quadratic ease-in: slow start, accelerating."
  [t]
  (* t t))

(defn ease-out-quad
  "Quadratic ease-out: fast start, decelerating."
  [t]
  (- 1.0 (* (- 1.0 t) (- 1.0 t))))

(defn ease-in-out-quad
  "Quadratic ease-in-out: slow start and end."
  [t]
  (if (< t 0.5)
    (* 2.0 t t)
    (- 1.0 (/ (* (+ -2.0 (* 2.0 t)) (+ -2.0 (* 2.0 t))) 2.0))))

(defn ease-in-cubic
  "Cubic ease-in."
  [t]
  (* t t t))

(defn ease-out-cubic
  "Cubic ease-out."
  [t]
  (let [t1 (- t 1.0)]
    (+ 1.0 (* t1 t1 t1))))

(defn ease-in-out-cubic
  "Cubic ease-in-out."
  [t]
  (if (< t 0.5)
    (* 4.0 t t t)
    (+ 1.0 (* 4.0 (- t 1.0) (- t 1.0) (- t 1.0)))))

;; ============================================================================
;; Tap Tempo (Future Enhancement)
;; ============================================================================

(defonce !tap-times (atom []))

(defn tap-tempo!
  "Record a tap for tap-tempo BPM detection.
   Call this repeatedly to detect BPM from tapping rhythm."
  []
  (let [now (System/currentTimeMillis)
        max-taps 8
        max-interval 2000]
    (swap! !tap-times
           (fn [taps]
             (let [filtered (filterv #(< (- now %) max-interval) taps)
                   updated (conj filtered now)]
               (if (> (count updated) max-taps)
                 (subvec updated (- (count updated) max-taps))
                 updated))))
    (let [taps @!tap-times]
      (when (>= (count taps) 2)
        (let [intervals (mapv - (rest taps) (butlast taps))
              avg-interval (/ (reduce + intervals) (count intervals))
              detected-bpm (/ 60000.0 avg-interval)]
          (when (and (>= detected-bpm 40) (<= detected-bpm 240))
            (set-global-bpm! detected-bpm)
            detected-bpm))))))

(defn reset-tap-tempo!
  "Reset the tap tempo buffer."
  []
  (reset! !tap-times []))
