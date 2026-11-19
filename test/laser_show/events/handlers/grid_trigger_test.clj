(ns laser-show.events.handlers.grid-trigger-test
  "Unit tests for grid and effects cell triggers, movement, and cleanup."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.handlers.grid :as grid-handlers]
            [laser-show.events.handlers.effects :as effects-handlers]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.engine :as engine]))

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


(defn- starter-vibes []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)))

(defn- vibe [state id & ks] (get-in state (into [:config :vibes :library id] ks)))

(deftest grid-cell-move-follows-vibe-pools-test
  (let [st (:state (grid-handlers/handle {:event/type :grid/move-cell
                                          :from-col 0 :from-row 0 :to-col 2 :to-row 3
                                          :state (starter-vibes)}))]
    (is (= [[2 3] [1 0] [2 0] [3 0]] (vibe st :vibe-1 :hit :cells)) "the pool keeps its order"))
  (testing "a moved flash cue, and a playing voice: its release still stops it"
    (let [s0 (:state (engine/press (starter-vibes) {:role :hit :time 1000}))
          [col row] (get-in s0 [:vibe-runtime :voices :hit :cell])
          moved (:state (grid-handlers/handle {:event/type :grid/move-cell
                                               :from-col col :from-row row :to-col 5 :to-row 3 :state s0}))
          released (:state (engine/release moved {:role :hit :time 1400}))]
      (is (= [5 3] (get-in moved [:vibe-runtime :voices :hit :cell])))
      (is (some? (get-in moved [:playback :active-cues [5 3]])))
      (is (nil? (get-in released [:playback :active-cues [5 3]])))))
  (is (= [5 3] (vibe (:state (grid-handlers/handle {:event/type :grid/move-cell
                                                    :from-col 6 :from-row 0 :to-col 5 :to-row 3
                                                    :state (starter-vibes)}))
                     :vibe-1 :flash :cue))))

(deftest grid-cell-clear-leaves-vibe-pools-test
  (let [st (:state (grid-handlers/handle {:event/type :grid/clear-cell :col 6 :row 0 :state (starter-vibes)}))]
    (is (nil? (vibe st :vibe-1 :flash :cue)))
    (is (= [] (vibe st :vibe-2 :accent :cells)))
    (is (= [[5 0] [7 0]] (vibe st :vibe-2 :hit :cells)) "other cells stay")))

(deftest effects-cell-move-and-clear-follow-fx-sets-test
  (let [s0 (-> (starter-vibes)
               (assoc-in [:chains :effect-chains [0 0]] {:items [:effect] :active? false})
               (assoc-in [:config :vibes :library :vibe-1 :fx] [[0 0]])
               (assoc-in [:config :vibes :library :vibe-2 :flash :fx] [[0 0]]))
        moved (:state (effects-handlers/handle {:event/type :effects/move-cell
                                                :from-col 0 :from-row 0 :to-col 1 :to-row 1 :state s0}))
        cleared (:state (effects-handlers/handle {:event/type :effects/clear-cell :col 1 :row 1 :state moved}))]
    (is (= [[1 1]] (vibe moved :vibe-1 :fx)) "the vibe's FX set")
    (is (= [[1 1]] (vibe moved :vibe-2 :flash :fx)) "a flash's effect cells")
    (is (= [[0 0] [1 0] [2 0] [3 0]] (vibe moved :vibe-1 :hit :cells)) "cue cell [0 0] is another cell")
    (is (= [] (vibe cleared :vibe-1 :fx)))
    (is (= [] (vibe cleared :vibe-2 :flash :fx)))))
