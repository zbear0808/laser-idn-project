(ns laser-show.events.handlers.list.helpers-test
  "Tests for ID-based tree manipulation helpers."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.handlers.list.helpers :as h]))

(def id-a (java.util.UUID/fromString "00000000-0000-0000-0000-00000000000a"))
(def id-b (java.util.UUID/fromString "00000000-0000-0000-0000-00000000000b"))
(def id-c (java.util.UUID/fromString "00000000-0000-0000-0000-00000000000c"))
(def id-d (java.util.UUID/fromString "00000000-0000-0000-0000-00000000000d"))
(def id-e (java.util.UUID/fromString "00000000-0000-0000-0000-00000000000e"))
(def id-f (java.util.UUID/fromString "00000000-0000-0000-0000-0000000000aa"))


(def item-a {:id id-a :name "A"})
(def item-b {:id id-b :name "B"})
(def item-c {:id id-c :name "C"})
(def item-d {:id id-d :name "D"})
(def item-e {:id id-e :name "E"})

(def group-1
  {:id id-f :type :group :name "G1" :enabled? true
   :items [item-c item-d]})

(def flat-tree [item-a item-b item-c])

(def nested-tree
  "Tree: A, G1{C, D}, E"
  [item-a group-1 item-e])

(def id-g2-val (java.util.UUID/fromString "00000000-0000-0000-0000-000000000002"))

(def doubly-nested-tree
  "Tree: A, G1{C, G2{D}}, E"
  [item-a
   {:id id-f :type :group :name "G1" :enabled? true
    :items [item-c
            {:id id-g2-val :type :group :name "G2" :enabled? true
             :items [item-d]}]}
   item-e])


;; remove-by-ids tests


