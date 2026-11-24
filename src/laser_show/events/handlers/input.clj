(ns laser-show.events.handlers.input
  "Event handlers for input device management (MIDI, OSC, Keyboard).
   
   Handles:
   - MIDI device discovery, connection, and configuration
   - OSC server management and address mappings
   - Gamepad and keyboard input switches
   - Input learning and bindings: one learn attempt at a time, its
     identity (:learn-id) and data in [:ui :input-learn] (begin-learn,
     end-learn, wrap-learn-lifetime; laser-show.input.learn runs its
     listener and waiter)
   
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
  "Disable MIDI input processing. The inputs' note-offs can't come in any
   more, so what their keys hold lets go (:trigger/retire, handlers/
   triggers.clj)."
  [{:keys [state]}]
  {:state (update-in state (midi-path) midi/disable)
   :dispatch {:event/type :trigger/retire :source :midi}})

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
   or all of them. Their keys' releases can't come any more, so what the
   MIDI keys hold lets go (:trigger/retire, handlers/triggers.clj); a
   pad's hold, or another source's key's, stays. A disconnect superseded
   by a later request lets nothing go: its answer changes nothing."
  [{:keys [state connected-devices] :as event}]
  (if (latest? state :midi :connections event)
    {:state (-> state
                (answered :midi :connections)
                (assoc-in (midi-path :connected-devices) (set connected-devices)))
     :dispatch {:event/type :trigger/retire :source :midi}}
    {}))

(defn- handle-midi-set-channel-filter
  "Set MIDI channel filter.
   channels: nil for all channels, or set of channel numbers (0-15)"
  [{:keys [state channels]}]
  (let [midi-state (get-midi-state state)
        updated-midi (midi/set-channel-filter midi-state channels)]
    {:state (assoc-in state (midi-path) updated-midi)}))


;; OSC Event Handlers


(defn- handle-osc-enable
  "Enable OSC input processing."
  [{:keys [state]}]
  {:state (update-in state (osc-path) osc/enable)})

(defn- handle-osc-disable
  "Disable OSC input processing. No release comes in any more, so what OSC
   keys hold lets go (:trigger/retire, handlers/triggers.clj)."
  [{:keys [state]}]
  {:state (update-in state (osc-path) osc/disable)
   :dispatch {:event/type :trigger/retire :source :osc}})

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
   notice says so; what OSC keys held (on a server it replaced) lets go."
  [{:keys [state server-running? port error] :as event}]
  (if (latest? state :osc :server event)
    (let [state (answered state :osc :server)]
      (if server-running?
        {:state (update-in state (osc-path) assoc :server-running? true :port port :enabled? true)}
        (assoc (h/show-notice (update-in state (osc-path) assoc :server-running? false :enabled? false)
                              (or error (str "Could not listen for OSC on port " port))
                              :error)
               :dispatch {:event/type :trigger/retire :source :osc})))
    {}))

(defn- handle-osc-stop-server
  "Stop letting OSC messages in, and ask the device worker to stop the
   server: :input/osc-stopped says when it has. From now on no release
   comes in, so what OSC keys hold lets go now (:trigger/retire)."
  [{:keys [state]}]
  (let [[state id] (request state :osc :server)]
    {:state (assoc-in state (osc-path :enabled?) false)
     :input/osc-stop {:request-id id}
     :dispatch {:event/type :trigger/retire :source :osc}}))

(defn- handle-osc-stopped
  "The server stopped by the latest Stop: not listening."
  [{:keys [state] :as event}]
  (if (latest? state :osc :server event)
    {:state (-> state
                (answered :osc :server)
                (assoc-in (osc-path :server-running?) false))}
    {}))

(defn- handle-osc-set-port
  "Set the port Start Server listens on: an integer from 1 to 65535 (else
   nothing changes). It takes effect at the next Start: the Port field
   applies it on Enter or when it loses focus, so a click on Start applies
   what the field shows first."
  [{:keys [state port]}]
  (if (and (int? port) (<= 1 port 65535))
    {:state (assoc-in state (osc-path :port) port)}
    {}))

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


;; Session input switches


(defn- set-device-enabled
  "The gamepad poller and keyboard filter read their device's session switch."
  [{:keys [state]} device enabled?]
  {:state (assoc-in state [:backend :input device :enabled?] enabled?)})


