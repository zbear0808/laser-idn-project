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
            [laser-show.routing.compose :as compose]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.zone-patterns :as zone-patterns]
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


;; Zones plan Z3: the routing, worked out once a tick before the render

(defn- two-lasers! []
  (state/swap-state! assoc :projectors {:p1 {:name "Laser L" :host "10.0.0.1" :enabled? true :zone-groups []}
                                        :p2 {:name "Laser R" :host "10.0.0.2" :enabled? true :zone-groups []}}))

(deftest snapshot-carries-routing-test
  (init! {[0 0] (cue :circle)})
  (two-lasers!)
  (testing "nothing playing: the routing all the same, for the preview and the engines"
    (let [s (show-clock/tick! 0)]
      (is (= (rig/routing (rig/routing-inputs (state/get-raw-state) :map/left-right)) (:routing s)))))
  (testing "playing"
    (trigger! 0 0)
    (let [s (show-clock/tick! 16666667)]
      (is (= [[:p1 nil nil] [:p2 nil nil]] (rig/resolve-ref (:routing s) :all)))
      (is (= [[:p1 nil nil]] (rig/resolve-ref (:routing s) :auto/left)))
      (is (= {:index 1 :scan-rate 25000} (select-keys (get-in s [:routing :outputs :p2]) [:index :scan-rate])))
      (is (= :map/left-right (get-in s [:routing :map])) "the map in use: a new show's"))))

(deftest snapshot-names-the-effective-map-test
  (init! {[0 0] (cue :circle)})
  (two-lasers!)
  (trigger! 0 0)
  (let [s (show-clock/tick! 0)]
    (is (= {:map :map/left-right :lanes [:auto/left :auto/right] :overrides {}} (select-keys (:routing s) [:map :lanes :overrides])))
    (is (= [[:p1 nil nil]] (rig/resolve-ref (:routing s) :zone/a)) "A is Left"))
  (state/assoc-in-state! [:zone-map :active] :map/odd-even)
  (let [s (show-clock/tick! 16666667)]
    (is (= :map/odd-even (get-in s [:routing :map])) "the next tick")
    (is (= [:auto/odd :auto/even] (get-in s [:routing :lanes]))))
  (testing "an id that is no map: Left / Right"
    (state/assoc-in-state! [:zone-map :active] :map/gone)
    (is (= :map/left-right (get-in (show-clock/tick! 33333334) [:routing :map])))))

(deftest pending-map-is-effective-once-due-test
  (testing "spec §4, 06 invariant 7: from the tick whose global beats reach it, before any commit lands"
    (init! {[0 0] (cue :circle)})
    (two-lasers!)
    (trigger! 0 0)
    (state/assoc-in-state! [:zone-map :pending] {:id :p :map :map/unison :at-beat 1.0 :bar? false})
    (with-redefs [events/dispatch! (fn [_])]
      (let [before (show-clock/tick! 0)
            just-before (show-clock/tick! 450000000)
            on-the-beat (show-clock/tick! 500000000)]
        (is (= :map/left-right (get-in before [:routing :map])))
        (is (= :map/left-right (get-in just-before [:routing :map])) "0.9 beats: not yet")
        (is (= :map/unison (get-in on-the-beat [:routing :map])) "beat 1: the tick that reaches it already routes by it")
        (is (= [[:p1 nil nil] [:p2 nil nil]] (rig/resolve-ref (:routing on-the-beat) :zone/a)) "A is All under Unison")
        (is (= :map/unison (get-in (show-clock/tick! 750000000) [:routing :map])) "and stays, until the commit")
        (is (= :map/left-right (get-in (state/get-raw-state) [:zone-map :active])) "app state is the commit's to change")))))

