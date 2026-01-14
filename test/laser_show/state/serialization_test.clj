(ns laser-show.state.serialization-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [laser-show.state.serialization :as ser]))


;; Basic Round-trip Tests


(deftest basic-round-trip-test
  (testing "Map round-trip"
    (let [data {:type :cue :data {:name "test"}}]
      (is (= data (-> data ser/serialize ser/deserialize)))))
  
  (testing "Vector round-trip"
    (let [data [:item1 :item2 :item3]]
      (is (= data (-> data ser/serialize ser/deserialize))))))


;; Data Structure Validation Tests


(deftest deserialize-returns-valid-structures-test
  (testing "Deserialize returns map"
    (let [result (ser/deserialize "{:type :cue}")]
      (is (map? result))
      (is (= :cue (:type result)))))
  
  (testing "Deserialize returns vector"
    (let [result (ser/deserialize "[1 2 3]")]
      (is (sequential? result))
      (is (= [1 2 3] result)))))


;; Type Validation Tests (Application-specific)


(deftest type-check-validation-test
  (testing "Accept correct type"
    (let [cue-data {:type :cue :data {}}
          serialized (ser/serialize cue-data)
          result (ser/deserialize-with-schema serialized
                   :schema-fn (ser/with-type-check :cue))]
      (is (some? result))
      (is (= :cue (:type result)))))
  
  (testing "Reject wrong type"
    (let [effect-data {:type :effect :data {}}
          serialized (ser/serialize effect-data)
          result (ser/deserialize-with-schema serialized
                   :schema-fn (ser/with-type-check :cue)
                   :on-invalid (constantly nil))]
      (is (nil? result))))
  
  (testing "Reject data without type"
    (let [no-type-data {:data {}}
          serialized (ser/serialize no-type-data)
          result (ser/deserialize-with-schema serialized
                   :schema-fn (ser/with-type-check :cue)
                   :on-invalid (constantly nil))]
      (is (nil? result)))))

(deftest clipboard-type-safety-test
  (testing "Can't paste effect into cue grid"
    (let [effect {:type :effect-assignment :effect-id :color-cycle}
          serialized (ser/serialize-for-clipboard effect)
          result (ser/deserialize-from-clipboard serialized
                   :schema-fn (ser/with-type-check :cell-assignment)
                   :on-invalid (constantly nil))]
      (is (nil? result))
      (is (not= effect result))))
  
  (testing "Can paste cue into cue grid"
    (let [cue {:type :cell-assignment :preset-id :circle}
          serialized (ser/serialize-for-clipboard cue)
          result (ser/deserialize-from-clipboard serialized
                   :schema-fn (ser/with-type-check :cell-assignment))]
      (is (some? result))
      (is (= cue result))))
  
  (testing "Can't paste cue into effect grid"
    (let [cue {:type :cell-assignment :preset-id :circle}
          serialized (ser/serialize-for-clipboard cue)
          result (ser/deserialize-from-clipboard serialized
                   :schema-fn (ser/with-type-check :effect-assignment)
                   :on-invalid (constantly nil))]
      (is (nil? result))
      (is (not= cue result))))
  
  (testing "Can paste effect into effect grid"
    (let [effect {:type :effect-assignment :effect-id :color-cycle}
          serialized (ser/serialize-for-clipboard effect)
          result (ser/deserialize-from-clipboard serialized
                   :schema-fn (ser/with-type-check :effect-assignment))]
      (is (some? result))
      (is (= effect result)))))


;; File Operations (Basic Smoke Tests)


(deftest file-operations-test
  (let [test-file "test-serialization.edn"
        test-data {:type :projector :id :proj-1 :host "192.168.1.100"}]
    
    (testing "Save and load file"
      (is (ser/save-to-file! test-file test-data))
      (is (= test-data (ser/load-from-file test-file))))
    
    ;; Cleanup
    (when (.exists (io/file test-file))
      (.delete (io/file test-file)))))
