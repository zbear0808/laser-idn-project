(ns laser-show.backend.frame-provider
  "Frame provider for streaming - generates animation frames based on current state.
   This is separated from UI to maintain proper layer boundaries."
  (:require
   [laser-show.animation.presets :as presets]
   [laser-show.animation.time :as anim-time]
   [laser-show.animation.types :as t]
   [laser-show.state.atoms :as state]))

(defn create-frame-provider
  "Create a function that provides the current animation frame for streaming.
   Reads from runtime state atoms.
   
   Returns a function that:
   - Returns the current animation frame when called
   - Returns nil if no cell is active or no preset is assigned
   
   The returned frame respects:
   - Current active cell from playback state
   - Trigger time for animation timing
   - Global BPM for time-based animations"
  []
  (fn []
    (let [active-cell (state/get-active-cell)
          trigger-time (state/get-trigger-time)]
      (when active-cell
        (let [cell (state/get-cell (first active-cell) (second active-cell))
              preset-id (:preset-id cell)]
          (when preset-id
            (let [anim (presets/create-animation-from-preset preset-id)
                  elapsed (- (System/currentTimeMillis) trigger-time)
                  _bpm (anim-time/get-global-bpm)
                  base-frame (t/get-frame anim elapsed)]
              base-frame)))))))

(defn create-frame-provider-with-effects
  "Create a frame provider that also applies effects from the effects grid.
   
   Parameters:
   - effects-applier: A function (fn [frame time-ms bpm] -> frame) that applies effects
   
   Returns a function that provides frames with effects applied."
  [effects-applier]
  (fn []
    (let [base-provider (create-frame-provider)
          frame (base-provider)]
      (when frame
        (if effects-applier
          (effects-applier frame (System/currentTimeMillis) (anim-time/get-global-bpm))
          frame)))))
