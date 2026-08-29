(ns laser-show.events.timing-handlers-test
  "Unit tests for timing event handlers.
   
   Tests timing/transport handlers."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.timing :as timing]))


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
      (is (true? (:timing/calculate-bpm result)))))
  
  (testing "Multiple taps within 2 seconds are accumulated"
    (let [state {:timing {:tap-times [1000]}}
          event {:event/type :timing/tap-tempo
                 :state state
                 :time 1500}
          result (timing/handle event)]
      (is (= [1000 1500] (get-in result [:state :timing :tap-times])))
      (is (true? (:timing/calculate-bpm result)))))
  
  (testing "Tap after more than 2 seconds clears old taps"
    (let [state {:timing {:tap-times [1000 1500]}}
          event {:event/type :timing/tap-tempo
                 :state state
                 :time 4000}  ;; 2.5 seconds after last tap
          result (timing/handle event)]
      (is (= [4000] (get-in result [:state :timing :tap-times])))
      (is (true? (:timing/calculate-bpm result)))))
  
  (testing "Tap exactly at 2 second boundary clears old taps"
    (let [state {:timing {:tap-times [1000 1500]}}
          event {:event/type :timing/tap-tempo
                 :state state
                 :time 3501}  ;; Exactly 2001ms after last tap
          result (timing/handle event)]
      (is (= [3501] (get-in result [:state :timing :tap-times])))
      (is (true? (:timing/calculate-bpm result)))))
  
  (testing "Tap just under 2 seconds keeps old taps"
    (let [state {:timing {:tap-times [1000 1500]}}
          event {:event/type :timing/tap-tempo
                 :state state
                 :time 3499}  ;; 1999ms after last tap
          result (timing/handle event)]
      (is (= [1000 1500 3499] (get-in result [:state :timing :tap-times])))
      (is (true? (:timing/calculate-bpm result))))))


(deftest transport-play-test
  (testing "Play sets playing? to true"
    (let [state {:playback {:playing? false}}
          event {:event/type :transport/play
                 :state state}
          result (timing/handle event)]
      (is (true? (get-in result [:state :playback :playing?]))))))


(deftest transport-stop-test
  (testing "Stop resets playback state and timing accumulators"
    (let [state {:playback {:playing? true
                            :active-cue {:id :test}
                            :active-cues {[0 0] {:trigger-time 500
                                                 :accumulated-beats 5.0
                                                 :accumulated-ms 2500.0
                                                 :phase-offset 0.3
                                                 :phase-offset-target 0.5
                                                 :last-frame-time 1000}
                                          [1 0] {:trigger-time 600
                                                 :accumulated-beats 3.0
                                                 :accumulated-ms 1500.0
                                                 :phase-offset 0.2
                                                 :phase-offset-target 0.4
                                                 :last-frame-time 900}}}
                 :timing {:global-clock {:accumulated-beats 10.0
                                         :accumulated-ms 5000.0
                                         :last-frame-time 1000}}}
          event {:event/type :transport/stop
                 :state state}
          result (timing/handle event)]
      (is (false? (get-in result [:state :playback :playing?])))
      (is (nil? (get-in result [:state :playback :active-cue])))
      ;; Check global clock was reset
      (is (= 0.0 (get-in result [:state :timing :global-clock :accumulated-beats])))
      (is (= 0.0 (get-in result [:state :timing :global-clock :accumulated-ms])))
      ;; Check active cues were reset
      (let [cue-0-0 (get-in result [:state :playback :active-cues [0 0]])
            cue-1-0 (get-in result [:state :playback :active-cues [1 0]])]
        (is (= 0.0 (:accumulated-beats cue-0-0)))
        (is (= 0.0 (:accumulated-ms cue-0-0)))
        (is (= 0.0 (:phase-offset cue-0-0)))
        (is (= 0.0 (:phase-offset-target cue-0-0)))
        (is (= 0.0 (:accumulated-beats cue-1-0)))
        (is (= 0.0 (:accumulated-ms cue-1-0)))
        (is (= 0.0 (:phase-offset cue-1-0)))
        (is (= 0.0 (:phase-offset-target cue-1-0)))))))


(deftest transport-retrigger-test
  (testing "Retrigger resets global clock and all active cue timing accumulators"
    (let [state {:playback {:active-cues {[0 0] {:trigger-time 500
                                                 :accumulated-beats 5.0
                                                 :accumulated-ms 2500.0
                                                 :phase-offset 0.3
                                                 :phase-offset-target 0.5
                                                 :last-frame-time 1000}
                                          [1 0] {:trigger-time 600
                                                 :accumulated-beats 3.0
                                                 :accumulated-ms 1500.0
                                                 :phase-offset 0.2
                                                 :phase-offset-target 0.4
                                                 :last-frame-time 900}}}
                 :timing {:global-clock {:accumulated-beats 10.0
                                         :accumulated-ms 5000.0
                                         :last-frame-time 1000}}}
          event {:event/type :transport/retrigger
                 :state state}
          result (timing/handle event)]
      ;; Check global clock was reset
      (is (= 0.0 (get-in result [:state :timing :global-clock :accumulated-beats])))
      (is (= 0.0 (get-in result [:state :timing :global-clock :accumulated-ms])))
      ;; Check active cues were reset
      (let [cue-0-0 (get-in result [:state :playback :active-cues [0 0]])
            cue-1-0 (get-in result [:state :playback :active-cues [1 0]])]
        (is (= 0.0 (:accumulated-beats cue-0-0)))
        (is (= 0.0 (:accumulated-ms cue-0-0)))
        (is (= 0.0 (:phase-offset cue-0-0)))
        (is (= 0.0 (:phase-offset-target cue-0-0)))
        (is (= 0.0 (:accumulated-beats cue-1-0)))
        (is (= 0.0 (:accumulated-ms cue-1-0)))
        (is (= 0.0 (:phase-offset cue-1-0)))
        (is (= 0.0 (:phase-offset-target cue-1-0)))))))
