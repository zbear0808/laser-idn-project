(ns laser-show.animation.chains-test
  "Tests for chain management functions including deep copy and safe operations."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.animation.chains :as chains]))


;; Test Data Fixtures


(def sample-item-1
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :effect-id :scale
   :enabled? true
   :params {:x 1.0 :y 1.0}})

(def sample-item-2
  {:id #uuid "00000000-0000-0000-0000-000000000002"
   :effect-id :translate
   :enabled? true
   :params {:x 0.5 :y -0.5}})

(def sample-item-3
  {:id #uuid "00000000-0000-0000-0000-000000000003"
   :effect-id :rotate
   :enabled? false
   :params {:angle 45}})

(def sample-item-4
  {:id #uuid "00000000-0000-0000-0000-000000000004"
   :effect-id :scale
   :enabled? true
   :params {:x 2.0 :y 2.0}})

(def sample-item-5
  {:id #uuid "00000000-0000-0000-0000-000000000005"
   :effect-id :translate
   :enabled? true
   :params {:x 0.0 :y 0.0}})

(def sample-group
  {:id #uuid "00000000-0000-0000-0000-000000000010"
   :type :group
   :name "Test Group"
   :enabled? true
   :collapsed? false
   :items [sample-item-4 sample-item-5]})

(def nested-group
  {:id #uuid "00000000-0000-0000-0000-000000000020"
   :type :group
   :name "Outer Group"
   :enabled? true
   :items [{:id #uuid "00000000-0000-0000-0000-000000000011"
            :type :group
            :name "Inner Group"
            :enabled? true
            :items [sample-item-3]}
           sample-item-1]})

(def sample-chain
  [sample-item-1 sample-group sample-item-3])


;; Deep Copy Tests


(deftest deep-copy-item-test
  (testing "generates new UUID for leaf item"
    (let [original sample-item-1
          copied (chains/deep-copy-item original)]
      (is (uuid? (:id copied)))
      (is (not= (:id original) (:id copied)))
      (is (= (:effect-id original) (:effect-id copied)))
      (is (= (:params original) (:params copied)))))
  
  (testing "generates new UUIDs for group and all children"
    (let [original sample-group
          copied (chains/deep-copy-item original)]
      ;; Group itself has new ID
      (is (not= (:id original) (:id copied)))
      ;; All children have new IDs
      (is (not= (get-in original [:items 0 :id])
                (get-in copied [:items 0 :id])))
      (is (not= (get-in original [:items 1 :id])
                (get-in copied [:items 1 :id])))
      ;; Content preserved
      (is (= (:name original) (:name copied)))
      (is (= (count (:items original)) (count (:items copied))))))
  
  (testing "generates new UUIDs for deeply nested groups"
    (let [original nested-group
          copied (chains/deep-copy-item original)]
      ;; Outer group has new ID
      (is (not= (:id original) (:id copied)))
      ;; Inner group has new ID
      (is (not= (get-in original [:items 0 :id])
                (get-in copied [:items 0 :id])))
      ;; Item inside inner group has new ID
      (is (not= (get-in original [:items 0 :items 0 :id])
                (get-in copied [:items 0 :items 0 :id]))))))

(deftest deep-copy-items-test
  (testing "copies multiple items with new UUIDs"
    (let [originals [sample-item-1 sample-item-2 sample-item-3]
          copied (chains/deep-copy-items originals)]
      (is (= 3 (count copied)))
      ;; All have new IDs
      (doseq [[orig copy] (map vector originals copied)]
        (is (not= (:id orig) (:id copy)))
        (is (= (:effect-id orig) (:effect-id copy))))))
  
  (testing "handles empty vector"
    (is (= [] (chains/deep-copy-items [])))))


;; Safe Delete Tests


(deftest delete-paths-safely-test
  (testing "deletes single item"
    (let [chain sample-chain
          result (chains/delete-paths-safely chain [[0]])]
      (is (= 2 (count result)))
      (is (= (:id sample-group) (:id (first result))))))
  
  (testing "deletes multiple items at same level"
    (let [chain sample-chain
          result (chains/delete-paths-safely chain [[0] [2]])]
      (is (= 1 (count result)))
      (is (= (:id sample-group) (:id (first result))))))
  
  (testing "deletes nested item"
    (let [chain sample-chain
          result (chains/delete-paths-safely chain [[1 :items 0]])]
      (is (= 3 (count result)))
      ;; Group should have one fewer item
      (is (= 1 (count (get-in result [1 :items]))))))
  
  (testing "handles empty paths list"
    (let [chain sample-chain
          result (chains/delete-paths-safely chain [])]
      (is (= chain result)))))


