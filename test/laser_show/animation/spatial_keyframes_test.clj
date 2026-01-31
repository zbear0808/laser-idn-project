(ns laser-show.animation.spatial-keyframes-test
  "Tests for spatial keyframe modulator functionality."
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.animation.keyframes :as kf]
   [laser-show.animation.modulator-defs :as mod-defs]
   [laser-show.animation.modulation :as mod]))

;; =============================================================================
;; get-spatial-position tests - Point Index Axis
;; =============================================================================

(deftest get-spatial-position-point-index-test
  (testing "first point (index 0) returns 0.0"
    (is (= 0.0 (kf/get-spatial-position
                :point-index
                {:point-index 0 :point-count 10}
                {}))))
  
  (testing "last point (index n-1) returns 1.0"
    (is (= 1.0 (kf/get-spatial-position
                :point-index
                {:point-index 9 :point-count 10}
                {}))))
  
  (testing "middle point returns 0.5 for odd count"
    (is (= 0.5 (kf/get-spatial-position
                :point-index
                {:point-index 5 :point-count 11}
                {}))))
  
  (testing "single point (count=1) returns 0.0"
    (is (= 0.0 (kf/get-spatial-position
                :point-index
                {:point-index 0 :point-count 1}
                {}))))
  
  (testing "zero points returns 0.0"
    (is (= 0.0 (kf/get-spatial-position
                :point-index
                {:point-index 0 :point-count 0}
                {}))))
  
  (testing "missing point-index defaults to 0"
    (is (= 0.0 (kf/get-spatial-position
                :point-index
                {:point-count 10}
                {})))))

;; =============================================================================
;; get-spatial-position tests - Position X Axis
;; =============================================================================

(deftest get-spatial-position-pos-x-test
  (testing "x=-1.0 returns 0.0"
    (is (= 0.0 (kf/get-spatial-position :pos-x {:x -1.0} {}))))
  
  (testing "x=0.0 returns 0.5"
    (is (= 0.5 (kf/get-spatial-position :pos-x {:x 0.0} {}))))
  
  (testing "x=1.0 returns 1.0"
    (is (= 1.0 (kf/get-spatial-position :pos-x {:x 1.0} {}))))
  
  (testing "nil x returns 0.5 (0+1)/2"
    (is (= 0.5 (kf/get-spatial-position :pos-x {} {})))))

;; =============================================================================
;; get-spatial-position tests - Position Y Axis
;; =============================================================================

(deftest get-spatial-position-pos-y-test
  (testing "y=-1.0 returns 0.0"
    (is (= 0.0 (kf/get-spatial-position :pos-y {:y -1.0} {}))))
  
  (testing "y=0.0 returns 0.5"
    (is (= 0.5 (kf/get-spatial-position :pos-y {:y 0.0} {}))))
  
  (testing "y=1.0 returns 1.0"
    (is (= 1.0 (kf/get-spatial-position :pos-y {:y 1.0} {}))))
  
  (testing "nil y returns 0.5 (0+1)/2"
    (is (= 0.5 (kf/get-spatial-position :pos-y {} {})))))

;; =============================================================================
;; get-spatial-position tests - Radial Axis
;; =============================================================================

(deftest get-spatial-position-radial-test
  (let [sqrt-2 (Math/sqrt 2.0)]
    (testing "center (0,0) returns 0.0"
      (is (= 0.0 (kf/get-spatial-position :radial {:x 0.0 :y 0.0} {:normalize? true}))))
    
    (testing "corner (1,1) returns 1.0 when normalized"
      (is (< (Math/abs (- 1.0 (kf/get-spatial-position :radial {:x 1.0 :y 1.0} {:normalize? true}))) 0.001)))
    
    (testing "edge (1,0) returns ~0.707 when normalized"
      (let [result (kf/get-spatial-position :radial {:x 1.0 :y 0.0} {:normalize? true})
            expected (/ 1.0 sqrt-2)]
        (is (< (Math/abs (- expected result)) 0.001))))
    
    (testing "normalize?=false uses max-dist=1.0"
      (is (= 1.0 (kf/get-spatial-position :radial {:x 1.0 :y 0.0} {:normalize? false}))))
    
    (testing "default normalize? is true"
      (let [result (kf/get-spatial-position :radial {:x 1.0 :y 0.0} {})
            expected (/ 1.0 sqrt-2)]
        (is (< (Math/abs (- expected result)) 0.001))))))

