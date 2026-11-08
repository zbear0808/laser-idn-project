(ns laser-show.events.history-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.history :as history-handlers]
            [laser-show.events.core :as events]))

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
  (testing "mouse-down and mouse-up group intermediate changes into one undo step"
    (let [s1 (run (test-state) {:event/type :history/begin-transaction
                                :label "Adjust Size" :source-id [:slider :size]})
          _ (is (some? (get-in s1 [:history :transaction])))
          s2 (run s1 (assoc edit-size :value 1.5))
          s3 (run s2 (assoc edit-size :value 2.0))
          _ (is (= 0 (undo-count s3)) "no snapshots while the transaction is open")
          s4 (run s3 {:event/type :history/commit-transaction :source-id [:slider :size]})]
      (is (nil? (get-in s4 [:history :transaction])))
      (is (= 1 (undo-count s4)))
      (is (= "Adjust Size" (history/undo-label (:history s4))))
      (let [s5 (run s4 {:event/type :edit/undo})]
        (is (= 1.0 (size-of s5)) "undo restores the value from before the drag")
        (is (true? (history/can-redo? (:history s5))))))))

(deftest continuous-transaction-abort-test
  (testing "abort-transaction reverts document back to baseline snapshot"
    (let [s1 (run (test-state) {:event/type :history/begin-transaction
                                :label "Adjust Size" :source-id [:slider :size]})
          s2 (run s1 edit-size)
          s3 (run s2 {:event/type :history/abort-transaction})]
      (is (= 2.0 (size-of s2)))
      (is (nil? (get-in s3 [:history :transaction])))
      (is (= 1.0 (size-of s3)))
      (is (false? (history/can-undo? (:history s3)))))))

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
                  (run {:event/type :history/begin-transaction :label "x" :source-id :bpm})
                  (run {:event/type :timing/set-bpm :bpm 140.0})
                  (run {:event/type :history/commit-transaction :source-id :bpm}))]
        (is (= 0 (undo-count s)))))))

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

(deftest scan-complete-rebases-clean-project-test
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
  (let [s1 (run (test-state) {:event/type :projectors/add-manual :name "P" :host "10.0.0.9" :port 7255})
        effects (pipeline {:event/type :edit/undo :state s1})]
    (is (seq (:projectors s1)))
    (is (true? (:multi-engine/refresh effects)))
    (testing "no refresh when only content changed"
      (let [s (run (test-state) edit-size)]
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
  (let [devices [{:address "10.0.0.5" :host-name "dac" :unit-id "u1" :port 7255 :services []}]
        effects (pipeline {:event/type :projectors/scan-complete :devices devices :state (clean-state)})]
    (is (not-any? #{:history/skip? :history/coalesce-key :document/rebase?} (keys effects)))))

(deftest history-labels-name-real-events-test
  (doseq [event-type (keys history-handlers/event-descriptions)]
    (let [result (try (handlers/handle-event {:event/type event-type :state (test-state)})
                      (catch Throwable _ ::threw))]
      (is (not= {} result)
          (str event-type " is not routed to a handler (unknown events return {})")))))
