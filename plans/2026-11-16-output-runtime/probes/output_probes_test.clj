;; EVIDENCE for plans/2026-11-16-output-runtime/.
;; Not part of the test suite and not on any classpath.
;;
;; Each deftest prints PROBE lines describing what the output code does TODAY
;; (master 70e049e). The plan docs quote them. When a phase fixes a behaviour,
;; turn the matching probe into a real test on the Phase 1 fake DAC, with an
;; assertion on the NEW behaviour.
;;
;; Since Phase 3 the probes' dispatch! goes through the wiring app/create-app
;; uses (the pipeline on a cljfx agent). Before, it called events/event-handler
;; on the calling thread, which was what an effect's callback did then.
;;
;; Since Phase 4 the engines live in the output runtime's registry, not in app
;; state, so streaming-flags reads the registry and the published status.
;;
;; Everything runs on loopback (127.0.0.1): no DAC, no network, no UI. A small
;; receiver here stands in for the DAC and decodes 16/8 samples.
;;
;; Some probes slow one function down with with-redefs (a sleep of 50-300 ms)
;; to make a race window reproducible. Each says which one and why; without the
;; sleep the same window is a few milliseconds wide.
;;
;; Run from the repo root (macOS Apple Silicon; use :win, :linux or :mac elsewhere).
;; The plan folder is the classpath root, so this namespace is probes.output-probes-test:
;;
;;   export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
;;   export PATH="$JAVA_HOME/bin:$PATH"
;;   P=plans/2026-11-16-output-runtime
;;   clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
;;       -M:mac-arm:test:probe -d "$P/probes" \
;;       -n probes.output-probes-test 2>&1 | grep PROBE

(ns probes.output-probes-test
  (:require [cljfx.api :as fx]
            [cljfx.event-handler :as event-handler]
            [clojure.test :refer [deftest]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.calibration]
            [laser-show.animation.types :as t]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.idn.hello :as hello]
            [laser-show.idn.output-config :as oc]
            [laser-show.idn.stream :as stream]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains])
  (:import [java.net DatagramPacket DatagramSocket InetAddress SocketException SocketTimeoutException]
           [java.nio ByteBuffer ByteOrder]))

(defn- say [& xs] (apply println "PROBE" xs))


;; A minimal fake DAC: records every datagram with its source port

(defn- fake-dac []
  (let [socket (doto (DatagramSocket. 0 (InetAddress/getLoopbackAddress))
                 (.setSoTimeout 50)
                 (.setReceiveBufferSize (* 4 1024 1024)))
        received (atom [])
        thread (doto (Thread.
                      (fn []
                        (while (not (.isClosed socket))
                          (let [buf (byte-array 65536)
                                pkt (DatagramPacket. buf (alength buf))]
                            (try
                              (.receive socket pkt)
                              (swap! received conj {:port (.getPort pkt)
                                                    :ms (System/currentTimeMillis)
                                                    :bytes (java.util.Arrays/copyOf buf (.getLength pkt))})
                              (catch SocketTimeoutException _)
                              (catch SocketException _)))))
                      "probe-fake-dac")
                 (.setDaemon true)
                 (.start))]
    {:socket socket :received received :thread thread :port (.getLocalPort socket)}))

(defn- close-dac! [{:keys [^DatagramSocket socket ^Thread thread]}]
  (.close socket)
  (.join thread 500))

(defn- chunk-of
  "Chunk duration (µs) and decoded samples [x y r g b] of a 16/8 frame message."
  [^bytes payload info]
  (let [chunk-at (+ 8 (if-let [c (:config info)] (+ 4 (* 4 (:scwc c))) 0))
        buf (doto (ByteBuffer/wrap payload) (.order ByteOrder/BIG_ENDIAN))
        n (quot (- (alength payload) chunk-at 4) 8)]
    {:duration-us (bit-and (.getInt buf (int chunk-at)) 0xFFFFFF)
     :samples (vec (for [i (range n)
                         :let [at (int (+ chunk-at 4 (* 8 i)))]]
                     [(.getShort buf at) (.getShort buf (int (+ at 2)))
                      (bit-and (.get buf (int (+ at 4))) 0xFF)
                      (bit-and (.get buf (int (+ at 5))) 0xFF)
                      (bit-and (.get buf (int (+ at 6))) 0xFF)]))}))

