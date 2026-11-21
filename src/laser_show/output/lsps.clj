(ns laser-show.output.lsps
  "LSPS v1, the Laser Show Point Stream (plans/2026-11-19-output-drivers/01
   §3, docs/OUTPUT_DRIVERS.md): our own one-way UDP point protocol, and the
   built-in :lsps output driver that speaks it. It is meant for bridges (a
   separate program that drives the hardware) and as the reference driver
   for add-on authors.

   Each frame goes out as one or more datagrams, big-endian:

     0  4  magic \"LSPS\"
     4  1  version, 1
     5  1  flags: bit 0 LAST (the frame's last packet), bit 1 CLOSE
     6  2  packet sequence, +1 every packet (close included), wrapping
     8  2  frame number, +1 every frame sent, wrapping
     10 2  total points in the frame
     12 2  index of this packet's first point
     14 2  points in this packet
     16 4  frame duration, µs
     20 …  points, 10 bytes each: x y as s16, r g b as u16

   A packet carries up to (max-packet-bytes - 20) / 10 points; a frame is cut
   to 65,535 points. An empty frame is one packet with LAST and no points
   (go dark). On close, one CLOSE packet, then the socket closes.

   The registry (laser-show.output.driver) registers driver when it loads."
  (:require [clojure.string :as str]
            [laser-show.idn.stream :as stream]
            [laser-show.output.sample-format :as sf])
  (:import [java.net DatagramPacket DatagramSocket InetAddress UnknownHostException]
           [java.nio ByteBuffer]
           [java.util.concurrent TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

(set! *warn-on-reflection* true)

(def ^:const VERSION 1)
(def ^:const HEADER_BYTES 20)
(def ^:const POINT_BYTES 10)
(def ^:const FLAG_LAST 0x01)
(def ^:const FLAG_CLOSE 0x02)

(def ^:const MAX_POINTS
  "The most points a frame can have: the header counts them in 16 bits.
   Longer frames are cut."
  65535)

(def ^:const DEFAULT_PORT 7266)
(def ^:const DEFAULT_MAX_PACKET_BYTES 1400)
(def ^:const MIN_PACKET_BYTES 120)
(def ^:const MAX_PACKET_BYTES
  "The largest UDP payload over IPv4."
  65507)

(def ^:private magic (.getBytes "LSPS" "US-ASCII"))

(def point-layout
  "The points' sample layout (laser-show.output.sample-format)."
  {:byte-order :big :fields [[:x :s16] [:y :s16] [:r :u16] [:g :u16] [:b :u16]]})

(def ^:private compiled-layout (sf/compile-layout point-layout))

(def ^:private close-lock-ms
  "How long close! waits for a send in progress before it gives up on it
   (01 §1.4 C-4); well inside the core's CLOSE_MS (500)."
  100)

(def fields
  "The settings a projector with this driver has (01 §5.4)."
  [{:key :host :label "Host" :type :host :required? true :summary? true
    :hint "The computer the bridge runs on, e.g. 127.0.0.1"}
   {:key :port :label "Port" :type :int :min 1 :max 65535 :default DEFAULT_PORT :summary? true}
   {:key :max-packet-bytes :label "Max packet size (bytes)" :type :int
    :min MIN_PACKET_BYTES :max MAX_PACKET_BYTES :default DEFAULT_MAX_PACKET_BYTES
    :hint "The largest datagram sent. 1400 fits a normal network without fragmenting"}])

(def ^:private defaults
  {:port DEFAULT_PORT :max-packet-bytes DEFAULT_MAX_PACKET_BYTES})


;; Packets

(defn- put-header!
  [^ByteBuffer buf flags sq frame-no total index n duration-us]
  (doto buf
    (.put ^bytes magic)
    (.put (unchecked-byte VERSION))
    (.put (unchecked-byte flags))
    (.putShort (unchecked-short sq))
    (.putShort (unchecked-short frame-no))
    (.putShort (unchecked-short total))
    (.putShort (unchecked-short index))
    (.putShort (unchecked-short n))
    (.putInt (unchecked-int duration-us))))

(defn- next-sequence!
  "This packet's sequence number; the session's moves on, wrapping at 16 bits."
  ^long [session]
  (let [[sq _] (swap-vals! (:next-sequence session) #(bit-and (inc (long %)) 0xFFFF))]
    sq))

(defn- next-frame!
  ^long [session]
  (let [[n _] (swap-vals! (:next-frame session) #(bit-and (inc (long %)) 0xFFFF))]
    n))

(defn- send-buffer! [session ^ByteBuffer buf]
  (let [^DatagramPacket packet (:packet session)]
    (.setLength packet (.position buf))
    (.send ^DatagramSocket (:socket session) packet)))


;; The driver

(defn- check-config
  "config with its defaults, or throws when it can't be used."
  [config]
  (let [{:keys [host port max-packet-bytes] :as config} (merge defaults config)
        host (when (string? host) (str/trim host))]
    (when (str/blank? host)
      (throw (ex-info "LSPS needs a host" {:config config})))
    (when-not (and (int? port) (<= 1 port 65535))
      (throw (ex-info (str "LSPS port must be 1–65535, not " (pr-str port)) {:config config})))
    (when-not (and (int? max-packet-bytes) (<= MIN_PACKET_BYTES max-packet-bytes MAX_PACKET_BYTES))
      (throw (ex-info (str "LSPS packet size must be " MIN_PACKET_BYTES "–" MAX_PACKET_BYTES
                           " bytes, not " (pr-str max-packet-bytes))
                      {:config config})))
    (assoc config :host host)))

(defn- resolve-host ^InetAddress [host]
  (try
    (InetAddress/getByName host)
    (catch UnknownHostException e
      (throw (ex-info (str "LSPS can't find host " host) {:host host} e)))))

(defn open
  "The driver's :open (01 §1): check config (§3's fields, defaults filled
   in), resolve the host once and open a UDP socket. ctx gives :scan-rate
   (pts/s) and :fps. Throws when the config is invalid or the host can't be
   found, with nothing left open."
  [config ctx]
  (let [{:keys [host port max-packet-bytes]} (check-config config)
        address (resolve-host host)
        socket (DatagramSocket.)]
    (try
      (when (< (.getSendBufferSize socket) (long max-packet-bytes))
        (.setSendBufferSize socket max-packet-bytes))
      (let [buf (ByteBuffer/allocate max-packet-bytes)]
        {:host host
         :port port
         :address address
         :socket socket
         :max-packet-bytes max-packet-bytes
         :points-per-packet (quot (- (long max-packet-bytes) HEADER_BYTES) POINT_BYTES)
         :scan-rate (long (or (:scan-rate ctx) stream/DEFAULT_SCAN_RATE))
         :fps (or (:fps ctx) 60)
         :buffer buf
         :packet (DatagramPacket. (.array buf) 0 address (int port))
         :next-sequence (atom 0)
         :next-frame (atom 0)
         :lock (ReentrantLock.)
         :closed? (atom false)})
      (catch Throwable t
        (.close socket)
        (throw t)))))

(defn send-frame!
  "The driver's :send-frame! (01 §1): send frame as LSPS packets, splitting
   it by the session's packet size, and return {:transfers packets
   :duration-us d}. d is the scan time of the points sent at the session's
   scan rate (stream/scan-duration-us), or one frame interval for an empty
   frame. Only the streaming thread calls it (C-3)."
  [session frame]
  (let [^ReentrantLock lock (:lock session)
        ^ByteBuffer buf (:buffer session)
        total (min (count frame) MAX_POINTS)
        duration-us (if (zero? total)
                      (stream/frame-duration-us (:fps session))
                      (stream/scan-duration-us total (:scan-rate session)))
        per-packet (long (:points-per-packet session))
        frame-no (next-frame! session)]
    (.lock lock)
    (try
      (if (zero? total)
        (do (.clear buf)
            (put-header! buf FLAG_LAST (next-sequence! session) frame-no 0 0 0 duration-us)
            (send-buffer! session buf)
            {:transfers 1 :duration-us duration-us})
        (loop [index 0
               transfers 0]
          (if (< index total)
            (let [n (min per-packet (- total index))
                  end (+ index n)]
              (.clear buf)
              (put-header! buf (if (= end total) FLAG_LAST 0) (next-sequence! session)
                           frame-no total index n duration-us)
              (sf/encode-points! compiled-layout buf frame index end)
              (send-buffer! session buf)
              (recur end (inc transfers)))
            {:transfers transfers :duration-us duration-us})))
      (finally
        (.unlock lock)))))

(defn- close-packet ^bytes [session]
  (let [buf (ByteBuffer/allocate HEADER_BYTES)]
    (put-header! buf FLAG_CLOSE (next-sequence! session) 0 0 0 0 0)
    (.array buf)))

(defn close!
  "The driver's :close! (01 §1): send one CLOSE packet, then close the
   socket. If a send is stuck on the streaming thread (C-4), it doesn't wait
   for it: the socket is closed first, which ends the stuck send, and the
   CLOSE goes out from a short-lived socket of its own. A second call does
   nothing."
  [session]
  (when (compare-and-set! (:closed? session) false true)
    (let [^ReentrantLock lock (:lock session)
          ^DatagramSocket socket (:socket session)
          send-close! (fn [^DatagramSocket s]
                        (let [data (close-packet session)]
                          (.send s (DatagramPacket. data (alength data)
                                                    ^InetAddress (:address session)
                                                    (int (:port session))))))]
      (if (.tryLock lock close-lock-ms TimeUnit/MILLISECONDS)
        (try
          (try
            (send-close! socket)
            (finally
              (.close socket)))
          (finally
            (.unlock lock)))
        (do
          (.close socket)
          (with-open [s (DatagramSocket.)]
            (send-close! s)))))
    nil))

(defn describe
  "The driver's :describe: \"host:port\", for logs and the projector list."
  [config]
  (let [{:keys [host port]} (merge defaults config)]
    (str host ":" port)))

(def driver
  "The built-in :lsps output driver (API 1)."
  {:id :lsps
   :name "LSPS point stream (UDP)"
   :short-name "LSPS"
   :api 1
   :transport :network
   :fields fields
   :capabilities {:scan-rate {:min stream/MIN_SCAN_RATE
                              :max stream/MAX_SCAN_RATE
                              :default stream/DEFAULT_SCAN_RATE}
                  :max-points-per-frame MAX_POINTS}
   :open open
   :send-frame! send-frame!
   :close! close!
   :describe describe})
