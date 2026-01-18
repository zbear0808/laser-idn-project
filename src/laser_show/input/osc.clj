(ns laser-show.input.osc
  "OSC input handler using overtone/osc-clj.
   Converts OSC messages to standardized input events.
   
   This module provides pure functions for OSC state management.
   State is passed as a parameter and returned as output.
   Actual state storage is managed by the application state in domains.clj."
  (:require [clojure.tools.logging :as log]
            [overtone.osc :as o]
            [laser-show.input.events :as events]))


;; Default Address Mappings


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


;; OSC Message Conversion (pure functions)


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
   Returns a sequence of events (may be empty).
   
   This is a pure function - mappings are passed as parameter."
  [msg address-mappings]
  (let [address (:path msg)
        args (:args msg)
        mapping (get address-mappings address)]
    (when mapping
      (let [value (parse-osc-value (first args))
            channel 0]
        (case (:type mapping)
          :control-change
          (let [evts [(events/control-change :osc channel (:control mapping) value)]]
            ;; Handle XY pads with second control
            (if (and (:second-control mapping) (second args))
              (conj evts (events/control-change :osc channel 
                                                 (:second-control mapping) 
                                                 (parse-osc-value (second args))))
              evts))
          
          :note
          (if (pos? value)
            [(events/note-on :osc channel (:note mapping) value)]
            [(events/note-off :osc channel (:note mapping))])
          
          :trigger
          [(events/trigger :osc (:id mapping) 
                          (if (pos? value) :pressed :released))]
          
          [])))))


;; OSC Handler Creation


(defn create-osc-handler
  "Creates a handler function for incoming OSC messages.
   
   Args:
   - dispatch-fn: Function to call with converted events
   - get-state-fn: Function that returns current OSC state
   
   Returns: handler function that processes raw OSC messages"
  [dispatch-fn get-state-fn]
  (fn [msg]
    (let [osc-state (get-state-fn)]
      (when (:enabled osc-state)
        (let [evts (osc-msg->events msg (:address-mappings osc-state))]
          (doseq [event evts]
            (dispatch-fn event)))))))


;; Server Management (state transformations with side effects)


(defn start-server
  "Starts the OSC server on the specified port.
   Returns updated state map.
   
   Note: This function performs side effects (opens network socket)."
  ([osc-state dispatch-fn get-state-fn]
   (start-server osc-state (:port osc-state 9000) dispatch-fn get-state-fn))
  ([osc-state port dispatch-fn get-state-fn]
   (try
     ;; Close existing server if any
     (when-let [existing (:server osc-state)]
       (try
         (o/osc-close existing)
         (catch Exception _)))
     
     (let [server (o/osc-server port)
           handler (create-osc-handler dispatch-fn get-state-fn)]
       ;; Use osc-listen to catch ALL messages (no pattern matching)
       ;; This avoids the issue with wildcard patterns not being allowed
       (o/osc-listen server handler :laser-show-handler)
       
       (log/info "OSC server started on port" port)
       (-> osc-state
           (assoc :server server)
           (assoc :port port)
           (assoc :server-running? true)))
     (catch Exception e
       (log/error "Error starting OSC server:" (.getMessage e))
       (assoc osc-state :server-running? false)))))

(defn stop-server
  "Stops the OSC server.
   Returns updated state map."
  [osc-state]
  (when-let [server (:server osc-state)]
    (try
      ;; Remove the listener before closing
      (o/osc-rm-listener server :laser-show-handler)
      (catch Exception _))
    (try
      (o/osc-close server)
      (catch Exception _))
    (log/info "OSC server stopped"))
  (-> osc-state
      (assoc :server nil)
      (assoc :server-running? false)))

(defn server-running?
  "Returns true if OSC server is running."
  [osc-state]
  (:server-running? osc-state false))

(defn get-port
  "Returns the current OSC port."
  [osc-state]
  (:port osc-state 9000))


;; Address Mapping (pure functions)


(defn set-address-mapping
  "Sets the mapping for a specific OSC address.
   - address: OSC address string (e.g., '/fader1')
   - mapping: {:type :control-change/:note/:trigger, :control/:note/:id value}
   Returns updated state."
  [osc-state address mapping]
  (assoc-in osc-state [:address-mappings address] mapping))

