(ns laser-show.idn.fake-dac
  "A receive-only stand-in for an IDN DAC on loopback, for tests.

   start! binds 127.0.0.1 on a free port and records every datagram with its
   source port and arrival time. Each streaming engine has its own socket, so a
   source port identifies an engine. messages decodes what arrived the way a
   DAC would: IDN-Hello header, channel message, channel config, and frame
   samples laid out by the last config seen on that sender's channel.

   Waits are bounded by deadlines (wait-for!, quiet?), never fixed sleeps.

   Not a test namespace: the runner only selects namespaces ending in -test."
  (:require [laser-show.idn.hello :as hello]
            [laser-show.idn.stream :as stream])
  (:import [java.net DatagramPacket DatagramSocket InetAddress SocketException SocketTimeoutException]
           [java.nio ByteBuffer ByteOrder]
           [java.util Arrays]))


;; Receiver

(defn start!
  "Bind 127.0.0.1 on a free port and start recording datagrams on a daemon
   thread. Returns {:port … :received atom …}; stop it with stop!."
  []
  (let [socket (doto (DatagramSocket. 0 (InetAddress/getLoopbackAddress))
                 (.setSoTimeout 50)
                 (.setReceiveBufferSize (* 4 1024 1024)))
        received (atom [])
        thread (doto (Thread.
                      (fn []
                        (let [buf (byte-array 65536)
                              pkt (DatagramPacket. buf (alength buf))]
                          (while (not (.isClosed socket))
                            (try
                              (.setLength pkt (alength buf))
                              (.receive socket pkt)
                              (swap! received conj {:port (.getPort pkt)
                                                    :ns (System/nanoTime)
                                                    :bytes (Arrays/copyOf buf (.getLength pkt))})
                              (catch SocketTimeoutException _)
                              (catch SocketException _)))))
                      "fake-dac")
                 (.setDaemon true)
                 (.start))]
    {:port (.getLocalPort socket) :socket socket :thread thread :received received}))

(defn stop!
  "Close the socket and wait for the receiver thread to end."
  [{:keys [^DatagramSocket socket ^Thread thread]}]
  (.close socket)
  (.join thread 1000))


;; Decoding

(defn- field-layout
  "[[field bytes] …] for a tag array: X, Y, red, green, blue, or :skip for
   padding (NOP). A field followed by a precision tag takes 2 bytes."
  [tags]
  (loop [[tag & more] tags
         acc []]
    (cond
      (nil? tag) acc
      (= tag stream/TAG_VOID) (recur more acc)
      (= tag stream/TAG_NOP) (recur more (conj acc [:skip 1]))
      :else (let [field (condp = tag
                          stream/TAG_X :x
                          stream/TAG_Y :y
                          stream/TAG_COLOR_RED :r
                          stream/TAG_COLOR_GREEN :g
                          stream/TAG_COLOR_BLUE :b
                          :skip)
                  wide? (= stream/TAG_PRECISION (first more))]
              (recur (if wide? (rest more) more)
                     (conj acc [field (if wide? 2 1)]))))))

