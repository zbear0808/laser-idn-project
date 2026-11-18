(ns laser-show.idn.fake-dac
  "A receive-only stand-in for an IDN DAC on loopback, for tests.

   start! binds 127.0.0.1 on a free port and records every datagram with its
   source port and arrival time. Each streaming engine has its own socket, so a
   source port identifies an engine. messages decodes what arrived the way a
   DAC would: IDN-Hello header, channel message, channel config, and frame
   samples laid out by the last config seen on that sender's channel.
   violations checks everything that arrived against the stream rules.

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


;; Checking a stream against the rules

(def ^:private max-config-gap-us
  "Stream §2.2: on unreliable links the configuration is resent every 200 or
   250 ms."
  250000)

(defn- u8 ^long [^bytes b i] (bit-and (aget b (int i)) 0xFF))

(defn- u16 ^long [^bytes b i] (bit-or (bit-shift-left (u8 b i) 8) (u8 b (inc (long i)))))

(defn- u32 ^long [^bytes b i] (bit-or (bit-shift-left (u16 b i) 16) (u16 b (+ 2 (long i)))))

(defn- sample-size
  "Octets per sample for a tag array, or nil when it describes none."
  [tags]
  (let [size (reduce + (map second (field-layout tags)))]
    (when (pos? size) size)))

(defn- message-violations
  "Violations of one channel message (payload, the datagram minus its Hello
   header) on a channel in state {:configured? :last-config-ts :last-ts :tags},
   and the channel's next state."
  [^bytes payload channel {:keys [configured? last-config-ts last-ts tags] :as cnl}]
  (let [n (alength payload)
        total-size (u16 payload 0)
        cnl-byte (u8 payload 2)
        chunk-type (u8 payload 3)
        ts (u32 payload 4)
        sequel? (>= chunk-type stream/CHUNK_TYPE_FRAME_SAMPLES_SEQUEL)
        back? (and (not sequel?) last-ts
                   (>= (bit-and (- ts (long last-ts)) 0xFFFFFFFF) 0x80000000))
        has-config? (and (not sequel?) (bit-test cnl-byte 6))
        [scwc cfl service-id service-mode] (when has-config? (map #(u8 payload %) (range 8 12)))
        routing? (and has-config? (bit-test cfl 0))
        close? (and has-config? (bit-test cfl 1))
        config-tags (when (and has-config? (pos? scwc))
                      (mapv #(u16 payload (+ 12 (* 2 %))) (range (* 2 scwc))))
        tags (or config-tags tags)
        data-at (if has-config? (+ 12 (* 4 scwc)) 8)
        found (cond-> []
                (not (bit-test cnl-byte 7))
                (conj {:rule :message-size :detail "CNL bit 7 (channel message) is clear"})

                back?
                (conj {:rule :timestamps :detail (str "timestamp " ts " after " last-ts)})

                (and (not sequel?) (not has-config?) (not configured?))
                (conj {:rule :config :detail "the first message on the channel has no configuration"})

                (and (not sequel?) (not back?) (not has-config?) configured?
                     (> (bit-and (- ts (long last-config-ts)) 0xFFFFFFFF) max-config-gap-us))
                (conj {:rule :config
                       :detail (str (quot (bit-and (- ts (long last-config-ts)) 0xFFFFFFFF) 1000)
                                    " ms after the last configuration")})

                (and has-config? (not (zero? (bit-and cfl 0xFC))))
                (conj {:rule :unused-bits :detail (format "CFL 0x%02X: SDM or reserved bits set" cfl)})

                (and has-config? (not routing?) (not (zero? (bit-or scwc service-id service-mode))))
                (conj {:rule :unused-bits :detail "SCWC, service ID or mode set without Routing"}))
        frame (when (and (= chunk-type stream/CHUNK_TYPE_FRAME_SAMPLES) (<= (+ data-at 4) n))
                (let [flags (u8 payload data-at)
                      octets (- n data-at 4)
                      size (sample-size tags)]
                  (cond-> []
                    (not (zero? (bit-and flags 0xFE)))
                    (conj {:rule :unused-bits :detail (format "chunk flags 0x%02X: SCM or reserved bits set" flags)})

                    (and (pos? octets) (nil? size))
                    (conj {:rule :frame :detail "samples, but no configuration describes them"})

                    (and (pos? octets) size (not (zero? (rem octets (long size)))))
                    (conj {:rule :frame :detail (str octets " octets of samples, not a whole number of "
                                                     size "-octet samples")})

                    (and (pos? octets) size (zero? (rem octets (long size))) (< (quot octets (long size)) 2))
                    (conj {:rule :frame :detail "one sample: a frame needs a start and an end point"})

                    (and (pos? octets) size (zero? (rem octets (long size))))
                    (into (let [buf (ByteBuffer/wrap payload (int (+ data-at 4)) (int size))
                                [[_ _ r g b]] (decode-samples (.slice buf) tags)]
                            (when-not (= 0 r g b)
                              [{:rule :frame :detail (str "the first sample is lit: " [r g b])}]))))))]
    [(if (or (not= total-size n) (< total-size 8) (> total-size stream/MAX_MESSAGE_SIZE))
       (conj found {:rule :message-size :detail (str "total size " total-size ", message " n " octets")})
       (into found frame))
     (cond
       close? {}
       sequel? cnl
       :else (cond-> (assoc cnl :last-ts ts :tags tags :configured? (or configured? has-config?))
               has-config? (assoc :last-config-ts ts)))]))

(defn check
  "What in datagrams ({:port :ns :bytes}, in arrival order) breaks the IDN
   stream rules: [{:rule :port :seq :detail} …], empty when nothing does.
   - :hello-header: 4 octets at least, command 0x40 or 0x44 (Hello §2.1, §6.2)
   - :sequence: +1 per packet from each sender, wrapping at 16 bits (Hello §6)
   - :message-size: total size = datagram minus 4, in 0x0008-0xFF00, CNL bit 7
     set (Hello §6.1, Stream §2.1)
   - :timestamps: never decrease on a sender's channel, wrapping at 32 bits,
     sequel fragments left out (Stream §2.1)
   - :config: on each channel the first message (and the first after a close)
     carries the configuration, and no message comes more than 250 ms after
     the last one that did, by the messages' own timestamps (Stream §2.2)
   - :frame: a non-empty frame has 2 samples at least, a whole number of them,
     and a dark first one (Stream §6.2)
   - :unused-bits: the upper Hello flags, SDM and the reserved CFL bits,
     SCWC/service ID/mode without Routing, and the chunk flags other than Once
     are zero (Hello §2.1, Stream §1.4, §2.2, §6.2)
   :skip leaves out the rules a test breaks on purpose."
  [datagrams & {:keys [skip]}]
  (let [skip (set skip)]
    (->> (reduce
          (fn [{:keys [seqs channels] :as acc} {:keys [port ^bytes bytes]}]
            (if (< (alength bytes) 4)
              (update acc :found conj {:rule :hello-header :port port
                                       :detail (str (alength bytes) " octets")})
              (let [cmd (u8 bytes 0)
                    flags (u8 bytes 1)
                    sq (u16 bytes 2)
                    expected (some-> (get seqs port) (+ 1) (bit-and 0xFFFF))
                    tag #(assoc % :port port :seq sq)
                    payload (when (> (alength bytes) 4) (Arrays/copyOfRange bytes 4 (alength bytes)))
                    channel (when (and payload (>= (alength payload) 3)) (bit-and (aget payload 2) 0x3F))
                    [msg-found cnl'] (if (and payload (>= (alength payload) 8)
                                              (#{hello/CMD_RT_CHANNEL_MESSAGE hello/CMD_RT_CLOSE} cmd))
                                       (message-violations payload channel (get channels [port channel] {}))
                                       [(when payload
                                          [{:rule :message-size :detail (str (alength payload) " octets after the header")}])
                                        nil])
                    found (cond-> []
                            (not (#{hello/CMD_RT_CHANNEL_MESSAGE hello/CMD_RT_CLOSE} cmd))
                            (conj {:rule :hello-header :detail (format "command 0x%02X" cmd)})

                            (not (zero? (bit-and flags 0xF0)))
                            (conj {:rule :unused-bits :detail (format "Hello flags 0x%02X" flags)})

                            (and expected (not= sq expected))
                            (conj {:rule :sequence :detail (str "expected " expected)})

                            :always (into msg-found))]
                (cond-> (-> acc
                            (update :found into (map tag) found)
                            (assoc-in [:seqs port] sq))
                  cnl' (assoc-in [:channels [port channel]] cnl')))))
          {:found [] :seqs {} :channels {}}
          datagrams)
         :found
         (remove (comp skip :rule))
         vec)))

(defn violations
  "check on every datagram dac has received (same options)."
  [dac & opts]
  (apply check @(:received dac) opts))
