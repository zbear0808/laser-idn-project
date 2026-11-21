(ns laser-show.idn.driver
  "The built-in :idn output driver (plans/2026-11-19-output-drivers/01 §1):
   IDN-Stream over UDP, wrapped in IDN-Hello. It only handles the wire; the
   streaming engine (backend/streaming_engine.clj) paces the frames, keeps
   the stats and blanks on errors.

   Supports configurable output formats:
   - 8-bit or 16-bit color (RGB)
   - 8-bit or 16-bit position (XY)

   Supports optional application-layer frame fragmentation per IDN-Stream spec
   Section 6.2 (disabled by default).

   A session (what :open returns) holds one stream's socket, packet buffer,
   sequence counter, config timer and resolved address. Each engine opens its
   own, so engines streaming to services of one device never interleave
   their sequence numbers."
  (:require [clojure.tools.logging :as log]
            [laser-show.dev-config :as dev-config]
            [laser-show.idn.fragmentation :as fragmentation]
            [laser-show.idn.hello :as hello]
            [laser-show.idn.output-config :as output-config]
            [laser-show.idn.stream :as idn-stream])
  (:import [java.net DatagramSocket InetAddress]))


;; Constants


;; IDN-Hello standard port per ILDA specification
(def ^:const DEFAULT_PORT 7255)

;; Default IDN channel - most systems use channel 0 for primary output
(def ^:const DEFAULT_CHANNEL_ID 0)

;; Per IDN-Stream spec Section 2.2: Channel configuration must be present
;; at least every 200ms to allow consumers to recover from packet loss.
;; This ensures robust streaming even with occasional network issues.
(def ^:const CONFIG_RESEND_INTERVAL_MS 200)

;; Default target MTU for fragmentation (conservative for Ethernet)
(def ^:const DEFAULT_TARGET_MTU 1400)


;; Session


(defn- open
  "Open a stream: a UDP socket and the state below. config is
   {:host :port :channel-id :service-id :output-config :target-mtu
   :fragmentation-enabled?} (only :host is required), ctx {:id :scan-rate
   :fps}.

   NOTE: channel-id is for logical multiplexing (0-63), service-id targets
   physical outputs (0-255). For multi-head DACs, each output has a different
   service-id."
  [{:keys [host port channel-id service-id output-config target-mtu fragmentation-enabled?]}
   {:keys [id scan-rate fps]}]
  (let [output-config (or output-config output-config/default-config)
        max-samples {true (idn-stream/max-points-per-message output-config true)
                     false (idn-stream/max-points-per-message output-config false)}
        packet-buffer (idn-stream/create-packet-buffer)
        ;; Last, so nothing can throw once it is open
        socket (hello/create-udp-socket)]
    {:id id
     :target-host host
     :target-port (or port DEFAULT_PORT)
     :channel-id (or channel-id DEFAULT_CHANNEL_ID)
     :service-id (or service-id 0)
     :output-config output-config
     :fps fps
     :scan-rate scan-rate
     ;; Samples that fit one message, with and without config. A frame is cut to
     ;; this (stream/frame->packet), so its duration counts only what is sent
     :max-samples max-samples
     :target-mtu (or target-mtu DEFAULT_TARGET_MTU)
     :fragmentation-enabled? (boolean fragmentation-enabled?)
     :packet-buffer packet-buffer
     :socket socket
     ;; Resolved InetAddress for target-host, cached so packets don't do a DNS lookup each
     :target-address (atom nil)
     ;; The last address resolved, kept when :target-address is cleared for a re-lookup;
     ;; the close pair goes there. Nil if the host was never resolved (nothing was sent)
     :last-address (atom nil)
     ;; System/nanoTime at open; timestamps are microseconds since then
     :start-ns (System/nanoTime)
     ;; System/nanoTime of the last frame sent with its config, nil if none yet
     :last-config-ns (atom nil)
     ;; Each stream needs its own sequence counter for proper packet ordering
     :sequence-counter (atom 0)}))

