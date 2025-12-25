(ns laser-show.animation.modulation-test
  "Tests for the parameter modulation system."
  (:require [clojure.test :refer [deftest testing is]]
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

(defn make-point-context
  "Create a test context with per-point data."
  [time-ms bpm x y point-index point-count]
  {:time-ms time-ms
   :bpm bpm
   :x x
   :y y
   :point-index point-index
   :point-count point-count
   :midi-state {}
   :osc-state {}})

;; ============================================================================
;; Basic Resolution Tests
;; ============================================================================

(deftest resolve-param-static-test
  (testing "Static values pass through unchanged"
    (let [context (make-test-context 0 120)]
      (is (= 1.5 (mod/resolve-param 1.5 context)))
      (is (= 0 (mod/resolve-param 0 context)))
      (is (= -10 (mod/resolve-param -10 context))))))

(deftest modulator-config?-test
  (testing "modulator-config? correctly identifies configs"
    (is (mod/modulator-config? {:type :sine :min 0 :max 1}))
    (is (mod/modulator-config? {:type :constant :value 5}))
    (is (not (mod/modulator-config? 1.5)))
    (is (not (mod/modulator-config? {:foo :bar})))
    (is (not (mod/modulator-config? (fn [x] x))))))

(deftest resolve-params-map-test
  (testing "resolve-params resolves all params in a map"
    (let [context (make-test-context 0 120)
          params {:static 1.5
                  :modulated {:type :constant :value 2.0}}
          resolved (mod/resolve-params params context)]
      (is (= 1.5 (:static resolved)))
      (is (= 2.0 (:modulated resolved))))))

;; ============================================================================
;; Constant Modulator Tests
;; ============================================================================

(deftest constant-test
  (testing "Constant modulator returns same value regardless of context"
    (let [config {:type :constant :value 42}]
      (is (= 42 (mod/resolve-param config (make-test-context 0 120))))
      (is (= 42 (mod/resolve-param config (make-test-context 1000 120))))
      (is (= 42 (mod/resolve-param config (make-test-context 5000 60)))))))

;; ============================================================================
;; Sine Wave Modulator Tests
;; ============================================================================

(deftest sine-mod-range-test
  (testing "Sine modulator stays within min/max bounds"
    (let [config {:type :sine :min 0.5 :max 1.5 :period 1.0}]
      (doseq [t (range 0 2000 50)]
        (let [value (mod/resolve-param config (make-test-context t 120))]
          (is (>= value 0.5) (str "Value " value " below min at t=" t))
          (is (<= value 1.5) (str "Value " value " above max at t=" t)))))))

(deftest sine-mod-period-test
  (testing "Sine modulator completes one cycle per beat"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)  ; 500ms
          config {:type :sine :min 0.0 :max 1.0 :period 1.0}]
      ;; At phase 0, sine is at midpoint
      (is (approx= 0.5 (mod/resolve-param config (make-test-context 0 bpm)) 0.05))
      ;; At phase 0.25 (1/4 beat), sine should be at max
      (is (approx= 1.0 (mod/resolve-param config (make-test-context (* 0.25 ms-per-beat) bpm)) 0.05))
      ;; At phase 0.5 (1/2 beat), sine is back at midpoint
      (is (approx= 0.5 (mod/resolve-param config (make-test-context (* 0.5 ms-per-beat) bpm)) 0.05))
      ;; At phase 0.75 (3/4 beat), sine should be at min
      (is (approx= 0.0 (mod/resolve-param config (make-test-context (* 0.75 ms-per-beat) bpm)) 0.05)))))

