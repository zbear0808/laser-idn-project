(ns laser-show.backend.streaming-engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.animation.types :as t]))


;; Behavioral Tests (not testing internal structure)


(deftest engine-creation-test
  (testing "Can create engine with frame provider"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "192.168.1.100" frame-provider)]
      (is (some? engine) "Engine should be created")
      (is (false? (se/running? engine)) "Engine should not be running initially"))))

(deftest engine-creation-with-null-host-test
  (testing "Can create engine with nil host (validation should happen at multi-engine level)"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine nil frame-provider)]
      (is (some? engine) "Engine should be created even with nil host")
      ;; The host validation happens at the multi-engine level
      ;; when deciding which engines to create and start
      (is (nil? (:target-host engine)) "Engine should have nil host"))))

(deftest engine-creation-with-blank-host-test
  (testing "Can create engine with blank host (validation should happen at multi-engine level)"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "" frame-provider)]
      (is (some? engine) "Engine should be created even with blank host")
      (is (= "" (:target-host engine)) "Engine should have blank host"))))

(deftest engine-stats-test
  (testing "Engine provides stats after creation"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "192.168.1.100" frame-provider)
          stats (se/get-stats engine)]
      (is (map? stats) "Stats should be a map")
      (is (contains? stats :frames-sent) "Stats should include frames-sent")
      (is (zero? (:frames-sent stats)) "Initial frames-sent should be zero"))))
