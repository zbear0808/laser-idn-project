(ns laser-show.events.timeline-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.handlers.timeline :as timeline]
            [laser-show.state.domains :refer [build-initial-state]]))

(def base-state
  (build-initial-state))

(deftest handle-open-editor-test
  (testing "initializes tracks, timing fields, and sets :editor-type :timeline on empty cell"
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
      (is (= {:event/type :ui/open-dialog
              :dialog-id :timeline-cue-editor
              :data {:col 1 :row 1}}
             (:dispatch result)))))

  (testing "sets :editor-type :timeline on existing cue chain with presets"
    (let [state (assoc-in base-state [:chains :cue-chains [0 0]]
                          {:items [{:preset-id :circle}]})
          event {:event/type :timeline-cue-editor/open
                 :col 0
                 :row 0
                 :state state}
          result (timeline/handle event)
          cue (get-in result [:state :chains :cue-chains [0 0]])
          first-item (first (:items cue))]
      (is (= :timeline (:editor-type cue)))
      (is (seq (:tracks cue)))
      (is (= 0.0 (:timeline/start first-item)))
      (is (= 4.0 (:timeline/duration first-item))))))

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
      (is (= 4.0 (get-in add-res [:state :ui :timeline :cursor-beat])))
      (is (= [(:id added)] (get-in add-res [:dispatch :ids])))
      (is (= #{(:track-id added)} (get-in add-res [:state :list-ui :timeline-tracks :selected-ids])))))

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
      (is (= 8.0 (get-in s2 [:ui :timeline :cursor-beat]))))))

