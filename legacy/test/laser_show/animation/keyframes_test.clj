(ns laser-show.animation.keyframes-test
  "Unit tests for keyframe modulator driver system.
   
   Tests cover the unified driver system that supports both time-based
   and spatial keyframe evaluation, including:
   - Driver type detection
   - Edge behavior defaults
   - Position calculation per driver type  
   - Full keyframe evaluation with driver context"
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.animation.keyframes :as kf]))

;; =============================================================================
;; spatial-driver? tests
;; =============================================================================

(deftest spatial-driver?-test
  (testing "time is not spatial"
    (is (false? (kf/spatial-driver? :time)))
    (is (false? (kf/spatial-driver? nil))))
  
  (testing "spatial drivers"
    (is (true? (kf/spatial-driver? :point-index)))
    (is (true? (kf/spatial-driver? :pos-x)))
    (is (true? (kf/spatial-driver? :pos-y)))
    (is (true? (kf/spatial-driver? :radial))))
  
  (testing "unknown drivers are not spatial"
    (is (false? (kf/spatial-driver? :unknown)))
    (is (false? (kf/spatial-driver? :foo-bar)))))

;; =============================================================================
;; get-edge-behavior tests
;; =============================================================================

(deftest get-edge-behavior-test
  (testing "defaults for time driver"
    (is (= :wrap (kf/get-edge-behavior {:driver :time})))
    (is (= :wrap (kf/get-edge-behavior {}))))  ;; defaults to time
  
  (testing "defaults for spatial drivers"
    (is (= :clamp (kf/get-edge-behavior {:driver :point-index})))
    (is (= :clamp (kf/get-edge-behavior {:driver :pos-x})))
    (is (= :clamp (kf/get-edge-behavior {:driver :pos-y})))
    (is (= :clamp (kf/get-edge-behavior {:driver :radial}))))
  
  (testing "explicit override"
    (is (= :clamp (kf/get-edge-behavior {:driver :time :edge-behavior :clamp})))
    (is (= :wrap (kf/get-edge-behavior {:driver :pos-x :edge-behavior :wrap})))
    (is (= :wrap (kf/get-edge-behavior {:driver :point-index :edge-behavior :wrap})))))

;; =============================================================================
;; get-keyframe-position tests
;; =============================================================================

(deftest get-keyframe-position-time-driver-test
  (testing "time driver uses time calculation"
    ;; Test with period=1 beat, at 0.5 beats should be 0.5 position
    (let [config {:driver :time :period 1.0 :time-unit :beats}
          ;; Context needs time-ms and bpm for get-beats-from-context to calculate beats
          ;; At 120 BPM: ms->beats(250, 120) = 250 / (60000/120) = 250/500 = 0.5 beats
          context {:time-ms 250 :bpm 120.0}]
      (is (< (Math/abs (- 0.5 (kf/get-keyframe-position config 250 context))) 0.01))))
  
  (testing "time driver uses effective-beats directly when available"
    (let [config {:driver :time :period 1.0 :time-unit :beats}
          ;; effective-beats takes priority over time-ms calculation
          context {:effective-beats 0.5 :bpm 120.0}]
      (is (< (Math/abs (- 0.5 (kf/get-keyframe-position config 0 context))) 0.01))))
  
  (testing "time driver defaults when driver not specified"
    (let [config {:period 1.0 :time-unit :beats}
          context {:time-ms 250 :bpm 120.0}]
      ;; Should use time driver by default
      (is (< (Math/abs (- 0.5 (kf/get-keyframe-position config 250 context))) 0.01)))))

(deftest get-keyframe-position-point-index-driver-test
  (testing "point-index driver uses context"
    (let [config {:driver :point-index}
          context {:point-index 2 :point-count 5}]
      ;; point 2 of 5 = 2/(5-1) = 0.5
      (is (== 0.5 (kf/get-keyframe-position config 0 context)))))
  
  (testing "point-index with first point"
    (let [config {:driver :point-index}
          context {:point-index 0 :point-count 10}]
      (is (== 0.0 (kf/get-keyframe-position config 0 context)))))
  
  (testing "point-index with last point"
    (let [config {:driver :point-index}
          context {:point-index 9 :point-count 10}]
      (is (== 1.0 (kf/get-keyframe-position config 0 context)))))
  
  (testing "point-index with single point"
    (let [config {:driver :point-index}
          context {:point-index 0 :point-count 1}]
      (is (== 0.0 (kf/get-keyframe-position config 0 context))))))

