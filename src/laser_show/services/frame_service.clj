(ns laser-show.services.frame-service
  "Frame service for generating preview frames.
   
   This service:
   - Reads from the new unified state
   - Generates frames based on active cell and cue chain
   - Applies effects from the effects grid and per-preset effects
   - Provides frames for preview canvas and IDN streaming
   
   Cue Chain Support:
   - Cue chains are sequential lists of presets
   - Each preset can have its own parameters and effect chain
   - Presets render one after another in time"
  (:require [clojure.tools.logging :as log]
            [laser-show.state.core :as state]
            [laser-show.state.queries :as queries]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.types :as t]
            [laser-show.animation.time :as anim-time]
            [laser-show.common.timing :as timing]
            [laser-show.profiling.frame-profiler :as profiler]
            ;; Require effect implementations to register them
            [laser-show.animation.effects.shape]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.calibration]
            [laser-show.animation.effects.zone]))


;; Beat Accumulation
;;
;; These functions manage the incremental beat/time accumulators that enable
;; smooth animation during BPM changes. Instead of recalculating beats from
;; elapsed time (which causes jumps), we accumulate beats incrementally.


(defn- update-timing-accumulators!
  "Update beat/time accumulators for the current frame.
   Call once per frame before generating animation.
   
   Handles:
   - Incremental beat and ms accumulation (ALWAYS runs - for modulator preview)
   - Phase offset exponential decay toward target
   - Protection against large deltas (>1 second gaps)
   
   NOTE: Beat accumulation runs continuously, not just when playing.
   This allows modulators to animate in editor preview mode.
   The retrigger button resets accumulators to zero."
  [current-time-ms]
  (let [bpm (queries/bpm)]
    (state/swap-state!
      (fn [s]
        (let [{:keys [last-frame-time accumulated-beats accumulated-ms
                      phase-offset phase-offset-target resync-rate]} (:playback s)
              resync-rate (or resync-rate 4.0)]
          (if (pos? last-frame-time)
            ;; Normal frame - calculate deltas and update
            (let [delta-ms (- current-time-ms last-frame-time)]
              ;; Guard against unreasonable deltas (>1 second = probably pause/resume)
              (if (> delta-ms 1000)
                ;; Skip accumulation, just update timestamp
                (assoc-in s [:playback :last-frame-time] current-time-ms)
                ;; Normal accumulation - runs continuously for modulator preview
                (let [delta-beats (* delta-ms (/ bpm 60000.0))
                      new-accumulated-beats (+ (or accumulated-beats 0.0) delta-beats)
                      new-accumulated-ms (+ (or accumulated-ms 0.0) delta-ms)
                      ;; Exponential decay toward target phase offset
                      decay (Math/exp (- (/ delta-beats (max 0.1 resync-rate))))
                      new-phase-offset (+ (or phase-offset-target 0.0)
                                          (* (- (or phase-offset 0.0)
                                                (or phase-offset-target 0.0))
                                             decay))]
                  (-> s
                      (assoc-in [:playback :accumulated-beats] new-accumulated-beats)
                      (assoc-in [:playback :accumulated-ms] new-accumulated-ms)
                      (assoc-in [:playback :phase-offset] new-phase-offset)
                      (assoc-in [:playback :last-frame-time] current-time-ms)))))
            ;; First frame - just initialize timestamp
            (assoc-in s [:playback :last-frame-time] current-time-ms)))))))


;; Frame Generation


;; NOTE: All these functions use get-raw-state instead of get-state because
;; they are called from a background timer thread (preview-update), not the
;; UI thread. Using fx/sub-val from a background thread causes assertion errors.

(defn get-active-cell-data
  "Get the cell data for the currently active cell.
   Returns a map with :cue-chain from the unified :chains domain."
  []
  (let [s (state/get-raw-state)
        active-cell (get-in s [:playback :active-cell])]
    (when active-cell
      (let [[col row] active-cell
            cue-chain-data (get-in s [:chains :cue-chains [col row]])]
        (when (seq (:items cue-chain-data))
          {:cue-chain cue-chain-data})))))