(deftest due-switch-is-reported-test
  (testing "06 SQ-4: once due, the switch is reported with the status, again 250 ms on while app state still has it pending"
    (init! {[0 0] (cue :circle)})
    (trigger! 0 0)
    (state/assoc-in-state! [:zone-map :pending] {:id :p1 :map :map/unison :at-beat 1.0 :bar? false})
    (let [sent (atom [])
          due #(filterv (comp #{:show-clock/zone-map-due} :event/type) @sent)]
      (with-redefs [events/dispatch! #(swap! sent conj %)]
        (show-clock/tick! 0)
        (show-clock/tick! 450000000)
        (is (= [] (due)) "not due yet")
        (show-clock/tick! 500000000)
        (is (= [{:event/type :show-clock/zone-map-due :id :p1 :map :map/unison}] (due)) "on the beat")
        (show-clock/tick! 600000000)
        (is (= 1 (count (due))) "100 ms on: not again")
        (show-clock/tick! 760000000)
        (is (= 2 (count (due))) "260 ms on, still pending in app state: again"))
      (testing "handled: the map is in use, and nothing more is due"
        (run! events/event-handler (due))
        (is (= {:active :map/unison :pending nil} (select-keys (:zone-map (state/get-raw-state)) [:active :pending])))
        (reset! sent [])
        (with-redefs [events/dispatch! #(swap! sent conj %)]
          (show-clock/tick! 900000000))
        (is (= [] (due)))))))

(deftest clock-writes-no-zone-map-test
  (testing "06 invariant 6: the show clock never writes [:zone-map]"
    (init! {[0 0] (cue :circle)})
    (trigger! 0 0)
    (state/assoc-in-state! [:zone-map :pending] {:id :p :map :map/unison :at-beat 1.0 :bar? false})
    (let [before (:zone-map (state/get-raw-state))
          writes (atom 0)
          k ::zone-map]
      (add-watch (state/get-context-atom) k
                 (fn [_ _ old new]
                   (when (not= (get-in old [:cljfx.context/m :zone-map]) (get-in new [:cljfx.context/m :zone-map]))
                     (swap! writes inc))))
      (try
        (with-redefs [events/dispatch! (fn [_])]
          (ticks! 60))
        (finally (remove-watch (state/get-context-atom) k)))
      (is (zero? @writes))
      (is (= before (:zone-map (state/get-raw-state)))))))

(deftest routing-is-made-before-the-render-test
  (testing "spec §5's order (Z5 S-3): the routing comes from the tick's state before the render, which finds it made"
    (init! {[0 0] (cue :circle)})
    (two-lasers!)
    (trigger! 0 0)
    (let [seen (atom [])
          render fs/generate-frames-by-zone]
      (with-redefs [fs/generate-frames-by-zone (fn [& args]
                                                 (swap! seen conj (get-in @show-time/!runtime [:routing-cache :routing]))
                                                 (apply render args))]
        (let [s (show-clock/tick! 0)]
          (is (= 1 (count @seen)))
          (is (some? (first @seen)) "made before the render, the first tick after a reset too")
          (is (identical? (:routing s) (first @seen)) "the one the snapshot carries"))))))

(deftest routing-is-reused-while-nothing-changes-test
  (init! {[0 0] (cue :circle)})
  (two-lasers!)
  (trigger! 0 0)
  (let [a (show-clock/tick! 0)
        b (show-clock/tick! 16666667)
        built (atom 0)
        routing rig/routing]
    (is (some? (:routing a)))
    (is (identical? (:routing a) (:routing b)) "the same object, tick after tick")
    (with-redefs [rig/routing (fn [& args] (swap! built inc) (apply routing args))]
      (ticks! 30 33333334))
    (is (zero? @built) "never worked out again while nothing it reads changes")))

(deftest routing-follows-a-group-edit-next-tick-test
  (init! {[0 0] (cue :circle)})
  (two-lasers!)
  (trigger! 0 0)
  (let [before (show-clock/tick! 0)]
    (is (= [] (rig/resolve-ref (:routing before) :graphics)))
    (events/event-handler {:event/type :projectors/toggle-zone-group :projector-id :p2 :zone-group-id :graphics})
    (let [after (show-clock/tick! 16666667)]
      (is (= [[:p2 nil nil]] (rig/resolve-ref (:routing after) :graphics)) "the next tick")
      (is (not (identical? (:routing before) (:routing after)))))
    (testing "and the order"
      (events/event-handler {:event/type :rig/move-projector :projector-id :p2 :target :p1 :side :before})
      (is (= [[:p2 nil nil]] (rig/resolve-ref (:routing (show-clock/tick! 33333334)) :auto/left))))))

(deftest column-default-reaches-its-cues-test
  (testing "zones plan Z4: a cue in a column with a default target draws there, one elsewhere doesn't"
    (init! {[2 0] (cue :circle) [3 0] (cue :square)})
    (state/swap-state! #(-> %
                            (assoc-in [:grid :column-targets] {2 :zone/a})
                            (assoc-in [:config :default-target] :graphics)))
    (trigger! 2 0)
    (trigger! 3 0)
    (let [s (ticks! 2)]
      (is (= {[2 0] [:zone/a] [3 0] [:graphics]}
             (into {} (map (fn [{:keys [cell zone-frames]}] [cell (vec (keys zone-frames))])) (:cues s)))))))

(deftest a-throwing-resolver-keeps-the-last-routing-test
  (init! {[0 0] (cue :circle)})
  (two-lasers!)
  (trigger! 0 0)
  (let [good (:routing (show-clock/tick! 0))]
    (state/assoc-in-state! [:projectors :p2 :exclude-from-all?] true)
    (with-redefs [rig/routing (fn [_] (throw (ex-info "test: resolver failed" {})))]
      (let [s (show-clock/tick! 16666667)]
        (is (identical? good (:routing s)) "the last routing")
        (is (seq (get-in s [:cues 0 :zone-frames :all])) "and the clock renders on")
        (is (re-find #"resolver failed" (get-in (output-errors/snapshot) [[:routing] :message])) "reported under [:routing]")))
    (testing "it clears once the resolver works again, and the edit shows"
      (let [s (show-clock/tick! 33333334)]
        (is (= [[:p1 nil nil]] (rig/resolve-ref (:routing s) :all)))
        (is (nil? (get (output-errors/snapshot) [:routing])))))
    (testing "with no routing yet: an empty one"
      (show-time/reset-runtime!)
      (with-redefs [rig/routing (fn [_] (throw (ex-info "test: resolver failed" {})))]
        (is (= {:map nil :refs {} :outputs {}} (:routing (show-clock/tick! 0))))))))

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
          zone (get-in @(:preview s) [:ref-frames :all])
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
  [[:timing :beat-index] [:backend :streaming :frame-stats] [:backend :input :mtc] [:backend :streaming :cue-warnings]])

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

(defn- heavy-cue
  "A 2,000-point circle (99 + 1 dark × 20) and a square: at 25,000 pts/s,
   about 12 fps."
  []
  {:destination-zone {:zone-group-id :all}
   :items [{:id :big :type :preset :preset-id :circle :enabled? true :effects [] :params {:num-points 1999}}
           {:id :small :type :preset :preset-id :square :enabled? true :effects []}]})

(defn- cue-warnings [] (get-in (state/get-raw-state) [:backend :streaming :cue-warnings]))

(deftest cue-warnings-reach-app-state-test
  (testing "zones plan Z3: a playing cue too heavy for a laser, at most 4 times a second, rounded"
    (init! {[0 0] (heavy-cue) [1 0] (cue :circle)})
    (two-lasers!)
    (trigger! 0 0)
    (trigger! 1 0)
    (with-redefs [mtc/current (constantly {:state :stopped})]
      (let [s (ticks! 2)
            points (compose/cue-points (get-in s [:cues 0 :zone-frames]) (:routing s) :p1)]
        (is (< 1990 points 2200))
        (is (= {[0 0] {:projector :p1 :points (* 10 (Math/round (/ points 10.0))) :fps (Math/round (/ 25000.0 points))
                       :min-fps 20 :scan-rate 25000}}
               (cue-warnings))
            "the heavy cue at its worst laser (both draw it; p1 has the lower index), the light one not at all")
        (testing "gone once the cue stops (Q-Z3-3 A: while it plays)"
          (trigger! 0 0)
          (ticks! 20 50000000)
          (is (nil? (cue-warnings))))))))

(deftest edited-cue-names-its-heaviest-item-test
  (init! {[0 0] (heavy-cue)})
  (two-lasers!)
  (trigger! 0 0)
  (with-redefs [mtc/current (constantly {:state :stopped})]
    (ticks! 2)
    (is (some? (cue-warnings)))
    (is (not (contains? (get (cue-warnings) [0 0]) :item)) "its editor is closed: no item counts")
    (state/assoc-in-state! [:ui :dialogs :cue-chain-editor] {:open? true :col 0 :row 0})
    (ticks! 20 50000000)
    (is (= {:id :big :points 2000} (get-in (cue-warnings) [[0 0] :item])) "open: the circle's 2,000 points")
    (testing "the timeline editor's cue too"
      (state/swap-state! #(-> %
                              (assoc-in [:ui :dialogs :cue-chain-editor] {:open? false})
                              (assoc-in [:ui :dialogs :timeline-cue-editor] {:open? true :col 0 :row 0})))
      (ticks! 20 400000000)
      (is (= :big (get-in (cue-warnings) [[0 0] :item :id]))))
    (testing "another cell's editor: not this cue's"
      (state/assoc-in-state! [:ui :dialogs :timeline-cue-editor] {:open? true :col 1 :row 0})
      (ticks! 20 750000000)
      (is (some? (get (cue-warnings) [0 0])))
      (is (not (contains? (get (cue-warnings) [0 0]) :item))))))

(def ^:private decimate
  {:id (random-uuid) :effect-id :decimate :enabled? true :params {:kpps 25 :fps 30}})

(deftest decimate-put-in-a-calibration-chain-moves-the-warning-test
  (testing "Q-D-2 B: the next warnings count the cue at Decimate's budget on a laser that has one in its calibration chain"
    (init! {[0 0] (heavy-cue)})
    (two-lasers!)
    (trigger! 0 0)
    (with-redefs [mtc/current (constantly {:state :stopped})]
      (ticks! 2)
      (is (= :p1 (get-in (cue-warnings) [[0 0] :projector])) "both draw its 2,000 points under 20 fps; p1 has the lower index")
      (state/assoc-in-state! [:chains :projector-effects :p1 :items] [decimate])
      (ticks! 20 50000000)
      (is (= :p2 (get-in (cue-warnings) [[0 0] :projector])) "p1 draws 833 of them: only p2 is too slow")
      (state/assoc-in-state! [:chains :projector-effects :p2 :items] [decimate])
      (ticks! 20 400000000)
      (is (nil? (cue-warnings)) "both do: no ⚠"))))

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
            [1 0] (assoc (cue :square) :target :left)
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


;; Zones plan Z5: the Map cycle's pick before the routing, and the zone
;; stage's steps in the snapshot (07 §4.4, §8.6)

(defn- ticks-to!
  "Ticks every 100 ms from from-ns (the fake clock) to beats b at 120 BPM
   (500 ms a beat), the last one on b; [the last snapshot, its ns]."
  [from-ns b]
  (let [to-ns (long (* b 500000000))]
    (loop [ns (long from-ns)]
      (let [ns (min ns to-ns)
            s (show-clock/tick! ns)]
        (if (>= ns to-ns) [s to-ns] (recur (+ ns 100000000)))))))

(defn- cycle-cell!
  "A Map cycle in B2 ([1 1]), on: Left / Right, then Odd / Even, a bar each."
  []
  (state/assoc-in-state! [:chains :effect-chains [1 1]]
                         {:active? true :items [(assoc (zone-patterns/cycle-instance) :id :c1)]}))

(deftest map-cycle-sets-the-effective-map-test
  (init! {[0 0] (cue :circle)})
  (two-lasers!)
  (cycle-cell!)
  (state/assoc-in-state! [:zone-map :active] :map/unison)
  (with-redefs [events/dispatch! (fn [_])]
    (let [map-at (fn [[s _]] (get-in s [:routing :map]))
          t0 (show-clock/tick! 0)
          [s1 n1] (ticks-to! 100000000 3.9)
          [s2 n2] (ticks-to! (+ n1 100000000) 4.0)
          [s3 n3] (ticks-to! (+ n2 100000000) 7.9)
          [s4 _] (ticks-to! (+ n3 100000000) 8.0)]
      (is (= :map/left-right (get-in t0 [:routing :map])) "07 §8.6: beat 0, step 0: Left / Right, though Unison is the active map")
      (is (= [:map/left-right :map/odd-even :map/odd-even :map/left-right] (mapv map-at [[s1] [s2] [s3] [s4]]))
          "a bar each, from the tick that reaches it")
      (is (= {:cell [1 1] :map :map/odd-even :paused? false} (get-in s2 [:routing :cycle])) "the snapshot says what the cycle shows")
      (is (= [[:p1 nil nil]] (rig/resolve-ref (:routing s2) :zone/a)) "and the routing follows it: A is Odd")
      (testing "paused, the active map; the snapshot says it is paused"
        (state/assoc-in-state! [:zone-map :cycle-paused?] true)
        (let [s (show-clock/tick! (+ 4100000000 0))]
          (is (= :map/unison (get-in s [:routing :map])))
          (is (true? (get-in s [:routing :cycle :paused?])))))
      (testing "its cell off: no cycle in the snapshot, the routing the cached one"
        (state/assoc-in-state! [:chains :effect-chains [1 1] :active?] false)
        (let [a (show-clock/tick! 4200000000)
              b (show-clock/tick! 4216666667)]
          (is (nil? (get-in a [:routing :cycle])))
          (is (identical? (:routing a) (:routing b))))))))

(deftest snapshot-carries-zone-steps-and-cycle-test
  (init! {[0 0] (assoc (cue :circle) :zone-patterns [(assoc (zone-patterns/preset-instance :alternate :cue) :id :p1)])})
  (two-lasers!)
  (trigger! 0 0)
  (with-redefs [events/dispatch! (fn [_])]
    (let [first-tick (show-clock/tick! 0)
          [s _] (ticks-to! 100000000 1.5)]
      (is (= {:p1 0} (:zone-steps first-tick)) "each pattern's step, for the pattern window's Now")
      (is (= 1 (get-in s [:zone-steps :p1])) "it follows the cue's beats")
      (is (= #{:zone/a :zone/b} (into #{} (comp (mapcat :cues) (mapcat (comp keys :zone-frames))) [first-tick s]))
          "and the cue's picture moves")
      (is (nil? (get-in s [:routing :cycle])) "no cycle on: none in the snapshot")))
  (testing "nothing playing: no steps"
    (show-time/reset-runtime!)
    (state/swap-state! assoc-in [:playback :active-cues] {})
    (is (= {} (:zone-steps (show-clock/tick! 0))))))


;; Zones plan Z5: the pattern pads' switch from the tick that reaches its
;; beat, and the Zone step key's presses

(defn- alternate-cue
  "A cue whose All picture alternates A ⇄ B on the show's beats."
  []
  (assoc (cue :circle) :zone-patterns [(assoc (zone-patterns/preset-instance :alternate :cell) :id :p1)]))

(defn- drawn-keys [snapshot] (set (mapcat (comp keys :zone-frames) (:cues snapshot))))

(deftest pending-pattern-switch-is-effective-once-due-test
  (testing "spec §9: a pad's switch is the patterns' style from the tick whose global beats reach it, before the commit"
    (init! {[0 0] (alternate-cue)})
    (two-lasers!)
    (trigger! 0 0)
    (state/assoc-in-state! [:pattern-pads :pending] {:id :p :style :off :at-beat 1.0 :bar? false})
    (with-redefs [events/dispatch! (fn [_])]
      (let [before (show-clock/tick! 0)
            [just-before n] (ticks-to! 100000000 0.9)
            [on-the-beat _] (ticks-to! (+ n 100000000) 1.0)]
        (is (= #{:zone/a} (drawn-keys before)) "As set: Alternate, step 0")
        (is (= #{:zone/a} (drawn-keys just-before)) "0.9 beats: not yet")
        (is (= #{:all} (drawn-keys on-the-beat)) "beat 1: Off, the picture where it was drawn")
        (is (nil? (get-in (state/get-raw-state) [:pattern-pads :style])) "app state is the commit's to change")))))

(deftest pattern-due-is-reported-test
  (init! {[0 0] (alternate-cue)})
  (trigger! 0 0)
  (state/assoc-in-state! [:pattern-pads :pending] {:id :p1 :style :chase :at-beat 1.0 :bar? false})
  (let [sent (atom [])
        due #(filterv (comp #{:show-clock/pattern-due} :event/type) @sent)]
    (with-redefs [events/dispatch! #(swap! sent conj %)]
      (show-clock/tick! 0)
      (show-clock/tick! 450000000)
      (is (= [] (due)) "not due yet")
      (show-clock/tick! 500000000)
      (is (= [{:event/type :show-clock/pattern-due :id :p1 :style :chase}] (due)) "on the beat")
      (show-clock/tick! 600000000)
      (is (= 1 (count (due))) "100 ms on: not again")
      (show-clock/tick! 760000000)
      (is (= 2 (count (due))) "260 ms on, still waiting in app state: again"))
    (testing "handled: the pads' style is it, and nothing more is due"
      (run! events/event-handler (due))
      (is (= {:style :chase :pending nil} (select-keys (:pattern-pads (state/get-raw-state)) [:style :pending])))
      (reset! sent [])
      (with-redefs [events/dispatch! #(swap! sent conj %)]
        (show-clock/tick! 900000000))
      (is (= [] (due))))))

(deftest zone-step-press-steps-at-the-next-tick-test
  (init! {[0 0] (assoc (cue :circle) :zone-patterns [(assoc (zone-patterns/preset-instance :alternate :cue) :id :p1 :params
                                                            (assoc (:params (zone-patterns/preset-instance :alternate :cue)) :step-on :zone-step))])})
  (trigger! 0 0)
  (with-redefs [events/dispatch! (fn [_])]
    (let [first-tick (show-clock/tick! 0)]
      (is (= {:p1 0} (:zone-steps first-tick)) "no press yet: step 0")
      (events/event-handler {:event/type :pattern-pads/step :src :pad})
      (is (= {:id 1} (get-in (state/get-raw-state) [:playback :pulses :zone-step])) "the press is in app state")
      (let [next-tick (show-clock/tick! 16666667)]
        (is (= {:p1 1} (:zone-steps next-tick)) "the next tick steps it")
        (is (= #{:zone/b} (drawn-keys next-tick)))
        (is (= 1 (get-in next-tick [:pulses :zone-step :count])) "the clock stamped it as any source")))))


;; Zones plan Z6: a step grid on the clock (08 §8)

(deftest zone-steps-follow-a-grid-test
  (let [grid (-> (zone-patterns/preset-instance :steps :cue)
                 (assoc :id :g1)
                 (assoc-in [:params :steps] [[:zone/a] [] [:zone/b] [:zone/a :zone/b]]))]
    (init! {[0 0] (assoc (cue :circle) :zone-patterns [grid])})
    (two-lasers!)
    (trigger! 0 0)
    (with-redefs [events/dispatch! (fn [_])]
      (let [first-tick (show-clock/tick! 0)
            [half n1] (ticks-to! 100000000 0.5)
            [one n2] (ticks-to! (+ n1 100000000) 1.0)
            [one-half n3] (ticks-to! (+ n2 100000000) 1.5)
            [two n4] (ticks-to! (+ n3 100000000) 2.0)]
        (testing "the snapshot has the grid's step, 2 a beat from the cue's start (Z6-Q1 A)"
          (is (= {:g1 0} (:zone-steps first-tick)))
          (is (= [1 2 3 4] (mapv #(get-in % [:zone-steps :g1]) [half one one-half two]))
              "the step goes on: the playhead is step mod 4"))
        (testing "each step lights its own zones; a rest lights none"
          (is (= #{:zone/a} (drawn-keys first-tick)))
          (is (= #{} (drawn-keys half)) "step 1, a rest: nothing drawn")
          (is (= #{:zone/b} (drawn-keys one)))
          (is (= #{[:zone/a :zone/b]} (drawn-keys one-half)) "A and B: one frame under both")
          (is (= #{:zone/a} (drawn-keys two)) "step 4 is step 0 again"))
        (testing "a grid edited while it plays shows from the next tick (08 invariant 8)"
          (events/event-handler {:event/type :step-grid/toggle
                                 :target {:domain :cue-zone-patterns :entity-key [0 0] :effect-path [0]}
                                 :row :zone/b :step 0})
          (is (= #{[:zone/a :zone/b]} (drawn-keys (show-clock/tick! (+ n4 16666667))))))))))
