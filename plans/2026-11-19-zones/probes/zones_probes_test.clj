;; EVIDENCE for plans/2026-11-19-zones/05-phase-z3-routing-core.md §8 invariant 12
;; and §9 (probes z1-z4). Not part of the test suite and not on any classpath.
;;
;; Each deftest prints PROBE lines. They run against the code as it is, so the
;; same file measures before Z3 (6bedf25, Z2 on master) and after it: where a
;; Z3 function doesn't exist yet (routing.rig/routing, routing.compose,
;; animation.fit-guard) the probe says so and measures what there is.
;;
;; - z1: what one laser's engine makes of a tick (multi-engine/projector-frame,
;;   which composes and then runs the projector effects and safety: none set
;;   here), with 1, 3 and 8 lasers, on 1,000-point frames: one cue on All
;;   (whole) and one with a picture on Left and another on Right (split).
;;   After Z3 also routing.compose/projector-frame alone.
;; - z2: the fit guard on 1,000, 2,000 and 4,000 points (25,000 pts/s, min 20:
;;   a budget of 1,250).
;; - z3: the IDN time (provider + send, the profiler's samples) with 3 lasers on
;;   the fake DAC, the show clock thread running, for 3 s of each setup; and the
;;   render time the clock records (JFR FrameGeneration's baseTimeUs).
;; - z4: renders per tick with 8 engines (show clock probe c2).
;; - z5 (07-phase-z5-zone-patterns.md §9): the zone stage on 1,000-point
;;   frames with one cue pattern and three cell patterns, at 1, 3 and 8
;;   lasers: routing.zone-remap/run-stage alone (parameters resolved once, as
;;   the render does), the points and objects in and out; then a cue's whole
;;   render (frame-service/generate-frames-by-zone) with and without them.
;;   Before Z5 it says there is no stage.
;;
;; No UI, no hardware. z3 and z4 stream to the fake DAC on loopback.
;;
;; Run from the repo root (macOS Apple Silicon; use :win, :linux or :mac elsewhere).
;; The plan folder is the classpath root, so this namespace is probes.zones-probes-test:
;;
;;   P=plans/2026-11-19-zones
;;   clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
;;       -M:mac-arm:test:probe -d "$P/probes" \
;;       -n probes.zones-probes-test 2>&1 | grep PROBE

(ns probes.zones-probes-test
  (:require [clojure.test :refer [deftest]]
            [laser-show.animation.effects.shape]
            [laser-show.animation.show-time :as show-time]
            [laser-show.backend.multi-engine :as me]
            [laser-show.backend.output-errors :as output-errors]
            [laser-show.backend.streaming-engine :as se]
            [laser-show.events.core :as events]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.profiling.frame-profiler :as profiler]
            [laser-show.services.frame-service :as fs]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))

(defn- say [& xs] (apply println "PROBE" xs) (flush))

(defn- z3-fn
  "A Z3 function by its symbol, or nil before Z3."
  [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

(defn- percentile [sorted p]
  (nth sorted (min (dec (count sorted)) (long (* p (count sorted))))))

(defn- timed
  "[median p95] µs of (f), after a warm-up."
  [f]
  (dotimes [_ 3000] (f))
  (let [ns (sort (for [_ (range 5000)]
                   (let [t0 (System/nanoTime)]
                     (f)
                     (- (System/nanoTime) t0))))]
    [(/ (percentile ns 0.5) 1000.0) (/ (percentile ns 0.95) 1000.0)]))

(defn- us [[median p95]] (format "%.1f µs (p95 %.1f)" median p95))

(defn- circle
  "n lit points on a circle of radius r around (cx, 0)."
  [n cx r]
  (mapv (fn [i] (let [a (/ (* 2.0 Math/PI i) n)]
                  [(+ cx (* r (Math/cos a))) (* r (Math/sin a)) 0.0 1.0 0.0]))
        (range n)))

(defn- lasers
  "n enabled projectors :p1 … :pn, in that order (by IP)."
  [n & {:keys [host port]}]
  (into {} (for [k (range 1 (inc n))]
             [(keyword (str "p" k))
              (cond-> {:name (str "Laser " k) :host (or host (str "10.0.0." k)) :service-id k
                       :enabled? true :zone-groups [] :exclude-from-all? false}
                port (assoc :port port))])))

(defn- routing-of
  "The snapshot's :routing for state (Z3), or nil before Z3."
  [s]
  (when-let [inputs (z3-fn 'laser-show.routing.rig/routing-inputs)]
    ((z3-fn 'laser-show.routing.rig/routing) (inputs s nil))))

(defn- snapshot [cues]
  ;; at-ns a minute ahead, so the measurement never sees a stale tick; since
  ;; state integrity 01 the engines take their calibration and safety zone
  ;; from the snapshot's :engine-inputs
  (let [s (state/get-raw-state)]
    {:tick 1 :at-ns (+ (System/nanoTime) 60000000000) :playing? true :cues cues :projector-ctx nil
     :routing (routing-of s) :preview (delay nil)
     :engine-inputs (me/engine-inputs s (System/currentTimeMillis))}))


;; z1: one laser's frame from a tick

(deftest z1-composition-per-laser
  (let [a (circle 1000 0.0 0.5)
        b (circle 1000 0.2 0.3)
        compose (z3-fn 'laser-show.routing.compose/projector-frame)]
    (say "z1 the routing" (if compose "is in the snapshot (Z3)" "is read live by each engine (before Z3)"))
    (doseq [n [1 3 8]
            [setup frames] [[:whole {:all a}] [:split {:auto/left a :auto/right b}]]]
      (state/init-state! (assoc (domains/build-initial-state) :projectors (lasers n)))
      (output-errors/clear!)
      (let [snap (snapshot [{:cell [0 0] :zone-frames frames}])
            per-laser (for [k (range 1 (inc n))
                            :let [p (keyword (str "p" k))
                                  out (#'me/projector-frame p snap)]]
                        [p (count out) (timed #(#'me/projector-frame p snap))])]
        (say "z1" n (if (= 1 n) "laser" "lasers") (name setup) "| per laser: points sent, engine frame time:"
             (pr-str (mapv (fn [[p c t]] [p c (us t)]) per-laser)))
        (when compose
          (let [routing (:routing snap)
                cues (:cues snap)]
            (say "z1" n (name setup) "| compose/projector-frame alone, per laser:"
                 (pr-str (mapv (fn [k] (let [p (keyword (str "p" k))]
                                         [p (:regions (compose cues routing p)) (us (timed #(compose cues routing p)))]))
                               (range 1 (inc n)))))))))))


;; z2: the fit guard

(deftest z2-fit-guard-cost
  (if-let [guard (z3-fn 'laser-show.animation.fit-guard/guard)]
    (let [settings ((z3-fn 'laser-show.animation.fit-guard/settings) nil)]
      (doseq [n [1000 2000 4000]]
        (let [frame (circle n 0.0 0.5)
              {out :frame fit :fit} (guard frame 25000 settings)]
          (say "z2 a" n "point circle at 25,000 pts/s, min 20:" (count out) "points out,"
               (format "%.1f -> %.1f fps," (double (:est-fps fit)) (double (:guard-fps fit)))
               "same object?" (identical? frame out) "|" (us (timed #(guard frame 25000 settings)))))))
    (say "z2 no fit guard yet (before Z3)")))


;; z3: the engines' IDN time on the fake DAC

(defn- preset [preset-id zone]
  (cond-> {:id (random-uuid) :type :preset :preset-id preset-id :enabled? true
           :effects [{:id (random-uuid) :effect-id :rotation :enabled? true
                      :params {:angle {:type :sine :min 0.0 :max 360.0 :period 1.0}}}]}
    zone (assoc :zone-group-id zone)))

(defn- stream-for!
  "Stream projectors to the fake DAC with the show clock thread running and
   cue at [0 0] playing for ms (after 1 s to warm up); the IDN samples of
   that time and each engine's frames sent."
  [projectors cue ms]
  (state/init-state! (-> (domains/build-initial-state)
                         (assoc-in [:timing :bpm] 120.0)
                         (assoc-in [:chains :cue-chains [0 0]] cue)
                         (assoc :projectors projectors)))
  (show-time/reset-runtime!)
  (output-errors/clear!)
  (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
  (let [engines (mapv (fn [[id p]] (me/create-engine-for-projector id p)) projectors)]
    (try
      (run! se/start! engines)
      (show-clock/start!)
      (Thread/sleep 1000)
      (let [sent0 (mapv #(:frames-sent (se/get-stats %)) engines)]
        (Thread/sleep ms)
        {:samples (mapv :idn-time-us (profiler/get-idn-samples (* 3 (quot (* 60 ms) 1000))))
         :frames (mapv #(- (:frames-sent (se/get-stats %2)) %1) sent0 engines)
         :render (profiler/get-recent-stats 60)
         :errors (keys (output-errors/snapshot))})
      (finally
        (show-clock/stop!)
        (se/stop-all! engines)))))

(deftest z3-idn-time-with-three-lasers
  (let [fake (dac/start!)]
    (try
      (doseq [[setup cue] [[:all {:destination-zone {:zone-group-id :all} :items [(preset :circle nil)]}]
                           [:left-right {:destination-zone {:zone-group-id :all}
                                         :items [(preset :circle :auto/left) (preset :wave :auto/right)]}]]]
        (let [{:keys [samples frames render errors]} (stream-for! (lasers 3 :host "127.0.0.1" :port (:port fake)) cue 3000)
              sorted (vec (sort samples))]
          (say "z3" (name setup) "| 3 engines, 3 s:" (count samples) "IDN samples, avg"
               (format "%.1f µs, p50 %.1f, p95 %.1f, max %.1f"
                       (/ (reduce + samples) (max 1 (count samples)))
                       (double (percentile sorted 0.5)) (double (percentile sorted 0.95)) (double (peek sorted))))
          (say "z3" (name setup) "| frames each engine sent:" (pr-str frames)
               "| the clock's render (baseTimeUs) avg" (format "%.1f µs" (double (or (:avg-base-us render) 0.0)))
               "| output errors:" (pr-str (vec errors)))))
      (finally
        (dac/stop! fake)))))


;; z4: one render per cue per tick, with 8 engines

(deftest z4-renders-per-tick-with-eight-engines
  (let [fake (dac/start!)
        projectors (lasers 8 :host "127.0.0.1" :port (:port fake))]
    (state/init-state! (-> (domains/build-initial-state)
                           (assoc-in [:timing :bpm] 120.0)
                           (assoc-in [:chains :cue-chains [0 0]] {:destination-zone {:zone-group-id :all}
                                                                  :items [(preset :circle nil)]})
                           (assoc :projectors projectors)))
    (show-time/reset-runtime!)
    (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
    (let [renders (atom 0)
          render fs/generate-frames-by-zone
          engines (mapv (fn [[id p]] (me/create-engine-for-projector id p)) projectors)]
      (try
        (with-redefs [fs/generate-frames-by-zone (fn [& args] (swap! renders inc) (apply render args))]
          (run! se/start! engines)
          (dotimes [_ 60]
            (show-clock/tick! (System/nanoTime))
            (Thread/sleep 5))
          (say "z4 60 ticks, one cue, 8 engines:" @renders "renders;"
               "frames sent" (pr-str (mapv #(:frames-sent (se/get-stats %)) engines))))
        (finally
          (se/stop-all! engines)
          (show-time/reset-runtime!)
          (dac/stop! fake))))))


;; z5: the zone stage (07 §9)

(defn- zone-pattern
  "A :zone-pattern effect instance of style over zones, more merged into its
   params (routing.zone-patterns' preset, on the cue's clock or the show's)."
  [where style zones & {:as more}]
  (let [preset (z3-fn 'laser-show.routing.zone-patterns/preset-instance)]
    (-> (preset style where)
        (assoc :id (random-uuid))
        (update :params merge {:zones zones} more))))

(defn- frame-objects
  "How many frame objects frames holds (each counted once), and their points."
  [frames]
  (let [objs (reduce (fn [acc f] (if (some #(identical? % f) acc) acc (conj acc f))) [] (vals frames))]
    [(count objs) (reduce + (map count objs))]))

(defn- testing-the-render
  "A cue's whole render (frame-service/generate-frames-by-zone): a 1,000-point
   circle on All, with its own pattern and the three cells' (as the effects
   grid's zone effects), and with none; 3 lasers."
  [cue-pattern cells timing]
  (let [zone-ctx (z3-fn 'laser-show.services.frame-service/zone-ctx)
        routing (z3-fn 'laser-show.routing.rig/routing)
        inputs (z3-fn 'laser-show.routing.rig/routing-inputs)
        s (assoc (domains/build-initial-state) :projectors (lasers 3))
        _ (state/init-state! s)
        ctx (zone-ctx (routing (inputs s nil)) nil)
        cue {:items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true :params {:num-points 999} :effects []}]}
        render (fn [cue zone-effects]
                 (fs/generate-frames-by-zone cue timing {:global-ctx timing :zone-effects zone-effects :zone-ctx ctx}))]
    (doseq [[label c zfx] [["no pattern" cue []]
                           ["its own pattern" (assoc cue :zone-patterns [cue-pattern]) []]
                           ["its own + 3 cells" (assoc cue :zone-patterns [cue-pattern]) (mapv (fn [fx] {:effect fx}) cells)]]]
      (let [out (render c zfx)]
        (say "z5 render, 3 lasers |" label "| keys:" (pr-str (vec (sort-by str (keys out))))
             "| points:" (reduce + (map count (vals out))) "| zone-steps:" (count (:zone-steps (meta out)))
             "|" (us (timed #(render c zfx))))))))

(deftest z5-zone-stage-cost
  (if-let [run-stage (z3-fn 'laser-show.routing.zone-remap/run-stage)]
    (let [resolve (z3-fn 'laser-show.animation.effects/resolve-effect-params)
          zone-ctx (z3-fn 'laser-show.services.frame-service/zone-ctx)
          routing (z3-fn 'laser-show.routing.rig/routing)
          inputs (z3-fn 'laser-show.routing.rig/routing-inputs)
          _ (require 'laser-show.animation.effects.zone)
          timing {:accumulated-beats 4.5 :effective-beats 4.5 :phase-offset 0.0 :accumulated-ms 2250.0
                  :time-ms 2250.0 :bpm 120.0}
          cue-pattern (zone-pattern :cue :alternate [:zone/a :zone/b])
          cells [(zone-pattern :cell :chase [:lane/lasers])
                 (zone-pattern :cell :random [:zone/a :zone/b :zone/c :zone/d] :width 2 :source :zones)
                 (zone-pattern :cell :shift [:zone/a :zone/b :zone/c :zone/d])]
          resolved (fn [fxs] (mapv (fn [fx] {:id (:id fx) :params (resolve fx timing)}) fxs))
          one (resolved [cue-pattern])
          four (resolved (cons cue-pattern cells))
          all-picture {:all (circle 1000 0.0 0.5)}
          zoned {:zone/a (circle 1000 -0.3 0.2) :zone/b (circle 1000 0.3 0.2)}]
      (doseq [n [1 3 8]]
        (let [s (assoc (domains/build-initial-state) :projectors (lasers n))
              ctx (assoc (zone-ctx (routing (inputs s nil)) nil) :beats {:cue 4.5 :global 4.5} :pulses nil)]
          (doseq [[setup frames] [[:all all-picture] [:zones zoned]]
                  [label patterns] [["no pattern" []] ["1 cue pattern" one] ["1 cue + 3 cell patterns" four]]]
            (let [{out :frames steps :steps} (run-stage frames patterns ctx)
                  [objs-in pts-in] (frame-objects frames)
                  [objs-out pts-out] (frame-objects out)]
              (say "z5" n (if (= 1 n) "laser" "lasers") (name setup) "|" label "| keys out:" (pr-str (vec (sort-by str (keys out))))
                   "| objects in/out:" objs-in "/" objs-out "| points in/out:" pts-in "/" pts-out
                   "| same map?" (identical? frames out) "| steps:" (pr-str (vec (vals steps)))
                   "|" (us (timed #(run-stage frames patterns ctx))))))))
      (testing-the-render cue-pattern cells timing))
    (say "z5 no zone stage yet (before Z5)")))
