(ns laser-show.events.history-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.templates :as templates]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.history :as history-handlers]
            [laser-show.events.core :as events]
            [laser-show.routing.zone-patterns :as zp]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.timeline.model :as model]))

(def item-id #uuid "11111111-1111-1111-1111-111111111111")

(defn test-state []
  (-> (domains/build-initial-state)
      (assoc-in [:grid :size] [8 4])
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:chains :cue-chains [0 0] :items]
                [{:id item-id
                  :type :preset
                  :preset-id :test-preset
                  :enabled? true
                  :params {:size 1.0}}])
      (assoc-in [:chains :effect-chains [1 0]] {:active? false :items []})
      (assoc :history {:undo-stack []
                       :redo-stack []
                       :transaction nil})))

(defn clean-state [] (history/mark-clean (test-state)))

(def pipeline events/handle-event-pipeline)

(defn- run
  "Run an event through the pipeline, then any :dispatch it returns (like the agent would)."
  [state event]
  (let [effects (pipeline (assoc event :state state))
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run s next-event)
      s)))

(defn- undo-count [s] (count (get-in s [:history :undo-stack])))

(def edit-size {:event/type :chain/update-param
                :domain :cue-chains :entity-key [0 0] :effect-path [0]
                :param-key :size :value 2.0})

(defn- size-of [s] (get-in s [:chains :cue-chains [0 0] :items 0 :params :size]))

;; A press (reactivity plan 16): its begin, its own edits and its end carry
;; its id

