(ns laser-show.animation.show-time
  "Show time: where the global clock and every playing cue are, and the
   frames of the newest tick.

   The show clock thread (laser-show.services.show-clock) is the only writer.
   60 times a second it runs step on !runtime, renders every playing cue once
   and publishes the result (publish!). Each projector's engine waits on
   frame-signal and sends that tick from its own thread. Everything that reads
   show time reads it here, so it needn't require the clock thread, which
   itself needs frame-service and events.core.

   App state keeps what events decide (state/domains :playback):
   - [:playback :active-cues cell]: an intent, with no position:
     {:run id :timeline? :start-beat :paused? :seek {:id :beat}}, or
     {:chasing? true :timeline? true} for a timeline following MIDI timecode;
   - [:playback :requests]: {:restart {:id} :align {:id} :shift {:id :offset}},
     each applied once per id.
   step reads them once per tick. What the clock learns goes back to app state
   as events: a timeline's end and a timecode chase (:show-clock/cue-ended,
   :show-clock/chase), and what views show a few times a second, the beat
   square, the stats and the MTC text (:show-clock/status, on status-paths).
   Positions never reach app state: views that move every frame draw the
   newest snapshot (views/components/clock_view.clj)."
  (:require [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.common.util :as u]
            [laser-show.state.extractors :as ex]
            [laser-show.timeline.playback :as playback])
  (:import [java.util.concurrent.atomic AtomicLong]))


;; Runtime

(def initial-runtime
  "The runtime after a reset: no runs, the global clock at 0, waiting for its
   first tick (which sets :t0-ns and the clocks' :last-frame-time)."
  {:tick 0
   :t0-ns nil
   :global {:accumulated-beats 0.0 :accumulated-ms 0.0 :phase-offset 0.0
            :phase-offset-target 0.0 :effective-beats 0.0}
   :runs {}
   :chase {}
   :applied {}
   :sent {}})

(defonce ^{:doc "Show time ({:tick :t0-ns :global :runs {run-id timing} :chase {cell timing}
                 :applied {kind request-id} :sent {report now-ms} :status {…}}). Only the
                 show clock thread writes it (show-clock/tick! in tests)."}
  !runtime
  (atom initial-runtime))

(def status-paths
  "Where app state holds the show clock's status, which :show-clock/status
   writes: what views show a few times a second."
  {:beat-index [:timing :beat-index]
   :frame-stats [:backend :streaming :frame-stats]
   :mtc [:backend :input :mtc]})

