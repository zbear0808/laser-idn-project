(ns laser-show.backend.output-lifecycle-test
  "Multi-engine streaming started and stopped through the event pipeline, with
   projectors pointed at the fake DAC."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.app :as app]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.events.core :as events]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.idn.hello :as hello]
            [laser-show.idn.stream :as stream]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))

(def ^:dynamic *dac* nil)

(defn- with-fake-dac [f]
  (let [fake (dac/start!)]
    (try
      (binding [*dac* fake] (f))
      (finally
        (dac/stop! fake)))))

(defn- stopping-every-engine
  "Record every engine started during the test and stop them all afterwards,
   so a failing test can't leave a sender running."
  [f]
  (let [started (atom [])
        start! se/start!]
    (try
      (with-redefs [se/start! (fn [engine]
                                (swap! started conj engine)
                                (start! engine))]
        (f))
      (finally
        (run! se/stop! @started)))))

(use-fixtures :each with-fake-dac stopping-every-engine)

(defn- projector [service-id enabled?]
  {:name (str "Fake " service-id) :host "127.0.0.1" :port (:port *dac*)
   :service-id service-id :enabled? enabled? :zone-groups [:all]
   :output-config {:color-bit-depth 8 :xy-bit-depth 16}})

(defn- init-with-projectors! [projectors]
  (state/init-state! (assoc (domains/build-initial-state) :projectors projectors)))

(defn- dispatch! [event]
  (events/event-handler event))

(defn- senders-lately []
  (dac/senders *dac* :since-ns (dac/ms-ago 150)))

(defn- wait-for-senders! [n]
  (dac/wait-for! #(= n (count (senders-lately))) 3000))

(defn- closed-ports
  "Source ports whose last two messages are a channel close and an RT close."
  []
  (->> (dac/messages *dac*)
       (group-by :port)
       (keep (fn [[port msgs]]
               (let [[close rt-close] (take-last 2 msgs)]
                 (when (and (:close? close)
                            (= stream/CHUNK_TYPE_VOID (:chunk-type close))
                            (= hello/CMD_RT_CLOSE (:cmd rt-close)))
                   port))))
       set))

(defn- stop-streaming! []
  (let [ports (dac/senders *dac*)]
    (dispatch! {:event/type :idn/stop-multi-streaming})
    (dac/wait-for! #(= ports (closed-ports)) 3000)))


(deftest start-streams-every-enabled-projector-test
  (init-with-projectors! {:a (projector 1 true) :b (projector 2 true) :c (projector 3 false)})
  (dispatch! {:event/type :idn/start-multi-streaming})
  (is (wait-for-senders! 2) "one sender per enabled projector")
  (is (= #{1 2} (set (map :channel-id (dac/frames *dac* :since-ns (dac/ms-ago 150)))))
      "the disabled projector's channel stays silent")
  (is (stop-streaming!)))

(deftest stop-closes-every-engine-test
  (init-with-projectors! {:a (projector 1 true) :b (projector 2 true)})
  (dispatch! {:event/type :idn/start-multi-streaming})
  (is (wait-for-senders! 2))
  (is (stop-streaming!) "every engine ends with a channel close then an RT close")
  (is (dac/quiet? *dac* 300) "nothing arrives after Stop"))

(deftest two-services-two-channels-test
  (init-with-projectors! {:a (projector 1 true) :b (projector 2 true)})
  (dispatch! {:event/type :idn/start-multi-streaming})
  (is (wait-for-senders! 2))
  (let [by-port (group-by :port (dac/frames *dac*))]
    (is (= 2 (count by-port)) "two source ports")
    (is (= #{#{1} #{2}} (set (map (fn [[_ fs]] (set (map :channel-id fs))) by-port)))
        "each sender keeps to one channel, 1 or 2")
    (doseq [[_ fs] by-port
            :let [configs (filter :has-config? fs)]]
      (is (seq configs))
      (is (every? #(= (:channel-id %) (:service-id %)) configs)
          "the config names the projector's service on its channel")))
  (is (stop-streaming!)))

(deftest app-stop-closes-engines-test
  (testing "app/stop! sends the close pair for every engine in state"
    (init-with-projectors! {:a (projector 1 true) :b (projector 2 true)})
    (dispatch! {:event/type :idn/start-multi-streaming})
    (is (wait-for-senders! 2))
    (is (dac/wait-for! #(= 2 (count (get-in (state/get-raw-state)
                                            [:backend :streaming :multi-engine-state :engines])))
                       3000))
    (let [ports (dac/senders *dac*)]
      (app/stop!)
      (is (dac/wait-for! #(= ports (closed-ports)) 1000))
      (is (dac/quiet? *dac* 300)))))
