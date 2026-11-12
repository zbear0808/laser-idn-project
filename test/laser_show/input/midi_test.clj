(ns laser-show.input.midi-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.input.fixtures :as fx]
            [laser-show.input.midi :as midi]
            [laser-show.input.mtc :as mtc]))

(deftest fixture-shape-test
  (testing "midi-clj leaves :status nil above channel 0, which is why we read :command"
    (is (nil? (:status (fx/note-on 1 60 100))))
    (is (= :note-on (:command (fx/note-on 1 60 100))))))

(deftest note-on-all-channels-test
  (doseq [ch [0 1 15]]
    (testing (str "note-on on channel " ch)
      (let [event (midi/midi-msg->event (fx/note-on ch 60 100))]
        (is (= :note-on (:type event)))
        (is (= :midi (:source event)))
        (is (= ch (:channel event)))
        (is (= 60 (:note event)))
        (is (< 0.78 (:velocity event) 0.79))))))

(deftest note-off-test
  (doseq [ch [0 1 15]]
    (testing (str "note-off on channel " ch)
      (let [event (midi/midi-msg->event (fx/note-off ch 48))]
        (is (= :note-off (:type event)))
        (is (= ch (:channel event)))
        (is (= 48 (:note event))))))
  (testing "note-on with velocity 0 is a note-off"
    (let [event (midi/midi-msg->event (fx/note-on 5 48 0))]
      (is (= :note-off (:type event)))
      (is (= 5 (:channel event))))))

(deftest control-change-test
  (doseq [ch [0 1 15]]
    (testing (str "CC on channel " ch)
      (let [event (midi/midi-msg->event (fx/control-change ch 7 127))]
        (is (= :control-change (:type event)))
        (is (= ch (:channel event)))
        (is (= 7 (:control event)))
        (is (= 1.0 (:value event)))))))

(deftest program-change-test
  (let [event (midi/midi-msg->event (fx/program-change 9 3))]
    (is (= :program-change (:type event)))
    (is (= 9 (:channel event)))
    (is (= 3 (:program event)))))

(deftest unhandled-message-test
  (is (nil? (midi/midi-msg->event
             (fx/midi-msg (fx/short-message javax.sound.midi.ShortMessage/PITCH_BEND 0 0 64))))))

(deftest hand-built-numeric-status-test
  (testing "numeric status bytes still decode, including the channel nibble"
    (let [event (midi/midi-msg->event {:status 0x93 :note 60 :velocity 100})]
      (is (= :note-on (:type event)))
      (is (= 3 (:channel event))))))

(deftest handler-channel-filter-test
  (let [sent (atom [])
        state (atom {:enabled? true :channel-filter #{2} :connected-devices #{"Test Device"}})
        handler (midi/create-midi-handler "Test Device" #(swap! sent conj %) #(deref state))]
    (handler (fx/note-on 2 60 100))
    (handler (fx/note-on 3 60 100))
    (is (= [2] (mapv :channel @sent)))
    (swap! state assoc :enabled? false)
    (handler (fx/note-on 2 61 100))
    (is (= 1 (count @sent)))))

(defn- fake-midi-in
  "A midi-in map shaped like overtone.midi/midi-in's result, with a
   Transmitter and MidiDevice that record what was done to them."
  [calls]
  {:name "Test Device"
   :transmitter (reify javax.sound.midi.Transmitter
                  (setReceiver [_ r] (swap! calls conj [:set-receiver r]))
                  (getReceiver [_] nil)
                  (close [_] (swap! calls conj :close-transmitter)))
   :device (reify javax.sound.midi.MidiDevice
             (isOpen [_] true)
             (close [_] (swap! calls conj :close-device)))})

(deftest disconnect-detaches-and-closes-test
  (let [calls (atom [])
        state {:enabled? true
               :devices {"Test Device" (fake-midi-in calls)}
               :handlers {"Test Device" identity}
               :connected-devices #{"Test Device"}}
        after (midi/disconnect-device state "Test Device")]
    (is (= [[:set-receiver nil] :close-transmitter :close-device] @calls))
    (is (empty? (:devices after)))
    (is (empty? (:handlers after)))
    (is (empty? (:connected-devices after)))))

(deftest disconnect-all-closes-every-device-test
  (let [calls (atom [])
        state {:devices {"A" (fake-midi-in calls) "B" (fake-midi-in calls)}
               :connected-devices #{"A" "B"}}
        after (midi/disconnect-all state)]
    (is (= 2 (count (filter #{:close-device} @calls))))
    (is (empty? (:connected-devices after)))))

(deftest stale-handler-ignored-after-disconnect-test
  (let [sent (atom [])
        state (atom {:enabled? true :connected-devices #{}})
        handler (midi/create-midi-handler "Test Device" #(swap! sent conj %) #(deref state))]
    (handler (fx/note-on 0 60 100))
    (is (empty? @sent))))

(deftest mtc-fixture-shape-test
  (testing "midi-clj 0.5.0's map for 0xF1: no :command, a keyword :status, channel 1"
    (is (= {:channel 1 :command nil :status :midi-time-code :data1 0x25 :data2 0 :note 0x25 :velocity 0}
           (select-keys (fx/mtc-quarter-frame 0x25) [:channel :command :status :data1 :data2 :note :velocity])))))

(deftest mtc-quarter-frame-bypasses-triggers-and-channel-filter-test
  (mtc/reset-decoder!)
  (let [sent (atom [])
        state (atom {:enabled? true :channel-filter #{5} :connected-devices #{"Test Device"}})
        handler (midi/create-midi-handler "Test Device" #(swap! sent conj %) #(deref state))]
    (doseq [b [0x0C 0x10 0x25 0x30 0x40 0x50 0x61 0x72]]
      (handler (fx/mtc-quarter-frame b)))
    (is (empty? @sent) "no trigger events")
    (is (= :running (:state (mtc/current))) "channel 1 isn't in the filter, the decoder still got them")
    (is (= 25 (:fps (mtc/current))))
    (testing "disabled MIDI ignores MTC too"
      (mtc/reset-decoder!)
      (swap! state assoc :enabled? false)
      (doseq [b [0x0C 0x10 0x25 0x30 0x40 0x50 0x61 0x72]]
        (handler (fx/mtc-quarter-frame b)))
      (is (nil? (:seconds (mtc/current)))))
    (mtc/reset-decoder!)))

(deftest sysex-handler-forwards-mtc-full-frame-test
  (mtc/reset-decoder!)
  (let [handler (midi/create-sysex-handler "Test Device" (constantly {:enabled? true :connected-devices #{"Test Device"}}))
        msg (fx/sysex-msg [0xF0 0x7F 0x7F 0x01 0x01 0x21 0 20 0 0xF7])]
    (is (= 0xF0 (:status msg)))
    (handler (fx/sysex-msg [0xF0 0x7E 0x7F 0x06 0x01 0xF7]))
    (is (nil? (:seconds (mtc/current))) "other SysEx is ignored")
    (handler msg)
    (is (= 3620.0 (:seconds (mtc/current))))
    (is (= :stopped (:state (mtc/current)))))
  (mtc/reset-decoder!))
