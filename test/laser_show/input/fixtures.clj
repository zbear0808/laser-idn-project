(ns laser-show.input.fixtures
  "Real-shaped input messages, built with the same library code that
   produces them at runtime (overtone/midi-clj and overtone/osc-clj), and a
   fake MIDI input device shaped like the ones javax.sound.midi gives."
  (:require [overtone.midi :as m]
            [overtone.osc.decode :as osc-decode]
            [overtone.osc.encode :as osc-encode]
            [overtone.osc.util :as osc-util])
  (:import [java.nio ByteBuffer]
           [javax.sound.midi MidiDevice MidiMessage Receiver ShortMessage SysexMessage Transmitter]))

(defn short-message
  "Builds a javax.sound.midi.ShortMessage. channel is 0-based (0-15)."
  [command channel data1 data2]
  (doto (ShortMessage.)
    (.setMessage (int command) (int channel) (int data1) (int data2))))

(defn midi-msg
  "Builds a message map exactly like overtone.midi/midi-handle-events
   passes to its handler."
  ([^ShortMessage sm] (midi-msg sm -1))
  ([^ShortMessage sm timestamp]
   (assoc (m/midi-msg sm timestamp) :device {:name "Test Device"})))

(defn note-on [channel note velocity]
  (midi-msg (short-message ShortMessage/NOTE_ON channel note velocity)))

(defn note-off [channel note]
  (midi-msg (short-message ShortMessage/NOTE_OFF channel note 0)))

(defn control-change [channel control value]
  (midi-msg (short-message ShortMessage/CONTROL_CHANGE channel control value)))

(defn program-change [channel program]
  (midi-msg (short-message ShortMessage/PROGRAM_CHANGE channel program 0)))

(defn mtc-quarter-frame
  "An MTC quarter-frame (status 0xF1) as midi-clj delivers it:
   {:channel 1 :command nil :status :midi-time-code :data1 byte ...}."
  [qf-byte]
  (midi-msg (doto (ShortMessage.)
              (.setMessage (int ShortMessage/MIDI_TIME_CODE) (int qf-byte) (int 0)))))

(defn sysex-msg
  "A SysEx message map as midi-clj's sysex handler receives it. bytes
   include the leading F0 (like the wire); :data is what getData returns."
  [bytes]
  (let [msg (doto (SysexMessage.)
              (.setMessage (byte-array (map unchecked-byte bytes)) (count bytes)))]
    {:timestamp -1
     :data (.getData msg)
     :status (.getStatus msg)
     :length (.getLength msg)
     :device {:name "Test Device"}}))

(defn osc-msg
  "Builds an OSC message the way an osc-clj server delivers it to listeners:
   encoded to bytes, decoded back, then tagged with the source host/port.
   Use (float x) / (int x) args to choose the OSC type tag (f / i)."
  [path & args]
  (let [msg (apply osc-util/mk-osc-msg path (osc-util/osc-type-tag args) args)
        buf (ByteBuffer/allocate 1024)]
    (osc-encode/osc-encode-msg buf msg)
    (.flip buf)
    (assoc (osc-decode/osc-decode-packet buf)
           :src-host "localhost"
           :src-port 9001)))

(defn midi-source
  "A MIDI input as overtone.midi/midi-sources lists it ({:name :description
   :vendor :version :sources :sinks :info :device}, typed :midi-device), on
   a fake javax.sound.midi device. Like a real port, each getTransmitter
   opens a new transmitter, and every one of them hands what the device
   sends (send-midi!) to its receiver. calls gets what is done to the
   device: :open, :get-transmitter, [:set-receiver r] (nil detaches),
   :close-transmitter and :close-device."
  ([device-name] (midi-source device-name (atom [])))
  ([device-name calls]
   (let [open? (atom false)
         transmitters (atom [])
         record! #(swap! calls conj %)
         transmitter (fn []
                       (let [receiver (atom nil)]
                         (reify Transmitter
                           (setReceiver [_ r] (record! [:set-receiver r]) (reset! receiver r))
                           (getReceiver [_] @receiver)
                           (close [this]
                             (record! :close-transmitter)
                             (swap! transmitters #(filterv (fn [t] (not (identical? t this))) %))))))
         device (reify MidiDevice
                  (getDeviceInfo [_] nil)
                  (open [_] (record! :open) (reset! open? true))
                  (isOpen [_] @open?)
                  (close [_]
                    (record! :close-device)
                    (reset! open? false)
                    (reset! transmitters []))
                  (getMaxTransmitters [_] -1)
                  (getMaxReceivers [_] 0)
                  (getTransmitter [_]
                    (record! :get-transmitter)
                    (let [t (transmitter)]
                      (swap! transmitters conj t)
                      t))
                  (getTransmitters [_] @transmitters))]
     (with-meta {:name device-name
                 :description device-name
                 :vendor "Fake"
                 :version "1.0"
                 :sources Integer/MAX_VALUE
                 :sinks 0
                 :info nil
                 :device device}
       {:type :midi-device}))))

(defn send-midi!
  "What a source (midi-source) sends: msg (a ShortMessage, e.g.
   (short-message ShortMessage/NOTE_ON 0 60 100)) to the receiver of each
   transmitter still open, as the device's own thread would."
  [source ^MidiMessage msg]
  (doseq [^Transmitter t (.getTransmitters ^MidiDevice (:device source))]
    (when-let [^Receiver r (.getReceiver t)]
      (.send r msg -1))))