(defn get-trigger-time
  "Get the trigger time for animation timing."
  []
  (get-in (state/get-raw-state) [:playback :trigger-time] 0))

(defn get-bpm
  "Get the current BPM."
  []
  (get-in (state/get-raw-state) [:timing :bpm] 120.0))

(defn is-playing?
  "Check if playback is active."
  []
  (get-in (state/get-raw-state) [:playback :playing?] false))

(defn get-timing-context
  "Get the timing context for modulator evaluation.
   Returns a map with accumulated-beats, accumulated-ms, phase-offset, effective-beats."
  []
  (let [s (state/get-raw-state)
        {:keys [accumulated-beats accumulated-ms phase-offset]} (:playback s)]
    {:accumulated-beats (or accumulated-beats 0.0)
     :accumulated-ms (or accumulated-ms 0.0)
     :phase-offset (or phase-offset 0.0)
     :effective-beats (+ (or accumulated-beats 0.0) (or phase-offset 0.0))}))

(defn get-active-effects
  "Get all active effect instances from the effects grid.
   Returns an effect chain or nil if no active effects."
  []
  (let [s (state/get-raw-state)
        effect-chains (get-in s [:chains :effect-chains] {})]
    (when (seq effect-chains)
      (let [active-effects (->> effect-chains
                                (vals)
                                (filter :active)
                                (mapcat :items)
                                (vec))]
        (when (seq active-effects)
          ;; Debug logging - uncomment to trace effect chain construction
          ;; (println "[DEBUG get-active-effects] Found" (count active-effects) "effects:"
          ;;          (mapv :effect-id active-effects))
          {:effects active-effects})))))

;; Cue Chain Rendering
;;
;; Cue chains render ALL enabled presets simultaneously, not sequentially.
;; This is achieved by:
;; 1. Rendering each preset/group recursively
;; 2. Applying effects at each level (preset effects, then group effects)
;; 3. Concatenating all point sequences with blanking points between them
;; 4. The galvo will draw all shapes rapidly, creating persistence of vision
;;
;; Effect Pipeline: Preset Effects → Group Effects → Concatenate → Grid Effects
;;
;; For IDN output, the combined points are sent as a single frame.
;; For preview, we display all shapes at once.

(defn- render-preset-instance
  "Render a single preset instance with its parameters.
   Creates an animation from the preset-id and applies preset-specific params."
  [preset-instance elapsed-ms]
  (let [{:keys [preset-id params]} preset-instance]
    (when-let [anim (if (seq params)
                      (presets/create-animation-with-params preset-id params)
                      (presets/create-animation-from-preset preset-id))]
      (t/get-frame anim elapsed-ms))))

(defn- apply-item-effects
  "Apply an item's (preset or group) effect chain to a frame."
  [frame item elapsed-ms bpm trigger-time timing-ctx]
  (let [item-effects (:effects item [])]
    (if (seq item-effects)
      (try
        (effects/apply-effect-chain frame {:effects item-effects} elapsed-ms bpm trigger-time timing-ctx)
        (catch Exception e
          (log/error "apply-item-effects: Effect chain failed for" (:type item) ":" (.getMessage e))
          frame))
      frame)))

(defn- create-blanking-jump
  "Create blanking points to safely jump from one position to another.
   Returns a vector of blanking points that:
   1. Turn off laser at current position (source)
   2. Move to new position with laser off (destination)
   
   This prevents visible lines when galvos travel between shapes."
  [from-point to-point]
  (let [;; First: blanking point at source (turn off laser at current position)
        blank-at-source (when from-point
                          (t/->LaserPoint (:x from-point) (:y from-point) 0.0 0.0 0.0))
        ;; Second: blanking point at destination (move to new position with laser off)
        blank-at-dest (when to-point
                        (t/->LaserPoint (:x to-point) (:y to-point) 0.0 0.0 0.0))]
    (filterv some? [blank-at-source blank-at-dest])))

