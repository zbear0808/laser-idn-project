(ns laser-show.ui-test.reentry-test
  "Results that background effects deliver later (a future, a timer) are
   handled on the event agent, like every other event, and not on the thread
   that produced them."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.connection :as connection]
            [laser-show.events.handlers.ui :as ui-handlers]
            [laser-show.events.helpers :as h]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- agent-thread? [thread-name]
  (= events/event-thread-name thread-name))

(deftest lifecycle-results-run-on-the-agent-test
  (let [threads (atom [])
        handle connection/handle
        status-handled? (fn [pred] (some (fn [[t _ e]] (and (= :output/status t) (pred e))) @threads))]
    (with-redefs [connection/handle (fn [event]
                                      (swap! threads conj [(:event/type event)
                                                           (.getName (Thread/currentThread))
                                                           event])
                                      (handle event))]
      ;; Nothing to stream: the output runtime still publishes that it's on
      (events/dispatch! {:event/type :idn/start-multi-streaming})
      (ui/wait-for! #(status-handled? (comp false? :pending? :status)))
      (events/dispatch! {:event/type :idn/stop-multi-streaming})
      (ui/wait-for! #(status-handled? (comp nil? :status))))
    (is (status-handled? #(= {:engines 0 :wanted 0 :pending? false :projectors {}} (:status %)))
        "the status the output thread published")
    (is (nil? (get-in (state/get-raw-state) [:backend :streaming :status])) "stopped")
    (is (every? (comp agent-thread? second) @threads) (pr-str (mapv pop @threads)))))

(deftest dispatch-later-runs-on-the-agent-test
  (let [expired (promise)
        handle ui-handlers/handle
        text "Using the backup rig"]
    (with-redefs [h/notice-ms 10
                  ui-handlers/handle (fn [{:keys [event/type id state] :as event}]
                                       (when (and (= :ui/clear-notice type) (= id (get-in state [:ui :notice :id])))
                                         (deliver expired [(get-in state [:ui :notice :text]) (.getName (Thread/currentThread))]))
                                       (handle event))]
      (events/dispatch! {:event/type :rig/startup-notice :text text})
      (is (= [text events/event-thread-name] (deref expired 3000 ::timeout)))
      (ui/wait-for! #(nil? (get-in (state/get-raw-state) [:ui :notice]))))))
