(ns laser-show.animation.modulation-test
  "Tests for the parameter modulation system."
  (:require [clojure.test :refer [deftest testing is are]]
            [laser-show.animation.modulation :as mod]))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn approx=
  "Check if two numbers are approximately equal within tolerance."
  ([a b] (approx= a b 0.01))
  ([a b tolerance]
   (< (Math/abs (- a b)) tolerance)))

(defn make-test-context
  "Create a test modulation context."
  [time-ms bpm]
  {:time-ms time-ms :bpm bpm :midi-state {} :osc-state {}})

;; ============================================================================
;; Basic Resolution Tests
;; ============================================================================

(deftest resolve-param-static-test
  (testing "Static values pass through unchanged"
    (let [context (make-test-context 0 120)]
      (is (= 1.5 (mod/resolve-param 1.5 context)))
      (is (= 0 (mod/resolve-param 0 context)))
      (is (= -10 (mod/resolve-param -10 context))))))

(deftest modulator?-test
  (testing "modulator? correctly identifies modulators"
    (is (mod/modulator? (mod/sine-mod 0 1)))
    (is (mod/modulator? (mod/constant 5)))
    (is (not (mod/modulator? 1.5)))
    (is (not (mod/modulator? (fn [x] x))))))

(deftest resolve-params-map-test
  (testing "resolve-params resolves all params in a map"
    (let [context (make-test-context 0 120)
          params {:static 1.5
                  :modulated (mod/constant 2.0)}
          resolved (mod/resolve-params params context)]
      (is (= 1.5 (:static resolved)))
      (is (= 2.0 (:modulated resolved))))))

;; ============================================================================
;; Constant Modulator Tests
;; ============================================================================

(deftest constant-test
  (testing "Constant modulator returns same value regardless of context"
    (let [modulator (mod/constant 42)]
      (is (= 42 (mod/resolve-param modulator (make-test-context 0 120))))
      (is (= 42 (mod/resolve-param modulator (make-test-context 1000 120))))
      (is (= 42 (mod/resolve-param modulator (make-test-context 5000 60)))))))

;; ============================================================================
;; Sine Wave Modulator Tests
;; ============================================================================

(deftest sine-mod-range-test
  (testing "Sine modulator stays within min/max bounds"
    (let [modulator (mod/sine-mod 0.5 1.5 1.0)]
      (doseq [t (range 0 2000 50)]
        (let [value (mod/resolve-param modulator (make-test-context t 120))]
          (is (>= value 0.5) (str "Value " value " below min at t=" t))
          (is (<= value 1.5) (str "Value " value " above max at t=" t)))))))

(deftest sine-mod-period-test
  (testing "Sine modulator completes one cycle per beat"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm) ; 500ms
          modulator (mod/sine-mod 0.0 1.0 1.0)]
      ;; At phase 0, sine is at midpoint
      (is (approx= 0.5 (mod/resolve-param modulator (make-test-context 0 bpm)) 0.05))
      ;; At phase 0.25 (1/4 beat), sine should be at max
      (is (approx= 1.0 (mod/resolve-param modulator (make-test-context (* 0.25 ms-per-beat) bpm)) 0.05))
      ;; At phase 0.5 (1/2 beat), sine is back at midpoint
      (is (approx= 0.5 (mod/resolve-param modulator (make-test-context (* 0.5 ms-per-beat) bpm)) 0.05))
      ;; At phase 0.75 (3/4 beat), sine should be at min
      (is (approx= 0.0 (mod/resolve-param modulator (make-test-context (* 0.75 ms-per-beat) bpm)) 0.05)))))

(deftest sine-mod-frequency-test
  (testing "Frequency multiplier changes cycle speed"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          modulator-1x (mod/sine-mod 0.0 1.0 1.0)
          modulator-2x (mod/sine-mod 0.0 1.0 2.0)]
      ;; At half beat with 2x frequency, should be same as full beat with 1x
      (let [val-1x-full (mod/resolve-param modulator-1x (make-test-context ms-per-beat bpm))
            val-2x-half (mod/resolve-param modulator-2x (make-test-context (* 0.5 ms-per-beat) bpm))]
        (is (approx= val-1x-full val-2x-half 0.05))))))

