(ns laser-show.events.handlers.triggers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.triggers :as triggers]))

(deftest legacy-cue-trigger-dispatches-grid-event
  (let [input {:type :trigger :source :keyboard :id :space :timestamp 1000}
        state {:config {:input {:trigger-map
                                {{:source :keyboard :id :space}
                                 {:type :trigger-cue :target [2 1]}}}}
               :trigger-runtime {}}
        result (triggers/handle {:event/type :trigger/input
                                 :state state
                                 :input-event input
                                 :time 1000})]
    (is (= :grid/trigger-cell (:event/type (:dispatch result))))
    (is (= 2 (:col (:dispatch result))))
    (is (= 1 (:row (:dispatch result))))
    (is (= #{{:source :keyboard :id :space}}
           (get-in result [:state :trigger-runtime :held-inputs])))))

(deftest trigger-release-removes-held-input
  (let [input {:type :trigger-release :source :keyboard :id :space :timestamp 1100}
        state {:config {:input {:trigger-map {}}}
               :trigger-runtime {:held-inputs #{{:source :keyboard :id :space}}}}
        result (triggers/handle {:event/type :trigger/input
                                 :state state
                                 :input-event input
                                 :time 1100})]
    (is (empty? (get-in result [:state :trigger-runtime :held-inputs])))))

(deftest trigger-group-lock-and-expiry
  (let [state {:triggers {:settings {:group-duration-secs 1}}
               :trigger-runtime {:active-groups
                                 {:rhythm {:state :active :entered-at 0}}}}
        armed (:state (triggers/handle {:event/type :trigger/tick
                                        :state state
                                        :time 1000}))
        locked (:state (triggers/handle {:event/type :trigger-group/toggle-lock
                                         :state armed
                                         :group-id :rhythm}))]
    (is (= :armed (get-in armed [:trigger-runtime :active-groups :rhythm :state])))
    (is (= :locked (get-in locked [:trigger-runtime :active-groups :rhythm :state])))))

(def ^:private note-key {:source :midi :channel 0 :note 60})

(defn- momentary-state [action-type]
  {:config {:input {:trigger-map
                    {note-key {:type action-type :target [0 0] :behavior :momentary}}}}
   :chains {:cue-chains {[0 0] {:items []}}}
   :playback {:active-cues {}}
   :trigger-runtime {}})

(defn- input [state type]
  (triggers/handle {:event/type :trigger/input
                    :state state
                    :input-event {:type type :source :midi :channel 0 :note 60 :timestamp 1}
                    :time 1}))

(deftest momentary-cue-press-and-release
  (let [state (momentary-state :trigger-cue)
        press (:dispatch (input state :note-on))
        release (:dispatch (input state :note-off))]
    (is (= :grid/activate-cell (:event/type press)))
    (is (= :grid/release-cell (:event/type release)))
    (testing "press then release through the routed grid handler starts then stops the cue"
      (let [started (:state (handlers/handle-event (assoc press :state state)))
            stopped (:state (handlers/handle-event (assoc release :state started)))]
        (is (contains? (get-in started [:playback :active-cues]) [0 0]))
        (is (not (contains? (get-in stopped [:playback :active-cues]) [0 0])))))
    (testing "press on an already playing cue keeps it playing (no toggle-off)"
      (let [started (:state (handlers/handle-event (assoc press :state state)))
            again (:state (handlers/handle-event (assoc press :state started)))]
        (is (contains? (get-in again [:playback :active-cues]) [0 0]))))))

(deftest momentary-effect-press-and-release
  (let [state (momentary-state :trigger-effect)
        press (:dispatch (input state :note-on))
        release (:dispatch (input state :note-off))]
    (is (= [:effects/toggle-cell true] [(:event/type press) (:fx/event press)]))
    (is (= [:effects/toggle-cell false] [(:event/type release) (:fx/event release)]))))

(deftest non-momentary-release-does-nothing
  (let [state (assoc-in (momentary-state :trigger-cue)
                        [:config :input :trigger-map note-key :behavior] :retrigger)]
    (is (= :grid/trigger-cell (:event/type (:dispatch (input state :note-on)))))
    (is (nil? (:dispatch (input state :note-off))))))