(deftest sine-mod-period-speed-test
  (testing "Period controls cycle speed"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          config-1beat {:type :sine :min 0.0 :max 1.0 :period 1.0}      ; 1 beat period
          config-half-beat {:type :sine :min 0.0 :max 1.0 :period 0.5}  ; 0.5 beat period (faster)
          val-1-half (mod/resolve-param config-1beat (make-test-context (* 0.5 ms-per-beat) bpm))
          val-half-quarter (mod/resolve-param config-half-beat (make-test-context (* 0.25 ms-per-beat) bpm))]
      ;; At quarter beat with 0.5 period, should be same as half beat with 1.0 period
      (is (approx= val-1-half val-half-quarter 0.05)))))

(deftest sine-mod-phase-offset-test
  (testing "Phase offset shifts the waveform"
    (let [bpm 120
          config-no-offset {:type :sine :min 0.0 :max 1.0 :period 1.0 :phase 0.0}
          config-quarter-offset {:type :sine :min 0.0 :max 1.0 :period 1.0 :phase 0.25}]
      ;; With 0.25 phase offset at t=0, should be at peak (like 0.25 phase without offset)
      (is (approx= 1.0 (mod/resolve-param config-quarter-offset (make-test-context 0 bpm)) 0.05)))))

;; ============================================================================
;; Square Wave Modulator Tests
;; ============================================================================

(deftest square-mod-values-test
  (testing "Square modulator alternates between min and max"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          config {:type :square :min 0.0 :max 1.0 :period 1.0 :duty-cycle 0.5}]
      ;; First half of beat should be max
      (is (= 1.0 (mod/resolve-param config (make-test-context 0 bpm))))
      (is (= 1.0 (mod/resolve-param config (make-test-context (* 0.25 ms-per-beat) bpm))))
      ;; Second half should be min
      (is (= 0.0 (mod/resolve-param config (make-test-context (* 0.5 ms-per-beat) bpm))))
      (is (= 0.0 (mod/resolve-param config (make-test-context (* 0.75 ms-per-beat) bpm)))))))

(deftest square-mod-duty-cycle-test
  (testing "Duty cycle controls on/off ratio"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          config {:type :square :min 0.0 :max 1.0 :period 1.0 :duty-cycle 0.25}]  ; 25% on
      ;; First 25% should be max
      (is (= 1.0 (mod/resolve-param config (make-test-context 0 bpm))))
      (is (= 1.0 (mod/resolve-param config (make-test-context (* 0.1 ms-per-beat) bpm))))
      ;; Rest should be min
      (is (= 0.0 (mod/resolve-param config (make-test-context (* 0.3 ms-per-beat) bpm))))
      (is (= 0.0 (mod/resolve-param config (make-test-context (* 0.75 ms-per-beat) bpm)))))))

;; ============================================================================
;; Triangle Wave Modulator Tests
;; ============================================================================

(deftest triangle-mod-range-test
  (testing "Triangle modulator stays within bounds"
    (let [config {:type :triangle :min 0.0 :max 1.0 :period 1.0}]
      (doseq [t (range 0 2000 50)]
        (let [value (mod/resolve-param config (make-test-context t 120))]
          (is (>= value 0.0))
          (is (<= value 1.0)))))))

;; ============================================================================
;; Sawtooth Wave Modulator Tests
;; ============================================================================

(deftest sawtooth-mod-ramp-test
  (testing "Sawtooth ramps from min to max"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          config {:type :sawtooth :min 0.0 :max 1.0 :period 1.0}]
      (is (approx= 0.0 (mod/resolve-param config (make-test-context 0 bpm)) 0.05))
      (is (approx= 0.5 (mod/resolve-param config (make-test-context (* 0.5 ms-per-beat) bpm)) 0.05)))))

;; ============================================================================
;; Hz-based Modulator Tests
;; ============================================================================

