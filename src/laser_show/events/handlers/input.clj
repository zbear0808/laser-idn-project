(ns laser-show.events.handlers.input
  "Event handlers for input device management (MIDI, OSC, Keyboard).
   
   Handles:
   - MIDI device discovery, connection, and configuration
   - OSC server management and address mappings
   - Gamepad and keyboard input switches
   - Input learning and bindings
   
   The MIDI inputs and the OSC server belong to the device worker
   (laser-show.input.devices): a Refresh, Connect, Disconnect, Start Server
   or Stop Server here is a request to it, a registered effect with an id
   of its own, the latest of its kind ([:backend :input :midi :requests],
   [:backend :input :osc :requests]). Its result comes back as an event with
   that id, plain data, and changes the status only while it answers the
   latest request: an earlier one's, coming late, changes nothing."
  (:require [clojure.string :as str]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.events.helpers :as h]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.input.events :as input-events]
            [laser-show.routing.zone-maps :as zone-maps]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.vibes.engine :as engine]
            [laser-show.vibes.library :as library]))


;; MIDI State Path Helper

(defn- midi-path [& keys]
  (into [:backend :input :midi] keys))

(defn- osc-path [& keys]
  (into [:backend :input :osc] keys))

(defn- get-midi-state [state]
  (get-in state (midi-path)))

(defn- get-osc-state [state]
  (get-in state (osc-path)))


;; Requests to the device worker

(defn- request
  "[state id]: state with a new request of kind (:list or :connections for
   :midi, :server for :osc) recorded as device's latest, and its id."
  [state device kind]
  (let [id (random-uuid)]
    [(assoc-in state [:backend :input device :requests kind] id) id]))

(defn- latest?
  "Is event the result of device's latest request of kind, still waiting
   for it? Once one is in, the request is over: the same result again, or
   an earlier request's, isn't."
  [state device kind {:keys [request-id]}]
  (and (some? request-id)
       (= request-id (get-in state [:backend :input device :requests kind]))))

(defn- answered
  "state with device's request of kind over."
  [state device kind]
  (update-in state [:backend :input device :requests] dissoc kind))

(defn- with-error
  "{:state state}, with a notice for error (the device worker's sentence)
   when there is one."
  [state error]
  (if error
    (h/show-notice state error :error)
    {:state state}))


;; MIDI Event Handlers


(defn- handle-midi-enable
  "Enable MIDI input processing."
  [{:keys [state]}]
  {:state (update-in state (midi-path) midi/enable)})

(defn- handle-midi-disable
  "Disable MIDI input processing."
  [{:keys [state]}]
  {:state (update-in state (midi-path) midi/disable)})

(defn- handle-midi-refresh-devices
  "Ask the device worker which MIDI inputs there are: their names come
   back as :input/midi-listed."
  [{:keys [state]}]
  (let [[state id] (request state :midi :list)]
    {:state state
     :input/midi-list {:request-id id}}))

(defn- handle-midi-listed
  "The MIDI inputs the latest Refresh found, for the device list."
  [{:keys [state available-devices] :as event}]
  (if (latest? state :midi :list event)
    {:state (-> state
                (answered :midi :list)
                (assoc-in (midi-path :available-devices) (vec available-devices)))}
    {}))

(defn- handle-midi-connect-device
  "Ask the device worker to connect a MIDI input by name: which inputs are
   connected comes back as :input/midi-connected."
  [{:keys [state device-name]}]
  (let [[state id] (request state :midi :connections)]
    {:state state
     :input/midi-connect {:request-id id :device-name device-name}}))

(defn- handle-midi-auto-connect
  "Ask the device worker to connect the first MIDI input there is (once:
   connected already, it opens nothing)."
  [{:keys [state]}]
  (let [[state id] (request state :midi :connections)]
    {:state state
     :input/midi-connect {:request-id id :auto? true}}))

(defn- handle-midi-connected
  "The MIDI inputs connected once the latest Connect or Auto-Connect was
   done, and a notice when its input couldn't be connected."
  [{:keys [state connected-devices error] :as event}]
  (if (latest? state :midi :connections event)
    (-> state
        (answered :midi :connections)
        (assoc-in (midi-path :connected-devices) (set connected-devices))
        (with-error error))
    {}))

