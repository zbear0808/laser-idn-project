(ns laser-show.input.link
  "Ableton Link synchronization service using beat-carabiner.
   
   This is a READ-ONLY integration - we receive BPM, peers and the beat
   phase from Link but never push our tempo, or a beat, to the Link
   session.
   
   Architecture:
   - Carabiner: Auto-connects on app startup (embedded lib-carabiner):
     app/start! sends :timing/carabiner-auto-connect, which asks for
     :link/connect unless Auto-connect is off
   - Link: local switches enable Link and following its tempo
   
   The service:
   1. Owns the connection to Carabiner and its status listener (reactivity
      follow-up R5, as input.devices owns the MIDI inputs and the OSC
      server): app state never holds them, only plain status
      ([:backend :link]), which comes back as events
   2. Enables/disables Link sync mode on user request
   3. Listens for Link tempo and peer updates
   4. Dispatches events to update app state
   5. Keeps the app's beats on Link's (Align beats with Link): Link's beat
      phase read on a query connection of its own (beat-carabiner gives
      none), the show clock's phase moved onto it
   6. Does NOT call set-link-tempo, lock-tempo or beat-at-time (read-only)
   
   The connection:
   - connect! (the :link/connect effect) connects on the Link thread
     (link-carabiner), one connect at a time, so the event thread never
     waits for Carabiner (it can take seconds: it may start an embedded
     one), and registers one status listener (create-status-listener) and
     one disconnection listener. Connected already, nothing new opens and
     no second listener.
   - The listener's reports (:timing/link-bpm-changed,
     :timing/link-peers-changed) and how the connect went
     (:timing/carabiner-connected {:connected?}) go through the dispatch fn
     of the effect that asked, so a stopped app's are dropped.
   - When the connection closes without release! (Carabiner quit or closed
     it, or beat-carabiner dropped it for sending no status), the
     disconnection listener says so through that dispatch fn too:
     :timing/carabiner-connected {:connected? false} (follow-up N5). The
     connection is over; the next connect! makes a new one.
   - While it is up, the Link thread checks the app's beat phase against
     Link's every alignment-interval-ms, and soon after each tempo change
     (check-beat!, follow-up N4's successor): only while the app aligns
     (aligning?), on a query connection to Carabiner of the owner's own,
     asking phase-at-time and nothing else; a correction goes through the
     same dispatch fn (:timing/align-to-link-beat).
   - release! (app/stop!) removes the listeners, stops the checks, closes
     the query connection and disconnects, once, whatever app state says,
     and drops connects until open! (app/start!, start-test!); what a
     connect running then connects is undone at once. Before the first
     release! the Link thread starts with the first connect, so tests
     without an app can use it.
   
   The rest of this module is pure functions for Link state management, on
   [:backend :link] (state/domains.clj)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [beat-carabiner.core :as bc]
            [laser-show.animation.show-time :as show-time]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex])
  (:import [java.io BufferedReader EOFException InputStreamReader OutputStream]
           [java.net InetSocketAddress Socket]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ExecutorService RejectedExecutionException ScheduledFuture
            ScheduledThreadPoolExecutor ThreadFactory TimeUnit]))


;; BPM Change Threshold
;; Use threshold to avoid flooding events when BPM is stable


(def ^:const bpm-change-threshold 0.01)


;; Link Status Listener


(defn bpm-changed?
  "Is this a usable Link tempo that differs from the previous one?"
  [old-bpm new-bpm]
  (and (number? new-bpm) (pos? new-bpm) (Double/isFinite (double new-bpm))
       (or (nil? old-bpm)
           (> (Math/abs (- (double new-bpm) (double old-bpm))) bpm-change-threshold))))

(defn should-follow-bpm?
  "Would following this Link tempo change the app's BPM right now?"
  [state bpm]
  (and (ex/link-sync-enabled? state) (bpm-changed? (ex/bpm state) bpm)))

