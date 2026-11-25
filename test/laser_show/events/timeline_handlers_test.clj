(ns laser-show.events.timeline-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.shape]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.timeline :as timeline]
            [laser-show.events.show-lock :as show-lock]
            [laser-show.state.domains :refer [build-initial-state]]
            [laser-show.state.history :as history]
            [laser-show.timeline.layout :as layout]
            [laser-show.timeline.model :as model]
            [laser-show.views.components.visual-editors.timeline.drop :as drop]))

(def base-state
  (build-initial-state))

(def u #uuid "00000000-0000-0000-0000-00000000e001")
(def u2 #uuid "00000000-0000-0000-0000-00000000e002")

(defn- show-state
  "base-state with cue as show u (plans/2026-11-25-timeline-shows 05 §8.1)."
  ([cue] (show-state base-state cue))
  ([state cue] (assoc-in state [:chains :shows u] cue)))

(defn- show-of [s] (get-in s [:chains :shows u]))
(defn- view-of [s] (get-in s [:ui :timeline-editor u]))
(defn- empty-show [] (model/normalize-timeline-cue {:editor-type :timeline :items []}))

(defn- run [state event]
  (timeline/handle (assoc event :show-id u :state state)))

(deftest ui-state-is-per-show-test
  (let [s0 (-> base-state
               (assoc-in [:chains :shows] {u (empty-show) u2 (empty-show)})
               (assoc-in [:ui :timeline-editor u] {:selection #{:x} :cursor-beat 8.0 :zoom-x 80.0})
               (assoc-in [:list-ui [:timeline-tracks u]] {:selected-ids #{:t}}))
        s1 (:state (timeline/handle {:event/type :timeline/set-cursor-beat :show-id u2 :beat 4.0 :state s0}))
        s2 (:state (timeline/handle {:event/type :timeline/select-items :show-id u2 :ids [:y] :state s1}))]
    (testing "editing another show's view leaves this one alone"
      (is (= {:selection #{:x} :cursor-beat 8.0 :zoom-x 80.0} (get-in s2 [:ui :timeline-editor u])))
      (is (= {:cursor-beat 4.0 :selection #{:y}} (get-in s2 [:ui :timeline-editor u2])))
      (is (= {:selected-ids #{:t}} (get-in s2 [:list-ui [:timeline-tracks u]]))))
    (testing "events without a show use the open editor's show"
      (let [s3 (-> s2
                   (assoc-in [:ui :dialogs :timeline-cue-editor] {:open? true :show-id u2})
                   (#(:state (timeline/handle {:event/type :timeline/clear-selection :state %}))))]
        (is (= #{} (get-in s3 [:ui :timeline-editor u2 :selection])))
        (is (= #{:x} (get-in s3 [:ui :timeline-editor u :selection])))))))

(deftest event-show-fallback-test
  (let [s0 (-> base-state
               (assoc-in [:chains :shows] {u (empty-show) u2 (empty-show)})
               (assoc-in [:ui :timeline-editor] {u {:selection #{:a}} u2 {:selection #{:b}}}))
        clear (fn [dialog event]
                (let [s (assoc-in s0 [:ui :dialogs :timeline-cue-editor] dialog)]
                  [s (:state (timeline/handle (merge {:event/type :timeline/clear-selection :state s} event)))]))
        cleared (fn [s] (set (keep (fn [[id v]] (when (empty? (:selection v)) id)) (get-in s [:ui :timeline-editor]))))]
    (testing "the event's show wins over the dialog's"
      (is (= #{u} (cleared (second (clear {:open? true :show-id u2} {:show-id u}))))))
    (testing "no show: the dialog's"
      (is (= #{u2} (cleared (second (clear {:open? true :show-id u2} {}))))))
    (testing "no show and no dialog's: nothing changes"
      (let [[s s'] (clear {:open? false} {})]
        (is (identical? s s'))))
    (testing ":col :row (an old caller) are ignored, never a path"
      (let [[_ s'] (clear {:open? true :show-id u2} {:col 0 :row 0})]
        (is (= #{u2} (cleared s')))
        (is (nil? (get-in s' [:ui :timeline-editor [0 0]])))))))

(deftest add-track-does-not-stamp-target-test
  (let [s0 (show-state {:target :front :tracks [] :items []})
        tracks (fn [ev] (:tracks (show-of (:state (run s0 ev)))))]
    (is (not (contains? (last (tracks {:event/type :timeline/add-track})) :target)) "a new track inherits")
    (is (not (contains? (last (tracks {:event/type :timeline/add-folder})) :target)))
    (is (= :back (:target (last (tracks {:event/type :timeline/add-track :target :back})))))
    (is (= [:zone/a :zone/b] (:target (last (tracks {:event/type :timeline/add-folder :target [:zone/b :zone/a]})))))))

(deftest set-track-and-clip-target-test
  (let [s0 (show-state {:editor-type :timeline
                        :tracks [{:id :f :type :group :items [{:id :t :type :track}]}]
                        :items [{:id :c :type :preset :preset-id :circle :track-id :t :timeline/start 0.0 :timeline/duration 4.0}]})
        r1 (run s0 {:event/type :timeline/set-track-target :track-id :f :target :zone/c})
        r2 (run (:state r1) {:event/type :timeline/set-clip-target :item-id :c :target [:graphics :zone/a]})
        cue #(show-of (:state %))]
    (is (= :zone/c (get-in (cue r1) [:tracks 0 :target])))
    (is (= "Change Track Target" (:history/description r1)))
    (is (= [:graphics :zone/a] (get-in (cue r2) [:items 0 :target])))
    (is (= "Change Clip Target" (:history/description r2)))
    (let [r3 (run (:state r2) {:event/type :timeline/set-clip-target :item-id :c :target nil})]
      (is (not (contains? (get-in (cue r3) [:items 0]) :target)) "nil: it inherits again"))))

(deftest set-cue-target-test
  (let [s0 (show-state (assoc (empty-show) :name "Intro"))
        r1 (run s0 {:event/type :timeline/set-cue-target :target :zone/a})
        r2 (run (:state r1) {:event/type :timeline/set-cue-target :target :inherit})
        r3 (run (:state r1) {:event/type :timeline/set-cue-target :target nil})]
    (is (= :zone/a (:target (show-of (:state r1)))))
    (is (= "Change Show Target" (:history/description r1)))
    (is (not (contains? (show-of (:state r2)) :target)) ":inherit clears it")
    (is (not (contains? (show-of (:state r3)) :target)) "nil clears it")
    (is (identical? s0 (:state (run s0 {:event/type :timeline/set-cue-target :target :inherit})))
        "clearing what isn't set changes nothing")
    (testing "the show lock refuses it"
      (is (contains? show-lock/blocked-events :timeline/set-cue-target)))))

(deftest set-name-clears-editing-flag-test
  (let [s0 (-> (show-state (assoc (empty-show) :name "Intro"))
               (assoc-in [:ui :dialogs :timeline-cue-editor] {:open? true :show-id u :editing-name? true}))
        named (:state (run s0 {:event/type :timeline/set-name :name "Outro"}))
        cleared (:state (run s0 {:event/type :timeline/set-name :name ""}))]
    (is (= "Outro" (:name (show-of named))))
    (is (false? (get-in named [:ui :dialogs :timeline-cue-editor :editing-name?])))
    (is (not (contains? (show-of cleared) :name)) "\"\" removes the name")
    (is (false? (get-in cleared [:ui :dialogs :timeline-cue-editor :editing-name?])))
    (testing "a missing show: unchanged"
      (let [s (update-in s0 [:chains :shows] dissoc u)]
        (is (= {:state s} (run s {:event/type :timeline/set-name :name "X"})))))
    (testing "the undo label"
      (let [s1 (:state (events/handle-event-pipeline (assoc {:event/type :timeline/set-name :show-id u :name "Outro"}
                                                            :state (assoc s0 :history {:undo-stack [] :redo-stack [] :transaction nil}))))]
        (is (= "Rename Show" (history/undo-label (:history s1))))))))

(deftest handle-add-preset-to-track-test
  (testing "adds preset at cursor-beat on resolved leaf track"
    (let [add-res (run (show-state (empty-show)) {:event/type :timeline/add-preset-to-track :item-id :circle})
          items (:items (show-of (:state add-res)))
          added (first items)]
      (is (= 1 (count items)))
      (is (= :circle (:preset-id added)))
      (is (some? (:track-id added)))
      (is (= 0.0 (:timeline/start added)))
      (is (= 4.0 (:timeline/duration added)))
      (is (= 4.0 (get-in add-res [:state :ui :timeline-editor u :cursor-beat])))
      (is (= #{(:id added)} (get-in add-res [:state :ui :timeline-editor u :selection])))
      (is (= #{(:track-id added)} (get-in add-res [:state :list-ui [:timeline-tracks u] :selected-ids])))))

  (testing "advances cursor beat for successive additions"
    (let [s1 (:state (run (show-state (empty-show)) {:event/type :timeline/add-preset-to-track :item-id :circle}))
          s2 (:state (run s1 {:event/type :timeline/add-preset-to-track :item-id :square}))
          items (:items (show-of s2))]
      (is (= 2 (count items)))
      (is (= 0.0 (:timeline/start (first items))))
      (is (= 4.0 (:timeline/start (second items))))
      (is (= 8.0 (get-in s2 [:ui :timeline-editor u :cursor-beat]))))))

(deftest handle-select-track-test
  (testing "sets selected-ids and last-selected-id in canonical list-ui state"
    (let [track-id (random-uuid)
          result (run (show-state (empty-show)) {:event/type :timeline/select-track :track-id track-id})
          ui-state (get-in result [:state :list-ui [:timeline-tracks u]])]
      (is (= #{track-id} (:selected-ids ui-state)))
      (is (= track-id (:last-selected-id ui-state))))))

(deftest handle-add-track-test
  (testing "appends track and selects it in canonical list-ui state"
    (let [result (run (show-state (empty-show)) {:event/type :timeline/add-track})
          tracks (:tracks (show-of (:state result)))
          new-track (last tracks)
          ui-state (get-in result [:state :list-ui [:timeline-tracks u]])]
      (is (seq tracks))
      (is (= #{(:id new-track)} (:selected-ids ui-state)))
      (is (= (:id new-track) (:last-selected-id ui-state))))))

(deftest set-clip-track-op-test
  (testing "moves item to target track without affecting other fields"
    (let [add-res (run (show-state (empty-show)) {:event/type :timeline/add-preset-to-track :item-id :circle})
          item (first (:items (show-of (:state add-res))))
          item-id (:id item)
          with-track (:state (run (:state add-res) {:event/type :timeline/add-track}))
          target-track-id (:id (last (:tracks (show-of with-track))))
          move (fn [track-id]
                 (-> (run with-track {:event/type :timeline/apply-op
                                      :op {:op :set-clip-track :clip-id item-id :track-id track-id}})
                     :state show-of :items first))
          updated-item (move target-track-id)]
      (is (= target-track-id (:track-id updated-item)))
      (is (= (:timeline/start item) (:timeline/start updated-item)))
      (is (= (:timeline/duration item) (:timeline/duration updated-item)))
      (is (= (:track-id item) (:track-id (move (random-uuid)))) "unknown track: unchanged"))))

(deftest handle-delete-selection-test
  (let [a (random-uuid) b (random-uuid) c (random-uuid)
        s0 (show-state {:items [{:id a :preset-id :circle}
                                {:id b :preset-id :square}
                                {:id c :preset-id :line}]})]
    (testing "removes only the selected clips and clears the selection"
      (let [s1 (assoc-in s0 [:ui :timeline-editor u :selection] #{a c})
            res (run s1 {:event/type :timeline/delete-selection})]
        (is (= [b] (mapv :id (:items (show-of (:state res))))))
        (is (= #{} (get-in res [:state :ui :timeline-editor u :selection])))
        (is (= "Delete 2 Clips" (:history/description res)))))

    (testing "is a no-op with an empty selection"
      (let [s1 (assoc-in s0 [:ui :timeline-editor u :selection] #{})
            res (run s1 {:event/type :timeline/delete-selection})]
        (is (identical? s1 (:state res)))))))


(deftest set-snap-uses-fx-event-false-test
  (testing "an unchecked checkbox (:fx/event false) disables snap"
    (let [s0 (assoc-in base-state [:ui :timeline-prefs :snap :enabled?] true)
          res (timeline/handle {:event/type :timeline/set-snap :fx/event false :state s0})]
      (is (false? (get-in res [:state :ui :timeline-prefs :snap :enabled?])))))
  (testing "a checked checkbox enables it"
    (let [s0 (assoc-in base-state [:ui :timeline-prefs :snap :enabled?] false)
          res (timeline/handle {:event/type :timeline/set-snap :fx/event true :state s0})]
      (is (true? (get-in res [:state :ui :timeline-prefs :snap :enabled?])))))
  (testing "the snap-value combo leaves the enabled flag alone"
    (let [s0 (assoc-in base-state [:ui :timeline-prefs :snap :enabled?] false)
          res (timeline/handle {:event/type :timeline/set-snap :value 0.5 :state s0})]
      (is (false? (get-in res [:state :ui :timeline-prefs :snap :enabled?])))
      (is (== 0.5 (get-in res [:state :ui :timeline-prefs :snap :value]))))))

(deftest loop-op-on-missing-loop-stays-disabled-test
  (let [s0 (show-state {:editor-type :timeline :items []})
        res (run s0 {:event/type :timeline/apply-op :op {:op :move-loop :delta 2.0}})]
    (is (false? (get-in (show-of (:state res)) [:loop :enabled?])))
    (is (== 2.0 (get-in (show-of (:state res)) [:loop :start])))))

(deftest trim-ops-keep-the-other-edge-test
  (let [s0 (show-state {:tracks [{:id :t :type :track}]
                        :items [{:id :c :track-id :t :timeline/start 1.0 :timeline/duration 4.0}]})
        clip-after (fn [op]
                     (-> (run s0 {:event/type :timeline/apply-op :op op}) :state show-of :items first))]
    (testing "trimming the left edge past 0 doesn't lengthen the clip's end"
      (let [c (clip-after {:op :trim-start :clip-id :c :to -3.0})]
        (is (== 0.0 (:timeline/start c)))
        (is (== 5.0 (:timeline/duration c)))))
    (testing "trimming the left edge past the end leaves a sliver ending at the old end"
      (let [c (clip-after {:op :trim-start :clip-id :c :to 9.0})]
        (is (== 5.0 (+ (:timeline/start c) (:timeline/duration c))))))
    (testing "right edge keeps the start"
      (let [c (clip-after {:op :trim-end :clip-id :c :to 7.0})]
        (is (== 1.0 (:timeline/start c)))
        (is (== 6.0 (:timeline/duration c)))))
    (testing "moving keeps the length"
      (let [c (clip-after {:op :move-clips :clip-ids #{:c} :delta 2.0})]
        (is (== 3.0 (:timeline/start c)))
        (is (== 4.0 (:timeline/duration c)))))))

(deftest update-tracks-deleting-track-deletes-its-clips-test
  (let [s0 (-> (show-state {:editor-type :timeline
                            :tracks [{:id :f :type :group :items [{:id :t1 :type :track}]}
                                     {:id :t2 :type :track}]
                            :items [{:id :a :track-id :t1 :timeline/start 0.0 :timeline/duration 4.0}
                                    {:id :b :track-id :t2 :timeline/start 0.0 :timeline/duration 4.0}]})
               (assoc-in [:ui :timeline-editor u :selection] #{:a :b}))
        cue (fn [tracks]
              (:state (run s0 {:event/type :timeline/update-tracks :items tracks})))]
    (testing "deleting the folder deletes the clips of the track inside it"
      (let [s1 (cue [{:id :t2 :type :track}])]
        (is (= [:b] (mapv :id (:items (show-of s1)))))
        (is (= #{:b} (get-in s1 [:ui :timeline-editor u :selection])))))
    (testing "reordering or regrouping keeps every clip"
      (let [s1 (cue [{:id :t2 :type :track} {:id :t1 :type :track}])]
        (is (= [:a :b] (mapv :id (:items (show-of s1)))))))
    (testing "a show that isn't there isn't made (it was a cleared cell's, 05 §1)"
      (let [s (update-in s0 [:chains :shows] dissoc u)]
        (is (= {:state s} (run s {:event/type :timeline/update-tracks :items [{:id :t9 :type :track}]})))))))

(deftest set-track-muted-test
  (let [s0 (show-state {:tracks [{:id :f :type :group :items [{:id :t1 :type :track}]}]})
        muted (:state (run s0 {:event/type :timeline/set-track-muted :track-id :t1 :muted? true}))
        unmuted (:state (run muted {:event/type :timeline/set-track-muted :track-id :t1 :muted? false}))]
    (is (true? (get-in (show-of muted) [:tracks 0 :items 0 :muted?])))
    (is (not (contains? (get-in (show-of unmuted) [:tracks 0 :items 0]) :muted?)))))

(deftest apply-op-prunes-selection-test
  (let [s0 (-> (show-state {:editor-type :timeline
                            :tracks [{:id :t1 :type :track}]
                            :items [{:id :a :track-id :t1 :timeline/start 0.0 :timeline/duration 4.0}
                                    {:id :b :track-id :t1 :timeline/start 4.0 :timeline/duration 4.0}]})
               (assoc-in [:ui :timeline-editor u :selection] #{:a :b}))
        res (run s0 {:event/type :timeline/apply-op :op {:op :delete-clips :clip-ids #{:a}}})]
    (is (= [:b] (mapv :id (:items (show-of (:state res))))))
    (is (= #{:b} (get-in res [:state :ui :timeline-editor u :selection])))
    (is (= "Delete Clip" (:history/description res))))
  (testing "unknown ops change nothing"
    (let [s0 (show-state (empty-show))
          res (run s0 {:event/type :timeline/apply-op :op {:op :nope}})]
      (is (identical? s0 (:state res))))))

(deftest set-snap-toggle-and-inspector-test
  (let [s1 (:state (timeline/handle {:event/type :timeline/set-snap :toggle? true :state base-state}))
        s2 (:state (timeline/handle {:event/type :timeline/set-snap :toggle? true :state s1}))]
    (is (false? (get-in s1 [:ui :timeline-prefs :snap :enabled?])))
    (is (true? (get-in s2 [:ui :timeline-prefs :snap :enabled?]))))
  (let [s1 (:state (timeline/handle {:event/type :timeline/toggle-inspector :state base-state}))]
    (is (false? (get-in s1 [:ui :timeline-prefs :inspector-open?])))))

;; The invariants (05 §7): every addressed event, as data

(def ^:private addressed-events
  "05 §6's addressed :timeline/* events (the show id is added)."
  [{:event/type :timeline/select-items :ids [:c1] :mode :replace}
   {:event/type :timeline/clear-selection}
   {:event/type :timeline/delete-selection}
   {:event/type :timeline/select-track :track-id :t1}
   {:event/type :timeline/set-cursor-beat :beat 4.0}
   {:event/type :timeline/nudge-selection :delta-beats 1.0}
   {:event/type :timeline/toggle-loop}
   {:event/type :timeline/apply-op :op {:op :set-tempo :bpm 128.0}}
   {:event/type :timeline/update-tracks :items [{:id :t2 :type :track}] :history/description "Reorder Items"}
   {:event/type :timeline/set-track-muted :track-id :t1 :muted? true}
   {:event/type :timeline/add-track}
   {:event/type :timeline/add-folder}
   {:event/type :timeline/set-track-target :track-id :t1 :target :zone/a}
   {:event/type :timeline/set-clip-target :item-id :c1 :target :graphics}
   {:event/type :timeline/add-preset-to-track :item-id :square :track-id :t1 :start 8.25}
   {:event/type :timeline/add-effect-to-item :item-id :scale :target-item-id :c1 :index 1 :select-target? true}
   {:event/type :timeline/play :from 3.0}
   {:event/type :timeline/pause}
   {:event/type :timeline/toggle-play}
   {:event/type :timeline/stop}
   {:event/type :timeline/seek :beat 6.0}
   {:event/type :timeline/set-name :name "Intro"}
   {:event/type :timeline/set-cue-target :target :zone/a}])

(defn- a-show []
  {:editor-type :timeline
   :tracks [{:id :t1 :type :track}]
   :items [{:id :c1 :type :preset :preset-id :circle :track-id :t1 :enabled? true :effects []
            :timeline/start 0.0 :timeline/duration 16.0}]})

(deftest edits-never-create-a-show-test
  (let [s0 (-> base-state
               (assoc-in [:chains :shows] {u2 (a-show)})
               (assoc-in [:ui :timeline-editor u :selection] #{:c1}))]
    (doseq [event addressed-events]
      (testing (str (:event/type event))
        (is (identical? s0 (:state (run s0 event))) "the handler")
        (is (identical? s0 (:state (events/handle-event-pipeline (assoc event :show-id u :state s0))))
            "the pipeline")))
    (testing "the open dialog's show, gone (undo removed it): nothing either"
      (let [s (assoc-in s0 [:ui :dialogs :timeline-cue-editor] {:open? true :show-id u})]
        (doseq [event addressed-events]
          (is (identical? s (:state (timeline/handle (assoc event :state s)))) (str (:event/type event))))))))

(deftest no-handler-touches-cue-chains-test
  (let [grid-timeline (assoc (a-show) :timeline/chase :mtc)
        s0 (-> base-state
               (assoc-in [:chains :cue-chains] {[0 0] grid-timeline})
               (assoc-in [:chains :shows] {u (a-show)})
               (assoc-in [:ui :timeline-editor u :selection] #{:c1})
               (assoc-in [:ui :dialogs :timeline-cue-editor] {:open? true :show-id u}))
        ccs (get-in s0 [:chains :cue-chains])]
    (doseq [event addressed-events
            event [(assoc event :show-id u) (assoc event :col 0 :row 0)]]
      (testing (pr-str (select-keys event [:event/type :show-id :col]))
        (let [s1 (:state (events/handle-event-pipeline (assoc event :state s0)))]
          (is (identical? ccs (get-in s1 [:chains :cue-chains])))
          (is (nil? (get-in s1 [:ui :timeline-editor [0 0]])))
          (is (nil? (get-in s1 [:playback :active-cues [0 0]]))))))))

;; Transport

(defn- transport-state []
  (show-state {:editor-type :timeline
               :tracks [{:id :t :type :track}]
               :items [{:id :a :track-id :t :timeline/start 0.0 :timeline/duration 16.0}]}))

(defn- timing-at [s] (get-in s [:playback :active-cues [:show u]]))

(deftest play-from-cursor-test
  (let [s0 (assoc-in (transport-state) [:ui :timeline-editor u :cursor-beat] 6.0)
        s1 (:state (run s0 {:event/type :timeline/play :time 1000}))]
    (is (= 6.0 (:start-beat (timing-at s1))) "the show clock starts the run there")
    (is (true? (:timeline? (timing-at s1))))
    (is (uuid? (:run (timing-at s1))))
    (is (not (contains? (timing-at s1) :accumulated-beats)) "an intent holds no position")
    (is (true? (get-in s1 [:playback :playing?]))))
  (testing "a cursor at the end of a non-looping timeline plays from 0"
    (let [s0 (assoc-in (transport-state) [:ui :timeline-editor u :cursor-beat] 16.0)
          s1 (:state (run s0 {:event/type :timeline/play}))]
      (is (= 0.0 (:start-beat (timing-at s1))))))
  (testing ":from overrides the cursor"
    (let [s1 (:state (run (transport-state) {:event/type :timeline/play :from 3.0}))]
      (is (= 3.0 (:start-beat (timing-at s1)))))))

(deftest show-transport-uses-show-key-test
  (let [s0 (-> (transport-state)
               (assoc-in [:chains :cue-chains [0 0]] (a-show))
               (assoc-in [:ui :timeline-editor u :cursor-beat] 6.0))
        steps (reductions (fn [s ev] (:state (run s ev))) s0
                          [{:event/type :timeline/play}
                           {:event/type :timeline/pause}
                           {:event/type :timeline/seek :beat 2.0}
                           {:event/type :timeline/stop}])
        [_ played paused sought stopped] steps]
    (is (= {:run (:run (timing-at played)) :timeline? true :start-beat 6.0} (timing-at played)))
    (doseq [s (rest steps)]
      (is (every? #{[:show u]} (keys (get-in s [:playback :active-cues]))) "no [col row] key is written"))
    (is (true? (:paused? (timing-at paused))))
    (is (= 2.0 (get-in (timing-at sought) [:seek :beat])))
    (is (nil? (timing-at stopped)))))

(deftest pause-freezes-position-test
  (let [s1 (:state (run (transport-state) {:event/type :timeline/play :from 2.0}))
        s2 (:state (run s1 {:event/type :timeline/pause}))
        s3 (:state (run s2 {:event/type :timeline/play}))]
    (is (true? (:paused? (timing-at s2))))
    (is (false? (:paused? (timing-at s3))) "play resumes")
    (is (= (:run (timing-at s1)) (:run (timing-at s3))) "the same run, so from where it paused")
    (is (nil? (:seek (timing-at s3))) "resuming without :from doesn't seek")
    (is (nil? (timing-at (:state (run (transport-state) {:event/type :timeline/pause}))))
        "pausing a stopped timeline does nothing")
    (testing "play with :from while paused resumes and seeks"
      (let [s4 (:state (run s2 {:event/type :timeline/play :from 7.0}))]
        (is (= [false 7.0] ((juxt :paused? (comp :beat :seek)) (timing-at s4))))))))

(deftest seek-inactive-sets-cursor-only-test
  (let [s1 (:state (run (transport-state) {:event/type :timeline/seek :beat 5.0}))]
    (is (= 5.0 (get-in s1 [:ui :timeline-editor u :cursor-beat])))
    (is (nil? (timing-at s1))))
  (testing "seeking a playing timeline asks the show clock to move it, once per seek"
    (let [s1 (:state (run (transport-state) {:event/type :timeline/play :from 0.0}))
          s2 (:state (run s1 {:event/type :timeline/seek :beat 9.0}))
          s3 (:state (run s2 {:event/type :timeline/seek :beat 9.0}))]
      (is (= 9.0 (get-in (timing-at s2) [:seek :beat])))
      (is (uuid? (get-in (timing-at s2) [:seek :id])))
      (is (not= (get-in (timing-at s2) [:seek :id]) (get-in (timing-at s3) [:seek :id]))
          "seeking to the same beat again is a new request"))))

(deftest stop-keeps-cursor-test
  (let [s0 (assoc-in (transport-state) [:ui :timeline-editor u :cursor-beat] 4.0)
        s1 (:state (run s0 {:event/type :timeline/play}))
        s2 (:state (run s1 {:event/type :timeline/stop}))]
    (is (nil? (timing-at s2)))
    (is (false? (get-in s2 [:playback :playing?])))
    (is (= 4.0 (get-in s2 [:ui :timeline-editor u :cursor-beat])))))

(deftest timeline-settings-ops-test
  (let [run-op (fn [op] (show-of (:state (run (transport-state) {:event/type :timeline/apply-op :op op}))))]
    (is (= 32.0 (:timeline/length (run-op {:op :set-length :beats 32.0}))))
    (is (= 128.0 (:timeline/tempo-bpm (run-op {:op :set-tempo :bpm 128.0}))))
    (is (= 3600.0 (:timeline/tc-start (run-op {:op :set-tc-start :seconds 3600.0}))))
    (is (= 25 (:timeline/tc-fps (run-op {:op :set-tc-fps :fps 25}))))
    (is (nil? (:timeline/tc-fps (run-op {:op :set-tc-fps :fps 48}))) "unsupported fps is ignored")))

(deftest chasing-timeline-ignores-transport-test
  (let [s0 (assoc-in (transport-state) [:chains :shows u :timeline/chase] :mtc)]
    (doseq [t [:timeline/play :timeline/toggle-play :timeline/pause :timeline/stop]]
      (is (= s0 (:state (run s0 {:event/type t}))) (str t)))
    (testing ":set-chase"
      (let [run-op (fn [src] (:timeline/chase (show-of (:state (run (transport-state) {:event/type :timeline/apply-op
                                                                                      :op {:op :set-chase :source src}})))))]
        (is (= :mtc (run-op :mtc)))
        (is (nil? (run-op nil)))))))

(deftest seek-ignored-while-chasing-test
  (let [s0 (-> (transport-state)
               (assoc-in [:chains :shows u :timeline/chase] :mtc)
               (assoc-in [:playback :active-cues [:show u]] {:chasing? true :timeline? true}))]
    (is (identical? s0 (:state (run s0 {:event/type :timeline/seek :beat 3.0})))
        "no :seek lands in the chase marker, and the cursor stays (07 §4.6)")
    (testing "the ruler still moves the cursor, through set-cursor-beat"
      (is (= 3.0 (get-in (:state (run s0 {:event/type :timeline/set-cursor-beat :beat 3.0}))
                         [:ui :timeline-editor u :cursor-beat]))))))

(deftest reconcile-chase-sources-test
  (let [[u0 u1 u2 u3] (map #(java.util.UUID. 0 (+ 0xf00 %)) (range 4))
        k (fn [id] [:show id])]
    (testing "reactivity plan 15 §4 step 1: each changed follower gets the next version, a show that stops following loses its own"
      (let [follower {:editor-type :timeline :timeline/chase :mtc :items []}
            ordinary {:editor-type :timeline :items []}
            marker {:chasing? true :timeline? true :chase-id [:d (k u0) 0 nil 1]}
            s0 (-> base-state
                   (assoc-in [:project :document-id] :d)
                   (assoc-in [:chains :shows] {u0 follower u1 follower u2 ordinary})
                   (assoc-in [:playback :chase-sources] {:next 5 :cells {(k u0) 3 (k u1) 4}})
                   (assoc-in [:playback :active-cues] {(k u0) marker (k u2) {:run :r}})
                   (assoc-in [:playback :playing?] true)
                   (assoc-in [:playback :active-cell] (k u0)))
            sources #(get-in % [:playback :chase-sources])
            edit (fn [s id cue] (timeline/reconcile-chase-sources s (assoc-in s [:chains :shows id] cue)))]
        (is (identical? s0 (timeline/reconcile-chase-sources s0 s0)) "nothing changed")
        (let [after (assoc-in s0 [:ui :notice] {:text "x"})]
          (is (identical? after (timeline/reconcile-chase-sources s0 after)) "the shows identical: nothing to version"))
        (testing "an edited follower, even one equal to the show it replaced, is a new version; its marker stays"
          (let [s (edit s0 u0 (into {} follower))]
            (is (= {:next 6 :cells {(k u0) 5 (k u1) 4}} (sources s)))
            (is (= marker (get-in s [:playback :active-cues (k u0)])))))
        (testing "Follow off: its version and its marker go, as a cue stops; another show's stays"
          (let [s (edit s0 u0 ordinary)]
            (is (= {:next 5 :cells {(k u1) 4}} (sources s)) ":next never goes back")
            (is (= {(k u2) {:run :r}} (get-in s [:playback :active-cues])))
            (is (= [true (k u2)] ((juxt :playing? :active-cell) (:playback s))) "another show plays on, with the focus")))
        (testing "the last marker going stops the transport"
          (let [alone (update-in s0 [:playback :active-cues] dissoc (k u2))
                s (timeline/reconcile-chase-sources alone (update-in alone [:chains :shows] dissoc u0))]
            (is (= [false {} nil] ((juxt :playing? :active-cues :active-cell) (:playback s))))
            (is (= {:next 5 :cells {(k u1) 4}} (sources s)))))
        (testing "a run at the key (written by the edit itself) stays"
          (let [s (timeline/reconcile-chase-sources s0 (-> s0
                                                           (assoc-in [:chains :shows u0] ordinary)
                                                           (assoc-in [:playback :active-cues (k u0)] {:run :r2})))]
            (is (= {:run :r2} (get-in s [:playback :active-cues (k u0)])))))
        (testing "an ordinary show's edit, and a new follower"
          (is (= (sources s0) (sources (edit s0 u2 (assoc ordinary :name "x")))))
          (is (= {:next 6 :cells {(k u0) 3 (k u1) 4 (k u3) 5}} (sources (edit s0 u3 follower)))))
        (testing "a grid cue that follows (an older file's timeline cell) is versioned by its cell, as before"
          (let [s (timeline/reconcile-chase-sources s0 (assoc-in s0 [:chains :cue-chains [2 0]] follower))]
            (is (= {:next 6 :cells {(k u0) 3 (k u1) 4 [2 0] 5}} (sources s)))))
        (testing "a new document: its followers start at 0, shows' and cells' alike, the counter at the new one's"
          (let [s (timeline/reconcile-chase-sources s0 (-> s0
                                                           (assoc-in [:project :document-id] :d2)
                                                           (assoc-in [:chains :cue-chains [5 5]] follower)
                                                           (assoc :playback (:playback base-state))))]
            (is (= {:next 1 :cells {(k u0) 0 (k u1) 0 [5 5] 0}} (sources s)))))))
    (testing "through the pipeline: Follow on and off, undo and redo"
      (let [s0 (-> base-state
                   (assoc-in [:project :document-id] :d)
                   (assoc-in [:chains :shows u] {:editor-type :timeline :items []}))
            pipe (fn [s event] (:state (events/handle-event-pipeline (assoc event :state s))))
            follow (fn [s src] (pipe s {:event/type :timeline/apply-op :show-id u :op {:op :set-chase :source src}}))
            version #(get-in % [:playback :chase-sources :cells [:show u]])
            on (follow s0 :mtc)
            off (follow on nil)
            undone (pipe off {:event/type :edit/undo})
            redone (pipe undone {:event/type :edit/redo})]
        (is (= [1 nil 2 nil] (map version [on off undone redone])) "undo and redo are versioned too")
        (is (= 3 (get-in redone [:playback :chase-sources :next])))
        (is (= :mtc (get-in undone [:chains :shows u :timeline/chase])))))))

(deftest pipeline-normalizes-an-edited-show-test
  (let [s0 (show-state {:editor-type :timeline
                        :tracks [{:id :t :type :track}]
                        :items [{:id :c :type :preset :preset-id :circle :track-id :t
                                 :timeline/start 0.0 :timeline/duration 4.0}]})
        raw (assoc-in s0 [:chains :shows u :items 0 :track-id] nil)
        out (events/handle-event-pipeline {:event/type :chain/set-items :domain :timeline-items
                                           :entity-key [:show u]
                                           :items (:items (show-of raw))
                                           :state s0})]
    (is (= :t (get-in (show-of (:state out)) [:items 0 :track-id]))
        "a clip without a track is put back on one, as a grid timeline's is")))

(deftest add-effect-to-track-test
  (let [run-add (fn [extra] (show-of (:state (run (transport-state)
                                                  (merge {:event/type :timeline/add-effect-to-item
                                                          :item-id :scale
                                                          ;; A definition the event carries (an old bank's): the
                                                          ;; handler reads the registered one (reactivity plan 17)
                                                          :item {:id :scale :parameters [{:key :x-scale :default 9.0}]}}
                                                         extra)))))]
    (is (= [{:effect-id :scale :params (effects/get-default-params :scale) :enabled? true}]
           (mapv #(dissoc % :id) (get-in (run-add {:target-track-id :t}) [:tracks 0 :effects]))))
    (is (= :scale (get-in (run-add {:target-item-id :a}) [:items 0 :effects 0 :effect-id])))
    (is (= (show-of (transport-state)) (run-add {})) "no target")
    (is (= (show-of (transport-state)) (run-add {:target-track-id :t :item-id :no-such-effect}))
        "an id no effect has")))

(deftest track-effect-param-edit-via-chain-events-test
  (let [s0 (assoc-in (transport-state) [:chains :shows u :tracks 0 :effects]
                     [{:id :e :effect-id :scale :params {:x-scale 1.0} :enabled? true}])
        r (handlers/handle-event {:event/type :chain/update-param :domain :timeline-tracks :entity-key [:show u]
                                  :effect-path [0 :effects 0] :param-key :x-scale :fx/event 0.5 :state s0})]
    (is (= 0.5 (get-in (show-of (:state r)) [:tracks 0 :effects 0 :params :x-scale])))))

(deftest set-detail-height-clamps-test
  (let [height-after (fn [h]
                       (get-in (timeline/handle {:event/type :timeline/set-detail-height
                                                 :height h :state base-state})
                               [:state :ui :timeline-prefs :detail-height]))]
    (is (= 300.0 (get-in base-state [:ui :timeline-prefs :detail-height])) "the default")
    (is (= 420.5 (height-after 420.5)))
    (is (= 160.0 (height-after 50)))
    (is (= 600.0 (height-after 9999)))
    (is (= 300.0 (height-after nil))))
  (testing "the other prefs are untouched"
    (let [s (:state (timeline/handle {:event/type :timeline/set-detail-height
                                      :height 420.5 :state base-state}))]
      (is (= (dissoc (get-in base-state [:ui :timeline-prefs]) :detail-height)
             (dissoc (get-in s [:ui :timeline-prefs]) :detail-height))))))

;; Drag from the browser (plans/2026-11-08-timeline-rework/08-drag-from-browser.md)

(defn- drop-state
  "Show u, its editor open (drops come from it)."
  []
  (-> (show-state {:editor-type :timeline
                   :tracks [{:id :f :type :group :items [{:id :t1 :type :track}]}
                            {:id :t2 :type :track}]
                   :items [{:id :a :type :preset :preset-id :circle :track-id :t1
                            :timeline/start 2.0 :timeline/duration 4.0
                            :effects [{:id :e0 :effect-id :scale :params {} :enabled? true}
                                      {:id :e1 :effect-id :scale :params {} :enabled? true}]}]})
      (assoc-in [:ui :dialogs :timeline-cue-editor] {:open? true :show-id u})
      (assoc-in [:ui :timeline-editor u :selection] #{})
      (assoc-in [:list-ui [:timeline-tracks u]] {:selected-ids #{:t2} :last-selected-id :t2})))

(defn- add-effect [s extra]
  (:state (run s (merge {:event/type :timeline/add-effect-to-item :item-id :rotation} extra))))

(defn- selection-of [s] (get-in s [:ui :timeline-editor u :selection]))
(defn- sidebar-of [s] (get-in s [:list-ui [:timeline-tracks u] :selected-ids]))

(deftest add-effect-with-index-and-select-target-test
  (testing "a clip: inserted at :index, the clip and its track get selected"
    (let [s (add-effect (drop-state) {:target-item-id :a :index 1 :select-target? true})
          fx (get-in (show-of s) [:items 0 :effects])]
      (is (= [:scale :rotation :scale] (mapv :effect-id fx)))
      (is (= [:e0 :e1] [(:id (fx 0)) (:id (fx 2))]))
      (is (= #{:a} (selection-of s)))
      (is (= #{:t1} (sidebar-of s)))))
  (testing "a folder: appended, the selection clears and the folder is the sidebar track"
    (let [s (add-effect (assoc-in (drop-state) [:ui :timeline-editor u :selection] #{:a})
                        {:target-track-id :f :select-target? true})]
      (is (= [:rotation] (mapv :effect-id (get-in (show-of s) [:tracks 0 :effects]))))
      (is (= #{} (selection-of s)))
      (is (= #{:f} (sidebar-of s)))))
  (testing "a click (no :select-target?) leaves the selection alone"
    (let [s (add-effect (drop-state) {:target-item-id :a})]
      (is (= 3 (count (get-in (show-of s) [:items 0 :effects]))))
      (is (= #{} (selection-of s)))
      (is (= #{:t2} (sidebar-of s)))))
  (testing "an unknown target changes nothing, not even the selection"
    (let [s0 (drop-state)]
      (is (identical? s0 (add-effect s0 {:target-item-id :nope :select-target? true}))))))

(deftest add-effect-looks-up-definition-test
  (let [def-params (reduce (fn [acc {:keys [key default]}] (assoc acc key default))
                           {} (:parameters (effects/get-effect :rotation)))
        params #(get-in (show-of %) [:items 0 :effects 2 :params])]
    (is (seq def-params))
    (is (= def-params (params (add-effect (drop-state) {:target-item-id :a}))))
    (is (= def-params (params (add-effect (drop-state) {:target-item-id :a :item (effects/get-effect :rotation)})))
        "same as a click, which carries the definition")))

;; Drops resolved the way the editor does (drop/drop-target on a lane hit)

(def ^:private drop-view {:zoom 50.0 :scroll-beat 0.0 :scroll-y 0.0 :width 1000 :height 400})

(defn- canvas-drop
  "The event a drop of payload at canvas (x, y) dispatches, and its preview."
  [s payload x y]
  (let [cue (show-of s)
        rows (layout/rows (:tracks cue) (:items cue))
        h (assoc (layout/hit-test rows cue drop-view x y #{}) :beat (layout/x->beat drop-view x))]
    (drop/drop-target payload h {:show-id u :tracks (:tracks cue)
                                 :snap (get-in s [:ui :timeline-prefs :snap] {:enabled? true :value 0.25})})))

(def ^:private square-payload {:laser-show/drag :bank-item :kind :preset :id :square :category :geometric})
(def ^:private scale-payload {:laser-show/drag :bank-item :kind :effect :id :scale :category :shape})

(deftest drop-is-one-undo-step-test
  (let [s0 (assoc (drop-state) :history {:undo-stack [] :redo-stack [] :transaction nil})
        pipe (fn [s event] (:state (events/handle-event-pipeline (assoc event :state s))))
        undo-count #(count (get-in % [:history :undo-stack]))]
    (doseq [[label payload [x y] changed?]
            [["Add Clip" square-payload [413 118] #(= 2 (count (:items (show-of %))))]
             ["Add Effect" scale-payload [150 81] #(= 3 (count (get-in (show-of %) [:items 0 :effects])))]
             ["Add Effect" scale-payload [400 81] #(seq (:effects (model/find-track (:tracks (show-of %)) :t1)))]]]
      (testing (str label " at " x "," y)
        (let [{:keys [event]} (canvas-drop s0 payload x y)
              s1 (pipe s0 event)]
          (is (changed? s1))
          (is (= 1 (undo-count s1)) "exactly one undo entry")
          (is (= label (history/undo-label (:history s1))))
          (let [s2 (pipe s1 {:event/type :edit/undo})]
            (is (= (:items (show-of s0)) (:items (show-of s2))))
            (is (= (:tracks (show-of s0)) (:tracks (show-of s2))))))))))

(deftest ghost-matches-committed-clip-test
  (doseq [snap [{:enabled? true :value 0.25} {:enabled? true :value 1.0} {:enabled? false :value 0.25}]
          [x y] [[413 118] [150 81] [150 49] [3 118]]]
    (let [s0 (assoc-in (drop-state) [:ui :timeline-prefs :snap] snap)
          {:keys [event preview]} (canvas-drop s0 square-payload x y)
          s1 (:state (timeline/handle (assoc event :state s0)))
          id (first (selection-of s1))
          clip (some #(when (= id (:id %)) %) (:items (show-of s1)))]
      (is (= [(:row-id (:ghost preview)) (:start (:ghost preview)) (:length (:ghost preview))]
             [(:track-id clip) (:timeline/start clip) (:timeline/duration clip)])
          (pr-str snap x y)))))
