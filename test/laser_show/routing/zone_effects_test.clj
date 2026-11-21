(ns laser-show.routing.zone-effects-test
  "The zone routing debug logging, and the Zone Selector's own reading of
   its parameter. Since zones plan Z4 no routing reads a selector: where
   each item goes is routing.targets' (routing/targets_test.clj), and an old
   show's selectors become targets on load (state/migration_test.clj)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.routing.zone-effects :as zone-effects]
            [laser-show.animation.effects.zone :as zone]))


;; evaluate-zone tests

(deftest evaluate-zone-test
  (testing "Simple keyword target returns that keyword"
    (is (= :left (zone/evaluate-zone {:target-zone :left})))
    (is (= :right (zone/evaluate-zone {:target-zone :right}))))

  (testing "Missing target-zone defaults to :all"
    (is (= :all (zone/evaluate-zone {})))))


;; Debug Logging Tests


(deftest log-once-is-lazy-test
  (testing "log-once does not evaluate key or message when debug is off"
    (zone-effects/disable-routing-debug!)
    (let [evaluated (atom 0)]
      (zone-effects/log-once (do (swap! evaluated inc) :k)
                             (do (swap! evaluated inc) "msg"))
      (is (zero? @evaluated))))

  (testing "log-once builds the message once per key when debug is on"
    (zone-effects/reset-zone-debug!)
    (zone-effects/enable-routing-debug!)
    (try
      (let [evaluated (atom 0)]
        (dotimes [_ 3]
          (zone-effects/log-once ::lazy-test (do (swap! evaluated inc) "msg")))
        (is (= 1 @evaluated)))
      (finally
        (zone-effects/disable-routing-debug!)
        (zone-effects/reset-zone-debug!)))))
