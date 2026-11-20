(ns laser-show.services.show-clock
  "The show clock: one daemon thread, show-clock, ticking 60 times a second.
   It owns show time.

   Each tick (tick!):
   1. steps show time from app state's intents and requests and the MIDI
      timecode (show-time/step);
   2. works out the routing (routing.rig/cached-routing: which lasers each
      group reaches, and each laser's halves, scan rate and fit settings),
      from the tick's state and before the render (spec 01 §5), the cached
      one while nothing it reads changed;
   3. renders every playing cue once (frame-service/generate-frames-by-zone),
      each in its own try, at its timing context and with one render context
      for the whole tick: the tick's one read of app state and of the input
      store (every tick, playing or not), the faders as their bindings now
      give them (vibes.faders), the presses tap reactions follow
      (show-time/pulse-view; calibration gets none) and the global effects
      (frame-service/render-context);
   4. publishes the result (show-time/publish!), which wakes every projector's
      engine: each composes its laser's frame from the tick's cues and
      routing and sends it on its own thread (backend/multi_engine.clj,
      routing/compose.clj). The snapshot also carries where each cue is,
      the presses (the Perform pads' pulse bars), where the faders are (the
      Perform tab's meters), and the preview as a delay
      that the screen forces (views/components/clock_view.clj draws it on
      each screen refresh);
   5. sends what it learned back as events: a timeline's end and a timecode
      chase (:show-clock/cue-ended, :show-clock/chase), auto-advance's arm and
      advance (:vibe/arm, :vibe/advance: laser-show.vibes.rotation/due, on
      the tick's read of app state), a key held too long (:vibe/release
      :reason :watchdog: laser-show.vibes.watchdog/due), and the status views
      show a few times a second, the beat square, the frame stats and the MTC
      text (:show-clock/status), when app state doesn't have it yet. It
      writes app state no other way.

   Only app requires this namespace; tests call tick! by hand."
  (:require [clojure.tools.logging :as log]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.animation.show-time :as show-time]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.common.timing :as timing]
            [laser-show.events.core :as events]
            [laser-show.input.mtc :as mtc]
            [laser-show.input.store :as input-store]
            [laser-show.profiling.frame-profiler :as profiler]
            [laser-show.profiling.jfr-profiler :as jfr]
            [laser-show.routing.rig :as rig]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex]
            [laser-show.timeline.playback :as playback]
            [laser-show.vibes.faders :as faders]
            [laser-show.vibes.rotation :as rotation]
            [laser-show.vibes.watchdog :as watchdog]))

(def ^:const TICK_HZ 60)

(def ^:private stats-interval-ms
  "The frame stats are recomputed at most this often (4 times a second)."
  250.0)


;; Rendering a tick

(defn- render-cue
  "One cue's frames by zone at its run's timing, with the tick's render
   context (its input values and presses); {} if the render throws, reported
   under [:render cell]."
  [{:keys [cell cue-chain cue-timing]} bpm render-ctx]
  (let [cue-bpm (playback/cue-bpm cue-chain bpm)
        ctx (assoc (cue-timing/get-cue-timing-context cue-timing cue-bpm)
                   :input-values (:input-values render-ctx)
                   :pulses (:pulses render-ctx))]
    (try
      (let [zone-frames (frame-service/generate-frames-by-zone cue-chain ctx render-ctx)]
        (output-errors/ok! [:render cell])
        zone-frames)
      (catch Throwable e
        (output-errors/report! [:render cell] e)
        {}))))

(defn- record-render!
  "The profiler sample and the JFR event for one tick's render."
  [cues render-ns global-effects]
  (let [base-time-us (quot (long render-ns) 1000)
        effect-count (count (:effects global-effects))]
    (profiler/record-frame-timing! {:base-time-us base-time-us :effects-time-us 0 :effect-count effect-count})
    (jfr/emit-frame-event! {:base-time-us base-time-us :effects-time-us 0 :effect-count effect-count
                            :point-count (transduce (comp (mapcat (comp vals :zone-frames)) (map count)) + cues)})))

