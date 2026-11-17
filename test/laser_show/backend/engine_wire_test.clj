(ns laser-show.backend.engine-wire-test
  "One streaming engine with a fixed frame provider, observed on the fake DAC."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-errors :as oe]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.idn.hello :as hello]
            [laser-show.idn.output-config :as oc]
            [laser-show.idn.stream :as stream]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))

(def ^:dynamic *dac* nil)

(def ^:private engines (atom []))

(defn- with-fake-dac [f]
  (let [fake (dac/start!)]
    (try
      (binding [*dac* fake] (f))
      (finally
        (run! se/stop! @engines)
        (reset! engines [])
        (dac/stop! fake)))))

(use-fixtures :each with-fake-dac)

(defn- start-engine!
  "Start an engine streaming provider to the fake DAC. It is stopped after the
   test even when the test fails."
  [provider & opts]
  (let [engine (apply se/create-engine "127.0.0.1" provider :port (:port *dac*) opts)]
    (swap! engines conj engine)
    (se/start! engine)))

(defn- port-of
  "The engine's source port, which identifies it on the fake DAC."
  [engine]
  (.getLocalPort ^java.net.DatagramSocket @(:socket engine)))

(defn- wait-for-frames! [n]
  (dac/wait-for! #(>= (count (dac/frames *dac*)) n) 3000))

(def ^:private frame
  [[-1.0 -1.0 0.0 0.0 0.0]
   [0.0 0.0 0.5 0.5 0.5]
   [0.5 0.5 0.999 0.999 0.999]
   [1.0 1.0 1.0 1.0 1.0]])

(defn- close-pair? [[close rt-close]]
  (and (= hello/CMD_RT_CHANNEL_MESSAGE (:cmd close))
       (= stream/CHUNK_TYPE_VOID (:chunk-type close))
       (:close? close)
       (= hello/CMD_RT_CLOSE (:cmd rt-close))
       (nil? (:payload rt-close))))


(deftest sequence-counts-up-including-close-test
  (let [engine (start-engine! (constantly frame))]
    (is (wait-for-frames! 5))
    (se/stop! engine)
    (is (dac/wait-for! #(close-pair? (take-last 2 (dac/messages *dac*))) 1000))
    (let [msgs (dac/messages *dac*)]
      (is (= (range 1 (inc (count msgs))) (map :seq msgs))
          "1, 2, 3, … with no gaps, close packets included")
      (is (= 1 (count (set (map :port msgs)))) "one sender"))))

(deftest first-message-carries-config-test
  (doseq [output-config [oc/standard-config oc/default-config oc/compact-config]]
    (testing (oc/config-name output-config)
      (let [engine (start-engine! (constantly frame) :channel-id 5 :service-id 5
                                  :output-config output-config)
            port (port-of engine)
            _ (is (dac/wait-for! #(seq (filter (comp #{port} :port) (dac/frames *dac*))) 3000))
            first-msg (first (filter (comp #{port} :port) (dac/messages *dac*)))
            [_ padded-tags] (stream/calculate-scwc (stream/get-tags-for-config output-config))]
        (se/stop! engine)
        (is (= {:seq 1 :channel-id 5 :has-config? true :service-id 5
                :service-mode stream/SERVICE_MODE_GRAPHIC_DISCRETE :close? false
                :tags padded-tags}
               (select-keys first-msg [:seq :channel-id :has-config? :service-id
                                       :service-mode :close? :tags])))))))

(deftest config-repeats-within-250ms-test
  (testing "No frame goes out more than 250 ms (engine clock) after the last config"
    (let [engine (start-engine! (constantly frame) :output-config oc/standard-config)]
      (is (dac/wait-for! #(<= 4 (count (filter :has-config? (dac/frames *dac*)))) 3000)
          "config repeats")
      (se/stop! engine)
      (let [fs (dac/frames *dac*)]
        (is (:has-config? (first fs)))
        (doseq [[last-config-ts msg] (rest (reductions (fn [[ts _] m]
                                                         [(if (:has-config? m) (:timestamp m) ts) m])
                                                       [(:timestamp (first fs)) (first fs)]
                                                       (rest fs)))]
          (is (< (- (:timestamp msg) last-config-ts) 250000)
              (str "seq " (:seq msg) " is " (- (:timestamp msg) last-config-ts)
                   " µs after the last config")))))))

(deftest samples-match-quantized-input-test
  (doseq [[output-config expected]
          {oc/standard-config [[-32767 -32767 0 0 0] [0 0 127 127 127]
                               [16383 16383 254 254 254] [32767 32767 255 255 255]]
           oc/default-config [[-32767 -32767 0 0 0] [0 0 32767 32767 32767]
                              [16383 16383 65469 65469 65469] [32767 32767 65535 65535 65535]]
           oc/compact-config [[-127 -127 0 0 0] [0 0 127 127 127]
                              [64 64 254 254 254] [127 127 255 255 255]]}]
    (testing (oc/config-name output-config)
      (let [engine (start-engine! (constantly frame) :output-config output-config)
            port (port-of engine)
            mine #(filterv (comp #{port} :port) (dac/frames *dac*))]
        (is (dac/wait-for! #(<= 3 (count (mine))) 3000))
        (se/stop! engine)
        (let [fs (mine)]
          (is (some :has-config? fs))
          (is (some (complement :has-config?) fs))
          (is (every? #(= expected (:samples %)) fs)))))))

(deftest idle-provider-sends-empty-frames-test
  (state/init-state! (domains/build-initial-state))
  (state/assoc-in-state! [:playback :playing?] false)
  (let [provider (:frame-provider (me/create-engine-for-projector
                                   :idle {:host "127.0.0.1" :zone-groups [:all]}))
        engine (start-engine! provider)]
    (is (wait-for-frames! 5))
    (se/stop! engine)
    (let [fs (dac/frames *dac*)]
      (is (seq fs))
      (is (every? #(= [] (:samples %)) fs) "every frame message has 0 samples"))))

(deftest stop-sends-close-pair-then-silence-test
  (let [engine (start-engine! (constantly frame) :channel-id 2 :service-id 2)]
    (is (wait-for-frames! 3))
    (se/stop! engine)
    (is (dac/wait-for! #(close-pair? (take-last 2 (dac/messages *dac*))) 1000))
    (let [[close] (take-last 2 (dac/messages *dac*))]
      (is (= 2 (:channel-id close))))
    (is (dac/quiet? *dac* 300) "nothing arrives after the close pair")
    (is (not (se/running? engine)))))


;; Phase 2: nothing unsafe reaches the wire

(def ^:private dark-then-lit
  "[d(-0.5,0) [0.5 0 1 1 1]] on the wire (16/8)"
  [[-16383 0 0 0 0] [16383 0 255 255 255]])

(defn- frames-after
  "Frame messages after the first n."
  [n]
  (vec (drop n (dac/frames *dac*))))

(deftest nan-point-is-filtered-test
  (start-engine! (constantly [[-0.5 0.0 0.0 0.0 0.0] [##NaN ##NaN 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]])
                 :output-config oc/standard-config)
  (is (wait-for-frames! 5))
  (is (every? #(= dark-then-lit (:samples %)) (dac/frames *dac*))
      "the NaN point is gone, the rest arrives"))

(deftest nil-point-is-filtered-test
  (let [calls (atom 0)
        good [[-0.5 0.0 0.0 0.0 0.0] [0.5 0.0 1.0 1.0 1.0]]
        bad [[-0.5 0.0 0.0 0.0 0.0] [nil 0.0 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]]
        engine (start-engine! (fn [] (if (<= (swap! calls inc) 5) good bad))
                              :output-config oc/standard-config)]
    (is (wait-for-frames! 15) "frames keep arriving after the bad ones start")
    (is (every? #(= dark-then-lit (:samples %)) (frames-after 5)))
    (is (se/running? engine))))

(deftest error-in-provider-keeps-the-engine-alive-test
  (let [calls (atom 0)
        failing? (atom true)
        engine (start-engine! (fn []
                                (if (and (> (swap! calls inc) 3) @failing?)
                                  (throw (AssertionError. "test: Assert failed"))
                                  [[-0.5 0.0 0.0 0.0 0.0] [0.5 0.0 1.0 1.0 1.0]]))
                              :id :wire-test :output-config oc/standard-config)]
    (is (wait-for-frames! 10))
    (is (.isAlive ^Thread @(:thread engine)) "the thread survives the Error")
    (is (every? #(= [] (:samples %)) (frames-after 3)) "each failed frame goes out empty")
    (is (< 3 (get-in (oe/snapshot) [[:engine :wire-test] :count] 0))
        "reported under the engine's key")
    (let [n (count (dac/frames *dac*))]
      (reset! failing? false)
      (is (dac/wait-for! #(= dark-then-lit (:samples (last (dac/frames *dac*)))) 2000)
          "frames come back when the provider recovers")
      (is (< n (count (dac/frames *dac*)))))
    (is (dac/wait-for! #(not (contains? (oe/snapshot) [:engine :wire-test])) 1000)
        "recovery clears the key")))

(deftest first-sample-is-dark-test
  (testing "a frame that starts lit gets a dark copy of its first point"
    (start-engine! (constantly [[0.5 0.5 1.0 1.0 1.0] [-0.5 0.5 1.0 1.0 1.0]])
                   :output-config oc/standard-config)
    (is (wait-for-frames! 3))
    (is (every? #(= [[16383 16383 0 0 0] [16383 16383 255 255 255] [-16383 16383 255 255 255]]
                    (:samples %))
                (dac/frames *dac*)))))

(deftest send-error-keeps-the-engine-alive-test
  (let [sends (atom 0)
        send-packet-to hello/send-packet-to]
    (with-redefs [hello/send-packet-to (fn [& args]
                                         (when (= 4 (swap! sends inc))
                                           (throw (AssertionError. "test: send failed")))
                                         (apply send-packet-to args))]
      (let [engine (start-engine! (constantly [[-0.5 0.0 0.0 0.0 0.0] [0.5 0.0 1.0 1.0 1.0]])
                                  :output-config oc/standard-config)]
        (is (wait-for-frames! 10))
        (is (.isAlive ^Thread @(:thread engine)) "the thread survives the failed send")
        (let [fs (dac/frames *dac*)]
          (is (= [] (:samples (nth fs 3))) "an empty frame replaces the one that failed")
          (is (every? #(= dark-then-lit (:samples %)) (concat (take 3 fs) (drop 4 fs)))))
        (se/stop! engine)))))
