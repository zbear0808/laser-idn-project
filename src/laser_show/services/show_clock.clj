(ns laser-show.services.show-clock
  "The show clock: one daemon thread, show-clock, ticking 60 times a second.
   It owns show time.

   Each tick (tick!):
   1. steps show time from app state's intents and requests and the MIDI
      timecode (show-time/step);
   2. renders every playing cue once (frame-service/generate-frames-by-zone),
      each in its own try;
   3. publishes the result (show-time/publish!), which wakes every projector's
      engine: each takes its zones and sends them on its own thread
      (backend/multi_engine.clj). The preview is built from the same tick,
      after the engines are awake;
   4. every MIRROR_EVERY ticks, mirrors positions, the beat clock, the MTC
      status, the preview frame and the frame stats into app state, in one
      swap on show-time/mirror-paths: the one write to app state outside the
      event flow;
   5. sends what it learned back as events (:show-clock/cue-ended,
      :show-clock/chase).

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
            [laser-show.services.frame-service :as frame-service]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex]
            [laser-show.timeline.playback :as playback]))

(def ^:const TICK_HZ 60)

(def ^:const MIRROR_EVERY
  "The mirror writes app state every this many ticks (30 times a second)."
  2)


;; Rendering a tick

(defn- render-cue
  "One cue's frames by zone at its run's timing; {} if the render throws,
   reported under [:render cell]."
  [{:keys [cell cue-chain cue-timing]} bpm input-values]
  (let [cue-bpm (playback/cue-bpm cue-chain bpm)
        ctx (assoc (cue-timing/get-cue-timing-context cue-timing cue-bpm) :input-values input-values)]
    (try
      (let [zone-frames (frame-service/generate-frames-by-zone
                         cue-chain (long (:accumulated-ms cue-timing 0.0)) cue-bpm 0 ctx)]
        (output-errors/ok! [:render cell])
        zone-frames)
      (catch Throwable e
        (output-errors/report! [:render cell] e)
        {}))))

(defn- record-render!
  "The profiler sample and the JFR event for one tick's render."
  [cues render-ns]
  (let [base-time-us (quot (long render-ns) 1000)
        effect-count (count (:effects (frame-service/get-active-global-effects)))]
    (profiler/record-frame-timing! {:base-time-us base-time-us :effects-time-us 0 :effect-count effect-count})
    (jfr/emit-frame-event! {:base-time-us base-time-us :effects-time-us 0 :effect-count effect-count
                            :point-count (transduce (comp (mapcat (comp vals :zone-frames)) (map count)) + cues)})))

(defn- render
  "The snapshot fields for runs (show-time/active-runs), or none while not
   playing: {:cues [{:cell :run :zone-frames}] :projector-ctx :render-us}."
  [state runs]
  (if (empty? runs)
    {:cues [] :projector-ctx nil :render-us 0.0}
    (let [bpm (double (or (ex/bpm state) 120.0))
          input-values (input-store/get-all-continuous-values)
          t0 (System/nanoTime)
          cues (mapv (fn [run]
                       {:cell (:cell run) :run (:run run) :zone-frames (render-cue run bpm input-values)})
                     runs)
          render-ns (- (System/nanoTime) t0)]
      (record-render! cues render-ns)
      {:cues cues
       ;; Projector effects run at the first cue's timing and the global BPM
       :projector-ctx (assoc (cue-timing/get-cue-timing-context (:cue-timing (first runs)) bpm)
                             :input-values input-values)
       :render-us (/ render-ns 1000.0)})))


;; The mirror

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

(defn- mirror!
  "Copy what views read into app state, in one swap on show-time/mirror-paths.
   The MTC status keeps its 100 ms limit on monotonic ms, so a clock restart
   can't hold it back."
  [runtime snapshot mtc-pos now-ns]
  (let [preview (frame-service/preview->mirror @(:preview snapshot))
        stats (frame-stats)
        positions (show-time/positions runtime)]
    (state/swap-state!
     (fn [s]
       (cond-> (-> s
                   (assoc-in [:timing :global-clock] (:global runtime))
                   (assoc-in [:playback :positions] positions)
                   (update-in [:backend :input :mtc] mtc/mirror-status mtc-pos (quot (long now-ns) 1000000))
                   (assoc-in [:backend :streaming :current-frame] preview))
         stats (assoc-in [:backend :streaming :frame-stats] stats))))))


;; A tick

(defn tick!
  "One tick at now-ns (System/nanoTime): step show time, render every playing
   cue, publish, mirror every MIRROR_EVERY ticks, then send the step's reports
   as events (events/dispatch!). now-ms, the step's time base, is fractional
   milliseconds since the first tick after a reset. Returns the snapshot."
  [now-ns]
  (let [state (state/get-raw-state)
        mtc-pos (mtc/current)
        before @show-time/!runtime
        t0 (or (:t0-ns before) now-ns)
        {:keys [runtime reports]} (show-time/step before state mtc-pos (/ (- (long now-ns) (long t0)) 1e6))
        runtime (assoc runtime :t0-ns t0)
        _ (reset! show-time/!runtime runtime)
        playing? (boolean (get-in state [:playback :playing?]))
        {:keys [cues] :as rendered} (render state (when playing? (show-time/active-runs state runtime)))
        snapshot (show-time/publish! (assoc rendered
                                            :at-ns (System/nanoTime)
                                            :playing? playing?
                                            :preview (delay (frame-service/preview-data cues))))]
    (when (zero? (mod (long (:tick runtime)) MIRROR_EVERY))
      (mirror! runtime snapshot mtc-pos now-ns))
    (run! events/dispatch! reports)
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
