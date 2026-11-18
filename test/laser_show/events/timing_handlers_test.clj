(ns laser-show.events.timing-handlers-test
  "Unit tests for timing event handlers.
   
   Tests timing/transport handlers."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.cue-timing :as cue-timing]
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
  (testing "Stop sets playing? false and asks the show clock to restart (its numbers: show-time-test)"
    (let [intents {[0 0] {:run #uuid "00000000-0000-0000-0000-000000000001"}
                   [1 0] {:run #uuid "00000000-0000-0000-0000-000000000002"}}
          state {:playback {:playing? true
                            :active-cue {:id :test}
                            :active-cues intents}}
          result (timing/handle {:event/type :transport/stop :state state})
          restart (get-in result [:state :playback :requests :restart])]
      (is (false? (get-in result [:state :playback :playing?])))
      (is (nil? (get-in result [:state :playback :active-cue])))
      (is (uuid? (:id restart)))
      (is (= intents (get-in result [:state :playback :active-cues])) "the cues keep running (and counting)")
      (is (not= restart (get-in (timing/handle {:event/type :transport/stop :state (:state result)})
                                [:state :playback :requests :restart]))
          "each Stop is a new request"))))


(deftest transport-retrigger-test
  (testing "Retrigger asks the show clock to restart, and changes nothing else"
    (let [state {:playback {:playing? true :active-cues {[0 0] {:run (random-uuid)}}}}
          result (timing/handle {:event/type :transport/retrigger :state state})]
      (is (uuid? (get-in result [:state :playback :requests :restart :id])))
      (is (= state (update (:state result) :playback dissoc :requests))))))


(deftest timing-tap-tempo-phase-alignment-test
  (testing "Tap tempo asks the show clock to align to the nearest beat (its numbers: show-time-test)"
    (let [state {:timing {:tap-times []} :playback {:active-cues {[0 0] {:run (random-uuid)}}}}
          result (timing/handle {:event/type :timing/tap-tempo :state state :time 2000})
          align (get-in result [:state :playback :requests :align])]
      (is (uuid? (:id align)))
      (is (= [2000] (get-in result [:state :timing :tap-times])))
      (is (= (get-in state [:playback :active-cues]) (get-in result [:state :playback :active-cues])))
      (is (not= align (get-in (timing/handle {:event/type :timing/tap-tempo :state (:state result) :time 2500})
                              [:state :playback :requests :align]))
          "every tap aligns again"))))

(deftest global-clock-smooth-decay-test
  (testing "update-global-clock smoothly glides phase-offset toward target across frames"
    (let [initial-clock {:accumulated-beats 10.0
                         :accumulated-ms 5000.0
                         :phase-offset 0.0
                         :phase-offset-target 0.25
                         :last-frame-time 1000}
          ;; Advance 1 beat (500ms at 120 BPM) with resync-rate 4.0
          frame-1 (cue-timing/update-global-clock initial-clock 1500 120.0 4.0)]
      ;; Accumulated beats increased by 1 beat
      (is (< (Math/abs (- 11.0 (:accumulated-beats frame-1))) 0.001))
      ;; Phase offset decayed towards 0.25 smoothly, not 0.0 and not snapped to 0.25 yet
      (is (> (:phase-offset frame-1) 0.0))
      (is (< (:phase-offset frame-1) 0.25))
      ;; Effective beats is continuous
      (is (= (:effective-beats frame-1)
             (+ (:accumulated-beats frame-1) (:phase-offset frame-1)))))))
