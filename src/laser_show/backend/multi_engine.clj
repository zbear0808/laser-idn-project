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
   
   This namespace builds engines and their frame providers. Starting, stopping
   and keeping them is the output runtime's job (backend/output_runtime.clj)."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.backend.streaming-engine :as engine]
            [laser-show.dev-config :as dev-config]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.timeline.playback :as playback]
            [laser-show.input.store :as input-store]
            [laser-show.idn.output-config :as output-config]))


;; Routing Debug Logging
;;
;; We use an atom to throttle logging so we don't flood the console
;; Logging is controlled by dev-config/idn-stream-logging? (disabled by default)

(def ^:private routing-log-counter (atom 0))
(def ^:const ROUTING_LOG_INTERVAL 300) ;; Log routing info every N frames (~5 seconds at 60fps)


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
        (let [out (effects/apply-effect-chain frame {:effects projector-effects}
                                              elapsed-ms bpm trigger-time timing-ctx)]
          (output-errors/ok! [:projector-effects projector-id])
          out)
        (catch Throwable e
          (output-errors/report! [:projector-effects projector-id] e)
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

(defn- projector-frame
  "The projector's frame for now: every active cue routed to its zones, then
   its projector effects. Nil while nothing is playing."
  [projector-id]
  (let [raw-state (state/get-raw-state)
        playing? (get-in raw-state [:playback :playing?])
        ;; Get ALL active cues (multi-cue support); idle frames return nil
        all-cues (when playing? (get-active-cues-data raw-state))]
    (when (seq all-cues)
      (let [projector-zone-groups (get-projector-zone-groups raw-state projector-id)
            bpm (get-in raw-state [:timing :bpm] 120.0)
            current-time (System/currentTimeMillis)
            input-values (input-store/get-all-continuous-values)
            
            ;; Generate frames for each active cue and extract this projector's zones
            all-projector-frames
            (into []
                  (keep
                    (fn [{:keys [cue-chain cue-timing]}]
                      (let [trigger-time (:trigger-time cue-timing 0)
                            elapsed (- current-time trigger-time)
                            cue-bpm (playback/cue-bpm cue-chain bpm)
                            timing-ctx (assoc (cue-timing/get-cue-timing-context cue-timing cue-bpm)
                                              :input-values input-values)
                            ;; Generate zone frames for this cue
                            zone-frames-map (frame-service/generate-frames-by-zone-cached
                                              cue-chain elapsed cue-bpm trigger-time timing-ctx)
                            ;; Extract frames for this projector's zones
                            projector-frames (extract-frames-for-zones zone-frames-map projector-zone-groups)]
                        ;; Combine this cue's zone frames
                        (combine-zone-frames projector-frames))))
                  all-cues)
            
            ;; Combine frames from all cues
            combined-frame (combine-zone-frames all-projector-frames)
            
            ;; Get timing info for projector effects (use first cue's timing)
            first-cue-timing (:cue-timing (first all-cues))
            trigger-time (:trigger-time first-cue-timing 0)
            elapsed (- current-time trigger-time)
            timing-ctx (assoc (cue-timing/get-cue-timing-context first-cue-timing bpm)
                              :input-values input-values)
            
            log-count (swap! routing-log-counter inc)]
        
        (when (and (dev-config/idn-stream-logging?)
                   (zero? (mod log-count ROUTING_LOG_INTERVAL)))
          (log/debug (format "Zone routing [%s]: zone-groups=%s, active-cues=%d, combined-points=%s"
                             projector-id
                             (pr-str projector-zone-groups)
                             (count all-cues)
                             (if combined-frame (count combined-frame) "nil"))))
        
        (when combined-frame
          (apply-projector-effects combined-frame projector-id elapsed bpm trigger-time timing-ctx))))))

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
      (let [frame (projector-frame projector-id)]
        (output-errors/ok! [:provider projector-id])
        frame)
      (catch Throwable e
        (output-errors/report! [:provider projector-id] e)
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
                                  :id projector-id
                                  :port port
                                  :channel-id service-id
                                  :service-id service-id
                                  :output-config output-cfg
                                  :scan-rate (:scan-rate projector))))))

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