(deftest handle-select-track-test
  (testing "sets selected-ids and last-selected-id in canonical list-ui state"
    (let [track-id (random-uuid)
          result (timeline/handle {:event/type :timeline/select-track
                                   :track-id track-id
                                   :state base-state})
          ui-state (get-in result [:state :list-ui :timeline-tracks])]
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
          ui-state (get-in result [:state :list-ui :timeline-tracks])]
      (is (seq tracks))
      (is (= #{(:id new-track)} (:selected-ids ui-state)))
      (is (= (:id new-track) (:last-selected-id ui-state))))))

(deftest handle-move-item-to-track-test
  (testing "moves item to target track without affecting other fields"
    (let [open-res (timeline/handle {:event/type :timeline-cue-editor/open
                                     :col 0 :row 0 :state base-state})
          add-res (timeline/handle {:event/type :timeline/add-preset-to-track
                                    :col 0 :row 0
                                    :item-id :circle
                                    :state (:state open-res)})
          item (first (get-in add-res [:state :chains :cue-chains [0 0] :items]))
          item-id (:id item)
          target-track-id (random-uuid)
          move-res (timeline/handle {:event/type :timeline/move-item-to-track
                                     :col 0 :row 0
                                     :item-id item-id
                                     :track-id target-track-id
                                     :state (:state add-res)})
          updated-item (first (get-in move-res [:state :chains :cue-chains [0 0] :items]))]
      (is (= target-track-id (:track-id updated-item)))
      (is (= (:timeline/start item) (:timeline/start updated-item)))
      (is (= (:timeline/duration item) (:timeline/duration updated-item))))))

(deftest handle-set-hvalue-test
  (testing "sets and clamps scroll-hvalue in state"
    (let [res (timeline/handle {:event/type :timeline/set-hvalue
                                :hvalue 0.65
                                :state base-state})]
      (is (== 0.65 (get-in res [:state :ui :timeline :scroll-hvalue]))))

    (let [res (timeline/handle {:event/type :timeline/set-hvalue
                                :hvalue 1.5
                                :state base-state})]
      (is (== 1.0 (get-in res [:state :ui :timeline :scroll-hvalue]))))

    (let [res (timeline/handle {:event/type :timeline/set-hvalue
                                :hvalue -0.2
                                :state base-state})]
      (is (== 0.0 (get-in res [:state :ui :timeline :scroll-hvalue]))))))

(deftest handle-scroll-horizontal-test
  (testing "preserves scroll-hvalue in state when scroll-pane is not registered (headless)"
    (let [s0 (assoc-in base-state [:ui :timeline :scroll-hvalue] 0.4)
          res (timeline/handle {:event/type :timeline/scroll-horizontal
                                :delta-y 40.0
                                :state s0})]
      (is (== 0.4 (get-in res [:state :ui :timeline :scroll-hvalue]))))))

(deftest handle-zoom-at-cursor-test
  (testing "zooms in and out centered at cursor"
    (let [s0 (assoc-in base-state [:ui :timeline :zoom-x] 50.0)
          ;; Zoom in
          res-in (timeline/handle {:event/type :timeline/zoom-at-cursor
                                  :x 200.0
                                  :delta-y 40.0
                                  :col 0 :row 0
                                  :state s0})
          zoom-in (get-in res-in [:state :ui :timeline :zoom-x])]
      (is (> zoom-in 50.0))
      ;; Zoom out
      (let [res-out (timeline/handle {:event/type :timeline/zoom-at-cursor
                                     :x 200.0
                                     :delta-y -40.0
                                     :col 0 :row 0
                                     :state (:state res-in)})
            zoom-out (get-in res-out [:state :ui :timeline :zoom-x])]
        (is (< zoom-out zoom-in)))))

  (testing "enforces zoom bounds"
    (let [s-max (assoc-in base-state [:ui :timeline :zoom-x] 190.0)
          res (timeline/handle {:event/type :timeline/zoom-at-cursor
                               :x 100.0
                               :delta-y 40.0
                               :col 0 :row 0
                               :state s-max})]
      (is (== 200.0 (get-in res [:state :ui :timeline :zoom-x]))))

    (let [s-min (assoc-in base-state [:ui :timeline :zoom-x] 11.0)
          res (timeline/handle {:event/type :timeline/zoom-at-cursor
                               :x 100.0
                               :delta-y -40.0
                               :col 0 :row 0
                               :state s-min})]
      (is (== 10.0 (get-in res [:state :ui :timeline :zoom-x]))))))

(deftest handle-delete-selection-test
  (let [a (random-uuid) b (random-uuid) c (random-uuid)
        s0 (-> base-state
               (assoc-in [:chains :cue-chains [0 0] :items]
                         [{:id a :preset-id :circle}
                          {:id b :preset-id :square}
                          {:id c :preset-id :line}]))]
    (testing "removes only the selected clips and clears the selection"
      (let [s1 (assoc-in s0 [:ui :timeline :selection] #{a c})
            res (timeline/handle {:event/type :timeline/delete-selection
                                  :col 0 :row 0 :state s1})]
        (is (= [b] (mapv :id (get-in res [:state :chains :cue-chains [0 0] :items]))))
        (is (= #{} (get-in res [:state :ui :timeline :selection])))))

    (testing "is a no-op with an empty selection"
      (let [s1 (assoc-in s0 [:ui :timeline :selection] #{})
            res (timeline/handle {:event/type :timeline/delete-selection
                                  :col 0 :row 0 :state s1})]
        (is (identical? s1 (:state res)))))))


(deftest set-zoom-uses-fx-event-test
  (testing "the slider's new value arrives as :fx/event"
    (let [s0 (assoc-in base-state [:ui :timeline :zoom-x] 50.0)
          res (timeline/handle {:event/type :timeline/set-zoom
                                :fx/event 120.0
                                :state s0})]
      (is (== 120.0 (get-in res [:state :ui :timeline :zoom-x])))))
  (testing "an explicit :zoom still works and is clamped"
    (let [res (timeline/handle {:event/type :timeline/set-zoom
                                :zoom 500.0
                                :state base-state})]
      (is (== 200.0 (get-in res [:state :ui :timeline :zoom-x]))))))

(deftest set-snap-uses-fx-event-false-test
  (testing "an unchecked checkbox (:fx/event false) disables snap"
    (let [s0 (assoc-in base-state [:ui :timeline :snap-enabled?] true)
          res (timeline/handle {:event/type :timeline/set-snap
                                :fx/event false
                                :state s0})]
      (is (false? (get-in res [:state :ui :timeline :snap-enabled?])))))
  (testing "a checked checkbox enables it"
    (let [s0 (assoc-in base-state [:ui :timeline :snap-enabled?] false)
          res (timeline/handle {:event/type :timeline/set-snap
                                :fx/event true
                                :state s0})]
      (is (true? (get-in res [:state :ui :timeline :snap-enabled?])))))
  (testing "the snap-value combo leaves the enabled flag alone"
    (let [s0 (assoc-in base-state [:ui :timeline :snap-enabled?] false)
          res (timeline/handle {:event/type :timeline/set-snap
                                :value 0.5
                                :state s0})]
      (is (false? (get-in res [:state :ui :timeline :snap-enabled?])))
      (is (== 0.5 (get-in res [:state :ui :timeline :snap-value]))))))

(deftest set-hvalue-has-no-side-effects-test
  (let [res (timeline/handle {:event/type :timeline/set-hvalue
                              :fx/event 0.3
                              :state base-state})]
    (is (= #{:state} (set (keys res))))
    (is (== 0.3 (get-in res [:state :ui :timeline :scroll-hvalue])))))

(deftest update-loop-timing-missing-loop-stays-disabled-test
  (let [s0 (assoc-in base-state [:chains :cue-chains [0 0]] {:items []})
        res (timeline/handle {:event/type :timeline/update-loop-timing
                              :col 0 :row 0 :start 2.0
                              :state s0})]
    (is (false? (get-in res [:state :chains :cue-chains [0 0] :loop :enabled?])))))

(defn- cell-with-clip [state clip]
  (assoc-in state [:chains :cue-chains [0 0] :items] [clip]))

(deftest update-item-timing-left-edge-keeps-end-test
  (let [s0 (cell-with-clip base-state {:id :c :timeline/start 1.0 :timeline/duration 4.0})
        clip-after (fn [event]
                     (-> (timeline/handle (merge {:event/type :timeline/update-item-timing
                                                  :col 0 :row 0 :id :c :state s0}
                                                 event))
                         (get-in [:state :chains :cue-chains [0 0] :items 0])))]
    (testing "trimming the left edge past 0 doesn't lengthen the clip's end"
      (let [c (clip-after {:edge :left :start -3.0})]
        (is (== 0.0 (:timeline/start c)))
        (is (== 5.0 (:timeline/duration c)))))
    (testing "trimming the left edge past the end leaves a sliver ending at the old end"
      (let [c (clip-after {:edge :left :start 9.0})]
        (is (== 5.0 (+ (:timeline/start c) (:timeline/duration c))))))
    (testing "right edge keeps the start"
      (let [c (clip-after {:edge :right :end 7.0})]
        (is (== 1.0 (:timeline/start c)))
        (is (== 6.0 (:timeline/duration c)))))
    (testing "center moves without changing length"
      (let [c (clip-after {:edge :center :start 3.0})]
        (is (== 3.0 (:timeline/start c)))
        (is (== 4.0 (:timeline/duration c)))))))
