(ns laser-show.input.midi
  "MIDI input handler using overtone/midi-clj.
   Converts MIDI messages to standardized input events.
   
   It also lists the inputs, builds the handlers a connected input hands
   its messages to, and opens and closes an input's port (open-input!,
   close-input!): laser-show.input.devices, which owns every port, calls
   those on its worker thread. App state ([:backend :input :midi]) holds
   plain data only (the switch, the channel filter, the inputs' status);
   the pure functions below take that map and return it changed."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [overtone.midi :as m]
            [laser-show.input.events :as events]
            [laser-show.input.mtc :as mtc]
            [laser-show.common.util :as u])
  (:import [javax.sound.midi MidiDevice Transmitter]))


;; Device Discovery


(defn list-devices
  "Returns a list of available MIDI input devices (overtone.midi/midi-sources:
   {:name :description :vendor :version :info :device …}, :device the
   javax.sound.midi device). Enumerating them can take a while: the device
   worker (input.devices) calls this."
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
  "The input in sources (as list-devices lists them) named device-name,
   else the first whose name contains it, ignoring case; nil if none."
  [sources device-name]
  (when device-name
    (let [part (str/lower-case device-name)]
      (or (u/seek #(= device-name (:name %)) sources)
          (u/seek #(some-> (:name %) str/lower-case (str/includes? part)) sources)))))


;; MIDI Message Conversion (pure function)


(defn- message-command
  "Returns the channel-voice command keyword of a midi-clj message.

   overtone.midi/midi-msg puts the channel-stripped command in :command.
   Its :status is only set for channel 0 (it looks up the full status byte),
   so it must not be used to decide the message type. A keyword or numeric
   :status (0x80-0xEF) is still accepted for hand-built maps."
  [msg]
  (let [command (:command msg)
        status (:status msg)]
    (cond
      (keyword? command) command
      (keyword? status) status
      (number? status) (case (bit-and (int status) 0xF0)
                         0x80 :note-off
                         0x90 :note-on
                         0xB0 :control-change
                         0xC0 :program-change
                         nil)
      :else nil)))

(defn- message-channel
  [msg]
  (or (:channel msg)
      (when (number? (:status msg))
        (bit-and (int (:status msg)) 0x0F))
      0))

(defn midi-msg->event
  "Converts a midi-clj message to a unified input event."
  [msg]
  (let [command (message-command msg)
        channel (message-channel msg)
        data1 (:note msg (:data1 msg))
        data2 (:velocity msg (:data2 msg))]
    (case command
      ;; midi-clj already turns note-on with velocity 0 into :note-off
      :note-on (if (and data2 (pos? data2))
                 (events/note-on :midi channel data1 (events/midi-to-normalized data2))
                 (events/note-off :midi channel data1))
      :note-off (events/note-off :midi channel data1)
      :control-change (events/control-change :midi channel data1 (events/midi-to-normalized data2))
      :program-change (events/program-change :midi channel data1)
      nil)))


;; Handlers and ports


(defn create-midi-handler
  "Creates a handler function for MIDI messages.
   
   MTC quarter-frames go straight to the timecode decoder (laser-show.input.mtc):
   they ignore the channel filter (system messages have no channel) and never
   become trigger events.
   
   Args:
   - device-name: Name of the device for logging
   - dispatch-fn: Function to call with converted events
   - get-state-fn: Function that returns current MIDI state: :enabled? and
     :channel-filter, and :connected-devices, which has device-name only
     while this handler's connection is the live one (input.devices' fn
     says so)
   
   Returns: handler function that processes raw MIDI messages"
  [device-name dispatch-fn get-state-fn]
  (fn [msg]
    (let [midi-state (get-state-fn)]
      (when (and (:enabled? midi-state)
                 ;; Guard against a handler that outlives its disconnect
                 (contains? (:connected-devices midi-state) device-name))
        (if (= :midi-time-code (:status msg))
          (mtc/on-quarter-frame! (:data1 msg))
          (let [channel-filter (:channel-filter midi-state)
                channel (:channel msg)]
            ;; Check channel filter
            (when (or (nil? channel-filter)
                      (contains? channel-filter channel))
              (when-let [event (midi-msg->event msg)]
                (dispatch-fn event)))))))))

(defn create-sysex-handler
  "Creates a handler for SysEx messages: MTC full-frame (locate) messages go
   to the timecode decoder; everything else is ignored."
  [device-name get-state-fn]
  (fn [{:keys [data]}]
    (let [midi-state (get-state-fn)]
      (when (and (:enabled? midi-state)
                 (contains? (:connected-devices midi-state) device-name)
                 data
                 (mtc/full-frame? data))
        (mtc/on-sysex! data)))))

(defn close-input!
  "Detaches our receiver and closes the transmitter and device of midi-in
   (open-input!'s, or a source with none yet). Each step is attempted
   independently."
  [{:keys [transmitter device] :as midi-in}]
  (when transmitter
    (try (.setReceiver ^Transmitter transmitter nil)
         (catch Exception e (log/warn "Error detaching MIDI receiver:" (.getMessage e))))
    (try (.close ^Transmitter transmitter)
         (catch Exception e (log/warn "Error closing MIDI transmitter:" (.getMessage e)))))
  (when device
    (try (.close ^MidiDevice device)
         (catch Exception e (log/warn "Error closing MIDI device:" (.getMessage e)))))
  midi-in)

(defn open-input!
  "Opens source (an input as list-devices lists it) for reading: its device
   opened if it isn't yet, a transmitter of its own, and on that a receiver
   handing short messages to on-short and SysEx to on-sysex
   (overtone.midi/midi-handle-events). Every call opens another transmitter,
   so another delivery of each message: the device worker opens a device
   once. Returns source with :transmitter and :receiver, a midi-in for
   close-input!. Throws when the port won't open, having closed what it
   opened."
  [{:keys [^MidiDevice device] :as source} on-short on-sysex]
  (let [transmitter (try
                      (when-not (.isOpen device)
                        (.open device))
                      (.getTransmitter device)
                      (catch Exception e
                        (close-input! source)
                        (throw e)))
        midi-in (assoc source :transmitter transmitter)]
    (try
      (assoc midi-in :receiver (m/midi-handle-events midi-in on-short on-sysex))
      (catch Exception e
        (close-input! midi-in)
        (throw e)))))

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
  (assoc midi-state :enabled? true))

(defn disable
  "Disables MIDI input processing.
   Returns updated state."
  [midi-state]
  (assoc midi-state :enabled? false))

(defn enabled?
  "Returns true if MIDI input is enabled."
  [midi-state]
  (:enabled? midi-state true))


;; Initialization (pure functions)


(def initial-state
  "Default MIDI state structure: plain data (the ports are the device
   worker's, input.devices). Settings' identify of an input is a learn
   attempt (handlers/input.clj), not MIDI state."
  {:enabled? true
   :available-devices []
   :connected-devices #{}
   :requests {}
   :channel-filter nil
   :note-mappings {}
   :cc-mappings {}})


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
