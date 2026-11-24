(ns laser-show.input.gamepad-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.handlers.input-mapper :as mapper]
            [laser-show.input.gamepad :as gamepad]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]
            [laser-show.state.core :as state]))

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


;; The poll loop (reactivity plan 27): an axis is sent once it is past the
;; threshold from the value it was last sent at, not from the last sample

(defn- run-scripted-polls
  "Runs the real poll loop on this thread over script, a pass an entry:
   the normalized controller map that pass reads, or :disabled for a pass
   with gamepads switched off. No SDL, thread or sleep: the read and both
   poll intervals are stubbed, and the last pass stops the loop. Every
   event the loop sends goes on to dispatch. {:reads n :passes [[event …]
   …]}: how often it read the controllers, and what each pass sent."
  [script dispatch]
  (let [script (vec script)
        running (atom (boolean (seq script)))
        pass (atom -1)
        reads (atom 0)
        passes (atom (vec (repeat (count script) [])))
        poll-loop @#'gamepad/poll-loop]
    (with-redefs-fn {#'gamepad/read-controllers (fn [_] (swap! reads inc) (nth script @pass))
                     #'gamepad/poll-interval-ms 0
                     #'gamepad/disabled-poll-interval-ms 0}
      (fn []
        (poll-loop nil
                   (fn [event]
                     (swap! passes update @pass conj event)
                     (dispatch event))
                   (fn []
                     (let [i (swap! pass inc)]
                       (when (= i (dec (count script)))
                         (reset! running false))
                       (not= :disabled (nth script i))))
                   running)))
    {:reads @reads :passes @passes}))

(defn- through-mapper
  "(f app-events) with the input mapper on the router and an empty fast
   store, as in the app, and both cleared after: app-events collects what
   the mapper sends the app's event pipeline."
  [f]
  (router/clear-handlers!)
  (store/clear-continuous-values!)
  (let [app-events (atom [])]
    (try
      (mapper/set-dispatch-fn! #(swap! app-events conj %))
      (mapper/start-mapper!)
      (f app-events)
      (finally
        (router/clear-handlers!)
        (mapper/set-dispatch-fn! nil)
        (store/clear-continuous-values!)))))

(defn- fields [event] ((juxt :type :source :channel :control :value) event))

(defn- axis [controller control value] [:control-change :gamepad controller control value])

(deftest slow-sweep-reaches-the-fast-store-test
  (testing "§7 case 1: a sweep in steps under the threshold is sent each time it is past it
            from the value last sent"
    (through-mapper
     (fn [app-events]
       (let [writes (atom 0)
             k ::writes]
         (add-watch (state/get-context-atom) k (fn [_ _ _ _] (swap! writes inc)))
         (try
           (let [{:keys [reads passes]} (run-scripted-polls
                                         (for [x [0.500 0.502 0.504 0.506 0.508 0.510 0.512]]
                                           {0 (ctrl {} {:left-x x})})
                                         router/dispatch!)]
             (is (= [[] [] [] [(axis 0 :left-x 0.506)] [] [] [(axis 0 :left-x 0.512)]]
                    (mapv #(mapv fields %) passes)))
             (is (= 7 reads) "one read a pass")
             (is (= {[:gamepad 0 :left-x] 0.512} (store/get-all-continuous-values)) "the fast store has where the stick is")
             (is (= [] @app-events) "nothing for the app's event pipeline")
             (is (zero? @writes) "and no app state written"))
           (finally
             (remove-watch (state/get-context-atom) k))))))))

(deftest jitter-is-relative-to-each-emitted-axis-test
  (through-mapper
   (fn [_]
     (let [pad #(ctrl {} %)
           {:keys [passes]}
           (run-scripted-polls
            [{0 (pad {:left-x 0.600 :trigger-left 0.0}) 1 (pad {})}
             {0 (pad {:left-x 0.604 :trigger-left 0.0}) 1 (pad {:left-x 0.503})}
             {0 (pad {:left-x 0.596 :trigger-left 0.0 :right-x 0.497}) 1 (pad {:left-x 0.506})}
             {0 (pad {:left-x 0.604 :trigger-left 0.005 :right-x 0.494}) 1 (pad {:left-x 0.502})}
             {0 (pad {:left-x 0.600 :trigger-left 0.0051 :right-x 0.494}) 1 (pad {:left-x 0.502})}
             {0 (pad {:left-x 0.600 :trigger-left 0.0051 :right-x 0.494}) 1 (pad {:left-x 0.502})}]
            router/dispatch!)
           sent (mapv #(mapv fields %) passes)]
       (is (= [(axis 0 :left-x 0.6) (axis 0 :trigger-left 0.0)] (sent 0))
           "connected: controller 0's stick at 0.600 and its trigger at rest are sent")
       (testing "§7 case 2: 0's stick jitters round 0.600, 0.008 between readings, and is never
                 sent; each other axis is, once past the threshold from its own last value"
         (is (= [[] [(axis 1 :left-x 0.506)] [(axis 0 :right-x 0.494)] [(axis 0 :trigger-left 0.0051)] []]
                (subvec sent 1))
             "1's stick at 0.506 though 0's stick was sent at 0.600; the trigger 0.005 from its 0.0 is not past it, 0.0051 is")
         (is (= {[:gamepad 0 :left-x] 0.6 [:gamepad 0 :right-x] 0.494 [:gamepad 0 :trigger-left] 0.0051
                 [:gamepad 1 :left-x] 0.506}
                (store/get-all-continuous-values))
             "the store holds what was last sent: 0's stick still at 0.600"))))))

(deftest disconnect-and-disable-forget-only-poll-memory-test
  (let [held {1 {:buttons {:b true} :axes {:trigger-left 0.0}}}
        back {1 {:buttons {:b false} :axes {:trigger-left 0.0}}}
        summary (fn [e] (if (= :control-change (:type e))
                          ((juxt :type :channel :control :value) e)
                          ((juxt :type :id) e)))]
    (doseq [[label gone reads] [["unplugged twice" {} 5] ["switched off twice" :disabled 3]]]
      (testing (str "§7 case 3, " label ", the B button held and the trigger at rest")
        (through-mapper
         (fn [app-events]
           (store/set-continuous-value! [:gamepad 0 :left-x] 0.3)
           (let [result (run-scripted-polls [held held gone gone back] router/dispatch!)]
             (is (= [[[:trigger [1 :b]] [:control-change 1 :trigger-left 0.0]]
                     []
                     [[:trigger-release [1 :b]]]
                     []
                     [[:control-change 1 :trigger-left 0.0]]]
                    (mapv #(mapv summary %) (:passes result)))
                 "held: no second press; gone: one release, no axis sent; back: its trigger sent afresh, at the value it had")
             (is (= reads (:reads result)) "no read while switched off")
             (is (= [[:trigger [1 :b]] [:trigger-release [1 :b]]]
                    (mapv (comp (juxt :type :id) :input-event) @app-events))
                 "the app's pipeline gets the press and its release")
             (is (= {[:gamepad 0 :left-x] 0.3 [:gamepad 1 :trigger-left] 0.0} (store/get-all-continuous-values))
                 "the store keeps every value: another controller's, and the gone one's last"))))))))

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
