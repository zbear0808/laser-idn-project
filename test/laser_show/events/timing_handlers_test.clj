(ns laser-show.events.timing-handlers-test
  "Unit tests for timing event handlers.
   
   Tests the phase adjustment calculation used in tap tempo
   and the timing/transport handlers."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.timing :as timing]))


;; Test the calculate-phase-adjustment function
;; This is private, so we need to access it via the var
(def calculate-phase-adjustment #'timing/calculate-phase-adjustment)


(deftest calculate-phase-adjustment-test
  (testing "Phase at 0.0 - no adjustment needed"
    (is (= 0.0 (calculate-phase-adjustment 0.0 0.0))))
  
  (testing "Phase at 0.25 - slow down (negative adjustment)"
    (let [adjustment (calculate-phase-adjustment 0.25 0.0)]
      (is (neg? adjustment) "Should be negative to slow down")
      (is (= -0.25 adjustment) "Should adjust by -0.25")))
  
  (testing "Phase at 0.5 - boundary case, slow down"
    (let [adjustment (calculate-phase-adjustment 0.5 0.0)]
      (is (= -0.5 adjustment) "At 0.5, should slow down to reach 0")))
  
  (testing "Phase at 0.75 - speed up (positive adjustment)"
    (let [adjustment (calculate-phase-adjustment 0.75 0.0)]
      (is (pos? adjustment) "Should be positive to speed up")
      (is (= 0.25 adjustment) "Should adjust by +0.25 to reach 1.0 (wraps to 0)")))
  
  (testing "Phase at 0.9 - speed up"
    (let [adjustment (calculate-phase-adjustment 0.9 0.0)]
      (is (pos? adjustment))
      (is (< (Math/abs (- 0.1 adjustment)) 0.001) "Should adjust by ~0.1")))
  
  (testing "Phase with offset - accumulated 0.3, offset 0.2 = 0.5"
    (let [adjustment (calculate-phase-adjustment 0.3 0.2)]
      (is (= -0.5 adjustment) "Combined phase 0.5 should slow down")))
  
  (testing "Phase wrapping - accumulated 1.25 wraps to 0.25"
    (let [adjustment (calculate-phase-adjustment 1.25 0.0)]
      (is (= -0.25 adjustment) "Should handle phase > 1.0")))
  
  (testing "Nil handling - nil accumulated beats"
    (let [adjustment (calculate-phase-adjustment nil 0.3)]
      (is (= -0.3 adjustment) "Should treat nil as 0.0")))
  
  (testing "Nil handling - nil phase offset"
    (let [adjustment (calculate-phase-adjustment 0.7 nil)]
      (is (< (Math/abs (- 0.3 adjustment)) 0.001) "Should treat nil as 0.0")))
  
  (testing "Nil handling - both nil"
    (let [adjustment (calculate-phase-adjustment nil nil)]
      (is (= 0.0 adjustment) "Should return 0.0 for nil inputs"))))


(deftest timing-set-bpm-test
  (testing "Set BPM updates state"
    (let [state {:timing {:bpm 120.0}}
          event {:event/type :timing/set-bpm
                 :bpm 140
                 :state state}
          result (timing/handle event)]
      (is (= 140.0 (get-in result [:state :timing :bpm])))
      (is (double? (get-in result [:state :timing :bpm]))))))


(deftest timing-tap-tempo-test
  (testing "Tap tempo records timestamp"
    (let [state {:timing {:tap-times []}}
          event {:event/type :timing/tap-tempo
                 :state state
                 :time 1000}
          result (timing/handle event)]
      (is (= [1000] (get-in result [:state :timing :tap-times])))
      (is (true? (:timing/calculate-bpm result))))))


(deftest timing-bpm-calculated-test
  (testing "BPM calculated triggers phase resync"
    (let [state {:timing {:bpm 120.0}
                 :playback {:accumulated-beats 0.25
                            :phase-offset 0.0
                            :phase-offset-target 0.0}}
          event {:event/type :timing/bpm-calculated
                 :bpm 130.0
                 :state state}
          result (timing/handle event)]
      (is (= 130.0 (get-in result [:state :timing :bpm])))
      ;; Phase adjustment should be applied to target
      (is (= -0.25 (get-in result [:state :playback :phase-offset-target]))))))


(deftest timing-resync-phase-test
  (testing "Manual resync calculates phase adjustment"
    (let [state {:playback {:accumulated-beats 0.75
                            :phase-offset 0.0
                            :phase-offset-target 0.0}}
          event {:event/type :timing/resync-phase
                 :state state}
          result (timing/handle event)]
      ;; Phase 0.75 should speed up by 0.25
      (is (= 0.25 (get-in result [:state :playback :phase-offset-target]))))))


(deftest timing-clear-taps-test
  (testing "Clear taps empties tap-times"
    (let [state {:timing {:tap-times [100 200 300]}}
          event {:event/type :timing/clear-taps
                 :state state}
          result (timing/handle event)]
      (is (= [] (get-in result [:state :timing :tap-times]))))))


(deftest transport-play-test
  (testing "Play sets playing? to true"
    (let [state {:playback {:playing? false}}
          event {:event/type :transport/play
                 :state state}
          result (timing/handle event)]
      (is (true? (get-in result [:state :playback :playing?]))))))


(deftest transport-stop-test
  (testing "Stop resets playback state"
    (let [state {:playback {:playing? true
                            :active-cell [0 0]
                            :accumulated-beats 5.0
                            :accumulated-ms 2500.0
                            :phase-offset 0.3
                            :phase-offset-target 0.5
                            :last-frame-time 1000}}
          event {:event/type :transport/stop
                 :state state}
          result (timing/handle event)]
      (is (false? (get-in result [:state :playback :playing?])))
      (is (nil? (get-in result [:state :playback :active-cell])))
      (is (= 0.0 (get-in result [:state :playback :accumulated-beats])))
      (is (= 0.0 (get-in result [:state :playback :accumulated-ms])))
      (is (= 0.0 (get-in result [:state :playback :phase-offset])))
      (is (= 0.0 (get-in result [:state :playback :phase-offset-target])))
      (is (= 0 (get-in result [:state :playback :last-frame-time]))))))


(deftest transport-retrigger-test
  (testing "Retrigger resets timing accumulators"
    (let [state {:playback {:trigger-time 500
                            :accumulated-beats 5.0
                            :accumulated-ms 2500.0
                            :phase-offset 0.3
                            :phase-offset-target 0.5
                            :last-frame-time 1000}}
          event {:event/type :transport/retrigger
                 :state state
                 :time 2000}
          result (timing/handle event)]
      (is (= 2000 (get-in result [:state :playback :trigger-time])))
      (is (= 0.0 (get-in result [:state :playback :accumulated-beats])))
      (is (= 0.0 (get-in result [:state :playback :accumulated-ms])))
      (is (= 0.0 (get-in result [:state :playback :phase-offset])))
      (is (= 0.0 (get-in result [:state :playback :phase-offset-target])))
      (is (= 0 (get-in result [:state :playback :last-frame-time]))))))
