(ns laser-show.events.handlers.input-mapper-test
  "The input mapper: discrete inputs go to the trigger map as :trigger/input,
   continuous ones to the input store, and a learn keeps the press it
   catches (K-15) but not the releases."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]))

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
      (is (= [:note-off :trigger-release] (mapv (comp :type :input-event) (drop 4 @sent)))
          "learning: the learn catches the presses (handlers/input.clj), and their old bindings don't fire; the releases still reach theirs")
      (testing "a fader still moves while a learn waits"
        (router/dispatch! (input :control-change :control 7 :value 0.25))
        (is (= 0.25 (store/get-continuous-value [:midi 0 7]))))
      (learning! nil)
      (router/dispatch! (first discrete))
      (is (= 7 (count @sent)) "learned: inputs reach the trigger map again")
      (finally
        (input-mapper/stop-mapper!)
        (input-mapper/set-dispatch-fn! nil)
        (store/clear-continuous-values!)))))

(deftest a-key-held-when-a-learn-starts-still-lets-go-test
  (testing "HIT's key held, a learn for ACCENT starts, the key is let go: so is HIT (03 §10, Found, not done)"
    (let [hit-key {:source :midi :channel 0 :note 36}
          app-state #(state/get-raw-state)]
      (state/init-state! (-> (domains/build-initial-state)
                             (templates/apply-starter-cue-chains)
                             (templates/apply-starter-vibes)
                             (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
                             (assoc-in [:config :input :trigger-map] {hit-key {:type :vibe :role :hit}})))
      (input-mapper/set-dispatch-fn! events/event-handler)
      (input-mapper/start-mapper!)
      (try
        (router/dispatch! (input :note-on :note 36 :velocity 1.0 :timestamp 1000))
        (let [cell (get-in (app-state) [:vibe-runtime :voices :hit :cell])]
          (is (= :input (get-in (app-state) [:vibe-runtime :held :hit :src])))
          (is (some? (get-in (app-state) [:playback :active-cues cell])))
          (events/event-handler {:event/type :input/start-learn :target-action {:type :vibe :role :accent}})
          (is (= {:type :vibe :role :accent} (get-in (app-state) [:backend :input :learning-target])))
          (router/dispatch! (input :note-off :note 36 :timestamp 2000))
          (is (nil? (get-in (app-state) [:vibe-runtime :held :hit])) "the role is let go")
          (is (false? (get-in (app-state) [:playback :pulses :hit :held?])) "and its pulse")
          (is (nil? (get-in (app-state) [:playback :active-cues cell])) "held a second: its cue stops, as a release does")
          (is (= {:type :vibe :role :accent} (get-in (app-state) [:backend :input :learning-target]))
              "the learn still waits for its press")
          (is (= {hit-key {:type :vibe :role :hit}} (get-in (app-state) [:config :input :trigger-map]))))
        (finally
          (events/event-handler {:event/type :input/cancel-learn})
          (input-mapper/stop-mapper!)
          (input-mapper/set-dispatch-fn! nil))))))
