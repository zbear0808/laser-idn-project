(ns laser-show.events.handlers.input
  "Event handlers for input device management (MIDI, OSC, Keyboard).
   
   Handles:
   - MIDI device discovery, connection, and configuration
   - OSC server management and address mappings
   - Learn modes for MIDI and OSC
   
   State is stored at [:backend :input :midi] and [:backend :input :osc]"
  (:require [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.state.core :as state]))


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
  {:state (assoc-in state (midi-path :enabled) true)})

(defn- handle-midi-disable
  "Disable MIDI input processing."
  [{:keys [state]}]
  {:state (assoc-in state (midi-path :enabled) false)})

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
    {:state (assoc-in state (midi-path) updated-midi)
     :learn-promise learn-promise}))

(defn- handle-midi-cancel-learn
  "Cancel MIDI learn mode."
  [{:keys [state]}]
  (router/unregister-handler! ::midi-learn)
  (let [midi-state (get-midi-state state)
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
  {:state (assoc-in state (osc-path :enabled) true)})

(defn- handle-osc-disable
  "Disable OSC input processing."
  [{:keys [state]}]
  {:state (assoc-in state (osc-path :enabled) false)})

(defn- handle-osc-start-server
  "Start the OSC server."
  [{:keys [state port]}]
  (let [osc-state (get-osc-state state)
        server-port (or port (:port osc-state 9000))
        updated-osc (osc/start-server
                      osc-state
                      server-port
                      router/dispatch!
                      #(get-osc-state (state/get-raw-state)))]
    {:state (assoc-in state (osc-path) updated-osc)}))

(defn- handle-osc-stop-server
  "Stop the OSC server."
  [{:keys [state]}]
  (let [osc-state (get-osc-state state)
        updated-osc (osc/stop-server osc-state)]
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
    {:state (assoc-in state (osc-path) updated-osc)
     :learn-promise learn-promise}))

(defn- handle-osc-cancel-learn
  "Cancel OSC learn mode."
  [{:keys [state]}]
  (router/unregister-handler! ::osc-learn)
  (let [osc-state (get-osc-state state)
        updated-osc (osc/cancel-learn osc-state)]
    {:state (assoc-in state (osc-path) updated-osc)}))


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
    
    ;; Unknown event
    {}))
