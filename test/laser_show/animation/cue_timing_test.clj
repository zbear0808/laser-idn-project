(ns laser-show.animation.cue-timing-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.cue-timing :as ct]))

(def ^:private no-loop {:enabled? false :start 0.0 :duration 4.0})

(defn- timeline-at [beats]
  {:timeline? true :accumulated-beats beats :accumulated-ms 0.0
   :phase-offset 0.0 :phase-offset-target 0.0 :last-frame-time 0})

(defn- tick
  "Advance by delta-beats at bpm (120 unless given)."
  ([t delta-beats cfg loop] (tick t delta-beats cfg loop 120.0))
  ([t delta-beats cfg loop bpm]
   (ct/update-cue-timing t (long (* delta-beats (/ 60000.0 bpm))) bpm 4.0 loop cfg)))

(deftest timeline-update-examples-test
  (testing "plan §7.1"
    (let [r (tick (timeline-at 15.9) 0.2 {:length 16.0} no-loop)]
      (is (= 16.0 (:accumulated-beats r)))
      (is (true? (:ended? r))))
    (let [r (tick (timeline-at 15.9) 0.2 {:length 32.0} {:enabled? true :start 0.0 :duration 16.0})]
      (is (< (Math/abs (- 0.1 (:accumulated-beats r))) 1e-9) "loops instead of ending")
      (is (not (:ended? r))))
    (let [r (tick (assoc (timeline-at 7.0) :paused? true) 0.5 {:length 32.0} no-loop)]
      (is (= 7.0 (:accumulated-beats r)) "paused cues don't move"))
    (let [t (assoc (timeline-at 3.3) :phase-offset-target 0.4)]
      (is (= 0.0 (:phase-offset (tick t 0.5 {:length 32.0} no-loop))) "no phase glide"))
    (testing "a locked tempo is the bpm the cue advances at"
      (let [r (ct/update-cue-timing (timeline-at 0.0) 500 120.0 4.0 no-loop {:length 32.0})]
        (is (< (Math/abs (- 1.0 (:accumulated-beats r))) 1e-9)))))
  (testing "an ended cue stays put"
    (let [r (tick (assoc (timeline-at 16.0) :ended? true) 1.0 {:length 16.0} no-loop)]
      (is (= 16.0 (:accumulated-beats r))))))

(deftest timeline-cue-ignores-phase-alignment-test
  (let [t (assoc (timeline-at 3.3) :phase-offset 0.0)]
    (is (identical? t (ct/align-cue-timing-phase t))))
  (is (not= 0.0 (:phase-offset-target (ct/align-cue-timing-phase
                                       {:accumulated-beats 3.3 :phase-offset 0.0 :phase-offset-target 0.0})))
      "other cues still align"))

(deftest timing-context-is-relative-test
  (let [t0 (System/currentTimeMillis)
        t (-> (ct/start-timing t0 0.0 {})
              (ct/update-cue-timing t0 120.0 4.0 no-loop)
              (ct/update-cue-timing (+ t0 1000) 120.0 4.0 no-loop))
        ctx (ct/get-cue-timing-context t 120.0)]
    (is (not (contains? ctx :trigger-time)) "no wall-clock time in a timing context")
    (is (= 1000.0 (:accumulated-ms ctx)))
    (is (< (Math/abs (- 2.0 (:accumulated-beats ctx))) 1e-9))
    (is (not (contains? (ct/get-global-timing-context {:accumulated-beats 1.0 :accumulated-ms 500.0} 120.0)
                        :trigger-time))))
  (testing "missing keys count as 0 instead of throwing"
    (is (= 0.0 (:effective-beats (ct/get-cue-timing-context {} 120.0))))))

(deftest update-cue-timing-is-nil-safe-test
  (testing "a timing map with only :last-frame-time advances instead of throwing"
    (let [r (ct/update-cue-timing {:last-frame-time 0} 500 120.0 4.0 no-loop)]
      (is (< (Math/abs (- 1.0 (:accumulated-beats r))) 1e-9))
      (is (= 500.0 (double (:accumulated-ms r))))))
  (testing "a missing resync rate uses the default"
    (is (some? (ct/update-cue-timing {:accumulated-beats 0.0 :accumulated-ms 0.0 :phase-offset 0.0
                                      :phase-offset-target 0.3 :last-frame-time 0}
                                     500 120.0 nil no-loop)))))

(deftest phase-glide-is-the-same-for-both-clocks-test
  (let [start {:accumulated-beats 0.0 :accumulated-ms 0.0 :phase-offset 0.0
               :phase-offset-target 0.25 :last-frame-time 0}
        expected (+ 0.25 (* -0.25 (Math/exp -0.25)))]
    (testing "1 beat with a resync rate of 4 beats: exponential decay toward the target"
      (is (< (Math/abs (- expected (:phase-offset (ct/update-cue-timing start 500 120.0 4.0 no-loop)))) 1e-12))
      (is (< (Math/abs (- expected (:phase-offset (ct/update-global-clock start 500 120.0 4.0)))) 1e-12)))
    (testing "within 0.02 beats of the target it snaps"
      (let [near (assoc start :phase-offset 0.24)]
        (is (= 0.25 (:phase-offset (ct/update-cue-timing near 500 120.0 4.0 no-loop))))
        (is (= 0.25 (:phase-offset (ct/update-global-clock near 500 120.0 4.0))))))))

