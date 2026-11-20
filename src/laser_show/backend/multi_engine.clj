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
            [laser-show.animation.chains :as chains]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.safety :as safety]
            [laser-show.animation.test-pattern :as test-pattern]
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


(defn- report-skips!
  "A calibration effect that throws is skipped and the rest of the chain runs
   (effects/apply-effect-chain reports it under [:effect id]). Say so under
   [:calibration projector-id], naming the skipped effects (\"Corner Pin
   skipped\"), so the projector's status line shows it; clear it once none is
   skipped. Costs nothing while no output error is failing."
  [projector-id projector-effects]
  (when (output-errors/failing?)
    (let [errors (output-errors/snapshot)
          skipped (filterv #(contains? errors [:effect (or (:id %) (:effect-id %))])
                           (chains/flatten-chain projector-effects))]
      (if (seq skipped)
        (output-errors/report! [:calibration projector-id]
                               (str (str/join ", " (map #(:name (effects/get-effect (:effect-id %))
                                                              (name (:effect-id %)))
                                                        skipped))
                                    " skipped"))
        (output-errors/ok! [:calibration projector-id])))))

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
          (report-skips! projector-id projector-effects)
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

(defn- stamp
  "frame with the snapshot's {:tick :at-ns}, so the engine knows which tick it sends."
  [snapshot frame]
  (with-meta frame (select-keys snapshot [:tick :at-ns])))

(defn- guard-safety
  "The last stage of every frame a projector sends: (f zone)'s frame inside
   the projector's safety zone (animation.safety, zones plan Z1). Reading and
   normalizing the zone, f and apply-safety are one step: if anything in it
   throws, a zone that can't be read included, the projector sends an empty
   frame and [:safety projector-id] says why (it fails closed). Either way the
   frame carries the snapshot's {:tick :at-ns}."
  [raw-state projector-id snapshot f]
  (try
    (let [zone (safety/normalize (get-in raw-state [:projectors projector-id :safety]))
          out (safety/apply-safety zone (f zone))]
      (output-errors/ok! [:safety projector-id])
      (stamp snapshot out))
    (catch Throwable e
      (output-errors/report! [:safety projector-id] e)
      (stamp snapshot []))))

(defn now-ms
  "The wall clock Identify's flash is timed by (a var, so tests can set it)."
  []
  (System/currentTimeMillis))

(defn- test-pattern-mode
  "Is projector-id showing the test pattern at now-ms: :identify-on or
   :identify-off while Identify flashes ([:projector-ui :identify id], until
   its end time), else :calibrate while it is the calibrating projector, else
   nil. Identify wins while it flashes."
  [raw-state projector-id now-ms]
  (let [until (get-in raw-state [:projector-ui :identify projector-id])]
    (cond
      (and until (< (long now-ms) (long until)))
      (if (test-pattern/identify-on? now-ms until) :identify-on :identify-off)

      (= projector-id (get-in raw-state [:projector-ui :calibrating-projector-id]))
      :calibrate)))

(defn- test-pattern-frame
  "The test pattern (animation.test-pattern): the grid through the projector
   effects, the safety outline on top in output space, at the calibration
   brightness, then the safety stage."
  [raw-state projector-id snapshot]
  (let [brightness (get-in raw-state [:projector-ui :calibration-brightness] 0.1)]
    (guard-safety raw-state projector-id snapshot
                  (fn [zone]
                    (frame-service/concatenate-frames
                     [(apply-projector-effects (test-pattern/content-layer brightness) projector-id
                                               (:projector-ctx snapshot))
                      (test-pattern/safety-layer zone brightness)])))))

(declare show-frame)

(defn- projector-frame
  "The projector's frame for the show clock's tick in snapshot: each cue's
   frames for its zones, joined, then its projector effects at the first
   cue's timing (show-frame). While it shows the test pattern (calibration or
   Identify), the pattern instead, even with nothing playing; dark during
   BLACKOUT and in Identify's dark halves. Nil before the first tick, while
   nothing is playing and when none of the cues reach its zones. Its safety
   zone comes last (guard-safety): it clips what the projector effects made,
   and if it fails the projector sends an empty frame. A snapshot older than
   show-time/STALE_MS throws \"Show clock stalled\". The frame carries the
   snapshot's {:tick :at-ns} as metadata, so the engine knows which tick it
   sends."
  [projector-id snapshot]
  (when snapshot
    (let [now (System/nanoTime)]
      (when (show-time/stale? snapshot now)
        (throw (stalled snapshot now))))
    (let [raw-state (state/get-raw-state)
          pattern (test-pattern-mode raw-state projector-id (now-ms))]
      (cond
        ;; BLACKOUT darkens the test pattern, and only the pattern
        (and pattern (get-in raw-state [:vibe-runtime :blackout?])) (stamp snapshot [])
        (= :identify-off pattern) (stamp snapshot [])
        ;; Even with nothing playing; it skips the render, so the effects grid
        ;; (the Master included) never touches it
        pattern (test-pattern-frame raw-state projector-id snapshot)
        (:playing? snapshot) (show-frame raw-state projector-id snapshot)))))

(defn- show-frame
  "What the projector draws of the show: each cue's frames for its zones,
   joined, then its projector effects and its safety zone. Nil when none of
   the cues reach its zones."
  [raw-state projector-id snapshot]
  (let [zone-groups (get-projector-zone-groups raw-state projector-id)
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
      (guard-safety raw-state projector-id snapshot
                    (fn [_] (apply-projector-effects combined-frame projector-id (:projector-ctx snapshot)))))))

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
