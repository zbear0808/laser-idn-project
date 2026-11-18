(ns laser-show.backend.multi-engine
  "Multi-engine management for streaming to multiple projectors.
   
   Each projector gets its own streaming engine with a projector-specific
   frame provider. The frame provider uses zone-based routing to determine
   which frames each projector receives.
   
   ZONE-BASED ARCHITECTURE (v3):
   - One streaming engine per enabled projector
   - The show clock renders every playing cue once per tick
     (services/show_clock.clj) and publishes it (show-time/latest)
   - Each engine wakes on the tick (show-time/frame-signal) and its frame
     provider, on the engine's own thread:
     1. Looks up projector's zone-groups from config
     2. Takes those zones from each cue of the tick and joins them
     3. Applies projector calibration effects (color curves + corner-pin)
   
   Each projector receives only content routed to its assigned zones.
   
   This namespace builds engines and their frame providers. Starting, stopping
   and keeping them is the output runtime's job (backend/output_runtime.clj)."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [laser-show.animation.show-time :as show-time]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.backend.streaming-engine :as engine]
            [laser-show.dev-config :as dev-config]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex]
            [laser-show.animation.effects :as effects]
            [laser-show.services.frame-service :as frame-service]
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
   - zone-frames-map: Map of {zone-group-id → frame}, one cue's render
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
      (frame-service/concatenate-frames non-nil-frames))))


;; Frame Provider Creation


(defn- apply-projector-effects
  "Apply projector-level calibration effects to a frame, at timing-ctx (the
   snapshot's projector context).
   
   Projector effects are stored in [:chains :projector-effects projector-id :items]
   These include RGB curves, corner-pin calibration, etc."
  [frame projector-id timing-ctx]
  (let [raw-state (state/get-raw-state)
        projector-effects (get-in raw-state [:chains :projector-effects projector-id :items] [])]
    (if (seq projector-effects)
      (try
        (let [out (effects/apply-effect-chain frame {:effects projector-effects} timing-ctx)]
          (output-errors/ok! [:projector-effects projector-id])
          out)
        (catch Throwable e
          (output-errors/report! [:projector-effects projector-id] e)
          frame))
      frame)))

(defn- stalled
  "Why a projector sends an empty frame instead of the snapshot's."
  [snapshot now-ns]
  (ex-info "Show clock stalled"
           {:stale-ms (quot (- (long now-ns) (long (:at-ns snapshot))) 1000000)}))

(defn- projector-frame
  "The projector's frame for the show clock's tick in snapshot: each cue's
   frames for its zones, joined, then its projector effects at the first
   cue's timing. Nil before the first tick, while nothing is playing and when
   none of the cues reach its zones. A snapshot older than show-time/STALE_MS
   throws \"Show clock stalled\". The frame carries the snapshot's
   {:tick :at-ns} as metadata, so the engine knows which tick it sends."
  [projector-id snapshot]
  (when snapshot
    (let [now (System/nanoTime)]
      (when (show-time/stale? snapshot now)
        (throw (stalled snapshot now))))
    (when (:playing? snapshot)
      (let [raw-state (state/get-raw-state)
            zone-groups (get-projector-zone-groups raw-state projector-id)
            cues (:cues snapshot)
            combined-frame (combine-zone-frames
                            (into [] (keep #(combine-zone-frames (extract-frames-for-zones (:zone-frames %) zone-groups)))
                                  cues))
            log-count (swap! routing-log-counter inc)]
        
        (when (and (dev-config/idn-stream-logging?)
                   (zero? (mod log-count ROUTING_LOG_INTERVAL)))
          (log/debug (format "Zone routing [%s]: zone-groups=%s, active-cues=%d, combined-points=%s"
                             projector-id
                             (pr-str zone-groups)
                             (count cues)
                             (if combined-frame (count combined-frame) "nil"))))
        
        (when combined-frame
          (let [frame (apply-projector-effects combined-frame projector-id (:projector-ctx snapshot))]
            (with-meta frame (select-keys snapshot [:tick :at-ns]))))))))

(defn- create-projector-frame-provider
  "Create a frame provider function for a specific projector or virtual projector.
   
   The frame provider takes the show clock's newest tick (show-time/latest):
   1. For each cue the clock rendered, takes this projector's zones
   2. Concatenates them, and then every cue
   3. Applies projector calibration effects (color curves + corner-pin)
   
   The preview is built from the same tick, so it shows what the engines send.
   
   Returns a zero-arity function that returns a LaserFrame or nil."
  [projector-id]
  (fn []
    (try
      (let [frame (projector-frame projector-id (show-time/latest))]
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
                                  :scan-rate (:scan-rate projector)
                                  :frame-signal show-time/frame-signal)))))

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
