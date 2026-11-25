(ns laser-show.backend.streaming-engine
  "Streaming engine - streams one projector's frames through its output
   driver (plans/2026-11-19-output-drivers/01 §1). Each engine runs its own
   streaming thread, which decides when a frame goes out (the show clock's
   ticks or its own deadline, keepalive, a device-paced driver's :ready?),
   checks it (frame-rules/sanitize-frame), keeps the stats and blanks after
   an error. The driver only decides how the frame goes out: IDN is the
   built-in :idn driver (idn/driver.clj).

   The core calls into a driver only on the engine's streaming thread (:open
   included) and its output-close-<id> thread, never on the caller's, so a
   driver that blocks can't hold up the output lifecycle thread or Stop
   (01 §1.4 C-7). The one exception is :status, which output-runtime's
   status thread asks through poll-driver-status! and which only reads what
   the session already knows. Every call goes through driver/with-loader, so
   an add-on's namespaces are found on every thread.

   A driver's error may say more in its ex-data (01 §1.1): {:reopen? true}
   from :send-frame! or :ready? closes the session and opens a new one;
   {:busy? true :holder \"…\"} says another program has the device.

   Each engine keeps one observation for its rig card's thumbnail
   (thumbnail): the frame of its last driver call that returned normally,
   and when it returned. A return says only that the call returned, not what
   the device received or drew.

   A driver error is reported under [:engine id] (output-errors) for the
   run that made the call, and only while that run owns the engine
   (report-error!, report-ok!): what a stopped engine's call throws or
   returns late is never its replacement's status, and a new run starts
   with no error of the run before it."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.common.timing :as timing]
            [laser-show.idn.driver :as idn-driver]
            [laser-show.idn.frame-rules :as frame-rules]
            [laser-show.idn.stream :as idn-stream]
            [laser-show.idn.output-config :as output-config]
            [laser-show.animation.types :as t]
            [laser-show.output.driver :as driver]
            [laser-show.profiling.frame-profiler :as profiler])
  (:import [java.util.concurrent.atomic AtomicLong]
           [java.util.concurrent.locks LockSupport]))


;; Constants


;; Default streaming FPS - 60 FPS provides smooth animation while being
;; achievable on most networks and hardware
(def ^:const DEFAULT_FPS 60)

(def ^:const READY_POLL_US
  "How often a device-paced driver's :ready? is asked again (01 §1.3)."
  500)

(def ^:const REOPEN_MS
  "How long the streaming thread waits before it tries a driver's :open
   again after it threw (01 §1.1)."
  1000)

(def ^:const CLOSE_MS
  "How long stopping an engine waits for its driver's :close!, which runs on
   its own thread (01 §1.1). One that takes longer is logged and left behind."
  500)


;; Engine State


(def ^:private initial-stats
  "Stats of an engine that hasn't sent a frame yet (see update-stats).
   :last-attempt-ns is when the streaming thread last tried a frame, sent,
   skipped or failed, or opened the session; :opening-since-ns when the
   :open in progress began (both for output-runtime's \"not responding\");
   :frames-skipped counts the ticks a device-paced driver wasn't ready for
   (01 §1.3). :fit is the fit guard's record of the last frame sent (its
   :fit metadata, zones plan Z3), nil for a frame without one."
  {:frames-sent 0
   :packets-sent 0
   :fragments-sent 0
   :frames-skipped 0
   :last-send-ns nil
   :last-attempt-ns nil
   :opening-since-ns nil
   :frame-duration-us 0
   :interval-us 0.0
   :frame-time-us 0.0
   :send-fps 0.0
   :draw-fps 0.0
   :scan-limited? false
   :tick-to-send-us nil
   :fit nil})

(def ^:const KEEPALIVE_INTERVALS
  "While paced by the show clock, an engine that gets no tick for this many
   frame intervals (50 ms at 60 fps) sends the newest frame again. In discrete
   mode the DAC repeats the last frame anyway (Stream §3.3); this keeps the
   link alive (Hello §7.2) and the config at most 250 ms apart (Stream §2.2)."
  3)