(deftest get-keyframe-position-pos-x-driver-test
  (testing "pos-x driver uses x coordinate"
    (let [config {:driver :pos-x}
          context {:x 0.5}]
      ;; pos-x maps -1..1 to 0..1: (0.5 + 1) / 2 = 0.75
      (is (== 0.75 (kf/get-keyframe-position config 0 context)))))
  
  (testing "pos-x at left edge"
    (let [config {:driver :pos-x}
          context {:x -1.0}]
      (is (== 0.0 (kf/get-keyframe-position config 0 context)))))
  
  (testing "pos-x at right edge"
    (let [config {:driver :pos-x}
          context {:x 1.0}]
      (is (== 1.0 (kf/get-keyframe-position config 0 context)))))
  
  (testing "pos-x at center"
    (let [config {:driver :pos-x}
          context {:x 0.0}]
      (is (== 0.5 (kf/get-keyframe-position config 0 context))))))

(deftest get-keyframe-position-pos-y-driver-test
  (testing "pos-y driver uses y coordinate"
    (let [config {:driver :pos-y}
          context {:y 0.5}]
      ;; pos-y maps -1..1 to 0..1: (0.5 + 1) / 2 = 0.75
      (is (== 0.75 (kf/get-keyframe-position config 0 context)))))
  
  (testing "pos-y at bottom edge"
    (let [config {:driver :pos-y}
          context {:y -1.0}]
      (is (== 0.0 (kf/get-keyframe-position config 0 context)))))
  
  (testing "pos-y at top edge"
    (let [config {:driver :pos-y}
          context {:y 1.0}]
      (is (== 1.0 (kf/get-keyframe-position config 0 context))))))

(deftest get-keyframe-position-radial-driver-test
  (testing "radial driver at center"
    (let [config {:driver :radial}
          context {:x 0.0 :y 0.0}]
      (is (== 0.0 (kf/get-keyframe-position config 0 context)))))
  
  (testing "radial driver at corner (normalized)"
    (let [config {:driver :radial :normalize? true}
          context {:x 1.0 :y 1.0}]
      ;; distance = sqrt(2), normalized by sqrt(2) = 1.0
      (is (< (Math/abs (- 1.0 (kf/get-keyframe-position config 0 context))) 0.001))))
  
  (testing "radial driver at edge"
    (let [config {:driver :radial :normalize? true}
          context {:x 1.0 :y 0.0}
          sqrt-2 (Math/sqrt 2.0)]
      ;; distance = 1.0, normalized by sqrt(2) ≈ 0.707
      (is (< (Math/abs (- (/ 1.0 sqrt-2) (kf/get-keyframe-position config 0 context))) 0.001)))))

;; =============================================================================
;; eval-keyframe with drivers tests
;; =============================================================================

(deftest eval-keyframe-with-drivers-test
  (let [keyframes [{:position 0.0 :params {:scale 0.0}}
                   {:position 1.0 :params {:scale 100.0}}]]
    
    (testing "time driver (existing behavior, backward compat)"
      (let [config {:enabled? true
                    :driver :time
                    :period 1.0
                    :time-unit :beats
                    :keyframes keyframes}
            ;; Use effective-beats directly for predictable testing
            context {:effective-beats 0.5 :bpm 120.0}]
        ;; At 0.5 beats with period 1.0, position = 0.5
        (is (< (Math/abs (- 50.0 (:scale (kf/eval-keyframe config 0 context)))) 0.001))))
    
    (testing "point-index driver"
      (let [config {:enabled? true
                    :driver :point-index
                    :keyframes keyframes}
            context {:point-index 2 :point-count 5}]  ;; position = 0.5
        (is (< (Math/abs (- 50.0 (:scale (kf/eval-keyframe config 0 context)))) 0.001))))
    
    (testing "pos-x driver"
      (let [config {:enabled? true
                    :driver :pos-x
                    :keyframes keyframes}
            context {:x 0.0}]  ;; x=0 maps to position=0.5
        (is (< (Math/abs (- 50.0 (:scale (kf/eval-keyframe config 0 context)))) 0.001))))
    
    (testing "pos-y driver"
      (let [config {:enabled? true
                    :driver :pos-y
                    :keyframes keyframes}
            context {:y 0.0}]  ;; y=0 maps to position=0.5
        (is (< (Math/abs (- 50.0 (:scale (kf/eval-keyframe config 0 context)))) 0.001))))
    
    (testing "radial driver"
      (let [config {:enabled? true
                    :driver :radial
                    :keyframes keyframes}
            context {:x 0.0 :y 0.0}]  ;; center maps to position=0.0
        (is (< (Math/abs (- 0.0 (:scale (kf/eval-keyframe config 0 context)))) 0.001))))
    
    (testing "backward compatibility - no driver defaults to time"
      (let [config {:enabled? true
                    :period 1.0
                    :time-unit :beats
                    :keyframes keyframes}
            ;; Use effective-beats directly
            context {:effective-beats 0.5 :bpm 120.0}]
        ;; Should behave same as explicit :time driver
        (is (< (Math/abs (- 50.0 (:scale (kf/eval-keyframe config 0 context)))) 0.001))))))

