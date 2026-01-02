(ns laser-show.backend.idn-stream-test
  "Tests for IDN-Stream packet format compliance with ILDA spec (Revision 002, July 2025)"
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.idn.stream :as stream]
            [laser-show.idn.output-config :as output-config]
            [laser-show.animation.types :as t])
  (:import [java.nio ByteBuffer ByteOrder]))


;; Helper Functions

(defn get-byte [^bytes arr idx]
  (bit-and (aget arr idx) 0xFF))

(defn get-short [^bytes arr idx]
  (let [buf (ByteBuffer/wrap arr)]
    (.order buf ByteOrder/BIG_ENDIAN)
    (.position buf idx)
    (.getShort buf)))

(defn get-int [^bytes arr idx]
  (let [buf (ByteBuffer/wrap arr)]
    (.order buf ByteOrder/BIG_ENDIAN)
    (.position buf idx)
    (.getInt buf)))



;; Channel Message Header Tests (Section 2.1)


(deftest channel-message-header-test
  (testing "Empty frame packet has correct header structure"
    (let [packet (stream/empty-frame-packet 0 1000000)]
      (is (>= (alength packet) 8) "Packet must be at least 8 bytes")

      (let [total-size (get-short packet 0)
            cnl-byte (get-byte packet 2)
            chunk-type (get-byte packet 3)
            timestamp (get-int packet 4)]

        (is (= (alength packet) total-size) "Total size field must match packet length")
        (is (= 0x02 chunk-type) "Chunk type should be FRAME_SAMPLES (0x02)")
        (is (= 1000000 timestamp) "Timestamp should match input"))))

  (testing "CNL byte structure - bit 7 always set for channel messages"
    (let [frame (t/make-frame [(t/make-point 0 0 1.0 1.0 1.0)])
          packet-with-config (stream/frame->packet-with-config frame 5 1000 33333)
          packet-without-config (stream/frame->packet frame 5 1000 33333)
          cnl-with (get-byte packet-with-config 2)
          cnl-without (get-byte packet-without-config 2)]
      (is (bit-test cnl-with 7) "Bit 7 (channel msg) should ALWAYS be set")
      (is (bit-test cnl-without 7) "Bit 7 (channel msg) should ALWAYS be set even without config")))

  (testing "CCLF bit (bit 6) is set when config is present"
    (let [frame (t/make-frame [(t/make-point 0 0 1.0 1.0 1.0)])
          packet (stream/frame->packet-with-config frame 5 1000 33333)
          cnl-byte (get-byte packet 2)]
      (is (bit-test cnl-byte 6) "CCLF bit (bit 6) should be set when config present")
      (is (= 5 (bit-and cnl-byte 0x3F)) "Channel ID should be in lower 6 bits")))

  (testing "CCLF bit (bit 6) is clear when config is absent"
    (let [frame (t/make-frame [(t/make-point 0 0 1.0 1.0 1.0)])
          packet (stream/frame->packet frame 5 1000 33333)
          cnl-byte (get-byte packet 2)]
      (is (not (bit-test cnl-byte 6)) "CCLF bit (bit 6) should be clear when no config")
      (is (= 5 (bit-and cnl-byte 0x3F)) "Channel ID should be in lower 6 bits"))))


;; Channel Configuration Header Tests (Section 2.2)


(deftest channel-config-header-test
  (testing "Configuration header has correct structure"
    (let [frame (t/make-frame [(t/make-point 0 0 1.0 1.0 1.0)])
          packet (stream/frame->packet-with-config frame 0 1000 33333)
          config-offset 8
          scwc (get-byte packet config-offset)
          cfl (get-byte packet (+ config-offset 1))
          service-id (get-byte packet (+ config-offset 2))
          service-mode (get-byte packet (+ config-offset 3))]

      (is (pos? scwc) "SCWC should be positive (tags present)")
      (is (bit-test cfl 0) "Routing flag should be set")
      (is (= 0 service-id) "Default service ID should be 0")
      (is (= 0x02 service-mode) "Service mode should be GRAPHIC_DISCRETE")))

  (testing "Close flag is set when requested"
    (let [frame (t/make-frame [(t/make-point 0 0 1.0 1.0 1.0)])
          packet (stream/frame->packet-with-config frame 0 1000 33333 :close? true)
          cfl (get-byte packet 9)]
      (is (bit-test cfl 1) "Close flag (bit 1) should be set"))))


