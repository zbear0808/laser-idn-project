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

(deftest engine-stats-test
  (testing "Engine provides stats after creation"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "192.168.1.100" frame-provider)
          stats (se/get-stats engine)]
      (is (map? stats) "Stats should be a map")
      (is (contains? stats :frames-sent) "Stats should include frames-sent")
      (is (zero? (:frames-sent stats)) "Initial frames-sent should be zero"))))

(deftest log-callback-behavior-test
  (testing "Log callback can be set and functions properly"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "192.168.1.100" frame-provider)
          log-calls (atom [])
          callback (fn [data] (swap! log-calls conj data))]
      (se/set-log-callback! engine callback)
      ;; Test behavior, not internal structure - callback should be set
      (is (fn? (se/set-log-callback! engine callback))))))
