(ns laser-show.backend.multi-engine
  "Multi-engine management for streaming to multiple projectors.
   
   Each projector gets its own streaming engine with a projector-specific
   frame provider. The frame provider uses the routing system to determine
   which cues should be sent to each projector.
   
   SIMPLIFIED ARCHITECTURE (v2):
   - One streaming engine per enabled projector (and virtual projector)
   - Each engine has a frame provider that:
     1. Gets active cues and their destination zone groups
     2. Uses routing/core to determine if this projector/VP matches
     3. Generates frame if matched
     4. Applies projector calibration effects (color curves + corner-pin)
   
   Corner-pin is now directly on projectors/VPs, eliminating zones.
   Virtual projectors inherit color curves from parent but have
   independent corner-pin for things like graphics/crowd scanning.
   
   The multi-engine state is stored in [:backend :streaming :multi-engine-state]"
  (:require [clojure.tools.logging :as log]
            [laser-show.backend.streaming-engine :as engine]
            [laser-show.state.core :as state]
            [laser-show.state.queries :as queries]
            [laser-show.routing.core :as routing]
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
  "Create a frame provider function for a specific projector or virtual projector.
   
   The frame provider:
   1. Gets the current active cue and its destination zone group
   2. Checks if this projector/VP belongs to the target zone group
   3. Generates the frame if matched
   4. Applies projector calibration effects (color curves + corner-pin)
   
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
            
            ;; Get projectors and virtual projectors for routing
            projectors-items (get raw-state :projectors {})
            virtual-projectors (get raw-state :virtual-projectors {})
            
            ;; For now, treat the whole cue chain as "the cue" for routing
            ;; In future, each item in the chain could have its own destination-zone
            ;; For now, default destination is :all zone group
            cue-for-routing {:id :active-cue
                             :destination-zone {:mode :zone-group
                                                :zone-group-id :all}
                             :effects []}
            
            ;; Build routing map for this cue
            ;; Returns: #{projector-id-or-vp-id ...}
            matching-outputs (routing/build-routing-map cue-for-routing
                                                        projectors-items
                                                        virtual-projectors)]
        
        (if (contains? matching-outputs projector-id)
          ;; This projector/VP receives the cue - generate frame
          (let [base-frame (frame-service/generate-current-frame)]
            (when base-frame
              ;; Apply projector effects (color curves + corner-pin)
              (apply-projector-effects base-frame projector-id
                                       elapsed bpm trigger-time timing-ctx)))
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