(defn- describe
  "\"10.0.0.5:7255 service 1 channel 1 (…, fragmentation=disabled)\", for logs."
  [{:keys [host port service-id channel-id output-config fragmentation-enabled?]}]
  (format "%s:%d service %d channel %d (%s, fragmentation=%s)"
          host (or port DEFAULT_PORT) (or service-id 0) (or channel-id DEFAULT_CHANNEL_ID)
          (output-config/config-name (or output-config output-config/default-config))
          (if fragmentation-enabled? "enabled" "disabled")))


;; Timestamp Management


(defn- current-timestamp-us
  "Microseconds since the stream opened, on the monotonic clock: Stream §2.1
   requires timestamps that increase monotonically. Written mod 2^32."
  ^long [session]
  (quot (- (System/nanoTime) (long (:start-ns session))) 1000))

(defn- should-resend-config?
  "True when no config has gone out yet, or the last one is
   CONFIG_RESEND_INTERVAL_MS old (Stream §2.2), on the monotonic clock."
  [session]
  (let [last-ns @(:last-config-ns session)]
    (or (nil? last-ns)
        (>= (- (System/nanoTime) (long last-ns))
            (* CONFIG_RESEND_INTERVAL_MS 1000000)))))


;; Sending


(defn- wire-duration-us
  "How long the DAC takes to draw frame, in µs. An empty frame lasts one frame
   interval. Otherwise it's the samples that go on the wire at the stream's
   scan rate: all of them when fragmentation splits the frame, else at most
   one message's worth (stream/frame->packet cuts the rest)."
  ^long [session frame include-config?]
  (let [n (count frame)]
    (if (zero? n)
      (idn-stream/frame-duration-us (:fps session))
      (idn-stream/scan-duration-us
       (if (:fragmentation-enabled? session)
         n
         (min n (long (get (:max-samples session) (boolean include-config?)))))
       (:scan-rate session)))))

(defn- create-idn-stream-packets
  "Create IDN-Stream packet(s) for the given frame, with the channel
   configuration when include-config?, each saying it takes duration-us to draw.
   Returns a vector of packets (usually 1, more if fragmented).
   Uses the session's output-config for bit depth settings.
   Passes service-id to target the correct physical output on multi-head DACs."
  [session frame include-config? duration-us]
  (let [buf (:packet-buffer session)
        channel-id (:channel-id session)
        service-id (:service-id session)
        timestamp-us (current-timestamp-us session)
        output-cfg (:output-config session)
        target-mtu (:target-mtu session)
        fragmentation-enabled? (:fragmentation-enabled? session)]
    
    ;; Check if fragmentation is enabled and needed
    (if (and fragmentation-enabled?
             (fragmentation/needs-fragmentation? frame output-cfg :target-mtu target-mtu))
      ;; Use fragmentation - returns vector of packets
      (fragmentation/frame->fragmented-packets
       buf frame channel-id timestamp-us duration-us
       :output-config output-cfg
       :service-id service-id
       :include-config? include-config?
       :target-mtu target-mtu)
      ;; Single packet path (no fragmentation needed or disabled)
      [(if include-config?
         (idn-stream/frame->packet-with-config buf frame channel-id timestamp-us duration-us
                                               :service-id service-id
                                               :output-config output-cfg)
         (idn-stream/frame->packet buf frame channel-id timestamp-us duration-us
                                   :output-config output-cfg))])))

