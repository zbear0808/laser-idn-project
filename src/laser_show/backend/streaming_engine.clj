(ns laser-show.backend.streaming-engine
  "IDN streaming engine - manages continuous frame streaming to laser hardware.
   Implements IDN-Stream spec compliant packet generation and transmission.
   Runs in a separate thread and sends frames at a configurable rate.
   
   Supports configurable output formats:
   - 8-bit or 16-bit color (RGB)
   - 8-bit or 16-bit position (XY)
   
   Default is standard ISP-DB25 format (8-bit color, 16-bit XY)."
  (:require [clojure.tools.logging :as log]
            [laser-show.idn.stream :as idn-stream]
            [laser-show.idn.output-config :as output-config]
            [laser-show.animation.types :as t]
            [laser-show.idn.hello :as hello]
            [laser-show.common.util :as u])
  (:import [java.net DatagramSocket]))


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


;; Engine State


(defn create-engine
  "Create a new streaming engine configuration.
   
   Parameters:
   - target-host: IP address of the laser DAC
   - frame-provider: Function that returns the current LaserFrame to send
   - opts: Optional map with:
     - :fps - Target frames per second (default 60)
     - :port - Target UDP port (default 7255)
     - :channel-id - IDN channel ID (default 0)
     - :output-config - OutputConfig for bit depth (default 8-bit color, 16-bit XY)
   
   Returns an engine map that can be started with start!"
  [target-host frame-provider & {:keys [fps port channel-id output-config]
                                  :or {fps DEFAULT_FPS
                                       port DEFAULT_PORT
                                       channel-id DEFAULT_CHANNEL_ID
                                       output-config output-config/default-config}}]
  {:target-host target-host
   :target-port port
   :frame-provider frame-provider
   :fps fps
   :channel-id channel-id
   :output-config output-config
   :packet-buffer (idn-stream/create-packet-buffer)
   :running? (atom false)
   :socket (atom nil)
   :thread (atom nil)
   :start-time-us (atom 0)
   :last-config-time-ms (atom 0)
   :stats (atom {:frames-sent 0
                 :last-frame-time 0
                 :actual-fps 0.0})})


;; Timestamp Management


(defn- current-timestamp-us
  "Get current timestamp in microseconds relative to engine start."
  [engine]
  (let [now-us (* (System/currentTimeMillis) 1000)
        start-us @(:start-time-us engine)]
    (- now-us start-us)))

(defn- should-resend-config?
  "Check if channel configuration should be resent.
   Per spec Section 2.2: config should be present every 200ms for recovery."
  [engine]
  (let [now-ms (System/currentTimeMillis)
        last-config-ms @(:last-config-time-ms engine)]
    (>= (- now-ms last-config-ms) CONFIG_RESEND_INTERVAL_MS)))


;; Streaming Loop


(defn- calculate-actual-fps
  "Calculate actual FPS based on frame timing."
  [last-time current-time]
  (let [elapsed-ms (- current-time last-time)]
    (if (pos? elapsed-ms)
      (/ 1000.0 elapsed-ms)
      0.0)))

(defn- create-idn-stream-packet
  "Create an IDN-Stream packet for the given frame.
   Includes configuration periodically per spec requirements.
   Uses the engine's output-config for bit depth settings."
  [engine frame]
  (let [buf (:packet-buffer engine)
        channel-id (:channel-id engine)
        timestamp-us (current-timestamp-us engine)
        duration-us (idn-stream/frame-duration-us (:fps engine))
        output-cfg (:output-config engine)
        include-config? (should-resend-config? engine)]
    
    (when include-config?
      (reset! (:last-config-time-ms engine) (System/currentTimeMillis)))
    
    (if include-config?
      (idn-stream/frame->packet-with-config buf frame channel-id timestamp-us duration-us
                                            :output-config output-cfg)
      (idn-stream/frame->packet buf frame channel-id timestamp-us duration-us
                                :output-config output-cfg))))