;; Learning (reactivity plan 24): one attempt at a time
;;
;; [:ui :input-learn :active] is the attempt waiting for an input, or nil:
;; {:learn-id :owner :capture ...}. Its :learn-id is new each time (the
;; pipeline puts a :fresh-learn-id on the events that can begin one,
;; events/core.clj), so a capture, a timeout or a Cancel of an attempt
;; before it, for the very same target too, names another id and does
;; nothing ({}). An attempt that binds a control into the show (:owner
;; :binding, a cell's, a pad's, a role's or a fader's; :trigger-editor, the
;; editor's for its cell; :learn-all) also has the :document-id of the show
;; it was started in, and only what comes back for that show, while it is
;; open, binds. Settings' identify (:owner :settings) binds nothing: it says
;; on its card what came in ([:ui :input-learn :settings source]).
;; wrap-learn-lifetime ends an attempt whose owner went (the show, the
;; editor, Settings, its source), and has the router's learn slot follow
;; the active attempt (:input/sync-learn, laser-show.input.learn).

(def ^:private binding-owners
  "The owners of an attempt that binds a control into the show."
  #{:binding :trigger-editor :learn-all})

(defn active-learn
  "The learn attempt waiting for an input, or nil."
  [state]
  (get-in state [:ui :input-learn :active]))

(defn- binds?
  "Does attempt bind a control into the show (not Settings' identify)?"
  [attempt]
  (contains? binding-owners (:owner attempt)))

(defn learn-target
  "What a learn is for: the target action's :type and :target (a cell),
   :role (a vibe role) or :slot (a fader). The binding it learns is the
   whole action, its :behavior included."
  [target-action]
  (select-keys target-action [:type :target :role :slot]))

(defn learning-target
  "The target of the attempt waiting to bind a control (learn-target), or
   nil."
  [state]
  (let [attempt (active-learn state)]
    (when (binds? attempt)
      (learn-target (:target-action attempt)))))

(defn binding-attempt
  "The attempt waiting to bind a control, as a view shows and cancels it:
   {:target (learn-target) :learn-id :document-id}, or nil."
  [state]
  (let [attempt (active-learn state)]
    (when (binds? attempt)
      {:target (learn-target (:target-action attempt))
       :learn-id (:learn-id attempt)
       :document-id (:document-id attempt)})))

(defn source-available?
  "Can Settings identify an input of source (:midi, :osc) now, by the
   status the device worker reports (reactivity plan 25): MIDI on with an
   input connected, OSC on with its server listening?"
  [state source]
  (case source
    :midi (let [m (get-midi-state state)]
            (and (midi/enabled? m) (boolean (seq (:connected-devices m)))))
    :osc (let [o (get-osc-state state)]
           (and (true? (:enabled? o)) (true? (:server-running? o))))
    false))

(defn identify-id
  "The :learn-id of Settings' identify of source while it waits, or nil."
  [state source]
  (let [attempt (active-learn state)]
    (when (and (= :settings (:owner attempt)) (= source (:source attempt)))
      (:learn-id attempt))))

(defn- editor-cell
  "The cell the trigger editor is open on, as a target ({:type
   :trigger-cue|:trigger-effect :target [col row]}), or nil."
  [state]
  (let [{:keys [open? col row cell-mode]} (get-in state [:ui :dialogs :trigger-editor])]
    (when (and open? (some? col) (some? row))
      {:type (if (= :effects cell-mode) :trigger-effect :trigger-cue) :target [col row]})))

(defn cell-behavior
  "The behaviour the trigger editor shows for the cell target, which a
   learn it starts binds with: the one of the learn waiting for that cell
   (the last choice made while it waits), else the one of the cell's first
   binding, else the editor's choice. :momentary or :follow: a
   :retrigger, :toggle, :quantized-decay or :one-shot binding from an older
   project follows the cue's mode, so it is :follow."
  [state target]
  (let [attempt (active-learn state)
        pending (when (and (binds? attempt) (= target (learn-target (:target-action attempt))))
                  (:target-action attempt))
        bound (some (fn [[_ action]] (when (= target (learn-target action)) action))
                    (get-in state [:config :input :trigger-map]))
        behavior (cond
                   pending (:behavior pending)
                   bound (:behavior bound)
                   :else (get-in state [:ui :dialogs :trigger-editor :behavior]))]
    (if (= :momentary behavior) :momentary :follow)))

(defn- current?
  "Does event name the active attempt: its non-nil :learn-id, and for one
   that binds into the show, the :document-id of that attempt, which is the
   show open now?"
  [state {:keys [learn-id document-id]}]
  (let [attempt (active-learn state)]
    (and (some? learn-id)
         (= learn-id (:learn-id attempt))
         (or (not (binds? attempt))
             (and (some? document-id)
                  (= document-id (:document-id attempt))
                  (= document-id (h/document-id state)))))))

