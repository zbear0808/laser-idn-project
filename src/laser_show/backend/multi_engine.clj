(ns laser-show.backend.multi-engine
  "Multi-engine management for streaming to multiple projectors.
   
   Each projector gets its own streaming engine with a projector-specific
   frame provider. The frame provider uses zone-based routing to determine
   which frames each projector receives.
   
   ZONE-BASED ARCHITECTURE (v3):
   - One streaming engine per enabled projector
   - Each engine has a frame provider that:
     1. Looks up projector's zone-groups from config
     2. Gets zone-frames map from frame-service (cached per frame)
     3. Extracts and concatenates frames for projector's zones
     4. Applies projector calibration effects (color curves + corner-pin)
   
   Each projector receives only content routed to its assigned zones.
   Multiple projectors in the same zone share cached frame computation.
   
   The multi-engine state is stored in [:backend :streaming :multi-engine-state]"
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.set :as set]
            [laser-show.backend.streaming-engine :as engine]
            [laser-show.dev-config :as dev-config]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex]
            [laser-show.animation.effects :as effects]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.idn.output-config :as output-config]
            [laser-show.services.ilda-player :as ilda-player]))


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


;; Helper Functions for Zone-Based Frame Routing


(defn- get-projector-zone-groups
  "Get the zone groups a projector belongs to.
   
   Args:
   - raw-state: The raw application state
   - projector-id: The projector's ID keyword
   
   Returns: Vector of zone-group-ids the projector belongs to."
  [raw-state projector-id]
  (let [projector (get-in raw-state [:projectors projector-id])]
    (-> projector
        :zone-groups
        seq)))

(defn- extract-frames-for-zones
  "Extract frames from a zone-frames map for the specified zone groups.
   
   Args:
   - zone-frames-map: Map of {zone-group-id → frame} from generate-frames-by-zone-cached
   - zone-group-ids: Vector of zone-group-ids to extract frames for
   
   Returns: Vector of frames (may contain nils for zones with no content).
            Returns empty vector if zone-group-ids is empty."
  [zone-frames-map zone-group-ids]
  (if (empty? zone-group-ids)
    []
    (mapv #(get zone-frames-map %) zone-group-ids)))

(defn- combine-zone-frames
  "Combine multiple zone frames into a single frame with proper blanking.
   
   Uses the concatenate-frames utility from frame-service to join
   frames with blanking points between them for safe galvo travel.
   
   Args:
   - frames: Vector of frames (each frame is a vector of points)
   
   Returns: Combined frame or nil if all input frames are nil/empty."
  [frames]
  (let [non-nil-frames (filterv some? frames)]
    (when (seq non-nil-frames)
      (frame-service/concatenate-frames non-nil-frames 0))))


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

(defn- get-active-cues-data
  "Get data for ALL active cues (multi-cue support for IDN streaming).
   
   Returns a vector of maps, each with:
   - :cell - [col row] coordinates
   - :cue-chain - the cue chain data
   - :cue-timing - the timing state for this cue
   
   Returns empty vector if no active cues."
  [raw-state]
  (let [active-cues (get-in raw-state [:playback :active-cues] {})]
    (into []
          (keep (fn [[[col row] cue-timing]]
                  (let [cue-chain-data (get-in raw-state [:chains :cue-chains [col row]])]
                    (when (seq (:items cue-chain-data))
                      {:cell [col row]
                       :cue-chain cue-chain-data
                       :cue-timing cue-timing}))))
          active-cues)))

(defn- create-projector-frame-provider
  "Create a frame provider function for a specific projector or virtual projector.
   
   The frame provider uses multi-cue zone-based routing:
   1. Gets ALL active cues from [:playback :active-cues]
   2. For each active cue, generates frames by zone
   3. Extracts frames for this projector's zones from each cue
   4. Concatenates all zone frames from all cues
   5. Applies projector calibration effects (color curves + corner-pin)
   
   This aligns with how preview generates frames, supporting multi-cue playback.
   
   Returns a zero-arity function that returns a LaserFrame or nil."
  [projector-id]
  (fn []
    (try
      (let [raw-state (state/get-raw-state)
            playing? (get-in raw-state [:playback :playing?])
            ilda-playing? (ilda-player/is-playing?)
            
            _ (when-not (or playing? ilda-playing?) (throw (ex-info "Not playing" {:skip true})))
            
            ;; Get ALL active cues (multi-cue support)
            all-cues (get-active-cues-data raw-state)

            ;; If neither cues nor ILDA available, skip
            _ (when (and (empty? all-cues) (not ilda-playing?))
                (throw (ex-info "No active cues or ILDA" {:skip true})))
            
            projector-zone-groups (get-projector-zone-groups raw-state projector-id)
            bpm (get-in raw-state [:timing :bpm] 120.0)
            current-time (System/currentTimeMillis)
            
            ;; Generate frames for each active cue and extract this projector's zones
            all-projector-frames
            (into []
                  (keep
                    (fn [{:keys [cue-chain cue-timing]}]
                      (let [trigger-time (:trigger-time cue-timing 0)
                            elapsed (- current-time trigger-time)
                            timing-ctx (frame-service/get-timing-context)
                            ;; Generate zone frames for this cue
                            zone-frames-map (frame-service/generate-frames-by-zone-cached
                                              cue-chain elapsed bpm trigger-time timing-ctx)
                            ;; Extract frames for this projector's zones
                            projector-frames (extract-frames-for-zones zone-frames-map projector-zone-groups)]
                        ;; Combine this cue's zone frames
                        (combine-zone-frames projector-frames))))
                  all-cues)
            
            ;; Combine frames from all cues
            combined-frame (combine-zone-frames all-projector-frames)
            
            ;; Get ILDA frame if playing
            ilda-frame (ilda-player/get-current-frame)

            ;; Combine cue frames with ILDA frame
            final-frame (let [frames (filterv some? [combined-frame ilda-frame])]
                          (when (seq frames)
                            (frame-service/concatenate-frames frames 0)))

            ;; Get timing info for projector effects (use first cue's timing or default)
            timing-info (if (seq all-cues)
                          (let [first-cue-timing (:cue-timing (first all-cues))]
                            {:trigger-time (:trigger-time first-cue-timing 0)
                             :elapsed (- current-time (:trigger-time first-cue-timing 0))
                             :timing-ctx (frame-service/get-timing-context)})
                          {:trigger-time 0
                           :elapsed 0
                           :timing-ctx (frame-service/get-timing-context)})

            {:keys [elapsed trigger-time timing-ctx]} timing-info
            
            log-count (swap! routing-log-counter inc)]
        
        (when (and (dev-config/idn-stream-logging?)
                   (zero? (mod log-count ROUTING_LOG_INTERVAL)))
          (log/debug (format "Zone routing [%s]: zone-groups=%s, active-cues=%d, combined-points=%s"
                             projector-id
                             (pr-str projector-zone-groups)
                             (count all-cues)
                             (if final-frame (count final-frame) "nil"))))
        
        (when final-frame
          (apply-projector-effects final-frame projector-id elapsed bpm trigger-time timing-ctx)))
      
      (catch clojure.lang.ExceptionInfo e
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