(deftest sine-mod-phase-offset-test
  (testing "Phase offset shifts the waveform"
    (let [bpm 120
          modulator-no-offset (mod/sine-mod 0.0 1.0 1.0 0.0)
          modulator-quarter-offset (mod/sine-mod 0.0 1.0 1.0 0.25)]
      ;; With 0.25 phase offset at t=0, should be at peak (like 0.25 phase without offset)
      (is (approx= 1.0 (mod/resolve-param modulator-quarter-offset (make-test-context 0 bpm)) 0.05)))))

;; ============================================================================
;; Square Wave Modulator Tests
;; ============================================================================

(deftest square-mod-values-test
  (testing "Square modulator alternates between min and max"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          modulator (mod/square-mod 0.0 1.0 1.0 0.5)]
      ;; First half of beat should be max
      (is (= 1.0 (mod/resolve-param modulator (make-test-context 0 bpm))))
      (is (= 1.0 (mod/resolve-param modulator (make-test-context (* 0.25 ms-per-beat) bpm))))
      ;; Second half should be min
      (is (= 0.0 (mod/resolve-param modulator (make-test-context (* 0.5 ms-per-beat) bpm))))
      (is (= 0.0 (mod/resolve-param modulator (make-test-context (* 0.75 ms-per-beat) bpm)))))))

(deftest square-mod-duty-cycle-test
  (testing "Duty cycle controls on/off ratio"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          modulator (mod/square-mod 0.0 1.0 1.0 0.25)] ; 25% on
      ;; First 25% should be max
      (is (= 1.0 (mod/resolve-param modulator (make-test-context 0 bpm))))
      (is (= 1.0 (mod/resolve-param modulator (make-test-context (* 0.1 ms-per-beat) bpm))))
      ;; Rest should be min
      (is (= 0.0 (mod/resolve-param modulator (make-test-context (* 0.3 ms-per-beat) bpm))))
      (is (= 0.0 (mod/resolve-param modulator (make-test-context (* 0.75 ms-per-beat) bpm)))))))

;; ============================================================================
;; Triangle Wave Modulator Tests
;; ============================================================================

(deftest triangle-mod-range-test
  (testing "Triangle modulator stays within bounds"
    (let [modulator (mod/triangle-mod 0.0 1.0 1.0)]
      (doseq [t (range 0 2000 50)]
        (let [value (mod/resolve-param modulator (make-test-context t 120))]
          (is (>= value 0.0))
          (is (<= value 1.0)))))))

;; ============================================================================
;; Sawtooth Wave Modulator Tests
;; ============================================================================

(deftest sawtooth-mod-ramp-test
  (testing "Sawtooth ramps from min to max"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          modulator (mod/sawtooth-mod 0.0 1.0 1.0)]
      (is (approx= 0.0 (mod/resolve-param modulator (make-test-context 0 bpm)) 0.05))
      (is (approx= 0.5 (mod/resolve-param modulator (make-test-context (* 0.5 ms-per-beat) bpm)) 0.05)))))

;; ============================================================================
;; Hz-based Modulator Tests
;; ============================================================================

(deftest sine-hz-test
  (testing "Sine Hz modulator uses Hz instead of BPM"
    (let [modulator (mod/sine-hz 0.0 1.0 1.0)] ; 1 Hz = 1 cycle per second
      ;; At t=0, should be at midpoint
      (is (approx= 0.5 (mod/resolve-param modulator (make-test-context 0 120)) 0.05))
      ;; At t=250ms (quarter cycle), should be at max
      (is (approx= 1.0 (mod/resolve-param modulator (make-test-context 250 120)) 0.05))
      ;; At t=500ms (half cycle), back to midpoint
      (is (approx= 0.5 (mod/resolve-param modulator (make-test-context 500 120)) 0.05)))))

;; ============================================================================
;; Decay Modulator Tests
;; ============================================================================

(deftest linear-decay-test
  (testing "Linear decay from start to end"
    (let [modulator (mod/linear-decay 1.0 0.0 1000 0)]
      (is (approx= 1.0 (mod/resolve-param modulator (make-test-context 0 120))))
      (is (approx= 0.5 (mod/resolve-param modulator (make-test-context 500 120))))
      (is (approx= 0.0 (mod/resolve-param modulator (make-test-context 1000 120))))
      ;; Should stay at end value after duration
      (is (approx= 0.0 (mod/resolve-param modulator (make-test-context 2000 120)))))))

