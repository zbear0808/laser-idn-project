(ns laser-show.events.list-handlers-test
  "Unit tests for list event handlers.
   
   Tests the hierarchical list UI state management including
   selection, drag-drop, and rename operations."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.list :as list-handlers]))


;; Selection Tests


(deftest select-item-single-test
  (testing "Single click replaces selection"
    (let [state {:list-ui {:test-list {:selected-ids #{:a :b}
                                       :last-selected-id :b}}}
          event {:event/type :list/select-item
                 :component-id :test-list
                 :item-id :c
                 :mode :single
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:c} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :c (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest select-item-ctrl-toggle-on-test
  (testing "Ctrl+click adds to selection"
    (let [state {:list-ui {:test-list {:selected-ids #{:a}
                                       :last-selected-id :a}}}
          event {:event/type :list/select-item
                 :component-id :test-list
                 :item-id :b
                 :mode :ctrl
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a :b} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :b (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest select-item-ctrl-toggle-off-test
  (testing "Ctrl+click removes from selection"
    (let [state {:list-ui {:test-list {:selected-ids #{:a :b}
                                       :last-selected-id :b}}}
          event {:event/type :list/select-item
                 :component-id :test-list
                 :item-id :b
                 :mode :ctrl
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :b (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest select-item-shift-range-test
  (testing "Shift+click uses pre-computed range"
    (let [state {:list-ui {:test-list {:selected-ids #{:a}
                                       :last-selected-id :a}}}
          event {:event/type :list/select-item
                 :component-id :test-list
                 :item-id :d
                 :mode :shift
                 :selected-ids-override #{:a :b :c :d}
                 :last-id-override :a
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a :b :c :d} (get-in result [:state :list-ui :test-list :selected-ids])))
      ;; Shift preserves last-selected-id from override
      (is (= :a (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest select-item-cue-chain-clears-item-effects-test
  (testing "Selecting in cue-chain clears item-effects selection"
    (let [state {:list-ui {[:cue-chain 0 0] {:selected-ids #{:preset-1}
                                              :last-selected-id :preset-1}
                           [:item-effects 0 0] {:selected-ids #{:effect-1 :effect-2}
                                                :last-selected-id :effect-2}}}
          event {:event/type :list/select-item
                 :component-id [:cue-chain 0 0]
                 :item-id :preset-2
                 :mode :single
                 :state state}
          result (list-handlers/handle event)]
      ;; Cue chain selection updated
      (is (= #{:preset-2} (get-in result [:state :list-ui [:cue-chain 0 0] :selected-ids])))
      ;; Item effects selection cleared
      (is (= #{} (get-in result [:state :list-ui [:item-effects 0 0] :selected-ids])))
      (is (nil? (get-in result [:state :list-ui [:item-effects 0 0] :last-selected-id]))))))


(deftest select-all-test
  (testing "Select all sets all IDs"
    (let [state {:list-ui {:test-list {:selected-ids #{}}}}
          event {:event/type :list/select-all
                 :component-id :test-list
                 :all-ids [:a :b :c :d]
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a :b :c :d} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :a (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest clear-selection-test
  (testing "Clear selection empties selection and cancels rename"
    (let [state {:list-ui {:test-list {:selected-ids #{:a :b}
                                       :last-selected-id :b
                                       :renaming-id :a}}}
          event {:event/type :list/clear-selection
                 :component-id :test-list
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (nil? (get-in result [:state :list-ui :test-list :last-selected-id])))
      (is (nil? (get-in result [:state :list-ui :test-list :renaming-id]))))))


;; Drag-Drop Tests


(deftest start-drag-selected-item-test
  (testing "Dragging selected item drags all selected"
    (let [state {:list-ui {:test-list {:selected-ids #{:a :b}
                                       :last-selected-id :b}}}
          event {:event/type :list/start-drag
                 :component-id :test-list
                 :item-id :a
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a :b} (get-in result [:state :list-ui :test-list :dragging-ids])))
      (is (= #{:a :b} (get-in result [:state :list-ui :test-list :selected-ids]))))))


(deftest start-drag-unselected-item-test
  (testing "Dragging unselected item selects and drags only that item"
    (let [state {:list-ui {:test-list {:selected-ids #{:a :b}
                                       :last-selected-id :b}}}
          event {:event/type :list/start-drag
                 :component-id :test-list
                 :item-id :c
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:c} (get-in result [:state :list-ui :test-list :dragging-ids])))
      (is (= #{:c} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :c (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest update-drop-target-test
  (testing "Update drop target sets target and position"
    (let [state {:list-ui {:test-list {}}}
          event {:event/type :list/update-drop-target
                 :component-id :test-list
                 :target-id :b
                 :drop-position :before
                 :state state}
          result (list-handlers/handle event)]
      (is (= :b (get-in result [:state :list-ui :test-list :drop-target-id])))
      (is (= :before (get-in result [:state :list-ui :test-list :drop-position]))))))


(deftest clear-drag-test
  (testing "Clear drag resets drag state"
    (let [state {:list-ui {:test-list {:dragging-ids #{:a}
                                       :drop-target-id :b
                                       :drop-position :before}}}
          event {:event/type :list/clear-drag
                 :component-id :test-list
                 :state state}
          result (list-handlers/handle event)]
      (is (nil? (get-in result [:state :list-ui :test-list :dragging-ids])))
      (is (nil? (get-in result [:state :list-ui :test-list :drop-target-id])))
      (is (nil? (get-in result [:state :list-ui :test-list :drop-position]))))))


(deftest perform-drop-no-items-test
  (testing "Perform drop with no items just clears drag state"
    (let [state {:list-ui {:test-list {:dragging-ids #{:a}
                                       :drop-target-id :b
                                       :drop-position :before}}
                 :chains {:effect-chains {[0 0] {:items []}}}}
          event {:event/type :list/perform-drop
                 :component-id :test-list
                 :dragging-ids #{:a}
                 :target-id :b
                 :drop-position :before
                 :on-change-event :chain/set-items
                 :on-change-params {:domain :effect-chains :entity-key [0 0]}
                 :items-key :items
                 :state state}
          result (list-handlers/handle event)]
      ;; Drag state cleared
      (is (nil? (get-in result [:state :list-ui :test-list :dragging-ids])))
      ;; No dispatch since no items
      (is (nil? (:dispatch result))))))


(deftest perform-drop-valid-items-test
  (testing "Perform drop with valid items dispatches change event"
    (let [id-a (random-uuid)
          id-b (random-uuid)
          id-c (random-uuid)
          item-a {:id id-a :effect-id :test :enabled? true}
          item-b {:id id-b :effect-id :test :enabled? true}
          item-c {:id id-c :effect-id :test :enabled? true}
          state {:list-ui {:test-list {:dragging-ids #{id-a}
                                       :drop-target-id id-c
                                       :drop-position :before}}
                 :chains {:effect-chains {[0 0] {:items [item-a item-b item-c]}}}}
          event {:event/type :list/perform-drop
                 :component-id :test-list
                 :dragging-ids #{id-a}
                 :target-id id-c
                 :drop-position :before
                 :on-change-event :chain/set-items
                 :on-change-params {:domain :effect-chains :entity-key [0 0]}
                 :items-key :items
                 :state state}
          result (list-handlers/handle event)]
      ;; Drag state cleared
      (is (nil? (get-in result [:state :list-ui :test-list :dragging-ids])))
      ;; Dispatch event generated
      (is (= :chain/set-items (get-in result [:dispatch :event/type])))
      (is (= :effect-chains (get-in result [:dispatch :domain])))
      (is (= [0 0] (get-in result [:dispatch :entity-key])))
      ;; Items reordered - item-a moved before item-c
      (let [new-items (get-in result [:dispatch :items])]
        (is (= 3 (count new-items)))
        ;; Order should be: b, a, c (a moved from position 0 to before c)
        (is (= [id-b id-a id-c] (mapv :id new-items)))))))


;; Rename Tests


(deftest start-rename-test
  (testing "Start rename sets renaming-id"
    (let [state {:list-ui {:test-list {}}}
          event {:event/type :list/start-rename
                 :component-id :test-list
                 :item-id :a
                 :state state}
          result (list-handlers/handle event)]
      (is (= :a (get-in result [:state :list-ui :test-list :renaming-id]))))))


(deftest cancel-rename-test
  (testing "Cancel rename clears renaming-id"
    (let [state {:list-ui {:test-list {:renaming-id :a}}}
          event {:event/type :list/cancel-rename
                 :component-id :test-list
                 :state state}
          result (list-handlers/handle event)]
      (is (nil? (get-in result [:state :list-ui :test-list :renaming-id]))))))


;; Edge Cases


(deftest empty-component-state-test
  (testing "Handlers work with missing component state"
    (let [state {:list-ui {}}
          event {:event/type :list/select-item
                 :component-id :new-list
                 :item-id :a
                 :mode :single
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a} (get-in result [:state :list-ui :new-list :selected-ids]))))))


(deftest perform-drop-with-explicit-items-path-test
  (testing "Perform drop uses explicit items-path when provided"
    (let [id-a (random-uuid)
          id-b (random-uuid)
          item-a {:id id-a :effect-id :test :enabled? true}
          item-b {:id id-b :effect-id :test :enabled? true}
          state {:list-ui {:test-list {:dragging-ids #{id-a}}}
                 :custom {:path {:items [item-a item-b]}}}
          event {:event/type :list/perform-drop
                 :component-id :test-list
                 :dragging-ids #{id-a}
                 :target-id id-b
                 :drop-position :after
                 :on-change-event :custom/set-items
                 :on-change-params {}
                 :items-key :items
                 :items-path [:custom :path :items]
                 :state state}
          result (list-handlers/handle event)]
      ;; Should dispatch with items from custom path
      (is (= :custom/set-items (get-in result [:dispatch :event/type])))
      (let [new-items (get-in result [:dispatch :items])]
        ;; item-a moved after item-b, so order is b, a
        (is (= [id-b id-a] (mapv :id new-items)))))))
