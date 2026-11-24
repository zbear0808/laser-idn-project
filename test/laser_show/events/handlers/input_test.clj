(ns laser-show.events.handlers.input-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input :as input]
            [laser-show.events.helpers :as h]
            [laser-show.input.events :as input-events]
            [laser-show.input.fixtures :as fixtures]
            [laser-show.input.learn :as learn]
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
        (is (= {:event/type :trigger/retire :source :osc} (:dispatch stop))
            "and what OSC keys hold lets go, as their releases can't come in (reactivity plan 26)")
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

(deftest osc-set-port-takes-only-a-port-test
  (let [handle #(input/handle {:event/type :input/osc-set-port :port % :state (initial-state)})]
    (doseq [port [1 9100 65535]]
      (is (= port (get-in (handle port) [:state :backend :input :osc :port]))))
    (doseq [bad [0 65536 70000 -1 nil "9100" 9100.5]]
      (is (= {} (handle bad)) (str (pr-str bad) ": no port, so nothing changes (no commit)")))))

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


;; Learning an input (reactivity plan 24): one attempt at a time, each with
;; an id of its own. Its events go through the real pipeline, which gives
;; a Start its id (events/new-learn-id) and asks the router's learn slot
;; to follow (:input/sync-learn); a test that needs the slot puts the
;; listener there as that effect does (armed).

(defn- shown
  "state as a show the app has open: an identity of its own
   (persistent/begin-document), saved (clean)."
  ([] (shown (initial-state)))
  ([state] (-> state persistent/begin-document history/mark-clean)))

(defn- pipe
  "The pipeline's effects for event on state."
  [state event]
  (events/handle-event-pipeline (merge {:time 0} event {:state state})))

(defn- piped
  "state after event through the pipeline, then each event it dispatches
   (:dispatch, :dispatch-n), as the agent runs them."
  [state event]
  (let [effects (pipe state event)
        s (or (:state effects) state)]
    (reduce piped s (cond-> [] (:dispatch effects) (conj (:dispatch effects)) (:dispatch-n effects) (into (:dispatch-n effects))))))

(defn- with-learn-ids
  "(f) with the pipeline giving new learn attempts ids, in order
   (events/new-learn-id): fixture ids a test can name."
  [ids f]
  (let [left (atom ids)]
    (with-redefs [events/new-learn-id (fn [] (let [[[id]] (swap-vals! left rest)] id))]
      (f))))

(defn- active [state] (input/active-learn state))
(defn- learning-target [state] (input/learning-target state))
(defn- wizard [state] (:wizard (active state)))

(defn- start-learn
  "The Start of a learn for target-action, as a view of the show open in
   state sends it (its :document-id)."
  [state target-action & {:as opts}]
  (merge {:event/type :input/start-learn :target-action target-action :document-id (h/document-id state)} opts))

(defn- learning
  "state after a Start of a learn for target (opts: :capture ...)."
  [state target & opts]
  (piped state (apply start-learn state target opts)))

(defn- caught
  "What the waiter of the attempt active in state sends once its listener
   takes input-event (laser-show.input.learn): the capture, with the
   attempt's identity."
  [state input-event]
  (let [{:keys [learn-id document-id]} (active state)]
    (cond-> {:event/type :input/learn-captured :learn-id learn-id :input-event input-event}
      document-id (assoc :document-id document-id))))

(defn- captured
  "state after the attempt waiting in state caught input."
  [state input]
  (piped state (caught state input)))

(defn- cancelled
  "The Cancel of the attempt active in state, as its view names it."
  [state]
  (merge {:event/type :input/cancel-learn} (select-keys (active state) [:learn-id :document-id])))

(defn- armed
  "[state sent]: event through the pipeline, its learn listener put in the
   router's slot as the :input/sync-learn effect does (the slot emptied
   first, as a new app's is); sent gets the first event the attempt's
   waiter dispatches."
  [state event]
  (learn/retire!)
  (let [effects (pipe state event)
        sent (promise)]
    (learn/sync! (:input/sync-learn effects) #(deliver sent %))
    [(:state effects) sent]))

(defn- sent
  "The event the waiter dispatched (within 2 s), or nil."
  [p]
  (deref p 2000 nil))

(defn- nothing-sent?
  "Has the waiter dispatched nothing yet (in 150 ms)?"
  [p]
  (nil? (deref p 150 nil)))

(defn- note [n] {:type :note-on :source :midi :channel 0 :note n :velocity 1.0})
(defn- note-key [n] {:source :midi :channel 0 :note n})

(defn- trigger-map [state] (get-in state [:config :input :trigger-map]))

(defn- stop-learning!
  "Retire the attempt in the router's learn slot (its waiter exits), and
   the test's handlers."
  []
  (learn/retire!)
  (router/clear-handlers!))


;; Settings' identify (reactivity plan 24 §2): it waits for one input of its
;; source, says what it was on its card, and binds nothing

(defn- devices-up
  "state with a MIDI input connected and the OSC server listening, as the
   device worker reports them, on the Settings tab."
  [state]
  (-> state
      (assoc-in [:backend :input :midi :connected-devices] #{"Pad"})
      (update-in [:backend :input :osc] assoc :enabled? true :server-running? true)
      (assoc-in [:ui :active-tab] :settings)))

(defn- card [state source] (get-in state [:ui :input-learn :settings source]))

(deftest identify-waits-for-its-source-and-binds-nothing-test
  (let [s0 (devices-up (shown))
        start (pipe s0 {:event/type :input/midi-start-learn})
        s1 (:state start)
        l1 (:learn-id (active s1))]
    (testing "Identify MIDI input: one attempt, for MIDI, that binds nothing; only registered effects"
      (is (uuid? l1))
      (is (= {:learn-id l1 :owner :settings :capture :observe :source :midi} (active s1)))
      (is (= l1 (input/identify-id s1 :midi)))
      (is (nil? (input/identify-id s1 :osc)))
      (is (nil? (learning-target s1)) "no control waits to be bound")
      (is (= {:retire nil :arm {:learn-id l1 :capture :observe :source :midi}} (:input/sync-learn start)))
      (is (every? (set (keys events/effects)) (keys start))))
    (testing "an input of the other source is no MIDI input: nothing shown, it ends as failed"
      (let [s (captured s1 {:type :trigger :source :osc :id "/1/push1" :path "/1/push1" :value 1.0})]
        (is (nil? (active s)))
        (is (= {:status :failed} (card s :midi)))))
    (testing "the MIDI input: on the card, as it is; nothing bound, nothing to undo or save"
      (let [s2 (captured s1 {:type :note-on :source :midi :channel 1 :note 60 :velocity 1.0 :timestamp 5})]
        (is (nil? (active s2)))
        (is (= {:status :captured :input-event {:type :note-on :source :midi :channel 1 :note 60}} (card s2 :midi)))
        (is (= (get-in s0 [:config :input]) (get-in s2 [:config :input])))
        (is (= (get-in s0 [:backend :input :osc :address-mappings]) (get-in s2 [:backend :input :osc :address-mappings])))
        (is (empty? (get-in s2 [:history :undo-stack])))
        (is (false? (get-in s2 [:project :dirty?])))
        (is (= {} (pipe s2 (caught s1 (note 61)))) "a later input changes nothing")
        (testing "a new Identify starts with nothing on its card"
          (is (nil? (card (piped s2 {:event/type :input/midi-start-learn}) :midi))))))
    (testing "Cancel with the id it shows: ready again; any other: {}"
      (is (= {} (pipe s1 {:event/type :input/midi-cancel-learn})))
      (is (= {} (pipe s1 {:event/type :input/midi-cancel-learn :learn-id #uuid "00000000-0000-0000-0000-0000000024ff"})))
      (is (= {} (pipe s1 {:event/type :input/osc-cancel-learn :learn-id l1})) "not OSC's")
      (let [s (piped s1 {:event/type :input/midi-cancel-learn :learn-id l1})]
        (is (nil? (active s)))
        (is (nil? (card s :midi)))))))

(deftest identify-needs-its-source-and-ends-with-it-test
  (let [s0 (devices-up (shown))
        midi (piped s0 {:event/type :input/midi-start-learn})
        osc (piped s0 {:event/type :input/osc-start-learn})
        cell {:type :trigger-cue :target [0 0]}]
    (testing "a source that can't send: {}, superseding nothing"
      (let [s (learning (assoc-in s0 [:backend :input :midi :connected-devices] #{}) cell)]
        (is (= {} (pipe s {:event/type :input/midi-start-learn})) "no MIDI input connected")
        (is (= {} (pipe (assoc-in s [:backend :input :midi :enabled?] false) {:event/type :input/midi-start-learn})))
        (is (= {} (pipe (assoc-in s [:backend :input :osc :server-running?] false) {:event/type :input/osc-start-learn})) "not listening")
        (is (= cell (learning-target s)))))
    (testing "OSC's identify supersedes MIDI's: one attempt"
      (let [s (piped midi {:event/type :input/osc-start-learn})]
        (is (= :osc (:source (active s))))
        (is (nil? (input/identify-id s :midi)))))
    (testing "leaving Settings, or MIDI going (off, or its last input gone), ends MIDI's identify"
      (doseq [event [{:event/type :ui/set-active-tab :tab-id :grid}
                     {:event/type :input/midi-disable}]]
        (let [effects (pipe midi event)]
          (is (nil? (active (:state effects))) (str (:event/type event)))
          (is (= {:retire (:learn-id (active midi)) :arm nil} (:input/sync-learn effects)))))
      (let [asked (pipe midi {:event/type :input/midi-disconnect-all})
            gone (pipe (:state asked) {:event/type :input/midi-disconnected
                                       :request-id (get-in asked [:input/midi-disconnect :request-id])
                                       :connected-devices #{}})]
        (is (some? (active (:state asked))) "until the worker says so")
        (is (nil? (active (:state gone))))
        (is (= {:event/type :trigger/retire :source :midi} (:dispatch gone)) "and its keys still let go (reactivity plan 26)")))
    (testing "OSC stopping ends OSC's only"
      (is (nil? (active (piped osc {:event/type :input/osc-stop-server}))))
      (is (= :midi (:source (active (piped midi {:event/type :input/osc-stop-server}))))))
    (testing "another tab change, or the device lists, leave it waiting"
      (is (= (active midi) (active (piped midi {:event/type :ui/set-active-tab :tab-id :settings}))))
      (is (= (active midi) (active (piped midi {:event/type :input/midi-refresh-devices})))))))

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

(deftest relearning-a-role-moves-its-binding-test
  (try
    (let [hit {:type :vibe :role :hit}
          accent {:type :vibe :role :accent}
          s0 (assoc-in (shown) [:config :input :trigger-map]
                       {(note-key 36) hit {:source :keyboard :id :h} hit (note-key 37) accent})
          learning-hit (learning s0 hit)]
      (is (= hit (learning-target learning-hit)) "learn aims at the role")
      (is (= {:owner :binding :capture :press :target-action hit :document-id (h/document-id s0)}
             (dissoc (active learning-hit) :learn-id)))
      (let [s1 (captured learning-hit (note 50))]
        (is (= {(note-key 50) hit (note-key 37) accent} (trigger-map s1))
            "invariant 6: exactly one key bound to HIT, and ACCENT keeps its own")
        (is (nil? (active s1))))
      (testing "HIT's capture, once ACCENT's learn has started, is another attempt's: nothing"
        (is (= {} (pipe (learning learning-hit accent) (caught learning-hit (note 50))))))
      (testing "a cell still gets a second key"
        (let [cue {:type :trigger-cue :target [0 0]}
              s (assoc-in (shown) [:config :input :trigger-map] {(note-key 60) cue})]
          (is (= {(note-key 60) cue (note-key 61) cue} (trigger-map (captured (learning s cue) (note 61))))))))
    (finally
      (stop-learning!))))

(deftest learning-over-another-action-names-it-in-the-undo-label-test
  (try
    (let [hit {:type :vibe :role :hit}
          learn (fn [bound target]
                  (let [s (-> (shown)
                              (assoc-in [:config :input :trigger-map] (if bound {(note-key 36) bound} {}))
                              (history/mark-clean)
                              (learning target))
                        s' (captured s (note 36))]
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


;; Learn all (Phase 3): one attempt a step, its queue in the attempt

(defn- learn-all
  "Learn all, as the Vibes tab sends it from the show open in state."
  [state]
  {:event/type :vibe/learn-all :document-id (h/document-id state)})

(defn- press-key
  "state after the attempt waiting now caught note n."
  [state n]
  (captured state (note n)))

(def ^:private hit-role {:type :vibe :role :hit})
(def ^:private accent-role {:type :vibe :role :accent})

(deftest learn-all-chains-the-five-roles-test
  (try
    (let [s0 (shown)
          s1 (piped s0 (learn-all s0))
          steps (reductions press-key s1 [36 37 38 39 40])
          done (last steps)]
      (is (= {:queue [:hit :accent :flash :vibe :blackout] :done []} (wizard s1)))
      (is (= hit-role (learning-target s1)) "it starts with HIT")
      (is (= :learn-all (:owner (active s1))))
      (is (empty? (get-in s1 [:history :undo-stack])) "starting it is no undo step")
      (is (= [:accent :flash :vibe :blackout nil] (map (comp :role learning-target) (rest steps)))
          "each key caught begins the next role's attempt, in the same state")
      (is (= 5 (count (distinct (keep (comp :learn-id active) steps)))) "each step an attempt of its own")
      (is (= [[:hit] [:hit :accent] [:hit :accent :flash] [:hit :accent :flash :vibe]]
             (map (comp :done wizard) (butlast (rest steps)))))
      (is (= (zipmap (map note-key [36 37 38 39 40]) (map #(hash-map :type :vibe :role %) [:hit :accent :flash :vibe :blackout]))
             (trigger-map done)))
      (is (nil? (active done)) "invariant 8: it ends after BLACKOUT, nothing left learning")
      (is (= ["Learn Trigger"] (distinct (map :description (get-in done [:history :undo-stack])))))
      (is (= 5 (count (get-in done [:history :undo-stack]))) "one undo step a key")
      (testing "Undo takes a binding back, never an attempt"
        (let [undone (piped done {:event/type :edit/undo})]
          (is (= 4 (count (trigger-map undone))))
          (is (nil? (active undone))))))
    (finally
      (stop-learning!))))

(deftest learn-all-refuses-a-key-twice-test
  (try
    (let [s0 (shown)
          s1 (-> s0 (piped (learn-all s0)) (press-key 36))
          refused (pipe s1 (caught s1 (note 36)))
          s2 (:state refused)
          s3 (press-key s2 37)]
      (is (= accent-role (learning-target s2)) "invariant 7: HIT's key pressed for ACCENT: the step waits for another key")
      (is (= :hit (:refused (wizard s2))) "and says it is HIT's already")
      (is (= (:queue (wizard s1)) (:queue (wizard s2))) "the same queue")
      (is (not= (:learn-id (active s1)) (:learn-id (active s2))) "a fresh attempt for the same step")
      (is (= {:retire (:learn-id (active s1)) :arm (select-keys (active s2) [:learn-id :document-id :capture])}
             (:input/sync-learn refused))
          "its listener in the slot, the step's last one retired")
      (is (= {(note-key 36) hit-role} (trigger-map s2)))
      (is (= (:history s1) (:history s2)) "no edit")
      (is (= {} (pipe s2 (caught s1 (note 37)))) "the refused attempt's capture, again, is nothing now")
      (is (= {:type :vibe :role :flash} (learning-target s3)))
      (is (nil? (:refused (wizard s3))))
      (is (= {(note-key 36) hit-role (note-key 37) accent-role} (trigger-map s3))))
    (testing "a key bound before Learn all moves to the role it is pressed for, as a re-learn does"
      (let [s0 (assoc-in (shown) [:config :input :trigger-map] {(note-key 50) accent-role})
            s (-> s0 (piped (learn-all s0)) (press-key 50))]
        (is (= {(note-key 50) hit-role} (trigger-map s)))
        (is (= accent-role (learning-target s)))))
    (finally
      (stop-learning!))))

(deftest cancel-leaves-no-learning-target-test
  (try
    (let [s0 (shown)
          s1 (-> s0 (piped (learn-all s0)) (press-key 36))]
      (testing "Cancel, for the attempt it shows"
        (let [s (piped s1 (assoc (cancelled s1) :event/type :vibe/wizard-cancel))]
          (is (nil? (active s)) "invariant 8")
          (is (= {(note-key 36) hit-role} (trigger-map s)) "what was learned stays")
          (testing "and the capture of the cancelled step, if it comes late, binds nothing and starts nothing"
            (is (= {} (pipe s (caught s1 (note 37))))))))
      (testing "a Cancel that names no attempt, or not this show's, does nothing"
        (is (= {} (pipe s1 {:event/type :vibe/wizard-cancel})))
        (is (= {} (pipe s1 {:event/type :input/cancel-learn})))
        (is (= {} (pipe s1 (dissoc (cancelled s1) :document-id))))
        (is (= {} (pipe s1 (assoc (cancelled s1) :learn-id (random-uuid))))))
      (testing "a pad's Cancel learning ends it too"
        (is (nil? (active (piped s1 (cancelled s1))))))
      (testing "and so does a Start for anything, ACCENT's own too: that one's key is learned, and nothing follows"
        (doseq [target [{:type :trigger-cue :target [0 0]} {:type :vibe :role :vibe} accent-role]]
          (let [s (learning s1 target)
                s' (press-key s 60)]
            (is (= target (learning-target s)))
            (is (= :binding (:owner (active s))))
            (is (nil? (wizard s)))
            (is (= target (get (trigger-map s') (note-key 60))))
            (is (nil? (active s')))))))
    (finally
      (stop-learning!))))


;; Learning a fader (Phase 5): {:type :macro :slot} with :capture :continuous

(def ^:private slider-1 {:type :macro :slot :slider-1})
(def ^:private slider-2 {:type :macro :slot :slider-2})

(defn- start-fader-learn
  "[state sent]: the learn for a fader slot started on state, its listener
   in the router's slot (armed)."
  [state target]
  (armed state (start-learn state target :capture :continuous)))

(defn- move!
  "MIDI CC control on channel moved through values, through the router."
  [channel control & values]
  (doseq [v values]
    (router/dispatch! (input-events/control-change :midi channel control v))))

(defn- taken
  "The input the attempt's listener took, as its waiter sent it."
  [p]
  (:input-event (sent p)))

(defn- macro-map [state] (get-in state [:config :input :macro-map]))

(deftest learning-capture-mode-test
  (try
    (let [mode #(:capture (active %))
          s0 (shown)
          continuous (learning s0 hit-role :capture :continuous)
          press (learning continuous hit-role)
          fader (learning press slider-1)]
      (is (= :continuous (mode continuous)) "an explicit continuous request, even for a non-macro target")
      (is (= hit-role (learning-target continuous)) "the views still recognize the target")
      (is (= :press (mode press)) "restarting the same target as an ordinary learn replaces its mode")
      (is (= :continuous (mode fader)) "a macro always waits for movement")
      (is (nil? (active (press-key press 36))) "a completed learn ends it")
      (is (nil? (active (piped fader (cancelled fader)))) "so does cancel"))
    (finally
      (stop-learning!))))

(defn- fader-learned
  "state after the learn for target caught a move of MIDI CC control on
   channel 0 from a to b, as its waiter sends it."
  [state target control a b]
  (let [[s p] (start-fader-learn state target)]
    (move! 0 control a b)
    (piped s (sent p))))

(deftest learn-cc-needs-movement-test
  (try
    (let [[s p] (start-fader-learn (shown) slider-1)]
      (is (= slider-1 (learning-target s)) "the learn aims at the slot")
      (is (= (:learn-id (active s)) (router/learn-listener-id)) "its listener is in the router's slot")
      (testing "the doc's example: CC 7 at 0.50, then 0.52: nothing; CC 1 at 0.10, then 0.31: caught"
        (move! 0 7 0.50 0.52 0.47 0.55)
        (move! 0 1 0.10)
        (is (nothing-sent? p) "invariant 9: a noisy CC that wiggles by less than 0.08 is never captured; a first value is no movement")
        (move! 0 1 0.31)
        (is (= [:midi 0 1] (:store-key (taken p))))
        (is (= 0.31 (:value (taken p))))
        (is (= (select-keys (active s) [:learn-id :document-id]) (select-keys (sent p) [:learn-id :document-id]))
            "sent with the attempt's identity")
        (is (= (:learn-id (active s)) (router/learn-listener-id))
            "the listener takes itself out of nothing: the capture's turn retires it")
        (is (= {:retire (:learn-id (active s)) :arm nil} (:input/sync-learn (pipe s (sent p)))))))
    (testing "about 10 of a CC's 127 steps: 10 aren't enough, 11 are, either way"
      (let [[_ p] (start-fader-learn (shown) slider-1)
            step! #(router/dispatch! (midi/midi-msg->event (fixtures/control-change 0 9 %)))]
        (step! 64)
        (step! 74)
        (step! 54)
        (is (nothing-sent? p))
        (step! 75)
        (is (= [:midi 0 9] (:store-key (taken p))))))
    (testing "an OSC fader on an address of its own, and a gamepad trigger"
      (let [[_ p] (start-fader-learn (shown) slider-2)]
        (run! router/dispatch! (osc/osc-msg->events (fixtures/osc-msg "/fader/1" (float 0.2)) {}))
        (run! router/dispatch! (osc/osc-msg->events (fixtures/osc-msg "/fader/1" (float 0.5)) {}))
        (is (= [:osc "/fader/1"] (:store-key (taken p)))))
      (let [[_ p] (start-fader-learn (shown) slider-2)]
        (router/dispatch! (input-events/control-change :gamepad 0 :trigger-left 0.0))
        (router/dispatch! (input-events/control-change :gamepad 0 :trigger-left 0.9))
        (is (= [:gamepad 0 :trigger-left] (:store-key (taken p))))))
    (finally
      (stop-learning!))))

(deftest learn-cc-binds-a-slot-and-replaces-its-old-source-test
  (try
    (let [s0 (shown)
          s1 (fader-learned s0 slider-1 1 0.10 0.31)]
      (is (= {[:midi 0 1] :slider-1} (macro-map s1)))
      (is (nil? (active s1)))
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
      (testing "slider-1's capture, once slider-2's learn has started, binds nothing"
        (let [[s p] (start-fader-learn s1 slider-1)]
          (move! 0 5 0.0 0.5)
          (is (= {} (pipe (learning s slider-2 :capture :continuous) (sent p))))))
      (testing "undone: no binding"
        (is (empty? (macro-map (piped s1 {:event/type :edit/undo}))))))
    (testing "a fader's learn ends Learn all, as any other learn does"
      (let [s0 (shown)
            s (piped s0 (learn-all s0))
            s' (learning s slider-1 :capture :continuous)]
        (is (some? (wizard s)))
        (is (nil? (wizard s')))
        (is (= slider-1 (learning-target s')))))
    (finally
      (stop-learning!))))

(deftest learn-continuous-ignores-notes-test
  (try
    (let [[s p] (start-fader-learn (shown) slider-1)]
      (router/dispatch! (input-events/note-on :midi 0 36 1.0))
      (router/dispatch! (input-events/note-off :midi 0 36))
      (router/dispatch! (input-events/trigger :osc "/hit" :pressed))
      (router/dispatch! (input-events/trigger :gamepad [0 :a] :pressed))
      (router/dispatch! (input-events/note-on :midi 0 37 0.2))
      (is (nothing-sent? p) "invariant 8: notes and buttons never bind a fader")
      (testing "and a note handed to a fader's learn binds nothing, and ends it, saying so"
        (let [s' (captured s (note 36))]
          (is (empty? (macro-map s')))
          (is (= (trigger-map s) (trigger-map s')))
          (is (nil? (active s')))
          (is (= {:text "Learn stopped: that input can't be learned" :level :warning}
                 (dissoc (get-in s' [:ui :notice]) :id)))))
      (testing "even with a :store-key: a note writes no store key"
        (is (empty? (macro-map (captured s (assoc (note 36) :store-key [:midi 0 36])))))))
    (finally
      (stop-learning!))))

(deftest learn-a-role-ignores-cc-test
  (try
    (let [s0 (shown)
          [s p] (armed s0 (start-learn s0 hit-role))]
      (move! 0 1 0.1 0.9 0.0 1.0)
      (is (nothing-sent? p) "probe v6, now pinned: a CC never captures a role's learn")
      (router/dispatch! (input-events/note-on :midi 0 36 1.0))
      (is (= :note-on (:type (taken p))) "a note does")
      (testing "and a CC handed to a role's learn binds nothing, even caught as a fader's learn catches one"
        (let [f0 (shown)
              [fs fp] (armed f0 (start-learn f0 hit-role :capture :continuous))]
          (move! 0 1 0.1 0.9)
          (let [cc (taken fp)]
            (is (= [:midi 0 1] (:store-key cc)))
            (doseq [[learning-state capture] [[fs (sent fp)] [s (caught s cc)]]]
              (let [s' (piped learning-state capture)]
                (is (= (trigger-map learning-state) (trigger-map s')))
                (is (empty? (macro-map s')))
                (is (nil? (active s')))))))))
    (finally
      (stop-learning!))))


(deftest relearning-a-map-pad-moves-its-key-test
  (try
    (let [odd-even {:type :zone-map :target :map/odd-even}
          unison {:type :zone-map :target :map/unison}
          s0 (assoc-in (shown) [:config :input :trigger-map]
                       {(note-key 36) odd-even (note-key 37) unison})
          learning-it (learning s0 odd-even)]
      (is (= odd-even (learning-target learning-it)) "learn aims at the pad")
      (is (= {(note-key 50) odd-even (note-key 37) unison} (trigger-map (captured learning-it (note 50))))
          "Z4-Q2 A: one key a pad, so learning it again moves it; another pad keeps its own")
      (testing "learned over HIT's key: the key is the pad's now, and the label says so"
        (let [s (-> (shown)
                    (assoc-in [:config :input :trigger-map] {(note-key 36) {:type :vibe :role :hit}})
                    (history/mark-clean)
                    (learning odd-even))
              s' (captured s (note 36))]
          (is (= {(note-key 36) odd-even} (trigger-map s')))
          (is (= "Learn Trigger (replaces HIT)" (history/undo-label (:history s'))))))
      (testing "the label names a pad over another pad's key"
        (let [s (-> (assoc-in (shown) [:config :input :trigger-map] {(note-key 37) unison})
                    (history/mark-clean)
                    (learning odd-even))]
          (is (= "Learn Trigger (replaces zone map Unison)" (history/undo-label (:history (captured s (note 37)))))))))
    (finally
      (stop-learning!))))

(deftest relearning-a-pattern-pad-moves-its-key-test
  (try
    (let [chase {:type :pattern-pad :target :chase}
          off {:type :pattern-pad :target :off}
          step {:type :zone-step}
          s0 (assoc-in (shown) [:config :input :trigger-map]
                       {(note-key 36) chase (note-key 37) off (note-key 38) step})]
      (is (= chase (learning-target (learning s0 chase))) "learn aims at the pad")
      (is (= {(note-key 50) chase (note-key 37) off (note-key 38) step} (trigger-map (captured (learning s0 chase) (note 50))))
          "zones plan Z5: one key a pad, so learning it again moves it; the other pads keep theirs")
      (is (= {(note-key 36) chase (note-key 37) off (note-key 51) step} (trigger-map (captured (learning s0 step) (note 51))))
          "the Zone step key moves too")
      (testing "the label names a pad over another one's key"
        (let [s (-> (assoc-in (shown) [:config :input :trigger-map] {(note-key 37) off (note-key 38) step})
                    (history/mark-clean)
                    (learning chase))
              label (fn [k] (history/undo-label (:history (captured s (note k)))))]
          (is (= "Learn Trigger (replaces pattern pad Off)" (label 37)))
          (is (= "Learn Trigger (replaces zone step)" (label 38)))
          (is (= "Learn Trigger (replaces zone map CYCLE)"
                 (history/undo-label (:history (-> (assoc-in (shown) [:config :input :trigger-map]
                                                             {(note-key 39) {:type :zone-map :target :cycle}})
                                                   (history/mark-clean)
                                                   (learning chase)
                                                   (captured (note 39))))))))))
    (finally
      (stop-learning!))))

(deftest map-pad-learn-ignores-a-cc-test
  (try
    (let [odd-even {:type :zone-map :target :map/odd-even}
          s0 (shown)
          [_ p] (armed s0 (start-learn s0 odd-even))]
      (move! 0 1 0.1 0.9 0.0 1.0)
      (is (nothing-sent? p) "moving a control is how a fader learns: a pad never takes it")
      (router/dispatch! (input-events/note-on :midi 0 36 1.0))
      (is (= :note-on (:type (taken p))) "a note does"))
    (finally
      (stop-learning!))))

;; What a key holds lets go when the key can't let go itself
;; (plans/2026-11-18-vibe-mode/07)

(defn- vibe-world []
  (-> (shown)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)))

(deftest midi-disconnect-releases-input-holds-only-test
  (let [s0 (-> (vibe-world)
               (assoc-in [:backend :input :midi :connected-devices] #{"Pad"})
               (assoc-in [:config :input :trigger-map] {(note-key 36) {:type :vibe :role :hit}
                                                       {:source :keyboard :id :j} {:type :vibe :role :flash}}))
        key-down (fn [input-event] {:event/type :trigger/input :input-event (assoc input-event :timestamp 1000) :time 1000})
        held (-> s0
                 (piped (key-down (note 36)))
                 (piped (key-down (input-events/trigger :keyboard :j :pressed)))
                 (piped {:event/type :vibe/press :role :accent :src :pad :time 1000}))]
    (is (= {:hit :input :flash :input :accent :pad} (update-vals (get-in held [:vibe-runtime :held]) :src)))
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
              (is (= :pad (get-in s [:vibe-runtime :held :accent :src])) "a pad's hold stays: its release still comes")
              (is (= :input (get-in s [:vibe-runtime :held :flash :src]))
                  "so does a keyboard key's: only MIDI's keys can't let go (reactivity plan 26)"))))))))

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


;; One attempt, one owner (reactivity plan 24 §7)

(deftest a-capture-after-new-or-open-binds-nothing-test
  ;; Before (reactivity plan 20): the old learn's input bound in the new
  ;; show, which became unsaved and got a "Learn Trigger" undo step
  (try
    (let [show (shown)
          cell {:type :trigger-cue :target [0 0]}]
      (doseq [[label replace] [["File > New" {:event/type :file/new-project :force? true}]
                               ["File > Open" {:event/type :file/project-loaded :file-path "other.zip"
                                               :contents (persistent/project-files show)}]]
              [what start target input!] [["a cell's learn" (start-learn show cell) cell #(router/dispatch! (note 36))]
                                          ["Learn all" (learn-all show) hit-role #(router/dispatch! (note 37))]
                                          ["a fader's learn" (start-learn show slider-1) slider-1 #(move! 0 1 0.1 0.5)]]]
        (testing (str label ", during " what)
          (let [[learning p] (armed show start)
                replacing (pipe learning replace)
                replaced (piped learning replace)
                _ (input!)
                late (sent p)]
            (is (= target (learning-target learning)))
            (is (some? late) "the old attempt's listener, left in the slot here, still caught the input")
            (is (nil? (active replaced)) "the new show isn't learning, so its keys play their bindings")
            (is (= {:retire (:learn-id (active learning)) :arm nil} (:input/sync-learn replacing))
                "and the old attempt's listener goes in the same turn")
            (is (= {} (pipe replaced late)) "the late capture is the old show's: no commit at all")))))
    (finally
      (stop-learning!))))

(def ^:private l1 #uuid "00000000-0000-0000-0000-0000000024b1")
(def ^:private l2 #uuid "00000000-0000-0000-0000-0000000024b2")
(def ^:private l3 #uuid "00000000-0000-0000-0000-0000000024b3")
(def ^:private l4 #uuid "00000000-0000-0000-0000-0000000024b4")

(defn- registered
  "Run the registered :input/sync-learn effect (events/core.clj) on the
   change effects asks for, its waiter sending through dispatch."
  [effects dispatch]
  ((get events/effects :input/sync-learn) (:input/sync-learn effects) dispatch))

(defn- settles?
  "Is (f) true within 2 s?"
  [f]
  (loop [n 0]
    (cond
      (f) true
      (< n 200) (do (Thread/sleep 10) (recur (inc n)))
      :else false)))

(deftest same-target-restart-refuses-retired-results-test
  ;; Case 3: before, the target was the attempt: a Cancel and a Start for
  ;; the same cell let the first attempt's capture bind
  (try
    (with-learn-ids [l1 l2 l3 l4]
      (fn []
        (let [cell {:type :trigger-cue :target [0 0]}
              s0 (shown)
              d1 (h/document-id s0)
              s1 (learning s0 cell)
              held {:event/type :input/learn-captured :learn-id l1 :document-id d1 :input-event (note 60)}
              s2 (piped s1 (cancelled s1))
              s3 (learning s2 cell)]
          (is (= l1 (:learn-id (active s1))))
          (is (nil? (active s2)))
          (is (= {:learn-id l2 :document-id d1 :owner :binding :capture :press :target-action cell} (active s3))
              "the same cell, the same show, another attempt")
          (testing "L1's capture, timeout and Cancel, late: nothing, L2's attempt included"
            (doseq [event [held
                           {:event/type :input/learn-ended :learn-id l1 :document-id d1 :reason :timeout}
                           {:event/type :input/cancel-learn :learn-id l1 :document-id d1}]]
              (is (= {} (pipe s3 event)) (str (:event/type event)))))
          (testing "untagged results and Cancels: nothing"
            (doseq [event [(dissoc held :learn-id)
                           (assoc held :learn-id l2 :document-id nil)
                           (dissoc (assoc held :learn-id l2) :document-id)
                           {:event/type :input/learn-ended :reason :timeout}
                           {:event/type :input/cancel-learn}
                           {:event/type :input/cancel-learn :learn-id l2}]]
              (is (= {} (pipe s3 event)) (pr-str (dissoc event :input-event)))))
          (testing "only 61, for L2, binds, once"
            (let [l2-capture (assoc held :learn-id l2 :input-event (note 61))
                  s4 (piped s3 l2-capture)]
              (is (= {(note-key 61) cell} (trigger-map s4)))
              (is (nil? (active s4)))
              (is (= ["Learn Trigger"] (map :description (get-in s4 [:history :undo-stack]))))
              (is (= {} (pipe s4 l2-capture)) "the second delivery: L2 is over")
              (is (= (get-in s0 [:config :input :macro-map]) (get-in s4 [:config :input :macro-map]))))))))
    (finally
      (stop-learning!)))
  (testing "through the registered effect: a retired attempt's waiter exits; a timeout ends its own attempt; other listeners stay"
    (try
      (router/register-global-handler! ::unrelated (fn [_]))
      (let [cell {:type :trigger-cue :target [0 0]}
            s0 (shown)
            out (atom [])
            dispatch #(swap! out conj %)
            started (pipe s0 (start-learn s0 cell))
            s1 (:state started)
            a (:learn-id (active s1))
            cancel (pipe s1 (cancelled s1))
            idle (:state cancel)]
        (learn/retire!)
        (registered started dispatch)
        (is (= a (router/learn-listener-id)))
        (is (contains? (learn/waiting) a) "its waiter waits")
        (testing "Cancel before any input: no listener, no waiter, nothing sent"
          (registered cancel dispatch)
          (is (nil? (router/learn-listener-id)))
          (is (settles? #(not (contains? (learn/waiting) a))) "the waiter woke and went")
          (router/dispatch! (note 60))
          (is (empty? @out))
          (testing "and the same change again, stale, does nothing"
            (registered cancel dispatch)
            (is (nil? (router/learn-listener-id)))))
        (testing "no input until the deadline (a controlled wait): the attempt ends, saying so, and leaves nothing"
          (let [release (promise)]
            (with-redefs [learn/wait-for-capture (fn [_gate _ms] (deref release 2000 nil) :laser-show.input.learn/timeout)]
              (let [restarted (pipe idle (start-learn idle cell))
                    s2 (:state restarted)
                    b (:learn-id (active s2))]
                (registered restarted dispatch)
                (is (= b (router/learn-listener-id)))
                (deliver release true)
                (is (settles? #(seq @out)))
                (is (= [{:event/type :input/learn-ended :learn-id b :document-id (h/document-id s0) :reason :timeout}] @out))
                (let [ended (pipe s2 (first @out))]
                  (is (nil? (active (:state ended))))
                  (is (= {:text "Learn stopped: no input came in" :level :info} (dissoc (get-in ended [:state :ui :notice]) :id)))
                  (is (= (trigger-map s2) (trigger-map (:state ended))))
                  (registered ended dispatch)
                  (is (nil? (router/learn-listener-id)))
                  (is (settles? #(empty? (learn/waiting)))))))))
        (is (contains? (set (:global-handlers (router/list-handlers))) ::unrelated) "the other listeners stay"))
      (finally
        (stop-learning!)))))

(deftest old-document-capture-cannot-bind-or-cancel-a-fresh-fader-test
  ;; Case 5: before, a capture coming back after New or Open bound in the
  ;; new show: a binding, an undo step and an unsaved show
  (try
    (let [path "show-24.zip"
          d1-show (-> (shown) (assoc-in [:project :current-file] path) history/mark-clean)
          cc! #(router/dispatch! (midi/midi-msg->event (fixtures/control-change 15 7 %)))]
      (doseq [[label replace] [["File > New" {:event/type :file/new-project :force? true}]
                               ["File > Open" {:event/type :file/project-loaded :file-path "other.zip"
                                               :contents (persistent/project-files d1-show)}]
                               ["File > Open, the same file again" {:event/type :file/project-loaded :file-path path
                                                                    :contents (persistent/project-files d1-show)}]]]
        (testing label
          (let [[s1 p1] (start-fader-learn d1-show slider-1)
                _ (run! cc! [64 74 75])
                held (sent p1)
                d2 (piped s1 replace)
                [s2 p2] (start-fader-learn d2 slider-1)
                l2 (:learn-id (active s2))
                d2-id (h/document-id s2)]
            (is (= [:midi 15 7] (get-in held [:input-event :store-key])) "the 11-step movement, on channel 15 (16 to the user)")
            (is (not= (h/document-id s1) d2-id) "another show")
            (is (nil? (active d2)) "the replaced show's attempt ended with it")
            (is (= {:retire (:learn-id (active s1)) :arm nil} (:input/sync-learn (pipe s1 replace))) "its listener retired in the same turn")
            (testing "the old pair, held till now: nothing at all"
              (is (= {} (pipe s2 held))))
            (testing "each check on its own: L2 with D1, then L1 with D2"
              (is (= {} (pipe s2 (assoc held :learn-id l2))))
              (is (= {} (pipe s2 (assoc held :document-id d2-id)))))
            (testing "nor can the old attempt's Cancel end L2"
              (is (= {} (pipe s2 {:event/type :input/cancel-learn :learn-id (:learn-id held) :document-id (:document-id held)}))))
            (is (= slider-1 (learning-target s2)) "D2/L2 still waits")
            (is (empty? (macro-map s2)))
            (is (false? (get-in s2 [:project :dirty?])))
            (is (empty? (get-in s2 [:history :undo-stack])))
            (testing "the same movement for D2/L2 binds"
              (run! cc! [64 74 75])
              (let [s3 (piped s2 (sent p2))]
                (is (= {[:midi 15 7] :slider-1} (macro-map s3)))
                (is (= ["Learn Fader"] (map :description (get-in s3 [:history :undo-stack]))))
                (is (true? (get-in s3 [:project :dirty?])))
                (is (nil? (active s3)))))))))
    (finally
      (stop-learning!))))
