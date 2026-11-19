(ns laser-show.triggers.shuffle-bag-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.triggers.shuffle-bag :as bag]))

(defn- picks
  "The first n picks from a bag over items, refill k (from 0) shuffled by
   (shuffle-for k), as the vibe engine does with (bag/seeded (+ seed k))."
  [items n shuffle-for]
  (loop [b (bag/make items) k 0 acc []]
    (if (= n (count acc))
      acc
      (let [refill? (empty? (:remaining b))
            [item b'] (bag/next b (shuffle-for k))]
        (recur b' (cond-> k refill? inc) (conj acc item))))))

(defn- repeats [xs]
  (count (filter (fn [[a b]] (= a b)) (partition 2 1 xs))))

(deftest emits-each-item-once
  (let [b (bag/make [:a :b :c])
        [a b] (bag/next b identity)
        [x c] (bag/next b identity)
        [y d] (bag/next c identity)
        [z _] (bag/next d identity)]
    (is (= [:a :b :c] [a x y]))
    (is (= :a z))
    (is (= 0 (bag/remaining-count d)))))

(deftest never-repeats-back-to-back-test
  (testing "10,000 picks from pools of 2 to 8, a new seed every refill (probe v1: 25 % repeats for 2 before the guard)"
    (doseq [n (range 2 9)]
      (let [xs (picks (vec (range n)) 10000 #(bag/seeded (+ 1000 %)))]
        (is (zero? (repeats xs)) (str "pool of " n))))))

(deftest refill-guard-swaps-a-repeat-test
  (let [script [[:a :b :c] [:c :b :a]]]
    (is (= [:a :b :c :a :b :c] (picks [:a :b :c] 6 (fn [k] (fn [_] (script k)))))
        "the second refill would start with :c, the last pick: its first and last swap"))
  (let [script [[:a :b] [:b :a] [:b :a]]]
    (is (= [:a :b :a :b :a :b] (picks [:a :b] 6 (fn [k] (fn [_] (script k)))))
        "two items alternate: a refill that starts with the last pick swaps")))

(deftest same-seed-same-order-test
  (let [items (vec (range 10))
        order (fn [seed] ((bag/seeded seed) items))]
    (is (= (order 42) (order 42)))
    (is (= (sort (order 42)) items) "a permutation")
    (is (not= (order 42) (order 43)))
    (is (= (picks items 30 #(bag/seeded (+ 7 %))) (picks items 30 #(bag/seeded (+ 7 %))))
        "a bag's picks follow from its seed")))

(deftest edge-sizes-test
  (let [[item b] (bag/next (bag/make []) identity)]
    (is (nil? item))
    (is (= (bag/make []) b)))
  (is (= [:a :a :a] (picks [:a] 3 (fn [_] identity))) "nothing to avoid"))

(deftest sync-keeps-last-and-drops-gone-test
  (let [[_ b] (bag/next (bag/make [:a :b :c]) identity)]
    (testing "the table's case: :a picked, then the pool is [:b :c :d]"
      (is (= {:items [:b :c :d] :remaining [:b :c :d] :last :a}
             (select-keys (bag/sync b [:b :c :d]) [:items :remaining :last]))))
    (testing "a cell still to play this cycle goes when it leaves the pool"
      (is (= [:c] (:remaining (bag/sync b [:a :c])))))
    (testing "the same pool leaves the bag as it is"
      (is (identical? b (bag/sync b [:a :b :c]))))
    (testing "keys the engine keeps beside the bag's own stay"
      (is (= [7 1] ((juxt :seed :n) (bag/sync (assoc b :seed 7 :n 1) [:b])))))))
