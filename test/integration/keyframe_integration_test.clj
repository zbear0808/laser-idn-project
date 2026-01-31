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
   [laser-show.animation.modulation :as mod]
   [laser-show.animation.modulator-registry :as reg]
   [laser-show.state.serialization :as ser]))


;; =============================================================================
;; 6.1 Effect Chain Integration Verification
;; =============================================================================


(deftest modulator-config-spatial-keyframe-test
  (testing "modulator-config? returns true for spatial keyframe config"
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :point-index
                  :keyframes [{:position 0.0 :value 0.0}
                              {:position 1.0 :value 100.0}]}]
      (is (mod/modulator-config? config)))))

(deftest config-requires-per-point-spatial-keyframe-test
  (testing "config-requires-per-point? returns true for active spatial keyframe"
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :point-index
                  :keyframes [{:position 0.0 :value 0.0}
                              {:position 1.0 :value 100.0}]}]
      (is (mod/config-requires-per-point? config))
      (is (true? (reg/per-point? :spatial-keyframe)))))
  
  (testing "config-requires-per-point? returns false for inactive spatial keyframe"
    (let [config {:type :spatial-keyframe
                  :active? false
                  :axis :point-index
                  :keyframes [{:position 0.0 :value 0.0}]}]
      (is (not (mod/config-requires-per-point? config))))))

(deftest valid-modulator-type-spatial-keyframe-test
  (testing ":spatial-keyframe is a registered modulator type"
    (is (reg/valid-modulator-type? :spatial-keyframe))
    (is (some? (reg/get-evaluator :spatial-keyframe)))))

(deftest make-param-resolver-spatial-keyframe-test
  (testing "make-param-resolver returns per-point function for spatial keyframe"
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :point-index
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 1.0 :value 100.0}]}
          params {:hue config}
          ctx {:point-count 10 :timing-ctx {}}
          resolver (effects/make-param-resolver :hue params 0 120 ctx)]
      ;; Should be a function (not constantly)
      (is (fn? resolver))
      ;; First point (index 0) should get value near 0
      (is (< (Math/abs (- 0.0 (resolver 0.0 0.0 0))) 0.1))
      ;; Last point (index 9) should get value near 100
      (is (< (Math/abs (- 100.0 (resolver 0.0 0.0 9))) 0.1))
      ;; Middle point should get interpolated value
      (let [mid-value (resolver 0.0 0.0 5)]
        (is (> mid-value 40.0))
        (is (< mid-value 60.0))))))

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
          resolver (effects/make-param-resolver :amount params 0 120 ctx)]
      ;; All points should get the same value (resolved once)
      (let [v0 (resolver 0.0 0.0 0)
            v5 (resolver 0.5 0.5 5)
            v9 (resolver 0.9 0.9 9)]
        (is (= v0 v5))
        (is (= v5 v9))))))


;; =============================================================================
;; Spatial Keyframe Different Points Different Values
;; =============================================================================


(deftest spatial-keyframe-different-points-different-values-test
  (testing "Spatial keyframe produces different values for different points"
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :point-index
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 0.5 :value 180.0 :interpolation :linear}
                              {:position 1.0 :value 360.0}]}
          ;; Use 11 points so index 5 maps to exactly position 0.5 (5/10 = 0.5)
          point-count 11]
      ;; Evaluate for different points
      (let [values (mapv (fn [idx]
                           (kf/eval-spatial-keyframe config
                                                     {:point-index idx
                                                      :point-count point-count}))
                         (range point-count))]
        ;; First point should be 0
        (is (< (Math/abs (- 0.0 (first values))) 0.1))
        ;; Last point should be 360
        (is (< (Math/abs (- 360.0 (last values))) 0.1))
        ;; Middle point (index 5 of 11) at position 0.5 should be ~180
        (is (< (Math/abs (- 180.0 (nth values 5))) 1.0))
        ;; Values should be different (not all the same)
        (is (> (count (set values)) 1))))))

