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

(defn- overlap?
  "Do shows a and b's ranges overlap, checked both ways round (it must be
   symmetric)?"
  [a b]
  (let [ra (shows/tc-range a)
        rb (shows/tc-range b)
        ab (shows/tc-overlap? ra rb)]
    (is (= ab (shows/tc-overlap? rb ra)) "symmetric")
    ab))

(deftest overlap-examples-test
  (testing "07 §7.2, every row, from show maps"
    (is (not (overlap? (timed 120.0 600.0 0.0) (timed 120.0 600.0 300.0))) "[0, 300) and [300, 600): the ends touch")
    (is (overlap? (timed 120.0 600.0 0.0) (timed 120.0 600.02 299.99)) "[0, 300) and [299.99, 600)")
    (let [a (timed 120.0 600.00000000001 0.0)]
      (is (< 300.0 (:end (shows/tc-range a))))
      (is (not (overlap? a (timed 120.0 600.0 300.0))) "inside the 1 µs tolerance"))
    (let [a (timed 122.7 656.0 3600.0 :timeline/tc-fps 25)
          end (:end (shows/tc-range a))]
      (is (< 3920.78 end 3920.79))
      (is (= "01:05:20:20" (tc/format-tc (tc/seconds->tc-ceil end 25))) "TC end shown as the ceiling")
      (is (not (overlap? a (timed 122.7 100.0 (tc-at "01:05:20:20" 25) :timeline/tc-fps 25)))
          "a start typed as the shown end")
      (is (overlap? a (timed 122.7 100.0 (tc-at "01:05:20:19" 25) :timeline/tc-fps 25))
          "the floor, which a floored display would invite")
      (is (overlap? a (timed 122.7 100.0 (tc-at "01:05:20:20" 30) :timeline/tc-fps 30))
          "the same digits at 30 fps are another time")
      (is (= {:reason :overlap :with u1 :range (shows/tc-range a)}
             (shows/timecode-problem {u1 (assoc a :timeline/chase :mtc)
                                      u2 (timed 122.7 100.0 (tc-at "01:05:20:20" 30) :timeline/tc-fps 30)}
                                     u2))
          "the problem carries A's range, at A's 25 fps"))
    (let [df (timed 128.0 64.0 (tc-at "01:00:00;00" 29.97) :timeline/tc-fps 29.97)]
      (is (< (Math/abs (- 3600.0 (:start (shows/tc-range df)))) 1e-6))
      (is (< (Math/abs (- 3630.0 (:end (shows/tc-range df)))) 1e-6))
      (is (not (overlap? df (timed 128.0 64.0 (tc-at "01:00:30:00" 30)))) "drop-frame maps to real seconds"))
    (let [late (timed 120.0 240.0 (tc-at "23:59:00:00" 30))]
      (is (= [86340.0 86460.0] ((juxt :start :end) (shows/tc-range late))))
      (is (not (overlap? late (timed 120.0 120.0 0.0))) "linear seconds, no 24 h wrap"))
    (is (not (overlap? (timed 128.0 64.0 0.0 :loop {:enabled? true :start 0.0 :duration 4.0})
                       (timed 128.0 64.0 30.0)))
        "the chase ignores the loop"))
  (testing "containment and equal ranges overlap"
    (is (overlap? (timed 120.0 200.0 0.0) (timed 120.0 20.0 10.0)))
    (is (overlap? (timed 120.0 20.0 10.0) (timed 120.0 20.0 10.0)))))