(deftest sine-hz-test
  (testing "Sine Hz modulator uses Hz instead of BPM"
    (let [config {:type :sine-hz :min 0.0 :max 1.0 :frequency-hz 1.0}]  ; 1 Hz = 1 cycle per second
      ;; At t=0, should be at midpoint
      (is (approx= 0.5 (mod/resolve-param config (make-test-context 0 120)) 0.05))
      ;; At t=250ms (quarter cycle), should be at max
      (is (approx= 1.0 (mod/resolve-param config (make-test-context 250 120)) 0.05))
      ;; At t=500ms (half cycle), back to midpoint
      (is (approx= 0.5 (mod/resolve-param config (make-test-context 500 120)) 0.05)))))

;; ============================================================================
;; Decay Modulator Tests
;; ============================================================================

(deftest linear-decay-test
  (testing "Linear decay from start to end"
    (let [config {:type :linear-decay :start 1.0 :end 0.0 :duration-ms 1000 :trigger 0}]
      (is (approx= 1.0 (mod/resolve-param config (make-test-context 0 120))))
      (is (approx= 0.5 (mod/resolve-param config (make-test-context 500 120))))
      (is (approx= 0.0 (mod/resolve-param config (make-test-context 1000 120))))
      ;; Should stay at end value after duration
      (is (approx= 0.0 (mod/resolve-param config (make-test-context 2000 120)))))))

(deftest halflife-decay-test
  (testing "Halflife decay halves at half-life"
    (let [config {:type :halflife-decay :start 1.0 :end 0.0 :half-life-ms 500 :trigger 0}]
      (is (approx= 1.0 (mod/resolve-param config (make-test-context 0 120))))
      (is (approx= 0.5 (mod/resolve-param config (make-test-context 500 120)) 0.05))
      (is (approx= 0.25 (mod/resolve-param config (make-test-context 1000 120)) 0.05)))))

(deftest exp-decay-test
  (testing "Exp decay (beat-synced) decays each beat"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          config {:type :exp-decay :max 1.0 :min 0.0 :decay-type :linear}]
      ;; Start of beat
      (is (approx= 1.0 (mod/resolve-param config (make-test-context 0 bpm))))
      ;; Middle of beat
      (is (approx= 0.5 (mod/resolve-param config (make-test-context (* 0.5 ms-per-beat) bpm)) 0.05))
      ;; End of beat (wraps to start of next)
      (is (approx= 1.0 (mod/resolve-param config (make-test-context ms-per-beat bpm)) 0.05)))))

(deftest beat-decay-test
  (testing "Beat decay resets each beat"
    (let [bpm 120
          ms-per-beat (/ 60000 bpm)
          config {:type :beat-decay :max 1.0 :min 0.0 :decay-type :linear}]
      ;; Start of beat
      (is (approx= 1.0 (mod/resolve-param config (make-test-context 0 bpm))))
      ;; Middle of beat
      (is (approx= 0.5 (mod/resolve-param config (make-test-context (* 0.5 ms-per-beat) bpm)) 0.05))
      ;; End of beat (wraps to start of next)
      (is (approx= 1.0 (mod/resolve-param config (make-test-context ms-per-beat bpm)) 0.05)))))

;; ============================================================================
;; Random and Step Modulator Tests
;; ============================================================================

(deftest random-mod-range-test
  (testing "Random modulator stays within bounds"
    (let [config {:type :random :min 0.0 :max 1.0 :changes-per-beat 4.0}]
      (doseq [t (range 0 2000 100)]
        (let [value (mod/resolve-param config (make-test-context t 120))]
          (is (>= value 0.0))
          (is (<= value 1.0)))))))

(deftest random-mod-deterministic-test
  (testing "Random modulator produces same value for same time"
    (let [config {:type :random :min 0.0 :max 1.0 :changes-per-beat 4.0}
          context (make-test-context 500 120)]
      (is (= (mod/resolve-param config context)
             (mod/resolve-param config context))))))