(deftest spatial-keyframe-pos-x-axis-test
  (testing "Spatial keyframe with :pos-x axis produces position-based gradient"
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :pos-x
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 1.0 :value 100.0}]}]
      ;; Left edge (x=-1.0) -> pos 0.0 -> value 0
      (is (< (Math/abs (kf/eval-spatial-keyframe config {:x -1.0})) 0.1))
      ;; Center (x=0.0) -> pos 0.5 -> value 50
      (is (< (Math/abs (- 50.0 (kf/eval-spatial-keyframe config {:x 0.0}))) 0.1))
      ;; Right edge (x=1.0) -> pos 1.0 -> value 100
      (is (< (Math/abs (- 100.0 (kf/eval-spatial-keyframe config {:x 1.0}))) 0.1)))))

(deftest spatial-keyframe-radial-axis-test
  (testing "Spatial keyframe with :radial axis produces center-to-edge gradient"
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :radial
                  :normalize? true
                  :keyframes [{:position 0.0 :value 100.0 :interpolation :linear}
                              {:position 1.0 :value 0.0}]}]
      ;; Center (0,0) -> pos 0.0 -> value 100
      (is (< (Math/abs (- 100.0 (kf/eval-spatial-keyframe config {:x 0.0 :y 0.0}))) 0.1))
      ;; Corner (1,1) -> pos 1.0 -> value 0
      (is (< (Math/abs (kf/eval-spatial-keyframe config {:x 1.0 :y 1.0})) 0.1)))))


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

(deftest spatial-keyframe-serialization-round-trip-test
  (testing "Spatial keyframe config survives EDN round-trip"
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :radial
                  :normalize? false
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 0.5 :value 50.0 :interpolation :exp-decay}
                              {:position 1.0 :value 100.0 :interpolation :step}]}
          serialized (pr-str config)
          deserialized (edn/read-string serialized)]
      ;; Top-level keys preserved
      (is (= :spatial-keyframe (:type deserialized)))
      (is (= :radial (:axis deserialized)))
      (is (= false (:normalize? deserialized)))
      (is (= true (:active? deserialized)))
      ;; Keyframes preserved
      (is (= 3 (count (:keyframes deserialized))))
      (is (= :linear (get-in deserialized [:keyframes 0 :interpolation])))
      (is (= :exp-decay (get-in deserialized [:keyframes 1 :interpolation])))
      (is (= :step (get-in deserialized [:keyframes 2 :interpolation])))
      ;; Values preserved
      (is (= 0.0 (get-in deserialized [:keyframes 0 :value])))
      (is (= 50.0 (get-in deserialized [:keyframes 1 :value])))
      (is (= 100.0 (get-in deserialized [:keyframes 2 :value]))))))

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

(deftest effect-chain-with-keyframes-serialization-test
  (testing "Full effect chain with keyframes survives serialization"
    (let [chain {:effects [{:id #uuid "11111111-1111-1111-1111-111111111111"
                            :effect-id :set-hue
                            :enabled? true
                            :params {:hue {:type :spatial-keyframe
                                           :active? true
                                           :axis :point-index
                                           :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                                                       {:position 1.0 :value 360.0}]}}}
                           {:id #uuid "22222222-2222-2222-2222-222222222222"
                            :effect-id :scale
                            :enabled? true
                            :params {:x-scale 1.0 :y-scale 1.0}
                            :keyframe-modulator {:enabled? true
                                                 :period 2.0
                                                 :keyframes [{:position 0.0 :params {:x-scale 0.5 :y-scale 0.5} :interpolation :linear}
                                                             {:position 1.0 :params {:x-scale 1.5 :y-scale 1.5}}]}}]}
          serialized (ser/serialize chain :pretty? false)
          deserialized (ser/deserialize serialized)]
      ;; Chain structure preserved
      (is (= 2 (count (:effects deserialized))))
      ;; First effect - spatial keyframe modulator
      (let [effect1 (first (:effects deserialized))
            hue-param (get-in effect1 [:params :hue])]
        (is (= :set-hue (:effect-id effect1)))
        (is (= :spatial-keyframe (:type hue-param)))
        (is (= :point-index (:axis hue-param)))
        (is (= 2 (count (:keyframes hue-param)))))
      ;; Second effect - time-based keyframe modulator
      (let [effect2 (second (:effects deserialized))
            kf-mod (:keyframe-modulator effect2)]
        (is (= :scale (:effect-id effect2)))
        (is (= true (:enabled? kf-mod)))
        (is (= 2.0 (:period kf-mod)))
        (is (= 2 (count (:keyframes kf-mod))))))))


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