(defn app-status
  "The show clock's status as app state holds it."
  [state]
  (update-vals status-paths #(get-in state %)))

(def report-interval-ms
  "The same report, or status, goes back to app state at most this often
   while app state still differs."
  250.0)

(defn- effective-beats ^double [clock]
  (double (or (:effective-beats clock)
              (+ (double (or (:accumulated-beats clock) 0.0))
                 (double (or (:phase-offset clock) 0.0))))))


;; Requests: restart, align and shift, each applied once per id

(def ^:private request-kinds [:restart :align :shift])

(defn request-ids
  "{kind id} of the requests in state that have an id."
  [state]
  (into {} (keep (fn [kind]
                   (when-some [id (get-in state [:playback :requests kind :id])]
                     [kind id])))
        request-kinds))

(defn- shift-target [offset timing]
  (assoc timing :phase-offset-target (+ (double (or (:phase-offset timing) 0.0)) offset)))

(defn- apply-request
  "Transport Stop and Retrigger restart every clock; a tap aligns the beat
   phase to the nearest whole beat; Link shifts the phase targets. Timelines
   keep their position through align and shift."
  [runtime kind request]
  (case kind
    :restart (-> runtime
                 (update :global cue-timing/reset-global-clock)
                 (update :runs update-vals cue-timing/reset-cue-timing))
    :align (-> runtime
               (update :global cue-timing/align-global-clock-phase)
               (update :runs update-vals cue-timing/align-cue-timing-phase))
    :shift (let [offset (double (or (:offset request) 0.0))]
             (-> runtime
                 (update :global #(shift-target offset %))
                 (update :runs update-vals #(if (:timeline? %) % (shift-target offset %)))))))

(defn- apply-requests [runtime requests]
  (reduce (fn [rt kind]
            (let [request (get requests kind)
                  id (:id request)]
              (if (and (some? id) (not= id (get-in rt [:applied kind])))
                (-> rt
                    (apply-request kind request)
                    (assoc-in [:applied kind] id))
                rt)))
          runtime
          request-kinds))


;; Runs: one per intent, keyed by run id

(defn- chased?
  "Is the cell's cue driven by timecode rather than its own run?"
  [intent chain]
  (or (:chasing? intent) (playback/chases-mtc? chain)))

(defn- run-for
  "The run the intent names (started now if the runtime doesn't have it yet),
   at the intent's cell, with its pause and seek applied."
  [runs global cell intent now-ms]
  (let [run (or (get runs (:run intent))
                (cue-timing/start-timing now-ms (effective-beats global)
                                         {:timeline? (:timeline? intent)
                                          :start-beat (:start-beat intent)}))
        run (cond-> (assoc run
                           :cell cell
                           :paused? (boolean (:paused? intent)))
              (and (:timeline? intent) (not (:timeline? run)))
              (assoc :timeline? true :phase-offset 0.0 :phase-offset-target 0.0))
        {:keys [id beat]} (:seek intent)]
    (if (and (some? id) (not= id (:seek-id run)))
      (-> run
          (cue-timing/seek (or beat 0.0))
          (assoc :seek-id id))
      run)))

(defn- reconcile-runs
  "Runs for exactly the intents in app state: new ones start, moved ones keep
   their position, the rest go. Cues driven by timecode are the chase's."
  [runtime state now-ms]
  (let [chains (get-in state [:chains :cue-chains])
        {:keys [runs global]} runtime]
    (assoc runtime :runs
           (reduce-kv (fn [acc cell intent]
                        (if (or (nil? (:run intent)) (chased? intent (get chains cell)))
                          acc
                          (assoc acc (:run intent) (run-for runs global cell intent now-ms))))
                      {}
                      (or (get-in state [:playback :active-cues]) {})))))

(def ^:private no-loop {:enabled? false :start 0.0 :duration 4.0})

(defn- advance
  "Every clock to now-ms: the global clock at the global BPM, each run at its
   cue's tempo, loop and timeline length."
  [runtime state bpm now-ms]
  (let [resync-rate (ex/resync-rate state)
        chains (get-in state [:chains :cue-chains])]
    (-> runtime
        (update :global cue-timing/update-global-clock now-ms bpm resync-rate)
        (update :runs update-vals
                (fn [run]
                  (let [chain (get chains (:cell run))]
                    (cue-timing/update-cue-timing run now-ms (playback/cue-bpm chain bpm) resync-rate
                                                  (get chain :loop no-loop)
                                                  (when (and (:timeline? run) chain)
                                                    (playback/timeline-cfg chain)))))))))

(defn- chase [runtime state mtc-pos bpm now-ms]
  (assoc runtime :chase
         (:active-cues (playback/apply-chase {:active-cues (:chase runtime)}
                                             (get-in state [:chains :cue-chains])
                                             mtc-pos bpm now-ms))))


;; Reports back to app state

(defn- pending-reports
  "What app state needs to hear now: timeline runs that ended while their
   intent is still there, and chasing cells where app state and the chase
   disagree."
  [runtime state]
  (let [intents (or (get-in state [:playback :active-cues]) {})]
    (concat
     (for [[run-id run] (:runs runtime)
           :when (and (:ended? run) (= run-id (get-in intents [(:cell run) :run])))]
       {:event/type :show-clock/cue-ended :cell (:cell run) :run run-id})
     (for [[cell chain] (get-in state [:chains :cue-chains])
           :when (playback/chases-mtc? chain)
           :let [active? (contains? (:chase runtime) cell)
                 intent (get intents cell)]
           :when (if active? (not (:chasing? intent)) (some? intent))]
       {:event/type :show-clock/chase :cell cell :active? active?}))))

(defn- due-reports
  "[sent reports]: each pending report goes out unless the same report went
   out less than report-interval-ms ago. Reports that no longer hold are
   forgotten, so they go out at once if they come back."
  [runtime pending now-ms]
  (reduce (fn [[sent reports] report]
            (let [last-ms (get (:sent runtime) report)]
              (if (or (nil? last-ms) (>= (- (double now-ms) (double last-ms)) report-interval-ms))
                [(assoc sent report now-ms) (conj reports report)]
                [(assoc sent report last-ms) reports])))
          [{} []]
          pending))


;; The step

(defn step
  "Show time one tick on, at now-ms (fractional milliseconds on the clock's
   monotonic time base), from app state's intents and requests and the MTC
   position (laser-show.input.mtc/position, or nil). Pure: returns
   {:runtime r :reports [event …]}. In order:
   1. requests not applied yet (restart, align, shift);
   2. runs: started, moved, paused, seeked or dropped to match the intents;
   3. every clock advanced to now-ms;
   4. chasing timelines set from the timecode;
   5. reports for app state, each at most every report-interval-ms."
  [runtime state mtc-pos now-ms]
  (let [bpm (double (or (ex/bpm state) 120.0))
        rt (-> runtime
               (apply-requests (get-in state [:playback :requests]))
               (reconcile-runs state now-ms)
               (advance state bpm now-ms)
               (chase state mtc-pos bpm now-ms)
               (update :tick inc))
        [sent reports] (due-reports rt (pending-reports rt state) now-ms)]
    {:runtime (assoc rt :sent sent)
     :reports reports}))


;; Readers

(defn positions
  "{cell beats} of every run and chased cue in runtime."
  [runtime]
  (into {}
        (concat (map (fn [run] [(:cell run) (:accumulated-beats run)]) (vals (:runs runtime)))
                (map (fn [[cell timing]] [cell (:accumulated-beats timing)]) (:chase runtime)))))

(defn active-runs
  "The cues to render: [{:cell :run :cue-chain :cue-timing} …], in the order
   of app state's intents. Runs the clock hasn't started yet, ended runs and
   chains without items are left out. A chased cue takes its intent's place,
   or goes last (by cell) while app state has no chasing intent for it yet."
  ([state] (active-runs state @!runtime))
  ([state {:keys [runs chase]}]
   (let [chains (get-in state [:chains :cue-chains])
         intents (or (get-in state [:playback :active-cues]) {})
         entry (fn [cell run-id timing]
                 (let [chain (get chains cell)]
                   (when (and timing (not (:ended? timing)) (seq (:items chain)))
                     {:cell cell :run run-id :cue-chain chain :cue-timing timing})))
         from-intents (u/keepv (fn [[cell intent]]
                                 (cond
                                   (:chasing? intent) (entry cell nil (get chase cell))
                                   (playback/chases-mtc? (get chains cell)) nil
                                   :else (entry cell (:run intent) (get runs (:run intent)))))
                               intents)
         chased-later (keep (fn [cell]
                              (when-not (:chasing? (get intents cell))
                                (entry cell nil (get chase cell))))
                            (sort (keys chase)))]
     (into from-intents chased-later))))

(defn global-clock
  "The global clock (beat indicator, global effects)."
  []
  (:global @!runtime))

(defn global-beats
  "The global clock's effective beats, in runtime (default: !runtime)."
  (^double [] (global-beats @!runtime))
  (^double [runtime] (effective-beats (:global runtime))))

(defn cue-beats
  "Where snapshot (latest) has the cue at cell, in beats, or nil when it isn't
   playing."
  [snapshot cell]
  (get (:positions snapshot) cell))


;; The newest tick, and the signal the engines wait on

(def ^:const STALE_MS
  "After this long without a tick, engines send empty frames and report
   \"Show clock stalled\" (decision S3)."
  250)

(defonce ^:private monitor (Object.))

(defonce ^{:private true
           :doc "The newest tick's snapshot, nil before the first: {:tick :at-ns :playing? :cues
                 [{:cell :run :zone-frames}] :positions {cell beats} :projector-ctx
                 :preview (delay …) :render-us}."}
  !snapshot
  (atom nil))

(defonce ^:private ^AtomicLong tick-counter (AtomicLong.))

(defonce ^:private !clock-running? (atom false))

(defn publish!
  "Make snapshot the newest tick, numbered with the next :tick (never reset,
   so a tick an engine has seen stays older than every later one), and wake
   every engine waiting in frame-signal. Returns the published snapshot."
  [snapshot]
  (locking monitor
    (let [published (assoc snapshot :tick (.incrementAndGet tick-counter))]
      (reset! !snapshot published)
      (.notifyAll ^Object monitor)
      published)))

(defn latest
  "The newest tick's snapshot, or nil before the first tick."
  []
  @!snapshot)

(defn reset-runtime!
  "Forget show time and the newest tick (tests, app/start-test!). Given app
   state (show-clock/start!), the requests already in it count as applied, so
   restarting the clock doesn't apply one again."
  ([] (reset-runtime! nil))
  ([state]
   (reset! !runtime (assoc initial-runtime :applied (request-ids state)))
   (reset! !snapshot nil)
   nil))

(defn stale?
  "Was snapshot published more than STALE_MS before now-ns?"
  [snapshot now-ns]
  (> (- (long now-ns) (long (:at-ns snapshot))) (* STALE_MS 1000000)))

(defn set-clock-running!
  "Whether a clock thread is running (show-clock/start! and stop!). While it
   isn't, engines pace themselves."
  [running?]
  (locking monitor
    (reset! !clock-running? (boolean running?))
    (.notifyAll ^Object monitor)))

(defn- newest-tick ^long []
  (long (or (:tick @!snapshot) 0)))

(def ^:private ^:const max-wait-ns
  "Longest single wait, so a waiting engine notices it is stopping."
  5000000)

(defn- await-tick
  "Wait until a tick newer than seen exists, deadline-ns passes, the clock
   stops or (keep-waiting?) is false. The check and the wait are under the
   monitor publish! notifies, so a tick can't slip in between."
  [seen deadline-ns keep-waiting?]
  (let [seen (long seen)
        deadline-ns (long deadline-ns)
        running? @!clock-running?]
    (locking monitor
      (loop []
        (let [remaining (- deadline-ns (System/nanoTime))]
          (when (and (<= (newest-tick) seen)
                     (pos? remaining)
                     (= running? @!clock-running?)
                     (keep-waiting?))
            (let [wait-ns (min remaining max-wait-ns)]
              (.wait ^Object monitor (quot wait-ns 1000000) (int (rem wait-ns 1000000))))
            (recur)))))))

(def frame-signal
  "What an engine paces itself by while the clock runs
   (streaming-engine/create-engine :frame-signal):
   - :tick, the newest tick's number (0 before the first);
   - :running?, whether a clock thread is running;
   - :await [seen deadline-ns keep-waiting?], see await-tick."
  {:tick (fn [] (newest-tick))
   :running? (fn [] @!clock-running?)
   :await (fn [seen deadline-ns keep-waiting?] (await-tick seen deadline-ns keep-waiting?))})