;; =============================================================================
;; get-spatial-position tests - Angle Axis
;; =============================================================================

(deftest get-spatial-position-angle-test
  (testing "point at (1,0) returns ~0.5 (0° normalized from -PI to PI)"
    ;; atan2(0, 1) = 0, normalized: (0 + PI) / (2*PI) = 0.5
    (is (< (Math/abs (- 0.5 (kf/get-spatial-position :angle {:x 1.0 :y 0.0} {}))) 0.001)))
  
  (testing "point at (0,1) returns ~0.75 (90°)"
    ;; atan2(1, 0) = PI/2, normalized: (PI/2 + PI) / (2*PI) = 0.75
    (is (< (Math/abs (- 0.75 (kf/get-spatial-position :angle {:x 0.0 :y 1.0} {}))) 0.001)))
  
  (testing "point at (-1,0) returns ~1.0 or 0.0 (180°)"
    ;; atan2(0, -1) = PI, normalized: (PI + PI) / (2*PI) = 1.0
    (let [result (kf/get-spatial-position :angle {:x -1.0 :y 0.0} {})]
      (is (or (< (Math/abs (- 1.0 result)) 0.001)
              (< (Math/abs result) 0.001)))))
  
  (testing "point at (0,-1) returns ~0.25 (270° / -90°)"
    ;; atan2(-1, 0) = -PI/2, normalized: (-PI/2 + PI) / (2*PI) = 0.25
    (is (< (Math/abs (- 0.25 (kf/get-spatial-position :angle {:x 0.0 :y -1.0} {}))) 0.001)))
  
  (testing "center point (0,0) returns 0.0"
    (is (= 0.0 (kf/get-spatial-position :angle {:x 0.0 :y 0.0} {})))))

;; =============================================================================
;; get-spatial-position tests - Unknown Axis
;; =============================================================================

(deftest get-spatial-position-unknown-axis-test
  (testing "unknown axis returns 0.0"
    (is (= 0.0 (kf/get-spatial-position :unknown-axis {:x 0.5 :y 0.5} {})))))

;; =============================================================================
;; eval-spatial-keyframe tests - Basic Interpolation
;; =============================================================================

(deftest eval-spatial-keyframe-basic-test
  (let [config {:type :spatial-keyframe
                :axis :point-index
                :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                            {:position 1.0 :value 100.0}]}]
    
    (testing "position at first keyframe returns first value"
      (let [result (kf/eval-spatial-keyframe config {:point-index 0 :point-count 10})]
        (is (< (Math/abs (- 0.0 result)) 0.001))))
    
    (testing "position at last keyframe returns last value"
      (let [result (kf/eval-spatial-keyframe config {:point-index 9 :point-count 10})]
        (is (< (Math/abs (- 100.0 result)) 0.001))))
    
    (testing "position at midpoint interpolates correctly with linear mode"
      (let [result (kf/eval-spatial-keyframe config {:point-index 5 :point-count 11})]
        (is (< (Math/abs (- 50.0 result)) 0.001))))))

(deftest eval-spatial-keyframe-multi-keyframe-test
  (let [config {:type :spatial-keyframe
                :axis :point-index
                :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                            {:position 0.5 :value 100.0 :interpolation :linear}
                            {:position 1.0 :value 50.0}]}]
    
    (testing "position between first and second keyframes"
      (let [result (kf/eval-spatial-keyframe config {:point-index 2 :point-count 9})]
        ;; point 2 of 9 = 0.25 position, between 0.0->0.5 segment
        ;; t = (0.25 - 0) / (0.5 - 0) = 0.5
        ;; value = lerp(0, 100, 0.5) = 50
        (is (< (Math/abs (- 50.0 result)) 0.001))))
    
    (testing "position between second and third keyframes"
      (let [result (kf/eval-spatial-keyframe config {:point-index 6 :point-count 9})]
        ;; point 6 of 9 = 0.75 position, between 0.5->1.0 segment
        ;; t = (0.75 - 0.5) / (1.0 - 0.5) = 0.5
        ;; value = lerp(100, 50, 0.5) = 75
        (is (< (Math/abs (- 75.0 result)) 0.001))))))

;; =============================================================================
;; eval-spatial-keyframe tests - Interpolation Modes
;; =============================================================================

