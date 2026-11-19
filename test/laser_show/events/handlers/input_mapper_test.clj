(ns laser-show.events.handlers.input-mapper-test
  "The input mapper: discrete inputs go to the trigger map as :trigger/input,
   continuous ones to the input store, and a learn keeps the input it
   catches (K-15)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))

(defn- input [type & {:as m}]
  (merge {:type type :source :midi :channel 0 :timestamp 1000} m))

(def ^:private discrete
  [(input :note-on :note 36 :velocity 1.0)
   (input :note-off :note 36 :velocity 0.0)
   (input :trigger :source :osc :id "/hit" :state :pressed)
   (input :trigger-release :source :osc :id "/hit" :state :released)])

(deftest mapper-ignores-discrete-events-while-learning-test
  (let [sent (atom [])
        learning! #(state/swap-state! assoc-in [:backend :input :learning-target] %)]
    (state/init-state! (domains/build-initial-state))
    (input-mapper/set-dispatch-fn! #(swap! sent conj %))
    (input-mapper/start-mapper!)
    (try
      (run! router/dispatch! discrete)
      (is (= 4 (count @sent)) "not learning: each goes to the trigger map")
      (is (= {:event/type :trigger/input :input-event (first discrete) :time 1000} (first @sent)))
      (learning! {:type :vibe :role :hit})
      (run! router/dispatch! discrete)
      (is (= 4 (count @sent)) "learning: the learn catches the press (handlers/input.clj), and its old binding doesn't fire")
      (testing "a fader still moves while a learn waits"
        (router/dispatch! (input :control-change :control 7 :value 0.25))
        (is (= 0.25 (store/get-continuous-value [:midi 0 7]))))
      (learning! nil)
      (router/dispatch! (first discrete))
      (is (= 5 (count @sent)) "learned: inputs reach the trigger map again")
      (finally
        (input-mapper/stop-mapper!)
        (input-mapper/set-dispatch-fn! nil)
        (store/clear-continuous-values!)))))
