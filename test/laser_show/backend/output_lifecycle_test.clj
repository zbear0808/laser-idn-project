(ns laser-show.backend.output-lifecycle-test
  "Multi-engine streaming started and stopped through the event pipeline, with
   projectors pointed at the fake DAC. The engines live in the output runtime
   (backend/output_runtime.clj); these tests replay the races of probes p4-p7
   and check its invariants (plans/2026-11-16-output-runtime/04 §7)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging :as log]
            [laser-show.app :as app]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.animation.show-time :as show-time]
            [laser-show.animation.types :as t]
            [laser-show.animation.fit-guard :as fit-guard]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.points]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.idn.driver :as idn-driver]
            [laser-show.idn.hello :as hello]
            [laser-show.idn.stream :as stream]
            [laser-show.output.driver :as driver]
            [laser-show.output.lsps-receiver :as rx]
            [laser-show.output.recording-driver :as rd]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.views.components.load-warning :as lw])
  (:import [java.net DatagramSocket UnknownHostException URL URLClassLoader]))

(def ^:dynamic *dac* nil)
(def ^:dynamic *started* nil)

(defn- with-fake-dac
  "Each test streams to its own fake DAC; afterwards everything that arrived
   is checked against the stream rules (fake-dac/check)."
  [f]
  (let [fake (dac/start!)]
    (try
      (binding [*dac* fake] (f))
      (finally
        (let [found (dac/violations fake)]
          (is (empty? found) (str "the stream breaks IDN rules: " (pr-str (take 5 found)))))
        (dac/stop! fake)))))

(defn- stopping-every-engine
  "Record every engine started during the test, and afterwards drain the
   output runtime and stop every recorded engine, so a failing test can't
   leave a sender running. The runtime is opened again for the next test,
   and show time is reset."
  [f]
  (let [started (atom [])
        start! se/start!]
    (output-runtime/open!)
    (output-errors/clear!)
    (try
      (with-redefs [se/start! (fn [engine]
                                (swap! started conj engine)
                                (start! engine))]
        (binding [*started* started]
          (f)))
      (finally
        (output-runtime/drain! 2000)
        (run! se/stop! @started)
        (output-runtime/open!)
        (output-errors/clear!)
        ;; A test that ran the clock leaves its last tick behind, which the
        ;; providers would call stale
        (show-time/reset-runtime!)))))

(use-fixtures :each with-fake-dac stopping-every-engine)

(defn- projector
  ([service-id enabled?] (projector service-id enabled? (:port *dac*)))
  ([service-id enabled? port]
   {:name (str "Fake " service-id) :host "127.0.0.1" :port port
    :service-id service-id :enabled? enabled? :zone-groups [:all]
    :output-config {:color-bit-depth 8 :xy-bit-depth 16}}))

(defn- init-with-projectors! [projectors]
  (state/init-state! (assoc (domains/build-initial-state) :projectors projectors)))

(defn- dispatch! [event]
  (events/event-handler event))

(defn- senders-lately []
  (dac/senders *dac* :since-ns (dac/ms-ago 150)))

