(ns laser-show.events.handlers.connection
  "Event handlers for IDN connection management and configuration.
   
   Handles:
   - IDN connection lifecycle (connect/disconnect)
   - Multi-engine streaming (one engine per projector)
   - Connection status updates
   - Configuration updates
   
   The connection system now supports multi-projector streaming:
   - :idn/start-multi-streaming - Start streaming to all enabled projectors
   - :idn/stop-multi-streaming - Stop all streaming engines
   - Legacy single-projector handlers are kept for backwards compatibility")


;; Legacy Single-Projector Handlers (kept for backwards compatibility)


(defn- handle-idn-connect
  "Start IDN connection (legacy single-projector mode).
   For multi-projector streaming, use :idn/start-multi-streaming instead."
  [{:keys [host port state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connecting?] true)
              (assoc-in [:backend :idn :error] nil))
   :idn/start-streaming {:host host :port (or port 7255)}})

(defn- handle-idn-connected
  "IDN connection established (legacy single-projector mode)."
  [{:keys [engine target state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connected?] true)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :target] target)
              (assoc-in [:backend :idn :streaming-engine] engine))})

(defn- handle-idn-connection-failed
  "IDN connection failed."
  [{:keys [error state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connected?] false)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :error] error))})

(defn- handle-idn-disconnect
  "Disconnect from IDN target (legacy single-projector mode)."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connected?] false)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :target] nil)
              (assoc-in [:backend :idn :streaming-engine] nil))
   :idn/stop-streaming true})


;; Multi-Projector Streaming Handlers


(defn- handle-start-multi-streaming
  "Start streaming to all enabled projectors.
   
   Creates one streaming engine per enabled projector, using the routing
   system to determine which cues go to which projector/zone.
   
   Effects returned:
   - :multi-engine/start - Triggers multi-engine startup"
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:backend :streaming :running?] true)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :connected?] true))
   :multi-engine/start true})

(defn- handle-stop-multi-streaming
  "Stop streaming to all projectors.
   
   Stops all streaming engines and clears multi-engine state.
   
   Effects returned:
   - :multi-engine/stop - Triggers multi-engine shutdown"
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:backend :streaming :running?] false)
              (assoc-in [:backend :idn :connected?] false))
   :multi-engine/stop true})

(defn- handle-multi-streaming-started
  "Multi-engine streaming successfully started.
   Called by effect handler after engines are started."
  [{:keys [engine-count state]}]
  {:state (assoc-in state [:backend :streaming :engine-count] engine-count)})

(defn- handle-multi-streaming-stopped
  "Multi-engine streaming stopped.
   Called by effect handler after engines are stopped."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:backend :streaming :engine-count] 0)
              (assoc-in [:backend :streaming :multi-engine-state] nil))})

(defn- handle-multi-streaming-refreshed
  "Multi-engine streaming refreshed after projector state change.
   Called by effect handler after engines are refreshed."
  [{:keys [engine-count state]}]
  {:state (assoc-in state [:backend :streaming :engine-count] engine-count)})


;; Configuration Handler


(defn- handle-config-update
  "Update a config value."
  [{:keys [path value state]}]
  {:state (assoc-in state (into [:config] path) value)})


;; Public API


(defn handle
  "Dispatch connection and config events to their handlers.
   
   Accepts events with :event/type in the :idn/* or :config/* namespace.
   
   Multi-projector streaming events:
   - :idn/start-multi-streaming - Start engines for all enabled projectors
   - :idn/stop-multi-streaming - Stop all streaming engines
   - :idn/multi-streaming-started - Engines started confirmation
   - :idn/multi-streaming-stopped - Engines stopped confirmation
   - :idn/multi-streaming-refreshed - Engines refreshed after projector state change
   
   Legacy single-projector events (backwards compatibility):
   - :idn/connect - Connect to single target
   - :idn/connected - Single connection established
   - :idn/connection-failed - Connection failed
   - :idn/disconnect - Disconnect from single target"
  [{:keys [event/type] :as event}]
  (case type
    ;; Multi-projector streaming
    :idn/start-multi-streaming (handle-start-multi-streaming event)
    :idn/stop-multi-streaming (handle-stop-multi-streaming event)
    :idn/multi-streaming-started (handle-multi-streaming-started event)
    :idn/multi-streaming-stopped (handle-multi-streaming-stopped event)
    :idn/multi-streaming-refreshed (handle-multi-streaming-refreshed event)
    
    ;; Legacy single-projector (backwards compatibility)
    :idn/connect (handle-idn-connect event)
    :idn/connected (handle-idn-connected event)
    :idn/connection-failed (handle-idn-connection-failed event)
    :idn/disconnect (handle-idn-disconnect event)
    
    ;; Configuration
    :config/update (handle-config-update event)
    
    ;; Unknown event in this domain
    {}))
