(ns laser-show.input.events-test
  "Unit tests for the input events system."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.input.events :as events]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]))


;; Event Creation Tests


(deftest control-change-test
  (testing "Creates valid control change event"
    (let [event (events/control-change :midi 0 1 0.5)]
      (is (= :control-change (:type event)))
      (is (= :midi (:source event)))
      (is (= 0 (:channel event)))
      (is (= 1 (:control event)))
      (is (= 0.5 (:value event)))
      (is (number? (:timestamp event)))))
  
  (testing "Clamps values to 0.0-1.0 range"
    (is (= 0.0 (:value (events/control-change :midi 0 1 -0.5))))
    (is (= 1.0 (:value (events/control-change :midi 0 1 1.5))))))

(deftest note-on-test
  (testing "Creates valid note-on event"
    (let [event (events/note-on :midi 0 60 0.8)]
      (is (= :note-on (:type event)))
      (is (= :midi (:source event)))
      (is (= 0 (:channel event)))
      (is (= 60 (:note event)))
      (is (= 0.8 (:velocity event)))))
  
  (testing "Clamps velocity to 0.0-1.0 range"
    (is (= 0.0 (:velocity (events/note-on :midi 0 60 -0.1))))
    (is (= 1.0 (:velocity (events/note-on :midi 0 60 2.0))))))

(deftest note-off-test
  (testing "Creates valid note-off event"
    (let [event (events/note-off :midi 0 60)]
      (is (= :note-off (:type event)))
      (is (= :midi (:source event)))
      (is (= 0 (:channel event)))
      (is (= 60 (:note event)))
      (is (= 0.0 (:velocity event))))))

(deftest trigger-test
  (testing "Creates trigger event for pressed state"
    (let [event (events/trigger :keyboard :play :pressed)]
      (is (= :trigger (:type event)))
      (is (= :keyboard (:source event)))
      (is (= :play (:id event)))
      (is (= :pressed (:state event)))))
  
  (testing "Creates trigger-release event for released state"
    (let [event (events/trigger :keyboard :play :released)]
      (is (= :trigger-release (:type event)))
      (is (= :released (:state event))))))

(deftest program-change-test
  (testing "Creates valid program change event"
    (let [event (events/program-change :midi 0 5)]
      (is (= :program-change (:type event)))
      (is (= :midi (:source event)))
      (is (= 0 (:channel event)))
      (is (= 5 (:program event))))))


;; Event Predicate Tests


(deftest predicate-tests
  (testing "control-change?"
    (is (events/control-change? (events/control-change :midi 0 1 0.5)))
    (is (not (events/control-change? (events/note-on :midi 0 60 1.0)))))
  
  (testing "note-on?"
    (is (events/note-on? (events/note-on :midi 0 60 1.0)))
    (is (not (events/note-on? (events/note-off :midi 0 60)))))
  
  (testing "note-off?"
    (is (events/note-off? (events/note-off :midi 0 60)))
    (is (not (events/note-off? (events/note-on :midi 0 60 1.0)))))
  
  (testing "trigger?"
    (is (events/trigger? (events/trigger :keyboard :play :pressed)))
    (is (not (events/trigger? (events/trigger :keyboard :play :released)))))
  
  (testing "trigger-release?"
    (is (events/trigger-release? (events/trigger :keyboard :play :released)))
    (is (not (events/trigger-release? (events/trigger :keyboard :play :pressed))))))

(deftest source-predicate-tests
  (testing "midi-event?"
    (is (events/midi-event? (events/note-on :midi 0 60 1.0)))
    (is (not (events/midi-event? (events/note-on :keyboard 0 60 1.0)))))
  
  (testing "osc-event?"
    (is (events/osc-event? (events/control-change :osc 0 1 0.5)))
    (is (not (events/osc-event? (events/control-change :midi 0 1 0.5)))))
  
  (testing "keyboard-event?"
    (is (events/keyboard-event? (events/trigger :keyboard :play :pressed)))
    (is (not (events/keyboard-event? (events/trigger :midi :play :pressed))))))


;; Event Matching Tests


(deftest matches-test
  (let [event (events/note-on :midi 0 60 1.0)]

    (testing "Matches by multiple keys"
      (is (events/matches? event {:type :note-on :source :midi :channel 0}))) 

    (testing "Empty pattern matches everything"
      (is (events/matches? event {})))))

