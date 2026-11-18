(ns laser-show.backend.streaming-engine
  "IDN streaming engine - manages continuous frame streaming to laser hardware.
   Implements IDN-Stream spec compliant packet generation and transmission.
   Runs in a separate thread and sends frames at a configurable rate.
   
   Supports configurable output formats:
   - 8-bit or 16-bit color (RGB)
   - 8-bit or 16-bit position (XY)
   
   Supports optional application-layer frame fragmentation per IDN-Stream spec Section 6.2
   (disabled by default; see create-engine)."
  (:require [clojure.tools.logging :as log]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.common.timing :as timing]
            [laser-show.idn.frame-rules :as frame-rules]
            [laser-show.idn.stream :as idn-stream]
            [laser-show.idn.output-config :as output-config]
            [laser-show.idn.fragmentation :as fragmentation]
            [laser-show.animation.types :as t]
            [laser-show.idn.hello :as hello]
            [laser-show.dev-config :as dev-config]
            [laser-show.profiling.frame-profiler :as profiler])
  (:import [java.net DatagramSocket InetAddress]))


;; Constants


;; Default streaming FPS - 60 FPS provides smooth animation while being
;; achievable on most networks and hardware
(def ^:const DEFAULT_FPS 60)

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


;; Engine State


(def ^:private initial-stats
  "Stats of an engine that hasn't sent a frame yet (see update-stats)."
  {:frames-sent 0
   :packets-sent 0
   :fragments-sent 0
   :last-send-ns nil
   :frame-duration-us 0
   :interval-us 0.0
   :frame-time-us 0.0
   :send-fps 0.0
   :draw-fps 0.0
   :scan-limited? false
   :tick-to-send-us nil})

(def ^:const KEEPALIVE_INTERVALS
  "While paced by the show clock, an engine that gets no tick for this many
   frame intervals (50 ms at 60 fps) sends the newest frame again. In discrete
   mode the DAC repeats the last frame anyway (Stream §3.3); this keeps the
   link alive (Hello §7.2) and the config at most 250 ms apart (Stream §2.2)."
  3)

