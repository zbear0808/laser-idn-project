(ns laser-show.events.effects-handlers-test
  "Tests for effects handler logic.
   
   Effect-level operations (curve points, reorder, remove) now use :chain/* events
   from chain.clj. Effects-specific operations use :effects/*."
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.handlers.effects :as effects]
   [laser-show.events.handlers.chain :as chain]
   [laser-show.state.domains :refer [build-initial-state]]))


;; Test Data Fixtures


(defn make-effect
  "Create a test effect with an ID."
  [id-suffix effect-id]
  {:id (java.util.UUID/fromString (str "00000000-0000-0000-0000-00000000000" id-suffix))
   :effect-id effect-id
   :enabled? true
   :params {}})

(def sample-effects
  [(make-effect "1" :scale)
   (make-effect "2" :rotate)
   (make-effect "3" :intensity)])

(def base-state
  "Base state using actual app initial state structure."
  (build-initial-state))

(def sample-state
  "State with effect chain for testing."
  (-> base-state
      (assoc-in [:chains :effect-chains [0 0]] {:items sample-effects :active true})
      (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
      (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})))


;; Reordering Tests (using chain/handle with :chain/reorder-items)


(deftest handle-effects-reorder-test
  (testing "Reorder moves effect forward in chain"
    (let [event {:event/type :chain/reorder-items
                 :domain :effect-chains
                 :entity-key [0 0]
                 :from-idx 0  ;; scale
                 :to-idx 2    ;; move after rotate and intensity
                 :state sample-state}
          result (chain/handle event)
          new-effects (get-in result [:state :chains :effect-chains [0 0] :items])]
      ;; Order should be: rotate, intensity, scale
      (is (= :rotate (:effect-id (nth new-effects 0))))
      (is (= :intensity (:effect-id (nth new-effects 1))))
      (is (= :scale (:effect-id (nth new-effects 2))))
      (is (true? (get-in result [:state :project :dirty?])))))
  
  (testing "Reorder moves effect backward in chain"
    (let [event {:event/type :chain/reorder-items
                 :domain :effect-chains
                 :entity-key [0 0]
                 :from-idx 2  ;; intensity
                 :to-idx 0    ;; move before scale
                 :state sample-state}
          result (chain/handle event)
          new-effects (get-in result [:state :chains :effect-chains [0 0] :items])]
      ;; Order should be: intensity, scale, rotate
      (is (= :intensity (:effect-id (nth new-effects 0))))
      (is (= :scale (:effect-id (nth new-effects 1))))
      (is (= :rotate (:effect-id (nth new-effects 2)))))))


;; Remove Effect Tests (using chain/handle with :chain/remove-item-at-path)


(deftest handle-effects-remove-test
  (testing "Remove effect from chain"
    (let [event {:event/type :chain/remove-item-at-path
                 :domain :effect-chains
                 :entity-key [0 0]
                 :path [0]  ;; Remove scale
                 :state sample-state}
          result (chain/handle event)
          new-effects (get-in result [:state :chains :effect-chains [0 0] :items])]
      ;; Effects should be: rotate, intensity (scale removed)
      (is (= 2 (count new-effects)))
      (is (= :rotate (:effect-id (first new-effects))))
      (is (= :intensity (:effect-id (second new-effects))))
      (is (true? (get-in result [:state :project :dirty?])))))
  
  (testing "Remove middle effect"
    (let [event {:event/type :chain/remove-item-at-path
                 :domain :effect-chains
                 :entity-key [0 0]
                 :path [1]  ;; Remove rotate (middle)
                 :state sample-state}
          result (chain/handle event)
          new-effects (get-in result [:state :chains :effect-chains [0 0] :items])]
      ;; Should have scale and intensity
      (is (= 2 (count new-effects)))
      (is (= :scale (:effect-id (first new-effects))))
      (is (= :intensity (:effect-id (second new-effects)))))))


;; Curve Point Manipulation Tests (using chain/handle)


(deftest handle-effects-update-curve-point-test
  (testing "Corner points can only move in Y axis"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0 0] [128 128] [255 255]])
          ;; Try to move first point (corner) to [50, 100]
          event {:event/type :chain/update-curve-point
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :channel :r
                 :point-idx 0
                 :x 50   ;; Should be ignored
                 :y 100  ;; Should be applied
                 :state state-with-curve}
          result (chain/handle event)
          updated-points (get-in result [:state :chains :effect-chains [0 0] :items 0 :params :r-curve-points])]
      ;; First point should keep X=0 but get Y=100
      (is (= [0 100] (first updated-points)))))
  
  (testing "Middle points can move in both X and Y"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0 0] [128 128] [255 255]])
          ;; Move middle point to [150, 180]
          event {:event/type :chain/update-curve-point
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :channel :r
                 :point-idx 1
                 :x 150
                 :y 180
                 :state state-with-curve}
          result (chain/handle event)
          updated-points (get-in result [:state :chains :effect-chains [0 0] :items 0 :params :r-curve-points])]
      ;; Middle point should move to [150, 180]
      (is (= [150 180] (second updated-points)))))
  
  (testing "Points are sorted by X coordinate after update"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0 0] [100 100] [200 200] [255 255]])
          ;; Move point at idx 1 to X=150 (between idx 2 and 3)
          event {:event/type :chain/update-curve-point
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :channel :r
                 :point-idx 1
                 :x 150
                 :y 100
                 :state state-with-curve}
          result (chain/handle event)
          updated-points (get-in result [:state :chains :effect-chains [0 0] :items 0 :params :r-curve-points])]
      ;; Points should be sorted: [0,0], [150,100], [200,200], [255,255]
      (is (= [[0 0] [150 100] [200 200] [255 255]] updated-points)))))


(deftest handle-effects-remove-curve-point-test
  (testing "Cannot remove corner points"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0 0] [128 128] [255 255]])
          ;; Try to remove first point (corner)
          event {:event/type :chain/remove-curve-point
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :channel :r
                 :point-idx 0
                 :state state-with-curve}
          result (chain/handle event)
          points (get-in result [:state :chains :effect-chains [0 0] :items 0 :params :r-curve-points])]
      ;; Should still have all 3 points
      (is (= 3 (count points)))))
  
  (testing "Can remove middle points"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0 0] [64 64] [128 128] [192 192] [255 255]])
          ;; Remove middle point at idx 2
          event {:event/type :chain/remove-curve-point
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :channel :r
                 :point-idx 2
                 :state state-with-curve}
          result (chain/handle event)
          points (get-in result [:state :chains :effect-chains [0 0] :items 0 :params :r-curve-points])]
      ;; Should have 4 points now
      (is (= 4 (count points)))
      ;; [128 128] should be gone
      (is (= [[0 0] [64 64] [192 192] [255 255]] points)))))


(deftest handle-effects-add-curve-point-test
  (testing "New point is inserted and sorted by X"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0 0] [255 255]])
          ;; Add point at [128, 64]
          event {:event/type :chain/add-curve-point
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :channel :r
                 :x 128
                 :y 64
                 :state state-with-curve}
          result (chain/handle event)
          points (get-in result [:state :chains :effect-chains [0 0] :items 0 :params :r-curve-points])]
      ;; Should have 3 points, sorted
      (is (= [[0 0] [128 64] [255 255]] points)))))


;; Cell Operations Tests (still using effects/handle)


(deftest handle-effects-toggle-cell-test
  (testing "Toggle cell enables empty cell"
    (let [event {:event/type :effects/toggle-cell
                 :col 0 :row 1
                 :state sample-state}
          result (effects/handle event)]
      ;; Should create cell with active: true
      (is (true? (get-in result [:state :chains :effect-chains [0 1] :active]))))))

(deftest handle-effects-clear-cell-test
  (testing "Clear cell removes effects"
    (let [event {:event/type :effects/clear-cell
                 :col 0 :row 0
                 :state sample-state}
          result (effects/handle event)]
      ;; Should have empty items
      (is (empty? (get-in result [:state :chains :effect-chains [0 0] :items]))))))
