(ns laser-show.animation.show-time-test
  "show-time/step: the clock's pure step from app state's intents and requests
   (plans/2026-11-17-show-clock/01 §7). App state is built by the real
   handlers through events/event-handler, so intents have their real shape."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]))

(def ^:private circle
  {:destination-zone {:zone-group-id :all}
   :items [{:type :preset :preset-id :circle :enabled? true}]})

(defn- timeline [extra]
  (merge {:editor-type :timeline
          :destination-zone {:zone-group-id :all}
          :tracks [{:id :t :type :track}]
          :items [{:id :a :type :preset :preset-id :circle :track-id :t :enabled? true :effects []
                   :timeline/start 0.0 :timeline/duration 16.0}]}
         extra))

(defn- init! [chains]
  (state/init-state! (-> (domains/build-initial-state)
                         (assoc-in [:chains :cue-chains] chains)
                         (assoc-in [:timing :bpm] 120.0)))
  (show-time/reset-runtime!))

(use-fixtures :each (fn [f] (init! {}) (f)))

(defn- handle! [event]
  (events/event-handler event)
  (state/get-raw-state))

(defn- step
  "One step at now-ms with app state as it is now: {:runtime :reports}."
  ([runtime now-ms] (step runtime now-ms nil))
  ([runtime now-ms mtc-pos] (show-time/step runtime (state/get-raw-state) mtc-pos now-ms)))

(defn- run-at
  "The runtime after stepping at each of now-mss in turn."
  [runtime & now-mss]
  (reduce #(:runtime (step %1 %2)) runtime now-mss))

(defn- run-id [cell]
  (get-in (state/get-raw-state) [:playback :active-cues cell :run]))

(defn- run-of [runtime cell]
  (get-in runtime [:runs (run-id cell)]))

(defn- beats [runtime cell]
  (:accumulated-beats (run-of runtime cell)))

(defn- close? [expected actual]
  (and (number? actual) (< (Math/abs (- (double expected) (double actual))) 1e-6)))

(def ^:private tick-ms (/ 1000.0 60))

(def ^:private start (show-time/step show-time/initial-runtime {} nil 0.0))


(deftest new-run-starts-at-its-start-beat-test
  (init! {[0 0] circle [1 0] (timeline {})})
  (let [global {:accumulated-beats 7.25 :accumulated-ms 3625.0 :phase-offset 0.0
                :phase-offset-target 0.0 :effective-beats 7.25 :last-frame-time 1000.0}
        rt0 (assoc show-time/initial-runtime :global global)]
    (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
    (is (= {:run (run-id [0 0])} (get-in (state/get-raw-state) [:playback :active-cues [0 0]]))
        "the intent names a run and holds no position")
    (is (uuid? (run-id [0 0])))
    (let [rt1 (run-at rt0 1000.0)
          r1 (run-of rt1 [0 0])]
      (is (= [0.0 1000.0 [0 0]] ((juxt :accumulated-beats :last-frame-time :cell) r1)))
      (is (close? 0.25 (:phase-offset-target r1)) "toward the global beat phase, (mod 7.25 1)")
      (let [r1' (run-of (run-at rt1 (+ 1000.0 tick-ms)) [0 0])]
        (is (close? (/ 1.0 30) (:accumulated-beats r1')) "one tick at 120 BPM is 1/30 beat")
        (is (< 0.0 (:phase-offset r1') 0.25) "gliding toward 0.25")))
    (testing "a timeline starts at its start beat, with no glide"
      (handle! {:event/type :timeline/play :col 1 :row 0 :from 3.0})
      (is (= {:run (run-id [1 0]) :timeline? true :start-beat 3.0}
             (get-in (state/get-raw-state) [:playback :active-cues [1 0]])))
      (let [r2 (run-of (run-at rt0 1000.0) [1 0])]
        (is (= [true 3.0 0.0 0.0] ((juxt :timeline? :accumulated-beats :phase-offset :phase-offset-target) r2)))))))

(deftest removed-intent-drops-the-run-test
  (init! {[0 0] circle [1 0] circle})
  (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
  (handle! {:event/type :grid/trigger-cell :col 1 :row 0})
  (let [rt (run-at (:runtime start) 0.0 500.0)]
    (is (= 2 (count (:runs rt))))
    (testing "untriggered"
      (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
      (is (= #{[1 0]} (set (map :cell (vals (:runs (run-at rt 516.0))))))))
    (testing "cleared"
      (handle! {:event/type :grid/clear-cell :col 1 :row 0})
      (is (empty? (:runs (run-at rt 516.0)))))))

(deftest moved-cue-keeps-its-position-test
  (init! {[0 0] circle})
  (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
  (let [r (run-id [0 0])
        rt (run-at (:runtime start) 0.0 500.0 1000.0 1500.0 1750.0)]
    (is (close? 3.5 (beats rt [0 0])))
    (handle! {:event/type :grid/move-cell :from-col 0 :from-row 0 :to-col 1 :to-row 1})
    (is (= r (run-id [1 1])) "the intent moves with its run")
    (let [rt' (run-at rt (+ 1750.0 tick-ms))]
      (is (= [1 1] (:cell (get-in rt' [:runs r]))))
      (is (close? (+ 3.5 (/ 1.0 30)) (get-in rt' [:runs r :accumulated-beats])))
      (is (= {[1 1] (get-in rt' [:runs r :accumulated-beats])} (show-time/positions rt'))))))

(deftest paused-run-holds-test
  (init! {[0 0] (timeline {})})
  (handle! {:event/type :timeline/play :col 0 :row 0 :from 2.0})
  (let [rt (run-at (:runtime start) 0.0)]
    (is (close? 2.0 (beats rt [0 0])))
    (handle! {:event/type :timeline/pause :col 0 :row 0})
    (let [rt (run-at rt 500.0)]
      (is (close? 2.0 (beats rt [0 0])) "held")
      (handle! {:event/type :timeline/play :col 0 :row 0})
      (is (close? 3.0 (beats (run-at rt 1000.0) [0 0])) "resumed from where it paused"))))

(deftest seek-request-applies-once-test
  (init! {[0 0] (timeline {})})
  (handle! {:event/type :timeline/play :col 0 :row 0 :from 3.0})
  (let [rt (run-at (:runtime start) 0.0)]
    (handle! {:event/type :timeline/seek :col 0 :row 0 :beat 9.0})
    (let [{:keys [id beat]} (get-in (state/get-raw-state) [:playback :active-cues [0 0] :seek])]
      (is (uuid? id))
      (is (= 9.0 beat)))
    (let [rt1 (run-at rt tick-ms)
          rt2 (run-at rt1 (* 2 tick-ms))]
      (is (close? (+ 9.0 (/ 1.0 30)) (beats rt1 [0 0])) "seeked, then advanced")
      (is (close? (+ 9.0 (/ 2.0 30)) (beats rt2 [0 0])) "the same intent again: a seek applies once"))))

(defn- with-runs
  "The runtime with each triggered cell's run set to timing (merged)."
  [runtime cell->timing]
  (reduce-kv (fn [rt cell timing] (update-in rt [:runs (run-id cell)] merge timing))
             runtime cell->timing))

(deftest restart-request-resets-everything-once-test
  (init! {[0 0] circle [1 0] circle})
  (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
  (handle! {:event/type :grid/trigger-cell :col 1 :row 0})
  (let [rt (-> (run-at (:runtime start) 0.0 1000.0)
               (assoc :global {:accumulated-beats 10.0 :accumulated-ms 5000.0 :phase-offset 0.0
                               :phase-offset-target 0.0 :last-frame-time 1000.0})
               (with-runs {[0 0] {:accumulated-beats 5.0 :accumulated-ms 2500.0 :phase-offset 0.3
                                  :phase-offset-target 0.5 :last-frame-time 1000.0}
                           [1 0] {:accumulated-beats 3.0 :accumulated-ms 1500.0 :phase-offset 0.2
                                  :phase-offset-target 0.4 :last-frame-time 1000.0}}))]
    (handle! {:event/type :transport/stop})
    (is (false? (get-in (state/get-raw-state) [:playback :playing?])))
    (is (uuid? (get-in (state/get-raw-state) [:playback :requests :restart :id])))
    (let [rt1 (run-at rt 1000.0)]
      (is (= [0.0 0.0] ((juxt :accumulated-beats :accumulated-ms) (:global rt1))))
      (doseq [cell [[0 0] [1 0]]]
        (is (= [0.0 0.0 0.0 0.0]
               ((juxt :accumulated-beats :accumulated-ms :phase-offset :phase-offset-target) (run-of rt1 cell)))
            (str cell)))
      (testing "it applies once: the next tick advances"
        (let [rt2 (run-at rt1 1500.0)]
          (is (close? 1.0 (beats rt2 [0 0])) "the runs keep counting, as today")
          (testing "retrigger is the same request"
            (handle! {:event/type :transport/retrigger})
            (is (= 0.0 (beats (run-at rt2 1500.0) [0 0])))))))))

(deftest align-request-targets-the-nearest-beat-test
  (init! {[0 0] circle [1 0] (timeline {})})
  (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
  (handle! {:event/type :timeline/play :col 1 :row 0 :from 4.35})
  (let [rt (-> (run-at (:runtime start) 1000.0)
               (assoc :global {:accumulated-beats 12.35 :accumulated-ms 6175.0 :phase-offset 0.0
                               :phase-offset-target 0.0 :last-frame-time 1000.0})
               (with-runs {[0 0] {:accumulated-beats 4.35 :phase-offset 0.0 :phase-offset-target 0.0}}))]
    (handle! {:event/type :timing/tap-tempo :time 2000})
    (let [rt1 (run-at rt 1000.0)]
      (is (= 0.0 (:phase-offset (:global rt1))) "no snap at the tap")
      (is (close? -0.35 (:phase-offset-target (:global rt1))) "12.35 → 12.0, the shorter way")
      (is (close? -0.35 (:phase-offset-target (run-of rt1 [0 0]))))
      (is (= 0.0 (:phase-offset-target (run-of rt1 [1 0]))) "a timeline doesn't move")
      (is (close? 4.35 (beats rt1 [1 0]))))
    (testing "past the half beat it pulls forward"
      (handle! {:event/type :timing/tap-tempo :time 2500})
      (is (close? 0.2 (:phase-offset-target (:global (run-at (assoc-in rt [:global :accumulated-beats] 12.8)
                                                             1000.0))))))))

(deftest shift-request-adds-the-offset-test
  (init! {[0 0] circle [1 0] (timeline {})})
  (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
  (handle! {:event/type :timeline/play :col 1 :row 0 :from 2.0})
  (let [rt (-> (run-at (:runtime start) 1000.0)
               (assoc-in [:global :phase-offset] 0.1)
               (assoc-in [:global :phase-offset-target] 0.1)
               (with-runs {[0 0] {:phase-offset 0.05 :phase-offset-target 0.05}}))]
    (handle! {:event/type :timing/set-phase-offset-target :offset 0.25})
    (is (= 0.25 (get-in (state/get-raw-state) [:playback :requests :shift :offset])))
    (let [rt1 (run-at rt 1000.0)]
      (is (close? 0.35 (:phase-offset-target (:global rt1))))
      (is (close? 0.30 (:phase-offset-target (run-of rt1 [0 0]))))
      (is (= 0.0 (:phase-offset-target (run-of rt1 [1 0]))) "timelines keep their position"))))

(deftest nil-request-ids-never-apply-test
  (init! {[0 0] circle})
  (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
  (let [rt (run-at (:runtime start) 0.0 500.0)
        unchanged? (fn [rt']
                     (and (close? 3.0 (beats rt' [0 0]))
                          (= 0.0 (:phase-offset-target (run-of rt' [0 0])))
                          (= 0.0 (get-in rt' [:global :phase-offset-target]))))]
    (testing "a request without an id"
      (state/swap-state! assoc-in [:playback :requests] {:restart {:id nil} :align {} :shift {:offset 0.5}})
      (is (unchanged? (run-at rt 1000.0 1500.0))))
    (testing "a new document has no requests, and what was applied before stays applied"
      (state/swap-state! assoc-in [:playback :requests] {})
      (is (unchanged? (run-at (assoc-in rt [:applied :restart] (random-uuid)) 1000.0 1500.0)))))
  (testing "restarting the clock doesn't apply a request it already applied"
    (handle! {:event/type :transport/retrigger})
    (show-time/reset-runtime! (state/get-raw-state))
    (is (= (show-time/request-ids (state/get-raw-state)) (:applied @show-time/!runtime)))
    (let [ten-beats {:accumulated-beats 10.0 :accumulated-ms 5000.0 :last-frame-time 0.0}]
      (is (close? 11.0 (get-in (run-at (assoc @show-time/!runtime :global ten-beats) 500.0)
                               [:global :accumulated-beats])))
      (testing "(a runtime reset without the state applies it)"
        (show-time/reset-runtime!)
        (is (close? 1.0 (get-in (run-at (assoc @show-time/!runtime :global ten-beats) 500.0)
                                [:global :accumulated-beats])))))))

(deftest timeline-end-reports-until-the-intent-goes-test
  (init! {[0 0] (timeline {})})
  (handle! {:event/type :timeline/play :col 0 :row 0 :from 15.9})
  (let [r2 (run-id [0 0])
        report {:event/type :show-clock/cue-ended :cell [0 0] :run r2}
        {rt1 :runtime reports1 :reports} (step (run-at (:runtime start) 0.0) 100.0)]
    (is (= [true 16.0] ((juxt :ended? :accumulated-beats) (run-of rt1 [0 0]))) "held at its end")
    (is (= [report] reports1))
    (is (empty? (show-time/active-runs (state/get-raw-state) rt1)) "an ended run isn't rendered")
    (let [{rt2 :runtime reports2 :reports} (step rt1 200.0)
          {rt3 :runtime reports3 :reports} (step rt2 350.0)]
      (is (empty? reports2) "at most once every 250 ms")
      (is (= [report] reports3) "sent again while the intent is still there")
      (run! events/event-handler reports3)
      (is (nil? (get-in (state/get-raw-state) [:playback :active-cues [0 0]])))
      (is (false? (get-in (state/get-raw-state) [:playback :playing?])))
      (let [{rt4 :runtime reports4 :reports} (step rt3 400.0)]
        (is (empty? reports4))
        (is (empty? (:runs rt4)))))))

(deftest report-for-an-old-run-changes-nothing-test
  (init! {[0 0] (timeline {})})
  (handle! {:event/type :timeline/play :col 0 :row 0})
  (let [r2 (run-id [0 0])]
    (handle! {:event/type :timeline/stop :col 0 :row 0})
    (handle! {:event/type :timeline/play :col 0 :row 0})
    (is (not= r2 (run-id [0 0])) "started again: a new run")
    (let [before (state/get-raw-state)]
      (events/event-handler {:event/type :show-clock/cue-ended :cell [0 0] :run r2})
      (is (= (:playback before) (:playback (state/get-raw-state)))))))

(def ^:private chasing
  (timeline {:timeline/chase :mtc :timeline/tc-start 0.0 :timeline/tempo-bpm 120.0 :timeline/length 64.0}))

(deftest chase-activates-and-reports-test
  (init! {[2 0] chasing})
  (let [running {:state :running :seconds 10.0 :fps 25}
        {rt :runtime reports :reports} (step (:runtime start) 0.0 running)]
    (is (close? 20.0 (get-in rt [:chase [2 0] :accumulated-beats])))
    (is (true? (get-in rt [:chase [2 0] :chasing?])))
    (is (= [{:event/type :show-clock/chase :cell [2 0] :active? true}] reports))
    (is (= [[2 0]] (map :cell (show-time/active-runs (assoc-in (state/get-raw-state) [:playback :playing?] true) rt)))
        "rendered before app state hears of it")
    (run! events/event-handler reports)
    (is (= {:chasing? true :timeline? true} (get-in (state/get-raw-state) [:playback :active-cues [2 0]])))
    (is (true? (get-in (state/get-raw-state) [:playback :playing?])))
    (is (empty? (:reports (step rt 16.0 running))) "app state agrees: nothing to report")
    (testing "timecode stops: the chase ends and says so at once, not 250 ms after the last report"
      (let [{rt' :runtime reports' :reports} (step rt 100.0 {:state :stopped :seconds 10.0 :fps 25})]
        (is (empty? (:chase rt')))
        (is (= [{:event/type :show-clock/chase :cell [2 0] :active? false}] reports'))
        (run! events/event-handler reports')
        (is (nil? (get-in (state/get-raw-state) [:playback :active-cues [2 0]])))
        (is (false? (get-in (state/get-raw-state) [:playback :playing?])))))))

(deftest sixty-hz-ticks-match-thirty-hz-ticks-test
  (init! {[0 0] circle})
  (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
  (let [at-hz (fn [hz] (apply run-at (:runtime start) (map #(* % (/ 1000.0 hz)) (range (inc hz)))))
        rt60 (at-hz 60)
        rt30 (at-hz 30)]
    (is (< (Math/abs (- 2.0 (beats rt60 [0 0]))) 1e-9) "1 s at 120 BPM")
    (is (< (Math/abs (- (beats rt60 [0 0]) (beats rt30 [0 0]))) 1e-9))
    (is (< (Math/abs (- (get-in rt60 [:global :accumulated-beats]) (get-in rt30 [:global :accumulated-beats]))) 1e-9))
    (is (= 61 (- (:tick rt60) (:tick (:runtime start)))) "one tick per step")))


;; Moved from frame_service_test (they called update-timing-accumulators!)

(deftest timeline-cue-stops-at-end-test
  (init! {[0 0] (timeline {:items [{:id :a :type :preset :preset-id :circle :track-id :t :enabled? true
                                    :effects [] :timeline/start 0.0 :timeline/duration 4.0}]})})
  (handle! {:event/type :timeline/play :col 0 :row 0 :from 3.5})
  (let [{:keys [runtime reports]} (step (run-at (:runtime start) 1000.0) 1500.0)]
    (is (true? (:ended? (run-of runtime [0 0]))) "past the last clip end (4 beats)")
    (is (= [:show-clock/cue-ended] (map :event/type reports)))
    (run! events/event-handler reports)
    (is (false? (get-in (state/get-raw-state) [:playback :playing?]))))
  (testing "with the loop on it keeps going"
    (state/swap-state! assoc-in [:chains :cue-chains [0 0] :loop] {:enabled? true :start 0.0 :duration 4.0})
    (handle! {:event/type :timeline/play :col 0 :row 0 :from 3.5})
    (is (close? 0.5 (beats (run-at (:runtime start) 1000.0 1500.0) [0 0])))))

(deftest locked-tempo-ignores-global-bpm-test
  (init! {[0 0] (timeline {:timeline/tempo-bpm 60.0 :timeline/length 100.0})})
  (state/swap-state! assoc-in [:timing :bpm] 180.0)
  (handle! {:event/type :timeline/play :col 0 :row 0 :from 0.0})
  (is (close? 1.0 (beats (run-at (:runtime start) 1000.0 2000.0) [0 0]))
      "1 s at the locked 60 BPM = 1 beat, not 3"))

(deftest chasing-timeline-follows-mtc-test
  (init! {[0 0] (timeline {:timeline/chase :mtc :timeline/tc-start 3600.0 :timeline/tempo-bpm 120.0
                           :timeline/length 64.0})})
  (let [chase-beats (fn [rt] (get-in rt [:chase [0 0] :accumulated-beats]))
        rt1 (:runtime (step (:runtime start) 1000.0 {:state :running :seconds 3599.0 :fps 25}))
        rt2 (:runtime (step rt1 1033.0 {:state :running :seconds 3605.48 :fps 25}))]
    (is (nil? (chase-beats rt1)) "before beat 0")
    (is (close? 10.96 (chase-beats rt2)))
    (testing "the cue's own clock doesn't move it between timecode updates"
      (is (close? 10.96 (chase-beats (:runtime (step rt2 1500.0 {:state :running :seconds 3605.48 :fps 25}))))))
    (is (nil? (chase-beats (:runtime (step rt2 1600.0 {:state :stopped :seconds 3605.48 :fps 25})))))))

(deftest non-chasing-cues-ignore-mtc-test
  (init! {[0 0] (timeline {:timeline/length 100.0})})
  (handle! {:event/type :timeline/play :col 0 :row 0 :from 2.0})
  (let [mtc {:state :running :seconds 50.0 :fps 25}
        rt (:runtime (step (:runtime (step (:runtime start) 1000.0 mtc)) 1500.0 mtc))]
    (is (close? 3.0 (beats rt [0 0])) "advanced by its own clock (+1 beat), not set to 100")
    (is (empty? (:chase rt)))))

(deftest chase-takes-over-a-playing-cue-test
  (testing "a cue playing when its chase is switched on stops when no timecode runs"
    (init! {[0 0] (timeline {:timeline/length 64.0})})
    (handle! {:event/type :timeline/play :col 0 :row 0 :from 2.0})
    (let [rt (run-at (:runtime start) 0.0)]
      (state/swap-state! assoc-in [:chains :cue-chains [0 0] :timeline/chase] :mtc)
      (let [{rt' :runtime reports :reports} (step rt 16.0 {:state :stopped})]
        (is (empty? (:runs rt')) "the timecode drives it now")
        (is (empty? (show-time/active-runs (state/get-raw-state) rt')))
        (is (= [{:event/type :show-clock/chase :cell [0 0] :active? false}] reports))
        (run! events/event-handler reports)
        (is (nil? (get-in (state/get-raw-state) [:playback :active-cues [0 0]])))))))

(deftest active-runs-keep-the-intents-order-test
  (init! {[0 0] circle [1 0] circle [2 0] {:items []}})
  (doseq [col [1 0 2]] (handle! {:event/type :grid/trigger-cell :col col :row 0}))
  (let [state (state/get-raw-state)
        rt (run-at (:runtime start) 0.0)]
    (is (= (keep #(when (seq (get-in state [:chains :cue-chains % :items])) %)
                 (keys (get-in state [:playback :active-cues])))
           (map :cell (show-time/active-runs state rt)))
        "app state's order, without the chain that has no items")
    (is (= #{:cell :run :cue-chain :cue-timing} (set (keys (first (show-time/active-runs state rt))))))
    (testing "an intent the clock hasn't started yet isn't rendered"
      (state/swap-state! assoc-in [:chains :cue-chains [3 0]] circle)
      (handle! {:event/type :grid/trigger-cell :col 3 :row 0})
      (is (some? (run-id [3 0])))
      (is (not-any? #{[3 0]} (map :cell (show-time/active-runs (state/get-raw-state) rt)))))))
