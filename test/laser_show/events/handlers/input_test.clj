(ns laser-show.events.handlers.input-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.handlers.input :as input]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.state.domains :as domains]))

(defn- initial-state []
  (domains/build-initial-state))

(defn- run [state event-type]
  (:state (input/handle {:event/type event-type :state state})))

(deftest midi-enable-disable-test
  (let [disabled (run (initial-state) :input/midi-disable)
        enabled (run disabled :input/midi-enable)]
    (is (false? (midi/enabled? (get-in disabled [:backend :input :midi]))))
    (is (true? (midi/enabled? (get-in enabled [:backend :input :midi]))))
    (is (not (contains? (get-in enabled [:backend :input :midi]) :enabled)))))

(deftest osc-enable-disable-test
  (testing "OSC starts disabled and can be enabled"
    (let [state (initial-state)
          enabled (run state :input/osc-enable)
          disabled (run enabled :input/osc-disable)]
      (is (false? (get-in state [:backend :input :osc :enabled?])))
      (is (true? (osc/enabled? (get-in enabled [:backend :input :osc]))))
      (is (false? (osc/enabled? (get-in disabled [:backend :input :osc]))))
      (is (not (contains? (get-in enabled [:backend :input :osc]) :enabled))))))

(deftest osc-handler-sees-enable-test
  (let [sent (atom [])
        state (atom (initial-state))
        handler (osc/create-osc-handler #(swap! sent conj %)
                                        #(get-in @state [:backend :input :osc]))]
    (handler {:path "/btn" :args [(float 1.0)]})
    (is (empty? @sent))
    (swap! state run :input/osc-enable)
    (handler {:path "/btn" :args [(float 1.0)]})
    (is (= 1 (count @sent)))))

(def ^:private registered-effects
  "Effect keys registered in laser-show.events.core/event-handler that these
   handlers may return."
  #{:state :dispatch :dispatch-later})

(deftest learn-handlers-return-only-registered-effects
  (try
    (doseq [[start cancel] [[:input/midi-start-learn :input/midi-cancel-learn]
                            [:input/osc-start-learn :input/osc-cancel-learn]]]
      (testing (str start)
        (let [started (input/handle {:event/type start :state (initial-state)})
              cancelled (input/handle {:event/type cancel :state (:state started)})]
          (is (every? registered-effects (keys started)))
          (is (every? registered-effects (keys cancelled))))))
    (finally
      (router/clear-handlers!))))

(deftest learn-cancel-releases-waiting-promise
  (try
    (testing "MIDI learn"
      (let [started (:state (input/handle {:event/type :input/midi-start-learn
                                           :state (initial-state)}))
            p (get-in started [:backend :input :midi :learn-mode])]
        (input/handle {:event/type :input/midi-cancel-learn :state started})
        (is (realized? p))
        (is (nil? @p))))
    (testing "generalized learn: cancel and restart release the pending future"
      (let [target {:type :trigger-cue :target [0 0]}
            pending #(deref @#'input/!pending-learn)
            started (:state (input/handle {:event/type :input/start-learn
                                           :state (initial-state)
                                           :target-action target}))
            p1 (pending)
            _ (input/handle {:event/type :input/start-learn
                             :state started
                             :target-action target})
            p2 (pending)]
        (is (realized? p1))
        (is (not (realized? p2)))
        (input/handle {:event/type :input/cancel-learn :state started})
        (is (realized? p2))
        (is (nil? (pending)))))
    (finally
      (router/clear-handlers!))))