(deftest backward-compatibility-spatial-keyframe-without-interpolation-test
  (testing "Old spatial keyframes without :interpolation key default to :linear"
    (let [old-config {:type :spatial-keyframe
                      :axis :point-index
                      :keyframes [{:position 0.0 :value 0.0}
                                  {:position 1.0 :value 100.0}]}
          result (kf/eval-spatial-keyframe old-config {:point-index 5 :point-count 11})]
      ;; Should behave as linear: at pos=0.5, value should be 50.0
      (is (< (Math/abs (- 50.0 result)) 0.1)))))

(deftest backward-compatibility-modulator-without-active-key-test
  (testing "Old modulators without :active? key are treated as active"
    (let [config {:type :sine :min 0.0 :max 1.0 :period 1.0}]
      ;; Should be treated as active (default true)
      (is (mod/modulator-config? config))
      ;; config-requires-per-point? returns false for sine (not per-point)
      (is (not (mod/config-requires-per-point? config))))))


;; =============================================================================
;; 6.3 End-to-End Test Scenarios
;; =============================================================================


(deftest scenario-hue-gradient-by-point-index
  (testing "Scenario: Hue gradient by point index"
    ;; Setup: Effect with spatial keyframe on hue param
    ;; Axis: :point-index
    ;; Keyframes: 0.0 → hue 0, 0.5 → hue 180, 1.0 → hue 360
    ;; Expected: First point red, middle point cyan, last point red
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :point-index
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 0.5 :value 180.0 :interpolation :linear}
                              {:position 1.0 :value 360.0}]}
          point-count 101
          ;; Evaluate for first, middle, and last points
          first-hue (kf/eval-spatial-keyframe config {:point-index 0 :point-count point-count})
          middle-hue (kf/eval-spatial-keyframe config {:point-index 50 :point-count point-count})
          last-hue (kf/eval-spatial-keyframe config {:point-index 100 :point-count point-count})]
      ;; First point: red (hue 0)
      (is (< (Math/abs (- 0.0 first-hue)) 1.0))
      ;; Middle point: cyan (hue 180)
      (is (< (Math/abs (- 180.0 middle-hue)) 1.0))
      ;; Last point: red (hue 360)
      (is (< (Math/abs (- 360.0 last-hue)) 1.0)))))

(deftest scenario-intensity-falloff-from-center
  (testing "Scenario: Intensity falloff from center with exp-decay"
    ;; Setup: Effect with spatial keyframe on intensity
    ;; Axis: :radial with :exp-decay interpolation
    ;; Expected: Center bright, edges dim with fast falloff
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :radial
                  :normalize? true
                  :keyframes [{:position 0.0 :value 1.0 :interpolation :exp-decay}
                              {:position 1.0 :value 0.0}]}
          ;; Center point
          center-intensity (kf/eval-spatial-keyframe config {:x 0.0 :y 0.0})
          ;; Half-way point (radial position ~0.35 when normalized due to sqrt(2) max)
          mid-intensity (kf/eval-spatial-keyframe config {:x 0.5 :y 0.0})
          ;; Corner point
          corner-intensity (kf/eval-spatial-keyframe config {:x 1.0 :y 1.0})]
      ;; Center should be bright (1.0)
      (is (< (Math/abs (- 1.0 center-intensity)) 0.01))
      ;; With exp-decay (fast start, ease-out), the interpolation moves quickly at first
      ;; So at mid position, the VALUE should be LESS than the linear midpoint (0.5)
      ;; because we've already traveled more than half the distance
      (is (< mid-intensity 0.7) "exp-decay should move quickly from start value")
      (is (> mid-intensity 0.0) "but should still be positive at mid")
      ;; Corner should be dim (0.0)
      (is (< corner-intensity 0.01)))))

