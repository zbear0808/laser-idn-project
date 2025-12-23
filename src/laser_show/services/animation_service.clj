(ns laser-show.services.animation-service
  "Animation service - orchestrates animation operations.
   
   This service provides high-level operations for animation management,
   coordinating between presets, effects, and playback state.
   
   Responsibilities:
   - Creating animations from presets
   - Applying effect chains to frames
   - Managing timing and BPM"
  (:require [laser-show.animation.presets :as presets]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.time :as anim-time]
            [laser-show.animation.types :as t]
            [laser-show.state.dynamic :as dyn]))

;; ============================================================================
;; Animation Creation
;; ============================================================================

(defn create-animation
  "Create an animation from a preset ID with default parameters.
   
   Parameters:
   - preset-id: The preset keyword
   
   Returns: Animation instance or nil if preset not found"
  [preset-id]
  (presets/create-animation-from-preset preset-id))

(defn create-animation-with-params
  "Create an animation from a preset ID with custom parameters.
   
   Parameters:
   - preset-id: The preset keyword
   - params: Map of parameter overrides
   
   Returns: Animation instance or nil if preset not found"
  [preset-id params]
  (presets/create-animation-with-params preset-id params))

(defn get-preset
  "Get a preset definition by ID.
   
   Parameters:
   - preset-id: The preset keyword
   
   Returns: Preset map or nil if not found"
  [preset-id]
  (presets/get-preset preset-id))

(defn list-presets
  "Get all available presets.
   
   Returns: Sequence of preset definitions"
  []
  presets/all-presets)

(defn list-presets-by-category
  "Get presets filtered by category.
   
   Parameters:
   - category: Category keyword (:geometric, :beam, :wave, :abstract, :text)
   
   Returns: Sequence of preset definitions"
  [category]
  (presets/get-presets-by-category category))

;; ============================================================================
;; Frame Generation
;; ============================================================================

(defn get-frame
  "Get a frame from an animation at a specific time.
   
   Parameters:
   - animation: Animation instance
   - time-ms: Time in milliseconds since animation start
   
   Returns: LaserFrame with points"
  [animation time-ms]
  (t/get-frame animation time-ms))

(defn get-current-frame
  "Get the current frame for the active cell.
   Uses the current trigger time from playback state.
   
   Returns: LaserFrame or nil if no active cell"
  []
  (when-let [active-cell (dyn/get-active-cell)]
    (let [cell (dyn/get-cell (first active-cell) (second active-cell))
          preset-id (:preset-id cell)
          trigger-time (dyn/get-trigger-time)]
      (when preset-id
        (let [anim (create-animation preset-id)
              elapsed (- (System/currentTimeMillis) trigger-time)]
          (get-frame anim elapsed))))))

;; ============================================================================
;; Effect Operations
;; ============================================================================

(defn apply-effect
  "Apply a single effect to a frame.
   
   Parameters:
   - frame: LaserFrame to transform
   - effect-instance: {:effect-id :scale :enabled true :params {...}}
   - time-ms: Current time in milliseconds
   
   Returns: Transformed frame"
  [frame effect-instance time-ms]
  (effects/apply-effect frame effect-instance time-ms (anim-time/get-global-bpm)))

(defn apply-effect-chain
  "Apply an effect chain to a frame.
   
   Parameters:
   - frame: LaserFrame to transform
   - chain: Effect chain from effects/make-effect-chain
   - time-ms: Current time in milliseconds
   
   Returns: Transformed frame"
  [frame chain time-ms]
  (effects/apply-effect-chain frame chain time-ms (anim-time/get-global-bpm)))

(defn get-effect
  "Get an effect definition by ID.
   
   Parameters:
   - effect-id: The effect keyword
   
   Returns: Effect definition or nil"
  [effect-id]
  (effects/get-effect effect-id))

(defn list-effects
  "Get all registered effects.
   
   Returns: Sequence of effect definitions"
  []
  (effects/list-effects))

(defn list-effects-by-category
  "Get effects filtered by category.
   
   Parameters:
   - category: Category keyword (:shape, :color, :intensity, :calibration)
   
   Returns: Sequence of effect definitions"
  [category]
  (effects/list-effects-by-category category))

(defn make-effect-instance
  "Create an effect instance with parameters.
   
   Parameters:
   - effect-id: The effect keyword
   - opts: Optional :enabled and :params
   
   Returns: Effect instance map"
  [effect-id & opts]
  (apply effects/make-effect-instance effect-id opts))

(defn make-effect-chain
  "Create an effect chain from effect instances.
   
   Parameters:
   - effect-instances: Variable number of effect instances
   
   Returns: Effect chain"
  [& effect-instances]
  (apply effects/make-effect-chain effect-instances))

;; ============================================================================
;; Timing / BPM
;; ============================================================================

(defn get-bpm
  "Get the current global BPM.
   
   Returns: BPM as a double"
  []
  (anim-time/get-global-bpm))

(defn set-bpm!
  "Set the global BPM.
   
   Parameters:
   - bpm: New BPM value (will be clamped to valid range)"
  [bpm]
  (anim-time/set-global-bpm! bpm))

(defn tap-tempo!
  "Record a tap for tap-tempo BPM detection.
   Call this repeatedly to set BPM by tapping."
  []
  (anim-time/tap-tempo!))

(defn reset-tap-tempo!
  "Reset the tap tempo system."
  []
  (anim-time/reset-tap-times!))

(defn get-beat-position
  "Get the current position within the beat (0.0 to 1.0).
   
   Returns: Beat position as a double"
  []
  (dyn/get-beat-position))

;; ============================================================================
;; Playback Control
;; ============================================================================

(defn retrigger!
  "Retrigger the current animation.
   Resets the trigger time to now, restarting the animation."
  []
  (dyn/trigger!))

(defn start-playback!
  "Start playback."
  []
  (dyn/start-playback!))

(defn stop-playback!
  "Stop playback."
  []
  (dyn/stop-playback!))

(defn playing?
  "Check if currently playing.
   
   Returns: true if playing, false otherwise"
  []
  (dyn/playing?))

(defn get-trigger-time
  "Get the current trigger time.
   
   Returns: Timestamp in milliseconds"
  []
  (dyn/get-trigger-time))

;; ============================================================================
;; Composite Operations
;; ============================================================================

(defn get-frame-with-effects
  "Get a frame with effects applied for the active cell.
   
   Parameters:
   - effects-applier: Function (fn [frame time-ms bpm] -> frame) to apply effects
   
   Returns: Frame with effects applied, or nil if no active cell"
  [effects-applier]
  (when-let [frame (get-current-frame)]
    (if effects-applier
      (effects-applier frame (System/currentTimeMillis) (get-bpm))
      frame)))

(defn calculate-elapsed-time
  "Calculate elapsed time since trigger.
   
   Returns: Elapsed time in milliseconds"
  []
  (- (System/currentTimeMillis) (get-trigger-time)))

(defn calculate-beat-elapsed
  "Calculate the number of beats elapsed since trigger.
   
   Returns: Number of beats as a double"
  []
  (let [elapsed-ms (calculate-elapsed-time)
        bpm (get-bpm)
        ms-per-beat (/ 60000.0 bpm)]
    (/ elapsed-ms ms-per-beat)))
