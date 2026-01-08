(ns laser-show.events.cue-chain-handlers-test
  "Tests for cue chain event handlers focusing on complex curve and spatial logic."
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.handlers.cue-chain :as cue-chain]
   [laser-show.state.domains :refer [build-initial-state]]))


;; Test Fixtures


(def base-state
  "Base state using actual app initial state structure."
  (build-initial-state))

(def sample-state-with-item
  "State with a single cue chain item."
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]]
                {:items [{:preset-id :circle
                          :params {:size 100}
                          :effects []}]})
      (assoc-in [:cue-chain-editor :cell] [0 0])
      (assoc-in [:cue-chain-editor :selected-paths] #{})
      (assoc-in [:cue-chain-editor :clipboard] nil)))

(def sample-state-with-effect
  "State with a cue chain item that has an RGB curves effect."
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]]
                {:items [{:preset-id :circle
                          :params {:size 100}
                          :effects [{:id (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                                     :effect-id :rgb-curves
                                     :enabled? true
                                     :params {:r-curve-points [[0 0] [255 255]]
                                              :g-curve-points [[0 0] [255 255]]
                                              :b-curve-points [[0 0] [255 255]]}}]}]})
      (assoc-in [:cue-chain-editor :cell] [0 0])))

(def sample-state-with-spatial-effect
  "State with a cue chain item that has a spatial effect."
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]]
                {:items [{:preset-id :circle
                          :effects [{:id (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174001")
                                     :effect-id :translate
                                     :enabled? true
                                     :params {:x 0.0 :y 0.0}}]}]})
      (assoc-in [:cue-chain-editor :cell] [0 0])))


;; Curve Point Addition Tests


(deftest handle-add-curve-point-basic
  (testing "Adding curve point to middle of curve"
    (let [event {:event/type :cue-chain/add-item-effect-curve-point
                 :col 0
                 :row 0
                 :item-path [0]
                 :effect-path [0]
                 :channel :r
                 :x 128
                 :y 64
                 :state sample-state-with-effect}
          result (cue-chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      (is (= 3 (count new-points)))
      (is (= [0 0] (first new-points)))
      (is (= [128 64] (second new-points)))
      (is (= [255 255] (last new-points)))
      ;; Should maintain sort order
      (is (= new-points (sort-by first new-points))))))

(deftest handle-add-curve-point-sorting
  (testing "Added curve point is sorted by x value"
    (let [event {:event/type :cue-chain/add-item-effect-curve-point
                 :col 0
                 :row 0
                 :item-path [0]
                 :effect-path [0]
                 :channel :g
                 :x 200
                 :y 100
                 :state sample-state-with-effect}
          result (cue-chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :g-curve-points])]
      (is (= [[0 0] [200 100] [255 255]] new-points)))))

(deftest handle-add-curve-point-multiple
  (testing "Adding multiple curve points maintains sort order"
    (let [state1 (cue-chain/handle {:event/type :cue-chain/add-item-effect-curve-point
                                    :col 0 :row 0 :item-path [0] :effect-path [0]
                                    :channel :b :x 100 :y 50
                                    :state sample-state-with-effect})
          state2 (cue-chain/handle {:event/type :cue-chain/add-item-effect-curve-point
                                    :col 0 :row 0 :item-path [0] :effect-path [0]
                                    :channel :b :x 50 :y 100
                                    :state (:state state1)})
          final-points (get-in state2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :b-curve-points])]
      (is (= [[0 0] [50 100] [100 50] [255 255]] final-points)))))


;; Curve Point Update Tests


(deftest handle-update-curve-point-middle
  (testing "Updating middle curve point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0 0] [128 128] [255 255]])
          event {:event/type :cue-chain/update-item-effect-curve-point
                 :col 0 :row 0 :item-path [0] :effect-path [0]
                 :channel :r :point-idx 1 :x 140 :y 100
                 :state initial-state}
          result (cue-chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      (is (= [[0 0] [140 100] [255 255]] new-points)))))

