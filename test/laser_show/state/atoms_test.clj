(ns laser-show.state.atoms-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.state.atoms :as state]))

;; Reset state before each test
(defn reset-fixture [f]
  (state/reset-all!)
  (f))

(use-fixtures :each reset-fixture)

;; ============================================================================
;; Integration Tests (Complex State Transitions)
;; ============================================================================

(deftest test-trigger-cell
  (testing "Trigger cell sets active cell, playing, and trigger time"
    (state/reset-all!)
    (let [before (System/currentTimeMillis)]
      (state/trigger-cell! 3 2)
      (let [after (System/currentTimeMillis)]
        (is (= [3 2] (state/get-active-cell)))
        (is (true? (state/playing?)))
        (is (>= (state/get-trigger-time) before))
        (is (<= (state/get-trigger-time) after))))))

(deftest test-grid-move-cell
  (testing "Move cell from one position to another (non-trivial state operation)"
    (state/set-cell-preset! 5 5 :test-preset)
    (state/move-cell! 5 5 6 6)
    (is (nil? (state/get-cell 5 5)))
    (is (= {:preset-id :test-preset} (state/get-cell 6 6)))))

;; ============================================================================
;; Effects State Tests (Complex State Operations)
;; ============================================================================

(deftest test-effect-chain-ordering
  (testing "Get all active effect instances in row-major order (tests complex ordering logic)"
    (state/reset-effects!)
    ;; Set up effects: [1,0] active, [0,1] active, [2,0] inactive
    (state/set-effect-cell! 1 0 {:effects [{:effect-id :rotate :params {:angle 45}}] :active true})
    (state/set-effect-cell! 0 1 {:effects [{:effect-id :scale :params {:x 2.0}}] :active true})
    (state/set-effect-cell! 2 0 {:effects [{:effect-id :hue :params {:shift 30}}] :active false})
    
    (let [active (state/get-all-active-effect-instances)]
      ;; Should be in row-major order: [1,0] then [0,1]
      (is (= 2 (count active)))
      (is (= :rotate (:effect-id (first active))))
      (is (= :scale (:effect-id (second active)))))))

(deftest test-effect-chain-operations
  (testing "Chain operations maintain order when removing effects from middle"
    (state/reset-effects!)
    (state/add-effect-to-cell! 0 0 {:effect-id :scale :params {}})
    (state/add-effect-to-cell! 0 0 {:effect-id :rotate :params {}})
    (state/add-effect-to-cell! 0 0 {:effect-id :hue :params {}})
    
    ;; Remove middle effect - tests that indices are handled correctly
    (state/remove-effect-from-cell! 0 0 1)
    (let [effects (:effects (state/get-effect-cell 0 0))]
      (is (= 2 (count effects)))
      (is (= :scale (:effect-id (first effects))))
      (is (= :hue (:effect-id (second effects)))))))

(deftest test-effect-param-updates
  (testing "Parameter updates preserve other params (tests partial update logic)"
    (state/reset-effects!)
    (state/add-effect-to-cell! 0 0 {:effect-id :scale :params {:x 1.0 :y 1.0}})
    (state/update-effect-param! 0 0 0 :x 2.5)
    (is (= 2.5 (state/get-effect-param 0 0 0 :x)))
    (is (= 1.0 (state/get-effect-param 0 0 0 :y)))))

