;; EVIDENCE for plans/2026-11-20-state-integrity/01-engine-snapshot.md.
;; Not part of the test suite and not on any classpath.
;;
;; Each deftest prints PROBE lines describing what the engines do TODAY
;; (written at master 3229c95, re-run at cc9e000 after Decimate landed and
;; at fe7d554 after the output drivers, zones Z4-Z6 and Decimate's
;; follow-ups; e1i added there, the citations below updated): a
;; projector's frame takes the tick's cues and routing from the show clock's
;; snapshot, and its calibration, safety zone, test pattern and BLACKOUT from
;; app state as it is when the engine gets to them (backend/multi_engine.clj
;; :85 and :177). The plan quotes the lines.
;;
;; - e1: one frame, several states. A tick's snapshot is taken; then app
;;   state changes (calibration, safety, BLACKOUT, the test pattern); then the
;;   engine makes that tick's frame. Also an edit landing while one frame is
;;   being made, and an edit landing between two lasers' frames of one tick.
;;   e1g does it once through show-clock/tick! itself. e1h adds Decimate to a
;;   laser's calibration after the tick: since d6fbb15 the frame's :fit
;;   record (the card, LOW FPS) counts what the calibration left. e1i removes
;;   it after the tick: the tick's routing still carries its budget
;;   (Q-D-3 B), which decides how the record is metered.
;; - e2: latency. (a) d, how long before the tick is published the clock read
;;   app state (show-clock/tick! :263 to the snapshot's :at-ns, :287), for an
;;   idle tick, one cue, and four cues with a global effect on three lasers;
;;   (b) with the clock thread and three engines on the fake DAC, how long
;;   after the publish the engines read app state, and how long after it
;;   their frames went out (the engine's :tick-to-send-us).
;; - e3: cost. What the engines do per frame today that moves (the two state
;;   reads, safety/normalize), what stays (the calibration chain, with and
;;   without Decimate), what the snapshot would add per tick (references and
;;   a small map: a stand-in for the plan's NEW multi-engine/engine-inputs
;;   until it exists, then the real function), and what the routing costs
;;   per change (it already normalizes every laser's zone).
;; - e4: how many times one frame reads app state, per kind of frame.
;;
;; The same file measures before the plan (cc9e000, fe7d554) and after it: once
;; multi-engine/engine-inputs exists, snapshot adds :engine-inputs as the
;; clock will, e1 should print "follows the tick" for every case, e1e reach
;; 0.80, e1h keep the guard's 1,800 points and record, e1i keep Decimate's
;; [833 833 833] for the tick, e2b no reads on the
;; engine threads, e4 0 reads per frame.
;;
;; Timings are medians (p95) of 5,000 calls after 3,000 warm-up calls, on
;; whatever else the machine is doing: compare them with each other, not
;; with another machine. e3 prints the load average.
;;
;; No UI, no hardware. e2 (b) streams to the fake DAC on loopback.
;;
;; Run from the repo root (macOS Apple Silicon; use :win, :linux or :mac elsewhere).
;; The plan folder is the classpath root, so this namespace is
;; probes.engine-snapshot-probes-test:
;;
;;   export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
;;   export PATH="$JAVA_HOME/bin:$PATH"
;;   P=plans/2026-11-20-state-integrity
;;   clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
;;       -M:mac-arm:test:probe -d "$P/probes" \
;;       -n probes.engine-snapshot-probes-test 2>&1 | grep PROBE

(ns probes.engine-snapshot-probes-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.calibration]
            [laser-show.animation.effects.shape]
            [laser-show.animation.fit-guard :as fit-guard]
            [laser-show.animation.safety :as safety]
            [laser-show.animation.show-time :as show-time]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.projector :as projector-handlers]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.routing.rig :as rig]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))

(defn- say [& xs] (apply println "PROBE" xs) (flush))

(defn- percentile [sorted p]
  (nth sorted (min (dec (count sorted)) (long (* p (count sorted))))))