(deftest eval-spatial-keyframe-interpolation-modes-test
  (testing ":linear produces linear interpolation"
    (let [config {:type :spatial-keyframe
                  :axis :pos-x
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 1.0 :value 100.0}]}
          result (kf/eval-spatial-keyframe config {:x 0.0})]
      ;; x=0 -> pos=0.5 -> linear interp -> 50
      (is (< (Math/abs (- 50.0 result)) 0.001))))
  
  (testing ":step holds value until next keyframe"
    (let [config {:type :spatial-keyframe
                  :axis :pos-x
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :step}
                              {:position 1.0 :value 100.0}]}
          result (kf/eval-spatial-keyframe config {:x 0.0})]
      ;; x=0 -> pos=0.5 -> step interp -> 0 (holds until t=1)
      (is (< (Math/abs (- 0.0 result)) 0.001))))
  
  (testing ":exp-decay produces fast-start curve"
    (let [config {:type :spatial-keyframe
                  :axis :pos-x
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :exp-decay}
                              {:position 1.0 :value 100.0}]}
          result (kf/eval-spatial-keyframe config {:x 0.0})]
      ;; x=0 -> pos=0.5 -> exp-decay should be > 50 (fast start)
      (is (> result 50.0))))
  
  (testing ":exp-grow produces slow-start curve"
    (let [config {:type :spatial-keyframe
                  :axis :pos-x
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :exp-grow}
                              {:position 1.0 :value 100.0}]}
          result (kf/eval-spatial-keyframe config {:x 0.0})]
      ;; x=0 -> pos=0.5 -> exp-grow should be < 50 (slow start)
      (is (< result 50.0)))))

;; =============================================================================
;; eval-spatial-keyframe tests - Edge Cases
;; =============================================================================

(deftest eval-spatial-keyframe-edge-cases-test
  (testing "single keyframe returns that value regardless of position"
    (let [config {:type :spatial-keyframe
                  :axis :point-index
                  :keyframes [{:position 0.5 :value 42.0}]}]
      (is (= 42.0 (kf/eval-spatial-keyframe config {:point-index 0 :point-count 10})))
      (is (= 42.0 (kf/eval-spatial-keyframe config {:point-index 5 :point-count 10})))
      (is (= 42.0 (kf/eval-spatial-keyframe config {:point-index 9 :point-count 10})))))
  
  (testing "empty keyframes returns fallback value (midpoint of min/max or 0.0)"
    (let [config {:type :spatial-keyframe
                  :axis :point-index
                  :keyframes []}]
      ;; With no min/max/value, fallback is 0.0
      (is (= 0.0 (kf/eval-spatial-keyframe config {:point-index 5 :point-count 10}))))
    (let [config {:type :spatial-keyframe
                  :axis :point-index
                  :keyframes []
                  :min -100.0
                  :max 100.0}]
      ;; With min/max, fallback is midpoint
      (is (= 0.0 (kf/eval-spatial-keyframe config {:point-index 5 :point-count 10}))))
    (let [config {:type :spatial-keyframe
                  :axis :point-index
                  :keyframes []
                  :value 42.0}]
      ;; With explicit :value, use that as fallback
      (is (= 42.0 (kf/eval-spatial-keyframe config {:point-index 5 :point-count 10})))))
  
  (testing "keyframes out of order are sorted before use"
    (let [config {:type :spatial-keyframe
                  :axis :point-index
                  :keyframes [{:position 1.0 :value 100.0}
                              {:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 0.5 :value 50.0 :interpolation :linear}]}
          result (kf/eval-spatial-keyframe config {:point-index 2 :point-count 9})]
      ;; Should still interpolate correctly despite out-of-order input
      ;; point 2/8 = 0.25, which is 50% between 0.0 and 0.5
      ;; lerp(0, 50, 0.5) = 25
      (is (< (Math/abs (- 25.0 result)) 0.001))))
  
  (testing "nil values in keyframes default to 0.0"
    (let [config {:type :spatial-keyframe
                  :axis :point-index
                  :keyframes [{:position 0.0 :interpolation :linear}
                              {:position 1.0 :value 100.0}]}
          result (kf/eval-spatial-keyframe config {:point-index 5 :point-count 11})]
      ;; Missing :value defaults to 0.0
      (is (< (Math/abs (- 50.0 result)) 0.001)))))

