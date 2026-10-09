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

