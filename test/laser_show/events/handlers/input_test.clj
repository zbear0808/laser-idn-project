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

(deftest osc-server-is-the-one-switch-test
  ;; osc/start-server opens a socket: these tests stand in for it
  (let [osc-of #(get-in % [:state :backend :input :osc])]
    (with-redefs [osc/start-server (fn [osc-state port _dispatch _get-state]
                                     (assoc osc-state :port port :server-running? true))]
      (let [started (input/handle {:event/type :input/osc-start-server :port 9001 :state (initial-state)})
            stopped (input/handle {:event/type :input/osc-stop-server :state (:state started)})]
        (testing "Start Server listens and lets the messages in: there is no second switch to find"
          (is (= {:port 9001 :server-running? true :enabled? true}
                 (select-keys (osc-of started) [:port :server-running? :enabled?])))
          (is (nil? (get-in started [:state :ui :notice]))))
        (testing "Stop Server does both the other way"
          (is (= {:server-running? false :enabled? false}
                 (select-keys (osc-of stopped) [:server-running? :enabled?]))))))
    (testing "a port that won't open: still off, and a notice says so"
      (with-redefs [osc/start-server (fn [osc-state _port _dispatch _get-state]
                                       (assoc osc-state :server-running? false))]
        (let [result (input/handle {:event/type :input/osc-start-server :port 9001 :state (initial-state)})
              {:keys [id] :as notice} (get-in result [:state :ui :notice])]
          (is (= {:server-running? false :enabled? false}
                 (select-keys (osc-of result) [:server-running? :enabled?])))
          (is (= {:text "Could not listen for OSC on port 9001" :level :error} (dissoc notice :id)))
          (is (= {:event {:event/type :ui/clear-notice :id id} :delay-ms 6000}
                 (:dispatch-later result))))))))

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


;; Learn all (Phase 3)

(defn- piped
  "state after event through the pipeline, then each event it dispatches,
   as the agent runs them."
  [state event]
  (let [effects (events/handle-event-pipeline (merge {:time 0} event {:state state}))
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (piped s next-event)
      s)))

(defn- learning-target [state] (get-in state [:backend :input :learning-target]))
(defn- wizard [state] (get-in state [:ui :vibes :wizard]))

(defn- press-key
  "state after the learn waiting now caught note n, as :input/await-learn
   hands it on."
  [state n]
  (piped state {:event/type :input/learn-captured :target-action (learning-target state) :input-event (note n)}))

(def ^:private learn-all {:event/type :vibe/learn-all})
(def ^:private hit-role {:type :vibe :role :hit})
(def ^:private accent-role {:type :vibe :role :accent})

(deftest learn-all-chains-the-five-roles-test
  (try
    (let [s1 (piped (history/mark-clean (initial-state)) learn-all)
          steps (reductions press-key s1 [36 37 38 39 40])
          done (last steps)]
      (is (= {:queue [:hit :accent :flash :vibe :blackout] :done []} (wizard s1)))
      (is (= hit-role (learning-target s1)) "it starts with HIT")
      (is (empty? (get-in s1 [:history :undo-stack])) "starting it is no undo step")
      (is (= [:accent :flash :vibe :blackout nil] (map (comp :role learning-target) (rest steps)))
          "each key caught starts the next role's learn")
      (is (= [[:hit] [:hit :accent] [:hit :accent :flash] [:hit :accent :flash :vibe]]
             (map (comp :done wizard) (butlast (rest steps)))))
      (is (= (zipmap (map note-key [36 37 38 39 40]) (map #(hash-map :type :vibe :role %) [:hit :accent :flash :vibe :blackout]))
             (trigger-map done)))
      (is (nil? (wizard done)) "it ends after BLACKOUT")
      (is (nil? (learning-target done)) "invariant 8")
      (is (= ["Learn Trigger"] (distinct (map :description (get-in done [:history :undo-stack])))))
      (is (= 5 (count (get-in done [:history :undo-stack]))) "one undo step a key"))
    (finally
      (stop-learning!))))

(deftest learn-all-refuses-a-key-twice-test
  (try
    (let [s1 (-> (initial-state) (piped learn-all) (press-key 36))
          s2 (press-key s1 36)
          s3 (press-key s2 37)]
      (is (= accent-role (learning-target s2)) "invariant 7: HIT's key pressed for ACCENT: the step waits for another key")
      (is (= :hit (:refused (wizard s2))) "and says it is HIT's already")
      (is (= {(note-key 36) hit-role} (trigger-map s2)))
      (is (= {:type :vibe :role :flash} (learning-target s3)))
      (is (nil? (:refused (wizard s3))))
      (is (= {(note-key 36) hit-role (note-key 37) accent-role} (trigger-map s3))))
    (testing "a key bound before Learn all moves to the role it is pressed for, as a re-learn does"
      (let [s (-> (initial-state)
                  (assoc-in [:config :input :trigger-map] {(note-key 50) accent-role})
                  (piped learn-all)
                  (press-key 50))]
        (is (= {(note-key 50) hit-role} (trigger-map s)))
        (is (= accent-role (learning-target s)))))
    (finally
      (stop-learning!))))

(deftest cancel-leaves-no-learning-target-test
  (try
    (let [s1 (-> (initial-state) (piped learn-all) (press-key 36))]
      (testing "Cancel"
        (let [s (piped s1 {:event/type :vibe/wizard-cancel})]
          (is (nil? (learning-target s)) "invariant 8")
          (is (nil? (wizard s)))
          (is (= {(note-key 36) hit-role} (trigger-map s)) "what was learned stays")
          (testing "and the capture of the cancelled learn, if it comes late, binds nothing and starts nothing"
            (let [s' (piped s {:event/type :input/learn-captured :target-action accent-role :input-event (note 37)})]
              (is (= (trigger-map s) (trigger-map s')))
              (is (nil? (learning-target s')))))))
      (testing "a pad's Cancel learning ends it too"
        (let [s (piped s1 {:event/type :input/cancel-learn})]
          (is (nil? (learning-target s)))
          (is (nil? (wizard s)))))
      (testing "and so does a learn for anything else: that one's key is learned, and nothing follows"
        (doseq [target [{:type :trigger-cue :target [0 0]} {:type :vibe :role :vibe}]]
          (let [s (piped s1 {:event/type :input/start-learn :target-action target})
                s' (press-key s 60)]
            (is (= target (learning-target s)))
            (is (nil? (wizard s)))
            (is (= target (get (trigger-map s') (note-key 60))))
            (is (nil? (learning-target s')))))))
    (finally
      (stop-learning!))))