(defn remove-address-mapping
  "Removes the mapping for a specific OSC address.
   Returns updated state."
  [osc-state address]
  (update osc-state :address-mappings dissoc address))

(defn set-mappings
  "Sets all address mappings at once.
   Returns updated state."
  [osc-state mappings]
  (assoc osc-state :address-mappings mappings))

(defn load-default-mappings
  "Loads the default address mappings.
   Returns updated state."
  [osc-state]
  (set-mappings osc-state default-mappings))

(defn get-mappings
  "Returns current address mappings."
  [osc-state]
  (:address-mappings osc-state {}))


;; Dynamic Handler Registration


(defn register-handler
  "Registers a specific OSC handler for an address pattern.
   The handler receives the raw OSC message.
   Returns updated state."
  [osc-state handler-id address-pattern handler-fn]
  (when-let [server (:server osc-state)]
    (o/osc-handle server address-pattern handler-fn))
  (assoc-in osc-state [:handlers handler-id] 
            {:pattern address-pattern :handler handler-fn}))

(defn unregister-handler
  "Removes a registered OSC handler.
   Returns updated state."
  [osc-state handler-id]
  (when-let [server (:server osc-state)]
    (when-let [{:keys [pattern]} (get-in osc-state [:handlers handler-id])]
      (o/osc-rm-handler server pattern)))
  (update osc-state :handlers dissoc handler-id))


;; OSC Learn (pure functions for state)


(defn start-learn
  "Starts OSC learn mode.
   Returns updated state with promise in :learn-mode.
   
   The caller should:
   1. Store the returned state  
   2. Register a global handler to listen for OSC events
   3. When event received, deliver to the promise and call cancel-learn"
  [osc-state]
  (let [p (promise)]
    (assoc osc-state :learn-mode p)))

(defn cancel-learn
  "Cancels OSC learn mode.
   Returns updated state with :learn-mode set to nil."
  [osc-state]
  (assoc osc-state :learn-mode nil))

(defn learning?
  "Returns true if OSC learn mode is active."
  [osc-state]
  (boolean (:learn-mode osc-state)))

(defn get-learn-promise
  "Returns the learn mode promise, or nil if not learning."
  [osc-state]
  (:learn-mode osc-state))


;; Enable/Disable (pure functions)


(defn enable
  "Enables OSC input processing.
   Returns updated state."
  [osc-state]
  (assoc osc-state :enabled true))

(defn disable
  "Disables OSC input processing.
   Returns updated state."
  [osc-state]
  (assoc osc-state :enabled false))

(defn enabled?
  "Returns true if OSC input is enabled."
  [osc-state]
  (:enabled osc-state true))


;; Initialization (pure functions)


(def initial-state
  "Default OSC state structure."
  {:enabled false
   :server nil
   :server-running? false
   :port 9000
   :address-mappings {}
   :handlers {}
   :learn-mode nil})

(defn init
  "Initializes OSC state with default mappings.
   Optionally starts the server.
   Returns initialized state."
  ([osc-state]
   (init osc-state false 9000 nil nil))
  ([osc-state start-server?]
   (init osc-state start-server? 9000 nil nil))
  ([osc-state start-server? port dispatch-fn get-state-fn]
   (let [state (-> osc-state
                   load-default-mappings
                   enable)]
     (if start-server?
       (start-server state port dispatch-fn get-state-fn)
       state))))

(defn shutdown
  "Shuts down OSC system.
   Returns cleaned up state."
  [osc-state]
  (-> osc-state
      stop-server
      disable))


;; Utility Functions


(defn format-address-mapping
  "Formats an address mapping for display."
  [address mapping]
  (str address " -> " 
       (case (:type mapping)
         :control-change (str "CC " (:control mapping)
                              (when (:second-control mapping)
                                (str "/" (:second-control mapping))))
         :note (str "Note " (:note mapping))
         :trigger (str "Trigger " (name (:id mapping)))
         "Unknown")))

(defn mapping-summary
  "Returns a summary of all mappings."
  [osc-state]
  (let [mappings (get-mappings osc-state)]
    {:total (count mappings)
     :control-changes (count (filter #(= :control-change (:type (val %))) mappings))
     :notes (count (filter #(= :note (:type (val %))) mappings))
     :triggers (count (filter #(= :trigger (:type (val %))) mappings))}))