(defn create-status-listener
  "Forward changed observations, not unconditional tempo commands. Compare
   with the last enqueued report because dispatch need not have run yet.
   A steady peer may also need to correct a locally changed app tempo."
  [dispatch! get-app-state]
  (let [sent (volatile! (select-keys (ex/link-data (get-app-state)) [:link-bpm :link-peers]))]
    (fn listen [{:keys [link-bpm link-peers]}]
      (locking listen
        (let [state (get-app-state)
              previous @sent
              timing (:timing state)
              correct? (should-follow-bpm? state link-bpm)]
          (when (or (bpm-changed? (:link-bpm previous) link-bpm)
                    (and correct?
                         ;; An unchanged timing snapshot means a correction
                         ;; for this local tempo is already in the queue.
                         (not (identical? timing (:correcting-timing previous)))))
            (vswap! sent assoc :link-bpm link-bpm :correcting-timing (when correct? timing))
            (dispatch! {:event/type :timing/link-bpm-changed :bpm link-bpm}))
          (when (and (some? link-peers) (not= link-peers (:link-peers previous 0)))
            (vswap! sent assoc :link-peers link-peers)
            (dispatch! {:event/type :timing/link-peers-changed :peers link-peers})))))))


;; Beat alignment: the app's beat phase on Link's (quantum 1: beats line
;; up, bars needn't)
;;
;; beat-carabiner 8.0.0 can't read Link's beat: its state has :link-bpm and
;; :link-peers only, and its beat-at-time is no query (it moves the whole
;; session's beat grid, so this read-only integration never calls it).
;; Carabiner itself answers a pure query, though: phase-at-time <micros>
;; <quantum>, on any of its client connections, so the owner opens one of
;; its own for it (query-phase!). Times are Link's clock, which is
;; System/nanoTime in microseconds (the show clock's time base too).


(def beat-deadband
  "A beat phase error up to this many beats is left alone: 2% of a beat
   (about 10 ms at 120 BPM), what the show clock's phase glide snaps across
   too (cue-timing)."
  0.02)

(def alignment-interval-ms
  "How often the Link thread checks the app's beat phase against Link's
   while it aligns them."
  1000)

(def tempo-check-delay-ms
  "How soon after Link's tempo changes the beat phase is checked again: the
   app follows the new tempo meanwhile."
  250)

(def query-timeout-ms
  "How long a query to Carabiner may take, its connection's connect
   included: it answers at once, as it only reads Link's timeline."
  500)

(defn- the-short-way
  "d beats, the shorter way round a beat: -0.5 to just under 0.5."
  ^double [^double d]
  (let [m (- d (Math/floor d))]
    (if (>= m 0.5) (- m 1.0) m)))

(defn beat-correction
  "The correction, in beats (the shorter way round: -0.5 to 0.5), that puts
   the app's beat phase on Link's: link-phase (0 to 1, Link's phase-at-time
   for quantum 1) against the app's beats at the same instant ({:current
   :settled}, show-time/global-beats-at). nil when :settled is within
   beat-deadband of Link's beat: aligned, or gliding onto it already. The
   correction is from :current, as the show clock moves a phase target
   from where the phase is (its :shift request)."
  [link-phase {:keys [current settled]}]
  (when (> (Math/abs (the-short-way (- (double link-phase) (double settled)))) beat-deadband)
    (the-short-way (- (double link-phase) (double current)))))

(defn aligning?
  "Does the app keep its beats on Link's now: Carabiner connected, Link
   enabled with its tempo followed (Sync BPM from Link: aligning the phase
   of a tempo the app doesn't follow would only chase it), and Align beats
   with Link on ([:backend :link :beat-sync?], on unless set off)?"
  [state]
  (and (true? (get-in state [:backend :link :carabiner-connected?]))
       (boolean (ex/link-sync-enabled? state))
       (not (false? (get-in state [:backend :link :beat-sync?])))))


;; The connection to Carabiner: its owner
;; Carabiner is the underlying daemon - connects automatically on startup


(defonce ^{:private true
           :doc "The connection connect! made, or nil: {:token :listener (its
   status listener) :on-closed (its disconnection listener), both
   registered with beat-carabiner, :checks (its beat checks, a task on the
   Link thread) :soon (an atom: the check asked for after a tempo change,
   until it has run)}."}
  !connection
  (atom nil))

(defonce ^{:private true
           :doc "The query connection to Carabiner (beat alignment), or nil:
   {:token (that of the connection it belongs to) :socket :reader :writer}.
   Only the Link thread opens and uses it; whoever takes it out closes it."}
  !query
  (atom nil))

(defonce ^{:private true
           :doc "{:state :idle|:open|:closed :generation n :executor}: n counts
   the release!s, so a connect asked for before one can't keep what it
   connects."}
  !runtime
  (atom {:state :idle :generation 0}))

(defonce ^:private lock (Object.))

(defn holds-connection?
  "Is there a connection to Carabiner that connect! made, with its status
   listener, and that neither release! closed nor beat-carabiner said has
   closed?"
  []
  (some? @!connection))

(defn- current?
  "Is the owner open, with no release! since a connect of generation was
   asked for?"
  [generation]
  (let [{:keys [state] g :generation} @!runtime]
    (and (not= :closed state) (= generation g))))

(defn- connect-carabiner!
  "Connect to the Carabiner daemon (bc/connect: the one on its port, else
   an embedded one it starts), on this thread: it can take seconds. Does
   NOT enable Link - that's a separate step. True if connected."
  []
  (try
    (boolean (bc/connect))
    (catch Exception e
      (log/error e "Error connecting to Carabiner:" (.getMessage e))
      false)))


;; The query connection (beat alignment)

(defn- close-query!
  "Close the query connection, if one is open."
  []
  (when-let [^Socket socket (:socket (first (reset-vals! !query nil)))]
    (try (.close socket) (catch Exception _ nil))))

(defn- open-query!
  "A new query connection to Carabiner, for the connection with token:
   {:token :socket :reader :writer}, a socket to the loopback address
   (Carabiner listens only there, and takes several clients) at
   beat-carabiner's port (bc/state's :port). nil (logged) when it can't be
   opened."
  [token]
  (let [port (int (or (:port (bc/state)) 17000))
        socket (Socket.)]
    (try
      (.connect socket (InetSocketAddress. "127.0.0.1" port) (int query-timeout-ms))
      (.setSoTimeout socket (int query-timeout-ms))
      (.setTcpNoDelay socket true)
      {:token token
       :socket socket
       :reader (BufferedReader. (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))
       :writer (.getOutputStream socket)}
      (catch Exception e
        (try (.close socket) (catch Exception _ nil))
        (log/warn "Could not open a query connection to Carabiner on port" port ":" (.getMessage e))
        nil))))

(defn- query-connection!
  "The query connection of the owner's connection with token: the one
   open, or a new one (open-query!), kept unless release! or the
   connection's close came meanwhile. nil when there is none."
  [token generation]
  (or (let [q @!query] (when (identical? token (:token q)) q))
      (when-let [q (open-query! token)]
        (if (locking lock
              (when (and (current? generation) (identical? token (:token @!connection)))
                (close-query!)
                (reset! !query q)
                true))
          q
          (do (.close ^Socket (:socket q)) nil)))))

(def ^:private phase-answer "phase-at-time ")

(defn- query-phase!
  "Link's beat phase (0 to 1) at time-us, microseconds on Link's clock:
   Carabiner's answer to phase-at-time time-us 1 on the query connection q,
   the other lines it sends every client (its status) passed over. Nothing
   else is ever sent on it: no command that changes the session. nil when
   no answer came within query-timeout-ms, or the connection failed; then
   it is closed, for a new one at the next check."
  [{:keys [^OutputStream writer ^BufferedReader reader]} time-us]
  (try
    (.write writer (.getBytes (str phase-answer time-us " 1\n") StandardCharsets/UTF_8))
    (.flush writer)
    (loop []
      (let [line (.readLine reader)]
        (cond
          (nil? line)
          (throw (EOFException. "Carabiner closed the query connection"))

          (str/starts-with? line phase-answer)
          (let [{at :when phase :phase} (edn/read-string (subs line (count phase-answer)))]
            (if (and (number? at) (== (long at) (long time-us)) (number? phase))
              (double phase)
              (recur)))

          (str/starts-with? line "bad-")
          (throw (ex-info (str "Carabiner refused the query: " line) {}))

          :else (recur))))
    (catch Exception e
      (log/debug "Link beat query failed:" (.getMessage e))
      (close-query!)
      nil)))


;; The beat checks

(defn- check-beat!
  "One beat check, on the Link thread, for the owner's connection with
   token: while the app aligns its beats with Link's (aligning?, app state
   now), Link's beat phase at this instant, the stored latency ahead
   (:latency-ms: our output arrives that late, so the beats run that far
   ahead of Link's), is asked of Carabiner (query-phase!) and set against
   where the show clock is at the same instant (show-time/global-beats-at).
   An error beyond beat-deadband goes to dispatch, the dispatcher of the
   app that connected: :timing/align-to-link-beat {:offset
   (beat-correction)}. Never throws: a periodic task that did would stop."
  [token dispatch generation]
  (try
    (when (and (current? generation) (identical? token (:token @!connection)))
      (let [state (state/get-raw-state)]
        (when (aligning? state)
          (when-let [q (query-connection! token generation)]
            (let [now-ns (System/nanoTime)
                  ahead-us (* 1000 (long (or (get-in state [:backend :link :latency-ms]) 0)))
                  link-phase (query-phase! q (+ (quot now-ns 1000) ahead-us))
                  app (show-time/global-beats-at @show-time/!runtime (double (or (ex/bpm state) 120.0)) now-ns)]
              (when-let [offset (and link-phase app (beat-correction link-phase app))]
                (dispatch {:event/type :timing/align-to-link-beat :offset offset})))))))
    (catch Throwable t
      (log/error t "Link beat check failed"))))

(defn- schedule!
  "f on the Link thread after delay-ms, and with period-ms every period-ms
   after that, unless release! came since generation: its ScheduledFuture,
   or nil."
  ([generation delay-ms f] (schedule! generation delay-ms nil f))
  ([generation delay-ms period-ms f]
   (let [{:keys [^ScheduledThreadPoolExecutor executor]} @!runtime
         task ^Runnable (bound-fn* f)]
     (when (and executor (current? generation))
       (try
         (if period-ms
           (.scheduleWithFixedDelay executor task (long delay-ms) (long period-ms) TimeUnit/MILLISECONDS)
           (.schedule executor task (long delay-ms) TimeUnit/MILLISECONDS))
         (catch RejectedExecutionException _ nil))))))

(defn- check-soon!
  "check on the Link thread tempo-check-delay-ms from now, unless one asked
   for so is still to run (soon, the connection's)."
  [soon generation check]
  (let [^ScheduledFuture pending @soon]
    (when-not (and pending (not (.isDone pending)))
      (reset! soon (schedule! generation tempo-check-delay-ms check)))))

(defn- stop-checks!
  "The beat checks of the connection conn stop: its periodic one, and one
   asked for after a tempo change."
  [{:keys [^ScheduledFuture checks soon]}]
  (some-> checks (.cancel false))
  (some-> ^ScheduledFuture (some-> soon deref) (.cancel false)))


;; The connection's lifetime

(defn- forget!
  "The connection with token is over: unless it is gone already (release!,
   a close heard before), it isn't the owner's any more, its listeners are
   removed, its beat checks stop and its query connection is closed. True
   if it was the owner's."
  [token]
  (locking lock
    (let [{:keys [listener on-closed] :as conn} @!connection]
      (when (identical? token (:token conn))
        (reset! !connection nil)
        (stop-checks! conn)
        (close-query!)
        (bc/remove-status-listener listener)
        (bc/remove-disconnection-listener on-closed)
        true))))

(defn- closed!
  "beat-carabiner's word, on its read thread, that a connection closed (its
   read loop ended: Carabiner quit or closed it, unexpected? true; or it
   sent no status within a second of the connect). For the owner's
   connection (token), unless Carabiner is connected again (a connect took
   it over since, its listeners with it): it is over (forget!), and
   dispatch gets :timing/carabiner-connected {:connected? false}. A close
   release! asked for isn't heard: it removes this listener first."
  [token dispatch unexpected?]
  (locking lock
    (when (and (not (bc/active?)) (forget! token))
      (log/warn (if unexpected? "Carabiner closed the connection" "The connection to Carabiner closed")
                "- Link is off until Carabiner connects again")
      (dispatch {:event/type :timing/carabiner-connected :connected? false}))))

