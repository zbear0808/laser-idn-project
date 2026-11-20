(ns laser-show.services.show-clock-test
  "The show clock thread and its tick (plans/2026-11-17-show-clock/01–04 §8).
   Most tests tick by hand; the ones that need the thread start it."
  (:require [cljfx.api :as fx]
            [cljfx.event-handler :as event-handler]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.effects.shape]
            [laser-show.animation.show-time :as show-time]
            [laser-show.app]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.dev.reload :as reload]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input :as input-handlers]
            [laser-show.events.handlers.input-mapper :as mapper]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.input.events :as ie]
            [laser-show.input.mtc :as mtc]
            [laser-show.input.router :as router]
            [laser-show.input.store :as input-store]
            [laser-show.profiling.frame-profiler :as profiler]
            [laser-show.services.frame-service :as fs]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.templates :as templates]))

(defn- sine [lo hi period] {:type :sine :min lo :max hi :period period})

(defn- preset [preset-id & effects]
  {:id (random-uuid) :type :preset :preset-id preset-id :enabled? true
   :effects (mapv (fn [[effect-id params]]
                    {:id (random-uuid) :effect-id effect-id :enabled? true :params params})
                  effects)})

(defn- cue [preset-id]
  {:destination-zone {:zone-group-id :all}
   :items [(preset preset-id [:rotation {:angle (sine 0.0 360.0 1.0)}])]})

(def ^:private one-clip-timeline
  {:editor-type :timeline
   :destination-zone {:zone-group-id :all}
   :tracks [{:id :t :type :track}]
   :items [{:id :a :type :preset :preset-id :circle :track-id :t :enabled? true :effects []
            :timeline/start 0.0 :timeline/duration 4.0}]})

(defn- init! [chains]
  (state/init-state! (-> (domains/build-initial-state)
                         (assoc-in [:chains :cue-chains] chains)
                         (assoc-in [:timing :bpm] 120.0)))
  (show-time/reset-runtime!))

(use-fixtures :each (fn [f]
                      (init! {})
                      (output-errors/clear!)
                      (try (f)
                           (finally
                             (show-clock/stop!)
                             (show-time/reset-runtime!)
                             (output-errors/clear!)))))

(defn- trigger! [col row]
  (events/event-handler {:event/type :grid/trigger-cell :col col :row row}))

(defn- ticks!
  "n ticks one interval apart on a fake clock from start-ns; the last snapshot."
  ([n] (ticks! n 0))
  ([n start-ns]
   (last (for [i (range n)] (show-clock/tick! (+ start-ns (* i 16666667)))))))

(defn- position [cell]
  (get (show-time/positions @show-time/!runtime) cell))


;; Phase 1: the clock owns show time

(deftest ^:timing ticks-advance-cues-at-60-hz-test
  (init! {[0 0] (cue :circle)})
  (trigger! 0 0)
  (show-clock/start!)
  (Thread/sleep 100)
  (let [tick0 (:tick @show-time/!runtime)
        beats0 (position [0 0])]
    (Thread/sleep 1000)
    (let [ticks (- (:tick @show-time/!runtime) tick0)
          beats (- (position [0 0]) beats0)]
      (is (<= 55 ticks 62) (str ticks " ticks in 1 s"))
      (is (< 1.9 beats 2.1) (str beats " beats in 1 s at 120 BPM")))))

(deftest clock-survives-an-error-test
  (let [calls (atom 0)
        deadline (+ (System/currentTimeMillis) 3000)]
    (with-redefs [show-clock/tick! (fn [_]
                                     (when (= 3 (swap! calls inc))
                                       (throw (AssertionError. "test: Assert failed"))))]
      (show-clock/start!)
      (while (and (< @calls 10) (< (System/currentTimeMillis) deadline))
        (Thread/sleep 10))
      (is (<= 10 @calls) "ticks keep coming after the one that threw")
      (is (show-clock/running?)))))