;; Safe Move Tests


(deftest move-items-to-target-test
  (testing "moves single item to different position"
    (let [chain [sample-item-1 sample-item-2 sample-item-3]
          ;; Move item at [0] to before item at [2]
          result (chains/move-items-to-target 
                   chain 
                   [[0]] 
                   (:id sample-item-3)
                   :before)]
      (is (= 3 (count result)))
      ;; Item-1 should now be at position 1 (before item-3)
      (is (= (:id sample-item-2) (:id (nth result 0))))
      (is (= (:id sample-item-1) (:id (nth result 1))))
      (is (= (:id sample-item-3) (:id (nth result 2))))))
  
  (testing "moves item into group"
    (let [chain [sample-item-3 sample-group]
          ;; Move item-3 into the group
          result (chains/move-items-to-target
                   chain
                   [[0]]
                   (:id sample-group)
                   :into)]
      (is (= 1 (count result)))
      ;; Group should now have 3 items
      (is (= 3 (count (get-in result [0 :items]))))))
  
  (testing "moves items before target - reordering case"
    (let [chain [sample-item-1 sample-item-2 sample-item-3]
          ;; Move item-3 before item-1 (should result in [3, 1, 2])
          result (chains/move-items-to-target
                   chain
                   [[2]]  ;; item-3
                   (:id sample-item-1)  ;; target
                   :before)]
      (is (= 3 (count result)))
      ;; item-3 should now be first
      (is (= (:id sample-item-3) (:id (nth result 0))))
      (is (= (:id sample-item-1) (:id (nth result 1))))
      (is (= (:id sample-item-2) (:id (nth result 2))))))
  
  (testing "moves items that are already before target - no effective change"
    (let [chain [sample-item-1 sample-item-2 sample-item-3]
          ;; Move items 0 and 1 before item-3 - they're already before item-3
          ;; After removal: [item-3], insert item-1, item-2 at position 0
          ;; Result should have item-1, item-2, item-3 (same as before)
          result (chains/move-items-to-target
                   chain
                   [[0] [1]]
                   (:id sample-item-3)
                   :before)]
      (is (= 3 (count result)))
      ;; Order preserved since items were already before the target
      (is (= (:id sample-item-1) (:id (nth result 0))))
      (is (= (:id sample-item-2) (:id (nth result 1))))
      (is (= (:id sample-item-3) (:id (nth result 2)))))))


;; Path Operations Tests


