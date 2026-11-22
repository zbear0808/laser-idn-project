;; EVIDENCE for plans/2026-11-18-vibe-mode/ (deleted 2026-11-20 when its Phases 1-7
;; were done; in git history at 8b4b7d7). It moved here with that plan's
;; Phase 8, 11-vibe-phase8-zone-effects.md, whose probes are v19-v24; v1-v18
;; are the finished phases' evidence, kept because the probes share helpers.
;; Not part of the test suite and not on any classpath.
;;
;; Each deftest prints PROBE lines describing what today's trigger, timing and
;; persistence code does, for the claims the plan builds on. They ran against
;; master b1da0f2, whose src/ is unchanged since 378db47. The plan docs quote
;; the results (00-overview.md section 3). When a phase changes a behaviour,
;; turn the matching probe into a real test that asserts the new one. A probe
;; stops reproducing its number once the phase that fixes it lands (with Phase 1
;; in the working tree, v1 ends in an error and v3's [:triggers] lines read nil):
;; to see the plan's numbers again, run it in a checkout of b1da0f2.
;;
;; No UI, no hardware, no sockets. Everything runs in the REPL-less test JVM
;; with state from domains/build-initial-state and the starter cue chains.
;;
;; Run from the repo root (macOS Apple Silicon; use :win, :linux or :mac elsewhere).
;; The plan folder is the classpath root, so this namespace is probes.vibe-probes-test:
;;
;;   export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
;;   export PATH="$JAVA_HOME/bin:$PATH"
;;   P=plans/2026-11-19-zones
;;   clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
;;       -M:mac-arm:test:probe -d "$P/probes" \
;;       -n probes.vibe-probes-test 2>&1 | grep PROBE

(ns probes.vibe-probes-test
  (:require [cljfx.api :as fx]
            [cljfx.event-handler :as event-handler]
            [clojure.test :refer [deftest]]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.shape]
            [laser-show.animation.modulator-evaluators :as evaluators]
            [laser-show.animation.modulation :as mod]
            [laser-show.animation.show-time :as show-time]
            [laser-show.backend.multi-engine :as me]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.events :as input-events]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]
            [laser-show.routing.rig :as rig]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.templates :as templates]
            [laser-show.timeline.playback :as playback]
            [laser-show.triggers.shuffle-bag :as bag])
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]))

(defn- say [& xs] (apply println "PROBE" xs) (flush))

(defn- percentile [sorted p]
  (nth sorted (min (dec (count sorted)) (long (* p (count sorted))))))

(def ^:private hit-key {:source :keyboard :id :h})
(def ^:private blackout-key {:source :keyboard :id :b})

(defn- starter-state
  "Starter cue chains and two keyboard bindings: h plays cell [0 0] while held,
   b is the blackout action."
  []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:config :input :trigger-map]
                {hit-key {:type :trigger-cue :target [0 0] :behavior :momentary}
                 blackout-key {:type :blackout}})))

(defn- init! [st]
  (state/init-state! st)
  (show-time/reset-runtime!)
  (router/clear-handlers!)
  (events/set-dispatch-fn! nil))

(defn- key-event [id press?]
  {:event/type :trigger/input
   :input-event (input-events/trigger :keyboard id (if press? :pressed :released))})


;; v1: does the shuffle bag ever repeat a cue back to back?
;; The Rust bag swaps the first pick of a refill if it equals the last pick
;; (beyond-vibe/src/engine/shuffle_bag.rs:30-38). laser-show.triggers.shuffle-bag
;; reshuffles with no such check (shuffle_bag.clj:21-30).