(deftest exp-decay-test
  (testing "Exponential decay halves at half-life"
    (let [modulator (mod/exp-decay 1.0 0.0 500 0)] ; half-life = 500ms
      (is (approx= 1.0 (mod/resolve-param modulator (make-test-context 0 120))))
      (is (approx= 0.5 (mod/resolve-param modulator (make-test-context 500 120)) 0.05))
      (is (approx= 0.25 (mod/resolve-param modulator (make-test-context 1000 120)) 0.05)))))

(deftest beat-decay-test
  (testing "Beat decay resets each beat"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          modulator (mod/beat-decay 1.0 0.0 :linear)]
      ;; Start of beat
      (is (approx= 1.0 (mod/resolve-param modulator (make-test-context 0 bpm))))
      ;; Middle of beat
      (is (approx= 0.5 (mod/resolve-param modulator (make-test-context (* 0.5 ms-per-beat) bpm)) 0.05))
      ;; End of beat (wraps to start of next)
      (is (approx= 1.0 (mod/resolve-param modulator (make-test-context ms-per-beat bpm)) 0.05)))))

;; ============================================================================
;; Combinator Tests
;; ============================================================================

(deftest add-mod-test
  (testing "add-mod adds two modulator values"
    (let [mod-a (mod/constant 1.0)
          mod-b (mod/constant 2.0)
          combined (mod/add-mod mod-a mod-b)]
      (is (= 3.0 (mod/resolve-param combined (make-test-context 0 120)))))))

(deftest mult-mod-test
  (testing "mult-mod multiplies two modulator values"
    (let [mod-a (mod/constant 2.0)
          mod-b (mod/constant 3.0)
          combined (mod/mult-mod mod-a mod-b)]
      (is (= 6.0 (mod/resolve-param combined (make-test-context 0 120)))))))

(deftest clamp-mod-test
  (testing "clamp-mod restricts output to range"
    (let [modulator (mod/constant 2.0)
          clamped (mod/clamp-mod modulator 0.0 1.0)]
      (is (= 1.0 (mod/resolve-param clamped (make-test-context 0 120)))))
    (let [modulator (mod/constant -1.0)
          clamped (mod/clamp-mod modulator 0.0 1.0)]
      (is (= 0.0 (mod/resolve-param clamped (make-test-context 0 120)))))))

(deftest invert-mod-test
  (testing "invert-mod inverts value"
    (let [modulator (mod/constant 0.3)
          inverted (mod/invert-mod modulator)]
      (is (approx= 0.7 (mod/resolve-param inverted (make-test-context 0 120)))))))

;; ============================================================================
;; Random and Step Modulator Tests
;; ============================================================================

(deftest random-mod-range-test
  (testing "Random modulator stays within bounds"
    (let [modulator (mod/random-mod 0.0 1.0 4.0)]
      (doseq [t (range 0 2000 100)]
        (let [value (mod/resolve-param modulator (make-test-context t 120))]
          (is (>= value 0.0))
          (is (<= value 1.0)))))))

(deftest random-mod-deterministic-test
  (testing "Random modulator produces same value for same time"
    (let [modulator (mod/random-mod 0.0 1.0 4.0)
          context (make-test-context 500 120)]
      (is (= (mod/resolve-param modulator context)
             (mod/resolve-param modulator context))))))

(deftest step-mod-test
  (testing "Step modulator cycles through values"
    (let [values [1 2 3 4]
          bpm 120
          ms-per-beat (/ 60000 bpm)
          modulator (mod/step-mod values 1.0)]
      (is (= 1 (mod/resolve-param modulator (make-test-context 0 bpm))))
      (is (= 2 (mod/resolve-param modulator (make-test-context ms-per-beat bpm))))
      (is (= 3 (mod/resolve-param modulator (make-test-context (* 2 ms-per-beat) bpm))))
      (is (= 4 (mod/resolve-param modulator (make-test-context (* 3 ms-per-beat) bpm))))
      ;; Should wrap
      (is (= 1 (mod/resolve-param modulator (make-test-context (* 4 ms-per-beat) bpm)))))))

;; ============================================================================
;; Preset Tests
;; ============================================================================

(deftest preset-test
  (testing "Presets are accessible and valid modulators"
    (is (mod/modulator? (mod/preset :gentle-pulse)))
    (is (mod/modulator? (mod/preset :strong-pulse)))
    (is (mod/modulator? (mod/preset :breathe)))
    (is (mod/modulator? (mod/preset :strobe-4x)))
    (is (nil? (mod/preset :nonexistent)))))
