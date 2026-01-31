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
