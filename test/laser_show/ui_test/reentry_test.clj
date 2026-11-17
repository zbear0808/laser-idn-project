(ns laser-show.ui-test.reentry-test
  "Results that background effects deliver later (a future, a timer) are
   handled on the event agent, like every other event, and not on the thread
   that produced them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
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
  (str/starts-with? thread-name "clojure-agent-send-pool-"))

(defn- recording-threads
  "Wrap handle so it records [event-type thread-name] into threads."
  [handle threads]
  (fn [event]
    (swap! threads conj [(:event/type event) (.getName (Thread/currentThread))])
    (handle event)))

(deftest lifecycle-results-run-on-the-agent-test
  (let [threads (atom [])]
    (with-redefs [connection/handle (recording-threads connection/handle threads)]
      (events/dispatch! {:event/type :idn/start-multi-streaming})
      (ui/wait-for! #(some (fn [[t]] (= :idn/multi-streaming-started t)) @threads)))
    (let [[_ thread-name] (first (filter #(= :idn/multi-streaming-started (first %)) @threads))]
      (is (agent-thread? thread-name) thread-name))
    (is (every? (comp agent-thread? second) @threads) (pr-str @threads))))

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
