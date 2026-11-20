(ns laser-show.backend.streaming-engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.show-time :as show-time]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.animation.types :as t]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.idn.hello :as hello]
            [laser-show.idn.output-config :as oc]
            [laser-show.idn.stream :as stream])
  (:import [java.net DatagramPacket DatagramSocket InetAddress SocketTimeoutException]
           [java.util.concurrent.locks LockSupport]))


(defn- receive-all
  "Drain datagrams from socket until it is quiet for timeout-ms."
  [^DatagramSocket socket timeout-ms]
  (.setSoTimeout socket timeout-ms)
  (loop [acc []]
    (let [buf (byte-array 65536)
          pkt (DatagramPacket. buf (alength buf))
          got (try (.receive socket pkt)
                   (java.util.Arrays/copyOf buf (.getLength pkt))
                   (catch SocketTimeoutException _ nil))]
      (if got (recur (conj acc got)) acc))))

(defn- header [^bytes data]
  (let [h (hello/parse-packet-header data)]
    {:command (bit-and (:command h) 0xFF)
     :sequence (bit-and (:sequence h) 0xFFFF)
     :payload (:payload h)}))

(deftest close-uses-engine-sequence-test
  (testing "Close packets continue the engine's own sequence and end with IDN-RT close"
    (with-open [receiver (DatagramSocket. 0 (InetAddress/getLoopbackAddress))]
      ;; Advance the global counter so a regression to it would be visible
      (dotimes [_ 500] (hello/wrap-channel-message (byte-array 0)))
      (let [engine (se/create-engine "127.0.0.1" (fn [] (t/empty-frame))
                                     :port (.getLocalPort receiver)
                                     :channel-id 3)]
        (se/start! engine)
        (Thread/sleep 100)
        (se/stop! engine)
        (let [packets (mapv header (receive-all receiver 300))
              [data-msgs tail] (split-at (- (count packets) 2) packets)
              [close-msg rt-close] tail]
          (is (seq data-msgs) "Engine should have streamed some frames")
          (is (= (range 1 (inc (count packets))) (map :sequence packets))
              "Sequence numbers count up by one, close packets included")
          (is (= hello/CMD_RT_CHANNEL_MESSAGE (:command close-msg)))
          (let [info (stream/packet-info (:payload close-msg))]
            (is (= stream/CHUNK_TYPE_VOID (:chunk-type info)))
            (is (= 3 (:channel-id info)))
            (is (bit-test (get-in info [:config :flags]) 1) "Channel close flag set"))
          (is (= hello/CMD_RT_CLOSE (:command rt-close)))
          (is (nil? (:payload rt-close)) "Graceful close is sent without payload"))))))


(deftest target-address-resolved-once-test
  (testing "Engine resolves its host once and reuses the address for every packet"
    (with-open [receiver (DatagramSocket. 0 (InetAddress/getLoopbackAddress))]
      (let [engine (se/create-engine "127.0.0.1" (fn [] (t/empty-frame))
                                     :port (.getLocalPort receiver))
            lookups (atom 0)
            addresses (atom #{})
            orig-send hello/send-packet-to]
        (with-redefs [hello/send-packet (fn [& _] (swap! lookups inc))
                      hello/send-packet-to (fn [socket data address port]
                                             (swap! addresses conj (System/identityHashCode address))
                                             (orig-send socket data address port))]
          (se/start! engine)
          (Thread/sleep 100)
          (se/stop! engine))
        (is (zero? @lookups) "No per-packet host-name send path")
        (is (= 1 (count @addresses)) "Same InetAddress instance for every packet")
        (is (>= (count (receive-all receiver 300)) 3))))))


;; Behavioral Tests (not testing internal structure)


(deftest engine-creation-test
  (testing "Can create engine with frame provider"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "192.168.1.100" frame-provider)]
      (is (some? engine) "Engine should be created")
      (is (false? (se/running? engine)) "Engine should not be running initially"))))

