(ns integration.idn-hello-test
  "Integration tests for IDN-Hello protocol - tests that send actual network packets.
   These tests should only be run when you want to test against real network infrastructure."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.idn.hello :as idn]))

;; ============================================================================
;; Network Send Tests (Integration)
;; These tests actually send UDP packets to localhost
;; ============================================================================

(deftest send-to-dac-test
  (testing "Send to registered and non-existent DACs"
    (idn/clear-dac-registry!)
    (idn/register-dac! :test-dac {:address "127.0.0.1" :port 7255})
    (let [socket (idn/create-udp-socket)]
      (try
        (is (false? (idn/send-to-dac! :nonexistent socket (byte-array [0x00]))))
        (is (true? (idn/send-to-dac! :test-dac socket (byte-array [0x00]))))
        (finally (.close socket))))))

(deftest send-to-all-dacs-test
  (testing "Sends to all registered DACs"
    (idn/clear-dac-registry!)
    (let [socket (idn/create-udp-socket)]
      (try
        (is (empty? (idn/send-to-all-dacs! socket (byte-array [0x00]))))
        
        (idn/register-dac! :dac1 {:address "127.0.0.1" :port 7255})
        (idn/register-dac! :dac2 {:address "127.0.0.1" :port 7256})
        (let [results (idn/send-to-all-dacs! socket (byte-array [0x00]))]
          (is (= 2 (count results)))
          (is (every? true? (vals results))))
        (finally (.close socket))))))
