(ns laser-show.input.osc
  "OSC input handler using overtone/osc-clj.
   Converts OSC messages to standardized input events."
  (:require [overtone.osc :as o]
            [laser-show.input.events :as events]
            [laser-show.input.router :as router]))

;; ============================================================================
;; OSC State
;; ============================================================================

(defonce osc-state
  (atom {:enabled true
         :server nil           ; OSC server instance
         :port 9000            ; Default OSC port
         :address-mappings {}  ; Map of OSC address -> event config
         :handlers {}}))       ; Registered OSC handlers

;; ============================================================================
;; Default Address Mappings
;; ============================================================================

(def default-mappings
  "Default OSC address mappings for common controllers like TouchOSC.
   Maps OSC addresses to event configurations."
  {;; Faders (control changes)
   "/fader1" {:type :control-change :control 1}
   "/fader2" {:type :control-change :control 2}
   "/fader3" {:type :control-change :control 3}
   "/fader4" {:type :control-change :control 4}
   "/fader5" {:type :control-change :control 5}
   "/fader6" {:type :control-change :control 6}
   "/fader7" {:type :control-change :control 7}
   "/fader8" {:type :control-change :control 8}
   ;; Alternative fader paths
   "/1/fader1" {:type :control-change :control 1}
   "/1/fader2" {:type :control-change :control 2}
   "/1/fader3" {:type :control-change :control 3}
   "/1/fader4" {:type :control-change :control 4}
   ;; XY Pads
   "/xy/1" {:type :control-change :control 20 :second-control 21}
   "/1/xy" {:type :control-change :control 20 :second-control 21}
   ;; Grid buttons (notes)
   "/grid/1/1" {:type :note :note 0}
   "/grid/1/2" {:type :note :note 1}
   "/grid/1/3" {:type :note :note 2}
   "/grid/1/4" {:type :note :note 3}
   "/grid/1/5" {:type :note :note 4}
   "/grid/1/6" {:type :note :note 5}
   "/grid/1/7" {:type :note :note 6}
   "/grid/1/8" {:type :note :note 7}
   "/grid/2/1" {:type :note :note 8}
   "/grid/2/2" {:type :note :note 9}
   "/grid/2/3" {:type :note :note 10}
   "/grid/2/4" {:type :note :note 11}
   "/grid/2/5" {:type :note :note 12}
   "/grid/2/6" {:type :note :note 13}
   "/grid/2/7" {:type :note :note 14}
   "/grid/2/8" {:type :note :note 15}
   ;; Transport controls (triggers)
   "/play" {:type :trigger :id :play}
   "/stop" {:type :trigger :id :stop}
   "/pause" {:type :trigger :id :pause}
   "/record" {:type :trigger :id :record}
   "/1/play" {:type :trigger :id :play}
   "/1/stop" {:type :trigger :id :stop}})

;; ============================================================================
;; OSC Message Conversion
;; ============================================================================

(defn- parse-osc-value
  "Parses OSC argument value, normalizing to 0.0-1.0 range if numeric."
  [arg]
  (cond
    (float? arg) (double arg)
    (integer? arg) (/ (double arg) 127.0)
    (number? arg) (double arg)
    :else 1.0))

(defn osc-msg->events
  "Converts an OSC message to unified input events based on address mappings.
   Returns a sequence of events (may be empty)."
  [msg]
  (let [address (:path msg)
        args (:args msg)
        mappings (:address-mappings @osc-state)
        mapping (get mappings address)]
    (when mapping
      (let [value (parse-osc-value (first args))
            channel 0]
        (case (:type mapping)
          :control-change
          (let [events [(events/control-change :osc channel (:control mapping) value)]]
            ;; Handle XY pads with second control
            (if (and (:second-control mapping) (second args))
              (conj events (events/control-change :osc channel 
                                                   (:second-control mapping) 
                                                   (parse-osc-value (second args))))
              events))
          
          :note
          (if (pos? value)
            [(events/note-on :osc channel (:note mapping) value)]
            [(events/note-off :osc channel (:note mapping))])
          
          :trigger
          [(events/trigger :osc (:id mapping) 
                          (if (pos? value) :pressed :released))]
          
          [])))))

;; ============================================================================
;; OSC Handler
;; ============================================================================

(defn- handle-osc-message
  "Handler function for incoming OSC messages."
  [msg]
  (when (:enabled @osc-state)
    (doseq [event (osc-msg->events msg)]
      (router/dispatch! event))))