;; Service Configuration Tags Tests (Section 3.4)


(deftest service-config-tags-test
  (testing "Default tags match ISP-DB25 compatibility (Section 3.4.10/3.4.11)"
    ;; 7 descriptor tags for X(16-bit), Y(16-bit), R, G, B
    ;; Plus TAG_VOID for 32-bit alignment (8 tags = 16 bytes = 4 words)
    (let [expected-tags [stream/TAG_X stream/TAG_PRECISION
                         stream/TAG_Y stream/TAG_PRECISION
                         stream/TAG_COLOR_RED
                         stream/TAG_COLOR_GREEN
                         stream/TAG_COLOR_BLUE
                         stream/TAG_VOID]]  ; Alignment padding per Section 3.4.2
      (is (= expected-tags stream/default-graphic-tags))))

  (testing "Tags are written correctly to packet"
    (let [frame (t/make-frame [(t/make-point 0 0 1.0 1.0 1.0)])
          packet (stream/frame->packet-with-config frame 0 1000 33333)
          tags-offset 12
          buf (ByteBuffer/wrap packet)]
      (.order buf ByteOrder/BIG_ENDIAN)
      (.position buf tags-offset)

      (is (= stream/TAG_X (.getShort buf)) "First tag should be X")
      (is (= stream/TAG_PRECISION (.getShort buf)) "Second tag should be PRECISION")
      (is (= stream/TAG_Y (.getShort buf)) "Third tag should be Y")
      (is (= stream/TAG_PRECISION (.getShort buf)) "Fourth tag should be PRECISION"))))


;; Frame Samples Data Chunk Tests (Section 6.2)


(deftest frame-chunk-header-test
  (testing "Frame chunk header has correct structure"
    (let [frame (t/make-frame [(t/make-point 0 0 1.0 1.0 1.0)])
          packet (stream/frame->packet frame 0 1000 33333)
          chunk-offset 8
          flags (get-byte packet chunk-offset)
          duration-hi (get-byte packet (+ chunk-offset 1))
          duration-mid (get-byte packet (+ chunk-offset 2))
          duration-lo (get-byte packet (+ chunk-offset 3))
          duration (bit-or (bit-shift-left duration-hi 16)
                           (bit-shift-left duration-mid 8)
                           duration-lo)]

      (is (= 0 (bit-and flags 0x01)) "Once flag should be clear by default")
      (is (= 33333 duration) "Duration should match input")))

  (testing "Once flag is set when single-scan requested"
    (let [frame (t/make-frame [(t/make-point 0 0 1.0 1.0 1.0)])
          packet (stream/frame->packet frame 0 1000 33333 :single-scan? true)
          flags (get-byte packet 8)]
      (is (= 1 (bit-and flags 0x01)) "Once flag should be set"))))


;; Sample Data Tests


(deftest sample-data-test
  (testing "Point data is written in correct format (8-bit color, standard config)"
    ;; Using normalized values: 1.0, 0.5 (128/255), 0.25 (64/255)
    ;; Testing with standard-config (8-bit color, 16-bit XY) for predictable byte values
    (let [point (t/make-point 0.5 -0.5 1.0 0.5 0.25)
          frame (t/make-frame [point])
          packet (stream/frame->packet frame 0 1000 33333
                                       :output-config output-config/standard-config)
          sample-offset 12
          buf (ByteBuffer/wrap packet)]
      (.order buf ByteOrder/BIG_ENDIAN)
      (.position buf sample-offset)

      (let [x (.getShort buf)
            y (.getShort buf)
            r (bit-and (.get buf) 0xFF)
            g (bit-and (.get buf) 0xFF)
            b (bit-and (.get buf) 0xFF)]

        (is (> x 0) "X should be positive for 0.5")
        (is (< y 0) "Y should be negative for -0.5")
        (is (= 255 r) "Red should be 255 (1.0 normalized)")
        (is (= 127 g) "Green should be 127 (0.5 normalized)")
        (is (= 63 b) "Blue should be 63 (0.25 normalized)"))))

  (testing "Multiple points are written sequentially (8-bit color)"
    (let [points [(t/make-point 0 0 1.0 0 0)
                  (t/make-point 0.5 0.5 0 1.0 0)
                  (t/make-point -0.5 -0.5 0 0 1.0)]
          frame (t/make-frame points)
          packet (stream/frame->packet frame 0 1000 33333
                                       :output-config output-config/standard-config)]

      (is (= (+ 12 (* 7 3)) (alength packet))
          "Packet size should be header + 3 points * 7 bytes each")))

  (testing "Multiple points with 16-bit color (default config)"
    (let [points [(t/make-point 0 0 1.0 0 0)
                  (t/make-point 0.5 0.5 0 1.0 0)
                  (t/make-point -0.5 -0.5 0 0 1.0)]
          frame (t/make-frame points)
          packet (stream/frame->packet frame 0 1000 33333)]
      ;; 16-bit XY (4 bytes) + 16-bit RGB (6 bytes) = 10 bytes per point
      (is (= (+ 12 (* 10 3)) (alength packet))
          "Packet size should be header + 3 points * 10 bytes each"))))


