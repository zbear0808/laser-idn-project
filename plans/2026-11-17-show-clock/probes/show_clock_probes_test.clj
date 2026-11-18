;; EVIDENCE for plans/2026-11-17-show-clock/.
;; Not part of the test suite and not on any classpath.
;;
;; Each deftest prints PROBE lines describing what the timing and render code
;; does. They first ran against master f7fdd79 (src last changed in 8edfe59),
;; before the show clock; since Phases 1 and 2 they drive the clock instead of
;; the preview timer, and the same lines show what changed. The plan docs quote
;; both runs. When a phase changes a behaviour, turn the matching probe into a
;; real test asserting the new one.
;;
;; Everything runs on loopback with the fake DAC from test/ and no UI. One
;; grid cue plays at [0 0]: a circle rotated by a 1-beat sine, so every new
;; timing position gives a different frame.
;;
;; c5 slows one handler down with with-redefs (300 ms) so that the race window
;; is wide enough to see; without the sleep it is as long as the handler.
;;
;; Run from the repo root (macOS Apple Silicon; use :win, :linux or :mac elsewhere).
;; The plan folder is the classpath root, so this namespace is probes.show-clock-probes-test:
;;
;;   export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
;;   export PATH="$JAVA_HOME/bin:$PATH"
;;   P=plans/2026-11-17-show-clock
;;   clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
;;       -M:mac-arm:test:probe -d "$P/probes" \
;;       -n probes.show-clock-probes-test 2>&1 | grep PROBE

(ns probes.show-clock-probes-test
  (:require [cljfx.api :as fx]
            [cljfx.event-handler :as event-handler]
            [clojure.test :refer [deftest]]
            [laser-show.animation.cue-timing :as ct]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.shape]
            [laser-show.animation.modulation :as mod]
            [laser-show.animation.show-time :as show-time]
            [laser-show.app]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.dev.reload :as reload]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.services.frame-service :as fs]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))

(defn- say [& xs] (apply println "PROBE" xs) (flush))

(defn- sine [lo hi period] {:type :sine :min lo :max hi :period period})

(defn- preset [preset-id & effects]
  {:id (random-uuid) :type :preset :preset-id preset-id :enabled? true
   :effects (mapv (fn [[effect-id params]]
                    {:id (random-uuid) :effect-id effect-id :enabled? true :params params})
                  effects)})

(def ^:private spinning-circle
  {:destination-zone {:zone-group-id :all}
   :items [(preset :circle [:rotation {:angle (sine 0.0 360.0 1.0)}])]})

(def ^:private busy-cue
  "4 presets with 3 modulated effects each."
  {:destination-zone {:zone-group-id :all}
   :items (vec (for [p [:circle :square :star :spiral]]
                 (preset p
                         [:rotation {:angle (sine 0.0 360.0 1.0)}]
                         [:scale {:x-scale (sine 0.5 1.0 2.0) :y-scale (sine 0.5 1.0 3.0)}]
                         [:hue-shift {:degrees (sine 0.0 180.0 4.0)}])))})

(defn- start-show!
  "App state with cue at [0 0] playing, triggered now (its intent: the show
   clock starts the run at its next tick)."
  [cue]
  (state/init-state! (domains/build-initial-state))
  (show-time/reset-runtime!)
  (state/swap-state! #(-> %
                          (assoc-in [:chains :cue-chains [0 0]] cue)
                          (update :playback ct/activate-cue [0 0] (random-uuid) {}))))

(defn- cue-beats []
  (get (show-time/positions @show-time/!runtime) [0 0]))

(defn- with-engines
  "Put n projectors (zone :all) in app state, start their engines streaming to
   a fake DAC, call (f fake engines), then stop everything."
  [n f]
  (let [fake (dac/start!)
        projectors (into {} (for [i (range 1 (inc n))]
                              [(keyword (str "p" i))
                               {:host "127.0.0.1" :port (:port fake) :service-id i
                                :zone-groups [:all] :enabled? true}]))
        _ (state/swap-state! update :projectors merge projectors)
        engines (mapv (fn [[id p]] (me/create-engine-for-projector id p)) projectors)]
    (try
      (run! se/start! engines)
      (f fake engines)
      (finally
        (se/stop-all! engines)
        (dac/stop! fake)))))

(defn- with-clock [f]
  (show-clock/start!)
  (try (f) (finally (show-clock/stop!))))

