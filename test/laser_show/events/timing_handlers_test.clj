(ns laser-show.events.timing-handlers-test
  "Unit tests for timing event handlers.
   
   Tests timing/transport handlers."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.triggers.tap-tempo :as tap-tempo]))


(deftest timing-set-bpm-test
  (testing "Set BPM updates state"
    (let [state {:timing {:bpm 120.0}}
          event {:event/type :timing/set-bpm
                 :bpm 140
                 :state state}
          result (timing/handle event)]
      (is (= 140.0 (get-in result [:state :timing :bpm])))
      (is (double? (get-in result [:state :timing :bpm]))))))


(defn- taps
  "[:timing :tap] after taps at times (ms), as triggers.tap-tempo keeps them."
  [times]
  (reduce tap-tempo/tap (tap-tempo/make) times))

(deftest timing-tap-tempo-test
  (testing "Tap tempo records timestamp"
    (let [state {:timing {:bpm 90.0 :tap (tap-tempo/make)}}
          event {:event/type :timing/tap-tempo
                 :state state
                 :time 1000}
          result (timing/handle event)]
      (is (= [1000] (get-in result [:state :timing :tap :taps])))
      (is (= 90.0 (get-in result [:state :timing :bpm])) "one tap makes no tempo")
      (is (= [:state] (keys result)) "the BPM needs no effect after the handler")))
  
  (testing "Multiple taps within 1.5 seconds are accumulated, and the BPM is in the same state"
    (let [state {:timing {:bpm 90.0 :tap (taps [1000])}}
          event {:event/type :timing/tap-tempo
                 :state state
                 :time 1500}
          result (timing/handle event)]
      (is (= [1000 1500] (get-in result [:state :timing :tap :taps])))
      (is (= 120.0 (get-in result [:state :timing :bpm])))))
  
  (testing "Tap after more than 1.5 seconds clears old taps, and leaves the BPM as it is"
    (let [state {:timing {:bpm 90.0 :tap (taps [1000 1500])}}
          event {:event/type :timing/tap-tempo
                 :state state
                 :time 4000}  ;; 2.5 seconds after last tap
          result (timing/handle event)]
      (is (= [4000] (get-in result [:state :timing :tap :taps])))
      (is (= 90.0 (get-in result [:state :timing :bpm])) "set since the taps by the BPM box, say")))
  
  (testing "Tap just past the 1.5 second boundary clears old taps"
    (let [state {:timing {:tap (taps [1000 1500])}}
          event {:event/type :timing/tap-tempo
                 :state state
                 :time 3001}  ;; 1501 ms after last tap
          result (timing/handle event)]
      (is (= [3001] (get-in result [:state :timing :tap :taps])))))
  
  (testing "Tap at 1.5 seconds keeps old taps"
    (let [state {:timing {:tap (taps [1000 1500])}}
          event {:event/type :timing/tap-tempo
                 :state state
                 :time 3000}  ;; 1500 ms after last tap
          result (timing/handle event)]
      (is (= [1000 1500 3000] (get-in result [:state :timing :tap :taps])))
      (is (= 60.0 (get-in result [:state :timing :bpm])) "intervals 500 and 1500: the median is 1000 ms"))))


(defn- tap [state t]
  (:state (timing/handle {:event/type :timing/tap-tempo :state state :time t})))

(deftest tap-history-keeps-the-last-5-test
  (let [times (vec (range 1000 11000 500))
        states (reductions tap {:timing {:tap (tap-tempo/make)}} times)
        kept (get-in (last states) [:timing :tap :taps])]
    (is (= 20 (count times)))
    (is (every? #(<= (count (get-in % [:timing :tap :taps])) 5) states)
        "HIT taps on every press: the history mustn't grow for a whole set")
    (is (= (subvec times 15) kept) "the last 5, all the median reads")
    (testing "20 steady taps give the BPM all of them give"
      (is (= 120.0 (get-in (last states) [:timing :bpm]) (tap-tempo/beats-per-minute times))))
    (testing "and so does a tempo that drifts"
      (let [drifting (vec (reductions + 1000 (range 400 600 10)))]
        (is (= (tap-tempo/beats-per-minute drifting)
               (get-in (reduce tap {:timing {:tap (tap-tempo/make)}} drifting) [:timing :bpm])))))))

(deftest bounced-tap-requests-no-align-test
  (let [s1 (tap {:timing {:tap (tap-tempo/make)} :playback {}} 2000)
        s2 (tap s1 2050)]
    (is (uuid? (get-in s1 [:playback :requests :align :id])))
    (is (identical? s1 s2) "50 ms after the last tap: a bounce, which moves neither the tempo nor the beat phase")
    (is (not= (get-in s1 [:playback :requests :align]) (get-in (tap s1 2100) [:playback :requests :align]))
        "100 ms after it: a tap, which aligns")))

(deftest input-time-wins-over-time-test
  (let [state {:timing {:bpm 90.0 :tap (tap-tempo/make)}}
        handle #(:state (timing/handle (assoc %2 :event/type :timing/tap-tempo :state %1)))
        s1 (handle state {:time 5000 :input-time 1000})
        s2 (handle s1 {:time 5100 :input-time 1500})]
    (is (= [1000 1500] (get-in s2 [:timing :tap :taps])) "the input's own time, not when the tap was handled")
    (is (= 120.0 (get-in s2 [:timing :bpm])) "handled 100 ms apart, pressed 500 ms apart")
    (is (= [1000 1500 2000] (get-in (handle s2 {:time 2000}) [:timing :tap :taps])) "with no input time, the pipeline's")))


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
    (let [state {:timing {:tap (tap-tempo/make)} :playback {:active-cues {[0 0] {:run (random-uuid)}}}}
          result (timing/handle {:event/type :timing/tap-tempo :state state :time 2000})
          align (get-in result [:state :playback :requests :align])]
      (is (uuid? (:id align)))
      (is (= [2000] (get-in result [:state :timing :tap :taps])))
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
