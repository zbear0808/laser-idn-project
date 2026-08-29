(ns laser-show.idn.fragmentation-test
  "Tests for IDN-Stream frame fragmentation support."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.idn.fragmentation :as sut]
            [laser-show.idn.output-config :as output-config]
            [laser-show.idn.stream :as idn-stream]))


;; Test Fixtures


(def standard-config output-config/standard-config)  ; 16-bit XY, 8-bit RGB (8 bytes/pt with NOP padding)
(def default-config output-config/default-config)    ; 16-bit XY, 16-bit RGB (10 bytes/pt)
(def compact-config output-config/compact-config)    ; 8-bit XY, 8-bit RGB (6 bytes/pt with NOP padding)

(defn make-test-frame
  "Create a test frame with n points."
  [n]
  (vec (repeat n [0.0 0.0 1.0 1.0 1.0])))


;; calculate-max-points-per-fragment Tests


(deftest calculate-max-points-per-fragment-test
  (testing "Standard config (16-bit XY, 8-bit RGB = 8 bytes/pt with NOP padding)"
    (let [max-with-config (sut/calculate-max-points-per-fragment 
                           standard-config
                           :target-mtu 1400
                           :include-config? true)
          max-without-config (sut/calculate-max-points-per-fragment 
                              standard-config
                              :target-mtu 1400
                              :include-config? false)]
      ;; With config should have lower capacity due to header overhead
      (is (< max-with-config max-without-config))
      ;; Should be reasonable values (around 180-198 for standard config)
      (is (> max-with-config 150))
      (is (< max-with-config 250))))
  
  (testing "Default config (16-bit XY, 16-bit RGB = 10 bytes/pt)"
    (let [max-pts (sut/calculate-max-points-per-fragment 
                   default-config
                   :target-mtu 1400
                   :include-config? false)]
      ;; Should be lower than standard due to larger point size
      (is (> max-pts 100))
      (is (< max-pts 180))))
  
  (testing "Compact config (8-bit XY, 8-bit RGB = 6 bytes/pt with NOP padding)"
    (let [max-pts (sut/calculate-max-points-per-fragment
                   compact-config
                   :target-mtu 1400
                   :include-config? false)]
      ;; Should be higher due to smaller point size
      (is (> max-pts 200))))
  
  (testing "Minimum points enforced"
    (let [max-pts (sut/calculate-max-points-per-fragment 
                   standard-config
                   :target-mtu 50  ; Very small MTU
                   :include-config? true)]
      (is (>= max-pts sut/MIN_POINTS_PER_FRAGMENT)))))


;; needs-fragmentation? Tests


(deftest needs-fragmentation-test
  (testing "Small frame doesn't need fragmentation"
    (let [frame (make-test-frame 100)]
      (is (not (sut/needs-fragmentation? frame standard-config)))))
  
  (testing "Large frame needs fragmentation"
    (let [frame (make-test-frame 500)]
      (is (sut/needs-fragmentation? frame standard-config))))
  
  (testing "Boundary case - exactly at max doesn't need fragmentation"
    (let [max-pts (sut/calculate-max-points-per-fragment 
                   standard-config
                   :target-mtu 1400
                   :include-config? true)
          frame (make-test-frame max-pts)]
      (is (not (sut/needs-fragmentation? frame standard-config)))))
  
  (testing "Boundary case - one over max needs fragmentation"
    (let [max-pts (sut/calculate-max-points-per-fragment 
                   standard-config
                   :target-mtu 1400
                   :include-config? true)
          frame (make-test-frame (inc max-pts))]
      (is (sut/needs-fragmentation? frame standard-config)))))


;; fragment-count Tests


(deftest fragment-count-test
  (testing "Single fragment for small frames"
    (is (= 1 (sut/fragment-count 100 standard-config))))
  
  (testing "Multiple fragments for large frames"
    (is (> (sut/fragment-count 500 standard-config) 1)))
  
  (testing "Fragment count increases with point count"
    (let [count-500 (sut/fragment-count 500 standard-config)
          count-1000 (sut/fragment-count 1000 standard-config)]
      (is (> count-1000 count-500))))
  
  (testing "Different configs affect fragment count"
    ;; Default config has larger points (10 bytes vs 8 bytes)
    ;; so it should need more fragments
    (let [count-standard (sut/fragment-count 1000 standard-config)
          count-default (sut/fragment-count 1000 default-config)]
      (is (> count-default count-standard)))))


;; split-frame-into-fragments Tests


(deftest split-frame-into-fragments-test
  (testing "Small frame returns single fragment"
    (let [frame (make-test-frame 100)
          fragments (sut/split-frame-into-fragments frame standard-config)]
      (is (= 1 (count fragments)))
      (is (= frame (first fragments)))))
  
  (testing "Frame split preserves all points"
    (let [frame (make-test-frame 500)
          fragments (sut/split-frame-into-fragments frame standard-config)
          total-points (reduce + (map count fragments))]
      (is (= (count frame) total-points))))
  
  (testing "First fragment is smaller (accounts for config header)"
    (let [frame (make-test-frame 500)
          fragments (sut/split-frame-into-fragments frame standard-config)]
      (when (> (count fragments) 1)
        ;; First fragment has config overhead, sequel fragments don't
        (is (< (count (first fragments))
               (count (second fragments)))))))
  
  (testing "All fragments are within size limits"
    (let [frame (make-test-frame 1000)
          fragments (sut/split-frame-into-fragments frame standard-config)
          max-first (sut/calculate-max-points-per-fragment 
                     standard-config :include-config? true)
          max-sequel (sut/calculate-max-points-per-fragment 
                      standard-config :include-config? false)]
      (is (<= (count (first fragments)) max-first))
      (doseq [frag (rest fragments)]
        (is (<= (count frag) max-sequel)))))
  
  (testing "Large frame splits into multiple fragments"
    (let [frame (make-test-frame 1000)
          fragments (sut/split-frame-into-fragments frame standard-config)]
      (is (> (count fragments) 2)))))