(defn- create-catch-all-handler
  "Creates an OSC handler that catches all messages."
  []
  (fn [msg]
    (handle-osc-message msg)))

;; ============================================================================
;; Server Management
;; ============================================================================

(defn start-server!
  "Starts the OSC server on the specified port.
   Returns the server if successful, nil otherwise."
  ([]
   (start-server! (:port @osc-state)))
  ([port]
   (try
     (when-let [existing (:server @osc-state)]
       (o/osc-close existing))
     
     (let [server (o/osc-server port)]
       ;; Register catch-all handler for all messages
       (o/osc-handle server "/*" (create-catch-all-handler))
       
       (swap! osc-state assoc :server server :port port)
       (println "OSC server started on port" port)
       server)
     (catch Exception e
       (println "Error starting OSC server:" (.getMessage e))
       nil))))

(defn stop-server!
  "Stops the OSC server."
  []
  (when-let [server (:server @osc-state)]
    (try
      (o/osc-close server)
      (catch Exception _))
    (swap! osc-state assoc :server nil)
    (println "OSC server stopped")))

(defn server-running?
  "Returns true if OSC server is running."
  []
  (boolean (:server @osc-state)))

(defn get-port
  "Returns the current OSC port."
  []
  (:port @osc-state))

;; ============================================================================
;; Address Mapping
;; ============================================================================

(defn set-address-mapping!
  "Sets the mapping for a specific OSC address.
   - address: OSC address string (e.g., '/fader1')
   - mapping: {:type :control-change/:note/:trigger, :control/:note/:id value}"
  [address mapping]
  (swap! osc-state assoc-in [:address-mappings address] mapping))

(defn remove-address-mapping!
  "Removes the mapping for a specific OSC address."
  [address]
  (swap! osc-state update :address-mappings dissoc address))

(defn set-mappings!
  "Sets all address mappings at once."
  [mappings]
  (swap! osc-state assoc :address-mappings mappings))

(defn load-default-mappings!
  "Loads the default address mappings."
  []
  (set-mappings! default-mappings))

(defn get-mappings
  "Returns current address mappings."
  []
  (:address-mappings @osc-state))

;; ============================================================================
;; Dynamic Handler Registration
;; ============================================================================

(defn register-handler!
  "Registers a specific OSC handler for an address pattern.
   The handler receives the raw OSC message."
  [handler-id address-pattern handler-fn]
  (when-let [server (:server @osc-state)]
    (o/osc-handle server address-pattern handler-fn)
    (swap! osc-state assoc-in [:handlers handler-id] 
           {:pattern address-pattern :handler handler-fn})
    handler-id))

(defn unregister-handler!
  "Removes a registered OSC handler."
  [handler-id]
  (when-let [server (:server @osc-state)]
    (when-let [{:keys [pattern]} (get-in @osc-state [:handlers handler-id])]
      (o/osc-rm-handler server pattern)
      (swap! osc-state update :handlers dissoc handler-id))))

;; ============================================================================
;; OSC Learn
;; ============================================================================

(defonce learn-state (atom nil))

(defn start-learn!
  "Starts OSC learn mode. Returns a promise that will be delivered
   with the OSC address of the next received message."
  []
  (let [p (promise)]
    (reset! learn-state p)
    ;; Register a temporary global handler to catch OSC events
    (router/register-global-handler! ::osc-learn
      (fn [event]
        (when (and @learn-state (events/osc-event? event))
          (deliver @learn-state (events/event-id event))
          (router/unregister-handler! ::osc-learn)
          (reset! learn-state nil))))
    p))

(defn cancel-learn!
  "Cancels OSC learn mode."
  []
  (when @learn-state
    (router/unregister-handler! ::osc-learn)
    (reset! learn-state nil)))

(defn learning?
  "Returns true if OSC learn mode is active."
  []
  (boolean @learn-state))

;; ============================================================================
;; Enable/Disable
;; ============================================================================

(defn enable!
  "Enables OSC input processing."
  []
  (swap! osc-state assoc :enabled true))

(defn disable!
  "Disables OSC input processing."
  []
  (swap! osc-state assoc :enabled false))

(defn enabled?
  "Returns true if OSC input is enabled."
  []
  (:enabled @osc-state))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initializes OSC system with default mappings.
   Optionally starts the server."
  ([]
   (init! false))
  ([start-server?]
   (init! start-server? 9000))
  ([start-server? port]
   (load-default-mappings!)
   (enable!)
   (when start-server?
     (start-server! port))))

(defn shutdown!
  "Shuts down OSC system."
  []
  (stop-server!)
  (disable!))
