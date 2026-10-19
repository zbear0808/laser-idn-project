(ns laser-show.triggers.tap-tempo-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.triggers.tap-tempo :as tap]))

(deftest calculates-bpm
  (let [state (-> (tap/make) (tap/tap 0) (tap/tap 500) (tap/tap 1000))]
    (is (= 120.0 (tap/bpm state)))))

(deftest timeout-starts-new-series
  (let [state (-> (tap/make {:timeout-ms 1000})
                  (tap/tap 0) (tap/tap 500) (tap/tap 2000))]
    (is (nil? (tap/bpm state)))
    (is (= [2000] (:taps state)))))