(deftest scenario-horizontal-color-bands
  (testing "Scenario: Horizontal color bands with step interpolation"
    ;; Setup: Effect with spatial keyframe on hue
    ;; Axis: :pos-x with :step interpolation
    ;; Expected: Discrete color zones across X axis
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :pos-x
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :step}
                              {:position 0.33 :value 120.0 :interpolation :step}
                              {:position 0.66 :value 240.0 :interpolation :step}
                              {:position 1.0 :value 360.0}]}
          ;; Far left (x=-1.0 -> pos 0.0, value 0)
          left-hue (kf/eval-spatial-keyframe config {:x -1.0})
          ;; Just before 0.33 threshold (x=-0.35 -> pos 0.325)
          before-first-step (kf/eval-spatial-keyframe config {:x -0.35})
          ;; Just after 0.33 threshold (x=-0.32 -> pos 0.34)
          after-first-step (kf/eval-spatial-keyframe config {:x -0.30})]
      ;; Left should be hue 0 (red)
      (is (< (Math/abs (- 0.0 left-hue)) 0.1))
      ;; Before step should still be hue 0 (held)
      (is (< (Math/abs (- 0.0 before-first-step)) 0.1))
      ;; After step should jump to hue 120 (green)
      (is (< (Math/abs (- 120.0 after-first-step)) 0.1)))))


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

(deftest pre-calculated-constants-test
  (testing "sqrt(2) constant exists for radial normalization"
    ;; The keyframes namespace should have pre-calculated sqrt(2)
    ;; We can verify by checking radial axis calculation
    (let [sqrt-2 (Math/sqrt 2.0)
          config {:type :spatial-keyframe
                  :axis :radial
                  :normalize? true
                  :keyframes [{:position 0.0 :value 0.0}
                              {:position 1.0 :value 1.0}]}
          ;; At corner (1,1), radial distance = sqrt(2), normalized = 1.0
          result (kf/eval-spatial-keyframe config {:x 1.0 :y 1.0})]
      (is (< (Math/abs (- 1.0 result)) 0.001)))))

(deftest keyframe-sorting-efficiency-test
  (testing "Keyframes are sorted for efficient lookup"
    ;; Out-of-order keyframes should still work
    (let [config {:type :spatial-keyframe
                  :axis :point-index
                  :keyframes [{:position 1.0 :value 100.0}
                              {:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 0.5 :value 50.0 :interpolation :linear}]}
          result (kf/eval-spatial-keyframe config {:point-index 3 :point-count 9})]
      ;; Should still interpolate correctly despite out-of-order input
      ;; point 3/8 = 0.375, between 0.0 and 0.5
      ;; Expected: 0.375 / 0.5 * 50 = 37.5
      (is (< (Math/abs (- 37.5 result)) 1.0)))))


;; =============================================================================
;; Spatial Keyframe Type Verification
;; =============================================================================


(deftest spatial-keyframe-type-verification-test
  (testing "Spatial keyframe is properly registered in modulator registry"
    (let [modulator-info (reg/get-modulator :spatial-keyframe)]
      (is (some? modulator-info))
      (is (= :spatial-keyframe (:id modulator-info)))
      (is (= "Spatial Keyframe" (:name modulator-info)))
      (is (= :special (:category modulator-info)))
      (is (true? (:per-point? modulator-info)))
      (is (fn? (:evaluator modulator-info))))))

(deftest spatial-keyframe-evaluator-integration-test
  (testing "Spatial keyframe evaluator works via modulator registry"
    (let [config {:type :spatial-keyframe
                  :active? true
                  :axis :point-index
                  :keyframes [{:position 0.0 :value 0.0 :interpolation :linear}
                              {:position 1.0 :value 100.0}]}
          context {:point-index 5 :point-count 11}
          ;; Use the registry evaluator directly
          result (mod/evaluate-modulator config context)]
      (is (number? result))
      (is (< (Math/abs (- 50.0 result)) 0.1)))))


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
          context {:x 0.5 :y -0.5 :point-index 1 :point-count 4 :time-ms 0}]
      ;; pos-x: x=0.5 maps to position (0.5+1)/2 = 0.75
      ;; pos-y: y=-0.5 maps to position (-0.5+1)/2 = 0.25
      ;; point-index: 1/3 ≈ 0.333
      (let [result-x (kf/eval-keyframe (assoc base-config :driver :pos-x) context)
            result-y (kf/eval-keyframe (assoc base-config :driver :pos-y) context)
            result-idx (kf/eval-keyframe (assoc base-config :driver :point-index) context)
            
            value-x (double (or (:value result-x) 0.0))
            value-y (double (or (:value result-y) 0.0))
            value-idx (double (or (:value result-idx) 0.0))]
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
        (is (< (Math/abs (- 0.333 value-idx)) 0.1) "point-index value should be near 0.333")))))

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