(defn- handle-midi-disconnect-device
  "Ask the device worker to disconnect a MIDI input: :input/midi-disconnected
   says when it has."
  [{:keys [state device-name]}]
  (let [[state id] (request state :midi :connections)]
    {:state state
     :input/midi-disconnect {:request-id id :device-name device-name}}))

(defn- handle-midi-disconnect-all
  "Ask the device worker to disconnect every MIDI input."
  [{:keys [state]}]
  (let [[state id] (request state :midi :connections)]
    {:state state
     :input/midi-disconnect {:request-id id :all? true}}))

(defn- handle-midi-disconnected
  "The MIDI inputs still connected once the latest request disconnected one
   or all of them. Their keys' releases can't come any more, so every role
   a key holds lets go (:vibe/release-all :only :input); a pad's hold
   stays."
  [{:keys [state connected-devices] :as event}]
  (if (latest? state :midi :connections event)
    {:state (-> state
                (answered :midi :connections)
                (assoc-in (midi-path :connected-devices) (set connected-devices)))
     :dispatch {:event/type :vibe/release-all :only :input}}
    {}))

(defn- handle-midi-set-channel-filter
  "Set MIDI channel filter.
   channels: nil for all channels, or set of channel numbers (0-15)"
  [{:keys [state channels]}]
  (let [midi-state (get-midi-state state)
        updated-midi (midi/set-channel-filter midi-state channels)]
    {:state (assoc-in state (midi-path) updated-midi)}))


(defn- handle-midi-start-learn
  "Start MIDI learn mode."
  [{:keys [state]}]
  (let [midi-state (get-midi-state state)
        _ (some-> (midi/get-learn-promise midi-state) (deliver nil))
        updated-midi (midi/start-learn midi-state)
        learn-promise (midi/get-learn-promise updated-midi)]
    ;; Register a global handler to catch the next MIDI event
    (when learn-promise
      (router/register-global-handler! ::midi-learn
                                       (fn [event]
                                         (when (and learn-promise
                                                    (= :midi (:source event)))
                                           (deliver learn-promise event)
                                           (router/unregister-handler! ::midi-learn)))))
    {:state (assoc-in state (midi-path) updated-midi)}))

(defn- handle-midi-cancel-learn
  "Cancel MIDI learn mode."
  [{:keys [state]}]
  (router/unregister-handler! ::midi-learn)
  (let [midi-state (get-midi-state state)
        _ (some-> (midi/get-learn-promise midi-state) (deliver nil))
        updated-midi (midi/cancel-learn midi-state)]
    {:state (assoc-in state (midi-path) updated-midi)}))


;; OSC Event Handlers


(defn- handle-osc-enable
  "Enable OSC input processing."
  [{:keys [state]}]
  {:state (update-in state (osc-path) osc/enable)})

(defn- handle-osc-disable
  "Disable OSC input processing."
  [{:keys [state]}]
  {:state (update-in state (osc-path) osc/disable)})

(defn- handle-osc-start-server
  "Ask the device worker to start the OSC server on the port set now
   ([:backend :input :osc :port], read here: not a :port the button was
   drawn with). Whether it listens comes back as :input/osc-started."
  [{:keys [state]}]
  (let [[state id] (request state :osc :server)]
    {:state state
     :input/osc-start {:request-id id :port (get-in state (osc-path :port) 9000)}}))

(defn- handle-osc-started
  "The latest Start's result. Listening, the server lets its messages in:
   one switch, as the server without :enabled? drops every message
   (osc/create-osc-handler). A port that won't open leaves OSC off, and a
   notice says so."
  [{:keys [state server-running? port error] :as event}]
  (if (latest? state :osc :server event)
    (let [state (answered state :osc :server)]
      (if server-running?
        {:state (update-in state (osc-path) assoc :server-running? true :port port :enabled? true)}
        (h/show-notice (update-in state (osc-path) assoc :server-running? false :enabled? false)
                       (or error (str "Could not listen for OSC on port " port))
                       :error)))
    {}))

