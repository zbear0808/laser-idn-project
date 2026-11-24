(ns laser-show.animation.show-time-test
  "show-time/step: the clock's pure step from app state's intents and requests
   (plans/2026-11-17-show-clock/01 §7). App state is built by the real
   handlers through events/event-handler, so intents have their real shape."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]))

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
        report {:event/type :show-clock/cue-ended :cell [0 0] :run r2 :position-ids {:restart nil :seek nil}}
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
      (events/event-handler {:event/type :show-clock/cue-ended :cell [0 0] :run r2 :position-ids {:restart nil :seek nil}})
      (is (= (:playback before) (:playback (state/get-raw-state)))))))

(deftest due-reports-keeps-separate-memories-test
  (testing "a report goes at most every 250 ms by the memory it is given"
    (let [r {:event/type :vibe/arm :from :vibe-1}
          [sent reports] (show-time/due-reports {} [r] 0.0)]
      (is (= [{r 0.0} [r]] [sent reports]))
      (is (= [{r 0.0} []] (show-time/due-reports sent [r] 100.0)) "100 ms later: not again")
      (is (= [{r 300.0} [r]] (show-time/due-reports sent [r] 300.0)) "300 ms later: again")
      (is (= [{} []] (show-time/due-reports sent [] 100.0)) "no longer pending: forgotten, so it goes at once if it comes back")))
  (testing "the step keeps its own (:sent) and leaves the rotation's (:vibe-sent, show-clock/tick!) alone"
    (init! {[0 0] (timeline {})})
    (handle! {:event/type :timeline/play :col 0 :row 0 :from 15.9})
    (let [ended {:event/type :show-clock/cue-ended :cell [0 0] :run (run-id [0 0]) :position-ids {:restart nil :seek nil}}
          vibe-sent {{:event/type :vibe/arm :from :vibe-1} 50.0}
          {rt1 :runtime r1 :reports} (step (assoc (run-at (:runtime start) 0.0) :vibe-sent vibe-sent) 100.0)
          {rt2 :runtime r2 :reports} (step rt1 200.0)]
      (is (= [ended] r1))
      (is (empty? r2) "its own report waits its 250 ms")
      (is (= {ended 100.0} (:sent rt2)))
      (is (= vibe-sent (:vibe-sent rt1) (:vibe-sent rt2)))))
  (is (= [{} {}] ((juxt :sent :vibe-sent) show-time/initial-runtime)) "a reset runtime has both, empty"))


;; An end names the position that ended, and Stop stays in force
;; (plans/2026-11-23-cljfx-reactivity/03 §7, §8)

(def ^:private short-timeline
  "A timeline 0.25 beats long: 125 ms at 120 BPM."
  (timeline {:items [{:id :a :type :preset :preset-id :circle :track-id :t :enabled? true :effects []
                      :timeline/start 0.0 :timeline/duration 0.25}]}))

(defn- ended
  "The end the show clock reports of run at cell, at the position the
   restart and seek request ids name."
  [cell run restart seek]
  {:event/type :show-clock/cue-ended :cell cell :run run :position-ids {:restart restart :seek seek}})

(defn- transport
  "App state's transport now, as plan 03 §7 writes it: [playing? {cell run} active-cell]."
  []
  (let [{:keys [playing? active-cues active-cell]} (:playback (state/get-raw-state))]
    [playing? (update-vals active-cues :run) active-cell]))

(defn- restart-id [] (get-in (state/get-raw-state) [:playback :requests :restart :id]))

(defn- seek-of [cell] (get-in (state/get-raw-state) [:playback :active-cues cell :seek]))

