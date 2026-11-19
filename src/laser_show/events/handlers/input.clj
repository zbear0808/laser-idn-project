(ns laser-show.events.handlers.input
  "Event handlers for input device management (MIDI, OSC, Keyboard).
   
   Handles:
   - MIDI device discovery, connection, and configuration
   - OSC server management and address mappings
   - Learn modes for MIDI and OSC
   
   State is stored at [:backend :input :midi] and [:backend :input :osc]"
  (:require [clojure.string :as str]
            [laser-show.events.helpers :as h]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.input.events :as input-events]
            [laser-show.state.core :as state]
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
  "Refresh list of available MIDI devices.
   This is a query operation - doesn't change state but returns available devices."
  [{:keys [state]}]
  ;; Store discovered devices in state for UI access
  (let [devices (midi/list-device-names)]
    {:state (assoc-in state (midi-path :available-devices) (vec devices))}))

(defn- handle-midi-connect-device
  "Connect to a MIDI input device."
  [{:keys [state device-name]}]
  (let [midi-state (get-midi-state state)
        ;; Use state/get-raw-state for background thread access
        updated-midi (midi/connect-device
                      midi-state
                      device-name
                      router/dispatch!
                      #(get-midi-state (state/get-raw-state)))]
    {:state (assoc-in state (midi-path) updated-midi)}))

(defn- handle-midi-disconnect-device
  "Disconnect from a MIDI device."
  [{:keys [state device-name]}]
  (let [midi-state (get-midi-state state)
        updated-midi (midi/disconnect-device midi-state device-name)]
    {:state (assoc-in state (midi-path) updated-midi)}))

(defn- handle-midi-disconnect-all
  "Disconnect from all MIDI devices."
  [{:keys [state]}]
  (let [midi-state (get-midi-state state)
        updated-midi (midi/disconnect-all midi-state)]
    {:state (assoc-in state (midi-path) updated-midi)}))

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

(defn- handle-midi-auto-connect
  "Auto-connect to the first available MIDI device."
  [{:keys [state]}]
  (let [midi-state (get-midi-state state)
        updated-midi (midi/auto-connect
                      midi-state
                      router/dispatch!
                      #(get-midi-state (state/get-raw-state)))]
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
  "Start the OSC server, and let its messages in: one switch, as the
   server without :enabled? drops every message (osc/create-osc-handler).
   A port that won't open leaves OSC off, and a notice says so."
  [{:keys [state port]}]
  (let [osc-state (get-osc-state state)
        server-port (or port (:port osc-state 9000))
        updated-osc (osc/start-server
                     osc-state
                     server-port
                     router/dispatch!
                     #(get-osc-state (state/get-raw-state)))]
    (if (:server-running? updated-osc)
      {:state (assoc-in state (osc-path) (osc/enable updated-osc))}
      (h/show-notice (assoc-in state (osc-path) updated-osc)
                     (str "Could not listen for OSC on port " server-port)
                     :error))))

(defn- handle-osc-stop-server
  "Stop the OSC server, and stop letting messages in."
  [{:keys [state]}]
  (let [osc-state (get-osc-state state)
        updated-osc (osc/disable (osc/stop-server osc-state))]
    {:state (assoc-in state (osc-path) updated-osc)}))

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


;; Gamepad Event Handlers


(defn- handle-gamepad-set-enabled
  "Enable or disable gamepad input. The poll thread checks this flag every
   poll, so no device work happens here."
  [{:keys [state]} enabled?]
  {:state (assoc-in state [:backend :input :gamepad :enabled?] enabled?)})


;; Generalized Trigger Handlers

;; The promise the current learn future is waiting on. Delivering nil to it
;; lets that future exit when learn is cancelled or restarted.
(defonce ^:private !pending-learn (atom nil))

(defn- release-pending-learn! []
  (when-let [p (first (reset-vals! !pending-learn nil))]
    (deliver p nil)))

(defn- learn-target
  "What a learn is for: the target action's :type and :target (a cell) or
   :role (a vibe role). The binding it learns is the whole action, its
   :behavior included."
  [target-action]
  (select-keys target-action [:type :target :role]))

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

(defn- handle-input-start-learn
  "Start generic input learn mode for a target action.
   Registers a temporary global handler to capture the next input event
   and bind it to the target action."
  [{:keys [state target-action]}]
  (release-pending-learn!)
  (let [learn-promise (promise)]
    (reset! !pending-learn learn-promise)
    ;; Register a global handler to catch the next trigger event
    (router/register-global-handler! ::generalized-input-learn
                                     (fn [event]
                                       ;; Only capture discrete triggers (NoteOn or Trigger type)
                                       (when (or (input-events/note-on? event)
                                                 (input-events/trigger? event))
                                         (deliver learn-promise event)
                                         (router/unregister-handler! ::generalized-input-learn))))

    ;; The :input/await-learn effect waits for the captured input off the event
    ;; thread, then dispatches :input/learn-captured, which writes the binding
    ;; through the normal pipeline (undoable, marks the project unsaved).
    {:state (-> state
                (assoc-in [:backend :input :learning-target] (learn-target target-action))
                (end-other-wizard (learn-target target-action)))
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
   or its type (\"tap tempo\")."
  [{:keys [type target role]}]
  (let [cell (fn [[col row]] (str (char (+ 65 row)) (inc col)))]
    (cond
      (and (= :trigger-cue type) (vector? target)) (str "cue " (cell target))
      (and (= :trigger-effect type) (vector? target)) (str "effect " (cell target))
      (and (= :vibe type) role) (str/upper-case (name role))
      :else (str/replace (name (or type :binding)) "-" " "))))

(defn- handle-input-learn-captured
  "Bind the captured input to the target action, :behavior included (ignored
   if learn was cancelled or restarted for another target in the meantime).
   A vibe role keeps one control: its other bindings go, so learning it again
   moves it. The undo label names what the input did before, if anything.
   During Learn all the next role's learn follows, and a key already learned
   for an earlier role of it is refused: the same learn starts again, and
   the wizard says whose the key is (:refused)."
  [{:keys [state input-event target-action]}]
  (let [target (learn-target target-action)
        learning? (= (get-in state [:backend :input :learning-target]) target)
        lookup-key (learn-lookup-key input-event)
        replaced (get-in state [:config :input :trigger-map lookup-key])
        role (when (= :vibe (:type target-action)) (:role target-action))
        {:keys [queue done] :as wizard} (when (= target (wizard-target state))
                                          (get-in state [:ui :vibes :wizard]))
        taken (some #(when (library/binds-role? replaced %) %) done)]
    (cond
      (not (and lookup-key learning?))
      {:state (cond-> state
                learning? (update-in [:backend :input] dissoc :learning-target))}

      taken
      {:state (assoc-in state [:ui :vibes :wizard :refused] taken)
       :dispatch {:event/type :input/start-learn :target-action target-action}}

      :else
      (let [queue (vec (rest queue))]
        (cond-> {:state (-> state
                            (update-in [:config :input :trigger-map]
                                       #(cond->> % role (into {} (remove (fn [[_ action]] (library/binds-role? action role))))))
                            (assoc-in [:config :input :trigger-map lookup-key] target-action)
                            (update-in [:backend :input] dissoc :learning-target)
                            (cond-> wizard (assoc-in [:ui :vibes :wizard]
                                                     (when (seq queue) {:queue queue :done (conj (vec done) role)}))))
                 :history/description (if (and replaced (not= (learn-target replaced) target))
                                        (str "Learn Trigger (replaces " (action-name replaced) ")")
                                        "Learn Trigger")}
          (and wizard (seq queue))
          (assoc :dispatch {:event/type :input/start-learn :target-action {:type :vibe :role (first queue)}}))))))

(defn- handle-input-cancel-learn
  "Cancel generic input learn mode, and Learn all with it."
  [{:keys [state]}]
  (router/unregister-handler! ::generalized-input-learn)
  (release-pending-learn!)
  ;; Remove learning indicator
  {:state (-> state
              (update-in [:backend :input] dissoc :learning-target)
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

    ;; Gamepad events
    :input/gamepad-enable (handle-gamepad-set-enabled event true)
    :input/gamepad-disable (handle-gamepad-set-enabled event false)

    ;; Generalized triggers
    :input/start-learn (handle-input-start-learn event)
    :input/learn-captured (handle-input-learn-captured event)
    :input/cancel-learn (handle-input-cancel-learn event)
    :input/remove-trigger (handle-input-remove-trigger event)
    :input/clear-cell-triggers (handle-input-clear-cell-triggers event)
    :input/update-cell-trigger-action (handle-input-update-cell-trigger-action event)

    ;; Unknown event
    {}))
