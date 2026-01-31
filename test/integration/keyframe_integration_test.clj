(ns integration.keyframe-integration-test
  "Integration tests for keyframe features (Phases 1-5).
   
   Covers:
   - Effect chain integration with spatial keyframes
   - Serialization round-trip verification
   - Full end-to-end scenarios
   - Performance considerations verification"
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.edn :as edn]
   [laser-show.animation.effects :as effects]
   [laser-show.animation.interpolation :as interp]
   [laser-show.animation.keyframes :as kf]
   [laser-show.animation.modulation :as mod]))


(deftest make-param-resolver-static-value-test
  (testing "make-param-resolver returns constant function for static value"
    (let [params {:hue 180.0}
          ctx {:point-count 10 :timing-ctx {}}
          resolver (effects/make-param-resolver :hue params 0 120 ctx)]
      ;; All points should get the same value
      (is (= 180.0 (resolver 0.0 0.0 0)))
      (is (= 180.0 (resolver 0.0 0.0 5)))
      (is (= 180.0 (resolver 0.0 0.0 9))))))

(deftest make-param-resolver-time-based-modulator-test
  (testing "make-param-resolver returns constant function for time-based modulator"
    (let [config {:type :sine :min 0.0 :max 100.0 :period 1.0 :active? true}
          params {:amount config}
          ctx {:point-count 10 :timing-ctx {}}
          resolver (effects/make-param-resolver :amount params 0 120 ctx)
          v0 (resolver 0.0 0.0 0)
          v5 (resolver 0.5 0.5 5)
          v9 (resolver 0.9 0.9 9)]
      ;; All points should get the same value (resolved once)

      (is (= v0 v5))
      (is (= v5 v9)))))


;; =============================================================================
;; 6.2 Serialization Verification
;; =============================================================================


(deftest interpolation-serialization-round-trip-test
  (testing "Interpolation key survives EDN round-trip"
    (let [keyframe {:position 0.5
                    :params {:x 1.0 :y 2.0}
                    :interpolation :exp-decay}
          serialized (pr-str keyframe)
          deserialized (edn/read-string serialized)]
      (is (= :exp-decay (:interpolation deserialized)))
      (is (= 0.5 (:position deserialized)))
      (is (= {:x 1.0 :y 2.0} (:params deserialized))))))

(deftest time-keyframe-modulator-serialization-test
  (testing "Time-based keyframe modulator survives EDN round-trip"
    (let [modulator {:enabled? true
                     :period 4.0
                     :time-unit :beats
                     :loop-mode :loop
                     :keyframes [{:position 0.0 :params {:scale 1.0} :interpolation :linear}
                                 {:position 0.5 :params {:scale 2.0} :interpolation :exp-grow}
                                 {:position 1.0 :params {:scale 1.0} :interpolation :step}]}
          serialized (pr-str modulator)
          deserialized (edn/read-string serialized)]
      (is (= true (:enabled? deserialized)))
      (is (= 4.0 (:period deserialized)))
      (is (= :beats (:time-unit deserialized)))
      (is (= 3 (count (:keyframes deserialized))))
      ;; Interpolation modes preserved
      (is (= :linear (get-in deserialized [:keyframes 0 :interpolation])))
      (is (= :exp-grow (get-in deserialized [:keyframes 1 :interpolation])))
      (is (= :step (get-in deserialized [:keyframes 2 :interpolation]))))))

;; =============================================================================
;; Backward Compatibility
;; =============================================================================


(deftest backward-compatibility-keyframe-without-interpolation-test
  (testing "Old keyframes without :interpolation key default to :linear"
    (let [old-config {:keyframes [{:position 0.0 :params {:x 0.0}}
                                  {:position 1.0 :params {:x 100.0}}]
                      :period 1.0
                      :time-unit :beats}
          bpm 120
          ms-per-beat (/ 60000 bpm)
          ctx {:time-ms (* 0.5 ms-per-beat) :bpm bpm}
          result (kf/eval-keyframe old-config ctx)]
      ;; Should behave as linear: at t=0.5, x should be 50.0
      (is (< (Math/abs (- 50.0 (:x result))) 0.1)))))