(defn- handle-osc-stop-server
  "Stop letting OSC messages in, and ask the device worker to stop the
   server: :input/osc-stopped says when it has."
  [{:keys [state]}]
  (let [[state id] (request state :osc :server)]
    {:state (assoc-in state (osc-path :enabled?) false)
     :input/osc-stop {:request-id id}}))

(defn- handle-osc-stopped
  "The server stopped by the latest Stop: not listening."
  [{:keys [state] :as event}]
  (if (latest? state :osc :server event)
    {:state (-> state
                (answered :osc :server)
                (assoc-in (osc-path :server-running?) false))}
    {}))

(defn- handle-osc-set-port
  "Set the OSC server port (requires restart to take effect)."
  [{:keys [state port]}]
  {:state (assoc-in state (osc-path :port) port)})

(defn- handle-osc-remove-address-mapping
  "Remove an OSC address mapping."
  [{:keys [state address]}]
  (let [osc-state (get-osc-state state)
        updated-osc (osc/remove-address-mapping osc-state address)]
    {:state (assoc-in state (osc-path) updated-osc)}))

(defn- handle-osc-load-default-mappings
  "Load the default OSC address mappings."
  [{:keys [state]}]
  (let [osc-state (get-osc-state state)
        updated-osc (osc/load-default-mappings osc-state)]
    {:state (assoc-in state (osc-path) updated-osc)}))

(defn- handle-osc-start-learn
  "Start OSC learn mode."
  [{:keys [state]}]
  (let [osc-state (get-osc-state state)
        _ (some-> (osc/get-learn-promise osc-state) (deliver nil))
        updated-osc (osc/start-learn osc-state)
        learn-promise (osc/get-learn-promise updated-osc)]
    ;; Register a global handler to catch the next OSC event
    (when learn-promise
      (router/register-global-handler! ::osc-learn
                                       (fn [event]
                                         (when (and learn-promise
                                                    (= :osc (:source event)))
                                           (deliver learn-promise event)
                                           (router/unregister-handler! ::osc-learn)))))
    {:state (assoc-in state (osc-path) updated-osc)}))

(defn- handle-osc-cancel-learn
  "Cancel OSC learn mode."
  [{:keys [state]}]
  (router/unregister-handler! ::osc-learn)
  (let [osc-state (get-osc-state state)
        _ (some-> (osc/get-learn-promise osc-state) (deliver nil))
        updated-osc (osc/cancel-learn osc-state)]
    {:state (assoc-in state (osc-path) updated-osc)}))


;; Session input switches


(defn- set-device-enabled
  "The gamepad poller and keyboard filter read their device's session switch."
  [{:keys [state]} device enabled?]
  {:state (assoc-in state [:backend :input device :enabled?] enabled?)})


;; Generalized Trigger Handlers

;; The promise the current learn future is waiting on. Delivering nil to it
;; lets that future exit when learn is cancelled or restarted.
(defonce ^:private !pending-learn (atom nil))

(defn- release-pending-learn! []
  (when-let [p (first (reset-vals! !pending-learn nil))]
    (deliver p nil)))

(defn- learn-target
  "What a learn is for: the target action's :type and :target (a cell),
   :role (a vibe role) or :slot (a fader). The binding it learns is the
   whole action, its :behavior included."
  [target-action]
  (select-keys target-action [:type :target :role :slot]))

(defn- clear-learning [state]
  (update-in state [:backend :input] dissoc :learning-target :learning-capture))

(defn- wizard-target
  "The learn Learn all waits on ([:ui :vibes :wizard], handlers/vibes.clj):
   its next role's, or nil."
  [state]
  (when-let [role (first (get-in state [:ui :vibes :wizard :queue]))]
    {:type :vibe :role role}))

(defn- end-other-wizard
  "state without Learn all, when the learn for target isn't the one it
   waits on: any other learn (or a cancel) ends it."
  [state target]
  (cond-> state
    (and (get-in state [:ui :vibes :wizard]) (not= target (wizard-target state)))
    (assoc-in [:ui :vibes :wizard] nil)))

