(ns laser-show.input.link
  "Ableton Link synchronization service using beat-carabiner.
   
   This is a READ-ONLY integration - we receive BPM and beat position
   from Link but never push our tempo to the Link session.
   
   The service:
   1. Manages connection to Carabiner (embedded lib-carabiner)
   2. Listens for Link tempo and beat updates
   3. Dispatches events to update app state
   4. Does NOT call set-link-tempo or lock-tempo
   
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
   - :link-peers - Number of peers in the Link session"
  [dispatch-fn get-state-fn]
  (fn [status]
    (let [link-state (get-state-fn)
          new-bpm (:link-bpm status)
          new-peers (:link-peers status 0)
          old-bpm (:link-bpm link-state)
          old-peers (:link-peers link-state 0)
          sync-enabled? (:sync-enabled? link-state)]
      ;; Always update Link BPM state (for UI display)
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


;; Connection Lifecycle


(defn start-link!
  "Starts the Link connection and registers status listener.
   Returns updated state map with stored listener reference.
   
   Note: This function performs side effects (network connection)."
  [link-state dispatch-fn get-state-fn]
  (try
    ;; Initialize beat-carabiner connection
    (bc/connect)
    
    ;; Register status listener and store reference for cleanup
    (let [listener (create-status-listener dispatch-fn get-state-fn)]
      (bc/add-status-listener listener)
      
      (log/info "Ableton Link connected")
      (-> link-state
          (assoc :connected? true)
          (assoc :listener listener)))
    (catch Exception e
      (log/error e "Error starting Link connection:" (.getMessage e))
      (assoc link-state :connected? false))))

(defn stop-link!
  "Stops the Link connection and removes status listener.
   Returns updated state map."
  [link-state]
  (try
    ;; Remove listener if it exists
    (when-let [listener (:listener link-state)]
      (bc/remove-status-listener listener))
    
    ;; Disconnect from Carabiner
    (bc/disconnect)
    
    (log/info "Ableton Link disconnected")
    (-> link-state
        (assoc :connected? false)
        (assoc :link-bpm nil)
        (assoc :link-peers 0)
        (dissoc :listener))
    (catch Exception e
      (log/error e "Error stopping Link connection:" (.getMessage e))
      link-state)))


;; Connection Status


(defn connected?
  "Returns true if Link is connected."
  [link-state]
  (:connected? link-state false))


;; BPM Sync Control


(defn enable-sync
  "Enables BPM sync from Link.
   Returns updated state."
  [link-state]
  (assoc link-state :sync-enabled? true))

(defn disable-sync
  "Disables BPM sync from Link.
   Returns updated state."
  [link-state]
  (assoc link-state :sync-enabled? false))

(defn sync-enabled?
  "Returns true if BPM sync is enabled."
  [link-state]
  (:sync-enabled? link-state false))


;; Beat Position Query


(defn get-link-beat-position
  "Gets the current beat position from Link (0.0-1.0 within beat).
   Returns nil if not connected."
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


;; Initialization


(def initial-state
  "Default Link state structure."
  {:connected? false
   :sync-enabled? false
   :link-bpm nil})

(defn init
  "Initializes Link state.
   Optionally connects if auto-connect? is true.
   Returns initialized state."
  ([link-state]
   (init link-state false nil nil))
  ([link-state auto-connect? dispatch-fn get-state-fn]
   (if auto-connect?
     (start-link! link-state dispatch-fn get-state-fn)
     link-state)))

(defn shutdown
  "Shuts down Link system.
   Returns cleaned up state."
  [link-state]
  (stop-link! link-state))
