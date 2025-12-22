(ns laser-show.input.midi
  "MIDI input handler using overtone/midi-clj.
   Converts MIDI messages to standardized input events."
  (:require [overtone.midi :as m]
            [laser-show.input.events :as events]
            [laser-show.input.router :as router]))

;; ============================================================================
;; MIDI State
;; ============================================================================

(defonce midi-state
  (atom {:enabled true
         :devices {}           ; Map of device-name -> device
         :handlers {}          ; Map of device-name -> handler
         :channel-filter nil   ; nil = all channels, or set of allowed channels
         :note-mappings {}     ; Optional note remapping
         :cc-mappings {}}))    ; Optional CC remapping

;; ============================================================================
;; Device Discovery
;; ============================================================================

(defn list-devices
  "Returns a list of available MIDI input devices."
  []
  (try
    (m/midi-sources)
    (catch Exception e
      (println "Error listing MIDI devices:" (.getMessage e))
      [])))

(defn list-device-names
  "Returns just the names of available MIDI input devices."
  []
  (map :name (list-devices)))

(defn find-device
  "Finds a MIDI device by name (partial match)."
  [name-pattern]
  (first (filter #(re-find (re-pattern (str "(?i)" name-pattern)) 
                           (:name %))
                 (list-devices))))

;; ============================================================================
;; MIDI Message Conversion
;; ============================================================================

(defn midi-msg->event
  "Converts a midi-clj message to a unified input event."
  [msg]
  (let [status (:status msg)
        channel (:channel msg 0)
        data1 (:note msg (:data1 msg))
        data2 (:velocity msg (:data2 msg))]
    (cond
      ;; Note On (status 144-159, or :note-on)
      (or (= status :note-on)
          (and (number? status) (>= status 144) (<= status 159)))
      (if (and data2 (pos? data2))
        (events/note-on :midi channel data1 (events/midi-to-normalized data2))
        (events/note-off :midi channel data1))
      
      ;; Note Off (status 128-143, or :note-off)
      (or (= status :note-off)
          (and (number? status) (>= status 128) (<= status 143)))
      (events/note-off :midi channel data1)
      
      ;; Control Change (status 176-191, or :control-change)
      (or (= status :control-change)
          (and (number? status) (>= status 176) (<= status 191)))
      (events/control-change :midi channel data1 (events/midi-to-normalized data2))
      
      ;; Program Change (status 192-207, or :program-change)
      (or (= status :program-change)
          (and (number? status) (>= status 192) (<= status 207)))
      (events/program-change :midi channel data1)
      
      ;; Unhandled message type
      :else nil)))

;; ============================================================================
;; MIDI Message Handler
;; ============================================================================

(defn- create-midi-handler
  "Creates a handler function for MIDI messages."
  [device-name]
  (fn [msg]
    (when (:enabled @midi-state)
      (let [channel-filter (:channel-filter @midi-state)
            channel (:channel msg)]
        ;; Check channel filter
        (when (or (nil? channel-filter)
                  (contains? channel-filter channel))
          (when-let [event (midi-msg->event msg)]
            (router/dispatch! event)))))))

;; ============================================================================
;; Device Connection
;; ============================================================================

(defn connect-device!
  "Connects to a MIDI input device by name.
   Returns the device if successful, nil otherwise."
  [device-name]
  (try
    (if-let [device (find-device device-name)]
      (let [receiver (m/midi-in device)
            handler (create-midi-handler (:name device))]
        ;; Set up the handler
        (m/midi-handle-events receiver handler)
        ;; Store in state
        (swap! midi-state
               (fn [state]
                 (-> state
                     (assoc-in [:devices (:name device)] receiver)
                     (assoc-in [:handlers (:name device)] handler))))
        (println "Connected to MIDI device:" (:name device))
        device)
      (do
        (println "MIDI device not found:" device-name)
        nil))
    (catch Exception e
      (println "Error connecting to MIDI device:" (.getMessage e))
      nil)))

(defn disconnect-device!
  "Disconnects from a MIDI device."
  [device-name]
  (when-let [device (get-in @midi-state [:devices device-name])]
    ;; Note: overtone.midi doesn't have a close function, 
    ;; the device will be garbage collected
    (swap! midi-state
           (fn [state]
             (-> state
                 (update :devices dissoc device-name)
                 (update :handlers dissoc device-name))))
    (println "Disconnected from MIDI device:" device-name)))

(defn disconnect-all!
  "Disconnects from all MIDI devices."
  []
  (doseq [device-name (keys (:devices @midi-state))]
    (disconnect-device! device-name)))

(defn connected-devices
  "Returns list of currently connected device names."
  []
  (keys (:devices @midi-state)))

;; ============================================================================
;; Configuration
;; ============================================================================

(defn set-channel-filter!
  "Sets which MIDI channels to listen to.
   Pass nil to listen to all channels, or a set of channel numbers (0-15)."
  [channels]
  (swap! midi-state assoc :channel-filter 
         (when channels (set channels))))

(defn clear-channel-filter!
  "Removes channel filter, listening to all channels."
  []
  (swap! midi-state assoc :channel-filter nil))

;; ============================================================================
;; Enable/Disable
;; ============================================================================

(defn enable!
  "Enables MIDI input processing."
  []
  (swap! midi-state assoc :enabled true))

(defn disable!
  "Disables MIDI input processing."
  []
  (swap! midi-state assoc :enabled false))

(defn enabled?
  "Returns true if MIDI input is enabled."
  []
  (:enabled @midi-state))

;; ============================================================================
;; MIDI Learn
;; ============================================================================

(defonce learn-state (atom nil))

(defn start-learn!
  "Starts MIDI learn mode. Returns a promise that will be delivered
   with the next received MIDI message info."
  []
  (let [p (promise)]
    (reset! learn-state p)
    ;; Register a temporary global handler
    (router/register-global-handler! ::midi-learn
      (fn [event]
        (when (and @learn-state (events/midi-event? event))
          (deliver @learn-state (events/event-id event))
          (router/unregister-handler! ::midi-learn)
          (reset! learn-state nil))))
    p))

(defn cancel-learn!
  "Cancels MIDI learn mode."
  []
  (when @learn-state
    (router/unregister-handler! ::midi-learn)
    (reset! learn-state nil)))

(defn learning?
  "Returns true if MIDI learn mode is active."
  []
  (boolean @learn-state))

;; ============================================================================
;; Auto-Connect
;; ============================================================================

(defn auto-connect!
  "Attempts to connect to the first available MIDI input device.
   Returns the device if successful, nil if no devices found."
  []
  (if-let [devices (seq (list-devices))]
    (do
      (println "Found MIDI devices:" (map :name devices))
      (connect-device! (:name (first devices))))
    (do
      (println "No MIDI input devices found")
      nil)))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initializes MIDI system. Optionally auto-connects to first device."
  ([]
   (init! false))
  ([auto-connect?]
   (enable!)
   (when auto-connect?
     (auto-connect!))))

(defn shutdown!
  "Shuts down MIDI system, disconnecting all devices."
  []
  (disconnect-all!)
  (disable!))