(defn- get-next-sequence!
  "Get the next sequence number for this stream.
   Each stream maintains its own sequence counter to avoid interleaving issues."
  [session]
  (swap! (:sequence-counter session) #(bit-and (inc %) 0xFFFF)))

(defn- target-address!
  "Return the session's resolved target InetAddress, resolving it on first use."
  ^InetAddress [session]
  (or @(:target-address session)
      (let [address (InetAddress/getByName (:target-host session))]
        (reset! (:last-address session) address)
        (reset! (:target-address session) address))))

;; Debug logging for streaming (controlled by dev-config/idn-stream-logging?)
(def ^:private stream-log-counter (atom 0))
(def ^:const STREAM_LOG_INTERVAL 300) ;; Log every N frames (~5 seconds at 60fps)

(defn- send-frame!
  "Encode frame and send its packet(s), with the channel configuration when it
   is due (should-resend-config?). Returns {:transfers packet-count
   :duration-us d} (01 §1.2)."
  [session frame]
  (let [{:keys [target-host target-port socket service-id channel-id]} session
        include-config? (should-resend-config? session)
        duration-us (wire-duration-us session frame include-config?)
        ;; Get vector of packets (1 if no fragmentation, multiple if fragmented)
        idn-packets (create-idn-stream-packets session frame include-config? duration-us)
        packet-count (count idn-packets)
        log-count (swap! stream-log-counter inc)]
    
    (when include-config?
      (reset! (:last-config-ns session) (System/nanoTime)))
    
    ;; Send all packets (fragments) for this frame
    (doseq [idn-packet idn-packets]
      (let [seq-num (get-next-sequence! session)
            hello-packet (hello/wrap-channel-message idn-packet {:sequence seq-num})]
        
        ;; Throttled debug logging - only when enabled via dev-config
        (when (and (dev-config/idn-stream-logging?)
                   (zero? (mod log-count STREAM_LOG_INTERVAL))
                   (= service-id (or service-id 0)))
          (log/debug (format "Streaming [%s:%d svc=%d ch=%d]: frame-points=%d, packets=%d, packet-size=%d, seq=%d"
                             target-host target-port (or service-id 0) (or channel-id 0)
                             (count frame) packet-count (alength hello-packet) seq-num)))
        
        (hello/send-packet-to socket hello-packet (target-address! session) target-port)))
    {:transfers packet-count :duration-us duration-us}))

(defn- on-error
  "After a failed frame: re-resolve the host on the next one in case its
   address changed."
  [session _throwable]
  (reset! (:target-address session) nil))


;; Closing


(defn- send-close-pair!
  "Send a channel close message, then an IDN-RT graceful close (0x44,
   IDN-Hello §6.2.2), both numbered with this stream's sequence counter.
   Skipped if the host was never resolved: nothing reached it, and a lookup
   now could block on DNS."
  [session socket]
  (if-let [address (or @(:target-address session) @(:last-address session))]
    (try
      (let [timestamp-us (current-timestamp-us session)
            close-packet (idn-stream/close-channel-packet (:channel-id session) timestamp-us)
            hello-packet (hello/wrap-channel-message close-packet
                                                     {:sequence (get-next-sequence! session)})
            rt-close-packet (hello/create-packet-header hello/CMD_RT_CLOSE 0
                                                        (get-next-sequence! session))]
        (hello/send-packet-to socket hello-packet address (:target-port session))
        (hello/send-packet-to socket rt-close-packet address (:target-port session)))
      (catch Exception e
        (log/error "Error sending close packet:" (.getMessage e))))
    (log/debug "No close pair for" (:id session) "- its host was never resolved")))

(defn- close!
  "Send the close pair and close the socket. Closing the socket also ends a
   send still blocked on it (C-4)."
  [session]
  (let [socket (:socket session)]
    (send-close-pair! session socket)
    (.close ^DatagramSocket socket)))


;; The driver


(def driver
  "The :idn driver map (01 §1). Each function calls through its var, so a
   reload of this namespace reaches engines that are already open."
  {:id :idn
   :name "IDN (ILDA Digital Network)"
   :short-name "IDN"
   :api 1
   :transport :network
   :fields [{:key :host :label "Host" :type :host :required? true}
            {:key :port :label "Port" :type :int :min 1 :max 65535 :default DEFAULT_PORT}
            {:key :service-id :label "Service" :type :int :min 0 :max 255 :default 0}]
   :capabilities {:scan-rate {:min idn-stream/MIN_SCAN_RATE
                              :max idn-stream/MAX_SCAN_RATE
                              :default idn-stream/DEFAULT_SCAN_RATE}
                  :max-points-per-frame nil}
   :open (fn [config ctx] (open config ctx))
   :send-frame! (fn [session frame] (send-frame! session frame))
   :close! (fn [session] (close! session))
   :on-error (fn [session throwable] (on-error session throwable))
   :describe (fn [config] (describe config))})
