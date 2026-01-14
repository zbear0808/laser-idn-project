(ns laser-show.backend.multi-engine
  "Multi-engine management for streaming to multiple projectors.
   
   Each projector gets its own streaming engine with a zone-specific
   frame provider. The frame provider uses the routing system to determine
   which cues should be sent to each projector's zones.
   
   Architecture:
   - One streaming engine per enabled projector
   - Each engine has a frame provider that:
     1. Gets active cues
     2. Uses routing/core to determine which zone matches for this projector
     3. Generates frame for the matched zone
     4. Applies zone effects (from [:chains :zone-effects zone-id :items])
     5. Applies projector calibration effects
   
   Zone effects use the same effects system as projector and cue effects,
   allowing any calibration effect (corner-pin, flip, scale, etc.) to be
   applied at the zone level.
   
   The multi-engine state is stored in [:backend :streaming :multi-engine-state]"
  (:require [clojure.tools.logging :as log]
            [laser-show.backend.streaming-engine :as engine]
            [laser-show.state.core :as state]
            [laser-show.state.queries :as queries]
            [laser-show.routing.core :as routing]
            [laser-show.animation.types :as t]
            [laser-show.animation.effects :as effects]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.idn.output-config :as output-config]))


;; Multi-Engine State Structure
;;
;; The multi-engine state is a map of:
;; {:engines {projector-id engine-instance ...}
;;  :running? true/false
;;  :start-time-ms timestamp}


;; Frame Provider Creation


(defn- apply-zone-effects
  "Apply zone effects to a frame using the standard effects system.
   
   Zone effects are stored in [:chains :zone-effects zone-id :items]
   and are processed identically to projector effects. Recommended
   zone effects include: corner-pin, flip, scale, offset, rotation.
   
   This uses the same effects/apply-effect-chain function as projector
   effects, ensuring consistent behavior and allowing all calibration
   effects to be used on zones."
  [frame zone-id elapsed-ms bpm trigger-time timing-ctx]
  (if-not frame
    frame
    (let [raw-state (state/get-raw-state)
          zone-effects (get-in raw-state [:chains :zone-effects zone-id :items] [])]
      (if (seq zone-effects)
        (try
          (effects/apply-effect-chain frame {:effects zone-effects}
                                      elapsed-ms bpm trigger-time timing-ctx)
          (catch Exception e
            (log/error "Error applying zone effects:" (.getMessage e))
            frame))
        frame))))

(defn- apply-projector-effects
  "Apply projector-level calibration effects to a frame.
   
   Projector effects are stored in [:chains :projector-effects projector-id :items]
   These include RGB curves, corner-pin calibration, etc."
  [frame projector-id elapsed-ms bpm trigger-time timing-ctx]
  (let [raw-state (state/get-raw-state)
        projector-effects (get-in raw-state [:chains :projector-effects projector-id :items] [])]
    (if (seq projector-effects)
      (try
        (effects/apply-effect-chain frame {:effects projector-effects}
                                    elapsed-ms bpm trigger-time timing-ctx)
        (catch Exception e
          (log/error "Error applying projector effects:" (.getMessage e))
          frame))
      frame)))