(defn- wait-for-senders! [n]
  (dac/wait-for! #(= n (count (senders-lately))) 3000))

(defn- settled?
  "True once the output thread has nothing queued or running."
  []
  (not (:pending? (output-runtime/status))))

(defn- wait-settled! []
  (dac/wait-for! settled? 3000))

(defn- closed-ports
  "Source ports whose last two messages are a channel close and an RT close."
  ([] (closed-ports *dac*))
  ([fake]
   (->> (dac/messages fake)
        (group-by :port)
        (keep (fn [[port msgs]]
                (let [[close rt-close] (take-last 2 msgs)]
                  (when (and (:close? close)
                             (= stream/CHUNK_TYPE_VOID (:chunk-type close))
                             (= hello/CMD_RT_CLOSE (:cmd rt-close)))
                    port))))
        set)))

(defn- every-sender-closed?
  "True when every engine that sent a frame has sent its close pair."
  []
  (= (dac/senders *dac*) (closed-ports)))

(defn- stop-streaming!
  "Stop, and wait until the registry is empty and every sender has closed."
  []
  (dispatch! {:event/type :idn/stop-multi-streaming})
  (and (dac/wait-for! #(empty? (output-runtime/registry)) 3000)
       (dac/wait-for! every-sender-closed? 1000)))

(defn- live-objects
  "Threads, sockets and engines anywhere in app state (invariant 4)."
  []
  (filter #(or (instance? Thread %)
               (instance? DatagramSocket %)
               (and (map? %) (contains? % :frame-provider)))
          (tree-seq coll? seq (state/get-raw-state))))

(defn- slowed-start!
  "se/start! (the fixture's recording one) after a delay of ms, delivering
   entered when the first call begins."
  [ms entered]
  (let [start! se/start!]
    (fn [engine]
      (deliver entered true)
      (Thread/sleep (long ms))
      (start! engine))))


;; Start and stop

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
  (is (dac/quiet? *dac* 300) "nothing arrives after Stop")
  (is (empty? (output-runtime/registry))))

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


;; The races of probes p4-p7

(deftest double-start-gives-one-sender-per-projector-test
  (init-with-projectors! {:a (projector 1 true)})
  (dispatch! {:event/type :idn/start-multi-streaming})
  (dispatch! {:event/type :idn/start-multi-streaming})
  (is (wait-for-senders! 1))
  (is (wait-settled!))
  (is (= 1 (count @*started*)) "the second sync finds nothing to change")
  (is (= 1 (count (senders-lately))))
  (is (stop-streaming!))
  (is (dac/quiet? *dac* 300)))

(deftest stop-during-start-leaves-nothing-streaming-test
  (init-with-projectors! {:a (projector 1 true)})
  (let [entered (promise)]
    (with-redefs [se/start! (slowed-start! 150 entered)]
      (dispatch! {:event/type :idn/start-multi-streaming})
      (is (deref entered 3000 false) "the start is under way")
      (dispatch! {:event/type :idn/stop-multi-streaming})
      (is (dac/wait-for! #(empty? (output-runtime/registry)) 3000))))
  (is (dac/wait-for! every-sender-closed? 1000) "the engine that started got its close pair")
  (is (dac/quiet? *dac* 300) "nothing streams after Stop")
  (is (false? (get-in (state/get-raw-state) [:backend :streaming :running?]))
      "the button offers Stream, and nothing streams"))

(deftest start-during-stop-gives-one-engine-set-test
  (init-with-projectors! {:a (projector 1 true) :b (projector 2 true)})
  (dispatch! {:event/type :idn/start-multi-streaming})
  (is (wait-for-senders! 2))
  (let [first-ports (dac/senders *dac*)
        entered (promise)
        stop-all! se/stop-all!]
    (with-redefs [se/stop-all! (fn [engines]
                                 (deliver entered true)
                                 (Thread/sleep 150)
                                 (stop-all! engines))]
      (dispatch! {:event/type :idn/stop-multi-streaming})
      (is (deref entered 3000 false) "the stop is under way")
      (dispatch! {:event/type :idn/start-multi-streaming})
      (is (wait-settled!)))
    (is (wait-for-senders! 2))
    (is (dac/wait-for! #(= first-ports (closed-ports)) 1000) "the first set was closed")
    (is (empty? (filter first-ports (senders-lately))) "and stays closed")
    (is (= 4 (count @*started*)) "then one new engine per projector")
    (is (= #{:a :b} (set (keys (output-runtime/registry))))))
  (is (stop-streaming!)))

(deftest quick-enables-start-each-projector-once-test
  (init-with-projectors! {:a (projector 1 true) :b (projector 2 false) :c (projector 3 false)})
  (dispatch! {:event/type :idn/start-multi-streaming})
  (is (wait-for-senders! 1))
  (with-redefs [se/start! (slowed-start! 100 (promise))]
    (dispatch! {:event/type :projectors/toggle-enabled :projector-id :b})
    (dispatch! {:event/type :projectors/toggle-enabled :projector-id :c})
    (is (wait-settled!)))
  (is (wait-for-senders! 3))
  (let [per-channel (->> (dac/frames *dac* :since-ns (dac/ms-ago 150))
                         (group-by :channel-id)
                         (map (fn [[ch fs]] [ch (count (set (map :port fs)))]))
                         (into (sorted-map)))]
    (is (= {1 1 2 1 3 1} per-channel) "one sender per channel"))
  (is (= 3 (count @*started*)) "one engine each for A, B and C")
  (is (stop-streaming!))
  (is (dac/quiet? *dac* 300)))

(deftest concurrent-event-cannot-lose-engines-test
  (init-with-projectors! {:a (projector 1 true)})
  (let [handle timing/handle
        snapshot-taken (promise)]
    (with-redefs [timing/handle (fn [event]
                                  (deliver snapshot-taken true)
                                  (Thread/sleep 300)
                                  (handle event))]
      ;; An event that took its state snapshot before Stream was handled, and
      ;; writes it back after the engines started
      (let [other (future (dispatch! {:event/type :timing/set-bpm :bpm 128.0}))]
        (is (deref snapshot-taken 3000 false))
        (dispatch! {:event/type :idn/start-multi-streaming})
        (is (wait-for-senders! 1))
        @other)))
  (is (= 1 (count (output-runtime/registry))) "the registry still holds the engine")
  (is (empty? (live-objects)) "app state never holds a thread, socket or engine")
  (is (stop-streaming!) "Stop still reaches it")
  (is (dac/quiet? *dac* 300)))


;; Projector edits while streaming

(deftest host-edit-restarts-the-engine-test
  (let [other (dac/start!)]
    (try
      (init-with-projectors! {:a (projector 1 true) :b (projector 2 true)})
      (dispatch! {:event/type :idn/start-multi-streaming})
      (is (wait-for-senders! 2))
      (let [[a-port] (map :port (filter #(= 1 (:channel-id %))
                                        (dac/frames *dac* :since-ns (dac/ms-ago 150))))
            b-ports (set (map :port (filter #(= 2 (:channel-id %)) (dac/frames *dac*))))]
        (testing "a port edit closes the old engine and starts one at the new address"
          (dispatch! {:event/type :projectors/update-settings :projector-id :a
                      :updates {:port (:port other)}})
          (is (dac/wait-for! #(contains? (closed-ports) a-port) 3000)
              "close pair to the old address")
          (is (dac/wait-for! #(seq (dac/senders other :since-ns (dac/ms-ago 150))) 3000)
              "the new engine streams to the new address"))
        (testing "a host edit does the same"
          (let [before (count @*started*)
                old-ports (dac/senders other)]
            (dispatch! {:event/type :projectors/update-settings :projector-id :a
                        :updates {:host "localhost"}})
            (is (wait-settled!))
            (is (= (inc before) (count @*started*)))
            (is (dac/wait-for! #(every? (closed-ports other) old-ports) 3000)
                "the engine for the old host was closed")))
        (is (dac/wait-for! #(= b-ports (senders-lately)) 1000)
            "the other projector kept its engine")
        (is (= 1 (count (closed-ports))) "only A's first engine was closed here"))
      (is (stop-streaming!))
      (is (empty? (dac/violations other)) "the new address got a valid stream too")
      (finally
        (dac/stop! other)))))

(deftest zone-group-edit-restarts-nothing-test
  (init-with-projectors! {:a (projector 1 true) :b (projector 2 true)})
  (state/swap-state! assoc-in [:zone-groups :left] {:id :left :name "Left"})
  (dispatch! {:event/type :idn/start-multi-streaming})
  (is (wait-for-senders! 2))
  (let [ports (senders-lately)]
    (dispatch! {:event/type :projectors/toggle-zone-group :projector-id :a :zone-group-id :left})
    (dispatch! {:event/type :projectors/update-settings :projector-id :a :updates {:name "Renamed"}})
    (testing "zones plan Z3: nor do the fit guard, the safety limit or the rig's order"
      (dispatch! {:event/type :projectors/set-fit :projector-id :a :key :min-fps :value 30})
      (dispatch! {:event/type :projectors/set-safety :projector-id :a :safety {:limit [-0.5 -0.5 0.5 0.5]}})
      (dispatch! {:event/type :rig/move-projector :projector-id :a :target :b :side :before}))
    (is (= [:all :left] (get-in (state/get-raw-state) [:projectors :a :zone-groups])))
    (is (= 30 (get-in (state/get-raw-state) [:projectors :a :fit :min-fps])))
    (is (= [:a :b] (get-in (state/get-raw-state) [:rig :order])) "Renamed came after Fake 2 by name; moved back first")
    (is (wait-settled!))
    (is (= 2 (count @*started*)) "no engine was started")
    (is (= #{} (closed-ports)) "and none was closed")
    (is (= ports (senders-lately))))
  (is (stop-streaming!)))


;; Failures

(defn- unresolvable
  "A target-address! that fails for host as a DNS lookup would."
  [host]
  (let [resolve! @#'idn-driver/target-address!]
    (fn [engine]
      (if (= host (:target-host engine))
        (throw (UnknownHostException. (str host ": nodename nor servname provided")))
        (resolve! engine)))))

(deftest failed-start-is-closed-and-reported-test
  (init-with-projectors! {:a (projector 1 true) :b (projector 2 true)})
  (let [start! se/start!]
    (with-redefs [se/start! (fn [engine]
                              (if (= :b (:id engine))
                                (throw (ex-info "No buffer space available" {}))
                                (start! engine)))]
      (dispatch! {:event/type :idn/start-multi-streaming})
      (is (wait-for-senders! 1))
      (is (wait-settled!))))
  (is (= #{:a} (set (keys (output-runtime/registry)))) "the failed engine isn't kept")
  (is (= {:state :error :error "No buffer space available"}
         (get-in (output-runtime/status) [:projectors :b])))
  (is (= #{1} (set (map :channel-id (dac/frames *dac* :since-ns (dac/ms-ago 150))))))
  (is (stop-streaming!)))

(deftest status-reports-engines-and-errors-test
  (init-with-projectors! {:a (projector 1 true)
                          :b (assoc (projector 2 true) :host "unresolvable.test")
                          :c (projector 3 false)})
  (let [status #(get-in (state/get-raw-state) [:backend :streaming :status])
        writes (atom 0)
        watch-key ::status-writes]
    (add-watch (state/get-context-atom) watch-key
               (fn [_ _ old new]
                 (when-not (= (get-in (:cljfx.context/m old) [:backend :streaming :status])
                              (get-in (:cljfx.context/m new) [:backend :streaming :status]))
                   (swap! writes inc))))
    (try
      (with-redefs-fn {#'idn-driver/target-address! (unresolvable "unresolvable.test")}
        (fn []
          (dispatch! {:event/type :idn/start-multi-streaming})
          (is (dac/wait-for! #(and (= :streaming (get-in (status) [:projectors :a :state]))
                                   (= :error (get-in (status) [:projectors :b :state])))
                             3000)
              (pr-str (status)))
          (let [s (status)]
            (is (= 2 (:engines s)))
            (is (= 2 (:wanted s)))
            (is (false? (:pending? s)))
            (is (pos? (get-in s [:projectors :a :fps])))
            (is (re-find #"UnknownHostException: unresolvable.test"
                         (get-in s [:projectors :b :error])))
            (is (not (contains? (:projectors s) :c)) "disabled projectors aren't listed"))
          (testing "at most 4 writes a second"
            (let [before @writes]
              (Thread/sleep 1000)
              (is (<= (- @writes before) 5))))
          (is (empty? (live-objects)))
          (dispatch! {:event/type :idn/stop-multi-streaming})
          (is (dac/wait-for! #(nil? (status)) 3000)
              "once the registry is empty the status says stopped")))
      (finally
        (remove-watch (state/get-context-atom) watch-key)))))

(deftest status-shows-the-fps-the-projector-draws-test
  (init-with-projectors! {:a (assoc (projector 1 true) :scan-rate 25000)})
  (let [status #(get-in (state/get-raw-state) [:backend :streaming :status :projectors :a])
        lit (vec (for [i (range 1000)] [(- (/ (double i) 1000) 0.5) 0.0 1.0 1.0 1.0]))]
    (with-redefs-fn {#'me/projector-frame (constantly lit)}
      (fn []
        (dispatch! {:event/type :idn/start-multi-streaming})
        (is (dac/wait-for! #(:scan-limited? (status)) 3000) (pr-str (status)))
        (let [{:keys [state fps]} (status)]
          (is (= :streaming state))
          (is (< 24.0 fps 26.0) "1 001 samples at 25 000 pts/s: 25 fps"))
        (is (stop-streaming!))))))

(deftest status-carries-each-lasers-load-test
  (testing "zones plan Z3: each laser's load, rounded, and whether it is low, at most 4 writes a second"
    (init-with-projectors! {:a (assoc (projector 1 true) :scan-rate 25000)
                            :b (assoc (projector 2 true) :scan-rate 25000)})
    (let [status #(get-in (state/get-raw-state) [:backend :streaming :status :projectors %])
          heavy (vec (for [i (range 3000)] [(* 0.5 (Math/cos (* i 0.002))) (* 0.5 (Math/sin (* i 0.002))) 1.0 1.0 1.0]))
          {thinned :frame record :fit} (fit-guard/guard heavy 25000 fit-guard/defaults)
          frames {:a (with-meta thinned {:fit (assoc record :regions 0)})
                  :b [[0.0 0.0 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]]}
          writes (atom 0)
          watch-key ::load-writes]
      (add-watch (state/get-context-atom) watch-key
                 (fn [_ _ old new]
                   (when-not (= (get-in (:cljfx.context/m old) [:backend :streaming :status])
                                (get-in (:cljfx.context/m new) [:backend :streaming :status]))
                     (swap! writes inc))))
      (try
        (with-redefs-fn {#'me/projector-frame (fn [id _] (get frames id))}
          (fn []
            (dispatch! {:event/type :idn/start-multi-streaming})
            (is (dac/wait-for! #(:load (status :a)) 3000) (pr-str (status :a)))
            (is (= {:points-in 3000 :points 1800 :budget 1250 :min-fps 20 :scan-rate 25000 :est-fps 8 :guard-fps 14
                    :reduced-pct 40 :dim-pct 0 :regions 0}
                   (:load (status :a)))
                "whole fps and percent")
            (is (true? (:low? (status :a))) "13.9 fps after the guard, under its 20")
            (is (= :streaming (:state (status :a))))
            (is (dac/wait-for! #(= :streaming (:state (status :b))) 3000))
            (is (not (contains? (status :b) :load)) "a frame with no record: no load")
            (testing "a steady load writes nothing more"
              (let [before @writes]
                (Thread/sleep 1000)
                (is (<= (- @writes before) 5))))
            (is (stop-streaming!))))
        (finally
          (remove-watch (state/get-context-atom) watch-key))))))

(deftest status-load-counts-what-decimate-left-test
  (testing "Q-D-1 B: with Decimate in a laser's calibration chain, its meter counts what Decimate left; without, what the laser is asked for"
    (init-with-projectors! {:a (assoc (projector 1 true) :scan-rate 25000)
                            :b (assoc (projector 2 true) :scan-rate 25000)})
    (let [status #(get-in (state/get-raw-state) [:backend :streaming :status :projectors %])
          heavy (vec (for [i (range 3000)] [(* 0.5 (Math/cos (* i 0.002))) (* 0.5 (Math/sin (* i 0.002))) 1.0 1.0 1.0]))
          {thinned :frame record :fit} (fit-guard/guard heavy 25000 fit-guard/defaults)
          ;; As multi-engine/show-frame makes them: the guard, then the laser's
          ;; projector effects, and the record counted again when they changed the count
          decimated (effects/apply-effect-chain thinned {:effects [{:id (random-uuid) :effect-id :decimate :enabled? true
                                                                     :params {:kpps 25 :fps 30}}]})
          frames {:a (with-meta decimated {:fit (assoc (fit-guard/recount record (count decimated)) :regions 0)})
                  :b (with-meta thinned {:fit (assoc record :regions 0)})}]
      (with-redefs-fn {#'me/projector-frame (fn [id _] (get frames id))}
        (fn []
          (dispatch! {:event/type :idn/start-multi-streaming})
          (is (dac/wait-for! #(and (:load (status :a)) (:load (status :b))) 3000) (pr-str (status :a)))
          (is (= {:points-in 3000 :points 830 :effect-points 833 :budget 1250 :min-fps 20 :scan-rate 25000 :est-fps 8
                  :guard-fps 30 :reduced-pct 72 :dim-pct 0 :regions 0}
                 (:load (status :a)))
              "the count Decimate left as it is: it is its budget, so it doesn't flicker")
          (is (= 67 (lw/load-percent (:load (status :a)))) "833 of its 1,250, green")
          (is (false? (:low? (status :a))) "30 fps")
          (is (not (contains? (:load (status :b)) :effect-points)))
          (is (= 240 (lw/load-percent (:load (status :b)))) "without Decimate: the 3,000 it is asked for, as before (Q-Z3-4 A)")
          (is (stop-streaming!)))))))


(deftest safety-error-shows-in-status-test
  (init-with-projectors! {:a (projector 1 true)})
  (let [status #(get-in (state/get-raw-state) [:backend :streaming :status :projectors :a])
        lit [[0.0 0.0 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]]]
    (try
      (with-redefs-fn {#'me/projector-frame (constantly lit)}
        (fn []
          (dispatch! {:event/type :idn/start-multi-streaming})
          (is (dac/wait-for! #(= :streaming (:state (status))) 3000) (pr-str (status)))
          (output-errors/report! [:safety :a] (ex-info "test: zone unreadable" {}))
          (is (dac/wait-for! #(= :safety (:kind (status))) 3000) (pr-str (status)))
          (is (= {:state :error :kind :safety :error "Safety error: output off"}
                 (select-keys (status) [:state :kind :error])))
          (is (re-find #"test: zone unreadable" (:detail (status))))
          (output-errors/ok! [:safety :a])
          (is (dac/wait-for! #(= :streaming (:state (status))) 3000) "and it streams again once it works")
          (is (stop-streaming!))))
      (finally
        (output-errors/ok! [:safety :a])))))

(deftest skipped-calibration-shows-as-a-warning-test
  (init-with-projectors! {:a (projector 1 true)})
  (let [status #(get-in (state/get-raw-state) [:backend :streaming :status :projectors :a])
        lit [[0.0 0.0 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]]]
    (try
      (with-redefs-fn {#'me/projector-frame (constantly lit)}
        (fn []
          (dispatch! {:event/type :idn/start-multi-streaming})
          (is (dac/wait-for! #(= :streaming (:state (status))) 3000) (pr-str (status)))
          (output-errors/report! [:calibration :a] "Corner Pin skipped")
          (is (dac/wait-for! #(= "Corner Pin skipped" (:warning (status))) 3000) (pr-str (status)))
          (is (= :streaming (:state (status))) "it still streams")
          (output-errors/ok! [:calibration :a])
          (is (dac/wait-for! #(nil? (:warning (status))) 3000) "and the warning goes once it works")
          (is (stop-streaming!))))
      (finally
        (output-errors/ok! [:calibration :a])))))


;; Each engine on its own thread (invariant 6); stop signals all first (invariant 7)

(deftest slow-projector-does-not-delay-others-test
  (init-with-projectors! {:fast (projector 1 true) :slow (projector 2 true)})
  (let [threads (atom {})
        send-threads (atom #{})
        slow-entered (promise)
        release (promise)
        send-frame! @#'se/send-frame!]
    (with-redefs-fn {#'me/projector-frame
                     (fn [id _snapshot]
                       (swap! threads update id (fnil conj #{}) (.getName (Thread/currentThread)))
                       (when (= :slow id)
                         (deliver slow-entered true)
                         (deref release 3000 nil))
                       nil)
                     #'se/send-frame!
                     (fn [engine frame n]
                       (swap! send-threads conj (.getName (Thread/currentThread)))
                       (send-frame! engine frame n))}
      (fn []
        (try
          (dispatch! {:event/type :idn/start-multi-streaming})
          (is (deref slow-entered 3000 false))
          (let [since (System/nanoTime)
                fast-frames #(count (filter (fn [f] (= 1 (:channel-id f)))
                                            (dac/frames *dac* :since-ns since)))]
            (is (dac/wait-for! #(>= (fast-frames) 15) 3000)
                "the fast projector streams while the slow one's frame is still being computed"))
          (finally
            (deliver release true)))
        (is (stop-streaming!))))
    (is (= #{"idn-streaming-fast"} (get @threads :fast)))
    (is (= #{"idn-streaming-slow"} (get @threads :slow)))
    (is (= #{"idn-streaming-fast" "idn-streaming-slow"} @send-threads)
        "frames are computed and sent only on the engines' own threads")))

(defn- engine-taking [ms id]
  (se/create-engine "127.0.0.1"
                    (fn [] (Thread/sleep (long ms)) (t/empty-frame))
                    :id id :port (:port *dac*) :channel-id (case id :e1 1 :e2 2 :e3 3)))

(deftest stop-all-signals-every-engine-first-test
  (let [engines (mapv #(engine-taking 200 %) [:e1 :e2 :e3])
        order (atom [])
        close! @#'se/close!]
    (run! se/start! engines)
    (is (wait-for-senders! 3))
    (doseq [e engines]
      (add-watch (:running? e) ::order
                 (fn [_ _ was now]
                   (when (and was (not now)) (swap! order conj [:clear (:id e)])))))
    (with-redefs-fn {#'se/close! (fn [engine]
                                   (swap! order conj [:close (:id engine)])
                                   (close! engine))}
      #(se/stop-all! engines))
    (is (= #{:clear} (set (map first (take 3 @order))))
        "every flag is cleared before any engine is waited on and closed")
    (is (= [:close :close :close] (map first (drop 3 @order))))
    (is (dac/wait-for! every-sender-closed? 1000) "every close pair arrives")
    (is (dac/quiet? *dac* 300)))
  (testing "Stop on the output thread stops all engines in one stop-all!"
    (init-with-projectors! {:a (projector 1 true) :b (projector 2 true) :c (projector 3 true)})
    (dispatch! {:event/type :idn/start-multi-streaming})
    (is (wait-for-senders! 3))
    (let [calls (atom [])
          stop-all! se/stop-all!]
      (with-redefs [se/stop-all! (fn [engines]
                                   (swap! calls conj (count engines))
                                   (stop-all! engines))]
        (dispatch! {:event/type :idn/stop-multi-streaming})
        (is (dac/wait-for! #(empty? (output-runtime/registry)) 3000)))
      (is (= [3] @calls)))))

(deftest stop-does-not-wait-for-a-host-lookup-test
  (let [release (promise)
        e (se/create-engine "slow-dns.test" (fn [] (t/empty-frame)) :id :slow-dns :port (:port *dac*))]
    (with-redefs-fn {#'idn-driver/target-address! (fn [_] @release (throw (UnknownHostException. "slow-dns.test")))}
      (fn []
        (try
          (se/start! e)
          (Thread/sleep 50)
          (is (not= ::timeout (deref (future (se/stop-all! [e])) 3000 ::timeout))
              "stop-all! returns while the engine's host lookup is still blocked")
          (is (nil? @(:session e)) "the socket is closed")
          (finally
            (deliver release true)))))
    (is (dac/quiet? *dac* 100) "and nothing was sent to the never-resolved host")))

(deftest ^:timing slow-projector-rate-test
  (init-with-projectors! {:fast (projector 1 true) :slow (projector 2 true)})
  (with-redefs-fn {#'me/projector-frame (fn [id _snapshot] (when (= :slow id) (Thread/sleep 250)) nil)}
    (fn []
      (dispatch! {:event/type :idn/start-multi-streaming})
      (is (wait-for-senders! 2))
      (let [since (System/nanoTime)]
        (Thread/sleep 1000)
        (let [per-channel (frequencies (map :channel-id (dac/frames *dac* :since-ns since)))]
          (is (<= 15 (get per-channel 1 0)) "the fast projector at its own rate")
          (is (<= (get per-channel 2 0) 5) "the slow one at its provider's rate")))
      (is (stop-streaming!)))))

(deftest ^:timing every-projector-sends-every-tick-once-test
  (testing "with the show clock running, three projectors send each tick once, in the same order (c1)"
    (init-with-projectors! {:a (projector 1 true) :b (projector 2 true) :c (projector 3 true)})
    (state/swap-state! assoc-in [:chains :cue-chains [0 0]]
                       ;; Turning at constant speed, 0.75° a tick for 8 s: every tick's
                       ;; frame differs, so a frame's samples name its tick. A sine
                       ;; wouldn't do: it is symmetric about its peak and trough, so two
                       ;; ticks half a beat apart can send the same frame
                       {:destination-zone {:zone-group-id :all}
                        :items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true
                                 :effects [{:id (random-uuid) :effect-id :rotation :enabled? true
                                            :params {:angle {:type :sawtooth :min 0.0 :max 360.0 :period 16.0}}}]}]})
    (dispatch! {:event/type :grid/trigger-cell :col 0 :row 0})
    (show-clock/start!)
    (try
      (dispatch! {:event/type :idn/start-multi-streaming})
      (is (wait-for-senders! 3))
      (Thread/sleep 1000)
      (let [since (System/nanoTime)]
        (Thread/sleep 2000)
        (let [frames (filterv (comp seq :samples) (dac/frames *dac* :since-ns since))
              first-seen (reduce (fn [m {:keys [samples ns]}] (update m samples #(or % ns))) {} frames)]
          (doseq [[port fs] (group-by :port frames)
                  :let [samples (map :samples fs)
                        repeats (count (filter true? (map = samples (rest samples))))]]
            (is (<= 100 (count fs)) (str port ": " (count fs) " frames in 2 s"))
            (is (<= repeats (* 0.02 (count fs))) (str port ": " repeats " frames repeat the one before"))
            (is (apply <= (map first-seen (dedupe samples)))
                (str port ": the ticks come in the order every sender sends them")))))
      (is (stop-streaming!))
      (finally
        (show-clock/stop!)))))

(deftest ^:timing stop-all-takes-about-one-frame-test
  (let [engines (mapv #(engine-taking 200 %) [:e1 :e2 :e3])]
    (run! se/start! engines)
    (is (wait-for-senders! 3))
    (let [t0 (System/nanoTime)]
      (se/stop-all! engines)
      (is (< (/ (- (System/nanoTime) t0) 1e6) 450) "one after another takes about 600 ms"))
    (is (dac/wait-for! every-sender-closed? 1000))))


;; Drain, exit and shutdown

(deftest drain-closes-every-engine-test
  (init-with-projectors! {:a (projector 1 true) :b (projector 2 true) :c (projector 3 true)})
  (dispatch! {:event/type :idn/start-multi-streaming})
  (is (wait-for-senders! 3))
  (output-runtime/drain! 2000)
  (is (dac/wait-for! every-sender-closed? 1000))
  (is (empty? (output-runtime/registry)))
  (is (dac/quiet? *dac* 300))
  (testing "after drain! nothing starts an engine until open!"
    (dispatch! {:event/type :idn/start-multi-streaming})
    (is (dac/quiet? *dac* 300))
    (is (empty? (output-runtime/registry))))
  (output-runtime/drain! 2000)
  (testing "open! accepts commands again"
    (output-runtime/open!)
    (dispatch! {:event/type :idn/start-multi-streaming})
    (is (wait-for-senders! 3)))
  (is (stop-streaming!)))

(deftest drain-during-a-sync-closes-what-started-test
  (init-with-projectors! {:a (projector 1 true) :b (projector 2 true) :c (projector 3 true)})
  (let [entered (promise)]
    (with-redefs [se/start! (slowed-start! 150 entered)]
      (dispatch! {:event/type :idn/start-multi-streaming})
      (is (deref entered 3000 false))
      (output-runtime/drain! 2000)))
  (is (empty? (output-runtime/registry)))
  (is (<= (count @*started*) 1) "the sync stops starting engines once drain! begins")
  (is (dac/wait-for! every-sender-closed? 1000))
  (is (dac/quiet? *dac* 300)))

(deftest shutdown-hook-drains-test
  (init-with-projectors! {:a (projector 1 true) :b (projector 2 true)})
  (dispatch! {:event/type :idn/start-multi-streaming})
  (is (wait-for-senders! 2))
  (let [hook (output-runtime/install-shutdown-hook!)]
    (is (identical? hook (output-runtime/install-shutdown-hook!)) "registered once")
    (.run ^Thread hook))
  (is (dac/wait-for! every-sender-closed? 1000))
  (is (empty? (output-runtime/registry)))
  (is (dac/quiet? *dac* 300)))

(deftest app-stop-closes-engines-test
  (testing "app/stop! sends the close pair for every engine in the registry"
    (init-with-projectors! {:a (projector 1 true) :b (projector 2 true)})
    (dispatch! {:event/type :idn/start-multi-streaming})
    (is (wait-for-senders! 2))
    (is (dac/wait-for! #(= 2 (count (output-runtime/registry))) 3000))
    (let [ports (dac/senders *dac*)]
      (app/stop!)
      (is (dac/wait-for! #(= ports (closed-ports)) 1000))
      (is (dac/quiet? *dac* 300))))
  (testing "after app/stop! a dispatched Stream starts nothing"
    (events/dispatch! {:event/type :idn/start-multi-streaming})
    (is (dac/quiet? *dac* 300))
    (is (empty? (output-runtime/registry)))))


;; Drivers (plans/2026-11-19-output-drivers/01 §1.4, §5.5; 02 D1)

(defn- with-driver
  "Run f with rec's driver registered."
  [rec f]
  (driver/register! (:driver rec))
  (try
    (f)
    (finally
      (driver/unregister! (:id (:driver rec))))))

(defn- driver-projector [driver-id driver-config]
  {:name (str "Driver " driver-id) :driver driver-id :driver-config driver-config
   :enabled? true :zone-groups [:all]})

(defn- sends-of [rec]
  (count (filter #(= :send (first %)) @(:calls rec))))

(deftest stuck-driver-shows-not-responding-test
  (let [rec (rd/recording-driver {:id :test/stuck :block-send-ms {10 1500}})
        status #(get-in (output-runtime/status) [:projectors %])]
    (with-driver rec
      (fn []
        (init-with-projectors! {:a (projector 1 true) :stuck (driver-projector :test/stuck {})})
        (dispatch! {:event/type :idn/start-multi-streaming})
        (is (wait-for-senders! 1))
        (is (dac/wait-for! #(= {:state :error :kind :stuck :error "The output driver is not responding"} (status :stuck)) 3000)
            (pr-str (status :stuck)))
        (let [since (System/nanoTime)]
          (is (dac/wait-for! #(<= 10 (count (dac/frames *dac* :since-ns since))) 400)
              "the other projector keeps streaming meanwhile")
          (is (= 11 (sends-of rec)) "the stuck send is still running")
          (is (= :streaming (:state (status :a)))))
        (is (dac/wait-for! #(= :streaming (:state (status :stuck))) 3000)
            "it streams again once the send returns")
        (is (stop-streaming!))))))

(deftest blocking-close-does-not-hold-up-stop-test
  (let [rec (rd/recording-driver {:id :test/slow-close :block-close-ms 5000})
        slow (se/create-driver-engine (:driver rec) {} (constantly []) :id :slow-close)
        other (engine-taking 0 :e1)]
    (se/start! slow)
    (se/start! other)
    (is (wait-for-senders! 1))
    (is (dac/wait-for! #(pos? (sends-of rec)) 1000))
    (let [t0 (System/nanoTime)
          stopped (se/stop-all! [slow other])
          ms (/ (- (System/nanoTime) t0) 1e6)]
      (is (= 2 (count stopped)))
      (is (< ms (+ se/STOP_JOIN_MS se/CLOSE_MS 200)) (str "stop-all! took " ms " ms"))
      (is (<= (- se/CLOSE_MS 50) ms) "it waited for the close"))
    (is (= [:close] (peek @(:calls rec))) "the slow close was called")
    (is (some #(= "output-close-slow-close" (.getName ^Thread %)) (keys (Thread/getAllStackTraces)))
        "and is left behind")
    (is (dac/wait-for! every-sender-closed? 1000) "the other engine's close still ran")
    (is (dac/quiet? *dac* 300))))

(deftest missing-driver-reports-error-test
  (let [nova (driver-projector :acme/nova {:serial "NV-00123" :output 0})]
    (init-with-projectors! {:a (projector 1 true) :nova nova})
    (dispatch! {:event/type :idn/start-multi-streaming})
    (is (wait-for-senders! 1))
    (is (wait-settled!))
    (is (= {:state :error :error "Output driver :acme/nova is not installed"}
           (get-in (output-runtime/status) [:projectors :nova])))
    (is (= #{:a} (set (keys (output-runtime/registry)))) "the other projector streams")
    (is (= nova (get-in (state/get-raw-state) [:projectors :nova])) "the projector is kept as it is (OD-10)")
    (is (stop-streaming!))))

(deftest driver-config-change-restarts-the-engine-test
  (let [rec (assoc-in (rd/recording-driver {:id :test/config}) [:driver :fields]
                      [{:key :port :label "Port" :type :int :min 1 :max 65535}])
        opens-and-closes #(into [] (keep (fn [[kind config]]
                                           (case kind
                                             :open [:open config]
                                             :close [:close]
                                             nil)))
                                @(:calls rec))]
    (with-driver rec
      (fn []
        (init-with-projectors! {:a (projector 1 true) :r (driver-projector :test/config {:port 1})})
        (state/assoc-in-state! [:backend :output :drivers] (driver/descriptors))
        (dispatch! {:event/type :idn/start-multi-streaming})
        (is (wait-for-senders! 1))
        (is (dac/wait-for! #(pos? (sends-of rec)) 1000))
        (let [a-ports (senders-lately)]
          (dispatch! {:event/type :projectors/update-driver-config :projector-id :r :key :port :value "2"})
          (is (wait-settled!))
          (is (dac/wait-for! #(= [[:open {:port 1}] [:close] [:open {:port 2}]] (opens-and-closes)) 3000)
              "the old session is closed and one with the new config opened")
          (is (= 3 (count @*started*)) "one new engine, for :r")
          (is (= a-ports (senders-lately)) "the other projector kept its engine")
          (is (= #{} (closed-ports)) "and wasn't closed"))
        (is (stop-streaming!))))))

(deftest failing-open-is-reported-and-retried-test
  (let [rec (rd/recording-driver {:id :test/flaky :fail-open? #{0 1}})
        status #(get-in (output-runtime/status) [:projectors :flaky])]
    (with-driver rec
      (fn []
        (init-with-projectors! {:flaky (driver-projector :test/flaky {})})
        (dispatch! {:event/type :idn/start-multi-streaming})
        (is (dac/wait-for! #(= :error (:state (status))) 1000) (pr-str (status)))
        (is (re-find #"test: open \d failed" (str (:error (status)))) "the status says why")
        (is (zero? (sends-of rec)) "nothing is sent without a session")
        (is (dac/wait-for! #(= :streaming (:state (status))) 4000) "it streams once an open works")
        (let [opens (filterv #(= :open (first %)) @(:calls rec))]
          (is (= 3 (count opens)) "tried again after each failure"))
        (is (stop-streaming!))
        (is (= [:close] (peek @(:calls rec))))))))

(deftest stop-does-not-wait-for-a-blocking-open-test
  (let [rec (rd/recording-driver {:id :test/slow-open :block-open-ms {0 2000}})]
    (with-driver rec
      (fn []
        (init-with-projectors! {:a (projector 1 true) :slow (driver-projector :test/slow-open {})})
        (dispatch! {:event/type :idn/start-multi-streaming})
        (is (wait-for-senders! 1) "the other projector streams while the open blocks")
        (is (wait-settled!) "the lifecycle thread isn't held up by the open")
        (is (= #{:a :slow} (set (keys (output-runtime/registry)))))
        (let [t0 (System/nanoTime)]
          (dispatch! {:event/type :idn/stop-multi-streaming})
          (is (dac/wait-for! #(empty? (output-runtime/registry)) 3000))
          (let [ms (/ (- (System/nanoTime) t0) 1e6)]
            (is (< ms (+ se/STOP_JOIN_MS se/CLOSE_MS 200)) (str "Stop took " ms " ms"))))
        (is (dac/wait-for! every-sender-closed? 1000) "the other projector stopped normally")
        (is (dac/quiet? *dac* 300))
        (is (= [[:open]] (mapv (comp vector first) @(:calls rec))) "no session yet, so nothing to close")
        (is (dac/wait-for! #(= [:close] (peek @(:calls rec))) 2000)
            "the session that opened after Stop is closed")
        (is (zero? (sends-of rec)) "and never sent to")))))

(deftest stuck-open-shows-not-responding-test
  (let [rec (rd/recording-driver {:id :test/stuck-open :block-open-ms {0 1500}})
        status #(get-in (output-runtime/status) [:projectors :slow])]
    (with-driver rec
      (fn []
        (init-with-projectors! {:a (projector 1 true) :slow (driver-projector :test/stuck-open {})})
        (dispatch! {:event/type :idn/start-multi-streaming})
        (is (= {:state :starting} (status)) "an open in progress starts")
        (is (dac/wait-for! #(= {:state :error :kind :stuck :error "The output driver is not responding"} (status)) 3000)
            (pr-str (status)))
        (is (= :streaming (get-in (output-runtime/status) [:projectors :a :state])))
        (is (dac/wait-for! #(= :streaming (:state (status))) 3000) "it streams once the open returns")
        (is (stop-streaming!))))))


;; Contract additions from the first third-party add-on (01 §1, §1.4; 02 § 1D)

(defn- calls-of [kind rec]
  (filterv #(= kind (first %)) @(:calls rec)))

(defn- with-registered
  "Run f with driver d registered."
  [d f]
  (driver/register! d)
  (try
    (f)
    (finally
      (driver/unregister! (:id d)))))

(deftest driver-status-is-sanitized-into-the-status-test
  (let [release (promise)
        loader (URLClassLoader. (into-array URL []) (.getContextClassLoader (Thread/currentThread)))
        status-loaders (atom #{})
        rec (rd/recording-driver
             {:id :test/status
              :on-open (fn [_ _ _] (deref release 3000 nil))
              :status (fn [session]
                        (swap! status-loaders conj (.getContextClassLoader (Thread/currentThread)))
                        {:level :ok
                         :message "Fine"
                         :info {:scan-rate 30303 :buffer-ms 40.5 :interlock :closed :ok? true :none nil
                                :serial (apply str (repeat 300 "x"))
                                :config (:config session)
                                :ratio 1/4
                                :nested {:fifo [1 2 {:free 3}] "usb-path" "1-2"}
                                :callback inc :atom (atom 1) :nan ##NaN :inf ##Inf :set #{1} :list '(1 2)
                                :bytes (byte-array 2) :thread (Thread/currentThread) 'symbol-key 1
                                :deep {:a {:b {:c {:d 1}}}}}})})
        d (assoc (:driver rec) ::driver/loader loader)
        status #(get-in (output-runtime/status) [:projectors :s])]
    (with-registered d
      (fn []
        (init-with-projectors! {:s (driver-projector :test/status {})})
        (dispatch! {:event/type :idn/start-multi-streaming})
        (is (dac/wait-for! #(seq (calls-of :open rec)) 1000))
        (Thread/sleep (* 3 output-runtime/STATUS_INTERVAL_MS))
        (is (empty? (calls-of :status rec)) "not asked before there is a session")
        (deliver release true)
        (is (dac/wait-for! #(:driver-info (status)) 3000) (pr-str (status)))
        (is (= {:scan-rate 30303 :buffer-ms 40.5 :interlock :closed :ok? true :none nil
                :serial (apply str (repeat 200 "x"))
                :config {}
                :ratio 0.25
                :nested {:fifo [1 2 {:free 3}] "usb-path" "1-2"}
                :deep {:a {:b {}}}}
               (:driver-info (status)))
            "plain EDN only: functions, atoms, NaN, sets, lists, arrays, objects and symbol keys are dropped, strings cut to 200 characters, nesting to 4 levels")
        (is (= :streaming (:state (status))))
        (is (not (contains? (status) :warning)) ":ok's message isn't shown")
        (is (= (:driver-info (status)) (read-string (pr-str (:driver-info (status))))) "it round-trips as EDN")
        (is (= #{"output-status"} (set (map second (filter #(= :status (first %)) @(:threads rec)))))
            "asked on the output-status thread, never the streaming thread")
        (is (= #{loader} @status-loaders) "with the driver's own loader")
        (let [n (count (calls-of :status rec))]
          (Thread/sleep 1000)
          (is (<= 2 (- (count (calls-of :status rec)) n) 6) "about once per STATUS_INTERVAL_MS"))
        (is (stop-streaming!))
        (let [n (count (calls-of :status rec))]
          (Thread/sleep (* 2 output-runtime/STATUS_INTERVAL_MS))
          (is (= n (count (calls-of :status rec))) "not asked once the engine has stopped"))))))

(deftest driver-status-error-and-warning-levels-test
  (let [reported (atom {:level :warning :message "Interlock open"})
        rec (rd/recording-driver {:id :test/levels :status (fn [_] @reported)})
        status #(get-in (output-runtime/status) [:projectors :lv])
        sends #(count (calls-of :send rec))]
    (with-driver rec
      (fn []
        (try
          (init-with-projectors! {:lv (driver-projector :test/levels {})})
          (dispatch! {:event/type :idn/start-multi-streaming})
          (testing ":warning keeps the streaming state and adds the message"
            (is (dac/wait-for! #(= "Interlock open" (:warning (status))) 3000) (pr-str (status)))
            (is (= :streaming (:state (status))))
            (is (pos? (:fps (status)))))
          (testing "next to a skipped calibration effect's warning"
            (output-errors/report! [:calibration :lv] "Corner Pin skipped")
            (is (dac/wait-for! #(= "Corner Pin skipped; Interlock open" (:warning (status))) 3000) (pr-str (status)))
            (output-errors/ok! [:calibration :lv]))
          (testing ":error makes the state :error with the message, and :info is still there"
            (reset! reported {:level :error :message "E-stop active" :info {:estop? true}})
            (is (dac/wait-for! #(= {:state :error :error "E-stop active" :driver-info {:estop? true}} (status)) 3000)
                (pr-str (status)))
            (let [n (sends)]
              (is (dac/wait-for! #(< (+ n 5) (sends)) 1000) "the core still streams: only the status says so")))
          (testing ":error without a message"
            (reset! reported {:level :error})
            (is (dac/wait-for! #(= {:state :error :error "The output driver reports an error"} (status)) 3000)
                (pr-str (status))))
          (testing ":warning without a message"
            (reset! reported {:level :warning :message ""})
            (is (dac/wait-for! #(= "The output driver reports a warning" (:warning (status))) 3000)
                (pr-str (status))))
          (testing "back to :ok, or a level that isn't one: streaming, no warning"
            (reset! reported {:level :ok :message "Interlock closed"})
            (is (dac/wait-for! #(and (= :streaming (:state (status))) (not (contains? (status) :warning))) 3000)
                (pr-str (status)))
            (reset! reported {:level :purple :message "?"})
            (Thread/sleep (* 2 output-runtime/STATUS_INTERVAL_MS))
            (is (= :streaming (:state (status))))
            (is (empty? (select-keys (status) [:warning :error :driver-info])) (pr-str (status))))
          (is (stop-streaming!))
          (finally
            (output-errors/ok! [:calibration :lv])))))))

(deftest throwing-status-is-ignored-test
  (let [mode (atom :throw)
        rec (rd/recording-driver {:id :test/bad-status
                                  :status (fn [_]
                                            (case @mode
                                              :throw (throw (ex-info "test: status broke" {}))
                                              :string "fine"
                                              :error (throw (StackOverflowError. "test: status overflowed"))))})
        status #(get-in (output-runtime/status) [:projectors :bad])
        warnings (atom [])]
    (with-redefs [log/log* (fn [_ level _ message]
                             (when (and (= :warn level) (re-find #":status" (str message)))
                               (swap! warnings conj message)))]
      (with-driver rec
        (fn []
          (init-with-projectors! {:bad (driver-projector :test/bad-status {})})
          (dispatch! {:event/type :idn/start-multi-streaming})
          (is (dac/wait-for! #(= :streaming (:state (status))) 3000) (pr-str (status)))
          (is (dac/wait-for! #(<= 3 (count (calls-of :status rec))) 2000) "asked again and again")
          (is (= :streaming (:state (status))))
          (is (empty? (select-keys (status) [:warning :error :driver-info])) "a :status that throws changes nothing")
          (is (nil? (get (output-errors/snapshot) [:engine :bad])) "and isn't an error of the engine")
          (is (= 1 (count @warnings)) (str "logged once: " (pr-str @warnings)))
          (reset! mode :error)
          (let [n (count (calls-of :status rec))]
            (is (dac/wait-for! #(< (+ n 2) (count (calls-of :status rec))) 2000) "any throwable, an Error too"))
          (reset! mode :string)
          (let [n (count (calls-of :status rec))]
            (is (dac/wait-for! #(< (+ n 2) (count (calls-of :status rec))) 2000)))
          (is (empty? (select-keys (status) [:warning :error :driver-info])) "nor does one that doesn't return a map")
          (is (= :streaming (:state (status))))
          (is (= 1 (count @warnings)))
          (is (stop-streaming!)))))))

(deftest busy-open-says-in-use-test
  (doseq [[data message] [[{:busy? true :holder "ShowCtl on 10.0.0.3"} "In use by ShowCtl on 10.0.0.3"]
                          [{:busy? true} "In use by another program"]
                          [{:busy? true :holder 42} "In use by another program"]
                          [{:busy? true :holder "  "} "In use by another program"]]]
    (testing (pr-str data)
      (let [free? (atom false)
            rec (rd/recording-driver {:id :test/busy :fail-open? (fn [_] (when-not @free? data))})
            status #(get-in (output-runtime/status) [:projectors :busy])]
        (with-driver rec
          (fn []
            (init-with-projectors! {:busy (driver-projector :test/busy {})})
            (dispatch! {:event/type :idn/start-multi-streaming})
            (is (dac/wait-for! #(= {:state :error :error message :busy? true} (status)) 2000) (pr-str (status)))
            (when (:holder data)
              (is (dac/wait-for! #(<= 2 (count (calls-of :open rec))) 2000) "it is still tried every REOPEN_MS")
              (reset! free? true)
              (is (dac/wait-for! #(= :streaming (:state (status))) 3000) "and streams once it is free")
              (is (not (contains? (status) :busy?))))
            (is (stop-streaming!)))))))
  (testing "a send that says the device is busy"
    (let [rec (rd/recording-driver {:id :test/busy-send :fail-send? #(when (<= 3 %) {:busy? true})})
          status #(get-in (output-runtime/status) [:projectors :busy])]
      (with-driver rec
        (fn []
          (init-with-projectors! {:busy (driver-projector :test/busy-send {})})
          (dispatch! {:event/type :idn/start-multi-streaming})
          (is (dac/wait-for! #(= {:state :error :error "In use by another program" :busy? true} (status)) 2000)
              (pr-str (status)))
          (is (stop-streaming!))))))
  (testing "an error that isn't busy after a busy one: no :busy?"
    (let [rec (rd/recording-driver {:id :test/busy-then :fail-open? #(case % 0 {:busy? true} 1 {} nil)})
          status #(get-in (output-runtime/status) [:projectors :busy])]
      (with-driver rec
        (fn []
          (init-with-projectors! {:busy (driver-projector :test/busy-then {})})
          (dispatch! {:event/type :idn/start-multi-streaming})
          (is (dac/wait-for! #(:busy? (status)) 2000) (pr-str (status)))
          (is (dac/wait-for! #(= {:state :error :error "test: open 1 failed"} (status)) 3000)
              (pr-str (status)))
          (is (stop-streaming!)))))))


;; The tests deferred until D1 had merged (02 § D2)

(deftest lsps-projector-streams-test
  (let [receiver (rx/start!)
        lit [[0.0 0.0 1.0 1.0 1.0] [0.5 0.0 1.0 0.0 0.0]]
        projectors {:bridge {:name "Bridge" :driver :lsps
                             :driver-config {:host "127.0.0.1" :port (:port receiver)}
                             :stable-id [:manual :lsps :bridge]
                             :enabled? true :zone-groups [:all] :scan-rate 25000}}
        lit-frames #(filterv (comp seq :points) (rx/frames (rx/packets receiver)))]
    (try
      (with-redefs-fn {#'me/projector-frame (constantly lit)}
        (fn []
          (init-with-projectors! projectors)
          (is (output-runtime/submit-sync! projectors))
          (is (rx/wait-for! #(<= 3 (count (lit-frames))) 2000) "the frames arrive")
          (is (= [[0 0 0 0 0] [0 0 65535 65535 65535] [16384 0 65535 0 0]] (:points (last (lit-frames))))
              "after sanitize-frame (a dark point in front), as 01 §3's points")
          (is (= 80 (:duration-us (last (lit-frames)))) "(3 - 1) / 25 000 s")
          (is (dac/wait-for! #(= :streaming (get-in (output-runtime/status) [:projectors :bridge :state])) 2000))
          (is (some #(= "output-streaming-bridge" (.getName ^Thread %)) (keys (Thread/getAllStackTraces)))
              "on the engine's own thread")
          (is (output-runtime/submit-stop!))
          (is (dac/wait-for! #(empty? (output-runtime/registry)) 3000))
          (let [packets (rx/packets receiver)]
            (is (:close? (last packets)) "Stop sends CLOSE")
            (is (= 1 (count (filter :close? packets))))
            (is (= [] (rx/violations packets 1400))))
          (is (rx/wait-for! #(not (some (fn [^Thread t] (= "output-streaming-bridge" (.getName t)))
                                        (keys (Thread/getAllStackTraces))))
                            1000))))
      (finally
        (rx/stop! receiver)))))
