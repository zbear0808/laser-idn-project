(ns laser-show.timeline.shows-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [laser-show.timeline.shows :as shows]))

(def u1 #uuid "00000000-0000-0000-0000-000000000001")
(def u2 #uuid "00000000-0000-0000-0000-000000000002")

(def follower {:editor-type :timeline :timeline/chase :mtc :items []})
(def not-following {:editor-type :timeline :items []})

(deftest play-key-test
  (testing "play-key, play-key?, grid-cell?, key-show-id"
    (is (= [:show u1] (shows/play-key u1)))
    (is (shows/play-key? [:show u1]))
    (is (not (shows/play-key? [0 1])))
    (is (not (shows/play-key? [:show "00000000-0000-0000-0000-000000000001"])) "a string id isn't a show key")
    (is (not (shows/play-key? [:show u1 :extra])))
    (is (not (shows/play-key? (list :show u1))))
    (is (not (shows/play-key? nil)))
    (is (not (shows/play-key? (shows/play-key nil))))
    (is (shows/grid-cell? [0 1]))
    (is (not (shows/grid-cell? [:show u1])))
    (is (not (shows/grid-cell? [0.0 1])))
    (is (= u1 (shows/key-show-id [:show u1])))
    (is (nil? (shows/key-show-id [3 2])))
    (is (nil? (shows/key-show-id nil))))
  (testing "a show key reads back equal from EDN"
    (is (= (shows/play-key u1) (edn/read-string (pr-str (shows/play-key u1)))))))

(deftest cue-at-test
  (let [c {:items [{:id 1}]}
        s {:editor-type :timeline}]
    (is (= c (shows/cue-at {:chains {:cue-chains {[0 0] c}}} [0 0])))
    (is (= s (shows/cue-at {:chains {:shows {u1 s}}} [:show u1])))
    (is (nil? (shows/cue-at {:chains {:shows {}}} [:show u1])) "a deleted show")
    (is (nil? (shows/cue-at {:chains {:cue-chains {[0 0] c}}} nil)))
    (is (nil? (shows/cue-at {:chains {:cue-chains {[:show u1] c}}} [:show u1]))
        "a show key never reads cue-chains")
    (is (= [:chains :shows u1] (shows/cue-path [:show u1])))
    (is (= [:chains :cue-chains [2 1]] (shows/cue-path [2 1])))))

(deftest key-order-test
  (testing "03 §4 examples"
    (is (= [[0 1] [1 0] [:show u1] [:show u2]]
           (sort shows/key-order [[:show u2] [1 0] [0 1] [:show u1]])))
    (is (= [[0 0] [1 0]] (sort shows/key-order [[1 0] [0 0]])) "grid cells keep today's order")
    (is (= [[:show u1] [:show u1]] (sort shows/key-order [[:show u1] [:show u1]])))
    (is (= [[0 0] [:show u1] nil] (sort shows/key-order [nil [:show u1] [0 0]])))
    (is (= [[:show u1] [:show u2]] (sort shows/key-order [[:show u2] [:show u1]]))))
  (testing "why it exists: plain sort throws on a mix of key shapes"
    (is (thrown? ClassCastException (doall (sort [[:show u1] [0 1]])))))
  (testing "total: every shuffle of a mixed vector sorts to one result"
    (let [ks [[2 0] [0 1] [:show u2] nil [:show u1] [:odd] [1 3]]
          sorted (sort shows/key-order ks)
          rnd (java.util.Random. 7)]
      (is (every? #(= sorted (sort shows/key-order %))
                  (repeatedly 200 #(let [l (java.util.ArrayList. ^java.util.Collection ks)]
                                     (java.util.Collections/shuffle l rnd)
                                     (vec l))))))))

(deftest playable-shows-test
  (is (= {} (shows/playable-shows {})) "no shows, no grid")
  (is (= {[:show u1] follower} (shows/playable-shows {:chains {:shows {u1 follower}}})))
  (is (= {[:show u1] follower} (shows/playable-shows {:chains {:shows {u1 follower u2 not-following}}})))
  (is (= {} (shows/playable-shows {:chains {:shows {u1 {:editor-type :cue-chain :timeline/chase :mtc}}}}))
      "not a timeline"))

(deftest playable-shows-s1-includes-grid-followers-test
  (is (= {[2 0] follower [:show u1] follower}
         (shows/playable-shows {:chains {:cue-chains {[2 0] follower [0 0] {:items []}}
                                         :shows {u1 follower}}}))
      "until S2 moves them, grid followers chase too"))