(def u3 #uuid "00000000-0000-0000-0000-000000000003")

(defn- on [show] (assoc show :timeline/chase :mtc))

(deftest timecode-problem-examples-test
  (testing "01 §4.2"
    (is (nil? (shows/timecode-problem {u1 (on (timed 120.0 600.0 0.0)) u2 (timed 120.0 600.0 300.0)} u2))
        "A on [0, 300), B [300, 600): the ends touch")
    (let [a (on (timed 120.0 600.0 0.0))]
      (is (= {:reason :overlap :with u1 :range {:start 0.0 :end 300.0 :fps 30}}
             (shows/timecode-problem {u1 a u2 (timed 120.0 602.0 299.0)} u2))
          "A on [0, 300), B [299, 600): overlaps A"))
    (let [a (timed 120.0 600.0 0.0)
          b (timed 120.0 600.0 100.0)]
      (is (nil? (shows/timecode-problem {u1 a u2 b} u1)) "enable A and B [100, 400): A first, nothing on")
      (is (= u1 (:with (shows/timecode-problem {u1 (on a) u2 b} u2))) "then B against A, on"))
    (is (= {:reason :no-bpm} (shows/timecode-problem {u3 (dissoc (timed 120.0 64.0 0.0) :timeline/tempo-bpm)} u3))
        "C, tempo unlocked: needs a BPM"))
  (testing "the other reasons"
    (is (= {:reason :empty} (shows/timecode-problem {u1 (timed 120.0 64.0 0.0 :items [])} u1)))
    (is (= {:reason :empty} (shows/timecode-problem {u1 (timed 120.0 64.0 0.0 :timeline/length 0.0)} u1)))
    (is (= {:reason :no-bpm} (shows/timecode-problem {u1 (dissoc (timed 120.0 64.0 0.0 :items []) :timeline/tempo-bpm)} u1))
        "no BPM is said before empty")
    (is (nil? (shows/timecode-problem {} u1)) "no such show"))
  (testing "only other shows that follow"
    (is (nil? (shows/timecode-problem {u1 (timed 120.0 600.0 0.0) u2 (timed 120.0 600.0 0.0)} u2))
        "an overlapping show that doesn't follow")
    (is (nil? (shows/timecode-problem {u1 (on (timed 120.0 600.0 0.0))} u1)) "itself, following")
    (is (= {:reason :overlap :with u1 :range {:start 0.0 :end 300.0 :fps 30}}
           (shows/timecode-problem {u1 (on (timed 120.0 600.0 0.0)) u2 (on (timed 120.0 600.0 0.0))
                                    u3 (timed 120.0 600.0 0.0)}
                                   u3))
        "the first in (str id) order")))

(deftest invalid-timecode-ids-test
  (let [a u1 b u2 c u3]
    (testing "07 §7.3"
      (is (= [[a {:reason :overlap :with b :range {:start 300.0 :end 600.0 :fps 30}}]]
             (shows/invalid-timecode-ids {a (on (timed 120.0 604.0 0.0)) b (on (timed 120.0 600.0 300.0))} #{a}))
          "A [0, 302) now overlaps B [300, 600)")
      (is (= [[a {:reason :no-bpm}]]
             (shows/invalid-timecode-ids {a (on (dissoc (timed 120.0 600.0 0.0) :timeline/tempo-bpm))} #{a}))
          "A on, tempo removed")
      (is (= [] (shows/invalid-timecode-ids {a (on (timed 120.0 600.0 0.0)) b (timed 120.0 600.0 100.0)} #{b}))
          "B is off: never checked")
      (is (= [] (shows/invalid-timecode-ids {a (on (timed 120.0 600.0 0.0 :artist "Daft Punk"))
                                             b (on (timed 120.0 600.0 300.0))}
                                            #{a}))
          "only A's Artist edited")
      (is (neg? (compare (str a) (str b))))
      (is (= [[a {:reason :overlap :with c :range {:start 0.0 :end 100.0 :fps 30}}]]
             (shows/invalid-timecode-ids {c (on (timed 120.0 200.0 0.0))
                                          a (on (timed 120.0 200.0 50.0))
                                          b (on (timed 120.0 200.0 120.0))}
                                         #{a b}))
          "A is dropped from the kept set, so B meets only C, and passes"))
    (testing "a changed show that passes joins the kept set"
      (is (= [[b {:reason :overlap :with a :range {:start 50.0 :end 150.0 :fps 30}}]]
             (shows/invalid-timecode-ids {a (on (timed 120.0 200.0 50.0)) b (on (timed 120.0 200.0 120.0))} #{a b}))))
    (testing "nothing to check"
      (is (= [] (shows/invalid-timecode-ids {a (on (timed 120.0 604.0 0.0)) b (on (timed 120.0 600.0 300.0))} #{}))
          "nothing changed")
      (is (= [] (shows/invalid-timecode-ids {a (on (timed 120.0 600.0 0.0))} #{b})) "a deleted show")
      (is (= [] (shows/invalid-timecode-ids {} nil))))
    (testing "an unchanged show that breaks the rule is not this edit's"
      (is (= [] (shows/invalid-timecode-ids {a (on (timed 120.0 600.0 0.0 :items []))
                                             b (on (timed 120.0 600.0 300.0))}
                                            #{b}))))))

(deftest invariant-violations-test
  (let [a u1 b u2 c u3]
    (is (= [] (shows/invariant-violations {})))
    (is (= [] (shows/invariant-violations {a (on (timed 120.0 600.0 0.0)) b (on (timed 120.0 600.0 300.0))}))
        "the ends touch")
    (is (= [] (shows/invariant-violations {a (on (timed 120.0 600.0 0.0)) b (timed 120.0 600.0 100.0)
                                           c (dissoc (timed 120.0 1.0 0.0) :timeline/tempo-bpm)}))
        "shows that don't follow aren't held to it")
    (is (= [[b {:reason :overlap :with a :range {:start 0.0 :end 300.0 :fps 30}}]]
           (shows/invariant-violations {a (on (timed 120.0 600.0 0.0)) b (on (timed 120.0 600.0 100.0))})))
    (is (= [[a {:reason :no-bpm}] [b {:reason :empty}]]
           (shows/invariant-violations {a (on (dissoc (timed 120.0 600.0 0.0) :timeline/tempo-bpm))
                                        b (on (timed 120.0 600.0 0.0 :items []))})))
    (is (= [[b {:reason :overlap :with a :range {:start 0.0 :end 100.0 :fps 30}}]
            [c {:reason :overlap :with a :range {:start 0.0 :end 100.0 :fps 30}}]]
           (shows/invariant-violations {a (on (timed 120.0 200.0 0.0))
                                        b (on (timed 120.0 20.0 10.0))
                                        c (on (timed 120.0 20.0 30.0))}))
        "a range that holds two: each is caught, though B and C don't meet")))