(deftest remove-by-ids-test
  (testing "remove single item from flat tree"
    (is (= [item-a item-c]
           (h/remove-by-ids flat-tree #{id-b}))))

  (testing "remove multiple items from flat tree"
    (is (= [item-b]
           (h/remove-by-ids flat-tree #{id-a id-c}))))

  (testing "remove item from inside a group"
    (let [result (h/remove-by-ids nested-tree #{id-c})]
      (is (= 3 (count result)))
      (is (= [item-d] (get-in result [1 :items])))))

  (testing "remove a group itself"
    (let [result (h/remove-by-ids nested-tree #{id-f})]
      (is (= [item-a item-e] result))))

  (testing "remove from deeply nested"
    (let [result (h/remove-by-ids doubly-nested-tree #{id-d})]
      (is (= [] (get-in result [1 :items 1 :items])))))

  (testing "remove nonexistent ID — no change"
    (let [fake-id (java.util.UUID/randomUUID)]
      (is (= flat-tree (h/remove-by-ids flat-tree #{fake-id})))))

  (testing "remove from empty tree"
    (is (= [] (h/remove-by-ids [] #{id-a})))))


;; update-by-id tests


(deftest update-by-id-test
  (testing "update item in flat tree"
    (let [result (h/update-by-id flat-tree id-b #(assoc % :name "B2"))]
      (is (= "B2" (:name (second result))))))

  (testing "update item inside group"
    (let [result (h/update-by-id nested-tree id-c #(assoc % :name "C2"))]
      (is (= "C2" (get-in result [1 :items 0 :name])))))

  (testing "update group itself"
    (let [result (h/update-by-id nested-tree id-f #(assoc % :name "Renamed"))]
      (is (= "Renamed" (get-in result [1 :name])))))

  (testing "update nonexistent ID — no change"
    (let [fake-id (java.util.UUID/randomUUID)]
      (is (= flat-tree (h/update-by-id flat-tree fake-id #(assoc % :x 1)))))))


;; set-item-field tests


(deftest set-item-field-test
  (testing "set field on flat item"
    (let [result (h/set-item-field flat-tree id-a :enabled? false)]
      (is (= false (:enabled? (first result))))))

  (testing "set field on nested item"
    (let [result (h/set-item-field nested-tree id-d :name "D-renamed")]
      (is (= "D-renamed" (get-in result [1 :items 1 :name]))))))


;; insert-after-id tests


(deftest insert-after-id-test
  (testing "insert after item in flat tree"
    (let [new-item {:id (java.util.UUID/randomUUID) :name "NEW"}
          result (h/insert-after-id flat-tree id-a [new-item])]
      (is (= 4 (count result)))
      (is (= id-a (:id (first result))))
      (is (= "NEW" (:name (second result))))
      (is (= id-b (:id (nth result 2))))))

  (testing "insert after last item"
    (let [new-item {:id (java.util.UUID/randomUUID) :name "NEW"}
          result (h/insert-after-id flat-tree id-c [new-item])]
      (is (= 4 (count result)))
      (is (= "NEW" (:name (last result))))))

  (testing "insert after item inside group"
    (let [new-item {:id (java.util.UUID/randomUUID) :name "NEW"}
          result (h/insert-after-id nested-tree id-c [new-item])]
      (is (= 3 (count result)))  ;; top level unchanged
      (is (= 3 (count (get-in result [1 :items]))))
      (is (= "NEW" (get-in result [1 :items 1 :name])))))

  (testing "insert multiple items after"
    (let [n1 {:id (java.util.UUID/randomUUID) :name "N1"}
          n2 {:id (java.util.UUID/randomUUID) :name "N2"}
          result (h/insert-after-id flat-tree id-a [n1 n2])]
      (is (= 5 (count result)))
      (is (= ["A" "N1" "N2" "B" "C"] (mapv :name result))))))


;; insert-before-id tests


(deftest insert-before-id-test
  (testing "insert before first item"
    (let [new-item {:id (java.util.UUID/randomUUID) :name "NEW"}
          result (h/insert-before-id flat-tree id-a [new-item])]
      (is (= 4 (count result)))
      (is (= "NEW" (:name (first result))))
      (is (= id-a (:id (second result))))))

  (testing "insert before item inside group"
    (let [new-item {:id (java.util.UUID/randomUUID) :name "NEW"}
          result (h/insert-before-id nested-tree id-d [new-item])]
      (is (= 3 (count (get-in result [1 :items]))))
      (is (= "NEW" (get-in result [1 :items 1 :name])))
      (is (= "D" (get-in result [1 :items 2 :name]))))))


;; insert-into-group tests


(deftest insert-into-group-test
  (testing "insert into group"
    (let [new-item {:id (java.util.UUID/randomUUID) :name "NEW"}
          result (h/insert-into-group nested-tree id-f [new-item])]
      (is (= 3 (count (get-in result [1 :items]))))
      (is (= "NEW" (get-in result [1 :items 2 :name])))))

  (testing "insert into empty group"
    (let [empty-group {:id id-f :type :group :name "Empty" :items []}
          tree [item-a empty-group]
          new-item {:id (java.util.UUID/randomUUID) :name "NEW"}
          result (h/insert-into-group tree id-f [new-item])]
      (is (= 1 (count (get-in result [1 :items]))))
      (is (= "NEW" (get-in result [1 :items 0 :name]))))))


;; move-items tests


(deftest move-items-test
  (testing "move item forward within flat tree"
    ;; A B C → move B after C → A C B
    (let [result (h/move-items flat-tree #{id-b} id-c :after)]
      (is (= [id-a id-c id-b] (mapv :id result)))))

  (testing "move item backward within flat tree"
    ;; A B C → move C before A → C A B
    (let [result (h/move-items flat-tree #{id-c} id-a :before)]
      (is (= [id-c id-a id-b] (mapv :id result)))))

  (testing "move item into group"
    ;; A G1{C D} E → move A into G1
    (let [result (h/move-items nested-tree #{id-a} id-f :into)]
      (is (= 2 (count result)))  ;; G1 and E at top level
      (is (= 3 (count (get-in result [0 :items]))))  ;; C, D, A inside G1
      (is (= id-a (get-in result [0 :items 2 :id])))))

  (testing "move item out of group"
    ;; A G1{C D} E → move C after G1
    (let [result (h/move-items nested-tree #{id-c} id-f :after)]
      (is (= 4 (count result)))  ;; A, G1, C, E
      (is (= id-c (:id (nth result 2))))
      ;; G1 should only have D
      (is (= [id-d] (mapv :id (get-in result [1 :items]))))))

  (testing "move multiple items"
    ;; A B C → move A and C before B → A C B
    (let [result (h/move-items flat-tree #{id-a id-c} id-b :before)]
      (is (= [id-a id-c id-b] (mapv :id result)))))

  (testing "move to same position — should not crash"
    (let [result (h/move-items flat-tree #{id-a} id-a :after)]
      ;; A is removed then inserted after where A was — result may vary
      ;; but should not throw
      (is (= 3 (count result)))))

  (testing "move nonexistent items — no change"
    (let [fake-id (java.util.UUID/randomUUID)]
      (is (= flat-tree (h/move-items flat-tree #{fake-id} id-a :before))))))


;; group-items-by-ids tests


(deftest group-items-by-ids-test
  (testing "group two items at same level"
    (let [result (h/group-items-by-ids flat-tree #{id-a id-b})]
      (is (some? result))
      (is (some? (:group-id result)))
      (is (= 2 (count (:items result))))  ;; group + C
      (let [group (first (:items result))]
        (is (= :group (:type group)))
        (is (= [id-a id-b] (mapv :id (:items group)))))))

  (testing "group items inside nested group"
    ;; G1 has {C D} — group C and D
    (let [result (h/group-items-by-ids nested-tree #{id-c id-d})]
      (is (some? result))
      ;; G1's items should have 1 sub-group containing C and D
      (let [g1 (second (:items result))]
        (is (= 1 (count (:items g1))))
        (is (= :group (:type (first (:items g1))))))))

  (testing "cannot group items at different levels"
    ;; A is at top level, C is inside G1
    (is (nil? (h/group-items-by-ids nested-tree #{id-a id-c}))))

  (testing "group preserves order"
    ;; A B C → group B C → should be [A, group{B C}]
    (let [result (h/group-items-by-ids flat-tree #{id-b id-c})]
      (is (some? result))
      (is (= 2 (count (:items result))))
      (is (= id-a (:id (first (:items result)))))
      (let [group (second (:items result))]
        (is (= [id-b id-c] (mapv :id (:items group))))))))


;; ungroup-by-id tests


(deftest ungroup-by-id-test
  (testing "ungroup at top level"
    ;; A G1{C D} E → ungroup G1 → A C D E
    (let [result (h/ungroup-by-id nested-tree id-f)]
      (is (= [id-a id-c id-d id-e] (mapv :id result)))))

  (testing "ungroup nested group"
    ;; A G1{C G2{D}} E → ungroup G2 → A G1{C D} E
    (let [result (h/ungroup-by-id doubly-nested-tree id-g2-val)]
      (is (= 3 (count result)))
      (is (= [id-c id-d] (mapv :id (get-in result [1 :items]))))))

  (testing "ungroup nonexistent ID — no change"
    (let [fake-id (java.util.UUID/randomUUID)]
      (is (= nested-tree (h/ungroup-by-id nested-tree fake-id)))))

  (testing "ungroup non-group item — no change"
    (is (= nested-tree (h/ungroup-by-id nested-tree id-a))))

  (testing "ungroup empty group"
    (let [empty-group {:id id-f :type :group :name "Empty" :items []}
          tree [item-a empty-group item-e]
          result (h/ungroup-by-id tree id-f)]
      (is (= [id-a id-e] (mapv :id result))))))