(def ^:private press-g #uuid "00000000-0000-0000-0000-0000000016c1")
(def ^:private press-h #uuid "00000000-0000-0000-0000-0000000016c2")

(defn- begin
  ([id] (begin id nil))
  ([id t] (cond-> {:event/type :history/begin-transaction :label "Adjust Size" :source-id [:slider :size]
                   :history/gesture-id id}
            t (assoc :time t))))

(defn- member
  "Press id's slider edit of the size to value."
  ([id value] (member id value nil))
  ([id value t] (cond-> (assoc edit-size :value value :history/gesture-id id) t (assoc :time t))))

(defn- commit
  ([id] (commit id nil))
  ([id t] (cond-> {:event/type :history/commit-transaction :source-id [:slider :size] :history/gesture-id id}
            t (assoc :time t))))

(deftest discrete-event-automatic-history-test
  (testing "discrete event automatically captures pre-action snapshot on undo stack"
    (let [s0 (test-state)
          s1 (:state (pipeline {:event/type :grid/clear-cell :col 0 :row 0 :state s0}))
          h1 (:history s1)]
      (is (nil? (get-in s1 [:chains :cue-chains [0 0]])))
      (is (true? (history/can-undo? h1)))
      (is (false? (history/can-redo? h1)))
      (is (= "Clear Cell" (history/undo-label h1)))

      (let [s2 (:state (pipeline {:event/type :edit/undo :state s1}))
            h2 (:history s2)]
        (is (= 1 (count (get-in s2 [:chains :cue-chains [0 0] :items]))))
        (is (false? (history/can-undo? h2)))
        (is (true? (history/can-redo? h2)))
        (is (= "Clear Cell" (history/redo-label h2)))

        (let [s3 (:state (pipeline {:event/type :edit/redo :state s2}))
              h3 (:history s3)]
          (is (nil? (get-in s3 [:chains :cue-chains [0 0]])))
          (is (true? (history/can-undo? h3)))
          (is (false? (history/can-redo? h3))))))))

(deftest continuous-transaction-lifecycle-test
  (testing "a press's begin and end group its own changes into one undo step"
    (let [s1 (run (test-state) (begin press-g))
          _ (is (some? (get-in s1 [:history :transaction])))
          s2 (run s1 (member press-g 1.5))
          s3 (run s2 (member press-g 2.0))
          _ (is (= 0 (undo-count s3)) "no snapshots while the transaction is open")
          s4 (run s3 (commit press-g))]
      (is (nil? (get-in s4 [:history :transaction])))
      (is (= 1 (undo-count s4)))
      (is (= "Adjust Size" (history/undo-label (:history s4))))
      (let [s5 (run s4 {:event/type :edit/undo})]
        (is (= 1.0 (size-of s5)) "undo restores the value from before the drag")
        (is (true? (history/can-redo? (:history s5))))))))

(deftest continuous-transaction-abort-test
  (testing "abort-transaction reverts document back to baseline snapshot"
    (let [s1 (run (test-state) (begin press-g))
          s2 (run s1 (member press-g 2.0))
          s3 (run s2 {:event/type :history/abort-transaction :history/gesture-id press-g})]
      (is (= 2.0 (size-of s2)))
      (is (nil? (get-in s3 [:history :transaction])))
      (is (= 1.0 (size-of s3)))
      (is (false? (history/can-undo? (:history s3))))
      (testing "another press's abort does nothing"
        (is (= s2 (run s2 {:event/type :history/abort-transaction :history/gesture-id press-h})))))))

(deftest runtime-events-do-not-trigger-history-test
  (testing "runtime events such as selection do not push undo snapshots"
    (let [s1 (run (test-state) {:event/type :grid/select-cell :col 1 :row 1})]
      (is (= [1 1] (get-in s1 [:grid :selected-cell])))
      (is (= 0 (undo-count s1))))))

(deftest bpm-changes-are-not-undoable-test
  (let [s0 (clean-state)
        s1 (run s0 {:event/type :timing/set-bpm :bpm 128.0})]
    (is (= 128.0 (get-in s1 [:timing :bpm])))
    (is (= 0 (undo-count s1)))
    (is (false? (get-in s1 [:project :dirty?])) "BPM is live: not an unsaved change")
    (testing "a slider transaction with only a BPM change creates no step"
      (let [s (-> s0
                  (run {:event/type :history/begin-transaction :label "x" :source-id :bpm :history/gesture-id press-g})
                  (run {:event/type :timing/set-bpm :bpm 140.0})
                  (run {:event/type :history/commit-transaction :source-id :bpm :history/gesture-id press-g}))]
        (is (= 0 (undo-count s)))
        (is (nil? (get-in s [:history :transaction])))))))

(deftest effect-toggle-is-not-undoable-test
  (let [s0 (clean-state)
        s1 (run s0 {:event/type :effects/toggle-cell :col 1 :row 0})]
    (is (true? (get-in s1 [:chains :effect-chains [1 0] :active?])))
    (is (= 0 (undo-count s1)))
    (is (false? (get-in s1 [:project :dirty?])))
    (testing "undoing a later content edit keeps the effect on"
      (let [s3 (-> s1 (run edit-size) (run {:event/type :edit/undo}))]
        (is (= 1.0 (size-of s3)))
        (is (true? (get-in s3 [:chains :effect-chains [1 0] :active?])))))
    (testing "undoing an edit made before the toggle keeps the effect on, and BPM"
      (let [s (-> s0
                  (run edit-size)
                  (run {:event/type :effects/toggle-cell :col 1 :row 0})
                  (run {:event/type :timing/set-bpm :bpm 99.0})
                  (run {:event/type :edit/undo}))]
        (is (= 1.0 (size-of s)))
        (is (true? (get-in s [:chains :effect-chains [1 0] :active?])))
        (is (= 99.0 (get-in s [:timing :bpm])))))))

(deftest preview-change-is-dirty-but-not-undoable-test
  (let [s1 (run (clean-state) {:event/type :preview/set-grid-layout :fx/event "3x3"})]
    (is (= [3 3] (get-in s1 [:config :preview :grid-layout])))
    (is (= 0 (undo-count s1)))
    (is (true? (get-in s1 [:project :dirty?])))
    (testing "undo of a later edit keeps the preview layout"
      (let [s (-> s1 (run edit-size) (run {:event/type :edit/undo}))]
        (is (= [3 3] (get-in s [:config :preview :grid-layout])))))))

(deftest trigger-originated-events-skip-history-test
  (let [s1 (run (clean-state) (assoc edit-size :source-trigger {:type :trigger-cue}))]
    (is (= 2.0 (size-of s1)))
    (is (= 0 (undo-count s1)))
    (testing ":history/skip? on the event also skips"
      (is (= 0 (undo-count (run (clean-state) (assoc edit-size :history/skip? true))))))))

(deftest undo-to-saved-state-is-clean-test
  (let [s1 (run (clean-state) edit-size)]
    (is (true? (get-in s1 [:project :dirty?])))
    (let [s2 (run s1 {:event/type :edit/undo})]
      (is (false? (get-in s2 [:project :dirty?])) "back to the saved version")
      (is (true? (get-in (run s2 {:event/type :edit/redo}) [:project :dirty?]))))))

(deftest save-and-load-set-the-baseline-test
  (let [edited (run (clean-state) edit-size)
        saved (persistent/mark-saved edited "x.zip" 0)]
    (is (false? (get-in saved [:project :dirty?])))
    (is (true? (get-in (run saved {:event/type :edit/undo}) [:project :dirty?]))
        "undo after saving moves away from the saved version"))
  (is (false? (get-in (persistent/new-project-state (run (clean-state) edit-size)) [:project :dirty?]))))

(deftest scan-complete-backfills-clean-project-test
  (let [devices [{:address "10.0.0.5" :host-name "dac" :unit-id "u1" :port 7255 :services []}]
        s1 (run (clean-state) {:event/type :projectors/scan-complete :devices devices})]
    (is (seq (:projectors s1)) "projectors were auto-created")
    (is (= 0 (undo-count s1)))
    (is (false? (get-in s1 [:project :dirty?])) "discovery doesn't make a clean project unsaved")
    (testing "on a dirty project, the project stays dirty"
      (let [s (-> (clean-state) (run edit-size) (run {:event/type :projectors/scan-complete :devices devices}))]
        (is (true? (get-in s [:project :dirty?])))))))

(deftest list-operation-labels-test
  (let [component-id [:cue-chain 0 0]
        base (-> (test-state)
                 (assoc-in [:list-ui component-id :selected-ids] #{item-id}))
        list-event (fn [type & {:as extra}]
                     (merge {:event/type type
                             :component-id component-id
                             :items-path [:chains :cue-chains [0 0] :items]
                             :item-id item-id
                             :on-change-event :chain/set-items
                             :on-change-params {:domain :cue-chains :entity-key [0 0]}}
                            extra))
        label-after #(history/undo-label (:history (run base %)))]
    (is (= "Delete Item(s)" (label-after (list-event :list/delete-selected))))
    (is (= "Group Items" (label-after (list-event :list/group-selected))))
    (is (= "Rename Item" (label-after (list-event :list/commit-rename :new-name "x"))))
    (is (= "Enable/Disable Item" (label-after (list-event :list/set-enabled :enabled? false))))
    (testing "collapse is view state: no undo step"
      (let [grouped (run base (list-event :list/group-selected))
            group-id (:id (first (get-in grouped [:chains :cue-chains [0 0] :items])))
            collapsed (run grouped (list-event :list/toggle-collapse :item-id group-id))]
        (is (true? (get-in collapsed [:chains :cue-chains [0 0] :items 0 :collapsed?])))
        (is (= (undo-count grouped) (undo-count collapsed)))))))

(deftest coalesced-events-form-one-undo-step-test
  (let [nudge (assoc edit-size :history/coalesce-key [:nudge])
        s (-> (test-state)
              (run (assoc nudge :value 1.5 :time 1000))
              (run (assoc nudge :value 2.0 :time 1200))
              (run (assoc nudge :value 2.5 :time 1400)))]
    (is (= 1 (undo-count s)))
    (is (= 1.0 (size-of (run s {:event/type :edit/undo}))))))

(deftest undo-emits-engine-refresh-when-projectors-change-test
  (let [streaming (assoc-in (test-state) [:backend :streaming :running?] true)
        s1 (run streaming {:event/type :projectors/add-manual :name "P" :host "10.0.0.9" :port 7255})
        effects (pipeline {:event/type :edit/undo :state s1})]
    (is (seq (:projectors s1)))
    (is (= {} (get-in effects [:multi-engine/refresh :projectors]))
        "the output-sync middleware sees the restored projectors")
    (testing "no refresh when only content changed"
      (let [s (run streaming edit-size)]
        (is (nil? (:multi-engine/refresh (pipeline {:event/type :edit/undo :state s}))))))
    (testing "no refresh while not streaming"
      (let [s (run (test-state) {:event/type :projectors/add-manual :name "P" :host "10.0.0.9" :port 7255})]
        (is (nil? (:multi-engine/refresh (pipeline {:event/type :edit/undo :state s}))))))))

(deftest undo-prunes-active-cues-for-removed-cells-test
  (let [s0 (test-state)
        ;; Paste-style edit creates cell [3 3], which then starts playing
        s1 (-> s0
               (run {:event/type :chain/set-items :domain :cue-chains :entity-key [3 3]
                     :items [{:id #uuid "44444444-4444-4444-4444-444444444444" :type :preset}]})
               (assoc-in [:playback :active-cues [3 3]] {:trigger-time 1})
               (assoc-in [:playback :active-cues [0 0]] {:trigger-time 1}))
        s2 (run s1 {:event/type :edit/undo})]
    (is (nil? (get-in s2 [:chains :cue-chains [3 3]])))
    (is (= #{[0 0]} (set (keys (get-in s2 [:playback :active-cues])))))))

(deftest meta-keys-do-not-reach-effects-test
  (doseq [[label effects]
          [["scan (backfill)" (pipeline {:event/type :projectors/scan-complete
                                         :devices [{:address "10.0.0.5" :host-name "dac" :unit-id "u1"
                                                    :port 7255 :services []}]
                                         :state (clean-state)})]
           ["timeline convert (description)" (pipeline {:event/type :timeline-cue-editor/open
                                                        :col 0 :row 0 :state (clean-state)})]
           ["nudge (coalesce)" (pipeline {:event/type :timeline/nudge-selection :col 0 :row 0
                                          :delta-beats 1.0 :state (test-state)})]]]
    (is (not-any? #{:history/skip? :history/coalesce-key :history/backfill :history/description}
                  (keys effects))
        label)))

(def ^:private grid-id #uuid "00000000-0000-0000-0000-0000000016d1")
(def ^:private grid-session #uuid "00000000-0000-0000-0000-0000000016d2")
(def ^:private grid-target {:domain :cue-zone-patterns :entity-key [0 0] :effect-instance-id grid-id})

(defn- valid-events
  "Valid events (with their :state) for the labels whose handlers rightly do
   nothing ({}) without what they name: the step grid's press events,
   without the window open on the grid, and :paint without its press; the
   ∿ toggle, without its effect (follow-up B6)."
  []
  (let [s (-> (test-state)
              (assoc-in [:chains :cue-chains [0 0] :zone-patterns] [(assoc (zp/preset-instance :steps :cue) :id grid-id)])
              (run {:event/type :step-grid/open :target grid-target :editor-id grid-session}))
        press {:editor-id grid-session :target grid-target :history/gesture-id press-g}
        pressed (run s (assoc press :event/type :step-grid/paint-start :row 0 :step 0))]
    {:step-grid/paint-start (assoc press :state s :row 0 :step 1)
     :step-grid/paint (assoc press :state pressed :row 0 :step 2)
     :modulator/toggle (assoc grid-target :state s :param-key :offset :param-spec {:min -64.0 :max 64.0} :fx/event true)}))

(deftest history-labels-name-real-events-test
  (let [preset {:state (test-state) :col 0 :row 0 :item-id item-id}
        valid (assoc (valid-events)
                     ;; It does nothing ({}) for an item that isn't there
                     :cue-chain/set-item-target {:state (test-state) :col 0 :row 0 :item-id item-id :target :zone/a}
                     ;; Nor do a preset's edits (follow-up N10)
                     :cue-chain/update-preset-param (assoc preset :param-key :size :value 2.0)
                     :cue-chain/update-preset-param-from-text (assoc preset :param-key :size :min 0.0 :max 10.0 :is-int? false
                                                                     :text "2.0")
                     :cue-chain/update-preset-color (assoc preset :color [0.5 0.5 0.5]))]
    (doseq [event-type (keys history-handlers/event-descriptions)]
      (let [event (get valid event-type {:state (test-state)})
            result (try (handlers/handle-event (assoc event :event/type event-type))
                        (catch Throwable _ ::threw))]
        (is (not= {} result)
            (str event-type " is not routed to a handler (unknown events return {})"))))))

;; Regression tests for the 2026-11-08 review

(def devices [{:address "10.0.0.5" :host-name "dac" :unit-id "u1" :port 7255 :services []}])
(def scan {:event/type :projectors/scan-complete :devices devices})

(deftest drag-back-to-saved-value-is-clean-test
  (testing "clean -> drag away and back -> commit is clean"
    (let [s (-> (clean-state) (run (begin press-g)) (run (member press-g 2.0))
                (run (member press-g 1.0)) (run (commit press-g)))]
      (is (false? (get-in s [:project :dirty?])))
      (is (= 0 (undo-count s)))))
  (testing "dirty -> drag back to the saved value -> commit is clean"
    (let [s (-> (clean-state) (run (assoc edit-size :value 3.0)) (run (begin press-g))
                (run (member press-g 1.0)) (run (commit press-g)))]
      (is (false? (get-in s [:project :dirty?]))))))

(deftest long-drag-stays-one-step-test
  (let [t0 1000000
        s (reduce (fn [s n] (run s (member press-g (+ 1.0 (* n 0.01)) (+ t0 (* n 1000)))))
                  (run (clean-state) (begin press-g t0))
                  (range 1 100))
        s (run s (commit press-g (+ t0 100000)))]
    (is (= 1 (undo-count s)) "a 100 s drag with activity is one step")))

(deftest stale-transaction-auto-commits-after-inactivity-test
  (let [t0 1000000
        s (-> (clean-state)
              (run (begin press-g t0))
              (run (member press-g 2.0 (+ t0 10))))
        s (run s {:event/type :grid/clear-cell :col 0 :row 0 :time (+ t0 history/stale-transaction-ms 100)})]
    (is (nil? (get-in s [:history :transaction])))
    (is (= 2 (undo-count s)) "the stale drag, then the clear as its own step")))

(defn- beat-report
  "The show clock's status report with beat in the bar beat, at t: what
   :show-clock/status writes (the beat into [:timing], a document root)."
  [beat t]
  {:event/type :show-clock/status :time t
   :status {:beat-index beat :frame-stats {} :mtc {:state :stopped :fps nil :text nil} :cue-warnings nil}})

(deftest only-owned-undoable-changes-renew-the-deadline-test
  ;; Reactivity plan 16 §4.4: the deadline is the press's last undoable
  ;; change + 60 s; beats, UI and other presses never move it
  (let [begun (-> (clean-state) (run (begin press-g 0)) (run (member press-g 2.0 0)))
        beats (fn [s from to] (reduce (fn [s t] (run s (beat-report (mod (quot t 500) 4) t))) s (range from to 500)))]
    (testing "beats until 59,999 ms: status updates, the press's activity doesn't"
      (let [s (-> begun (beats 500 60000) (run (beat-report 3 59999)))]
        (is (= 3 (get-in s [:timing :beat-index])) "the beat still shows")
        (is (= 0 (get-in s [:history :transaction :last-activity])) "the last activity is the press's own")
        (is (= press-g (history/gesture-id (:history s))) "still open")
        (is (= 0 (undo-count s)))
        (let [due (run s (beat-report 0 60000))]
          (is (nil? (get-in due [:history :transaction])) "at 60,000 ms the press's step is sealed")
          (is (= 1 (undo-count due)))
          (is (= 0 (get-in due [:timing :beat-index])) "and the report applied"))))
    (testing "an event that returns no state at the deadline still publishes the seal"
      (let [effects (pipeline {:event/type :ui/clear-notice :id (random-uuid) :state begun :time 60000})]
        (is (nil? (get-in effects [:state :history :transaction])))
        (is (= 1 (undo-count (:state effects))))
        (is (= {:cancel-id press-g} (:history/gesture-alarm effects)) "and its wake-up is cancelled")))
    (testing "the press's wake-up with nothing else happening seals it"
      (let [effects (pipeline {:event/type :history/gesture-timeout :history/gesture-id press-g :state begun :time 60000})]
        (is (nil? (get-in effects [:state :history :transaction])))
        (is (= 1 (undo-count (:state effects))))
        (is (= 2.0 (size-of (:state effects))) "the value it set stays")))
    (testing "an early wake-up after the press's own change waits the rest of its time, writing nothing"
      (let [later (run begun (member press-g 2.5 50000))
            effects (pipeline {:event/type :history/gesture-timeout :history/gesture-id press-g :state later :time 60000})]
        (is (= 50000 (get-in later [:history :transaction :last-activity])))
        (is (= {:history/gesture-alarm {:arm {:gesture-id press-g :delay-ms 50000}}} effects))
        (let [held (-> later (run (member press-g 3.0 100000)) (run (commit press-g 100001)))]
          (is (= 1 (undo-count held)) "every value of the press is one step")
          (is (= 1.0 (size-of (run held {:event/type :edit/undo})))))))
    (testing "a wake-up for an older press while H is held does nothing"
      (let [h (run begun (begin press-h 1000))]
        (is (= press-h (history/gesture-id (:history h))) "H's begin sealed G")
        (is (= {} (pipeline {:event/type :history/gesture-timeout :history/gesture-id press-g :state h :time 60000})))
        (is (= {} (pipeline (assoc (member press-g 9.0 2000) :state h))) "nor an old press's edit")
        (is (= {} (pipeline (assoc (commit press-g 2000) :state h))) "nor its end")))
    (testing "the alarm follows the open press: armed on begin, cancelled on its end, handed over to the next"
      (is (= {:arm {:gesture-id press-g :delay-ms history/stale-transaction-ms}}
             (:history/gesture-alarm (pipeline (assoc (begin press-g 0) :state (clean-state))))))
      (is (= {:cancel-id press-g} (:history/gesture-alarm (pipeline (assoc (commit press-g 10) :state begun)))))
      (is (= {:cancel-id press-g :arm {:gesture-id press-h :delay-ms history/stale-transaction-ms}}
             (:history/gesture-alarm (pipeline (assoc (begin press-h 10) :state begun))))))
    (testing "a drag moved back to its saved value is no step, and the project is clean"
      (let [s (-> begun (run (member press-g 1.0 10)) (run (commit press-g 20)))]
        (is (= 0 (undo-count s)))
        (is (false? (get-in s [:project :dirty?])))))))

(deftest scan-backfill-survives-undo-and-redo-test
  (testing "edit -> undo -> scan -> redo keeps the projectors"
    (let [s (-> (clean-state) (run edit-size) (run {:event/type :edit/undo}) (run scan)
                (run {:event/type :edit/redo}))]
      (is (seq (:projectors s)))
      (is (= 2.0 (size-of s)))))
  (testing "edit -> save -> scan -> undo -> redo keeps projectors, and is clean back at the saved version"
    (let [saved (persistent/mark-saved (run (clean-state) edit-size) "x.zip" 0)
          scanned (run saved scan)
          undone (run scanned {:event/type :edit/undo})
          redone (run undone {:event/type :edit/redo})]
      (is (false? (get-in scanned [:project :dirty?])))
      (is (seq (:projectors undone)))
      (is (true? (get-in undone [:project :dirty?])))
      (is (false? (get-in redone [:project :dirty?])) "redo returns to the saved version"))))

(deftest timeline-editor-open-classification-test
  (testing "converting a cue to a timeline cue is an undoable edit"
    (let [s (run (clean-state) {:event/type :timeline-cue-editor/open :col 0 :row 0})]
      (is (= :timeline (get-in s [:chains :cue-chains [0 0] :editor-type])))
      (is (= 1 (undo-count s)))
      (is (= "Convert to Timeline Cue" (history/undo-label (:history s))))
      (is (true? (get-in s [:project :dirty?])))))
  (testing "opening a normalized timeline cue doesn't touch the document"
    (let [s0 (run (clean-state) {:event/type :timeline-cue-editor/open :col 0 :row 0})
          s1 (run s0 {:event/type :timeline-cue-editor/open :col 0 :row 0})]
      (is (identical? (get-in s0 [:chains :cue-chains]) (get-in s1 [:chains :cue-chains])))
      (is (= (undo-count s0) (undo-count s1)))))
  (testing "a timeline cue that skipped normalization is normalized on open as a backfill"
    (let [timeline-cue (-> (clean-state)
                           (assoc-in [:chains :cue-chains [0 0] :editor-type] :timeline)
                           history/mark-clean)
          other-edit {:event/type :timeline/add-track :col 1 :row 0}
          s0 (run timeline-cue other-edit)
          s1 (run s0 {:event/type :timeline-cue-editor/open :col 0 :row 0})
          s2 (run s1 {:event/type :edit/undo})]
      (is (= (undo-count s0) (undo-count s1)) "opening adds no step")
      (is (some? (get-in s2 [:chains :cue-chains [0 0] :items 0 :timeline/start]))
          "undoing an earlier edit keeps the filled-in fields")
      (is (seq (get-in s2 [:chains :cue-chains [0 0] :tracks])))
      (is (false? (get-in s2 [:project :dirty?]))))))

(deftest pipeline-normalizes-items-added-by-cue-chain-editor-test
  (let [s0 (run (clean-state) {:event/type :timeline-cue-editor/open :col 0 :row 0})
        steps (undo-count s0)
        s1 (run s0 {:event/type :cue-chain/add-preset :col 0 :row 0 :preset-id :circle})
        items (get-in s1 [:chains :cue-chains [0 0] :items])
        leaf-ids (model/leaf-track-ids (get-in s1 [:chains :cue-chains [0 0] :tracks]))]
    (is (= 2 (count items)))
    (is (every? #(number? (:timeline/start %)) items))
    (is (every? #(number? (:timeline/duration %)) items))
    (is (every? #(contains? leaf-ids (:track-id %)) items))
    (is (= (inc steps) (undo-count s1)) "one undo entry for the add, normalization included")))

(deftest empty-effect-cell-toggle-is-a-no-op-test
  (let [s (run (clean-state) {:event/type :effects/toggle-cell :col 5 :row 3})]
    (is (nil? (get-in s [:chains :effect-chains [5 3]])))
    (is (= 0 (undo-count s)))
    (is (false? (get-in s [:project :dirty?])))))

(deftest moved-effect-cell-keeps-on-state-after-undo-test
  (let [s (-> (clean-state)
              (assoc-in [:chains :effect-chains [1 0] :items] [{:id #uuid "66666666-6666-6666-6666-666666666666" :effect-id :scale}])
              (run {:event/type :effects/move-cell :from-col 1 :from-row 0 :to-col 2 :to-row 0})
              (run {:event/type :effects/toggle-cell :col 2 :row 0})
              (run {:event/type :edit/undo}))]
    (is (some? (get-in s [:chains :effect-chains [1 0]])))
    (is (true? (get-in s [:chains :effect-chains [1 0] :active?])))))

(deftest stored-effect-actions-use-the-current-cell-test
  (let [tree {:active? true
              :items [{:id #uuid "77777777-0000-0000-0000-000000000001" :type :group :name "Outer"
                       :items [{:id #uuid "77777777-0000-0000-0000-000000000002" :type :group :name "Off" :enabled? false
                                :items [(assoc (zone-patterns/preset-instance :chase :cell)
                                               :id #uuid "77777777-0000-0000-0000-000000000003")
                                        {:id #uuid "77777777-0000-0000-0000-000000000004" :effect-id :scale}]}]}]}
        cell #(get-in %1 [:chains :effect-chains %2])
        kept (fn [s] {:cells (get-in s [:chains :effect-chains])
                      :selected (get-in s [:effects :selected-cell])
                      :editor (get-in s [:ui :dialogs :effect-chain-editor])
                      :history (select-keys (:history s) [:undo-stack :redo-stack])
                      :dirty? (get-in s [:project :dirty?])})
        reaction {:event/type :effects/new-reaction :col 2 :row 1 :kind :step-colour}
        pattern {:event/type :zone-patterns/new-cell :col 2 :row 1 :style :chase}
        seeded #(history/mark-clean (assoc-in (clean-state) [:chains :effect-chains [2 1]] %))]
    (testing "both New menus, chosen while [2 1] was empty, find a disabled tree there: nothing happens"
      (let [s0 (seeded tree)]
        (doseq [intent [reaction pattern]]
          (is (= (kept s0) (kept (run s0 intent))) (str (:event/type intent))))))
    (testing "a named cell with no items is empty: each creates, in one step, and only the pattern opens its editor"
      (let [s0 (seeded {:name "Old" :items []})
            reacted (run s0 reaction)
            patterned (run s0 pattern)]
        (is (= ["Step colour" 1] [(:name (cell reacted [2 1])) (undo-count reacted)]))
        (is (not (get-in reacted [:ui :dialogs :effect-chain-editor :open?])))
        (is (= [:zone-pattern 1] [(get-in (cell patterned [2 1]) [:items 0 :effect-id]) (undo-count patterned)]))
        (is (true? (get-in patterned [:ui :dialogs :effect-chain-editor :open?])))))
    (testing "a move takes the whole stored tree, its ids and the vibe that names it, in one step"
      (let [fx #(get-in % [:config :vibes :library :vibe-1 :fx])
            s0 (-> (clean-state)
                   (templates/apply-starter-vibes)
                   (assoc-in [:chains :effect-chains [2 1]] tree)
                   (assoc-in [:config :vibes :library :vibe-1 :fx] [[2 1]])
                   (history/mark-clean))
            move {:event/type :effects/move-cell :from-col 2 :from-row 1 :to-col 3 :to-row 1}
            moved (run s0 move)
            undone (run moved {:event/type :edit/undo})]
        (is (= [nil tree [[3 1]] 1] [(cell moved [2 1]) (cell moved [3 1]) (fx moved) (undo-count moved)]))
        (is (= [tree nil [[2 1]]] [(cell undone [2 1]) (cell undone [3 1]) (fx undone)]) "Undo puts both back")
        (is (false? (get-in undone [:project :dirty?])) "the saved show again")
        (testing "; its source emptied before the drop, it moves nothing"
          (let [emptied (run s0 {:event/type :chain/set-items :domain :effect-chains :entity-key [2 1] :items []})
                dropped (run emptied move)]
            (is (= [] (:items (cell emptied [2 1]))))
            (is (= (kept emptied) (kept dropped)))
            (is (= (fx emptied) (fx dropped)) "the vibe still names [2 1]")))))))

(deftest modulator-toggle-is-one-undoable-edit-test
  (let [before {:id item-id :effect-id :intensity :enabled? true
                :params {:amount {:type :triangle :period 7.0 :min 0.2 :max 0.8 :value 0.6 :active? false}}
                :keyframe-modulator {:enabled? true :keyframes [{:position 0.0 :params {:amount 0.3}}
                                                                {:position 1.0 :params {:amount 0.9}}]}}
        after (-> before (assoc-in [:keyframe-modulator :enabled?] false)
                         (assoc-in [:params :amount :active?] true))
        path [:chains :effect-chains [1 0] :items 0]
        initial (assoc-in (test-state) path before)
        {:keys [state dispatch dispatch-later]}
        (pipeline {:event/type :modulator/toggle :state initial :domain :effect-chains :entity-key [1 0]
                   :effect-instance-id item-id :param-key :amount :param-spec {:min 0.0 :max 1.0}
                   :current-value 0.3 :fx/event true}) ; stale value from the keyframe editor
        toggled (cond-> (or state initial) dispatch (run dispatch))
        ;; Undo before delivering any timer the toggle scheduled. No clock or sleep involved.
        undone (cond-> (run toggled {:event/type :edit/undo})
                 dispatch-later (run (:event dispatch-later)))]
    (is (= after (get-in toggled path)) "the stored modulator is active as soon as keyframes are off")
    (is (= 1 (undo-count toggled)))
    (is (= "Toggle Modulation" (history/undo-label (:history toggled))))
    (is (= before (get-in undone path)) "no late write reactivates modulation after Undo")
    (is (zero? (undo-count undone)))
    (is (= "Toggle Modulation" (history/redo-label (:history undone))))
    (is (= after (get-in (run undone {:event/type :edit/redo}) path)))))

(deftest typed-number-is-one-undo-step-test
  (let [path [:chains :effect-chains [1 0] :items 0]
        field {:domain :effect-chains :entity-key [1 0] :effect-path [0] :param-key :amount
               :min 0.0 :max 3.0 :is-int? false}
        config {:type :sine :active? false :value 0.2 :min 0.0 :max 1.0 :period 1.0}
        effect {:id #uuid "77777777-7777-7777-7777-777777777777" :effect-id :intensity :enabled? true}]
    (doseq [[what params kf event read]
            [["a plain field" {:amount 0.5} nil
              (assoc field :event/type :chain/update-param-from-text)
              #(get-in % (conj path :params :amount))]
             ["a modulator's that is off" {:amount config} nil
              (assoc field :event/type :modulator/update-static-value :effect-instance-id (:id effect))
              #(get-in % (conj path :params :amount :value))]
             ["a keyframe's" {:amount 1.0} {:enabled? true :keyframes [{:position 0.0 :params {:amount 0.3}}
                                                                        {:position 1.0 :params {:amount 0.9}}]}
              (assoc field :event/type :keyframe/update-param :keyframe-idx 1)
              #(get-in % (conj path :keyframe-modulator :keyframes 1 :params :amount))]]]
      (testing what
        (let [initial (history/mark-clean (assoc-in (test-state) path (cond-> (assoc effect :params params)
                                                                         kf (assoc :keyframe-modulator kf))))
              typed (run initial (assoc event :text "0.42"))
              undone (run typed {:event/type :edit/undo})
              refused (run undone (assoc event :text "abc"))]
          (is (= 0.42 (read typed)))
          (is (= 1 (undo-count typed)) "one undo step")
          (is (true? (get-in typed [:project :dirty?])))
          (is (= (read initial) (read undone)) "Undo takes back the typed number")
          (is (false? (get-in undone [:project :dirty?])))
          (is (= (:history undone) (:history refused)) "text that isn't a number adds no step and keeps Redo")
          (is (= 0.42 (read (run refused {:event/type :edit/redo})))))))))

(deftest modulator-field-undo-boundaries-test
  ;; Reactivity plan 07: each field edit writes its own field of what is
  ;; stored, so each is its own undo step; one that changes nothing is none
  (let [path [:chains :effect-chains [1 0] :items 0]
        id #uuid "88888888-8888-8888-8888-888888888888"
        triangle {:type :triangle :active? true :period 7.0 :min 0.2 :max 0.8}
        initial (history/mark-clean (assoc-in (test-state) path {:id id :effect-id :intensity :enabled? true
                                                                 :params {:amount triangle}}))
        ;; What the Min and Max sliders send, from one render
        field (fn [k v] {:event/type :modulator/update-param :domain :effect-chains :entity-key [1 0]
                         :effect-instance-id id :param-key :amount :mod-param-key k :fx/event v})
        amount #(get-in % (conj path :params :amount))
        edited (-> initial (run (field :min 0.3)) (run (field :max 0.9)))
        undo-max (run edited {:event/type :edit/undo})
        undo-min (run undo-max {:event/type :edit/undo})]
    (is (= (assoc triangle :min 0.3 :max 0.9) (amount edited)))
    (is (= 2 (undo-count edited)) "two edits, two steps")
    (is (true? (get-in edited [:project :dirty?])))
    (is (= (assoc triangle :min 0.3) (amount undo-max)) "Undo takes back Max; Min stays")
    (is (= (history/extract-document-state initial) (history/extract-document-state undo-min))
        "Undo again: the saved show")
    (is (false? (get-in undo-min [:project :dirty?])))
    (testing "an edit that changes nothing between Undo and Redo keeps Redo"
      (let [ignored (reduce run undo-min [(field :min 0.2)            ; the value it has
                                          (field :duty-cycle 0.3)     ; not a Triangle's
                                          (assoc (field :max 0.9) :effect-instance-id #uuid "99999999-9999-9999-9999-999999999999")])
            redone (-> ignored (run {:event/type :edit/redo}) (run {:event/type :edit/redo}))]
        (is (= undo-min ignored))
        (is (= (assoc triangle :min 0.3 :max 0.9) (amount redone)) "Redo puts both back")
        (is (true? (get-in redone [:project :dirty?])))))
    (testing "Min's changes inside one press and release are one step: each carries the press's id"
      (let [press #(assoc (field :min %) :history/gesture-id press-g)
            s (reduce run initial [{:event/type :history/begin-transaction :label "Adjust Min" :source-id [:modulator-slider :min]
                                    :history/gesture-id press-g}
                                   (press 0.25) (press 0.3) (press 0.35)
                                   {:event/type :history/commit-transaction :source-id [:modulator-slider :min]
                                    :history/gesture-id press-g}])]
        (is (= (assoc triangle :min 0.35) (amount s)))
        (is (= 1 (undo-count s)))
        (is (nil? (get-in s [:history :transaction])))
        (is (= "Adjust Min" (history/undo-label (:history s))))
        (is (= triangle (amount (run s {:event/type :edit/undo}))))
        (testing "and the Max set meanwhile, with no press, is a step of its own"
          (let [s (reduce run initial [{:event/type :history/begin-transaction :label "Adjust Min" :source-id [:modulator-slider :min]
                                        :history/gesture-id press-g}
                                       (press 0.25) (field :max 0.9) (press 0.3)
                                       {:event/type :history/commit-transaction :source-id [:modulator-slider :min]
                                        :history/gesture-id press-g}])]
            (is (= (assoc triangle :min 0.25 :max 0.9) (amount s)) "the press's later change does nothing")
            (is (= 2 (undo-count s)))))))))

(deftest timeline-nudge-coalesces-test
  (let [base (-> (test-state)
                 (assoc-in [:chains :cue-chains [0 0] :items 0 :timeline/start] 0.0)
                 (assoc-in [:ui :timeline-editor [0 0] :selection] #{item-id}))
        nudge {:event/type :timeline/nudge-selection :col 0 :row 0 :delta-beats 1.0}
        s (-> base (run (assoc nudge :time 1000)) (run (assoc nudge :time 1300)) (run (assoc nudge :time 1600)))]
    (is (= 3.0 (get-in s [:chains :cue-chains [0 0] :items 0 :timeline/start])))
    (is (= 1 (undo-count s)))))

(deftest learned-binding-is-undoable-and-unsaved-test
  (let [target {:type :trigger-cue :target [0 0]}
        input {:type :note-on :source :midi :channel 0 :note 60 :velocity 1.0}
        s0 (persistent/begin-document (clean-state))
        doc (get-in s0 [:project :document-id])
        learning (run s0 {:event/type :input/start-learn :target-action target :document-id doc})
        capture {:event/type :input/learn-captured :input-event input :document-id doc
                 :learn-id (get-in learning [:ui :input-learn :active :learn-id])}
        s (run learning capture)]
    (is (zero? (undo-count learning)) "starting a learn is no undo step")
    (is (false? (get-in learning [:project :dirty?])))
    (is (= target (get-in s [:config :input :trigger-map {:source :midi :channel 0 :note 60}])))
    (is (nil? (get-in s [:ui :input-learn :active])))
    (is (true? (get-in s [:project :dirty?])))
    (is (= "Learn Trigger" (history/undo-label (:history s))))
    (testing "Undo takes the binding back, and leaves no learn waiting"
      (let [undone (run s {:event/type :edit/undo})]
        (is (empty? (get-in undone [:config :input :trigger-map])))
        (is (nil? (get-in undone [:ui :input-learn :active])))))
    (testing "a capture after learn was cancelled is ignored"
      (let [cancelled (run learning {:event/type :input/cancel-learn :learn-id (:learn-id capture) :document-id doc})
            s (run cancelled capture)]
        (is (empty? (get-in s [:config :input :trigger-map])))
        (is (zero? (undo-count s)))))))

(deftest live-events-keep-the-dirty-flag-test
  (let [dirty (run (clean-state) edit-size)]
    (is (true? (get-in (run dirty {:event/type :timing/set-bpm :bpm 90.0}) [:project :dirty?])))
    (is (true? (get-in (run dirty {:event/type :effects/toggle-cell :col 1 :row 0}) [:project :dirty?])))
    (is (false? (get-in (run (clean-state) {:event/type :effects/toggle-cell :col 1 :row 0}) [:project :dirty?])))))

(deftest grid-press-keeps-document-history-test
  (let [kept (fn [s0 s]
               (is (= (get-in s0 [:chains :cue-chains]) (get-in s [:chains :cue-chains])) "the cues")
               (is (= (select-keys (:history s0) [:undo-stack :redo-stack])
                      (select-keys (:history s) [:undo-stack :redo-stack]))
                   "undo and redo")
               (is (= (get-in s0 [:project :dirty?]) (get-in s [:project :dirty?])) "unsaved or not"))]
    (doseq [[label s0] [["clean" (clean-state)]
                        ["unsaved, after an edit" (run (clean-state) edit-size)]]]
      (testing label
        (let [started (run s0 {:event/type :grid/trigger-cell :col 0 :row 0})
              stopped (run started {:event/type :grid/trigger-cell :col 0 :row 0})
              selected (run stopped {:event/type :grid/trigger-cell :col 1 :row 0})]
          (is (some? (get-in started [:playback :active-cues [0 0]])))
          (is (nil? (get-in stopped [:playback :active-cues [0 0]])))
          (is (= [1 0] (get-in selected [:grid :selected-cell])) "an empty neighbour, selected")
          (doseq [s [started stopped selected]]
            (kept s0 s)))))
    (testing "a press after Clear, by the click shown before it: nothing starts, nothing is recorded"
      (let [cleared (run (clean-state) {:event/type :grid/clear-cell :col 0 :row 0})
            pressed (run cleared {:event/type :grid/cell-clicked :col 0 :row 0 :has-content? true})]
        (is (= 1 (undo-count cleared)))
        (is (true? (get-in cleared [:project :dirty?])))
        (is (= [0 0] (get-in pressed [:grid :selected-cell])))
        (is (= (:playback cleared) (:playback pressed)))
        (kept cleared pressed)))))
(defn- timeline-state []
  (-> (test-state)
      (assoc-in [:chains :cue-chains [0 0]]
                {:editor-type :timeline
                 :tracks [{:id :f :type :group :collapsed? false
                           :items [{:id :t1 :type :track :name "T1"}]}]
                 :items [{:id item-id :type :preset :preset-id :circle :track-id :t1
                          :timeline/start 0.0 :timeline/duration 4.0}]})
      history/mark-clean))

(deftest track-mute-is-not-undoable-test
  (let [s0 (timeline-state)
        s1 (run s0 {:event/type :timeline/set-track-muted :col 0 :row 0 :track-id :t1 :muted? true})]
    (is (true? (get-in s1 [:chains :cue-chains [0 0] :tracks 0 :items 0 :muted?])))
    (is (= (undo-count s0) (undo-count s1)))
    (is (false? (get-in s1 [:project :dirty?])))))

(deftest folder-collapse-is-not-undoable-test
  (let [s0 (timeline-state)
        tracks (get-in s0 [:chains :cue-chains [0 0] :tracks])
        s1 (run s0 {:event/type :timeline/update-tracks :col 0 :row 0
                    :items (assoc-in tracks [0 :collapsed?] true)})]
    (is (true? (get-in s1 [:chains :cue-chains [0 0] :tracks 0 :collapsed?])))
    (is (= (undo-count s0) (undo-count s1)))
    (is (false? (get-in s1 [:project :dirty?])))))

(deftest undo-does-not-unmute-test
  (let [s0 (timeline-state)
        s1 (run s0 {:event/type :timeline/set-track-muted :col 0 :row 0 :track-id :t1 :muted? true})
        s2 (run s1 {:event/type :timeline/apply-op :col 0 :row 0
                    :op {:op :move-clips :clip-ids #{item-id} :delta 8.0}})
        s3 (run s2 {:event/type :edit/undo})]
    (is (== 8.0 (get-in s2 [:chains :cue-chains [0 0] :items 0 :timeline/start])))
    (is (== 0.0 (get-in s3 [:chains :cue-chains [0 0] :items 0 :timeline/start])) "the move is undone")
    (is (true? (get-in s3 [:chains :cue-chains [0 0] :tracks 0 :items 0 :muted?])) "the mute stays")))

(deftest apply-op-single-undo-entry-test
  (let [s0 (timeline-state)
        s1 (run s0 {:event/type :timeline/apply-op :col 0 :row 0
                    :op {:op :move-clips :clip-ids #{item-id} :delta 2.0}})]
    (is (== 2.0 (get-in s1 [:chains :cue-chains [0 0] :items 0 :timeline/start])))
    (is (= (inc (undo-count s0)) (undo-count s1)))
    (is (= "Move Clip" (history/undo-label (:history s1))))
    (is (== 0.0 (get-in (run s1 {:event/type :edit/undo}) [:chains :cue-chains [0 0] :items 0 :timeline/start])))))

(deftest transport-events-create-no-undo-test
  (let [s0 (timeline-state)
        s (reduce run s0 [{:event/type :timeline/play :col 0 :row 0}
                          {:event/type :timeline/seek :col 0 :row 0 :beat 2.0}
                          {:event/type :timeline/pause :col 0 :row 0}
                          {:event/type :timeline/stop :col 0 :row 0}
                          {:event/type :timeline/set-ruler-mode :mode :time}])]
    (is (= (undo-count s0) (undo-count s)))
    (is (false? (get-in s [:project :dirty?])))))

(deftest timeline-pref-events-create-no-undo-test
  (let [pref-events [{:event/type :timeline/set-detail-height :height 420.0}
                     {:event/type :timeline/toggle-inspector}
                     {:event/type :timeline/toggle-add-panel}]]
    (testing "a clean project stays clean, with no undo entry"
      (let [s0 (timeline-state)
            s (reduce run s0 pref-events)]
        (is (= 420.0 (get-in s [:ui :timeline-prefs :detail-height])))
        (is (= (undo-count s0) (undo-count s)))
        (is (false? (get-in s [:project :dirty?])))))
    (testing "a dirty project stays dirty"
      (let [s0 (run (timeline-state) {:event/type :timeline/apply-op :col 0 :row 0
                                      :op {:op :move-clips :clip-ids #{item-id} :delta 2.0}})
            s (reduce run s0 pref-events)]
        (is (true? (get-in s0 [:project :dirty?])))
        (is (= (undo-count s0) (undo-count s)))
        (is (true? (get-in s [:project :dirty?])))))))

(deftest undo-that-grows-a-live-zone-asks-test
  (let [live (-> (test-state)
                 (assoc-in [:projectors :p1] {:name "Laser L" :host "10.0.0.5" :port 7255 :enabled? true
                                              :zone-groups [:all] :safety {:limit [-0.5 -0.5 0.5 0.5] :blocked []}})
                 (assoc-in [:backend :streaming :running?] true))
        shrunk (run live {:event/type :projectors/set-safety :projector-id :p1 :safety {:limit [-0.3 -0.5 0.5 0.5]}})
        zone #(get-in % [:projectors :p1 :safety :limit])]
    (is (= [-0.3 -0.5 0.5 0.5] (zone shrunk)) "shrinking applied at once")
    (let [asked (pipeline {:event/type :edit/undo :state shrunk})]
      (is (= [-0.3 -0.5 0.5 0.5] (zone (:state asked))) "Undo would grow it, so it asks and changes nothing")
      (is (= "Allow light in a larger area?" (get-in asked [:state :ui :dialogs :confirm :title])))
      (is (= "Undo gives Laser L a larger area while it is on."
             (get-in asked [:state :ui :dialogs :confirm :message])))
      (is (= "Undo" (get-in asked [:state :ui :dialogs :confirm :confirm-label])))
      (testing "a confirmed Undo restores"
        (let [undone (run (:state asked) {:event/type :ui/confirm})]
          (is (= [-0.5 -0.5 0.5 0.5] (zone undone)))
          (testing "and Redo, which shrinks it again, doesn't ask"
            (is (= [-0.3 -0.5 0.5 0.5] (zone (run undone {:event/type :edit/redo}))))))))
    (testing "a laser that isn't outputting just restores"
      (let [off (assoc-in shrunk [:backend :streaming :running?] false)]
        (is (= [-0.5 -0.5 0.5 0.5] (zone (run off {:event/type :edit/undo}))))))))

(deftest safety-events-have-undo-labels-test
  (doseq [[event-type label] [[:projectors/set-safety "Change Safety Zone"]
                              [:projectors/set-safety-coordinate "Change Safety Zone"]
                              [:projectors/reset-safety "Reset Safety Zone"]
                              [:projectors/add-blocked-area "Add Blocked Area"]
                              [:projectors/remove-blocked-area "Remove Blocked Area"]
                              [:projectors/copy-safety "Copy Safety Zone"]]]
    (is (= label (get history-handlers/event-descriptions event-type)) (str event-type))))

(deftest coordinate-edits-are-separate-rig-undo-steps-test
  ;; plans/2026-11-23-cljfx-reactivity/08 §5: each field commit that changes
  ;; the zone, and Reset, is one undo step of the rig. The show stays saved,
  ;; the rig file follows, the engines aren't rebuilt. Effects are only
  ;; looked at, never run
  (let [rig-path (.getPath (java.io.File. (System/getProperty "java.io.tmpdir") "coordinate-edits-test/rig.edn"))
        s0 (-> (clean-state)
               (assoc-in [:projectors :p1] {:name "Laser L" :host "10.0.0.5" :port 7255 :enabled? true
                                            :zone-groups [:all] :safety {:limit [-0.5 -0.5 0.5 0.5] :blocked []}})
               (assoc-in [:rig-file :path] rig-path)
               history/mark-clean)
        coord (fn [path value] {:event/type :projectors/set-safety-coordinate :projector-id :p1 :path path :value value})
        live #(assoc-in % [:backend :streaming :running?] true)
        zone #(get-in % [:projectors :p1 :safety])
        labels #(mapv :description (get-in % [:history :undo-stack]))
        redo #(get-in % [:history :redo-stack])
        x0 (coord [:limit 0] -0.4)
        y1 (coord [:limit 3] 0.3)
        s1 (run s0 x0)
        s2 (run s1 y1)]
    (testing "two edits, two steps"
      (is (= {:limit [-0.4 -0.5 0.5 0.3] :blocked []} (zone s2)))
      (is (= ["Change Safety Zone" "Change Safety Zone"] (labels s2)))
      (is (false? (get-in s2 [:project :dirty?])) "the rig is no unsaved change of the show"))
    (testing "the rig file gets both edits; streaming, the engines aren't rebuilt"
      (let [effects (pipeline (assoc y1 :state (live s1)))]
        (is (= rig-path (get-in effects [:rig/write :path])))
        (is (= {:limit [-0.4 -0.5 0.5 0.3] :blocked []} (get-in effects [:rig/write :doc :projectors :p1 :safety])))
        (is (not (contains? effects :multi-engine/refresh)))))
    (testing "the value it has again: no step"
      (is (= (labels s2) (labels (run s2 y1)))))
    (let [undone (run s2 {:event/type :edit/undo})]
      (testing "Undo takes back the second only, and Redo puts it back"
        (is (= {:limit [-0.4 -0.5 0.5 0.5] :blocked []} (zone undone)))
        (is (= ["Change Safety Zone"] (labels undone)))
        (is (= (zone s2) (zone (run undone {:event/type :edit/redo})))))
      (testing "asking, Cancel, a refused edit, an invalid one and one that changes nothing keep Redo"
        (let [asked (run (live undone) (coord [:limit 2] 0.75))
              cancelled (run asked {:event/type :ui/close-dialog :dialog-id :confirm
                                    :review-id (get-in asked [:ui :dialogs :confirm :safety-review :id])})
              refused (run (assoc-in cancelled [:ui :show-lock?] true) (coord [:limit 2] 0.75))
              same (run cancelled x0)
              invalid (run cancelled (coord [:limit 0] 3.0))]
          (is (true? (get-in asked [:ui :dialogs :confirm :open?])))
          (is (= "Larger safety zone refused" (get-in refused [:ui :show-lock-notice :text])))
          (doseq [[label s] [["asked" asked] ["cancelled" cancelled] ["refused" refused] ["the same" same] ["invalid" invalid]]]
            (is (= (redo undone) (redo s)) label)
            (is (= (labels undone) (labels s)) label)
            (is (= (zone undone) (zone s)) label)
            (is (false? (get-in s [:project :dirty?])) label))
          (is (= (zone s2) (zone (run same {:event/type :edit/redo}))) "Redo still puts the second edit back"))))
    (testing "Reset is one step"
      (let [reset (run s2 {:event/type :projectors/reset-safety :projector-id :p1})]
        (is (= {:limit [-1.0 -1.0 1.0 1.0] :blocked []} (zone reset)))
        (is (= ["Change Safety Zone" "Change Safety Zone" "Reset Safety Zone"] (labels reset)))
        (is (false? (get-in reset [:project :dirty?])))
        (is (= (zone s2) (zone (run reset {:event/type :edit/undo}))))))))