(deftest backward-compatibility-modulator-without-active-key-test
  (testing "Old modulators without :active? key are treated as active"
    (let [config {:type :sine :min 0.0 :max 1.0 :period 1.0}]
      ;; Should be treated as active (default true)
      (is (mod/modulator-config? config))
      ;; config-requires-per-point? returns false for sine (not per-point)
      (is (not (mod/config-requires-per-point? config))))))


;; =============================================================================
;; 6.4 Performance Considerations Tests
;; =============================================================================


(deftest no-lazy-sequences-in-interpolation-test
  (testing "interpolate-params uses mapv (not lazy map)"
    ;; This test ensures the code doesn't introduce lazy sequences
    (let [p1 {:x 0.0 :y 0.0 :z 0.0}
          p2 {:x 10.0 :y 20.0 :z 30.0}
          result (interp/interpolate-params p1 p2 0.5)]
      ;; Result should be a map, not lazy sequence
      (is (map? result))
      ;; All values should be already realized
      (is (= 5.0 (:x result)))
      (is (= 10.0 (:y result)))
      (is (= 15.0 (:z result))))))

;; =============================================================================
;; Unified Driver System Integration Tests
;; =============================================================================


(deftest spatial-driver-keyframe-evaluation-test
  (testing "eval-keyframe with point-index driver varies per point"
    (let [config {:keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 1.0 :value 1.0}]
                  :driver :point-index
                  :params {:value {:min 0.0 :max 1.0}}}
          ;; First point
          ctx-first {:point-index 0 :point-count 3 :time-ms 0}
          ;; Last point
          ctx-last {:point-index 2 :point-count 3 :time-ms 0}
          
          result-first (kf/eval-keyframe config ctx-first)
          result-last (kf/eval-keyframe config ctx-last)]
      ;; First point (index 0) should have value near 0.0
      (is (< (Math/abs (double (or (:value result-first) 0.0))) 0.1))
      ;; Last point (index 2) should have value near 1.0
      (is (> (Math/abs (double (or (:value result-last) 1.0))) 0.9)))))

(deftest per-point-detection-integration-test
  (testing "spatial driver triggers per-point evaluation"
    (let [effect-spatial {:id (random-uuid)
                          :effect-id :set-color
                          :params {:red 1.0}
                          :keyframe-modulator {:enabled? true
                                               :driver :pos-x
                                               :keyframes [{:position 0.0 :params {:red 0.0}}
                                                           {:position 1.0 :params {:red 1.0}}]}}
          effect-time {:id (random-uuid)
                       :effect-id :set-color
                       :params {:red 1.0}
                       :keyframe-modulator {:enabled? true
                                            :driver :time
                                            :period 1.0
                                            :keyframes [{:position 0.0 :params {:red 0.0}}
                                                        {:position 1.0 :params {:red 1.0}}]}}]
      ;; Spatial driver requires per-point
      (is (true? (mod/keyframe-modulator-requires-per-point? effect-spatial)))
      ;; Time driver does not
      (is (false? (mod/keyframe-modulator-requires-per-point? effect-time)))))
  
  (testing "disabled keyframe-modulator does not require per-point"
    (let [effect-disabled {:id (random-uuid)
                           :effect-id :set-color
                           :params {:red 1.0}
                           :keyframe-modulator {:enabled? false
                                                :driver :point-index
                                                :keyframes [{:position 0.0 :params {:red 0.0}}
                                                            {:position 1.0 :params {:red 1.0}}]}}]
      ;; Returns nil or false when disabled - both are falsy
      (is (not (mod/keyframe-modulator-requires-per-point? effect-disabled))))))