(defn- read-field [^ByteBuffer buf field width]
  (let [signed? (#{:x :y} field)]
    (if (= 2 width)
      (let [v (.getShort buf)] (if signed? v (bit-and v 0xFFFF)))
      (let [v (.get buf)] (if signed? v (bit-and v 0xFF))))))

(defn- decode-samples [^ByteBuffer buf tags]
  (let [layout (field-layout tags)
        size (reduce + (map second layout))]
    (when (pos? size)
      (vec (for [_ (range (quot (.remaining buf) size))]
             (let [values (into {} (keep (fn [[field width]]
                                           (let [v (read-field buf field width)]
                                             (when (not= :skip field) [field v]))))
                                layout)]
               (mapv #(get values % 0) [:x :y :r :g :b])))))))

(defn- decode-channel-message
  "Decode one IDN-Stream channel message. tags-for is the tag array to use when
   the message carries no config."
  [^bytes payload tags-for]
  (let [buf (doto (ByteBuffer/wrap payload) (.order ByteOrder/BIG_ENDIAN))
        _total-size (bit-and (.getShort buf) 0xFFFF)
        cnl (bit-and (.get buf) 0xFF)
        chunk-type (bit-and (.get buf) 0xFF)
        timestamp (bit-and (.getInt buf) 0xFFFFFFFF)
        has-config? (bit-test cnl 6)
        config (when has-config?
                 (let [scwc (bit-and (.get buf) 0xFF)
                       flags (bit-and (.get buf) 0xFF)
                       service-id (bit-and (.get buf) 0xFF)
                       service-mode (bit-and (.get buf) 0xFF)
                       tags (vec (repeatedly (* 2 scwc) #(bit-and (.getShort buf) 0xFFFF)))]
                   {:flags flags :service-id service-id :service-mode service-mode :tags tags}))
        msg (cond-> {:channel-id (bit-and cnl 0x3F)
                     :has-config? has-config?
                     :chunk-type chunk-type
                     :timestamp timestamp}
              config (assoc :service-id (:service-id config)
                            :service-mode (:service-mode config)
                            :tags (:tags config)
                            :close? (bit-test (:flags config) 1)))]
    (if (= chunk-type stream/CHUNK_TYPE_FRAME_SAMPLES)
      (let [flags (bit-and (.get buf) 0xFF)
            duration (bit-or (bit-shift-left (bit-and (.get buf) 0xFF) 16)
                             (bit-shift-left (bit-and (.get buf) 0xFF) 8)
                             (bit-and (.get buf) 0xFF))
            tags (or (:tags config) tags-for)]
        (assoc msg
               :duration-us duration
               :once? (bit-test flags 0)
               :samples (when tags (decode-samples buf tags))))
      msg)))

(defn decode-all
  "Decode recorded datagrams ({:port :ns :bytes}) in arrival order. Samples of a
   message without config are decoded with the last config that sender sent on
   that channel (nil :samples if there was none yet)."
  [datagrams]
  (first
   (reduce (fn [[acc configs] {:keys [port ^bytes bytes] :as d}]
             (let [{:keys [command sequence payload]} (hello/parse-packet-header bytes)
                   cmd (bit-and command 0xFF)
                   base (assoc (dissoc d :bytes) :cmd cmd :seq (bit-and sequence 0xFFFF))]
               (if (and (= cmd hello/CMD_RT_CHANNEL_MESSAGE) payload)
                 (let [channel (bit-and (aget payload 2) 0x3F)
                       msg (decode-channel-message payload (get configs [port channel]))]
                   [(conj acc (merge base msg))
                    (cond-> configs
                      (seq (:tags msg)) (assoc [port channel] (:tags msg)))])
                 [(conj acc (assoc base :payload payload)) configs])))
           [[] {}]
           datagrams)))


;; Queries

(defn messages
  "Every datagram received so far, decoded (see decode-all). With :since-ns,
   only those that arrived after that System/nanoTime."
  [dac & {:keys [since-ns]}]
  (cond->> (decode-all @(:received dac))
    since-ns (filterv #(> (:ns %) since-ns))))

(defn frames
  "Frame-sample messages only. Same options as messages."
  [dac & opts]
  (filterv #(= stream/CHUNK_TYPE_FRAME_SAMPLES (:chunk-type %))
           (apply messages dac opts)))

(defn senders
  "Source ports that sent a frame message. Same options as messages."
  [dac & opts]
  (set (map :port (apply frames dac opts))))

(defn ms-ago
  "System/nanoTime of ms milliseconds ago, for :since-ns."
  [ms]
  (- (System/nanoTime) (* (long ms) 1000000)))

(defn wait-for!
  "Call pred every few ms until it returns truthy or timeout-ms passes.
   Returns pred's value, or nil on timeout."
  [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
    (loop []
      (or (pred)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep 5)
            (recur))))))

(defn quiet?
  "True if no datagram arrives within the next ms milliseconds. Returns false
   as soon as one does."
  [dac ms]
  (let [n (count @(:received dac))]
    (not (wait-for! #(> (count @(:received dac)) n) ms))))
