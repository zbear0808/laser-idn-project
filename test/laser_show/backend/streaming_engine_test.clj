(ns laser-show.backend.streaming-engine-test
  (:require [clojure.test :refer :all]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.animation.types :as t]))

(deftest create-engine-test
  (testing "Creating a streaming engine with default options"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "192.168.1.100" frame-provider)]
      (is (= "192.168.1.100" (:target-host engine)))
      (is (= 7255 (:target-port engine)))
      (is (= 30 (:fps engine)))
      (is (= 0 (:channel-id engine)))
      (is (fn? (:frame-provider engine)))
      (is (false? @(:running? engine)))))
  
  (testing "Creating a streaming engine with custom options"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "10.0.0.1" frame-provider
                                   :fps 60
                                   :port 8000
                                   :channel-id 2)]
      (is (= "10.0.0.1" (:target-host engine)))
      (is (= 8000 (:target-port engine)))
      (is (= 60 (:fps engine)))
      (is (= 2 (:channel-id engine))))))

(deftest engine-state-test
  (testing "Engine running state"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "192.168.1.100" frame-provider)]
      (is (false? (se/running? engine)))
      (is (= {:frames-sent 0 :last-frame-time 0 :actual-fps 0.0}
             (se/get-stats engine))))))

(deftest log-callback-test
  (testing "Setting log callback"
    (let [frame-provider (fn [] (t/empty-frame))
          engine (se/create-engine "192.168.1.100" frame-provider)
          log-calls (atom [])
          callback (fn [data] (swap! log-calls conj data))]
      (is (nil? @(:log-callback engine)))
      (se/set-log-callback! engine callback)
      (is (= callback @(:log-callback engine))))))
