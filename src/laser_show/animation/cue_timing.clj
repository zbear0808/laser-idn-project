(ns laser-show.animation.cue-timing
  "Centralized timing utilities for multi-cue playback.
   
   This namespace provides functions for managing timing state for the multi-cue
   playback system. Each cue has independent timing accumulators, and there's also
   a global clock for BPM visualization. The show clock
   (laser-show.animation.show-time) keeps these maps and calls the update
   functions 60 times a second; app state only holds each cue's intent
   (activate-cue, request-seek).
   
   Key concepts:
   - Global clock: Independent timing for BPM visualization, never reset by cue triggers
   - Per-cue timing: Each active cue has its own accumulated beats/ms and phase offset
   - Phase offset decay: Gradual synchronization to global beat phase"
  (:require [laser-show.common.util :as u]))


(def ^:private ^:const snap-threshold
  "Phase difference threshold below which we snap directly to target.
   2% of a beat, ~10ms at 120 BPM - imperceptible to humans."
  0.02)

(def ^:private ^:const decay-epsilon
  "Beats a run may fall short of its :decay-beats and still end: the sum of
   60 ticks a second misses a whole beat by about 1e-15."
  1e-9)

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

(defn- with-aligned-target
  "timing with a :phase-offset-target that glides its effective beats onto
   the nearest whole beat, the shorter way."
  [{:keys [accumulated-beats phase-offset effective-beats] :as timing}]
  (let [eff-beats (double (or effective-beats
                              (+ (double (or accumulated-beats 0.0))
                                 (double (or phase-offset 0.0)))))
        correction (calculate-beat-phase-correction eff-beats)
        current-phase (double (or phase-offset 0.0))]
    (assoc timing
           :phase-offset current-phase
           :phase-offset-target (+ current-phase correction))))

(defn- glide-phase
  "The phase offset after delta-beats: within snap-threshold of the target it
   snaps there, otherwise it decays toward it, reaching about 63 % of the way
   every resync-rate beats."
  ^double [^double phase ^double target ^double delta-beats ^double resync-rate]
  (let [diff (- phase target)]
    (if (< (Math/abs diff) snap-threshold)
      target
      (+ target (* diff (Math/exp (- (/ delta-beats (max 0.1 resync-rate)))))))))

(defn align-global-clock-phase
  "Set target phase offset on global clock so it smoothly glides to align
   with the nearest integer beat boundary without visual snapping."
  [global-clock]
  (with-aligned-target (or global-clock {:accumulated-beats 0.0 :accumulated-ms 0.0 :last-frame-time 0})))

(defn align-cue-timing-phase
  "Set target phase offset on a cue timing state so it smoothly glides to align
   with the nearest integer beat boundary. Timeline cues are returned
   unchanged: a sequenced show must not shift when someone taps the tempo."
  [cue-timing]
  (if (:timeline? cue-timing)
    cue-timing
    (with-aligned-target cue-timing)))

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
                 new-phase (glide-phase phase-offset' target' delta-beats resync-rate')
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
   - bpm: The cue's tempo (a timeline's locked tempo, else the global BPM)
   - resync-rate: Beats to reach ~63% of phase correction (decay rate)
   - loop-config: Map with {:enabled? :start :duration} for arrangement looping
   - timeline-cfg: (timeline cues) {:length beats}
   
   Timeline cues (:timeline? true) have no phase glide (phase stays 0, so
   accumulated = effective beats), don't move while :paused?, and without an
   enabled loop stop at :length with :ended? true. A cue with :decay-beats
   (vibe mode) ends with :ended? true once its accumulated beats reach them
   while :hold? is false. Missing accumulators and phase keys count as 0, and
   a missing resync-rate as 4 beats.
   
   Returns: Updated cue timing state"
  ([ct current-time-ms bpm resync-rate loop-config]
   (update-cue-timing ct current-time-ms bpm resync-rate loop-config nil))
  ([{:keys [accumulated-beats accumulated-ms phase-offset phase-offset-target
            last-frame-time timeline? paused? decay-beats hold?] :as ct}
    current-time-ms bpm resync-rate {:keys [enabled? start duration] :as _loop-config} timeline-cfg]
   (cond
     (nil? last-frame-time)
     ;; First frame - just initialize timestamp
     (assoc ct :last-frame-time current-time-ms)

     (or paused? (:ended? ct))
     (assoc ct :last-frame-time current-time-ms)

     :else
     (let [delta-ms (- current-time-ms last-frame-time)]
       ;; Guard against unreasonable deltas
       (if (> delta-ms 1000)
         ;; Skip accumulation, just update timestamp
         (assoc ct :last-frame-time current-time-ms)
         ;; Normal accumulation with phase decay
         (let [delta-beats (* delta-ms (/ bpm 60000.0))
               new-phase (if timeline?
                           0.0
                           (glide-phase (double (or phase-offset 0.0))
                                        (double (or phase-offset-target 0.0))
                                        delta-beats
                                        (double (or resync-rate 4.0))))

               new-accumulated-beats (+ (or accumulated-beats 0.0) delta-beats)
               looping? (and enabled? (pos? duration))
               length (:length timeline-cfg)

               ;; Wrap beats if we crossed the loop end
               wrapped-beats (if (and looping?
                                      (>= new-accumulated-beats (+ start duration)))
                               (let [overshoot (- new-accumulated-beats (+ start duration))]
                                 (+ start (mod overshoot duration)))
                               new-accumulated-beats)
               at-end? (and timeline? (not looping?) length
                            (>= wrapped-beats (double length)))
               decayed? (and decay-beats (not hold?)
                             (>= (+ new-accumulated-beats decay-epsilon) (double decay-beats)))]
           (cond-> (assoc ct
                          :accumulated-beats (if at-end? (double length) wrapped-beats)
                          :accumulated-ms (+ (or accumulated-ms 0.0) delta-ms)
                          :phase-offset new-phase
                          :last-frame-time current-time-ms)
             timeline? (assoc :phase-offset-target 0.0)
             (or at-end? decayed?) (assoc :ended? true))))))))


