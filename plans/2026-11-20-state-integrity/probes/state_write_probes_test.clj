;; EVIDENCE for plans/2026-11-20-state-integrity/02-direct-state-writes.md.
;; Not part of the test suite and not on any classpath.
;;
;; Each deftest prints PROBE lines describing what the code does TODAY
;; (master 3229c95, and again at cc9e000: the same results). The plan quotes
;; them. When the plan's work lands, turn the matching probe into a real test
;; with an assertion on the NEW behaviour (the plan's §8 names them).
;;
;; - w1: app/start!'s Link write (app.clj:213-220, copied here form for form:
;;   it is inline in start!, so it can't be called on its own) while an event
;;   is being handled. The event's write-back takes the connection away.
;; - w2: app/stop!'s teardown (the real private fns, app.clj:237-267):
;;   (a) disconnect-link!'s write lost to an event in flight, so a second
;;   stop! disconnects again; (b) a side effect inside swap-state! runs again
;;   when another writer changes the atom meanwhile (swap! retries its fn).
;; - w3: effects run in the effects map's order (cljfx/event_handler.clj:27),
;;   so a write inside the turn survives only if :state ran before it; and
;;   what :output/status returns through the whole pipeline today.
;; - w4: without an app, two threads run the pipeline at once (the output
;;   runtime's status thread and the caller): with today's path-only status
;;   write, Stop survives; with the status folded into :state, Stop is undone.
;; - w5: waiting for the agent: a no-op marker's round trip, :fx/sync on a
;;   handler that throws (it never returns), what a thread check costs, and
;;   what putting :state first costs next to the pipeline itself.
;; - w6: two app instances' agents (an old app's late event, the new app's
;;   event) run at once on the shared send pool, and the late write-back
;;   loses the new write; with one shared single-thread executor they queue.
;;
;; The agent wiring is app/create-app's (app.clj:94-108) without the renderer:
;; the bare pipeline with our co-effects and effects on a cljfx agent, as in
;; plans/2026-11-16-output-runtime/probes/output_probes_test.clj.
;;
;; Some probes slow one handler with with-redefs (a sleep of 200 ms) to make a
;; race window reproducible. Without the sleep the window is the handler's own
;; run time (well under a millisecond for these events), so the same loss
;; happens only when a write lands inside it.
;;
;; No UI, no hardware, no network.
;;
;; Run from the repo root (macOS Apple Silicon; use :win, :linux or :mac elsewhere).
;; The plan folder is the classpath root, so this namespace is probes.state-write-probes-test:
;;
;;   export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
;;   export PATH="$JAVA_HOME/bin:$PATH"
;;   P=plans/2026-11-20-state-integrity
;;   clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
;;       -M:mac-arm:test:probe -d "$P/probes" \
;;       -n probes.state-write-probes-test 2>&1 | grep PROBE

(ns probes.state-write-probes-test
  (:require [cljfx.api :as fx]
            [cljfx.event-handler :as event-handler]
            [clojure.test :refer [deftest]]
            [laser-show.app :as app]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.connection :as connection]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.input.link :as link]
            [laser-show.input.midi :as midi]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains])
  (:import [java.util.concurrent Executors ThreadFactory]))

(defn- say [& xs] (apply println "PROBE" xs) (flush))


;; The production wiring, and waiting for it

(defn- agent-handler
  "app/create-app's event handler without the renderer: the pipeline with our
   co-effects and effects, on a new cljfx agent. extra: more agent options
   (:fx/executor)."
  ([] (agent-handler {}))
  ([extra]
   (-> events/handle-event-pipeline
       (fx/wrap-co-effects (assoc events/co-effects :fx/context (constantly nil)))
       (fx/wrap-effects events/effects)
       (event-handler/wrap-async (merge {:error-handler (fn [_ e] (say "  (agent error:" (.getMessage ^Throwable e) ")"))}
                                        extra)))))

(def ^:private noop
  "A routed event that returns no effects (timing/handle's default, timing.clj:275-276)."
  {:event/type :timing/probe-noop})

(defn- settle!
  "Wait (up to 3 s) until the agent behind handler has handled everything sent
   to it so far. :timeout if it hasn't."
  [handler]
  (deref (future (handler (assoc noop :fx/sync true))) 3000 :timeout))

