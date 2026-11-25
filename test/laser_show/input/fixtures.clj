(ns laser-show.input.fixtures
  "Real-shaped input messages, built with the same library code that
   produces them at runtime (overtone/midi-clj and overtone/osc-clj), a
   fake MIDI input device shaped like the ones javax.sound.midi gives, and
   Carabiner stood in for behind beat-carabiner's functions (with-carabiner)
   and on a loopback port of the test's own, for the Link owner's query
   connections (with-phase-server)."
  (:require [beat-carabiner.core :as bc]
            [laser-show.input.link :as link]
            [overtone.midi :as m]
            [overtone.osc.decode :as osc-decode]
            [overtone.osc.encode :as osc-encode]
            [overtone.osc.util :as osc-util])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net InetAddress ServerSocket Socket]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util Locale]
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


;; Carabiner, stood in for behind beat-carabiner's functions: nothing
;; reaches a daemon or starts one

(defn with-carabiner
  "(f carabiner) with beat-carabiner's connection to Carabiner stood in for
   (no socket, no daemon, nothing started): carabiner is an atom
   {:connected? :status :status-listeners :disconnection-listeners :calls}.
   bc/connect connects (fails with :refuse?), bc/active? and bc/state read
   it (:status, :link-bpm :link-peers, as given; a :port only when :status
   gives one, e.g. with-phase-server's, so otherwise the Link owner opens
   no query connection), bc/disconnect disconnects
   and, as beat-carabiner's read loop does then, tells the disconnection
   listeners left (unexpected? false), and the listeners' add and remove
   fns keep them. bc/beat-at-time, which would move the Link session's beat
   grid, only records its call and returns nil, as beat-carabiner's does.
   :calls records each call, in order: :connect :disconnect :add-listener
   :remove-listener :add-closed-listener :remove-closed-listener
   :beat-at-time. What f leaves connected is released
   (input.link/release!) while it stands in, and the Link owner opened
   again."
  ([f] (with-carabiner {} f))
  ([{:keys [refuse? status]} f]
   (let [carabiner (atom {:connected? false
                          :status (merge {:link-bpm nil :link-peers 0} status)
                          :status-listeners #{}
                          :disconnection-listeners #{}
                          :calls []})
         call! #(swap! carabiner update :calls conj %)]
     (with-redefs [bc/connect (fn []
                                (call! :connect)
                                (:connected? (swap! carabiner update :connected? #(or % (not refuse?)))))
                   bc/active? #(:connected? @carabiner)
                   bc/state #(:status @carabiner)
                   bc/disconnect (fn []
                                   (call! :disconnect)
                                   (when (:connected? @carabiner)
                                     (swap! carabiner assoc :connected? false)
                                     (doseq [l (:disconnection-listeners @carabiner)] (l false))))
                   bc/add-status-listener (fn [l]
                                            (call! :add-listener)
                                            (swap! carabiner update :status-listeners conj l))
                   bc/remove-status-listener (fn [l]
                                               (call! :remove-listener)
                                               (swap! carabiner update :status-listeners disj l))
                   bc/add-disconnection-listener (fn [l]
                                                   (call! :add-closed-listener)
                                                   (swap! carabiner update :disconnection-listeners conj l))
                   bc/remove-disconnection-listener (fn [l]
                                                      (call! :remove-closed-listener)
                                                      (swap! carabiner update :disconnection-listeners disj l))
                   bc/beat-at-time (fn [& _] (call! :beat-at-time) nil)]
       (link/open!)
       (try
         (f carabiner)
         (finally
           (link/release! 2000)
           (link/open!)))))))

(defn report-status!
  "What a status from Carabiner does in beat-carabiner: status (:link-bpm,
   :link-peers) is what bc/state gives from now on, and each status
   listener hears it, as on its read thread."
  [carabiner status]
  (let [{:keys [status status-listeners]} (swap! carabiner update :status merge status)]
    (doseq [listener status-listeners]
      (listener status))))

(defn drop-carabiner!
  "What beat-carabiner's read loop does when the connection to Carabiner
   ends without a disconnect: not connected any more, then each
   disconnection listener hears unexpected? (true, the default: Carabiner
   quit or closed it; false: beat-carabiner's own check found no status
   within a second of the connect)."
  ([carabiner] (drop-carabiner! carabiner true))
  ([carabiner unexpected?]
   (swap! carabiner assoc :connected? false)
   (doseq [listener (:disconnection-listeners @carabiner)]
     (listener unexpected?))))

(defn carabiner-calls
  "How many calls of kind k (with-carabiner's :calls) carabiner has had."
  [carabiner k]
  (count (filter #{k} (:calls @carabiner))))


;; Carabiner's own port, stood in for by an in-process server on a loopback
;; port: what the Link owner's query connection talks to (beat alignment)

(def ^:private greeting
  "What Carabiner sends each client when it connects, and with changes."
  "status { :peers 1 :bpm 120.000000 :start 0 :beat 0.000000 }\n")

(defn- serve-client!
  "Talk to one query connection as Carabiner does (with-phase-server)."
  [server ^Socket socket]
  (try
    (let [reader (BufferedReader. (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))
          out (.getOutputStream socket)
          write! (fn [^String s] (.write out (.getBytes s StandardCharsets/UTF_8)) (.flush out))]
      (write! greeting)
      (loop []
        (when-let [line (.readLine reader)]
          (swap! server update :received conj line)
          (when-let [[_ t q] (re-matches #"phase-at-time (-?\d+) (\S+)" line)]
            (some-> (:hold @server) (deref 5000 nil))
            (let [phase (double ((:phase-at @server) (parse-long t)))]
              ;; A status in the same write: answers can share a packet
              (write! (str greeting "phase-at-time { :when " t " :quantum " q
                           " :phase " (String/format Locale/ROOT "%.6f" (object-array [phase])) " }\n"))))
          (recur))))
    (catch Exception _ nil)
    (finally
      (swap! server update :closed inc)
      (try (.close socket) (catch Exception _ nil)))))

(defn with-phase-server
  "(f server) with a stand-in for Carabiner's own port, for the Link
   owner's query connections: an in-process server socket on the loopback
   address (port 0: any free one; give it to with-carabiner as :status
   {:port}). It greets each client with a status line, as Carabiner does,
   keeps each line a client sends, in order, in :received, and answers
   phase-at-time t q with phase-at-time { :when t :quantum q :phase
   ((:phase-at @server) t) }, a status line before it in the same write,
   once :hold (a promise, when set) is delivered. server is an atom {:port
   :received :phase-at :hold :clients (accepted) :closed (connections seen
   to end)}. Every socket is closed after f."
  [f]
  (let [listening (ServerSocket. 0 50 (InetAddress/getLoopbackAddress))
        server (atom {:port (.getLocalPort listening) :received [] :phase-at (constantly 0.0)
                      :hold nil :clients 0 :closed 0 :sockets []})
        acceptor (doto (Thread. ^Runnable
                        (fn []
                          (try
                            (loop []
                              (let [socket (.accept listening)]
                                (swap! server #(-> % (update :clients inc) (update :sockets conj socket)))
                                (doto (Thread. ^Runnable #(serve-client! server socket) "fake-carabiner-client")
                                  (.setDaemon true)
                                  (.start))
                                (recur)))
                            (catch Exception _ nil)))
                        "fake-carabiner")
                   (.setDaemon true)
                   (.start))]
    (try
      (f server)
      (finally
        (.close listening)
        (.join acceptor 1000)
        (doseq [^Socket socket (:sockets @server)]
          (try (.close socket) (catch Exception _ nil)))))))
