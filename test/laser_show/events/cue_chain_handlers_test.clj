(ns laser-show.events.cue-chain-handlers-test
  "Tests for cue chain event handlers focusing on complex curve and spatial logic.
   
   Effect-level operations (curve points, spatial params) now use :chain/* events
   from chain.clj. Cue-chain specific operations (add-preset, etc.) use :cue-chain/*."
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.handlers.cue-chain :as cue-chain]
   [laser-show.events.handlers.chain :as chain]
   [laser-show.events.handlers.list :as list]
   [laser-show.state.domains :refer [build-initial-state]]))


;; Test Fixtures


(def base-state
  "Base state using actual app initial state structure."
  (build-initial-state))

(def sample-state-with-item
  "State with a single cue chain item."
  ;; FLATTENED: Dialog fields live alongside :open?, not under :data
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]]
                {:items [{:preset-id :circle
                          :params {:size 100}
                          :effects []}]})
      (assoc-in [:ui :dialogs :cue-chain-editor] {:open? false :col 0 :row 0 :selected-paths #{}})
      (assoc-in [:cue-chain-editor :cell] [0 0])
      (assoc-in [:cue-chain-editor :selected-paths] #{})
      (assoc-in [:cue-chain-editor :clipboard] nil)))

(def sample-state-with-effect
  "State with a cue chain item that has an RGB curves effect."
  ;; FLATTENED: Dialog fields live alongside :open?, not under :data
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]]
                {:items [{:preset-id :circle
                          :params {:size 100}
                          :effects [{:id (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                                     :effect-id :rgb-curves
                                     :enabled? true
                                     :params {:r-curve-points [[0.0 0.0] [1.0 1.0]]
                                              :g-curve-points [[0.0 0.0] [1.0 1.0]]
                                              :b-curve-points [[0.0 0.0] [1.0 1.0]]}}]}]})
      (assoc-in [:ui :dialogs :cue-chain-editor] {:open? false :col 0 :row 0})
      (assoc-in [:cue-chain-editor :cell] [0 0])))

(def sample-state-with-spatial-effect
  "State with a cue chain item that has a spatial effect."
  ;; FLATTENED: Dialog fields live alongside :open?, not under :data
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]]
                {:items [{:preset-id :circle
                          :effects [{:id (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174001")
                                     :effect-id :translate
                                     :enabled? true
                                     :params {:x 0.0 :y 0.0}}]}]})
      (assoc-in [:ui :dialogs :cue-chain-editor] {:open? false :col 0 :row 0})
      (assoc-in [:cue-chain-editor :cell] [0 0])))


;; Curve Point Addition Tests (using chain/handle with :chain/* events)


(deftest handle-add-curve-point-basic
  (testing "Adding curve point to middle of curve"
    (let [;; For cue chain item effects, effect-path is relative to items
          ;; Full path: items -> [0] -> :effects -> [0]
          ;; So effect-path = [0 :effects 0]
          event {:event/type :chain/add-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r
                 :x 0.5
                 :y 0.25
                 :state sample-state-with-effect}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      (is (= 3 (count new-points)))
      (is (= [0.0 0.0] (first new-points)))
      (is (= [0.5 0.25] (second new-points)))
      (is (= [1.0 1.0] (last new-points)))
      ;; Should maintain sort order
      (is (= new-points (sort-by first new-points))))))

(deftest handle-add-curve-point-sorting
  (testing "Added curve point is sorted by x value"
    (let [event {:event/type :chain/add-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :g
                 :x 0.8
                 :y 0.4
                 :state sample-state-with-effect}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :g-curve-points])]
      (is (= [[0.0 0.0] [0.8 0.4] [1.0 1.0]] new-points)))))

(deftest handle-add-curve-point-multiple
  (testing "Adding multiple curve points maintains sort order"
    (let [state1 (chain/handle {:event/type :chain/add-curve-point
                                :domain :cue-chains
                                :entity-key [0 0]
                                :effect-path [0 :effects 0]
                                :channel :b :x 0.4 :y 0.2
                                :state sample-state-with-effect})
          state2 (chain/handle {:event/type :chain/add-curve-point
                                :domain :cue-chains
                                :entity-key [0 0]
                                :effect-path [0 :effects 0]
                                :channel :b :x 0.2 :y 0.4
                                :state (:state state1)})
          final-points (get-in state2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :b-curve-points])]
      (is (= [[0.0 0.0] [0.2 0.4] [0.4 0.2] [1.0 1.0]] final-points)))))


;; Curve Point Update Tests


(deftest handle-update-curve-point-middle
  (testing "Updating middle curve point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          event {:event/type :chain/update-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 1 :x 0.55 :y 0.4
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      (is (= [[0.0 0.0] [0.55 0.4] [1.0 1.0]] new-points)))))

(deftest handle-update-curve-point-corner-left
  (testing "Updating left corner point only changes Y"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          event {:event/type :chain/update-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 0 :x 0.2 :y 0.4
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; X should remain 0.0, only Y changes
      (is (= [0.0 0.4] (first new-points))))))

(deftest handle-update-curve-point-corner-right
  (testing "Updating right corner point only changes Y"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          event {:event/type :chain/update-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 2 :x 0.8 :y 0.6
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; X should remain 1.0, only Y changes
      (is (= [1.0 0.6] (last new-points))))))

(deftest handle-update-curve-point-resorting
  (testing "Updating point X maintains sorted order"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.25 0.25] [0.5 0.5] [0.75 0.75] [1.0 1.0]])
          event {:event/type :chain/update-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 1 :x 0.6 :y 0.25
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; Point should move to maintain sort order
      (is (= [[0.0 0.0] [0.5 0.5] [0.6 0.25] [0.75 0.75] [1.0 1.0]] new-points)))))


;; Curve Point Removal Tests


(deftest handle-remove-curve-point-middle
  (testing "Removing middle curve point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.25 0.25] [0.5 0.5] [0.75 0.75] [1.0 1.0]])
          event {:event/type :chain/remove-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 2
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      (is (= [[0.0 0.0] [0.25 0.25] [0.75 0.75] [1.0 1.0]] new-points)))))

(deftest handle-remove-curve-point-corner-left
  (testing "Cannot remove left corner point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          event {:event/type :chain/remove-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 0
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; Points should remain unchanged
      (is (= [[0.0 0.0] [0.5 0.5] [1.0 1.0]] new-points)))))

(deftest handle-remove-curve-point-corner-right
  (testing "Cannot remove right corner point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          event {:event/type :chain/remove-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 2
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; Points should remain unchanged
      (is (= [[0.0 0.0] [0.5 0.5] [1.0 1.0]] new-points)))))


;; Spatial Parameter Update Tests


(deftest handle-update-spatial-params-center
  (testing "Updating center point spatial parameters"
    (let [param-map {:center {:x :x :y :y}}
          event {:event/type :chain/update-spatial-params
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :point-id :center :x 0.5 :y -0.3
                 :param-map param-map
                 :state sample-state-with-spatial-effect}
          result (chain/handle event)]
      (is (= 0.5 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x])))
      (is (= -0.3 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y]))))))

(deftest handle-update-spatial-params-multiple-points
  (testing "Updating with multiple point mappings"
    (let [initial-state (assoc-in sample-state-with-spatial-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params]
                                  {:x1 0.0 :y1 0.0 :x2 1.0 :y2 1.0})
          param-map {:p1 {:x :x1 :y :y1}
                     :p2 {:x :x2 :y :y2}}
          event1 {:event/type :chain/update-spatial-params
                  :domain :cue-chains
                  :entity-key [0 0]
                  :effect-path [0 :effects 0]
                  :point-id :p1 :x -0.5 :y 0.5
                  :param-map param-map
                  :state initial-state}
          result1 (chain/handle event1)
          event2 {:event/type :chain/update-spatial-params
                  :domain :cue-chains
                  :entity-key [0 0]
                  :effect-path [0 :effects 0]
                  :point-id :p2 :x 0.8 :y -0.8
                  :param-map param-map
                  :state (:state result1)}
          result2 (chain/handle event2)]
      (is (= -0.5 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x1])))
      (is (= 0.5 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y1])))
      (is (= 0.8 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x2])))
      (is (= -0.8 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y2]))))))

(deftest handle-update-spatial-params-unknown-point
  (testing "Updating unknown point ID does nothing"
    (let [param-map {:center {:x :x :y :y}}
          event {:event/type :chain/update-spatial-params
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :point-id :nonexistent :x 0.5 :y -0.3
                 :param-map param-map
                 :state sample-state-with-spatial-effect}
          result (chain/handle event)]
      ;; State should remain unchanged
      (is (= 0.0 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x])))
      (is (= 0.0 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y]))))))


;; Add Preset Tests (still using cue-chain/handle)


(deftest handle-add-preset-to-empty-cell
  (testing "Adding preset to empty cell creates cell"
    ;; FLATTENED: Dialog fields live alongside :open?, not under :data
    (let [event {:event/type :cue-chain/add-preset
                 :col 0
                 :row 0
                 :preset-id :circle
                 :state {:chains {:cue-chains {}}
                         :ui {:dialogs {:cue-chain-editor {:open? false}}}
                         :project {:dirty? false}}}
          result (cue-chain/handle event)
          items (get-in result [:state :chains :cue-chains [0 0] :items])
          ;; Process the dispatch event to apply selection
          dispatch-result (when-let [dispatch-event (:dispatch result)]
                            (list/handle (assoc dispatch-event :state (:state result))))
          final-state (if dispatch-result (:state dispatch-result) (:state result))
          new-item-id (:id (first items))
          selected-ids (get-in final-state [:list-ui [:cue-chain 0 0] :selected-ids])]
      (is (= 1 (count items)))
      (is (= :circle (:preset-id (first items))))
      (is (true? (get-in result [:state :project :dirty?])))
      ;; Should auto-select the new preset (ID-based selection in list-ui)
      (is (= #{new-item-id} selected-ids)))))

(deftest handle-add-preset-to-existing-cell
  (testing "Adding preset to cell with content appends to list"
    (let [event {:event/type :cue-chain/add-preset
                 :col 0
                 :row 0
                 :preset-id :wave
                 :state sample-state-with-item}
          result (cue-chain/handle event)
          items (get-in result [:state :chains :cue-chains [0 0] :items])
          ;; Process the dispatch event to apply selection
          dispatch-result (when-let [dispatch-event (:dispatch result)]
                            (list/handle (assoc dispatch-event :state (:state result))))
          final-state (if dispatch-result (:state dispatch-result) (:state result))
          new-item-id (:id (second items))
          selected-ids (get-in final-state [:list-ui [:cue-chain 0 0] :selected-ids])]
      (is (= 2 (count items)))
      (is (= :circle (:preset-id (first items))))
      (is (= :wave (:preset-id (second items))))
      ;; Should auto-select the newly added preset (ID-based selection in list-ui)
      (is (= #{new-item-id} selected-ids)))))


;; Remove Item Tests (using cue-chain/handle with :cue-chain/set-item-effects)
;; Note: Removing effects from cue chain items is done via the list component
;; which calls :cue-chain/set-item-effects with the updated effects array.


(deftest handle-set-item-effects
  (testing "Setting item effects replaces the effects array"
    (let [new-effects [{:id (java.util.UUID/randomUUID) :effect-id :rotate :enabled? true :params {}}]
          event {:event/type :cue-chain/set-item-effects
                 :col 0 :row 0
                 :item-path [0]
                 :items new-effects
                 :state sample-state-with-effect}
          result (cue-chain/handle event)]
      (is (= 1 (count (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects]))))
      (is (= :rotate (:effect-id (first (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects])))))
      (is (true? (get-in result [:state :project :dirty?]))))))

(deftest handle-set-item-effects-empty
  (testing "Setting empty effects array clears effects"
    (let [event {:event/type :cue-chain/set-item-effects
                 :col 0 :row 0
                 :item-path [0]
                 :items []
                 :state sample-state-with-effect}
          result (cue-chain/handle event)]
      (is (empty? (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects])))
      (is (true? (get-in result [:state :project :dirty?]))))))