(defn- register!
  "Make the connection just connected the owner's: a status listener, its
   reports through dispatch, and a disconnection listener (closed!), both
   registered with beat-carabiner, and its beat checks (check-beat!) on the
   Link thread every alignment-interval-ms, and soon after each change of
   tempo the status listener hears. Under lock. True if Carabiner is still
   connected with them in; if it closed before its close could be heard,
   the connection is over at once."
  [dispatch generation]
  (let [token (Object.)
        report (create-status-listener dispatch state/get-raw-state)
        check #(check-beat! token dispatch generation)
        soon (atom nil)
        tempo (atom ::none)
        listener (fn [{:keys [link-bpm] :as status}]
                   (report status)
                   (let [[heard _] (reset-vals! tempo link-bpm)]
                     (when (and (not= ::none heard) (not= heard link-bpm))
                       (check-soon! soon generation check))))
        on-closed (fn [unexpected?] (closed! token dispatch unexpected?))]
    (reset! !connection {:token token :listener listener :on-closed on-closed :soon soon
                         :checks (schedule! generation alignment-interval-ms alignment-interval-ms check)})
    (bc/add-disconnection-listener on-closed)
    ;; connect can receive the first tempo before registration. Seed it
    ;; without letting an older snapshot overtake a later callback.
    (locking report
      (bc/add-status-listener listener)
      (listener (bc/state)))
    (or (boolean (bc/active?))
        (do (forget! token)
            (log/warn "Carabiner closed the connection as it connected")
            false))))

