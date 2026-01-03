(ns laser-show.animation.effects-test
  "Tests for effect chain groups functionality."
  (:require [clojure.test :refer [deftest testing is are]]
            [laser-show.animation.effects :as effects]))


;; Test Data


(def sample-effect-1
  {:effect-id :scale :params {:x-scale 1.5} :enabled? true})

(def sample-effect-2
  {:effect-id :rotate :params {:angle 45} :enabled? true})

(def sample-effect-3
  {:effect-id :translate :params {:x 0.1 :y 0.2} :enabled? true})

(def disabled-effect
  {:effect-id :hue-shift :params {:shift 0.5} :enabled? false})

(def sample-group
  {:type :group
   :id "group-1"
   :name "Test Group"
   :collapsed? false
   :enabled? true
   :items [sample-effect-1 sample-effect-2]})

(def disabled-group
  {:type :group
   :id "group-2"
   :name "Disabled Group"
   :collapsed? false
   :enabled? false
   :items [sample-effect-1 sample-effect-2]})

(def nested-group
  {:type :group
   :id "outer-group"
   :name "Outer Group"
   :enabled? true
   :items [{:type :group
            :id "inner-group"
            :name "Inner Group"
            :enabled? true
            :items [sample-effect-1]}
           sample-effect-2]})


;; group? and effect? tests


(deftest group?-test
  (testing "identifies groups correctly"
    (is (effects/group? sample-group))
    (is (effects/group? {:type :group :items []})))
  
  (testing "returns false for effects"
    (is (not (effects/group? sample-effect-1)))
    (is (not (effects/group? {:effect-id :scale})))
    (is (not (effects/group? {:type :effect :effect-id :scale})))))


(deftest effect?-test
  (testing "identifies effects correctly"
    (is (effects/effect? sample-effect-1))
    (is (effects/effect? {:effect-id :scale}))
    (is (effects/effect? {:type :effect :effect-id :scale})))
  
  (testing "returns false for groups"
    (is (not (effects/effect? sample-group)))
    (is (not (effects/effect? {:type :group :items []})))))


;; flatten-chain tests


