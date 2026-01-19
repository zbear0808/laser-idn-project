(ns laser-show.animation.time
  "Time utilities for BPM-synchronized effects.
   Handles BPM conversions, phase calculations, and time-based computations.")


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