(deftest engine-creation-with-null-host-test
  (testing "Can create engine with nil host (validation should happen at multi-engine level)"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine nil frame-provider)]
      (is (some? engine) "Engine should be created even with nil host")
      ;; The host validation happens at the multi-engine level
      ;; when deciding which engines to create and start
      (is (nil? (:target-host engine)) "Engine should have nil host"))))

(deftest engine-creation-with-blank-host-test
  (testing "Can create engine with blank host (validation should happen at multi-engine level)"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "" frame-provider)]
      (is (some? engine) "Engine should be created even with blank host")
      (is (= "" (:target-host engine)) "Engine should have blank host"))))

(deftest engine-stats-test
  (testing "Engine provides stats after creation"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "192.168.1.100" frame-provider)
          stats (se/get-stats engine)]
      (is (map? stats) "Stats should be a map")
      (is (contains? stats :frames-sent) "Stats should include frames-sent")
      (is (zero? (:frames-sent stats)) "Initial frames-sent should be zero"))))


;; Pacing and stats (plans/2026-11-16-output-runtime/05 §7)

(def ^:private update-stats #'se/update-stats)

(deftest stats-measure-the-send-rate-test
  (let [target-us 16666
        frames (fn [intervals-us]
                 (reduce (fn [[stats t] dt]
                           (let [t (+ t (* 1000 dt))]
                             [(update-stats stats t 400 1 target-us) t]))
                         [(se/get-stats (se/create-engine "127.0.0.1" (constantly []))) 0]
                         intervals-us))
        [first-frame] (frames [0])
        [at-60] (frames (repeat 30 16667))
        [at-48] (frames (concat (repeat 30 16667) (repeat 60 20833)))]
    (is (= 1 (:frames-sent first-frame)))
    (is (< (Math/abs (- 60.0 (:send-fps first-frame))) 0.1)
        "before any interval is measured, the target rate")
    (is (= 30 (:frames-sent at-60)))
    (is (< (Math/abs (- 60.0 (:send-fps at-60))) 0.1))
    (is (< (Math/abs (- 48.0 (:send-fps at-48))) 0.2) "follows a slower rate within about 10 frames")
    (is (= 90 (:packets-sent at-48)))))

(deftest stats-report-the-fps-the-projector-draws-test
  (let [run (fn [interval-us duration-us]
              (first (reduce (fn [[stats t] _]
                               (let [t (+ t (* 1000 interval-us))]
                                 [(update-stats stats t duration-us 1 16666) t]))
                             [(se/get-stats (se/create-engine "127.0.0.1" (constantly []))) 0]
                             (range 60))))
        near? (fn [expected actual] (< (Math/abs (- expected (double actual))) 0.2))]
    (testing "frames that take 40 ms to draw: 25 fps, set by the scan rate"
      (let [s (run 16667 40000)]
        (is (near? 25.0 (:draw-fps s)))
        (is (near? 60.0 (:send-fps s)))
        (is (true? (:scan-limited? s)))
        (is (= 40000 (:frame-duration-us s)))))
    (testing "frames drawn in 0.4 ms are repeated: the projector gets a new one at the send rate"
      (let [s (run 16667 400)]
        (is (near? 60.0 (:draw-fps s)))
        (is (false? (:scan-limited? s)))))
    (testing "an engine that only manages 48 fps"
      (let [s (run 20833 400)]
        (is (near? 48.0 (:draw-fps s)))
        (is (near? 48.0 (:send-fps s)))
        (is (false? (:scan-limited? s)))))
    (testing "a frame that just fits the interval isn't scan-limited"
      (let [s (run 16667 16680)]
        (is (near? 60.0 (:draw-fps s)))
        (is (false? (:scan-limited? s)))))))

(defn- eventually
  "Wait up to 2 s for (pred) to be true; its last value."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 5)
            (recur))))))

