(ns laser-show.animation.time-test
  "Elapsed time and once-mode progress (plans/2026-11-17-modulator-time-base.md §7)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.time :as time]))

(defn- near? [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 1e-9))

(deftest elapsed-examples-test
  (testing "the raw accumulators: beats without the phase glide, or ms"
    (is (near? 2.0 (time/elapsed {:accumulated-beats 2.0 :accumulated-ms 1000.0
                                  :phase-offset 0.4 :effective-beats 2.4}
                                 :beats)))
    (is (near? 1.0 (time/elapsed {:accumulated-beats 2.0 :accumulated-ms 1000.0} :seconds))))
  (testing "a wall-clock trigger time next to the accumulators is ignored"
    (is (near? 2.0 (time/elapsed {:accumulated-beats 2.0 :accumulated-ms 1000.0 :time-ms 1000.0
                                  :trigger-time 1794905667401}
                                 :beats))))
  (testing "without accumulators: time-ms - trigger-time, as beats at the context's BPM"
    (is (near? 1.0 (time/elapsed {:time-ms 10500 :trigger-time 10000 :bpm 120.0} :beats)))
    (is (near? 0.5 (time/elapsed {:time-ms 10500 :trigger-time 10000} :seconds)))
    (is (near? 2.0 (time/elapsed {:time-ms 1000 :bpm 120.0} :beats)) "no trigger time counts from 0")))

(deftest once-progress-examples-test
  (let [grid-cue {:accumulated-beats 2.0 :accumulated-ms 1000.0 :phase-offset 0.0
                  :effective-beats 2.0 :time-ms 1000.0 :bpm 120.0}]
    (is (near? 0.5 (time/calculate-once-progress grid-cue 4.0 :beats)))
    (is (near? 1.0 (time/calculate-once-progress (assoc grid-cue :accumulated-beats 4.0) 4.0 :beats)))
    (is (near? 1.0 (time/calculate-once-progress (assoc grid-cue :accumulated-beats 9.0) 4.0 :beats))
        "done, and held")
    (is (near? 0.5 (time/calculate-once-progress (assoc grid-cue :bpm 60.0) 4.0 :beats))
        "2 beats played are 2 beats, whatever the tempo is now")
    (is (near? 0.0 (time/calculate-once-progress (assoc grid-cue :accumulated-beats 0.0 :accumulated-ms 0.0)
                                                 4.0 :beats))
        "retriggered: back to the start")
    (is (near? 0.25 (time/calculate-once-progress {:accumulated-ms 500.0 :accumulated-beats 99.0} 2.0 :seconds)))
    (is (near? 0.5 (time/calculate-once-progress {:time-ms 1000 :trigger-time 0 :bpm 120.0} 4.0 :beats))
        "a hand-built context without accumulators, as today")
    (is (near? 1.0 (time/calculate-once-progress grid-cue 0.0 :beats)) "a zero duration is done")))