(defn- identical-neighbours
  "How many frames repeat the frame before them, per sender."
  [fake]
  (->> (dac/frames fake)
       (group-by :port)
       vals
       (map (fn [fs] (count (filter true? (map = (map :samples fs) (map :samples (rest fs)))))))
       (reduce +)))


;; c1 — what the engine sends: positions per second vs frames per second

(deftest c1-engines-send-each-position-twice
  (start-show! spinning-circle)
  (with-clock
    (fn []
      (let [seen (atom [])
            projector-frame @#'me/projector-frame]
        (with-redefs-fn {#'me/projector-frame (fn [id snapshot] (swap! seen conj (:tick snapshot)) (projector-frame id snapshot))}
          (fn []
            (with-engines 1
              (fn [fake _]
                (Thread/sleep 1000)
                (reset! seen [])
                (let [since (System/nanoTime)]
                  (Thread/sleep 2000)
                  (let [ticks @seen
                        frames (dac/frames fake :since-ns since)]
                    (say "c1 in 2 s: engine frames" (count ticks) "| distinct ticks (each a new cue position)" (count (dedupe ticks))
                         "| frames on the wire" (count frames)
                         "| frames identical to the one before" (count (filter true? (map = (map :samples frames) (map :samples (rest frames))))))))))))))))


;; c2 — how often the same content is rendered, by whom

(deftest c2-renders-per-second
  (start-show! spinning-circle)
  (doseq [n [0 1 2 4]]
    (let [renders (atom {})
          render @#'fs/generate-frames-by-zone]
      (with-redefs-fn {#'fs/generate-frames-by-zone
                       (fn [& args]
                         (let [t (.getName (Thread/currentThread))
                               who (cond (= t "show-clock") :clock
                                         (.startsWith t "idn-streaming") :engines
                                         :else :other)]
                           (swap! renders update who (fnil inc 0)))
                         (apply render args))}
        (fn []
          (with-clock
            (fn []
              (with-engines n
                (fn [_ _]
                  (Thread/sleep 1000)
                  (reset! renders {})
                  (Thread/sleep 2000)
                  (let [r @renders]
                    (say "c2" n "projector(s), renders per second:" (/ (reduce + (vals r)) 2.0)
                         "| show clock" (/ (get r :clock 0) 2.0) "| engines" (/ (get r :engines 0) 2.0))))))))))))


;; c3 — what one render of a cue costs

(defn- render-cost-us [cue n]
  (start-show! cue)
  (let [ctx (ct/get-cue-timing-context (ct/start-timing 0 0.0 {}) 120.0)
        once #(fs/generate-frames-by-zone cue 1000 120.0 0 (assoc ctx :accumulated-beats (* 0.01 %) :effective-beats (* 0.01 %)))]
    (dotimes [i 300] (once i))
    (let [ts (sort (for [i (range n)]
                     (let [t0 (System/nanoTime)]
                       (once i)
                       (/ (- (System/nanoTime) t0) 1000.0))))]
      {:median (nth ts (quot n 2)) :p95 (nth ts (long (* 0.95 n)))
       :points (count (val (first (once 0))))})))

(deftest c3-render-cost
  (doseq [[label cue] [["1 preset, 1 effect" spinning-circle] ["4 presets, 3 effects each" busy-cue]]]
    (let [{:keys [median p95 points]} (render-cost-us cue 1000)]
      (say "c3" label (str "(" points " points):") (format "median %.0f µs, p95 %.0f µs per render" median p95)))))


;; c4 — app-state writes per second while one cue plays (no UI, no engines)

(deftest c4-app-state-writes
  (start-show! spinning-circle)
  (let [writes (atom [])
        k ::c4]
    (add-watch (state/get-context-atom) k
               (fn [_ _ old new]
                 (let [o (:cljfx.context/m old) n (:cljfx.context/m new)]
                   (swap! writes conj (set (keep (fn [p] (when (not= (get-in o p) (get-in n p)) p))
                                                 [[:timing :global-clock] [:playback :active-cues]
                                                  [:playback :positions] [:backend :streaming :current-frame]]))))))
    (try
      (with-clock
        (fn []
          (Thread/sleep 500)
          (reset! writes [])
          (Thread/sleep 2000)))
      (finally (remove-watch (state/get-context-atom) k)))
    (let [w @writes]
      (say "c4 app-state writes per second:" (/ (count w) 2.0)
           "| changing the global clock" (/ (count (filter #(contains? % [:timing :global-clock]) w)) 2.0)
           "| the cue timings or positions" (/ (count (filter #(or (contains? % [:playback :active-cues])
                                                                   (contains? % [:playback :positions])) w)) 2.0)
           "| the preview frame" (/ (count (filter #(contains? % [:backend :streaming :current-frame]) w)) 2.0)))))


;; c5 — an event that is being handled while show time advances writes the
;; whole state back when it finishes (handler slowed to 300 ms). Before the
;; show clock, the preview timer kept the positions in app state, so this put
;; the cue back in time; now they are in the clock's runtime

(def ^:private agent-handler
  (-> events/handle-event-pipeline
      (fx/wrap-co-effects (assoc events/co-effects :fx/context (constantly nil)))
      (fx/wrap-effects events/effects)
      (event-handler/wrap-async {:error-handler (fn [_ e] (say "agent error" e))})))

(deftest c5-a-slow-event-moves-the-cue-back
  (start-show! spinning-circle)
  (with-clock
    (fn []
      (Thread/sleep 300)
      (let [samples (atom [])
            sampling (atom true)
            sampler (future (while @sampling
                              (swap! samples conj (cue-beats))
                              (Thread/sleep 2)))
            handle timing/handle]
        (with-redefs [timing/handle (fn [event] (Thread/sleep 300) (handle event))]
          (agent-handler {:event/type :timing/set-bpm :bpm 120.0 :fx/sync true}))
        (Thread/sleep 200)
        (reset! sampling false)
        @sampler
        (let [s (remove nil? @samples)
              backs (filter neg? (map - (rest s) s))]
          (say "c5 the cue's position went backwards" (count backs) "time(s), by"
               (format "%.2f beats" (double (- (reduce min 0.0 backs))))
               "(at 120 BPM, 1 beat = 500 ms)"))))))


;; c6 — a dev code reload (with nothing to reload) while engines stream:
;; before the show clock it stopped the preview timer, and the output froze

(deftest c6-output-during-a-code-reload
  (start-show! spinning-circle)
  (with-clock
    (fn []
      (with-engines 1
        (fn [fake _]
          (Thread/sleep 800)
          (with-redefs [reload/discover-namespaces (constantly [])]
            (reload/reload-app-code!))
          (let [since (System/nanoTime)]
            (Thread/sleep 1000)
            (let [frames (dac/frames fake :since-ns since)]
              (say "c6 1 s after a code reload: frames sent" (count frames)
                   "| distinct frames" (count (distinct (map :samples frames)))))))))))


;; c7 — how much of a render goes into building modulation contexts
;; (effects.clj builds one per modulated parameter: make-param-resolver,
;; resolve-params-global, the keyframe path). The time is measured inside
;; make-context and make-base-context only, so the merges that build their
;; argument maps are not counted: a lower bound.

(deftest c7-modulation-contexts-per-render
  (doseq [[label cue] [["1 preset, 1 effect" spinning-circle] ["4 presets, 3 effects each" busy-cue]]]
    (start-show! cue)
    (let [calls (atom 0)
          inside-ns (atom 0)
          timed (fn [f] (fn [m]
                          (let [t0 (System/nanoTime)
                                r (f m)]
                            (swap! inside-ns + (- (System/nanoTime) t0))
                            (swap! calls inc)
                            r)))
          ctx (ct/get-cue-timing-context (ct/start-timing 0 0.0 {}) 120.0)
          once #(fs/generate-frames-by-zone cue 1000 120.0 0 (assoc ctx :accumulated-beats (* 0.01 %) :effective-beats (* 0.01 %)))
          n 2000]
      (dotimes [i 300] (once i))
      (let [t0 (System/nanoTime)
            _ (dotimes [i n] (once i))
            plain-ns (- (System/nanoTime) t0)]
        (with-redefs-fn {#'mod/make-context (timed mod/make-context)
                         #'mod/make-base-context (timed mod/make-base-context)}
          (fn []
            (dotimes [i n] (once i))))
        (say "c7" label (format "contexts built per render %.1f | %.2f µs per render inside them | a render %.0f µs | %.0f %%"
                                (/ @calls (double n))
                                (/ @inside-ns n 1000.0)
                                (/ plain-ns n 1000.0)
                                (* 100.0 (/ (double @inside-ns) plain-ns))))))))