(defn- concatenate-frames
  "Concatenate multiple frames with blanking points between them.
   Returns a new LaserFrame with all points combined."
  [frames elapsed-ms]
  (when (seq frames)
    (let [combined-points (reduce
                            (fn [acc frame]
                              (let [points (:points frame)]
                                (if (empty? points)
                                  acc
                                  (if (empty? acc)
                                    ;; First frame - just add points
                                    (vec points)
                                    ;; Subsequent frames - add blanking jump then points
                                    (let [last-point (peek acc)
                                          first-new-point (first points)
                                          blanking (create-blanking-jump last-point first-new-point)]
                                      (-> acc
                                          (into blanking)
                                          (into points)))))))
                            []
                            frames)]
      (when (seq combined-points)
        (t/->LaserFrame combined-points elapsed-ms {})))))

(defn- render-item-with-effects
  "Recursively render a cue chain item (preset or group) applying effects at each level.
   
   Effect Pipeline:
   - For presets: render → apply preset effects
   - For groups: render children → concatenate → apply group effects
   
   Returns a LaserFrame or nil if disabled/empty."
  [item elapsed-ms bpm trigger-time timing-ctx]
  (when (:enabled? item true)
    (cond
      ;; Preset: render and apply its effects
      (= :preset (:type item))
      (when-let [frame (render-preset-instance item elapsed-ms)]
        (apply-item-effects frame item elapsed-ms bpm trigger-time timing-ctx))
      
      ;; Group: render children, concatenate, then apply group effects
      (chains/group? item)
      (let [child-frames (keep #(render-item-with-effects % elapsed-ms bpm trigger-time timing-ctx)
                               (:items item []))
            concatenated (concatenate-frames child-frames elapsed-ms)]
        (when concatenated
          (apply-item-effects concatenated item elapsed-ms bpm trigger-time timing-ctx)))
      
      :else nil)))

