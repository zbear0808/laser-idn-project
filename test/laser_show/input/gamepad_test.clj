(ns laser-show.input.gamepad-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.handlers.input-mapper :as mapper]
            [laser-show.input.gamepad :as gamepad]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]))

(defn- ctrl [buttons axes]
  {:buttons (merge (zipmap (keys gamepad/buttons) (repeat false)) buttons)
   :axes (merge {:left-x 0.5 :left-y 0.5 :right-x 0.5 :right-y 0.5
                 :trigger-left 0.5 :trigger-right 0.5}
                axes)})

(defn- summary [events]
  (mapv (juxt :type :id) events))

(deftest press-hold-release-test
  (let [idle {0 (ctrl {} {})}
        held {0 (ctrl {:a true} {})}]
    (testing "press fires once"
      (is (= [[:trigger [0 :a]]] (summary (gamepad/diff-controller-states idle held)))))
    (testing "holding does not refire"
      (is (empty? (gamepad/diff-controller-states held held))))
    (testing "release is sent"
      (is (= [[:trigger-release [0 :a]]] (summary (gamepad/diff-controller-states held idle)))))))

(deftest new-controller-test
  (testing "a controller that just appeared only reports pressed buttons"
    (is (= [[:trigger [1 :b]]]
           (summary (gamepad/diff-controller-states {} {1 (ctrl {:b true} {})}))))))

(deftest unplugged-controller-releases-held-buttons-test
  (is (= [[:trigger-release [0 :x]]]
         (summary (gamepad/diff-controller-states {0 (ctrl {:x true} {})} {})))))

(deftest axis-threshold-test
  (let [base {0 (ctrl {} {})}]
    (is (empty? (gamepad/diff-controller-states base {0 (ctrl {} {:left-x 0.502})})))
    (let [[e :as events] (gamepad/diff-controller-states base {0 (ctrl {} {:left-x 0.8})})]
      (is (= 1 (count events)))
      (is (= [:control-change :gamepad 0 :left-x 0.8]
             ((juxt :type :source :channel :control :value) e))))))

(deftest gamepad-events-through-router-test
  (router/clear-handlers!)
  (store/clear-continuous-values!)
  (let [dispatched (atom [])]
    (try
      (mapper/set-dispatch-fn! #(swap! dispatched conj %))
      (mapper/start-mapper!)
      (doseq [e (gamepad/diff-controller-states {0 (ctrl {} {})}
                                                {0 (ctrl {:a true} {:left-x 0.8})})]
        (router/dispatch! e))
      (testing "buttons reach the trigger pipeline as well-formed events"
        (is (= [:trigger/input] (mapv :event/type @dispatched)))
        (is (= [0 :a] (get-in (first @dispatched) [:input-event :id]))))
      (testing "axes go to the input store only"
        (is (= 0.8 (store/get-continuous-value [:gamepad 0 :left-x] nil))))
      (finally
        (router/clear-handlers!)
        (mapper/set-dispatch-fn! nil)
        (store/clear-continuous-values!)))))

(deftest stop-without-start-is-safe-test
  (is (nil? (gamepad/stop!)))
  (is (false? (gamepad/running?))))

(deftest poll-loop-stops-and-respects-enabled-test
  (let [updates (atom 0)
        enabled? (atom true)
        manager (proxy [com.studiohartman.jamepad.ControllerManager] []
                  (update [] (swap! updates inc))
                  (getNumControllers [] 0))
        running (atom true)
        poll-loop @#'gamepad/poll-loop
        t (doto (Thread. ^Runnable #(poll-loop manager (fn [_]) (fn [] @enabled?) running))
            (.setDaemon true)
            (.start))]
    (Thread/sleep 100)
    (is (pos? @updates) "polls while enabled")
    (reset! enabled? false)
    (Thread/sleep 150)
    (let [n @updates]
      (Thread/sleep 250)
      (is (= n @updates) "does not touch controllers while disabled"))
    (gamepad/stop! {:thread t :running running})
    (is (not (.isAlive t)) "stop! ends the poll thread")))