;; =============================================================================
;; eval-spatial-keyframe tests - Integration with Different Axes
;; =============================================================================

(deftest eval-spatial-keyframe-axis-integration-test
  (let [config-base {:type :spatial-keyframe
                     :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                                 {:position 1.0 :value 100.0}]}]
    
    (testing "works with :point-index axis"
      (let [config (assoc config-base :axis :point-index)
            result (kf/eval-spatial-keyframe config {:point-index 5 :point-count 11})]
        (is (< (Math/abs (- 50.0 result)) 0.001))))
    
    (testing "works with :pos-x axis"
      (let [config (assoc config-base :axis :pos-x)
            result (kf/eval-spatial-keyframe config {:x 0.0})]
        ;; x=0 maps to pos=0.5
        (is (< (Math/abs (- 50.0 result)) 0.001))))
    
    (testing "works with :pos-y axis"
      (let [config (assoc config-base :axis :pos-y)
            result (kf/eval-spatial-keyframe config {:y 0.0})]
        ;; y=0 maps to pos=0.5
        (is (< (Math/abs (- 50.0 result)) 0.001))))
    
    (testing "works with :radial axis with normalize?=true"
      (let [config (assoc config-base :axis :radial :normalize? true)
            result (kf/eval-spatial-keyframe config {:x 0.0 :y 0.0})]
        ;; center maps to pos=0.0
        (is (< (Math/abs (- 0.0 result)) 0.001))))
    
    (testing "works with :radial axis with normalize?=false"
      (let [config (assoc config-base :axis :radial :normalize? false)
            result (kf/eval-spatial-keyframe config {:x 1.0 :y 0.0})]
        ;; (1,0) at distance 1.0, normalize?=false means max=1.0, so pos=1.0
        (is (< (Math/abs (- 100.0 result)) 0.001))))
    
    (testing "works with :angle axis"
      (let [config (assoc config-base :axis :angle)
            result (kf/eval-spatial-keyframe config {:x 1.0 :y 0.0})]
        ;; angle at (1,0) is 0, normalized to 0.5
        (is (< (Math/abs (- 50.0 result)) 0.001))))))

;; =============================================================================
;; eval-spatial-keyframe tests - Default Values
;; =============================================================================

(deftest eval-spatial-keyframe-defaults-test
  (testing "default axis is :point-index"
    (let [config {:type :spatial-keyframe
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 1.0 :value 100.0}]}
          result (kf/eval-spatial-keyframe config {:point-index 5 :point-count 11})]
      (is (< (Math/abs (- 50.0 result)) 0.001))))
  
  (testing "default normalize? is true"
    (let [config {:type :spatial-keyframe
                  :axis :radial
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 1.0 :value 100.0}]}
          ;; At corner (1,1), radial distance = sqrt(2), normalized by sqrt(2) = 1.0
          result (kf/eval-spatial-keyframe config {:x 1.0 :y 1.0})]
      (is (< (Math/abs (- 100.0 result)) 0.001))))
  
  (testing "default interpolation is :linear"
    (let [config {:type :spatial-keyframe
                  :axis :pos-x
                  :keyframes [{:position 0.0 :value 0.0}
                              {:position 1.0 :value 100.0}]}
          result (kf/eval-spatial-keyframe config {:x 0.0})]
      ;; Without explicit :interpolation, should use :linear
      (is (< (Math/abs (- 50.0 result)) 0.001)))))

;; =============================================================================
;; build-default-modulator tests - Spatial Keyframe Default Keyframes
;; =============================================================================

(deftest build-default-modulator-spatial-keyframe-test
  (testing "spatial-keyframe modulator gets default keyframes"
    (let [config (mod-defs/build-default-modulator :spatial-keyframe {:min -360.0 :max 360.0})]
      (is (contains? config :keyframes) "must have :keyframes")
      (is (vector? (:keyframes config)) "keyframes must be a vector")
      (is (= 2 (count (:keyframes config))) "must have 2 default keyframes")
      ;; Check keyframe structure
      (let [[kf1 kf2] (:keyframes config)]
        (is (= 0.0 (:position kf1)) "first keyframe at position 0.0")
        (is (= 1.0 (:position kf2)) "second keyframe at position 1.0")
        (is (= -360.0 (:value kf1)) "first keyframe value is min")
        (is (= 360.0 (:value kf2)) "second keyframe value is max")
        (is (= :linear (:interpolation kf1)) "first keyframe has linear interpolation")
        (is (= :linear (:interpolation kf2)) "second keyframe has linear interpolation"))))
  
  (testing "spatial-keyframe modulator uses param-spec min/max for keyframe values"
    (let [config (mod-defs/build-default-modulator :spatial-keyframe {:min 0.0 :max 100.0})]
      (is (= 0.0 (get-in config [:keyframes 0 :value])))
      (is (= 100.0 (get-in config [:keyframes 1 :value])))))
  
  (testing "other modulator types do not get default keyframes"
    (let [sine-config (mod-defs/build-default-modulator :sine {:min 0.0 :max 1.0})]
      (is (not (contains? sine-config :keyframes)) "sine should not have keyframes"))))