(defn- timed
  "[median p95] µs of (f), after a warm-up."
  [f]
  (dotimes [_ 3000] (f))
  (let [ns (vec (sort (for [_ (range 5000)]
                        (let [t0 (System/nanoTime)]
                          (f)
                          (- (System/nanoTime) t0)))))]
    [(/ (percentile ns 0.5) 1000.0) (/ (percentile ns 0.95) 1000.0)]))

(defn- us [[median p95]] (format "%.2f µs (p95 %.2f)" (double median) (double p95)))

(defn- dist
  "median / p95 / max of xs (µs), or \"none\"."
  [xs]
  (if (empty? xs)
    "none"
    (let [s (vec (sort xs))]
      (format "median %.1f µs, p95 %.1f, max %.1f (n=%d)"
              (double (percentile s 0.5)) (double (percentile s 0.95)) (double (peek s)) (count s)))))

(defn- f2 [x] (format "%.2f" (double x)))

(defn- lit [frame] (filterv (fn [[_ _ r g b]] (or (> r 1e-6) (> g 1e-6) (> b 1e-6))) frame))

(defn- reach
  "How far right the frame's light goes: the largest x of a lit point, 0 for none."
  [frame]
  (reduce max 0.0 (map #(double (first %)) (lit frame))))

(defn- pin
  "A corner pin with its corners at ±s: it scales every point by s."
  [s]
  {:id #uuid "00000000-0000-0000-0000-0000000000a1" :effect-id :corner-pin :enabled? true
   :params {:tl-x (- s) :tl-y s :tr-x s :tr-y s :bl-x (- s) :bl-y (- s) :br-x s :br-y (- s)}})

(defn- box [h] {:limit [(- h) (- h) h h]})

(def ^:private line-to-08
  "A lit line from the middle to x = 0.8."
  [[0.0 0.0 0.0 0.0 0.0] [0.8 0.0 1.0 1.0 1.0]])

(defn- circle
  "n lit points on a circle of radius r around (cx, 0)."
  [n cx r]
  (mapv (fn [i] (let [a (/ (* 2.0 Math/PI i) n)]
                  [(+ cx (* r (Math/cos a))) (* r (Math/sin a)) 0.0 1.0 0.0]))
        (range n)))

(defn- laser [n & {:keys [host port]}]
  (cond-> {:name (str "Laser " n) :host (or host (str "10.0.0." n)) :service-id n
           :enabled? true :zone-groups [] :exclude-from-all? false}
    port (assoc :port port)))

(defn- init! [projectors]
  (state/init-state! (-> (domains/build-initial-state)
                         (assoc-in [:timing :bpm] 120.0)
                         (assoc :projectors projectors)))
  (show-time/reset-runtime!)
  (output-errors/clear!))

(defn- planned-fn
  "A function the plan adds, by its symbol, or nil before it exists."
  [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

(defn- snapshot
  "A snapshot as the show clock publishes it (show-clock/tick! :286-294),
   its routing from app state as it is now, and after the plan its NEW
   :engine-inputs too (multi-engine/engine-inputs), so the same probe runs
   before and after. :at-ns a minute ahead, so no frame here is ever stale."
  [cues & {:keys [playing?] :or {playing? true}}]
  (cond-> {:tick 1 :at-ns (+ (System/nanoTime) 60000000000) :playing? playing? :cues cues :projector-ctx nil
           :routing (rig/routing (rig/routing-inputs (state/get-raw-state) nil)) :preview (delay nil)}
    (planned-fn 'laser-show.backend.multi-engine/engine-inputs)
    (assoc :engine-inputs ((planned-fn 'laser-show.backend.multi-engine/engine-inputs)
                           (state/get-raw-state) (System/currentTimeMillis)))))

(defn- follows [before after]
  (if (= before after) "the tick (no change)" "the edit"))


;; e1: one frame, several states

(defn- e1a []
  (init! {:p (laser 5)})
  (state/assoc-in-state! [:chains :projector-effects :p :items] [(pin 1.0)])
  (let [snap (snapshot [{:cell [0 0] :zone-frames {:all line-to-08}}])
        before (#'me/projector-frame :p snap)]
    (state/assoc-in-state! [:chains :projector-effects :p :items] [(pin 0.5)])
    (let [after (#'me/projector-frame :p snap)]
      (say "e1a calibration edited after the tick (corner pin 1.0 -> 0.5): the tick's frame is lit to x ="
           (f2 (reach before)) "before the edit and" (f2 (reach after)) "after it: it follows" (follows before after)))))

(defn- e1b []
  (init! {:p (laser 5)})
  (state/assoc-in-state! [:projectors :p :safety] (box 0.9))
  (let [left (circle 64 0.0 0.9)
        cues [{:cell [0 0] :zone-frames {:auto/left left}}]
        snap (snapshot cues)
        before (#'me/projector-frame :p snap)]
    (state/assoc-in-state! [:projectors :p :safety] (box 0.3))
    (let [after (#'me/projector-frame :p snap)
          next-tick (#'me/projector-frame :p (snapshot cues))]
      (say "e1b safety narrowed after the tick (limit ±0.9 -> ±0.3), a cue on Left of one laser: the tick's routing fits Left to"
           (pr-str (mapv f2 (get-in snap [:routing :outputs :p :regions :L]))) "(the ±0.9 box); its frame has"
           (count (lit before)) "lit points before the edit and" (count (lit after))
           "after it (fitted to the old box, clipped to the new one); the next tick fits"
           (pr-str (mapv f2 (get-in (snapshot cues) [:routing :outputs :p :regions :L]))) "and keeps"
           (count (lit next-tick)) "lit"))))

(defn- e1c []
  (init! {:p (laser 5)})
  (state/swap-state! #(-> % (assoc-in [:projector-ui :calibrating-projector-id] :p)
                          (assoc-in [:vibe-runtime :blackout?] false)))
  (let [snap (snapshot [] :playing? false)
        before (#'me/projector-frame :p snap)]
    (state/assoc-in-state! [:vibe-runtime :blackout?] true)
    (let [after (#'me/projector-frame :p snap)]
      (say "e1c BLACKOUT pressed after the tick, test pattern on: the tick's frame has" (count before)
           "points before and" (count after) "after: it follows" (follows before after)))))

(defn- e1d []
  (init! {:p (laser 5)})
  (let [snap (snapshot [{:cell [0 0] :zone-frames {:all line-to-08}}])
        before (#'me/projector-frame :p snap)]
    (state/assoc-in-state! [:projector-ui :calibrating-projector-id] :p)
    (let [after (#'me/projector-frame :p snap)]
      (say "e1d test pattern turned on after the tick: the tick's frame has" (count before) "points (the cue) before and"
           (count after) "after (the pattern): it follows" (follows before after)))))

(defn- e1e []
  (init! {:p (laser 5)})
  (state/swap-state! #(-> % (assoc-in [:projectors :p :safety] (box 0.9))
                          (assoc-in [:chains :projector-effects :p :items] [(pin 1.0)])))
  (let [snap (snapshot [{:cell [0 0] :zone-frames {:all line-to-08}}])
        guard fit-guard/guard
        edit! #(state/swap-state! (fn [s] (-> s (assoc-in [:projectors :p :safety] (box 0.3))
                                              (assoc-in [:chains :projector-effects :p :items] [(pin 0.5)]))))
        mixed (with-redefs [fit-guard/guard (fn [& args] (edit!) (apply guard args))]
                (#'me/projector-frame :p snap))
        all-new (#'me/projector-frame :p (snapshot [{:cell [0 0] :zone-frames {:all line-to-08}}]))]
    (say "e1e calibration and safety edited while the engine composes (inside fit-guard/guard): the frame is lit to x ="
         (f2 (reach mixed)) "= the line's 0.8 through the NEW pin 0.5, clipped by the OLD limit 0.9;"
         "all old would be 0.80, all new" (f2 (reach all-new)))))

(defn- e1f []
  (init! {:a (laser 5) :b (laser 6)})
  (state/swap-state! #(-> % (assoc-in [:chains :projector-effects :a :items] [(pin 1.0)])
                          (assoc-in [:chains :projector-effects :b :items] [(pin 1.0)])))
  (let [snap (snapshot [{:cell [0 0] :zone-frames {:all line-to-08}}])
        a (#'me/projector-frame :a snap)]
    (state/swap-state! #(-> % (assoc-in [:chains :projector-effects :a :items] [(pin 0.5)])
                            (assoc-in [:chains :projector-effects :b :items] [(pin 0.5)])))
    (let [b (#'me/projector-frame :b snap)]
      (say "e1f two lasers, one tick, both corner pins edited (1.0 -> 0.5) between their frames: :a is lit to"
           (f2 (reach a)) "and :b to" (f2 (reach b))))))

(defn- e1g []
  (init! {:p (laser 5)})
  (state/swap-state! #(-> % (assoc-in [:chains :cue-chains [0 0]]
                                      {:destination-zone {:zone-group-id :all}
                                       :items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true :effects []}]})
                          (assoc-in [:chains :projector-effects :p :items] [(pin 1.0)])))
  (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
  (with-redefs [events/dispatch! (fn [_])]
    (show-clock/tick! 0)
    (let [s (show-clock/tick! 16666667)
          before (#'me/projector-frame :p s)]
      (state/assoc-in-state! [:chains :projector-effects :p :items] [(pin 0.5)])
      (let [after (#'me/projector-frame :p s)]
        (say "e1g through show-clock/tick! (tick" (:tick s) "), a circle cue, corner pin 1.0 -> 0.5 after the tick: the tick's frame reaches x ="
             (f2 (reach before)) "before and" (f2 (reach after)) "after: it follows" (follows before after)))))
  (show-time/reset-runtime!))

(def ^:private decimate
  "Decimate (zones plan 12) at its defaults: a budget of 833 points (25,000 / 30)."
  {:id #uuid "00000000-0000-0000-0000-0000000000d1" :effect-id :decimate :enabled? true :params {:kpps 25 :fps 30}})

(defn- e1h []
  (init! {:p (assoc (laser 5) :scan-rate 25000)})
  (let [cues [{:cell [0 0] :zone-frames {:all (circle 3000 0.0 0.5)}}]
        points-and-record (fn [frame] [(count frame) (:points (:fit (meta frame)))])
        snap (snapshot cues)
        before (#'me/projector-frame :p snap)]
    (state/assoc-in-state! [:chains :projector-effects :p :items] [decimate])
    (let [after (#'me/projector-frame :p snap)
          next-tick (#'me/projector-frame :p (snapshot cues))]
      (say "e1h Decimate (25 kpps, 30 fps) added to the calibration after the tick, a 3,000-point cue"
           (str "(Decimate " (if (effects/get-effect :decimate) "registered" "NOT registered") "):")
           "the tick's [points sent, record's :points] are" (pr-str (points-and-record before)) "before the edit and"
           (pr-str (points-and-record after)) "after: it follows" (follows before after)
           "| the next tick:" (pr-str (points-and-record next-tick))))))

(defn- e1i
  "Added at fe7d554: since Q-D-3 B (bfcaeab) the tick's routing carries the
   Decimate budget of each laser's chain (rig/routing :decimate-budget, from
   the tick's [:chains :projector-effects]), and show-frame meters what the
   laser draws (fit-guard/drawn) when the routing has one, while the chain it
   runs is read #2's."
  []
  (init! {:p (assoc (laser 5) :scan-rate 25000)})
  (state/assoc-in-state! [:chains :projector-effects :p :items] [decimate])
  (let [cues [{:cell [0 0] :zone-frames {:all (circle 3000 0.0 0.5)}}]
        counts (fn [frame] (let [fit (:fit (meta frame))] [(count frame) (:points fit) (:effect-points fit)]))
        snap (snapshot cues)
        before (#'me/projector-frame :p snap)]
    (state/assoc-in-state! [:chains :projector-effects :p :items] [])
    (let [after (#'me/projector-frame :p snap)
          next-tick (#'me/projector-frame :p (snapshot cues))]
      (say "e1i Decimate (25 kpps, 30 fps) removed from the calibration after the tick, a 3,000-point cue: the tick's routing has"
           ":decimate-budget" (get-in snap [:routing :outputs :p :decimate-budget])
           "| the tick's [points sent, record's :points, :effect-points] are" (pr-str (counts before)) "before the edit and"
           (pr-str (counts after)) "after (the edit's chain, the tick's meter rule) | the next tick:" (pr-str (counts next-tick))))))

(deftest e1-a-frame-mixes-states
  (e1a) (e1b) (e1c) (e1d) (e1e) (e1f) (e1g) (e1h) (e1i))


;; e2: latency

(defn- cue [preset-id zone]
  {:destination-zone {:zone-group-id :all}
   :items [(cond-> {:id (random-uuid) :type :preset :preset-id preset-id :enabled? true
                    :effects [{:id (random-uuid) :effect-id :rotation :enabled? true
                               :params {:angle {:type :sine :min 0.0 :max 360.0 :period 1.0}}}]}
             zone (assoc :zone-group-id zone))]})

(defn- read-to-publish
  "d of n ticks of the current state, on a fake clock 16.67 ms apart: µs from
   the tick's read of app state to its snapshot's :at-ns."
  [n]
  (let [read-at (volatile! 0)
        get-raw-state state/get-raw-state
        publish! show-time/publish!
        ds (atom [])]
    (with-redefs [events/dispatch! (fn [_])
                  state/get-raw-state (fn [] (let [s (get-raw-state)] (vreset! read-at (System/nanoTime)) s))
                  show-time/publish! (fn [snap] (swap! ds conj (/ (- (long (:at-ns snap)) (long @read-at)) 1000.0))
                                       (publish! snap))]
      (dotimes [i n] (show-clock/tick! (* i 16666667))))
    (subvec @ds (min 50 (count @ds)))))

(deftest e2-latency
  (say "e2 the tick period: show-clock TICK_HZ" show-clock/TICK_HZ "=" (f2 (/ 1000.0 show-clock/TICK_HZ)) "ms;"
       "an engine paced by the clock sends each new tick, and the newest again after"
       se/KEEPALIVE_INTERVALS "intervals without one;" "STALE_MS" show-time/STALE_MS)
  (doseq [[label projectors chains effects-grid triggers]
          [["idle (nothing playing), 1 laser" {:p1 (laser 1)} {} nil []]
           ["1 cue (circle + rotation), 1 laser" {:p1 (laser 1)} {[0 0] (cue :circle nil)} nil [[0 0]]]
           ["4 cues (All, Left, Right, All) + a global effect, 3 lasers"
            {:p1 (laser 1) :p2 (laser 2) :p3 (laser 3)}
            {[0 0] (cue :circle nil) [1 0] (cue :square :auto/left) [2 0] (cue :wave :auto/right) [3 0] (cue :triangle nil)}
            {[0 3] {:active? true :items [{:id (random-uuid) :effect-id :rotation :enabled? true
                                           :params {:angle {:type :sine :min 0.0 :max 90.0 :period 2.0}}}]}}
            [[0 0] [1 0] [2 0] [3 0]]]]]
    (init! projectors)
    (state/swap-state! #(cond-> (assoc-in % [:chains :cue-chains] chains)
                          effects-grid (assoc-in [:chains :effect-chains] effects-grid)))
    (doseq [[c r] triggers] (events/event-handler {:event/type :grid/trigger-cell :col c :row r}))
    (say "e2a d, the clock's read of app state -> its publish," label ":" (dist (read-to-publish 650))))
  (show-time/reset-runtime!)
  (let [fake (dac/start!)
        projectors {:p1 (laser 1 :host "127.0.0.1" :port (:port fake))
                    :p2 (laser 2 :host "127.0.0.1" :port (:port fake))
                    :p3 (laser 3 :host "127.0.0.1" :port (:port fake))}]
    (try
      (init! projectors)
      (state/swap-state! #(-> % (assoc-in [:chains :cue-chains [0 0]] (cue :circle nil))
                              (assoc-in [:chains :projector-effects]
                                        (into {} (for [id (keys projectors)]
                                                   [id {:items projector-handlers/DEFAULT_PROJECTOR_EFFECTS}])))))
      (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
      (let [engines (mapv (fn [[id p]] (me/create-engine-for-projector id p)) projectors)
            reads (atom [])
            get-raw-state state/get-raw-state]
        (try
          (run! se/start! engines)
          (show-clock/start!)
          (Thread/sleep 1000)
          (with-redefs [state/get-raw-state
                        (fn []
                          (let [s (get-raw-state)
                                t (System/nanoTime)
                                thread (.getName (Thread/currentThread))]
                            (when (str/starts-with? thread "idn-streaming-")
                              (when-let [at (:at-ns (show-time/latest))]
                                (swap! reads conj [thread (/ (- t (long at)) 1000.0)])))
                            s))]
            (Thread/sleep 2000))
          (let [by-thread (group-by first @reads)
                firsts (mapcat (fn [[_ xs]] (map second (take-nth 2 xs))) by-thread)
                seconds (mapcat (fn [[_ xs]] (map second (take-nth 2 (rest xs)))) by-thread)]
            (say "e2b 3 engines on the fake DAC, the clock thread running, one cue, the default calibration: in 2 s the engines read app state"
                 (count @reads) "times (" (pr-str (update-vals by-thread count)) ")")
            (say "e2b the engines' first read of a frame (multi_engine.clj:177), after the tick's publish:" (dist firsts))
            (say "e2b their second read (apply-projector-effects, :85), after the publish:" (dist seconds))
            (say "e2b tick-to-send (the engine's EMA, publish -> its frame went out), per engine:"
                 (pr-str (mapv #(some-> (:tick-to-send-us (se/get-stats %)) f2) engines)) "µs"))
          (finally
            (show-clock/stop!)
            (se/stop-all! engines))))
      (finally
        (dac/stop! fake)
        (show-time/reset-runtime!)))))


;; e3: cost

(deftest e3-cost
  (init! {:p (laser 5)})
  (let [zone {:limit [-0.9 -0.8 0.9 0.95] :blocked [[-0.2 -1.0 0.2 -0.4] [0.5 0.5 0.7 0.7]]}
        _ (state/swap-state! #(-> % (assoc-in [:projectors :p :safety] zone)
                                  (assoc-in [:chains :projector-effects :p :items] projector-handlers/DEFAULT_PROJECTOR_EFFECTS)))
        s (state/get-raw-state)
        frame (circle 1000 0.0 0.5)]
    (say "e3 per frame today, per laser: (state/get-raw-state) + get-in, as :85 and :177 do:"
         (us (timed #(get-in (state/get-raw-state) [:chains :projector-effects :p :items]))))
    (say "e3 per frame today, per laser: safety/normalize of a zone with 2 blocked areas (guard-safety :118, then again inside apply-safety :276):"
         (us (timed #(safety/normalize zone))))
    (say "e3 the same for a limit with no blocked area:" (us (timed #(safety/normalize {:limit [-0.9 -0.8 0.9 0.95]})))
         "| for no zone (nil, the default):" (us (timed #(safety/normalize nil))))
    (say "e3 per frame, unchanged by the plan: the default calibration (rgb-curves + corner-pin) on 1,000 points:"
         (us (timed #(effects/apply-effect-chain frame {:effects projector-handlers/DEFAULT_PROJECTOR_EFFECTS} nil))))
    (say "e3 the same chain on a 2-point frame (what building it costs: three 256-entry LUTs, eight resolvers):"
         (us (timed #(effects/apply-effect-chain [[0.0 0.0 1.0 1.0 1.0] [0.1 0.0 1.0 1.0 1.0]]
                                                 {:effects projector-handlers/DEFAULT_PROJECTOR_EFFECTS} nil))))
    (when (effects/get-effect :decimate)
      (let [guarded (circle 1800 0.0 0.5)
            chain {:effects (conj projector-handlers/DEFAULT_PROJECTOR_EFFECTS decimate)}]
        (say "e3 per frame, unchanged by the plan: the default calibration with Decimate last, on the guard's 1,800 points (1800 ->"
             (count (effects/apply-effect-chain guarded chain nil)) "points):"
             (us (timed #(effects/apply-effect-chain guarded chain nil)))
             "| without Decimate:" (us (timed #(effects/apply-effect-chain guarded {:effects projector-handlers/DEFAULT_PROJECTOR_EFFECTS} nil))))))
    (say "e3 safety/apply-safety of that zone on 1,000 points:" (us (timed #(safety/apply-safety zone frame))))
    (say "e3 per tick after (NEW multi-engine/engine-inputs on the clock thread: references and one small map;"
         (if (planned-fn 'laser-show.backend.multi-engine/engine-inputs) "the real function):" "a stand-in, the same map):")
         (us (timed (if-let [f (planned-fn 'laser-show.backend.multi-engine/engine-inputs)]
                      #(f s (System/currentTimeMillis))
                      #(hash-map :projectors (:projectors s)
                                 :projector-effects (get-in s [:chains :projector-effects])
                                 :calibrating (get-in s [:projector-ui :calibrating-projector-id])
                                 :brightness (get-in s [:projector-ui :calibration-brightness] 0.1)
                                 :identify (get-in s [:projector-ui :identify])
                                 :blackout? (boolean (get-in s [:vibe-runtime :blackout?]))
                                 :wall-ms (System/currentTimeMillis))))))
    (doseq [n [1 3 8]]
      (let [ps (into {} (for [k (range 1 (inc n))] [(keyword (str "p" k)) (assoc (laser k) :safety zone)]))
            st (assoc s :projectors ps)
            inputs (rig/routing-inputs st nil)]
        (say "e3 per change: rig/routing with" n (if (= 1 n) "laser" "lasers")
             "(it already normalizes each zone, in halves, rig.clj:395-402):" (us (timed #(rig/routing inputs))))))
    (say "e3 per tick while nothing changed: rig/cached-routing's identity check:"
         (let [inputs (rig/routing-inputs s nil)
               [cache] (rig/cached-routing nil inputs)]
           (us (timed #(rig/cached-routing cache inputs)))))
    (say "e3 the whole frame today, per laser (compose, guard, calibration, safety; 1,000 points):"
         (let [snap (snapshot [{:cell [0 0] :zone-frames {:all frame}}])]
           (us (timed #(#'me/projector-frame :p snap)))))
    (state/assoc-in-state! [:projectors :p :safety] nil)
    (say "e3 the same frame with no safety zone (the default calibration only):"
         (let [snap (snapshot [{:cell [0 0] :zone-frames {:all frame}}])]
           (us (timed #(#'me/projector-frame :p snap)))))
    (say "e3 load average while measuring:"
         (format "%.1f" (.getSystemLoadAverage (java.lang.management.ManagementFactory/getOperatingSystemMXBean))))))


;; e4: reads of app state per frame

(deftest e4-reads-per-frame
  (init! {:p (laser 5)})
  (state/assoc-in-state! [:chains :projector-effects :p :items] projector-handlers/DEFAULT_PROJECTOR_EFFECTS)
  (let [n (atom 0)
        get-raw-state state/get-raw-state
        count-reads (fn [f] (reset! n 0)
                      (with-redefs [state/get-raw-state (fn [] (swap! n inc) (get-raw-state))] (f))
                      @n)
        show (snapshot [{:cell [0 0] :zone-frames {:all line-to-08}}])
        idle (snapshot [] :playing? false)]
    (say "e4 app-state reads in one frame: a cue playing" (count-reads #(#'me/projector-frame :p show))
         "| nothing playing" (count-reads #(#'me/projector-frame :p idle)))
    (state/assoc-in-state! [:projector-ui :calibrating-projector-id] :p)
    (let [pattern (snapshot [] :playing? false)]
      (say "e4 app-state reads in one frame: the test pattern" (count-reads #(#'me/projector-frame :p pattern))
           "(" (count (#'me/projector-frame :p pattern)) "points )"))
    (state/assoc-in-state! [:vibe-runtime :blackout?] true)
    (let [dark (snapshot [] :playing? false)]
      (say "e4 app-state reads in one frame: the test pattern during BLACKOUT" (count-reads #(#'me/projector-frame :p dark))
           "(" (count (#'me/projector-frame :p dark)) "points )"))))