(def ^:private fader-travel
  "How far (0..1) a control must move for a fader's learn to take it: about
   10 of a MIDI CC's 127 steps, more than a fader's noise."
  0.08)

(defn- fader-catcher
  "The router handler of a fader's learn: it keeps the first value it sees
   for each input store key an event writes (input-mapper/store-keys: a
   CC, an axis, an OSC value; a note or a button writes none), and delivers
   to p the first event whose key has moved fader-travel or more from it,
   with that key as :store-key."
  [p]
  (let [!first (atom {})]
    (fn [event]
      (let [v (max 0.0 (min 1.0 (double (or (:value event) 0.0))))
            ks (input-mapper/store-keys event)
            seen (swap! !first (fn [m] (reduce #(cond-> %1 (not (contains? %1 %2)) (assoc %2 v)) m ks)))]
        (when-let [k (some #(when (>= (Math/abs (- v (double (get seen %)))) fader-travel) %) ks)]
          (deliver p (assoc event :store-key k))
          (router/unregister-handler! ::generalized-input-learn))))))

(defn- handle-input-start-learn
  "Start generic input learn mode for a target action.
   Registers a temporary global handler to capture the next input event
   and bind it to the target action: a press (a note or a button), or with
   :capture :continuous, as a fader's learn ({:type :macro :slot}) always
   is, a control that moves (fader-catcher)."
  [{:keys [state target-action capture]}]
  (release-pending-learn!)
  (let [learn-promise (promise)
        target (learn-target target-action)
        mode (if (or (= :continuous capture) (= :macro (:type target))) :continuous :press)]
    (reset! !pending-learn learn-promise)
    ;; Register a global handler to catch the next trigger event
    (router/register-global-handler! ::generalized-input-learn
                                     (if (= :continuous mode)
                                       (fader-catcher learn-promise)
                                       (fn [event]
                                         ;; Only capture discrete triggers (NoteOn or Trigger type)
                                         (when (or (input-events/note-on? event)
                                                   (input-events/trigger? event))
                                           (deliver learn-promise event)
                                           (router/unregister-handler! ::generalized-input-learn)))))

    ;; The :input/await-learn effect waits for the captured input off the event
    ;; thread, then dispatches :input/learn-captured, which writes the binding
    ;; through the normal pipeline (undoable, marks the project unsaved).
    {:state (-> state
                (update-in [:backend :input] assoc :learning-target target :learning-capture mode)
                (end-other-wizard target))
     :input/await-learn {:promise learn-promise
                         :on-captured {:event/type :input/learn-captured
                                       :target-action target-action}}}))

(defn- learn-lookup-key
  "Trigger-map key for a captured input event, or nil."
  [input-event]
  (cond
    (input-events/note-on? input-event)
    {:source (:source input-event) :channel (:channel input-event) :note (:note input-event)}

    (input-events/trigger? input-event)
    {:source (:source input-event) :id (:id input-event)}))

(defn- action-name
  "What a binding did, for an undo label: \"cue A1\", \"effect A1\", \"HIT\",
   \"zone map Odd / Even\" (a custom map by its name in state; \"zone map
   CYCLE\"), \"pattern pad Chase\", or its type (\"tap tempo\", \"zone
   step\")."
  [state {:keys [type target role]}]
  (let [cell (fn [[col row]] (str (char (+ 65 row)) (inc col)))]
    (cond
      (and (= :trigger-cue type) (vector? target)) (str "cue " (cell target))
      (and (= :trigger-effect type) (vector? target)) (str "effect " (cell target))
      (and (= :vibe type) role) (str/upper-case (name role))
      (= :zone-map type) (str "zone map " (if (= :cycle target)
                                            "CYCLE"
                                            (or (:name (zone-maps/map-by-id state target)) (some-> target name))))
      (= :pattern-pad type) (str "pattern pad " (zone-patterns/pad-name target))
      :else (str/replace (name (or type :binding)) "-" " "))))

(def ^:private one-key-types
  "The targets that keep one key, so learning one again moves it: a vibe
   role (vibe Phase 2), a zone map pad (zones plan Z4-Q2 A), a pattern pad
   and the Zone step key (Z5). A cell keeps every key it is given."
  #{:vibe :zone-map :pattern-pad :zone-step})

(defn- handle-input-learn-captured
  "Bind the captured input to the target action, :behavior included (ignored
   if learn was cancelled or restarted for another target in the meantime).
   A vibe role and a zone map pad keep one control (one-key-types): its other
   bindings go, so learning it again moves it. The undo label names what the input did before, if anything.
   A role whose key lost its binding so lets go now, if that key holds it
   (engine/release-unbound): its release would reach nothing.
   During Learn all the next role's learn follows, and a key already learned
   for an earlier role of it is refused: the same learn starts again, and
   the wizard says whose the key is (:refused)."
  [{:keys [state input-event target-action] :as event}]
  (let [target (learn-target target-action)
        learning? (= (get-in state [:backend :input :learning-target]) target)
        lookup-key (learn-lookup-key input-event)
        replaced (get-in state [:config :input :trigger-map lookup-key])
        role (when (= :vibe (:type target-action)) (:role target-action))
        one-key? (contains? one-key-types (:type target))
        {:keys [queue done] :as wizard} (when (= target (wizard-target state))
                                          (get-in state [:ui :vibes :wizard]))
        taken (some #(when (library/binds-role? replaced %) %) done)]
    (cond
      (not (and lookup-key learning?))
      {:state (cond-> state
                learning? (clear-learning))}

      taken
      {:state (assoc-in state [:ui :vibes :wizard :refused] taken)
       :dispatch {:event/type :input/start-learn :target-action target-action}}

      :else
      (let [queue (vec (rest queue))]
        (cond-> {:state (-> state
                            (update-in [:config :input :trigger-map]
                                       #(cond->> % one-key? (into {} (remove (fn [[_ action]] (= target (learn-target action)))))))
                            (assoc-in [:config :input :trigger-map lookup-key] target-action)
                            (clear-learning)
                            (cond-> wizard (assoc-in [:ui :vibes :wizard]
                                                     (when (seq queue) {:queue queue :done (conj (vec done) role)})))
                            (engine/release-unbound (get-in state [:config :input :trigger-map]) (h/current-time-ms event)))
                 :history/description (if (and replaced (not= (learn-target replaced) target))
                                        (str "Learn Trigger (replaces " (action-name state replaced) ")")
                                        "Learn Trigger")}
          (and wizard (seq queue))
          (assoc :dispatch {:event/type :input/start-learn :target-action {:type :vibe :role (first queue)}}))))))

(defn- handle-fader-captured
  "Bind the control a fader's learn caught (its input store key, :store-key)
   to the fader's slot in [:config :input :macro-map], where the render
   looks it up each tick: the slot's other control goes (one control a slot),
   and the key's other slot with it (one slot a key). Ignored if the learn
   was cancelled or restarted for another target, and for an input that
   writes no such key: a note or a button never binds a fader."
  [{:keys [state input-event target-action]}]
  (let [target (learn-target target-action)
        learning? (= (get-in state [:backend :input :learning-target]) target)
        k (some #{(:store-key input-event)} (input-mapper/store-keys input-event))
        slot (:slot target-action)]
    (if (and learning? k slot)
      {:state (-> state
                  (update-in [:config :input :macro-map] #(into {} (remove (fn [[_ s]] (= slot s))) %))
                  (assoc-in [:config :input :macro-map k] slot)
                  (clear-learning))
       :history/description "Learn Fader"}
      {:state (cond-> state
                learning? (clear-learning))})))

(defn- handle-input-cancel-learn
  "Cancel generic input learn mode, and Learn all with it."
  [{:keys [state]}]
  (router/unregister-handler! ::generalized-input-learn)
  (release-pending-learn!)
  ;; Remove learning indicator
  {:state (-> state
              (clear-learning)
              (end-other-wizard nil))})

(defn- handle-input-remove-trigger
  "Removes a generic trigger from the mapping."
  [{:keys [state trigger-key]}]
  {:state (update-in state [:config :input :trigger-map] dissoc trigger-key)})

(defn- handle-input-clear-cell-triggers
  "Removes all triggers targeting a specific cell."
  [{:keys [state target-action]}]
  (let [{:keys [type target]} target-action]
    {:state (update-in state [:config :input :trigger-map]
                       (fn [m]
                         (into {}
                               (remove (fn [[_ v]]
                                         (and (= (:type v) type)
                                              (= (:target v) target))))
                               m)))}))

(defn- handle-input-update-cell-trigger-action
  "Updates behavior metadata for all bindings targeting a cell. The behavior
   also becomes the trigger editor's choice, which its Learn button passes on,
   so a binding learned next gets it too."
  [{:keys [state target-action updates] :as event}]
  (let [{:keys [type target]} target-action]
    (let [selected (or (:fx/event event) (:event event))
          behavior (cond
                     (keyword? selected) selected
                     (map? selected) (or (:behavior selected) (:id selected))
                     :else nil)
          updates (merge updates (when behavior {:behavior behavior}))]
      (cond-> {:state (update-in state [:config :input :trigger-map]
                                 (fn [m]
                                   (into {}
                                         (map (fn [[k action]]
                                                [k (if (and (= (:type action) type)
                                                            (= (:target action) target))
                                                     (merge action updates)
                                                     action)]))
                                         m)))}
        behavior (assoc :dispatch {:event/type :ui/update-dialog-data
                                   :dialog-id :trigger-editor
                                   :updates {:behavior behavior}})))))


;; Public API


(defn handle
  "Dispatch input events to their handlers.
   
   Accepts events with :event/type in the :input/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    ;; MIDI events
    :input/midi-enable (handle-midi-enable event)
    :input/midi-disable (handle-midi-disable event)
    :input/midi-refresh-devices (handle-midi-refresh-devices event)
    :input/midi-connect-device (handle-midi-connect-device event)
    :input/midi-disconnect-device (handle-midi-disconnect-device event)
    :input/midi-disconnect-all (handle-midi-disconnect-all event)
    :input/midi-set-channel-filter (handle-midi-set-channel-filter event)
    :input/midi-start-learn (handle-midi-start-learn event)
    :input/midi-cancel-learn (handle-midi-cancel-learn event)
    :input/midi-auto-connect (handle-midi-auto-connect event)
    ;; What the device worker reports back
    :input/midi-listed (handle-midi-listed event)
    :input/midi-connected (handle-midi-connected event)
    :input/midi-disconnected (handle-midi-disconnected event)

    ;; OSC events
    :input/osc-enable (handle-osc-enable event)
    :input/osc-disable (handle-osc-disable event)
    :input/osc-start-server (handle-osc-start-server event)
    :input/osc-stop-server (handle-osc-stop-server event)
    :input/osc-set-port (handle-osc-set-port event)
    :input/osc-remove-address-mapping (handle-osc-remove-address-mapping event)
    :input/osc-load-default-mappings (handle-osc-load-default-mappings event)
    :input/osc-start-learn (handle-osc-start-learn event)
    :input/osc-cancel-learn (handle-osc-cancel-learn event)
    ;; What the device worker reports back
    :input/osc-started (handle-osc-started event)
    :input/osc-stopped (handle-osc-stopped event)

    ;; Session input switches
    :input/gamepad-enable (set-device-enabled event :gamepad true)
    :input/gamepad-disable (set-device-enabled event :gamepad false)
    :input/keyboard-enable (set-device-enabled event :keyboard true)
    :input/keyboard-disable (set-device-enabled event :keyboard false)

    ;; Generalized triggers
    :input/start-learn (handle-input-start-learn event)
    :input/learn-captured (if (= :macro (get-in event [:target-action :type]))
                            (handle-fader-captured event)
                            (handle-input-learn-captured event))
    :input/cancel-learn (handle-input-cancel-learn event)
    :input/remove-trigger (handle-input-remove-trigger event)
    :input/clear-cell-triggers (handle-input-clear-cell-triggers event)
    :input/update-cell-trigger-action (handle-input-update-cell-trigger-action event)

    ;; Unknown event
    {}))
