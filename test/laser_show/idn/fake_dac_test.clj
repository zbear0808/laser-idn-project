(ns laser-show.idn.fake-dac-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.idn.hello :as hello]
            [laser-show.idn.output-config :as oc]
            [laser-show.idn.stream :as stream])
  (:import [java.net DatagramPacket DatagramSocket InetAddress]))

(def ^:private frame
  [[-1.0 -1.0 0.0 0.0 0.0]
   [0.0 0.0 0.5 0.5 0.5]
   [0.5 0.5 0.999 0.999 0.999]
   [1.0 1.0 1.0 1.0 1.0]])

(def ^:private expected-samples
  {oc/standard-config [[-32767 -32767 0 0 0] [0 0 127 127 127]
                       [16383 16383 254 254 254] [32767 32767 255 255 255]]
   oc/default-config [[-32767 -32767 0 0 0] [0 0 32767 32767 32767]
                      [16383 16383 65469 65469 65469] [32767 32767 65535 65535 65535]]
   oc/compact-config [[-127 -127 0 0 0] [0 0 127 127 127]
                      [64 64 254 254 254] [127 127 255 255 255]]})

(defn- datagram [port sequence ^bytes channel-message]
  {:port port :ns sequence
   :bytes (hello/wrap-channel-message channel-message {:sequence sequence})})

(defn- encoded-stream
  "What an engine sends for one config: a frame with config, one without, the
   channel close and the RT close."
  [output-config]
  (let [buf (stream/create-packet-buffer)]
    [(datagram 1000 1 (stream/frame->packet-with-config buf frame 3 1000 16666
                                                        :service-id 3 :output-config output-config))
     (datagram 1000 2 (stream/frame->packet buf frame 3 2000 16666 :output-config output-config))
     (datagram 1000 3 (stream/close-channel-packet 3 3000))
     {:port 1000 :ns 4 :bytes (hello/create-packet-header hello/CMD_RT_CLOSE 0 4)}]))

(deftest decodes-what-stream-encodes-test
  (doseq [[output-config samples] expected-samples]
    (testing (oc/config-name output-config)
      (let [[with-config without-config close rt-close] (dac/decode-all (encoded-stream output-config))
            [_ padded-tags] (stream/calculate-scwc (stream/get-tags-for-config output-config))]
        (testing "frame with config"
          (is (= {:port 1000 :cmd hello/CMD_RT_CHANNEL_MESSAGE :seq 1
                  :channel-id 3 :has-config? true :service-id 3
                  :service-mode stream/SERVICE_MODE_GRAPHIC_DISCRETE
                  :chunk-type stream/CHUNK_TYPE_FRAME_SAMPLES :timestamp 1000
                  :duration-us 16666 :once? false :close? false :tags padded-tags
                  :samples samples}
                 (dissoc with-config :ns))))
        (testing "frame without config is decoded with the config sent before"
          (is (= {:seq 2 :has-config? false :timestamp 2000 :samples samples}
                 (select-keys without-config [:seq :has-config? :timestamp :samples])))
          (is (not (contains? without-config :service-id))))
        (testing "channel close"
          (is (= {:cmd hello/CMD_RT_CHANNEL_MESSAGE :seq 3 :channel-id 3
                  :chunk-type stream/CHUNK_TYPE_VOID :close? true}
                 (select-keys close [:cmd :seq :channel-id :chunk-type :close?]))))
        (testing "RT close"
          (is (= {:cmd hello/CMD_RT_CLOSE :seq 4 :payload nil}
                 (select-keys rt-close [:cmd :seq :payload])))))))

  (testing "a frame before any config has no samples"
    (let [buf (stream/create-packet-buffer)
          [msg] (dac/decode-all [(datagram 1000 1 (stream/frame->packet buf frame 0 0 16666))])]
      (is (= stream/CHUNK_TYPE_FRAME_SAMPLES (:chunk-type msg)))
      (is (nil? (:samples msg)))))

  (testing "timestamps decode unsigned"
    (let [buf (stream/create-packet-buffer)
          [msg] (dac/decode-all [(datagram 1000 1 (stream/frame->packet-with-config
                                                   buf frame 0 0xFFFFFFF0 16666))])]
      (is (= 0xFFFFFFF0 (:timestamp msg))))))

(deftest records-every-sender-on-loopback-test
  (let [fake (dac/start!)]
    (try
      (with-open [a (DatagramSocket.)
                  b (DatagramSocket.)]
        (let [address (InetAddress/getLoopbackAddress)
              send! (fn [^DatagramSocket socket {:keys [^bytes bytes]}]
                      (.send socket (DatagramPacket. bytes (alength bytes) address (int (:port fake)))))
              [first-frame second-frame] (encoded-stream oc/standard-config)]
          (send! a first-frame)
          (send! b first-frame)
          (send! a second-frame)
          (is (dac/wait-for! #(= 3 (count (dac/frames fake))) 2000))
          (is (= #{(.getLocalPort a) (.getLocalPort b)} (dac/senders fake)))
          (is (= [1 2] (map :seq (filter #(= (.getLocalPort a) (:port %)) (dac/frames fake)))))
          (is (every? #(= (expected-samples oc/standard-config) (:samples %)) (dac/frames fake)))
          (is (dac/quiet? fake 100))
          (let [since (System/nanoTime)]
            (send! b second-frame)
            (is (dac/wait-for! #(= 1 (count (dac/frames fake :since-ns since))) 2000))
            (is (= #{(.getLocalPort b)} (dac/senders fake :since-ns since))))))
      (finally
        (dac/stop! fake)))
    (is (not (.isAlive ^Thread (:thread fake))) "stop! ends the receiver thread")))
