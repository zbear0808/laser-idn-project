(ns laser-show.output.lsps-receiver
  "A receive-only LSPS v1 receiver on loopback, for tests
   (plans/2026-11-19-output-drivers/01 §3), built like
   laser-show.idn.fake-dac.

   start! binds 127.0.0.1 on a free port and records every datagram with
   its source port and arrival time. packets decodes them: the 20-byte
   header and the 10-byte points, as raw integers ([x y] signed, [r g b]
   unsigned). frames puts the packets back together the way a bridge does
   (01 §3's receiver rules), and violations checks L-1 to L-3.

   Waits are bounded by deadlines (wait-for!), never fixed sleeps.

   Not a test namespace: the runner only selects namespaces ending in -test."
  (:require [laser-show.output.lsps :as lsps])
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
                 (.setReceiveBufferSize (* 8 1024 1024)))
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
                      "lsps-receiver")
                 (.setDaemon true)
                 (.start))]
    {:port (.getLocalPort socket) :socket socket :thread thread :received received}))

(defn stop!
  "Close the socket and wait for the receiver thread to end."
  [{:keys [^DatagramSocket socket ^Thread thread]}]
  (.close socket)
  (.join thread 1000))

(defmacro with-receiver
  "(with-receiver [rx (start!)] body…): body with rx bound to a started
   receiver, stopped afterwards, like with-open."
  [[name init] & body]
  `(let [~name ~init]
     (try
       ~@body
       (finally
         (stop! ~name)))))


;; Decoding

(defn decode
  "One LSPS datagram as {:magic :version :flags :last? :close? :sequence
   :frame :total :index :count :duration-us :points :size}. :points are
   [x y r g b] integers as they are on the wire."
  [^bytes bytes]
  (let [buf (doto (ByteBuffer/wrap bytes) (.order ByteOrder/BIG_ENDIAN))
        magic (String. bytes 0 4 "US-ASCII")
        _ (.position buf 4)
        version (bit-and (.get buf) 0xFF)
        flags (bit-and (.get buf) 0xFF)
        u16 #(bit-and (.getShort buf) 0xFFFF)
        sq (u16)
        frame (u16)
        total (u16)
        index (u16)
        n (u16)
        duration (bit-and (.getInt buf) 0xFFFFFFFF)]
    {:magic magic
     :version version
     :flags flags
     :last? (bit-test flags 0)
     :close? (bit-test flags 1)
     :sequence sq
     :frame frame
     :total total
     :index index
     :count n
     :duration-us duration
     :size (alength bytes)
     :points (vec (for [_ (range (quot (.remaining buf) lsps/POINT_BYTES))]
                    [(.getShort buf) (.getShort buf) (u16) (u16) (u16)]))}))


;; Queries

(defn packets
  "Every datagram received so far, decoded, in arrival order, each with
   the :port it came from."
  [rx]
  (mapv (fn [{:keys [port bytes]}] (assoc (decode bytes) :port port)) @(:received rx)))

(defn raw
  "The bytes of every datagram received so far, as unsigned ints."
  [rx]
  (mapv (fn [{:keys [^bytes bytes]}] (mapv #(bit-and % 0xFF) bytes)) @(:received rx)))

(defn frames
  "The complete frames among packets, as a bridge reassembles them: the
   packets of one frame number, contiguous from index 0, ending with LAST.
   Each is {:frame :total :duration-us :points}."
  [packets]
  (->> packets
       (remove :close?)
       (partition-by :frame)
       (keep (fn [ps]
               (let [{:keys [frame total duration-us]} (first ps)]
                 (when (and (:last? (last ps))
                            (= (map :index ps) (reductions + 0 (butlast (map :count ps))))
                            (= total (reduce + (map :count ps))))
                   {:frame frame :total total :duration-us duration-us
                    :points (into [] (mapcat :points) ps)}))))
       vec))

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

(defn wait-for-packets!
  "Wait until at least n datagrams have arrived (up to 2 s), then return
   them decoded."
  [rx n]
  (wait-for! #(>= (count @(:received rx)) n) 2000)
  (packets rx))


;; Checking a stream against the rules

(defn violations
  "What in packets (decoded, in arrival order) breaks 01 §3's rules, as
   [{:rule :sequence :detail …} …], empty when nothing does:
   - :header: magic \"LSPS\", version 1, only the LAST and CLOSE flags;
   - :frame (L-1): within a frame the indexes are contiguous from 0, the
     counts add up to the total, only the last packet has LAST, and a
     packet's points are its count;
   - :sequence (L-2): +1 per packet, wrapping at 0xFFFF, within a stream: a
     CLOSE ends one, and the next (a new session, e.g. after a restart)
     starts its own count;
   - :size (L-3): no packet is larger than max-packet-bytes."
  [packets max-packet-bytes]
  (let [header (for [{:keys [magic version flags sequence]} packets
                     :when (or (not= "LSPS" magic) (not= 1 version) (not (zero? (bit-and flags 0xFC))))]
                 {:rule :header :sequence sequence :detail [magic version flags]})
        size (for [{:keys [size sequence]} packets
                   :when (> size max-packet-bytes)]
               {:rule :size :sequence sequence :detail (str size " bytes")})
        sq (for [[a b] (partition 2 1 packets)
                 :when (and (not (:close? a))
                            (not= (:sequence b) (bit-and (inc (:sequence a)) 0xFFFF)))]
             {:rule :sequence :sequence (:sequence b) :detail (str "after " (:sequence a))})
        streams (loop [ps packets out []]
                  (if (empty? ps)
                    out
                    (let [[before more] (split-with (complement :close?) ps)]
                      (recur (rest more) (conj out (vec before))))))
        frame (for [stream streams
                    ps (partition-by :frame stream)
                    :let [{:keys [total frame]} (first ps)
                          problems (cond-> []
                                     (not= (map :index ps) (reductions + 0 (butlast (map :count ps))))
                                     (conj "indexes aren't contiguous from 0")
                                     (not= total (reduce + (map :count ps)))
                                     (conj "counts don't add up to the total")
                                     (not= (map :last? ps) (concat (repeat (dec (count ps)) false) [true]))
                                     (conj "LAST isn't on the last packet only")
                                     (some #(not= (:count %) (count (:points %))) ps)
                                     (conj "a packet's points aren't its count")
                                     (some #(not= total (:total %)) ps)
                                     (conj "the total changes within the frame"))]
                    p problems]
                {:rule :frame :frame frame :detail p})]
    (vec (concat header size sq frame))))
