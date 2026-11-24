(ns laser-show.input.osc
  "OSC input handler using overtone/osc-clj.
   Converts OSC messages to standardized input events.
   
   It also builds the handler the server hands its messages to, and opens
   and closes the server (open-server!, close-server!):
   laser-show.input.devices, which owns it, calls those on its worker
   thread. App state ([:backend :input :osc]) holds plain data only (the
   switch, the port, the address mappings, whether the server listens);
   the pure functions below take that map and return it changed."
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
    (if mapping
      (let [value (parse-osc-value (first args))
            channel 0]
        (case (:type mapping)
          :control-change
          (let [evts [(assoc (events/control-change :osc channel (:control mapping) value)
                             :path address)]]
            ;; Handle XY pads with second control
            (if (and (:second-control mapping) (second args))
              (conj evts (assoc (events/control-change :osc channel 
                                                       (:second-control mapping) 
                                                       (parse-osc-value (second args)))
                                :path (str address "/y")))
              evts))
          
          :note
          (if (pos? value)
            [(assoc (events/note-on :osc channel (:note mapping) value) :path address)]
            [(assoc (events/note-off :osc channel (:note mapping)) :path address)])
          
          :trigger
          [(assoc (events/trigger :osc (:id mapping) 
                                  (if (pos? value) :pressed :released))
                  :path address)]
          
          []))
      ;; Unmapped address: always a trigger keyed by the address (see
      ;; plans/v3/plan-grid-trigger-v2.md §1.4), so any button can be learned.
      ;; The numeric value rides along so the input mapper can store it under
      ;; [:osc address] for modulators; no shared CC number is invented.
      (let [first-arg (first args)
            value (if (seq args) (parse-osc-value first-arg) 1.0)]
        [(assoc (events/trigger :osc address (if (pos? value) :pressed :released))
                :path address
                :value value)]))))


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
      (when (:enabled? osc-state)
        (let [evts (osc-msg->events msg (:address-mappings osc-state))]
          (doseq [event evts]
            (dispatch-fn event)))))))


;; Server Management (side effects: the device worker's, input.devices)


(def ^:private listener-key
  "The key of the server's listener (create-osc-handler's)."
  :laser-show-handler)

(defn close-server!
  "Removes our listener from server, then closes it. Each step is attempted
   independently."
  [server]
  (try
    (o/osc-rm-listener server listener-key)
    (catch Exception e
      (log/warn "Error removing the OSC listener:" (.getMessage e))))
  (try
    (o/osc-close server)
    (catch Exception e
      (log/warn "Error closing the OSC server:" (.getMessage e))))
  (log/info "OSC server stopped")
  server)

(defn open-server!
  "Opens an OSC server on port (a socket) with one listener for every
   message, handler (create-osc-handler): osc-listen catches all of them,
   as a wildcard pattern isn't allowed. Returns the server; throws when the
   port won't open."
  [port handler]
  (let [server (o/osc-server port)]
    (try
      (o/osc-listen server handler listener-key)
      (catch Exception e
        (close-server! server)
        (throw e)))
    (log/info "OSC server started on port" port)
    server))

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
  (assoc osc-state :enabled? true))

(defn disable
  "Disables OSC input processing.
   Returns updated state."
  [osc-state]
  (assoc osc-state :enabled? false))

(defn enabled?
  "Returns true if OSC input is enabled."
  [osc-state]
  (:enabled? osc-state true))


;; Initialization (pure functions)


(def initial-state
  "Default OSC state structure: plain data (the server is the device
   worker's, input.devices)."
  {:enabled? false
   :server-running? false
   :port 9000
   :requests {}
   :address-mappings {}
   :learn-mode nil})


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