(defn- parse [{:keys [^bytes bytes] :as d}]
  (let [h (hello/parse-packet-header bytes)
        cmd (bit-and (:command h) 0xFF)
        payload (:payload h)
        info (when (and (= cmd hello/CMD_RT_CHANNEL_MESSAGE) payload)
               (stream/packet-info payload))]
    (cond-> (assoc d :cmd cmd :info info)
      (= stream/CHUNK_TYPE_FRAME_SAMPLES (:chunk-type info)) (merge (chunk-of payload info)))))

(defn- frames
  "Frame messages received after t-ms (all of them without t-ms)."
  ([dac] (frames dac 0))
  ([dac t-ms]
   (->> @(:received dac)
        (filter #(> (:ms %) t-ms))
        (map parse)
        (filter #(= stream/CHUNK_TYPE_FRAME_SAMPLES (get-in % [:info :chunk-type]))))))

(defn- senders-lately
  "Source ports (one per engine socket) that sent a frame in the last 200 ms."
  [dac]
  (set (map :port (frames dac (- (System/currentTimeMillis) 200)))))


;; Engines and app state

(defn- tracking-engines
  "Call (f started) with every engine start recorded in the atom started and
   delayed by delay-ms first (0 = as today). Stops every recorded engine after,
   so an orphan can't outlive the probe."
  [delay-ms f]
  (let [started (atom [])
        start! se/start!]
    (try
      (with-redefs [se/start! (fn [engine]
                                (when (pos? delay-ms) (Thread/sleep (long delay-ms)))
                                (swap! started conj engine)
                                (start! engine))]
        (f started))
      (finally
        (run! se/stop! @started)))))

(defn- projector [port service-id enabled?]
  {:name (str "Probe " service-id) :host "127.0.0.1" :port port :service-id service-id
   :enabled? enabled? :zone-groups [:all] :output-config {:color-bit-depth 8 :xy-bit-depth 16}})

(defn- init-with-projectors! [projectors]
  (state/init-state! (assoc (domains/build-initial-state) :projectors projectors)))

(defn- streaming-flags
  "Since Phase 4: the operator's intent, the engine count the published status
   reports, and the engines the output runtime's registry actually holds."
  []
  (let [s (state/get-raw-state)]
    {:running? (get-in s [:backend :streaming :running?])
     :status-engines (get-in s [:backend :streaming :status :engines])
     :engines-in-registry (count (output-runtime/registry))}))

(def ^:private agent-handler
  (-> events/handle-event-pipeline
      (fx/wrap-co-effects (assoc events/co-effects :fx/context (constantly nil)))
      (fx/wrap-effects events/effects)
      (event-handler/wrap-async {:error-handler (fn [_ e] (say "agent error" e))})))

(defn- dispatch!
  "Run an event through the wiring app/create-app uses (since Phase 3: the
   pipeline on a cljfx agent, so effects call back through its queue) and wait
   until the agent has handled it."
  [event]
  (agent-handler (assoc event :fx/sync true)))


;; p1 — what non-finite and nil coordinates become on the wire

(defn- encode-and-decode [frame]
  (let [payload (stream/frame->packet (stream/create-packet-buffer) frame 0 0 16666
                                      :output-config oc/standard-config)]
    (:samples (chunk-of payload (stream/packet-info payload)))))

(deftest p1-non-finite-coordinates
  (say "p1 [NaN NaN 1 1 1] encodes as" (first (encode-and-decode [[##NaN ##NaN 1.0 1.0 1.0]])))
  (say "p1 [+Inf -Inf 1 1 1] encodes as" (first (encode-and-decode [[##Inf ##-Inf 1.0 1.0 1.0]])))
  (say "p1 [0.5 0.5 NaN 1 1] encodes as" (first (encode-and-decode [[0.5 0.5 ##NaN 1.0 1.0]])))
  (say "p1 blanked? of [0 0 NaN NaN NaN]:" (t/blanked? [0.0 0.0 ##NaN ##NaN ##NaN]))
  (say "p1 [nil 0 1 1 1] throws"
       (try (encode-and-decode [[nil 0.0 1.0 1.0 1.0]]) "nothing"
            (catch Throwable e (.getName (class e))))))


;; p2 — a frame that fails to encode is not sent at all

(deftest p2-a-frame-that-fails-to-encode-sends-nothing
  (let [dac (fake-dac)
        calls (atom 0)
        good [(t/blanked-point -0.5 0.0) [0.5 0.0 1.0 1.0 1.0]]
        bad [[nil 0.0 1.0 1.0 1.0]]
        engine (se/create-engine "127.0.0.1" (fn [] (if (<= (swap! calls inc) 5) good bad))
                                 :port (:port dac) :output-config oc/standard-config)]
    (se/start! engine)
    (Thread/sleep 600)
    (say "p2 frames asked for" @calls "frame messages received" (count (frames dac))
         "engine running?" (se/running? engine))
    (se/stop! engine)
    (close-dac! dac)))


;; p3 — an Error (not an Exception) ends the engine thread, silently

(deftest p3-an-error-ends-the-engine-thread
  (let [dac (fake-dac)
        calls (atom 0)
        engine (se/create-engine "127.0.0.1"
                                 (fn [] (if (<= (swap! calls inc) 3)
                                          (t/empty-frame)
                                          (throw (AssertionError. "probe: Assert failed"))))
                                 :port (:port dac))]
    (se/start! engine)
    (Thread/sleep 400)
    (say "p3 thread alive?" (.isAlive ^Thread @(:thread engine))
         "engine running?" (se/running? engine) "frames received" (count (frames dac)))
    (se/stop! engine)
    (close-dac! dac)))


;; p4 — Stream started twice: two senders per projector, Stop stops one

(deftest p4-stream-started-twice
  (let [dac (fake-dac)]
    (init-with-projectors! {:probe-a (projector (:port dac) 1 true)})
    (tracking-engines 0
      (fn [started]
        (dispatch! {:event/type :idn/start-multi-streaming})
        (dispatch! {:event/type :idn/start-multi-streaming})
        (Thread/sleep 400)
        (say "p4 engines started" (count @started) "senders on channel 1" (count (senders-lately dac)))
        (dispatch! {:event/type :idn/stop-multi-streaming})
        (Thread/sleep 400)
        (say "p4 after Stop: senders still streaming" (count (senders-lately dac)) (streaming-flags))))
    (close-dac! dac)))


;; p5 — Stop while Start is still starting (start! slowed by 150 ms, as with
;; several projectors or a slow host lookup)

(deftest p5-stop-during-start
  (let [dac (fake-dac)]
    (init-with-projectors! {:probe-a (projector (:port dac) 1 true)})
    (tracking-engines 150
      (fn [_]
        (dispatch! {:event/type :idn/start-multi-streaming})
        (Thread/sleep 50)
        (dispatch! {:event/type :idn/stop-multi-streaming})
        (Thread/sleep 600)
        (say "p5 after Stop: senders streaming" (count (senders-lately dac)) (streaming-flags))))
    (close-dac! dac)))


;; p6 — two projectors enabled in quick succession while streaming (start!
;; slowed by 100 ms so the two refreshes overlap reliably)

(deftest p6-two-quick-enables-while-streaming
  (let [dac (fake-dac)]
    (init-with-projectors! {:probe-a (projector (:port dac) 1 true)
                            :probe-b (projector (:port dac) 2 false)
                            :probe-c (projector (:port dac) 3 false)})
    (tracking-engines 100
      (fn [started]
        (dispatch! {:event/type :idn/start-multi-streaming})
        (Thread/sleep 400)
        (dispatch! {:event/type :projectors/toggle-enabled :projector-id :probe-b})
        (dispatch! {:event/type :projectors/toggle-enabled :projector-id :probe-c})
        (Thread/sleep 800)
        (let [recent (frames dac (- (System/currentTimeMillis) 200))
              per-channel (->> (group-by #(get-in % [:info :channel-id]) recent)
                               (map (fn [[ch fs]] [ch (count (set (map :port fs)))]))
                               (into (sorted-map)))]
          (say "p6 engines started" (count @started) "senders per channel" per-channel
               (streaming-flags)))
        (dispatch! {:event/type :idn/stop-multi-streaming})
        (Thread/sleep 400)
        (say "p6 after Stop: senders still streaming" (count (senders-lately dac)))))
    (close-dac! dac)))


;; p7 — an event that was being handled while the engines started writes its
;; older state snapshot back (the handler is slowed to 300 ms so the engines'
;; write lands inside it; normally the window is the handler's run time)

(deftest p7-a-concurrent-event-drops-the-engine-handles
  (let [dac (fake-dac)
        handle timing/handle]
    (init-with-projectors! {:probe-a (projector (:port dac) 1 true)})
    (tracking-engines 50
      (fn [_]
        (with-redefs [timing/handle (fn [event] (Thread/sleep 300) (handle event))]
          (dispatch! {:event/type :idn/start-multi-streaming})
          ;; the next event, e.g. a BPM change from Link: snapshot now, write back at +300 ms
          (let [other (future (dispatch! {:event/type :timing/set-bpm :bpm 128.0}))]
            @other))
        (Thread/sleep 200)
        (say "p7 after both: senders streaming" (count (senders-lately dac)) (streaming-flags))
        (dispatch! {:event/type :idn/stop-multi-streaming})
        (Thread/sleep 400)
        (say "p7 after Stop: senders still streaming" (count (senders-lately dac)) (streaming-flags))))
    (close-dac! dac)))


;; p8 — calibration that fails is skipped: the projector gets the raw frame

(effects/register-effect!
 {:id :probe-calibration-fails-per-point :name "Probe: fails per point"
  :category :calibration :timing :static :parameters []
  :apply-transducer (fn [_ _ _ _] (map (fn [_] (throw (ex-info "probe: corner pin failed" {})))))})

(effects/register-effect!
 {:id :probe-calibration-fails-to-build :name "Probe: fails to build"
  :category :calibration :timing :static :parameters []
  :apply-transducer (fn [_ _ _ _] (throw (ex-info "probe: bad calibration params" {})))})

(deftest p8-failing-calibration-passes-the-frame-through
  (let [frame [[0.9 0.9 1.0 1.0 1.0] [-0.9 -0.9 1.0 1.0 1.0]]
        apply-projector-effects @#'me/apply-projector-effects]
    (doseq [effect-id [:probe-calibration-fails-per-point :probe-calibration-fails-to-build]]
      (init-with-projectors! {})
      (state/swap-state! assoc-in [:chains :projector-effects :probe-a :items]
                         [{:id (random-uuid) :effect-id effect-id :enabled? true :params {}}])
      (say "p8" effect-id "-> output equals the uncalibrated input?"
           (= frame (apply-projector-effects frame :probe-a 0 120.0 0 nil))))))


;; p9 — RGB curves with a raised low end (diode threshold compensation)

(deftest p9-rgb-curves-light-blanked-points
  (let [chain {:effects [{:id (random-uuid) :effect-id :rgb-curves :enabled? true
                          :params {:r-curve-points [[0.0 0.1] [1.0 1.0]]
                                   :g-curve-points [[0.0 0.0] [1.0 1.0]]
                                   :b-curve-points [[0.0 0.0] [1.0 1.0]]}}]}
        [out] (effects/apply-effect-chain [(t/blanked-point 0.5 0.5)] chain 0 120.0 nil nil)]
    (say "p9 blanked point after rgb-curves (red 0 -> 0.1):" out "blanked?" (t/blanked? out))))


;; p10 — frame duration and timestamp resolution

(deftest p10-duration-and-timestamps
  (let [dac (fake-dac)
        n (atom 10)
        engine (se/create-engine "127.0.0.1" (fn [] (vec (repeat @n [0.0 0.0 1.0 1.0 1.0])))
                                 :port (:port dac) :output-config oc/standard-config)]
    (se/start! engine)
    (Thread/sleep 200)
    (reset! n 3000)
    (Thread/sleep 200)
    (se/stop! engine)
    (let [fs (frames dac)
          by-size (group-by #(count (:samples %)) fs)]
      ;; Sizes as sent: since Phase 2 a lit first sample gets a dark copy in
      ;; front, so the 10- and 3000-point frames arrive with 11 and 3001 samples
      (doseq [k (sort (filter pos? (keys by-size)))
              :let [d (:duration-us (first (get by-size k)))]]
        (say "p10" k "samples: duration" d "µs =" (long (/ (* k 1e6) d)) "points/s"))
      (say "p10 timestamps mod 1000 µs:" (set (map #(mod (get-in % [:info :timestamp]) 1000) fs))))
    (close-dac! dac)))


;; p12 — pacing: frames per second and the gaps between them, after a 1 s warm-up

(deftest p12-pacing
  (let [dac (fake-dac)
        engine (se/create-engine "127.0.0.1" (fn [] [(t/blanked-point 0.0 0.0) [0.1 0.0 1.0 1.0 1.0]])
                                 :port (:port dac) :output-config oc/standard-config)]
    (se/start! engine)
    (Thread/sleep 1000)
    (let [t0 (System/currentTimeMillis)]
      (Thread/sleep 2000)
      (se/stop! engine)
      (let [arrivals (sort (map :ms (frames dac t0)))
            gaps (sort (map - (rest arrivals) arrivals))
            pct (fn [p] (nth gaps (min (dec (count gaps)) (long (* p (count gaps))))))]
        (say "p12 frames in 2 s:" (count arrivals) "=" (/ (count arrivals) 2.0) "fps (target 60)"
             "gap ms min/median/p95/max:" (first gaps) (pct 0.5) (pct 0.95) (last gaps))))
    (close-dac! dac)))


;; p13 — the same machine with deadline pacing (next += interval, parkNanos
;; until the deadline) instead of sleeping the remainder of each frame

(deftest p13-deadline-pacing-on-this-machine
  (let [sleep-remainder (fn []
                          (let [start (System/currentTimeMillis)
                                left (- (/ 1000.0 60) (- (System/currentTimeMillis) start))]
                            (when (pos? left) (Thread/sleep (long left)))))
        fps (fn [n step!]
              (let [t0 (System/nanoTime)]
                (dotimes [_ n] (step!))
                (/ n (/ (- (System/nanoTime) t0) 1e9))))
        interval (long (/ 1e9 60))
        next-deadline (atom (System/nanoTime))
        deadline-step (fn []
                        (let [deadline (swap! next-deadline + interval)]
                          (loop []
                            (let [wait (- deadline (System/nanoTime))]
                              (when (pos? wait)
                                (java.util.concurrent.locks.LockSupport/parkNanos wait)
                                (recur))))))]
    (say "p13 sleep-the-remainder (today):" (format "%.1f fps" (fps 120 sleep-remainder)))
    (reset! next-deadline (System/nanoTime))
    (say "p13 deadline + parkNanos:" (format "%.1f fps" (fps 120 deadline-step)))))


;; p11 — an Error in a preview tick ends the preview timer, and with it every
;; advance of beat timing (update-preview-frame! is made to throw on its 3rd call)

(deftest p11-an-error-ends-the-preview-timer
  (let [frame-service-ns (the-ns 'laser-show.services.frame-service)
        tick (ns-resolve frame-service-ns 'update-preview-frame!)
        calls (atom 0)]
    (with-redefs-fn {tick (fn []
                            (when (= 3 (swap! calls inc))
                              (throw (AssertionError. "probe: Assert failed"))))}
      (fn []
        ((ns-resolve frame-service-ns 'start-preview-updates!) 30)
        (Thread/sleep 500)
        (say "p11 preview ticks in 500 ms at 30 fps:" @calls "(15 expected)")
        ((ns-resolve frame-service-ns 'stop-preview-updates!))))))
