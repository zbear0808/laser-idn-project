(ns laser-show.input.link
  "Ableton Link synchronization service using beat-carabiner.
   
   This is a READ-ONLY integration - we receive BPM and peers from Link
   (not its beat position: beat-carabiner gives none, see
   get-link-beat-position) but never push our tempo, or a beat, to the
   Link session.
   
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
   5. Does NOT call set-link-tempo, lock-tempo or beat-at-time (read-only)
   
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
   - release! (app/stop!) removes the listeners and disconnects, once,
     whatever app state says, and drops connects until open! (app/start!,
     start-test!); what a connect running then connects is undone at once.
     Before the first release! the Link thread starts with the first
     connect, so tests without an app can use it.
   
   The rest of this module is pure functions for Link state management, on
   [:backend :link] (state/domains.clj)."
  (:require [clojure.tools.logging :as log]
            [beat-carabiner.core :as bc]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex])
  (:import [java.util.concurrent ExecutorService Executors ThreadFactory TimeUnit]))


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


;; The connection to Carabiner: its owner
;; Carabiner is the underlying daemon - connects automatically on startup


(defonce ^{:private true
           :doc "The connection connect! made, or nil: {:token :listener (its
   status listener) :on-closed (its disconnection listener)}, both
   registered with beat-carabiner."}
  !connection
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

(defn- forget!
  "The connection with token is over: unless it is gone already (release!,
   a close heard before), it isn't the owner's any more and its listeners
   are removed. True if it was the owner's."
  [token]
  (locking lock
    (let [{:keys [listener on-closed] :as conn} @!connection]
      (when (identical? token (:token conn))
        (reset! !connection nil)
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
   registered with beat-carabiner. Under lock. True if Carabiner is still
   connected with them in; if it closed before its close could be heard,
   the connection is over at once."
  [dispatch]
  (let [token (Object.)
        listener (create-status-listener dispatch state/get-raw-state)
        on-closed (fn [unexpected?] (closed! token dispatch unexpected?))]
    (reset! !connection {:token token :listener listener :on-closed on-closed})
    (bc/add-disconnection-listener on-closed)
    ;; connect can receive the first tempo before registration. Seed it
    ;; without letting an older snapshot overtake a later callback.
    (locking listener
      (bc/add-status-listener listener)
      (listener (bc/state)))
    (or (boolean (bc/active?))
        (do (forget! token)
            (log/warn "Carabiner closed the connection as it connected")
            false))))

(defn- connect-now!
  "Connect to Carabiner, unless connected already (nothing new opens), with
   its listeners (register!), and send how it went through dispatch:
   :timing/carabiner-connected {:connected?}, under the lock, so a close of
   this connection is reported after it. One that release! came during is
   undone: disconnected, unless release! has the connection to close itself,
   or an app opened since (its own connect takes it over)."
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

                         (register! dispatch)
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
   reports (see the ns doc). False (logged) after release!, until open!."
  [_request dispatch]
  (locking lock
    (if (= :closed (:state @!runtime))
      (do (log/warn "Link is released (the app stopped); dropping a connect to Carabiner")
          false)
      (do
        (when (= :idle (:state @!runtime))
          (swap! !runtime assoc
                 :state :open
                 :executor (Executors/newSingleThreadExecutor (daemon-factory "link-carabiner"))))
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
  "Remove the listeners and disconnect from Carabiner, once, on this
   thread, whatever app state says (app/stop!): a connect not yet run is
   dropped, the one running gets up to timeout-ms to finish (what it
   connects after that is undone at once), and later connects are dropped
   until open!. Its own close isn't reported (the disconnection listener
   goes first). Writes no app state. Idempotent; nil."
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
    (when-let [{:keys [listener on-closed]} (locking lock (first (reset-vals! !connection nil)))]
      (try
        (bc/remove-disconnection-listener on-closed)
        (bc/remove-status-listener listener)
        (bc/disconnect)
        (log/info "Carabiner daemon disconnected")
        (catch Exception e
          (log/warn e "Error disconnecting from Carabiner:" (.getMessage e)))))
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


;; Beat Position Query
;;
;; beat-carabiner 8.0.0 has no way to read Link's beat. state (and so what
;; its status listeners get) holds :link-bpm and :link-peers only: of each
;; Carabiner status, which carries the beat too (:beat, :start), it keeps
;; :bpm and :peers. Its phase probe (phase-at-time) is private, for lining
;; a Pioneer player up with Link. And its beat-at-time is no query: it
;; sends Carabiner "beat-at-time t 4.0" (t on Link's clock: System/nanoTime
;; in microseconds, not the wall clock), returns nil (the socket write's),
;; and when the answer comes back it moves the whole Link session's beat
;; grid so a beat falls at t, unless one nearly does (Carabiner's
;; force-beat-at-time: every peer's playback shifts). This integration is
;; read only, so it never calls it.


(defn get-link-beat-position
  "Link's beat phase now (0.0-1.0 within the beat), or nil when it isn't
   known: with beat-carabiner 8.0.0, always (see above). Sends nothing to
   Carabiner."
  []
  nil)

(defn sync-to-downbeat!
  "Synchronizes playback to the next downbeat.
   Uses the phase-offset-target mechanism for smooth correction.
   
   This is a one-shot sync operation that calculates the phase
   offset needed to align with Link's next downbeat. While Link's beat
   phase isn't known (get-link-beat-position: with beat-carabiner 8.0.0,
   never), it does nothing."
  [dispatch-fn accumulated-beats]
  (try
    (when-let [link-beat-phase (get-link-beat-position)]
      (let [;; Calculate how far we are into the current beat
            internal-phase (mod accumulated-beats 1.0)
            ;; Calculate correction needed to align with Link
            phase-diff (- link-beat-phase internal-phase)
            ;; Normalize to -0.5 to 0.5 range (shortest path)
            correction (cond
                         (> phase-diff 0.5)  (- phase-diff 1.0)
                         (< phase-diff -0.5) (+ phase-diff 1.0)
                         :else phase-diff)]
        ;; Dispatch phase offset target change
        (dispatch-fn {:event/type :timing/set-phase-offset-target
                      :offset correction})))
    (catch Exception e
      (log/error e "Error syncing to downbeat:" (.getMessage e)))))

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
