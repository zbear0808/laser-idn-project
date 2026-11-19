;; EVIDENCE for plans/2026-11-18-vibe-mode/.
;; Not part of the test suite and not on any classpath.
;;
;; Each deftest prints PROBE lines describing what today's trigger, timing and
;; persistence code does, for the claims the plan builds on. They ran against
;; master b1da0f2, whose src/ is unchanged since 378db47. The plan docs quote
;; the results (00-overview.md section 3). When a phase changes a behaviour,
;; turn the matching probe into a real test that asserts the new one.
;;
;; No UI, no hardware, no sockets. Everything runs in the REPL-less test JVM
;; with state from domains/build-initial-state and the starter cue chains.
;;
;; Run from the repo root (macOS Apple Silicon; use :win, :linux or :mac elsewhere).
;; The plan folder is the classpath root, so this namespace is probes.vibe-probes-test:
;;
;;   export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
;;   export PATH="$JAVA_HOME/bin:$PATH"
;;   P=plans/2026-11-18-vibe-mode
;;   clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
;;       -M:mac-arm:test:probe -d "$P/probes" \
;;       -n probes.vibe-probes-test 2>&1 | grep PROBE

(ns probes.vibe-probes-test
  (:require [cljfx.api :as fx]
            [cljfx.event-handler :as event-handler]
            [clojure.test :refer [deftest]]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.modulation :as mod]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.events :as input-events]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]
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
