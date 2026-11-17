(ns laser-show.backend.output-runtime
  "Owns every streaming engine. App state never holds one.

   - The registry (a runtime atom) maps projector-id to
     {:engine :fingerprint :started-ms}. An engine goes in before its thread
     starts and comes out only after its close pair was sent.
   - One lifecycle thread (output-lifecycle) runs the commands submit-sync!
     and submit-stop! queue, one at a time. A queued sync is replaced by a
     newer one; a stop drops any queued sync.
   - Each engine computes and sends its frames on its own streaming thread
     (streaming-engine/start!). The lifecycle thread only starts, stops and
     replaces engines.
   - The status thread (output-status) publishes pure-data status as
     :output/status at most every STATUS_INTERVAL_MS, when it differs from what
     app state holds.
   - drain! closes the runtime: every engine is stopped and later submits are
     dropped until open! (app/start!). Before the first open!/drain! the runtime
     opens on the first submit, so tests without an app can stream."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.backend.multi-engine :as multi-engine]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.backend.streaming-engine :as engine]
            [laser-show.state.core :as state])
  (:import [java.util.concurrent ExecutorService Executors ScheduledExecutorService
            ThreadFactory TimeUnit]))

(def ^:const STATUS_INTERVAL_MS 250)

(def status-path [:backend :streaming :status])


;; Fingerprints

(defn fingerprint
  "What an engine is built from. A change restarts the projector's engine;
   zone groups and names are read live by its frame provider."
  [projector]
  (select-keys projector [:host :port :service-id :output-config :scan-rate]))

(defn wanted
  "{projector-id fingerprint} of the projectors that should stream: enabled,
   with a non-blank host."
  [projectors]
  (into {}
        (keep (fn [[id p]]
                (let [host (:host p)]
                  (when (and (:enabled? p true) (string? host) (not (str/blank? host)))
                    [id (fingerprint p)]))))
        projectors))


;; Runtime state

(defonce ^:private !registry (atom {}))

(defonce ^{:private true
           :doc "{:state :idle|:open|:closed, :executor, :ticker, :dispatch}"}
  !runtime
  (atom {:state :idle}))

(defonce ^{:private true :doc "Commands not yet run: [:sync projectors] or [:stop]."}
  !queue
  (atom []))

(defonce ^{:private true
           :doc "{:wanted {id fingerprint} (nil = stopped), :failed {id message}, :busy? bool}"}
  !intent
  (atom {:wanted nil :failed {} :busy? false}))

(defonce ^:private lock (Object.))

(defonce ^:private !shutdown-hook (atom nil))

(defn- closed? []
  (= :closed (:state @!runtime)))

(defn registry
  "{projector-id {:engine :fingerprint :started-ms}} of every engine that
   exists (for status and tests)."
  []
  @!registry)


;; Engines (lifecycle thread, and drain!)

(defn- message-of [^Throwable t]
  (str (.getName (class t)) ": " (.getMessage t)))