(defn- create-projector-frame-provider
  "Create a frame provider function for a specific projector.
   
   The frame provider:
   1. Gets the current active cue
   2. Uses routing to determine which zone (if any) matches for this projector
   3. Generates the frame for that zone
   4. Applies zone effects (using standard effects system)
   5. Applies projector calibration effects
   
   Returns a zero-arity function that returns a LaserFrame."
  [projector-id]
  (fn []
    (try
      (let [raw-state (state/get-raw-state)
            playing? (get-in raw-state [:playback :playing?])
            
            ;; If not playing, return empty frame
            _ (when-not playing? (throw (ex-info "Not playing" {:skip true})))
            
            ;; Get active cell and cue chain
            active-cell (get-in raw-state [:playback :active-cell])
            _ (when-not active-cell (throw (ex-info "No active cell" {:skip true})))
            
            [col row] active-cell
            cue-chain-data (get-in raw-state [:chains :cue-chains [col row]])
            _ (when-not (seq (:items cue-chain-data))
                (throw (ex-info "No cue items" {:skip true})))
            
            ;; Get timing info
            trigger-time (get-in raw-state [:playback :trigger-time] 0)
            elapsed (- (System/currentTimeMillis) trigger-time)
            bpm (get-in raw-state [:timing :bpm] 120.0)
            timing-ctx (frame-service/get-timing-context)
            
            ;; Get zones for routing
            zones-items (get-in raw-state [:zones :items] {})
            
            ;; For now, treat the whole cue chain as "the cue" for routing
            ;; In future, each item in the chain could have its own destination-zone
            ;; For now, default destination is :all zone group
            cue-for-routing {:id :active-cue
                             :destination-zone {:mode :zone-group
                                                :zone-group-id :all}
                             :effects []}
            
            ;; Build routing map for this cue
            ;; Returns: {projector-id zone-id, ...}
            routing-map (routing/build-routing-map cue-for-routing zones-items)
            
            ;; Check if this projector is in the routing
            zone-id (get routing-map projector-id)]
        
        (if zone-id
          ;; This projector receives the cue - generate frame
          (let [;; Generate base frame from cue chain (same as current logic)
                base-frame (frame-service/generate-current-frame)]
            (when base-frame
              ;; Apply zone effects (using standard effects system)
              ;; Zone effects are stored in [:chains :zone-effects zone-id :items]
              (let [frame-with-zone-effects (apply-zone-effects base-frame zone-id
                                                                 elapsed bpm trigger-time timing-ctx)
                    ;; Apply projector effects
                    final-frame (apply-projector-effects frame-with-zone-effects projector-id
                                                         elapsed bpm trigger-time timing-ctx)]
                final-frame)))
          ;; This projector doesn't receive the cue - empty frame
          nil))
      
      (catch clojure.lang.ExceptionInfo e
        ;; Expected "skip" exceptions (not playing, no active cell, etc.)
        (when-not (:skip (ex-data e))
          (log/error "Frame provider error:" (.getMessage e)))
        nil)
      (catch Exception e
        (log/error "Unexpected frame provider error:" (.getMessage e))
        nil))))


;; Engine Management


(defn create-engine-for-projector
  "Create a streaming engine for a specific projector.
   
   Args:
   - projector-id: The projector's ID keyword
   - projector: The projector configuration map
   
   Returns: A streaming engine instance (not started)"
  [projector-id projector]
  (let [host (:host projector)
        port (or (:port projector) 7255)
        output-cfg (if-let [cfg (:output-config projector)]
                     (output-config/make-config
                       (or (:color-bit-depth cfg) 8)
                       (or (:xy-bit-depth cfg) 16))
                     output-config/default-config)
        frame-provider (create-projector-frame-provider projector-id)]
    (engine/create-engine host frame-provider
                          :port port
                          :output-config output-cfg)))

(defn create-engines
  "Create streaming engines for all enabled projectors.
   
   Returns: Map of projector-id -> engine"
  []
  (let [projectors (queries/projectors-items)]
    (into {}
      (for [[proj-id proj] projectors
            :when (:enabled? proj true)]
        [proj-id (create-engine-for-projector proj-id proj)]))))


;; Multi-Engine Lifecycle


(defn start-engines!
  "Start all streaming engines for enabled projectors.
   
   Creates engines for each enabled projector and starts them.
   Stores the multi-engine state in [:backend :streaming :multi-engine-state]"
  []
  (let [engines (create-engines)
        start-time (System/currentTimeMillis)]
    
    ;; Start each engine
    (doseq [[proj-id engine] engines]
      (try
        (engine/start! engine)
        (log/info "Started streaming engine for projector:" proj-id)
        (catch Exception e
          (log/error "Failed to start engine for projector" proj-id ":" (.getMessage e)))))
    
    ;; Store multi-engine state
    (state/assoc-in-state! [:backend :streaming :multi-engine-state]
                           {:engines engines
                            :running? true
                            :start-time-ms start-time})
    
    (log/info "Multi-engine streaming started for" (count engines) "projectors")
    engines))