;; Packet Validation Tests


(deftest packet-validation-test
  (testing "Valid packet passes validation"
    (let [frame (t/make-frame [(t/make-point 0 0 1.0 1.0 1.0)])
          packet (stream/frame->packet frame 0 1000 33333)
          result (stream/validate-packet packet)]
      (is (:valid? result) "Packet should be valid")))

  (testing "Packet info extraction"
    (let [frame (t/make-frame [(t/make-point 0 0 1.0 1.0 1.0)])
          packet (stream/frame->packet-with-config frame 3 5000000 33333)
          info (stream/packet-info packet)]
      (is (= 3 (:channel-id info)))
      (is (= 5000000 (:timestamp info)))
      (is (= 0x02 (:chunk-type info)))
      (is (:has-config? info)))))


;; Close Channel Packet Tests


(deftest close-channel-packet-test
  (testing "Close channel packet has correct structure"
    (let [packet (stream/close-channel-packet 5 1000000)
          info (stream/packet-info packet)]
      (is (= 5 (:channel-id info)))
      (is (= 0x00 (:chunk-type info)) "Chunk type should be VOID")
      (is (:has-config? info) "Should have config header")

      (let [cfl (get-byte packet 9)]
        (is (bit-test cfl 1) "Close flag should be set")))))


;; Utility Function Tests


(deftest utility-functions-test
  (testing "Frame duration calculation"
    (is (= 33333 (stream/frame-duration-us 30)) "30 FPS = ~33333 us")
    (is (= 16666 (stream/frame-duration-us 60)) "60 FPS = ~16666 us"))

  (testing "Packet size calculations with standard config (16-bit XY, 8-bit color)"
    (let [config output-config/standard-config]
      (is (= 12 (stream/packet-size-without-config 0 config)) "Empty frame: 8 + 4 = 12")
      (is (= 19 (stream/packet-size-without-config 1 config)) "1 point: 8 + 4 + 7 = 19")
      (is (= 26 (stream/packet-size-without-config 2 config)) "2 points: 8 + 4 + 14 = 26")))

  (testing "Packet size calculations with default config (16-bit color, 16-bit XY)"
    (let [config output-config/default-config]
      ;; 16-bit XY (4 bytes) + 16-bit RGB (6 bytes) = 10 bytes per point
      (is (= 12 (stream/packet-size-without-config 0 config)) "Empty frame: 8 + 4 = 12")
      (is (= 22 (stream/packet-size-without-config 1 config)) "1 point: 8 + 4 + 10 = 22")
      (is (= 32 (stream/packet-size-without-config 2 config)) "2 points: 8 + 4 + 20 = 32")))

  (testing "Packet size calculations with compact config (8-bit XY, 8-bit color)"
    (let [config output-config/compact-config]
      ;; 8-bit XY (2 bytes) + 8-bit RGB (3 bytes) = 5 bytes per point
      (is (= 12 (stream/packet-size-without-config 0 config)) "Empty frame: 8 + 4 = 12")
      (is (= 17 (stream/packet-size-without-config 1 config)) "1 point: 8 + 4 + 5 = 17")
      (is (= 22 (stream/packet-size-without-config 2 config)) "2 points: 8 + 4 + 10 = 22"))))
