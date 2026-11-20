(ns laser-show.triggers.tap-tempo-test
  "The one tap-tempo algorithm (plans/2026-11-18-vibe-mode/06 §7), with
   beyond-vibe's tests ported (engine/tap_tempo.rs:95-118)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.triggers.tap-tempo :as tap]))

(defn- taps
  "The tap tempo after a tap at each of times (ms), from state (a new one)."
  ([times] (taps (tap/make) times))
  ([state times] (reduce tap/tap state times)))

(deftest calculates-bpm
  (is (= 120.0 (tap/bpm (taps [0 500 1000])))))

(deftest timeout-starts-new-series
  (testing "a gap longer than :reset-ms starts a new series, and keeps the tempo"
    (let [options (assoc tap/default-options :reset-ms 1000)
          state (reduce #(tap/tap %1 %2 options) (tap/make) [0 500 2000])]
      (is (= [2000] (:taps state)))
      (is (= 120.0 (tap/bpm state))))))

(deftest steady-taps-test
  (is (= 120.0 (tap/bpm (taps [0 500 1000 1500]))) "beyond-vibe steady_taps")
  (testing "the last 5 taps count: 4 intervals"
    (let [state (taps (range 0 10000 500))]
      (is (= [7500 8000 8500 9000 9500] (:taps state)))
      (is (= 9500 (:last-tap state)))
      (is (= 120.0 (tap/bpm state)))))
  (testing "clamped to 40-300 BPM"
    (is (= 300.0 (tap/bpm (taps [0 150 300 450]))) "400 BPM")
    (is (= 40.0 (tap/bpm (taps [0 1500 3000]))) "the longest gap that isn't a pause")))

(deftest bounce-is-ignored-test
  (let [before (taps [0 500])]
    (is (identical? before (tap/tap before 505)) "5 ms after the last tap: the state is unchanged")
    (is (identical? before (tap/tap before 599)))
    (is (= [0 500 600] (:taps (tap/tap before 600))) "100 ms after it is a tap")
    (is (identical? before (tap/tap before 400)) "nor does a tap from before the last one count"))
  (is (= 120.0 (tap/bpm (taps [0 500 505 1000 1500]))) "beyond-vibe ignores_bounce"))

(deftest pause-keeps-the-last-tempo-test
  (let [before (taps [0 400 800 1200])
        after (tap/tap before 10000)]
    (is (= 150.0 (tap/bpm before)))
    (is (= 150.0 (tap/bpm after)) "beyond-vibe keeps_last_tempo_after_pause")
    (is (= [10000] (:taps after)) "a new series, which sets the tempo from its second tap")
    (is (= 125.0 (tap/bpm (tap/tap after 10480)))))
  (testing "0 500, a 2.5 s pause, then 3000 3500 4000: 120 throughout"
    (is (= [nil 120.0 120.0 120.0 120.0] (map tap/bpm (rest (reductions tap/tap (tap/make) [0 500 3000 3500 4000]))))))
  (testing "1.5 s is no pause yet"
    (is (= [0 1500] (:taps (taps [0 1500]))))
    (is (= [1501] (:taps (taps [0 1501]))))))

(deftest missed-beat-test
  (is (= 120.0 (tap/bpm (taps [0 500 1500 2000 2500]))) "intervals 500 1000 500 500: the median is 500")
  (testing "an extra tap between beats moves it: the median of four intervals can't undo it"
    (is (= 160.0 (tap/bpm (taps [0 500 750 1000 1500]))))))

(deftest one-tap-changes-nothing-test
  (is (= {:taps [0] :bpm nil :last-tap 0} (taps [0])) "one tap makes no interval")
  (is (= 150.0 (tap/bpm (taps (taps [0 400 800]) [5000]))) "nor does one after a pause"))

(deftest beats-per-minute-test
  (is (= 120.0 (tap/beats-per-minute [0 500 505 1000 1500])))
  (is (= 120.0 (tap/beats-per-minute [0 500 1500 2000 2500])))
  (is (nil? (tap/beats-per-minute [0])))
  (is (nil? (tap/beats-per-minute []))))
