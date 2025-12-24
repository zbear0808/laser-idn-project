(ns laser-show.backend.multi-projector-stream
  "Multi-projector streaming coordinator.
   Manages multiple streaming engines (one per projector) and coordinates
   frame distribution based on zone routing.
   
   Effect Application Order:
   1. Cue effects (applied by cue system before routing)
   2. Zone group effects (applied during routing)
   3. Zone effects (applied during routing)
   4. Projector effects (applied here after routing)"
  (:require [laser-show.backend.streaming-engine :as engine]
            [laser-show.backend.projectors :as projectors]
            [laser-show.backend.zones :as zones]
            [laser-show.backend.zone-router :as router]
            [laser-show.backend.cues :as cues]
            [laser-show.animation.types :as t]
            [laser-show.animation.effects :as fx]
            [laser-show.state.atoms :as state]))

;; ============================================================================
;; Multi-Engine State
;; ============================================================================

(defonce !multi-engine-state
  (atom {:engines {}           ; projector-id -> engine
         :running? false
         :frame-provider nil   ; Function that returns base frame
         :target-provider nil  ; Function that returns current target
         :log-callback nil
         :start-time-ms nil})) ; Start time for consistent timing

;; ============================================================================
;; Timing
;; ============================================================================

(defn get-current-time-ms
  "Get current time relative to engine start."
  []
  (let [start-time (:start-time-ms @!multi-engine-state)]
    (if start-time
      (- (System/currentTimeMillis) start-time)
      0)))

;; ============================================================================
;; Frame Provider Integration
;; ============================================================================

(defn- create-projector-frame-provider
  "Create a frame provider for a specific projector.
   This wraps the base frame provider and applies zone transformations."
  [base-frame-provider target-provider projector-id]
  (fn []
    (when-let [base-frame (base-frame-provider)]
      (let [target (or (target-provider) router/default-target)
            projector-frames (router/prepare-projector-frames base-frame target)]
        (get projector-frames projector-id)))))

(defn- create-projector-frame-provider-with-effects
  "Create a frame provider for a specific projector with full effect chain support.
   
   This wraps the base frame provider and applies:
   1. Zone group effects (via router)
   2. Zone effects (via router)
   3. Projector effects (applied here)
   
   Note: Cue effects should already be applied by the base-frame-provider."
  [base-frame-provider target-provider projector-id]
  (fn []
    (when-let [base-frame (base-frame-provider)]
      (let [target (or (target-provider) router/default-target)
            time-ms (get-current-time-ms)
            bpm (state/get-bpm)
            ;; Apply zone group and zone effects during routing
            projector-frames (router/prepare-projector-frames-with-effects 
                              base-frame target time-ms bpm)
            routed-frame (get projector-frames projector-id)]
        ;; Apply projector-level effects (calibration)
        (when routed-frame
          (projectors/apply-projector-effects projector-id routed-frame time-ms bpm))))))

;; ============================================================================
;; Engine Management
;; ============================================================================

(defn- create-engine-for-projector
  "Create a streaming engine for a specific projector."
  [projector base-frame-provider target-provider opts]
  (let [projector-id (:id projector)
        use-effects? (get opts :use-effects? true)
        frame-provider (if use-effects?
                         (create-projector-frame-provider-with-effects
                          base-frame-provider
                          target-provider
                          projector-id)
                         (create-projector-frame-provider 
                          base-frame-provider 
                          target-provider 
                          projector-id))]
    (engine/create-engine
     (:address projector)
     frame-provider
     :fps (or (:fps opts) 30)
     :port (:port projector)
     :channel-id (:channel-id projector)
     :log-callback (:log-callback opts))))

(defn- start-engine-for-projector!
  "Start the streaming engine for a projector."
  [projector-id]
  (when-let [eng (get-in @!multi-engine-state [:engines projector-id])]
    (engine/start! eng)))

(defn- stop-engine-for-projector!
  "Stop the streaming engine for a projector."
  [projector-id]
  (when-let [eng (get-in @!multi-engine-state [:engines projector-id])]
    (engine/stop! eng)))

;; ============================================================================
;; Multi-Engine Lifecycle
;; ============================================================================

(defn create-multi-engine
  "Create a multi-projector streaming coordinator.
   
   Parameters:
   - base-frame-provider: Function that returns the current base LaserFrame
   - target-provider: Function that returns the current target specification
   - opts: Optional map with:
     - :fps - Target frames per second (default 30)
     - :log-callback - Optional function called with each packet for logging
     - :use-effects? - Whether to apply effect chains (default true)
   
   Returns the multi-engine state."
  [base-frame-provider target-provider & {:keys [fps log-callback use-effects?]
                                           :or {fps 30 use-effects? true}}]
  (let [active-projectors (projectors/get-active-projectors)
        engines (into {}
                      (map (fn [proj]
                             [(:id proj)
                              (create-engine-for-projector 
                               proj 
                               base-frame-provider 
                               target-provider
                               {:fps fps 
                                :log-callback log-callback
                                :use-effects? use-effects?})])
                           active-projectors))]
    (reset! !multi-engine-state
            {:engines engines
             :running? false
             :frame-provider base-frame-provider
             :target-provider target-provider
             :log-callback (atom log-callback)
             :start-time-ms nil})
    @!multi-engine-state))

