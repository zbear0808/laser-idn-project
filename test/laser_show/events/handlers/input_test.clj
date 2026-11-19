(ns laser-show.events.handlers.input-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input :as input]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]))

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

(deftest gamepad-enable-disable-test
  (let [state (initial-state)
        disabled (run state :input/gamepad-disable)
        enabled (run disabled :input/gamepad-enable)]
    (is (true? (get-in state [:backend :input :gamepad :enabled?])))
    (is (false? (get-in disabled [:backend :input :gamepad :enabled?])))
    (is (true? (get-in enabled [:backend :input :gamepad :enabled?])))))


;; Learning a vibe role (Phase 2)

(defn- learning
  "state as :input/start-learn leaves it for target."
  [state target]
  (:state (input/handle {:event/type :input/start-learn :target-action target :state state})))

(defn- captured
  "state after the learn for target captured input."
  [state target input]
  (:state (input/handle {:event/type :input/learn-captured :target-action target :input-event input :state state})))

(defn- note [n] {:type :note-on :source :midi :channel 0 :note n :velocity 1.0})
(defn- note-key [n] {:source :midi :channel 0 :note n})

(defn- trigger-map [state] (get-in state [:config :input :trigger-map]))

(defn- stop-learning! []
  (input/handle {:event/type :input/cancel-learn :state (initial-state)})
  (router/clear-handlers!))

(deftest relearning-a-role-moves-its-binding-test
  (try
    (let [hit {:type :vibe :role :hit}
          accent {:type :vibe :role :accent}
          s0 (assoc-in (initial-state) [:config :input :trigger-map]
                       {(note-key 36) hit {:source :keyboard :id :h} hit (note-key 37) accent})
          learning-hit (learning s0 hit)]
      (is (= hit (get-in learning-hit [:backend :input :learning-target])) "learn aims at the role")
      (let [s1 (captured learning-hit hit (note 50))]
        (is (= {(note-key 50) hit (note-key 37) accent} (trigger-map s1))
            "invariant 6: exactly one key bound to HIT, and ACCENT keeps its own")
        (is (nil? (get-in s1 [:backend :input :learning-target]))))
      (testing "a capture for HIT while ACCENT is being learned is ignored: they are different targets"
        (is (= (trigger-map s0) (trigger-map (captured (learning s0 accent) hit (note 50))))))
      (testing "a cell still gets a second key"
        (let [cue {:type :trigger-cue :target [0 0]}
              s (assoc-in (initial-state) [:config :input :trigger-map] {(note-key 60) cue})]
          (is (= {(note-key 60) cue (note-key 61) cue} (trigger-map (captured (learning s cue) cue (note 61))))))))
    (finally
      (stop-learning!))))

(deftest learning-over-another-action-names-it-in-the-undo-label-test
  (try
    (let [hit {:type :vibe :role :hit}
          learn (fn [bound target]
                  (let [s (-> (initial-state)
                              (assoc-in [:config :input :trigger-map] (if bound {(note-key 36) bound} {}))
                              (history/mark-clean)
                              (learning target))
                        s' (:state (events/handle-event-pipeline
                                    {:event/type :input/learn-captured :target-action target
                                     :input-event (note 36) :state s}))]
                    [(get (trigger-map s') (note-key 36)) (history/undo-label (:history s'))]))]
      (is (= [hit "Learn Trigger (replaces cue A1)"] (learn {:type :trigger-cue :target [0 0]} hit)))
      (is (= [hit "Learn Trigger (replaces effect B3)"] (learn {:type :trigger-effect :target [2 1]} hit)))
      (is (= [{:type :vibe :role :accent} "Learn Trigger (replaces HIT)"] (learn hit {:type :vibe :role :accent})))
      (is (= [{:type :trigger-cue :target [0 0]} "Learn Trigger (replaces tap tempo)"]
             (learn {:type :tap-tempo} {:type :trigger-cue :target [0 0]})))
      (is (= [hit "Learn Trigger"] (learn nil hit)) "a free key replaces nothing")
      (is (= [hit nil] (learn hit hit))
          "HIT's own key: nothing changes, so no undo step (K-15: that press also fired HIT)"))
    (finally
      (stop-learning!))))