(deftest stats-keep-the-frames-fit-record-test
  (testing "zones plan Z3: the fit guard's record rides on the frame (:fit), and the stats keep the last one sent"
    (with-open [receiver (DatagramSocket. 0 (InetAddress/getLoopbackAddress))]
      (let [record {:points-in 3000 :points 1800 :guard-fps 13.9 :low? true}
            frame (atom (with-meta [[0.0 0.0 0.0 0.0 0.0] [0.5 0.0 1.0 1.0 1.0]] {:fit record}))
            engine (se/create-engine "127.0.0.1" (fn [] @frame) :port (.getLocalPort receiver))]
        (is (contains? (se/get-stats engine) :fit))
        (is (nil? (:fit (se/get-stats engine))) "none before a frame")
        (se/start! engine)
        (try
          (is (eventually #(= record (:fit (se/get-stats engine)))) "as it came: the engine doesn't look inside")
          (reset! frame nil)
          (is (eventually #(and (nil? (:fit (se/get-stats engine))) (pos? (:frames-sent (se/get-stats engine)))))
              "a frame without one (nothing playing) clears it")
          (reset! frame (with-meta [[0.0 0.0 1.0 1.0 1.0]] {:fit record}))
          (is (eventually #(= record (:fit (se/get-stats engine)))))
          (finally
            (se/stop! engine)))
        (testing "start! resets it with the other stats"
          (is (= record (:fit (se/get-stats engine))) "kept after a stop")
          (with-redefs-fn {#'se/streaming-loop (fn [_])}
            (fn []
              (se/start! engine)
              (is (nil? (:fit (se/get-stats engine))))
              (se/stop! engine))))))))


;; Paced by the show clock's ticks (plans/2026-11-17-show-clock/02 §4, §7)

(defn- tick-frame
  "A frame that shows the tick it was built from: x is (tick mod 1000) / 1000."
  [tick]
  (let [x (/ (double (mod tick 1000)) 1000.0)]
    [[x 0.0 0.0 0.0 0.0] [x 0.5 1.0 1.0 1.0]]))

(defn- tick-provider
  "The newest tick's frame with its {:tick :at-ns}, as multi-engine builds it;
   nil before the first tick."
  []
  (when-let [{:keys [tick at-ns]} (show-time/latest)]
    (with-meta (tick-frame tick) {:tick tick :at-ns at-ns})))

(defn- publish!
  "Publish a tick as the show clock would. Returns its number."
  []
  (:tick (show-time/publish! {:at-ns (System/nanoTime) :playing? true :cues []})))

(defn- park-until! [deadline-ns]
  (loop []
    (let [wait (- (long deadline-ns) (System/nanoTime))]
      (when (pos? wait)
        (LockSupport/parkNanos wait)
        (recur)))))

(defn- sent
  "[[i timestamp-us] …] for the lit frames port sent, in arrival order: i is
   the frame's tick as an index into published."
  [fake port published]
  (let [index (into {} (map-indexed (fn [i t] [(mod t 1000) i])) published)]
    (into [] (keep (fn [{:keys [samples timestamp]}]
                     (when-let [[_ [x]] (seq samples)]
                       [(index (Math/round (/ (double x) 32.767))) timestamp])))
          (filter (comp #{port} :port) (dac/frames fake)))))

(defn- with-clock-engine
  "Call (f fake engine port) with an engine paced by show-time/frame-signal,
   streaming tick-provider's frames to a fake DAC, while no clock thread runs
   but the signal says one does (the test publishes the ticks). Afterwards the
   stream is checked against the rules (fake-dac/check)."
  ([f] (with-clock-engine {} f))
  ([{:keys [running? skip] :or {running? true}} f]
   (let [fake (dac/start!)]
     (show-time/reset-runtime!)
     (show-time/set-clock-running! running?)
     (let [engine (se/create-engine "127.0.0.1" tick-provider :port (:port fake)
                                    :output-config oc/standard-config
                                    :frame-signal show-time/frame-signal)]
       (try
         (se/start! engine)
         (f fake engine (.getLocalPort ^DatagramSocket @(:socket engine)))
         (finally
           (se/stop! engine)
           (show-time/set-clock-running! false)
           (show-time/reset-runtime!)
           (let [found (dac/violations fake :skip skip)]
             (is (empty? found) (pr-str (take 3 found))))
           (dac/stop! fake)))))))

(defn- keepalive-repeats-only? [sends]
  (every? (fn [[[i ts] [j ts']]] (or (not= i j) (>= (- ts' ts) 45000)))
          (partition 2 1 sends)))

(deftest engine-sends-once-per-tick-test
  (with-clock-engine
    (fn [fake _ port]
      (let [t0 (System/nanoTime)
            published (vec (for [k (range 30)]
                             (do (park-until! (+ t0 (* k 16666667))) (publish!))))]
        (is (dac/wait-for! #(= 29 (first (peek (sent fake port published)))) 2000) "the last tick goes out")
        (let [sends (sent fake port published)]
          (is (apply <= (map first sends)) "ticks go out in order")
          (is (keepalive-repeats-only? sends)
              "a tick goes out again only as a keepalive, 50 ms after the last send"))))))

(deftest engine-sends-a-late-tick-in-order-test
  (testing "ticks at 0, 26.7 (10 ms late) and 33.3 ms: each goes out, in order, and the
            late one isn't preceded by a resend of the one before (A1, A2)"
    (with-clock-engine
      (fn [fake _ port]
        (let [t0 (System/nanoTime)
              a (publish!)
              _ (park-until! (+ t0 26700000))
              b (publish!)
              _ (dac/wait-for! #(some #{1} (map first (sent fake port [a b]))) 1000)
              _ (park-until! (+ t0 33300000))
              c (publish!)
              published [a b c]]
          (is (dac/wait-for! #(= 2 (first (peek (sent fake port published)))) 1000))
          (let [sends (sent fake port published)]
            (is (= [0 1 2] (dedupe (map first sends))))
            (is (keepalive-repeats-only? sends))))))))

(deftest new-tick-goes-out-without-the-minimum-gap-test
  (testing "a tick published just after the last send goes out at once, not half an interval
            later (A2). Under load a single try can be slow, so 3 of 5 must be"
    (with-clock-engine
      (fn [fake _ port]
        (let [sent-index? (fn [published i]
                            (let [deadline (+ (System/nanoTime) 1000000000)]
                              (loop []
                                (cond
                                  (some #{i} (map first (sent fake port published))) true
                                  (> (System/nanoTime) deadline) false
                                  :else (do (LockSupport/parkNanos 200000) (recur))))))
              gaps (vec (for [_ (range 5)]
                          (let [a (publish!)]
                            (sent-index? [a] 0)
                            (let [b (publish!)]
                              (sent-index? [a b] 1)
                              (let [sends (sent fake port [a b])
                                    ts-of (fn [i] (some (fn [[j ts]] (when (= i j) ts)) sends))]
                                (Thread/sleep 60)
                                (- (ts-of 1) (ts-of 0)))))))]
          (is (<= 3 (count (filter #(< % 8000) gaps))) (str "µs from one tick's frame to the next: " (vec gaps))))))))

(deftest engine-resends-when-no-tick-comes-test
  (with-clock-engine
    (fn [fake _ port]
      (let [a (publish!)]
        (Thread/sleep 300)
        (let [sends (sent fake port [a])]
          (is (<= 3 (count sends)) "the newest tick again as a keepalive")
          (is (keepalive-repeats-only? sends) "every 50 ms, not every frame interval"))))))

(deftest frame-signal-loses-no-wakeup-test
  (testing "a tick published while an engine checks for one, or just after, wakes it at once;
            a missed wake-up would wait the full 2 s"
    (show-time/set-clock-running! true)
    (try
      (let [{:keys [tick await]} show-time/frame-signal
            slow (count (filter #(> % 500000000)
                                (for [_ (range 300)]
                                  (let [seen (tick)
                                        ready (promise)
                                        waited (future
                                                 (deliver ready true)
                                                 (let [t0 (System/nanoTime)]
                                                   (await seen (+ t0 2000000000) (constantly true))
                                                   (- (System/nanoTime) t0)))]
                                    @ready
                                    (let [until (+ (System/nanoTime) (rand-int 20000))]
                                      (while (< (System/nanoTime) until)))
                                    (publish!)
                                    @waited))))]
        (is (zero? slow) (str slow " of 300 waits missed their tick")))
      (finally
        (show-time/set-clock-running! false)
        (show-time/reset-runtime!)))))

(deftest engine-records-tick-to-send-test
  (with-clock-engine
    (fn [fake engine port]
      (let [published (vec (repeatedly 5 #(do (Thread/sleep 17) (publish!))))]
        (is (dac/wait-for! #(= 4 (first (peek (sent fake port published)))) 2000))
        (let [{:keys [tick-to-send-us]} (se/get-stats engine)]
          (is (number? tick-to-send-us))
          (is (< 0.0 tick-to-send-us 50000.0) (str tick-to-send-us " µs from publish to send")))))))

(defn- frames-in-500-ms [fake port]
  (Thread/sleep 200)
  (let [since (System/nanoTime)]
    (Thread/sleep 500)
    (filterv (comp #{port} :port) (dac/frames fake :since-ns since))))

(deftest engine-without-a-signal-paces-itself-test
  (let [fake (dac/start!)
        engine (se/create-engine "127.0.0.1" (constantly [[-0.5 0.0 0.0 0.0 0.0] [0.5 0.0 1.0 1.0 1.0]])
                                 :port (:port fake) :output-config oc/standard-config)]
    (try
      (se/start! engine)
      (let [fs (frames-in-500-ms fake (.getLocalPort ^DatagramSocket @(:socket engine)))]
        (is (<= 15 (count fs) 32) (str (count fs) " frames in 500 ms at its own 60 fps")))
      (finally
        (se/stop! engine)
        (is (empty? (dac/violations fake)))
        (dac/stop! fake)))))

(deftest engine-paces-itself-while-no-clock-runs-test
  (testing "with a frame signal but no clock running (start-test!): its own 60 fps, and empty
            frames while there is no tick yet (A5)"
    (with-clock-engine {:running? false}
      (fn [fake _ port]
        (let [fs (frames-in-500-ms fake port)]
          (is (<= 15 (count fs) 32) (str (count fs) " frames in 500 ms"))
          (is (every? #(= [] (:samples %)) fs))
          (testing "and a tick published by hand is sent at that pace too"
            (let [a (publish!)]
              (is (dac/wait-for! #(seq (sent fake port [a])) 1000)))))))))

(deftest failing-sends-wait-for-the-next-tick-test
  (testing "once sends start failing (the network went away) and no tick comes, the engine
            tries once per keepalive interval instead of spinning on the error path"
    (let [failing? (atom false)
          attempts (atom 0)
          send-packet-to hello/send-packet-to]
      (with-redefs [hello/send-packet-to (fn [& args]
                                           (if @failing?
                                             (do (swap! attempts inc)
                                                 (throw (java.net.SocketException. "test: Network is unreachable")))
                                             (apply send-packet-to args)))]
        (with-clock-engine {:skip [:sequence]}
          (fn [fake engine port]
            (let [a (publish!)]
              (is (dac/wait-for! #(seq (sent fake port [a])) 1000) "a frame goes out first")
              (Thread/sleep 100)
              (reset! failing? true)
              (Thread/sleep 300)
              (reset! failing? false)
              (is (<= 2 @attempts 30) (str @attempts " sends tried in 300 ms"))
              (is (.isAlive ^Thread @(:thread engine))))))))
    (output-errors/clear!)))
