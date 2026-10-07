(ns laser-show.animation.cue-timing
  "Centralized timing utilities for multi-cue playback.
   
   This namespace provides functions for managing timing state for the multi-cue
   playback system. Each cue has independent timing accumulators, and there's also
   a global clock for BPM visualization.
   
   Key concepts:
   - Global clock: Independent timing for BPM visualization, never reset by cue triggers
   - Per-cue timing: Each active cue has its own accumulated beats/ms and phase offset
   - Phase offset decay: Gradual synchronization to global beat phase"
  (:require [laser-show.common.util :as u]))


(def ^:private ^:const snap-threshold
  "Phase difference threshold below which we snap directly to target.
   2% of a beat, ~10ms at 120 BPM - imperceptible to humans."
  0.02)

(defn calculate-beat-phase-correction
  "Calculate the shortest-path phase correction (-0.5 to 0.5) to align
   current beat count with the nearest integer beat boundary (0.0 phase).
   
   Positive correction advances phase forward to reach the next beat.
   Negative correction pulls phase backward to align with the current beat."
  ^double [^double current-beats]
  (let [beat-phase (u/fmod current-beats 1.0)]
    (if (> beat-phase 0.5)
      (- 1.0 beat-phase)
      (- beat-phase))))

(defn align-global-clock-phase
  "Set target phase offset on global clock so it smoothly glides to align
   with the nearest integer beat boundary without visual snapping."
  [{:keys [accumulated-beats phase-offset effective-beats] :as global-clock}]
  (let [eff-beats (double (or effective-beats
                              (+ (double (or accumulated-beats 0.0))
                                 (double (or phase-offset 0.0)))))
        correction (calculate-beat-phase-correction eff-beats)
        current-phase (double (or phase-offset 0.0))
        target-phase (+ current-phase correction)]
    (assoc (or global-clock {:accumulated-beats 0.0 :accumulated-ms 0.0 :last-frame-time 0})
           :phase-offset current-phase
           :phase-offset-target target-phase)))

(defn align-cue-timing-phase
  "Set target phase offset on a cue timing state so it smoothly glides to align
   with the nearest integer beat boundary."
  [{:keys [accumulated-beats phase-offset effective-beats] :as cue-timing}]
  (let [eff-beats (double (or effective-beats
                              (+ (double (or accumulated-beats 0.0))
                                 (double (or phase-offset 0.0)))))
        correction (calculate-beat-phase-correction eff-beats)
        current-phase (double (or phase-offset 0.0))
        target-phase (+ current-phase correction)]
    (assoc cue-timing
           :phase-offset current-phase
           :phase-offset-target target-phase)))

(defn align-all-active-cues-phase
  "Align phase target for all active cues."
  [active-cues]
  (when (seq active-cues)
    (reduce-kv (fn [acc k v]
                 (assoc acc k (align-cue-timing-phase v)))
               {}
               active-cues)))

