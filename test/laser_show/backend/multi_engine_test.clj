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
