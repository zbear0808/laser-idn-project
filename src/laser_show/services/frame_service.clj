(ns laser-show.services.frame-service
  "Frame service for generating preview frames.
   
   This service:
   - Reads from the new unified state
   - Generates frames based on active cell and cue chain
   - Applies effects from the effects grid and per-preset effects
   - Provides frames for preview canvas and IDN streaming
   - Filters preview based on zone group routing
   - Generates calibration frames for projector calibration mode
   
   Cue Chain Support:
   - Cue chains are sequential lists of presets
   - Each preset can have its own parameters and effect chain
   - Presets render one after another in time
   
   Zone Group Filtering:
   - Preview can be filtered by zone group to show only content routed to specific zones
   - Default filter is :all (shows content routed to :all zone group)
   - Filter nil means show all content regardless of routing (master view)
   
   Calibration Mode:
   - When a projector is in calibration mode, it receives a boundary box test pattern
   - The test pattern has corner pin applied for live preview during calibration
   - Only the calibrating projector receives the test pattern
   
   Performance Note:
   - All collection operations use eager evaluation (vec, mapv, filterv, keepv)
   - Lazy sequences avoided in frame generation hot paths"
  (:require [clojure.tools.logging :as log]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.types :as t]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.common.timing :as timing]
            [laser-show.common.util :as u]
            [laser-show.profiling.frame-profiler :as profiler]
            [laser-show.profiling.jfr-profiler :as jfr]
            [laser-show.routing.zone-effects :as ze]
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
   
   Multi-Cue Architecture:
   1. Updates global clock (for BPM visualization, runs always)
   2. Updates each active cue's timing (with phase offset decay)
   
   NOTE: Global clock and cue timing run continuously, not just when playing.
   This allows modulators to animate in editor preview mode.
   The retrigger button resets accumulators to zero."
  [current-time-ms]
  (let [raw-state (state/get-raw-state)
        bpm (ex/bpm raw-state)
        resync-rate (ex/resync-rate raw-state)]
    (state/swap-state!
      (fn [s]
        (-> s
            ;; 1. Update global clock (always, for BPM visualization)
            (update-in [:timing :global-clock]
                       (fn [gc]
                         (cue-timing/update-global-clock
                           (or gc {:accumulated-beats 0.0
                                   :accumulated-ms 0.0
                                   :last-frame-time 0})
                           current-time-ms
                           bpm)))
            ;; 2. Update all active cues' timing
            (update-in [:playback :active-cues]
                       (fn [cues]
                         (reduce-kv
                           (fn [acc cell-key cue-timing-state]
                             (assoc acc cell-key
                                    (cue-timing/update-cue-timing
                                      cue-timing-state
                                      current-time-ms
                                      bpm
                                      resync-rate)))
                           {}
                           (or cues {})))))))))


;; Frame Generation


;; NOTE: All these functions use get-raw-state instead of get-state because
;; they are called from a background timer thread (preview-update), not the
;; UI thread. Using fx/sub-val from a background thread causes assertion errors.

(defn get-active-cell-data
  "DEPRECATED: Use get-all-active-cues-data for multi-cue support.
   
   Get the cell data for the currently active cell (first active cue).
   Returns a map with :cue-chain from the unified :chains domain.
   For backward compatibility, returns data for first active cue."
  []
  (let [s (state/get-raw-state)
        active-cues (get-in s [:playback :active-cues] {})]
    (when-let [first-cell (first (keys active-cues))]
      (let [[col row] first-cell
            cue-chain-data (get-in s [:chains :cue-chains [col row]])]
        (when (seq (:items cue-chain-data))
          {:cue-chain cue-chain-data})))))

(defn get-all-active-cues-data
  "Get data for ALL active cues (multi-cue support).
   
   Returns a vector of maps, each with:
   - :cell - [col row] coordinates
   - :cue-chain - the cue chain data
   - :cue-timing - the timing state for this cue"
  []
  (let [s (state/get-raw-state)
        active-cues (get-in s [:playback :active-cues] {})]
    (u/keepv (fn [[[col row] cue-timing]]
               (let [cue-chain-data (get-in s [:chains :cue-chains [col row]])]
                 (when (seq (:items cue-chain-data))
                   {:cell [col row]
                    :cue-chain cue-chain-data
                    :cue-timing cue-timing})))
             active-cues)))

(defn get-trigger-time
  "DEPRECATED: Use per-cue trigger times.
   
   Get the trigger time for animation timing (first active cue).
   For backward compatibility, returns trigger time of first active cue."
  []
  (let [s (state/get-raw-state)
        active-cues (get-in s [:playback :active-cues] {})]
    (if-let [[_ first-cue-timing] (first active-cues)]
      (:trigger-time first-cue-timing 0)
      0)))

(defn get-bpm
  "Get the current BPM."
  []
  (get-in (state/get-raw-state) [:timing :bpm] 120.0))

(defn is-playing?
  "Check if playback is active."
  []
  (get-in (state/get-raw-state) [:playback :playing?] false))

