(ns laser-show.backend.packet-logger-test
  "Unit tests for IDN packet logging functionality."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [laser-show.backend.packet-logger :as plog]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def test-log-file "test-packet-log.tmp")

(defn cleanup-fixture
  "Cleans up test log files after tests."
  [f]
  (f)
  (when (.exists (io/file test-log-file))
    (io/delete-file test-log-file)))

(use-fixtures :each cleanup-fixture)

;; ============================================================================
;; Command Name Tests
;; ============================================================================

(deftest command-name-test
  (testing "Maps known command bytes to names"
    (is (= "PING_REQUEST" (plog/command-name 0x08)))
    (is (= "PING_RESPONSE" (plog/command-name 0x09)))
    (is (= "SCAN_REQUEST" (plog/command-name 0x10)))
    (is (= "SCAN_RESPONSE" (plog/command-name 0x11)))
    (is (= "SERVICE_MAP_REQUEST" (plog/command-name 0x12)))
    (is (= "SERVICE_MAP_RESPONSE" (plog/command-name 0x13)))
    (is (= "RT_CHANNEL_MESSAGE" (plog/command-name 0x40)))
    (is (= "RT_CHANNEL_MESSAGE_ACK" (plog/command-name 0x41)))
    (is (= "RT_CLOSE" (plog/command-name 0x44)))
    (is (= "RT_CLOSE_ACK" (plog/command-name 0x45)))
    (is (= "RT_ABORT" (plog/command-name 0x46)))
    (is (= "RT_ACKNOWLEDGE" (plog/command-name 0x47))))
  
  (testing "Unknown commands return UNKNOWN_XX format"
    (is (= "UNKNOWN_FF" (plog/command-name 0xFF)))
    (is (= "UNKNOWN_00" (plog/command-name 0x00)))
    (is (= "UNKNOWN_99" (plog/command-name 0x99))))
  
  (testing "Handles negative bytes (signed byte conversion)"
    (is (= "UNKNOWN_FF" (plog/command-name -1)))
    (is (= "UNKNOWN_80" (plog/command-name -128)))))

;; ============================================================================
;; Packet Formatting Tests
;; ============================================================================

(deftest format-packet-log-entry-test
  (testing "Formats basic packet correctly"
    (let [packet (byte-array [0x40 0x00 0x00 0x01])
          entry (plog/format-packet-log-entry packet)]
      (is (string? entry))
      (is (.contains entry "RT_CHANNEL_MESSAGE"))
      (is (.contains entry "seq=1"))
      (is (.contains entry "40 00 00 01"))))
  
  (testing "Formats packet with payload"
    (let [packet (byte-array [0x08 0x00 0x00 0x64 0xAA 0xBB 0xCC])
          entry (plog/format-packet-log-entry packet)]
      (is (.contains entry "PING_REQUEST"))
      (is (.contains entry "seq=100"))
      (is (.contains entry "AA BB CC"))))
  
  (testing "Handles sequence number correctly"
    ;; Big-endian: 0x01 0x00 = 256
    (let [packet (byte-array [0x40 0x00 0x01 0x00])
          entry (plog/format-packet-log-entry packet)]
      (is (.contains entry "seq=256"))))
  
  (testing "Returns nil for empty data"
    (is (nil? (plog/format-packet-log-entry (byte-array 0))))
    (is (nil? (plog/format-packet-log-entry nil)))))

;; ============================================================================
;; File Operations Tests
;; ============================================================================

(deftest start-stop-logging-test
  (testing "Starts and stops logging"
    (let [writer (plog/start-logging! test-log-file)]
      (is (some? writer))
      (plog/stop-logging! writer)
      (is (.exists (io/file test-log-file))))))

(deftest log-packet-test
  (testing "Logs packets to file"
    (let [writer (plog/start-logging! test-log-file)]
      (try
        (let [packet (byte-array [0x40 0x00 0x00 0x01 0xAB 0xCD])]
          (is (true? (plog/log-packet! writer packet))))
        (finally
          (plog/stop-logging! writer)))
      
      ;; Read and verify log contents
      (let [contents (slurp test-log-file)]
        (is (.contains contents "RT_CHANNEL_MESSAGE"))
        (is (.contains contents "seq=1"))
        (is (.contains contents "AB CD"))))))

(deftest log-multiple-packets-test
  (testing "Logs multiple packets in sequence"
    (let [writer (plog/start-logging! test-log-file)]
      (try
        (plog/log-packet! writer (byte-array [0x08 0x00 0x00 0x01]))
        (plog/log-packet! writer (byte-array [0x40 0x00 0x00 0x02]))
        (plog/log-packet! writer (byte-array [0x44 0x00 0x00 0x03]))
        (finally
          (plog/stop-logging! writer)))
      
      (let [contents (slurp test-log-file)
            lines (clojure.string/split-lines contents)]
        (is (= 3 (count lines)))
        (is (.contains (first lines) "PING_REQUEST"))
        (is (.contains (second lines) "RT_CHANNEL_MESSAGE"))
        (is (.contains (nth lines 2) "RT_CLOSE"))))))

;; ============================================================================
;; Callback Tests
;; ============================================================================

(deftest create-log-callback-test
  (testing "Creates working callback function"
    (let [writer (plog/start-logging! test-log-file)
          callback (plog/create-log-callback writer)]
      (try
        (is (fn? callback))
        (callback (byte-array [0x40 0x00 0x00 0x05]))
        (finally
          (plog/stop-logging! writer)))
      
      (let [contents (slurp test-log-file)]
        (is (.contains contents "RT_CHANNEL_MESSAGE"))
        (is (.contains contents "seq=5")))))
  
  (testing "Returns nil for nil writer"
    (is (nil? (plog/create-log-callback nil)))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest log-with-nil-writer-test
  (testing "log-packet! handles nil writer gracefully"
    (is (nil? (plog/log-packet! nil (byte-array [0x40 0x00 0x00 0x01]))))))

(deftest stop-nil-writer-test
  (testing "stop-logging! handles nil writer gracefully"
    (is (nil? (plog/stop-logging! nil)))))
