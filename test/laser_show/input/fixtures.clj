(ns laser-show.input.fixtures
  "Real-shaped input messages, built with the same library code that
   produces them at runtime (overtone/midi-clj and overtone/osc-clj)."
  (:require [overtone.midi :as m]
            [overtone.osc.decode :as osc-decode]
            [overtone.osc.encode :as osc-encode]
            [overtone.osc.util :as osc-util])
  (:import [java.nio ByteBuffer]
           [javax.sound.midi ShortMessage SysexMessage]))

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