;; =============================================================================
;; Edge behavior tests
;; =============================================================================

(deftest edge-behavior-clamp-test
  (let [keyframes [{:position 0.2 :params {:value 0.2} :interpolation :linear}
                   {:position 0.8 :params {:value 0.8}}]]
    
    (testing "clamp stays at first keyframe value for positions before first"
      (let [config {:enabled? true 
                    :driver :point-index 
                    :edge-behavior :clamp
                    :keyframes keyframes}]
        ;; At point-index 0 (position=0), should clamp to first keyframe value
        (is (== 0.2 (:value (kf/eval-keyframe config 0 {:point-index 0 :point-count 10}))))))
    
    (testing "clamp stays at last keyframe value for positions after last"
      (let [config {:enabled? true 
                    :driver :point-index 
                    :edge-behavior :clamp
                    :keyframes keyframes}]
        ;; At point-index 9 (position=1), should clamp to last keyframe value
        (is (== 0.8 (:value (kf/eval-keyframe config 0 {:point-index 9 :point-count 10}))))))
    
    (testing "clamp interpolates correctly between keyframes"
      (let [config {:enabled? true 
                    :driver :point-index 
                    :edge-behavior :clamp
                    :keyframes keyframes}]
        ;; At position 0.5 (between 0.2 and 0.8)
        ;; t = (0.5 - 0.2) / (0.8 - 0.2) = 0.3 / 0.6 = 0.5
        ;; value = lerp(0.2, 0.8, 0.5) = 0.5
        (is (< (Math/abs (- 0.5 (:value (kf/eval-keyframe config 0 {:point-index 5 :point-count 11})))) 0.01))))))

(deftest edge-behavior-wrap-test
  (let [keyframes [{:position 0.0 :params {:value 0.0} :interpolation :linear}
                   {:position 0.5 :params {:value 1.0} :interpolation :linear}
                   {:position 1.0 :params {:value 0.0}}]]
    
    (testing "wrap interpolates through keyframe range"
      (let [config {:enabled? true 
                    :driver :point-index 
                    :edge-behavior :wrap
                    :keyframes keyframes}]
        ;; At position 0.25 (between 0.0 and 0.5)
        ;; t = 0.25 / 0.5 = 0.5
        ;; value = lerp(0, 1, 0.5) = 0.5
        (is (< (Math/abs (- 0.5 (:value (kf/eval-keyframe config 0 {:point-index 2 :point-count 9})))) 0.01))))))

(deftest edge-behavior-default-by-driver-test
  (testing "time driver defaults to wrap"
    (let [config {:driver :time}]
      (is (= :wrap (kf/get-edge-behavior config)))))
  
  (testing "spatial drivers default to clamp"
    (doseq [driver [:point-index :pos-x :pos-y :radial]]
      (let [config {:driver driver}]
        (is (= :clamp (kf/get-edge-behavior config)) (str driver " should default to clamp"))))))

;; =============================================================================
;; calculate-spatial-position tests
;; =============================================================================

(deftest calculate-spatial-position-test
  (testing "point-index calculation"
    (is (== 0.0 (kf/calculate-spatial-position :point-index {:point-index 0 :point-count 5})))
    (is (== 0.5 (kf/calculate-spatial-position :point-index {:point-index 2 :point-count 5})))
    (is (== 1.0 (kf/calculate-spatial-position :point-index {:point-index 4 :point-count 5}))))
  
  (testing "pos-x calculation"
    (is (== 0.0 (kf/calculate-spatial-position :pos-x {:x -1.0})))
    (is (== 0.5 (kf/calculate-spatial-position :pos-x {:x 0.0})))
    (is (== 1.0 (kf/calculate-spatial-position :pos-x {:x 1.0}))))
  
  (testing "pos-y calculation"
    (is (== 0.0 (kf/calculate-spatial-position :pos-y {:y -1.0})))
    (is (== 0.5 (kf/calculate-spatial-position :pos-y {:y 0.0})))
    (is (== 1.0 (kf/calculate-spatial-position :pos-y {:y 1.0}))))
  
  (testing "radial calculation at origin"
    (is (== 0.0 (kf/calculate-spatial-position :radial {:x 0.0 :y 0.0}))))
  
  (testing "unknown driver returns 0.0"
    (is (== 0.0 (kf/calculate-spatial-position :unknown {:x 0.5 :y 0.5})))))

