(ns laser-show.input.midi
  "MIDI input handler using overtone/midi-clj.
   Converts MIDI messages to standardized input events.
   
   This module provides pure functions for MIDI state management.
   State is passed as a parameter and returned as output.
   Actual state storage is managed by the application state in domains.clj."
  (:require [clojure.tools.logging :as log]
            [overtone.midi :as m]
            [laser-show.input.events :as events]))


;; Device Discovery (pure functions - no state needed)


(defn list-devices
  "Returns a list of available MIDI input devices."
  []
  (try
    (m/midi-sources)
    (catch Exception e
      (log/error "Error listing MIDI devices:" (.getMessage e))
      [])))

(defn list-device-names
  "Returns just the names of available MIDI input devices."
  []
  (mapv :name (list-devices)))

(defn find-device
  "Finds a MIDI device by name (partial match)."
  [name-pattern]
  (first (filter #(re-find (re-pattern (str "(?i)" name-pattern)) 
                           (:name %))
                 (list-devices))))


;; MIDI Message Conversion (pure function)


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


;; MIDI State Operations (pure functions)


(defn create-midi-handler
  "Creates a handler function for MIDI messages.
   
   Args:
   - device-name: Name of the device for logging
   - dispatch-fn: Function to call with converted events
   - get-state-fn: Function that returns current MIDI state (for checking enabled/filter)
   
   Returns: handler function that processes raw MIDI messages"
  [device-name dispatch-fn get-state-fn]
  (fn [msg]
    (let [midi-state (get-state-fn)]
      (when (:enabled midi-state)
        (let [channel-filter (:channel-filter midi-state)
              channel (:channel msg)]
          ;; Check channel filter
          (when (or (nil? channel-filter)
                    (contains? channel-filter channel))
            (when-let [event (midi-msg->event msg)]
              (dispatch-fn event))))))))