(defn stop-engines!
  "Stop all streaming engines."
  []
  (let [multi-state (state/get-in-state [:backend :streaming :multi-engine-state])
        engines (:engines multi-state {})]
    
    ;; Stop each engine
    (doseq [[proj-id engine] engines]
      (try
        (engine/stop! engine)
        (log/info "Stopped streaming engine for projector:" proj-id)
        (catch Exception e
          (log/error "Failed to stop engine for projector" proj-id ":" (.getMessage e)))))
    
    ;; Clear multi-engine state
    (state/assoc-in-state! [:backend :streaming :multi-engine-state]
                           {:engines {}
                            :running? false
                            :start-time-ms nil})
    
    (log/info "Multi-engine streaming stopped")))

(defn restart-engines!
  "Restart all streaming engines.
   Useful when projector configuration changes."
  []
  (stop-engines!)
  (start-engines!))


;; Engine State Queries


(defn running?
  "Check if multi-engine streaming is running."
  []
  (get-in (state/get-raw-state) [:backend :streaming :multi-engine-state :running?] false))

(defn get-engine-count
  "Get the number of active streaming engines."
  []
  (count (get-in (state/get-raw-state) [:backend :streaming :multi-engine-state :engines] {})))

(defn get-engine-stats
  "Get stats for all streaming engines.
   
   Returns: Map of projector-id -> stats"
  []
  (let [engines (get-in (state/get-raw-state) [:backend :streaming :multi-engine-state :engines] {})]
    (into {}
      (for [[proj-id eng] engines]
        [proj-id (engine/get-stats eng)]))))

(defn get-start-time
  "Get the timestamp when multi-engine streaming was started."
  []
  (get-in (state/get-raw-state) [:backend :streaming :multi-engine-state :start-time-ms]))

(defn get-uptime-ms
  "Get how long multi-engine streaming has been running (in ms)."
  []
  (if-let [start-time (get-start-time)]
    (- (System/currentTimeMillis) start-time)
    0))


;; Dynamic Engine Updates


(defn add-projector-engine!
  "Add a streaming engine for a newly added projector.
   Call this when a projector is added while streaming is active."
  [projector-id projector]
  (when (running?)
    (let [engine (create-engine-for-projector projector-id projector)]
      (try
        (engine/start! engine)
        (state/swap-state!
          #(assoc-in % [:backend :streaming :multi-engine-state :engines projector-id] engine))
        (log/info "Added streaming engine for projector:" projector-id)
        (catch Exception e
          (log/error "Failed to add engine for projector" projector-id ":" (.getMessage e)))))))

(defn remove-projector-engine!
  "Remove the streaming engine for a removed projector.
   Call this when a projector is removed while streaming is active."
  [projector-id]
  (when (running?)
    (let [engines (get-in (state/get-raw-state) [:backend :streaming :multi-engine-state :engines] {})
          engine (get engines projector-id)]
      (when engine
        (try
          (engine/stop! engine)
          (state/swap-state!
            #(update-in % [:backend :streaming :multi-engine-state :engines] dissoc projector-id))
          (log/info "Removed streaming engine for projector:" projector-id)
          (catch Exception e
            (log/error "Failed to remove engine for projector" projector-id ":" (.getMessage e))))))))

(defn update-projector-engine!
  "Update the streaming engine for a projector when its config changes.
   Stops the old engine and creates a new one with updated settings."
  [projector-id projector]
  (when (running?)
    (remove-projector-engine! projector-id)
    (when (:enabled? projector true)
      (add-projector-engine! projector-id projector))))
