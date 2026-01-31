(ns laser-show.input.link
  "Ableton Link synchronization service using beat-carabiner.
   
   This is a READ-ONLY integration - we receive BPM and beat position
   from Link but never push our tempo to the Link session.
   
   Architecture:
   - Carabiner: Auto-connects on app startup (embedded lib-carabiner)
   - Link: User toggles via UI button (set-sync-mode :passive/:off)
   
   The service:
   1. Manages connection to Carabiner (embedded lib-carabiner)
   2. Enables/disables Link sync mode on user request
   3. Listens for Link tempo and beat updates
   4. Dispatches events to update app state
   5. Does NOT call set-link-tempo or lock-tempo (read-only)
   
   State is managed by the application state in domains.clj.
   This module provides pure functions for Link state management."
  (:require [clojure.tools.logging :as log]
            [beat-carabiner.core :as bc]))


;; BPM Change Threshold
;; Use threshold to avoid flooding events when BPM is stable


(def ^:const bpm-change-threshold 0.01)


;; Link Status Listener


(defn- bpm-changed?
  "Check if BPM has changed significantly.
   Returns true if abs(new-bpm - old-bpm) > threshold."
  [old-bpm new-bpm]
  (or (nil? old-bpm)
      (> (Math/abs (- new-bpm old-bpm)) bpm-change-threshold)))

(defn create-status-listener
  "Creates a status listener for Link updates.
   
   Args:
   - dispatch-fn: Function to dispatch events (receives event map)
   - get-state-fn: Function that returns current Link state
   
   Returns: listener function that processes Link status updates
   
   The status map from beat-carabiner includes:
   - :link-bpm - Current Link session BPM
   - :link-peers - Number of peers in the Link session
   - :sync-mode - Current sync mode (:off, :manual, :passive, :full)"
  [dispatch-fn get-state-fn]
  (fn [status]
    (let [link-state (get-state-fn)
          new-bpm (:link-bpm status)
          new-peers (:link-peers status 0)
          old-bpm (:link-bpm link-state)
          old-peers (:link-peers link-state 0)
          sync-enabled? (:sync-enabled? link-state)]
      ;; Always update Link BPM state (for UI display) when Link is enabled
      (when new-bpm
        (dispatch-fn {:event/type :timing/link-bpm-changed
                      :bpm new-bpm}))
      
      ;; Update peer count if changed
      (when (not= new-peers old-peers)
        (dispatch-fn {:event/type :timing/link-peers-changed
                      :peers new-peers}))
      
      ;; Only dispatch BPM change to app if sync is enabled and BPM changed significantly
      (when (and sync-enabled? 
                 new-bpm
                 (bpm-changed? old-bpm new-bpm))
        (dispatch-fn {:event/type :timing/set-bpm
                      :bpm new-bpm})))))


;; Carabiner Connection Lifecycle
;; Carabiner is the underlying daemon - connects automatically on startup


(defn connect-carabiner!
  "Connects to the Carabiner daemon (embedded lib-carabiner).
   This should be called once on app startup.
   Does NOT enable Link - that's a separate step.
   
   Returns updated state map."
  [link-state dispatch-fn get-state-fn]
  (try
    ;; Connect to Carabiner daemon
    (bc/connect)
    
    ;; Register status listener to receive updates
    (let [listener (create-status-listener dispatch-fn get-state-fn)]
      (bc/add-status-listener listener)
      
      (log/info "Carabiner daemon connected (Link not yet enabled)")
      (-> link-state
          (assoc :carabiner-connected? true)
          (assoc :listener listener)))
    (catch Exception e
      (log/error e "Error connecting to Carabiner:" (.getMessage e))
      (assoc link-state :carabiner-connected? false))))

(defn disconnect-carabiner!
  "Disconnects from the Carabiner daemon.
   This should be called on app shutdown.
   
   Returns updated state map."
  [link-state]
  (try
    ;; Remove listener if it exists
    (when-let [listener (:listener link-state)]
      (bc/remove-status-listener listener))
    
    ;; Disconnect from Carabiner
    (bc/disconnect)
    
    (log/info "Carabiner daemon disconnected")
    (-> link-state
        (assoc :carabiner-connected? false)
        (assoc :link-enabled? false)
        (assoc :link-bpm nil)
        (assoc :link-peers 0)
        (dissoc :listener))
    (catch Exception e
      (log/error e "Error disconnecting from Carabiner:" (.getMessage e))
      link-state)))


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


(defn get-link-beat-position
  "Gets the current beat position from Link (0.0-1.0 within beat).
   Returns nil if Carabiner not connected."
  []
  (try
    (when (bc/active?)
      ;; Call beat-at-time with current time in microseconds
      (let [current-time-us (* (System/currentTimeMillis) 1000)
            beat (bc/beat-at-time current-time-us)]
        (mod beat 1.0)))
    (catch Exception e
      (log/error e "Error getting Link beat position:" (.getMessage e))
      nil)))

(defn sync-to-downbeat!
  "Synchronizes playback to the next downbeat.
   Uses the phase-offset-target mechanism for smooth correction.
   
   This is a one-shot sync operation that calculates the phase
   offset needed to align with Link's next downbeat."
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


;; Legacy API (for backwards compatibility during transition)


(defn start-link!
  "DEPRECATED: Use connect-carabiner! followed by enable-link!.
   
   Starts Link by connecting to Carabiner and enabling sync mode.
   Returns updated state map."
  [link-state dispatch-fn get-state-fn]
  (-> link-state
      (connect-carabiner! dispatch-fn get-state-fn)
      (enable-link!)))

(defn stop-link!
  "DEPRECATED: Use disconnect-carabiner!.
   
   Stops Link by disabling sync and disconnecting from Carabiner.
   Returns updated state map."
  [link-state]
  (disconnect-carabiner! link-state))

(defn connected?
  "DEPRECATED: Use carabiner-connected? or link-enabled?.
   
   Returns true if both Carabiner is connected AND Link is enabled."
  [link-state]
  (and (:carabiner-connected? link-state false)
       (:link-enabled? link-state false)))


;; Initialization


(def initial-state
  "Default Link state structure."
  {:carabiner-connected? false
   :link-enabled? false
   :sync-enabled? false
   :link-bpm nil
   :link-peers 0})

(defn init
  "Initializes Link state.
   Optionally connects to Carabiner if auto-connect? is true.
   Does NOT enable Link - that's controlled by user via UI.
   Returns initialized state."
  ([link-state]
   (init link-state false nil nil))
  ([link-state auto-connect? dispatch-fn get-state-fn]
   (if auto-connect?
     (connect-carabiner! link-state dispatch-fn get-state-fn)
     link-state)))

(defn shutdown
  "Shuts down Link system - disconnects from Carabiner.
   Returns cleaned up state."
  [link-state]
  (disconnect-carabiner! link-state))