(defn create-engine
  "Create a new streaming engine configuration.
   
   Parameters:
   - target-host: IP address of the laser DAC
   - frame-provider: Function that returns the current LaserFrame to send. A
     frame may carry {:tick :at-ns} metadata: the show clock's tick it shows
     and when that was published (see :frame-signal)
   - opts: Optional map with:
     - :id - Names the engine in output-errors (default \"host:port/channel\")
     - :fps - Target frames per second (default 60)
     - :port - Target UDP port (default 7255)
     - :channel-id - IDN channel ID for message header (default 0)
     - :service-id - IDN service ID for config header (default 0) - targets specific laser output
     - :output-config - OutputConfig for bit depth (default 8-bit color, 16-bit XY)
     - :scan-rate - Points per second the projector draws (default
       idn-stream/DEFAULT_SCAN_RATE, clamped by idn-stream/clamp-scan-rate). It
       sets each frame's duration, so the galvos never run faster than it
     - :target-mtu - Target MTU for fragmentation (default 1400)
     - :fragmentation-enabled? - Enable application-layer fragmentation (default false).
       Off by default: frames up to the 0xFF00 message limit are sent in one message
       (Stream spec §6.2 prefers IP fragmentation), larger frames are truncated.
       The sequel-fragment layout has not been checked against a reference receiver.
     - :frame-signal - The show clock's ticks to send on (show-time/frame-signal):
       {:tick :running? :await}. While (:running?) is true the engine sends each
       new tick's frame as soon as it is published, and the newest one again after
       KEEPALIVE_INTERVALS without a tick; otherwise, and without a signal, it
       paces itself at :fps
   
   NOTE: channel-id is for logical multiplexing (0-63), service-id targets physical outputs (0-255).
   For multi-head DACs, each output has a different service-id.
   
   Returns an engine map that can be started with start!"
  [target-host frame-provider & {:keys [id fps port channel-id service-id output-config
                                        scan-rate target-mtu fragmentation-enabled? frame-signal]
                                  :or {fps DEFAULT_FPS
                                       port DEFAULT_PORT
                                       channel-id DEFAULT_CHANNEL_ID
                                       service-id 0
                                       output-config output-config/default-config
                                       target-mtu DEFAULT_TARGET_MTU
                                       fragmentation-enabled? false}}]
  {:id (or id (str target-host ":" port "/" channel-id))
   :target-host target-host
   :target-port port
   :frame-provider frame-provider
   :frame-signal frame-signal
   :fps fps
   :channel-id channel-id
   :service-id service-id
   :output-config output-config
   :scan-rate (or (idn-stream/clamp-scan-rate scan-rate) idn-stream/DEFAULT_SCAN_RATE)
   ;; Samples that fit one message, with and without config. A frame is cut to
   ;; this (stream/frame->packet), so its duration counts only what is sent
   :max-samples {true (idn-stream/max-points-per-message output-config true)
                 false (idn-stream/max-points-per-message output-config false)}
   :target-mtu target-mtu
   :fragmentation-enabled? fragmentation-enabled?
   :packet-buffer (idn-stream/create-packet-buffer)
   :running? (atom false)
   :socket (atom nil)
   ;; Resolved InetAddress for target-host, cached so packets don't do a DNS lookup each
   :target-address (atom nil)
   ;; The last address resolved, kept when :target-address is cleared for a re-lookup;
   ;; the close pair goes there. Nil if the host was never resolved (nothing was sent)
   :last-address (atom nil)
   :thread (atom nil)
   ;; System/nanoTime at start!; timestamps are microseconds since then
   :start-ns (atom 0)
   ;; System/nanoTime of the last frame sent with its config, nil if none yet
   :last-config-ns (atom nil)
   ;; Each engine needs its own sequence counter for proper packet ordering
   :sequence-counter (atom 0)
   :stats (atom initial-stats)})


;; Timestamp Management


(defn- current-timestamp-us
  "Microseconds since the engine started, on the monotonic clock: Stream §2.1
   requires timestamps that increase monotonically. Written mod 2^32."
  ^long [engine]
  (quot (- (System/nanoTime) (long @(:start-ns engine))) 1000))

(defn- should-resend-config?
  "True when no config has gone out yet, or the last one is
   CONFIG_RESEND_INTERVAL_MS old (Stream §2.2), on the monotonic clock."
  [engine]
  (let [last-ns @(:last-config-ns engine)]
    (or (nil? last-ns)
        (>= (- (System/nanoTime) (long last-ns))
            (* CONFIG_RESEND_INTERVAL_MS 1000000)))))


;; Streaming Loop


(defn- wire-duration-us
  "How long the DAC takes to draw frame, in µs. An empty frame lasts one frame
   interval. Otherwise it's the samples that go on the wire at the engine's
   scan rate: all of them when fragmentation splits the frame, else at most
   one message's worth (stream/frame->packet cuts the rest)."
  ^long [engine frame include-config?]
  (let [n (count frame)]
    (if (zero? n)
      (idn-stream/frame-duration-us (:fps engine))
      (idn-stream/scan-duration-us
       (if (:fragmentation-enabled? engine)
         n
         (min n (long (get (:max-samples engine) (boolean include-config?)))))
       (:scan-rate engine)))))

(defn- create-idn-stream-packets
  "Create IDN-Stream packet(s) for the given frame, with the channel
   configuration when include-config?, each saying it takes duration-us to draw.
   Returns a vector of packets (usually 1, more if fragmented).
   Uses the engine's output-config for bit depth settings.
   Passes service-id to target the correct physical output on multi-head DACs."
  [engine frame include-config? duration-us]
  (let [buf (:packet-buffer engine)
        channel-id (:channel-id engine)
        service-id (:service-id engine)
        timestamp-us (current-timestamp-us engine)
        output-cfg (:output-config engine)
        target-mtu (:target-mtu engine)
        fragmentation-enabled? (:fragmentation-enabled? engine)]
    
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
  "Get the next sequence number for this engine.
   Each engine maintains its own sequence counter to avoid interleaving issues."
  [engine]
  (swap! (:sequence-counter engine) #(bit-and (inc %) 0xFFFF)))

(defn- target-address!
  "Return the engine's resolved target InetAddress, resolving it on first use."
  ^InetAddress [engine]
  (or @(:target-address engine)
      (let [address (InetAddress/getByName (:target-host engine))]
        (reset! (:last-address engine) address)
        (reset! (:target-address engine) address))))

;; Debug logging for streaming (controlled by dev-config/idn-stream-logging?)
(def ^:private stream-log-counter (atom 0))
(def ^:const STREAM_LOG_INTERVAL 300) ;; Log every N frames (~5 seconds at 60fps)

(defn- send-frame!
  "Encode frame and send its packet(s), with the channel configuration when it
   is due (should-resend-config?). Returns [packet-count duration-us]."
  [engine frame frame-point-count]
  (let [{:keys [target-host target-port socket service-id channel-id]} engine
        include-config? (should-resend-config? engine)
        duration-us (wire-duration-us engine frame include-config?)
        ;; Get vector of packets (1 if no fragmentation, multiple if fragmented)
        idn-packets (create-idn-stream-packets engine frame include-config? duration-us)
        packet-count (count idn-packets)
        log-count (swap! stream-log-counter inc)]
    
    (when include-config?
      (reset! (:last-config-ns engine) (System/nanoTime)))
    
    ;; Send all packets (fragments) for this frame
    (doseq [idn-packet idn-packets]
      (let [seq-num (get-next-sequence! engine)
            hello-packet (hello/wrap-channel-message idn-packet {:sequence seq-num})]
        
        ;; Throttled debug logging - only when enabled via dev-config
        (when (and (dev-config/idn-stream-logging?)
                   (zero? (mod log-count STREAM_LOG_INTERVAL))
                   (= service-id (or service-id 0)))
          (log/debug (format "Streaming [%s:%d svc=%d ch=%d]: frame-points=%d, packets=%d, packet-size=%d, seq=%d"
                             target-host target-port (or service-id 0) (or channel-id 0)
                             frame-point-count packet-count (alength hello-packet) seq-num)))
        
        (hello/send-packet-to @socket hello-packet (target-address! engine) target-port)))
    [packet-count duration-us]))

(defn- update-stats
  "Stats after a frame of duration-us went out at now-ns in packet-count
   packets. The rates are smoothed over about 10 frames (an EMA); target-us is
   the send interval assumed before the first one is measured.
   - :send-fps: frames sent per second, on the monotonic clock;
   - :draw-fps: new frames the projector draws per second. It draws each frame
     for its duration, then the newest one it has (Stream §3.3), so this is
     10^6 / max(send interval, duration);
   - :scan-limited?: frames take more than 2 % longer to draw than the send
     interval, so the scan rate, not the send rate, sets :draw-fps;
   - :tick-to-send-us: how long after the show clock published a tick its
     frame went out (tick-to-send-us, nil for frames of no new tick)."
  ([stats now-ns duration-us packet-count target-us]
   (update-stats stats now-ns duration-us packet-count target-us nil))
  ([{:keys [frames-sent packets-sent fragments-sent last-send-ns interval-us frame-time-us] :as stats}
    now-ns duration-us packet-count target-us tick-to-send-us]
   (let [interval (if last-send-ns
                    (/ (- now-ns last-send-ns) 1000.0)
                    (double target-us))
         frame-time (max interval (double duration-us))
         smooth (fn [old x]
                  (if (pos? frames-sent)
                    (+ (* 0.9 old) (* 0.1 x))
                    x))
         interval' (smooth interval-us interval)
         frame-time' (smooth frame-time-us frame-time)]
     (cond-> (assoc stats
                    :frames-sent (inc frames-sent)
                    :packets-sent (+ packets-sent packet-count)
                    :fragments-sent (if (> packet-count 1)
                                      (+ fragments-sent packet-count)
                                      fragments-sent)
                    :last-send-ns now-ns
                    :frame-duration-us duration-us
                    :interval-us interval'
                    :frame-time-us frame-time'
                    :send-fps (/ 1e6 interval')
                    :draw-fps (/ 1e6 frame-time')
                    :scan-limited? (> frame-time' (* 1.02 interval')))
       tick-to-send-us (update :tick-to-send-us
                               #(if % (+ (* 0.9 (double %)) (* 0.1 (double tick-to-send-us)))
                                    (double tick-to-send-us)))))))

(defn- streaming-loop
  "Main streaming loop - runs in a separate thread.
   Continuously gets frames from the provider and sends them as IDN packets.
   Supports application-layer fragmentation for large frames.
   Records timing metrics for IDN streaming profiling.
   
   Paced by the show clock while it runs (:frame-signal): each new tick's
   frame goes out as soon as the tick is published, and after
   KEEPALIVE_INTERVALS without one the newest frame goes out again.
   Otherwise paced by deadline on System/nanoTime: each frame is due one
   interval after the last (timing/next-deadline), and the thread parks until
   then. In that mode, and after an error, no frame goes out less than half an
   interval after the one before, so a frame that overran can't be followed
   by a burst. Timestamps are always the send time.
   
   Each engine uses its own sequence counter to avoid interleaving issues
   when multiple engines stream to the same device on different services."
  [engine]
  (let [{:keys [target-host target-port frame-provider frame-signal fps
                running? stats output-config service-id channel-id
                fragmentation-enabled?]} engine
        interval-ns (timing/fps->interval-nanos fps)
        min-gap-ns (quot interval-ns 2)
        keepalive-ns (* KEEPALIVE_INTERVALS interval-ns)
        target-us (quot interval-ns 1000)
        error-key [:engine (:id engine)]
        keep-running? (fn [] @running?)
        clock-paced? (fn [] (boolean (and frame-signal ((:running? frame-signal)))))
        deadline (volatile! (System/nanoTime))
        last-sent (volatile! nil)
        ;; When the last frame was tried, sent or not: a keepalive is due
        ;; KEEPALIVE_INTERVALS after it, so failing sends wait for a tick too
        last-attempt (volatile! nil)
        ;; The newest clock tick this engine has tried to send
        seen (volatile! -1)
        await-gap! (fn []
                     (when-let [prev @last-sent]
                       (timing/park-until (+ (long prev) min-gap-ns) keep-running?)))]
    
    (log/info (format "Streaming loop started: %s:%d service %d channel %d @ %d FPS (%s, fragmentation=%s)"
                      target-host target-port (or service-id 0) (or channel-id 0) fps
                      (output-config/config-name output-config)
                      (if fragmentation-enabled? "enabled" "disabled")))
    
    (reset! (:last-config-ns engine) nil)
    (reset! (:sequence-counter engine) 0)  ;; Reset sequence on stream start
    
    (while @running?
      (let [paced? (clock-paced?)]
        (if paced?
          ((:await frame-signal) @seen
                                 (+ (long (or @last-attempt (System/nanoTime))) keepalive-ns)
                                 keep-running?)
          (timing/park-until @deadline keep-running?))
        (when @running?
          (let [tick (volatile! (when frame-signal ((:tick frame-signal))))]
            (try
              (let [produce-start (System/nanoTime)
                    frame (frame-provider)
                    {frame-tick :tick at-ns :at-ns} (meta frame)
                    _ (when frame-tick (vreset! tick frame-tick))
                    new-tick? (and frame-tick (> (long frame-tick) (long @seen)))
                    frame-point-count (count (or frame []))
                    frame (frame-rules/sanitize-frame (or frame (t/empty-frame)))
                    produced (System/nanoTime)
                    _ (when-not paced? (await-gap!))
                    send-start (System/nanoTime)
                    [packet-count duration-us] (send-frame! engine frame frame-point-count)
                    sent (System/nanoTime)]
                (vreset! last-sent sent)
                ;; IDN streaming time (µs): producing plus sending, without the wait between
                (profiler/record-idn-streaming! (/ (+ (- produced produce-start) (- sent send-start)) 1000.0))
                (swap! stats update-stats sent duration-us packet-count target-us
                       (when (and new-tick? at-ns) (/ (- sent (long at-ns)) 1000.0)))
                (output-errors/ok! error-key))
              
              (catch Throwable e
                ;; Re-resolve the host on the next frame in case its address changed
                (reset! (:target-address engine) nil)
                (output-errors/report! error-key e)
                ;; Void the DAC's frame buffer instead of leaving the last frame drawn
                (try
                  (await-gap!)
                  (send-frame! engine (t/empty-frame) 0)
                  (vreset! last-sent (System/nanoTime))
                  (catch Throwable e2
                    (output-errors/report! error-key e2)))))
            ;; Wait for a newer tick next, whether or not this one went out
            (when-let [t @tick]
              (vswap! seen max (long t)))
            (vreset! last-attempt (System/nanoTime))
            (vswap! deadline timing/next-deadline (System/nanoTime) interval-ns)))))))


;; Engine Control


(defn- thread-name
  "idn-streaming-<projector id>, so each engine's thread shows in a thread dump."
  [engine]
  (let [id (:id engine)]
    (str "idn-streaming-" (if (keyword? id) (name id) id))))

(defn start!
  "Start the streaming engine.
   Creates a UDP socket and starts the engine's own streaming thread.
   Returns the engine map, or throws on error (with nothing left open)."
  [engine]
  (when @(:running? engine)
    (throw (ex-info "Engine already running" {})))
  
  (let [socket (hello/create-udp-socket)]
    (reset! (:socket engine) socket)
    (reset! (:target-address engine) nil)
    (reset! (:last-address engine) nil)
    (reset! (:running? engine) true)
    (reset! (:start-ns engine) (System/nanoTime))
    (reset! (:last-config-ns engine) nil)
    (reset! (:stats engine) initial-stats)
    
    (try
      (let [thread (Thread. #(streaming-loop engine) (thread-name engine))]
        (.setDaemon thread true)
        (reset! (:thread engine) thread)
        (.start thread))
      (catch Throwable t
        (reset! (:running? engine) false)
        (reset! (:thread engine) nil)
        (reset! (:socket engine) nil)
        (.close socket)
        (throw t)))
    
    (log/info (format "Streaming engine started: %s:%d (%s)"
                      (:target-host engine)
                      (:target-port engine)
                      (output-config/config-name (:output-config engine))))
    engine))

(def ^:const STOP_JOIN_MS
  "How long stop-all! waits, in total, for the engines' threads to end."
  1000)

(defn- send-close-pair!
  "Send a channel close message, then an IDN-RT graceful close (0x44,
   IDN-Hello §6.2.2), both numbered with this engine's sequence counter.
   Skipped if the host was never resolved: nothing reached it, and a lookup
   now could block on DNS."
  [engine socket]
  (if-let [address (or @(:target-address engine) @(:last-address engine))]
    (try
      (let [timestamp-us (current-timestamp-us engine)
            close-packet (idn-stream/close-channel-packet (:channel-id engine) timestamp-us)
            hello-packet (hello/wrap-channel-message close-packet
                                                     {:sequence (get-next-sequence! engine)})
            rt-close-packet (hello/create-packet-header hello/CMD_RT_CLOSE 0
                                                        (get-next-sequence! engine))]
        (hello/send-packet-to socket hello-packet address (:target-port engine))
        (hello/send-packet-to socket rt-close-packet address (:target-port engine)))
      (catch Exception e
        (log/error "Error sending close packet:" (.getMessage e))))
    (log/debug "No close pair for" (:id engine) "- its host was never resolved")))

(defn- close!
  "Send the close pair and close the socket of an engine whose thread has ended
   (or was given up on)."
  [engine]
  (when-let [socket @(:socket engine)]
    (send-close-pair! engine socket)
    (.close ^DatagramSocket socket))
  (reset! (:socket engine) nil)
  (reset! (:thread engine) nil)
  (log/info "Streaming engine stopped:" (:id engine)))

(defn stop-all!
  "Stop several engines together. Clears every engine's running? flag first,
   so they all go dark within about a frame of each other, then joins each
   thread (sharing one STOP_JOIN_MS deadline) and sends its close pair. Engines
   that aren't running, or that another stop-all! already claimed, are
   skipped. Returns the engines this call stopped."
  [engines]
  (let [stopping (filterv #(compare-and-set! (:running? %) true false) engines)
        deadline (+ (System/nanoTime) (* STOP_JOIN_MS 1000000))]
    (doseq [engine stopping]
      (when-let [^Thread thread @(:thread engine)]
        (.join thread (max 1 (quot (- deadline (System/nanoTime)) 1000000))))
      (close! engine))
    stopping))

(defn stop!
  "Stop the streaming engine gracefully: (stop-all! [engine])."
  [engine]
  (stop-all! [engine])
  engine)

(defn running?
  "Check if the engine is currently running."
  [engine]
  @(:running? engine))

(defn get-stats
  "Current streaming statistics: the counts, :send-fps, :draw-fps,
   :scan-limited?, :frame-duration-us and :tick-to-send-us (see update-stats)."
  [engine]
  @(:stats engine))