(defn- connect-now!
  "Connect to Carabiner, unless connected already (nothing new opens), with
   its listeners and checks (register!), and send how it went through
   dispatch: :timing/carabiner-connected {:connected?}, under the lock, so a
   close of this connection is reported after it. One that release! came
   during is undone: disconnected, unless release! has the connection to
   close itself, or an app opened since (its own connect takes it over)."
  [dispatch generation]
  (let [connected? (or (and (holds-connection?) (bc/active?))
                       (connect-carabiner!))]
    (locking lock
      (dispatch
       {:event/type :timing/carabiner-connected
        :connected? (boolean
                     (when connected?
                       (cond
                         (not (current? generation))
                         (when (and (= :closed (:state @!runtime)) (not (holds-connection?)))
                           (bc/disconnect)
                           (log/info "Carabiner connected after Link was released: disconnected")
                           false)

                         (holds-connection?) true

                         (register! dispatch generation)
                         (do (log/info "Carabiner daemon connected (Link not yet enabled)")
                             true))))}))))

(defn- run-connect!
  "Connect unless release! came since it was asked for (connect-now!)."
  [dispatch generation]
  (when (current? generation)
    (try
      (connect-now! dispatch generation)
      (catch Throwable t
        (log/error t "Connecting to Carabiner failed")))))

