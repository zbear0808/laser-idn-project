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
        t (-> (get-in (ct/activate-cue {} [0 0] t0 0.0 {}) [:active-cues [0 0]])
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
  (let [p (ct/activate-cue {:active-cues {}} [1 2] 1000 3.5 {:timeline? false})]
    (is (true? (:playing? p)))
    (is (= [1 2] (:active-cell p)))
    (is (= 0.5 (get-in p [:active-cues [1 2] :phase-offset-target])))
    (let [p2 (ct/deactivate-cue p [1 2])]
      (is (false? (:playing? p2)))
      (is (empty? (:active-cues p2)))))
  (testing "timeline cues start where asked with no glide"
    (let [t (get-in (ct/activate-cue {} [0 0] 1000 3.5 {:timeline? true :start-beat 8.0}) [:active-cues [0 0]])]
      (is (= [true 8.0 0.0 0.0 false]
             ((juxt :timeline? :accumulated-beats :phase-offset :phase-offset-target :paused?) t)))))
  (testing "deactivating one of two keeps playing"
    (let [p (-> {} (ct/activate-cue [0 0] 0 0.0 {}) (ct/activate-cue [1 0] 0 0.0 {}) (ct/deactivate-cue [1 0]))]
      (is (true? (:playing? p)))
      (is (= [0 0] (:active-cell p))))))

(deftest seek-test
  (let [t (ct/seek (assoc (timeline-at 16.0) :ended? true) 4.0)]
    (is (= 4.0 (:accumulated-beats t)))
    (is (not (contains? t :ended?)))))