(deftest flatten-chain-test
  (testing "flat chain returns same effects"
    (let [chain [sample-effect-1 sample-effect-2]
          result (effects/flatten-chain chain)]
      (is (= 2 (count result)))
      (is (= sample-effect-1 (first result)))
      (is (= sample-effect-2 (second result)))))
  
  (testing "flattens single-level group"
    (let [chain [sample-group sample-effect-3]
          result (effects/flatten-chain chain)]
      (is (= 3 (count result)))
      (is (= sample-effect-1 (first result)))
      (is (= sample-effect-2 (second result)))
      (is (= sample-effect-3 (nth result 2)))))
  
  (testing "flattens nested groups"
    (let [result (effects/flatten-chain [nested-group])]
      (is (= 2 (count result)))
      (is (= sample-effect-1 (first result)))
      (is (= sample-effect-2 (second result)))))
  
  (testing "skips disabled effects"
    (let [chain [sample-effect-1 disabled-effect sample-effect-2]
          result (effects/flatten-chain chain)]
      (is (= 2 (count result)))
      (is (not (some #(= :hue-shift (:effect-id %)) result)))))
  
  (testing "skips disabled groups and all their contents"
    (let [chain [disabled-group sample-effect-3]
          result (effects/flatten-chain chain)]
      (is (= 1 (count result)))
      (is (= sample-effect-3 (first result)))))
  
  (testing "empty chain returns empty"
    (is (empty? (effects/flatten-chain []))))
  
  (testing "group with empty items returns empty"
    (let [empty-group {:type :group :id "empty" :enabled? true :items []}
          result (effects/flatten-chain [empty-group])]
      (is (empty? result)))))


;; nesting-depth tests


(deftest nesting-depth-test
  (testing "flat chain has depth 0"
    (is (= 0 (effects/nesting-depth [sample-effect-1 sample-effect-2]))))
  
  (testing "single group has depth 1"
    (is (= 1 (effects/nesting-depth [sample-group]))))
  
  (testing "nested group has depth 2"
    (is (= 2 (effects/nesting-depth [nested-group]))))
  
  (testing "deeply nested groups"
    (let [deep-chain [{:type :group
                       :enabled? true
                       :items [{:type :group
                                :enabled? true
                                :items [{:type :group
                                         :enabled? true
                                         :items [sample-effect-1]}]}]}]]
      (is (= 3 (effects/nesting-depth deep-chain)))))
  
  (testing "empty chain has depth 0"
    (is (= 0 (effects/nesting-depth [])))))


;; can-add-group-at-path? tests


(deftest can-add-group-at-path?-test
  (testing "can add group at top level"
    (is (effects/can-add-group-at-path? [] [0]))
    (is (effects/can-add-group-at-path? [] [1])))
  
  (testing "can add group at depth 1"
    (is (effects/can-add-group-at-path? [] [0 :items 0])))
  
  (testing "can add group at depth 2"
    (is (effects/can-add-group-at-path? [] [0 :items 0 :items 0])))
  
  (testing "cannot add group at depth 3 (max-nesting-depth)"
    (is (not (effects/can-add-group-at-path? [] [0 :items 0 :items 0 :items 0])))))


;; paths-in-chain tests


(deftest paths-in-chain-test
  (testing "flat chain generates simple paths"
    (let [chain [sample-effect-1 sample-effect-2 sample-effect-3]
          paths (effects/paths-in-chain chain)]
      (is (= [[0] [1] [2]] paths))))
  
  (testing "group generates path for group and nested items"
    (let [chain [sample-group]
          paths (effects/paths-in-chain chain)]
      (is (= [[0] [0 :items 0] [0 :items 1]] paths))))
  
  (testing "mixed chain generates correct paths"
    (let [chain [sample-effect-1 sample-group sample-effect-3]
          paths (effects/paths-in-chain chain)]
      (is (= [[0] [1] [1 :items 0] [1 :items 1] [2]] paths))))
  
  (testing "nested groups generate correct paths"
    (let [paths (effects/paths-in-chain [nested-group])]
      (is (= [[0] [0 :items 0] [0 :items 0 :items 0] [0 :items 1]] paths))))
  
  (testing "empty chain returns empty paths"
    (is (empty? (effects/paths-in-chain [])))))


;; get-item-at-path tests


(deftest get-item-at-path-test
  (testing "gets top-level item"
    (let [chain [sample-effect-1 sample-effect-2]]
      (is (= sample-effect-1 (effects/get-item-at-path chain [0])))
      (is (= sample-effect-2 (effects/get-item-at-path chain [1])))))
  
  (testing "gets item inside group"
    (let [chain [sample-group]]
      (is (= sample-effect-1 (effects/get-item-at-path chain [0 :items 0])))
      (is (= sample-effect-2 (effects/get-item-at-path chain [0 :items 1])))))
  
  (testing "gets group itself"
    (let [chain [sample-group]]
      (is (= sample-group (effects/get-item-at-path chain [0])))))
  
  (testing "returns nil for invalid path"
    (let [chain [sample-effect-1]]
      (is (nil? (effects/get-item-at-path chain [5])))
      (is (nil? (effects/get-item-at-path chain [0 :items 0]))))))


;; count-effects-recursive tests


(deftest count-effects-recursive-test
  (testing "counts flat chain"
    (is (= 3 (effects/count-effects-recursive [sample-effect-1 sample-effect-2 sample-effect-3]))))
  
  (testing "counts effects inside groups"
    (is (= 2 (effects/count-effects-recursive [sample-group]))))
  
  (testing "counts mixed chain"
    (is (= 4 (effects/count-effects-recursive [sample-effect-1 sample-group sample-effect-3]))))
  
  (testing "counts nested groups"
    (is (= 2 (effects/count-effects-recursive [nested-group]))))
  
  (testing "empty chain returns 0"
    (is (= 0 (effects/count-effects-recursive [])))))