(defn update-global-clock
  "Update global clock accumulators based on delta time with smooth phase offset decay.
   
   The global clock runs continuously and is only reset by the retrigger button.
   It's used for BPM visualization and global effects. Phase offsets decay smoothly
   toward phase-offset-target to enable tap-tempo and external sync beat alignment
   without visual snapping.
   
   Parameters:
   - global-clock: Current global clock state map
   - current-time-ms: Current time in milliseconds
   - bpm: Current BPM
   - resync-rate: (optional) Beats to reach ~63% of phase correction, defaults to 4.0
   
   Returns: Updated global clock state"
  ([global-clock current-time-ms bpm]
   (update-global-clock global-clock current-time-ms bpm 4.0))
  ([{:keys [accumulated-beats accumulated-ms phase-offset phase-offset-target last-frame-time] :as gc}
    current-time-ms bpm resync-rate]
   (let [phase-offset' (double (or phase-offset 0.0))
         target' (double (or phase-offset-target 0.0))
         resync-rate' (double (or resync-rate 4.0))]
     (if last-frame-time
       (let [delta-ms (- current-time-ms last-frame-time)]
         (if (> delta-ms 1000)
           ;; Skip accumulation, just update timestamp
           (assoc gc :last-frame-time current-time-ms)
           ;; Normal accumulation with smooth phase decay
           (let [delta-beats (* delta-ms (/ (double bpm) 60000.0))
                 diff (- phase-offset' target')
                 snap? (< (Math/abs diff) snap-threshold)
                 new-phase (if snap?
                             target'
                             (let [decay (Math/exp (- (/ delta-beats (max 0.1 resync-rate'))))]
                               (+ target' (* diff decay))))
                 new-acc-beats (+ (double (or accumulated-beats 0.0)) delta-beats)
                 new-acc-ms (+ (double (or accumulated-ms 0.0)) delta-ms)]
             (u/->map&
              :accumulated-beats new-acc-beats
              :accumulated-ms new-acc-ms
              :phase-offset new-phase
              :phase-offset-target target'
              :effective-beats (+ new-acc-beats new-phase)
              :last-frame-time current-time-ms))))
       ;; First frame - just initialize timestamp
       (assoc gc
              :last-frame-time current-time-ms
              :phase-offset phase-offset'
              :phase-offset-target target'
              :effective-beats (double (or accumulated-beats 0.0)))))))

(defn update-cue-timing
  "Update a cue's timing accumulators based on delta time.
   Applies phase offset exponential decay toward target, with snap-to-target
   when close enough to eliminate residual drift.
   
   Parameters:
   - cue-timing: Current cue timing state map
   - current-time-ms: Current time in milliseconds
   - bpm: Current BPM
   - resync-rate: Beats to reach ~63% of phase correction (decay rate)
   - loop-config: Map with {:enabled? :start :duration} for arrangement looping
   
   Returns: Updated cue timing state"
  [{:keys [accumulated-beats accumulated-ms phase-offset phase-offset-target
           last-frame-time trigger-time] :as ct}
   current-time-ms bpm resync-rate {:keys [enabled? start duration] :as loop-config}]
  (if last-frame-time
    (let [delta-ms (- current-time-ms last-frame-time)]
      ;; Guard against unreasonable deltas
      (if (> delta-ms 1000)
        ;; Skip accumulation, just update timestamp
        (assoc ct :last-frame-time current-time-ms)
        ;; Normal accumulation with phase decay
        (let [delta-beats (* delta-ms (/ bpm 60000.0))
              diff (- phase-offset phase-offset-target)
              ;; Snap to target when within threshold, else decay
              new-phase (if (< (Math/abs diff) snap-threshold)
                          phase-offset-target
                          (let [decay (Math/exp (- (/ delta-beats (max 0.1 resync-rate))))]
                            (+ phase-offset-target (* diff decay))))

              new-accumulated-beats (+ accumulated-beats delta-beats)

              ;; Wrap beats if we crossed the loop end
              wrapped-beats (if (and enabled?
                                     (pos? duration)
                                     (>= new-accumulated-beats (+ start duration)))
                              (let [overshoot (- new-accumulated-beats (+ start duration))]
                                (+ start (mod overshoot duration)))
                              new-accumulated-beats)]
          (assoc ct
                 :accumulated-beats wrapped-beats
                 :accumulated-ms (+ accumulated-ms delta-ms)
                 :phase-offset new-phase
                 :last-frame-time current-time-ms))))
    ;; First frame - just initialize timestamp
    (assoc ct :last-frame-time current-time-ms)))


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
  {:accumulated-beats accumulated-beats
   :accumulated-ms accumulated-ms
   :phase-offset phase-offset
   :effective-beats (+ accumulated-beats phase-offset)
   :time-ms accumulated-ms
   :bpm bpm
   :trigger-time trigger-time})


(defn get-global-timing-context
  "Build timing context map for global effects from global clock state.
   
   Unlike per-cue timing, global effects:
   - Don't reset on cue triggers
   - Use global clock's smooth phase offset for beat alignment
   - Use accumulated-ms as effective elapsed time
   
   Parameters:
   - global-clock: Global clock state map
   - bpm: Current BPM
   
   Returns: Timing context map with keys:
   - :accumulated-beats - Total beats accumulated since startup/last reset
   - :accumulated-ms - Total milliseconds since startup/last reset
   - :phase-offset - Current smooth phase offset
   - :effective-beats - accumulated-beats + phase-offset
   - :time-ms - Same as accumulated-ms
   - :bpm - Current BPM
   - :trigger-time - Always 0 (global effects don't have a trigger time)"
  [{:keys [accumulated-beats accumulated-ms phase-offset effective-beats]} bpm]
  (let [phase (double (or phase-offset 0.0))
        acc-beats (double (or accumulated-beats 0.0))
        eff-beats (double (or effective-beats (+ acc-beats phase)))]
    {:accumulated-beats acc-beats
     :accumulated-ms (double (or accumulated-ms 0.0))
     :phase-offset phase
     :effective-beats eff-beats
     :time-ms (double (or accumulated-ms 0.0))
     :bpm bpm
     :trigger-time 0}))


(defn create-cue-timing-state
  "Create initial timing state for a newly triggered cue.
   
   The cue starts at phase 0 (beginning of animation) but will gradually drift
   toward alignment with the global beat grid via exponential decay.
   
   Parameters:
   - current-time-ms: Current time in milliseconds
   - global-clock-beats: Current accumulated beats from global clock (for phase sync)
   
   Returns: Initial cue timing state map"
  [current-time-ms global-clock-beats]
  (let [target-phase (mod global-clock-beats 1.0)]
    {:trigger-time current-time-ms
     :accumulated-beats 0.0
     :accumulated-ms 0.0
     :phase-offset 0.0              ;; Start at beginning of animation
     :phase-offset-target target-phase  ;; Drift toward global beat position
     :last-frame-time current-time-ms}))


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
         :accumulated-ms 0.0
         :phase-offset 0.0
         :phase-offset-target 0.0
         :effective-beats 0.0))
