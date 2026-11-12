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