(deftest paths-in-chain-test
  (testing "returns all paths for flat chain"
    (let [chain [sample-item-1 sample-item-2]
          paths (chains/paths-in-chain chain)]
      (is (= [[0] [1]] (vec paths)))))
  
  (testing "returns paths including nested items"
    (let [chain [sample-item-1 sample-group]
          paths (chains/paths-in-chain chain)]
      ;; Should have: [0], [1], [1 :items 0], [1 :items 1]
      (is (= 4 (count paths)))
      (is (some #(= [1 :items 0] %) paths))
      (is (some #(= [1 :items 1] %) paths)))))

(deftest find-path-by-id-test
  (testing "finds top-level item"
    (let [chain sample-chain
          path (chains/find-path-by-id chain (:id sample-item-1))]
      (is (= [0] path))))
  
  (testing "finds nested item"
    (let [chain sample-chain
          ;; Item inside the group at index 1
          nested-id (get-in chain [1 :items 0 :id])
          path (chains/find-path-by-id chain nested-id)]
      (is (= [1 :items 0] path))))
  
  (testing "returns nil for non-existent ID"
    (let [chain sample-chain
          path (chains/find-path-by-id chain (random-uuid))]
      (is (nil? path)))))

(deftest select-range-test
  (testing "selects range between two paths"
    (let [chain [sample-item-1 sample-item-2 sample-item-3]
          range-paths (chains/select-range chain [0] [2])]
      (is (= [[0] [1] [2]] range-paths))))
  
  (testing "works regardless of path order"
    (let [chain [sample-item-1 sample-item-2 sample-item-3]
          range-paths (chains/select-range chain [2] [0])]
      (is (= [[0] [1] [2]] range-paths))))
  
  (testing "returns empty for invalid paths"
    (let [chain [sample-item-1]
          range-paths (chains/select-range chain [0] [5])]
      (is (= [] range-paths)))))


;; Item Manipulation Tests


(deftest remove-at-path-test
  (testing "removes top-level item"
    (let [chain sample-chain
          result (chains/remove-at-path chain [0])]
      (is (= 2 (count result)))
      (is (= (:id sample-group) (:id (first result))))))
  
  (testing "removes nested item"
    (let [chain sample-chain
          result (chains/remove-at-path chain [1 :items 0])]
      (is (= 3 (count result)))
      (is (= 1 (count (get-in result [1 :items])))))))

(deftest insert-at-path-test
  (testing "inserts at beginning"
    (let [chain [sample-item-2]
          result (chains/insert-at-path chain [0] sample-item-1)]
      (is (= 2 (count result)))
      (is (= (:id sample-item-1) (:id (first result))))))
  
  (testing "inserts at end"
    (let [chain [sample-item-1]
          result (chains/insert-at-path chain [1] sample-item-2)]
      (is (= 2 (count result)))
      (is (= (:id sample-item-2) (:id (second result))))))
  
  (testing "inserts into nested path"
    (let [chain sample-chain
          result (chains/insert-at-path chain [1 :items 0] sample-item-3)]
      (is (= 3 (count (get-in result [1 :items])))))))


;; Group Operations Tests


(deftest create-group-test
  (testing "creates group with default options"
    (let [group (chains/create-group [sample-item-1])]
      (is (chains/group? group))
      (is (uuid? (:id group)))
      (is (= "New Group" (:name group)))
      (is (true? (:enabled? group)))
      (is (false? (:collapsed? group)))
      (is (= 1 (count (:items group))))))
  
  (testing "creates group with custom options"
    (let [group (chains/create-group [sample-item-1 sample-item-2]
                                     {:name "Custom" :collapsed? true})]
      (is (= "Custom" (:name group)))
      (is (true? (:collapsed? group)))
      (is (= 2 (count (:items group)))))))

(deftest ungroup-test
  (testing "ungroups at top level"
    (let [chain [sample-group sample-item-3]
          result (chains/ungroup chain [0])]
      ;; Group had 2 items, plus the existing item-3 = 3 items
      (is (= 3 (count result)))
      ;; First two items should be what was in the group (sample-item-4 and sample-item-5)
      (is (= (:id sample-item-4) (:id (first result))))
      (is (= (:id sample-item-5) (:id (second result))))))
  
  (testing "returns unchanged if path is not a group"
    (let [chain sample-chain
          result (chains/ungroup chain [0])]
      (is (= chain result)))))


;; Nesting Depth Tests


(deftest nesting-depth-test
  (testing "returns 0 for flat chain"
    (is (= 0 (chains/nesting-depth [sample-item-1 sample-item-2]))))
  
  (testing "returns 1 for single level of groups"
    (is (= 1 (chains/nesting-depth [sample-group]))))
  
  (testing "returns depth for nested groups"
    (is (= 2 (chains/nesting-depth [nested-group]))))
  
  (testing "returns 0 for empty chain"
    (is (= 0 (chains/nesting-depth [])))))

(deftest can-add-group-at-path?-test
  (testing "allows group at top level"
    (is (true? (chains/can-add-group-at-path? [] []))))
  
  (testing "allows group up to max depth"
    (is (true? (chains/can-add-group-at-path? [] [0 :items])))
    (is (true? (chains/can-add-group-at-path? [] [0 :items 0 :items]))))
  
  (testing "disallows group beyond max depth"
    (is (false? (chains/can-add-group-at-path? [] [0 :items 0 :items 0 :items 0 :items])))))


;; Collect Descendant IDs Tests


(deftest collect-descendant-ids-test
  (testing "returns nil for non-group item"
    (is (nil? (chains/collect-descendant-ids sample-item-1))))
  
  (testing "returns empty set for group with no children"
    (let [empty-group {:id #uuid "00000000-0000-0000-0000-000000000099"
                       :type :group
                       :name "Empty Group"
                       :items []}]
      (is (= #{} (chains/collect-descendant-ids empty-group)))))
  
  (testing "returns child IDs for group with leaf children"
    (let [result (chains/collect-descendant-ids sample-group)]
      ;; sample-group has sample-item-4 and sample-item-5
      (is (= 2 (count result)))
      (is (contains? result (:id sample-item-4)))
      (is (contains? result (:id sample-item-5)))
      ;; Does NOT include the group's own ID
      (is (not (contains? result (:id sample-group))))))
  
  (testing "returns all descendant IDs for nested groups"
    (let [result (chains/collect-descendant-ids nested-group)]
      ;; nested-group has: inner-group{sample-item-3}, sample-item-1
      ;; Should include: inner-group ID, sample-item-3 ID, sample-item-1 ID
      (is (= 3 (count result)))
      (is (contains? result #uuid "00000000-0000-0000-0000-000000000011")) ; inner group
      (is (contains? result (:id sample-item-3)))
      (is (contains? result (:id sample-item-1)))
      ;; Does NOT include the outer group's own ID
      (is (not (contains? result (:id nested-group))))))
  
  (testing "returns all descendant IDs for deeply nested empty groups"
    ;; group-a{group-b{group-c{}}} - 3 levels of empty nesting
    (let [group-c {:id #uuid "00000000-0000-0000-0000-0000000000c0"
                   :type :group
                   :name "Group C (empty)"
                   :items []}
          group-b {:id #uuid "00000000-0000-0000-0000-0000000000b0"
                   :type :group
                   :name "Group B"
                   :items [group-c]}
          group-a {:id #uuid "00000000-0000-0000-0000-0000000000a0"
                   :type :group
                   :name "Group A"
                   :items [group-b]}
          result (chains/collect-descendant-ids group-a)]
      ;; Should include group-b and group-c IDs
      (is (= 2 (count result)))
      (is (contains? result (:id group-b)))
      (is (contains? result (:id group-c)))
      ;; Does NOT include the root group's own ID
      (is (not (contains? result (:id group-a))))))
  
  (testing "returns all descendant IDs for groups containing only groups"
    ;; outer{middle-a{}, middle-b{inner{}}} - groups containing only groups
    (let [inner {:id #uuid "00000000-0000-0000-0000-000000000031"
                 :type :group
                 :name "Inner"
                 :items []}
          middle-a {:id #uuid "00000000-0000-0000-0000-000000000032"
                    :type :group
                    :name "Middle A"
                    :items []}
          middle-b {:id #uuid "00000000-0000-0000-0000-000000000033"
                    :type :group
                    :name "Middle B"
                    :items [inner]}
          outer {:id #uuid "00000000-0000-0000-0000-000000000034"
                 :type :group
                 :name "Outer"
                 :items [middle-a middle-b]}
          result (chains/collect-descendant-ids outer)]
      ;; Should include middle-a, middle-b, and inner IDs
      (is (= 3 (count result)))
      (is (contains? result (:id middle-a)))
      (is (contains? result (:id middle-b)))
      (is (contains? result (:id inner)))
      ;; Does NOT include the root group's own ID
      (is (not (contains? result (:id outer))))))
  
  (testing "returns all descendant IDs for mixed groups and items"
    ;; root{group-a{item-1, group-b{item-2}}, item-3}
    (let [item-1 {:id #uuid "00000000-0000-0000-0000-000000000041"
                  :effect-id :scale}
          item-2 {:id #uuid "00000000-0000-0000-0000-000000000042"
                  :effect-id :translate}
          item-3 {:id #uuid "00000000-0000-0000-0000-000000000043"
                  :effect-id :rotate}
          group-b {:id #uuid "00000000-0000-0000-0000-000000000044"
                   :type :group
                   :name "Group B"
                   :items [item-2]}
          group-a {:id #uuid "00000000-0000-0000-0000-000000000045"
                   :type :group
                   :name "Group A"
                   :items [item-1 group-b]}
          root {:id #uuid "00000000-0000-0000-0000-000000000046"
                :type :group
                :name "Root"
                :items [group-a item-3]}
          result (chains/collect-descendant-ids root)]
      ;; Should include all 5 descendants: group-a, item-1, group-b, item-2, item-3
      (is (= 5 (count result)))
      (is (contains? result (:id group-a)))
      (is (contains? result (:id item-1)))
      (is (contains? result (:id group-b)))
      (is (contains? result (:id item-2)))
      (is (contains? result (:id item-3)))
      ;; Does NOT include the root group's own ID
      (is (not (contains? result (:id root)))))))