(deftest align-phase-targets-test
  (testing "the target moves the phase to the nearest whole beat, the shorter way"
    (doseq [[timing target] [[{:accumulated-beats 3.3 :phase-offset 0.0} -0.3]
                             [{:accumulated-beats 4.7 :phase-offset 0.1} 0.3]
                             [{:effective-beats 2.25 :accumulated-beats 9.0 :phase-offset 0.05} -0.2]]]
      (is (< (Math/abs (- target (:phase-offset-target (ct/align-cue-timing-phase timing)))) 1e-9) (pr-str timing))
      (is (< (Math/abs (- target (:phase-offset-target (ct/align-global-clock-phase timing)))) 1e-9) (pr-str timing))))
  (testing "the global clock starts from zeros when there is none yet"
    (is (= {:accumulated-beats 0.0 :accumulated-ms 0.0 :last-frame-time 0
            :phase-offset 0.0 :phase-offset-target 0.0}
           (ct/align-global-clock-phase nil)))))

(deftest non-timeline-cue-unchanged-test
  (let [t (ct/create-cue-timing-state 0 2.25)
        r (-> t
              (ct/update-cue-timing 0 120.0 4.0 no-loop)
              (ct/update-cue-timing 500 120.0 4.0 no-loop))]
    (is (= 0.25 (:phase-offset-target t)))
    (is (< (Math/abs (- 1.0 (:accumulated-beats r))) 1e-9))
    (is (< 0.0 (:phase-offset r) 0.25) "glides toward the global phase")
    (is (not (:ended? r))))
  (testing "the 5-arity (no timeline cfg) still works and never ends"
    (let [r (ct/update-cue-timing {:accumulated-beats 100.0 :accumulated-ms 0.0 :phase-offset 0.0
                                   :phase-offset-target 0.0 :last-frame-time 0}
                                  500 120.0 4.0 no-loop)]
      (is (not (:ended? r))))))

(deftest activate-deactivate-match-grid-behaviour-test
  (let [r (random-uuid)
        p (ct/activate-cue {:active-cues {}} [1 2] r {:timeline? false})]
    (is (true? (:playing? p)))
    (is (= [1 2] (:active-cell p)))
    (is (= {:run r} (get-in p [:active-cues [1 2]])) "an intent: the show clock starts the run")
    (let [p2 (ct/deactivate-cue p [1 2])]
      (is (false? (:playing? p2)))
      (is (empty? (:active-cues p2)))))
  (testing "timeline intents say where to start"
    (is (= {:run :r :timeline? true :start-beat 8.0}
           (get-in (ct/activate-cue {} [0 0] :r {:timeline? true :start-beat 8}) [:active-cues [0 0]]))))
  (testing "deactivating one of two keeps playing"
    (let [p (-> {} (ct/activate-cue [0 0] :r1 {}) (ct/activate-cue [1 0] :r2 {}) (ct/deactivate-cue [1 0]))]
      (is (true? (:playing? p)))
      (is (= [0 0] (:active-cell p))))))

(deftest deactivation-never-starts-transport-test
  (let [a [0 0]
        b [1 0]
        ia {:run :ra :timeline? true :start-beat 0.0 :seek {:id :s :beat 2.0}}
        ib {:run :rb :decay-beats 1.0 :hold? false}
        two (fn [playing? focus] {:playing? playing? :active-cell focus :active-cues {a ia b ib}})]
    (testing "one of two goes: the gate stays as it was (Stop holds the other), the other intent as it was"
      (doseq [playing? [true false]]
        (is (= {:playing? playing? :active-cell b :active-cues {b ib}} (ct/deactivate-cue (two playing? a) a))
            (str "the focused cell, playing? " playing?))
        (is (= {:playing? playing? :active-cell a :active-cues {a ia}} (ct/deactivate-cue (two playing? a) b))
            (str "another cell keeps the focus, playing? " playing?))))
    (testing "the last one goes: the gate closes, and nothing has the focus"
      (doseq [playing? [true false]]
        (is (= {:playing? false :active-cell nil :active-cues {}}
               (-> (two playing? a) (ct/deactivate-cue b) (ct/deactivate-cue a)))
            (str "playing? " playing?))))
    (testing "no intent at the cell: nothing changes"
      (let [empty-play {:playing? true :active-cell nil :active-cues {}}
            held (two false a)]
        (is (identical? empty-play (ct/deactivate-cue empty-play a)) "Play with no cue stays on")
        (is (identical? held (ct/deactivate-cue held [5 5])) "held cues stay held")
        (is (identical? held (ct/deactivate-cue held nil)))))))