(defn get-cue-timing-context
  "Build timing context map for modulator evaluation from cue timing state.
   A timing context holds relative time only: the cue's wall-clock
   :trigger-time stays in the timing state. Missing keys count as 0.
   
   Parameters:
   - cue-timing: Cue timing state map
   - bpm: Current BPM
   
   Returns: Timing context map with keys:
   - :accumulated-beats - Total beats accumulated since cue trigger
   - :accumulated-ms - Total milliseconds since cue trigger
   - :phase-offset - Current phase offset for beat sync
   - :effective-beats - accumulated-beats + phase-offset
   - :time-ms - Same as accumulated-ms
   - :bpm - Current BPM"
  [{:keys [accumulated-beats accumulated-ms phase-offset]} bpm]
  (let [beats (or accumulated-beats 0.0)
        ms (or accumulated-ms 0.0)
        phase (or phase-offset 0.0)]
    {:accumulated-beats beats
     :accumulated-ms ms
     :phase-offset phase
     :effective-beats (+ beats phase)
     :time-ms ms
     :bpm bpm}))


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
   - :bpm - Current BPM"
  [{:keys [accumulated-beats accumulated-ms phase-offset effective-beats]} bpm]
  (let [phase (double (or phase-offset 0.0))
        acc-beats (double (or accumulated-beats 0.0))
        eff-beats (double (or effective-beats (+ acc-beats phase)))]
    {:accumulated-beats acc-beats
     :accumulated-ms (double (or accumulated-ms 0.0))
     :phase-offset phase
     :effective-beats eff-beats
     :time-ms (double (or accumulated-ms 0.0))
     :bpm bpm}))


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
  (-> cue-timing
      (assoc :accumulated-beats 0.0
             :accumulated-ms 0.0
             :phase-offset 0.0
             :phase-offset-target 0.0)
      (dissoc :ended?)))


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


;; Starting cues on the :playback map
;;
;; App state holds an intent per playing cue; the show clock
;; (laser-show.animation.show-time) owns the timing and starts it with
;; start-timing at its next tick.


(defn start-timing
  "Fresh timing for a cue started at now-ms, while the global clock is at
   global-beats. Timeline cues (:timeline? true) start at :start-beat with no
   phase glide; other cues glide toward the global beat phase."
  [now-ms global-beats {:keys [timeline? start-beat]}]
  (let [base (create-cue-timing-state now-ms global-beats)]
    (if timeline?
      (assoc base
             :timeline? true
             :accumulated-beats (double (or start-beat 0.0))
             :phase-offset 0.0
             :phase-offset-target 0.0
             :paused? false)
      base)))

(defn activate-cue
  "Start the cue at cell as the run run-id: write its intent to
   (:active-cues playback) and set :playing?. Timeline cues (:timeline? true)
   say where to start (:start-beat). With :decay-beats the run ends after
   that many beats once :hold? is false (vibe mode: a press holds it, its
   release lets it go). The intent holds no position: the show clock starts
   the run's timing at its next tick."
  [playback cell run-id {:keys [timeline? start-beat decay-beats hold?]}]
  (-> playback
      (assoc-in [:active-cues cell] (cond-> {:run run-id}
                                      timeline? (assoc :timeline? true
                                                       :start-beat (double (or start-beat 0.0)))
                                      decay-beats (assoc :decay-beats (double decay-beats))
                                      (some? hold?) (assoc :hold? (boolean hold?))))
      (assoc :playing? true
             :active-cell cell)))

(defn deactivate-cue
  "Stop the cue at cell: its intent leaves (:active-cues playback), the
   others stay as they are. Removing the last one sets :playing? false;
   while others remain :playing? is left as it was, so removing a cue never
   starts a stopped transport (Stop holds the rest). If cell had the focus
   (:active-cell) it moves to the first remaining cell, nil after the last.
   No intent at cell: playback unchanged."
  [playback cell]
  (let [cues (:active-cues playback)]
    (if-not (contains? cues cell)
      playback
      (let [remaining (dissoc cues cell)]
        (cond-> (assoc playback :active-cues remaining)
          (empty? remaining) (assoc :playing? false)
          (= (:active-cell playback) cell) (assoc :active-cell (first (keys remaining))))))))

(defn request-seek
  "The intent, asking the show clock to move its run to beat. id names the
   request: the clock applies each id once."
  [intent beat id]
  (assoc intent :seek {:id id :beat (max 0.0 (double beat))}))

(defn position-ids
  "{:restart :seek}: the ids of the last requests that moved the cue at
   cell, the transport's restart (Stop, Retrigger) and the cell's own seek,
   each nil before the first. The show clock puts them in each end it
   reports, from the state it stepped from; the end's handler compares them
   with its own state's, so the end of a position that a later restart or
   seek replaced stops nothing."
  [playback cell]
  {:restart (get-in playback [:requests :restart :id])
   :seek (get-in playback [:active-cues cell :seek :id])})

(defn seek
  "Move a cue's position to beat (clears :ended?)."
  [cue-timing beat]
  (-> cue-timing
      (assoc :accumulated-beats (max 0.0 (double beat)))
      (dissoc :ended?)))
