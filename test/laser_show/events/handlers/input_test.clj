(ns laser-show.events.handlers.input-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input :as input]
            [laser-show.input.events :as input-events]
            [laser-show.input.fixtures :as fixtures]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.templates :as templates]))

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

(defn- answer
  "What the device worker sends back for the request effects carries under
   effect-key: an event of type with its :request-id and status."
  [effects effect-key type & {:as status}]
  (merge {:event/type type :request-id (get-in effects [effect-key :request-id])} status))

(deftest osc-server-is-the-one-switch-test
  ;; The device worker (input.devices) opens the socket: these tests answer
  ;; for it
  (let [osc-of #(get-in % [:backend :input :osc])
        handle (fn [state event] (input/handle (assoc event :state state)))
        set-port (:state (handle (initial-state) {:event/type :input/osc-set-port :port 9100}))
        start (handle set-port {:event/type :input/osc-start-server :port 9001})]
    (testing "Start asks the worker for the port set when it is handled, not one the button was drawn with"
      (is (= {:request-id (get-in start [:state :backend :input :osc :requests :server]) :port 9100}
             (:input/osc-start start)))
      (is (uuid? (get-in start [:input/osc-start :request-id])))
      (is (= {:server-running? false :enabled? false} (select-keys (osc-of (:state start)) [:server-running? :enabled?]))
          "nothing listens before the worker says so"))
    (let [started (handle (:state start) (answer start :input/osc-start :input/osc-started :server-running? true :port 9100))
          stop (handle (:state started) {:event/type :input/osc-stop-server})
          stopped (handle (:state stop) (answer stop :input/osc-stop :input/osc-stopped :server-running? false))]
      (testing "Start Server listens and lets the messages in: there is no second switch to find"
        (is (= {:port 9100 :server-running? true :enabled? true}
               (select-keys (osc-of (:state started)) [:port :server-running? :enabled?])))
        (is (empty? (:requests (osc-of (:state started)))) "the request is over")
        (is (nil? (get-in started [:state :ui :notice]))))
      (testing "Stop Server does both the other way: the messages stop at once, then the server"
        (is (= {:request-id (get-in stop [:state :backend :input :osc :requests :server])} (:input/osc-stop stop)))
        (is (= {:server-running? true :enabled? false} (select-keys (osc-of (:state stop)) [:server-running? :enabled?])))
        (is (= {:server-running? false :enabled? false}
               (select-keys (osc-of (:state stopped)) [:server-running? :enabled?])))))
    (testing "a port that won't open: still off, and a notice says so"
      (let [result (handle (:state start) (answer start :input/osc-start :input/osc-started
                                                  :server-running? false :port 9100
                                                  :error "Could not listen for OSC on port 9100"))
            {:keys [id] :as notice} (get-in result [:state :ui :notice])]
        (is (= {:server-running? false :enabled? false}
               (select-keys (osc-of (:state result)) [:server-running? :enabled?])))
        (is (= {:text "Could not listen for OSC on port 9100" :level :error} (dissoc notice :id)))
        (is (= {:event {:event/type :ui/clear-notice :id id} :delay-ms 6000}
               (:dispatch-later result)))))))