(deftest position-ids-test
  (testing "the ids of the requests that put the cue at cell where it is: the restart's and its own seek's"
    (is (= {:restart nil :seek nil} (ct/position-ids {} [0 0])) "nil before each, never left out")
    (is (= {:restart nil :seek nil} (ct/position-ids {:active-cues {[0 0] {:run :r}}} [0 0])))
    (let [pb {:requests {:restart {:id :k} :align {:id :al} :shift {:id :sh :offset 0.5}}
              :active-cues {[0 0] {:run :r :seek {:id :s1 :beat 2.0}}
                            [1 0] {:run :b :seek {:id :s2 :beat 4.0}}}}]
      (is (= {:restart :k :seek :s1} (ct/position-ids pb [0 0])) "align and shift don't move a timeline")
      (is (= {:restart :k :seek :s2} (ct/position-ids pb [1 0])) "each cell its own seek")
      (is (= {:restart :k :seek nil} (ct/position-ids pb [2 0]))))))

(deftest activate-cue-writes-decay-keys-test
  (is (= {:run :r :decay-beats 1.0 :hold? true}
         (get-in (ct/activate-cue {} [1 0] :r {:decay-beats 1 :hold? true}) [:active-cues [1 0]])))
  (is (= {:run :r :hold? true}
         (get-in (ct/activate-cue {} [6 0] :r {:hold? true}) [:active-cues [6 0]]))
      "a held flash: no decay")
  (is (= {:run :r} (get-in (ct/activate-cue {} [0 0] :r {:decay-beats nil :hold? nil}) [:active-cues [0 0]]))
      "nil keys leave the intent as before"))

(defn- plain-at
  "A plain run at beats, last ticked at time 0."
  [beats extra]
  (merge {:accumulated-beats beats :accumulated-ms 0.0 :phase-offset 0.0
          :phase-offset-target 0.0 :last-frame-time 0}
         extra))

(defn- after
  "The run delta-beats after its last tick, at bpm (120 unless given)."
  ([t delta-beats] (after t delta-beats 120.0))
  ([t delta-beats bpm]
   (ct/update-cue-timing t (+ (:last-frame-time t) (* delta-beats (/ 60000.0 bpm))) bpm 4.0 no-loop)))

(deftest decay-ends-a-plain-run-test
  (testing "probe v7: a plain run counted beats for ever; with :decay-beats it ends once let go"
    (let [r (after (plain-at 0.9 {:decay-beats 1.0 :hold? false}) 0.2)]
      (is (true? (:ended? r)))
      (is (< (Math/abs (- 1.1 (:accumulated-beats r))) 1e-9) "not moved back, unlike a timeline's end"))
    (is (not (:ended? (after (plain-at 0.5 {:decay-beats 1.0 :hold? false}) 0.2))) "not there yet")
    (testing "an ended run stays put"
      (let [r (after (plain-at 1.0 {:decay-beats 1.0 :hold? false :ended? true}) 1.0)]
        (is (= 1.0 (:accumulated-beats r)))))))

(deftest hold-suspends-decay-test
  (let [held (plain-at 0.0 {:decay-beats 1.0 :hold? true})
        r (reduce (fn [t _] (after t 0.4)) held (range 8))]
    (is (< (Math/abs (- 3.2 (:accumulated-beats r))) 1e-9))
    (is (not (:ended? r)) "held: never ends by decay")
    (is (true? (:ended? (after (assoc r :hold? false) 0.01)))
        ":hold? false at beat 3.2: the next tick ends it")))

(deftest decay-follows-tempo-test
  (let [r (after (plain-at 0.0 {:decay-beats 1.0 :hold? false}) 0.5 120.0)]
    (is (not (:ended? r)))
    (is (not (:ended? (after r 0.25 60.0))) "a quarter beat at 60 BPM")
    (is (true? (:ended? (after (after r 0.25 60.0) 0.25 60.0)))
        "0.5 more beats at the slower tempo: 500 ms, not the 250 ms they took at 120")))

(deftest start-timing-test
  (testing "a grid cue starts at 0 and glides toward the global beat phase"
    (is (= {:trigger-time 1000 :accumulated-beats 0.0 :accumulated-ms 0.0 :phase-offset 0.0
            :phase-offset-target 0.5 :last-frame-time 1000}
           (ct/start-timing 1000 3.5 {:timeline? false}))))
  (testing "timeline cues start where asked with no glide"
    (let [t (ct/start-timing 1000 3.5 {:timeline? true :start-beat 8.0})]
      (is (= [true 8.0 0.0 0.0 false]
             ((juxt :timeline? :accumulated-beats :phase-offset :phase-offset-target :paused?) t))))))

(deftest request-seek-test
  (is (= {:run :r :seek {:id :s :beat 4.0}} (ct/request-seek {:run :r} 4 :s)))
  (is (= 0.0 (get-in (ct/request-seek {:run :r} -2.0 :s) [:seek :beat])) "never before beat 0"))

(deftest seek-test
  (let [t (ct/seek (assoc (timeline-at 16.0) :ended? true) 4.0)]
    (is (= 4.0 (:accumulated-beats t)))
    (is (not (contains? t :ended?)))))