(defn- slowed
  "Call f while timing/handle (also :show-clock/* and :transport/*) sleeps ms
   before every event but the no-op marker."
  [ms f]
  (let [handle timing/handle]
    (with-redefs [timing/handle (fn [event]
                                  (when-not (= :timing/probe-noop (:event/type event))
                                    (Thread/sleep (long ms)))
                                  (handle event))]
      (f))))

(defn- link-now [] (select-keys (get-in (state/get-raw-state) [:backend :link]) [:carabiner-connected? :listener]))

(defn- with-dev-mode
  "Call f with laser-show.dev=true, recording [event-type changed-paths] for
   every write-back the dev detector finds another write under."
  [f]
  (let [old (System/getProperty "laser-show.dev")
        seen (atom [])
        check @#'events/check-lost-update!
        changed-paths @#'events/changed-paths]
    (System/setProperty "laser-show.dev" "true")
    (try
      (with-redefs [events/check-lost-update! (fn [event-type snapshot replaced]
                                                (when-not (identical? snapshot replaced)
                                                  (swap! seen conj [event-type (vec (changed-paths snapshot replaced 1))]))
                                                (check event-type snapshot replaced))]
        (f)
        @seen)
      (finally
        (if old (System/setProperty "laser-show.dev" old) (System/clearProperty "laser-show.dev"))))))


;; w1 — the Link write at start-up, while an event is handled

(defn- start-link-block!
  "app.clj:213-220, form for form, with link/connect-carabiner! as given."
  []
  (let [link-state (get-in (state/get-raw-state) [:backend :link])
        auto-connect? (:auto-connect? link-state true)
        get-link-state-fn #(get-in (state/get-raw-state) [:backend :link])]
    (when auto-connect?
      (let [new-link-state (link/connect-carabiner! link-state events/dispatch! get-link-state-fn)]
        (state/assoc-in-state! [:backend :link] new-link-state)))))

(defn- w1-control!
  "The same two writes with nothing in flight: the start-up write first, then
   the event."
  []
  (let [handler (agent-handler)]
    (state/init-state! (domains/build-initial-state))
    (start-link-block!)
    (handler {:event/type :show-clock/status :status {:beat-index 1}})
    (settle! handler)
    (say "w1 control (no event in flight): after both" (link-now))))

(deftest w1-the-startup-link-write-is-lost-to-an-event-in-flight
  (with-redefs [link/connect-carabiner! (fn [ls _ _] (assoc ls :carabiner-connected? true :listener ::probe-listener))]
    (w1-control!)
    (let [handler (agent-handler)
          seen (with-dev-mode
                 (fn []
                   (state/init-state! (domains/build-initial-state))
                   (slowed 200
                     (fn []
                       ;; what the show clock (started at app.clj:211) sends a few times a second
                       (handler {:event/type :show-clock/status :status {:beat-index 1}})
                       (Thread/sleep 50)
                       (start-link-block!)
                       (say "w1 right after the start-up write:" (link-now))
                       (settle! handler)))))]
      (say "w1 after the :show-clock/status write-back:" (link-now))
      (say "w1 the dev detector saw (event type, paths another writer changed):" seen))))


;; w2 — the teardown writes

(deftest w2a-the-link-teardown-write-is-lost-so-stop-disconnects-twice
  (let [calls (atom 0)
        handler (agent-handler)]
    (with-redefs [link/disconnect-carabiner! (fn [ls] (swap! calls inc)
                                               (-> ls (assoc :carabiner-connected? false) (dissoc :listener)))]
      (state/init-state! (update-in (domains/build-initial-state) [:backend :link]
                                    assoc :carabiner-connected? true :listener ::probe-listener))
      (slowed 200
        (fn []
          ;; what the Link listener sends (link.clj:69)
          (handler {:event/type :timing/link-peers-changed :peers 2})
          (Thread/sleep 50)
          (@#'app/disconnect-link!)
          (say "w2a right after disconnect-link!:" (link-now))
          (settle! handler)))
      (say "w2a after the :timing/link-peers-changed write-back:" (link-now))
      (@#'app/disconnect-link!)
      (say "w2a a second stop!'s disconnect-link! disconnects again: disconnect calls" @calls))))

(deftest w2b-a-side-effect-inside-swap-state-runs-again-on-retry
  (let [runs (atom 0)
        started (promise)]
    (state/init-state! (domains/build-initial-state))
    (with-redefs [midi/disconnect-all (fn [m]
                                        (swap! runs inc)
                                        (deliver started true)
                                        (Thread/sleep 5)   ; closing a device takes a moment
                                        m)]
      (let [teardown (future (@#'app/stop-input-devices!))]
        @started
        ;; any other write meanwhile, e.g. an event's write-back on the agent
        (state/swap-state! assoc-in [:timing :bpm] 99.0)
        @teardown))
    (say "w2b midi/disconnect-all ran" @runs "times for one stop-input-devices! (one other write meanwhile)")))


;; w3 — effects run in the map's order

(defn- run-effects!
  "Run an effects map through events/effects the way both wirings do
   (fx/wrap-effects: a doseq over the map, cljfx/event_handler.clj:22-33)."
  [effects-map]
  ((fx/wrap-effects (constantly effects-map) events/effects) {:event/type :probe/effects}))

(deftest w3-effect-order-decides-what-survives
  (let [status {:engines 1 :projectors {}}
        status-now #(get-in (state/get-raw-state) output-runtime/status-path)]
    (doseq [[label make] [["{:state … :output/write-status …}" (fn [s0] (array-map :state s0 :output/write-status status))]
                          ["(assoc {:output/write-status …} :state …), as a middleware adding :state would"
                           (fn [s0] (assoc {:output/write-status status} :state s0))]]]
      (state/init-state! (domains/build-initial-state))
      (let [m (make (state/get-raw-state))]
        (run-effects! m)
        (say "w3 order" (vec (keys m)) "-> status after:" (status-now) " ;" label)))
    (doseq [[label make] [["{:state … :dispatch set-bpm 99}" (fn [s0 e] (array-map :state s0 :dispatch e))]
                          ["{:dispatch set-bpm 99 :state …}" (fn [s0 e] (array-map :dispatch e :state s0))]]]
      (state/init-state! (domains/build-initial-state))
      (let [m (make (assoc-in (state/get-raw-state) [:ui :probe] 1) {:event/type :timing/set-bpm :bpm 99.0})]
        (run-effects! m)
        (say "w3 order" (vec (keys m)) "-> BPM after:" (get-in (state/get-raw-state) [:timing :bpm])
             "[:ui :probe]" (get-in (state/get-raw-state) [:ui :probe]) " ;" label)))
    (state/init-state! (domains/build-initial-state))
    (say "w3 :output/status through handle-event-pipeline today returns the keys"
         (vec (keys (events/handle-event-pipeline {:event/type :output/status :status status
                                                   :state (state/get-raw-state) :time 0}))))
    (let [others [:dispatch :dispatch-later :multi-engine/refresh :rig/write :output/write-status
                  :project/save :fx/show-file-chooser :system/exit]
          first-in (reduce #(assoc %1 %2 true) (array-map :state 1) others)
          last-in (assoc (reduce #(assoc %1 %2 true) (array-map) others) :state 1)]
      (say "w3 nine effect keys, :state put in first, make a" (.getSimpleName (class first-in))
           "that runs them in the order" (vec (keys first-in)))
      (say "w3 the same keys with :state put in last run in the order" (vec (keys last-in))))))


;; w4 — no app: two threads in the pipeline at once

(defn- status-vs-stop
  "The status event slowed 200 ms on one thread (as the runtime's status
   thread runs it without an app), Stop on this one 50 ms in. status-effects:
   what :output/status's handler returns."
  [status-effects]
  (events/set-dispatch-fn! nil)
  (state/init-state! (assoc-in (domains/build-initial-state) [:backend :streaming :running?] true))
  (let [handle connection/handle]
    (with-redefs [connection/handle (fn [event]
                                      (if (= :output/status (:event/type event))
                                        (do (Thread/sleep 200) (status-effects event))
                                        (handle event)))]
      (let [status-thread (future (events/event-handler {:event/type :output/status :status {:engines 0}}))]
        (Thread/sleep 50)
        (events/event-handler {:event/type :idn/stop-multi-streaming})
        @status-thread)))
  (output-runtime/drain! 500)
  (output-runtime/open!)
  (select-keys (get-in (state/get-raw-state) [:backend :streaming]) [:running? :status]))

(deftest w4-without-an-app-a-whole-state-status-write-undoes-stop
  (say "w4 path-only status write (today, connection.clj:33-34): after both"
       (status-vs-stop (fn [{:keys [status]}] {:output/write-status status})))
  (say "w4 status folded into :state: after both"
       (status-vs-stop (fn [{:keys [state status]}] {:state (assoc-in state output-runtime/status-path status)}))))


;; w5 — waiting for the agent

(defn- percentile [sorted p] (nth sorted (min (dec (count sorted)) (long (* p (count sorted))))))

(deftest w5-waiting-for-the-agent
  (state/init-state! (domains/build-initial-state))
  (let [handler (agent-handler)]
    (dotimes [_ 500] (handler (assoc noop :fx/sync true)))
    (let [us (sort (for [_ (range 2000)]
                     (let [t0 (System/nanoTime)]
                       (handler (assoc noop :fx/sync true))
                       (/ (- (System/nanoTime) t0) 1000.0))))]
      (say "w5 a no-op marker's round trip on an idle agent (2000 runs): median"
           (format "%.0f" (percentile us 0.5)) "µs, p95" (format "%.0f" (percentile us 0.95)) "µs"))
    (let [stuck (future (handler {:event/type :timing/set-bpm :bpm nil :fx/sync true}))]
      (say "w5 :fx/sync on a handler that throws (set-bpm with :bpm nil): returned after 1 s?"
           (not= ::waiting (deref stuck 1000 ::waiting)))
      (say "w5 the agent still handles the next event (error mode :continue):" (not= :timeout (settle! handler)))))
  (let [t (Thread/currentThread)
        n 10000000
        t0 (System/nanoTime)
        hits (loop [i 0 hits 0] (if (< i n) (recur (inc i) (if (identical? (Thread/currentThread) t) (inc hits) hits)) hits))]
    (say "w5 a thread-identity check costs" (format "%.1f" (/ (- (System/nanoTime) t0) (double n))) "ns (" hits "hits)"))
  (let [s (state/get-raw-state)
        event {:event/type :timing/set-bpm :bpm 128.0 :state s :time 0}
        effects (events/handle-event-pipeline event)
        state-first (fn [m] (if-let [e (find m :state)] (cons e (dissoc m :state)) m))
        per-op-ns (fn [n f] (dotimes [_ n] (f)) (let [t0 (System/nanoTime)] (dotimes [_ n] (f)) (/ (- (System/nanoTime) t0) (double n))))]
    (say "w5 putting :state first in" (vec (keys effects)) "costs"
         (format "%.0f" (per-op-ns 1000000 #(doall (state-first effects)))) "ns; the pipeline for that event costs"
         (format "%.0f" (per-op-ns 20000 #(events/handle-event-pipeline event))) "ns")))


;; w6 — two app instances

(defn- single-thread-executor []
  (Executors/newSingleThreadExecutor
   (reify ThreadFactory
     (newThread [_ r] (doto (Thread. ^Runnable r "probe-event-thread") (.setDaemon true))))))

(defn- old-and-new-app [opts]
  (let [threads (atom {})
        handle timing/handle]
    (state/init-state! (domains/build-initial-state))
    (with-redefs [timing/handle (fn [event]
                                  (swap! threads assoc (:event/type event) (.getName (Thread/currentThread)))
                                  (when (= :timing/link-peers-changed (:event/type event)) (Thread/sleep 200))
                                  (handle event))]
      (let [old-app (agent-handler opts)
            new-app (agent-handler opts)]
        (old-app {:event/type :timing/link-peers-changed :peers 1})   ; a late event for the old app
        (Thread/sleep 50)
        (new-app {:event/type :timing/set-bpm :bpm 99.0})              ; the new app's event
        (settle! old-app)
        (settle! new-app)))
    [(get-in (state/get-raw-state) [:timing :bpm]) (dissoc @threads :timing/probe-noop)]))

(deftest w6-two-app-instances-agents-run-at-once
  (let [[bpm threads] (old-and-new-app {})]
    (say "w6 two agents on the shared send pool: BPM after" bpm "threads" threads))
  (let [executor (single-thread-executor)
        [bpm threads] (old-and-new-app {:fx/executor executor})]
    (say "w6 two agents on one shared single-thread executor: BPM after" bpm "threads" threads)
    (.shutdown executor)))
