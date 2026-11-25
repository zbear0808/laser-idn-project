(ns laser-show.animation.show-time
  "Show time: where the global clock and every playing cue are, and the
   frames of the newest tick.

   The show clock thread (laser-show.services.show-clock) is the only writer.
   60 times a second it runs step on !runtime, renders every playing cue once
   and publishes the result (publish!). Each projector's engine waits on
   frame-signal and sends that tick from its own thread. Everything that reads
   show time reads it here, so it needn't require the clock thread, which
   itself needs frame-service and events.core.

   App state keeps what events decide (state/domains :playback), each by
   playback key: a grid cell [col row] or a show [:show id]
   (laser-show.timeline.shows, whose cue-at finds the cue a key plays):
   - [:playback :active-cues key]: an intent, with no position:
     {:run id :timeline? :start-beat :paused? :seek {:id :beat} :decay-beats :hold?},
     or {:chasing? true :timeline? true :chase-id k} for a timeline following
     MIDI timecode, k the episode it was installed for;
   - [:playback :chase-sources]: the version of each cue following timecode,
     which an episode belongs to (timeline.playback/source-version);
   - [:playback :chase-stopped]: {key chase-id}, a follower a press
     stopped for that episode, which the clock goes on chasing unseen
     (chase's :stopped?) until a new one begins there;
   - [:playback :requests]: {:restart {:id} :align {:id} :shift {:id :offset}},
     each applied once per id;
   - [:playback :pulses]: {source {:id :held?}}, the presses tap reactions
     follow (laser-show.animation.pulses), stamped on the pulse clock, which
     no request moves.
   step reads them once per tick. What the clock learns goes back to app state
   as events: a run's end (a timeline's, or a vibe cue's decay, with the
   restart and seek ids of the position that ended) and timecode taking a
   key over or letting go of it, naming the show, the cue's version and the
   owner it found there (:show-clock/cue-ended, :show-clock/chase),
   auto-advance's arm and advance and a stuck key's release
   (laser-show.vibes.rotation and .watchdog, with their own memory,
   :vibe-sent),
   and what views show a few times a second, the beat square, the stats, the
   MTC text and the cue warnings (:show-clock/status, on status-paths). Positions never reach
   app state: views that move every frame draw the newest snapshot
   (views/components/clock_view.clj)."
  (:require [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.animation.pulses :as pulses]
            [laser-show.common.util :as u]
            [laser-show.routing.zone-maps :as zone-maps]
            [laser-show.state.extractors :as ex]
            [laser-show.timeline.playback :as playback]
            [laser-show.timeline.shows :as shows])
  (:import [java.util.concurrent.atomic AtomicLong]))


;; Runtime

(def initial-runtime
  "The runtime after a reset: no runs, the global clock and the pulse clock
   at 0, no press stamped, no report sent, waiting for the first tick (which
   sets :t0-ns and the clocks' :last-frame-time)."
  {:tick 0
   :t0-ns nil
   :global {:accumulated-beats 0.0 :accumulated-ms 0.0 :phase-offset 0.0
            :phase-offset-target 0.0 :effective-beats 0.0}
   :pulse-clock {:accumulated-beats 0.0 :accumulated-ms 0.0 :phase-offset 0.0
                 :phase-offset-target 0.0 :effective-beats 0.0}
   :pulses {}
   :runs {}
   :chase {}
   :applied {}
   :sent {}
   :vibe-sent {}})

(defonce ^{:doc "Show time ({:tick :t0-ns :global :pulse-clock :pulses {source stamp}
                 :runs {run-id timing} :chase {key timing, with its :chase-id and
                 :start} :applied {kind request-id}
                 :sent {report now-ms} :vibe-sent {report now-ms} :status {…}
                 :routing-cache {:inputs :routing}}, the last routing and what it
                 was worked out from, routing.rig/cached-routing). Only the show
                 clock thread writes it (show-clock/tick! in tests)."}
  !runtime
  (atom initial-runtime))

