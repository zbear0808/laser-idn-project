(ns laser-show.events.handlers.grid-trigger-test
  "Unit tests for grid and effects cell triggers, movement, and cleanup."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.events.handlers.grid :as grid-handlers]
            [laser-show.events.handlers.effects :as effects-handlers]
            [laser-show.events.handlers.triggers :as triggers]
            [laser-show.input.events :as input-events]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.engine :as engine]))

(def ^:private P {:type :preset :preset-id :circle})

(def ^:private A [2 1])

(def ^:private r0 #uuid "00000000-0000-0000-0000-0000000000a0")

(def ^:private r1 #uuid "00000000-0000-0000-0000-0000000000a1")

(defn- presses
  "An ordinary press of [col row] as each source sends it: a click on the
   cell, the old name of a click with either flag for what the cell showed
   (ignored now), and a bound key's press as the trigger router sends it."
  [[col row]]
  {:click {:event/type :grid/trigger-cell :col col :row row}
   :old-click-shown-empty {:event/type :grid/cell-clicked :col col :row row :has-content? false}
   :old-click-shown-full {:event/type :grid/cell-clicked :col col :row row :has-content? true}
   :key (:dispatch (triggers/handle-input
                    {:state {:config {:input {:trigger-map {{:source :keyboard :id :a}
                                                            {:type :trigger-cue :target [col row]}}}}}
                     :input-event (input-events/trigger :keyboard :a :pressed)
                     :time 1}))})

(defn- pressed [state press] (:state (grid-handlers/handle (assoc press :state state))))

(defn- show
  "Cue cells cues, [0 0] selected, nothing in play, and the show's trigger
   mode :default (each cue's own, :toggle when it sets none)."
  [cues]
  {:chains {:cue-chains cues}
   :config {:cue {:trigger-mode :default}}
   :grid {:selected-cell [0 0]}
   :playback {:active-cues {} :active-cell nil :playing? false}})

(defn- grid-outcome [state]
  {:selected (get-in state [:grid :selected-cell])
   :intents (get-in state [:playback :active-cues])
   :active-cell (get-in state [:playback :active-cell])
   :playing? (get-in state [:playback :playing?])
   :pulse (get-in state [:playback :pulses :cue])})

(deftest grid-trigger-cell-test
  (testing "Triggering cell with content in toggle mode activates playback"
    (let [cue-data {:items [P] :trigger-mode :toggle}
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
      (is (empty? (get-in st [:playback :active-cues])))))

  (testing "a cell filled after it was shown: its preset, stored in a disabled group, plays and stops (plan 10 §7.2)"
    (let [s0 (show {A {:items [{:type :group :enabled? false :items [P]}]}})]
      (doseq [[source press] (presses A)]
        (testing (name source)
          (let [s1 (pressed s0 press)
                run (get-in s1 [:playback :active-cues A :run])]
            (is (uuid? run))
            (is (= {:selected A :intents {A {:run run}} :active-cell A :playing? true :pulse {:id 1}}
                   (grid-outcome s1)))
            (is (= {:selected A :intents {} :active-cell nil :playing? false :pulse {:id 1}}
                   (grid-outcome (pressed s1 press)))
                "the second press stops it, and doesn't pulse"))))))

  (testing "a name and an empty group aren't a cue: a press only selects the cell (plan 10 §7.3)"
    (let [s0 (show {A {:name "Draft" :items [{:type :group :items []}]}})]
      (doseq [[source press] (presses A)]
        (testing (name source)
          (is (= (assoc s0 :grid {:selected-cell A}) (pressed s0 press)))))
      (is (= s0 (pressed s0 {:event/type :grid/activate-cell :col 2 :row 1}))
          "a momentary press leaves even the selection as it was")))

  (testing "a timeline without clips is a cue: the show's :retrigger restarts it over its own :toggle (plan 10 §7.4)"
    (doseq [[what cue] [["explicit" {:editor-type :timeline :items [] :trigger-mode :toggle}]
                        ["from its tracks" {:tracks [{:id :t :type :track}] :items [] :trigger-mode :toggle}]]
            [source press] (presses A)]
      (testing (str what ", " (name source))
        (let [s0 (-> (show {A cue [0 0] {:items [P]}})
                     (assoc-in [:config :cue :trigger-mode] :retrigger)
                     (assoc :playback {:active-cues {[0 0] {:run r0} A {:run r1 :timeline? true :start-beat 0.0}}
                                       :active-cell A :playing? true :pulses {:cue {:id 4}}}))
              s1 (pressed s0 press)
              run (get-in s1 [:playback :active-cues A :run])]
          (is (and (uuid? run) (not= r1 run)) "a new run")
          (is (= {:selected A :intents {[0 0] {:run r0} A {:run run :timeline? true :start-beat 0.0}}
                  :active-cell A :playing? true :pulse {:id 5}}
                 (grid-outcome s1)))))))

  (testing "a timeline following MIDI timecode: a press selects it, and the timecode starts it (plan 10 §7.4)"
    (let [s0 (show {A {:editor-type :timeline :timeline/chase :mtc :items []}})]
      (doseq [[source press] (presses A)]
        (testing (name source)
          (is (= (assoc s0 :grid {:selected-cell A}) (pressed s0 press)))))))

  (testing "the show's trigger mode overrides the cue's, unless it is :default"
    (doseq [[show-mode cue-mode does] [[:default :toggle :stop]
                                       [:default :retrigger :restart]
                                       [:default nil :stop]
                                       [:toggle :retrigger :stop]
                                       [:retrigger :toggle :restart]]
            [source press] (presses A)]
      (testing (str show-mode " over " (pr-str cue-mode) ", " (name source))
        (let [s0 (-> (show {A (cond-> {:items [P]} cue-mode (assoc :trigger-mode cue-mode)) [0 0] {:items [P]}})
                     (assoc-in [:config :cue :trigger-mode] show-mode)
                     (assoc :playback {:active-cues {[0 0] {:run r0} A {:run r1}}
                                       :active-cell A :playing? true :pulses {:cue {:id 4}}}))
              s1 (pressed s0 press)
              intents (get-in s1 [:playback :active-cues])]
          (is (= {:run r0} (intents [0 0])) "the other cue plays on")
          (case does
            :stop (do (is (not (contains? intents A)))
                      (is (= {:id 4} (get-in s1 [:playback :pulses :cue])) "stopping doesn't pulse"))
            :restart (do (is (and (uuid? (:run (intents A))) (not= r1 (:run (intents A)))) "a new run")
                         (is (= {:id 5} (get-in s1 [:playback :pulses :cue]))))))))))

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

(deftest cue-start-pulses-test
  (testing "every cue start pulses :cue for the tap reactions that follow it (plans/2026-11-18-vibe-mode/04)"
    (let [s0 (-> (domains/build-initial-state) (templates/apply-starter-cue-chains))
          cue-pulse #(get-in % [:playback :pulses :cue])
          run (fn [s event] (:state (grid-handlers/handle (assoc event :state s))))]
      (testing "a click that starts a cue, and one that stops it"
        (let [s1 (run s0 {:event/type :grid/trigger-cell :col 0 :row 0})]
          (is (= {:id 1} (cue-pulse s1)))
          (is (= {:id 1} (cue-pulse (run s1 {:event/type :grid/trigger-cell :col 0 :row 0}))))
          (testing "(by the click's old name, whatever it says the cell showed)"
            (let [s1 (run s0 {:event/type :grid/cell-clicked :col 0 :row 0 :has-content? false})]
              (is (= {:id 1} (cue-pulse s1)))
              (is (= {:id 1} (cue-pulse (run s1 {:event/type :grid/cell-clicked :col 0 :row 0 :has-content? true}))))))))
      (testing "a restart pulses again, with a new run"
        (let [s (assoc-in s0 [:config :cue :trigger-mode] :retrigger)
              s1 (run s {:event/type :grid/trigger-cell :col 0 :row 0})
              s2 (run s1 {:event/type :grid/trigger-cell :col 0 :row 0})
              run-of #(get-in % [:playback :active-cues [0 0] :run])]
          (is (= [{:id 1} {:id 2}] [(cue-pulse s1) (cue-pulse s2)]))
          (is (not= (run-of s1) (run-of s2)))))
      (testing "a cell trigger, and momentary presses"
        (is (= {:id 1} (cue-pulse (run s0 {:event/type :grid/trigger-cell :col 1 :row 0}))))
        (is (= {:id 2} (cue-pulse (-> s0
                                      (run {:event/type :grid/activate-cell :col 1 :row 0})
                                      (run {:event/type :grid/activate-cell :col 2 :row 0}))))))
      (testing "a vibe's pick"
        (is (= {:id 1} (cue-pulse (:state (engine/press (templates/apply-starter-vibes s0) {:role :hit :time 1000}))))))
      (testing "a timeline that follows MIDI timecode isn't started, so no pulse"
        (let [s (assoc-in s0 [:chains :cue-chains [0 1]] {:editor-type :timeline :timeline/chase :mtc
                                                          :items [{:type :preset :preset-id :circle}]})]
          (is (nil? (cue-pulse (run s {:event/type :grid/trigger-cell :col 0 :row 1}))))
          (is (nil? (cue-pulse (run s {:event/type :grid/activate-cell :col 0 :row 1}))))))
      (testing "an empty cell starts nothing"
        (is (nil? (cue-pulse (run s0 {:event/type :grid/activate-cell :col 7 :row 3}))))
        (testing ", nor one with only a name and an empty group"
          (let [s (assoc-in s0 [:chains :cue-chains [7 3]] {:name "Draft" :items [{:type :group :items []}]})]
            (is (nil? (cue-pulse (run s {:event/type :grid/activate-cell :col 7 :row 3}))))
            (is (nil? (cue-pulse (run s {:event/type :grid/trigger-cell :col 7 :row 3}))))
            (is (nil? (cue-pulse (run s {:event/type :grid/cell-clicked :col 7 :row 3 :has-content? true}))))))))))