(deftest event-id-test
  (testing "Control change event ID"
    (let [event (events/control-change :midi 0 1 0.5)]
      (is (= [:midi :control-change 0 1] (events/event-id event)))))
  
  (testing "Note event ID"
    (let [event (events/note-on :midi 0 60 1.0)]
      (is (= [:midi :note 0 60] (events/event-id event)))))
  
  (testing "Trigger event ID"
    (let [event (events/trigger :keyboard :play :pressed)]
      (is (= [:keyboard :trigger :play] (events/event-id event))))))


;; Value Scaling Tests


(deftest midi-to-normalized-test
  (testing "Converts MIDI values correctly"
    (is (= 0.0 (events/midi-to-normalized 0)))
    (is (= 1.0 (events/midi-to-normalized 127)))
    (is (< 0.49 (events/midi-to-normalized 64) 0.51))))

(deftest normalized-to-midi-test
  (testing "Converts normalized values correctly"
    (is (= 0 (events/normalized-to-midi 0.0)))
    (is (= 127 (events/normalized-to-midi 1.0)))
    ;; 0.5 * 127 = 63.5, truncated to 63
    (is (= 63 (events/normalized-to-midi 0.5)))))

(deftest scale-value-test
  (testing "Scales values to target range"
    (is (= 0.0 (events/scale-value 0.0 0.0 100.0)))
    (is (= 100.0 (events/scale-value 1.0 0.0 100.0)))
    (is (= 50.0 (events/scale-value 0.5 0.0 100.0)))
    (is (= -50.0 (events/scale-value 0.5 -100.0 0.0)))))

(deftest unscale-value-test
  (testing "Unscales values from range to normalized"
    (is (= 0.0 (events/unscale-value 0.0 0.0 100.0)))
    (is (= 1.0 (events/unscale-value 100.0 0.0 100.0)))
    (is (= 0.5 (events/unscale-value 50.0 0.0 100.0)))))


;; MIDI Message Conversion & Normalization Tests

(deftest midi-msg->event-normalization-test
  (testing "MIDI CC messages normalized to 0.0-1.0 float range"
    (let [msg-min {:status 176 :channel 0 :data1 1 :data2 0}
          evt-min (midi/midi-msg->event msg-min)]
      (is (= :control-change (:type evt-min)))
      (is (= :midi (:source evt-min)))
      (is (= 0 (:channel evt-min)))
      (is (= 1 (:control evt-min)))
      (is (= 0.0 (:value evt-min))))

    (let [msg-mid {:status 176 :channel 0 :data1 1 :data2 64}
          evt-mid (midi/midi-msg->event msg-mid)]
      (is (= :control-change (:type evt-mid)))
      (is (= :midi (:source evt-mid)))
      (is (< 0.503 (:value evt-mid) 0.505)))

    (let [msg-max {:status 176 :channel 0 :data1 1 :data2 127}
          evt-max (midi/midi-msg->event msg-max)]
      (is (= :control-change (:type evt-max)))
      (is (= :midi (:source evt-max)))
      (is (= 1.0 (:value evt-max))))

    (let [msg-kw {:status :control-change :channel 2 :data1 7 :data2 127}
          evt-kw (midi/midi-msg->event msg-kw)]
      (is (= :control-change (:type evt-kw)))
      (is (= 2 (:channel evt-kw)))
      (is (= 7 (:control evt-kw)))
      (is (= 1.0 (:value evt-kw))))))


;; OSC Message Conversion & Path Preservation Tests

(deftest osc-msg->events-normalization-and-path-test
  (testing "Mapped OSC faders preserve address path and produce normalized control-change"
    (doseq [[raw-val expected] [[0.0 0.0] [0.5 0.5] [1.0 1.0]]]
      (let [events (osc/osc-msg->events {:path "/fader1" :args [raw-val]} osc/default-mappings)
            evt (first events)]
        (is (= 1 (count events)))
        (is (= :control-change (:type evt)))
        (is (= :osc (:source evt)))
        (is (= 1 (:control evt)))
        (is (= "/fader1" (:path evt)))
        (is (= expected (:value evt))))))

  (testing "Unmapped float messages produce valid continuous events with address preserved"
    (doseq [[raw-val expected] [[0.0 0.0] [0.5 0.5] [1.0 1.0]]]
      (let [events (osc/osc-msg->events {:path "/custom/fader" :args [raw-val]} {})
            evt (first events)]
        (is (= 1 (count events)))
        (is (= :control-change (:type evt)))
        (is (= :osc (:source evt)))
        (is (= "/custom/fader" (:path evt)))
        (is (= expected (:value evt)))))))

