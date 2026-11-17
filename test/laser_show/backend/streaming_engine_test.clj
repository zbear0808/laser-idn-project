(ns laser-show.backend.streaming-engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.animation.types :as t]
            [laser-show.idn.hello :as hello]
            [laser-show.idn.stream :as stream])
  (:import [java.net DatagramPacket DatagramSocket InetAddress SocketTimeoutException]))


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

(def ^:private next-deadline #'se/next-deadline)
(def ^:private update-stats #'se/update-stats)

(defn- ms->ns [ms] (long (* ms 1000000)))

(deftest next-deadline-examples-test
  (let [interval 16666666]
    (testing "on time: one interval after the deadline the frame was due at"
      (is (= interval (next-deadline 0 (ms->ns 2) interval))))
    (testing "less than one interval late: the deadline stays, so the next frame starts at once"
      (is (= interval (next-deadline 0 (ms->ns 25) interval)))
      (is (= interval (next-deadline 0 (* 2 interval) interval)) "exactly one interval late"))
    (testing "more than one interval late: the missed frames are skipped"
      (is (= (+ (ms->ns 50) interval) (next-deadline 0 (ms->ns 50) interval))))
    (testing "deadlines keep their own grid, whatever the clock's origin"
      (is (= (+ -5000 interval) (next-deadline -5000 (ms->ns 2) interval))))))

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