(deftest step-mod-test
  (testing "Step modulator cycles through values"
    (let [config {:type :step :values [1 2 3 4] :steps-per-beat 1.0}
          bpm 120
          ms-per-beat (/ 60000 bpm)]
      (is (= 1 (mod/resolve-param config (make-test-context 0 bpm))))
      (is (= 2 (mod/resolve-param config (make-test-context ms-per-beat bpm))))
      (is (= 3 (mod/resolve-param config (make-test-context (* 2 ms-per-beat) bpm))))
      (is (= 4 (mod/resolve-param config (make-test-context (* 3 ms-per-beat) bpm))))
      ;; Should wrap
      (is (= 1 (mod/resolve-param config (make-test-context (* 4 ms-per-beat) bpm)))))))

;; ============================================================================
;; Preset Tests
;; ============================================================================

(deftest preset-test
  (testing "Presets are accessible and valid configs"
    (is (mod/modulator-config? (mod/preset :gentle-pulse)))
    (is (mod/modulator-config? (mod/preset :strong-pulse)))
    (is (mod/modulator-config? (mod/preset :breathe)))
    (is (mod/modulator-config? (mod/preset :strobe-4x)))
    (is (nil? (mod/preset :nonexistent)))))

(deftest preset-evaluation-test
  (testing "Presets can be evaluated and produce valid values"
    (let [context (make-test-context 0 120)]
      ;; All presets should produce numbers
      (doseq [[preset-key _] mod/presets]
        (let [config (mod/preset preset-key)
              value (mod/resolve-param config context)]
          (is (number? value) (str "Preset " preset-key " should produce a number")))))))

;; ============================================================================
;; Per-Point Context Detection Tests
;; ============================================================================

(deftest config-requires-per-point-test
  (testing "Per-point configs are correctly flagged"
    ;; Position-based modulators should require per-point context
    (is (mod/config-requires-per-point? {:type :pos-x :min 0 :max 1}))
    (is (mod/config-requires-per-point? {:type :pos-y :min 0 :max 1}))
    (is (mod/config-requires-per-point? {:type :radial :min 0 :max 1}))
    (is (mod/config-requires-per-point? {:type :angle :min 0 :max 1}))
    (is (mod/config-requires-per-point? {:type :pos-wave :min 0 :max 1 :axis :x}))
    (is (mod/config-requires-per-point? {:type :pos-scroll :min 0 :max 1 :axis :x}))
    (is (mod/config-requires-per-point? {:type :rainbow-hue :axis :x}))
    
    ;; Point index modulators should require per-point context
    (is (mod/config-requires-per-point? {:type :point-index :min 0 :max 1}))
    (is (mod/config-requires-per-point? {:type :point-wave :min 0 :max 1}))
    
    ;; Time-based modulators should NOT require per-point context
    (is (not (mod/config-requires-per-point? {:type :sine :min 0 :max 1})))
    (is (not (mod/config-requires-per-point? {:type :triangle :min 0 :max 1})))
    (is (not (mod/config-requires-per-point? {:type :square :min 0 :max 1})))
    (is (not (mod/config-requires-per-point? {:type :sawtooth :min 0 :max 1})))
    (is (not (mod/config-requires-per-point? {:type :beat-decay :max 1 :min 0})))
    
    ;; Static values should not be per-point
    (is (not (mod/config-requires-per-point? 1.5)))
    (is (not (mod/config-requires-per-point? "test")))))

(deftest any-param-requires-per-point-test
  (testing "any-param-requires-per-point? detects per-point modulators in params"
    ;; Single per-point modulator
    (is (mod/any-param-requires-per-point? {:hue {:type :pos-x :min 0 :max 360}}))
    
    ;; Mixed params (one per-point, one time-based)
    (is (mod/any-param-requires-per-point?
          {:hue {:type :pos-x :min 0 :max 360}
           :amount {:type :sine :min 0.5 :max 1.0}}))
    
    ;; All time-based modulators
    (is (not (mod/any-param-requires-per-point?
               {:hue {:type :sine :min 0 :max 360}
                :amount {:type :sine :min 0.5 :max 1.0}})))
    
    ;; All static values
    (is (not (mod/any-param-requires-per-point? {:hue 180 :amount 0.75})))
    
    ;; Empty params
    (is (not (mod/any-param-requires-per-point? {})))))