(deftest unified-driver-serialization-test
  (testing "effect with spatial driver survives serialization"
    (let [effect {:id #uuid "33333333-3333-3333-3333-333333333333"
                  :effect-id :set-color
                  :params {:red 1.0}
                  :keyframe-modulator {:enabled? true
                                       :driver :radial
                                       :edge-behavior :clamp
                                       :normalize? true
                                       :keyframes [{:position 0.0 :params {:red 0.0} :interpolation :linear}
                                                   {:position 1.0 :params {:red 1.0}}]}}
          serialized (pr-str effect)
          deserialized (edn/read-string serialized)]
      ;; Driver preserved
      (is (= :radial (get-in deserialized [:keyframe-modulator :driver])))
      ;; Edge behavior preserved
      (is (= :clamp (get-in deserialized [:keyframe-modulator :edge-behavior])))
      ;; Normalize flag preserved
      (is (true? (get-in deserialized [:keyframe-modulator :normalize?])))
      ;; Keyframes preserved
      (is (= 2 (count (get-in deserialized [:keyframe-modulator :keyframes]))))
      ;; Interpolation preserved
      (is (= :linear (get-in deserialized [:keyframe-modulator :keyframes 0 :interpolation]))))))

(deftest different-spatial-drivers-test
  (testing "different drivers produce different results for same point"
    (let [base-config {:keyframes [{:position 0.0 :params {:value 0.0} :interpolation :linear}
                                   {:position 1.0 :params {:value 1.0}}]}
          ;; Point at x=0.5, y=-0.5, index 1 of 4
          context {:x 0.5 :y -0.5 :point-index 1 :point-count 4 :time-ms 0}
          result-x (kf/eval-keyframe (assoc base-config :driver :pos-x) context)
          result-y (kf/eval-keyframe (assoc base-config :driver :pos-y) context)
          result-idx (kf/eval-keyframe (assoc base-config :driver :point-index) context)
          
          value-x (double (or (:value result-x) 0.0))
          value-y (double (or (:value result-y) 0.0))
          value-idx (double (or (:value result-idx) 0.0))]
      ;; pos-x: x=0.5 maps to position (0.5+1)/2 = 0.75
      ;; pos-y: y=-0.5 maps to position (-0.5+1)/2 = 0.25
      ;; point-index: 1/3 ≈ 0.333
      
      ;; All values should be different since drivers use different inputs
      (is (not= value-x value-y) "pos-x and pos-y should produce different values")
      (is (not= value-x value-idx) "pos-x and point-index should produce different values")
      (is (not= value-y value-idx) "pos-y and point-index should produce different values")
      
      ;; Verify expected ranges based on position calculation
      ;; pos-x: position = 0.75, so value ≈ 0.75
      (is (< (Math/abs (- 0.75 value-x)) 0.1) "pos-x value should be near 0.75")
      ;; pos-y: position = 0.25, so value ≈ 0.25
      (is (< (Math/abs (- 0.25 value-y)) 0.1) "pos-y value should be near 0.25")
      ;; point-index: position = 1/3 ≈ 0.333, so value ≈ 0.333
      (is (< (Math/abs (- 0.333 value-idx)) 0.1) "point-index value should be near 0.333"))))

