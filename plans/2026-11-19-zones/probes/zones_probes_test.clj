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
  ;; at-ns a minute ahead, so the measurement never sees a stale tick
  {:tick 1 :at-ns (+ (System/nanoTime) 60000000000) :playing? true :cues cues :projector-ctx nil
   :routing (routing-of (state/get-raw-state)) :preview (delay nil)})


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