(def ^:private agent-handler
  "The event wiring app/create-app uses: the pipeline on an agent."
  (-> events/handle-event-pipeline
      (fx/wrap-co-effects (assoc events/co-effects :fx/context (constantly nil)))
      (fx/wrap-effects events/effects)
      (event-handler/wrap-async {:error-handler (fn [_ e] (throw e))})))

(deftest slow-event-cannot-move-a-cue-back-test
  (testing "an event handled while the clock ticks writes back its old snapshot, but
            positions aren't in app state any more (c5)"
    (init! {[0 0] (cue :circle)})
    (trigger! 0 0)
    (show-clock/start!)
    (Thread/sleep 100)
    (let [samples (atom [])
          sampling (atom true)
          sampler (future (while @sampling
                            (swap! samples conj (position [0 0]))
                            (Thread/sleep 2)))
          handle timing/handle]
      (with-redefs [timing/handle (fn [event] (Thread/sleep 300) (handle event))]
        (agent-handler {:event/type :timing/set-bpm :bpm 120.0 :fx/sync true}))
      (Thread/sleep 100)
      (reset! sampling false)
      @sampler
      (let [s (remove nil? @samples)]
        (is (< 100 (count s)))
        (is (empty? (filter neg? (map - (rest s) s))) "the cue never moved back")))))

(deftest reports-reach-app-state-test
  (testing "a timeline's end stops the cue through the :show-clock/cue-ended event"
    (init! {[0 0] one-clip-timeline})
    (events/event-handler {:event/type :timeline/play :col 0 :row 0 :from 3.9})
    (is (some? (get-in (state/get-raw-state) [:playback :active-cues [0 0]])))
    (show-clock/tick! 0)
    (show-clock/tick! 100000000)
    (is (nil? (get-in (state/get-raw-state) [:playback :active-cues [0 0]])))
    (is (false? (get-in (state/get-raw-state) [:playback :playing?])))))

