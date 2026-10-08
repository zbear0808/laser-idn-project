(ns laser-show.events.handlers.list.helpers-test
  "Tests for ID-based tree manipulation helpers."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.animation.chains :as chains]
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


;; collect-by-ids tests


(deftest collect-by-ids-test
  (testing "collect does not duplicate group children when both group and children IDs are selected"
    (let [all-ids (chains/collect-all-ids nested-tree)
          collected (h/collect-by-ids nested-tree all-ids)]
      (is (= 3 (count collected)))
      (is (= [id-a id-f id-e] (mapv :id collected)))
      (is (= [id-c id-d] (mapv :id (:items (second collected)))))))

  (testing "collect only children when group itself is not in ids set"
    (let [collected (h/collect-by-ids nested-tree #{id-c id-d})]
      (is (= [id-c id-d] (mapv :id collected))))))


;; normalize-selected-ids tests


(deftest normalize-selected-ids-test
  (testing "removes descendant IDs when group and all descendants are selected"
    (let [selected-ids #{id-f id-c id-d}
          normalized (h/normalize-selected-ids selected-ids nested-tree)]
      (is (= #{id-f} normalized))))

  (testing "removes descendant IDs even when only some descendants are selected alongside group"
    (let [selected-ids #{id-f id-c}
          normalized (h/normalize-selected-ids selected-ids nested-tree)]
      (is (= #{id-f} normalized))))

  (testing "preserves descendant IDs when group is not selected"
    (let [selected-ids #{id-c id-d}
          normalized (h/normalize-selected-ids selected-ids nested-tree)]
      (is (= #{id-c id-d} normalized))))

  (testing "deeply nested group descendant removal"
    (let [selected-ids #{id-f id-g2-val id-d}
          normalized (h/normalize-selected-ids selected-ids doubly-nested-tree)]
      (is (= #{id-f} normalized)))))


;; move-items-advanced tests


(deftest move-items-advanced-test
  (testing "pull nested group out before parent group to make it top level"
    (let [result (h/move-items doubly-nested-tree #{id-g2-val} id-f :before)]
      (is (= 4 (count result)))
      (is (= [id-a id-g2-val id-f id-e] (mapv :id result)))
      (is (= [id-c] (mapv :id (get-in result [2 :items]))))
      (is (= [id-d] (mapv :id (get-in result [1 :items]))))))

  (testing "pull nested group out after parent group to make it top level"
    (let [result (h/move-items doubly-nested-tree #{id-g2-val} id-f :after)]
      (is (= 4 (count result)))
      (is (= [id-a id-f id-g2-val id-e] (mapv :id result)))
      (is (= [id-c] (mapv :id (get-in result [1 :items]))))))

  (testing "pull nested group out to :root-end via :append"
    (let [result (h/move-items doubly-nested-tree #{id-g2-val} :root-end :append)]
      (is (= 4 (count result)))
      (is (= [id-a id-f id-e id-g2-val] (mapv :id result)))
      (is (= [id-c] (mapv :id (get-in result [1 :items]))))))

  (testing "prevent moving a group into itself (no data loss)"
    (let [result (h/move-items nested-tree #{id-f} id-f :into)]
      (is (= nested-tree result))))

  (testing "prevent moving a group into its own descendant (no data loss)"
    (let [result (h/move-items doubly-nested-tree #{id-f} id-g2-val :into)]
      (is (= doubly-nested-tree result)))
    (let [result (h/move-items doubly-nested-tree #{id-f} id-d :before)]
      (is (= doubly-nested-tree result))))

  (testing "fallback to append when target-id is not found (no items lost)"
    (let [fake-id (java.util.UUID/randomUUID)
          result (h/move-items nested-tree #{id-c} fake-id :after)]
      (is (= 4 (count result)))
      (is (= id-c (:id (last result))))
      (is (= [id-d] (mapv :id (get-in result [1 :items])))))))


;; calculate-selection tests


(def chain-12
  (mapv (fn [i] {:id (java.util.UUID/fromString (format "00000000-0000-0000-0000-%012d" i))
                 :name (str "Item " i)})
        (range 12)))

(def chain-ids (mapv :id chain-12))


(deftest calculate-selection-test
  (testing "single selection mode"
    (let [s0 (h/calculate-selection {} chain-12 (nth chain-ids 3) :single)]
      (is (= #{(nth chain-ids 3)} (:selected-ids s0)))
      (is (= (nth chain-ids 3) (:last-selected-id s0))))
    (testing "single selection replaces previous multi-selection"
      (let [initial {:selected-ids (set chain-ids) :last-selected-id (nth chain-ids 0)}
            result (h/calculate-selection initial chain-12 (nth chain-ids 5) :single)]
        (is (= #{(nth chain-ids 5)} (:selected-ids result)))
        (is (= (nth chain-ids 5) (:last-selected-id result))))))

  (testing "bottom-to-top shift selection (regression test: selecting 10th then 2nd element)"
    (let [initial (h/calculate-selection {} chain-12 (nth chain-ids 9) :single)
          shift-result (h/calculate-selection initial chain-12 (nth chain-ids 1) :shift)
          expected-ids (set (subvec chain-ids 1 10))]
      (is (= expected-ids (:selected-ids shift-result)))
      (is (= 9 (count (:selected-ids shift-result))))
      (is (= (nth chain-ids 9) (:last-selected-id shift-result))
          "Anchor must remain at original selection (item 9) for further shift clicks")))

  (testing "top-to-bottom shift selection"
    (let [initial (h/calculate-selection {} chain-12 (nth chain-ids 1) :single)
          shift-result (h/calculate-selection initial chain-12 (nth chain-ids 9) :shift)
          expected-ids (set (subvec chain-ids 1 10))]
      (is (= expected-ids (:selected-ids shift-result)))
      (is (= 9 (count (:selected-ids shift-result))))
      (is (= (nth chain-ids 1) (:last-selected-id shift-result))
          "Anchor must remain at original selection (item 1) for further shift clicks")))

  (testing "consecutive shift-clicks maintain the original anchor"
    (let [s0 (h/calculate-selection {} chain-12 (nth chain-ids 5) :single)
          s1 (h/calculate-selection s0 chain-12 (nth chain-ids 2) :shift)
          s2 (h/calculate-selection s1 chain-12 (nth chain-ids 8) :shift)
          s3 (h/calculate-selection s2 chain-12 (nth chain-ids 5) :shift)]
      ;; First shift click: 2..5 (4 items)
      (is (= (set (subvec chain-ids 2 6)) (:selected-ids s1)))
      (is (= (nth chain-ids 5) (:last-selected-id s1)))
      ;; Second shift click: 5..8 (4 items) - anchor did not move to 2
      (is (= (set (subvec chain-ids 5 9)) (:selected-ids s2)))
      (is (= (nth chain-ids 5) (:last-selected-id s2)))
      ;; Third shift click back to anchor: only 5 selected
      (is (= #{(nth chain-ids 5)} (:selected-ids s3)))
      (is (= (nth chain-ids 5) (:last-selected-id s3)))))

  (testing "ctrl-click toggle behavior"
    (let [s0 (h/calculate-selection {} chain-12 (nth chain-ids 2) :single)
          s1 (h/calculate-selection s0 chain-12 (nth chain-ids 6) :ctrl)
          s2 (h/calculate-selection s1 chain-12 (nth chain-ids 2) :ctrl)]
      (is (= #{(nth chain-ids 2) (nth chain-ids 6)} (:selected-ids s1)))
      (is (= (nth chain-ids 6) (:last-selected-id s1)))
      (is (= #{(nth chain-ids 6)} (:selected-ids s2)))
      (is (= (nth chain-ids 2) (:last-selected-id s2)))))

  (testing "ctrl-shift range unions with existing selection (both directions)"
    ;; Top-to-bottom range union
    (let [s0 {:selected-ids #{(nth chain-ids 0)} :last-selected-id (nth chain-ids 3)}
          s1 (h/calculate-selection s0 chain-12 (nth chain-ids 6) :ctrl-shift)]
      (is (= #{(nth chain-ids 0) (nth chain-ids 3) (nth chain-ids 4) (nth chain-ids 5) (nth chain-ids 6)}
             (:selected-ids s1)))
      (is (= (nth chain-ids 3) (:last-selected-id s1))))
    ;; Bottom-to-top range union
    (let [s0 {:selected-ids #{(nth chain-ids 0)} :last-selected-id (nth chain-ids 8)}
          s1 (h/calculate-selection s0 chain-12 (nth chain-ids 5) :ctrl-shift)]
      (is (= #{(nth chain-ids 0) (nth chain-ids 5) (nth chain-ids 6) (nth chain-ids 7) (nth chain-ids 8)}
             (:selected-ids s1)))
      (is (= (nth chain-ids 8) (:last-selected-id s1)))))

  (testing "nested tree shift-selection preserves document order"
    ;; Top-to-bottom across group: [id-a, id-f(group), id-c, id-d, id-e]
    (let [s0 {:selected-ids #{id-a} :last-selected-id id-a}
          s1 (h/calculate-selection s0 nested-tree id-d :shift)]
      (is (= #{id-a id-f id-c id-d} (:selected-ids s1)))
      (is (= id-a (:last-selected-id s1))))
    ;; Bottom-to-top across group
    (let [s0 {:selected-ids #{id-e} :last-selected-id id-e}
          s1 (h/calculate-selection s0 nested-tree id-c :shift)]
      (is (= #{id-c id-d id-e} (:selected-ids s1)))
      (is (= id-e (:last-selected-id s1))))
    ;; Bottom-to-top from inside group to top level
    (let [s0 {:selected-ids #{id-d} :last-selected-id id-d}
          s1 (h/calculate-selection s0 nested-tree id-a :shift)]
      (is (= #{id-a id-f id-c id-d} (:selected-ids s1)))
      (is (= id-d (:last-selected-id s1)))))

  (testing "edge cases and resilience"
    (testing "nil last-selected-id falls back to selecting target item"
      (let [result (h/calculate-selection {:selected-ids #{}} chain-12 (nth chain-ids 4) :shift)]
        (is (= #{(nth chain-ids 4)} (:selected-ids result)))
        (is (= (nth chain-ids 4) (:last-selected-id result)))))

    (testing "stale/invalid anchor ID resets anchor and selects target item"
      (let [stale-id (java.util.UUID/randomUUID)
            result (h/calculate-selection {:selected-ids #{stale-id} :last-selected-id stale-id}
                                          chain-12
                                          (nth chain-ids 4)
                                          :shift)]
        (is (= #{(nth chain-ids 4)} (:selected-ids result)))
        (is (= (nth chain-ids 4) (:last-selected-id result)))))

    (testing "target ID not found in tree does not throw exception"
      (let [unknown-id (java.util.UUID/randomUUID)
            result (h/calculate-selection {:selected-ids #{} :last-selected-id (nth chain-ids 2)}
                                          chain-12
                                          unknown-id
                                          :shift)]
        (is (= #{unknown-id} (:selected-ids result)))))

    (testing "nil current-selection parameter map"
      (let [result (h/calculate-selection nil chain-12 (nth chain-ids 2) :single)]
        (is (= #{(nth chain-ids 2)} (:selected-ids result)))
        (is (= (nth chain-ids 2) (:last-selected-id result)))))

    (testing "parameter map overload and thread-first chaining"
      (let [result (-> {}
                       (h/calculate-selection chain-12 (nth chain-ids 1) :single)
                       (h/calculate-selection chain-12 (nth chain-ids 4) :shift))]
        (is (= (set (subvec chain-ids 1 5)) (:selected-ids result)))
        (is (= (nth chain-ids 1) (:last-selected-id result))))
      (let [param-result (h/calculate-selection {} {:items chain-12
                                                    :item-id (nth chain-ids 3)
                                                    :mode :single})]
        (is (= #{(nth chain-ids 3)} (:selected-ids param-result)))))))
