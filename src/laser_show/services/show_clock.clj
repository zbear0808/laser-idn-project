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
      (backend/multi_engine.clj). The snapshot also carries where each cue is,
      and the preview as a delay that the screen forces (views/components/
      clock_view.clj draws it on each screen refresh);
   4. sends what it learned back as events: a timeline's end and a timecode
      chase (:show-clock/cue-ended, :show-clock/chase), and the status views
      show a few times a second, the beat square, the frame stats and the MTC
      text (:show-clock/status), when app state doesn't have it yet. It writes
      app state no other way.

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

(def ^:private stats-interval-ms
  "The frame stats are recomputed at most this often (4 times a second)."
  250.0)


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

(defn tick!
  "One tick at now-ns (System/nanoTime): step show time, render every playing
   cue, publish, then send the step's reports and the status as events
   (events/dispatch!). now-ms, the step's time base, is fractional
   milliseconds since the first tick after a reset. Returns the snapshot."
  [now-ns]
  (let [state (state/get-raw-state)
        mtc-pos (mtc/current)
        before @show-time/!runtime
        t0 (or (:t0-ns before) now-ns)
        now-ms (/ (- (long now-ns) (long t0)) 1e6)
        {:keys [runtime reports]} (show-time/step before state mtc-pos now-ms)
        runtime (assoc runtime :t0-ns t0)
        _ (reset! show-time/!runtime runtime)
        playing? (boolean (get-in state [:playback :playing?]))
        {:keys [cues] :as rendered} (render state (when playing? (show-time/active-runs state runtime)))
        snapshot (show-time/publish! (assoc rendered
                                            :at-ns (System/nanoTime)
                                            :playing? playing?
                                            :positions (show-time/positions runtime)
                                            :preview (delay (frame-service/preview-data cues))))
        ;; After publish!, so the engines never wait for it
        [runtime status] (status-step runtime state mtc-pos now-ms)]
    (reset! show-time/!runtime runtime)
    (run! events/dispatch! (cond-> reports status (conj status)))
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
