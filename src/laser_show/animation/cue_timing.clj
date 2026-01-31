(ns laser-show.animation.cue-timing
  "Centralized timing utilities for multi-cue playback.
   
   This namespace provides functions for managing timing state for the multi-cue
   playback system. Each cue has independent timing accumulators, and there's also
   a global clock for BPM visualization.
   
   Key concepts:
   - Global clock: Independent timing for BPM visualization, never reset by cue triggers
   - Per-cue timing: Each active cue has its own accumulated beats/ms and phase offset
   - Phase offset decay: Gradual synchronization to global beat phase")


(defn update-global-clock
  "Update global clock accumulators based on delta time.
   
   The global clock runs continuously and is only reset by the retrigger button.
   It's used for BPM visualization and should never reset when cues are triggered.
   
   Parameters:
   - global-clock: Current global clock state map
   - current-time-ms: Current time in milliseconds
   - bpm: Current BPM
   
   Returns: Updated global clock state"
  [{:keys [accumulated-beats accumulated-ms last-frame-time] :as gc} current-time-ms bpm]
  (let [last-time (or last-frame-time 0)]
    (if (pos? last-time)
      (let [delta-ms (- current-time-ms last-time)]
        ;; Guard against unreasonable deltas (>1 second = probably pause/resume)
        (if (> delta-ms 1000)
          ;; Skip accumulation, just update timestamp
          (assoc gc :last-frame-time current-time-ms)
          ;; Normal accumulation
          (let [delta-beats (* delta-ms (/ bpm 60000.0))]
            {:accumulated-beats (+ (or accumulated-beats 0.0) delta-beats)
             :accumulated-ms (+ (or accumulated-ms 0.0) delta-ms)
             :last-frame-time current-time-ms})))
      ;; First frame - just initialize timestamp
      (assoc gc :last-frame-time current-time-ms))))


(defn update-cue-timing
  "Update a cue's timing accumulators based on delta time.
   Applies phase offset exponential decay toward target.
   
   Parameters:
   - cue-timing: Current cue timing state map
   - current-time-ms: Current time in milliseconds
   - bpm: Current BPM
   - resync-rate: Beats to reach ~63% of phase correction (decay rate)
   
   Returns: Updated cue timing state"
  [{:keys [accumulated-beats accumulated-ms phase-offset phase-offset-target 
           last-frame-time trigger-time] :as ct}
   current-time-ms bpm resync-rate]
  (let [last-time (or last-frame-time 0)]
    (if (pos? last-time)
      (let [delta-ms (- current-time-ms last-time)]
        ;; Guard against unreasonable deltas
        (if (> delta-ms 1000)
          ;; Skip accumulation, just update timestamp
          (assoc ct :last-frame-time current-time-ms)
          ;; Normal accumulation with phase decay
          (let [delta-beats (* delta-ms (/ bpm 60000.0))
                ;; Exponential decay toward target phase offset
                decay (Math/exp (- (/ delta-beats (max 0.1 resync-rate))))
                new-phase (+ (or phase-offset-target 0.0)
                            (* (- (or phase-offset 0.0) (or phase-offset-target 0.0)) 
                               decay))]
            (assoc ct
                   :accumulated-beats (+ (or accumulated-beats 0.0) delta-beats)
                   :accumulated-ms (+ (or accumulated-ms 0.0) delta-ms)
                   :phase-offset new-phase
                   :last-frame-time current-time-ms))))
      ;; First frame - just initialize timestamp
      (assoc ct :last-frame-time current-time-ms))))


(defn get-cue-timing-context
  "Build timing context map for modulator evaluation from cue timing state.
   
   Parameters:
   - cue-timing: Cue timing state map
   - bpm: Current BPM
   
   Returns: Timing context map with keys:
   - :accumulated-beats - Total beats accumulated since cue trigger
   - :accumulated-ms - Total milliseconds since cue trigger
   - :phase-offset - Current phase offset for beat sync
   - :effective-beats - accumulated-beats + phase-offset
   - :time-ms - Same as accumulated-ms
   - :bpm - Current BPM
   - :trigger-time - When this cue was triggered"
  [{:keys [accumulated-beats accumulated-ms phase-offset trigger-time]} bpm]
  {:accumulated-beats (or accumulated-beats 0.0)
   :accumulated-ms (or accumulated-ms 0.0)
   :phase-offset (or phase-offset 0.0)
   :effective-beats (+ (or accumulated-beats 0.0) (or phase-offset 0.0))
   :time-ms (or accumulated-ms 0)
   :bpm (or bpm 120.0)
   :trigger-time (or trigger-time 0)})


(defn get-global-timing-context
  "Build timing context map for global effects from global clock state.
   
   Unlike per-cue timing, global effects:
   - Don't reset on cue triggers
   - Don't need phase offset (already synced at global level)
   - Use accumulated-ms as effective elapsed time
   
   Parameters:
   - global-clock: Global clock state map
   - bpm: Current BPM
   
   Returns: Timing context map with keys:
   - :accumulated-beats - Total beats accumulated since startup/last reset
   - :accumulated-ms - Total milliseconds since startup/last reset
   - :phase-offset - Always 0 for global clock
   - :effective-beats - Same as accumulated-beats (no phase correction needed)
   - :time-ms - Same as accumulated-ms
   - :bpm - Current BPM
   - :trigger-time - Always 0 (global effects don't have a trigger time)"
  [{:keys [accumulated-beats accumulated-ms]} bpm]
  {:accumulated-beats (or accumulated-beats 0.0)
   :accumulated-ms (or accumulated-ms 0.0)
   :phase-offset 0.0
   :effective-beats (or accumulated-beats 0.0)
   :time-ms (or accumulated-ms 0)
   :bpm (or bpm 120.0)
   :trigger-time 0})


(defn create-cue-timing-state
  "Create initial timing state for a newly triggered cue.
   
   Parameters:
   - current-time-ms: Current time in milliseconds
   - global-clock-beats: Current accumulated beats from global clock (for phase sync)
   
   Returns: Initial cue timing state map"
  [current-time-ms global-clock-beats]
  {:trigger-time current-time-ms
   :accumulated-beats 0.0
   :accumulated-ms 0.0
   :phase-offset 0.0
   :phase-offset-target 0.0
   :last-frame-time current-time-ms})


(defn reset-cue-timing
  "Reset cue timing accumulators to zero (for retrigger).
   Keeps the trigger time.
   
   Parameters:
   - cue-timing: Cue timing state to reset
   
   Returns: Reset cue timing state"
  [cue-timing]
  (assoc cue-timing
         :accumulated-beats 0.0
         :accumulated-ms 0.0
         :phase-offset 0.0
         :phase-offset-target 0.0))


(defn reset-global-clock
  "Reset global clock accumulators to zero.
   
   Parameters:
   - global-clock: Global clock state to reset
   
   Returns: Reset global clock state"
  [global-clock]
  (assoc global-clock
         :accumulated-beats 0.0
         :accumulated-ms 0.0))
