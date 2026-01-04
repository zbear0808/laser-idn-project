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
  (:require [laser-show.state.core :as state]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.types :as t]
            [laser-show.common.timing :as timing]
            [laser-show.profiling.frame-profiler :as profiler]
            ;; Require effect implementations to register them
            [laser-show.animation.effects.shape]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.calibration]))


;; Frame Generation


;; NOTE: All these functions use get-raw-state instead of get-state because
;; they are called from a background timer thread (preview-update), not the
;; UI thread. Using fx/sub-val from a background thread causes assertion errors.

(defn get-active-cell-data
  "Get the cell data for the currently active cell.
   Returns a map with either :cue-chain or :preset-id (legacy)."
  []
  (let [s (state/get-raw-state)
        active-cell (get-in s [:playback :active-cell])]
    (when active-cell
      (let [[col row] active-cell]
        (get-in s [:grid :cells [col row]])))))

(defn get-active-cell-preset
  "Get the preset ID for the currently active cell.
   DEPRECATED: Use get-active-cell-data for cue chain support."
  []
  (let [cell-data (get-active-cell-data)]
    (or (:preset-id cell-data)
        ;; Legacy support: if cue-chain exists with single preset, use that
        (when-let [cue-chain (:cue-chain cell-data)]
          (when-let [first-item (first (:items cue-chain))]
            (when (= :preset (:type first-item))
              (:preset-id first-item)))))))

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

(defn get-active-effects
  "Get all active effect instances from the effects grid.
   Returns an effect chain or nil if no active effects."
  []
  (let [s (state/get-raw-state)
        effects-cells (get-in s [:effects :cells] {})]
    (when (seq effects-cells)
      (let [active-effects (->> effects-cells
                                (vals)
                                (filter :active)
                                (mapcat :effects)
                                (vec))]
        (when (seq active-effects)
          ;; Debug logging - uncomment to trace effect chain construction
          ;; (println "[DEBUG get-active-effects] Found" (count active-effects) "effects:" 
          ;;          (mapv :effect-id active-effects))
          {:effects active-effects})))))

;; Cue Chain Rendering

(defn- flatten-enabled-presets
  "Flatten a cue chain to a list of enabled preset instances.
   Respects both preset and group enabled states."
  [items]
  (reduce
    (fn [acc item]
      (if (chains/group? item)
        ;; Group: recurse if enabled
        (if (:enabled? item true)
          (into acc (flatten-enabled-presets (:items item [])))
          acc)
        ;; Preset: add if enabled
        (if (:enabled? item true)
          (conj acc item)
          acc)))
    []
    items))

(defn- calculate-active-preset
  "Calculate which preset should be active based on elapsed time.
   Uses equal time division: each preset gets (total-time / preset-count) ms.
   
   Returns {:preset preset-instance :preset-elapsed ms-within-preset} or nil."
  [presets elapsed-ms]
  (when (seq presets)
    (let [preset-count (count presets)
          ;; Default duration: 2 beats per preset at 120 BPM = 1000ms
          ;; TODO: Add configurable duration per preset
          preset-duration-ms 1000
          total-duration (* preset-count preset-duration-ms)
          ;; Loop through presets
          loop-elapsed (mod elapsed-ms total-duration)
          preset-idx (int (/ loop-elapsed preset-duration-ms))
          preset-elapsed (mod loop-elapsed preset-duration-ms)]
      {:preset (nth presets preset-idx)
       :preset-elapsed preset-elapsed
       :preset-index preset-idx})))

(defn- render-preset-instance
  "Render a single preset instance with its parameters.
   Creates an animation from the preset-id and applies preset-specific params."
  [preset-instance elapsed-ms]
  (let [{:keys [preset-id params]} preset-instance]
    (when-let [anim (if (seq params)
                      (presets/create-animation-with-params preset-id params)
                      (presets/create-animation-from-preset preset-id))]
      (t/get-frame anim elapsed-ms))))

(defn- apply-preset-effects
  "Apply a preset instance's effect chain to a frame."
  [frame preset-instance elapsed-ms bpm trigger-time]
  (let [preset-effects (:effects preset-instance [])]
    (if (seq preset-effects)
      (try
        (effects/apply-effect-chain frame {:effects preset-effects} elapsed-ms bpm trigger-time)
        (catch Exception e
          (println "[ERROR apply-preset-effects] Effect chain failed:" (.getMessage e))
          frame))
      frame)))

(defn- generate-frame-from-cue-chain
  "Generate a frame from a cue chain.
   Handles sequential preset rendering with per-preset effects."
  [cue-chain elapsed-ms bpm trigger-time]
  (let [items (:items cue-chain [])
        enabled-presets (flatten-enabled-presets items)]
    (when (seq enabled-presets)
      (when-let [{:keys [preset preset-elapsed]} (calculate-active-preset enabled-presets elapsed-ms)]
        (when-let [base-frame (render-preset-instance preset preset-elapsed)]
          ;; Apply per-preset effects
          (apply-preset-effects base-frame preset elapsed-ms bpm trigger-time))))))

(defn generate-current-frame
  "Generate the current animation frame based on state.
   Supports both cue chains and legacy preset-id format.
   Applies active effects from the effects grid to the base frame.
   Returns a LaserFrame or nil if nothing to render.
   
   Frame generation is profiled to track performance."
  []
  (when (is-playing?)
    (when-let [cell-data (get-active-cell-data)]
      (let [trigger-time (get-trigger-time)
            elapsed (- (System/currentTimeMillis) trigger-time)
            bpm (get-bpm)
            
            ;; Measure base frame generation
            base-start (timing/nanotime)
            base-frame (cond
                         ;; New cue chain format
                         (:cue-chain cell-data)
                         (generate-frame-from-cue-chain (:cue-chain cell-data) elapsed bpm trigger-time)
                         
                         ;; Legacy preset-id format
                         (:preset-id cell-data)
                         (when-let [anim (presets/create-animation-from-preset (:preset-id cell-data))]
                           (t/get-frame anim elapsed)))
            base-end (timing/nanotime)]
        
        (when base-frame
          (let [effect-chain (get-active-effects)
                
                ;; Measure effect chain application (from effects grid)
                effects-start (timing/nanotime)
                final-frame (if effect-chain
                              (try
                                (effects/apply-effect-chain base-frame effect-chain elapsed bpm trigger-time)
                                (catch Exception e
                                  (println "[ERROR generate-current-frame] Effect chain failed:" (.getMessage e))
                                  (println "  effect-chain:" effect-chain)
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
   Call this periodically to update the preview."
  []
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
    (println "Preview updates stopped")))

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
                                   (println "Preview update error:" (.getMessage e))))))
                           0
                           interval-ms)
     (reset! preview-timer timer)
     (println "Preview updates started at" fps "FPS"))))

