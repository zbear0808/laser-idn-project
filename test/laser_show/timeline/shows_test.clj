(ns laser-show.timeline.shows-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.shows :as shows]
            [laser-show.timeline.timecode :as tc]))

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

(deftest new-show-test
  (let [show (shows/new-show {:name " One More Time "})]
    (is (= "One More Time" (:name show)))
    (is (= :timeline (:editor-type show)))
    (is (not (contains? show :id)) "its id is only its key")
    (is (not (contains? show :timeline/chase)) "timecode off")
    (is (seq (:tracks show)) "a track to add clips to")
    (is (empty? (:items show)))
    (is (nil? (:loop show)))
    (is (identical? show (model/normalize-timeline-cue show)) "normalized"))
  (is (= "Untitled" (:name (shows/new-show {}))))
  (is (= "Untitled" (:name (shows/new-show {:name "  "}))))
  (is (not= (:tracks (shows/new-show {})) (:tracks (shows/new-show {}))) "fresh track ids"))

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


;; Timecode ranges (07 §4.4, §7.2)

(defn- timed
  "A show of one clip len beats long, at bpm, starting at tc-start."
  [bpm len tc-start & {:as more}]
  (merge {:editor-type :timeline :timeline/tempo-bpm bpm :timeline/tc-start tc-start
          :items [{:id 1 :track-id 1 :timeline/start 0.0 :timeline/duration len}]}
         more))

(defn- tc-at [text fps]
  (tc/tc->seconds (tc/parse-tc text fps) fps))

(deftest tc-range-test
  (is (= {:start 0.0 :end 32.0 :fps 30} (shows/tc-range (timed 120.0 64.0 0.0))))
  (is (= {:start 10.0 :end 42.0 :fps 25} (shows/tc-range (timed 120.0 64.0 10.0 :timeline/tc-fps 25))))
  (is (= 0.0 (:start (shows/tc-range (dissoc (timed 120.0 64.0 0.0) :timeline/tc-start)))) "no TC start: 0")
  (is (= 30.0 (:end (shows/tc-range (timed 120.0 16.0 0.0 :timeline/length 60.0)))) ":timeline/length wins")
  (is (nil? (shows/tc-range (dissoc (timed 120.0 64.0 0.0) :timeline/tempo-bpm))) "no locked tempo")
  (is (nil? (shows/tc-range (timed 120.0 64.0 0.0 :items []))) "empty")
  (is (nil? (shows/tc-range (timed 120.0 64.0 0.0 :timeline/length 0.0))) "length 0")
  (is (nil? (shows/tc-range {})))
  (is (= [0.0 30.0] ((juxt :start :end) (shows/tc-range (timed 128.0 64.0 0.0 :loop {:enabled? true :start 0.0 :duration 4.0}))))
      "the loop is ignored"))

(deftest overlap-examples-test
  (let [r (fn [s e] {:start s :end e})
        a (shows/tc-range (timed 122.7 656.0 3600.0 :timeline/tc-fps 25))]
    (testing "07 §7.2"
      (is (not (shows/tc-overlap? (r 0.0 300.0) (r 300.0 600.0))) "the ends touch")
      (is (not (shows/tc-overlap? (r 300.0 600.0) (r 0.0 300.0))) "either order")
      (is (shows/tc-overlap? (r 0.0 300.0) (r 299.99 600.0)))
      (is (shows/tc-overlap? (r 299.99 600.0) (r 0.0 300.0)))
      (let [a (shows/tc-range (timed 120.0 600.00000000001 0.0))]
        (is (< 300.0 (:end a)))
        (is (not (shows/tc-overlap? a (r 300.0 600.0))) "inside the 1 µs tolerance"))
      (is (< 3920.78 (:end a) 3920.79))
      (is (not (shows/tc-overlap? a (r (tc-at "01:05:20:20" 25) 4000.0))) "the ceiling of A's end at 25 fps")
      (is (shows/tc-overlap? a (r (tc-at "01:05:20:19" 25) 4000.0)) "the floor")
      (is (shows/tc-overlap? a (r (tc-at "01:05:20:20" 30) 4000.0)) "the same digits at 30 fps")
      (let [df (shows/tc-range (timed 128.0 64.0 (tc-at "01:00:00;00" 29.97) :timeline/tc-fps 29.97))]
        (is (< (Math/abs (- 3600.0 (:start df))) 1e-6))
        (is (not (shows/tc-overlap? df (r (tc-at "01:00:30:00" 30) 3700.0))) "drop-frame maps to real seconds"))
      (is (not (shows/tc-overlap? (shows/tc-range (timed 120.0 240.0 (tc-at "23:59:00:00" 30))) (r 0.0 60.0)))
          "no 24 h wrap")
      (is (not (shows/tc-overlap? (shows/tc-range (timed 128.0 64.0 0.0 :loop {:enabled? true :start 0.0 :duration 4.0}))
                                  (r 30.0 60.0)))
          "the chase ignores the loop"))
    (testing "containment and equal ranges overlap"
      (is (shows/tc-overlap? (r 0.0 100.0) (r 10.0 20.0)))
      (is (shows/tc-overlap? (r 10.0 20.0) (r 10.0 20.0))))))