(deftest vibe-report-is-sent-once-per-interval-test
  (testing "auto-advance's reports go out with the others (vibe 06 §4): armed with the gap
            past, a tick sends :vibe/advance, at most every 250 ms"
    (state/init-state! (-> (domains/build-initial-state)
                           (assoc-in [:timing :bpm] 120.0)
                           (assoc-in [:config :vibes :order] [:vibe-1 :vibe-2])
                           (assoc-in [:config :vibes :library] {:vibe-1 {:id :vibe-1 :name "Shapes"}
                                                                :vibe-2 {:id :vibe-2 :name "Lines"}})
                           (update :vibe-runtime merge {:current :vibe-1 :rotation :armed :auto? true
                                                        :entered-at (- (System/currentTimeMillis) 60000)})))
    (show-time/reset-runtime!)
    (let [sent (atom [])
          advance {:event/type :vibe/advance :reason :gap :from :vibe-1}
          vibe-reports #(filterv (comp #{"vibe"} namespace :event/type) @sent)]
      (with-redefs [events/dispatch! #(swap! sent conj %)]
        (show-clock/tick! 0)
        (show-clock/tick! 100000000)
        (is (= [advance] (vibe-reports)) "two ticks 100 ms apart: one")
        (show-clock/tick! 300000000)
        (is (= [advance advance] (vibe-reports)) "300 ms after the first: again"))
      (testing "the handler's guard makes the second harmless"
        (run! events/event-handler (vibe-reports))
        (is (= :vibe-2 (get-in (state/get-raw-state) [:vibe-runtime :current])))
        (is (= {:id 1} (get-in (state/get-raw-state) [:playback :pulses :vibe])) "one change"))
      (testing "and then nothing is due: the new vibe has 45 s to go"
        (reset! sent [])
        (with-redefs [events/dispatch! #(swap! sent conj %)]
          (show-clock/tick! 400000000))
        (is (empty? (vibe-reports)))))))

(deftest reload-keeps-the-clock-running-test
  (testing "a dev code reload doesn't stop or freeze show time (c6)"
    (init! {[0 0] (cue :circle)})
    (trigger! 0 0)
    (show-clock/start!)
    (Thread/sleep 100)
    (with-redefs [reload/discover-namespaces (constantly [])]
      (is (:success? (reload/reload-app-code!))))
    (let [tick (:tick @show-time/!runtime)
          beats (position [0 0])]
      (Thread/sleep 200)
      (is (show-clock/running?))
      (is (< tick (:tick @show-time/!runtime)) "still ticking")
      (is (< beats (position [0 0])) "and the cue still moves"))))


;; Phase 2: render once per tick

(defn- with-engines
  "n projectors on zone :all streaming to a fake DAC (no clock thread, so they
   pace themselves) while (f) runs."
  [n f]
  (let [fake (dac/start!)
        projectors (into {} (for [i (range 1 (inc n))]
                              [(keyword (str "p" i))
                               {:host "127.0.0.1" :port (:port fake) :service-id i
                                :zone-groups [:all] :enabled? true}]))
        _ (state/swap-state! assoc :projectors projectors)
        engines (mapv (fn [[id p]] (me/create-engine-for-projector id p)) projectors)]
    (try
      (run! se/start! engines)
      (f)
      (finally
        (se/stop-all! engines)
        (is (empty? (dac/violations fake)))
        (dac/stop! fake)))))

(deftest renders-once-per-tick-test
  (testing "one render per cue per tick, whatever the number of projectors (c2)"
    (init! {[0 0] (cue :circle)})
    (trigger! 0 0)
    (doseq [n [0 1 4]]
      (let [renders (atom 0)
            render fs/generate-frames-by-zone]
        (with-redefs [fs/generate-frames-by-zone (fn [& args] (swap! renders inc) (apply render args))]
          (with-engines n
            (fn []
              (dotimes [i 60]
                (show-clock/tick! (System/nanoTime))
                (Thread/sleep 5))
              (is (= 60 @renders) (str n " projector(s)")))))))))

(deftest snapshot-shape-test
  (init! {[0 0] (cue :circle)})
  (testing "nothing playing: a snapshot every tick all the same, so engines send empty frames"
    (let [s (show-clock/tick! 0)]
      (is (= [false []] ((juxt :playing? :cues) s)))
      (is (pos-int? (:tick s)))
      (is (number? (:at-ns s)))
      (is (= {} (:pulses s)) "what tap reactions see: no press yet")
      (is (= {} (:faders s)) "where the faders are, for the meters, even with nothing playing: none learned")))
  (testing "playing"
    (trigger! 0 0)
    (let [s (show-clock/tick! 16666667)
          r (get-in @show-time/!runtime [:runs (get-in (state/get-raw-state) [:playback :active-cues [0 0] :run])])]
      (is (true? (:playing? s)))
      (is (= [[0 0]] (map :cell (:cues s))))
      (is (seq (get-in s [:cues 0 :zone-frames :all])))
      (is (= (:accumulated-beats r) (get-in s [:projector-ctx :accumulated-beats]))
          "projector effects run at the first cue's timing")
      (is (= 120.0 (get-in s [:projector-ctx :bpm])))
      (is (number? (:render-us s)))
      (is (= {[0 0] (:accumulated-beats r)} (:positions s)) "where each cue is, for the screen")
      (is (= (:accumulated-beats r) (show-time/cue-beats s [0 0])))
      (is (nil? (show-time/cue-beats s [1 0])) "not playing"))))

(deftest failing-cue-renders-empty-test
  (init! {[0 0] (cue :circle) [1 0] (cue :square)})
  (trigger! 0 0)
  (trigger! 1 0)
  (let [render fs/generate-frames-by-zone]
    (with-redefs [fs/generate-frames-by-zone (fn [chain & args]
                                               (if (= :square (get-in chain [:items 0 :preset-id]))
                                                 (throw (ex-info "test: render failed" {}))
                                                 (apply render chain args)))]
      (let [cues (into {} (map (juxt :cell :zone-frames)) (:cues (ticks! 2)))]
        (is (= {} (get cues [1 0])) "the failing cue renders nothing")
        (is (seq (get-in cues [[0 0] :all])) "the other still renders")
        (is (contains? (output-errors/snapshot) [:render [1 0]]))))))

(deftest preview-zones-have-blanking-between-cues-test
  (testing "a zone's preview joins its cues as an engine does, with a blanking jump (review 04 #27)"
    (init! {[0 0] (cue :circle) [1 0] (cue :square)})
    (trigger! 0 0)
    (trigger! 1 0)
    (let [s (ticks! 2)
          [a b] (map #(get-in % [:zone-frames :all]) (:cues s))
          zone (get-in @(:preview s) [:zone-frames :all])
          jump (subvec zone (count a) (+ 2 (count a)))]
      (is (= (+ (count a) 2 (count b)) (count zone)))
      (is (every? (fn [[_ _ r g b]] (= 0.0 r g b)) jump) "dark from the end of one to the start of the next")
      (is (= (fs/concatenate-frames [a b]) zone))
      (is (= #{[0 0] [1 0]} (set (keys (:cue-destinations @(:preview s)))))))))

(deftest render-is-timed-test
  (init! {[0 0] (cue :circle)})
  (let [samples (atom 0)]
    (with-redefs [profiler/record-frame-timing! (fn [_] (swap! samples inc))]
      (ticks! 5)
      (is (= 0 @samples) "nothing renders, nothing is timed")
      (trigger! 0 0)
      (ticks! 5 100000000)
      (is (= 5 @samples) "one sample per rendering tick"))))

(deftest preview-is-built-after-the-engines-wake-test
  (testing "the snapshot's preview is never joined on the clock thread: the screen forces it (A3)"
    (init! {[0 0] (cue :circle)})
    (trigger! 0 0)
    (let [seen (atom [])
          publish! show-time/publish!]
      (with-redefs [show-time/publish! (fn [s]
                                         (let [published (publish! s)]
                                           (swap! seen conj (realized? (:preview published)))
                                           published))]
        (let [first-tick (show-clock/tick! 0)
              second-tick (show-clock/tick! 16666667)]
          (is (= [false false] @seen) "not built when the engines wake")
          (is (not-any? #(realized? (:preview %)) [first-tick second-tick]) "nor after: no mirror builds it")
          (is (seq (:points @(:preview second-tick))) "forcing it gives the tick's preview"))))))


;; Phase 3: app state only hears what changes a few times a second

(def ^:private status-paths
  [[:timing :beat-index] [:backend :streaming :frame-stats] [:backend :input :mtc]])

(defn- watch-writes
  "Run (f) and return its app-state writes: each the set of status paths it
   changed, with :other when it changed anything else."
  [f]
  (let [writes (atom [])
        k ::writes
        strip #(reduce (fn [m p] (assoc-in m p nil)) % status-paths)]
    (add-watch (state/get-context-atom) k
               (fn [_ _ old new]
                 (let [o (:cljfx.context/m old)
                       n (:cljfx.context/m new)]
                   (swap! writes conj (cond-> (set (filter #(not= (get-in o %) (get-in n %)) status-paths))
                                        (not= (strip o) (strip n)) (conj :other))))))
    (try (f) (finally (remove-watch (state/get-context-atom) k)))
    @writes))

(deftest app-state-is-quiet-while-a-cue-plays-test
  (testing "2 s of ticks with one cue at 120 BPM write app state at most 14 times, all
            on the status paths (c4; 61 a second before Phase 1, 30 before Phase 3)"
    (init! {[0 0] (cue :circle)})
    (trigger! 0 0)
    (with-redefs [mtc/current (constantly {:state :stopped})]
      (let [writes (watch-writes #(ticks! 121))]
        (is (<= (count writes) 14) (str (count writes) " writes: " (frequencies (mapcat seq writes))))
        (is (not-any? :other writes) "nothing but the status")
        (is (<= 3 (count (filter #(contains? % [:timing :beat-index]) writes)) 4)
            "the beat square moves 4 times in 2 s")))
    (is (= (int (mod (show-time/global-beats) 4.0)) (get-in (state/get-raw-state) [:timing :beat-index])))
    (is (seq (get-in (state/get-raw-state) [:backend :streaming :frame-stats])) "the stats arrived")))

(deftest status-is-sent-only-when-it-changes-test
  (let [sent (atom [])
        stats (atom nil)
        status-at (fn [ms]
                    (reset! sent [])
                    (show-clock/tick! (* ms 1000000))
                    (mapv :status @sent))]
    (with-redefs [events/dispatch! #(swap! sent conj %)
                  profiler/get-recent-stats (fn [_] @stats)
                  mtc/current (constantly {:state :stopped})]
      (testing "app state already holds the status: nothing goes"
        (is (= [] (status-at 0))))
      (testing "the beat changed: it goes once"
        (is (= [1] (map :beat-index (status-at 600))) "1.2 beats at 120 BPM")
        (is (= :show-clock/status (:event/type (first @sent)))))
      (testing "app state hasn't caught up 16 ms later: not again yet"
        (is (= [] (status-at 616))))
      (testing "250 ms after it went, app state still differs (the write was lost): again"
        (is (= [1] (map :beat-index (status-at 850)))))
      (testing "once app state has it, nothing"
        (run! events/event-handler @sent)
        (is (= 1 (get-in (state/get-raw-state) [:timing :beat-index])))
        (is (= [] (status-at 900))))
      (testing "the stats are recomputed at most every 250 ms"
        (reset! stats {:avg-total-us 100 :p95-total-us 120 :max-total-us 150 :avg-base-us 90 :avg-effects-us 10})
        (is (= [] (status-at 950)) "computed at 850, so not due; and still beat 1 (1.9 beats)")
        (is (= [{:avg-latency-us 100 :p95-latency-us 120 :max-latency-us 150 :avg-base-us 90 :avg-effects-us 10}]
               (map :frame-stats (status-at 1100))))))))

(deftest timecode-status-reaches-app-state-test
  (with-redefs [mtc/current (constantly {:state :running :seconds 3599.0 :fps 25
                                         :tc {:h 0 :m 59 :s 59 :f 0 :drop? false}})]
    (ticks! 2))
  (is (= {:state :running :fps 25 :text "00:59:59:00"}
         (dissoc (get-in (state/get-raw-state) [:backend :input :mtc]) :at-ms))))


;; Phase 4: one timing context down the render

(deftest one-state-and-input-read-per-tick-test
  (testing "a tick reads app state and the input store once, whatever the number of
            cues, zones and global effects (04 §7, invariant 2)"
    (init! {[0 0] (cue :circle)
            [1 0] (assoc (cue :square) :destination-zone {:zone-group-id :left})
            [2 0] (cue :triangle)})
    (state/assoc-in-state! [:chains :effect-chains [0 0]]
                           {:active? true
                            :items [{:id (random-uuid) :effect-id :rotation :enabled? true
                                     :params {:angle (sine 0.0 90.0 2.0)}}]})
    (run! #(trigger! % 0) [0 1 2])
    (let [me (Thread/currentThread)
          reads (atom {:app-state 0 :input-store 0})
          counted (fn [k f] (fn [& args]
                              (when (identical? me (Thread/currentThread))
                                (swap! reads update k inc))
                              (apply f args)))
          get-raw-state state/get-raw-state
          get-all-continuous-values input-store/get-all-continuous-values]
      ;; Without the app, events/dispatch! runs an event inline, which reads app
      ;; state itself: the tick's events are left out
      (with-redefs [events/dispatch! (fn [_])]
        (show-clock/tick! 0)
        (with-redefs [state/get-raw-state (counted :app-state get-raw-state)
                      input-store/get-all-continuous-values (counted :input-store get-all-continuous-values)]
          (let [s (show-clock/tick! 16666667)]
            (is (= {:all 2 :left 1} (frequencies (mapcat (comp keys :zone-frames) (:cues s))))
                "3 cues on 2 zones, each zone frame through the global effects")
            (is (= {:app-state 1 :input-store 1} @reads))))))))


;; Tap reactions: every cue's and the effects grid's render sees the presses,
;; calibration never does (plans/2026-11-18-vibe-mode/04 §4)

(defn- punch [lo hi] {:type :envelope :source :hit :min lo :max hi :attack 0.0 :release 1.0 :curve :linear :hold? false})

(defn- width [frame]
  (let [xs (map first (remove (fn [[_ _ r g b]] (= 0.0 r g b)) frame))]
    (- (apply max xs) (apply min xs))))

(deftest render-context-carries-pulses-test
  (init! {[0 0] {:destination-zone {:zone-group-id :all}
                 :items [(preset :circle [:scale {:x-scale (punch 1.0 1.5) :y-scale 1.0}])]}})
  (state/assoc-in-state! [:chains :effect-chains [0 0]]
                         {:active? true
                          :items [{:id (random-uuid) :effect-id :scale :enabled? true
                                   :params {:x-scale (punch 1.0 1.2) :y-scale 1.0}}]})
  (trigger! 0 0)
  (let [seen (atom [])
        render fs/generate-frames-by-zone]
    (with-redefs [events/dispatch! (fn [_])
                  fs/generate-frames-by-zone (fn [chain ctx render-ctx]
                                               (swap! seen conj [(:pulses ctx) (get-in render-ctx [:global-ctx :pulses])])
                                               (render chain ctx render-ctx))]
      (let [rest-width (width (get-in (ticks! 2) [:cues 0 :zone-frames :all]))]
        (state/assoc-in-state! [:playback :pulses :hit] {:id 1 :held? true})
        (let [s (show-clock/tick! (* 2 16666667))
              [cue-pulses global-pulses] (peek @seen)]
          (is (= (show-time/pulse-view @show-time/!runtime) (:pulses s)) "the snapshot has the tick's view")
          (is (= 1 (get-in s [:pulses :hit :count])))
          (is (= [(:pulses s) (:pulses s)] [cue-pulses global-pulses])
              "the cue's own effects and the effects grid's see the same presses")
          (is (< (Math/abs (- (* 1.8 rest-width) (width (get-in s [:cues 0 :zone-frames :all])))) 1e-6)
              "both punch at the press: by half again for the cue's scale, a fifth more for the grid's")
          (let [later (show-clock/tick! (+ (* 2 16666667) 500000000))]
            (is (< (Math/abs (- rest-width (width (get-in later [:cues 0 :zone-frames :all])))) 1e-6)
                "and are back at rest a beat later")))))))

(deftest projector-effects-never-see-pulses-test
  (testing "invariant 4: calibration runs at the first cue's timing with the input values, and no presses"
    (init! {[0 0] (cue :circle)})
    (trigger! 0 0)
    (state/assoc-in-state! [:playback :pulses] {:hit {:id 3 :held? false} :vibe {:id 1} :cue {:id 1}})
    (with-redefs [events/dispatch! (fn [_])]
      (let [s (ticks! 2)]
        (is (= #{:hit :vibe :cue :any} (set (keys (:pulses s)))))
        (is (some? (:projector-ctx s)))
        (is (not (contains? (:projector-ctx s) :pulses)))))))


;; The faders: what the render sees follows the bindings as they are now,
;; never a stored copy (plans/2026-11-18-vibe-mode/05 §11)

(defn- brightness [snapshot]
  (reduce + 0.0 (for [{:keys [zone-frames]} (:cues snapshot) [_ frame] zone-frames [_ _ r g b] frame] (+ r g b))))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) (* 1e-9 (max 1.0 (Math/abs (double a))))))

(defn- with-faders
  "(f) on a new project's state (starter cues, vibes and the Master) with
   macro-map learned, cue [0 0] triggered, and the input mapper on the
   router, as in the app; the input store empty before and after, and the
   ticks' own events left out."
  [macro-map f]
  (state/init-state! (-> (domains/build-initial-state)
                         (templates/apply-starter-cue-chains)
                         (templates/apply-starter-vibes)
                         (templates/apply-starter-effects)
                         (assoc-in [:timing :bpm] 120.0)
                         (assoc-in [:config :input :macro-map] macro-map)
                         (history/mark-clean)))
  (show-time/reset-runtime!)
  (input-store/clear-continuous-values!)
  (mapper/set-dispatch-fn! (fn [_]))
  (mapper/start-mapper!)
  (try
    (with-redefs [events/dispatch! (fn [_])]
      (trigger! 0 0)
      (f))
    (finally
      (mapper/stop-mapper!)
      (mapper/set-dispatch-fn! nil)
      (router/clear-handlers!)
      (input-store/clear-continuous-values!))))

(def ^:private !ticks (atom 0))

(defn- tick-faders
  "The next tick's [brightness faders]."
  []
  ((juxt brightness :faders) (show-clock/tick! (* (swap! !ticks inc) 16666667))))

(defn- cc! [control v] (router/dispatch! (ie/control-change :midi 0 control v)))

(defn- learn! [slot control]
  (state/swap-state! assoc-in [:backend :input :learning-target] {:type :macro :slot slot})
  (events/event-handler {:event/type :input/learn-captured :target-action {:type :macro :slot slot}
                         :input-event (assoc (ie/control-change :midi 0 control 0.5) :store-key [:midi 0 control])}))

(defn- macro-map [] (get-in (state/get-raw-state) [:config :input :macro-map]))

(deftest cleared-fader-leaves-the-master-full-test
  (with-faders {[:midi 0 7] :slider-1}
    (fn []
      (let [[full faders] (tick-faders)]
        (is (pos? full))
        (is (= {} faders) "the fader not moved yet: the Master at its default, full")
        (cc! 7 0.5)
        (is (close? (* 0.5 full) (first (tick-faders))))
        (cc! 7 0.0)
        (is (= [0.0 {:slider-1 0.0}] (tick-faders)) "at 0: dark")
        (events/event-handler {:event/type :vibe/clear-control :slot :slider-1})
        (is (= {} (macro-map)))
        (let [[b faders] (tick-faders)]
          (is (close? full b) "Cleared: the Master at its default, though the fader is still at 0")
          (is (= {} faders)))
        (cc! 7 0.0)
        (is (close? full (first (tick-faders))) "and the control moves nothing now")))))

(deftest relearned-fader-holds-its-default-until-it-moves-test
  (with-faders {[:midi 0 7] :slider-1}
    (fn []
      (let [[full] (tick-faders)]
        (cc! 7 0.0)
        (is (zero? (first (tick-faders))))
        (testing "learned again on CC 9, which hasn't moved: its default, not CC 7's 0"
          (learn! :slider-1 9)
          (is (= {[:midi 0 9] :slider-1} (macro-map)))
          (is (= [true {}] ((juxt #(close? full (first %)) second) (tick-faders)))))
        (testing "until CC 9 moves; CC 7 moves nothing"
          (cc! 9 0.5)
          (is (close? (* 0.5 full) (first (tick-faders))))
          (cc! 7 1.0)
          (is (= [true {:slider-1 0.5}] ((juxt #(close? (* 0.5 full) (first %)) second) (tick-faders)))))
        (testing "learned for real, the catcher and the mapper both listening: the slot is where the control was left, at once"
          (let [{s :state {p :promise} :input/await-learn}
                (input-handlers/handle {:event/type :input/start-learn :target-action {:type :macro :slot :slider-1}
                                        :capture :continuous :state (state/get-raw-state)})]
            (state/reset-state! s)
            (cc! 11 0.1)
            (cc! 11 0.25)
            (events/event-handler {:event/type :input/learn-captured :target-action {:type :macro :slot :slider-1}
                                   :input-event (deref p 1000 nil)})
            (is (= {[:midi 0 11] :slider-1} (macro-map)))
            (is (close? (* 0.25 full) (first (tick-faders))) "no move needed after the learn")))))))

(deftest undone-learn-leaves-the-master-full-test
  (with-faders {}
    (fn []
      (let [[full] (tick-faders)]
        (learn! :slider-1 7)
        (is (= {[:midi 0 7] :slider-1} (macro-map)))
        (cc! 7 0.0)
        (is (zero? (first (tick-faders))))
        (events/event-handler {:event/type :edit/undo})
        (is (empty? (macro-map)) "the learn undone")
        (is (= [true {}] ((juxt #(close? full (first %)) second) (tick-faders)))
            "the Master at its default, though the fader is still at 0")
        (events/event-handler {:event/type :edit/redo})
        (is (= [0.0 {:slider-1 0.0}] (tick-faders)) "redone: it follows the fader again")))))

(deftest new-and-open-give-a-full-master-test
  (let [file "test-output/faders-open.zip"
        saved (fn [macro-map] (persistent/project-files (-> (persistent/new-project-state (domains/build-initial-state))
                                                              (assoc-in [:config :input :macro-map] macro-map))))]
    (.mkdirs (io/file "test-output"))
    (try
      (with-faders {[:midi 0 7] :slider-1}
        (fn []
          (let [[full] (tick-faders)
                dark! (fn []
                        (state/swap-state! assoc-in [:config :input :macro-map] {[:midi 0 7] :slider-1})
                        (cc! 7 0.0)
                        (is (zero? (first (tick-faders)))))]
            (dark!)
            (testing "File > New: the starter Master, full, though the store still holds the old fader at 0"
              (events/event-handler {:event/type :file/new-project :force? true})
              (is (= {} (macro-map)))
              (is (zero? (input-store/get-continuous-value [:midi 0 7] nil)))
              (trigger! 0 0)
              (is (= [true {}] ((juxt #(close? full (first %)) second) (tick-faders)))))
            (testing "File > Open of a project with no fader learned: full"
              (dark!)
              (is (persistent/write-project! file (saved {})))
              (events/event-handler {:event/type :file/open-result :file-path file})
              (is (= {} (macro-map)))
              (trigger! 0 0)
              (is (close? full (first (tick-faders)))))
            (testing "and one that learned the same control follows it at once: that fader is at 0"
              (is (persistent/write-project! file (saved {[:midi 0 7] :slider-1})))
              (events/event-handler {:event/type :file/open-result :file-path file})
              (trigger! 0 0)
              (is (= [0.0 {:slider-1 0.0}] (tick-faders)))))))
      (finally
        (io/delete-file file true)))))


;; The stuck-key guard (plans/2026-11-18-vibe-mode/07 §7)

(deftest watchdog-event-is-sent-once-per-interval-test
  (testing "a key held past :max-hold-secs: a tick sends its release with auto-advance's reports,
            on their memory, at most every 250 ms"
    (state/init-state! (-> (domains/build-initial-state)
                           (templates/apply-starter-cue-chains)
                           (templates/apply-starter-vibes)
                           (assoc-in [:timing :bpm] 120.0)
                           (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)))
    (show-time/reset-runtime!)
    (events/event-handler {:event/type :vibe/press :role :hit :input-time (- (System/currentTimeMillis) 61000)})
    (let [sent (atom [])
          release {:event/type :vibe/release :role :hit :reason :watchdog}
          vibe-reports #(filterv (comp #{"vibe"} namespace :event/type) @sent)]
      (with-redefs [events/dispatch! #(swap! sent conj %)]
        (show-clock/tick! 0)
        (show-clock/tick! 100000000)
        (is (= [release] (vibe-reports)) "two ticks 100 ms apart: one")
        (show-clock/tick! 300000000)
        (is (= [release release] (vibe-reports)) "300 ms after the first: again"))
      (testing "handled twice: let go of once, and the pad says why"
        (run! events/event-handler (vibe-reports))
        (is (nil? (get-in (state/get-raw-state) [:vibe-runtime :held :hit])))
        (is (= {:role :hit :why :stuck-key} (get-in (state/get-raw-state) [:vibe-runtime :problem]))))
      (testing "and then nothing is due"
        (reset! sent [])
        (with-redefs [events/dispatch! #(swap! sent conj %)]
          (show-clock/tick! 400000000))
        (is (empty? (vibe-reports)))))))