;; frame->fragmented-packets Tests


(deftest frame->fragmented-packets-test
  (let [buf (idn-stream/create-packet-buffer)
        channel-id 0
        timestamp-us 1000000
        duration-us 16666]
    
    (testing "Small frame produces single packet"
      (let [frame (make-test-frame 100)
            packets (sut/frame->fragmented-packets 
                     buf frame channel-id timestamp-us duration-us
                     :output-config standard-config)]
        (is (= 1 (count packets)))
        (is (instance? (Class/forName "[B") (first packets)))))
    
    (testing "Large frame produces multiple packets"
      (let [frame (make-test-frame 500)
            packets (sut/frame->fragmented-packets 
                     buf frame channel-id timestamp-us duration-us
                     :output-config standard-config)]
        (is (> (count packets) 1))
        (doseq [pkt packets]
          (is (instance? (Class/forName "[B") pkt)))))
    
    (testing "First packet has correct chunk type (0x03 for first fragment)"
      (let [frame (make-test-frame 500)
            packets (sut/frame->fragmented-packets 
                     buf frame channel-id timestamp-us duration-us
                     :output-config standard-config
                     :include-config? true)
            first-packet (first packets)
            info (idn-stream/parse-channel-message-header first-packet)]
        (is (= idn-stream/CHUNK_TYPE_FRAME_SAMPLES_FIRST (:chunk-type info)))))
    
    (testing "Sequel packets have correct chunk type (0xC0)"
      (let [frame (make-test-frame 500)
            packets (sut/frame->fragmented-packets 
                     buf frame channel-id timestamp-us duration-us
                     :output-config standard-config)
            sequel-packets (rest packets)]
        (doseq [pkt sequel-packets]
          (let [info (idn-stream/parse-channel-message-header pkt)]
            (is (= idn-stream/CHUNK_TYPE_FRAME_SAMPLES_SEQUEL (:chunk-type info)))))))
    
    (testing "Last sequel packet has CCLF bit set"
      (let [frame (make-test-frame 500)
            packets (sut/frame->fragmented-packets 
                     buf frame channel-id timestamp-us duration-us
                     :output-config standard-config)
            last-packet (last packets)
            info (idn-stream/parse-channel-message-header last-packet)]
        ;; For sequel fragments, CCLF=1 means last fragment
        (when (> (count packets) 1)
          (is (:has-config? info) "Last sequel packet should have CCLF=1 (last fragment)"))))))


;; fragmentation-stats Tests


(deftest fragmentation-stats-test
  (testing "Stats for small frame"
    (let [frame (make-test-frame 100)
          stats (sut/fragmentation-stats frame standard-config)]
      (is (= 100 (:point-count stats)))
      (is (not (:needs-fragmentation? stats)))
      (is (= 1 (:fragment-count stats)))
      (is (= [100] (:points-per-fragment stats)))))
  
  (testing "Stats for large frame"
    (let [frame (make-test-frame 500)
          stats (sut/fragmentation-stats frame standard-config)]
      (is (= 500 (:point-count stats)))
      (is (:needs-fragmentation? stats))
      (is (> (:fragment-count stats) 1))
      ;; Sum of points per fragment should equal total
      (is (= 500 (reduce + (:points-per-fragment stats)))))))


;; Packet Validation Tests


(deftest packet-validation-test
  (let [buf (idn-stream/create-packet-buffer)
        channel-id 0
        timestamp-us 1000000
        duration-us 16666]
    
    (testing "All generated packets are valid"
      (let [frame (make-test-frame 500)
            packets (sut/frame->fragmented-packets 
                     buf frame channel-id timestamp-us duration-us
                     :output-config standard-config
                     :include-config? true)]
        (doseq [pkt packets]
          (let [result (idn-stream/validate-packet pkt)]
            (is (:valid? result) 
                (str "Packet validation failed: " (:error result)))))))
    
    (testing "Packet sizes are within MTU"
      (let [frame (make-test-frame 1000)
            packets (sut/frame->fragmented-packets 
                     buf frame channel-id timestamp-us duration-us
                     :output-config standard-config
                     :target-mtu 1400)]
        (doseq [pkt packets]
          ;; IDN packet + 4 bytes for IDN-Hello header should fit
          (is (<= (+ (alength pkt) sut/IDN_HELLO_HEADER_SIZE) 1400)
              (str "Packet too large: " (alength pkt) " bytes")))))))


;; Edge Cases


(deftest edge-cases-test
  (testing "Empty frame"
    (let [buf (idn-stream/create-packet-buffer)
          frame []
          packets (sut/frame->fragmented-packets 
                   buf frame 0 1000000 16666
                   :output-config standard-config)]
      (is (= 1 (count packets)))))
  
  (testing "Very large frame respects max fragments"
    (let [frame (make-test-frame 10000)
          frag-count (sut/fragment-count 10000 standard-config)]
      (is (<= frag-count sut/MAX_FRAGMENTS_PER_FRAME))))
  
  (testing "Custom MTU affects fragmentation"
    (let [frame (make-test-frame 300)
          ;; With default MTU (1400), might not need fragmentation
          stats-default (sut/fragmentation-stats frame standard-config :target-mtu 1400)
          ;; With smaller MTU (500), should need more fragments
          stats-small (sut/fragmentation-stats frame standard-config :target-mtu 500)]
      (is (>= (:fragment-count stats-small) (:fragment-count stats-default))))))