(deftest stale-device-completion-is-ignored-test
  (let [pipe (fn [state event] (events/handle-event-pipeline (merge {:time 0} event {:state state})))
        s0 (history/mark-clean (initial-state))]
    (testing "two Refreshes: the first one's list, coming late, changes nothing"
      (let [r1 (pipe s0 {:event/type :input/midi-refresh-devices})
            r2 (pipe (:state r1) {:event/type :input/midi-refresh-devices})
            s2 (:state r2)
            listed #(answer %1 :input/midi-list :input/midi-listed :available-devices %2)]
        (is (not= (get-in r1 [:input/midi-list :request-id]) (get-in r2 [:input/midi-list :request-id])))
        (is (= {} (pipe s2 (listed r1 ["Old"]))) "no commit at all")
        (let [s3 (:state (pipe s2 (listed r2 ["Pad" "Keys"])))]
          (is (= ["Pad" "Keys"] (get-in s3 [:backend :input :midi :available-devices])))
          (is (= {} (pipe s3 (listed r2 ["Pad"]))) "the same result again: the request is over")
          (is (= {} (pipe s3 (listed r1 ["Old"]))) "nor an earlier one's after it")
          (is (= {} (pipe s3 {:event/type :input/midi-listed :available-devices ["Made up"]})) "nor one without an id")
          (is (false? (get-in s3 [:project :dirty?])) "a device list isn't the show's")
          (is (empty? (get-in s3 [:history :undo-stack]))))))
    (testing "Connect then Disconnect: the connect's result, before or after the disconnect's, can't connect it again"
      (let [c (pipe s0 {:event/type :input/midi-connect-device :device-name "Pad"})
            d (pipe (:state c) {:event/type :input/midi-disconnect-device :device-name "Pad"})
            connected (answer c :input/midi-connect :input/midi-connected :connected-devices #{"Pad"})
            disconnected (answer d :input/midi-disconnect :input/midi-disconnected :connected-devices #{})]
        (is (= {:request-id (:request-id connected) :device-name "Pad"} (:input/midi-connect c)))
        (is (= {:request-id (:request-id disconnected) :device-name "Pad"} (:input/midi-disconnect d)))
        (testing "in order"
          (is (= {} (pipe (:state d) connected)) "superseded: no commit, no notice")
          (is (= #{} (get-in (:state (pipe (:state d) disconnected)) [:backend :input :midi :connected-devices]))))
        (testing "reversed"
          (let [s (:state (pipe (:state d) disconnected))]
            (is (= {} (pipe s connected)))))))
    (testing "OSC: an earlier Start's result after a Stop's leaves it stopped"
      (let [start (pipe s0 {:event/type :input/osc-start-server})
            stop (pipe (:state start) {:event/type :input/osc-stop-server})
            s (:state (pipe (:state stop) (answer stop :input/osc-stop :input/osc-stopped :server-running? false)))]
        (is (= {} (pipe s (answer start :input/osc-start :input/osc-started :server-running? true :port 9000))))
        (is (= {} (pipe (:state stop) (answer start :input/osc-start :input/osc-started :server-running? false :port 9000
                                              :error "Could not listen for OSC on port 9000")))
            "nor does its failure leave a notice")))))

(deftest midi-connect-results-test
  (let [handle (fn [state event] (input/handle (assoc event :state state)))
        auto (handle (initial-state) {:event/type :input/midi-auto-connect})
        connected (handle (:state auto) (answer auto :input/midi-connect :input/midi-connected :connected-devices #{"Pad"}))]
    (is (= {:request-id (get-in auto [:input/midi-connect :request-id]) :auto? true} (:input/midi-connect auto)))
    (is (= #{"Pad"} (get-in connected [:state :backend :input :midi :connected-devices])))
    (is (nil? (get-in connected [:state :ui :notice])))
    (testing "the input couldn't be connected: the worker's sentence as a notice"
      (let [failed (handle (:state auto) (answer auto :input/midi-connect :input/midi-connected
                                                 :connected-devices #{} :error "No MIDI input to connect to"))]
        (is (= #{} (get-in failed [:state :backend :input :midi :connected-devices])))
        (is (= {:text "No MIDI input to connect to" :level :error}
               (dissoc (get-in failed [:state :ui :notice]) :id)))))
    (testing "Disconnect All asks for all of them"
      (is (true? (get-in (handle (:state connected) {:event/type :input/midi-disconnect-all})
                         [:input/midi-disconnect :all?]))))))

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

(deftest keyboard-enable-disable-test
  (let [clean (-> (initial-state)
                  (assoc-in [:config :input :trigger-map {:source :keyboard :id :space}] {:type :stop-all})
                  (history/mark-clean))
        switch (fn [s event-type]
                 (:state (events/handle-event-pipeline {:state s :event/type event-type})))
        disabled (switch clean :input/keyboard-disable)
        enabled (switch disabled :input/keyboard-enable)]
    (is (= {:enabled? true} (get-in clean [:backend :input :keyboard])))
    (is (false? (get-in disabled [:backend :input :keyboard :enabled?])))
    (is (true? (get-in enabled [:backend :input :keyboard :enabled?])))
    (doseq [s [disabled enabled]]
      (is (= (persistent/project-files clean) (persistent/project-files s)) "the switch is session-only; bindings stay in the show")
      (is (false? (get-in s [:project :dirty?])))
      (is (empty? (get-in s [:history :undo-stack]))))))


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


;; Learning a fader (Phase 5): {:type :macro :slot} with :capture :continuous

(def ^:private slider-1 {:type :macro :slot :slider-1})
(def ^:private slider-2 {:type :macro :slot :slider-2})

(defn- start-fader-learn
  "[state promise]: the learn for a fader slot started on state, its router
   handler registered; promise gets what it catches."
  [state target]
  (let [effects (input/handle {:event/type :input/start-learn :target-action target :capture :continuous :state state})]
    [(:state effects) (get-in effects [:input/await-learn :promise])]))

(defn- move!
  "MIDI CC control on channel moved through values, through the router."
  [channel control & values]
  (doseq [v values]
    (router/dispatch! (input-events/control-change :midi channel control v))))

(defn- caught [p] (deref p 0 nil))

(defn- macro-map [state] (get-in state [:config :input :macro-map]))

(deftest learning-capture-mode-test
  (try
    (let [mode #(get-in % [:backend :input :learning-capture])
          [continuous _] (start-fader-learn (initial-state) hit-role)
          press (learning continuous hit-role)
          fader (learning press slider-1)]
      (is (= :continuous (mode continuous)) "an explicit continuous request, even for a non-macro target")
      (is (= hit-role (learning-target continuous)) "the views and Learn all still recognize the target")
      (is (= :press (mode press)) "restarting the same target as an ordinary learn replaces its mode")
      (is (= :continuous (mode fader)) "a macro always waits for movement")
      (is (nil? (mode (captured press hit-role (note 36)))) "a completed learn clears the mode")
      (is (nil? (mode (run fader :input/cancel-learn))) "so does cancel"))
    (finally
      (stop-learning!))))

(defn- fader-learned
  "state after the learn for target caught a move of MIDI CC control on
   channel 0 from a to b, as :input/await-learn hands it on."
  [state target control a b]
  (let [[s p] (start-fader-learn state target)]
    (move! 0 control a b)
    (piped s {:event/type :input/learn-captured :target-action target :input-event (caught p)})))

(deftest learn-cc-needs-movement-test
  (try
    (let [[s p] (start-fader-learn (initial-state) slider-1)]
      (is (= slider-1 (learning-target s)) "the learn aims at the slot")
      (testing "the doc's example: CC 7 at 0.50, then 0.52: nothing; CC 1 at 0.10, then 0.31: caught"
        (move! 0 7 0.50 0.52 0.47 0.55)
        (is (nil? (caught p)) "invariant 9: a noisy CC that wiggles by less than 0.08 is never captured")
        (move! 0 1 0.10)
        (is (nil? (caught p)) "a first value is no movement")
        (move! 0 1 0.31)
        (is (= [:midi 0 1] (:store-key (caught p))))
        (is (= 0.31 (:value (caught p))))
        (is (not-any? #{::input/generalized-input-learn} (:global-handlers (router/list-handlers)))
            "it stops listening once it caught")))
    (testing "about 10 of a CC's 127 steps: 10 aren't enough, 11 are, either way"
      (let [[_ p] (start-fader-learn (initial-state) slider-1)
            step! #(router/dispatch! (midi/midi-msg->event (fixtures/control-change 0 9 %)))]
        (step! 64)
        (step! 74)
        (step! 54)
        (is (nil? (caught p)))
        (step! 75)
        (is (= [:midi 0 9] (:store-key (caught p))))))
    (testing "an OSC fader on an address of its own, and a gamepad trigger"
      (let [[_ p] (start-fader-learn (initial-state) slider-2)]
        (run! router/dispatch! (osc/osc-msg->events (fixtures/osc-msg "/fader/1" (float 0.2)) {}))
        (run! router/dispatch! (osc/osc-msg->events (fixtures/osc-msg "/fader/1" (float 0.5)) {}))
        (is (= [:osc "/fader/1"] (:store-key (caught p)))))
      (let [[_ p] (start-fader-learn (initial-state) slider-2)]
        (router/dispatch! (input-events/control-change :gamepad 0 :trigger-left 0.0))
        (router/dispatch! (input-events/control-change :gamepad 0 :trigger-left 0.9))
        (is (= [:gamepad 0 :trigger-left] (:store-key (caught p))))))
    (finally
      (stop-learning!))))

(deftest learn-cc-binds-a-slot-and-replaces-its-old-source-test
  (try
    (let [s0 (history/mark-clean (initial-state))
          s1 (fader-learned s0 slider-1 1 0.10 0.31)]
      (is (= {[:midi 0 1] :slider-1} (macro-map s1)))
      (is (nil? (learning-target s1)))
      (is (= "Learn Fader" (history/undo-label (:history s1))) "one undo step")
      (is (true? (get-in s1 [:project :dirty?])) "a saved, undoable edit")
      (is (= {} (trigger-map s1)) "the trigger map isn't touched")
      (testing "learning slider-1 again on CC 9 removes CC 1: one source per slot (invariant 5)"
        (is (= {[:midi 0 9] :slider-1} (macro-map (fader-learned s1 slider-1 9 0.9 0.5)))))
      (testing "slider-2 gets its own; then learned on slider-1's CC, it takes it: a key has one slot"
        (let [s2 (fader-learned s1 slider-2 2 0.0 1.0)]
          (is (= {[:midi 0 1] :slider-1 [:midi 0 2] :slider-2} (macro-map s2)))
          (is (= {[:midi 0 1] :slider-2} (macro-map (fader-learned s2 slider-2 1 0.6 0.2))))))
      (testing "the same control again changes nothing, so no undo step"
        (let [again (fader-learned s1 slider-1 1 0.2 0.6)]
          (is (= (macro-map s1) (macro-map again)))
          (is (= 1 (count (get-in again [:history :undo-stack]))))))
      (testing "a capture for slider-1 while slider-2 is learned binds nothing"
        (let [[s p] (start-fader-learn s1 slider-2)]
          (move! 0 5 0.0 0.5)
          (is (= (macro-map s1)
                 (macro-map (piped s {:event/type :input/learn-captured :target-action slider-1 :input-event (caught p)}))))))
      (testing "undone: no binding"
        (is (empty? (macro-map (piped s1 {:event/type :edit/undo}))))))
    (testing "a fader's learn ends Learn all, as any other learn does"
      (let [s (piped (initial-state) {:event/type :vibe/learn-all})
            [s' _] (start-fader-learn s slider-1)]
        (is (some? (get-in s [:ui :vibes :wizard])))
        (is (nil? (wizard s')))
        (is (= slider-1 (learning-target s')))))
    (finally
      (stop-learning!))))

(deftest learn-continuous-ignores-notes-test
  (try
    (let [[s p] (start-fader-learn (initial-state) slider-1)]
      (router/dispatch! (input-events/note-on :midi 0 36 1.0))
      (router/dispatch! (input-events/note-off :midi 0 36))
      (router/dispatch! (input-events/trigger :osc "/hit" :pressed))
      (router/dispatch! (input-events/trigger :gamepad [0 :a] :pressed))
      (router/dispatch! (input-events/note-on :midi 0 37 0.2))
      (is (nil? (caught p)) "invariant 8: notes and buttons never bind a fader")
      (testing "and a note handed to a fader's learn binds nothing, and ends it"
        (let [s' (captured s slider-1 (note 36))]
          (is (empty? (macro-map s')))
          (is (= (trigger-map s) (trigger-map s')))
          (is (nil? (learning-target s')))))
      (testing "even with a :store-key: a note writes no store key"
        (is (empty? (macro-map (captured s slider-1 (assoc (note 36) :store-key [:midi 0 36])))))))
    (finally
      (stop-learning!))))

(deftest learn-a-role-ignores-cc-test
  (try
    (let [effects (input/handle {:event/type :input/start-learn :target-action hit-role :state (initial-state)})
          s (:state effects)
          p (get-in effects [:input/await-learn :promise])]
      (move! 0 1 0.1 0.9 0.0 1.0)
      (is (nil? (caught p)) "probe v6, now pinned: a CC never captures a role's learn")
      (router/dispatch! (input-events/note-on :midi 0 36 1.0))
      (is (= :note-on (:type (caught p))) "a note does")
      (testing "and a CC handed to a role's learn binds nothing, even caught by a fader's catcher"
        (let [[_ fp] (start-fader-learn (initial-state) hit-role)]
          (move! 0 1 0.1 0.9)
          (let [cc (caught fp)
                s' (captured s hit-role cc)]
            (is (= [:midi 0 1] (:store-key cc)))
            (is (= (trigger-map s) (trigger-map s')))
            (is (empty? (macro-map s')))
            (is (nil? (learning-target s')))))))
    (finally
      (stop-learning!))))


(deftest relearning-a-map-pad-moves-its-key-test
  (try
    (let [odd-even {:type :zone-map :target :map/odd-even}
          unison {:type :zone-map :target :map/unison}
          s0 (assoc-in (initial-state) [:config :input :trigger-map]
                       {(note-key 36) odd-even (note-key 37) unison})
          learning-it (learning s0 odd-even)]
      (is (= odd-even (get-in learning-it [:backend :input :learning-target])) "learn aims at the pad")
      (is (= {(note-key 50) odd-even (note-key 37) unison} (trigger-map (captured learning-it odd-even (note 50))))
          "Z4-Q2 A: one key a pad, so learning it again moves it; another pad keeps its own")
      (testing "learned over HIT's key: the key is the pad's now, and the label says so"
        (let [s (-> (initial-state)
                    (assoc-in [:config :input :trigger-map] {(note-key 36) {:type :vibe :role :hit}})
                    (history/mark-clean)
                    (learning odd-even))
              s' (:state (events/handle-event-pipeline
                          {:event/type :input/learn-captured :target-action odd-even :input-event (note 36) :state s :time 1}))]
          (is (= {(note-key 36) odd-even} (trigger-map s')))
          (is (= "Learn Trigger (replaces HIT)" (history/undo-label (:history s'))))))
      (testing "the label names a pad over another pad's key"
        (let [s (-> (assoc-in (initial-state) [:config :input :trigger-map] {(note-key 37) unison})
                    (history/mark-clean)
                    (learning odd-even))
              s' (:state (events/handle-event-pipeline
                          {:event/type :input/learn-captured :target-action odd-even :input-event (note 37) :state s :time 1}))]
          (is (= "Learn Trigger (replaces zone map Unison)" (history/undo-label (:history s')))))))
    (finally
      (stop-learning!))))

(deftest relearning-a-pattern-pad-moves-its-key-test
  (try
    (let [chase {:type :pattern-pad :target :chase}
          off {:type :pattern-pad :target :off}
          step {:type :zone-step}
          s0 (assoc-in (initial-state) [:config :input :trigger-map]
                       {(note-key 36) chase (note-key 37) off (note-key 38) step})]
      (is (= chase (get-in (learning s0 chase) [:backend :input :learning-target])) "learn aims at the pad")
      (is (= {(note-key 50) chase (note-key 37) off (note-key 38) step} (trigger-map (captured (learning s0 chase) chase (note 50))))
          "zones plan Z5: one key a pad, so learning it again moves it; the other pads keep theirs")
      (is (= {(note-key 36) chase (note-key 37) off (note-key 51) step} (trigger-map (captured (learning s0 step) step (note 51))))
          "the Zone step key moves too")
      (testing "the label names a pad over another one's key"
        (let [s (-> (assoc-in (initial-state) [:config :input :trigger-map] {(note-key 37) off (note-key 38) step})
                    (history/mark-clean)
                    (learning chase))
              label (fn [k] (history/undo-label (:history (:state (events/handle-event-pipeline
                                                                    {:event/type :input/learn-captured :target-action chase
                                                                     :input-event (note k) :state s :time 1})))))]
          (is (= "Learn Trigger (replaces pattern pad Off)" (label 37)))
          (is (= "Learn Trigger (replaces zone step)" (label 38)))
          (is (= "Learn Trigger (replaces zone map CYCLE)"
                 (history/undo-label (:history (:state (events/handle-event-pipeline
                                                        {:event/type :input/learn-captured :target-action chase :input-event (note 39) :time 1
                                                         :state (-> (assoc-in (initial-state) [:config :input :trigger-map]
                                                                              {(note-key 39) {:type :zone-map :target :cycle}})
                                                                    (history/mark-clean)
                                                                    (learning chase))})))))))))
    (finally
      (stop-learning!))))

(deftest map-pad-learn-ignores-a-cc-test
  (try
    (let [odd-even {:type :zone-map :target :map/odd-even}
          effects (input/handle {:event/type :input/start-learn :target-action odd-even :state (initial-state)})
          p (get-in effects [:input/await-learn :promise])]
      (move! 0 1 0.1 0.9 0.0 1.0)
      (is (nil? (caught p)) "moving a control is how a fader learns: a pad never takes it")
      (router/dispatch! (input-events/note-on :midi 0 36 1.0))
      (is (= :note-on (:type (caught p))) "a note does"))
    (finally
      (stop-learning!))))

;; What a key holds lets go when the key can't let go itself
;; (plans/2026-11-18-vibe-mode/07)

(defn- vibe-world []
  (-> (initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)))

(deftest midi-disconnect-releases-input-holds-only-test
  (let [s0 (assoc-in (vibe-world) [:backend :input :midi :connected-devices] #{"Pad"})
        held (-> s0
                 (piped {:event/type :vibe/press :role :hit :input-time 1000 :time 1000})
                 (piped {:event/type :vibe/press :role :accent :src :pad :time 1000}))]
    (is (= {:hit :input :accent :pad} (update-vals (get-in held [:vibe-runtime :held]) :src)))
    (doseq [event [{:event/type :input/midi-disconnect-device :device-name "Pad"}
                   {:event/type :input/midi-disconnect-all}]]
      (testing (str (:event/type event))
        (let [asked (events/handle-event-pipeline (assoc event :time 2000 :state held))
              s (:state asked)]
          (is (= :input (get-in s [:vibe-runtime :held :hit :src])) "until the port is closed, its key holds on")
          (testing "the device worker says it's disconnected"
            (let [s (piped s (assoc (answer asked :input/midi-disconnect :input/midi-disconnected :connected-devices #{})
                                    :time 2000))]
              (is (not (contains? (get-in s [:backend :input :midi :connected-devices]) "Pad")))
              (is (nil? (get-in s [:vibe-runtime :held :hit])) "beyond-vibe disconnect_releases_held_keys: the key's hold ends")
              (is (false? (get-in s [:playback :pulses :hit :held?])))
              (is (= :pad (get-in s [:vibe-runtime :held :accent :src])) "a pad's hold stays: its release still comes"))))))))

(deftest a-key-that-loses-its-role-lets-go-test
  (try
    (let [hit {:type :vibe :role :hit}
          accent {:type :vibe :role :accent}
          s0 (assoc-in (vibe-world) [:config :input :trigger-map] {(note-key 36) hit (note-key 37) accent})
          held (-> s0
                   (piped {:event/type :vibe/press :role :hit :input-time 1000 :time 1000})
                   (piped {:event/type :vibe/press :role :accent :input-time 1000 :time 1000}))
          cell (get-in held [:vibe-runtime :voices :hit :cell])]
      (is (some? (get-in held [:playback :active-cues cell])))
      (testing "a learn moves HIT to another key: the key that holds it lets go now (04 §11)"
        (let [s (press-key (learning held hit) 50)]
          (is (= {(note-key 50) hit (note-key 37) accent} (trigger-map s)))
          (is (nil? (get-in s [:vibe-runtime :held :hit])))
          (is (false? (get-in s [:playback :pulses :hit :held?])) "and its pulse")
          (is (nil? (get-in s [:playback :active-cues cell])) "cut, as release-all lets go")
          (is (= :input (get-in s [:vibe-runtime :held :accent :src])) "ACCENT's key is still its: it holds on")))
      (testing "a cell learned on HIT's key"
        (is (nil? (get-in (press-key (learning held {:type :trigger-cue :target [0 1]}) 36) [:vibe-runtime :held :hit]))))
      (testing "HIT learned again on its own key: nothing lost, it holds on"
        (is (= :input (get-in (press-key (learning held hit) 36) [:vibe-runtime :held :hit :src]))))
      (testing "a pad's hold stays"
        (let [pad (piped s0 {:event/type :vibe/press :role :hit :src :pad :time 1000})]
          (is (= :pad (get-in (press-key (learning pad hit) 50) [:vibe-runtime :held :hit :src]))))))
    (finally
      (stop-learning!))))


;; A learn binds into the show it was started in (reactivity plan 20)

(deftest a-capture-after-new-or-open-binds-nothing-test
  ;; Before: the old learn's input bound in the new show, which became
  ;; unsaved and got a "Learn Trigger" undo step
  (try
    (let [show (history/mark-clean (initial-state))
          cell {:type :trigger-cue :target [0 0]}
          pending #(deref @#'input/!pending-learn)]
      (doseq [[label replace] [["File > New" {:event/type :file/new-project :force? true}]
                               ["File > Open" {:event/type :file/project-loaded :file-path "other.zip"
                                               :contents (persistent/project-files show)}]]
              [what start target input!] [["a cell's learn" {:event/type :input/start-learn :target-action cell}
                                           cell #(router/dispatch! (note 36))]
                                          ["Learn all" learn-all hit-role #(router/dispatch! (note 37))]
                                          ["a fader's learn" {:event/type :input/start-learn :target-action slider-1}
                                           slider-1 #(move! 0 1 0.1 0.5)]]]
        (testing (str label ", during " what)
          (let [learning (piped show start)
                p (pending)
                replaced (piped learning replace)
                _ (input!)
                late (piped replaced {:event/type :input/learn-captured :target-action target :input-event (caught p)})]
            (is (= target (learning-target learning)))
            (is (some? (caught p)) "the old learn's listener still caught the input")
            (is (nil? (learning-target replaced)) "the new show isn't learning")
            (is (nil? (get-in replaced [:backend :input :learning-capture])) "so its keys play their bindings")
            (is (nil? (wizard replaced)))
            (is (= (get-in replaced [:config :input]) (get-in late [:config :input])) "the late capture binds nothing")
            (is (false? (get-in late [:project :dirty?])))
            (is (empty? (get-in late [:history :undo-stack])))))))
    (finally
      (stop-learning!))))
