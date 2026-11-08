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
