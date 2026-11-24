(ns laser-show.backend.multi-engine
  "Multi-engine management for streaming to multiple projectors.
   
   Each projector gets its own streaming engine with a projector-specific
   frame provider. The frame provider composes, from the tick's snapshot,
   what that projector draws.
   
   ZONE-BASED ARCHITECTURE (v3):
   - One streaming engine per enabled projector
   - The show clock renders every playing cue once per tick and works out the
     tick's routing (services/show_clock.clj), and publishes both
     (show-time/latest)
   - Each engine wakes on the tick (show-time/frame-signal) and its frame
     provider, on the engine's own thread:
     1. Composes the projector's frame from the snapshot's cues and routing
        (routing/compose.clj): each key and each picture once, Left and Right
        fitted into its halves
     2. Below the projector's minimum fps, the fit guard draws fewer points,
        then dims if it may (animation/fit_guard.clj)
     3. Applies projector calibration effects (color curves + corner-pin)
     4. Clips it to the projector's safety zone
   
   No engine reads app state: each tick brings the routing and everything
   else a frame takes (engine-inputs: each laser's calibration and safety
   zone, the test pattern, Identify, BLACKOUT, the tick's wall clock), so a
   frame is a function of its snapshot, every laser draws a tick from the
   same state, an engine's frame and the preview agree, and an edit shows on
   the next tick without a restart.
   
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
            [laser-show.animation.fit-guard :as fit-guard]
            [laser-show.animation.safety :as safety]
            [laser-show.animation.test-pattern :as test-pattern]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.output.driver :as driver]
            [laser-show.routing.compose :as compose]))


;; Routing Debug Logging
;;
;; We use an atom to throttle logging so we don't flood the console
;; Logging is controlled by dev-config/idn-stream-logging? (disabled by default)

(def ^:private routing-log-counter (atom 0))
(def ^:const ROUTING_LOG_INTERVAL 300) ;; Log routing info every N frames (~5 seconds at 60fps)


;; Frame Provider Creation


(defn engine-inputs
  "What the engines take from app state for one tick, from state, the
   tick's one read (show-clock/tick!), and wall-ms, the tick's wall clock:
   {:projectors (each laser's safety zone) :projector-effects (each laser's
   calibration chain) :calibrating :brightness :identify {id until-ms}
   :blackout? :wall-ms}. The maps are state's own (the very
   :projector-effects the routing's Decimate budgets come from,
   rig/routing-inputs), so a frame's chain, zone and budget are one state's.
   Pure."
  [state wall-ms]
  {:projectors (:projectors state)
   :projector-effects (get-in state [:chains :projector-effects])
   :calibrating (get-in state [:projector-ui :calibrating-projector-id])
   :brightness (get-in state [:projector-ui :calibration-brightness] 0.1)
   :identify (get-in state [:projector-ui :identify])
   :blackout? (boolean (get-in state [:vibe-runtime :blackout?]))
   :wall-ms wall-ms})

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
  "Apply a projector's calibration effects, items (its chain from the tick's
   [:chains :projector-effects projector-id :items], engine-inputs), to a
   frame at timing-ctx (the snapshot's projector context). These include RGB
   curves, corner-pin calibration, Decimate, etc. Reads no state."
  [frame projector-id items timing-ctx]
  (let [projector-effects (or items [])]
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

(defn- chain-of
  "The tick's calibration chain for projector-id (engine-inputs)."
  [inputs projector-id]
  (get-in inputs [:projector-effects projector-id :items]))

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
   the projector's safety zone from the tick (inputs, engine-inputs;
   animation.safety, zones plan Z1). Reading and normalizing the zone, f and
   apply-safety are one step: if anything in it throws, a zone that can't be
   read included, the projector sends an empty frame and [:safety
   projector-id] says why (it fails closed). Either way the frame carries the
   snapshot's {:tick :at-ns}."
  [inputs projector-id snapshot f]
  (try
    (let [zone (safety/normalize (get-in inputs [:projectors projector-id :safety]))
          out (safety/apply-safety zone (f zone))]
      (output-errors/ok! [:safety projector-id])
      (stamp snapshot out))
    (catch Throwable e
      (output-errors/report! [:safety projector-id] e)
      (stamp snapshot []))))

