(ns laser-show.events.timeline-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.timeline :as timeline]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.state.core :as state]
            [laser-show.state.domains :refer [build-initial-state]]))

(def base-state
  (build-initial-state))

(deftest handle-open-editor-test
  (testing "initializes tracks and sets :editor-type :timeline on an empty cell"
    (let [empty-state (assoc-in base-state [:chains :cue-chains] {})
          event {:event/type :timeline-cue-editor/open
                 :col 1
                 :row 1
                 :state empty-state}
          result (timeline/handle event)
          cue (get-in result [:state :chains :cue-chains [1 1]])]
      (is (= :timeline (:editor-type cue)))
      (is (vector? (:tracks cue)))
      (is (seq (:tracks cue)))
      (is (nil? (:loop cue)) "an empty cell gets no loop")
      (is (= "Convert to Timeline Cue" (:history/description result)))
      (is (= {:event/type :ui/open-dialog
              :dialog-id :timeline-cue-editor
              :data {:col 1 :row 1}}
             (:dispatch result)))))

  (testing "converts a plain cue to 16-beat clips under an enabled 16-beat loop"
    (let [state (assoc-in base-state [:chains :cue-chains [0 0]]
                          {:items [{:id :a :type :preset :preset-id :circle}]})
          result (timeline/handle {:event/type :timeline-cue-editor/open
                                   :col 0 :row 0 :state state})
          cue (get-in result [:state :chains :cue-chains [0 0]])
          first-item (first (:items cue))]
      (is (= :timeline (:editor-type cue)))
      (is (seq (:tracks cue)))
      (is (= 0.0 (:timeline/start first-item)))
      (is (= 16.0 (:timeline/duration first-item)))
      (is (= {:enabled? true :start 0.0 :duration 16.0} (:loop cue))))))

(deftest open-editor-on-timeline-cue-does-not-change-document-test
  (let [converted (:state (timeline/handle {:event/type :timeline-cue-editor/open
                                            :col 0 :row 0 :state base-state}))
        again (timeline/handle {:event/type :timeline-cue-editor/open
                                :col 0 :row 0 :state converted})]
    (is (identical? (get-in converted [:chains :cue-chains])
                    (get-in again [:state :chains :cue-chains])))
    (is (not (contains? again :history/backfill)))
    (is (not (contains? again :history/description)))))

(deftest open-editor-converts-without-changing-output-test
  (let [plain {:destination-zone {:zone-group-id :all}
               :items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true :effects []}
                       {:id (random-uuid) :type :preset :preset-id :square :enabled? true :effects []}]}
        state (assoc-in base-state [:chains :cue-chains [0 0]] plain)
        converted (get-in (timeline/handle {:event/type :timeline-cue-editor/open
                                            :col 0 :row 0 :state state})
                          [:state :chains :cue-chains [0 0]])
        render (fn [cue beats]
                 (let [frames (frame-service/generate-frames-by-zone
                               cue 0 120.0 0 {:accumulated-beats beats :accumulated-ms 0.0
                                              :phase-offset 0.0 :effective-beats beats
                                              :time-ms 0 :bpm 120.0 :trigger-time 0})]
                   (update-vals frames count)))]
    (state/init-state! base-state)
    ;; Accumulated beats wrap inside [0, 16); the phase offset can push the
    ;; effective position up to ~1 beat past the loop end.
    (doseq [beats [0.0 5.0 15.0 15.99 16.5]]
      (is (= (render plain beats) (render converted beats))
          (str "same zones and point counts at beat " beats)))))

