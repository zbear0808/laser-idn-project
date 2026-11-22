(ns laser-show.ui-test.reentry-test
  "Results that background effects deliver later (a future, a timer) are
   handled on the event agent, like every other event, and not on the thread
   that produced them."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.chain :as chain]
            [laser-show.events.handlers.connection :as connection]
            [laser-show.events.handlers.keyframe :as keyframe]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- agent-thread? [thread-name]
  (= events/event-thread-name thread-name))

(defn- recording-threads
  "Wrap handle so it records [event-type thread-name] into threads."
  [handle threads]
  (fn [event]
    (swap! threads conj [(:event/type event) (.getName (Thread/currentThread))])
    (handle event)))

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
  (let [threads (atom [])
        entity-key [0 0]]
    (state/swap-state! assoc-in [:chains :effect-chains entity-key]
                       {:items [{:effect-id :scale
                                 :params {:x-scale 1.0}
                                 :keyframe-modulator {:enabled? true}}]})
    (with-redefs [chain/handle (recording-threads chain/handle threads)
                  keyframe/handle (recording-threads keyframe/handle threads)]
      (events/dispatch! {:event/type :modulator/toggle
                         :domain :effect-chains
                         :entity-key entity-key
                         :effect-path [0]
                         :param-key :x-scale
                         :param-spec {:min 0.0 :max 2.0 :default 1.0 :type :float}
                         :current-value 1.0
                         :fx/event true})
      (ui/wait-for! #(some (fn [[t]] (= :chain/update-param t)) @threads)))
    (is (= [:keyframe/toggle-enabled :chain/update-param] (mapv first @threads)))
    (is (every? (comp agent-thread? second) @threads) (pr-str @threads))
    (is (some? (get-in (state/get-raw-state)
                       [:chains :effect-chains entity-key :items 0 :params :x-scale :type])))))
