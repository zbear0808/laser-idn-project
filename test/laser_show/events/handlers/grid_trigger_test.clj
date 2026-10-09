(ns laser-show.events.handlers.grid-trigger-test
  "Unit tests for grid and effects cell triggers, movement, and cleanup."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.handlers.grid :as grid-handlers]
            [laser-show.events.handlers.effects :as effects-handlers]))

(deftest grid-trigger-cell-test
  (testing "Triggering cell with content in toggle mode activates playback"
    (let [cue-data {:items [{:id :preset-1}] :trigger-mode :toggle}
          initial-state {:chains {:cue-chains {[0 0] cue-data}}
                         :config {:cue {:trigger-mode :toggle}}
                         :playback {:active-cues {}}}
          res (grid-handlers/handle {:event/type :grid/trigger-cell
                                     :col 0
                                     :row 0
                                     :state initial-state})
          st (:state res)]
      (is (true? (get-in st [:playback :playing?])))
      (is (contains? (get-in st [:playback :active-cues]) [0 0]))

      (testing "Triggering again in toggle mode untriggers"
        (let [untrig-res (grid-handlers/handle {:event/type :grid/trigger-cell
                                                :col 0
                                                :row 0
                                                :state st})
              untrig-st (:state untrig-res)]
          (is (false? (get-in untrig-st [:playback :playing?])))
          (is (not (contains? (get-in untrig-st [:playback :active-cues]) [0 0])))))))

  (testing "Triggering empty cell selects it without starting playback"
    (let [initial-state {:chains {:cue-chains {}}
                         :playback {:active-cues {}}}
          res (grid-handlers/handle {:event/type :grid/trigger-cell
                                     :col 1
                                     :row 2
                                     :state initial-state})
          st (:state res)]
      (is (= [1 2] (get-in st [:grid :selected-cell])))
      (is (empty? (get-in st [:playback :active-cues]))))))

(deftest grid-cell-move-migrates-triggers-test
  (testing "Moving cue cell migrates trigger mappings to new coordinates"
    (let [trigger-key {:source :midi :channel 0 :note 60}
          initial-state {:chains {:cue-chains {[0 0] {:items [:preset]}}}
                         :config {:input {:trigger-map {trigger-key {:type :trigger-cue :target [0 0]}}}}}
          res (grid-handlers/handle {:event/type :grid/move-cell
                                     :from-col 0 :from-row 0
                                     :to-col 2 :to-row 3
                                     :state initial-state})
          st (:state res)]
      (is (= {:type :trigger-cue :target [2 3]}
             (get-in st [:config :input :trigger-map trigger-key]))))))

(deftest grid-cell-clear-removes-triggers-test
  (testing "Clearing cue cell removes associated trigger mappings"
    (let [trigger-key {:source :midi :channel 0 :note 60}
          other-key {:source :midi :channel 0 :note 64}
          initial-state {:chains {:cue-chains {[0 0] {:items [:preset]}}}
                         :config {:input {:trigger-map {trigger-key {:type :trigger-cue :target [0 0]}
                                                        other-key {:type :trigger-cue :target [1 1]}}}}}
          res (grid-handlers/handle {:event/type :grid/clear-cell
                                     :col 0 :row 0
                                     :state initial-state})
          st (:state res)]
      (is (nil? (get-in st [:config :input :trigger-map trigger-key])))
      (is (some? (get-in st [:config :input :trigger-map other-key]))))))

(deftest effects-cell-move-and-clear-triggers-test
  (testing "Moving effects cell migrates effect triggers"
    (let [trigger-key {:source :osc :id "/effect/toggle"}
          initial-state {:chains {:effect-chains {[0 0] {:items [:effect]}}}
                         :config {:input {:trigger-map {trigger-key {:type :trigger-effect :target [0 0]}}}}}
          res (effects-handlers/handle {:event/type :effects/move-cell
                                        :from-col 0 :from-row 0
                                        :to-col 1 :to-row 1
                                        :state initial-state})
          st (:state res)]
      (is (= {:type :trigger-effect :target [1 1]}
             (get-in st [:config :input :trigger-map trigger-key])))))

  (testing "Clearing effects cell removes effect triggers"
    (let [trigger-key {:source :osc :id "/effect/toggle"}
          initial-state {:chains {:effect-chains {[0 0] {:items [:effect]}}}
                         :config {:input {:trigger-map {trigger-key {:type :trigger-effect :target [0 0]}}}}}
          res (effects-handlers/handle {:event/type :effects/clear-cell
                                        :col 0 :row 0
                                        :state initial-state})
          st (:state res)]
      (is (nil? (get-in st [:config :input :trigger-map trigger-key]))))))

