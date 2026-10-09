(ns laser-show.input.trigger-test
  "Unit tests for external trigger mapping, conversion, and learning."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.input.events :as events]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.events.handlers.input :as input-handlers]))

(deftest midi-conversion-test
  (testing "MIDI note-on conversion creates unified event"
    (let [msg {:status 144 :channel 1 :note 60 :velocity 100}
          event (midi/midi-msg->event msg)]
      (is (= :note-on (:type event)))
      (is (= :midi (:source event)))
      (is (= 1 (:channel event)))
      (is (= 60 (:note event)))
      (is (> (:velocity event) 0.0))))

  (testing "MIDI note-off conversion"
    (let [msg {:status 128 :channel 0 :note 48 :velocity 0}
          event (midi/midi-msg->event msg)]
      (is (= :note-off (:type event)))
      (is (= 48 (:note event))))))

(deftest osc-conversion-test
  (testing "Mapped OSC address conversion"
    (let [mappings {"/play" {:type :trigger :id :play}}
          msg {:path "/play" :args [1.0]}
          evts (osc/osc-msg->events msg mappings)]
      (is (= 1 (count evts)))
      (let [evt (first evts)]
        (is (= :trigger (:type evt)))
        (is (= :osc (:source evt)))
        (is (= :play (:id evt)))
        (is (= :pressed (:state evt))))))

  (testing "Unmapped OSC address creates dynamic trigger event"
    (let [mappings {}
          msg {:path "/custom/button/1" :args [1.0]}
          evts (osc/osc-msg->events msg mappings)]
      (is (= 1 (count evts)))
      (let [evt (first evts)]
        (is (= :trigger (:type evt)))
        (is (= :osc (:source evt)))
        (is (= "/custom/button/1" (:id evt)))
        (is (= :pressed (:state evt))))))

  (testing "Unmapped OSC address with 0 value creates release event"
    (let [mappings {}
          msg {:path "/custom/button/1" :args [0.0]}
          evts (osc/osc-msg->events msg mappings)]
      (is (= 1 (count evts)))
      (let [evt (first evts)]
        (is (= :trigger-release (:type evt)))
        (is (= :osc (:source evt)))
        (is (= "/custom/button/1" (:id evt)))))))

(deftest input-handler-state-test
  (testing "Start and cancel learning state transformations"
    (let [initial-state {:config {:input {:trigger-map {}}}
                         :backend {:input {}}}
          target-action {:type :trigger-cue :target [1 2]}
          started (input-handlers/handle {:event/type :input/start-learn
                                          :state initial-state
                                          :target-action target-action})
          started-state (:state started)]
      (is (= target-action (get-in started-state [:backend :input :learning-target])))

      (let [canceled (input-handlers/handle {:event/type :input/cancel-learn
                                             :state started-state})
            canceled-state (:state canceled)]
        (is (nil? (get-in canceled-state [:backend :input :learning-target]))))))

  (testing "Remove trigger removes entry from trigger-map"
    (let [key1 {:source :midi :channel 0 :note 60}
          key2 {:source :osc :id "/play"}
          initial-state {:config {:input {:trigger-map {key1 {:type :trigger-cue :target [0 0]}
                                                        key2 {:type :trigger-cue :target [0 1]}}}}}
          res (input-handlers/handle {:event/type :input/remove-trigger
                                      :state initial-state
                                      :trigger-key key1})
          res-map (get-in (:state res) [:config :input :trigger-map])]
      (is (nil? (get res-map key1)))
      (is (some? (get res-map key2)))))

  (testing "Clear cell triggers removes all triggers for target cell"
    (let [key1 {:source :midi :channel 0 :note 60}
          key2 {:source :osc :id "/cue1"}
          key3 {:source :midi :channel 0 :note 62}
          initial-state {:config {:input {:trigger-map {key1 {:type :trigger-cue :target [0 0]}
                                                        key2 {:type :trigger-cue :target [0 0]}
                                                        key3 {:type :trigger-cue :target [1 1]}}}}}
          res (input-handlers/handle {:event/type :input/clear-cell-triggers
                                      :state initial-state
                                      :target-action {:type :trigger-cue :target [0 0]}})
          res-map (get-in (:state res) [:config :input :trigger-map])]
      (is (nil? (get res-map key1)))
      (is (nil? (get res-map key2)))
      (is (= {:type :trigger-cue :target [1 1]} (get res-map key3))))))

