(ns laser-show.events.history-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.templates :as templates]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.history :as history-handlers]
            [laser-show.events.core :as events]
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

(deftest history-labels-name-real-events-test
  (doseq [event-type (keys history-handlers/event-descriptions)]
    (let [result (try (handlers/handle-event {:event/type event-type :state (test-state)})
                      (catch Throwable _ ::threw))]
      (is (not= {} result)
          (str event-type " is not routed to a handler (unknown events return {})")))))

;; Regression tests for the 2026-11-08 review

(def devices [{:address "10.0.0.5" :host-name "dac" :unit-id "u1" :port 7255 :services []}])
(def scan {:event/type :projectors/scan-complete :devices devices})

(deftest drag-back-to-saved-value-is-clean-test
  (let [begin {:event/type :history/begin-transaction :label "Size" :source-id :size}
        commit {:event/type :history/commit-transaction :source-id :size}]
    (testing "clean -> drag away and back -> commit is clean"
      (let [s (-> (clean-state) (run begin) (run (assoc edit-size :value 2.0))
                  (run (assoc edit-size :value 1.0)) (run commit))]
        (is (false? (get-in s [:project :dirty?])))
        (is (= 0 (undo-count s)))))
    (testing "dirty -> drag back to the saved value -> commit is clean"
      (let [s (-> (clean-state) (run (assoc edit-size :value 3.0)) (run begin)
                  (run (assoc edit-size :value 1.0)) (run commit))]
        (is (false? (get-in s [:project :dirty?])))))))

(deftest long-drag-stays-one-step-test
  (let [t0 1000000
        s (reduce (fn [s n] (run s (assoc edit-size :value (+ 1.0 (* n 0.01)) :time (+ t0 (* n 1000)))))
                  (run (clean-state) {:event/type :history/begin-transaction :label "Size" :source-id :size :time t0})
                  (range 1 100))
        s (run s {:event/type :history/commit-transaction :source-id :size :time (+ t0 100000)})]
    (is (= 1 (undo-count s)) "a 100 s drag with activity is one step")))

(deftest stale-transaction-auto-commits-after-inactivity-test
  (let [t0 1000000
        s (-> (clean-state)
              (run {:event/type :history/begin-transaction :label "Size" :source-id :size :time t0})
              (run (assoc edit-size :time (+ t0 10))))
        s (run s {:event/type :grid/clear-cell :col 0 :row 0 :time (+ t0 history/stale-transaction-ms 100)})]
    (is (nil? (get-in s [:history :transaction])))
    (is (= 2 (undo-count s)) "the stale drag, then the clear as its own step")))

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
                   :effect-path [0] :param-key :amount :param-spec {:min 0.0 :max 1.0}
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
        input {:type :note-on :source :midi :channel 0 :note 60 :velocity 100}
        learning (assoc-in (clean-state) [:backend :input :learning-target] target)
        s (run learning {:event/type :input/learn-captured :target-action target :input-event input})]
    (is (= target (get-in s [:config :input :trigger-map {:source :midi :channel 0 :note 60}])))
    (is (nil? (get-in s [:backend :input :learning-target])))
    (is (true? (get-in s [:project :dirty?])))
    (is (= "Learn Trigger" (history/undo-label (:history s))))
    (testing "a capture after learn was cancelled is ignored"
      (let [s (run (clean-state) {:event/type :input/learn-captured :target-action target :input-event input})]
        (is (empty? (get-in s [:config :input :trigger-map])))))))

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
                              [:projectors/add-blocked-area "Add Blocked Area"]
                              [:projectors/remove-blocked-area "Remove Blocked Area"]
                              [:projectors/copy-safety "Copy Safety Zone"]]]
    (is (= label (get history-handlers/event-descriptions event-type)) (str event-type))))