(deftest handle-update-curve-point-corner-left
  (testing "Updating left corner point only changes Y"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0 0] [128 128] [255 255]])
          event {:event/type :cue-chain/update-item-effect-curve-point
                 :col 0 :row 0 :item-path [0] :effect-path [0]
                 :channel :r :point-idx 0 :x 50 :y 100
                 :state initial-state}
          result (cue-chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; X should remain 0, only Y changes
      (is (= [0 100] (first new-points))))))

(deftest handle-update-curve-point-corner-right
  (testing "Updating right corner point only changes Y"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0 0] [128 128] [255 255]])
          event {:event/type :cue-chain/update-item-effect-curve-point
                 :col 0 :row 0 :item-path [0] :effect-path [0]
                 :channel :r :point-idx 2 :x 200 :y 150
                 :state initial-state}
          result (cue-chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; X should remain 255, only Y changes
      (is (= [255 150] (last new-points))))))

(deftest handle-update-curve-point-resorting
  (testing "Updating point X maintains sorted order"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0 0] [64 64] [128 128] [192 192] [255 255]])
          event {:event/type :cue-chain/update-item-effect-curve-point
                 :col 0 :row 0 :item-path [0] :effect-path [0]
                 :channel :r :point-idx 1 :x 150 :y 64
                 :state initial-state}
          result (cue-chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; Point should move to maintain sort order
      (is (= [[0 0] [128 128] [150 64] [192 192] [255 255]] new-points)))))


;; Curve Point Removal Tests


(deftest handle-remove-curve-point-middle
  (testing "Removing middle curve point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0 0] [64 64] [128 128] [192 192] [255 255]])
          event {:event/type :cue-chain/remove-item-effect-curve-point
                 :col 0 :row 0 :item-path [0] :effect-path [0]
                 :channel :r :point-idx 2
                 :state initial-state}
          result (cue-chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      (is (= [[0 0] [64 64] [192 192] [255 255]] new-points)))))

(deftest handle-remove-curve-point-corner-left
  (testing "Cannot remove left corner point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0 0] [128 128] [255 255]])
          event {:event/type :cue-chain/remove-item-effect-curve-point
                 :col 0 :row 0 :item-path [0] :effect-path [0]
                 :channel :r :point-idx 0
                 :state initial-state}
          result (cue-chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; Points should remain unchanged
      (is (= [[0 0] [128 128] [255 255]] new-points)))))

(deftest handle-remove-curve-point-corner-right
  (testing "Cannot remove right corner point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0 0] [128 128] [255 255]])
          event {:event/type :cue-chain/remove-item-effect-curve-point
                 :col 0 :row 0 :item-path [0] :effect-path [0]
                 :channel :r :point-idx 2
                 :state initial-state}
          result (cue-chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; Points should remain unchanged
      (is (= [[0 0] [128 128] [255 255]] new-points)))))


;; Spatial Parameter Update Tests


(deftest handle-update-spatial-params-center
  (testing "Updating center point spatial parameters"
    (let [param-map {:center {:x :x :y :y}}
          event {:event/type :cue-chain/update-item-effect-spatial-params
                 :col 0 :row 0 :item-path [0] :effect-path [0]
                 :point-id :center :x 0.5 :y -0.3
                 :param-map param-map
                 :state sample-state-with-spatial-effect}
          result (cue-chain/handle event)]
      (is (= 0.5 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x])))
      (is (= -0.3 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y]))))))

(deftest handle-update-spatial-params-multiple-points
  (testing "Updating with multiple point mappings"
    (let [initial-state (assoc-in sample-state-with-spatial-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params]
                                  {:x1 0.0 :y1 0.0 :x2 1.0 :y2 1.0})
          param-map {:p1 {:x :x1 :y :y1}
                     :p2 {:x :x2 :y :y2}}
          event1 {:event/type :cue-chain/update-item-effect-spatial-params
                  :col 0 :row 0 :item-path [0] :effect-path [0]
                  :point-id :p1 :x -0.5 :y 0.5
                  :param-map param-map
                  :state initial-state}
          result1 (cue-chain/handle event1)
          event2 {:event/type :cue-chain/update-item-effect-spatial-params
                  :col 0 :row 0 :item-path [0] :effect-path [0]
                  :point-id :p2 :x 0.8 :y -0.8
                  :param-map param-map
                  :state (:state result1)}
          result2 (cue-chain/handle event2)]
      (is (= -0.5 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x1])))
      (is (= 0.5 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y1])))
      (is (= 0.8 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x2])))
      (is (= -0.8 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y2]))))))

