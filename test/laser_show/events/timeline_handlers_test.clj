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
      (is (= #{(:id added)} (get-in add-res [:dispatch :ids])))))

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