(defn- streaming-loop
  "Main streaming loop - runs in a separate thread.
   Continuously gets frames from the provider and sends them as IDN packets."
  [engine]
  (let [{:keys [target-host target-port frame-provider fps
                running? socket stats output-config]} engine
        frame-interval-ms (/ 1000.0 fps)]
    
    (log/info (format "Streaming loop started: %s:%d @ %d FPS (%s)"
                      target-host target-port fps
                      (output-config/config-name output-config)))
    
    (reset! (:last-config-time-ms engine) 0)
    
    (while @running?
      (let [loop-start (System/currentTimeMillis)]
        (try
          (let [frame (frame-provider)
                frame (or frame (t/empty-frame))
                idn-packet (create-idn-stream-packet engine frame)
                hello-packet (hello/wrap-channel-message idn-packet)]
            
            (hello/send-packet @socket hello-packet target-host target-port)
            
            (let [now (System/currentTimeMillis)
                  last-time (:last-frame-time @stats)
                  actual-fps (calculate-actual-fps last-time now)]
              (swap! stats assoc
                     :frames-sent (inc (:frames-sent @stats))
                     :last-frame-time now
                     :actual-fps actual-fps)))
          
          (catch Exception e
            (log/error "Streaming error:" (u/exception->map e))))
        
        (let [elapsed (- (System/currentTimeMillis) loop-start)
              sleep-time (- frame-interval-ms elapsed)]
          (when (pos? sleep-time)
            (Thread/sleep (long sleep-time))))))))


;; Engine Control


(defn start!
  "Start the streaming engine.
   Creates a UDP socket and starts the streaming thread.
   Returns the engine map, or throws on error."
  [engine]
  (when @(:running? engine)
    (throw (ex-info "Engine already running" {})))
  
  (let [socket (hello/create-udp-socket)]
    (reset! (:socket engine) socket)
    (reset! (:running? engine) true)
    (reset! (:start-time-us engine) (* (System/currentTimeMillis) 1000))
    (reset! (:last-config-time-ms engine) 0)
    (reset! (:stats engine) {:frames-sent 0
                             :last-frame-time (System/currentTimeMillis)
                             :actual-fps 0.0})
    
    (let [thread (Thread. #(streaming-loop engine) "idn-streaming")]
      (.setDaemon thread true)
      (reset! (:thread engine) thread)
      (.start thread))
    
    (log/info (format "Streaming engine started: %s:%d (%s)"
                      (:target-host engine)
                      (:target-port engine)
                      (output-config/config-name (:output-config engine))))
    engine))

(defn stop!
  "Stop the streaming engine gracefully.
   Sends channel close packet, stops the thread, and closes the socket."
  [engine]
  (when @(:running? engine)
    (reset! (:running? engine) false)
    
    (when-let [thread @(:thread engine)]
      (.join thread 1000))
    
    (when-let [socket @(:socket engine)]
      (try
        (let [timestamp-us (current-timestamp-us engine)
              close-packet (idn-stream/close-channel-packet (:channel-id engine) timestamp-us)
              hello-packet (hello/wrap-channel-message close-packet)]
          (hello/send-packet socket hello-packet (:target-host engine) (:target-port engine)))
        (catch Exception e
          (log/error "Error sending close packet:" (.getMessage e))))
      
      (.close socket))
    
    (reset! (:socket engine) nil)
    (reset! (:thread engine) nil)
    
    (log/info "Streaming engine stopped")
    engine))

(defn running?
  "Check if the engine is currently running."
  [engine]
  @(:running? engine))

(defn get-stats
  "Get current streaming statistics."
  [engine]
  @(:stats engine))

(defn get-output-config
  "Get the current output configuration."
  [engine]
  (:output-config engine))

(defn set-output-config!
  "Update the output configuration.
   Note: This only takes effect on the next packet, not mid-stream.
   For a clean transition, stop and restart the engine."
  [engine new-config]
  (assoc engine :output-config new-config))