(deftest handle-update-spatial-params-unknown-point
  (testing "Updating unknown point ID does nothing"
    (let [param-map {:center {:x :x :y :y}}
          event {:event/type :cue-chain/update-item-effect-spatial-params
                 :col 0 :row 0 :item-path [0] :effect-path [0]
                 :point-id :nonexistent :x 0.5 :y -0.3
                 :param-map param-map
                 :state sample-state-with-spatial-effect}
          result (cue-chain/handle event)]
      ;; State should remain unchanged
      (is (= 0.0 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x])))
      (is (= 0.0 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y]))))))


;; Add Preset Tests


(deftest handle-add-preset-to-empty-cell
  (testing "Adding preset to empty cell creates cell"
    (let [event {:event/type :cue-chain/add-preset
                 :col 0
                 :row 0
                 :preset-id :circle
                 :state {:chains {:cue-chains {}}
                        :cue-chain-editor {}
                        :project {:dirty? false}}}
          result (cue-chain/handle event)
          items (get-in result [:state :chains :cue-chains [0 0] :items])]
      (is (= 1 (count items)))
      (is (= :circle (:preset-id (first items))))
      (is (true? (get-in result [:state :project :dirty?])))
      ;; Should auto-select the new preset
      (is (= #{[0]} (get-in result [:state :cue-chain-editor :selected-paths]))))))

(deftest handle-add-preset-to-existing-cell
  (testing "Adding preset to cell with content appends to list"
    (let [event {:event/type :cue-chain/add-preset
                 :col 0
                 :row 0
                 :preset-id :wave
                 :state sample-state-with-item}
          result (cue-chain/handle event)
          items (get-in result [:state :chains :cue-chains [0 0] :items])]
      (is (= 2 (count items)))
      (is (= :circle (:preset-id (first items))))
      (is (= :wave (:preset-id (second items))))
      ;; Should auto-select the newly added preset at index 1
      (is (= #{[1]} (get-in result [:state :cue-chain-editor :selected-paths]))))))


;; Remove Effect from Item Tests


(deftest handle-remove-effect-deselects-if-selected
  (testing "Removing selected effect clears selection"
    (let [effect-id (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
          state (assoc-in sample-state-with-effect [:cue-chain-editor :selected-effect-id] effect-id)
          event {:event/type :cue-chain/remove-effect-from-item
                 :col 0 :row 0 :item-path [0]
                 :effect-id effect-id
                 :state state}
          result (cue-chain/handle event)]
      (is (empty? (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects])))
      (is (nil? (get-in result [:state :cue-chain-editor :selected-effect-id])))
      (is (true? (get-in result [:state :project :dirty?]))))))

(deftest handle-remove-effect-keeps-selection-if-different
  (testing "Removing non-selected effect keeps selection"
    (let [effect-id-1 (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
          effect-id-2 (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174001")
          state (-> sample-state-with-effect
                    (update-in [:chains :cue-chains [0 0] :items 0 :effects]
                               conj {:id effect-id-2 :effect-id :rotate :enabled? true})
                    (assoc-in [:cue-chain-editor :selected-effect-id] effect-id-2))
          event {:event/type :cue-chain/remove-effect-from-item
                 :col 0 :row 0 :item-path [0]
                 :effect-id effect-id-1
                 :state state}
          result (cue-chain/handle event)]
      (is (= 1 (count (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects]))))
      (is (= effect-id-2 (get-in result [:state :cue-chain-editor :selected-effect-id]))))))