(deftest stop-fences-old-ends-and-holds-survivors-test
  (init! {[0 0] short-timeline [1 0] circle})
  (handle! {:event/type :grid/trigger-cell :col 1 :row 0})
  (handle! {:event/type :timeline/play :col 0 :row 0 :from 0.2})
  (let [[a b] [[0 0] [1 0]]
        [r rb] [(run-id a) (run-id b)]
        b-intent (get-in (state/get-raw-state) [:playback :active-cues b])
        before (state/get-raw-state)
        {rt1 :runtime old :reports} (step (run-at (:runtime start) 0.0) 50.0)]
    (is (= [true {a r b rb} a] (transport)))
    (is (false? (get-in before [:project :dirty?])) "a clean project")
    (is (= [(ended a r nil nil)] old) "0.2 + 0.1 beats: past its end")
    (testing "example 1: an end seen before Stop leaves both cues held"
      (handle! {:event/type :transport/stop})
      (run! handle! old)
      (is (= [false {a r b rb} a] (transport))))
    (let [k1 (restart-id)
          {rt2 :runtime restarted :reports} (step rt1 50.0)
          {rt3 :runtime again :reports} (step rt2 175.0)]
      (is (= [0.0 0.0] [(beats rt2 a) (beats rt2 b)]) "the restart, at the same 50 ms")
      (is (empty? restarted))
      (is (= [(ended a r k1 nil)] again) "the restarted run's own end, 0.25 beats on")
      (testing "example 2: that end takes A out and leaves B held; only Play plays B"
        (run! handle! again)
        (is (= [false {b rb} b] (transport)))
        (is (= b-intent (get-in (state/get-raw-state) [:playback :active-cues b])) "B's whole intent")
        (run! handle! again)
        (is (= [false {b rb} b] (transport)) "a duplicate changes nothing")
        (handle! {:event/type :transport/play})
        (is (= [true {b rb} b] (transport)))
        (is (= [b] (map :cell (show-time/active-runs (state/get-raw-state) (run-at rt3 200.0)))) "B plays on"))
      (is (= [(:history before) false] [(:history (state/get-raw-state)) (get-in (state/get-raw-state) [:project :dirty?])])
          "no undo step, still clean"))))

(deftest seek-fences-an-end-on-the-same-run-test
  (init! {[0 0] (timeline {})})
  (handle! {:event/type :timeline/play :col 0 :row 0 :from 0.0})
  (handle! {:event/type :transport/retrigger})
  (let [a [0 0]
        r (run-id a)
        k1 (restart-id)
        rt0 (run-at (:runtime start) 0.0)]
    (testing "example 4, by :timeline/seek: the end before a seek is no end of where it went"
      (handle! {:event/type :timeline/seek :col 0 :row 0 :beat 15.9})
      (let [s1 (:id (seek-of a))
            {rt1 :runtime queued :reports} (step (run-at rt0 0.0) 100.0)]
        (is (= [(ended a r k1 s1)] queued))
        (handle! {:event/type :timeline/seek :col 0 :row 0 :beat 2.0})
        (run! handle! queued)
        (is (= [true {a r} a] (transport)))
        (is (= 2.0 (:beat (seek-of a))) "still asked for")
        (is (not= s1 (:id (seek-of a))))
        (let [rt2 (run-at rt1 100.0)]
          (is (= r (run-id a)) "the same run")
          (is (close? 2.0 (beats rt2 a)) "at beat 2 on a step that adds no time")
          (testing "by Play :from on the playing timeline, then a seek to the same beat: each its own"
            (handle! {:event/type :timeline/play :col 0 :row 0 :from 15.9})
            (let [s3 (:id (seek-of a))
                  {rt3 :runtime queued :reports} (step (run-at rt2 100.0) 200.0)]
              (is (= [(ended a r k1 s3)] queued))
              (handle! {:event/type :timeline/seek :col 0 :row 0 :beat 15.9})
              (run! handle! queued)
              (is (= [true {a r} a] (transport)))
              (let [s4 (:id (seek-of a))
                    rt4 (run-at rt3 200.0)
                    {final :reports} (step rt4 300.0)]
                (is (not= s3 s4) "the same beat, another seek")
                (is (close? 15.9 (beats rt4 a)) "the run that ended runs again from 15.9")
                (is (= [(ended a r k1 s4)] final))
                (run! handle! final)
                (is (= [false {} nil] (transport)) "the end of where it is now")))))))))