(defn- generate-frame-from-cue-chain
  "Generate a frame from a cue chain using recursive item rendering.
   
   Renders ALL enabled presets/groups, applying effects at each level:
   1. Preset-level effects on each preset
   2. Group-level effects on concatenated group contents
   3. Final concatenation of all top-level items
   
   This creates the effect of all shapes being displayed at once via persistence of vision."
  [cue-chain elapsed-ms bpm trigger-time timing-ctx]
  (let [items (:items cue-chain [])
        rendered-frames (keep #(render-item-with-effects % elapsed-ms bpm trigger-time timing-ctx) items)]
    (concatenate-frames rendered-frames elapsed-ms)))

(defn generate-current-frame
  "Generate the current animation frame based on state.
   Applies active effects from the effects grid to the base frame.
   Returns a LaserFrame or nil if nothing to render.
   
   Frame generation is profiled to track performance."
  []
  (when (is-playing?)
    (when-let [cell-data (get-active-cell-data)]
      (let [trigger-time (get-trigger-time)
            elapsed (- (System/currentTimeMillis) trigger-time)
            bpm (get-bpm)
            ;; Get timing context for modulator evaluation
            timing-ctx (get-timing-context)
            
            ;; Measure base frame generation
            base-start (timing/nanotime)
            base-frame (when-let [cue-chain (:cue-chain cell-data)]
                         (generate-frame-from-cue-chain cue-chain elapsed bpm trigger-time timing-ctx))
            base-end (timing/nanotime)]
        
        (when base-frame
          (let [effect-chain (get-active-effects)
                
                ;; Measure effect chain application (from effects grid)
                effects-start (timing/nanotime)
                final-frame (if effect-chain
                              (try
                                (effects/apply-effect-chain base-frame effect-chain elapsed bpm trigger-time timing-ctx)
                                (catch Exception e
                                  (log/error "generate-current-frame: Effect chain failed:" (.getMessage e))
                                  (log/debug "  effect-chain:" effect-chain)
                                  base-frame))
                              base-frame)
                effects-end (timing/nanotime)]
            
            ;; Record timing (profiler adds timestamp and calculates total)
            (profiler/record-frame-timing!
              {:base-time-us (timing/nanos->micros (- base-end base-start))
               :effects-time-us (if effect-chain
                                  (timing/nanos->micros (- effects-end effects-start))
                                  0)
               :effect-count (if effect-chain (count (:effects effect-chain)) 0)})
            
            final-frame))))))


;; Frame Conversion for Preview


(defn laser-point->preview-point
  "Convert a LaserPoint to a map suitable for preview rendering.
   
   LaserPoints now use normalized values:
   - x, y: -1.0 to 1.0 (already normalized)
   - r, g, b: 0.0 to 1.0 (already normalized)
   
   Preview uses the same format, so we just pass through the values."
  [^laser_show.animation.types.LaserPoint point]
  {:x (:x point)           ;; Already normalized (-1.0 to 1.0)
   :y (:y point)           ;; Already normalized (-1.0 to 1.0)
   :r (:r point)           ;; Already normalized (0.0 to 1.0)
   :g (:g point)           ;; Already normalized (0.0 to 1.0)
   :b (:b point)           ;; Already normalized (0.0 to 1.0)
   :blanked? (t/blanked? point)})

(defn frame->preview-data
  "Convert a LaserFrame to preview-friendly data."
  [frame]
  (when frame
    {:points (mapv laser-point->preview-point (:points frame))
     :timestamp (:timestamp frame)
     :metadata (:metadata frame)}))

(defn get-preview-frame
  "Get the current frame in preview-friendly format."
  []
  (frame->preview-data (generate-current-frame)))


;; Frame Provider for Streaming


(defn create-frame-provider
  "Create a frame provider function for IDN streaming.
   Returns a zero-arity function that returns the current LaserFrame."
  []
  (fn []
    (generate-current-frame)))


;; State Update Integration


(defn update-preview-frame!
  "Update the preview frame in state.
   Call this periodically to update the preview.
   Also updates timing accumulators for smooth beat-synced animation."
  []
  ;; Update timing accumulators FIRST, before generating frame
  (update-timing-accumulators! (System/currentTimeMillis))
  
  (let [frame (get-preview-frame)
        ;; Get recent profiler stats (last 30 frames = ~1 second at 30fps)
        recent-stats (profiler/get-recent-stats 30)
        frame-stats (when recent-stats
                      {:avg-latency-us (:avg-total-us recent-stats)
                       :p95-latency-us (:p95-total-us recent-stats)
                       :max-latency-us (:max-total-us recent-stats)})]
    (state/swap-state!
      (fn [s]
        (cond-> s
          true (assoc-in [:backend :streaming :current-frame] frame)
          frame-stats (assoc-in [:backend :streaming :frame-stats] frame-stats))))))


;; Preview Update Timer


(defonce ^:private preview-timer (atom nil))

(defn stop-preview-updates!
  "Stop periodic preview frame updates."
  []
  (when-let [timer @preview-timer]
    (.cancel timer)
    (reset! preview-timer nil)
    (log/info "Preview updates stopped")))

(defn start-preview-updates!
  "Start periodic preview frame updates.
   FPS determines how often frames are generated."
  ([]
   (start-preview-updates! 30)) ;; Default 30 FPS
  ([fps]
   (stop-preview-updates!)
   (let [interval-ms (long (/ 1000 fps))
         timer (java.util.Timer. "preview-update" true)]
     (.scheduleAtFixedRate timer
                           (proxy [java.util.TimerTask] []
                             (run []
                               (try
                                 (update-preview-frame!)
                                 (catch Exception e
                                   (log/error "Preview update error:" (.getMessage e))))))
                           0
                           interval-ms)
     (reset! preview-timer timer)
     (log/info "Preview updates started at" fps "FPS"))))
