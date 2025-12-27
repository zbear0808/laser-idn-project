(ns laser-show.idn.hello-test
  "Unit tests for IDN-Hello protocol implementation."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [laser-show.idn.hello :as idn]))

;; ============================================================================
;; Test Fixtures - Pre-defined byte arrays for testing
;; ============================================================================

;; Valid scan response: command=0x11, struct-size=36, version=2.5, status=0x91 (malfunction+occupied+realtime)
;; Hostname "TestDAC" starts at byte 24
(def valid-scan-response
  (byte-array [0x11 0x00 0x00 0x00   ; header (command 0x11)
               36 0x25 0x91 0x00     ; struct-size=36, version=2.5, status=0x91, reserved
               0x00 0x00 0x00 0x00   ; bytes 8-11
               0x00 0x00 0x00 0x00   ; bytes 12-15
               0x00 0x00 0x00 0x00   ; bytes 16-19
               0x00 0x00 0x00 0x00   ; bytes 20-23
               0x54 0x65 0x73 0x74   ; "Test" (bytes 24-27)
               0x44 0x41 0x43 0x00   ; "DAC\0" (bytes 28-31)
               0x00 0x00 0x00 0x00   ; padding (bytes 32-35)
               0x00 0x00 0x00 0x00   ; padding (bytes 36-39)
               0x00 0x00 0x00 0x00])); padding (bytes 40-43)

;; ============================================================================
;; Packet Header Tests
;; ============================================================================

(deftest packet-header-test
  (testing "Creates and parses headers correctly"
    (let [header (idn/create-packet-header 0x40 5 258)
          parsed (idn/parse-packet-header header)]
      (is (= 4 (alength header)))
      (is (= 0x40 (:command parsed)))
      (is (= 5 (:client-group parsed)))
      (is (= 258 (:sequence parsed)))))
  
  (testing "Client group is masked to 4 bits"
    (let [header (idn/create-packet-header 0x40 0xFF 1)
          parsed (idn/parse-packet-header header)]
      (is (= 0x0F (:client-group parsed)))))
  
  (testing "Extracts payload when present"
    (let [data (byte-array [0x40 0x00 0x00 0x01 0xAA 0xBB 0xCC])
          parsed (idn/parse-packet-header data)]
      (is (= 3 (alength (:payload parsed))))))
  
  (testing "Returns nil for invalid data"
    (is (nil? (idn/parse-packet-header (byte-array [0x40 0x00]))))
    (is (nil? (idn/parse-packet-header nil)))))

(deftest bytes-to-hex-test
  (testing "Converts bytes to hex string"
    (is (= "40 00 01 02" (idn/bytes-to-hex (byte-array [0x40 0x00 0x01 0x02]))))
    (is (= "FF" (idn/bytes-to-hex (byte-array [-1]))))
    (is (= "" (idn/bytes-to-hex (byte-array 0))))))

;; ============================================================================
;; Scan Response Parsing Tests
;; ============================================================================

(deftest parse-scan-response-test
  (testing "Parses valid scan response"
    (let [parsed (idn/parse-scan-response valid-scan-response)]
      (is (= 36 (:struct-size parsed)))
      (is (= {:major 2 :minor 5} (:protocol-version parsed)))
      (is (:malfunction (:status parsed)))
      (is (:occupied (:status parsed)))
      (is (:realtime (:status parsed)))
      (is (not (:offline (:status parsed))))
      (is (str/starts-with? (:host-name parsed) "TestDAC"))))
  
  (testing "Returns nil for too-short response"
    (is (nil? (idn/parse-scan-response (byte-array 20))))))

;; ============================================================================
;; Wrap Channel Message Tests
;; ============================================================================

(deftest wrap-channel-message-test
  (testing "Wraps message with header"
    (idn/reset-sequence-counter!)
    (let [input-msg (byte-array [0xAA 0xBB 0xCC])
          wrapped (idn/wrap-channel-message input-msg {:client-group 5 :sequence 1000})
          parsed (idn/parse-packet-header wrapped)]
      (is (= 7 (alength wrapped)))
      (is (= idn/CMD_RT_CHANNEL_MESSAGE (:command parsed)))
      (is (= 5 (:client-group parsed)))
      (is (= 1000 (:sequence parsed)))
      (is (= 3 (alength (:payload parsed))))))
  
  (testing "Auto-increments sequence"
    (idn/reset-sequence-counter!)
    (let [parsed1 (idn/parse-packet-header (idn/wrap-channel-message (byte-array [0x00])))
          parsed2 (idn/parse-packet-header (idn/wrap-channel-message (byte-array [0x00])))]
      (is (= 1 (- (:sequence parsed2) (:sequence parsed1)))))))

