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
  ;; FLATTENED: Dialog fields live alongside :open?, not under :data
  (-> base-state
      (assoc-in [:chains :effect-chains [0 0]] {:items sample-effects :active? true})
      (assoc-in [:ui :dialogs :effect-chain-editor :selected-effect-indices] #{})
      (assoc-in [:ui :dialogs :effect-chain-editor :selected-paths] #{})))




;; Curve Point Manipulation Tests (using chain/handle)


(deftest handle-effects-update-curve-point-test
  (testing "Corner points can only move in Y axis"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          ;; Try to move first point (corner) to [0.2, 0.4]
          event {:event/type :chain/update-curve-point
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :channel :r
                 :point-idx 0
                 :x 0.2   ;; Should be ignored
                 :y 0.4  ;; Should be applied
                 :state state-with-curve}
          result (chain/handle event)
          updated-points (get-in result [:state :chains :effect-chains [0 0] :items 0 :params :r-curve-points])]
      ;; First point should keep X=0.0 but get Y=0.4
      (is (= [0.0 0.4] (first updated-points)))))
  
  (testing "Middle points can move in both X and Y"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          ;; Move middle point to [0.6, 0.7]
          event {:event/type :chain/update-curve-point
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :channel :r
                 :point-idx 1
                 :x 0.6
                 :y 0.7
                 :state state-with-curve}
          result (chain/handle event)
          updated-points (get-in result [:state :chains :effect-chains [0 0] :items 0 :params :r-curve-points])]
      ;; Middle point should move to [0.6, 0.7]
      (is (= [0.6 0.7] (second updated-points)))))
  
  (testing "Points are sorted by X coordinate after update"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0.0 0.0] [0.4 0.4] [0.8 0.8] [1.0 1.0]])
          ;; Move point at idx 1 to X=0.6 (between idx 2 and 3)
          event {:event/type :chain/update-curve-point
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :channel :r
                 :point-idx 1
                 :x 0.6
                 :y 0.4
                 :state state-with-curve}
          result (chain/handle event)
          updated-points (get-in result [:state :chains :effect-chains [0 0] :items 0 :params :r-curve-points])]
      ;; Points should be sorted: [0.0,0.0], [0.6,0.4], [0.8,0.8], [1.0,1.0]
      (is (= [[0.0 0.0] [0.6 0.4] [0.8 0.8] [1.0 1.0]] updated-points)))))


(deftest handle-effects-remove-curve-point-test
  (testing "Cannot remove corner points"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
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
                                     [[0.0 0.0] [0.25 0.25] [0.5 0.5] [0.75 0.75] [1.0 1.0]])
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
      ;; [0.5 0.5] should be gone
      (is (= [[0.0 0.0] [0.25 0.25] [0.75 0.75] [1.0 1.0]] points)))))


(deftest handle-effects-add-curve-point-test
  (testing "New point is inserted and sorted by X"
    (let [state-with-curve (assoc-in sample-state
                                     [:chains :effect-chains [0 0] :items 0 :params :r-curve-points]
                                     [[0.0 0.0] [1.0 1.0]])
          ;; Add point at [0.5, 0.25]
          event {:event/type :chain/add-curve-point
                 :domain :effect-chains
                 :entity-key [0 0]
                 :effect-path [0]
                 :channel :r
                 :x 0.5
                 :y 0.25
                 :state state-with-curve}
          result (chain/handle event)
          points (get-in result [:state :chains :effect-chains [0 0] :items 0 :params :r-curve-points])]
      ;; Should have 3 points, sorted
      (is (= [[0.0 0.0] [0.5 0.25] [1.0 1.0]] points)))))


;; Cell Operations Tests (still using effects/handle)


(deftest handle-effects-toggle-cell-test
  (testing "Toggle cell enables empty cell"
    (let [event {:event/type :effects/toggle-cell
                 :col 0 :row 1
                 :state sample-state}
          result (effects/handle event)]
      ;; Should create cell with active?: true
      (is (true? (get-in result [:state :chains :effect-chains [0 1] :active?])))))
  
  (testing "Toggle cell disables active cell"
    (let [event {:event/type :effects/toggle-cell
                 :col 0 :row 0  ;; This cell is active in sample-state
                 :state sample-state}
          result (effects/handle event)]
      ;; Should toggle to false
      (is (false? (get-in result [:state :chains :effect-chains [0 0] :active?])))))
  
  (testing "With :fx/event true, sets cell active regardless of current state"
    (let [;; Cell [0 0] is already active, but :fx/event says true
          event {:event/type :effects/toggle-cell
                 :col 0 :row 0
                 :fx/event true
                 :state sample-state}
          result (effects/handle event)]
      ;; Should remain true (not toggle)
      (is (true? (get-in result [:state :chains :effect-chains [0 0] :active?])))))
  
  (testing "With :fx/event false, sets cell inactive regardless of current state"
    (let [;; Cell [0 0] is active, but :fx/event says false
          event {:event/type :effects/toggle-cell
                 :col 0 :row 0
                 :fx/event false
                 :state sample-state}
          result (effects/handle event)]
      ;; Should be false (explicit value)
      (is (false? (get-in result [:state :chains :effect-chains [0 0] :active?]))))))

(deftest handle-effects-clear-cell-test
  (testing "Clear cell removes effects"
    (let [event {:event/type :effects/clear-cell
                 :col 0 :row 0
                 :state sample-state}
          result (effects/handle event)]
      ;; Should have empty items
      (is (empty? (get-in result [:state :chains :effect-chains [0 0] :items]))))))