(defn get-preview-zone-filter
  "Get the current preview zone group filter from state.
   Returns:
   - :all - show only content routed to :all zone group
   - :left, :right, etc. - show only content routed to that zone group
   - nil - show all content regardless of destination (master view)"
  []
  (get-in (state/get-raw-state) [:config :preview :zone-group-filter] :all))

(defn- matches-preview-zone?
  "Check if a cue's final target zones match the preview zone filter.
   
   Matching logic:
   - If preview-zone is nil: show all (master view) -> always true
   - If final-targets contains preview-zone: direct match -> true
   - Otherwise: no match -> false
   
   Note: Zone groups like :all are just names, not special. Content routed
   to :all only appears in preview when filter is :all or nil (master view)."
  [preview-zone final-targets]
  (or (nil? preview-zone)                    ;; nil = show all (master view)
      (contains? final-targets preview-zone))) ;; direct match only

(defn get-timing-context
  "DEPRECATED: Use get-cue-timing-context for per-cue timing.
   
   Get the timing context for modulator evaluation (from first active cue).
   Returns a map with accumulated-beats, accumulated-ms, phase-offset, effective-beats.
   For backward compatibility, returns context from first active cue."
  []
  (let [s (state/get-raw-state)
        active-cues (get-in s [:playback :active-cues] {})]
    (if-let [[_ first-cue-timing] (first active-cues)]
      ;; Return context from first active cue
      (cue-timing/get-cue-timing-context first-cue-timing (ex/bpm s))
      ;; No active cues - return zeros
      {:accumulated-beats 0.0
       :accumulated-ms 0.0
       :phase-offset 0.0
       :effective-beats 0.0
       :time-ms 0
       :bpm (or (ex/bpm s) 120.0)
       :trigger-time 0})))

(defn get-active-global-effects
  "Get all active effect instances from the effects grid.
   Returns an effect chain or nil if no active effects.
   
   Performance: Uses eager collection operations to avoid lazy evaluation overhead."
  []
  (let [s (state/get-raw-state)
        effect-chains (get-in s [:chains :effect-chains] {})]
    (when (seq effect-chains)
      ;; Use eager operations: into with transducer (comp filter mapcat)
      (let [active-effects (into []
                                 (comp (filter :active?)
                                       (mapcat :items))
                                 (vals effect-chains))]
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
   Generates a frame directly from the preset generator."
  [preset-instance _elapsed-ms]
  (let [{:keys [preset-id params]} preset-instance]
    (if (seq params)
      (presets/generate-frame-with-params preset-id params)
      (presets/generate-frame preset-id))))

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
                          [(from-point t/X) (from-point t/Y) 0.0 0.0 0.0])
        ;; Second: blanking point at destination (move to new position with laser off)
        blank-at-dest (when to-point
                        [(to-point t/X) (to-point t/Y) 0.0 0.0 0.0])]
    (filterv some? [blank-at-source blank-at-dest])))

(defn- concatenate-frames
  "Concatenate multiple frames with blanking points between them.
   Returns a frame (vector of points) or nil."
  [frames _elapsed-ms]
  (when (seq frames)
    (let [combined-points (reduce
                            (fn [acc frame]
                              (if (empty? frame)
                                acc
                                (if (empty? acc)
                                  ;; First frame - just add points
                                  (vec frame)
                                  ;; Subsequent frames - add blanking jump then points
                                  (let [last-point (peek acc)
                                        first-new-point (first frame)
                                        blanking (create-blanking-jump last-point first-new-point)]
                                    (-> acc
                                        (into blanking)
                                        (into frame))))))
                            []
                            frames)]
      (when (seq combined-points)
        combined-points))))

