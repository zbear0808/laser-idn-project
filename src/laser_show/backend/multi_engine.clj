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
            [clojure.string :as str]
            [clojure.set :as set]
            [laser-show.backend.streaming-engine :as engine]
            [laser-show.dev-config :as dev-config]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex]
            [laser-show.routing.core :as routing]
            [laser-show.routing.zone-effects :as ze]
            [laser-show.animation.effects :as effects]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.idn.output-config :as output-config]))


;; Routing Debug Logging
;;
;; We use an atom to throttle logging so we don't flood the console
;; Logging is controlled by dev-config/idn-stream-logging? (disabled by default)

(def ^:private routing-log-counter (atom 0))
(def ^:const ROUTING_LOG_INTERVAL 300) ;; Log routing info every N frames (~5 seconds at 60fps)


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
   1. Gets the current active cue chain and its destination zone
   2. Collects zone effects from all items in the chain
   3. Checks if this projector matches the FINAL target (after zone effects)
   4. Generates the frame if matched
   5. Applies projector calibration effects (color curves + corner-pin)
   
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
            zone-group-ids (set (keys (get raw-state :zone-groups {})))
            
            ;; Read actual destination zone from cue chain (no default fallback)
            ;; If no destination is specified, routes to nothing (empty set)
            destination-zone (:destination-zone cue-chain-data)
            
            ;; Collect effects from all items in chain
            ;; Zone effects (zone-reroute, zone-broadcast, zone-mirror) modify routing
            collected-effects (ze/collect-effects-from-cue-chain
                                (:items cue-chain-data))
            
            ;; Build cue for routing with real data
            cue-for-routing {:id :active-cue
                             :destination-zone destination-zone
                             :effects collected-effects}
            
            ;; Build routing map for this cue - with zone effect processing
            ;; Returns: Vector of output configs
            matching-outputs (routing/build-routing-map cue-for-routing
                                                        projectors-items
                                                        virtual-projectors
                                                        zone-group-ids)
            
            ;; Check if THIS projector is in the matching outputs
            matching-output-ids (set (map :projector-id matching-outputs))
            
            ;; Throttled debug logging for routing decisions
            log-count (swap! routing-log-counter inc)]
        
        ;; Log routing info periodically (every ~5 seconds) - only when IDN logging enabled
        (when (and (dev-config/idn-stream-logging?)
                   (zero? (mod log-count ROUTING_LOG_INTERVAL)))
          (log/debug (format "Routing debug [projector=%s]: destination-zone=%s, effects=%d, matching-outputs=%s, this-matches?=%s"
                             projector-id
                             (pr-str destination-zone)
                             (count collected-effects)
                             (pr-str (mapv :id matching-outputs))
                             (contains? matching-output-ids projector-id))))
        
        (if (contains? matching-output-ids projector-id)
          ;; This projector receives the cue - generate frame
          ;; IMPORTANT: skip-zone-filter? true bypasses preview zone filtering
          ;; so IDN streaming works regardless of preview settings
          (let [base-frame (frame-service/generate-current-frame {:skip-zone-filter? true})
                frame-point-count (when base-frame (count base-frame))]
            ;; Log frame generation periodically - only when IDN logging enabled
            (when (and (dev-config/idn-stream-logging?)
                       (zero? (mod log-count ROUTING_LOG_INTERVAL))
                       (= projector-id (first (sort (keys (get raw-state :projectors {}))))))
              (log/debug (format "Frame gen [%s]: base-frame-points=%s"
                                 projector-id
                                 (or frame-point-count "nil"))))
            (when base-frame
              ;; Apply projector effects (color curves + corner-pin)
              (apply-projector-effects base-frame projector-id
                                       elapsed bpm trigger-time timing-ctx)))
          ;; This projector doesn't match - no frame
          (do
            (when (and (dev-config/idn-stream-logging?)
                       (zero? (mod log-count ROUTING_LOG_INTERVAL))
                       (= projector-id (first (sort (keys (get raw-state :projectors {}))))))
              (log/debug (format "No match [%s]: projector zone-groups=%s"
                                 projector-id
                                 (pr-str (:zone-groups (get projectors-items projector-id))))))
            nil)))
      
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
   
   Returns: A streaming engine instance (not started), or nil if host is invalid
   
   NOTE: service-id targets the physical laser output on multi-head DACs.
   Each projector entry represents one service/output on the device."
  [projector-id projector]
  (let [host (:host projector)]
    (when (and host (not (str/blank? host)))
      (let [port (or (:port projector) 7255)
            ;; service-id targets the physical output on the DAC (0-255)
            ;; This is different from channel-id which is for logical multiplexing
            service-id (or (:service-id projector) 0)
            output-cfg (if-let [cfg (:output-config projector)]
                         (output-config/make-config
                           (or (:color-bit-depth cfg) 8)
                           (or (:xy-bit-depth cfg) 16))
                         output-config/default-config)
            frame-provider (create-projector-frame-provider projector-id)]
            (log/info (format "Creating engine for %s -> %s:%d service %d (channel %d)"
                              projector-id host port service-id service-id))
            (engine/create-engine host frame-provider
                                  :port port
                                  :channel-id service-id
                                  :service-id service-id
                                  :output-config output-cfg)))))

