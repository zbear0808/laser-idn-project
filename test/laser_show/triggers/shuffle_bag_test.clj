(ns laser-show.triggers.shuffle-bag-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.triggers.shuffle-bag :as bag]))

(deftest emits-each-item-once
  (let [b (bag/make [:a :b :c] identity)
        [a b] (bag/next b)
        [x c] (bag/next b)
        [y d] (bag/next c)
        [z _] (bag/next d)]
    (is (= [:a :b :c] [a x y]))
    (is (= :a z))
    (is (= 0 (bag/remaining-count d)))))
