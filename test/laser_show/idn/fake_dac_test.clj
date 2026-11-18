(ns laser-show.idn.fake-dac-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.backend.streaming-engine :as se]
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


;; violations: every datagram checked against the stream rules (show clock 02 §4, A9)

(defn- rules [datagrams & opts]
  (set (map :rule (apply dac/check datagrams opts))))

(defn- with-config [ts frame & {:keys [close?] :or {close? false}}]
  (stream/frame->packet-with-config (stream/create-packet-buffer) frame 3 ts 16666
                                    :service-id 3 :output-config oc/standard-config :close? close?))

(defn- without-config [ts frame]
  (stream/frame->packet (stream/create-packet-buffer) frame 3 ts 16666 :output-config oc/standard-config))

(def ^:private dark-first
  [[-0.5 0.0 0.0 0.0 0.0] [0.5 0.0 1.0 1.0 1.0]])

(defn- stream-of
  "Datagrams from port 1000 numbered 1, 2, … from channel messages."
  [& messages]
  (vec (map-indexed (fn [i m] (datagram 1000 (inc i) m)) messages)))

(defn- set-octet [^bytes message i v]
  (doto (aclone message) (aset-byte i (unchecked-byte v))))

(deftest violations-find-each-broken-rule-test
  (testing "a stream that keeps every rule: config first, then repeated within 250 ms, then the close pair"
    (is (empty? (dac/check (conj (stream-of (with-config 0 dark-first)
                                            (without-config 16000 dark-first)
                                            (with-config 200000 dark-first)
                                            (without-config 400000 [])
                                            (stream/close-channel-packet 3 420000))
                                 {:port 1000 :ns 6 :bytes (hello/create-packet-header hello/CMD_RT_CLOSE 0 6)})))))
  (testing ":hello-header"
    (is (= #{:hello-header} (rules [{:port 1000 :ns 1 :bytes (byte-array 3)}])))
    (is (= #{:hello-header} (rules [{:port 1000 :ns 1 :bytes (hello/create-packet-header 0x08 0 1)}]))
        "a ping request isn't part of a stream"))
  (testing ":sequence"
    (is (= #{:sequence} (rules [(datagram 1000 1 (with-config 0 dark-first))
                                (datagram 1000 3 (without-config 16000 dark-first))])))
    (is (empty? (rules [(datagram 1000 0xFFFF (with-config 0 dark-first))
                        (datagram 1000 0 (without-config 16000 dark-first))]))
        "it wraps at 16 bits")
    (is (empty? (rules [(datagram 1000 1 (with-config 0 dark-first))
                        (datagram 1000 3 (without-config 16000 dark-first))]
                       :skip [:sequence]))
        ":skip leaves a rule out"))
  (testing ":message-size"
    (is (= #{:message-size} (rules (stream-of (set-octet (with-config 0 dark-first) 1 0x08)))) "a wrong total size")
    (is (= #{:message-size} (rules (stream-of (set-octet (with-config 0 dark-first) 2 0x43)))) "CNL bit 7 clear"))
  (testing ":timestamps"
    (is (= #{:timestamps} (rules (stream-of (with-config 2000 dark-first) (without-config 1000 dark-first)))))
    (is (empty? (rules (stream-of (with-config 0xFFFFFFF0 dark-first) (without-config 0x10 dark-first))))
        "they wrap at 32 bits"))
  (testing ":config"
    (is (= #{:config} (rules (stream-of (without-config 0 [])))) "the first message has none")
    (is (= #{:frame :config} (rules (stream-of (without-config 0 dark-first))))
        "(and with no configuration its samples can't be decoded)")
    (is (= #{:config} (rules (stream-of (with-config 0 dark-first) (without-config 300000 dark-first))))
        "300 ms after the last one")
    (is (= #{:config} (rules (stream-of (with-config 0 dark-first :close? true) (without-config 16000 []))))
        "after a close the channel needs it again"))
  (testing ":frame"
    (is (= #{:frame} (rules (stream-of (with-config 0 [[0.5 0.5 1.0 1.0 1.0] [-0.5 0.5 1.0 1.0 1.0]]))))
        "a lit first sample")
    (is (= #{:frame} (rules (stream-of (with-config 0 [[0.5 0.5 0.0 0.0 0.0]])))) "a single sample")
    (let [m (with-config 0 dark-first)
          n (alength m)
          cut (byte-array (dec n))]
      (System/arraycopy m 0 cut 0 (dec n))
      (aset-byte cut 1 (unchecked-byte (dec n)))
      (is (= #{:frame} (rules (stream-of cut))) "part of a sample")))
  (testing ":unused-bits"
    (is (= #{:unused-bits} (rules (stream-of (set-octet (with-config 0 dark-first) 9 0x11)))) "an SDM bit")
    (is (= #{:unused-bits} (rules (stream-of (set-octet (with-config 0 dark-first) 9 0x05)))) "a reserved CFL bit")
    (let [m (with-config 0 dark-first)
          [scwc] (stream/calculate-scwc (stream/get-tags-for-config oc/standard-config))
          chunk-flags (+ 8 4 (* 4 scwc))]
      (is (= #{:unused-bits} (rules (stream-of (set-octet m chunk-flags 0x10)))) "an SCM bit in the chunk flags"))
    (is (= #{:unused-bits} (rules [{:port 1000 :ns 1 :bytes (doto (hello/wrap-channel-message (with-config 0 dark-first)
                                                                                                  {:sequence 1})
                                                              (aset-byte 1 (unchecked-byte 0x20)))}]))
        "the upper half of the Hello flags"))
  (testing "a real engine's stream keeps every rule"
    (let [fake (dac/start!)
          engine (se/create-engine "127.0.0.1" (constantly dark-first) :port (:port fake)
                                   :output-config oc/standard-config)]
      (try
        (se/start! engine)
        (is (dac/wait-for! #(<= 20 (count (dac/frames fake))) 3000))
        (se/stop! engine)
        (is (dac/wait-for! #(= hello/CMD_RT_CLOSE (:cmd (last (dac/messages fake)))) 1000))
        (is (empty? (dac/violations fake)))
        (finally
          (se/stop! engine)
          (dac/stop! fake))))))
