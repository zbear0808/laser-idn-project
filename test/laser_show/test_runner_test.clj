(ns laser-show.test-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.test-runner :as runner]))

(deftest every-test-is-in-exactly-one-shard-test
  (doseq [total [0 1 3 4 5 17 355]
          n [1 2 3 4 5]]
    (testing (str total " tests in " n " shards")
      (let [tests (vec (range total))
            parts (mapv #(runner/shard tests % n) (range 1 (inc n)))
            sizes (map count parts)]
        (is (= tests (sort (apply concat parts))))
        (is (<= (- (apply max sizes) (apply min sizes)) 1))))))

(deftest a-shard-takes-every-nth-test-test
  (is (= [1 5 9] (runner/shard (range 12) 2 4)))
  (is (= [0 4 8] (runner/shard (range 12) 1 4)))
  (is (= [] (runner/shard (range 2) 3 4))))