;; =============================================================================
;; Integration tests - full eval-keyframe pipeline
;; =============================================================================

(deftest eval-keyframe-full-pipeline-test
  (testing "multi-keyframe interpolation with point-index driver"
    (let [keyframes [{:position 0.0 :params {:hue 0.0} :interpolation :linear}
                     {:position 0.5 :params {:hue 180.0} :interpolation :linear}
                     {:position 1.0 :params {:hue 360.0}}]
          config {:driver :point-index
                  :edge-behavior :clamp
                  :keyframes keyframes}]
      ;; First point
      (is (< (Math/abs (- 0.0 (:hue (kf/eval-keyframe config 0 {:point-index 0 :point-count 5})))) 0.001))
      ;; Middle point
      (is (< (Math/abs (- 180.0 (:hue (kf/eval-keyframe config 0 {:point-index 2 :point-count 5})))) 0.001))
      ;; Last point
      (is (< (Math/abs (- 360.0 (:hue (kf/eval-keyframe config 0 {:point-index 4 :point-count 5})))) 0.001))))
  
  (testing "eval-keyframe returns nil for empty keyframes"
    (let [config {:driver :point-index :keyframes []}]
      (is (nil? (kf/eval-keyframe config 0 {:point-index 0 :point-count 5})))))
  
  (testing "eval-keyframe returns nil for missing keyframes"
    (let [config {:driver :point-index}]
      (is (nil? (kf/eval-keyframe config 0 {:point-index 0 :point-count 5}))))))

(deftest eval-keyframe-interpolation-modes-test
  (let [keyframes [{:position 0.0 :params {:val 0.0} :interpolation :linear}
                   {:position 1.0 :params {:val 100.0}}]]
    
    (testing "linear interpolation"
      (let [config {:driver :point-index
                    :keyframes keyframes}
            result (:val (kf/eval-keyframe config 0 {:point-index 2 :point-count 5}))]
        ;; position 0.5 with linear -> 50.0
        (is (< (Math/abs (- 50.0 result)) 0.001))))
    
    (testing "step interpolation holds until end"
      (let [config {:driver :point-index
                    :keyframes [{:position 0.0 :params {:val 0.0} :interpolation :step}
                                {:position 1.0 :params {:val 100.0}}]}
            result (:val (kf/eval-keyframe config 0 {:point-index 2 :point-count 5}))]
        ;; position 0.5 with step -> still 0.0
        (is (< (Math/abs (- 0.0 result)) 0.001))))
    
    (testing "exp-decay (ease-out) curves faster at start"
      (let [config {:driver :point-index
                    :keyframes [{:position 0.0 :params {:val 0.0} :interpolation :exp-decay}
                                {:position 1.0 :params {:val 100.0}}]}
            result (:val (kf/eval-keyframe config 0 {:point-index 2 :point-count 5}))]
        ;; position 0.5 with exp-decay should be > 50 (fast start)
        (is (> result 50.0))))
    
    (testing "exp-grow (ease-in) curves slower at start"
      (let [config {:driver :point-index
                    :keyframes [{:position 0.0 :params {:val 0.0} :interpolation :exp-grow}
                                {:position 1.0 :params {:val 100.0}}]}
            result (:val (kf/eval-keyframe config 0 {:point-index 2 :point-count 5}))]
        ;; position 0.5 with exp-grow should be < 50 (slow start)
        (is (< result 50.0))))))

;; =============================================================================
;; Context handling tests
;; =============================================================================

(deftest context-handling-test
  (testing "missing context values use defaults"
    (let [config {:driver :point-index
                  :keyframes [{:position 0.0 :params {:v 0.0}}
                              {:position 1.0 :params {:v 100.0}}]}]
      ;; Missing point-index defaults to 0, missing point-count defaults to 1
      ;; So position = 0.0
      (is (number? (:v (kf/eval-keyframe config 0 {}))))))
  
  (testing "nil context uses defaults"
    (let [config {:driver :pos-x
                  :keyframes [{:position 0.0 :params {:v 0.0}}
                              {:position 1.0 :params {:v 100.0}}]}]
      ;; Missing x defaults to 0.0, maps to position 0.5
      (is (number? (:v (kf/eval-keyframe config 0 nil))))))
  
  (testing "2-arity call with context map"
    (let [config {:driver :point-index
                  :keyframes [{:position 0.0 :params {:v 0.0}}
                              {:position 1.0 :params {:v 100.0}}]}
          context {:point-index 2 :point-count 5 :time-ms 0}]
      ;; 2-arity extracts time-ms from context
      (is (< (Math/abs (- 50.0 (:v (kf/eval-keyframe config context)))) 0.001)))))