(deftest retrigger-fences-an-end-on-the-same-run-test
  (init! {[0 0] short-timeline})
  (handle! {:event/type :timeline/play :col 0 :row 0 :from 0.0})
  (handle! {:event/type :transport/retrigger})
  (handle! {:event/type :timeline/seek :col 0 :row 0 :beat 0.2})
  (let [a [0 0]
        r (run-id a)
        [k1 s1] [(restart-id) (:id (seek-of a))]
        rt0 (run-at (:runtime start) 0.0)
        {rt1 :runtime [e1 :as queued] :reports} (step rt0 50.0)]
    (is (close? 0.2 (beats rt0 a)) "the seek applied")
    (is (= [(ended a r k1 s1)] queued))
    (handle! {:event/type :transport/retrigger})
    (let [k2 (restart-id)
          {[e2 :as reports] :reports} (step rt1 175.0)]
      (is (true? (get-in (state/get-raw-state) [:playback :playing?])) "still playing")
      (is (= [(ended a r k2 s1)] reports) "ended again 125 ms after the first: a report of its own, sent at once")
      (is (not= e1 e2))
      (is (= [r s1] [(:run e2) (get-in e2 [:position-ids :seek])]) "the same run and seek")
      (is (not= k1 k2) "a new restart")
      (testing "example 5"
        (handle! e1)
        (is (= [true {a r} a] (transport)) "the end from before Retrigger")
        (handle! e2)
        (is (= [false {} nil] (transport)) "the restarted run's own end"))))
  (testing "a new document asks for no restart, though the clock applied one before: an end names app state's"
    (init! {[0 0] short-timeline})
    (handle! {:event/type :timeline/play :col 0 :row 0 :from 0.2})
    (is (nil? (restart-id)))
    (let [rt (run-at (assoc-in (:runtime start) [:applied :restart] (random-uuid)) 0.0)
          {:keys [reports]} (step rt 50.0)]
      (is (= [(ended [0 0] (run-id [0 0]) nil nil)] reports))
      (run! handle! reports)
      (is (= [false {} nil] (transport))))))


;; Vibe mode's decay (plans/2026-11-18-vibe-mode/01 §7 "The clock's side")

(defn- init-vibes!
  "The starter cue chains and vibes, HIT not tapping the tempo."
  []
  (state/init-state! (-> (domains/build-initial-state)
                         (templates/apply-starter-cue-chains)
                         (templates/apply-starter-vibes)
                         (assoc-in [:timing :bpm] 120.0)
                         (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)))
  (show-time/reset-runtime!))

(defn- hit-voice []
  (get-in (state/get-raw-state) [:vibe-runtime :voices :hit]))

