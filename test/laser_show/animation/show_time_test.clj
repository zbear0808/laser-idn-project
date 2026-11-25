(ns laser-show.animation.show-time-test
  "show-time/step: the clock's pure step from app state's intents and requests
   (plans/2026-11-17-show-clock/01 §7). App state is built by the real
   handlers through events/event-handler, so intents have their real shape."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.input.mtc :as mtc]
            [laser-show.input.mtc-test :as mtc-test]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.templates :as templates]
            [laser-show.timeline.shows :as shows]))

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

(def ^:private tl
  "The show the timeline cases play: a timeline is a library show, played by
   :timeline/* events under its key TL (plans/2026-11-25-timeline-shows 05
   §8.1)."
  #uuid "00000000-0000-0000-0000-0000000000a0")

(def ^:private TL (shows/play-key tl))

(def ^:private doc-id
  "The show open in init!'s state (reactivity plan 20's [:project :document-id])."
  #uuid "00000000-0000-0000-0000-00000000d0c1")

(defn- init!
  ([chains] (init! chains nil))
  ([chains shows]
   (state/init-state! (cond-> (-> (domains/build-initial-state)
                                  (assoc-in [:chains :cue-chains] chains)
                                  (assoc-in [:timing :bpm] 120.0)
                                  (assoc-in [:project :document-id] doc-id))
                        (some? shows) (assoc-in [:chains :shows] shows)))
   (show-time/reset-runtime!)))

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
  (init! {[0 0] circle} {tl (timeline {})})
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
      (handle! {:event/type :timeline/play :show-id tl :from 3.0})
      (is (= {:run (run-id TL) :timeline? true :start-beat 3.0}
             (get-in (state/get-raw-state) [:playback :active-cues TL])))
      (let [r2 (run-of (run-at rt0 1000.0) TL)]
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
  (init! {} {tl (timeline {})})
  (handle! {:event/type :timeline/play :show-id tl :from 2.0})
  (let [rt (run-at (:runtime start) 0.0)]
    (is (close? 2.0 (beats rt TL)))
    (handle! {:event/type :timeline/pause :show-id tl})
    (let [rt (run-at rt 500.0)]
      (is (close? 2.0 (beats rt TL)) "held")
      (handle! {:event/type :timeline/play :show-id tl})
      (is (close? 3.0 (beats (run-at rt 1000.0) TL)) "resumed from where it paused"))))

(deftest seek-request-applies-once-test
  (init! {} {tl (timeline {})})
  (handle! {:event/type :timeline/play :show-id tl :from 3.0})
  (let [rt (run-at (:runtime start) 0.0)]
    (handle! {:event/type :timeline/seek :show-id tl :beat 9.0})
    (let [{:keys [id beat]} (get-in (state/get-raw-state) [:playback :active-cues TL :seek])]
      (is (uuid? id))
      (is (= 9.0 beat)))
    (let [rt1 (run-at rt tick-ms)
          rt2 (run-at rt1 (* 2 tick-ms))]
      (is (close? (+ 9.0 (/ 1.0 30)) (beats rt1 TL)) "seeked, then advanced")
      (is (close? (+ 9.0 (/ 2.0 30)) (beats rt2 TL)) "the same intent again: a seek applies once"))))

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
  (init! {[0 0] circle} {tl (timeline {})})
  (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
  (handle! {:event/type :timeline/play :show-id tl :from 4.35})
  (let [rt (-> (run-at (:runtime start) 1000.0)
               (assoc :global {:accumulated-beats 12.35 :accumulated-ms 6175.0 :phase-offset 0.0
                               :phase-offset-target 0.0 :last-frame-time 1000.0})
               (with-runs {[0 0] {:accumulated-beats 4.35 :phase-offset 0.0 :phase-offset-target 0.0}}))]
    (handle! {:event/type :timing/tap-tempo :time 2000})
    (let [rt1 (run-at rt 1000.0)]
      (is (= 0.0 (:phase-offset (:global rt1))) "no snap at the tap")
      (is (close? -0.35 (:phase-offset-target (:global rt1))) "12.35 → 12.0, the shorter way")
      (is (close? -0.35 (:phase-offset-target (run-of rt1 [0 0]))))
      (is (= 0.0 (:phase-offset-target (run-of rt1 TL))) "a timeline doesn't move")
      (is (close? 4.35 (beats rt1 TL))))
    (testing "past the half beat it pulls forward"
      (handle! {:event/type :timing/tap-tempo :time 2500})
      (is (close? 0.2 (:phase-offset-target (:global (run-at (assoc-in rt [:global :accumulated-beats] 12.8)
                                                             1000.0))))))))

(deftest shift-request-adds-the-offset-test
  (init! {[0 0] circle} {tl (timeline {})})
  (handle! {:event/type :grid/trigger-cell :col 0 :row 0})
  (handle! {:event/type :timeline/play :show-id tl :from 2.0})
  (let [rt (-> (run-at (:runtime start) 1000.0)
               (assoc-in [:global :phase-offset] 0.1)
               (assoc-in [:global :phase-offset-target] 0.1)
               (with-runs {[0 0] {:phase-offset 0.05 :phase-offset-target 0.05}}))]
    (handle! {:event/type :timing/set-phase-offset-target :offset 0.25})
    (is (= 0.25 (get-in (state/get-raw-state) [:playback :requests :shift :offset])))
    (let [rt1 (run-at rt 1000.0)]
      (is (close? 0.35 (:phase-offset-target (:global rt1))))
      (is (close? 0.30 (:phase-offset-target (run-of rt1 [0 0]))))
      (is (= 0.0 (:phase-offset-target (run-of rt1 TL))) "timelines keep their position"))))

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
  (init! {} {tl (timeline {})})
  (handle! {:event/type :timeline/play :show-id tl :from 15.9})
  (let [r2 (run-id TL)
        report {:event/type :show-clock/cue-ended :cell TL :run r2 :position-ids {:restart nil :seek nil}}
        {rt1 :runtime reports1 :reports} (step (run-at (:runtime start) 0.0) 100.0)]
    (is (= [true 16.0] ((juxt :ended? :accumulated-beats) (run-of rt1 TL))) "held at its end")
    (is (= [report] reports1))
    (is (empty? (show-time/active-runs (state/get-raw-state) rt1)) "an ended run isn't rendered")
    (let [{rt2 :runtime reports2 :reports} (step rt1 200.0)
          {rt3 :runtime reports3 :reports} (step rt2 350.0)]
      (is (empty? reports2) "at most once every 250 ms")
      (is (= [report] reports3) "sent again while the intent is still there")
      (run! events/event-handler reports3)
      (is (nil? (get-in (state/get-raw-state) [:playback :active-cues TL])))
      (is (false? (get-in (state/get-raw-state) [:playback :playing?])))
      (let [{rt4 :runtime reports4 :reports} (step rt3 400.0)]
        (is (empty? reports4))
        (is (empty? (:runs rt4)))))))

(deftest report-for-an-old-run-changes-nothing-test
  (init! {} {tl (timeline {})})
  (handle! {:event/type :timeline/play :show-id tl})
  (let [r2 (run-id TL)]
    (handle! {:event/type :timeline/stop :show-id tl})
    (handle! {:event/type :timeline/play :show-id tl})
    (is (not= r2 (run-id TL)) "started again: a new run")
    (let [before (state/get-raw-state)]
      (events/event-handler {:event/type :show-clock/cue-ended :cell TL :run r2 :position-ids {:restart nil :seek nil}})
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
    (init! {} {tl (timeline {})})
    (handle! {:event/type :timeline/play :show-id tl :from 15.9})
    (let [ended {:event/type :show-clock/cue-ended :cell TL :run (run-id TL) :position-ids {:restart nil :seek nil}}
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
  (init! {[1 0] circle} {tl short-timeline})
  (handle! {:event/type :grid/trigger-cell :col 1 :row 0})
  (handle! {:event/type :timeline/play :show-id tl :from 0.2})
  (let [[a b] [TL [1 0]]
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
  (init! {} {tl (timeline {})})
  (handle! {:event/type :timeline/play :show-id tl :from 0.0})
  (handle! {:event/type :transport/retrigger})
  (let [a TL
        r (run-id a)
        k1 (restart-id)
        rt0 (run-at (:runtime start) 0.0)]
    (testing "example 4, by :timeline/seek: the end before a seek is no end of where it went"
      (handle! {:event/type :timeline/seek :show-id tl :beat 15.9})
      (let [s1 (:id (seek-of a))
            {rt1 :runtime queued :reports} (step (run-at rt0 0.0) 100.0)]
        (is (= [(ended a r k1 s1)] queued))
        (handle! {:event/type :timeline/seek :show-id tl :beat 2.0})
        (run! handle! queued)
        (is (= [true {a r} a] (transport)))
        (is (= 2.0 (:beat (seek-of a))) "still asked for")
        (is (not= s1 (:id (seek-of a))))
        (let [rt2 (run-at rt1 100.0)]
          (is (= r (run-id a)) "the same run")
          (is (close? 2.0 (beats rt2 a)) "at beat 2 on a step that adds no time")
          (testing "by Play :from on the playing timeline, then a seek to the same beat: each its own"
            (handle! {:event/type :timeline/play :show-id tl :from 15.9})
            (let [s3 (:id (seek-of a))
                  {rt3 :runtime queued :reports} (step (run-at rt2 100.0) 200.0)]
              (is (= [(ended a r k1 s3)] queued))
              (handle! {:event/type :timeline/seek :show-id tl :beat 15.9})
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
  (init! {} {tl short-timeline})
  (handle! {:event/type :timeline/play :show-id tl :from 0.0})
  (handle! {:event/type :transport/retrigger})
  (handle! {:event/type :timeline/seek :show-id tl :beat 0.2})
  (let [a TL
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
    (init! {} {tl short-timeline})
    (handle! {:event/type :timeline/play :show-id tl :from 0.2})
    (is (nil? (restart-id)))
    (let [rt (run-at (assoc-in (:runtime start) [:applied :restart] (random-uuid)) 0.0)
          {:keys [reports]} (step rt 50.0)]
      (is (= [(ended TL (run-id TL) nil nil)] reports))
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

;; Following timecode (plans/2026-11-23-cljfx-reactivity/15 §7, §8): an
;; episode belongs to one show and one version of its cue, and a report
;; applies only while the owner it found is still there

(def ^:private C [2 0])
(def ^:private B [1 0])

(def ^:private cs #uuid "00000000-0000-0000-0000-0000000000c0")

(def ^:private CS
  "C as a show: where a case edits or plays C with :timeline/* events, which
   address only shows. C itself is a grid cell, as an older file's timeline
   cell still is, for the cases the grid's own events reach."
  (shows/play-key cs))

(def ^:private follower
  "15 §7's cue: beat 0 at 3,600 s, locked at 120 BPM, 64 beats."
  (timeline {:timeline/chase :mtc :timeline/tc-start 3600.0 :timeline/tempo-bpm 120.0 :timeline/length 64.0}))

(def ^:private ms 1000000)

(def ^:private qf-decoder
  "01:00:05:12 at 25 fps, as 8 quarter-frames 10 ms apart from 0 ms: anchored
   at 3,605.55 s (the value and the forward 1.75 frames) when the last
   arrives, at 70 ms."
  (mtc-test/feed mtc/initial-decoder [0x0c 0x10 0x25 0x30 0x40 0x50 0x61 0x72] 25 0))

(defn- mtc-at
  "Where decoder (qf-decoder) has timecode t ms after its first quarter-frame."
  ([t] (mtc-at qf-decoder t))
  ([decoder t] (mtc/position decoder (long (* t ms)) nil)))

(defn- running [seconds] {:state :running :seconds seconds :fps 25})

(def ^:private stopped {:state :stopped :seconds nil :fps nil})

(defn- chase-id [rt cell] (get-in rt [:chase cell :chase-id]))

(defn- chase-at [rt cell] (get-in rt [:chase cell :accumulated-beats]))

(defn- intent [cell] (get-in (state/get-raw-state) [:playback :active-cues cell]))

(defn- version [cell] (get-in (state/get-raw-state) [:playback :chase-sources :cells cell]))

(defn- marker [k] {:chasing? true :timeline? true :chase-id k})

(defn- on-report
  "The On the clock sends for episode k at cell: the cue's version, the owner
   it found there and the episode's start authority."
  [cell version owner k start]
  {:event/type :show-clock/chase :cell cell :active? true :document-id doc-id
   :source-version version :expected-owner owner :chase-id k :start start})

(defn- off-report [cell version owner]
  {:event/type :show-clock/chase :cell cell :active? false :document-id doc-id
   :source-version version :expected-owner owner})

(defn- deliver!
  "App state after the show clock's reports, handled while the clock is at
   runtime rt (what :show-clock/read reads)."
  [rt reports]
  (with-redefs [show-time/!runtime (atom rt)]
    (run! events/event-handler reports))
  (state/get-raw-state))

(defn- follow!
  "Follow Timecode switched on or off for the show playing at k, as the
   inspector does."
  [k on?]
  (handle! {:event/type :timeline/apply-op :show-id (shows/key-show-id k)
            :op {:op :set-chase :source (when on? :mtc)}}))

(defn- drawn
  "How often each cell is drawn at rt with app state as it is now."
  [rt]
  (frequencies (map :cell (show-time/active-runs (state/get-raw-state) rt))))

(deftest chase-activates-and-reports-test
  (init! {C follower})
  (testing "example 1's samples, decoded"
    (is (= [:running :freewheel :stopped] (map (comp :state mtc-at) [70 370 670])))
    (is (every? true? (map #(close? %1 (:seconds (mtc-at %2))) [3605.55 3605.85 3605.55] [70 370 670]))))
  (let [{rt1 :runtime reports1 :reports} (step (:runtime start) 70.0 (mtc-at 70))
        k1 (chase-id rt1 C)]
    (is (close? 11.10 (chase-at rt1 C)))
    (is (true? (get-in rt1 [:chase C :chasing?])))
    (is (= [doc-id C 0 nil (:tick rt1)] k1)
        "the episode: the show, the cell, its cue's version, the clock's origin (none in a pure step) and its first tick")
    (is (= [(on-report C 0 :none k1 {:restart nil :allowed? true})] reports1))
    (is (= [C] (map :cell (show-time/active-runs (assoc-in (state/get-raw-state) [:playback :playing?] true) rt1)))
        "rendered before app state hears of it")
    (testing "the first On, held across a stopped tick before any marker: the transport stays idle"
      (let [{rt :runtime reports :reports} (step rt1 670.0 (mtc-at 670))
            before (state/get-raw-state)]
        (is (= [{} []] [(:chase rt) reports]) "nothing to retire")
        (is (identical? before (deliver! rt reports1)))))
    (deliver! rt1 reports1)
    (is (= (marker k1) (intent C)))
    (is (= [true {C nil} C] (transport)) "the first On from idle plays")
    (let [installed (state/get-raw-state)]
      (is (identical? installed (deliver! rt1 reports1)) "a duplicate changes nothing"))
    (let [{rt2 :runtime reports2 :reports} (step rt1 370.0 (mtc-at 370))
          {rt3 :runtime reports3 :reports} (step rt2 400.0 (mtc-at 370))]
      (testing "freewheel, then the same position again: the same episode and no other activation"
        (is (close? 11.70 (chase-at rt2 C)))
        (is (close? 11.70 (chase-at rt3 C)))
        (is (= k1 (chase-id rt2 C) (chase-id rt3 C)))
        (is (= [] reports2 reports3)))
      (testing "timecode stops: one Off at once, which takes the cue out and, the last, stops the transport"
        (let [{rt4 :runtime reports4 :reports} (step rt3 670.0 (mtc-at 670))]
          (is (= {} (:chase rt4)))
          (is (= [(off-report C 0 [:chase k1])] reports4))
          (deliver! rt4 reports4)
          (is (= [false {} nil] (transport)))
          (is (identical? (state/get-raw-state) (deliver! rt4 reports4)) "the duplicate changes nothing")
          (testing "; timecode again is another episode"
            (let [{rt5 :runtime reports5 :reports} (step rt4 1000.0 (running 3610.0))
                  k2 (chase-id rt5 C)]
              (is (close? 20.0 (chase-at rt5 C)))
              (is (not= k1 k2))
              (is (= [(on-report C 0 :none k2 {:restart nil :allowed? true})] reports5)))))))))

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
  (init! {} {tl (timeline {:items [{:id :a :type :preset :preset-id :circle :track-id :t :enabled? true
                                    :effects [] :timeline/start 0.0 :timeline/duration 4.0}]})})
  (handle! {:event/type :timeline/play :show-id tl :from 3.5})
  (let [{:keys [runtime reports]} (step (run-at (:runtime start) 1000.0) 1500.0)]
    (is (true? (:ended? (run-of runtime TL))) "past the last clip end (4 beats)")
    (is (= [:show-clock/cue-ended] (map :event/type reports)))
    (run! events/event-handler reports)
    (is (false? (get-in (state/get-raw-state) [:playback :playing?]))))
  (testing "with the loop on it keeps going"
    (state/swap-state! assoc-in [:chains :shows tl :loop] {:enabled? true :start 0.0 :duration 4.0})
    (handle! {:event/type :timeline/play :show-id tl :from 3.5})
    (is (close? 0.5 (beats (run-at (:runtime start) 1000.0 1500.0) TL)))))

(deftest locked-tempo-ignores-global-bpm-test
  (init! {} {tl (timeline {:timeline/tempo-bpm 60.0 :timeline/length 100.0})})
  (state/swap-state! assoc-in [:timing :bpm] 180.0)
  (handle! {:event/type :timeline/play :show-id tl :from 0.0})
  (is (close? 1.0 (beats (run-at (:runtime start) 1000.0 2000.0) TL))
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
    (is (nil? (chase-beats (:runtime (step rt2 1600.0 {:state :stopped :seconds 3605.48 :fps 25})))))
    (testing "its window is [0, 64) beats: 3,600 s is in, 3,632 s (beat 64) is out"
      (is (= [false true true false]
             (map #(some? (chase-beats (:runtime (step (:runtime start) 0.0 (running %)))))
                  [3599.99 3600.0 3631.99 3632.0]))))
    (testing "a locate alone doesn't start it"
      (let [located (mtc/step-full-frame mtc/initial-decoder [0x7F 0x7F 0x01 0x01 (bit-or 0x20 1) 0 20 0 0xF7] 0)
            pos (mtc/position located (* 1000 ms) nil)]
        (is (= [:stopped 3620.0] ((juxt :state :seconds) pos)) "known (beat 40), not running")
        (is (= {} (:chase (:runtime (step (:runtime start) 0.0 pos)))))))
    (testing "a locate back while timecode runs: the same episode, where timecode went"
      (let [back (-> qf-decoder
                     (mtc/step-full-frame [0x7F 0x7F 0x01 0x01 (bit-or 0x20 1) 0 2 0 0xF7] (* 80 ms))
                     (mtc-test/feed (mtc-test/qf-bytes {:h 1 :m 0 :s 2 :f 0} 25) 25 (* 90 ms)))
            ra (:runtime (step (:runtime start) 70.0 (mtc-at 70)))
            rb (:runtime (step ra 160.0 (mtc-at back 160)))]
        (is (close? 11.10 (chase-beats ra)))
        (is (= :running (:state (mtc-at back 160))))
        (is (close? 4.14 (chase-beats rb)) "3,602.07 s")
        (is (= (chase-id ra [0 0]) (chase-id rb [0 0])))))))

(deftest non-chasing-cues-ignore-mtc-test
  (init! {} {tl (timeline {:timeline/length 100.0})})
  (handle! {:event/type :timeline/play :show-id tl :from 2.0})
  (let [mtc {:state :running :seconds 50.0 :fps 25}
        rt (:runtime (step (:runtime (step (:runtime start) 1000.0 mtc)) 1500.0 mtc))]
    (is (close? 3.0 (beats rt TL)) "advanced by its own clock (+1 beat), not set to 100")
    (is (empty? (:chase rt)))))

(deftest chase-takes-over-a-playing-cue-test
  ;; Locked at the global 120 BPM: a show needs one to follow timecode (TS-6)
  (testing "a cue playing when Follow is switched on stops when no timecode runs"
    (init! {} {tl (timeline {:timeline/length 64.0 :timeline/tempo-bpm 120.0})})
    (handle! {:event/type :timeline/play :show-id tl :from 2.0})
    (let [r1 (run-id TL)
          rt (run-at (:runtime start) 0.0)]
      (follow! TL true)
      (let [v (version TL)
            {rt' :runtime reports :reports} (step rt 16.0 stopped)]
        (is (pos-int? v) "the edit gave the follower a version")
        (is (= r1 (:run (intent TL))) "and left the run for the clock to settle")
        (is (empty? (:runs rt')) "the timecode drives it now")
        (is (empty? (show-time/active-runs (state/get-raw-state) rt')))
        (is (= [(off-report TL v [:run r1])] reports))
        (deliver! rt' reports)
        (is (nil? (intent TL)))
        (is (false? (get-in (state/get-raw-state) [:playback :playing?])) "its last cue"))))
  (testing "while timecode runs inside it, timecode takes it over"
    (init! {} {tl (timeline {:timeline/length 64.0 :timeline/tempo-bpm 120.0})})
    (handle! {:event/type :timeline/play :show-id tl :from 2.0})
    (let [r1 (run-id TL)
          rt (run-at (:runtime start) 0.0)]
      (follow! TL true)
      (let [v (version TL)
            {rt' :runtime reports :reports} (step rt 16.0 (running 10.0))
            k (chase-id rt' TL)]
        (is (close? 20.0 (chase-at rt' TL)))
        (is (empty? (:runs rt')))
        (is (= {TL 1} (drawn rt')) "drawn once, at the timecode, before app state hears of it")
        (is (= [(on-report TL v [:run r1] k {:restart nil :allowed? true})] reports))
        (deliver! rt' reports)
        (is (= (marker k) (intent TL)))
        (is (= [true {TL nil} TL] (transport)))
        (is (= [] (:reports (step rt' 32.0 (running 10.016)))) "app state agrees")))))

(deftest active-runs-keep-the-intents-order-test
  (init! {[0 0] circle [1 0] circle [2 0] circle})
  (doseq [col [1 0 2]] (handle! {:event/type :grid/trigger-cell :col col :row 0}))
  ;; [2 0]'s chain loses its items while it plays
  (state/swap-state! assoc-in [:chains :cue-chains [2 0] :items] [])
  (let [state (state/get-raw-state)
        rt (run-at (:runtime start) 0.0)]
    (is (= [[1 0] [0 0] [2 0]] (keys (get-in state [:playback :active-cues])))
        "every cell has an intent, [2 0]'s with no items")
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


;; Following timecode, continued: what an edit, Stop and a later report do
;; (plans/2026-11-23-cljfx-reactivity/15 §7 examples 2-5, §8)

(defn- b-and-c-playing!
  "B's circle triggered, then C's follower taken by timecode at 3,605.55 s
   (example 2's C:k1, B:b): the runtime of that tick. c is where the
   follower is: the grid cell C, or a show's key (CS)."
  ([] (b-and-c-playing! C))
  ([c]
   (if (shows/play-key? c)
     (init! {B circle} {(shows/key-show-id c) follower})
     (init! {c follower B circle}))
   (handle! {:event/type :grid/trigger-cell :col 1 :row 0})
   (let [{rt :runtime reports :reports} (step (:runtime start) 70.0 (mtc-at 70))]
     (deliver! rt reports)
     rt)))

(deftest follow-off-and-replace-prune-chase-test
  ;; The follower as a show: these cases edit and play it with :timeline/*
  ;; events
  (let [rt1 (b-and-c-playing! CS)
        k1 (chase-id rt1 CS)
        b (run-id B)
        playing (state/get-raw-state)]
    (is (= [true {B b CS nil} CS] (transport)) "C:k1, B:b")
    (testing "example 2: Follow off while timecode runs, then Play from beat 3 before the next tick"
      (follow! CS false)
      (is (= [true {B b} B] (transport)) "only B is left, and plays on")
      (is (nil? (version CS)))
      (handle! {:event/type :timeline/play :show-id cs :from 3.0})
      (let [r2 (run-id CS)
            {rt :runtime reports :reports} (step rt1 100.0 (mtc-at 100))]
        (is (= {:run r2 :timeline? true :start-beat 3.0} (intent CS)) "C has just r2")
        (is (= [{} #{b r2}] [(:chase rt) (set (keys (:runs rt)))]))
        (is (close? 3.0 (get (show-time/positions rt) CS)) "at its start")
        (is (= [B CS] (map :cell (show-time/active-runs (state/get-raw-state) rt))))
        (is (= {B 1 CS 1} (drawn rt)))
        (is (= [] reports))
        (let [before (state/get-raw-state)]
          (is (identical? before (deliver! rt [(off-report CS nil [:chase k1])]))
              "an old Off for k1 leaves both"))))
    (testing "example 4: C's follower X replaced by Y, beat 0 at 3,604 s, while timecode runs at 3,605.55 s"
      (state/reset-state! playing)
      (let [{rt-x :runtime x-reports :reports} (step rt1 100.0 stopped)]
        (is (= [(off-report CS 0 [:chase k1])] x-reports) "X's Off, held")
        (handle! {:event/type :timeline/apply-op :show-id cs :op {:op :set-tc-start :seconds 3604.0}})
        (let [v (version CS)
              {rt :runtime reports :reports} (step rt1 100.0 (running 3605.55))
              k2 (chase-id rt CS)]
          (is (pos-int? v) "Y is another version")
          (is (= (marker k1) (intent CS)) "the marker stays for the clock to rebind")
          (is (close? 3.10 (chase-at rt CS)) "one Y, at beat 3.10")
          (is (= [doc-id CS v] (subvec k2 0 3)) "a fresh episode")
          (is (not= k1 k2))
          (is (= {B 1 CS 1} (drawn rt)) "C drawn once, in its marker's place")
          (is (= [(on-report CS v [:chase k1] k2 {:restart nil :allowed? true})] reports))
          (let [before (state/get-raw-state)]
            (is (identical? before (deliver! rt-x x-reports)) "X's Off fails the version guard"))
          (deliver! rt reports)
          (is (= (marker k2) (intent CS)))
          (is (= [true {B b CS nil} CS] (transport)) "rebound: the focus and the transport as they were"))))
    (testing "Follow off, then Undo and Redo: one edit; timecode takes C again only while it runs; reports aren't edits"
      (state/reset-state! playing)
      (let [undos (count (get-in (state/get-raw-state) [:history :undo-stack]))]
        (follow! CS false)
        (is (= (inc undos) (count (get-in (state/get-raw-state) [:history :undo-stack]))))
        (is (= "Set Follow Timecode" (history/undo-label (:history (state/get-raw-state)))))
        (handle! {:event/type :edit/undo})
        (let [v (version CS)
              undone (state/get-raw-state)]
          (is (pos-int? v) "the follower back, as another version")
          (is (= [true {B b} B] (transport)))
          (is (= [] (:reports (step rt1 100.0 stopped))) "timecode stopped: nothing to take")
          (let [{rt :runtime reports :reports} (step rt1 100.0 (mtc-at 100))
                k (chase-id rt CS)]
            (is (not= k1 k))
            (is (= [(on-report CS v :none k {:restart nil :allowed? true})] reports))
            (deliver! rt reports)
            (is (= (marker k) (intent CS)))
            (is (= ((juxt :history :project) undone) ((juxt :history :project) (state/get-raw-state)))
                "the report changed neither the history nor the unsaved flag")
            (handle! {:event/type :edit/redo})
            (is (= [true {B b} B] (transport)) "Redo is Follow off again")
            (is (nil? (version CS)))))))))

(deftest clear-paste-and-move-prune-chase-test
  ;; The follower as a grid cell (an older file's timeline cell): these
  ;; cases change it with the grid's own events
  (let [rt1 (b-and-c-playing!)
        k1 (chase-id rt1 C)
        b (run-id B)
        playing (state/get-raw-state)
        positioned (fn [rt] (set (keys (show-time/positions rt))))]
    (is (= [true {B b C nil} C] (transport)) "C:k1, B:b")
    (testing "a snapshot that lost C's cue but kept its marker: the Off for that marker"
      (is (= [(off-report C nil [:chase k1])]
             (:reports (show-time/step rt1 (update-in playing [:chains :cue-chains] dissoc C) (mtc-at 100) 100.0)))))
    (testing "clearing C instead: no C position, nothing drawn there, no Off"
      (state/reset-state! playing)
      (handle! {:event/type :grid/clear-cell :col 2 :row 0})
      (is (= [true {B b} B] (transport)))
      (let [{rt :runtime reports :reports} (step rt1 100.0 (mtc-at 100))]
        (is (= [{} #{b}] [(:chase rt) (set (keys (:runs rt)))]))
        (is (= #{B} (positioned rt)))
        (is (= {B 1} (drawn rt)))
        (is (= [] reports) "Clear took the marker already")))
    (testing "B's circle pasted over C: the follower's marker goes with it"
      (state/reset-state! playing)
      (handle! {:event/type :grid/copy-cell :col 1 :row 0})
      (handle! {:event/type :grid/paste-cell :col 2 :row 0})
      (is (= [:circle nil] ((juxt (comp :preset-id first :items) :timeline/chase)
                            (get-in (state/get-raw-state) [:chains :cue-chains C]))))
      (is (= [true {B b} B] (transport)))
      (is (nil? (version C)))
      (let [{rt :runtime reports :reports} (step rt1 100.0 (mtc-at 100))]
        (is (= {} (:chase rt)))
        (is (= #{B} (positioned rt)))
        (is (= {B 1} (drawn rt)))
        (is (= [] reports))))
    (testing "C moved to D: its marker moves with it, not ended, and is rebound there"
      (state/reset-state! playing)
      (handle! {:event/type :grid/move-cell :from-col 2 :from-row 0 :to-col 3 :to-row 0})
      (let [D [3 0]
            v (version D)
            {rt :runtime reports :reports} (step rt1 100.0 (mtc-at 100))
            kD (chase-id rt D)]
        (is (= [true {B b D nil} D] (transport)) "the move kept both cues and the transport")
        (is (= (marker k1) (intent D)))
        (is (= [nil true] [(version C) (pos-int? v)]))
        (is (= [#{D} #{B D}] [(set (keys (:chase rt))) (positioned rt)]) "nothing at C")
        (is (= [doc-id D v] (subvec kD 0 3)))
        (is (close? 11.16 (chase-at rt D)))
        (is (= {B 1 D 1} (drawn rt)))
        (is (= [(on-report D v [:chase k1] kD {:restart nil :allowed? true})] reports))
        (deliver! rt reports)
        (is (= (marker kD) (intent D)))
        (is (= [true {B b D nil} D] (transport)))))
    (testing "the only cue, cleared: the transport stops, once"
      (init! {C follower})
      (let [{rt :runtime reports :reports} (step (:runtime start) 70.0 (mtc-at 70))]
        (deliver! rt reports)
        (is (= [true {C nil} C] (transport)))
        (handle! {:event/type :grid/clear-cell :col 2 :row 0})
        (is (= [false {} nil] (transport)))
        (let [{rt' :runtime reports' :reports} (step rt 100.0 (mtc-at 100))]
          (is (= [{} {} []] [(:chase rt') (show-time/positions rt') reports'])))))))

(deftest stale-chase-off-cannot-retire-a-restart-test
  (init! {} {cs (timeline {:timeline/length 64.0 :timeline/tempo-bpm 120.0})})
  ;; As the start-timeline trigger does (handlers/triggers.clj)
  (handle! {:event/type :timeline/play :show-id cs})
  (let [r1 (run-id CS)
        rt0 (run-at (:runtime start) 0.0)
        _ (follow! CS true)
        v1 (version CS)
        queued (state/get-raw-state)
        {rt1 :runtime reports :reports} (step rt0 16.0 stopped)]
    (is (= [(off-report CS v1 [:run r1])] reports) "example 3: Follow on while timecode is stopped")
    (testing "delivered now: r1 stops, and no C timing remains"
      (deliver! rt1 reports)
      (is (= [false {} nil] (transport)))
      (let [rt (:runtime (step rt1 32.0 stopped))]
        (is (= [{} {} {}] [(:runs rt) (:chase rt) (show-time/positions rt)]))))
    (testing "delivered after Follow off, timeline Stop and Play, and Follow on again: the new run survives"
      (state/reset-state! queued)
      (follow! CS false)
      (is (= r1 (:run (intent CS))) "Follow off leaves a run alone")
      (handle! {:event/type :timeline/stop :show-id cs})
      (handle! {:event/type :timeline/play :show-id cs})
      (follow! CS true)
      (let [r2 (run-id CS)
            before (state/get-raw-state)]
        (is (not= r1 r2))
        (is (= [true {CS r2} CS] (transport)))
        (is (< v1 (version CS)) "following again: another version")
        (is (identical? before (deliver! rt1 reports)) "its whole intent, the focus and the transport")))
    (testing "the owner guard alone: the same show, version and absent episode, another run in r1's place"
      (state/reset-state! (assoc-in queued [:playback :active-cues CS :run] (random-uuid)))
      (let [before (state/get-raw-state)]
        (is (identical? before (deliver! rt1 reports)))))
    (testing "nor once a later episode took the cell over"
      (state/reset-state! queued)
      (let [{rt2 :runtime ons :reports} (step rt1 300.0 (running 10.0))
            k (chase-id rt2 CS)]
        (is (= [(on-report CS v1 [:run r1] k {:restart nil :allowed? true})] ons))
        (deliver! rt2 ons)
        (is (= (marker k) (intent CS)) "taken over")
        (let [before (state/get-raw-state)]
          (is (identical? before (deliver! rt2 reports)) "the clock on that episode")
          (is (identical? before (deliver! rt1 reports)) "the clock on none"))))
    (testing "a missing owner is no :none"
      (state/reset-state! (update queued :playback cue-timing/deactivate-cue CS))
      (let [{rt2 :runtime [on :as ons] :reports} (step rt1 300.0 (running 10.0))
            before (state/get-raw-state)]
        (is (= [:none {:restart nil :allowed? true}] ((juxt :expected-owner :start) on)))
        (is (identical? before (deliver! rt2 [(dissoc on :expected-owner)])))
        (deliver! rt2 ons)
        (is (= (marker (:chase-id on)) (intent CS)) "the same On, with its :none")
        (is (= [true {CS nil} CS] (transport)))))
    (testing "a pending report goes again after 250 ms, a changed owner's at once"
      (state/reset-state! queued)
      (let [{rt-a :runtime a :reports} (step rt0 16.0 stopped)
            {rt-b :runtime b :reports} (step rt-a 200.0 stopped)
            {rt-c :runtime c :reports} (step rt-b 266.0 stopped)
            r2 (random-uuid)
            _ (state/reset-state! (assoc-in queued [:playback :active-cues CS :run] r2))
            {d :reports} (step rt-c 300.0 stopped)]
        (is (= [reports [] reports] [a b c]) "the same report, at 16 and 266 ms")
        (is (= [(off-report CS v1 [:run r2])] d) "34 ms later, for another run")))))

(deftest chase-reports-respect-explicit-stop-test
  (testing "example 5: B playing; C's first On held across Stop"
    (init! {B circle} {cs follower})
    (handle! {:event/type :grid/trigger-cell :col 1 :row 0})
    (let [b (run-id B)
          {rt1 :runtime ons :reports} (step (:runtime start) 70.0 (mtc-at 70))
          k1 (chase-id rt1 CS)]
      (is (= [(on-report CS 0 :none k1 {:restart nil :allowed? true})] ons)
          "the start authority as timecode entered: the transport played")
      (handle! {:event/type :transport/stop})
      (is (= [false {B b} B] (transport)))
      (let [{rt2 :runtime retry :reports} (step rt1 330.0 (mtc-at 330))]
        (is (= ons retry) "its retry, past 250 ms, is the same report")
        (deliver! rt2 (concat ons retry))
        (is (= [false {B b CS nil} CS] (transport)) "C is held, as B is: the gate stays closed")
        (is (= (marker k1) (intent CS)))
        (testing "; timecode running on past the retry interval reopens nothing"
          (let [{rt3 :runtime r3 :reports} (step rt2 600.0 (running 3606.0))
                {rt4 :runtime r4 :reports} (step rt3 900.0 (running 3606.3))]
            (is (= [] r3 r4))
            (is (= k1 (chase-id rt4 CS)))
            (testing "; a revision of C's cue rebinds its held marker, and the gate stays closed"
              (handle! {:event/type :timeline/apply-op :show-id cs :op {:op :set-tempo :bpm 100.0}})
              (let [{rt5 :runtime r5 :reports} (step rt4 950.0 (running 3606.35))
                    k2 (chase-id rt5 CS)]
                (is (= [(on-report CS (version CS) [:chase k1] k2 {:restart (restart-id) :allowed? false})] r5)
                    "authority frozen at its entry: Stop held B")
                (deliver! rt5 r5)
                (is (= (marker k2) (intent CS)))
                (is (= [false {B b CS nil} CS] (transport)))
                (testing "; timecode stops: its Off, twice, takes C out and leaves B held"
                  (let [{rt6 :runtime offs :reports} (step rt5 1500.0 stopped)]
                    (is (= [(off-report CS (version CS) [:chase k2])] offs))
                    (deliver! rt6 (concat offs offs))
                    (is (= [false {B b} B] (transport)))
                    (handle! {:event/type :transport/play})
                    (is (= [true {B b} B] (transport)))
                    (is (= [B] (map :cell (show-time/active-runs (state/get-raw-state) (run-at rt6 1516.0))))
                        "Play: exactly B"))))))))))
  (testing "the last cue: held, retired, then an empty Play"
    (init! {} {cs follower})
    (let [{rt1 :runtime ons :reports} (step (:runtime start) 70.0 (mtc-at 70))]
      (deliver! rt1 ons)
      (is (= [true {CS nil} CS] (transport)) "timecode entering an idle transport plays")
      (handle! {:event/type :transport/stop})
      (is (= [false {CS nil} CS] (transport)))
      (let [{rt2 :runtime offs :reports} (step rt1 670.0 (mtc-at 670))]
        (deliver! rt2 offs)
        (is (= [false {} nil] (transport)) "its removal, the last")
        (handle! {:event/type :transport/play})
        (is (= [true {} nil] (transport)) "Play opens the empty transport")
        (deliver! rt2 offs)
        (is (= [true {} nil] (transport)) "a stale Off leaves that open gate alone")
        (testing "; Stop again, then timecode again: a fresh entry from idle plays"
          (handle! {:event/type :transport/stop})
          (let [{rt3 :runtime ons' :reports} (step rt2 1000.0 (running 3610.0))]
            (is (= {:restart (restart-id) :allowed? true} (:start (first ons'))))
            (deliver! rt3 ons')
            (is (= [true {CS nil} CS] (transport)))))))))

(deftest stop-outlasts-an-old-end-and-a-chase-cleanup-test
  (testing "00-overview's 03 → 15 check: after Stop with two intents, an end from before it and a matching
            chase cleanup open nothing, and a newer run at the cleaned cell outlasts both"
    (init! {} {tl short-timeline cs follower})
    (handle! {:event/type :timeline/play :show-id tl :from 0.2})
    (let [a TL
          r (run-id a)
          {rt1 :runtime ons :reports} (step (:runtime start) 0.0 (running 3605.55))
          k1 (chase-id rt1 CS)]
      (deliver! rt1 ons)
      (let [{rt2 :runtime ends :reports} (step rt1 50.0 (running 3605.6))]
        (is (= [true {a r CS nil} CS] (transport)) "two intents")
        (is (= [(ended a r nil nil)] ends) "A's end, seen before Stop")
        (handle! {:event/type :transport/stop})
        (run! handle! ends)
        (is (= [false {a r CS nil} CS] (transport)) "the earlier completion: both held")
        (let [{rt3 :runtime cleanup :reports} (step rt2 60.0 stopped)]
          (is (= [(off-report CS 0 [:chase k1])] cleanup) "timecode stopped: C's cleanup")
          (deliver! rt3 cleanup)
          (is (= [false {a r} a] (transport)) "the matching cleanup takes C out, and A stays held")
          (testing "; a newer run at C (Follow off, Play) outlasts both, delivered again"
            (follow! CS false)
            (handle! {:event/type :timeline/play :show-id cs})
            (let [r2 (run-id CS)
                  playback (:playback (state/get-raw-state))]
              (is (= [true {a r CS r2} CS] (transport)) "Play is what plays")
              (deliver! rt3 (concat ends cleanup))
              (is (= playback (:playback (state/get-raw-state)))))))))))

(deftest a-press-stops-a-chased-cue-until-timecode-enters-again-test
  ;; Follow-up B7, decided as "stop until timecode re-enters". Before, a
  ;; press on a cue following running timecode took its marker out, and the
  ;; clock's next On put it back: it couldn't be turned off
  (let [rt1 (b-and-c-playing!)
        k1 (chase-id rt1 C)
        b (run-id B)
        press! (fn [cell] (handle! {:event/type :grid/trigger-cell :col (first cell) :row (second cell)}))
        stopped-for #(get-in (state/get-raw-state) [:playback :chase-stopped])
        history-and-dirty #((juxt :history (comp :dirty? :project)) (state/get-raw-state))
        before-press (history-and-dirty)]
    (is (= [true {B b C nil} C] (transport)) "C:k1 follows timecode, B plays")
    (press! C)
    (let [pressed (state/get-raw-state)]
      (is (= [true {B b} B] (transport)) "pressed: C stops, B plays on")
      (is (= {C k1} (stopped-for)) "for k1, its episode")
      (is (= before-press (history-and-dirty)) "no edit: no undo step, nothing unsaved")
      (testing "timecode runs on: the same episode, which the clock neither reports nor draws"
        (let [{rt2 :runtime r2 :reports} (step rt1 370.0 (mtc-at 370))
              {rt3 :runtime r3 :reports} (step rt2 700.0 (running 3606.2))]
          (is (= k1 (chase-id rt2 C) (chase-id rt3 C)))
          (is (= [[] []] [r2 r3]))
          (is (= [{B 1} {B 1}] [(drawn rt2) (drawn rt3)]))
          (is (not-any? #(contains? (show-time/positions %) C) [rt2 rt3]))
          (is (identical? pressed (deliver! rt3 [(on-report C 0 :none k1 {:restart nil :allowed? true})]))
              "a late On for k1 changes nothing")))
      (testing "timecode stops, then runs again: a new episode, which plays"
        (state/reset-state! pressed)
        (let [{rt2 :runtime offs :reports} (step rt1 670.0 (mtc-at 670))
              {rt3 :runtime ons :reports} (step rt2 1000.0 (running 3610.0))
              k2 (chase-id rt3 C)]
          (is (= [] offs) "nothing to take out")
          (is (not= k1 k2))
          (is (= [(on-report C 0 :none k2 {:restart nil :allowed? true})] ons))
          (deliver! rt3 ons)
          (is (= (marker k2) (intent C)))
          (is (= [true {B b C nil} C] (transport)))
          (is (= {} (stopped-for)) "C's stop is over")
          (is (= {B 1 C 1} (drawn rt3)))))
      (testing "pressed again while stopped: it follows k1 again, from the next tick"
        (state/reset-state! pressed)
        (press! C)
        (is (= {} (stopped-for)))
        (is (= [true {B b} B] (transport)) "until the clock says where timecode is")
        (let [{rt2 :runtime ons :reports} (step rt1 370.0 (mtc-at 370))]
          (is (= [(on-report C 0 :none k1 {:restart nil :allowed? true})] ons))
          (deliver! rt2 ons)
          (is (= (marker k1) (intent C)))
          (is (= [true {B b C nil} C] (transport)))
          (is (= {B 1 C 1} (drawn rt2)))
          (is (= before-press (history-and-dirty)))))
      (testing "the only cue: its stop closes the transport, and k1 doesn't open it again"
        (state/reset-state! pressed)
        (press! B)
        (is (= [false {} nil] (transport)) "B stopped, the last")
        (is (= {C k1} (stopped-for)) "C's stop is C's")
        (let [{rt2 :runtime r2 :reports} (step rt1 370.0 (mtc-at 370))]
          (is (= [] r2))
          (is (= [false {} nil] (transport))))
        (press! B)
        (is (= [true {B (run-id B)} B] (transport)) "a cue that doesn't follow timecode toggles as before")
        (is (= {C k1} (stopped-for))))
      (testing "New and Open let go of it"
        (doseq [[label event] [["New" {:event/type :file/new-project}]
                               ["Open" {:event/type :file/project-loaded :file-path "/tmp/show.zip"
                                        :contents (persistent/project-files pressed)}]]]
          (state/reset-state! pressed)
          (handle! event)
          (is (= {} (stopped-for)) label))))))


;; Shows play under [:show id] beside the grid's cells
;; (plans/2026-11-25-timeline-shows/03 §8, §10): the same runs, ends, chase
;; and reports, the cue from [:chains :shows id]

(def ^:private u1 #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private u2 #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private S1 [:show u1])
(def ^:private S2 [:show u2])

(defn- start-show!
  "Show key k started as run r, as cue-timing/activate-cue writes it."
  [k r opts]
  (state/swap-state! update :playback cue-timing/activate-cue k r opts))

(defn- installed!
  "Show u1, following timecode, taken by it at 3,605.55 s and installed:
   [runtime k1]."
  []
  (init! {} {u1 follower})
  (let [{rt :runtime reports :reports} (step (:runtime start) 70.0 (mtc-at 70))]
    (deliver! rt reports)
    [rt (chase-id rt S1)]))

(deftest show-key-runs-and-ends-test
  (testing "E1: a manual show runs at its own tempo and ends at its length"
    (init! {} {u1 (timeline {:timeline/tempo-bpm 120.0 :timeline/length 8.0})})
    (let [r (random-uuid)]
      (start-show! S1 r {:timeline? true :start-beat 0.0})
      (let [{rt :runtime reports :reports} (step (run-at (:runtime start) 0.0 1000.0 2000.0 3000.0) 4000.0)
            run (get-in rt [:runs r])]
        (is (= [S1 true true 8.0] ((juxt :cell :timeline? :ended? :accumulated-beats) run)))
        (is (= [{:event/type :show-clock/cue-ended :cell S1 :run r :position-ids {:restart nil :seek nil}}]
               reports))
        (is (empty? (show-time/active-runs (state/get-raw-state) rt)) "an ended show isn't rendered")
        (run! events/event-handler reports)
        (is (= [false {} nil] (transport))))))
  (testing "the show's locked tempo, not the global BPM, and it renders"
    (init! {} {u1 (timeline {:timeline/tempo-bpm 60.0 :timeline/length 100.0})})
    (let [r (random-uuid)]
      (start-show! S1 r {:timeline? true :start-beat 0.0})
      (let [rt (run-at (:runtime start) 0.0 1000.0)]
        (is (close? 1.0 (get-in rt [:runs r :accumulated-beats])) "1 s at 60 BPM, the global 120 ignored")
        (is (= {S1 (get-in rt [:runs r :accumulated-beats])} (show-time/positions rt)))
        (is (= [[S1 r]] (map (juxt :cell :run) (show-time/active-runs (state/get-raw-state) rt))))
        (is (= (get-in (state/get-raw-state) [:chains :shows u1])
               (:cue-chain (first (show-time/active-runs (state/get-raw-state) rt))))))))
  (testing "a show key never reads the grid's cue at the same key"
    (init! {S1 (timeline {:timeline/tempo-bpm 60.0 :timeline/length 100.0})} {})
    (let [r (random-uuid)]
      (start-show! S1 r {:timeline? true :start-beat 0.0})
      (let [rt (run-at (:runtime start) 0.0 1000.0)]
        (is (close? 2.0 (get-in rt [:runs r :accumulated-beats])) "no show: the global BPM")
        (is (empty? (show-time/active-runs (state/get-raw-state) rt)) "nothing to draw")))))

(deftest show-key-chases-mtc-test
  (init! {} {u1 follower})
  (let [{rt1 :runtime reports1 :reports} (step (:runtime start) 70.0 (mtc-at 70))
        k1 (show-time/chase-id rt1 S1)]
    (testing "E2: On"
      (is (close? 11.10 (chase-at rt1 S1)))
      (is (= [doc-id S1 0 nil (:tick rt1)] k1) "version 0: no edit versioned it")
      (is (= [(on-report S1 0 :none k1 {:restart nil :allowed? true})] reports1))
      (is (= {S1 1} (drawn rt1)) "drawn before app state hears of it")
      (deliver! rt1 reports1)
      (is (= (marker k1) (intent S1)))
      (is (= [true {S1 nil} S1] (transport)))
      (is (= {S1 1} (drawn rt1)) "drawn once, in its marker's place")
      (is (= [] (:reports (step rt1 370.0 (mtc-at 370)))) "app state agrees"))
    (testing "E3: timecode stops: Off"
      (let [{rt2 :runtime reports2 :reports} (step rt1 670.0 (mtc-at 670))]
        (is (= {} (:chase rt2)))
        (is (= [(off-report S1 0 [:chase k1])] reports2))
        (deliver! rt2 reports2)
        (is (= [false {} nil] (transport)))))))

(deftest show-follower-takes-over-its-manual-run-test
  (testing "a show playing when it starts following stops when no timecode runs"
    (init! {} {u1 (timeline {:timeline/length 64.0})})
    (let [r1 (random-uuid)]
      (start-show! S1 r1 {:timeline? true :start-beat 2.0})
      (let [rt (run-at (:runtime start) 0.0)]
        (is (contains? (:runs rt) r1))
        (state/swap-state! assoc-in [:chains :shows u1] follower)
        (let [{rt' :runtime reports :reports} (step rt 16.0 stopped)]
          (is (empty? (:runs rt')) "timecode drives it now")
          (is (empty? (show-time/active-runs (state/get-raw-state) rt')))
          (is (= [(off-report S1 0 [:run r1])] reports))
          (deliver! rt' reports)
          (is (= [false {} nil] (transport)))))))
  (testing "while timecode runs inside it, timecode takes it over"
    (init! {} {u1 (timeline {:timeline/length 64.0})})
    (let [r1 (random-uuid)]
      (start-show! S1 r1 {:timeline? true :start-beat 2.0})
      (let [rt (run-at (:runtime start) 0.0)]
        (state/swap-state! assoc-in [:chains :shows u1] follower)
        (let [{rt' :runtime reports :reports} (step rt 16.0 (running 3610.0))
              k (chase-id rt' S1)]
          (is (close? 20.0 (chase-at rt' S1)))
          (is (empty? (:runs rt')))
          (is (= {S1 1} (drawn rt')))
          (is (= [(on-report S1 0 [:run r1] k {:restart nil :allowed? true})] reports))
          (deliver! rt' reports)
          (is (= (marker k) (intent S1)))
          (is (= [true {S1 nil} S1] (transport))))))))

(deftest mixed-keys-report-in-key-order-test
  (testing "E4: a grid follower and two show followers, no markers yet"
    (init! {C follower} {u2 follower u1 follower})
    (let [{rt :runtime reports :reports} (step (:runtime start) 70.0 (mtc-at 70))]
      (is (= [C S1 S2] (map :cell reports)) "grid cells first, then shows by id: no throw")
      (is (every? :active? reports))
      (is (= [C S1 S2] (map :cell (show-time/active-runs (state/get-raw-state) rt))))
      (deliver! rt (filter #(= S2 (:cell %)) reports))
      (is (= (marker (chase-id rt S2)) (intent S2)))
      (is (= [S2 C S1] (map :cell (show-time/active-runs (state/get-raw-state) rt)))
          "the installed one in its intent's place, the others chased later in key order")
      (is (= {C 1 S1 1 S2 1} (drawn rt)))
      (is (= [C S1] (map :cell (:reports (step rt 400.0 (mtc-at 370)))))
          "the two still to install, in key order"))))

(deftest removed-show-retires-its-chase-test
  (testing "E5: the show goes while its episode runs"
    (let [[rt1 k1] (installed!)]
      (is (= (marker k1) (intent S1)))
      (state/swap-state! update-in [:chains :shows] dissoc u1)
      (let [{rt :runtime reports :reports} (step rt1 100.0 (mtc-at 100))]
        (is (= {} (:chase rt)) "its episode is gone from this tick")
        (is (= [(off-report S1 nil [:chase k1])] reports) "one Off, for no version")
        (is (empty? (drawn rt)))
        (deliver! rt reports)
        (is (nil? (intent S1)))
        (is (= [false {} nil] (transport)))))))

(deftest a-stopped-show-stays-stopped-until-timecode-enters-again-test
  ;; As a grid press stops a chased cue (events.handlers.grid stop-chase):
  ;; [:playback :chase-stopped] is keyed by the playback key, a show's too
  (let [[rt1 k1] (installed!)
        stopped-for #(get-in (state/get-raw-state) [:playback :chase-stopped])]
    (state/swap-state! #(-> %
                            (update :playback cue-timing/deactivate-cue S1)
                            (assoc-in [:playback :chase-stopped S1] k1)))
    (let [pressed (state/get-raw-state)]
      (is (= [false {} nil] (transport)))
      (testing "timecode runs on: the same episode, neither reported, drawn nor positioned"
        (let [{rt2 :runtime r2 :reports} (step rt1 370.0 (mtc-at 370))
              {rt3 :runtime r3 :reports} (step rt2 700.0 (running 3606.2))]
          (is (= k1 (chase-id rt2 S1) (chase-id rt3 S1)))
          (is (= [[] []] [r2 r3]))
          (is (= [{} {}] [(drawn rt2) (drawn rt3)]))
          (is (not-any? #(contains? (show-time/positions %) S1) [rt2 rt3]))
          (is (identical? pressed (deliver! rt3 [(on-report S1 0 :none k1 {:restart nil :allowed? true})]))
              "a late On for k1 changes nothing")))
      (testing "timecode stops, then runs again: a new episode, which plays"
        (let [{rt2 :runtime offs :reports} (step rt1 670.0 (mtc-at 670))
              {rt3 :runtime ons :reports} (step rt2 1000.0 (running 3610.0))
              k2 (chase-id rt3 S1)]
          (is (= [] offs))
          (is (not= k1 k2))
          (is (= [(on-report S1 0 :none k2 {:restart nil :allowed? true})] ons))
          (deliver! rt3 ons)
          (is (= (marker k2) (intent S1)))
          (is (= {} (stopped-for)) "the show's stop is over")
          (is (= {S1 1} (drawn rt3))))))))


;; Timecode on and off (plans/2026-11-25-timeline-shows/07 §8, S4)

(deftest turned-off-show-stops-next-tick-test
  ;; Trace 1: u1 plays [3600, 3632) under timecode, u2 follows at [3632,
  ;; 3664); an edit that makes u1 overlap u2 turns u1's timecode off
  (init! {} {u1 follower u2 (assoc follower :timeline/tc-start 3632.0)})
  (let [{rt1 :runtime reports1 :reports} (step (:runtime start) 70.0 (mtc-at 70))
        k1 (chase-id rt1 S1)]
    (is (= [S1] (map :cell reports1)) "u1 takes timecode at 3,605.55 s; u2 is outside it")
    (deliver! rt1 reports1)
    (is (= (marker k1) (intent S1)))
    (handle! {:event/type :timeline/apply-op :show-id u1 :op {:op :set-length :beats 66.0}})
    (testing "the edit turns u1's timecode off, and its marker and version go in the same event"
      (is (nil? (get-in (state/get-raw-state) [:chains :shows u1 :timeline/chase])))
      (is (= :mtc (get-in (state/get-raw-state) [:chains :shows u2 :timeline/chase])))
      (is (nil? (intent S1)))
      (is (nil? (version S1)))
      (is (= [false {} nil] (transport))))
    (testing "the next tick: no chase, no position and no report for u1"
      (let [{rt2 :runtime reports2 :reports} (step rt1 100.0 (mtc-at 100))]
        (is (not (contains? (:chase rt2) S1)))
        (is (not (contains? (show-time/positions rt2) S1)))
        (is (empty? (drawn rt2)))
        (is (= [] reports2))))))

(deftest looping-show-chases-without-looping-test
  ;; 16 beats at 120 BPM from 0 s, looping over its first 4 beats: the chase
  ;; plays it straight through [0, 16) beats, [0, 8) s
  (init! {} {u1 (timeline {:timeline/chase :mtc :timeline/tc-start 0.0 :timeline/tempo-bpm 120.0
                           :loop {:enabled? true :start 0.0 :duration 4.0}})})
  (let [rt6 (:runtime (step (:runtime start) 0.0 (running 6.0)))]
    (is (close? 12.0 (chase-at rt6 S1)) "beat 12, not wrapped into the loop's [0, 4)")
    (is (= {S1 1} (drawn rt6)))
    (let [rt8 (:runtime (step rt6 2000.0 (running 8.0)))]
      (is (not (contains? (:chase rt8) S1)) "8 s is beat 16: outside its window, the loop or not")
      (is (empty? (drawn rt8))))))