(defn create-driver-engine
  "Create a streaming engine that sends through driver (a driver map, 01 §1).

   Parameters:
   - driver: the driver map, e.g. from driver/lookup
   - config: what the driver's :open gets (driver/projector-config)
   - frame-provider: Function that returns the current LaserFrame to send. A
     frame may carry {:tick :at-ns} metadata: the show clock's tick it shows
     and when that was published (see :frame-signal); and :fit, the fit
     guard's record, which the stats keep as it is
   - opts:
     - :id - Names the engine in output-errors and its threads (default the
       driver's id)
     - :fps - Target frames per second (default 60)
     - :scan-rate - Points per second the projector draws (default
       idn-stream/DEFAULT_SCAN_RATE, clamped by idn-stream/clamp-scan-rate). It
       sets each frame's duration, so the galvos never run faster than it
     - :frame-signal - The show clock's ticks to send on (show-time/frame-signal):
       {:tick :running? :await}. While (:running?) is true the engine sends each
       new tick's frame as soon as it is published, and the newest one again after
       KEEPALIVE_INTERVALS without a tick; otherwise, and without a signal, it
       paces itself at :fps

   Returns an engine map that can be started with start!. Its :session holds
   what the driver's :open returned while it runs."
  [driver config frame-provider & {:keys [id fps scan-rate frame-signal]
                                   :or {fps DEFAULT_FPS}}]
  {:id (or id (:id driver))
   :driver-id (:id driver)
   :driver driver
   :driver-config config
   :session (atom nil)
   :frame-provider frame-provider
   :frame-signal frame-signal
   :fps fps
   :scan-rate (or (idn-stream/clamp-scan-rate scan-rate) idn-stream/DEFAULT_SCAN_RATE)
   :running? (atom false)
   :thread (atom nil)
   :stats (atom initial-stats)
   ;; A driver result that isn't 01 §1.2's shape is logged once per start
   :result-warned? (atom false)
   ;; The engine's last error said the device is busy ({:busy? true}, G6)
   :busy? (atom false)
   ;; The driver's :status, sanitized (poll-driver-status!), and whether one
   ;; that threw was logged
   :driver-status (atom nil)
   :status-warned? (atom false)
   ;; The thumbnail's observation (thumbnail), nil while no run owns it
   :thumbnail (atom nil)})

(defn create-engine
  "Create a streaming engine for an IDN DAC (the :idn driver).
   
   Parameters:
   - target-host: IP address of the laser DAC
   - frame-provider: Function that returns the current LaserFrame to send (see
     create-driver-engine)
   - opts: Optional map with:
     - :id - Names the engine in output-errors (default \"host:port/channel\")
     - :fps - Target frames per second (default 60)
     - :port - Target UDP port (default 7255)
     - :channel-id - IDN channel ID for message header (default 0)
     - :service-id - IDN service ID for config header (default 0) - targets specific laser output
     - :output-config - OutputConfig for bit depth (default 8-bit color, 16-bit XY)
     - :scan-rate - Points per second the projector draws (see create-driver-engine)
     - :target-mtu - Target MTU for fragmentation (default 1400)
     - :fragmentation-enabled? - Enable application-layer fragmentation (default false).
       Off by default: frames up to the 0xFF00 message limit are sent in one message
       (Stream spec §6.2 prefers IP fragmentation), larger frames are truncated.
       The sequel-fragment layout has not been checked against a reference receiver.
     - :frame-signal - The show clock's ticks to send on (see create-driver-engine)
   
   NOTE: channel-id is for logical multiplexing (0-63), service-id targets physical outputs (0-255).
   For multi-head DACs, each output has a different service-id.
   
   Returns an engine map that can be started with start!"
  [target-host frame-provider & {:keys [id fps port channel-id service-id output-config
                                        scan-rate target-mtu fragmentation-enabled? frame-signal]
                                  :or {fps DEFAULT_FPS
                                       port idn-driver/DEFAULT_PORT
                                       channel-id idn-driver/DEFAULT_CHANNEL_ID
                                       service-id 0
                                       output-config output-config/default-config
                                       target-mtu idn-driver/DEFAULT_TARGET_MTU
                                       fragmentation-enabled? false}}]
  (create-driver-engine idn-driver/driver
                        {:host target-host
                         :port port
                         :channel-id channel-id
                         :service-id service-id
                         :output-config output-config
                         :target-mtu target-mtu
                         :fragmentation-enabled? fragmentation-enabled?}
                        frame-provider
                        :id (or id (str target-host ":" port "/" channel-id))
                        :fps fps
                        :scan-rate scan-rate
                        :frame-signal frame-signal))


;; The thumbnail's observation
;;
;; One slot per engine, {:run-token :session-token :knowledge :last-ok}: the
;; run start! began, the session its streaming thread opened (nil while it
;; opens or reopens), and what is known of that session's driver calls:
;; :returned once one returned normally, its frame in :last-ok {:frame
;; :at-ns :source}; :unknown before the first and from a call that threw
;; until the next one returns (:last-ok kept, for that session only). nil
;; once no run owns it. Every write names the run and session it is for and
;; changes the slot only while they still own it, in the swap itself, so a
;; late return or cleanup of an older run or session changes nothing.
;;
;; The run that owns the slot owns the engine's errors too (follow-up B10):
;; the run changes only under the slot's lock (claim-run!, retire-run!,
;; revoke-run!), and a run's errors are reported under it, in the same step
;; as the check that the run still owns the slot (report-error!,
;; report-ok!).


(defonce ^:private ^AtomicLong owner-tokens (AtomicLong.))

(defn- next-token [] (.incrementAndGet owner-tokens))

(defn- claim-run!
  "A fresh run token, with nothing known yet (start!). What an earlier run
   of this id left under [:engine id] goes: from now on the errors there
   are this run's."
  [engine]
  (let [run (next-token)]
    (locking (:thumbnail engine)
      (reset! (:thumbnail engine) {:run-token run :session-token nil :knowledge :unknown :last-ok nil})
      (output-errors/forget! [:engine (:id engine)]))
    run))

(defn- retire-run!
  "Clear the slot if run still owns it (a start that failed, the streaming
   thread's end)."
  [engine run]
  (locking (:thumbnail engine)
    (swap! (:thumbnail engine) #(when-not (= run (:run-token %)) %))))

(defn- revoke-run!
  "Stop: whatever run owns the slot owns it no more (stop-all!)."
  [engine]
  (locking (:thumbnail engine)
    (reset! (:thumbnail engine) nil)))

(defn- owns?
  "Does run still own engine (no stop, newer start or thread end since)?
   Under the slot's lock, for a decision that must not outlive it."
  [engine run]
  (= run (:run-token @(:thumbnail engine))))

(defn- open-observation!
  "Give the session run just opened a fresh token, with nothing known yet,
   if run still owns the slot. Returns the owner, [run session-token], its
   driver calls are recorded under."
  [engine run]
  (let [session (next-token)]
    (swap! (:thumbnail engine) #(if (= run (:run-token %))
                                   (assoc % :session-token session :knowledge :unknown :last-ok nil)
                                   %))
    [run session]))

(defn- record-outcome!
  "How a driver call made under owner ([run-token session-token]) ended, if
   owner still owns the slot: {:frame :at-ns :source}, a call that returned
   normally; :unknown, one that threw; :retired, the session ended (its
   success goes with it). Plain data in an atom no one watches; it never
   throws, so it can't change what the loop does next."
  [engine [run session] outcome]
  (try
    (swap! (:thumbnail engine)
           (fn [slot]
             (if (and slot (= run (:run-token slot)) (= session (:session-token slot)))
               (case outcome
                 :unknown (assoc slot :knowledge :unknown)
                 :retired (assoc slot :session-token nil :knowledge :unknown :last-ok nil)
                 (assoc slot :knowledge :returned :last-ok outcome))
               slot)))
    (catch Throwable _))
  nil)


;; Calling the driver


(defn- describe
  "The driver's :describe of the engine's config, for logs; its id without one."
  [{:keys [driver driver-config driver-id]}]
  (if-let [describe-fn (:describe driver)]
    (try
      (str (driver/with-loader driver #(describe-fn driver-config)))
      (catch Throwable _
        (str driver-id)))
    (str driver-id)))

(defn- default-duration-us
  "How long frame takes to draw when the driver doesn't say (01 §1.2): an
   empty frame one frame interval, otherwise its points at the scan rate."
  ^long [engine frame]
  (let [n (count frame)]
    (if (zero? n)
      (idn-stream/frame-duration-us (:fps engine))
      (idn-stream/scan-duration-us n (:scan-rate engine)))))

(defn- transfers? [x]
  (and (integer? x) (not (neg? x))))

(defn- duration? [x]
  (and (number? x) (Double/isFinite (double x)) (not (neg? x))))

(defn- frame-result
  "[transfers duration-us] from what the driver's :send-frame! returned for
   frame (01 §1.2): {:transfers n :duration-us d} as given; nil or a missing
   key gets 1 transfer and default-duration-us. A value of the wrong kind
   counts as missing and is logged once per engine."
  [engine frame result]
  (let [m? (map? result)
        transfers (when m? (:transfers result))
        duration-us (when m? (:duration-us result))]
    (when (and (or (and (some? result) (not m?))
                   (and (some? transfers) (not (transfers? transfers)))
                   (and (some? duration-us) (not (duration? duration-us))))
               (compare-and-set! (:result-warned? engine) false true))
      (log/warn "Output driver" (:driver-id engine) "of" (:id engine)
                "returned" (pr-str result) "from :send-frame!; expected nil or {:transfers n :duration-us d}"))
    [(if (transfers? transfers) (long transfers) 1)
     (if (duration? duration-us) (Math/round (double duration-us)) (default-duration-us engine frame))]))

(defn- busy-message
  "The status for a driver error that says the device is busy (G6): \"In
   use by <holder>\", or \"In use by another program\" without a holder."
  [e]
  (let [holder (:holder (ex-data e))]
    (if (and (string? holder) (not (str/blank? holder)))
      (str "In use by " (driver/cut-status-string (str/trim holder)))
      "In use by another program")))

(defn- report-error!
  "Report run's driver error under [:engine id] (output-errors/report!): a
   busy one ({:busy? true} in its ex-data) as busy-message, which the status
   shows with :busy? true. Only while run still owns the engine (owns?,
   with the report under the slot's lock): a call of a run that Stop ended
   or a newer start replaced reports nothing when it throws late, so the
   engine's next run never shows its error."
  [engine run e]
  (locking (:thumbnail engine)
    (when (owns? engine run)
      (let [busy? (boolean (:busy? (ex-data e)))]
        (reset! (:busy? engine) busy?)
        (output-errors/report! [:engine (:id engine)] (if busy? (busy-message e) e))))))

(defn- report-ok!
  "run's frame went out: its error under [:engine id], if any, is over
   (output-errors/ok!), and so is a busy device. Only while run still owns
   the engine, as for report-error!: a stopped run's late return clears
   nothing of the next run's."
  [engine run]
  (locking (:thumbnail engine)
    (when (owns? engine run)
      (output-errors/ok! [:engine (:id engine)])
      (when @(:busy? engine)
        (reset! (:busy? engine) false)))))

(defn- reopen?
  "Whether a driver's error asks for its session to be closed and opened
   again (G1)."
  [e]
  (boolean (:reopen? (ex-data e))))

(defn- send-frame!
  "Send frame through the engine's driver, for owner (the run and session
   streaming it) as source (:normal, or :recovery for the empty frame after
   an error). Returns [transfers duration-us] (frame-result). The moment the
   call returns normally, frame (this very vector) is owner's last success,
   stamped on timing/nanotime (record-outcome!); a call that throws records
   nothing."
  [engine frame session owner source]
  (let [driver (:driver engine)
        send! (:send-frame! driver)
        result (driver/with-loader driver #(send! session frame))]
    (record-outcome! engine owner {:frame frame :at-ns (timing/nanotime) :source source})
    (frame-result engine frame result)))

(defn- await-ready!
  "For a device-paced driver (01 §1.3): ask its :ready? every READY_POLL_US
   until it says true, or until one frame interval has passed since woke-ns.
   True if it is ready; false if not (that tick is skipped) or once the
   engine stops."
  [driver session woke-ns interval-ns keep-running?]
  (let [ready? (:ready? driver)
        give-up (+ (long woke-ns) (long interval-ns))
        poll-ns (* READY_POLL_US 1000)]
    (loop []
      (cond
        (driver/with-loader driver #(ready? session)) true
        (not (keep-running?)) false
        :else (let [wait (- give-up (System/nanoTime))]
                (if (pos? wait)
                  (do (LockSupport/parkNanos (min wait poll-ns))
                      (recur))
                  false))))))


;; Streaming Loop


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
   Continuously gets frames from the provider and sends them through the
   driver's session. Records timing metrics for streaming profiling.
   
   Paced by the show clock while it runs (:frame-signal): each new tick's
   frame goes out as soon as the tick is published, and after
   KEEPALIVE_INTERVALS without one the newest frame goes out again.
   Otherwise paced by deadline on System/nanoTime: each frame is due one
   interval after the last (timing/next-deadline), and the thread parks until
   then. In that mode, and after an error, no frame goes out less than half an
   interval after the one before, so a frame that overran can't be followed
   by a burst.

   A device-paced driver (:ready?) is asked after the wait, before the
   provider runs, so the newest frame goes out (await-ready!). A tick it isn't
   ready for within one interval is skipped (:frames-skipped).

   After an error (provider, :ready? or send) it is reported, the driver's
   :on-error runs and an empty frame goes out, unless the engine is stopping:
   then close! darkens it, and the session isn't used again (01 §1.4 C-3).
   An error whose ex-data has :reopen? (G1) is reported and ends the loop
   at once, with no :on-error and no empty frame: the session is dead.

   owner is the run and session it streams for: each send is recorded under
   it (send-frame!), and an error makes the observation unknown
   (record-outcome!) before anything that can block, until a send returns.
   Its errors, and their end once a frame goes out, are the run's
   (report-error!, report-ok!).

   Returns true when the driver asked for a reopen, else nil once the
   engine stops."
  [engine session owner]
  (let [{:keys [frame-provider frame-signal fps running? stats driver]} engine
        [run] owner
        on-error (:on-error driver)
        device-paced? (some? (:ready? driver))
        interval-ns (timing/fps->interval-nanos fps)
        min-gap-ns (quot interval-ns 2)
        keepalive-ns (* KEEPALIVE_INTERVALS interval-ns)
        target-us (quot interval-ns 1000)
        keep-running? (fn [] @running?)
        clock-paced? (fn [] (boolean (and frame-signal ((:running? frame-signal)))))
        deadline (volatile! (System/nanoTime))
        last-sent (volatile! nil)
        ;; When the last frame was tried, sent or not: a keepalive is due
        ;; KEEPALIVE_INTERVALS after it, so failing sends wait for a tick too
        last-attempt (volatile! nil)
        ;; The newest clock tick this engine has tried to send
        seen (volatile! -1)
        ;; The driver asked for its session to be closed and opened again
        reopen (volatile! false)
        await-gap! (fn []
                     (when-let [prev @last-sent]
                       (timing/park-until (+ (long prev) min-gap-ns) keep-running?)))]
    
    (log/info (format "Streaming loop started: %s @ %d FPS" (describe engine) fps))
    
    (while (and @running? (not @reopen))
      (let [paced? (clock-paced?)]
        (if paced?
          ((:await frame-signal) @seen
                                 (+ (long (or @last-attempt (System/nanoTime))) keepalive-ns)
                                 keep-running?)
          (timing/park-until @deadline keep-running?))
        (when @running?
          (let [tick (volatile! (when frame-signal ((:tick frame-signal))))
                woke (System/nanoTime)]
            (try
              (if (and device-paced?
                       (not (await-ready! driver session woke interval-ns keep-running?)))
                (swap! stats update :frames-skipped inc)
                (let [produce-start (System/nanoTime)
                      frame (frame-provider)
                      {frame-tick :tick at-ns :at-ns fit :fit} (meta frame)
                      _ (when frame-tick (vreset! tick frame-tick))
                      new-tick? (and frame-tick (> (long frame-tick) (long @seen)))
                      frame (frame-rules/sanitize-frame (or frame (t/empty-frame)))
                      produced (System/nanoTime)
                      _ (when-not paced? (await-gap!))
                      send-start (System/nanoTime)
                      [packet-count duration-us] (send-frame! engine frame session owner :normal)
                      sent (System/nanoTime)]
                  (vreset! last-sent sent)
                  ;; IDN streaming time (µs): producing plus sending, without the wait between
                  (profiler/record-idn-streaming! (/ (+ (- produced produce-start) (- sent send-start)) 1000.0))
                  (swap! stats #(assoc (update-stats % sent duration-us packet-count target-us
                                                     (when (and new-tick? at-ns) (/ (- sent (long at-ns)) 1000.0)))
                                       :fit fit))
                  (report-ok! engine run)))
              
              (catch Throwable e
                (record-outcome! engine owner :unknown)
                (report-error! engine run e)
                (cond
                  (not @running?) nil
                  ;; A dead session: nothing more is sent to it (run-engine closes it)
                  (reopen? e) (vreset! reopen true)
                  :else
                  (do
                    (when on-error
                      (try
                        (driver/with-loader driver #(on-error session e))
                        (catch Throwable e2
                          (report-error! engine run e2))))
                    ;; Void the device's frame buffer instead of leaving the last frame drawn
                    (try
                      (await-gap!)
                      (send-frame! engine (t/empty-frame) session owner :recovery)
                      (vreset! last-sent (System/nanoTime))
                      (catch Throwable e2
                        (report-error! engine run e2)
                        (when (and @running? (reopen? e2))
                          (vreset! reopen true))))))))
            ;; Wait for a newer tick next, whether or not this one went out
            (when-let [t @tick]
              (vswap! seen max (long t)))
            (let [now (System/nanoTime)]
              (vreset! last-attempt now)
              (swap! stats assoc :last-attempt-ns now))
            (vswap! deadline timing/next-deadline (System/nanoTime) interval-ns)))))
    (when @reopen
      (log/info "Output driver" (:driver-id engine) "asked to reopen" (:id engine))
      true)))


;; Engine Control


(defn- id-name [engine]
  (let [id (:id engine)]
    (if (keyword? id) (name id) (str id))))

(defn- thread-name
  "idn-streaming-<projector id> for IDN, output-streaming-<projector id> for
   any other driver, so each engine's thread shows in a thread dump."
  [engine]
  (str (if (= :idn (:driver-id engine)) "idn-streaming-" "output-streaming-")
       (id-name engine)))

(defn- close-session!
  "Call the driver's :close! on session on a daemon thread
   output-close-<id>, and wait for it up to CLOSE_MS. One that takes longer
   is logged and left behind, so a driver can't hold up Stop."
  [engine session]
  (let [{:keys [driver]} engine
        close (:close! driver)
        thread (Thread. ^Runnable (fn []
                                    (try
                                      (driver/with-loader driver #(close session))
                                      (catch Throwable e
                                        (log/error e "Output driver" (:driver-id engine)
                                                   "failed to close" (:id engine)))))
                        ^String (str "output-close-" (id-name engine)))]
    (.setDaemon thread true)
    (.start thread)
    (.join thread (long CLOSE_MS))
    (when (.isAlive thread)
      (log/warn "Output driver" (:driver-id engine) "is still closing" (:id engine)
                "after" CLOSE_MS "ms; leaving it behind"))))

(defn- open-session!
  "Open the driver's session (:open), on the streaming thread (C-7): an open
   that blocks holds up only this engine, never the lifecycle thread or Stop.
   One that throws (or returns nil, which would leave nothing to close) is
   reported under [:engine id], as run's (report-error!), and tried again
   REOPEN_MS later, while the engine runs. While an open is in progress the
   stats say since when (:opening-since-ns, output-runtime's \"not
   responding\"). The first try waits wait-ms (a reopen waits REOPEN_MS too).

   ctx's :cancelled? says true once the engine is stopping (G3), so a long
   :open can give up between its steps; what it throws then isn't reported.
   Returns the session, or nil once the engine stops; a session that opened
   after Stop is closed here, as close! found none to close."
  [engine run wait-ms]
  (let [{:keys [driver driver-config running? stats]} engine
        open (:open driver)
        keep-running? (fn [] @running?)
        ctx {:id (:id engine) :scan-rate (:scan-rate engine) :fps (:fps engine)
             :cancelled? (fn [] (not @running?))}]
    (timing/park-until (+ (System/nanoTime) (* (long wait-ms) 1000000)) keep-running?)
    (loop []
      (when @running?
        (swap! stats assoc :opening-since-ns (System/nanoTime))
        (let [session (try
                        (or (driver/with-loader driver #(open driver-config ctx))
                            (throw (ex-info (str "Output driver " (:driver-id engine) ": :open returned nil")
                                            {:driver-id (:driver-id engine)})))
                        (catch Throwable e
                          (when @running?
                            (report-error! engine run e))
                          ::failed))
              now (System/nanoTime)]
          (swap! stats assoc :opening-since-ns nil)
          (if (= ::failed session)
            (do (timing/park-until (+ now (* REOPEN_MS 1000000)) keep-running?)
                (recur))
            (do (reset! (:session engine) session)
                (swap! stats assoc :last-attempt-ns now)
                (if @running?
                  session
                  (when (compare-and-set! (:session engine) session nil)
                    (close-session! engine session)
                    nil)))))))))

(defn- drop-session!
  "The driver asked for session to be reopened (G1): it is owner's no more,
   so its last success goes (record-outcome!), before anything waits; then
   close it on its output-close-<id> thread within CLOSE_MS, as stop does,
   unless Stop already took it (C-4), and forget when a frame was last
   tried, so the wait to reopen isn't \"not responding\"."
  [engine session owner]
  (record-outcome! engine owner :retired)
  (when (compare-and-set! (:session engine) session nil)
    (close-session! engine session))
  (swap! (:stats engine) assoc :last-attempt-ns nil))

(defn- run-engine
  "The streaming thread of run (start!'s token): open the session, then
   stream until stopped. When the driver asks for a reopen (G1), the session
   is closed and, REOPEN_MS later, opened again as C-7's retry does. Each
   session gets its own token (open-observation!); when the thread ends, the
   run's observation goes, unless a newer run owns it."
  [engine run]
  (try
    (loop [wait-ms 0]
      (when-let [session (open-session! engine run wait-ms)]
        (let [owner (open-observation! engine run)]
          (when (streaming-loop engine session owner)
            (drop-session! engine session owner)
            (recur REOPEN_MS)))))
    (finally
      (retire-run! engine run))))

(defn start!
  "Start the streaming engine: start its own streaming thread, which opens
   the driver's session (open-session!) and streams. Nothing here calls into
   the driver (C-7), so it never blocks. Returns the engine map, or throws on
   error (with nothing left running). Each start is a new run of the
   engine's thumbnail observation, with nothing known yet, and of its
   errors, with none of the run before (claim-run!)."
  [engine]
  (when @(:running? engine)
    (throw (ex-info "Engine already running" {})))
  
  (reset! (:result-warned? engine) false)
  (reset! (:status-warned? engine) false)
  (reset! (:busy? engine) false)
  (reset! (:driver-status engine) nil)
  (reset! (:stats engine) initial-stats)
  (let [run (claim-run! engine)]
    (reset! (:running? engine) true)
    (try
      (let [thread (Thread. #(run-engine engine run) (thread-name engine))]
        (.setDaemon thread true)
        (reset! (:thread engine) thread)
        (.start thread))
      (catch Throwable t
        (reset! (:running? engine) false)
        (reset! (:thread engine) nil)
        (retire-run! engine run)
        (throw t))))
  
  (log/info (format "Streaming engine started: %s (driver %s)" (:id engine) (:driver-id engine)))
  engine)

(def ^:const STOP_JOIN_MS
  "How long stop-all! waits, in total, for the engines' threads to end."
  1000)

(defn- close!
  "Close the driver session of an engine whose thread has ended (or was given
   up on): at most once per session (01 §1.4 C-4), on its own thread
   (close-session!). Without a session (its :open never returned or always
   threw) there is nothing to close."
  [engine]
  (when-let [session (first (reset-vals! (:session engine) nil))]
    (close-session! engine session))
  (reset! (:thread engine) nil)
  (log/info "Streaming engine stopped:" (:id engine)))

(defn stop-all!
  "Stop several engines together. Clears every engine's running? flag first,
   so they all go dark within about a frame of each other, and ends each
   one's run (revoke-run!), so no call still in progress can record a
   result or report an error, or its end; then joins each thread (sharing
   one STOP_JOIN_MS deadline) and closes its driver session (for IDN, the
   close pair). Engines that aren't running, or that another stop-all!
   already claimed, are skipped. Returns the engines this call stopped."
  [engines]
  (let [stopping (filterv #(compare-and-set! (:running? %) true false) engines)
        deadline (+ (System/nanoTime) (* STOP_JOIN_MS 1000000))]
    (run! revoke-run! stopping)
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

(defn busy?
  "True while the engine's last error said the device is in use by another
   program ({:busy? true}, G6)."
  [engine]
  @(:busy? engine))

(defn poll-driver-status!
  "Ask the driver's :status (01 §1) about the engine's session, through
   driver/with-loader, and keep it sanitized (driver/sanitize-status) for
   driver-status. Only while the engine runs with a session; nil otherwise,
   and when :status throws (logged once per start) or returns nothing
   valid. Called by output-runtime's status thread, not the streaming one."
  [engine]
  (let [{:keys [driver running? driver-status]} engine
        status-fn (:status driver)
        session @(:session engine)]
    (reset! driver-status
            (when (and status-fn session @running?)
              (try
                (driver/sanitize-status (driver/with-loader driver #(status-fn session)))
                (catch Throwable e
                  (when (compare-and-set! (:status-warned? engine) false true)
                    (log/warn e "Output driver" (:driver-id engine) "of" (:id engine)
                              "threw from :status; ignoring it"))
                  nil))))))

(defn driver-status
  "The driver's last :status, sanitized: {:level :message :info}, or nil."
  [engine]
  @(:driver-status engine))

(defn get-stats
  "Current streaming statistics: the counts, :send-fps, :draw-fps,
   :scan-limited?, :frame-duration-us, :tick-to-send-us (see update-stats),
   :frames-skipped, :last-attempt-ns and :opening-since-ns, and :fit, the
   last frame's fit-guard record."
  [engine]
  @(:stats engine))

(defn thumbnail
  "The engine's observation for its rig card's thumbnail, pure data: nil
   while no run owns it (never started, stopped, its thread ended), else
   {:run-token :session-token :knowledge :last-ok}. :session-token is nil
   while a session opens or reopens; :knowledge is :returned once a driver
   call of this session returned normally, with :last-ok {:frame (the very
   vector the driver was passed) :at-ns (timing/nanotime when it returned)
   :source (:normal, or :recovery for the empty frame after an error)}, and
   :unknown before that and from an error until the next return. It says
   that a call returned, not what the device received or drew."
  [engine]
  @(:thumbnail engine))
