(ns laser-show.events.handlers.connection
  "Event handlers for IDN connection management and configuration.
   
   Handles:
   - Multi-engine streaming (one engine per projector)
   - Connection status updates
   - Configuration updates
   
   The connection system supports multi-projector streaming:
   - :idn/start-multi-streaming - Start streaming to all enabled projectors
   - :idn/stop-multi-streaming - Stop all streaming engines
   - :idn/connection-failed - Handle connection failures")


;; Connection Status Handler


(defn- handle-idn-connection-failed
  "IDN connection failed."
  [{:keys [error state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connected?] false)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :error] error))})
              
              
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
   - :idn/connection-failed - Connection failed"
  [{:keys [event/type] :as event}]
  (case type
    ;; Multi-projector streaming
    :idn/start-multi-streaming (handle-start-multi-streaming event)
    :idn/stop-multi-streaming (handle-stop-multi-streaming event)
    :idn/multi-streaming-started (handle-multi-streaming-started event)
    :idn/multi-streaming-stopped (handle-multi-streaming-stopped event)
    :idn/multi-streaming-refreshed (handle-multi-streaming-refreshed event)
    :idn/connection-failed (handle-idn-connection-failed event)
    
    ;; Configuration
    :config/update (handle-config-update event)
    
    ;; Unknown event in this domain
    {}))