(defn- ticks
  "The runtime after ticks 0 to n at 1/60 s, and the last tick's reports."
  [n]
  (let [rt (apply run-at (:runtime start) (map #(* % tick-ms) (range n)))]
    (step rt (* n tick-ms))))

(deftest decayed-run-reports-cue-ended-test
  (init-vibes!)
  (handle! {:event/type :vibe/press :role :hit :input-time 1000})
  (handle! {:event/type :vibe/release :role :hit :input-time 1090})
  (let [{:keys [cell run]} (hit-voice)
        report {:event/type :show-clock/cue-ended :cell cell :run run :position-ids {:restart nil :seek nil}}]
    (is (= {:run run :decay-beats 1.0 :hold? false} (get-in (state/get-raw-state) [:playback :active-cues cell]))
        "a tap: the intent lets the run go after its beat")
    (let [{rt29 :runtime reports29 :reports} (ticks 29)]
      (is (not (:ended? (get-in rt29 [:runs run]))))
      (is (empty? reports29))
      (is (= [cell] (map :cell (show-time/active-runs (state/get-raw-state) rt29))) "still rendered"))
    (let [{rt30 :runtime reports30 :reports} (ticks 30)]
      (is (true? (:ended? (get-in rt30 [:runs run]))) "30 ticks of 1/60 s at 120 BPM: 1.0 beat")
      (is (= [report] reports30))
      (is (empty? (show-time/active-runs (state/get-raw-state) rt30)) "an ended run isn't rendered")
      (run! events/event-handler reports30)
      (is (nil? (get-in (state/get-raw-state) [:playback :active-cues cell])))
      (is (false? (get-in (state/get-raw-state) [:playback :playing?]))))))

(deftest release-before-decay-lets-the-run-finish-test
  (init-vibes!)
  (handle! {:event/type :vibe/press :role :hit :input-time 1000})
  (let [{:keys [cell run]} (hit-voice)
        rt (run-at (:runtime start) 0.0 250.0 400.0)]
    (is (= true (get-in (state/get-raw-state) [:playback :active-cues cell :hold?])))
    (is (close? 0.8 (get-in rt [:runs run :accumulated-beats])))
    (testing "held past its beat, it plays on"
      (is (not (:ended? (get-in (run-at rt 600.0 1000.0) [:runs run])))))
    (handle! {:event/type :vibe/release :role :hit :input-time 1090})
    (is (= false (get-in (state/get-raw-state) [:playback :active-cues cell :hold?])) "a tap at 0.8 beats")
    (let [rt' (run-at rt 450.0)]
      (is (not (:ended? (get-in rt' [:runs run]))) "0.9 beats: the release didn't cut it")
      (let [{rt'' :runtime reports :reports} (step rt' 500.0)]
        (is (true? (:ended? (get-in rt'' [:runs run]))) "it ends at its beat")
        (is (= [{:event/type :show-clock/cue-ended :cell cell :run run :position-ids {:restart nil :seek nil}}] reports))))))

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


;; Tap reactions: the pulse clock and its stamps (plans/2026-11-18-vibe-mode/04 §7)

(deftest step-stamps-pulses-test
  (init-vibes!)
  (let [rt0 (run-at (:runtime start) 0.0 500.0)]
    (is (= [{} {}] [(:pulses rt0) (show-time/pulse-view rt0)]) "no press yet")
    (is (close? 1.0 (get-in rt0 [:pulse-clock :accumulated-beats])) "the pulse clock runs at the global BPM")
    (handle! {:event/type :vibe/press :role :hit :input-time 1000})
    (let [rt1 (run-at rt0 1000.0)
          {:keys [run]} (hit-voice)]
      (is (= {:id 1 :count 1 :press-beat 2.0 :press-ms 1000.0 :held? true :release-beat nil :recent [2.0]}
             (get-in rt1 [:pulses :hit]))
          "stamped at the tick after the press")
      (is (= [0.0 0.0] [(get-in rt1 [:runs run :accumulated-beats]) (get-in (show-time/pulse-view rt1) [:hit :beats])])
          "invariant 7: the press and the cue it starts reach the render at the same tick")
      (is (= 1 (get-in (show-time/pulse-view rt1) [:any :count])) "and :any")
      (handle! {:event/type :vibe/release :role :hit :input-time 1250})
      (let [rt2 (run-at rt1 1250.0)
            {:keys [beats ms held? since-release]} (:hit (show-time/pulse-view rt2))]
        (is (= [2.5 false] ((juxt :release-beat :held?) (get-in rt2 [:pulses :hit]))))
        (is (close? 0.5 beats))
        (is (close? 250.0 ms))
        (is (= [false 0.0] [held? since-release])))
      (testing "a clock that starts again doesn't replay the presses app state has"
        (show-time/reset-runtime! (state/get-raw-state))
        (let [rt (run-at @show-time/!runtime 2000.0 2500.0)]
          (is (= {} (show-time/pulse-view rt)))
          (handle! {:event/type :vibe/press :role :hit :input-time 3000})
          (is (= 1 (get-in (show-time/pulse-view (run-at rt 3000.0)) [:hit :count])) "the next press is new"))))))

(deftest restart-request-keeps-pulses-test
  (testing "Stop and Retrigger restart every clock but the pulse clock, and leave every stamp as it was"
    (init-vibes!)
    (let [rt0 (run-at (:runtime start) 0.0 500.0)
          _ (handle! {:event/type :vibe/press :role :flash :input-time 1000})
          rt1 (run-at rt0 1000.0)
          stamps (:pulses rt1)]
      (is (= 1 (get-in stamps [:flash :count])))
      (handle! {:event/type :transport/stop})
      (let [rt2 (run-at rt1 1250.0)]
        (is (close? 0.5 (get-in rt2 [:global :accumulated-beats])) "the global clock started again")
        (is (close? 2.5 (get-in rt2 [:pulse-clock :accumulated-beats])) "the pulse clock didn't")
        (is (= stamps (:pulses rt2)))
        (is (close? 0.5 (get-in (show-time/pulse-view rt2) [:flash :beats]))))
      (handle! {:event/type :transport/retrigger})
      (is (= stamps (:pulses (run-at rt1 1250.0 1500.0)))))))
