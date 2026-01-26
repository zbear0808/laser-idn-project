(ns laser-show.backend.multi-engine-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.backend.multi-engine :as me]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))


;; Test Fixtures

(defn setup-test-state [f]
  "Set up test state before each test"
  (state/init-state! (domains/build-initial-state))
  (f))

(use-fixtures :each setup-test-state)


;; Validation Tests


(deftest create-engine-with-valid-host-test
  (testing "Creates engine when projector has valid host"
    (let [projector {:name "Test Projector"
                     :host "192.168.1.100"
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]
                     :output-config {:color-bit-depth 8
                                    :xy-bit-depth 16}}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (some? engine) "Engine should be created with valid host")
      (is (= "192.168.1.100" (:target-host engine)) "Engine should have correct host"))))

(deftest create-engine-with-nil-host-test
  (testing "Returns nil when projector has nil host"
    (let [projector {:name "Test Projector"
                     :host nil
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (nil? engine) "Engine should not be created with nil host"))))

(deftest create-engine-with-blank-host-test
  (testing "Returns nil when projector has blank host"
    (let [projector {:name "Test Projector"
                     :host ""
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (nil? engine) "Engine should not be created with blank host"))))

(deftest create-engine-with-whitespace-host-test
  (testing "Returns nil when projector has whitespace-only host"
    (let [projector {:name "Test Projector"
                     :host "   "
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (nil? engine) "Engine should not be created with whitespace-only host"))))

(deftest create-engines-skips-invalid-hosts-test
  (testing "create-engines skips projectors with invalid hosts"
    ;; Set up state with mix of valid and invalid projectors
    (state/reset-state!
      {:projectors {:proj-1 {:name "Valid Projector"
                             :host "192.168.1.100"
                             :port 7255
                             :enabled? true
                             :zone-groups [:all]
                             :output-config {:color-bit-depth 8
                                           :xy-bit-depth 16}}
                    :proj-2 {:name "Invalid Projector (nil host)"
                             :host nil
                             :port 7255
                             :enabled? true
                             :zone-groups [:all]}
                    :proj-3 {:name "Invalid Projector (blank host)"
                             :host ""
                             :port 7255
                             :enabled? true
                             :zone-groups [:all]}
                    :proj-4 {:name "Disabled Projector"
                             :host "192.168.1.101"
                             :port 7255
                             :enabled? false
                             :zone-groups [:all]}}})
    
    (let [engines (me/create-engines)]
      (is (= 1 (count engines)) "Should only create engine for valid enabled projector")
      (is (contains? engines :proj-1) "Should include valid projector")
      (is (not (contains? engines :proj-2)) "Should skip projector with nil host")
      (is (not (contains? engines :proj-3)) "Should skip projector with blank host")
      (is (not (contains? engines :proj-4)) "Should skip disabled projector"))))


;; Channel ID Assignment Tests
;;
;; These tests verify that each streaming engine gets a unique channel ID
;; to prevent the service ID alternation bug (BUG-2026-01-25-1)


(deftest engine-uses-service-id-as-channel-id-test
  (testing "Engine uses service-id as channel-id to prevent channel collision"
    (let [projector {:name "Test Projector"
                     :host "192.168.1.100"
                     :port 7255
                     :enabled? true
                     :service-id 7
                     :zone-groups [:all]
                     :output-config {:color-bit-depth 8
                                    :xy-bit-depth 16}}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (some? engine) "Engine should be created")
      (is (= 7 (:service-id engine)) "Engine should have service-id 7")
      (is (= 7 (:channel-id engine)) "Engine should have channel-id equal to service-id"))))

(deftest engine-default-channel-id-when-no-service-id-test
  (testing "Engine uses 0 for both channel-id and service-id when not specified"
    (let [projector {:name "Test Projector"
                     :host "192.168.1.100"
                     :port 7255
                     :enabled? true
                     :zone-groups [:all]}
          engine (me/create-engine-for-projector :test-proj projector)]
      (is (some? engine) "Engine should be created")
      (is (= 0 (:service-id engine)) "Engine should default to service-id 0")
      (is (= 0 (:channel-id engine)) "Engine should default to channel-id 0"))))

(deftest multiple-engines-get-unique-channel-ids-test
  (testing "Multiple engines for same host get unique channel IDs based on service-id"
    ;; This tests the fix for the bug where all engines shared channel 0,
    ;; causing the IDN device to rapidly switch which service received data
    (state/reset-state!
      {:projectors {:proj-1 {:name "Projector 1"
                             :host "192.168.1.100"
                             :port 7255
                             :enabled? true
                             :service-id 1
                             :zone-groups [:all]}
                    :proj-2 {:name "Projector 2"
                             :host "192.168.1.100"  ;; Same host!
                             :port 7255
                             :enabled? true
                             :service-id 2
                             :zone-groups [:all]}
                    :proj-3 {:name "Projector 3"
                             :host "192.168.1.100"  ;; Same host!
                             :port 7255
                             :enabled? true
                             :service-id 10
                             :zone-groups [:left]}}})
    
    (let [engines (me/create-engines)
          channel-ids (set (map :channel-id (vals engines)))]
      (is (= 3 (count engines)) "Should create 3 engines")
      (is (= #{1 2 10} channel-ids) "Each engine should have unique channel-id from service-id"))))