(defn- over
  "state with no attempt waiting."
  [state]
  (assoc-in state [:ui :input-learn :active] nil))

(defn begin-learn
  "state with request ({:owner :capture ...}) the active attempt, fresh-id
   its :learn-id: the attempt before it, whatever it was (Learn all too),
   is over. Settings' identify of a source starts with nothing on its card."
  [state request fresh-id]
  (cond-> (assoc-in state [:ui :input-learn :active] (assoc request :learn-id fresh-id))
    (= :settings (:owner request)) (update-in [:ui :input-learn :settings] dissoc (:source request))))

(defn end-learn
  "state with no attempt waiting, when identity (an event's :learn-id, and
   :document-id for one that binds) names the active attempt; state as it
   is otherwise."
  [state identity]
  (cond-> state
    (current? state identity) over))

(defn start
  "Effects of a view's Start of request ({:owner :capture :target-action
   ...}), sent with the show it was in (:document-id) and given a
   :fresh-learn-id: the attempt it begins supersedes whatever waited. {}
   for a Start that names no show, or one that isn't open now: it
   supersedes nothing."
  [{:keys [state fresh-learn-id document-id]} request]
  (if (and (some? fresh-learn-id) (some? document-id) (= document-id (h/document-id state)))
    {:state (begin-learn state (assoc request :document-id document-id) fresh-learn-id)}
    {}))

(defn cancel
  "Effects of a Cancel of the attempt it names (:learn-id, and
   :document-id for one that binds, as its view showed them): {} for an
   attempt that isn't the active one."
  [{:keys [state] :as event}]
  (if (current? state event)
    {:state (over state)}
    {}))

(defn- handle-identify
  "Settings' Identify MIDI input / Identify OSC input: wait for the next
   input of source, to say on its card what it was. It binds nothing and
   lets every input go where it goes. Only for a source that can send one
   now (source-available?); else {}, superseding nothing."
  [{:keys [state fresh-learn-id]} source]
  (if (and (some? fresh-learn-id) (source-available? state source))
    {:state (begin-learn state {:owner :settings :capture :observe :source source} fresh-learn-id)}
    {}))

(defn- handle-identify-cancel
  "Cancel of Settings' identify of source, with the :learn-id its card
   showed: the card is ready again. {} for another attempt's."
  [{:keys [state learn-id]} source]
  (if (and (some? learn-id) (= learn-id (identify-id state source)))
    {:state (over state)}
    {}))

(defn- handle-input-start-learn
  "Start a learn for a target action: a press (a note or a button), or
   with :capture :continuous, as a fader's learn ({:type :macro :slot})
   always is, a control that moves. The trigger editor's (:owner
   :trigger-editor, the cell as :target-action) is for the cell it is open
   on, with the behaviour it shows then (cell-behavior); a choice made
   while it waits goes into the attempt (:input/update-cell-trigger-action).
   A Start supersedes any other attempt, Learn all's for the same role too."
  [{:keys [state target-action capture owner] :as event}]
  (let [target (learn-target target-action)]
    (cond
      (empty? target) {}

      (= :trigger-editor owner)
      (if (= target (editor-cell state))
        (start event {:owner :trigger-editor :capture :press
                      :target-action (assoc target :behavior (cell-behavior state target))})
        {})

      :else
      (start event {:owner :binding
                    :capture (if (or (= :continuous capture) (= :macro (:type target))) :continuous :press)
                    :target-action target-action}))))

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

(defn- ended
  "Effects of attempt (the active one in state) ending with nothing
   learned, for reason (:timeout, :failed): Settings' card says so, a
   learn's is a notice. What Learn all bound before stays."
  [state attempt reason]
  (let [state (over state)]
    (if (= :settings (:owner attempt))
      {:state (assoc-in state [:ui :input-learn :settings (:source attempt)] {:status reason})}
      (if (= :timeout reason)
        (h/show-notice state "Learn stopped: no input came in" :info)
        (h/show-notice state "Learn stopped: that input can't be learned" :warning)))))

(def ^:private shown-fields
  "What Settings' card keeps of the input it identified: what it says, not
   its value or its time."
  [:type :source :channel :note :control :program :id :path])

