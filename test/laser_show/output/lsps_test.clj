(ns laser-show.output.lsps-test
  "The built-in :lsps driver (plans/2026-11-19-output-drivers/01 §3),
   called directly through :open, :send-frame! and :close!, and received on
   loopback by laser-show.output.lsps-receiver."
  {:clj-kondo/config '{:lint-as {laser-show.output.lsps-receiver/with-receiver clojure.core/let
                                 laser-show.output.lsps-test/with-session clojure.core/let}}}
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.idn.stream :as stream]
            [laser-show.output.driver :as driver]
            [laser-show.output.lsps :as lsps]
            [laser-show.output.lsps-receiver :as rx :refer [with-receiver]]
            [laser-show.output.sample-format :as sf])
  (:import [java.net DatagramSocket]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

(def ^:private ctx {:id :projector-test :scan-rate 25000 :fps 60})

(defn- open!
  ([receiver] (open! receiver {} {}))
  ([receiver config] (open! receiver config {}))
  ([receiver config ctx-overrides]
   ((:open lsps/driver) (merge {:host "127.0.0.1" :port (:port receiver)} config)
                        (merge ctx ctx-overrides))))

(defn- send! [session frame] ((:send-frame! lsps/driver) session frame))

(defn- close! [session] ((:close! lsps/driver) session))

(defmacro ^:private with-session
  "(with-session [session (open! …)] body…): body with session bound,
   closed afterwards unless body closed it."
  [[name init] & body]
  `(let [~name ~init]
     (try
       ~@body
       (finally
         (when-not @(:closed? ~name) (close! ~name))))))

(defn- hex->ints [s]
  (mapv #(Integer/parseInt % 16) (re-seq #"[0-9A-F]{2}" s)))

(defn- frame-of
  "n points: a dark first point, then a ramp across x, lit red."
  [n]
  (vec (for [i (range n)]
         (if (zero? i)
           [-1.0 0.0 0.0 0.0 0.0]
           [(- (* 2.0 (/ i (max 1 (dec n)))) 1.0) 0.25 1.0 0.0 0.0]))))

(defn- wire-point
  "point as LSPS puts it on the wire (01 §3's layout)."
  [[x y r g b]]
  [(sf/scale-xy x :s16) (sf/scale-xy y :s16)
   (sf/scale-color r :u16) (sf/scale-color g :u16) (sf/scale-color b :u16)])


(deftest lsps-driver-is-registered-test
  (is (= [] (driver/validate lsps/driver)))
  (is (identical? lsps/driver (driver/lookup :lsps)) "requiring the registry registers it")
  (is (= {:id :lsps :name "LSPS point stream (UDP)" :short-name "LSPS" :transport :network :fields (:fields lsps/driver)
          :capabilities {:scan-rate {:min stream/MIN_SCAN_RATE :max stream/MAX_SCAN_RATE
                                     :default stream/DEFAULT_SCAN_RATE}
                         :max-points-per-frame 65535}
          :discover? false :addon nil}
         (get (driver/descriptors) :lsps)))
  (is (= [:lsps {:host "h" :port 7266 :max-packet-bytes 1400}]
         (driver/projector-config {:driver :lsps :driver-config {:host "h" :port 7266}}))
      "01 §5.2's row: the field defaults filled in")
  (is (= {:port 7266 :max-packet-bytes 1400} (driver/field-defaults (:fields lsps/driver))))
  (is (= "127.0.0.1:7266" ((:describe lsps/driver) {:host "127.0.0.1" :port 7266})))
  (is (= "bridge.local:7266" ((:describe lsps/driver) {:host "bridge.local"})) "the default port"))

(deftest three-point-frame-bytes-test
  (with-receiver [receiver (rx/start!)]
    (with-session [session (open! receiver)]
      (reset! (:next-sequence session) 41)
      (reset! (:next-frame session) 7)
      (is (= {:transfers 1 :duration-us 80}
             (send! session [[0 0 0 0 0] [0.5 -0.5 1 0 0] [1 1 0 0 1]])))
      (rx/wait-for-packets! receiver 1)
      (is (= [(hex->ints (str "4C 53 50 53 01 01 00 29 00 07 00 03 00 00 00 03 00 00 00 50"
                              "0000 0000 0000 0000 0000"
                              "4000 C000 FFFF 0000 0000"
                              "7FFF 7FFF 0000 0000 FFFF"))]
             (rx/raw receiver))
          "01 §3 example 1, byte for byte: one 50-byte packet"))))

(deftest large-frame-splits-test
  (with-receiver [receiver (rx/start!)]
    (with-session [session (open! receiver)]
      (let [frame (frame-of 600)]
        (is (= {:transfers 5 :duration-us 23960} (send! session frame)))
        (let [packets (rx/wait-for-packets! receiver 5)]
          (is (= [[0x00 0 138] [0x00 138 138] [0x00 276 138] [0x00 414 138] [0x01 552 48]]
                 (mapv (juxt :flags :index :count) packets))
              "01 §3 example 2")
          (is (every? #(= [600 23960 0] ((juxt :total :duration-us :frame) %)) packets))
          (is (= [0 1 2 3 4] (map :sequence packets)))
          (is (= [1400 1400 1400 1400 500] (map :size packets)))
          (is (= [] (rx/violations packets 1400)))
          (is (= [{:frame 0 :total 600 :duration-us 23960 :points (mapv wire-point frame)}]
                 (rx/frames packets))
              "L-4: the points as the encoder makes them, in order"))))))

(deftest empty-frame-is-one-dark-packet-test
  (with-receiver [receiver (rx/start!)]
    (with-session [session (open! receiver)]
      (is (= {:transfers 1 :duration-us 16666} (send! session [])))
      (let [[p :as packets] (rx/wait-for-packets! receiver 1)]
        (is (= 1 (count packets)))
        (is (= {:flags 0x01 :total 0 :index 0 :count 0 :duration-us 16666 :size 20 :points [] :frame 0}
               (select-keys p [:flags :total :index :count :duration-us :size :points :frame]))
            "01 §3 example 3: go dark, lasting a frame interval at 60 fps"))))
  (with-receiver [receiver (rx/start!)]
    (with-session [session (open! receiver {} {:fps 30})]
      (is (= {:transfers 1 :duration-us 33333} (send! session [])) "at 30 fps"))))

(deftest stop-sends-close-test
  (with-receiver [receiver (rx/start!)]
    (let [session (open! receiver)]
      (send! session (frame-of 3))
      (send! session [])
      (close! session)
      (let [packets (rx/wait-for-packets! receiver 3)
            p (last packets)]
        (is (= 3 (count packets)))
        (is (= {:flags 0x02 :close? true :last? false :sequence 2 :frame 0 :total 0 :index 0 :count 0
                :duration-us 0 :size 20}
               (select-keys p [:flags :close? :last? :sequence :frame :total :index :count :duration-us :size]))
            "01 §3 example 4, numbered after the frames")
        (is (= [] (rx/violations packets 1400))))
      (is (.isClosed ^DatagramSocket (:socket session)) "then the socket is closed")
      (is (thrown? Exception (send! session (frame-of 3))) "nothing is sent after close!")
      (close! session)
      (is (= 3 (count @(:received receiver))) "a second close! sends nothing"))))

(deftest a-new-session-starts-its-own-sequence-test
  (testing "after a CLOSE (a restart, a new port, an undo) the next session counts from 0: L-2 holds per stream"
    (with-receiver [receiver (rx/start!)]
      (let [first-session (open! receiver)]
        (send! first-session (frame-of 3))
        (close! first-session))
      (let [second-session (open! receiver)]
        (send! second-session (frame-of 3))
        (close! second-session))
      (let [packets (rx/wait-for-packets! receiver 4)]
        (is (= [[0 false] [1 true] [0 false] [1 true]] (mapv (juxt :sequence :close?) packets)))
        (is (= [] (rx/violations packets 1400)))
        (is (= [{:rule :sequence :sequence 0 :detail "after 0"}]
               (filterv #(= :sequence (:rule %)) (rx/violations [(first packets) (nth packets 2)] 1400)))
            "a sequence that starts again without a CLOSE still breaks L-2")))))

(deftest close-while-a-send-is-stuck-test
  (testing "C-4: a send stuck on the streaming thread holds the session's send lock;
            close! doesn't wait for it, closes the socket and still says CLOSE"
    (with-receiver [receiver (rx/start!)]
      (let [session (open! receiver)
            locked (CountDownLatch. 1)
            release (CountDownLatch. 1)
            stuck (future
                    (let [^ReentrantLock lock (:lock session)]
                      (.lock lock)
                      (try
                        (.countDown locked)
                        (.await release 5 TimeUnit/SECONDS)
                        (finally
                          (.unlock lock)))))]
        (try
          (.await locked 1 TimeUnit/SECONDS)
          (let [t0 (System/nanoTime)]
            (close! session)
            (is (< (/ (- (System/nanoTime) t0) 1e6) 400) "close! returns well within CLOSE_MS (500 ms)"))
          (is (.isClosed ^DatagramSocket (:socket session)))
          (let [[p] (rx/wait-for-packets! receiver 1)]
            (is (:close? p) "the CLOSE packet arrives anyway")
            (is (= 0 (:sequence p))))
          (finally
            (.countDown release)
            @stuck))))))

(deftest sequence-wraps-test
  (with-receiver [receiver (rx/start!)]
    (with-session [session (open! receiver)]
      (reset! (:next-sequence session) 0xFFFE)
      (reset! (:next-frame session) 0xFFFF)
      (is (= 3 (:transfers (send! session (frame-of 300)))))
      (send! session [])
      (close! session)
      (let [packets (rx/wait-for-packets! receiver 5)]
        (is (= [0xFFFE 0xFFFF 0 1 2] (map :sequence packets)) "L-2, close included")
        (is (= [0xFFFF 0xFFFF 0xFFFF 0] (map :frame (butlast packets))) "the frame number wraps too")
        (is (= [] (rx/violations packets 1400)))))))

(deftest duration-follows-scan-rate-test
  (doseq [[rate n expected] [[25000 600 23960] [30000 601 20000] [100000 2 10] [1000 3 2000] [25000 2 40]]]
    (with-receiver [receiver (rx/start!)]
      (with-session [session (open! receiver {} {:scan-rate rate})]
        (is (= expected (stream/scan-duration-us n rate)))
        (is (= expected (:duration-us (send! session (frame-of n)))) (str n " points at " rate))
        (is (every? #(= expected (:duration-us %))
                    (rx/wait-for-packets! receiver (:transfers (send! session (frame-of n))))))))))

(deftest frame-is-cut-at-65535-points-test
  (with-receiver [receiver (rx/start!)]
    (with-session [session (open! receiver {:max-packet-bytes 65507})]
      (let [frame (frame-of 70000)
            {:keys [transfers duration-us]} (send! session frame)
            packets (rx/wait-for-packets! receiver 11)]
        (is (= 11 transfers) "6,548 points a packet")
        (is (= (stream/scan-duration-us 65535 25000) duration-us) "the duration counts only what was sent")
        (is (= 11 (count packets)))
        (is (every? #(= 65535 (:total %)) packets))
        (is (= [] (rx/violations packets 65507)))
        (is (= (mapv wire-point (subvec frame 0 65535)) (:points (first (rx/frames packets)))))))))

(deftest packets-never-exceed-max-size-test
  (testing "L-1 to L-3 over 64 random frame sizes and packet sizes"
    (let [rng (java.util.Random. 20261120)]
      (with-receiver [receiver (rx/start!)]
        (dotimes [_ 64]
          (let [mpb (+ 120 (.nextInt rng (if (.nextBoolean rng) 200 9000)))
                n (let [k (.nextInt rng 2000)] (if (= 1 k) 2 k))
                per-packet (quot (- mpb 20) 10)
                before (count @(:received receiver))
                session (open! receiver {:max-packet-bytes mpb})
                {:keys [transfers]} (send! session (frame-of n))
                expected (max 1 (long (Math/ceil (/ n (double per-packet)))))]
            (close! session)
            (is (= expected transfers) (str n " points in " mpb "-byte packets"))
            (rx/wait-for! #(>= (count @(:received receiver)) (+ before transfers 1)) 2000)
            (let [packets (subvec (rx/packets receiver) before (+ before transfers 1))]
              (is (= [] (rx/violations packets mpb)) (str n " points in " mpb "-byte packets, then CLOSE"))
              (is (every? #(<= (:count %) per-packet) packets))
              (is (= n (reduce + (map :count packets))))
              (is (:close? (peek packets))))))))))

(deftest open-checks-its-config-test
  (with-receiver [receiver (rx/start!)]
    (doseq [[config message] [[{:host nil} #"needs a host"]
                              [{:host "  "} #"needs a host"]
                              [{:port 0} #"port must be 1–65535"]
                              [{:port "7266"} #"port must be 1–65535"]
                              [{:max-packet-bytes 119} #"packet size must be 120–65507"]
                              [{:max-packet-bytes 65508} #"packet size must be 120–65507"]
                              [{:host "no-such-host.invalid"} #"can't find host no-such-host.invalid"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo message (open! receiver config)) (pr-str config))))
  (testing "the defaults, and the host resolved once, at open"
    (let [session ((:open lsps/driver) {:host " 127.0.0.1 "} ctx)]
      (try
        (is (= 7266 (:port session)))
        (is (= 138 (:points-per-packet session)))
        (is (= "127.0.0.1" (.getHostAddress ^java.net.InetAddress (:address session))))
        (finally
          (close! session))))))