(defn create-engines
  "Create streaming engines for all enabled projectors.
   
   Returns: Map of projector-id -> engine (skips projectors with invalid hosts)"
  []
  (let [projectors (ex/projectors-items (state/get-raw-state))]
    (into {}
      (keep (fn [[proj-id proj]]
              (when (:enabled? proj true)
                (if-let [engine (create-engine-for-projector proj-id proj)]
                  [proj-id engine]
                  (do
                    (log/warn (format "Skipping projector %s - invalid or missing host: %s"
                                    proj-id (pr-str (:host proj))))
                    nil))))
            projectors))))


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

(defn refresh-engines!
  "Refresh streaming engines to match current projector state.
   
   This should be called when projector enabled state changes while streaming
   is already running. It will:
   1. Stop engines for projectors that are no longer enabled
   2. Create and start engines for newly enabled projectors
   
   Returns: Map of new engines (projector-id -> engine)"
  []
  (let [multi-state (state/get-in-state [:backend :streaming :multi-engine-state])
        current-engines (:engines multi-state {})
        running? (:running? multi-state false)]
    
    ;; Only refresh if streaming is actually running
    (when running?
      (let [start-time (or (:start-time-ms multi-state) (System/currentTimeMillis))
            ;; Get current desired engines based on enabled projectors
            desired-engines (create-engines)
            current-ids (set (keys current-engines))
            desired-ids (set (keys desired-engines))
            
            ;; Find engines to stop and start
            engines-to-stop (set/difference current-ids desired-ids)
            engines-to-start (set/difference desired-ids current-ids)]
        
        (log/info (format "Refreshing engines: stopping %d, starting %d"
                          (count engines-to-stop) (count engines-to-start)))
        
        ;; Stop engines for disabled projectors
        (doseq [proj-id engines-to-stop]
          (when-let [engine (get current-engines proj-id)]
            (try
              (engine/stop! engine)
              (log/info "Stopped engine for disabled projector:" proj-id)
              (catch Exception e
                (log/error "Failed to stop engine for" proj-id ":" (.getMessage e))))))
        
        ;; Start engines for newly enabled projectors
        (doseq [proj-id engines-to-start]
          (when-let [engine (get desired-engines proj-id)]
            (try
              (engine/start! engine)
              (log/info "Started engine for newly enabled projector:" proj-id)
              (catch Exception e
                (log/error "Failed to start engine for" proj-id ":" (.getMessage e))))))
        
        ;; Build new engines map: keep running engines that are still needed, add new ones
        (let [kept-engines (select-keys current-engines desired-ids)
              new-engines (merge kept-engines (select-keys desired-engines engines-to-start))]
          
          ;; Update multi-engine state
          (state/assoc-in-state! [:backend :streaming :multi-engine-state]
                                 {:engines new-engines
                                  :running? true
                                  :start-time-ms start-time})
          
          (log/info (format "Engine refresh complete: now running %d engine(s)"
                            (count new-engines)))
          new-engines)))))

(defn streaming-running?
  "Check if multi-engine streaming is currently running."
  []
  (boolean (get-in (state/get-raw-state) [:backend :streaming :running?])))