(defn- stop-engines!
  "Stop the registry's engines for ids together (streaming-engine/stop-all!),
   then remove the ones this call stopped. One that another caller is stopping
   (or that is still starting) is left to that caller."
  [ids]
  (let [entries (into [] (keep #(find @!registry %)) ids)]
    (when (seq entries)
      (let [stopped (engine/stop-all! (mapv (comp :engine val) entries))
            stopped? (fn [e] (some #(identical? e %) stopped))]
        (swap! !registry (fn [r]
                           (reduce (fn [r [id entry]]
                                     (if (and (identical? entry (get r id))
                                              (stopped? (:engine entry)))
                                       (dissoc r id)
                                       r))
                                   r entries)))
        (log/info "Output: stopped" (mapv key entries))))))

(defn- register!
  "Put entry in the registry unless the runtime is closed. True if it went in."
  [id entry]
  (locking lock
    (when-not (closed?)
      (swap! !registry assoc id entry)
      true)))

(defn- start-engine! [id projector fp]
  (if-let [e (multi-engine/create-engine-for-projector id projector)]
    (when (register! id {:engine e :fingerprint fp :started-ms (System/currentTimeMillis)})
      (try
        (engine/start! e)
        (swap! !intent update :failed dissoc id)
        ;; drain! may have run while start! did: it might have missed this engine
        (when (closed?) (stop-engines! [id]))
        (catch Throwable t
          (log/error t "Output: could not start" id)
          (engine/stop-all! [e])
          (swap! !registry #(if (identical? e (get-in % [id :engine])) (dissoc % id) %))
          (swap! !intent assoc-in [:failed id] (message-of t)))))
    (swap! !intent assoc-in [:failed id] "No host")))

(defn- sync!
  "Make the registry match projectors: stop engines no longer wanted or whose
   fingerprint changed (together), then start the new and changed ones."
  [projectors]
  (let [want (wanted projectors)
        have @!registry
        stale (keep (fn [[id {fp :fingerprint}]] (when (not= fp (get want id)) id)) have)
        fresh (filter (fn [[id fp]] (not= fp (get-in have [id :fingerprint]))) want)]
    (stop-engines! stale)
    (swap! !intent update :failed select-keys (keys want))
    (doseq [[id fp] fresh]
      (when-not (closed?)
        (start-engine! id (get projectors id) fp)))
    (when (or (seq stale) (seq fresh))
      (log/info (format "Output: sync stopped %d, started %d; %d engine(s)"
                        (count stale) (count fresh) (count @!registry))))))

(defn- stop! []
  (stop-engines! (keys @!registry))
  (swap! !intent assoc :failed {}))

(defn- run-next!
  "Run the oldest queued command (lifecycle thread). Busy from before the pop,
   so the status never shows nothing pending in between."
  []
  (swap! !intent assoc :busy? true)
  (try
    (let [[queue _] (swap-vals! !queue #(if (seq %) (subvec % 1) %))]
      (when-let [[kind arg] (first queue)]
        (try
          (case kind
            :sync (sync! arg)
            :stop (stop!))
          (catch Throwable t
            (log/error t "Output command failed:" kind)))))
    (finally
      (swap! !intent assoc :busy? false))))


;; Status

(defn- engine-status [e errors id]
  (let [error (some #(:message (get errors [% id])) [:engine :provider :projector-effects])
        ^Thread thread @(:thread e)
        {:keys [frames-sent actual-fps]} (engine/get-stats e)]
    (cond
      error {:state :error :error error}
      (and thread (not (.isAlive thread))) {:state :error :error "The streaming thread ended"}
      (zero? frames-sent) {:state :starting}
      :else {:state :streaming :fps (/ (Math/round (* 10.0 (double actual-fps))) 10.0)})))

(defn status
  "Pure-data status for app state, or nil when stopped with nothing running:
   {:engines n :wanted m :pending? bool
    :projectors {id {:state :streaming|:starting|:error :fps 59.8 :error \"…\"}}}"
  []
  (let [registry @!registry
        {:keys [wanted failed busy?]} @!intent
        pending? (boolean (or busy? (seq @!queue)))]
    (when (or wanted (seq registry) pending?)
      (let [errors (output-errors/snapshot)]
        {:engines (count registry)
         :wanted (count wanted)
         :pending? pending?
         :projectors (into {}
                           (map (fn [id]
                                  [id (if-let [e (get-in registry [id :engine])]
                                        (engine-status e errors id)
                                        (if-let [message (get failed id)]
                                          {:state :error :error message}
                                          {:state :starting}))]))
                           (distinct (concat (keys wanted) (keys registry))))}))))

(defn- publish-status!
  "Dispatch :output/status when the status differs from what app state holds
   (not from what was sent last, so a write that got lost is redone)."
  []
  (try
    (when-let [dispatch (:dispatch @!runtime)]
      (let [s (status)]
        (when (not= s (get-in (state/get-raw-state) status-path))
          (dispatch {:event/type :output/status :status s}))))
    (catch Throwable t
      (log/warn t "Output status could not be published"))))


;; Commands

(defn- daemon-factory [thread-name]
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. ^Runnable r ^String thread-name)
        (.setDaemon true)))))

(defn- ensure-started!
  "Start the lifecycle and status threads if the runtime isn't open yet
   (call with lock held). Returns the runtime map."
  []
  (when (= :idle (:state @!runtime))
    (let [ticker (Executors/newSingleThreadScheduledExecutor (daemon-factory "output-status"))]
      (.scheduleWithFixedDelay ticker ^Runnable publish-status!
                               STATUS_INTERVAL_MS STATUS_INTERVAL_MS TimeUnit/MILLISECONDS)
      (swap! !runtime assoc
             :state :open
             :executor (Executors/newSingleThreadExecutor (daemon-factory "output-lifecycle"))
             :ticker ticker)))
  @!runtime)

(defn- enqueue [queue [kind :as cmd]]
  (case kind
    :sync (if (= :sync (first (peek queue)))
            (conj (pop queue) cmd)
            (conj queue cmd))
    :stop [cmd]))

(defn- submit!
  "Queue cmd for the lifecycle thread. dispatch is how :output/status gets
   back into the event flow (the effect's dispatch). False if closed."
  [cmd dispatch]
  (locking lock
    (if (closed?)
      (do (log/warn "Output runtime is closed (app stopped); dropping" (first cmd))
          false)
      (let [{:keys [^ExecutorService executor]} (ensure-started!)]
        (when dispatch (swap! !runtime assoc :dispatch dispatch))
        (swap! !intent assoc :wanted (when (= :sync (first cmd)) (wanted (second cmd))))
        (swap! !queue enqueue cmd)
        (.execute executor ^Runnable run-next!)
        true))))

(defn submit-sync!
  "Make the engines match projectors (a projector snapshot), on the lifecycle
   thread. Replaces a sync that hasn't run yet."
  ([projectors] (submit-sync! projectors nil))
  ([projectors dispatch] (submit! [:sync projectors] dispatch)))

(defn submit-stop!
  "Stop every engine, on the lifecycle thread. Drops a sync that hasn't run yet."
  ([] (submit-stop! nil))
  ([dispatch] (submit! [:stop] dispatch)))


;; Open / drain

(defn open!
  "Allow submits again after drain! (app/start!). The threads start on the
   next submit."
  []
  (locking lock
    (when (closed?)
      (reset! !runtime {:state :idle}))))

(defn drain!
  "Close the runtime and stop every engine, on the calling thread: queued
   commands are dropped, the running one gets up to timeout-ms to finish, then
   every engine in the registry is stopped together. Later submits are dropped
   until open!. Idempotent."
  [timeout-ms]
  (let [{:keys [^ExecutorService executor ^ScheduledExecutorService ticker]}
        (locking lock
          (let [old @!runtime]
            (reset! !queue [])
            (reset! !runtime {:state :closed})
            old))]
    (when ticker
      (.shutdownNow ticker))
    (when executor
      (.shutdown executor)
      (when-not (.awaitTermination executor timeout-ms TimeUnit/MILLISECONDS)
        (log/warn "Output: the running command did not finish within" timeout-ms "ms")
        (.shutdownNow executor)))
    (stop-engines! (keys @!registry))
    (reset! !intent {:wanted nil :failed {} :busy? false})
    nil))

(defn install-shutdown-hook!
  "Register (once) a JVM shutdown hook that drains the runtime, so a kill or a
   System/exit from anywhere still closes every engine."
  []
  (locking lock
    (when-not @!shutdown-hook
      (let [hook (Thread. ^Runnable #(drain! 2000) "output-drain-on-shutdown")]
        (.addShutdownHook (Runtime/getRuntime) hook)
        (reset! !shutdown-hook hook))))
  @!shutdown-hook)