(defn- identified
  "Settings' identify caught input-event: its card shows it (:captured), if
   it is of the source it waits for; else it ends as failed."
  [state attempt input-event]
  (if (= (:source attempt) (:source input-event))
    {:state (assoc-in (over state) [:ui :input-learn :settings (:source attempt)]
                      {:status :captured
                       :input-event (into {} (filter (comp some? val)) (select-keys input-event shown-fields))})}
    (ended state attempt :failed)))

(defn- press-captured
  "Bind the press the attempt caught to its target action (read from the
   attempt, :behavior included). A vibe role and a zone map pad keep one
   control (one-key-types): its other bindings go, so learning it again
   moves it. The undo label names what the input did before, if anything.
   A role whose key lost its binding so lets go now, if that key holds it
   (engine/release-unbound): its release would reach nothing.
   During Learn all the next role's attempt begins in the same state, and a
   key already learned for an earlier role of it is refused: the same step
   waits again, as a new attempt, and the wizard says whose the key is
   (:refused). Input that isn't a press ends the attempt as failed."
  [{:keys [state input-event fresh-learn-id] :as event} attempt]
  (let [target-action (:target-action attempt)
        target (learn-target target-action)
        lookup-key (learn-lookup-key input-event)
        replaced (get-in state [:config :input :trigger-map lookup-key])
        role (when (= :vibe (:type target-action)) (:role target-action))
        one-key? (contains? one-key-types (:type target))
        {:keys [queue done] :as wizard} (:wizard attempt)
        taken (when lookup-key (some #(when (library/binds-role? replaced %) %) done))]
    (cond
      (or (nil? lookup-key) (not= :press (:capture attempt)))
      (ended state attempt :failed)

      taken
      {:state (if fresh-learn-id
                (begin-learn state (assoc-in attempt [:wizard :refused] taken) fresh-learn-id)
                (over state))}

      :else
      (let [queue (vec (rest queue))
            bound (-> state
                      (update-in [:config :input :trigger-map]
                                 #(cond->> % one-key? (into {} (remove (fn [[_ action]] (= target (learn-target action)))))))
                      (assoc-in [:config :input :trigger-map lookup-key] target-action)
                      (engine/release-unbound (get-in state [:config :input :trigger-map]) (h/current-time-ms event)))]
        {:state (if (and wizard (seq queue) fresh-learn-id)
                  (begin-learn bound {:owner :learn-all :capture :press :document-id (:document-id attempt)
                                      :target-action {:type :vibe :role (first queue)}
                                      :wizard {:queue queue :done (conj (vec done) role)}}
                               fresh-learn-id)
                  (over bound))
         :history/description (if (and replaced (not= (learn-target replaced) target))
                                (str "Learn Trigger (replaces " (action-name state replaced) ")")
                                "Learn Trigger")}))))

(defn- fader-captured
  "Bind the control a fader's learn caught (its input store key, :store-key,
   one the input really writes) to the fader's slot in [:config :input
   :macro-map], where the render looks it up each tick: the slot's other
   control goes (one control a slot), and the key's other slot with it (one
   slot a key). An input that writes no such key (a note or a button never
   binds a fader) ends the attempt as failed."
  [{:keys [state input-event]} attempt]
  (let [k (some #{(:store-key input-event)} (input-mapper/store-keys input-event))
        slot (get-in attempt [:target-action :slot])]
    (if (and k slot (= :continuous (:capture attempt)))
      {:state (-> state
                  (update-in [:config :input :macro-map] #(into {} (remove (fn [[_ s]] (= slot s))) %))
                  (assoc-in [:config :input :macro-map k] slot)
                  (over))
       :history/description "Learn Fader"}
      (ended state attempt :failed))))

(defn- handle-learn-captured
  "What the active attempt's waiter caught (laser-show.input.learn), with
   the attempt's :learn-id (and :document-id): Settings' card shows it, or
   it binds, the action read from the attempt in state, never from the
   event. {} for anything else: another attempt's, the same target's
   before it, one without an id, one for a show that isn't open any more."
  [{:keys [state] :as event}]
  (let [attempt (active-learn state)]
    (cond
      (not (current? state event)) {}
      (= :settings (:owner attempt)) (identified state attempt (:input-event event))
      (= :macro (get-in attempt [:target-action :type])) (fader-captured event attempt)
      :else (press-captured event attempt))))

(defn- handle-learn-ended
  "The active attempt's waiter ended with nothing (:reason :timeout after
   its deadline, or :failed): it is over, and says so (ended). {} for any
   other attempt's."
  [{:keys [state reason] :as event}]
  (if (current? state event)
    (ended state (active-learn state) (if (= :timeout reason) :timeout :failed))
    {}))

(defn- invalidated?
  "Has attempt, active in after, lost what it belongs to: the show it binds
   into (another one is open), its trigger editor (closed, or on another
   cell), Settings (left: before was on its tab, after isn't) or the source
   it identifies (it can't send now)?"
  [before after attempt]
  (case (:owner attempt)
    :settings (or (and (= :settings (get-in before [:ui :active-tab]))
                       (not= :settings (get-in after [:ui :active-tab])))
                  (not (source-available? after (:source attempt))))
    :trigger-editor (or (not= (:document-id attempt) (h/document-id after))
                        (not= (learn-target (:target-action attempt)) (editor-cell after)))
    (not= (:document-id attempt) (h/document-id after))))

(defn wrap-learn-lifetime
  "Middleware (events/core.clj's pipeline): after the handler, an attempt
   whose owner went (invalidated?: New and Open, closing or retargeting its
   editor, leaving Settings, its source off) is over in the same state.
   Then, when the active attempt isn't the one before (another :learn-id,
   or none), the effects get :input/sync-learn {:retire (the id before)
   :arm (the active one's :learn-id :document-id :capture :source, or
   nil)}, which runs right after :state (events/core.clj): the router's
   learn slot follows. An attempt whose id stays (a behaviour chosen while
   it waits) keeps its listener, its deadline and its fader's first values."
  [handler]
  (fn [{before :state :as event}]
    (let [effects (handler event)
          after (:state effects)]
      (if (nil? after)
        effects
        (let [attempt (active-learn after)
              after (cond-> after
                      (and attempt (invalidated? before after attempt)) over)
              was (:learn-id (active-learn before))
              now (active-learn after)]
          (cond-> (assoc effects :state after)
            (not= was (:learn-id now))
            (assoc :input/sync-learn {:retire was
                                      :arm (some-> now (select-keys [:learn-id :document-id :capture :source]))})))))))

(defn- handle-input-cancel-learn
  "A learn's Cancel (a cell's, a pad's, a role's, a fader's, the trigger
   editor's, Escape's), for the attempt it names (cancel)."
  [event]
  (cancel event))

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

(defn- with-behavior
  "Trigger map m with behavior on each binding of target that has another."
  [m target behavior]
  (reduce-kv (fn [acc k action]
               (if (and (= target (learn-target action)) (not= behavior (:behavior action)))
                 (assoc acc k (assoc action :behavior behavior))
                 acc))
             m
             m))

(defn- handle-input-update-cell-trigger-action
  "The trigger editor's behaviour choice (:fx/event: an option of its list,
   or a keyword) for the cell of target-action, sent with the show it was
   made in (:document-id): the cell's bindings get it, and so do the
   editor's choice and the learn waiting for the cell, if any, all in one
   state, so the binding learned next gets the choice shown, while it waits
   too. Only :follow or :momentary, for the cell the editor is open on, in
   the show open now; else {}."
  [{:keys [state target-action document-id] :as event}]
  (let [selected (or (:fx/event event) (:event event))
        behavior (cond
                   (keyword? selected) selected
                   (map? selected) (or (:behavior selected) (:id selected))
                   :else nil)
        target (learn-target target-action)
        attempt (active-learn state)]
    (if (and (#{:follow :momentary} behavior)
             (some? document-id)
             (= document-id (h/document-id state))
             (= target (editor-cell state)))
      {:state (cond-> (-> state
                          (update-in [:config :input :trigger-map] with-behavior target behavior)
                          (assoc-in [:ui :dialogs :trigger-editor :behavior] behavior))
                (and (binds? attempt) (= target (learn-target (:target-action attempt))))
                (assoc-in [:ui :input-learn :active :target-action :behavior] behavior))}
      {})))


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
    :input/midi-start-learn (handle-identify event :midi)
    :input/midi-cancel-learn (handle-identify-cancel event :midi)
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
    :input/osc-start-learn (handle-identify event :osc)
    :input/osc-cancel-learn (handle-identify-cancel event :osc)
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
    :input/learn-captured (handle-learn-captured event)
    :input/learn-ended (handle-learn-ended event)
    :input/cancel-learn (handle-input-cancel-learn event)
    :input/remove-trigger (handle-input-remove-trigger event)
    :input/clear-cell-triggers (handle-input-clear-cell-triggers event)
    :input/update-cell-trigger-action (handle-input-update-cell-trigger-action event)

    ;; Unknown event
    {}))