;; ============================================================================
;; Position-Based Modulator Tests
;; ============================================================================

(deftest position-x-mod-test
  (testing "Position X modulator maps X coordinate to range"
    (let [config {:type :pos-x :min 0.0 :max 100.0}]
      ;; At X=-1.0 (left), should be min (0.0)
      (is (approx= 0.0 (mod/resolve-param config (make-point-context 0 120 -1.0 0.0 0 100))))
      
      ;; At X=0.0 (center), should be mid (50.0)
      (is (approx= 50.0 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 0 100))))
      
      ;; At X=1.0 (right), should be max (100.0)
      (is (approx= 100.0 (mod/resolve-param config (make-point-context 0 120 1.0 0.0 0 100)))))))

(deftest position-y-mod-test
  (testing "Position Y modulator maps Y coordinate to range"
    (let [config {:type :pos-y :min 0.0 :max 100.0}]
      ;; At Y=-1.0 (bottom), should be min (0.0)
      (is (approx= 0.0 (mod/resolve-param config (make-point-context 0 120 0.0 -1.0 0 100))))
      
      ;; At Y=0.0 (center), should be mid (50.0)
      (is (approx= 50.0 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 0 100))))
      
      ;; At Y=1.0 (top), should be max (100.0)
      (is (approx= 100.0 (mod/resolve-param config (make-point-context 0 120 0.0 1.0 0 100)))))))

(deftest position-radial-mod-test
  (testing "Position radial modulator maps distance from center to range"
    (let [config {:type :radial :min 0.0 :max 100.0}]
      ;; At origin, distance = 0, should be min (0.0)
      (is (approx= 0.0 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 0 100))))
      
      ;; At corner (1,1), distance = sqrt(2) ≈ 1.41 (normalized to 1.0), should be max
      (is (approx= 100.0 (mod/resolve-param config (make-point-context 0 120 1.0 1.0 0 100)) 1.0)))))

(deftest position-angle-mod-test
  (testing "Position angle modulator maps angle to range"
    (let [config {:type :angle :min 0.0 :max 360.0}]
      ;; The modulator normalizes angle from -π..π to 0..1, then scales to min..max
      ;; At (1, 0), angle = 0 radians, normalized to 0.5, maps to 180°
      (is (approx= 180.0 (mod/resolve-param config (make-point-context 0 120 1.0 0.0 0 100)) 5.0))
      
      ;; At (0, 1), angle = π/2 radians, normalized to 0.75, maps to 270°
      (is (approx= 270.0 (mod/resolve-param config (make-point-context 0 120 0.0 1.0 0 100)) 5.0))
      
      ;; At (-1, 0), angle = π radians (or -π), normalized to 0 or 1, maps to 0° or 360°
      (let [val (mod/resolve-param config (make-point-context 0 120 -1.0 0.0 0 100))]
        (is (or (approx= 0.0 val 5.0) (approx= 360.0 val 5.0)))))))

(deftest point-index-mod-test
  (testing "Point index modulator maps point index to range"
    (let [config {:type :point-index :min 0.0 :max 100.0}]
      ;; First point (index 0) should be min
      (is (approx= 0.0 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 0 100))))
      
      ;; Middle point (index 50) should be mid
      (is (approx= 50.0 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 50 100)) 1.0))
      
      ;; Last point (index 99) should be max
      (is (approx= 100.0 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 99 100)))))))

(deftest point-index-wave-test
  (testing "Point index wave creates wave patterns along points"
    (let [config {:type :point-wave :min 0.0 :max 1.0 :cycles 2.0 :wave-type :sine}]  ; 2 cycles
      ;; At start (index 0), should be at mid of sine
      (is (approx= 0.5 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 0 100)) 0.1))
      
      ;; At 1/4 through points, should be near peak of first cycle
      (is (approx= 1.0 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 12 100)) 0.2))
      
      ;; At halfway, should be at mid (between two peaks)
      (is (approx= 0.5 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 50 100)) 0.1)))))