(defn start-multi-engine!
  "Start all streaming engines."
  []
  (when-not (:running? @!multi-engine-state)
    ;; Set start time for consistent timing across all projectors
    (swap! !multi-engine-state assoc :start-time-ms (System/currentTimeMillis))
    (doseq [[proj-id _] (:engines @!multi-engine-state)]
      (start-engine-for-projector! proj-id))
    (swap! !multi-engine-state assoc :running? true)
    (println "Multi-projector streaming started for" 
             (count (:engines @!multi-engine-state)) "projectors")))

(defn stop-multi-engine!
  "Stop all streaming engines."
  []
  (when (:running? @!multi-engine-state)
    (doseq [[proj-id _] (:engines @!multi-engine-state)]
      (stop-engine-for-projector! proj-id))
    (swap! !multi-engine-state assoc :running? false :start-time-ms nil)
    (println "Multi-projector streaming stopped")))

(defn running?
  "Check if the multi-engine is running."
  []
  (:running? @!multi-engine-state))

;; ============================================================================
;; Dynamic Projector Management
;; ============================================================================

(defn add-projector-engine!
  "Add a streaming engine for a newly registered projector."
  [projector-id]
  (when-let [projector (projectors/get-projector projector-id)]
    (when (and (:running? @!multi-engine-state)
               (not (get-in @!multi-engine-state [:engines projector-id])))
      (let [base-frame-provider (:frame-provider @!multi-engine-state)
            target-provider (:target-provider @!multi-engine-state)
            log-callback @(:log-callback @!multi-engine-state)
            new-engine (create-engine-for-projector 
                        projector 
                        base-frame-provider 
                        target-provider
                        {:log-callback log-callback})]
        (swap! !multi-engine-state assoc-in [:engines projector-id] new-engine)
        (engine/start! new-engine)
        (println "Added streaming engine for projector:" projector-id)))))

(defn remove-projector-engine!
  "Remove and stop the streaming engine for a projector."
  [projector-id]
  (when-let [eng (get-in @!multi-engine-state [:engines projector-id])]
    (engine/stop! eng)
    (swap! !multi-engine-state update :engines dissoc projector-id)
    (println "Removed streaming engine for projector:" projector-id)))

(defn refresh-engines!
  "Refresh engines to match current active projectors.
   Adds engines for new projectors, removes engines for inactive ones."
  []
  (let [active-proj-ids (set (projectors/get-active-projector-ids))
        current-engine-ids (set (keys (:engines @!multi-engine-state)))
        to-add (clojure.set/difference active-proj-ids current-engine-ids)
        to-remove (clojure.set/difference current-engine-ids active-proj-ids)]
    (doseq [proj-id to-remove]
      (remove-projector-engine! proj-id))
    (doseq [proj-id to-add]
      (add-projector-engine! proj-id))))

;; ============================================================================
;; Statistics
;; ============================================================================

(defn get-all-stats
  "Get streaming statistics for all projectors.
   Returns a map of projector-id -> stats."
  []
  (into {}
        (map (fn [[proj-id eng]]
               [proj-id (engine/get-stats eng)])
             (:engines @!multi-engine-state))))

(defn get-projector-stats
  "Get streaming statistics for a specific projector."
  [projector-id]
  (when-let [eng (get-in @!multi-engine-state [:engines projector-id])]
    (engine/get-stats eng)))

(defn get-total-frames-sent
  "Get total frames sent across all projectors."
  []
  (reduce + (map #(:frames-sent (val %)) (get-all-stats))))

;; ============================================================================
;; Logging
;; ============================================================================

(defn set-log-callback!
  "Set the logging callback for all engines."
  [callback]
  (reset! (:log-callback @!multi-engine-state) callback)
  (doseq [[_ eng] (:engines @!multi-engine-state)]
    (engine/set-log-callback! eng callback)))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn create-and-start!
  "Create and immediately start a multi-projector streaming coordinator."
  [base-frame-provider target-provider & opts]
  (apply create-multi-engine base-frame-provider target-provider opts)
  (start-multi-engine!)
  @!multi-engine-state)

(defn get-engine-count
  "Get the number of active streaming engines."
  []
  (count (:engines @!multi-engine-state)))

(defn get-engine-projector-ids
  "Get the projector IDs that have active engines."
  []
  (keys (:engines @!multi-engine-state)))

;; ============================================================================
;; Cue Integration
;; ============================================================================

(defn create-cue-target-provider
  "Create a target provider that reads from the active cue.
   Returns the target from the currently active cue, or default-target if none."
  []
  (fn []
    (if-let [active-cue (cues/get-active-cue)]
      (:target active-cue)
      router/default-target)))

;; ============================================================================
;; Effect-Enabled Frame Provider Helpers
;; ============================================================================

(defn create-cue-frame-provider-with-effects
  "Create a frame provider that reads from the active cue and applies cue effects.
   
   This is useful when you want to use the cue system with full effect support.
   The returned function provides frames with cue effects applied.
   Zone group, zone, and projector effects are applied by the multi-engine."
  []
  (fn []
    (when-let [active-cue (cues/get-active-cue)]
      (let [time-ms (get-current-time-ms)]
        (cues/get-cue-frame-with-effects (:id active-cue) time-ms (state/get-bpm))))))

(defn sync-bpm-to-cue!
  "Sync the global BPM to the active cue's BPM setting.
   Returns the new BPM or nil if no active cue."
  []
  (when-let [active-cue (cues/get-active-cue)]
    (when-let [cue-bpm (:bpm active-cue)]
      (state/set-bpm! cue-bpm)
      cue-bpm)))