(defn- render
  "The snapshot fields for runs (show-time/active-runs), or none while not
   playing: {:cues [{:cell :run :zone-frames}] :projector-ctx :render-us}.
   Every cue shares one render context (frame-service/render-context): the
   tick's input values with the faders' slots derived from their bindings,
   its presses (pulses, show-time/pulse-view), its global effects and
   global-clock's timing. Calibration gets the same input values and no
   presses."
  [state input-values global-clock runs pulses]
  (if (empty? runs)
    {:cues [] :projector-ctx nil :render-us 0.0}
    (let [bpm (double (or (ex/bpm state) 120.0))
          t0 (System/nanoTime)
          render-ctx (frame-service/render-context state input-values global-clock pulses)
          cues (mapv (fn [run]
                       {:cell (:cell run) :run (:run run) :zone-frames (render-cue run bpm render-ctx)})
                     runs)
          render-ns (- (System/nanoTime) t0)]
      (record-render! cues render-ns (:global-effects render-ctx))
      {:cues cues
       ;; Projector effects run at the first cue's timing and the global BPM
       :projector-ctx (assoc (cue-timing/get-cue-timing-context (:cue-timing (first runs)) bpm)
                             :input-values (:input-values render-ctx))
       :render-us (/ render-ns 1000.0)})))


;; The status: what views show a few times a second

(defn- frame-stats
  "Render and send times for the status bar, from the profiler's last second."
  []
  (when-let [recent (profiler/get-recent-stats TICK_HZ)]
    (cond-> {:avg-latency-us (:avg-total-us recent)
             :p95-latency-us (:p95-total-us recent)
             :max-latency-us (:max-total-us recent)
             :avg-base-us (:avg-base-us recent)
             :avg-effects-us (:avg-effects-us recent)}
      (:avg-idn-us recent) (merge (select-keys recent [:avg-idn-us :p95-idn-us :max-idn-us])))))