(deftest position-wave-test
  (testing "Position wave creates spatial wave patterns"
    (let [config {:type :pos-wave :min 0.0 :max 1.0 :axis :x :frequency 2.0 :wave-type :sine}]  ; 2 cycles across X
      ;; At X=-1, phase=0, should be at sine midpoint
      (is (approx= 0.5 (mod/resolve-param config (make-point-context 0 120 -1.0 0.0 0 100)) 0.1))
      
      ;; At X=0, phase=1, should be back at midpoint (1 full cycle)
      (is (approx= 0.5 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 0 100)) 0.1))
      
      ;; At X=1, phase=2, should be at midpoint (2 full cycles)
      (is (approx= 0.5 (mod/resolve-param config (make-point-context 0 120 1.0 0.0 0 100)) 0.1)))))

(deftest rainbow-hue-test
  (testing "Rainbow hue creates animated rainbow based on position"
    (let [config {:type :rainbow-hue :axis :x :speed 360.0}]  ; 360°/sec = 1 full rotation per second
      ;; At X=-1 (left edge) and t=0, hue should be 0
      (is (approx= 0.0 (mod/resolve-param config (make-point-context 0 120 -1.0 0.0 0 100)) 5.0))
      
      ;; At X=0 (center) and t=0, hue should be 180
      (is (approx= 180.0 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 0 100)) 5.0))
      
      ;; At X=1 (right edge) and t=0, hue should be 360 (wraps to 0)
      (let [hue (mod/resolve-param config (make-point-context 0 120 1.0 0.0 0 100))]
        (is (or (approx= 0.0 hue 5.0) (approx= 360.0 hue 5.0))))
      
      ;; At same position but t=1000ms, hue should have shifted by 360°
      (let [hue-t0 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 0 100))
            hue-t1000 (mod/resolve-param config (make-point-context 1000 120 0.0 0.0 0 100))]
        (is (approx= (mod (+ hue-t0 360.0) 360.0) (mod hue-t1000 360.0) 5.0))))))

(deftest position-scroll-test
  (testing "Position scroll creates moving wave patterns"
    (let [config {:type :pos-scroll :min 0.0 :max 1.0 :axis :x :speed 1.0 :wave-type :sine}
          val-1 (mod/resolve-param config (make-point-context 0 120 0.0 0.0 0 100))
          val-2 (mod/resolve-param config (make-point-context 0 120 0.3 0.0 0 100))
          val-3 (mod/resolve-param config (make-point-context 500 120 0.0 0.0 0 100))]
      ;; Verify it combines position and time and returns valid values
      ;; All values should be in valid range
      (is (>= val-1 0.0))
      (is (<= val-1 1.0))
      (is (>= val-2 0.0))
      (is (<= val-2 1.0))
      (is (>= val-3 0.0))
      (is (<= val-3 1.0))
      ;; At least one pair should be different (either different X or different time)
      (is (or (not (approx= val-1 val-2 0.01))
              (not (approx= val-1 val-3 0.01)))
          "Position-scroll should vary with position or time"))))

(deftest per-point-modulator-without-context-test
  (testing "Per-point modulators return default when context missing"
    (let [context (make-test-context 0 120)]  ; No x, y, point-index, point-count
      ;; Should return min value as default
      (is (= 0.0 (mod/resolve-param {:type :pos-x :min 0.0 :max 100.0} context)))
      (is (= 0.0 (mod/resolve-param {:type :pos-y :min 0.0 :max 100.0} context)))
      (is (= 0.0 (mod/resolve-param {:type :point-index :min 0.0 :max 100.0} context))))))