;; ============================================================================
;; Socket Tests
;; ============================================================================

(deftest create-udp-socket-test
  (testing "Creates functional socket"
    (let [socket (idn/create-udp-socket)]
      (try
        (is (not (.isClosed socket)))
        (finally (.close socket))))
    (let [socket (idn/create-udp-socket 0)]
      (try
        (is (pos? (.getLocalPort socket)))
        (finally (.close socket))))))

;; ============================================================================
;; DAC Registry Tests
;; ============================================================================

(deftest dac-registry-test
  (testing "Register, update, and unregister DACs"
    (idn/clear-dac-registry!)
    (is (empty? (idn/list-dacs)))
    
    (idn/register-dac! :dac1 {:address "192.168.1.101"})
    (idn/register-dac! :dac2 {:address "192.168.1.102" :client-group 5})
    (is (= 2 (count (idn/list-dacs))))
    
    (let [dac1 (idn/get-dac :dac1)]
      (is (= "192.168.1.101" (:address dac1)))
      (is (= idn/IDN_HELLO_PORT (:port dac1)))
      (is (= 0 (:client-group dac1))))
    
    (idn/update-dac! :dac1 {:client-group 3})
    (is (= 3 (:client-group (idn/get-dac :dac1))))
    
    (idn/update-dac! :nonexistent {:address "1.2.3.4"})
    (is (nil? (idn/get-dac :nonexistent)))
    
    (idn/unregister-dac! :dac1)
    (is (= 1 (count (idn/list-dacs))))
    
    (idn/clear-dac-registry!)
    (is (empty? (idn/list-dacs)))))

;; ============================================================================
;; Per-DAC Sequence Counter Tests
;; ============================================================================

(deftest dac-sequence-counter-test
  (testing "Independent sequence counters per DAC"
    (idn/reset-all-dac-sequences!)
    (is (= 1 (idn/get-dac-sequence :dac-a)))
    (is (= 2 (idn/get-dac-sequence :dac-a)))
    (is (= 1 (idn/get-dac-sequence :dac-b)))
    (is (= 3 (idn/get-dac-sequence :dac-a)))
    (is (= 2 (idn/get-dac-sequence :dac-b)))
    
    (idn/reset-dac-sequence! :dac-a)
    (is (= 1 (idn/get-dac-sequence :dac-a)))
    (is (= 3 (idn/get-dac-sequence :dac-b)))))

(deftest dac-sequence-wrap-test
  (testing "Sequence wraps at 16-bit boundary"
    (idn/reset-all-dac-sequences!)
    (dotimes [_ 65534] (idn/get-dac-sequence :wrap-test))
    (is (= 65535 (idn/get-dac-sequence :wrap-test)))
    (is (= 0 (idn/get-dac-sequence :wrap-test)))
    (is (= 1 (idn/get-dac-sequence :wrap-test)))))

;; ============================================================================
;; Available DACs Filter Tests
;; ============================================================================

(deftest get-available-dacs-test
  (testing "Filters DACs by status"
    (idn/clear-dac-registry!)
    (idn/register-dac! :good {:address "1.1.1.1" :status {:offline false :malfunction false :excluded false :occupied false}})
    (idn/register-dac! :offline {:address "1.1.1.2" :status {:offline true :malfunction false :excluded false :occupied false}})
    (idn/register-dac! :bad {:address "1.1.1.3" :status {:offline false :malfunction true :excluded false :occupied false}})
    (idn/register-dac! :occupied {:address "1.1.1.4" :status {:offline false :malfunction false :excluded false :occupied true}})
    (idn/register-dac! :no-status {:address "1.1.1.5"})
    
    (let [available (set (idn/get-available-dacs))]
      (is (contains? available :good))
      (is (contains? available :occupied))
      (is (contains? available :no-status))
      (is (not (contains? available :offline)))
      (is (not (contains? available :bad))))
    
    (let [available (set (idn/get-available-dacs {:exclude-occupied true}))]
      (is (not (contains? available :occupied))))))

;; Network send tests (send-to-dac!, send-to-all-dacs!) have been moved to
;; test/integration/idn_hello_test.clj since they send actual UDP packets.