(deftest ui-state-is-per-cell-test
  (let [s0 (-> base-state
               (assoc-in [:ui :timeline-editor [0 0]] {:selection #{:x} :cursor-beat 8.0 :zoom-x 80.0})
               (assoc-in [:list-ui [:timeline-tracks 0 0]] {:selected-ids #{:t}}))
        s1 (:state (timeline/handle {:event/type :timeline/set-cursor-beat :col 2 :row 1 :beat 4.0 :state s0}))
        s2 (:state (timeline/handle {:event/type :timeline/select-items :col 2 :row 1 :ids [:y] :state s1}))]
    (testing "editing another cell's view leaves this one alone"
      (is (= {:selection #{:x} :cursor-beat 8.0 :zoom-x 80.0} (get-in s2 [:ui :timeline-editor [0 0]])))
      (is (= {:cursor-beat 4.0 :selection #{:y}} (get-in s2 [:ui :timeline-editor [2 1]]))))
    (testing "opening the editor doesn't reset any cell's view"
      (let [s3 (:state (timeline/handle {:event/type :timeline-cue-editor/open :col 2 :row 1 :state s2}))]
        (is (= (get-in s2 [:ui :timeline-editor]) (get-in s3 [:ui :timeline-editor])))
        (is (= {:selected-ids #{:t}} (get-in s3 [:list-ui [:timeline-tracks 0 0]])))))
    (testing "events without a cell use the open editor's cell"
      (let [s3 (-> s2
                   (assoc-in [:ui :dialogs :timeline-cue-editor] {:open? true :col 2 :row 1})
                   (#(:state (timeline/handle {:event/type :timeline/clear-selection :state %}))))]
        (is (= #{} (get-in s3 [:ui :timeline-editor [2 1] :selection])))
        (is (= #{:x} (get-in s3 [:ui :timeline-editor [0 0] :selection])))))))

(deftest add-track-does-not-stamp-zone-test
  (let [s0 (assoc-in base-state [:chains :cue-chains [0 0]]
                     {:destination-zone {:zone-group-id :front} :tracks [] :items []})
        tracks (fn [ev] (get-in (timeline/handle (assoc ev :col 0 :row 0 :state s0))
                                [:state :chains :cue-chains [0 0] :tracks]))]
    (is (not (contains? (last (tracks {:event/type :timeline/add-track})) :zone-group-id)))
    (is (not (contains? (last (tracks {:event/type :timeline/add-folder})) :zone-group-id)))
    (is (= :back (:zone-group-id (last (tracks {:event/type :timeline/add-track :zone-group-id :back})))))))

(deftest handle-add-preset-to-track-test
  (testing "adds preset at cursor-beat on resolved leaf track"
    (let [open-res (timeline/handle {:event/type :timeline-cue-editor/open
                                     :col 0 :row 0 :state base-state})
          state-with-tracks (:state open-res)
          add-res (timeline/handle {:event/type :timeline/add-preset-to-track
                                    :col 0 :row 0
                                    :item-id :circle
                                    :state state-with-tracks})
          items (get-in add-res [:state :chains :cue-chains [0 0] :items])
          added (first items)]
      (is (= 1 (count items)))
      (is (= :circle (:preset-id added)))
      (is (some? (:track-id added)))
      (is (= 0.0 (:timeline/start added)))
      (is (= 4.0 (:timeline/duration added)))
      (is (= 4.0 (get-in add-res [:state :ui :timeline-editor [0 0] :cursor-beat])))
      (is (= #{(:id added)} (get-in add-res [:state :ui :timeline-editor [0 0] :selection])))
      (is (= #{(:track-id added)} (get-in add-res [:state :list-ui [:timeline-tracks 0 0] :selected-ids])))))

  (testing "advances cursor beat for successive additions"
    (let [open-res (timeline/handle {:event/type :timeline-cue-editor/open
                                     :col 0 :row 0 :state base-state})
          s1 (:state (timeline/handle {:event/type :timeline/add-preset-to-track
                                       :col 0 :row 0
                                       :item-id :circle
                                       :state (:state open-res)}))
          s2 (:state (timeline/handle {:event/type :timeline/add-preset-to-track
                                       :col 0 :row 0
                                       :item-id :square
                                       :state s1}))
          items (get-in s2 [:chains :cue-chains [0 0] :items])]
      (is (= 2 (count items)))
      (is (= 0.0 (:timeline/start (first items))))
      (is (= 4.0 (:timeline/start (second items))))
      (is (= 8.0 (get-in s2 [:ui :timeline-editor [0 0] :cursor-beat]))))))

(deftest handle-select-track-test
  (testing "sets selected-ids and last-selected-id in canonical list-ui state"
    (let [track-id (random-uuid)
          result (timeline/handle {:event/type :timeline/select-track :col 0 :row 0
                                   :track-id track-id
                                   :state base-state})
          ui-state (get-in result [:state :list-ui [:timeline-tracks 0 0]])]
      (is (= #{track-id} (:selected-ids ui-state)))
      (is (= track-id (:last-selected-id ui-state))))))

(deftest handle-add-track-test
  (testing "appends track and selects it in canonical list-ui state"
    (let [open-res (timeline/handle {:event/type :timeline-cue-editor/open
                                     :col 0 :row 0 :state base-state})
          result (timeline/handle {:event/type :timeline/add-track
                                   :col 0 :row 0
                                   :state (:state open-res)})
          tracks (get-in result [:state :chains :cue-chains [0 0] :tracks])
          new-track (last tracks)
          ui-state (get-in result [:state :list-ui [:timeline-tracks 0 0]])]
      (is (seq tracks))
      (is (= #{(:id new-track)} (:selected-ids ui-state)))
      (is (= (:id new-track) (:last-selected-id ui-state))))))

(deftest set-clip-track-op-test
  (testing "moves item to target track without affecting other fields"
    (let [open-res (timeline/handle {:event/type :timeline-cue-editor/open
                                     :col 0 :row 0 :state base-state})
          add-res (timeline/handle {:event/type :timeline/add-preset-to-track
                                    :col 0 :row 0
                                    :item-id :circle
                                    :state (:state open-res)})
          item (first (get-in add-res [:state :chains :cue-chains [0 0] :items]))
          item-id (:id item)
          with-track (:state (timeline/handle {:event/type :timeline/add-track
                                               :col 0 :row 0 :state (:state add-res)}))
          target-track-id (:id (last (get-in with-track [:chains :cue-chains [0 0] :tracks])))
          move (fn [track-id]
                 (-> (timeline/handle {:event/type :timeline/apply-op
                                       :col 0 :row 0
                                       :op {:op :set-clip-track :clip-id item-id :track-id track-id}
                                       :state with-track})
                     (get-in [:state :chains :cue-chains [0 0] :items 0])))
          updated-item (move target-track-id)]
      (is (= target-track-id (:track-id updated-item)))
      (is (= (:timeline/start item) (:timeline/start updated-item)))
      (is (= (:timeline/duration item) (:timeline/duration updated-item)))
      (is (= (:track-id item) (:track-id (move (random-uuid)))) "unknown track: unchanged"))))

(deftest handle-set-hvalue-test
  (testing "sets and clamps scroll-hvalue in state"
    (let [res (timeline/handle {:event/type :timeline/set-hvalue :col 0 :row 0
                                :hvalue 0.65
                                :state base-state})]
      (is (== 0.65 (get-in res [:state :ui :timeline-editor [0 0] :scroll-hvalue]))))

    (let [res (timeline/handle {:event/type :timeline/set-hvalue :col 0 :row 0
                                :hvalue 1.5
                                :state base-state})]
      (is (== 1.0 (get-in res [:state :ui :timeline-editor [0 0] :scroll-hvalue]))))

    (let [res (timeline/handle {:event/type :timeline/set-hvalue :col 0 :row 0
                                :hvalue -0.2
                                :state base-state})]
      (is (== 0.0 (get-in res [:state :ui :timeline-editor [0 0] :scroll-hvalue]))))))

(deftest handle-zoom-at-cursor-test
  (testing "zooms in and out centered at cursor"
    (let [s0 (assoc-in base-state [:ui :timeline-editor [0 0] :zoom-x] 50.0)
          ;; Zoom in
          res-in (timeline/handle {:event/type :timeline/zoom-at-cursor
                                  :x 200.0
                                  :delta-y 40.0
                                  :col 0 :row 0
                                  :state s0})
          zoom-in (get-in res-in [:state :ui :timeline-editor [0 0] :zoom-x])]
      (is (> zoom-in 50.0))
      ;; Zoom out
      (let [res-out (timeline/handle {:event/type :timeline/zoom-at-cursor
                                     :x 200.0
                                     :delta-y -40.0
                                     :col 0 :row 0
                                     :state (:state res-in)})
            zoom-out (get-in res-out [:state :ui :timeline-editor [0 0] :zoom-x])]
        (is (< zoom-out zoom-in)))))

  (testing "enforces zoom bounds"
    (let [s-max (assoc-in base-state [:ui :timeline-editor [0 0] :zoom-x] 190.0)
          res (timeline/handle {:event/type :timeline/zoom-at-cursor
                               :x 100.0
                               :delta-y 40.0
                               :col 0 :row 0
                               :state s-max})]
      (is (== 200.0 (get-in res [:state :ui :timeline-editor [0 0] :zoom-x]))))

    (let [s-min (assoc-in base-state [:ui :timeline-editor [0 0] :zoom-x] 11.0)
          res (timeline/handle {:event/type :timeline/zoom-at-cursor
                               :x 100.0
                               :delta-y -40.0
                               :col 0 :row 0
                               :state s-min})]
      (is (== 10.0 (get-in res [:state :ui :timeline-editor [0 0] :zoom-x]))))))

(deftest handle-delete-selection-test
  (let [a (random-uuid) b (random-uuid) c (random-uuid)
        s0 (-> base-state
               (assoc-in [:chains :cue-chains [0 0] :items]
                         [{:id a :preset-id :circle}
                          {:id b :preset-id :square}
                          {:id c :preset-id :line}]))]
    (testing "removes only the selected clips and clears the selection"
      (let [s1 (assoc-in s0 [:ui :timeline-editor [0 0] :selection] #{a c})
            res (timeline/handle {:event/type :timeline/delete-selection
                                  :col 0 :row 0 :state s1})]
        (is (= [b] (mapv :id (get-in res [:state :chains :cue-chains [0 0] :items]))))
        (is (= #{} (get-in res [:state :ui :timeline-editor [0 0] :selection])))))

    (testing "is a no-op with an empty selection"
      (let [s1 (assoc-in s0 [:ui :timeline-editor [0 0] :selection] #{})
            res (timeline/handle {:event/type :timeline/delete-selection
                                  :col 0 :row 0 :state s1})]
        (is (identical? s1 (:state res)))))))


(deftest set-zoom-uses-fx-event-test
  (testing "the slider's new value arrives as :fx/event"
    (let [s0 (assoc-in base-state [:ui :timeline-editor [0 0] :zoom-x] 50.0)
          res (timeline/handle {:event/type :timeline/set-zoom :col 0 :row 0
                                :fx/event 120.0
                                :state s0})]
      (is (== 120.0 (get-in res [:state :ui :timeline-editor [0 0] :zoom-x])))))
  (testing "an explicit :zoom still works and is clamped"
    (let [res (timeline/handle {:event/type :timeline/set-zoom :col 0 :row 0
                                :zoom 500.0
                                :state base-state})]
      (is (== 200.0 (get-in res [:state :ui :timeline-editor [0 0] :zoom-x]))))))

(deftest set-snap-uses-fx-event-false-test
  (testing "an unchecked checkbox (:fx/event false) disables snap"
    (let [s0 (assoc-in base-state [:ui :timeline-prefs :snap :enabled?] true)
          res (timeline/handle {:event/type :timeline/set-snap :col 0 :row 0
                                :fx/event false
                                :state s0})]
      (is (false? (get-in res [:state :ui :timeline-prefs :snap :enabled?])))))
  (testing "a checked checkbox enables it"
    (let [s0 (assoc-in base-state [:ui :timeline-prefs :snap :enabled?] false)
          res (timeline/handle {:event/type :timeline/set-snap :col 0 :row 0
                                :fx/event true
                                :state s0})]
      (is (true? (get-in res [:state :ui :timeline-prefs :snap :enabled?])))))
  (testing "the snap-value combo leaves the enabled flag alone"
    (let [s0 (assoc-in base-state [:ui :timeline-prefs :snap :enabled?] false)
          res (timeline/handle {:event/type :timeline/set-snap :col 0 :row 0
                                :value 0.5
                                :state s0})]
      (is (false? (get-in res [:state :ui :timeline-prefs :snap :enabled?])))
      (is (== 0.5 (get-in res [:state :ui :timeline-prefs :snap :value]))))))

(deftest set-hvalue-has-no-side-effects-test
  (let [res (timeline/handle {:event/type :timeline/set-hvalue :col 0 :row 0
                              :fx/event 0.3
                              :state base-state})]
    (is (= #{:state} (set (keys res))))
    (is (== 0.3 (get-in res [:state :ui :timeline-editor [0 0] :scroll-hvalue])))))

(deftest loop-op-on-missing-loop-stays-disabled-test
  (let [s0 (assoc-in base-state [:chains :cue-chains [0 0]] {:editor-type :timeline :items []})
        res (timeline/handle {:event/type :timeline/apply-op
                              :col 0 :row 0 :op {:op :move-loop :delta 2.0}
                              :state s0})]
    (is (false? (get-in res [:state :chains :cue-chains [0 0] :loop :enabled?])))
    (is (== 2.0 (get-in res [:state :chains :cue-chains [0 0] :loop :start])))))

(defn- cell-with-clip [state clip]
  (assoc-in state [:chains :cue-chains [0 0] :items] [clip]))

(deftest trim-ops-keep-the-other-edge-test
  (let [s0 (cell-with-clip base-state {:id :c :track-id :t :timeline/start 1.0 :timeline/duration 4.0})
        s0 (assoc-in s0 [:chains :cue-chains [0 0] :tracks] [{:id :t :type :track}])
        clip-after (fn [op]
                     (-> (timeline/handle {:event/type :timeline/apply-op :col 0 :row 0 :op op :state s0})
                         (get-in [:state :chains :cue-chains [0 0] :items 0])))]
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
  (let [s0 (-> base-state
               (assoc-in [:chains :cue-chains [0 0]]
                         {:editor-type :timeline
                          :tracks [{:id :f :type :group :items [{:id :t1 :type :track}]}
                                   {:id :t2 :type :track}]
                          :items [{:id :a :track-id :t1 :timeline/start 0.0 :timeline/duration 4.0}
                                  {:id :b :track-id :t2 :timeline/start 0.0 :timeline/duration 4.0}]})
               (assoc-in [:ui :timeline-editor [0 0] :selection] #{:a :b}))
        cue (fn [tracks]
              (:state (timeline/handle {:event/type :timeline/update-tracks
                                        :col 0 :row 0 :items tracks :state s0})))]
    (testing "deleting the folder deletes the clips of the track inside it"
      (let [s1 (cue [{:id :t2 :type :track}])]
        (is (= [:b] (mapv :id (get-in s1 [:chains :cue-chains [0 0] :items]))))
        (is (= #{:b} (get-in s1 [:ui :timeline-editor [0 0] :selection])))))
    (testing "reordering or regrouping keeps every clip"
      (let [s1 (cue [{:id :t2 :type :track} {:id :t1 :type :track}])]
        (is (= [:a :b] (mapv :id (get-in s1 [:chains :cue-chains [0 0] :items]))))))))

(deftest set-track-muted-test
  (let [s0 (assoc-in base-state [:chains :cue-chains [0 0] :tracks]
                     [{:id :f :type :group :items [{:id :t1 :type :track}]}])
        muted (:state (timeline/handle {:event/type :timeline/set-track-muted
                                        :col 0 :row 0 :track-id :t1 :muted? true
                                        :state s0}))
        unmuted (:state (timeline/handle {:event/type :timeline/set-track-muted
                                          :col 0 :row 0 :track-id :t1 :muted? false
                                          :state muted}))]
    (is (true? (get-in muted [:chains :cue-chains [0 0] :tracks 0 :items 0 :muted?])))
    (is (not (contains? (get-in unmuted [:chains :cue-chains [0 0] :tracks 0 :items 0]) :muted?)))))

(deftest apply-op-prunes-selection-test
  (let [s0 (-> base-state
               (assoc-in [:chains :cue-chains [0 0]]
                         {:editor-type :timeline
                          :tracks [{:id :t1 :type :track}]
                          :items [{:id :a :track-id :t1 :timeline/start 0.0 :timeline/duration 4.0}
                                  {:id :b :track-id :t1 :timeline/start 4.0 :timeline/duration 4.0}]})
               (assoc-in [:ui :timeline-editor [0 0] :selection] #{:a :b}))
        res (timeline/handle {:event/type :timeline/apply-op :col 0 :row 0
                              :op {:op :delete-clips :clip-ids #{:a}} :state s0})]
    (is (= [:b] (mapv :id (get-in res [:state :chains :cue-chains [0 0] :items]))))
    (is (= #{:b} (get-in res [:state :ui :timeline-editor [0 0] :selection])))
    (is (= "Delete Clip" (:history/description res))))
  (testing "unknown ops change nothing"
    (let [res (timeline/handle {:event/type :timeline/apply-op :col 0 :row 0
                                :op {:op :nope} :state base-state})]
      (is (identical? base-state (:state res))))))