;; =============================================================================
;; Integration tests - Spatial Keyframe Modulator in Effect Param Resolution
;; =============================================================================

(deftest spatial-keyframe-modulator-integration-test
  (testing "spatial-keyframe modulator can be evaluated via modulation/evaluate-modulator"
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :point-index
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 1.0 :value 100.0}]}
          context {:point-index 5 :point-count 11}
          result (mod/evaluate-modulator config context)]
      (is (number? result) "result must be a number")
      (is (< (Math/abs (- 50.0 result)) 0.001) "should interpolate to 50.0")))
  
  (testing "spatial-keyframe modulator returns fallback when keyframes missing"
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :point-index
                  :min -100.0
                  :max 100.0}  ;; No :keyframes!
          context {:point-index 5 :point-count 10}
          result (mod/evaluate-modulator config context)]
      (is (number? result) "result must be a number even without keyframes")
      (is (= 0.0 result) "should return midpoint of min/max as fallback")))
  
  (testing "newly created spatial-keyframe modulator works immediately"
    ;; This simulates the UI flow: user selects spatial-keyframe type from dropdown
    (let [config (mod-defs/build-default-modulator :spatial-keyframe {:min -360.0 :max 360.0})
          context {:point-index 0 :point-count 10}
          result (mod/evaluate-modulator config context)]
      (is (number? result) "newly created modulator must return a number")
      (is (= -360.0 result) "first point should get min value")))
  
  (testing "spatial-keyframe modulator returns number for last point"
    (let [config (mod-defs/build-default-modulator :spatial-keyframe {:min 0.0 :max 360.0})
          context {:point-index 9 :point-count 10}
          result (mod/evaluate-modulator config context)]
      (is (number? result))
      (is (= 360.0 result) "last point should get max value"))))

;; =============================================================================
;; Error Prevention Tests - Type Safety
;; =============================================================================

(deftest spatial-keyframe-type-safety-test
  (testing "eval-spatial-keyframe never returns nil"
    ;; These are all edge cases that previously could cause nil returns
    (is (number? (kf/eval-spatial-keyframe {:type :spatial-keyframe :keyframes []} {})))
    (is (number? (kf/eval-spatial-keyframe {:type :spatial-keyframe} {})))
    (is (number? (kf/eval-spatial-keyframe {:type :spatial-keyframe :keyframes nil} {}))))
  
  (testing "eval-spatial-keyframe handles missing context gracefully"
    (let [config {:type :spatial-keyframe
                  :axis :point-index
                  :keyframes [{:position 0.0 :value 0.0}
                              {:position 1.0 :value 100.0}]}]
      ;; Missing :point-index and :point-count
      (is (number? (kf/eval-spatial-keyframe config {})))
      ;; Missing :point-count only
      (is (number? (kf/eval-spatial-keyframe config {:point-index 5})))))
  
  (testing "eval-spatial-keyframe handles position axis with missing coordinates"
    (let [config {:type :spatial-keyframe
                  :axis :pos-x
                  :keyframes [{:position 0.0 :value 0.0}
                              {:position 1.0 :value 100.0}]}]
      ;; Missing :x coordinate - should use default
      (is (number? (kf/eval-spatial-keyframe config {:y 0.5})))))
  
  (testing "eval-spatial-keyframe handles radial axis with missing coordinates"
    (let [config {:type :spatial-keyframe
                  :axis :radial
                  :keyframes [{:position 0.0 :value 0.0}
                              {:position 1.0 :value 100.0}]}]
      ;; Missing both x and y
      (is (number? (kf/eval-spatial-keyframe config {}))))))