(defn connect-device
  "Connects to a MIDI input device by name.
   Returns updated state map with device and handler.
   
   Note: This function performs side effects (opens MIDI port).
   The returned state includes the live device/handler references."
  [midi-state device-name dispatch-fn get-state-fn]
  (try
    (if-let [device-info (find-device device-name)]
      (let [actual-name (:name device-info)
            receiver (m/midi-in device-info)
            handler (create-midi-handler actual-name dispatch-fn get-state-fn)]
        ;; Set up the handler (side effect)
        (m/midi-handle-events receiver handler)
        (log/info "Connected to MIDI device:" actual-name)
        ;; Return updated state
        (-> midi-state
            (assoc-in [:devices actual-name] receiver)
            (assoc-in [:handlers actual-name] handler)
            (update :connected-devices (fnil conj #{}) actual-name)))
      (do
        (log/warn "MIDI device not found:" device-name)
        midi-state))
    (catch Exception e
      (log/error "Error connecting to MIDI device:" (.getMessage e))
      midi-state)))

(defn disconnect-device
  "Disconnects from a MIDI device.
   Returns updated state map."
  [midi-state device-name]
  (if (get-in midi-state [:devices device-name])
    (do
      ;; Note: overtone.midi doesn't have a close function, 
      ;; the device will be garbage collected
      (log/info "Disconnected from MIDI device:" device-name)
      (-> midi-state
          (update :devices dissoc device-name)
          (update :handlers dissoc device-name)
          (update :connected-devices disj device-name)))
    midi-state))

(defn disconnect-all
  "Disconnects from all MIDI devices.
   Returns updated state map."
  [midi-state]
  (reduce (fn [state device-name]
            (disconnect-device state device-name))
          midi-state
          (keys (:devices midi-state))))

(defn connected-devices
  "Returns set of currently connected device names."
  [midi-state]
  (:connected-devices midi-state #{}))


;; Configuration (pure functions)


(defn set-channel-filter
  "Sets which MIDI channels to listen to.
   Pass nil to listen to all channels, or a set of channel numbers (0-15).
   Returns updated state."
  [midi-state channels]
  (assoc midi-state :channel-filter 
         (when channels (set channels))))

(defn clear-channel-filter
  "Removes channel filter, listening to all channels.
   Returns updated state."
  [midi-state]
  (assoc midi-state :channel-filter nil))


;; Enable/Disable (pure functions)


(defn enable
  "Enables MIDI input processing.
   Returns updated state."
  [midi-state]
  (assoc midi-state :enabled true))

(defn disable
  "Disables MIDI input processing.
   Returns updated state."
  [midi-state]
  (assoc midi-state :enabled false))

(defn enabled?
  "Returns true if MIDI input is enabled."
  [midi-state]
  (:enabled midi-state true))


;; MIDI Learn (pure functions for state, setup needs dispatch)


(defn start-learn
  "Starts MIDI learn mode.
   Returns updated state with promise in :learn-mode.
   
   The caller should:
   1. Store the returned state
   2. Register a global handler to listen for MIDI events
   3. When event received, deliver to the promise and call cancel-learn"
  [midi-state]
  (let [p (promise)]
    (assoc midi-state :learn-mode p)))

(defn cancel-learn
  "Cancels MIDI learn mode.
   Returns updated state with :learn-mode set to nil."
  [midi-state]
  (assoc midi-state :learn-mode nil))

(defn learning?
  "Returns true if MIDI learn mode is active."
  [midi-state]
  (boolean (:learn-mode midi-state)))

(defn get-learn-promise
  "Returns the learn mode promise, or nil if not learning."
  [midi-state]
  (:learn-mode midi-state))


;; Auto-Connect (pure function returning updated state)


(defn auto-connect
  "Attempts to connect to the first available MIDI input device.
   Returns updated state."
  [midi-state dispatch-fn get-state-fn]
  (if-let [devices (seq (list-devices))]
    (do
      (log/info "Found MIDI devices:" (mapv :name devices))
      (connect-device midi-state (:name (first devices)) dispatch-fn get-state-fn))
    (do
      (log/info "No MIDI input devices found")
      midi-state)))


;; Initialization (pure functions)


(def initial-state
  "Default MIDI state structure."
  {:enabled true
   :devices {}
   :connected-devices #{}
   :handlers {}
   :channel-filter nil
   :learn-mode nil
   :note-mappings {}
   :cc-mappings {}})

(defn init
  "Initializes MIDI state. Optionally auto-connects to first device.
   Returns initialized state."
  ([midi-state]
   (init midi-state false nil nil))
  ([midi-state auto-connect? dispatch-fn get-state-fn]
   (let [state (enable midi-state)]
     (if auto-connect?
       (auto-connect state dispatch-fn get-state-fn)
       state))))

(defn shutdown
  "Shuts down MIDI system, disconnecting all devices.
   Returns cleaned up state."
  [midi-state]
  (-> midi-state
      disconnect-all
      disable))


;; Utility functions for working with MIDI data


(defn midi-note-name
  "Converts a MIDI note number to a note name (e.g., 60 -> 'C4')."
  [note-number]
  (let [notes ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"]
        octave (- (quot note-number 12) 1)
        note-idx (mod note-number 12)]
    (str (notes note-idx) octave)))

(defn midi-cc-name
  "Returns a human-readable name for common MIDI CC numbers."
  [cc-number]
  (case cc-number
    0 "Bank Select"
    1 "Modulation"
    2 "Breath Controller"
    4 "Foot Controller"
    5 "Portamento Time"
    7 "Volume"
    8 "Balance"
    10 "Pan"
    11 "Expression"
    64 "Sustain Pedal"
    65 "Portamento"
    66 "Sostenuto"
    67 "Soft Pedal"
    68 "Legato Footswitch"
    69 "Hold 2"
    91 "Reverb"
    92 "Tremolo"
    93 "Chorus"
    94 "Detune"
    95 "Phaser"
    ;; Default
    (str "CC " cc-number)))