(defn- render-item-with-effects
  "Recursively render a cue chain item (preset or group) applying effects at each level.
   
   Effect Pipeline:
   - For presets: render → apply preset effects
   - For groups: render children → concatenate → apply group effects
   
   Returns a frame (vector of points) or nil if disabled/empty."
  [item elapsed-ms bpm trigger-time timing-ctx]
  (when (:enabled? item true)
    (cond
      ;; Preset: render and apply its effects
      (= :preset (:type item))
      (when-let [frame (render-preset-instance item elapsed-ms)]
        (apply-item-effects frame item elapsed-ms bpm trigger-time timing-ctx))
      
      ;; Group: render children, concatenate, then apply group effects
      (chains/group? item)
      (let [;; Use eager u/keepv to avoid lazy evaluation in frame rendering hot path
            child-frames (u/keepv #(render-item-with-effects % elapsed-ms bpm trigger-time timing-ctx)
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
   
   This creates the effect of all shapes being displayed at once via persistence of vision.
   
   Performance: Uses eager u/keepv to avoid lazy evaluation overhead in hot path."
  [cue-chain elapsed-ms bpm trigger-time timing-ctx]
  (let [items (:items cue-chain [])
        ;; Use eager u/keepv instead of lazy keep for frame rendering hot path
        rendered-frames (u/keepv #(render-item-with-effects % elapsed-ms bpm trigger-time timing-ctx) items)]
    (concatenate-frames rendered-frames elapsed-ms)))

(defn generate-current-frame
  "Generate the current animation frame based on state.
   Supports multi-cue playback: generates frames for all active cues and concatenates them.
   Applies active effects from the effects grid to the combined frame.
   Optionally filters based on preview zone group setting.
   Returns a LaserFrame or nil if nothing to render.
   
   Options:
   - skip-zone-filter? - If true, bypasses preview zone filtering (for IDN streaming)
   
   Zone filtering (when not skipped):
   - Gets each cue's destination zone and collects all effects
   - Resolves final target zones after applying zone effects
   - Only includes cue frames if targets match the preview zone filter
   
   Frame generation is profiled to track performance."
  ([] (generate-current-frame {}))
  ([{:keys [skip-zone-filter?] :or {skip-zone-filter? false}}]
   (when (is-playing?)
     (let [all-cues (get-all-active-cues-data)]
       (when (seq all-cues)
         (let [preview-zone (when-not skip-zone-filter? (get-preview-zone-filter))
               raw-state (state/get-raw-state)
               zone-group-ids (set (keys (get raw-state :zone-groups {})))
               bpm (get-bpm)
               current-time (System/currentTimeMillis)
               
               ;; Measure base frame generation
               base-start (timing/nanotime)
               
               ;; Generate frame for each active cue
               cue-frames (u/keepv
                            (fn [{:keys [cue-chain cue-timing]}]
                              ;; Zone filtering per cue
                              (let [destination (:destination-zone cue-chain)
                                    collected-effects (ze/collect-effects-from-cue-chain (:items cue-chain))
                                    final-targets (ze/resolve-final-target destination collected-effects zone-group-ids)
                                    matches? (or skip-zone-filter?
                                                 (matches-preview-zone? preview-zone final-targets))]
                                (when matches?
                                  (let [trigger-time (:trigger-time cue-timing)
                                        elapsed (- current-time trigger-time)
                                        timing-ctx (cue-timing/get-cue-timing-context cue-timing bpm)]
                                    (generate-frame-from-cue-chain cue-chain elapsed bpm trigger-time timing-ctx)))))
                            all-cues)
               
               ;; Concatenate all cue frames with blanking between them
               base-frame (concatenate-frames cue-frames 0)
               base-end (timing/nanotime)]
           
           (when base-frame
             (let [effect-chain (get-active-global-effects)
                   
                   ;; Measure effect chain application (from effects grid)
                   ;; Use timing context from first active cue for global effects
                   first-cue-timing (:cue-timing (first all-cues))
                   trigger-time (:trigger-time first-cue-timing)
                   elapsed (- current-time trigger-time)
                   timing-ctx (cue-timing/get-cue-timing-context first-cue-timing bpm)
                   
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
               (let [base-time-us (timing/nanos->micros (- base-end base-start))
                     effects-time-us (if effect-chain
                                       (timing/nanos->micros (- effects-end effects-start))
                                       0)
                     effect-count (if effect-chain (count (:effects effect-chain)) 0)
                     point-count (count final-frame)]
                 
                 ;; Frame profiler (always-on stats)
                 (profiler/record-frame-timing!
                   {:base-time-us base-time-us
                    :effects-time-us effects-time-us
                    :effect-count effect-count})
                 
                 ;; JFR event (low-overhead, for continuous recording and spike detection)
                 (jfr/emit-frame-event!
                   {:base-time-us base-time-us
                    :effects-time-us effects-time-us
                    :effect-count effect-count
                    :point-count point-count}))
               
               final-frame))))))))


;; Frame Conversion for Preview


(defn laser-point->preview-point
  "Convert a LaserPoint vector to a map suitable for preview rendering.
   
   LaserPoints are 5-element vectors [x y r g b] with normalized values:
   - x, y: -1.0 to 1.0 (already normalized)
   - r, g, b: 0.0 to 1.0 (already normalized)
   
   Preview uses the same format, so we just pass through the values."
  [point]
  {:x (point t/X)           ;; Already normalized (-1.0 to 1.0)
   :y (point t/Y)           ;; Already normalized (-1.0 to 1.0)
   :r (point t/R)           ;; Already normalized (0.0 to 1.0)
   :g (point t/G)           ;; Already normalized (0.0 to 1.0)
   :b (point t/B)           ;; Already normalized (0.0 to 1.0)
   :blanked? (t/blanked? point)})

(defn frame->preview-data
  "Convert a frame (vector of points) to preview-friendly data."
  [frame]
  (when (seq frame)
    {:points (mapv laser-point->preview-point frame)}))

(defn get-preview-frame
  "Get the current frame in preview-friendly format."
  []
  (frame->preview-data (generate-current-frame)))


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
                      (cond-> {:avg-latency-us (:avg-total-us recent-stats)
                               :p95-latency-us (:p95-total-us recent-stats)
                               :max-latency-us (:max-total-us recent-stats)
                               :avg-base-us (:avg-base-us recent-stats)
                               :avg-effects-us (:avg-effects-us recent-stats)}
                        ;; Include IDN streaming stats when available
                        (:avg-idn-us recent-stats)
                        (merge {:avg-idn-us (:avg-idn-us recent-stats)
                                :p95-idn-us (:p95-idn-us recent-stats)
                                :max-idn-us (:max-idn-us recent-stats)})))]
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