(def status-paths
  "Where app state holds the show clock's status, which :show-clock/status
   writes: what views show a few times a second. :cue-warnings are the
   playing cues too heavy for a laser (routing.compose/cue-warnings)."
  {:beat-index [:timing :beat-index]
   :frame-stats [:backend :streaming :frame-stats]
   :mtc [:backend :input :mtc]
   :cue-warnings [:backend :streaming :cue-warnings]})

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
   keep their position through align and shift. None of them touches the
   pulse clock, so a press keeps its age."
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
  "Is the key's cue driven by timecode rather than its own run?"
  [intent cue]
  (or (:chasing? intent) (playback/chases-mtc? cue)))

(defn- run-for
  "The run the intent names (started now if the runtime doesn't have it yet),
   at the intent's key, with its pause, decay and seek applied."
  [runs global k intent now-ms]
  (let [run (or (get runs (:run intent))
                (cue-timing/start-timing now-ms (effective-beats global)
                                         {:timeline? (:timeline? intent)
                                          :start-beat (:start-beat intent)}))
        run (cond-> (assoc run
                           :cell k
                           :paused? (boolean (:paused? intent)))
              (and (:timeline? intent) (not (:timeline? run)))
              (assoc :timeline? true :phase-offset 0.0 :phase-offset-target 0.0)
              (:decay-beats intent)
              (assoc :decay-beats (double (:decay-beats intent)) :hold? (boolean (:hold? intent))))
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
  (let [{:keys [runs global]} runtime]
    (assoc runtime :runs
           (reduce-kv (fn [acc k intent]
                        (if (or (nil? (:run intent)) (chased? intent (shows/cue-at state k)))
                          acc
                          (assoc acc (:run intent) (run-for runs global k intent now-ms))))
                      {}
                      (or (get-in state [:playback :active-cues]) {})))))

(def ^:private no-loop {:enabled? false :start 0.0 :duration 4.0})

(defn- advance
  "Every clock to now-ms: the global clock and the pulse clock at the global
   BPM, each run at its cue's tempo, loop and timeline length."
  [runtime state bpm now-ms]
  (let [resync-rate (ex/resync-rate state)]
    (-> runtime
        (update :global cue-timing/update-global-clock now-ms bpm resync-rate)
        (update :pulse-clock cue-timing/update-global-clock now-ms bpm resync-rate)
        (update :runs update-vals
                (fn [run]
                  (let [chain (shows/cue-at state (:cell run))]
                    (cue-timing/update-cue-timing run now-ms (playback/cue-bpm chain bpm) resync-rate
                                                  (get chain :loop no-loop)
                                                  (when (and (:timeline? run) chain)
                                                    (playback/timeline-cfg chain)))))))))

(defn- document-id
  "The show open in state ([:project :document-id], state.domains)."
  [state]
  (get-in state [:project :document-id]))

(defn- free-to-start?
  "May timecode start the transport of state: it plays, or nothing is held
   in it (Stop holds the intents it stopped)."
  [state]
  (or (true? (get-in state [:playback :playing?]))
      (empty? (get-in state [:playback :active-cues]))))

(defn- current-episode?
  "Is the chase of timing at key k still the cue k plays in state: the same
   show, and the cue's current version (playback/source-version, nil once
   it doesn't follow)?"
  [state k timing]
  (let [[doc at version] (:chase-id timing)]
    (and (some? version)
         (= at k)
         (= doc (document-id state))
         (= version (playback/source-version state k)))))

(defn- chase
  "The chased cues one tick on (playback/apply-chase): from the episodes
   still of their cue (current-episode?: none of another show's, of a cue
   replaced or no longer following), every following timeline inside its
   window at the timecode's beat: a show's or, until the grid holds no
   timelines, a grid cell's (shows/playable-shows). An episode keeps its
   timing and :chase-id while timecode runs, freewheels or locates inside
   the window; a cue entering it, again or for the first time, starts a new
   one at the timecode's beat, with :chase-id [document-id key version
   origin-ns tick] (the clock's :t0-ns and this tick's number) and :start,
   what may start the transport for it, as it was then: {:restart (the
   restart request's id) :allowed? (free-to-start?)}. One a press stopped
   ([:playback :chase-stopped], follow-up B7) goes on as the same episode,
   :stopped? true: it is neither drawn, positioned nor reported."
  [runtime state mtc-pos bpm now-ms]
  (let [seed (into {} (filter (fn [[k timing]] (current-episode? state k timing))) (:chase runtime))
        stopped (get-in state [:playback :chase-stopped])
        chased (:active-cues (playback/apply-chase {:active-cues seed}
                                                   (shows/playable-shows state)
                                                   mtc-pos bpm now-ms))
        entry (fn [k]
                {:chase-id [(document-id state) k (playback/source-version state k)
                            (:t0-ns runtime) (inc (long (:tick runtime 0)))]
                 :start {:restart (get-in state [:playback :requests :restart :id])
                         :allowed? (free-to-start? state)}})]
    (assoc runtime :chase
           (reduce-kv (fn [acc k timing]
                        (let [timing (if (:chase-id timing) timing (merge timing (entry k)))
                              stopped? (= (:chase-id timing) (get stopped k))]
                          (assoc acc k (if stopped? (assoc timing :stopped? true) (dissoc timing :stopped?)))))
                      chased
                      chased))))


;; Reports back to app state

(defn- chase-reports
  "One decision for each key timecode or app state says timecode is at:
   every chased key, every chase marker (one whose cue went too) and every
   run at a cue that follows timecode, in shows/key-order. On (:active?
   true) when an episode's marker isn't in app state yet: the marker to
   install, :chase-id, in place of the owner there, :expected-owner
   (playback/intent-owner); with the episode's :start authority. Off
   (:active? false) when no episode is there and app state still has a
   marker, or a run the following cue takes over. None for an ordinary
   cue's run or a key with no cue, nor for an episode a press stopped
   (:stopped?). Each names the
   show (:document-id) and the cue's version (:source-version, nil when it
   doesn't follow) it was decided for, never a beat or a time, so a retry
   is the same report and a changed owner a new one."
  [runtime state]
  (let [intents (or (get-in state [:playback :active-cues]) {})
        chase (:chase runtime)
        ks (sort shows/key-order
                 (distinct (concat (keys chase)
                                   (keep (fn [[k intent]]
                                           (when (or (:chasing? intent) (playback/chases-mtc? (shows/cue-at state k)))
                                             k))
                                         intents))))]
    (keep (fn [k]
            (let [{:keys [chase-id start stopped?]} (get chase k)
                  intent (get intents k)
                  owner (playback/intent-owner intent)
                  version (playback/source-version state k)
                  report {:event/type :show-clock/chase :cell k :document-id (document-id state)
                          :source-version version :expected-owner owner}]
              (cond
                chase-id (when (and (not stopped?) (not= owner [:chase chase-id]))
                           (assoc report :active? true :chase-id chase-id :start start))
                (:chasing? intent) (assoc report :active? false)
                (and (some? version) (some? (:run intent))) (assoc report :active? false))))
          ks)))

(defn- pending-reports
  "What app state needs to hear now: runs that ended (a timeline at its end,
   a decay after its beats) while their intent is still there, each with the
   ids of the restart and seek that put it where it ended
   (cue-timing/position-ids of state, the snapshot this step applied),
   where timecode takes a key over or lets go of it (chase-reports), and a
   zone map switch whose beat the global clock has reached (zones plan Z4:
   the tick routes by it already, and the report lands it in app state),
   and a pattern pads' switch likewise (zones plan Z5)."
  [runtime state]
  (let [intents (or (get-in state [:playback :active-cues]) {})
        pending (get-in state [:zone-map :pending])
        style-pending (get-in state [:pattern-pads :pending])
        beats (effective-beats (:global runtime))]
    (concat
     (when (and pending (number? (:at-beat pending)) (zone-maps/due? (:at-beat pending) beats))
       [{:event/type :show-clock/zone-map-due :id (:id pending) :map (:map pending)}])
     (when (and style-pending (number? (:at-beat style-pending)) (zone-maps/due? (:at-beat style-pending) beats))
       [{:event/type :show-clock/pattern-due :id (:id style-pending) :style (:style style-pending)}])
     (for [[run-id run] (:runs runtime)
           :when (and (:ended? run) (= run-id (get-in intents [(:cell run) :run])))]
       {:event/type :show-clock/cue-ended :cell (:cell run) :run run-id
        :position-ids (cue-timing/position-ids (:playback state) (:cell run))})
     (chase-reports runtime state))))

(defn due-reports
  "[sent' reports]: each of pending goes out unless the same report went out
   less than report-interval-ms ago, by sent ({report now-ms}, the memory of
   the reports of one kind: the step's :sent, the show clock's :vibe-sent).
   Reports that no longer hold are forgotten, so they go out at once if they
   come back."
  [sent pending now-ms]
  (reduce (fn [[sent' reports] report]
            (let [last-ms (get sent report)]
              (if (or (nil? last-ms) (>= (- (double now-ms) (double last-ms)) report-interval-ms))
                [(assoc sent' report now-ms) (conj reports report)]
                [(assoc sent' report last-ms) reports])))
          [{} []]
          pending))


;; The step

(defn- stamp-pulses
  "The presses in app state stamped on the pulse clock, as it is now."
  [runtime state]
  (update runtime :pulses pulses/stamp (get-in state [:playback :pulses]) (:pulse-clock runtime)))

(defn step
  "Show time one tick on, at now-ms (fractional milliseconds on the clock's
   monotonic time base), from app state's intents, requests and presses and
   the MTC position (laser-show.input.mtc/position, or nil). Pure: returns
   {:runtime r :reports [event …]}. In order:
   1. requests not applied yet (restart, align, shift);
   2. runs: started, moved, paused, seeked or dropped to match the intents;
   3. every clock advanced to now-ms;
   4. the presses stamped on the pulse clock (pulses/stamp), so a press and
      the cue it starts reach the render at the same tick;
   5. the timelines following timecode set from it (chase): an episode only
      while its show and its cue's version are app state's, so a cue
      removed, replaced or no longer following is gone from this tick on;
   6. reports for app state, each at most every report-interval-ms.
   runtime's :t0-ns, the clock's monotonic origin, and :tick name a new
   episode: step reads no clock and makes no random id."
  [runtime state mtc-pos now-ms]
  (let [bpm (double (or (ex/bpm state) 120.0))
        rt (-> runtime
               (apply-requests (get-in state [:playback :requests]))
               (reconcile-runs state now-ms)
               (advance state bpm now-ms)
               (stamp-pulses state)
               (chase state mtc-pos bpm now-ms)
               (update :tick inc))
        [sent reports] (due-reports (:sent rt) (pending-reports rt state) now-ms)]
    {:runtime (assoc rt :sent sent)
     :reports reports}))


;; Readers

(defn pulse-view
  "What tap reactions see of the presses in runtime (pulses/view): their
   ages on the pulse clock, by source."
  [runtime]
  (pulses/view (:pulses runtime) (:pulse-clock runtime)))

(defn positions
  "{key beats} of every run and chased cue in runtime, but an episode a
   press stopped (:stopped?, chase)."
  [runtime]
  (into {}
        (concat (map (fn [run] [(:cell run) (:accumulated-beats run)]) (vals (:runs runtime)))
                (keep (fn [[k timing]] (when-not (:stopped? timing) [k (:accumulated-beats timing)])) (:chase runtime)))))

(defn active-runs
  "The cues to render: [{:cell :run :cue-chain :cue-timing} …], :cell the
   playback key and :cue-chain the cue it plays (shows/cue-at), in the order
   of app state's intents. Runs the clock hasn't started yet, ended runs and
   chains without items are left out. A chased cue takes its intent's place,
   or goes last (in shows/key-order) while app state has no chasing intent
   for it yet, unless a press stopped its episode (:stopped?, chase)."
  ([state] (active-runs state @!runtime))
  ([state {:keys [runs chase]}]
   (let [intents (or (get-in state [:playback :active-cues]) {})
         entry (fn [k run-id timing]
                 (let [chain (shows/cue-at state k)]
                   (when (and timing (not (:ended? timing)) (seq (:items chain)))
                     {:cell k :run run-id :cue-chain chain :cue-timing timing})))
         from-intents (u/keepv (fn [[k intent]]
                                 (cond
                                   (:chasing? intent) (entry k nil (get chase k))
                                   (playback/chases-mtc? (shows/cue-at state k)) nil
                                   :else (entry k (:run intent) (get runs (:run intent)))))
                               intents)
         chased-later (keep (fn [k]
                              (when-not (or (:chasing? (get intents k)) (:stopped? (get chase k)))
                                (entry k nil (get chase k))))
                            (sort shows/key-order (keys chase)))]
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
  "Where snapshot (latest) has the cue at key k, in beats, or nil when it isn't
   playing."
  [snapshot k]
  (get (:positions snapshot) k))

(defn chase-id
  "The :chase-id of the timecode episode runtime (default: !runtime, one
   read) chases at key k, or nil when it chases nothing there."
  ([k] (chase-id @!runtime k))
  ([runtime k] (get-in runtime [:chase k :chase-id])))


;; The newest tick, and the signal the engines wait on

(def ^:const STALE_MS
  "After this long without a tick, engines send empty frames and report
   \"Show clock stalled\" (decision S3)."
  250)

(defonce ^:private monitor (Object.))

(defonce ^{:private true
           :doc "The newest tick's snapshot, nil before the first: {:tick :at-ns :playing? :cues
                 [{:cell :run :zone-frames}] :positions {key beats} :pulses (pulse-view)
                 :faders {slot value} (vibes.faders/fader-values) :routing (routing.rig/routing:
                 every ref's lasers and each laser's halves, scan rate and fit, which the
                 engines compose from) :engine-inputs (multi-engine/engine-inputs: each
                 laser's calibration and safety zone, the test pattern, Identify, BLACKOUT
                 and the tick's wall clock, all from the tick's read of app state)
                 :projector-ctx :preview (delay …) :render-us}."}
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
   state (show-clock/start!), the requests already in it count as applied
   and its presses as stamped, so restarting the clock replays neither."
  ([] (reset-runtime! nil))
  ([state]
   (reset! !runtime (assoc initial-runtime
                           :applied (request-ids state)
                           :pulses (pulses/seed (get-in state [:playback :pulses]))))
   (reset! !snapshot nil)
   nil))

(defn stale?
  "Was snapshot published more than STALE_MS before now-ns?"
  [snapshot now-ns]
  (> (- (long now-ns) (long (:at-ns snapshot))) (* STALE_MS 1000000)))

(defn global-beats-at
  "Where the global clock of runtime (one read of !runtime) is at now-ns
   (System/nanoTime: the clock's time base, and Link's), at bpm (app
   state's, the tempo it runs at): {:current (its effective beats)
   :settled (where they end once its phase offset has glided onto its
   target)}, its last tick's run on to now-ns. nil before its first tick,
   or when that tick is more than STALE_MS old (the clock isn't running).
   Beat alignment with Link reads it (input.link)."
  [runtime bpm now-ns]
  (let [{:keys [t0-ns global]} runtime
        {:keys [accumulated-beats phase-offset phase-offset-target last-frame-time]} global]
    (when (and t0-ns last-frame-time)
      (let [since-ms (- (/ (- (long now-ns) (long t0-ns)) 1e6) (double last-frame-time))]
        (when (<= since-ms STALE_MS)
          (let [beats (+ (double (or accumulated-beats 0.0)) (* since-ms (/ (double bpm) 60000.0)))]
            {:current (+ beats (double (or phase-offset 0.0)))
             :settled (+ beats (double (or phase-offset-target 0.0)))}))))))

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