(defn- status-step
  "[runtime event]: the runtime's :status one tick on, and the
   :show-clock/status event to send, or nil. The status is the beat in the
   bar, the frame stats (recomputed every stats-interval-ms, kept while the
   profiler has none) and the MTC text (mtc/mirror-status's 100 ms limit), all
   on the step's now-ms. It goes when app state doesn't hold it, and again at
   most every report-interval-ms while app state still differs. The first
   tick after a reset starts from what app state holds."
  [runtime state mtc-pos now-ms]
  (let [now-ms (double now-ms)
        held (show-time/app-status state)
        {:keys [value stats-at-ms sent sent-at-ms]} (or (:status runtime) {:value held})
        stats-due? (or (nil? stats-at-ms) (>= (- now-ms (double stats-at-ms)) stats-interval-ms))
        value' {:beat-index (int (mod (show-time/global-beats runtime) 4.0))
                :frame-stats (or (when stats-due? (frame-stats)) (:frame-stats value))
                :mtc (mtc/mirror-status (:mtc value) mtc-pos now-ms)}
        send? (and (not= value' held)
                   (or (not= value' sent)
                       (>= (- now-ms (double sent-at-ms)) show-time/report-interval-ms)))]
    [(assoc runtime :status {:value value'
                             :stats-at-ms (if stats-due? now-ms stats-at-ms)
                             :sent (if send? value' sent)
                             :sent-at-ms (if send? now-ms sent-at-ms)})
     (when send?
       {:event/type :show-clock/status :status value'})]))


;; A tick

(def ^:private no-routing
  "The routing before the resolver has worked one out: nothing reaches any
   laser."
  {:map nil :refs {} :outputs {}})

(defn- routing-step
  "[cache routing]: the tick's routing from its state (rig/cached-routing,
   the cached one while nothing it reads changed). A resolver that throws is
   reported under [:routing], and the tick keeps the cached routing, or none,
   so a bad edit can't stop the clock."
  [cache state]
  (try
    (let [result (rig/cached-routing cache (rig/routing-inputs state nil))]
      (output-errors/ok! [:routing])
      result)
    (catch Throwable e
      (output-errors/report! [:routing] e)
      [cache (or (:routing cache) no-routing)])))

(defn- vibe-step
  "[runtime reports]: vibe mode's reports, auto-advance's (vibes.rotation/due)
   and the stuck-key guard's (vibes.watchdog/due, with the tick's presses),
   on app state's time base, the wall clock, each at most every
   report-interval-ms by the runtime's :vibe-sent."
  [runtime state pulses now-ms]
  (let [wall-ms (System/currentTimeMillis)
        report (rotation/due state wall-ms (double (or (ex/bpm state) 120.0)))
        stuck (watchdog/due state wall-ms pulses)
        [vibe-sent reports] (show-time/due-reports (:vibe-sent runtime) (cond-> (or stuck []) report (conj report)) now-ms)]
    [(assoc runtime :vibe-sent vibe-sent) reports]))

(defn tick!
  "One tick at now-ns (System/nanoTime): step show time, work out the
   routing (spec 01 §5: before the render, from the tick's state, and the
   cached one while nothing it reads changes), render every playing cue,
   publish, then send the step's reports, vibe mode's (auto-advance and
   stuck keys) and the status as events (events/dispatch!). now-ms, the
   step's time base, is fractional milliseconds since the first tick after a
   reset. Returns the snapshot."
  [now-ns]
  (let [state (state/get-raw-state)
        input-values (input-store/get-all-continuous-values)
        mtc-pos (mtc/current)
        before @show-time/!runtime
        t0 (or (:t0-ns before) now-ns)
        now-ms (/ (- (long now-ns) (long t0)) 1e6)
        {:keys [runtime reports]} (show-time/step before state mtc-pos now-ms)
        pulses (show-time/pulse-view runtime)
        [routing-cache routing] (routing-step (:routing-cache runtime) state)
        runtime (assoc runtime :t0-ns t0 :routing-cache routing-cache)
        _ (reset! show-time/!runtime runtime)
        playing? (boolean (get-in state [:playback :playing?]))
        {:keys [cues] :as rendered} (render state input-values (:global runtime)
                                            (when playing? (show-time/active-runs state runtime)) pulses)
        snapshot (show-time/publish! (assoc rendered
                                            :at-ns (System/nanoTime)
                                            :playing? playing?
                                            :positions (show-time/positions runtime)
                                            :pulses pulses
                                            :faders (faders/fader-values input-values (get-in state [:config :input :macro-map]))
                                            :routing routing
                                            :preview (delay (frame-service/preview-data cues))))
        ;; After publish!, so the engines never wait for them
        [runtime status] (status-step runtime state mtc-pos now-ms)
        [runtime vibe-reports] (vibe-step runtime state pulses now-ms)]
    (reset! show-time/!runtime runtime)
    (run! events/dispatch! (cond-> (into reports vibe-reports) status (conj status)))
    snapshot))


;; The thread

(defonce ^:private lock (Object.))

(defonce ^:private !loop
  ;; {:thread t :running? (atom bool)} while the clock runs
  (atom nil))

(defn- run-clock
  "The clock thread: tick!, then park until the next deadline, skipping the
   ticks a late one missed (timing/next-deadline). tick! is called through
   its var, so a code reload takes effect at the next tick."
  [running?]
  (let [interval (timing/fps->interval-nanos TICK_HZ)
        keep-running? #(deref running?)]
    (loop [deadline (System/nanoTime)]
      (timing/park-until deadline keep-running?)
      (when @running?
        (try
          (tick! (System/nanoTime))
          (output-errors/ok! [:show-clock])
          (catch Throwable t
            (output-errors/report! [:show-clock] t)))
        (recur (timing/next-deadline deadline (System/nanoTime) interval))))))

(defn running?
  "Is the clock thread running?"
  []
  (some? @!loop))

(defn start!
  "Start the clock thread, with show time reset (a stopped app keeps no
   positions). Does nothing if it is running."
  []
  (locking lock
    (when-not @!loop
      (show-time/reset-runtime! (state/get-raw-state))
      (let [running? (atom true)
            thread (doto (Thread. ^Runnable #(run-clock running?) "show-clock")
                     (.setDaemon true))]
        (reset! !loop {:thread thread :running? running?})
        (show-time/set-clock-running! true)
        (.start thread)
        (log/info "Show clock started at" TICK_HZ "ticks a second")))))

(defn stop!
  "Stop the clock thread and wait (up to 1 s) for its last tick. Does nothing
   if it isn't running."
  []
  (locking lock
    (when-let [{:keys [^Thread thread running?]} @!loop]
      (reset! running? false)
      (show-time/set-clock-running! false)
      (.join thread 1000)
      (reset! !loop nil)
      (log/info "Show clock stopped"))))