(deftest v1-bag-repeats-at-refill-boundaries
  (doseq [n [2 3 4 5 6 8]]
    (let [picks (loop [b (bag/make (vec (range n))) acc (transient []) i 0]
                  (if (= i 20000)
                    (persistent! acc)
                    (let [[item b'] (bag/next b)]
                      (recur b' (conj! acc item) (inc i)))))
          repeats (count (filter (fn [[a b]] (= a b)) (map vector picks (rest picks))))]
      (say (format "v1 pool of %d: %d of %d picks repeat the previous one (%.1f %%; chance of a repeat at each refill is 1/%d)"
                   n repeats (dec (count picks)) (* 100.0 (/ repeats (dec (count picks)))) n)))))


;; v2: are input timestamps kept?
;; The mapper sends :time with the input's timestamp (input_mapper.clj:73-76),
;; but cljfx's wrap-co-effects assoc's every co-effect over the event
;; (cljfx/event_handler.clj:8-13) and events/co-effects has :time.

(deftest v2-input-time-is-overwritten
  (init! (starter-state))
  (let [seen (atom nil)
        handler (fx/wrap-co-effects (fn [event] (reset! seen (:time event)) {})
                                    events/co-effects)
        sent 1000
        before (System/currentTimeMillis)]
    (handler {:event/type :trigger/input :time sent})
    (say "v2 :time as the mapper sent it:" sent
         " :time as the handler saw it:" @seen
         (str "(" (- @seen before) " ms after the call started: the processing time)"))))


;; v3: what a project file keeps.
;; domains.clj:89-115 declares [:triggers] and [:trigger-runtime]; the first one's
;; docstring says "Persisted". persistent-state-mapping (persistent.clj:33-61)
;; has no [:triggers].

(deftest v3-what-is-saved
  (let [st (-> (domains/build-initial-state)
               (assoc-in [:triggers :groups] {:g1 {:name "kept?"}})
               (assoc-in [:triggers :settings :group-duration-secs] 99)
               (assoc-in [:config :vibes] {:order [:a]}))
        files (persistent/project-files st)
        top (into (sorted-map) (map (fn [[file data]] [file (set (keys data))])) files)
        loaded (persistent/apply-loaded-project (domains/build-initial-state) files "x.zip")]
    (say "v3 top-level state keys each project file saves:" (pr-str top))
    (say "v3 [:triggers :groups] after save + load:" (pr-str (get-in loaded [:triggers :groups]))
         " [:triggers :settings :group-duration-secs]:" (get-in loaded [:triggers :settings :group-duration-secs]))
    (say "v3 a NEW key under :config ([:config :vibes]) after save + load:" (pr-str (get-in loaded [:config :vibes])))
    ;; An older file, saved before a NEW :config key had a default, and read by
    ;; a build whose initial state has the default (:merge-defaults? true).
    (let [original domains/build-initial-state
          old-files (update-in files ["project-metadata.edn" :config] dissoc :vibes)
          with-default (fn [] (assoc-in (original) [:config :vibes] {:order [] :library {}}))
          loaded-old (with-redefs [domains/build-initial-state with-default]
                       (persistent/apply-loaded-project (with-default) old-files "old.zip"))]
      (say "v3 file without :vibes, loaded by a build whose default has it:" (pr-str (get-in loaded-old [:config :vibes]))))
    (let [s0 (domains/build-initial-state)
          s1 (assoc-in s0 [:config :vibes] {:order [:a]})
          d0 (history/extract-document-state s0)
          d1 (history/extract-document-state s1)]
      (say "v3 editing [:config :vibes]: undoable?" (history/undoable-changed? d0 d1)
           " counts as an unsaved change?" (history/persisted-changed? d0 d1)))
    (let [s0 (domains/build-initial-state)
          s1 (assoc-in s0 [:vibe-runtime :current] :a)]
      (say "v3 a change under a NEW top-level runtime key: document roots identical?"
           (history/document-roots-identical? s0 s1)))))


;; v4: what "blackout" does today.
;; triggers.clj:77 maps :blackout to :transport/stop; activate-cue sets
;; :playing? true again (cue_timing.clj:322-333).

(deftest v4-blackout-is-stop-and-any-cue-resumes
  (init! (starter-state))
  (let [playing #(get-in (state/get-raw-state) [:playback :playing?])
        cues #(vec (keys (get-in (state/get-raw-state) [:playback :active-cues])))]
    (events/event-handler (key-event :h true))
    (say "v4 after the hit key: playing?" (playing) "active cues" (cues))
    (events/event-handler (key-event :b true))
    (say "v4 after the blackout key: playing?" (playing) "active cues" (cues)
         "restart requested?" (some? (get-in (state/get-raw-state) [:playback :requests :restart])))
    (events/event-handler (key-event :h false))
    (events/event-handler (key-event :h true))
    (say "v4 hit key again while \"blacked out\": playing?" (playing) "active cues" (cues))))


;; v5: what one press costs the event pipeline (handler, history and dirty
;; middleware, the state write and the inline :dispatch), with no agent queue.

(deftest v5-press-cost
  (init! (starter-state))
  (let [press (key-event :h true)
        release (key-event :h false)
        run (fn [n]
              (vec (sort (for [_ (range n)
                               ev [press release]]
                           (let [t0 (System/nanoTime)]
                             (events/event-handler ev)
                             (- (System/nanoTime) t0))))))]
    (run 3000)
    (let [xs (run 5000)]
      (say (format "v5 press or release through events/event-handler, 10000 events: median %.0f us, p99 %.0f us, max %.0f us"
                   (/ (percentile xs 0.5) 1000.0) (/ (percentile xs 0.99) 1000.0) (/ (last xs) 1000.0))))))


;; v6: learn captures a note or trigger, never a control change
;; (handlers/input.clj:234-238), so a fader can't be learned.

(deftest v6-learn-ignores-control-change
  (init! (starter-state))
  (let [target {:type :vibe :role :hit}
        bound (fn [k] (get-in (state/get-raw-state) [:config :input :trigger-map k]))
        note-key {:source :midi :channel 0 :note 36}]
    (events/event-handler {:event/type :input/start-learn :target-action target})
    (router/dispatch! (input-events/control-change :midi 0 1 0.5))
    (Thread/sleep 300)
    (say "v6 after a control change: still learning?" (= target (get-in (state/get-raw-state) [:backend :input :learning-target]))
         " any binding for CC 1?" (some? (get-in (state/get-raw-state) [:config :input :trigger-map {:source :midi :channel 0 :control 1}])))
    (router/dispatch! (input-events/note-on :midi 0 36 1.0))
    (loop [i 0]
      (when (and (< i 50) (nil? (bound note-key)))
        (Thread/sleep 20)
        (recur (inc i))))
    (say "v6 after a note-on: bound to" (pr-str (bound note-key))
         " still learning?" (some? (get-in (state/get-raw-state) [:backend :input :learning-target])))
    (router/clear-handlers!)))


;; v7: a plain (non-timeline) cue never ends.
;; update-cue-timing sets :ended? only for timelines (cue_timing.clj:176-177).

(deftest v7-a-plain-cue-never-ends
  (init! (starter-state))
  (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
  (let [st (state/get-raw-state)
        tick (/ 1000.0 60)
        rt (reduce (fn [rt i] (:runtime (show-time/step rt st nil (* i tick))))
                   show-time/initial-runtime
                   (range 0 (* 60 20)))
        run (first (vals (:runs rt)))]
    (say "v7 after 20 s at 120 BPM:" (pr-str (select-keys run [:accumulated-beats :ended? :timeline?])))))


;; v8: what an unmapped input leaves behind in [:trigger-runtime]
;; (handlers/triggers.clj:91-114). Nothing reads it (no view subscribes).

(deftest v8-unmapped-input-churn
  (init! (starter-state))
  (dotimes [i 6]
    (events/event-handler {:event/type :trigger/input
                           :input-event (input-events/note-on :midi 0 (+ 60 i) 1.0)}))
  (let [rt (:trigger-runtime (state/get-raw-state))]
    (say "v8 after 6 note-ons that no binding wants and no note-off: held-inputs" (count (:held-inputs rt))
         " active-triggers" (count (:active-triggers rt))
         " tap-history" (count (:tap-history rt)) "(capped at 8) last-input-time set?" (some? (:last-input-time rt)))))


;; v9: a fader never visits the event agent; a note does.

(deftest v9-cc-goes-to-the-store-not-the-agent
  (init! (starter-state))
  (store/clear-continuous-values!)
  (let [dispatched (atom [])]
    (input-mapper/set-dispatch-fn! #(swap! dispatched conj %))
    (input-mapper/start-mapper!)
    (try
      (router/dispatch! (input-events/control-change :midi 0 1 0.5))
      (say "v9 after a CC: store [:midi 0 1] =" (store/get-continuous-value [:midi 0 1])
           " events sent to the app:" (count @dispatched))
      (router/dispatch! (input-events/note-on :midi 0 36 1.0))
      (say "v9 after a note-on: events sent to the app:" (count @dispatched)
           (pr-str (mapv :event/type @dispatched)))
      (say "v9 a fader nobody has moved yet reads" (store/get-continuous-value [:macro :slider-1])
           "(store default, store.clj:21-22)")
      (finally
        (input-mapper/stop-mapper!)
        (input-mapper/set-dispatch-fn! nil)))))


;; v10: the event agent's queue. The same wiring fx/create-app builds (wrap-async
;; over co-effects, the pipeline and the effects), without a renderer. A press
;; is timed from the dispatch call to the cue being in app state, first on an
;; idle agent, then behind a 200 ms effect (what a project save or a MIDI
;; device connect is, today: events/core.clj:189-210, handlers/input.clj:53-63).

(deftest v10-agent-hop
  (init! (starter-state))
  (let [handler (fn [event]
                  (if (= :probe/block (:event/type event))
                    {:probe/block 200}
                    (events/handle-event-pipeline event)))
        effects (assoc events/effects :probe/block (fn [ms _] (Thread/sleep (long ms))))
        dispatch! (-> handler
                      (event-handler/wrap-co-effects events/co-effects)
                      (event-handler/wrap-effects effects)
                      (event-handler/wrap-async {}))
        playing? #(contains? (get-in (state/get-raw-state) [:playback :active-cues]) [0 0])
        wait-until (fn [pred]
                     (loop [i 0]
                       (when (and (not (pred)) (< i 5000))
                         (Thread/sleep 1)
                         (recur (inc i)))))
        hop (fn []
              (let [t0 (System/nanoTime)]
                (dispatch! (key-event :h true))
                (loop []
                  (if (playing?)
                    (- (System/nanoTime) t0)
                    (do (Thread/onSpinWait) (recur))))))
        release! (fn []
                   (dispatch! (key-event :h false))
                   (wait-until #(not (playing?))))
        sample (fn [] (let [x (hop)] (release!) x))
        samples (fn [n] (vec (sort (repeatedly n sample))))]
    (samples 200)
    (let [idle (samples 500)]
      (say (format "v10 idle agent, 500 presses: median %.0f us, p99 %.0f us, max %.0f us"
                   (/ (percentile idle 0.5) 1000.0) (/ (percentile idle 0.99) 1000.0) (/ (last idle) 1000.0))))
    (dispatch! {:event/type :probe/block})
    (Thread/sleep 20)
    (let [blocked (sample)]
      (say (format "v10 press queued 20 ms into a 200 ms effect on the agent: %.0f ms until the cue is in app state"
                   (/ blocked 1e6))))))


;; v11: what a "master" effect costs per frame. The effects grid's active cells
;; become one chain that every cue's every zone frame goes through
;; (frame_service.clj:83-114, 288-300). The intensity effect's :amount modulated
;; by an input value, on a 1,000-point frame. The planned :macro modulator
;; resolves like :midi does: once per chain (effects.clj:417-447).

(deftest v11-master-effect-cost
  (let [frame (vec (for [i (range 1000)]
                     [(- (/ i 500.0) 1.0) (Math/sin (/ i 50.0)) 1.0 0.5 0.25]))
        chain (fn [amount] {:effects [{:id (random-uuid) :effect-id :intensity :enabled? true
                                       :params {:amount amount}}]})
        ctx (assoc (cue-timing/get-global-timing-context {} 120.0)
                   :input-values {[:midi 0 1] 0.8})
        ^ThreadMXBean bean (ManagementFactory/getThreadMXBean)
        tid (.getId (Thread/currentThread))
        measure (fn [c]
                  (dotimes [_ 3000] (effects/apply-effect-chain frame c ctx))
                  (let [n 3000
                        a0 (.getThreadAllocatedBytes bean tid)
                        ts (vec (sort (for [_ (range n)]
                                        (let [t0 (System/nanoTime)]
                                          (effects/apply-effect-chain frame c ctx)
                                          (- (System/nanoTime) t0)))))
                        a1 (.getThreadAllocatedBytes bean tid)]
                    [(/ (percentile ts 0.5) 1000.0) (/ (percentile ts 0.99) 1000.0) (/ (- a1 a0) n 1024.0)]))
        [m1 p1 g1] (measure nil)
        [m2 p2 g2] (measure (chain {:type :midi :channel 1 :cc 1 :min 0.0 :max 1.0}))
        [m3 p3 g3] (measure (chain 1.0))]
    (say (format "v11 1,000-point frame, no global effects: median %.1f us, p99 %.1f us, %.1f KB garbage" m1 p1 g1))
    (say (format "v11 1,000-point frame, intensity with a static amount: median %.1f us, p99 %.1f us, %.1f KB garbage" m3 p3 g3))
    (say (format "v11 1,000-point frame, intensity with a modulated amount: median %.1f us, p99 %.1f us, %.1f KB garbage" m2 p2 g2))))


;; v12: a tap behind a blocked agent. The handler measures how long a key was
;; down from the times it was handed (:time, overwritten, v2). An 80 ms tap
;; whose release waits behind a 200 ms effect on the agent looks like a hold.

(deftest v12-a-tap-behind-a-blocked-agent-looks-like-a-hold
  (init! (starter-state))
  (let [seen (atom [])
        handler (fn [event]
                  (case (:event/type event)
                    :probe/block {:probe/block 200}
                    :trigger/input (do (swap! seen conj {:state (get-in event [:input-event :state])
                                                         :handled-at (:time event)
                                                         :input-at (get-in event [:input-event :timestamp])})
                                       {})
                    {}))
        effects (assoc events/effects :probe/block (fn [ms _] (Thread/sleep (long ms))))
        dispatch! (-> handler
                      (event-handler/wrap-co-effects events/co-effects)
                      (event-handler/wrap-effects effects)
                      (event-handler/wrap-async {}))]
    (dispatch! (key-event :h true))
    (Thread/sleep 10)
    (dispatch! {:event/type :probe/block})
    (Thread/sleep 80)
    (dispatch! (key-event :h false))
    (loop [i 0]
      (when (and (< (count @seen) 2) (< i 500))
        (Thread/sleep 10)
        (recur (inc i))))
    (let [[press release] @seen]
      (say (format "v12 finger down for %d ms (input timestamps); the handler measures %d ms (the times it was handed); a 150 ms tap threshold calls it a %s"
                   (- (:input-at release) (:input-at press))
                   (- (:handled-at release) (:handled-at press))
                   (if (> (- (:handled-at release) (:handled-at press)) 150) "HOLD" "tap"))))))


;; Tap reactions (04-phase4-tap-reactions.md): what a tap can do to an effect today.

;; v13: can something new in a timing context reach a modulator? render-cue
;; puts :input-values on the cue's timing context (show_clock.clj:51-53), but
;; mod/make-context and make-base-context build their map from a fixed list of
;; keys (modulation.clj:63-71, 86-101).

(deftest v13-new-context-keys-never-reach-modulators
  (let [ctx (assoc (cue-timing/get-cue-timing-context {:accumulated-beats 2.0 :accumulated-ms 1000.0} 120.0)
                   :input-values {[:midi 0 1] 0.5}
                   :pulses {:hit {:count 3}})]
    (say "v13 a :pulses key on a cue's timing context: kept by mod/make-context?" (contains? (mod/make-context ctx) :pulses)
         " by mod/make-base-context?" (contains? (mod/make-base-context ctx) :pulses)
         " by playback/clip-timing-ctx?" (contains? (playback/clip-timing-ctx ctx {:timeline/start 1.0} 120.0) :pulses)
         " (:input-values kept by make-context?" (str (contains? (mod/make-context ctx) :input-values) ")"))))


;; v14: the modulator editor's "Retrigger" button (modulator_param_control.clj:224-239)
;; dispatches :modulator/retrigger, whose handler dispatches :transport/retrigger
;; (handlers/modulator.clj:211-221): a :restart request for every clock.

(deftest v14-modulator-retrigger-restarts-every-clock
  (init! (starter-state))
  (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
  (events/event-handler {:event/type :grid/trigger-cell :col 1 :row 0})
  (let [tick (/ 1000.0 60)
        rt (reduce (fn [rt i] (:runtime (show-time/step rt (state/get-raw-state) nil (* i tick))))
                   show-time/initial-runtime
                   (range 120))
        beats (fn [rt] (into (sorted-map) (map (fn [run] [(:cell run) (format "%.2f" (:accumulated-beats run))])) (vals (:runs rt))))]
    (events/event-handler {:event/type :modulator/retrigger :param-key :x-scale})
    (let [rt2 (:runtime (show-time/step rt (state/get-raw-state) nil (* 120 tick)))]
      (say "v14 two cues after 2 s:" (pr-str (beats rt)) (format "global %.2f beats" (get-in rt [:global :accumulated-beats])))
      (say "v14 one tick after one modulator's Retrigger:" (pr-str (beats rt2)) (format "global %.2f beats" (get-in rt2 [:global :accumulated-beats]))))))


;; v15: can a press re-fire an envelope? A :decay modulator (1.5 -> 1.0 over
;; one beat) measures from its context's start (time.clj:143-154): a cue's run,
;; or the global clock for the effects grid (frame_service.clj:102-114,
;; 288-300). A press that starts another cue changes neither.

(deftest v15-a-press-cannot-refire-an-envelope
  (init! (starter-state))
  (let [decay {:type :decay :min 1.0 :max 1.5 :duration 1.0 :time-unit :beats :decay-curve :exp}
        tick (/ 1000.0 60)
        steps (fn [rt from n]
                (reduce (fn [rt i] (:runtime (show-time/step rt (state/get-raw-state) nil (* i tick))))
                        rt (range from (+ from n))))
        on-global (fn [rt] (mod/resolve-param decay (mod/make-context (cue-timing/get-global-timing-context (:global rt) 120.0))))
        on-cue (fn [rt cell]
                 (let [run (get-in rt [:runs (get-in (state/get-raw-state) [:playback :active-cues cell :run])])]
                   (mod/resolve-param decay (mod/make-context (cue-timing/get-cue-timing-context run 120.0)))))
        _ (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
        rt (steps show-time/initial-runtime 0 240)
        g1 (on-global rt)
        _ (events/event-handler {:event/type :grid/trigger-cell :col 1 :row 0})
        rt2 (steps rt 240 1)]
    (say (format "v15 the decay on an effects-grid cell: %.3f after 8 beats, %.3f one tick after a press starts another cue"
                 g1 (on-global rt2)))
    (say (format "v15 the same decay as a cue's own effect: %.3f on the cue just pressed (its run is new), %.3f on the cue playing since beat 0"
                 (on-cue rt2 [1 0]) (on-cue rt2 [0 0])))))


;; v16: what a "random" modulator does on a restart. Its value is a hash of the
;; step number only (modulator_evaluators.clj:177-198), so every restart of a
;; once-mode random lands on the same value.

(deftest v16-random-gives-the-same-value-after-every-restart
  (let [cfg {:type :random :min 0.0 :max 1.0 :period 1.0 :time-unit :beats :loop-mode :once :once-periods 1.0}
        at (fn [beats] (mod/resolve-param cfg (mod/make-context (cue-timing/get-cue-timing-context
                                                                  {:accumulated-beats beats :accumulated-ms (* 500.0 beats)} 120.0))))]
    (say "v16 a once-mode random just after each of five restarts:" (pr-str (mapv (fn [_] (format "%.3f" (at 0.0))) (range 5)))
         " and 0.5 beats into each:" (pr-str (mapv (fn [_] (format "%.3f" (at 0.5))) (range 5))))))


;; v17: a "complex" tap reaction, four effects whose parameters all follow one
;; value (04-phase4-tap-reactions.md). A :midi modulator stands in for the
;; planned Envelope / Step / Energy ones: all of them are global modulators, so
;; apply-effect-chain resolves each once per parameter (effects.clj:467-470) and
;; the cost is the same. Does a value reach a global effect through the chain,
;; is each effect exactly neutral at its rest value, and what does a chain of
;; one and of four effects cost on a 1,000-point frame? The cost is per zone
;; frame of every playing cue (frame_service.clj:399-403).

(defn- circle-frame [n]
  (vec (for [i (range n)]
         (let [a (* 2.0 Math/PI (/ i (double n)))]
           [(* 0.8 (Math/cos a)) (* 0.8 (Math/sin a)) 1.0 0.6 0.2]))))

(defn- max-abs-diff [a b]
  (apply max (for [[p q] (map vector a b) i (range 5)]
               (Math/abs (- (double (p i)) (double (q i)))))))

(defn- measure-chain
  "[median-us p99-us kb-garbage-per-call] of apply-effect-chain on frame."
  [frame chain ctx]
  (let [^ThreadMXBean bean (ManagementFactory/getThreadMXBean)
        tid (.getId (Thread/currentThread))
        n 3000]
    (dotimes [_ 3000] (effects/apply-effect-chain frame chain ctx))
    (let [a0 (.getThreadAllocatedBytes bean tid)
          ts (vec (sort (for [_ (range n)]
                          (let [t0 (System/nanoTime)]
                            (effects/apply-effect-chain frame chain ctx)
                            (- (System/nanoTime) t0)))))
          a1 (.getThreadAllocatedBytes bean tid)]
      [(/ (percentile ts 0.5) 1000.0) (/ (percentile ts 0.99) 1000.0) (/ (- a1 a0) n 1024.0)])))

(deftest v17-complex-effect-chain
  (let [frame (circle-frame 1000)
        drive (fn [lo hi] {:type :midi :channel 1 :cc 7 :min lo :max hi})
        fx (fn [id params] {:id (random-uuid) :effect-id id :enabled? true :params params})
        impact {:effects [(fx :intensity {:amount (drive 1.0 2.0)})
                          (fx :scale {:x-scale (drive 1.0 1.3) :y-scale (drive 1.0 1.3)})
                          (fx :rotation {:angle (drive 0.0 25.0)})
                          (fx :hue-shift {:degrees (drive 0.0 40.0)})]}
        kick {:effects [(fx :intensity {:amount (drive 1.0 2.0)})]}
        ctx (fn [v] (assoc (cue-timing/get-global-timing-context {} 120.0)
                           :input-values {[:midi 0 7] v}))
        idle (effects/apply-effect-chain frame impact (ctx 0.0))
        half (effects/apply-effect-chain frame impact (ctx 0.5))
        peak (effects/apply-effect-chain frame impact (ctx 1.0))
        fmt (fn [pt] (mapv #(Double/parseDouble (format "%.3f" (double %))) pt))]
    (say "v17 the value reaches a global effect: point 100 in" (pr-str (fmt (frame 100)))
         "| value 0.0 ->" (pr-str (fmt (idle 100)))
         "| 0.5 ->" (pr-str (fmt (half 100)))
         "| 1.0 ->" (pr-str (fmt (peak 100))))
    (say (format "v17 neutral at rest: frames equal? %s, largest difference %.2e (the 4-effect chain at value 0.0)"
                 (= idle frame) (max-abs-diff frame idle)))
    (let [alone (fn [id params] (max-abs-diff frame (effects/apply-effect-chain frame {:effects [(fx id params)]} (ctx 0.0))))]
      (say "v17 largest difference from the input, each effect alone at its rest value:"
           (pr-str (into (sorted-map)
                         {:intensity (alone :intensity {:amount (drive 1.0 2.0)})
                          :scale (alone :scale {:x-scale (drive 1.0 1.3) :y-scale (drive 1.0 1.3)})
                          :rotation (alone :rotation {:angle (drive 0.0 25.0)})
                          :translate (alone :translate {:x (drive 0.0 0.4) :y (drive 0.0 0.4)})
                          :hue-shift (alone :hue-shift {:degrees (drive 0.0 40.0)})}))))
    (let [[m1 p1 g1] (measure-chain frame kick (ctx 0.7))
          [m4 p4 g4] (measure-chain frame impact (ctx 0.7))
          [m4i p4i g4i] (measure-chain frame impact (ctx 0.0))]
      (say (format "v17 1,000 points, one effect (kick):          median %.1f us, p99 %.1f us, %.0f KB garbage" m1 p1 g1))
      (say (format "v17 1,000 points, four effects (impact), live: median %.1f us, p99 %.1f us, %.0f KB garbage" m4 p4 g4))
      (say (format "v17 1,000 points, four effects (impact), idle: median %.1f us, p99 %.1f us, %.0f KB garbage" m4i p4i g4i)))))


;; v18: the decay curves an envelope would use (the decay modulator's own,
;; modulator_evaluators.clj:149-162): the fraction left at progress p.

(deftest v18-decay-curves
  (doseq [curve [:linear :exp :log]]
    (say "v18" (name curve) "fraction left at progress 0, .25, .5, .75, 1:"
         (pr-str (mapv #(Double/parseDouble (format "%.3f" (evaluators/decay-curve-factor curve (double %))))
                       [0.0 0.25 0.5 0.75 1.0])))))


;; Zone effects (11-vibe-phase8-zone-effects.md, once the vibe plan's 08): zone chase, swap and shift.
;;
;; v19-v24 look at the zone path as it is today and run PROTOTYPES of the three
;; remaps (the defns marked "prototype" below are probe code, the executable
;; form of the plan's data tables, not the implementation).

(def ^:private zone-cue-lcr
  "Three items, each routed to its own zone, as zone_routing_integration_test.clj
   does (that file's test-cue-chain-multiple-items)."
  {:id "cue-lcr" :name "Left Center Right" :destination-zone {:zone-group-id :all}
   :items [{:id "i-left" :type :preset :enabled? true :preset-id :circle
            :params {:radius 0.3 :num-points 20 :red 1.0 :green 0.0 :blue 0.0}
            :effects [{:effect-id :zone-selector :enabled? true :params {:target-zone :left}}]}
           {:id "i-right" :type :preset :enabled? true :preset-id :square
            :params {:size 0.3 :num-points 12 :red 0.0 :green 1.0 :blue 0.0}
            :effects [{:effect-id :zone-selector :enabled? true :params {:target-zone :right}}]}
           {:id "i-center" :type :preset :enabled? true :preset-id :triangle
            :params {:size 0.3 :num-points 3 :red 0.0 :green 0.0 :blue 1.0}
            :effects [{:effect-id :zone-selector :enabled? true :params {:target-zone :center}}]}]})

(def ^:private zone-cue-all
  "One item, no zone selector: it goes to the cue's destination, :all."
  {:id "cue-all" :name "All" :destination-zone {:zone-group-id :all}
   :items [{:id "i-ring" :type :preset :enabled? true :preset-id :circle
            :params {:radius 0.5 :num-points 32 :red 1.0 :green 1.0 :blue 1.0}
            :effects []}]})

(def ^:private zone-projectors
  "Three projectors in a row and each in :all (so a cue routed to :all reaches
   them), one scanning the crowd, one for graphics."
  {:p-left {:zone-groups [:all :left]} :p-center {:zone-groups [:all :center]} :p-right {:zone-groups [:all :right]}
   :p-crowd {:zone-groups [:crowd] :tags #{:crowd-scanning}} :p-gfx {:zone-groups [:graphics]}})

(defn- zone-state! []
  (init! (domains/build-initial-state))
  (state/assoc-in-state! [:projectors] zone-projectors))

(defn- zone-snapshot
  "A snapshot of one cue; since zones Z3 it carries the routing the engines
   compose from, and since state integrity 01 what they take from app state
   (multi-engine/engine-inputs), made from app state as the show clock makes
   them."
  [zone-frames]
  (let [s (state/get-raw-state)]
    {:tick 1 :at-ns (System/nanoTime) :playing? true :projector-ctx nil :preview (delay nil)
     :cues [{:cell [0 0] :zone-frames zone-frames}]
     :routing (rig/routing (rig/routing-inputs s nil))
     :engine-inputs (me/engine-inputs s (System/currentTimeMillis))}))

(defn- render-zones
  "generate-frames-by-zone for one cue at beats, through the same render context
   the show clock makes (frame_service.clj:102-114)."
  [cue beats]
  (let [rctx (frame-service/render-context (state/get-raw-state) {} (:global show-time/initial-runtime))
        ctx (cue-timing/get-cue-timing-context {:accumulated-beats beats :accumulated-ms (* 500.0 beats)} 120.0)]
    (frame-service/generate-frames-by-zone cue ctx rctx)))

(defn- points-by-zone [zone-frames]
  (into (sorted-map) (map (fn [[z f]] [z (count f)])) zone-frames))

(defn- drawn-by-projector
  "Points each projector sends for one cue's zone frames (nil = nothing, which
   the streaming engine sends as an empty frame, streaming_engine.clj:373)."
  [zone-frames]
  (into (sorted-map)
        (map (fn [pid] [pid (some-> (#'me/projector-frame pid (zone-snapshot zone-frames)) count)]))
        (keys zone-projectors)))

;; --- prototypes: pure re-keyings of {zone-id frame} (04's data tables come from these)

(defn- step-of
  "The whole number of zones along: floor(offset + rate * beats)."
  ^long [offset rate beats]
  (long (Math/floor (+ (double offset) (* (double rate) (double beats)) 1e-9))))

(defn- ring-index [step n path]
  (if (= path :bounce)
    (let [m (mod step (max 1 (- (* 2 n) 2)))] (if (< m n) m (- (* 2 n) 2 m)))
    (mod step n)))

(defn- merge-into [zone-map zone frame]
  (update zone-map zone #(if % (frame-service/concatenate-frames [% frame]) frame)))

(defn- proto-chase
  "prototype: the frame in :from goes to one zone of zones, the one the step names."
  [zone-map {:keys [zones from offset rate path] :or {from :all offset 0.0 rate 1.0 path :loop}} beats]
  (let [n (count zones)]
    (if (or (zero? n) (not (contains? zone-map from)))
      zone-map
      (merge-into (dissoc zone-map from) (nth zones (ring-index (step-of offset rate beats) n path)) (get zone-map from)))))

(defn- proto-shift
  "prototype: the frames of zones move k places along the ring."
  [zone-map {:keys [zones offset rate] :or {offset 0.0 rate 0.0}} beats]
  (let [n (count zones) k (if (< n 2) 0 (mod (step-of offset rate beats) n))]
    (if (zero? k)
      zone-map
      (merge (apply dissoc zone-map zones)
             (into {} (keep-indexed (fn [i z] (when-let [f (get zone-map z)] [(nth zones (mod (+ i k) n)) f]))) zones)))))

(defn- proto-swap
  "prototype: the frames of zone-a and zone-b trade places when amount >= 0.5."
  [zone-map {:keys [zone-a zone-b amount] :or {amount 1.0}}]
  (if (and (>= (double amount) 0.5) (not= zone-a zone-b))
    (let [a (get zone-map zone-a) b (get zone-map zone-b)]
      (cond-> (dissoc zone-map zone-a zone-b) b (assoc zone-a b) a (assoc zone-b a)))
    zone-map))


;; v19: what a cue's zone frames are, and who draws them (multi_engine.clj:47-73, 119-150).

(deftest v19-zone-frames-and-who-draws-them
  (zone-state!)
  (let [lcr (render-zones zone-cue-lcr 0.0)
        all (render-zones zone-cue-all 0.0)]
    (say "v19 the project's zone groups:" (pr-str (sort (keys (:zone-groups (state/get-raw-state))))))
    (say "v19 cue with items routed to left, right and center: points per zone" (pr-str (points-by-zone lcr))
         "| points each projector sends" (pr-str (drawn-by-projector lcr)))
    (say "v19 cue with no zone selector (destination :all): points per zone" (pr-str (points-by-zone all))
         "| points each projector sends" (pr-str (drawn-by-projector all)))))


;; v20: a zone selector in a global effect cell (the effects grid) does nothing:
;; only items' own effects are read for routing (zone_effects.clj:143-145), and the
;; effect itself is an identity transducer (effects/zone.clj:35-41).

(deftest v20-a-global-zone-selector-changes-nothing
  (zone-state!)
  (let [before (render-zones zone-cue-all 0.0)
        _ (state/assoc-in-state! [:chains :effect-chains [0 0]]
                                 {:name "Zone" :active? true
                                  :items [{:effect-id :zone-selector :enabled? true :params {:target-zone :left}}]})
        after (render-zones zone-cue-all 0.0)]
    (say "v20 a cue routed to :all, zones without and with an active effects-grid cell holding a zone selector for :left:"
         (pr-str (vec (keys before))) (pr-str (vec (keys after)))
         "| same frames?" (= before after))))


;; v21: the three remaps on real zone frames, and what each projector then draws.

(deftest v21-chase-shift-swap-on-real-zone-frames
  (zone-state!)
  (let [lcr (render-zones zone-cue-lcr 0.0)
        all (render-zones zone-cue-all 0.0)
        zones [:left :center :right]
        chase {:zones zones :from :all :offset 0.0 :rate 1.0}
        proj (fn [zf] (pr-str (drawn-by-projector (select-keys zf (keys zf)))))
        short-drawn (fn [zf] (pr-str (into (sorted-map) (remove (comp nil? val)) (drawn-by-projector zf))))]
    (say "v21 CHASE of the :all cue over [left center right], rate 1 zone per beat, beats 0 1 2 3 -> zone, projectors that draw:")
    (doseq [beats [0.0 1.0 2.0 3.0]]
      (let [zf (proto-chase all chase beats)]
        (say "v21   beats" beats "zone" (pr-str (vec (keys zf))) (short-drawn zf))))
    (say "v21 CHASE bounce, 3 zones, rate 1, beats 0..5 -> zone index:"
         (pr-str (mapv #(ring-index (step-of 0.0 1.0 %) 3 :bounce) (range 6)))
         "| loop:" (pr-str (mapv #(ring-index (step-of 0.0 1.0 %) 3 :loop) (range 6))))
    (say "v21 HOP: the :all cue with offset 0, 1, 2 (rate 0) -> zones"
         (pr-str (mapv #(vec (keys (proto-chase all (assoc chase :rate 0.0 :offset %) 0.0))) [0.0 1.0 2.0])))
    (say "v21 SHIFT of the left/center/right cue by 1 over [left center right]: points per zone before"
         (pr-str (points-by-zone lcr)) "after" (pr-str (points-by-zone (proto-shift lcr {:zones zones :offset 1.0} 0.0)))
         "| by 2:" (pr-str (points-by-zone (proto-shift lcr {:zones zones :offset 2.0} 0.0)))
         "| by 3 (a full turn, same as 0):" (= lcr (proto-shift lcr {:zones zones :offset 3.0} 0.0))
         "| the :all cue shifted: unchanged?" (= all (proto-shift all {:zones zones :offset 1.0} 0.0)))
    (say "v21 SWAP left and right: before" (pr-str (points-by-zone lcr))
         "after" (pr-str (points-by-zone (proto-swap lcr {:zone-a :left :zone-b :right :amount 1.0})))
         "| amount 0.49 changes nothing?" (= lcr (proto-swap lcr {:zone-a :left :zone-b :right :amount 0.49}))
         "| the frames really trade places?" (let [s (proto-swap lcr {:zone-a :left :zone-b :right :amount 1.0})]
                                                (and (= (:left lcr) (:right s)) (= (:right lcr) (:left s)) (= (:center lcr) (:center s)))))
    (let [gctx (fn [beats] (cue-timing/get-global-timing-context {:accumulated-beats beats :accumulated-ms (* 500.0 beats)
                                                                 :effective-beats beats :phase-offset 0.0 :phase-offset-target 0.0} 120.0))
          params {:zones zones :from :all :rate 0.0
                  :offset {:type :sawtooth :min 0.0 :max 3.0 :period 4.0 :time-unit :beats :loop-mode :loop}}
          at (fn [beats] (effects/resolve-params-global params nil nil (gctx beats)))]
      (say "v21 a Sawtooth 0..3 over 4 beats on :offset, resolved by effects/resolve-params-global at beats 0 1 2 3 3.99:"
           (pr-str (mapv #(format "%.2f" (double (:offset (at %)))) [0.0 1.0 2.0 3.0 3.99]))
           "| :zones comes through as" (pr-str (:zones (at 0.0)))))))


;; v22: why the remaps move frames and never copy them. A projector in two zones
;; that both hold the same frame draws it twice.

(deftest v22-a-copied-frame-is-drawn-twice
  (zone-state!)
  (let [all (render-zones zone-cue-all 0.0)
        frame (:all all)
        moved {:left frame}
        copied {:all frame :left frame}]
    (say "v22 one 32-point frame. p-left is in [:all :left]. Moved to :left: p-left sends"
         (count (#'me/projector-frame :p-left (zone-snapshot moved))) "points; the same frame under :all and :left: p-left sends"
         (count (#'me/projector-frame :p-left (zone-snapshot copied))) "points (twice the frame plus the blanking jump between the copies)")))


;; v23: what a remap costs on 1,000-point zone frames (a re-key, and a re-key that
;; has to join two frames).

(defn- measure-call
  "[median-us p99-us kb-garbage-per-call] of (f), after a warm-up."
  [f]
  (let [^ThreadMXBean bean (ManagementFactory/getThreadMXBean)
        tid (.getId (Thread/currentThread))
        n 5000]
    (dotimes [_ 20000] (f))
    (let [a0 (.getThreadAllocatedBytes bean tid)
          ts (vec (sort (for [_ (range n)] (let [t0 (System/nanoTime)] (f) (- (System/nanoTime) t0)))))
          a1 (.getThreadAllocatedBytes bean tid)]
      [(/ (percentile ts 0.5) 1000.0) (/ (percentile ts 0.99) 1000.0) (/ (- a1 a0) n 1024.0)])))

(deftest v23-remap-cost
  (let [frame (circle-frame 1000)
        zm {:left frame :center frame :right frame :all frame}
        zones [:left :center :right]
        fmt (fn [[m p g]] (format "median %.2f us, p99 %.2f us, %.1f KB garbage" m p g))]
    (say "v23 shift by 1 over 3 zones (3 frames of 1,000 points, a re-key):" (fmt (measure-call #(proto-shift zm {:zones zones :offset 1.0} 0.0))))
    (say "v23 swap two zones:" (fmt (measure-call #(proto-swap zm {:zone-a :left :zone-b :right :amount 1.0}))))
    (say "v23 chase, the :all frame to an empty zone (a re-key):" (fmt (measure-call #(proto-chase (dissoc zm :left) {:zones zones :rate 0.0 :offset 0.0} 0.0))))
    (say "v23 chase, the :all frame to a zone that already has 1,000 points (a join of two frames):"
         (fmt (measure-call #(proto-chase zm {:zones zones :rate 0.0 :offset 0.0} 0.0))))))


;; v24: in what order the active cells of the effects grid become one chain
;; (frame_service.clj:83-100). Zone effects don't commute (a shift then a swap is
;; not a swap then a shift), so their order has to be known.

(deftest v24-order-of-global-effect-cells
  (zone-state!)
  (let [cell-order (fn [cells]
                     (state/assoc-in-state! [:chains :effect-chains]
                                            (reduce (fn [m [col row]]
                                                      (assoc m [col row] {:name (str col row) :active? true
                                                                          :items [{:effect-id :intensity :enabled? true
                                                                                   :params {:amount (double (+ (* 10 row) col))}}]}))
                                                    {} cells))
                     (mapv #(long (get-in % [:params :amount]))
                           (:effects (frame-service/get-active-global-effects (state/get-raw-state)))))
        scrambled (fn [cells] (vec (sort-by #(hash [% :seed]) cells)))
        six (scrambled (for [row (range 2) col (range 3)] [col row]))
        twelve (scrambled (for [row (range 3) col (range 4)] [col row]))
        label (fn [cells] (mapv (fn [[c r]] (+ (* 10 r) c)) cells))]
    (say "v24 six cells (row*10+col) put in the grid in this order" (pr-str (label six)) "-> the chain's order" (pr-str (cell-order six)))
    (say "v24 twelve cells put in the grid in this order" (pr-str (label twelve)) "-> the chain's order" (pr-str (cell-order twelve))
         "| reading order would be" (pr-str (vec (sort (label twelve)))))))
