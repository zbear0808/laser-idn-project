(ns laser-show.input.osc-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [laser-show.events.handlers.input-mapper :as mapper]
            [laser-show.input.fixtures :as fx]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]))

(use-fixtures :each
  (fn [t]
    (router/clear-handlers!)
    (store/clear-continuous-values!)
    (try (t)
         (finally
           (router/clear-handlers!)
           (mapper/set-dispatch-fn! nil)
           (store/clear-continuous-values!)))))

(deftest fixture-shape-test
  (let [msg (fx/osc-msg "/1/push1" (float 1.0))]
    (is (= "/1/push1" (:path msg)))
    (is (= "f" (:type-tag msg)))
    (is (instance? Float (first (:args msg))))))

(deftest unmapped-float-button-test
  (testing "TouchOSC-style 1.0 / 0.0 floats become press / release triggers"
    (let [[press] (osc/osc-msg->events (fx/osc-msg "/1/push1" (float 1.0)) {})
          [release] (osc/osc-msg->events (fx/osc-msg "/1/push1" (float 0.0)) {})]
      (is (= :trigger (:type press)))
      (is (= "/1/push1" (:id press)))
      (is (= :pressed (:state press)))
      (is (= :trigger-release (:type release)))
      (is (= "/1/push1" (:id release))))))

(deftest unmapped-int-and-address-only-test
  (is (= [:trigger] (mapv :type (osc/osc-msg->events (fx/osc-msg "/btn" (int 1)) {}))))
  (is (= [:trigger-release] (mapv :type (osc/osc-msg->events (fx/osc-msg "/btn" (int 0)) {}))))
  (is (= [:trigger] (mapv :type (osc/osc-msg->events (fx/osc-msg "/go") {})))))

(deftest unmapped-faders-do-not-collide-test
  (testing "unmapped faders are stored by address, not under a shared CC 0"
    (mapper/set-dispatch-fn! (fn [_]))
    (mapper/start-mapper!)
    (doseq [e (concat (osc/osc-msg->events (fx/osc-msg "/fader/a" (float 0.25)) {})
                      (osc/osc-msg->events (fx/osc-msg "/fader/b" (float 0.75)) {}))]
      (router/dispatch! e))
    (is (= 0.25 (store/get-continuous-value [:osc "/fader/a"] nil)))
    (is (= 0.75 (store/get-continuous-value [:osc "/fader/b"] nil)))
    (is (nil? (store/get-continuous-value [:osc 0 0] nil)))))

(deftest unmapped-press-reaches-trigger-pipeline-test
  (let [dispatched (atom [])]
    (mapper/set-dispatch-fn! #(swap! dispatched conj %))
    (mapper/start-mapper!)
    (doseq [e (osc/osc-msg->events (fx/osc-msg "/1/push1" (float 1.0)) {})]
      (router/dispatch! e))
    (is (= [:trigger/input] (mapv :event/type @dispatched)))
    (is (= {:source :osc :id "/1/push1"}
           (select-keys (:input-event (first @dispatched)) [:source :id])))))

(deftest mapped-fader-test
  (let [evts (osc/osc-msg->events (fx/osc-msg "/fader1" (float 0.5)) osc/default-mappings)]
    (is (= [:control-change] (mapv :type evts)))
    (is (= 1 (:control (first evts))))
    (is (= 0.5 (:value (first evts))))))