(defn- daemon-factory [thread-name]
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. ^Runnable r ^String thread-name)
        (.setDaemon true)))))

(defn connect!
  "Queue a connect to Carabiner for the Link thread (the :link/connect
   effect, events/core.clj; its value, request, is {}): dispatch gets
   :timing/carabiner-connected {:connected?}, then the status listener's
   reports and the beat checks' corrections (see the ns doc). False
   (logged) after release!, until open!."
  [_request dispatch]
  (locking lock
    (if (= :closed (:state @!runtime))
      (do (log/warn "Link is released (the app stopped); dropping a connect to Carabiner")
          false)
      (do
        (when (= :idle (:state @!runtime))
          (swap! !runtime assoc
                 :state :open
                 :executor (doto (ScheduledThreadPoolExecutor. 1 ^ThreadFactory (daemon-factory "link-carabiner"))
                             (.setExecuteExistingDelayedTasksAfterShutdownPolicy false)
                             (.setRemoveOnCancelPolicy true))))
        (let [{:keys [generation ^ExecutorService executor]} @!runtime]
          ;; With the asker's bindings, as a future would have them
          (.execute executor ^Runnable (bound-fn* #(run-connect! dispatch generation)))
          true)))))

(defn open!
  "Take connects again after release! (app/start!, start-test!): the Link
   thread starts with the next one."
  []
  (locking lock
    (when (= :closed (:state @!runtime))
      (swap! !runtime assoc :state :idle))))

(defn release!
  "Remove the listeners, stop the beat checks, close the query connection
   and disconnect from Carabiner, once, on this thread, whatever app state
   says (app/stop!): a connect or check not yet run is dropped, the one
   running gets up to timeout-ms to finish (what a connect connects after
   that is undone at once), and later connects are dropped until open!. Its
   own close isn't reported (the disconnection listener goes first). Writes
   no app state. Idempotent; nil."
  [timeout-ms]
  (let [{:keys [^ExecutorService executor]}
        (locking lock
          (let [old @!runtime]
            (reset! !runtime {:state :closed :generation (inc (:generation old))})
            old))]
    (when executor
      (.shutdown executor)
      (when-not (.awaitTermination executor timeout-ms TimeUnit/MILLISECONDS)
        (log/warn "Link: the connect to Carabiner did not finish within" timeout-ms "ms")
        (.shutdownNow executor)))
    (when-let [{:keys [listener on-closed] :as conn} (locking lock (first (reset-vals! !connection nil)))]
      (stop-checks! conn)
      (try
        (bc/remove-disconnection-listener on-closed)
        (bc/remove-status-listener listener)
        (bc/disconnect)
        (log/info "Carabiner daemon disconnected")
        (catch Exception e
          (log/warn e "Error disconnecting from Carabiner:" (.getMessage e)))))
    (close-query!)
    nil))


;; Link Enable/Disable
;; For read-only Link access, we don't need set-sync-mode.
;; The connection to Carabiner already gives us Link BPM/peers.
;; The "enabled" flag just controls whether we display/use the data.


(defn enable-link!
  "Enables Link data display and BPM sync capability.
   Requires Carabiner to be connected first.
   
   Note: We don't call set-sync-mode because that requires VirtualCDJ
   (for Pioneer DJ equipment sync). For read-only Ableton Link access,
   just connecting to Carabiner is sufficient.
   
   Returns updated state map."
  [link-state]
  (if-not (:carabiner-connected? link-state)
    (do
      (log/warn "Cannot enable Link - Carabiner not connected")
      link-state)
    (do
      (log/info "Ableton Link enabled (read-only mode)")
      (assoc link-state :link-enabled? true))))

(defn disable-link!
  "Disables Link data display and BPM sync.
   
   Returns updated state map."
  [link-state]
  (log/info "Ableton Link disabled")
  (-> link-state
      (assoc :link-enabled? false)))


;; Connection Status


(defn carabiner-connected?
  "Returns true if Carabiner daemon is connected."
  [link-state]
  (:carabiner-connected? link-state false))

(defn link-enabled?
  "Returns true if Link sync is enabled."
  [link-state]
  (:link-enabled? link-state false))


;; BPM Sync Control (separate from Link connection)


(defn enable-sync
  "Enables BPM sync from Link to app.
   This controls whether received Link BPM updates the app BPM.
   Returns updated state."
  [link-state]
  (assoc link-state :sync-enabled? true))

(defn disable-sync
  "Disables BPM sync from Link to app.
   Link data is still received, just not applied to app BPM.
   Returns updated state."
  [link-state]
  (assoc link-state :sync-enabled? false))

(defn sync-enabled?
  "Returns true if BPM sync (Link BPM -> app BPM) is enabled."
  [link-state]
  (:sync-enabled? link-state false))


(defn connected?
  "DEPRECATED: Use carabiner-connected? or link-enabled?.
   
   Returns true if both Carabiner is connected AND Link is enabled."
  [link-state]
  (and (:carabiner-connected? link-state false)
       (:link-enabled? link-state false)))


(def initial-state
  "Default Link state structure."
  {:carabiner-connected? false
   :link-enabled? false
   :sync-enabled? false
   :link-bpm nil
   :link-peers 0})
