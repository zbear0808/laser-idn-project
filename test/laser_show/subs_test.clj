(ns laser-show.subs-test
  "Subscription tests run against a real cljfx context (no FX toolkit needed)."
  (:require
   [cljfx.api :as fx]
   [clojure.test :refer [deftest is testing]]
   [laser-show.events.handlers.timing :as timing]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains]
   [laser-show.subs :as subs]
   [laser-show.events.handlers :as handlers]
   [laser-show.state.templates :as templates]
   [laser-show.vibes.engine :as engine]
   [laser-show.vibes.library :as library]))

(def cue-chain {:name "Intro"
                :items [{:type :group
                         :items [{:type :preset :preset-id :circle}
                                 {:type :preset :preset-id :star}]}
                        {:type :preset :preset-id :wave}]})

(def base-state
  (-> (domains/build-initial-state)
      (assoc-in [:chains :cue-chains] {[0 0] cue-chain
                                       [1 0] {:items [{:type :preset :preset-id :line}]}})
      (assoc-in [:chains :effect-chains] {[0 0] {:name "FX"
                                                 :active? true
                                                 :items [{:type :effect :effect-id :scale}
                                                         {:type :effect :effect-id :rotate}]}})
      (assoc-in [:playback :active-cues] {[0 0] {:run #uuid "00000000-0000-0000-0000-000000000001"}})
      (assoc-in [:grid :selected-cell] [0 0])))

(defn- context [s]
  (fx/create-context s state/cache-factory))

(defn- status-write
  "What a playing show changes in app state: the show clock's status (the beat
   square, the stats)."
  [ctx]
  (fx/swap-context ctx #(-> %
                            (update-in [:timing :beat-index] (fn [b] (mod (inc (or b 0)) 4)))
                            (assoc-in [:backend :streaming :frame-stats] {:avg-latency-us 123}))))

(deftest cell-display-data-output-test
  (let [ctx (context base-state)]
    (is (= {:col 0 :row 0
            :name "Intro"
            :cue-chain cue-chain
            :preset-count 3
            :first-preset-id :circle
            :active? true
            :selected? true
            :timeline? false
            :has-content? true}
           (fx/sub-ctx ctx subs/cell-display-data 0 0)))
    (testing "empty cell"
      (is (= {:col 3 :row 2
              :name nil
              :cue-chain {:items []}
              :preset-count 0
              :first-preset-id nil
              :active? false
              :selected? false
              :timeline? false
              :has-content? false}
             (fx/sub-ctx ctx subs/cell-display-data 3 2))))))

(deftest cell-display-data-ignores-unrelated-changes-test
  (let [ctx (context base-state)
        before (fx/sub-ctx ctx subs/cell-display-data 0 0)]
    (testing "the show clock's status while a show plays"
      (let [ctx' (status-write ctx)]
        (is (identical? before (fx/sub-ctx ctx' subs/cell-display-data 0 0)))))
    (testing "another cell starts playing"
      (let [ctx' (fx/swap-context ctx assoc-in [:playback :active-cues [1 0]] {:run (random-uuid)})]
        (is (identical? before (fx/sub-ctx ctx' subs/cell-display-data 0 0)))))
    (testing "edits to another cell's chain"
      (let [ctx' (fx/swap-context ctx assoc-in [:chains :cue-chains [1 0] :name] "Other")]
        (is (identical? before (fx/sub-ctx ctx' subs/cell-display-data 0 0)))))
    (testing "this cell stops playing"
      (let [ctx' (fx/swap-context ctx update-in [:playback :active-cues] dissoc [0 0])]
        (is (false? (:active? (fx/sub-ctx ctx' subs/cell-display-data 0 0))))))
    (testing "this cell's chain changes"
      (let [ctx' (fx/swap-context ctx assoc-in [:chains :cue-chains [0 0] :name] "Renamed")]
        (is (= "Renamed" (:name (fx/sub-ctx ctx' subs/cell-display-data 0 0))))))))

(deftest effect-cell-display-data-test
  (let [ctx (context base-state)
        before (fx/sub-ctx ctx subs/effect-cell-display-data 0 0)]
    (is (= {:col 0 :row 0
            :name "FX"
            :effect-count 2
            :first-effect-id :scale
            :effect-ids [:scale :rotate]
            :active? true
            :selected? false
            :has-effects? true
            :display-text "scale +1"}
           before))
    (testing "unaffected by cue-chain edits and the show clock's status"
      (let [ctx' (-> ctx
                     (fx/swap-context assoc-in [:chains :cue-chains [0 0] :name] "Renamed")
                     (status-write))]
        (is (identical? before (fx/sub-ctx ctx' subs/effect-cell-display-data 0 0)))))))

(deftest beat-index-sub-test
  (let [ctx (context base-state)]
    (is (= 0 (fx/sub-ctx ctx subs/beat-index)) "the downbeat before any status")
    (testing "the status moves it"
      (is (= 3 (fx/sub-ctx (fx/swap-context ctx assoc-in [:timing :beat-index] 3) subs/beat-index))))
    (testing "the stats alone don't"
      (let [ctx' (fx/swap-context ctx assoc-in [:backend :streaming :frame-stats] {:avg-latency-us 10})]
        (is (= 0 (fx/sub-ctx ctx' subs/beat-index)))))))


;; Vibe mode: the Perform tab's subs

(def ^:private roles [:hit :accent :flash :vibe :blackout])

(def ^:private hit-key {:source :midi :channel 0 :note 36})

(defn- vibe-state
  "The starter cues and vibes, with a MIDI note learned for HIT."
  []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:config :input :trigger-map] {hit-key {:type :vibe :role :hit}})))

(defn- press [ctx role t] (fx/swap-context ctx #(:state (engine/press % {:role role :time t}))))
(defn- release [ctx role t] (fx/swap-context ctx #(:state (engine/release % {:role role :time t}))))
(defn- in-state [ctx & ks] (get-in (fx/sub-val ctx identity) ks))

(defn- handle
  "ctx after event through the handlers, then each event it dispatches."
  [ctx event]
  (fx/swap-context ctx (fn [s]
                         (loop [s s event event]
                           (let [{s' :state next-event :dispatch} (handlers/handle-event (merge {:time 0} event {:state s}))]
                             (if next-event
                               (recur (or s' s) next-event)
                               (or s' s)))))))

(defn- pads [ctx] (into {} (for [r roles] [r (fx/sub-ctx ctx subs/vibe-pad r)])))

(deftest vibe-pad-sub-test
  (let [ctx (context (vibe-state))]
    (is (= {:role :hit :label "HIT" :bindings [hit-key] :held? false :playing nil
            :learning? false :problem nil :dim? false}
           (fx/sub-ctx ctx subs/vibe-pad :hit)))
    (testing "a press holds the pad and names the cell it plays"
      (let [ctx' (press ctx :hit 1000)
            cell (in-state ctx' :vibe-runtime :voices :hit :cell)]
        (is (some? cell))
        (is (= {:held? true :playing cell} (select-keys (fx/sub-ctx ctx' subs/vibe-pad :hit) [:held? :playing])))
        (testing "and a quick release lets it decay: still playing, no longer held"
          (is (= {:held? false :playing cell}
                 (select-keys (fx/sub-ctx (release ctx' :hit 1050) subs/vibe-pad :hit) [:held? :playing]))))))
    (testing "VIBE says what comes next, and whether the rotation is locked; BLACKOUT whether it is on"
      (is (= {:next "Lines" :locked? false} (select-keys (fx/sub-ctx ctx subs/vibe-pad :vibe) [:next :locked?])))
      (is (= {:next "Shapes" :locked? true}
             (select-keys (fx/sub-ctx (-> ctx (press :vibe 1000) (release :vibe 1700) (handle {:event/type :vibe/select :vibe :vibe-2 :time 1800}))
                                      subs/vibe-pad :vibe)
                          [:next :locked?])))
      (is (false? (:on? (fx/sub-ctx ctx subs/vibe-pad :blackout)))))
    (testing "the pad being learned, and only that one"
      (let [ctx' (fx/swap-context ctx assoc-in [:backend :input :learning-target] {:type :vibe :role :accent})]
        (is (= {:hit false :accent true :flash false :vibe false :blackout false}
               (update-vals (pads ctx') :learning?)))))
    (testing "a press with nothing to play says so on its own pad"
      (let [ctx' (-> ctx
                     (fx/swap-context assoc-in [:config :vibes :library :vibe-1 :accent :cells] [])
                     (press :accent 1000))]
        (is (= {:hit nil :accent :empty-pool :flash nil :vibe nil :blackout nil} (update-vals (pads ctx') :problem)))))
    (testing "BLACKOUT dims the other four and shows it is on"
      (let [ctx' (press ctx :blackout 1000)]
        (is (= {:hit true :accent true :flash true :vibe true :blackout false} (update-vals (pads ctx') :dim?)))
        (is (true? (:on? (fx/sub-ctx ctx' subs/vibe-pad :blackout))))))
    (testing "invariant 8: with no vibes every pad is dim"
      (let [ctx' (fx/swap-context ctx assoc-in [:config :vibes] library/empty-library)]
        (is (every? true? (vals (update-vals (pads ctx') :dim?))))
        (is (nil? (:next (fx/sub-ctx ctx' subs/vibe-pad :vibe))))))
    (testing "the controls bound to a role, and a cell's left out"
      (let [ctx' (fx/swap-context ctx update-in [:config :input :trigger-map] assoc
                                  {:source :keyboard :id :h} {:type :vibe :role :hit}
                                  {:source :midi :channel 0 :note 40} {:type :trigger-cue :target [0 0]})]
        (is (= #{hit-key {:source :keyboard :id :h}} (set (:bindings (fx/sub-ctx ctx' subs/vibe-pad :hit)))))
        (is (= [] (:bindings (fx/sub-ctx ctx' subs/vibe-pad :accent))))))))

(deftest vibe-strip-sub-test
  (let [ctx (context (vibe-state))]
    (is (= [{:id :vibe-1 :name "Shapes" :color "#4A90D9" :energy 2 :current? true :next? false}
            {:id :vibe-2 :name "Lines" :color "#9B59B6" :energy 3 :current? false :next? true}]
           (fx/sub-ctx ctx subs/vibe-strip))
        "nothing pressed yet: the first vibe is current")
    (testing "a select moves both marks (next wraps)"
      (is (= [[:vibe-1 false true] [:vibe-2 true false]]
             (mapv (juxt :id :current? :next?)
                   (fx/sub-ctx (handle ctx {:event/type :vibe/select :vibe :vibe-2 :time 1000}) subs/vibe-strip)))))
    (testing "one vibe: current, and nothing next"
      (is (= [[:vibe-1 true false]]
             (mapv (juxt :id :current? :next?)
                   (fx/sub-ctx (fx/swap-context ctx update-in [:config :vibes :order] subvec 0 1) subs/vibe-strip)))))
    (testing "the header"
      (is (= {:name "Shapes" :color "#4A90D9" :energy 2 :rotation :active :blackout? false :banner nil :learning nil}
             (fx/sub-ctx ctx subs/vibe-header)))
      (is (= :hit (:learning (fx/sub-ctx (fx/swap-context ctx assoc-in [:backend :input :learning-target] {:type :vibe :role :hit})
                                         subs/vibe-header))))
      (is (nil? (:learning (fx/sub-ctx (fx/swap-context ctx assoc-in [:backend :input :learning-target] {:type :trigger-cue :target [0 0]})
                                       subs/vibe-header)))
          "a cell's learn isn't a role's"))))

(deftest hit-press-leaves-the-strip-and-other-pads-alone-test
  (testing "invariant 4: a HIT press re-renders the HIT pad; the strip, the header and the other pads keep their values"
    (let [ctx (context (vibe-state))
          before (pads ctx)
          strip (fx/sub-ctx ctx subs/vibe-strip)
          header (fx/sub-ctx ctx subs/vibe-header)]
      (doseq [ctx' [(press ctx :hit 1000)
                    (-> ctx (press :hit 1000) (release :hit 1050) (press :hit 1500))
                    (-> ctx (press :hit 1000) (status-write))]]
        (is (identical? strip (fx/sub-ctx ctx' subs/vibe-strip)))
        (is (identical? header (fx/sub-ctx ctx' subs/vibe-header)))
        (doseq [r [:accent :flash :vibe :blackout]]
          (is (identical? (before r) (fx/sub-ctx ctx' subs/vibe-pad r)) (str r)))
        (is (not= (before :hit) (fx/sub-ctx ctx' subs/vibe-pad :hit))))))
  (testing "the ACCENT pad changes only when its text does: a HIT that restarts the cell ACCENT plays"
    (let [ctx (-> (vibe-state)
                  (assoc-in [:config :vibes :library :vibe-1 :hit :cells] [[4 0]])
                  (context)
                  (press :accent 1000))
          accent (fx/sub-ctx ctx subs/vibe-pad :accent)]
      (is (= [4 0] (:playing accent)))
      (is (nil? (:playing (fx/sub-ctx (press ctx :hit 1100) subs/vibe-pad :accent)))))))

(deftest vibe-change-leaves-the-pads-but-vibe-alone-test
  (testing "invariant 5: a vibe change re-renders the strip, the header and VIBE's next; nothing else"
    (let [ctx (-> (context (vibe-state)) (press :hit 1000) (release :hit 1050) (press :accent 1100) (release :accent 1150))
          before (pads ctx)
          strip (fx/sub-ctx ctx subs/vibe-strip)
          header (fx/sub-ctx ctx subs/vibe-header)]
      (doseq [ctx' [(handle ctx {:event/type :vibe/select :vibe :vibe-2 :time 2000})
                    (-> ctx (press :vibe 2000) (release :vibe 2100))]]
        (is (not= strip (fx/sub-ctx ctx' subs/vibe-strip)))
        (is (= "Lines" (:name (fx/sub-ctx ctx' subs/vibe-header))))
        (is (not= header (fx/sub-ctx ctx' subs/vibe-header)))
        (is (= "Shapes" (:next (fx/sub-ctx ctx' subs/vibe-pad :vibe))))
        (doseq [r [:hit :accent :flash :blackout]]
          (is (identical? (before r) (fx/sub-ctx ctx' subs/vibe-pad r)) (str r)))))))

(deftest playing-name-clears-when-the-run-ends-test
  (let [ctx (-> (context (vibe-state)) (press :hit 1000) (release :hit 1050))
        {:keys [cell run]} (in-state ctx :vibe-runtime :voices :hit)]
    (is (= cell (:playing (fx/sub-ctx ctx subs/vibe-pad :hit))) "a tap: its cue decays")
    (testing "the show clock ends the decayed run: the pad shows nothing, though the engine still remembers the voice"
      (let [ctx' (handle ctx {:event/type :show-clock/cue-ended :cell cell :run run})]
        (is (some? (in-state ctx' :vibe-runtime :voices :hit)))
        (is (nil? (:playing (fx/sub-ctx ctx' subs/vibe-pad :hit))))))
    (testing "the grid starts the cell again: a run that isn't HIT's"
      (let [ctx' (handle ctx {:event/type :grid/activate-cell :col (first cell) :row (second cell)})]
        (is (some? (in-state ctx' :playback :active-cues cell)))
        (is (nil? (:playing (fx/sub-ctx ctx' subs/vibe-pad :hit))))))))

(deftest blackout-banner-is-honest-test
  (testing "the banner says dark only while nothing plays: a grid cue started during blackout (V2 A) is named"
    (let [ctx (-> (context (vibe-state))
                  (handle {:event/type :vibe/press :role :hit :time 1000})
                  (handle {:event/type :vibe/press :role :blackout :time 1100}))
          banner #(:banner (fx/sub-ctx % subs/vibe-header))
          lit (handle ctx {:event/type :grid/activate-cell :col 7 :row 0})
          lit-twice (handle lit {:event/type :grid/activate-cell :col 5 :row 0})]
      (is (= "BLACKOUT" (banner ctx)))
      (is (true? (in-state lit :vibe-runtime :blackout?)) "the vibe keys stay off")
      (is (true? (in-state lit :playback :playing?)) "and the room is lit")
      (is (= "BLACKOUT · vibe keys off · a grid cue is playing" (banner lit)))
      (is (= "BLACKOUT · vibe keys off · 2 grid cues are playing" (banner lit-twice)))
      (is (= "BLACKOUT" (banner (handle lit-twice {:event/type :transport/stop}))) "Stop: dark again")
      (is (nil? (banner (handle lit {:event/type :vibe/press :role :blackout :time 2000}))) "BLACKOUT off"))))


;; Vibe mode: the Vibes tab's subs (Phase 3)

(defn- learned-all [state]
  (assoc-in state [:config :input :trigger-map]
            (into {} (map-indexed (fn [i role] [{:source :midi :channel 0 :note (+ 36 i)} {:type :vibe :role role}])
                                  library/roles))))

(deftest cell-vibes-ignores-other-cells-test
  (let [ctx (context (vibe-state))
        here (fx/sub-ctx ctx subs/cell-vibes :cue 0 0)
        pick (fx/sub-ctx ctx subs/vibe-pick-cell 0 0)
        label (fx/sub-ctx ctx subs/vibe-pick-label 0 0)]
    (is (= [{:vibe :vibe-1 :color "#4A90D9" :role :hit :pos 1}] here))
    (testing "invariant 6: another cell joins a pool; this cell's values stay identical"
      (let [ctx' (handle ctx {:event/type :vibe/toggle-cell :vibe :vibe-1 :slot :hit :col 5 :row 0})]
        (is (= [{:vibe :vibe-1 :color "#4A90D9" :role :hit :pos 5} {:vibe :vibe-2 :color "#9B59B6" :role :hit :pos 1}]
               (fx/sub-ctx ctx' subs/cell-vibes :cue 5 0)))
        (is (identical? here (fx/sub-ctx ctx' subs/cell-vibes :cue 0 0)))
        (is (identical? pick (fx/sub-ctx ctx' subs/vibe-pick-cell 0 0)))))
    (testing "nor do a press, the cue playing, the show clock's status or the grid's selection"
      (let [ctx' (-> ctx (press :hit 1000) (status-write) (fx/swap-context assoc-in [:grid :selected-cell] [0 0]))]
        (is (identical? here (fx/sub-ctx ctx' subs/cell-vibes :cue 0 0)))
        (is (identical? pick (fx/sub-ctx ctx' subs/vibe-pick-cell 0 0)))
        (is (identical? label (fx/sub-ctx ctx' subs/vibe-pick-label 0 0)))))
    (testing "its own membership does"
      (let [ctx' (handle ctx {:event/type :vibe/toggle-cell :vibe :vibe-1 :slot :hit :col 0 :row 0})]
        (is (= [] (fx/sub-ctx ctx' subs/cell-vibes :cue 0 0)))
        (is (false? (:in? (fx/sub-ctx ctx' subs/vibe-pick-cell 0 0))))))))

(deftest vibe-cell-index-follows-a-move-test
  (let [ctx (context (-> (vibe-state)
                         (assoc-in [:chains :effect-chains [0 3]] {:items [{:effect-id :scale}] :active? false})
                         (assoc-in [:config :vibes :library :vibe-2 :fx] [[0 3]])))
        moved (handle ctx {:event/type :grid/move-cell :from-col 0 :from-row 0 :to-col 2 :to-row 3})]
    (is (= [{:vibe :vibe-1 :color "#4A90D9" :role :hit :pos 1}] (fx/sub-ctx moved subs/cell-vibes :cue 2 3))
        "invariant 5: the cell's place in the pool, at its new place on the grid")
    (is (= [] (fx/sub-ctx moved subs/cell-vibes :cue 0 0)))
    (is (= #{[2 3] [1 0] [2 0] [3 0] [4 0] [5 0] [6 0] [7 0]} (set (keys (fx/sub-ctx moved subs/vibe-cell-index :cue)))))
    (testing "the effects grid has its own index"
      (let [moved (handle ctx {:event/type :effects/move-cell :from-col 0 :from-row 3 :to-col 1 :to-row 2})]
        (is (= [{:vibe :vibe-2 :color "#9B59B6" :role :fx}] (fx/sub-ctx moved subs/cell-vibes :fx 1 2)))
        (is (= [] (fx/sub-ctx moved subs/cell-vibes :fx 0 3)))
        (is (= [] (fx/sub-ctx moved subs/cell-vibes :cue 1 2)) "a cue cell [1 2] is another cell")))))

(deftest vibe-pick-cell-sub-test
  (let [ctx (context (-> (vibe-state)
                         (assoc-in [:config :vibes :library :vibe-1 :bed] [5 0])
                         (assoc-in [:chains :cue-chains [3 0] :destination-zone :zone-group-id] :crowd)
                         (assoc-in [:chains :effect-chains [0 3]] {:items [{:effect-id :scale}] :active? false})
                         (assoc-in [:config :vibes :library :vibe-2 :fx] [[0 3]])))
        pick (fn [ctx col row] (fx/sub-ctx ctx subs/vibe-pick-cell col row))]
    (is (= {:in? true :pos 1 :roles [] :others [] :blocked nil} (pick ctx 0 0)) "Shapes is edited, its HIT: the first of the pool")
    (is (= {:in? false :pos nil :roles [:bed] :others ["#9B59B6"] :blocked nil} (pick ctx 5 0))
        "Shapes' bed, and in Lines")
    (is (= {:in? false :pos nil :roles [] :others [] :blocked nil} (pick ctx 0 2)) "an empty cell")
    (is (= :crowd (:blocked (pick ctx 3 0))) "Shapes never plays a cue that reaches the audience")
    (is (nil? (:blocked (pick (handle ctx {:event/type :vibe/set-option :vibe :vibe-1 :key :allow-audience? :value true}) 3 0)))
        "unless it allows them")
    (testing "another slot, another vibe"
      (let [bed (handle ctx {:event/type :vibe/set-slot :slot :bed})
            lines (handle ctx {:event/type :vibe/select-for-edit :vibe :vibe-2})]
        (is (= {:in? true :pos nil :roles [] :others ["#9B59B6"] :blocked nil} (pick bed 5 0)))
        (is (= {:in? true :pos 1 :roles [] :others ["#4A90D9"] :blocked nil} (pick lines 5 0)))
        (is (= {:vibe :vibe-2 :slot :hit} (fx/sub-ctx lines subs/vibe-edit-target)))))
    (testing "the effects grid"
      (is (= {:in? false :pos nil :roles [] :others ["#9B59B6"]} (fx/sub-ctx ctx subs/vibe-pick-fx 0 3)))
      (is (= {:in? true :pos nil :roles [] :others []}
             (fx/sub-ctx (-> ctx
                             (handle {:event/type :vibe/select-for-edit :vibe :vibe-2})
                             (handle {:event/type :vibe/set-slot :slot :fx}))
                         subs/vibe-pick-fx 0 3))))))

(deftest vibe-editor-sub-test
  (let [ctx (context (vibe-state))]
    (is (= {:vibe :vibe-1 :slot :hit} (fx/sub-ctx ctx subs/vibe-edit-target)) "nothing selected: the first vibe, HIT")
    (is (= (assoc (get-in (vibe-state) [:config :vibes :library :vibe-1]) :slot :hit :current? true)
           (fx/sub-ctx ctx subs/vibe-editor)))
    (is (= [[:vibe-1 true] [:vibe-2 false]] (mapv (juxt :id :selected?) (fx/sub-ctx ctx subs/vibe-list))))
    (let [ctx' (handle ctx {:event/type :vibe/select-for-edit :vibe :vibe-2})]
      (is (= [false "Lines"] ((juxt :current? :name) (fx/sub-ctx ctx' subs/vibe-editor))))
      (is (= [[:vibe-1 false] [:vibe-2 true]] (mapv (juxt :id :selected?) (fx/sub-ctx ctx' subs/vibe-list)))))
    (is (= :vibe-1 (:vibe (fx/sub-ctx (fx/swap-context ctx assoc-in [:ui :vibes :selected] :gone) subs/vibe-edit-target)))
        "a vibe that went: the first")
    (is (nil? (fx/sub-ctx (fx/swap-context ctx assoc-in [:config :vibes] library/empty-library) subs/vibe-editor)))))

(deftest vibe-checklist-test
  (let [done (fn [state] (mapv (juxt :step :done?) (fx/sub-ctx (context state) subs/vibe-checklist)))]
    (is (= [[:cues false] [:vibes false] [:hit false] [:controls false]] (done (domains/build-initial-state))))
    (is (= [[:cues true] [:vibes true] [:hit true] [:controls false]] (done (vibe-state)))
        "the starter show, with HIT's key only")
    (is (= "Learn the five controls (1 of 5)" (:text (peek (fx/sub-ctx (context (vibe-state)) subs/vibe-checklist)))))
    (is (= [[:cues true] [:vibes true] [:hit true] [:controls true]] (done (learned-all (vibe-state)))))
    (is (= [:hit false] (nth (done (assoc-in (vibe-state) [:config :vibes :library :vibe-2 :hit :cells] [])) 2))
        "a vibe with no HIT cue to play")
    (is (= [:hit false] (nth (done (assoc-in (vibe-state) [:config :vibes :library :vibe-2 :hit :cells] [[0 3]])) 2))
        "nor with an empty cell")))

(deftest vibe-lint-sub-test
  (let [ctx (context (vibe-state))
        lint (fx/sub-ctx ctx subs/vibe-lint)]
    (is (= [{:level :info :text "1 of 5 controls learned"}] lint))
    (is (identical? lint (fx/sub-ctx (-> ctx (press :hit 1000) (status-write)) subs/vibe-lint))
        "a press and the show clock's status leave it alone")
    (is (= [{:level :error :vibe :vibe-2 :text "Lines: HIT has no cues to play"}]
           (filterv #(= :error (:level %))
                    (fx/sub-ctx (-> ctx
                                    (handle {:event/type :grid/clear-cell :col 5 :row 0})
                                    (handle {:event/type :grid/clear-cell :col 7 :row 0}))
                                subs/vibe-lint)))
        "a cleared cell leaves its pools (vibes.refs): both of Lines' HIT cues cleared, it has nothing to play")))

(deftest vibe-controls-sub-test
  (let [ctx (context (vibe-state))
        wizard {:queue [:accent :flash :vibe :blackout] :done [:hit] :refused :hit}]
    (is (= {:role :hit :label "HIT" :bindings [hit-key] :learning? false} (fx/sub-ctx ctx subs/vibe-control :hit)))
    (is (= {:role :blackout :label "BLACKOUT" :bindings [] :learning? false} (fx/sub-ctx ctx subs/vibe-control :blackout)))
    (is (nil? (fx/sub-ctx ctx subs/vibe-wizard)))
    (let [ctx' (fx/swap-context ctx #(-> %
                                         (assoc-in [:ui :vibes :wizard] wizard)
                                         (assoc-in [:backend :input :learning-target] {:type :vibe :role :accent})))]
      (is (= {:role :accent :step 2 :of 5 :refused :hit} (fx/sub-ctx ctx' subs/vibe-wizard)))
      (is (true? (:learning? (fx/sub-ctx ctx' subs/vibe-control :accent))))
      (is (nil? (fx/sub-ctx (fx/swap-context ctx' update-in [:backend :input] dissoc :learning-target) subs/vibe-wizard))
          "nothing learning: no wizard shows, whatever is left in the UI state"))))

(deftest timeline-dialog-subs-ignore-playback-and-other-cells-test
  (let [ctx (context base-state)
        chain (fx/sub-ctx ctx subs/cue-cell-chain 0 0)
        ui (fx/sub-ctx ctx subs/timeline-ui 0 0)]
    (testing "the show clock's status doesn't invalidate the dialog's subs"
      (let [ctx' (status-write ctx)]
        (is (identical? chain (fx/sub-ctx ctx' subs/cue-cell-chain 0 0)))
        (is (identical? ui (fx/sub-ctx ctx' subs/timeline-ui 0 0)))))
    (testing "another cell's edits and view don't either"
      (let [ctx' (-> ctx
                     (fx/swap-context assoc-in [:chains :cue-chains [1 0] :name] "Other")
                     (fx/swap-context assoc-in [:ui :timeline-editor [1 0] :selection] #{:x}))]
        (is (identical? chain (fx/sub-ctx ctx' subs/cue-cell-chain 0 0)))
        (is (identical? ui (fx/sub-ctx ctx' subs/timeline-ui 0 0)))))))

(def ^:private playing-state
  "Three cues playing, started in no particular order: [0 1] (B1), [1 0] (A2)
   and [0 0] (A1)."
  (-> base-state
      (assoc-in [:chains :cue-chains [0 1]] {:items [{:type :preset :preset-id :star}]})
      (assoc-in [:playback :active-cues] {[0 1] {:run #uuid "00000000-0000-0000-0000-000000000003"}
                                          [1 0] {:run #uuid "00000000-0000-0000-0000-000000000002"}
                                          [0 0] {:run #uuid "00000000-0000-0000-0000-000000000001"}})
      (assoc-in [:playback :playing?] true)))

(defn- after [state & event-types]
  (reduce (fn [s t] (:state (timing/handle {:event/type t :state s}))) state event-types))

(deftest output-held-sub-test
  (let [held? #(fx/sub-ctx (context %) subs/output-held?)
        held #(fx/sub-ctx (context %) subs/held-cue-count)]
    (testing "while cues play, nothing is held"
      (is (false? (held? playing-state)))
      (is (zero? (held playing-state))))
    (testing "Stop is the blackout: it leaves the cues in play, and holds them"
      (is (true? (held? (after playing-state :transport/stop))))
      (is (= 3 (held (after playing-state :transport/stop)))))
    (testing "Play resumes them"
      (is (false? (held? (after playing-state :transport/stop :transport/play))))
      (is (zero? (held (after playing-state :transport/stop :transport/play)))))
    (testing "with no cue left there is nothing to hold, playing or not"
      (let [none (assoc-in playing-state [:playback :active-cues] {})]
        (is (false? (held? none)))
        (is (false? (held? (after none :transport/stop))))
        (is (zero? (held (after none :transport/stop))))))))

(deftest playing-cues-sub-test
  (let [ctx (context playing-state)
        cues (fx/sub-ctx ctx subs/playing-cues)]
    (testing "row by row, as the grid reads (A1, A2, then B1), with what each cell is named from"
      (is (= [{:col 0 :row 0 :name "Intro" :first-preset-id :circle :preset-count 3 :timeline? false}
              {:col 1 :row 0 :name nil :first-preset-id :line :preset-count 1 :timeline? false}
              {:col 0 :row 1 :name nil :first-preset-id :star :preset-count 1 :timeline? false}]
             cues)))
    (testing "unchanged by what changes inside the cues' intents (a pause, a seek) and the show clock's status"
      (let [ctx' (-> ctx
                     (fx/swap-context assoc-in [:playback :active-cues [0 0] :paused?] true)
                     (status-write))]
        (is (identical? cues (fx/sub-ctx ctx' subs/playing-cues)))))
    (testing "a cue that stops leaves the list"
      (is (= [[0 0] [0 1]]
             (map (juxt :col :row)
                  (fx/sub-ctx (fx/swap-context ctx update-in [:playback :active-cues] dissoc [1 0])
                              subs/playing-cues)))))
    (testing "nothing plays while Stop holds them"
      (is (= [] (fx/sub-ctx (context (after playing-state :transport/stop)) subs/playing-cues))))))

(deftest connection-status-says-why-nothing-streams-test
  (let [status-at (fn [running? status]
                    (select-keys (fx/sub-ctx (context (-> base-state
                                                          (assoc-in [:backend :streaming :running?] running?)
                                                          (assoc-in [:backend :streaming :status] status)))
                                             subs/connection-status)
                                 [:state :status-text]))]
    (is (= {:state :idle :status-text "No projectors enabled"}
           (status-at true {:engines 0 :wanted 0 :pending? false :projectors {}}))
        "streaming is on, with nothing to stream")
    (is (= {:state :streaming :status-text "Streaming to 1 of 1"}
           (status-at true {:engines 1 :wanted 1 :pending? false
                            :projectors {:a {:state :streaming :fps 59.8}}})))
    (is (= {:state :stopped :status-text "Not streaming"} (status-at false nil)))))