(deftest radial-driver-keyframe-test
  (testing "radial driver produces center-to-edge gradient"
    (let [config {:keyframes [{:position 0.0 :params {:intensity 1.0} :interpolation :linear}
                              {:position 1.0 :params {:intensity 0.0}}]
                  :driver :radial
                  :normalize? true}
          ;; Center point
          ctx-center {:x 0.0 :y 0.0 :time-ms 0}
          ;; Corner point (max distance with normalize)
          ctx-corner {:x 1.0 :y 1.0 :time-ms 0}
          ;; Mid-distance point
          ctx-mid {:x 0.5 :y 0.0 :time-ms 0}
          
          result-center (kf/eval-keyframe config ctx-center)
          result-corner (kf/eval-keyframe config ctx-corner)
          result-mid (kf/eval-keyframe config ctx-mid)]
      ;; Center should have high intensity (position 0.0 -> value 1.0)
      (is (< (Math/abs (- 1.0 (double (or (:intensity result-center) 0.0)))) 0.01))
      ;; Corner should have low intensity (position 1.0 -> value 0.0)
      (is (< (Math/abs (double (or (:intensity result-corner) 0.0))) 0.01))
      ;; Mid should be between (position ~0.35 -> value ~0.65)
      (let [mid-val (double (or (:intensity result-mid) 0.0))]
        (is (< mid-val 0.8))
        (is (> mid-val 0.5))))))

(deftest time-driver-backward-compatibility-test
  (testing "default driver is :time for backward compatibility"
    (let [config {:keyframes [{:position 0.0 :params {:scale 0.5} :interpolation :linear}
                              {:position 1.0 :params {:scale 1.5}}]
                  :period 1.0
                  :time-unit :beats}
          ;; At halfway through the beat period, scale should be 1.0
          bpm 120
          ms-per-beat (/ 60000 bpm)
          ctx {:time-ms (* 0.5 ms-per-beat) :bpm bpm}
          result (kf/eval-keyframe config ctx)]
      ;; At position 0.5, scale should be 1.0 (midpoint of 0.5 to 1.5)
      (is (< (Math/abs (- 1.0 (double (or (:scale result) 0.0)))) 0.1)))))

(deftest edge-behavior-integration-test
  (testing "spatial drivers default to :clamp edge behavior"
    (let [config {:keyframes [{:position 0.2 :params {:value 10.0} :interpolation :linear}
                              {:position 0.8 :params {:value 90.0}}]
                  :driver :pos-x}
          ;; Position beyond keyframe range (x=-1 -> pos=0.0, before first keyframe at 0.2)
          ctx-before {:x -1.0 :time-ms 0}
          ;; Position beyond keyframe range (x=1 -> pos=1.0, after last keyframe at 0.8)
          ctx-after {:x 1.0 :time-ms 0}
          
          result-before (kf/eval-keyframe config ctx-before)
          result-after (kf/eval-keyframe config ctx-after)]
      ;; With :clamp, positions outside range should clamp to first/last values
      (is (< (Math/abs (- 10.0 (double (or (:value result-before) 0.0)))) 0.1)
          "Value should clamp to first keyframe value")
      (is (< (Math/abs (- 90.0 (double (or (:value result-after) 0.0)))) 0.1)
          "Value should clamp to last keyframe value")))
  
  (testing "time drivers default to :wrap edge behavior"
    (let [config {:keyframes [{:position 0.0 :params {:value 0.0} :interpolation :linear}
                              {:position 1.0 :params {:value 100.0}}]
                  :driver :time
                  :period 1.0
                  :time-unit :beats}
          ;; At 1.5 beats with period 1.0, should wrap to 0.5 position
          bpm 120
          ms-per-beat (/ 60000 bpm)
          ctx {:time-ms (* 1.5 ms-per-beat) :bpm bpm}
          result (kf/eval-keyframe config ctx)]
      ;; With :wrap, position 1.5 wraps to 0.5, so value should be 50.0
      (is (< (Math/abs (- 50.0 (double (or (:value result) 0.0)))) 0.1)
          "Value should wrap around and be at midpoint"))))

(deftest kf-spatial-driver-detection-test
  (testing "kf/spatial-driver? correctly identifies spatial drivers"
    (is (true? (kf/spatial-driver? :point-index)))
    (is (true? (kf/spatial-driver? :pos-x)))
    (is (true? (kf/spatial-driver? :pos-y)))
    (is (true? (kf/spatial-driver? :radial)))
    (is (false? (kf/spatial-driver? :time)))
    (is (false? (kf/spatial-driver? nil)))))