(defn- test-pattern-mode
  "Is projector-id showing the test pattern at the tick's wall clock
   (inputs' :wall-ms): :identify-on or :identify-off while Identify flashes
   (:identify id, until its end time), else :calibrate while it is the
   calibrating projector, else nil. Identify wins while it flashes."
  [inputs projector-id]
  (let [until (get-in inputs [:identify projector-id])
        now-ms (:wall-ms inputs)]
    (cond
      (and until now-ms (< (long now-ms) (long until)))
      (if (test-pattern/identify-on? now-ms until) :identify-on :identify-off)

      (= projector-id (:calibrating inputs))
      :calibrate)))

(defn- test-pattern-frame
  "The test pattern (animation.test-pattern): the grid through the projector
   effects, the safety outline on top in output space, at the calibration
   brightness, then the safety stage, all from the tick (inputs)."
  [inputs projector-id snapshot]
  (let [brightness (:brightness inputs 0.1)]
    (guard-safety inputs projector-id snapshot
                  (fn [zone]
                    (frame-service/concatenate-frames
                     [(apply-projector-effects (test-pattern/content-layer brightness) projector-id
                                               (chain-of inputs projector-id) (:projector-ctx snapshot))
                      (test-pattern/safety-layer zone brightness)])))))

(declare show-frame)

(defn- projector-frame
  "The projector's frame for the show clock's tick in snapshot, from the
   snapshot alone: what it draws of the tick's cues, composed from the
   snapshot's routing, then its projector effects at the first cue's timing
   (show-frame). While it shows the test pattern (calibration or Identify),
   the pattern instead, even with nothing playing; dark during BLACKOUT and
   in Identify's dark halves. Its calibration, safety zone, test pattern,
   Identify and BLACKOUT are the tick's (:engine-inputs, engine-inputs), so
   the same snapshot always gives the same frame. Nil before the first tick,
   for a laser the tick doesn't know, while nothing is playing and when none
   of the cues reach it. Its safety zone comes last (guard-safety): it clips
   what the projector effects made, and if it fails the projector sends an
   empty frame. The frame carries the snapshot's {:tick :at-ns} as metadata,
   so the engine knows which tick it sends."
  [projector-id snapshot]
  (let [inputs (:engine-inputs snapshot)]
    (when (contains? (:projectors inputs) projector-id)
      (let [pattern (test-pattern-mode inputs projector-id)]
        (cond
          ;; BLACKOUT darkens the test pattern, and only the pattern
          (and pattern (:blackout? inputs)) (stamp snapshot [])
          (= :identify-off pattern) (stamp snapshot [])
          ;; Even with nothing playing; it skips the render, so the effects grid
          ;; (the Master included) never touches it
          pattern (test-pattern-frame inputs projector-id snapshot)
          (:playing? snapshot) (show-frame inputs projector-id snapshot))))))

(defn- show-frame
  "What the projector draws of the show: its frame composed from the tick's
   cues and routing (routing.compose/projector-frame), then the fit guard
   (animation.fit-guard, with the routing's scan rate and fit settings for
   it), then its projector effects and its safety zone, both the tick's
   (inputs). The frame carries the guard's record, with how many halves it
   drew, as :fit. When the projector effects change the point count
   (Decimate), the record counts what they left (fit-guard/recount): what
   reaches the safety stage, before the crossing points it may add. A laser
   whose calibration chain has an enabled Decimate (the routing's
   :decimate-budget, from the same tick's chains) meters that count whether
   Decimate changed it or not (fit-guard/drawn). Nil when none of the cues
   reach it."
  [inputs projector-id snapshot]
  (let [routing (:routing snapshot)
        {:keys [frame regions no-room?]} (compose/projector-frame (:cues snapshot) routing projector-id)
        log-count (swap! routing-log-counter inc)]
    (when (and (dev-config/idn-stream-logging?)
               (zero? (mod log-count ROUTING_LOG_INTERVAL)))
      (log/debug (format "Zone routing [%s]: active-cues=%d, composed-points=%s, regions=%s"
                         projector-id
                         (count (:cues snapshot))
                         (if frame (count frame) "nil")
                         regions)))
    (when frame
      (let [{:keys [scan-rate fit decimate-budget]} (get-in routing [:outputs projector-id])
            {guarded :frame record :fit} (fit-guard/guard frame scan-rate fit)
            calibrated (volatile! nil)
            items (chain-of inputs projector-id)
            out (guard-safety inputs projector-id snapshot
                              (fn [_] (vreset! calibrated (apply-projector-effects guarded projector-id items
                                                                                   (:projector-ctx snapshot)))))
            record (cond-> record
                     @calibrated (fit-guard/recount (count @calibrated))
                     (and @calibrated decimate-budget) fit-guard/drawn)]
        (vary-meta out assoc :fit (cond-> (assoc record :regions regions)
                                    no-room? (assoc :no-room? true)))))))

(defn- create-projector-frame-provider
  "Create a frame provider function for a specific projector or virtual projector.
   
   The frame provider takes the show clock's newest tick (show-time/latest),
   and sends an empty frame, reporting \"Show clock stalled\", for one older
   than show-time/STALE_MS on the engine's clock. Otherwise its frame
   (projector-frame) comes from the tick alone:
   1. Composes what this projector draws from the tick's cues and routing
   2. Draws fewer points, then dims, below its minimum fps (the fit guard)
   3. Applies the tick's projector calibration effects (color curves + corner-pin)
   4. Clips it to the tick's safety zone
   So a keepalive resend of the same tick sends the same frame.
   
   The fit guard's record on the frame (:fit) is averaged over the frames it
   makes (fit-guard/smooth, kept here: one provider per engine, called only
   on its thread), so the card's load and low flag don't flicker. What the
   card's thumbnail shows is what the engine's driver was passed and
   returned from (streaming-engine/thumbnail), not what this produced.
   
   The preview is built from the same tick, so it shows what the engines send.
   
   Returns a zero-arity function that returns a LaserFrame or nil."
  [projector-id]
  (let [smoothed (volatile! nil)]
    (fn []
      (try
        (let [snapshot (show-time/latest)
              _ (when snapshot
                  (let [now (System/nanoTime)]
                    (when (show-time/stale? snapshot now)
                      (throw (stalled snapshot now)))))
              frame (projector-frame projector-id snapshot)
              fit (vreset! smoothed (fit-guard/smooth @smoothed (:fit (meta frame))))
              frame (cond-> frame fit (vary-meta assoc :fit fit))]
          (output-errors/ok! [:provider projector-id])
          frame)
        (catch Throwable e
          (output-errors/report! [:provider projector-id] e)
          (vreset! smoothed nil)
          nil)))))


;; Engine Management


(defn create-engine-for-projector
  "Create a streaming engine for a specific projector, sending through its
   output driver with the config driver/projector-config gives it.
   
   Args:
   - projector-id: The projector's ID keyword
   - projector: The projector configuration map
   
   Returns: A streaming engine instance (not started), or nil for an IDN
   projector whose host is invalid. Throws \"Output driver :acme/nova is not
   installed\" for a driver the registry doesn't have (OD-10).
   
   NOTE: service-id targets the physical laser output on multi-head DACs.
   Each projector entry represents one service/output on the device."
  [projector-id projector]
  (let [[driver-id config] (driver/projector-config projector)
        host (:host config)]
    (when (or (not= :idn driver-id) (and host (not (str/blank? host))))
      (let [output-driver (or (driver/lookup driver-id)
                              (throw (ex-info (str "Output driver " driver-id " is not installed")
                                              {:driver driver-id})))
            frame-provider (create-projector-frame-provider projector-id)]
        (log/info (format "Creating engine for %s -> %s %s"
                          projector-id driver-id (pr-str (dissoc config :output-config))))
        (engine/create-driver-engine output-driver config frame-provider
                                     :id projector-id
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
