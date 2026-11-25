(ns laser-show.subs-test
  "Subscription tests run against a real cljfx context (no FX toolkit needed)."
  (:require
   [cljfx.api :as fx]
   [clojure.core.cache :as cache]
   [clojure.test :refer [deftest is testing]]
   [laser-show.animation.chains :as chains]
   [laser-show.animation.effects :as effects]
   [laser-show.animation.effects.calibration]
   [laser-show.animation.effects.color]
   [laser-show.animation.effects.intensity]
   [laser-show.animation.effects.points]
   [laser-show.animation.effects.shape]
   [laser-show.animation.effects.zone]
   [laser-show.animation.modulator-registry :as modulator-registry]
   [laser-show.animation.registries :as registries]
   [laser-show.events.handlers.timing :as timing]
   [laser-show.output.driver]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains]
   [laser-show.subs :as subs]
   [laser-show.events.handlers :as handlers]
   [laser-show.routing.rig :as rig]
   [laser-show.routing.targets :as targets]
   [laser-show.routing.zone-maps :as zone-maps]
   [laser-show.routing.zone-patterns :as zone-patterns]
   [laser-show.state.templates :as templates]
   [laser-show.vibes.engine :as engine]
   [laser-show.vibes.faders :as faders]
   [laser-show.vibes.library :as library]
   [laser-show.vibes.reactions :as reactions]))

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

(defn- waiting-to-learn
  "s with a learn attempt waiting to bind target-action ([:ui :input-learn
   :active], handlers/input.clj), a cell's or a pad's."
  [s target-action]
  (assoc-in s [:ui :input-learn :active] {:learn-id #uuid "00000000-0000-0000-0000-0000000024c1" :owner :binding
                                          :capture (if (= :macro (:type target-action)) :continuous :press)
                                          :document-id (get-in s [:project :document-id])
                                          :target-action target-action}))

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
             (fx/sub-ctx ctx subs/cell-display-data 3 2))))
    (testing "what a cell holds: a timeline, or a preset at the top or in a group, enabled or not"
      (let [P {:type :preset :preset-id :circle}
            holds (fn [cue]
                    (-> (update-in base-state [:chains :cue-chains] #(if cue (assoc % [2 1] cue) (dissoc % [2 1])))
                        context
                        (fx/sub-ctx subs/cell-display-data 2 1)
                        (select-keys [:first-preset-id :preset-count :timeline? :has-content?])))
            nothing {:first-preset-id nil :preset-count 0 :timeline? false :has-content? false}
            a-timeline {:first-preset-id nil :preset-count 0 :timeline? true :has-content? true}]
        (is (= nothing (holds nil)) "no cue")
        (is (= nothing (holds {:items []})) "the same as no cue")
        (is (= nothing (holds {:name "Draft" :trigger-mode :retrigger})) "a name and a mode alone")
        (is (= nothing (holds {:name "Draft" :items [{:type :group :items []}]})) "an empty group")
        (is (= nothing (holds {:items [{:preset-id :circle} {:type :effect :effect-id :scale}]}))
            "an untyped item and an effect")
        (is (= {:first-preset-id :circle :preset-count 1 :timeline? false :has-content? true}
               (holds {:items [{:type :group :enabled? false :items [(assoc P :enabled? false)]}]}))
            "a disabled preset in a disabled group")
        (is (= a-timeline (holds {:editor-type :timeline :items []})) "a timeline, without clips")
        (is (= a-timeline (holds {:tracks [{:id :t :type :track}] :items []})) "tracks make it a timeline")
        (is (= a-timeline (holds {:items [{:track-id :t}]})) "so does a clip's track")
        (is (= nothing (holds {:editor-type :cue-chain :tracks [{:id :t :type :track}]}))
            "a cue chain by choice: tracks alone don't make it a timeline")))))

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
            :has-content? true
            :display-text "scale +1"}
           before))
    (testing "unaffected by cue-chain edits and the show clock's status"
      (let [ctx' (-> ctx
                     (fx/swap-context assoc-in [:chains :cue-chains [0 0] :name] "Renamed")
                     (status-write))]
        (is (identical? before (fx/sub-ctx ctx' subs/effect-cell-display-data 0 0)))))
    (testing "what a cell stores, on or off, and whether it holds anything (plan 11 §7)"
      (let [P (zone-patterns/preset-instance :chase :cell)
            cells {[1 0] {:name "Old" :items []}
                   [2 0] {:items [{:type :group :items []}]}
                   [3 0] {:active? true :items [{:effect-id :zone-map-cycle :enabled? false}]}
                   [4 0] {:active? true :items [{:type :group :items [{:type :group :enabled? false
                                                                         :items [P {:effect-id :scale}]}]}]}
                   [0 3] (faders/master-cell)}
            ctx (context (update-in base-state [:chains :effect-chains] merge cells))
            stored (fn [col row]
                     (select-keys (fx/sub-ctx ctx subs/effect-cell-display-data col row)
                                  [:effect-count :first-effect-id :effect-ids :has-effects? :has-content? :display-text]))
            nothing {:effect-count 0 :first-effect-id nil :effect-ids [] :has-effects? false :has-content? false
                     :display-text nil}]
        (is (= nothing (stored 7 3)) "no cell")
        (is (= nothing (stored 1 0)) "a name, and no items")
        (is (= (assoc nothing :has-content? true) (stored 2 0)) "a group: occupied, no effect")
        (is (= {:effect-count 1 :first-effect-id :zone-map-cycle :effect-ids [:zone-map-cycle] :has-effects? true
                :has-content? true :display-text "zone-map-cycle"}
               (stored 3 0))
            "a disabled Map cycle")
        (is (= {:effect-count 2 :first-effect-id :zone-pattern :effect-ids [:zone-pattern :scale] :has-effects? true
                :has-content? true :display-text "zone-pattern +1"}
               (stored 4 0))
            "a pattern and a scale in a disabled group")
        (is (= {:effect-count 1 :first-effect-id :intensity :effect-ids [:intensity] :has-effects? true
                :has-content? true :display-text "intensity"}
               (stored 0 3))
            "the Master")
        (testing "the picker shows the same"
          (doseq [[col row] (conj (keys cells) [7 3])]
            (is (= (merge (select-keys (stored col row) [:effect-count :first-effect-id :effect-ids :has-content?])
                          {:name (get-in cells [[col row] :name])})
                   (fx/sub-ctx ctx subs/vibe-pick-fx-label col row))
                (str [col row]))))))))

(deftest effects-grid-empty-sub-test
  (testing "no effects cell, or none with an effect in it"
    (is (true? (fx/sub-ctx (context (domains/build-initial-state)) subs/effects-grid-empty?)))
    (is (true? (fx/sub-ctx (context (assoc-in base-state [:chains :effect-chains] {[2 1] {:active? true :items []}}))
                           subs/effects-grid-empty?)))
    (is (true? (fx/sub-ctx (context (assoc-in base-state [:chains :effect-chains]
                                              {[2 1] {:items [{:type :group :items []}]}}))
                           subs/effects-grid-empty?))
        "an empty group is no effect"))
  (testing "one effect, on or off, anywhere"
    (is (false? (fx/sub-ctx (context base-state) subs/effects-grid-empty?)))
    (is (false? (fx/sub-ctx (context (assoc-in base-state [:chains :effect-chains [0 0] :active?] false))
                            subs/effects-grid-empty?))))
  (testing "the Master alone doesn't count: a new project's grid is still a fresh one (05 §11)"
    (let [mastered (templates/apply-starter-effects (domains/build-initial-state))
          empty? #(fx/sub-ctx (context %) subs/effects-grid-empty?)]
      (is (true? (empty? mastered)))
      (is (true? (empty? (assoc-in mastered [:chains :effect-chains [0 3] :active?] false))) "switched off")
      (is (true? (empty? (assoc-in mastered [:chains :effect-chains [5 0]] (get-in mastered [:chains :effect-chains [0 3]]))))
          "moved, or copied: Masters only")
      (is (false? (empty? (assoc-in mastered [:chains :effect-chains [2 1]] {:items [{:effect-id :scale}]})))
          "with another cell that has an effect")
      (is (false? (empty? (update-in mastered [:chains :effect-chains [0 3] :items] conj {:effect-id :scale :enabled? true})))
          "with another effect in its own chain")
      (is (false? (empty? (assoc-in mastered [:chains :effect-chains [0 3] :items 0 :params :amount] 0.5)))
          "its Intensity on a fixed amount: an effect cell like any")
      (testing "a disabled effect is a stored one (plan 11 §7)"
        (is (false? (empty? (update-in mastered [:chains :effect-chains [0 3] :items] conj {:effect-id :scale :enabled? false})))
            "a disabled effect beside the Master")
        (is (false? (empty? (assoc-in mastered [:chains :effect-chains [0 3] :items 0 :enabled?] false)))
            "the Master's Intensity disabled")
        (is (false? (empty? (update-in mastered [:chains :effect-chains [0 3] :items] #(vector {:type :group :enabled? false :items %}))))
            "or in a disabled group")
        (is (false? (empty? (assoc-in mastered [:chains :effect-chains [3 0]]
                                      {:active? true :items [{:effect-id :zone-map-cycle :enabled? false}]})))
            "a disabled Map cycle")
        (is (false? (empty? (assoc-in mastered [:chains :effect-chains [4 0]]
                                      {:active? true :items [{:type :group :items [{:type :group :enabled? false
                                                                                    :items [(zone-patterns/preset-instance :chase :cell)
                                                                                            {:effect-id :scale}]}]}]})))
            "a pattern and a scale in a disabled group")
        (is (true? (empty? (assoc-in mastered [:chains :effect-chains [2 0]] {:items [{:type :group :items []}]})))
            "a group alone: no effect yet")))))

(deftest grid-content-selectors-read-only-the-cells-that-change-test
  ;; Follow-up R3: effects-grid-empty?, zone-patterns-in-use? and the Map
  ;; cycle check read the whole of each grid, so a cell switched on or off,
  ;; an effect's parameter or another cue's name ran them again, and walked
  ;; every effects cell (chains/stored-effect-content) each time
  (let [runs (atom {})
        walks (atom 0)
        counted (fn [k v] (let [f @v] (fn [& args] (swap! runs update k (fnil inc 0)) (apply f args))))
        stored-effect-content chains/stored-effect-content]
    (with-redefs-fn {#'subs/effects-grid-empty? (counted :empty? #'subs/effects-grid-empty?)
                     #'subs/zone-patterns-in-use? (counted :patterns? #'subs/zone-patterns-in-use?)
                     #'subs/map-cycles? (counted :cycles? #'subs/map-cycles?)
                     #'chains/stored-effect-content (fn [items] (swap! walks inc) (stored-effect-content items))}
      #(let [s (-> (templates/apply-starter-effects (domains/build-initial-state))
                   (assoc-in [:chains :effect-chains [1 0]] {:active? true :items [{:id :sc :effect-id :scale :enabled? true
                                                                                    :params {:x-scale 1.0}}]})
                   (assoc-in [:chains :effect-chains [2 0]] {:active? false :items [(assoc (zone-patterns/cycle-instance) :id :cy)]})
                   (assoc-in [:chains :cue-chains [0 0]] {:name "Intro" :items [{:id :c :type :preset :preset-id :circle}]}))
             shown (fn [ctx] [(fx/sub-ctx ctx subs/effects-grid-empty?)
                              (fx/sub-ctx ctx subs/zone-patterns-in-use?)
                              (fx/sub-ctx ctx subs/zone-map-pads-shown?)])
             step (fn [ctx f]
                    (reset! runs {})
                    (reset! walks 0)
                    (let [ctx' (fx/swap-context ctx f)]
                      [ctx' {:shown (shown ctx') :runs @runs :walks @walks}]))
             ctx0 (context s)
             _ (is (= [false false true] (shown ctx0)) "an effect and a Map cycle stored, no pattern")
             [ctx1 switched] (step ctx0 (fn [s] (assoc-in s [:chains :effect-chains [1 0] :active?] false)))
             [ctx2 tuned] (step ctx1 (fn [s] (assoc-in s [:chains :effect-chains [1 0] :items 0 :params :x-scale] 1.5)))
             [ctx3 renamed] (step ctx2 (fn [s] (assoc-in s [:chains :cue-chains [0 0] :name] "Verse")))
             [ctx4 patterned] (step ctx3 (fn [s] (assoc-in s [:chains :cue-chains [0 0] :zone-patterns]
                                                           [(assoc (zone-patterns/preset-instance :alternate :cue) :id :zp)])))
             [_ emptied] (step ctx4 (fn [s] (update-in s [:chains :effect-chains] dissoc [1 0] [2 0])))]
         (testing "a cell switched off: no selector runs, no cell is walked"
           (is (= {:shown [false false true] :runs {} :walks 0} switched)))
         (testing "an effect's parameter: that cell walked again, no selector runs"
           (is (= {:shown [false false true] :runs {} :walks 1} tuned)))
         (testing "a cue renamed: nothing"
           (is (= {:shown [false false true] :runs {} :walks 0} renamed)))
         (testing "what the grids store still counts, on or off (plan 11)"
           (is (= [false true true] (:shown patterned)) "a cue's pattern: the pattern pads show")
           (is (= [true true true] (:shown emptied)) "the Master alone left among the effects; the map pads show for the cue's pattern"))))))


;; Both grids: an edit builds again only the cells it changes
;; (plans/2026-11-23-cljfx-reactivity/35)

(defn- comparison-context
  "A context whose cache keeps every entry these tests make (4,096, nothing
   evicted), so a count says what depends on what."
  [s]
  (fx/create-context s #(cache/lru-cache-factory % :threshold 4096)))

(def ^:private grid-cells (for [row (range 4) col (range 8)] [col row]))

(defn- counting
  "Call (f counts) while each var of vars ({var kind}) counts its body's
   runs in counts, {[kind args] n}. The counting fns are in place before
   anything subscribes to them: they are what the cache keys on."
  [vars f]
  (let [counts (atom {})]
    (with-redefs-fn (into {} (for [[v kind] vars :let [body @v]]
                               [v (fn [ctx & args]
                                    (swap! counts update [kind (vec args)] (fnil inc 0))
                                    (apply body ctx args))]))
      #(f counts))))

(def ^:private grid-state
  "base-state with the effects grid's selection on A1 as well, and an
   effects cell of a group."
  (-> base-state
      (assoc-in [:effects :selected-cell] [0 0])
      (assoc-in [:chains :effect-chains [2 0]] {:items [{:type :group :items [{:type :effect :effect-id :hue-shift}]}]})))

(defn- rebuilt
  "Both grids' display data in ctx, {[:cue [col row]] map, [:fx [col row]]
   map}, and the cells whose body ran for them, each once (counts, of
   counting)."
  [counts ctx]
  (reset! counts {})
  (let [displays (into {} (for [[kind sub] [[:cue subs/cell-display-data] [:fx subs/effect-cell-display-data]]
                                [col row] grid-cells]
                            [[kind [col row]] (fx/sub-ctx ctx sub col row)]))]
    (is (every? #{1} (vals @counts)) "no cell built twice")
    [displays (set (keys @counts))]))

(deftest grid-selection-rebuilds-only-old-and-new-cells-test
  (counting
   {#'subs/cell-display-data :cue #'subs/effect-cell-display-data :fx}
   (fn [counts]
     (let [ctx (comparison-context grid-state)
           [before warm] (rebuilt counts ctx)
           selected (fn [displays & cells] (mapv #(:selected? (displays %)) cells))
           kept? (fn [after changed]
                   (every? #(identical? (before %) (after %)) (remove changed (keys before))))
           A1 [:cue [0 0]] A2 [:cue [1 0]]]
       (is (= 64 (count warm)) "warm: each cell of both grids built once")
       (testing "the cue grid's selection moves from A1 to A2: those two cells, no other"
         (let [[after ran] (rebuilt counts (fx/swap-context ctx assoc-in [:grid :selected-cell] [1 0]))]
           (is (= #{A1 A2} ran))
           (is (= [true false] (selected before A1 A2)))
           (is (= [false true] (selected after A1 A2)))
           (is (= (map #(dissoc (before %) :selected?) [A1 A2]) (map #(dissoc (after %) :selected?) [A1 A2]))
               "only :selected? changes")
           (is (kept? after #{A1 A2}) "the other 30 cells and the effects grid: the same maps")))
       (testing "the effects grid's, on its own"
         (let [[after ran] (rebuilt counts (fx/swap-context ctx assoc-in [:effects :selected-cell] [1 0]))]
           (is (= #{[:fx [0 0]] [:fx [1 0]]} ran))
           (is (= [true false] (selected before [:fx [0 0]] [:fx [1 0]])))
           (is (= [false true] (selected after [:fx [0 0]] [:fx [1 0]])))
           (is (kept? after #{[:fx [0 0]] [:fx [1 0]]}) "the cue grid and the other 30: the same maps")))
       (testing "the same cell selected again: none"
         (let [[after ran] (rebuilt counts (fx/swap-context ctx assoc-in [:grid :selected-cell] [0 0]))]
           (is (= #{} ran))
           (is (kept? after #{}))))
       (testing "no cell selected, then one: a cell each time"
         (let [none (fx/swap-context ctx assoc-in [:grid :selected-cell] nil)
               [after-none ran] (rebuilt counts none)
               [after ran'] (rebuilt counts (fx/swap-context none assoc-in [:grid :selected-cell] [1 0]))]
           (is (= #{A1} ran))
           (is (= [false false] (selected after-none A1 A2)))
           (is (= #{A2} ran'))
           (is (= [false true] (selected after A1 A2)))))))))

(def ^:private key-h {:source :keyboard :id :h})
(def ^:private note-60 {:source :midi :channel 0 :note 60})
(def ^:private cue-a2 {:type :trigger-cue :target [1 0]})
(def ^:private fx-a2 {:type :trigger-effect :target [1 0]})
(def ^:private vibe-hit {:type :vibe :role :hit})

(defn- cells-with-trigger
  "The cells of both grids that say an input triggers them, each asked on
   its own (cell-has-trigger?): #{[action-type [col row]]}."
  [ctx]
  (set (for [type [:trigger-cue :trigger-effect]
             [col row] grid-cells
             :when (fx/sub-ctx ctx subs/cell-has-trigger? type col row)]
         [type [col row]])))

(deftest cell-trigger-index-is-shared-by-both-grids-test
  (counting
   {#'subs/cell-trigger-targets :index}
   (fn [counts]
     (let [walks #(get @counts [:index []] 0)
           asked (fn [ctx] (let [n (walks) cells (cells-with-trigger ctx)] [cells (- (walks) n)]))
           bindings (fn [m] #(assoc-in % [:config :input :trigger-map] m))
           ctx (comparison-context base-state)]
       (testing "the 64 cells' first look walks the bindings once"
         (is (= [#{} 1] (asked ctx))))
       (let [last-ctx
             (reduce
              (fn [ctx [label f expected]]
                (let [ctx' (fx/swap-context ctx f)]
                  (testing label
                    (is (= expected (asked ctx')) "[the cells with ⚡, walks of the bindings]"))
                  ctx'))
              ctx
              [["a key for a Vibe role: one walk, no cell" (bindings {key-h vibe-hit}) [#{} 1]]
               ["two keys for cue A2" (bindings {key-h cue-a2 note-60 cue-a2}) [#{[:trigger-cue [1 0]]} 1]]
               ["one of them gone: it still has one" (bindings {note-60 cue-a2}) [#{[:trigger-cue [1 0]]} 1]]
               ["its last key on the effects cell at the same place" (bindings {note-60 fx-a2})
                [#{[:trigger-effect [1 0]]} 1]]
               ["and on a Vibe role" (bindings {note-60 vibe-hit}) [#{} 1]]
               ["two keys for an empty cell" (bindings {key-h fx-a2 note-60 fx-a2}) [#{[:trigger-effect [1 0]]} 1]]
               ["both kinds at one place" (bindings {key-h fx-a2 note-60 cue-a2})
                [#{[:trigger-cue [1 0]] [:trigger-effect [1 0]]} 1]]
               ["the last key gone" (bindings {}) [#{} 1]]
               ["a key that follows the cue's mode" (bindings {note-60 (assoc cue-a2 :behavior :follow)})
                [#{[:trigger-cue [1 0]]} 1]]
               ["its behavior alone: a walk, the same cells" (bindings {note-60 (assoc cue-a2 :behavior :momentary)})
                [#{[:trigger-cue [1 0]]} 1]]
               ["a selection: no walk" #(assoc-in % [:grid :selected-cell] [1 0]) [#{[:trigger-cue [1 0]]} 0]]
               ["a learn starting: no walk" #(waiting-to-learn % fx-a2)
                [#{[:trigger-cue [1 0]]} 0]]
               ["and ending: none" #(assoc-in % [:ui :input-learn :active] nil) [#{[:trigger-cue [1 0]]} 0]]
               ["a key for the cue that plays, as for any" (bindings {key-h {:type :trigger-cue :target [0 0]}
                                                                      note-60 (assoc cue-a2 :behavior :momentary)})
                [#{[:trigger-cue [0 0]] [:trigger-cue [1 0]]} 1]]
               ["that cue stopping: no walk" #(update-in % [:playback :active-cues] dissoc [0 0])
                [#{[:trigger-cue [0 0]] [:trigger-cue [1 0]]} 0]]])]
         (is (= (assoc cue-a2 :behavior :momentary) (get (fx/sub-ctx last-ctx subs/input-trigger-map) note-60))
             "the bindings themselves, which the trigger editor lists, keep their behavior"))))))

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
            :learning? false :problem nil :dim? false :reactions [] :effects-only? false}
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
      (let [ctx' (fx/swap-context ctx waiting-to-learn {:type :vibe :role :accent})]
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
      (is (= :hit (:learning (fx/sub-ctx (fx/swap-context ctx waiting-to-learn {:type :vibe :role :hit})
                                         subs/vibe-header))))
      (is (nil? (:learning (fx/sub-ctx (fx/swap-context ctx waiting-to-learn {:type :trigger-cue :target [0 0]})
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
      (let [ctx' (handle ctx {:event/type :show-clock/cue-ended :cell cell :run run :position-ids {:restart nil :seek nil}})]
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
                         (assoc-in [:chains :cue-chains [3 0] :target] :crowd)
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
      (is (= {:in? false :pos nil :roles [] :others ["#9B59B6"] :blocked nil} (fx/sub-ctx ctx subs/vibe-pick-fx 0 3)))
      (is (= {:in? true :pos nil :roles [] :others [] :blocked nil}
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
        "nor with an empty cell")
    (testing "Make cues counts a cue no vibe names, and only the cue grid's (reactivity plan 37)"
      (let [s (assoc-in (vibe-state) [:chains :cue-chains] {[3 3] {:items [{:type :preset :preset-id :circle}]}})
            ctx (context s)
            steps (fx/sub-ctx ctx subs/vibe-checklist)]
        (is (= (library/checklist s) steps))
        (is (= [[:cues true] [:vibes true] [:hit false] [:controls false]] (mapv (juxt :step :done?) steps))
            "D4 is in no vibe, and every HIT cell is empty")
        (is (= [:cues false] ((juxt :step :done?)
                              (first (fx/sub-ctx (fx/swap-context ctx update-in [:chains :cue-chains] dissoc [3 3])
                                                 subs/vibe-checklist))))
            "gone: no cue on the grid")
        (is (identical? steps (fx/sub-ctx (fx/swap-context ctx assoc-in [:chains :effect-chains [0 3]]
                                                           (assoc (reactions/reaction-cell :punch-size nil) :active? true))
                                          subs/vibe-checklist))
            "an effect cell is none of its business")))))

(deftest vibe-lint-sub-test
  (let [ctx (context (vibe-state))
        lint (fx/sub-ctx ctx subs/vibe-lint)]
    (is (= [{:level :info :text "1 of 5 controls learned"}] lint))
    (is (identical? lint (fx/sub-ctx (-> ctx (press :hit 1000) (status-write)) subs/vibe-lint))
        "a press and the show clock's status leave it alone")
    (is (= [{:level :error :vibe :vibe-2 :text "Lines: HIT does nothing (no cues, no tap reactions)"}]
           (filterv #(= :error (:level %))
                    (fx/sub-ctx (-> ctx
                                    (handle {:event/type :grid/clear-cell :col 5 :row 0})
                                    (handle {:event/type :grid/clear-cell :col 7 :row 0}))
                                subs/vibe-lint)))
        "a cleared cell leaves its pools (vibes.refs): both of Lines' HIT cues cleared, it has nothing to play")
    (is (= [{:level :info :vibe :vibe-2 :text "Lines: HIT moves effects only (Punch size)"}]
           (filterv #(= :vibe-2 (:vibe %))
                    (fx/sub-ctx (-> ctx
                                    (fx/swap-context assoc-in [:config :vibes :library :vibe-2 :hit :cells] [])
                                    (fx/swap-context reactions/add-to-vibe :vibe-2 :punch-size))
                                subs/vibe-lint)))
        "the lint reads the effect cells too")))

(deftest vibe-edited-reactions-sub-test
  (let [ctx (context (-> (vibe-state)
                         (reactions/add-to-vibe :vibe-1 :punch-size)
                         (reactions/add-to-vibe :vibe-2 :flip)
                         (assoc-in [:chains :effect-chains [7 3]] (assoc (reactions/reaction-cell :swell nil) :active? true))))
        playing (-> ctx (press :hit 1000) (release :hit 1050))
        moves (fx/sub-ctx playing subs/vibe-edited-reactions)]
    (is (= {:hit [{:grid :effects :cell [0 3] :name "Punch size"}]} moves)
        "the vibe edited (the first): its own cells, not one switched on on the Effects tab")
    (is (true? (in-state playing :chains :effect-chains [0 3] :active?)) "Shapes plays, its Punch on")
    (is (= {:accent [{:grid :effects :cell [1 3] :name "Flip"}]}
           (fx/sub-ctx (handle playing {:event/type :vibe/select-for-edit :vibe :vibe-2}) subs/vibe-edited-reactions))
        "it follows the selection")
    (is (identical? moves (fx/sub-ctx (-> playing (press :hit 2000) (status-write)) subs/vibe-edited-reactions))
        "a press and the show clock's status leave it alone")
    (testing "the vibe edited changes and the vibe that plays doesn't (reactivity plan 37)"
      (let [lines (handle playing {:event/type :vibe/select-for-edit :vibe :vibe-2})]
        (is (= :vibe-1 (fx/sub-ctx lines subs/vibe-current-id)) "Shapes still plays")
        (is (= {:hit ["Punch size"] :flash ["Swell"]}
               (into {} (for [role [:hit :flash]] [role (:reactions (fx/sub-ctx lines subs/vibe-pad role))])))
            "its pads name its reactions, and the cell switched on on the Effects tab")
        (is (= (reactions/reactions (in-state lines) :vibe-2 :own? true) (fx/sub-ctx lines subs/vibe-edited-reactions)))))
    (testing "the vibe that plays changes, which switches both vibes' cells, and the vibe edited doesn't: the same reactions"
      (let [lines-play (handle playing {:event/type :vibe/select :vibe :vibe-2 :time 3000})]
        (is (= :vibe-2 (fx/sub-ctx lines-play subs/vibe-current-id)))
        (is (= [false true] [(in-state lines-play :chains :effect-chains [0 3] :active?)
                             (in-state lines-play :chains :effect-chains [1 3] :active?)])
            "Shapes' Punch off, Lines' Flip on")
        (is (= :vibe-1 (fx/sub-ctx lines-play subs/vibe-edited-id)))
        (is (identical? moves (fx/sub-ctx lines-play subs/vibe-edited-reactions)))))))

;; Learning an input (reactivity plan 24): each control shows the attempt
;; active in [:ui :input-learn], the one its Cancel names

(deftest learn-selectors-follow-the-active-attempt-test
  (let [cue {:type :trigger-cue :target [0 0]}
        doc #uuid "00000000-0000-0000-0000-0000000024c0"
        ctx (context (assoc-in (domains/build-initial-state) [:project :document-id] doc))
        waiting (fx/swap-context ctx waiting-to-learn cue)]
    (testing "a learn waiting to bind: its target, and its Cancel's identity, for that target only"
      (is (nil? (fx/sub-ctx ctx subs/input-learning-target)))
      (is (= cue (fx/sub-ctx waiting subs/input-learning-target)))
      (is (= {:learn-id #uuid "00000000-0000-0000-0000-0000000024c1" :document-id doc}
             (fx/sub-ctx waiting subs/learn-for-target cue)))
      (is (nil? (fx/sub-ctx waiting subs/learn-for-target {:type :trigger-cue :target [1 0]})))
      (is (nil? (fx/sub-ctx waiting subs/learn-for-target {:type :trigger-effect :target [0 0]}))))
    (testing "Settings' identify is no control's learn: it is its card's"
      (let [l #uuid "00000000-0000-0000-0000-0000000024c3"
            identify (fx/swap-context ctx assoc-in [:ui :input-learn :active] {:learn-id l :owner :settings :capture :observe :source :midi})
            shown (fx/swap-context identify #(-> %
                                                 (assoc-in [:ui :input-learn :active] nil)
                                                 (assoc-in [:ui :input-learn :settings :midi] {:status :captured :input-event {:type :note-on :source :midi :channel 1 :note 60}})
                                                 (assoc-in [:backend :input :midi :connected-devices] #{"Pad"})))]
        (is (nil? (fx/sub-ctx identify subs/input-learning-target)))
        (is (= {:learn-id l :result nil :available? false} (fx/sub-ctx identify subs/settings-learn :midi)))
        (is (nil? (:learn-id (fx/sub-ctx identify subs/settings-learn :osc))))
        (is (= {:learn-id nil :result {:status :captured :input-event {:type :note-on :source :midi :channel 1 :note 60}} :available? true}
               (fx/sub-ctx shown subs/settings-learn :midi)))))
    (testing "the trigger editor's behaviour: the waiting learn's choice, else the cell's binding's, else the editor's"
      (let [bound (fx/swap-context ctx assoc-in [:config :input :trigger-map {:source :keyboard :id :q}] (assoc cue :behavior :quantized-decay))
            chosen #(fx/swap-context % assoc-in [:ui :dialogs :trigger-editor :behavior] :momentary)]
        (is (= :follow (fx/sub-ctx ctx subs/trigger-editor-behavior cue)))
        (is (= :momentary (fx/sub-ctx (chosen ctx) subs/trigger-editor-behavior cue)))
        (is (= :follow (fx/sub-ctx (chosen bound) subs/trigger-editor-behavior cue)) "an older project's binding plays the cue's mode")
        (is (= :momentary (fx/sub-ctx (fx/swap-context bound waiting-to-learn (assoc cue :behavior :momentary)) subs/trigger-editor-behavior cue))
            "the learn waiting for the cell")))))

(deftest vibe-controls-sub-test
  (let [ctx (context (vibe-state))
        wizard {:queue [:accent :flash :vibe :blackout] :done [:hit] :refused :hit}]
    (is (= {:role :hit :label "HIT" :bindings [hit-key] :learning? false} (fx/sub-ctx ctx subs/vibe-control :hit)))
    (is (= {:role :blackout :label "BLACKOUT" :bindings [] :learning? false} (fx/sub-ctx ctx subs/vibe-control :blackout)))
    (is (nil? (fx/sub-ctx ctx subs/vibe-wizard)))
    (let [ctx' (fx/swap-context ctx assoc-in [:ui :input-learn :active]
                                {:learn-id #uuid "00000000-0000-0000-0000-0000000024c2" :owner :learn-all :capture :press
                                 :target-action {:type :vibe :role :accent} :wizard wizard})]
      (is (= {:role :accent :step 2 :of 5 :refused :hit} (fx/sub-ctx ctx' subs/vibe-wizard)))
      (is (true? (:learning? (fx/sub-ctx ctx' subs/vibe-control :accent))))
      (is (nil? (fx/sub-ctx (fx/swap-context ctx' assoc-in [:ui :input-learn :active :owner] :binding) subs/vibe-wizard))
          "ACCENT's own learn, not Learn all's: no wizard shows")
      (is (nil? (fx/sub-ctx (fx/swap-context ctx' assoc-in [:ui :input-learn :active] nil) subs/vibe-wizard))
          "nothing learning: no wizard shows"))))

(deftest fader-subs-test
  (let [ctx (context (-> (vibe-state)
                         (templates/apply-starter-effects)
                         (assoc-in [:config :input :macro-map] {[:midi 0 7] :slider-1})))]
    (testing "the Controls panel's fader rows"
      (is (= {:slot :slider-1 :label "INTENSITY" :bindings [[:midi 0 7]] :learning? false}
             (fx/sub-ctx ctx subs/vibe-fader-control :slider-1)))
      (is (= {:slot :slider-2 :label "MORPH" :bindings [] :learning? false}
             (fx/sub-ctx ctx subs/vibe-fader-control :slider-2)))
      (let [learning (fx/swap-context ctx waiting-to-learn {:type :macro :slot :slider-2})]
        (is (true? (:learning? (fx/sub-ctx learning subs/vibe-fader-control :slider-2))))
        (is (false? (:learning? (fx/sub-ctx learning subs/vibe-fader-control :slider-1))))
        (is (false? (:learning? (fx/sub-ctx learning subs/vibe-control :hit))) "a role's row doesn't wait")
        (is (nil? (:learning (fx/sub-ctx learning subs/vibe-header))) "nor does Perform ask for a key"))
      (is (= {:slot :slider-1 :label "INTENSITY" :bindings [] :learning? false}
             (fx/sub-ctx (fx/swap-context ctx update-in [:config :input] dissoc :macro-map) subs/vibe-fader-control :slider-1))
          "a project from before the faders"))
    (testing "the meters: the Master wires Intensity, nothing Morph yet"
      (is (= {:slot :slider-1 :label "Intensity" :wired? true} (fx/sub-ctx ctx subs/vibe-meter :slider-1)))
      (is (= {:slot :slider-2 :label "Morph" :wired? false} (fx/sub-ctx ctx subs/vibe-meter :slider-2)))
      (let [morphed (handle ctx {:event/type :vibe/add-morph :vibe :vibe-1 :kind :size})]
        (is (true? (:wired? (fx/sub-ctx morphed subs/vibe-meter :slider-2))) "Shapes plays, and its FX set wires it")
        (is (false? (:wired? (fx/sub-ctx (handle morphed {:event/type :vibe/select :vibe :vibe-2 :time 1000}) subs/vibe-meter :slider-2)))
            "Lines doesn't")
        (is (= (fx/sub-ctx morphed subs/vibe-meter :slider-2) (fx/sub-ctx (press morphed :hit 2000) subs/vibe-meter :slider-2))
            "a press changes nothing a meter shows")))
    (testing "the buttons: where the Master is, a free cell, and the vibe the tab edits"
      (is (= {:master [0 3] :free? true :vibe :vibe-1 :vibe-name "Shapes"} (fx/sub-ctx ctx subs/vibe-fader-actions)))
      (is (nil? (:master (fx/sub-ctx (fx/swap-context ctx update-in [:chains :effect-chains] dissoc [0 3]) subs/vibe-fader-actions)))))))


(deftest pad-names-its-reactions-sub-test
  (let [ctx (context (-> (vibe-state)
                         (reactions/add-to-vibe :vibe-1 :punch-size)
                         (reactions/add-to-vibe :vibe-1 :fade-in)
                         (reactions/add-to-vibe :vibe-2 :flip)))]
    (is (= ["Punch size"] (:reactions (fx/sub-ctx ctx subs/vibe-pad :hit))) "the current vibe's, by role")
    (is (= ["Fade in"] (:reactions (fx/sub-ctx ctx subs/vibe-pad :vibe))))
    (is (= [] (:reactions (fx/sub-ctx ctx subs/vibe-pad :accent))) "vibe-2's Flip isn't vibe-1's")
    (is (= ["Flip"] (:reactions (fx/sub-ctx (handle ctx {:event/type :vibe/select :vibe :vibe-2 :time 1000})
                                            subs/vibe-pad :accent))))
    (testing "effects only: nothing to play, but reactions"
      (let [no-hits (fx/swap-context ctx assoc-in [:config :vibes :library :vibe-1 :hit :cells] [])]
        (is (false? (:effects-only? (fx/sub-ctx ctx subs/vibe-pad :hit))))
        (is (true? (:effects-only? (fx/sub-ctx no-hits subs/vibe-pad :hit))))
        (is (false? (:effects-only? (fx/sub-ctx (fx/swap-context no-hits assoc-in [:config :vibes :library :vibe-1 :accent :cells] [])
                                                subs/vibe-pad :accent)))
            "nothing to play and no reactions: not effects only")
        (testing "and its press lights the pad while held, though the engine holds nothing"
          (let [pressed (press no-hits :hit 1000)]
            (is (nil? (in-state pressed :vibe-runtime :held :hit)))
            (is (true? (:held? (fx/sub-ctx pressed subs/vibe-pad :hit))))
            (is (false? (:held? (fx/sub-ctx (release pressed :hit 1100) subs/vibe-pad :hit))))))))))

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
        (is (zero? (held (after none :transport/stop))))))
    (testing "plan 03 example 2: the end of a held cue after Stop leaves the other held, until Play"
      (let [two (update-in playing-state [:playback :active-cues] dissoc [0 1])
            stopped (after two :transport/stop)
            end {:event/type :show-clock/cue-ended :cell [0 0] :run #uuid "00000000-0000-0000-0000-000000000001"
                 :position-ids {:restart (get-in stopped [:playback :requests :restart :id]) :seek nil}}
            ended (:state (timing/handle (assoc end :state stopped)))
            playing (after ended :transport/play)]
        (is (= 2 (held stopped)))
        (is (= [true 1] [(held? ended) (held ended)]))
        (is (= [] (fx/sub-ctx (context ended) subs/playing-cues)) "nothing plays while it holds")
        (is (= {:state ended} (timing/handle (assoc end :state ended))) "a duplicate changes nothing")
        (is (= [false 0] [(held? playing) (held playing)]))
        (is (= [[1 0]] (map (juxt :col :row) (fx/sub-ctx (context playing) subs/playing-cues))))))))

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
                                 [:running? :state :status-text]))]
    (is (= {:running? true :state :idle :status-text "No projectors enabled"}
           (status-at true {:engines 0 :wanted 0 :pending? false :projectors {}}))
        "streaming is on, with nothing to stream")
    (is (= {:running? true :state :streaming :status-text "Streaming to 1 of 1"}
           (status-at true {:engines 1 :wanted 1 :pending? false
                            :projectors {:a {:state :streaming :fps 59.8}}})))
    (is (= {:running? false :state :stopped :status-text "Not streaming"} (status-at false nil)))
    (is (= {:running? true :state :starting :status-text "Starting…"} (status-at true nil))
        "on, and the output runtime has published nothing yet")
    (testing "off: the intent goes first, whatever the last report says"
      (is (= {:running? false :state :stopping :status-text "Stopping…"}
             (status-at false {:engines 1 :wanted 0 :pending? false
                               :projectors {:a {:state :error :error "open failed"}}}))
          "an engine is still there, with an old error")
      (is (= {:running? false :state :stopped :status-text "Not streaming"}
             (status-at false {:engines 0 :wanted 0 :pending? true :projectors {}}))
          "a queued command alone is no engine"))))

(deftest connection-status-follows-published-projector-states-test
  ;; One published sample at a time, as output-runtime/status makes them: only
  ;; a :streaming report counts, and "N of M" needs a settled sample, with
  ;; nothing pending and a report for each wanted output and no other. A
  ;; drained queue alone says nothing about what streams
  (let [starting {:state :starting}
        streams {:state :streaming :fps 59.8}
        failed {:state :error :error "open failed"}
        sample (fn [engines wanted pending? projectors]
                 {:engines engines :wanted wanted :pending? pending? :projectors projectors})
        on (context (-> base-state
                        (assoc :projectors {:a {:name "Laser L"} :b {:name "Laser R"}})
                        (assoc-in [:backend :streaming :running?] true)))]
    (reduce
     (fn [ctx [why status expected errors]]
       (let [ctx (fx/swap-context ctx assoc-in [:backend :streaming :status] status)
             {:keys [running? state status-text] :as said} (fx/sub-ctx ctx subs/connection-status)]
         (testing (str why " " (pr-str status))
           (is (= expected [state status-text]))
           (is (= errors (:errors said)) "the tooltip: a line for each error, by id")
           (is (true? running?) "the intent, so the button stays Stop"))
         ctx))
     on
     [["a command pending" (sample 1 1 true {:a starting})
       [:starting "Starting…"] []]
      ["the queue drained, and it still starts" (sample 1 1 false {:a starting})
       [:starting "Starting…"] []]
      ["it streams" (sample 1 1 false {:a streams})
       [:streaming "Streaming to 1 of 1"] []]
      ["another one added, still pending" (sample 2 2 true {:a streams :b starting})
       [:starting "Updating outputs…"] []]
      ["one streams, the other starts" (sample 2 2 false {:a streams :b starting})
       [:mixed "Streaming to 1 of 2 · 1 starting"] []]
      ["both stream" (sample 2 2 false {:a streams :b streams})
       [:streaming "Streaming to 2 of 2"] []]
      ["B failed before it was registered" (sample 1 2 false {:a starting :b failed})
       [:error "0 of 2 streaming · 1 starting · 1 error"] ["Laser R: open failed"]]
      ["both failed, with no engine" (sample 0 2 false {:b failed :a (assoc failed :error "No host")})
       [:error "0 of 2 streaming · 2 errors"] ["Laser L: No host" "Laser R: open failed"]]
      ["recovered: the errors go" (sample 2 2 false {:a streams :b streams})
       [:streaming "Streaming to 2 of 2"] []]
      ["two of three still start" (sample 3 3 false {:a streams :b starting :c starting})
       [:mixed "Streaming to 1 of 3 · 2 starting"] []]
      ["one not in the rig fails: its id names it" (sample 3 3 false {:a streams :b starting :c failed})
       [:error "Streaming to 1 of 3 · 1 starting · 1 error"] ["c: open failed"]]
      ["an old engine retires" (sample 2 1 true {:old streams :a streams})
       [:starting "Updating outputs…"] []]
      ["it fails while it retires" (sample 2 1 true {:old failed :a streams})
       [:error "Updating outputs… · 1 error"] ["old: open failed"]]
      ["still there with nothing pending: never 2 of 1" (sample 2 1 false {:old streams :a streams})
       [:starting "Updating outputs…"] []]
      ["gone" (sample 1 1 false {:a streams})
       [:streaming "Streaming to 1 of 1"] []]
      ["a warning is no error, and its output counts once" (sample 2 2 false {:a (assoc streams :warning "Corner Pin skipped") :b streams})
       [:streaming "Streaming to 2 of 2"] []]])))

(deftest zone-group-usage-counts-cues-test
  (let [s (-> base-state
              (assoc-in [:chains :cue-chains [2 0]] {:target :left
                                                     :items [{:type :preset :preset-id :line}]})
              (assoc-in [:chains :cue-chains [3 1]] {:items [{:type :preset :preset-id :line :target :left}]})
              (assoc-in [:chains :effect-chains [5 5]] {:items [{:effect-id :zone-selector :params {:target-zone :left}}]})
              (assoc-in [:projectors :p1] {:name "P1" :zone-groups [:all :left]}))
        usage (fx/sub-ctx (context s) subs/zone-group-usage :left)]
    (is (= 2 (:cue-count usage)) "two cues name Left; the effects grid isn't a cue")
    (is (= [[2 0] [3 1]] (vec (:cues usage))))
    (is (= 1 (:projector-count usage)))
    (testing "a cue with no destination counts for All"
      (is (= 2 (:cue-count (fx/sub-ctx (context base-state) subs/zone-group-usage :all)))))))


;; Safety (plans/2026-11-18-vibe-mode/07)

(deftest vibe-output-banner-sub-test
  (let [streaming (fn [ctx running? status]
                    (fx/swap-context ctx #(-> % (assoc-in [:backend :streaming :running?] running?)
                                              (assoc-in [:backend :streaming :status] status))))
        banner #(fx/sub-ctx % subs/vibe-output-banner)
        ctx (context (vibe-state))]
    (is (nil? (banner ctx)) "nothing played yet: the status line says it, no banner")
    (let [played (press ctx :hit 1000)]
      (is (= "NO OUTPUT: press Stream" (banner played)) "a press into nothing: the banner says why it shows nothing")
      (is (some? (get-in (fx/sub-val played identity) [:playback :active-cues (in-state played :vibe-runtime :voices :hit :cell)]))
          "invariant 6: the press still plays")
      (is (= "NO OUTPUT: no projectors enabled" (banner (streaming played true {:engines 0 :wanted 0 :pending? false :projectors {}})))
          "as the toolbar says (connection-status :idle)")
      (is (nil? (banner (streaming played true nil))) "starting")
      (is (nil? (banner (streaming played true {:engines 1 :wanted 1 :pending? false :projectors {:a {:state :streaming}}}))))
      (testing "an effects-only press, or a vibe chosen on the strip, has played too"
        (is (some? (banner (fx/swap-context ctx assoc-in [:playback :pulses :accent] {:id 1 :held? false}))))
        (is (some? (banner (fx/swap-context ctx assoc-in [:vibe-runtime :current] :vibe-2))))))))

(deftest vibe-show-lock-sub-test
  (let [ctx (context (vibe-state))]
    (is (= {:locked? false :notice nil} (fx/sub-ctx ctx subs/vibe-show-lock)))
    (is (= {:locked? true :notice "Move Cell refused"}
           (fx/sub-ctx (fx/swap-context ctx #(-> % (assoc-in [:ui :show-lock?] true)
                                                 (assoc-in [:ui :show-lock-notice] {:text "Move Cell refused" :event :grid/move-cell})))
                       subs/vibe-show-lock)))))

(deftest vibe-audience-subs-test
  (let [crowd {:items [{:type :preset :preset-id :circle}] :target :crowd}
        s (-> (vibe-state)
              (assoc-in [:chains :cue-chains [0 1]] crowd)
              (assoc-in [:config :vibes :library :vibe-1 :hit :cells] [[0 0] [0 1]]))
        allowing (assoc-in s [:config :vibes :library :vibe-1 :allow-audience?] true)
        marks #(into {} (for [r roles] [r (fx/sub-ctx (context %) subs/vibe-pad-audience? r)]))]
    (is (false? (fx/sub-ctx (context s) subs/vibe-audience?)))
    (is (= {:hit false :accent false :flash false :vibe false :blackout false} (marks s))
        "an audience cell in a pool that never plays it marks nothing (V6)")
    (is (true? (fx/sub-ctx (context allowing) subs/vibe-audience?)) "the header's badge")
    (is (= {:hit true :accent false :flash false :vibe false :blackout false} (marks allowing))
        "the pad that may start it")
    (is (true? (:flash (marks (assoc-in allowing [:config :vibes :library :vibe-1 :flash :cue] [0 1]))))
        "FLASH's cue")
    (testing "the vibe that plays: another one's rule isn't this one's"
      (is (false? (fx/sub-ctx (context (assoc-in allowing [:vibe-runtime :current] :vibe-2)) subs/vibe-audience?))))))


;; Audience indication (plans/2026-11-23-cljfx-reactivity/12): the pads, the
;; picker and the full state agree on what can reach the crowd, a cue whose
;; zone pattern alone names a custom group too

(def ^:private balcony-lane [:auto.laser/main :balcony])

(def ^:private balcony-cue
  "A cue on the laser that doesn't scan the crowd, whose zone pattern alone
   reaches the scanner: through :balcony, a custom group only it is in."
  {:target :auto.laser/main
   :items [{:type :preset :preset-id :circle}]
   :zone-patterns [(assoc-in (zone-patterns/preset-instance :alternate :cue) [:params :zones] balcony-lane)]})

(defn- audience-state
  "12 §7's fixture on the starter show: :main, and :aud, a crowd scanner
   that is off, out of All and in :balcony alone; the balcony cue at [0 0]
   and its pattern in the effect cell [2 1], off. Shapes' cue slots name
   cues that reach no crowd, and its FX set the Energy size reaction, which
   follows HIT, ACCENT and FLASH."
  []
  (-> (vibe-state)
      (assoc-in [:rig :order] [:main :aud])
      (assoc :projectors {:main {:enabled? true}
                          :aud {:enabled? false :exclude-from-all? true :tags #{:crowd-scanning} :zone-groups [:balcony]}})
      (assoc-in [:zone-groups :balcony] {:name "Balcony"})
      (assoc-in [:chains :cue-chains [0 0]] balcony-cue)
      (assoc-in [:chains :effect-chains [2 1]]
                {:active? false :items [(assoc-in (zone-patterns/preset-instance :alternate :cell) [:params :zones] balcony-lane)]})
      (assoc-in [:config :vibes :library :vibe-1 :hit :cells] [[1 0]])
      (reactions/add-to-vibe :vibe-1 :energy-size)))

(defn- allowing [s] (assoc-in s [:config :vibes :library :vibe-1 :allow-audience?] true))

(defn- naming
  "s with Shapes' role naming cells: its pool, or FLASH's cue (the first)."
  [s role cells]
  (if (= :flash role)
    (assoc-in s [:config :vibes :library :vibe-1 :flash :cue] (first cells))
    (assoc-in s [:config :vibes :library :vibe-1 role :cells] cells)))

(def ^:private pad-cue-status @#'subs/vibe-pad-cue-status)

(defn- pad-quiet?
  "Does role's press start no cue (the pad's own status, which its Effects
   only and its mark read)?"
  [ctx role]
  (:quiet? (fx/sub-ctx ctx pad-cue-status role)))

(def ^:private cue-slot {:hit :hit :accent :accent :flash :flash-cue})

(defn- expected-pads
  "12 §7's oracle on the full state s, each pad's {:quiet? :mark? :reactions
   :effects-only?}: quiet when the vibe that plays may start none of its
   role's cues (vibes.library/playable-cells), marked when it allows the
   audience and one of those can reach it."
  [s]
  (let [id (library/current-id s)
        vibe (library/vibe s id)
        by-role (reactions/reactions s id)]
    (into {} (for [role roles
                   :let [slot (cue-slot role)
                         playable (library/playable-cells s vibe (library/slot-cells vibe slot))
                         quiet? (and (some? slot) (empty? playable))
                         names (mapv :name (get by-role role))]]
               [role {:quiet? quiet?
                      :mark? (boolean (and slot (:allow-audience? vibe) (some #(library/audience-cell? s %) playable)))
                      :reactions names
                      :effects-only? (boolean (and quiet? (seq names)))}]))))

(defn- shown-pads
  "What ctx's pads show, as expected-pads has it."
  [ctx]
  (into {} (for [role roles
                 :let [pad (fx/sub-ctx ctx subs/vibe-pad role)]]
             [role {:quiet? (pad-quiet? ctx role)
                    :mark? (fx/sub-ctx ctx subs/vibe-pad-audience? role)
                    :reactions (:reactions pad)
                    :effects-only? (:effects-only? pad)}])))

(defn- edited-groups
  "The audience groups the vibe edited keeps out on the full state s; nil
   when it allows them."
  [s]
  (when-not (:allow-audience? (library/vibe s (library/edited-id s)))
    (library/audience-groups s)))

(defn- expected-blocked
  "12 §7's oracle for the picker's mark on the cue at cell: :crowd or
   :timecode (vibes.library/unplayable for the vibe edited); nil for one it
   plays, or an empty cell."
  [s cell]
  (#{:crowd :timecode} (library/unplayable s cell (get-in s [:chains :cue-chains cell]) (edited-groups s))))

(defn- expected-fx-blocked
  "The same for the effect cell at cell: :crowd while the vibe edited keeps
   the audience out and the cell can reach it."
  [s cell]
  (when-let [groups (edited-groups s)]
    (when (library/audience-fx? s cell groups) :crowd)))

(defn- agree
  "ctx's pads, and its picker at the balcony cells, say what the full state
   s does."
  [s ctx msg]
  (is (= (expected-pads s) (shown-pads ctx)) msg)
  (is (= (expected-blocked s [0 0]) (:blocked (fx/sub-ctx ctx subs/vibe-pick-cell 0 0))) msg)
  (is (= (expected-fx-blocked s [2 1]) (:blocked (fx/sub-ctx ctx subs/vibe-pick-fx 2 1))) msg))

(defn- display
  "12 §7's display for role: [its pad's mark, quiet?, effects only?, the
   picker's mark at [0 0]]."
  [ctx role]
  [(fx/sub-ctx ctx subs/vibe-pad-audience? role)
   (pad-quiet? ctx role)
   (:effects-only? (fx/sub-ctx ctx subs/vibe-pad role))
   (:blocked (fx/sub-ctx ctx subs/vibe-pick-cell 0 0))])

(defn- successors
  "[[s ctx] …]: s in a new context, then after each edit (a fn of the
   state), applied to the full map and to the context (fx/swap-context)."
  [s edits]
  (reductions (fn [[s ctx] f] [(f s) (fx/swap-context ctx f)]) [s (context s)] edits))

(deftest audience-indicators-match-full-state-for-each-role-test
  (let [;; [what, allowed?, an edit, the role's cells, what plays (full state), the display]
        rows [["its pattern's custom group reaches the scanner" false identity [[0 0]] [] [false true true :crowd]]
              ["the vibe allows the audience" true identity [[0 0]] [[0 0]] [true false false nil]]
              ["the pattern off" true #(assoc-in % [:chains :cue-chains [0 0] :zone-patterns 0 :enabled?] false)
               [[0 0]] [[0 0]] [false false false nil]]
              ["a cue that reaches no crowd after it" false identity [[0 0] [1 0]] [[1 0]] [false false false :crowd]]
              ["following MIDI timecode" true #(update-in % [:chains :cue-chains [0 0]] merge {:editor-type :timeline :timeline/chase :mtc})
               [[0 0]] [] [false true true :timecode]]]]
    (doseq [role [:hit :accent :flash]
            [label allow? edit cells playable shown] rows
            :when (or (not= :flash role) (= 1 (count cells)))
            :let [s (cond-> (edit (naming (audience-state) role cells)) allow? allowing)
                  ctx (context s)
                  msg (str (name role) ": " label)]]
      (is (= playable (library/playable-cells s (library/vibe s :vibe-1) cells)) msg)
      (is (= shown (display ctx role)) msg)
      (agree s ctx msg))
    (testing "an empty cell, or FLASH without a cue: quiet and unmarked, effects only with a reaction; no mark on an empty cell"
      (doseq [[role cells] [[:hit [[0 2]]] [:accent [[0 2]]] [:flash [[0 2]]] [:flash []]]
              allow? [false true]
              :let [s (cond-> (naming (audience-state) role cells) allow? allowing)
                    ctx (context s)
                    msg (str (name role) " " cells (when allow? ", allowed"))]]
        (is (= {:quiet? true :mark? false :reactions ["Energy size"] :effects-only? true} (get (shown-pads ctx) role)) msg)
        (is (nil? (:blocked (fx/sub-ctx ctx subs/vibe-pick-cell 0 2))) msg)
        (agree s ctx msg)))
    (testing "quiet without a reaction: not effects only"
      (let [s (-> (audience-state) (naming :hit [[0 0]]) (assoc-in [:config :vibes :library :vibe-1 :fx] []))
            ctx (context s)]
        (is (= [false true false :crowd] (display ctx :hit)))
        (agree s ctx "no reaction")))
    (testing "the cue still blocked: the reaction out of the FX set, then its cell switched on on the effects grid"
      (let [s (naming (audience-state) :hit [[0 0]])
            cell (first (get-in s [:config :vibes :library :vibe-1 :fx]))
            [_ [out out-ctx] [on on-ctx]] (successors s [#(assoc-in % [:config :vibes :library :vibe-1 :fx] [])
                                                         #(assoc-in % [:chains :effect-chains cell :active?] true)])]
        (is (= [false true false :crowd] (display out-ctx :hit)) "no reaction names it: not effects only")
        (is (= [false true true :crowd] (display on-ctx :hit)) "on, it reacts to every press: effects only, still no mark")
        (is (= ["Energy size"] (:reactions (fx/sub-ctx on-ctx subs/vibe-pad :hit))))
        (agree out out-ctx "out of the FX set")
        (agree on on-ctx "on on the effects grid")))
    (testing "VIBE and BLACKOUT never mark, nor does FLASH for effects alone that can reach the crowd"
      (doseq [flash-cue [[6 0] nil]
              :let [s (-> (audience-state)
                          (allowing)
                          (naming :hit [[0 0]])
                          (assoc-in [:config :vibes :library :vibe-1 :flash] {:cue flash-cue :fx [[2 1]] :solo? true}))
                    ctx (context s)
                    msg (str "FLASH's cue " flash-cue)]]
        (is (= {:hit true :accent false :flash false :vibe false :blackout false} (update-vals (shown-pads ctx) :mark?)) msg)
        (agree s ctx msg)))
    (testing "the effect cell holding the same pattern, off: the FX picker's own mark"
      (is (= :crowd (:blocked (fx/sub-ctx (context (audience-state)) subs/vibe-pick-fx 2 1))))
      (is (nil? (:blocked (fx/sub-ctx (context (allowing (audience-state))) subs/vibe-pick-fx 2 1)))))))

(deftest audience-indicators-follow-routing-edits-test
  (let [hit (naming (audience-state) :hit [[0 0]])
        each (fn [f steps] (mapv (fn [[_ ctx]] (f ctx)) steps))
        check (fn [label s edits {:keys [mark quiet blocked fx-blocked]}]
                (let [on (successors (allowing s) edits)
                      off (successors s edits)]
                  (is (= mark (each #(fx/sub-ctx % subs/vibe-pad-audience? :hit) on)) (str label ": HIT's mark, allowed"))
                  (is (= quiet (each #(pad-quiet? % :hit) off)) (str label ": HIT quiet, not allowed"))
                  (is (= blocked (each #(:blocked (fx/sub-ctx % subs/vibe-pick-cell 0 0)) off)) (str label ": the picker"))
                  (when fx-blocked
                    (is (= fx-blocked (each #(:blocked (fx/sub-ctx % subs/vibe-pick-fx 2 1)) off)) (str label ": the FX picker")))
                  (doseq [[s ctx] (concat on off)]
                    (agree s ctx label))))]
    (testing "the group's definition: none, added, removed"
      (check "definition"
             (update hit :zone-groups dissoc :balcony)
             [#(assoc-in % [:zone-groups :balcony] {:name "Balcony"}) #(update % :zone-groups dissoc :balcony)]
             {:mark [false true false] :quiet [false true false] :blocked [nil :crowd nil] :fx-blocked [nil :crowd nil]}))
    (testing "the membership on a virtual output of the scanner; that output gone; then tagged itself, on an untagged parent"
      (check "virtual"
             (-> hit
                 (assoc-in [:projectors :aud :zone-groups] [])
                 (assoc :virtual-projectors {:v1 {:name "Aud B" :parent-projector-id :aud :zone-groups [:balcony]}}))
             [#(update % :virtual-projectors dissoc :v1)
              #(-> %
                   (update-in [:projectors :aud] dissoc :tags)
                   (assoc :virtual-projectors {:v1 {:name "Aud B" :parent-projector-id :aud :zone-groups [:balcony]
                                                    :tags #{:crowd-scanning}}}))]
             {:mark [true false true] :quiet [true false true] :blocked [:crowd nil :crowd] :fx-blocked [:crowd nil :crowd]}))
    (testing "a scanner on and in All, moved from the left end to the right: a cue on Left"
      (check "order"
             (-> hit
                 (assoc :projectors {:aud {:enabled? true :tags #{:crowd-scanning}} :main {:enabled? true}})
                 (assoc-in [:rig :order] [:aud :main])
                 (assoc-in [:chains :cue-chains [0 0]] {:target :auto/left :items [{:type :preset :preset-id :circle}]}))
             [#(assoc-in % [:rig :order] [:main :aud])]
             {:mark [true false] :quiet [true false] :blocked [:crowd nil]}))
    (let [inheriting (-> hit
                         (assoc-in [:chains :cue-chains [0 0]] {:items [{:type :preset :preset-id :circle}]})
                         (assoc-in [:grid :column-targets 0] :zone/b))
          balcony-map {:name "Balcony" :lanes [:auto.laser/main] :overrides {:zone/b :balcony}}]
      (testing "an inheriting cue on its column's zone B, which a map not in use sends to :balcony"
        (check "zone map"
               inheriting
               [#(assoc-in % [:zone-maps :map/custom-1] balcony-map)
                #(assoc-in % [:zone-maps :map/custom-1 :overrides] {})
                #(-> %
                     (assoc-in [:zone-maps :map/custom-1 :overrides] {:zone/b :balcony})
                     (update-in [:grid :column-targets] dissoc 0)
                     (assoc-in [:config :default-target] :zone/b))
                #(assoc-in % [:grid :column-targets 0] :auto.laser/main)]
               {:mark [false true false true false] :quiet [false true false true false]
                :blocked [nil :crowd nil :crowd nil]}))
      (testing "the map in use, the show clock's status and a cue no slot of Shapes names change nothing"
        (let [s (allowing (assoc-in inheriting [:zone-maps :map/custom-1] balcony-map))
              ctx (context s)
              routing (fx/sub-ctx ctx subs/vibe-audience-routing)
              groups (fx/sub-ctx ctx subs/vibe-audience-groups)
              pad (fx/sub-ctx ctx subs/vibe-pad :hit)
              shown (shown-pads ctx)
              pick (fx/sub-ctx ctx subs/vibe-pick-cell 0 0)]
          (is (true? (get-in shown [:hit :mark?])))
          (doseq [[label f] [["the map in use" #(assoc-in % [:zone-map :active] :map/custom-1)]
                             ["the show clock's status" #(-> %
                                                             (assoc-in [:timing :beat-index] 1)
                                                             (assoc-in [:backend :streaming :frame-stats] {:avg-latency-us 123}))]
                             ["a cue no slot names, now on :crowd" #(assoc-in % [:chains :cue-chains [5 0] :target] :crowd)]]
                  :let [ctx' (fx/swap-context ctx f)]]
            (is (= shown (shown-pads ctx')) label)
            (is (= pick (fx/sub-ctx ctx' subs/vibe-pick-cell 0 0)) label)
            (is (identical? pad (fx/sub-ctx ctx' subs/vibe-pad :hit)) label)
            (agree (f s) ctx' label))
          (let [ctx' (status-write ctx)]
            (is (identical? routing (fx/sub-ctx ctx' subs/vibe-audience-routing)) "a status-only successor keeps the routing")
            (is (identical? groups (fx/sub-ctx ctx' subs/vibe-audience-groups)))))))))

(deftest audience-indicators-distinguish-current-from-edited-test
  (let [both (fn [cue current edited]
               (-> (audience-state)
                   (assoc-in [:chains :cue-chains [0 0]] cue)
                   (allowing)
                   (naming :hit [[0 0]])
                   (assoc-in [:config :vibes :library :vibe-2 :hit :cells] [[0 0]])
                   (assoc-in [:vibe-runtime :current] current)
                   (assoc-in [:ui :vibes :selected] edited)))
        crowd {:target :crowd :items [{:type :preset :preset-id :circle}]}
        from-balcony (update-in balcony-cue [:zone-patterns 0 :params] merge {:zones [:auto.laser/main :auto/left] :from :balcony})
        hit (fn [ctx] [(fx/sub-ctx ctx subs/vibe-pad-audience? :hit) (pad-quiet? ctx :hit)
                       (:blocked (fx/sub-ctx ctx subs/vibe-pick-cell 0 0))])]
    (doseq [[label cue reaches?] [["a pattern through :balcony" balcony-cue true]
                                  ["a cue on :crowd" crowd true]
                                  ["a pattern from :balcony alone" from-balcony false]]]
      (testing label
        (let [s (both cue :vibe-1 :vibe-2)
              ctx (context s)]
          (is (= [reaches? false (when reaches? :crowd)] (hit ctx))
              "Shapes, which allows the audience, plays; Lines, which doesn't, is edited: the pad marks it, the picker blocks it")
          (agree s ctx label))
        (let [s (both cue :vibe-2 :vibe-1)
              ctx (context s)]
          (is (= [false reaches? nil] (hit ctx)) "swapped: no mark, no block, and the HIT that plays is quiet")
          (agree s ctx label))))
    (testing "only the vibe edited changes: the picker does, the pad doesn't; only the vibe that plays: the other way round"
      (let [ctx (context (both balcony-cue :vibe-1 :vibe-2))
            pad (fx/sub-ctx ctx subs/vibe-pad :hit)
            pick (fx/sub-ctx ctx subs/vibe-pick-cell 0 0)
            edited (fx/swap-context ctx assoc-in [:ui :vibes :selected] :vibe-1)
            playing (fx/swap-context ctx assoc-in [:vibe-runtime :current] :vibe-2)]
        (is (= [true false nil] (hit edited)))
        (is (identical? pad (fx/sub-ctx edited subs/vibe-pad :hit)))
        (is (= [false true :crowd] (hit playing)))
        (is (identical? pick (fx/sub-ctx playing subs/vibe-pick-cell 0 0)))))))

(deftest vibes-tab-label-sub-test
  (let [s (vibe-state)]
    (is (= "Vibes" (fx/sub-ctx (context s) subs/vibes-tab-label)) "the starter vibes: no error, warnings and info aside")
    (is (= "Vibes ⚠ 1" (fx/sub-ctx (context (assoc-in s [:config :vibes :library :vibe-1 :hit :cells] [])) subs/vibes-tab-label))
        "HIT does nothing: one error")
    (is (= "Vibes ⚠ 2" (fx/sub-ctx (context (-> s (assoc-in [:config :vibes :library :vibe-1 :hit :cells] [])
                                                (assoc-in [:config :vibes :library :vibe-2 :hit :cells] [])))
                                   subs/vibes-tab-label)))))


;; Derived input costs (plans/2026-11-23-cljfx-reactivity/37): the lint, the
;; Playing list's sort and the pattern fold run again when what they read
;; changes, not for a live edit beside it. Counted inside the function, each
;; successor made from its parent once that was asked, as the app's next
;; commit is.

(defn- counted-calls
  "[calls f]: f calls the function var v holds now, and counts each call in
   the atom calls."
  [v]
  (let [calls (atom 0)
        original @v]
    [calls (fn [& args] (swap! calls inc) (apply original args))]))

(defn- edit-steps
  "[[s ctx shown] …]: s in a new context, then after each edit (a fn of
   the state) in turn, the state and a successor of the last context, made
   only once (shown ctx) asked that one what it shows."
  [s shown edits]
  (loop [s s ctx (context s) edits edits acc []]
    (let [acc (conj acc [s ctx (shown ctx)])]
      (if-let [[edit & more] (seq edits)]
        (recur (edit s) (fx/swap-context ctx edit) more acc)
        acc))))

(def ^:private groove
  "37 §7: Groove, the one vibe, nothing in its HIT; Punch size at [7 3],
   off, in no vibe."
  (-> (domains/build-initial-state)
      (assoc-in [:config :vibes] {:order [:groove] :next-id 2 :library {:groove (library/new-vibe :groove 1 "Groove")}})
      (assoc-in [:chains :cue-chains] {})
      (assoc-in [:chains :effect-chains] {[7 3] (reactions/reaction-cell :punch-size "Groove")})))

(deftest vibes-badge-skips-live-cell-switches-test
  (let [lint library/lint
        [lints counted] (counted-calls #'library/lint)
        switch (fn [on?] #(assoc-in % [:chains :effect-chains [7 3] :active?] on?))
        envelope-off (fn [k] #(assoc-in % [:chains :effect-chains [7 3] :items 0 :params k :active?] false))
        shown (fn [ctx] {:label (fx/sub-ctx ctx subs/vibes-tab-label)
                         :rows (fx/sub-ctx ctx subs/vibe-lint)
                         :lints @lints
                         :perform (:reactions (fx/sub-ctx ctx subs/vibe-pad :hit))
                         :edited (fx/sub-ctx ctx subs/vibe-edited-reactions)})
        no-hit {:level :error :vibe :groove :text "Groove: HIT does nothing (no cues, no tap reactions)"}
        moves {:level :info :vibe :groove :text "Groove: HIT moves effects only (Punch size)"}]
    (with-redefs [library/lint counted]
      (let [walk (edit-steps groove shown [(switch true)
                                           #(assoc-in % [:config :vibes :library :groove :fx] [[7 3]])
                                           (switch false)
                                           (switch true)
                                           (envelope-off :x-scale)
                                           (envelope-off :y-scale)])
            [start on-grid assigned off on] (map peek walk)]
        (is (= ["Vibes ⚠ 1" "Vibes ⚠ 1" "Vibes" "Vibes" "Vibes" "Vibes" "Vibes ⚠ 1"] (mapv (comp :label peek) walk))
            "switched on in no vibe, HIT still does nothing; in the FX set, on or off, it moves Punch size, until neither envelope follows HIT")
        (is (= [1 1 2 2 2 3 4] (mapv (comp :lints peek) walk))
            "one lint for the badge and the rows; none for a switch, in no vibe or in the FX set; one for each edit of what it reads")
        (doseq [[s _ {:keys [rows]}] walk]
          (is (= (lint s) rows) "the rows the lint gives the full state"))
        (is (some #{no-hit} (:rows start)))
        (is (identical? (:rows start) (:rows on-grid)) "the switch alone: the same rows")
        (is (some #{moves} (:rows off)) "in the FX set, and off: HIT moves effects only")
        (is (not-any? #{no-hit} (:rows off)))
        (is (identical? (:rows assigned) (:rows off)))
        (is (identical? (:rows assigned) (:rows on)))
        (testing "the switch alone: Perform's HIT moves the cell that is on; the Vibes tab's reactions are Groove's own cells"
          (is (= [[] ["Punch size"]] [(:perform start) (:perform on-grid)]))
          (is (= [{} {}] [(:edited start) (:edited on-grid)]))
          (let [[s] (second walk)]
            (is (= {:hit [{:grid :effects :cell [7 3] :name "Punch size"}]} (reactions/reactions s :groove)))
            (is (= {} (reactions/reactions s :groove :own? true))))
          (is (identical? (:edited assigned) (:edited off)) "in the FX set: switched off, the same reactions"))
        (doseq [[s _ {:keys [perform edited]}] walk]
          (is (= (mapv :name (:hit (reactions/reactions s :groove))) perform) "Perform's, as the full state's")
          (is (= (reactions/reactions s :groove :own? true) edited) "the Vibes tab's, as the full state's"))))))

(def ^:private chasing
  "A cue that follows MIDI timecode."
  {:editor-type :timeline :timeline/chase :mtc
   :items [{:type :preset :preset-id :circle :timeline/start 0.0 :timeline/duration 4.0}]})

(defn- lint-rig
  "The starter show with its five controls learned, and p1-p4 on and in
   All, none scanning the crowd."
  []
  (assoc (learned-all (vibe-state))
         :projectors (into {} (for [n (range 1 5)]
                                [(keyword (str "p" n)) {:name (str "p" n) :host (str "10.0.0." n) :zone-groups [] :tags #{}}]))))

(defn- pattern-cell
  "An effects cell, off, with an Alternate zone pattern, more merged into
   its params."
  [& {:as more}]
  {:active? false :items [(update (zone-patterns/preset-instance :alternate :cell) :params merge more)]})

(defn- vibes-shown
  "What the Vibes tab shows of ctx's lint, checklist and tap reactions."
  [ctx]
  {:lint (fx/sub-ctx ctx subs/vibe-lint)
   :badge (fx/sub-ctx ctx subs/vibes-tab-label)
   :checklist (fx/sub-ctx ctx subs/vibe-checklist)
   :edited (fx/sub-ctx ctx subs/vibe-edited-reactions)})

(defn- readers-agree
  "Each step's lint (all of it, in order), checklist and Vibes tab
   reactions, on its successor and on a new context, are what
   vibes.library and vibes.reactions give its full state."
  [walk]
  (doseq [[s _ {:keys [lint checklist edited]}] walk]
    (is (= (library/lint s) lint (fx/sub-ctx (context s) subs/vibe-lint)))
    (is (= (library/checklist s) checklist (fx/sub-ctx (context s) subs/vibe-checklist)))
    (is (= (reactions/reactions s (library/edited-id s) :own? true) edited))))

(defn- lint-rows
  "The [level text] rows of a step's lint."
  [[_ _ {:keys [lint]}]]
  (set (map (juxt :level :text) lint)))

(deftest vibe-lint-inputs-preserve-all-row-levels-test
  (testing "37 §7: a pattern's missing group defined, then the Zone step key learned: the badge says 1, 0, 0, the rows change each time"
    (let [walk (edit-steps (-> (lint-rig)
                               (update :zone-groups dissoc :graphics)
                               (assoc-in [:config :vibes :library :vibe-1 :hit :cells] [[0 0]])
                               (assoc-in [:chains :effect-chains [2 1]] (pattern-cell :zones [:zone/a :graphics] :step-on :zone-step))
                               (assoc-in [:config :vibes :library :vibe-1 :fx] [[2 1]]))
                           vibes-shown
                           [#(assoc-in % [:zone-groups :graphics] {:name "Graphics"})
                            #(assoc-in % [:config :input :trigger-map {:source :midi :channel 0 :note 50}] {:type :zone-step})
                            #(update-in % [:chains :effect-chains [2 1] :items] (fn [items] [{:type :group :enabled? false :items items}]))])
          [missing defined learned grouped] (map lint-rows walk)
          two "Shapes: B3 (Zone pattern) needs two zones (it has one: A)"
          dark "Shapes: B3 (Zone pattern): Graphics has no laser, so the picture vanishes on that step"
          step-key "Shapes: B3 (Zone pattern) steps on the Zone step key, which has no key yet"]
      (readers-agree walk)
      (is (= ["Vibes ⚠ 1" "Vibes" "Vibes" "Vibes"] (mapv (comp :badge peek) walk)))
      (is (every? missing [[:error two] [:warn "Shapes: B3 (Zone pattern) lists Graphics, which no longer exists"] [:info step-key]]))
      (is (every? defined [[:warn dark] [:info step-key]]) "two places now; Graphics has no laser")
      (is (not (defined [:error two])))
      (is (and (learned [:warn dark]) (not (learned [:info step-key]))) "the key learned: the badge stays, a row goes")
      (is (= #{[:warn "Shapes: one cue in HIT repeats on every press"]} grouped) "in a group that is off, the pattern says nothing")))
  (testing "37 §7: a map not in use sends the column's zone B to the crowd: the last HIT goes, an error more; allowed, it is back"
    (let [walk (edit-steps (-> (lint-rig)
                               (assoc-in [:config :vibes :library :vibe-1 :hit :cells] [[0 0]])
                               (assoc-in [:chains :cue-chains [0 0]] {:items [{:type :preset :preset-id :circle}]})
                               (assoc-in [:grid :column-targets 0] :zone/b))
                           vibes-shown
                           [#(assoc-in % [:zone-maps :map/custom-1] {:name "Crowd sweep" :lanes [:auto/left :auto/right]
                                                                     :overrides {:zone/b :crowd}})
                            #(assoc-in % [:config :vibes :library :vibe-1 :allow-audience?] true)])]
      (readers-agree walk)
      (is (= ["Vibes" "Vibes ⚠ 1" "Vibes"] (mapv (comp :badge peek) walk)))
      (is (some #(re-find #"^Shapes: A1 reaches the audience through Zone B \(the Crowd sweep map" (second %)) (lint-rows (second walk))))
      (is (= :hit (:step (nth (:checklist (peek (second walk))) 2))))
      (is (false? (:done? (nth (:checklist (peek (second walk))) 2))) "the checklist: Shapes has no HIT cue to play")))
  (testing "the order and the library, cues that can't play, a virtual projector, a zone's label, the map the show is on"
    (let [walk (edit-steps (-> (lint-rig)
                               (assoc-in [:chains :effect-chains [2 1]] (pattern-cell :zones [:zone/a]))
                               (assoc-in [:chains :effect-chains [3 1]] (pattern-cell :zones [:zone/a :zone/b]))
                               (assoc-in [:config :vibes :library :vibe-1 :fx] [[2 1] [3 1]]))
                           vibes-shown
                           [#(update-in % [:config :vibes :order] conj :vibe-1)
                            #(assoc-in % [:config :vibes :order] [:vibe-1])
                            #(assoc-in % [:config :vibes :order] [:vibe-1 :vibe-2 :gone])
                            #(assoc-in % [:config :vibes :order] [:vibe-1 :vibe-2])
                            #(update-in % [:chains :cue-chains] dissoc [2 0])
                            #(assoc-in % [:chains :cue-chains [2 0]] chasing)
                            #(assoc % :virtual-projectors {:v1 {:name "Aud B" :parent-projector-id :p1 :zone-groups []
                                                                :tags #{:crowd-scanning}}})
                            #(-> % (update-in [:virtual-projectors :v1] dissoc :tags) (assoc-in [:projectors :p1 :tags] #{:crowd-scanning}))
                            #(-> % (update :virtual-projectors dissoc :v1) (update-in [:projectors :p1] dissoc :tags))
                            #(assoc-in % [:zones :labels :zone/a] "Beams")
                            #(assoc-in % [:zone-map :active] :map/unison)
                            #(assoc-in % [:config :vibes :library :vibe-2 :name] "Shapes")])
          rows (mapv lint-rows walk)
          has? (fn [i row] (contains? (rows i) row))]
      (readers-agree walk)
      (is (has? 0 [:error "Shapes: B3 (Zone pattern) needs two zones (it has one: A)"]))
      (doseq [i [1 2 3]]
        (is (has? i [:error "Order and library disagree"]) "a repeat, a vibe left out, an id with no vibe"))
      (is (not (has? 4 [:error "Order and library disagree"])))
      (is (has? 5 [:error "Shapes: A3 can't be played (empty)"]))
      (is (has? 6 [:error "Shapes: A3 can't be played (follows timecode)"]))
      (doseq [i [7 8]]
        (is (some #(re-find #"reaches the audience through All" (second %)) (rows i)) "the virtual projector, then its projector"))
      (is (not-any? #(re-find #"reaches the audience" (second %)) (rows 9)))
      (is (has? 10 [:error "Shapes: B3 (Zone pattern) needs two zones (it has one: A · Beams)"]) "a zone by its label")
      (is (has? 11 [:info "Shapes: B4 (Zone pattern) doesn't move under Unison"]) "the map the show is on")
      (is (has? 12 [:warn "Two vibes are called Shapes"])))))

(deftest playing-cues-sorts-only-on-membership-change-test
  (let [[sorts counted] (counted-calls #'subs/cue-cells-in-grid-order)
        cue (fn [col row cue-name preset-id n timeline?]
              {:col col :row row :name cue-name :first-preset-id preset-id :preset-count n :timeline? timeline?})
        a1 (cue 0 0 "Intro" :circle 3 false)
        a2 (cue 1 0 nil :line 1 false)
        b1 (cue 0 1 nil :star 1 false)
        a3 (cue 2 0 nil :wave 1 false)
        opener (assoc a1 :name "Opener")
        ;; [what, the edit, the list after it (nil: the one before, identical), sorts so far]
        steps (concat
               (for [i (range 20)]
                 [(str "the show clock's stats, " i) #(assoc-in % [:backend :streaming :frame-stats] {:avg-latency-us i}) nil 1])
               [["the grid's selection" #(assoc-in % [:grid :selected-cell] [1 0]) nil 1]
                ["A1 started again" #(assoc-in % [:playback :active-cues [0 0]] {:run #uuid "00000000-0000-0000-0000-000000000004"}) nil 1]
                ["A1 paused" #(assoc-in % [:playback :active-cues [0 0] :paused?] true) nil 1]
                ["A1 renamed" #(assoc-in % [:chains :cue-chains [0 0] :name] "Opener") [opener a2 b1] 1]
                ["a preset more in A2" #(update-in % [:chains :cue-chains [1 0] :items] conj {:type :preset :preset-id :square})
                 [opener (assoc a2 :preset-count 2) b1] 1]
                ["B1 a timeline" #(assoc-in % [:chains :cue-chains [0 1] :editor-type] :timeline)
                 [opener (assoc a2 :preset-count 2) (assoc b1 :timeline? true)] 1]
                ["A3 starts" #(-> %
                                  (assoc-in [:chains :cue-chains [2 0]] {:items [{:type :preset :preset-id :wave}]})
                                  (assoc-in [:playback :active-cues [2 0]] {:run #uuid "00000000-0000-0000-0000-000000000005"}))
                 [opener (assoc a2 :preset-count 2) a3 (assoc b1 :timeline? true)] 2]
                ["A2 stops" #(update-in % [:playback :active-cues] dissoc [1 0]) [opener a3 (assoc b1 :timeline? true)] 3]
                ["Stop holds them" #(after % :transport/stop) [] 3]
                ["Play" #(after % :transport/play) [opener a3 (assoc b1 :timeline? true)] 3]])
        shown (fn [ctx] {:cues (fx/sub-ctx ctx subs/playing-cues) :sorts @sorts})]
    (with-redefs [subs/cue-cells-in-grid-order counted]
      (let [walk (edit-steps playing-state shown (map second steps))
            [_ _ start] (first walk)]
        (is (= [a1 a2 b1] (:cues start)) "row by row: A1, A2, then B1")
        (is (= 1 (:sorts start)))
        (doseq [[[what _ cues sorts] [_ _ before] [_ _ after]] (map vector steps walk (rest walk))]
          (if cues
            (is (= cues (:cues after)) what)
            (is (identical? (:cues before) (:cues after)) what))
          (is (= sorts (:sorts after)) (str what ": sorted again only when the cells in play change")))))))

(defn- fold-state
  "37 §7's fold: one laser, Laser 1, on; Left / Right; and A1's Alternate
   pattern, over A and B. No Graphics group yet."
  []
  (-> (domains/build-initial-state)
      (assoc :projectors {:p1 {:name "Laser 1" :host "10.0.0.1" :enabled? true :zone-groups [] :scan-rate 25000}})
      (update :zone-groups dissoc :graphics)
      (assoc-in [:zone-map :active] :map/left-right)
      (assoc-in [:chains :cue-chains [0 0]] {:items [{:type :preset :preset-id :circle}]
                                             :zone-patterns [(zone-patterns/preset-instance :alternate :cue)]})))

(deftest pattern-fold-keys-on-lane-and-display-routing-test
  (let [fold-text zone-patterns/fold-text
        expected (fn [s zones] (fold-text s {:zones zones} (targets/names (rig/target-groups s) (get-in s [:zones :labels]))))
        [folds counted] (counted-calls #'zone-patterns/fold-text)
        pattern [:chains :cue-chains [0 0] :zone-patterns 0 :params]
        others [[:lane/lasers] [:auto.laser/p1 :graphics]]
        shown (fn [ctx]
                (let [lane (get-in (fx/sub-ctx ctx subs/cue-zone-pattern 0 0) [:params :zones])]
                  {:lane lane
                   :texts (mapv #(fx/sub-ctx ctx subs/pattern-fold %) (cons lane others))
                   :folds @folds}))
        all (inc (count others))
        ;; [what, the edit, the folds it takes]
        steps (concat
               (for [i (range 1 65)]
                 [(str "a rate of " (* 0.125 i)) #(assoc-in % (conj pattern :rate) (* 0.125 i)) 0])
               [["a key learned for Zone step" #(assoc-in % [:config :input :trigger-map {:source :midi :channel 0 :note 50}] {:type :zone-step}) 0]
                ["a zone's label" #(assoc-in % [:zones :labels :zone/a] "Beams") 0]
                ["the pattern's steps" #(assoc-in % (conj pattern :steps) [[:zone/a] [] [:zone/b]]) 0]
                ["the laser's scan rate" #(assoc-in % [:projectors :p1 :scan-rate] 30000) 0]
                ["a switch to Unison waiting for beat 4" #(assoc-in % [:zone-map :pending] {:map :map/unison :at-beat 4.0}) 0]
                ["no switch waiting" #(assoc-in % [:zone-map :pending] nil) 0]
                ["a Map cycle on" #(assoc-in % [:chains :effect-chains [0 3]] {:active? true :items [(zone-patterns/cycle-instance)]}) 0]
                ["the show clock's stats and the grid's selection"
                 #(-> % (assoc-in [:backend :streaming :frame-stats] {:avg-latency-us 9}) (assoc-in [:grid :selected-cell] [0 0])) 0]
                ["the lane reversed" #(assoc-in % (conj pattern :zones) [:zone/b :zone/a]) 1]
                ["the laser renamed" #(assoc-in % [:projectors :p1 :name] "Front") all]
                ["a second laser" #(assoc-in % [:projectors :p2] {:name "Laser 2" :host "10.0.0.2" :enabled? true :zone-groups []}) all]
                ["the rig's order" #(assoc-in % [:rig :order] [:p2 :p1]) all]
                ["a group defined" #(assoc-in % [:zone-groups :graphics] {:name "Graphics"}) all]
                ["a laser in it" #(assoc-in % [:projectors :p2 :zone-groups] [:graphics]) all]
                ["the group renamed" #(assoc-in % [:zone-groups :graphics :name] "Gfx") all]
                ["the group's colour" #(assoc-in % [:zone-groups :graphics :color] "#123456") 0]
                ["a map of yours, not in use" #(assoc-in % [:zone-maps :map/custom-1] {:name "Mine" :lanes [:auto/right :auto/left] :overrides {}}) 0]
                ["that map in use" #(assoc-in % [:zone-map :active] :map/custom-1) all]
                ["its lanes" #(assoc-in % [:zone-maps :map/custom-1 :lanes] [:auto/left :graphics]) all]
                ["its overrides" #(assoc-in % [:zone-maps :map/custom-1 :overrides] {:zone/b :auto/right}) all]
                ["another map of yours" #(assoc-in % [:zone-maps :map/custom-2] {:name "Other" :lanes [:all] :overrides {}}) 0]
                ["Unison due at beat 0" #(assoc-in % [:zone-map :pending] {:map :map/unison :at-beat 0.0}) all]
                ["Unison in use" #(assoc % :zone-map {:active :map/unison :pending nil}) 0]
                ["back to Left / Right" #(assoc-in % [:zone-map :active] :map/left-right) all]
                ["no laser on" #(-> % (assoc-in [:projectors :p1 :enabled?] false) (assoc-in [:projectors :p2 :enabled?] false)) all]])]
    (with-redefs [zone-patterns/fold-text counted]
      (testing "37 §1: 64 rates over the same lane, asked on one context: one fold"
        (let [ctx (context (fold-state))
              params (get-in (fold-state) pattern)
              texts (mapv #(fx/sub-ctx ctx subs/pattern-fold (:zones (assoc params :rate (* 0.125 %)))) (range 1 65))]
          (is (= 1 @folds))
          (is (= #{"This rig (1 laser, Left / Right): A is its left half, B its right half."} (set texts)))))
      (reset! folds 0)
      (testing "successive states: one fold for each lane and each change to what the line reads, the full state's text"
        (let [walk (edit-steps (fold-state) shown (map second steps))
              [_ _ start] (first walk)
              text-after (fn [what] (some (fn [[[w] [_ _ {:keys [texts]}]]] (when (= w what) (first texts))) (map vector steps (rest walk))))]
          (is (= all (:folds start)) "one a lane")
          (is (= (mapv #(expected (fold-state) %) (cons [:zone/a :zone/b] others)) (:texts start)))
          (is (= "This rig (1 laser, Left / Right): A is its left half, B its right half." (first (:texts start))))
          (doseq [[[what _ cost] [_ _ before] [s _ after]] (map vector steps walk (rest walk))]
            (is (= cost (- (:folds after) (:folds before))) what)
            (is (= (mapv #(expected s %) (cons (:lane after) others)) (:texts after)) what))
          (is (= "This rig (1 laser, Left / Right): B is its right half, A its left half." (text-after "the lane reversed")))
          (is (= "Under Unison every zone is All, so this pattern doesn't move." (text-after "Unison due at beat 0")))
          (is (= "No laser is on, so this pattern has nowhere to send anything." (text-after "no laser on"))))))))

(deftest target-groups-order-test
  (let [s (-> base-state
              (assoc :projectors {:r {:name "Laser R" :host "10.0.0.7"}
                                  :l {:name "Laser L" :host "10.0.0.5"}
                                  :off {:name "Spare" :host "10.0.0.9" :enabled? false}})
              (assoc :zone-groups {:graphics {:name "Graphics" :color "#9B59B6"}
                                   :group-balcony {:name "Balcony" :color "#123456"}
                                   :left {:name "Left (manual)" :color "#4A90D9"}}))
        groups (fx/sub-ctx (context s) subs/target-groups)]
    (is (= [:all :auto/left :auto/right :auto/center :auto/odd :auto/even
            :auto.laser/l :auto.laser/r :auto.laser/off
            :group-balcony :graphics :left]
           (mapv :id groups))
        "All; the automatic groups; the lasers in rig order; your groups by name")
    (is (= [:all :auto :auto :auto :auto :auto :laser :laser :laser :custom :custom :custom] (mapv :kind groups)))
    (is (= ["All" "Left" "Right" "Center" "Odd" "Even" "Laser L" "Laser R" "Spare (off)" "Balcony" "Graphics" "Left (manual)"]
           (mapv :name groups))
        "a laser that is off says so")
    (is (= {:id :auto/left :name "Left" :color "#4A90D9" :kind :auto} (select-keys (second groups) [:id :name :color :kind])))
    (is (= "#4A6FA5" (:color (nth groups 6))))
    (is (= (nth groups 6) (get (fx/sub-ctx (context s) subs/target-groups-by-id) :auto.laser/l)))
    (testing "a show that still stores All lists it once, as the built-in"
      (is (= 1 (count (filter #(= :all (:id %))
                              (fx/sub-ctx (context (assoc-in s [:zone-groups :all] {:name "All"})) subs/target-groups)))))))
  (testing "the pickers' All is the built-in one, listed once"
    (is (= [:all] (mapv :id (take 1 (fx/sub-ctx (context base-state) subs/target-groups)))))))

(deftest rig-cards-test
  (let [s (-> base-state
              (assoc :projectors {:l {:name "Laser L" :host "10.0.0.5" :service-id 1 :stable-id [:unit "0A 1B" 1] :enabled? true}
                                  :aud {:name "Aud" :host "10.0.0.12" :service-id 1 :enabled? true :exclude-from-all? true
                                        :tags #{:crowd-scanning}}
                                  :r {:name "Laser R" :host "10.0.0.7" :service-id 2 :enabled? true}
                                  :spare {:name "Spare" :host "10.0.0.9" :enabled? false}})
              (assoc-in [:projector-ui :active-projector] :r))
        cards (fx/sub-ctx (context s) subs/rig-cards)]
    (is (= [:l :r :spare :aud] (mapv :id cards)) "in rig order: by IP as numbers")
    (is (= [1 2 nil nil] (mapv :position cards)) "the number is the place in E: none for one off or excluded")
    (is (= ["Laser L" "Laser R" "Spare" "Aud"] (mapv :name cards)))
    (is (= ["10.0.0.5 #1" "10.0.0.7 #2" "10.0.0.9" "10.0.0.12 #1"] (mapv :host-label cards)))
    (is (= [false true false false] (mapv :selected? cards)))
    (is (= [true true false true] (mapv :enabled? cards)))
    (is (= [false false false true] (mapv :excluded? cards)))
    (is (= #{:crowd-scanning} (:tags (last cards))))
    (is (= "Unit 0A 1B · output 1" (:identity (first cards))))
    (is (every? :found? cards) "before any scan, no card says it wasn't found")
    (testing "after a scan, a laser it didn't find says so"
      (let [scanned (-> s
                        (assoc-in [:rig-ui :scanned?] true)
                        (assoc-in [:projector-ui :discovered-devices] [{:address "10.0.0.5" :services [{:service-id 1}]}
                                                                        {:address "10.0.0.9" :services []}]))]
        (is (= [true false true false] (mapv :found? (fx/sub-ctx (context scanned) subs/rig-cards))))))
    (testing "the outputs of one device know of each other"
      (let [two (assoc-in s [:projectors :l2] {:name "Laser L2" :host "10.0.0.5" :service-id 2 :enabled? true})]
        (is (= {:l true :l2 true :r false}
               (into {} (keep #(when (#{:l :l2 :r} (:id %)) [(:id %) (:multi-output? %)]))
                     (fx/sub-ctx (context two) subs/rig-cards))))))))

(deftest active-projector-is-nil-for-a-dangling-id-test
  ;; Reactivity plan 14: an undo took the selected projector away
  (let [s (assoc base-state :projectors {:r {:name "Laser R" :host "10.0.0.7"}})
        active #(fx/sub-ctx (context (assoc-in s [:projector-ui :active-projector] %)) subs/active-projector)]
    (is (= {:id :r :name "Laser R" :host "10.0.0.7"} (active :r)))
    (is (nil? (active :gone)) "no projector, so the inspector shows none")
    (is (nil? (active nil)))
    (testing "with no projectors at all"
      (is (nil? (fx/sub-ctx (context (assoc-in base-state [:projector-ui :active-projector] :gone)) subs/active-projector))))))

(defn- matrix [projectors & {:keys [middle zone-groups]}]
  (fx/sub-ctx (context (cond-> (assoc base-state :projectors projectors)
                         middle (assoc-in [:rig :middle] middle)
                         zone-groups (assoc :zone-groups zone-groups)))
              subs/rig-matrix))

(defn- column [m id]
  (into {} (map (fn [row] [(:id row) (get-in row [:cells id])])) (:rows m)))

(deftest rig-matrix-marks-test
  (testing "one laser: Left and Odd are its left half, Right and Even its right half"
    (let [m (matrix {:p {:name "P" :host "10.0.0.1" :zone-groups []}})]
      (is (= [:p] (:columns m)))
      (is (= [:all :auto/left :auto/right :auto/center :auto/odd :auto/even :each-laser :crowd :graphics]
             (mapv :id (:rows m)))
          "All, the automatic groups, each laser, then your groups by name")
      (is (= {:all :whole :auto/left :left-half :auto/right :right-half :auto/center :whole :auto/odd :left-half
              :auto/even :right-half :each-laser :whole :crowd :not-member :graphics :not-member}
             (column m :p)))
      (is (= [:all :auto :auto :auto :auto :auto :laser :custom :custom] (mapv :kind (:rows m))))
      (is (= ["All" "Left" "Right" "Center" "Odd" "Even" "Each laser" "Crowd Scanning" "Graphics"] (mapv :name (:rows m))))))
  (testing "three lasers, the middle one split; one off and one excluded from All"
    (let [m (matrix {:a {:name "A" :host "10.0.0.1"} :b {:name "B" :host "10.0.0.2"} :c {:name "C" :host "10.0.0.3"}
                     :off {:name "Off" :host "10.0.0.4" :enabled? false :zone-groups [:graphics]}
                     :aud {:name "Aud" :host "10.0.0.5" :exclude-from-all? true :zone-groups [:crowd]}})]
      (is (= [:a :b :c :off :aud] (:columns m)))
      (is (= {:all :whole :auto/left :whole :auto/right :not-in :auto/center :not-in :auto/odd :whole :auto/even :not-in
              :each-laser :whole :crowd :not-member :graphics :not-member}
             (column m :a)))
      (is (= {:all :whole :auto/left :left-half :auto/right :right-half :auto/center :whole :auto/odd :not-in
              :auto/even :whole :each-laser :whole :crowd :not-member :graphics :not-member}
             (column m :b))
          "the middle laser: ◧ in Left, ◨ in Right")
      (is (= {:all :off :auto/left :off :auto/right :off :auto/center :off :auto/odd :off :auto/even :off
              :each-laser :off :crowd :not-member :graphics :member}
             (column m :off))
          "a laser that is off: – in All and the automatic rows; its own groups still say what it's in")
      (is (= {:all :excluded :auto/left :excluded :auto/right :excluded :auto/center :excluded :auto/odd :excluded
              :auto/even :excluded :each-laser :whole :crowd :member :graphics :not-member}
             (column m :aud))
          "excluded: × in All and the automatic rows (D-33), but it has its own group")))
  (testing "the middle in both: whole in both"
    (let [m (matrix {:a {:host "10.0.0.1"} :b {:host "10.0.0.2"} :c {:host "10.0.0.3"}} :middle :both)]
      (is (= [:whole :whole] ((juxt :auto/left :auto/right) (column m :b)))))))

(deftest zone-group-usage-counts-derived-refs-test
  (let [s (-> base-state
              (assoc :projectors {:a {:name "A" :host "10.0.0.1"} :b {:name "B" :host "10.0.0.2"}
                                  :off {:name "Off" :host "10.0.0.3" :enabled? false :zone-groups [:graphics]}})
              (assoc-in [:chains :cue-chains [2 0]] {:target :auto/left
                                                     :items [{:type :preset :preset-id :line}]})
              (assoc-in [:chains :cue-chains [3 0]] {:target :graphics
                                                     :items [{:type :preset :preset-id :line :target :auto.laser/b}]}))
        usage #(fx/sub-ctx (context s) subs/zone-group-usage %)]
    (is (= [1 1] ((juxt :cue-count :projector-count) (usage :auto/left))) "a cue names Left; Left reaches the left laser")
    (is (= 2 (:projector-count (usage :all))) "All reaches the lasers that are on")
    (is (= [1 1] ((juxt :cue-count :projector-count) (usage :auto.laser/b))) "pinned to one laser")
    (is (= [1 1] ((juxt :cue-count :projector-count) (usage :graphics))) "your groups count their members, as before")
    (testing "the tab's list counts the lasers each group reaches"
      (is (= {:all 2 :auto/left 1 :auto/center 2 :auto.laser/a 1 :auto.laser/off 0 :graphics 0 :crowd 0}
             (select-keys (into {} (map (juxt :id :count)) (fx/sub-ctx (context s) subs/zone-groups-tab-list))
                          [:all :auto/left :auto/center :auto.laser/a :auto.laser/off :graphics :crowd]))
          "Graphics' one member is off"))
    (testing "the lasers of a group, in rig order, with their marks"
      (is (= [[:a 1 :whole] [:b 2 :not-in] [:off nil :off]]
             (mapv (juxt :id :position :mark) (:lasers (fx/sub-ctx (context s) subs/group-members :auto/left)))))
      (is (= [:not-member :not-member :member]
             (mapv :mark (:lasers (fx/sub-ctx (context s) subs/group-members :graphics))))))))

(deftest vibe-audience-groups-follow-the-order-test
  (let [s (-> base-state
              (templates/apply-starter-vibes)
              (assoc-in [:chains :cue-chains] {[0 0] {:items [{:type :preset :preset-id :circle}]
                                                      :target :auto/left}
                                               [1 0] {:items [{:type :preset :preset-id :square}]
                                                      :target :auto/right}})
              (assoc :projectors {:aud {:name "Aud" :host "10.0.0.1" :zone-groups [] :tags #{:crowd-scanning}}
                                  :main {:name "Main" :host "10.0.0.2" :zone-groups []}}))
        ctx (context s)
        blocked (fn [ctx] [(fx/sub-ctx ctx subs/vibe-cell-blocked 0 0) (fx/sub-ctx ctx subs/vibe-cell-blocked 1 0)])]
    (is (= [:crowd nil] (blocked ctx)) "the scanner is the left laser: the cue on Left reaches the audience")
    (is (= [nil :crowd] (blocked (fx/swap-context ctx assoc-in [:rig :order] [:main :aud])))
        "moved to the right end, the mark moves to the cue on Right")))


;; Zones plan Z3: what the fit guard did, for the cards and the toolbar

(deftest low-fps-projectors-test
  (let [load (fn [guard-fps reduced] {:points-in 3000 :points 1800 :budget 1250 :min-fps 20 :scan-rate 25000
                                      :est-fps 8 :guard-fps guard-fps :reduced-pct reduced :dim-pct 0 :regions 0})
        state (-> (domains/build-initial-state)
                  (assoc :projectors {:p1 {:name "Laser L"} :p2 {:name "Laser C"} :p3 {} :p4 {:name "Laser X"}})
                  (assoc-in [:backend :streaming :status :projectors]
                            {:p1 {:state :streaming :fps 19.9 :low? true :load (load 16 40)}
                             :p2 {:state :streaming :fps 30.0 :low? false :load (load 25 0)}
                             :p3 {:state :streaming :fps 13.9 :low? true :load (load 14 40)}
                             :p4 {:state :error :error "gone"}}))
        ctx (context state)]
    (is (= [{:id :p3 :name "p3" :fps 14 :min-fps 20 :reduced-pct 40}
            {:id :p1 :name "Laser L" :fps 16 :min-fps 20 :reduced-pct 40}]
           (fx/sub-ctx ctx subs/low-fps-projectors))
        "the lasers still low after the guard, the lowest first, named (by id without a name)")
    (is (= {:load (load 16 40) :low? true} (fx/sub-ctx ctx subs/projector-load :p1)))
    (is (nil? (fx/sub-ctx ctx subs/projector-load :p4)) "not streaming: no load")
    (is (= [] (fx/sub-ctx (context (domains/build-initial-state)) subs/low-fps-projectors)) "nothing streaming")))

(deftest cue-warning-sub-test
  (let [ring #uuid "00000000-0000-0000-0000-0000000000a1"
        named #uuid "00000000-0000-0000-0000-0000000000a2"
        warning {:projector :p3 :points 1750 :fps 14 :min-fps 20 :scan-rate 25000 :item {:id ring :points 1200}}
        state (-> (domains/build-initial-state)
                  (assoc :projectors {:p3 {:name "Laser R"}})
                  (assoc-in [:chains :cue-chains [2 0]] {:items [{:id ring :type :preset :preset-id :circle}
                                                                 {:id named :type :preset :preset-id :wave :name "Swell"}]})
                  (assoc-in [:backend :streaming :cue-warnings] {[2 0] warning}))
        ctx (context state)]
    (is (= (assoc warning :laser "Laser R" :item-name "Circle") (fx/sub-ctx ctx subs/cue-warning 2 0))
        "with the laser's name, and its heaviest item's (a preset's)")
    (is (nil? (fx/sub-ctx ctx subs/cue-warning 3 0)) "a cue with none")
    (is (= "Swell" (:item-name (fx/sub-ctx (context (assoc-in state [:backend :streaming :cue-warnings [2 0] :item :id] named))
                                           subs/cue-warning 2 0)))
        "an item's own name first")
    (is (not (contains? (fx/sub-ctx (context (update-in state [:backend :streaming :cue-warnings [2 0]] dissoc :item))
                                    subs/cue-warning 2 0)
                        :item-name))
        "no item counts (its editor is closed): no item")))

;; Projectors with another output driver (output drivers D2)

(def ^:private bridge
  {:name "Bridge" :driver :lsps :driver-config {:host "127.0.0.1" :port 7266}
   :stable-id [:manual :lsps :b] :enabled? true})

(defn- with-drivers [s]
  (assoc-in s [:backend :output :drivers] (laser-show.output.driver/descriptors)))

(deftest rig-cards-show-driver-summary-test
  (let [s (-> base-state
              with-drivers
              (assoc :projectors {:l {:name "Laser L" :host "10.0.0.5" :service-id 1 :enabled? true}
                                  :b bridge
                                  :b2 (assoc bridge :name "Bridge 2" :stable-id [:manual :lsps :b2])
                                  :nova {:name "Nova" :driver :acme/nova :driver-config {:serial "NV-00123"}
                                        :stable-id [:device :acme/nova "NV-00123" 0] :enabled? false}}))
        by-id #(into {} (map (juxt :id %)) (fx/sub-ctx (context %2) subs/rig-cards))]
    (is (= {:l "10.0.0.5 #1" :b "LSPS · 127.0.0.1:7266" :b2 "LSPS · 127.0.0.1:7266" :nova "acme/nova · not installed"}
           (by-id :host-label s))
        "the address line is driver/summary")
    (is (= {:l :idn :b :lsps :b2 :lsps :nova :acme/nova} (by-id :driver s)))
    (is (= {:l ["10.0.0.5 #1" nil] :b ["LSPS" "127.0.0.1:7266"] :b2 ["LSPS" "127.0.0.1:7266"]
            :nova ["acme/nova" "not installed"]}
           (by-id :address-lines s))
        "on two lines for another driver")
    (is (= {:l false :b false :b2 false :nova false} (by-id :multi-output? s))
        "lasers without a host aren't one device's outputs")
    (is (= {:l true :b true :b2 true :nova true} (by-id :identifiable? s)))
    (is (= "Added by hand: a scan doesn't look for it" (:b (by-id :identity s))))
    (is (= "Device NV-00123 · output 0" (:nova (by-id :identity s))))
    (testing "after a scan, a driver without discovery or one that isn't installed is never marked missing"
      (let [scanned (-> s
                        (assoc-in [:rig-ui :scanned?] true)
                        (assoc-in [:projector-ui :discovered-devices] [{:address "10.0.0.9" :services []}]))]
        (is (= {:l false :b true :b2 true :nova true} (by-id :found? scanned)))))))

(deftest add-manual-form-sub-test
  (let [form #(fx/sub-ctx (context (assoc-in (with-drivers base-state) [:ui :dialogs :add-projector-manual] %)) subs/add-manual-form)]
    (is (= [:idn :lsps] (mapv :id (fx/sub-ctx (context (with-drivers base-state)) subs/add-manual-types))) "IDN first")
    (is (= {:driver :idn :can-add? false} (form {:open? true})))
    (is (= {:driver :idn :can-add? true} (form {:open? true :host "10.0.0.5"})))
    (let [{:keys [can-add? errors config fields]} (form {:open? true :driver :lsps})]
      (is (false? can-add?))
      (is (= {:host "Host is required"} errors))
      (is (= {:port 7266 :max-packet-bytes 1400} config))
      (is (= [:host :port :max-packet-bytes] (mapv :key fields))))
    (is (true? (:can-add? (form {:open? true :driver :lsps :driver-config {:host "127.0.0.1"}}))))
    (is (= {:port "Port must be 1–65535"}
           (:errors (form {:open? true :driver :lsps :driver-config {:host "127.0.0.1" :port "99999"}}))))
    (is (false? (:can-add? (form {:open? true :driver :acme/gone :driver-config {:host "h"}}))) "not installed")
    (testing "the name an unnamed one gets (the Name field's prompt)"
      (is (= "LSPS 1" (:default-name (form {:open? true :driver :lsps}))))
      (is (= "LSPS 2" (:default-name (fx/sub-ctx (context (-> (with-drivers base-state)
                                                                (assoc :projectors {:b (assoc bridge :name "LSPS 1")})
                                                                (assoc-in [:ui :dialogs :add-projector-manual] {:open? true :driver :lsps})))
                                                 subs/add-manual-form))))
      (is (nil? (:default-name (form {:open? true}))) "IDN is named after its host"))))

(deftest projector-driver-sub-test
  (let [s (-> base-state with-drivers
              (assoc :projectors {:l {:name "Laser L" :host "10.0.0.5"} :b bridge
                                  :nova {:name "Nova" :driver :acme/nova :driver-config {:serial "NV-00123"}}}))
        info #(fx/sub-ctx (context s) subs/projector-driver %)]
    (is (= {:driver :idn :idn? true :installed? true :name "IDN (ILDA Digital Network)"}
           (select-keys (info :l) [:driver :idn? :installed? :name])))
    (is (= {:driver :lsps :idn? false :installed? true :name "LSPS point stream (UDP)"
            :config {:host "127.0.0.1" :port 7266 :max-packet-bytes 1400} :summary "LSPS · 127.0.0.1:7266"}
           (select-keys (info :b) [:driver :idn? :installed? :name :config :summary])))
    (is (= {:driver :acme/nova :installed? false :name "acme/nova" :config {:serial "NV-00123"} :fields []}
           (select-keys (info :nova) [:driver :installed? :name :config :fields]))
        "kept as it is, read only")
    (is (nil? (info :nope)))))

(deftest projector-driver-info-sub-test
  (is (= "buffer-ms 40 · interlock closed · lit? true · rate 30.5 · scan-rate 30303"
         (subs/driver-info-text {:scan-rate 30303 :buffer-ms 40 :interlock :closed :lit? true :rate 30.5
                                 :fifo [1 2] :nested {:a 1} :gone nil}))
      "plain keys and values, by key; nested ones and nil left out")
  (is (nil? (subs/driver-info-text {:fifo [1 2]})))
  (is (nil? (subs/driver-info-text nil)))
  (let [s (assoc-in base-state [:backend :streaming :status :projectors :b]
                    {:state :streaming :fps 59.8 :warning "Interlock open" :driver-info {:scan-rate 30303.0}})]
    (is (= "scan-rate 30303" (fx/sub-ctx (context s) subs/projector-driver-info :b)) "a whole double as a whole number")
    (is (nil? (fx/sub-ctx (context s) subs/projector-driver-info :a)))))


;; Settings → Add-ons (plans/2026-11-19-output-drivers/02 § D3)


(deftest addon-rows-test
  (let [statuses [{:id :test/sample :name "Sample add-on" :version "1.0.0" :source "/Users/zoë/LaserShowProjects/addons/sample.jar"
                   :state :loaded :drivers [:test/sample-stream :test/gone] :load-ms 12}
                  {:id :test/broken :name "Broken add-on" :version "1.0.0" :source "C:\\Users\\Zoë Smith\\LaserShowProjects\\addons\\broken.jar"
                   :state :failed :error "Could not load acme.driver: boom"}
                  {:source "/addons/garbage.jar" :state :failed :error "Can't read garbage.jar: zip END header not found"}
                  {:id :test/windows-only :version "2.0" :source "/addons/win.jar" :state :skipped
                   :error "Not available on macOS (aarch64); it supports Windows"}
                  {:id :dev/nova :name "Nova (dev)" :source "jar:file:/Users/z/.m2/nova.jar!/laser-show-addon.edn" :state :loaded :drivers []}]
        drivers {:test/sample-stream {:id :test/sample-stream :name "Sample add-on stream"}}
        ctx (context (-> base-state
                         (assoc-in [:backend :output :addons] statuses)
                         (assoc-in [:backend :output :drivers] drivers)
                         (assoc-in [:backend :output :dir] "/Users/zoë/LaserShowProjects/addons")
                         (assoc-in [:backend :output :pending-installs]
                                   [{:id :acme/nova :name "Acme Nova" :version "0.3.0" :file "/Users/zoë/LaserShowProjects/addons/acme.jar.new" :replaced? false}
                                    {:id :acme/x :file "C:\\addons\\x.jar.new" :replaced? true}])))]
    (is (= [{:title "Sample add-on 1.0.0" :state :loaded :state-text "Loaded"
             :drivers "Sample add-on stream (:test/sample-stream), :test/gone" :file "sample.jar"}
            {:title "Broken add-on 1.0.0" :state :failed :state-text "Failed: Could not load acme.driver: boom"
             :drivers nil :file "broken.jar"}
            {:title "garbage.jar" :state :failed :state-text "Failed: Can't read garbage.jar: zip END header not found"
             :drivers nil :file "garbage.jar"}
            {:title ":test/windows-only 2.0" :state :skipped
             :state-text "Skipped: Not available on macOS (aarch64); it supports Windows" :drivers nil :file "win.jar"}
            {:title "Nova (dev)" :state :loaded :state-text "Loaded" :drivers nil :file "nova.jar"}]
           (fx/sub-ctx ctx subs/addon-rows)))
    (is (= [{:title "Acme Nova 0.3.0" :state-text "Installed, loads at next start" :file "acme.jar.new"}
            {:title ":acme/x" :state-text "Installed, loads at next start" :file "x.jar.new"}]
           (fx/sub-ctx ctx subs/pending-install-rows)))
    (is (= "/Users/zoë/LaserShowProjects/addons" (fx/sub-ctx ctx subs/addons-dir)))
    (testing "before startup has said: nothing"
      (let [ctx (context base-state)]
        (is (= [] (fx/sub-ctx ctx subs/addon-rows)))
        (is (= [] (fx/sub-ctx ctx subs/pending-install-rows)))))))


;; Grid badges (zones plan Z4, 06 §8.5)

(def ^:private badge-state
  (-> (domains/build-initial-state)
      (assoc-in [:chains :cue-chains]
                {[0 0] {:items [{:type :preset :id :p1 :preset-id :circle :target :zone/a}
                                {:type :preset :id :p2 :preset-id :wave :target :zone/b}]}
                 [1 0] {:items [{:type :preset :id :p3 :preset-id :circle :target :auto/left}
                                {:type :preset :id :p4 :preset-id :star :target :auto/right}]}
                 [2 0] {:items [{:type :preset :id :p5 :preset-id :circle}]}
                 [3 0] {:items [{:type :preset :id :p6 :preset-id :circle}
                                {:type :preset :id :p7 :preset-id :wave :target :zone/c :enabled? false}]}
                 [4 0] {:items [{:type :group :id :g :target [:zone/a :zone/b]
                                 :items [{:type :preset :id :p8 :preset-id :circle}]}]}
                 [5 0] {:items [{:type :group :id :off :enabled? false :target :zone/d
                                 :items [{:type :preset :id :p9 :preset-id :circle}]}
                                {:type :preset :id :p10 :preset-id :wave :target :zone/a}
                                {:type :preset :id :p11 :preset-id :star :target :graphics}]}})
      (assoc-in [:grid :column-targets] {2 :zone/a})
      (assoc-in [:zones :labels :zone/a] "Beams")))

(defn- badge-of [ctx col row]
  (some-> (fx/sub-ctx ctx subs/cell-target-badge col row) (select-keys [:zones :pins :inherited? :from])))

(deftest cell-badge-sub-test
  (let [ctx (context badge-state)
        tip #(fx/sub-ctx ctx subs/cell-target-tooltip %1 %2)]
    (testing "zones by letter; the tooltip names their lanes in the map in use"
      (is (= {:zones "A B" :pins nil :inherited? false} (badge-of ctx 0 0)))
      (is (= "A · Beams → Left · B → Right (map: Left / Right)" (tip 0 0))))
    (testing "pinned"
      (is (= {:zones nil :pins "L/R" :inherited? false} (badge-of ctx 1 0)))
      (is (= "Pinned: ignores the zone map" (tip 1 0))))
    (testing "all of it inherited from its column: dim, and the tooltip says from where"
      (is (= {:zones "A" :pins nil :inherited? true :from "from column 3"} (badge-of ctx 2 0)))
      (is (= "A · Beams → Left (map: Left / Right) · from column 3" (tip 2 0))))
    (testing "All: no badge; a switched-off item doesn't count"
      (is (nil? (badge-of ctx 3 0)))
      (is (nil? (tip 3 0))))
    (testing "a union"
      (is (= {:zones "A+B" :pins nil :inherited? false} (badge-of ctx 4 0)))
      (is (= "A + B → Left + Right (map: Left / Right)" (tip 4 0))))
    (testing "a zone and a group; a switched-off group's content doesn't count"
      (is (= {:zones "A" :pins "Graph…" :inherited? false} (badge-of ctx 5 0)))
      (is (= "A · Beams → Left (map: Left / Right) · Graphics (pinned)" (tip 5 0))))
    (testing "an empty cell"
      (is (nil? (badge-of ctx 6 0)))
      (is (nil? (tip 6 0))))))

(deftest map-switch-rerenders-only-zone-badges-test
  (let [ctx (context badge-state)
        cells [[0 0] [1 0] [2 0] [3 0] [4 0] [5 0] [6 0]]
        read (fn [ctx]
               (into {} (for [[c r] cells]
                          [[c r] {:badge (fx/sub-ctx ctx subs/cell-target-badge c r)
                                  :tip (fx/sub-ctx ctx subs/cell-target-tooltip c r)
                                  :display (fx/sub-ctx ctx subs/cell-display-data c r)}])))
        before (read ctx)
        after (read (fx/swap-context ctx assoc-in [:zone-map :active] :map/odd-even))]
    (testing "a switch leaves every badge and every cell's display data as it was (identical?)"
      (doseq [cell cells]
        (is (identical? (get-in before [cell :badge]) (get-in after [cell :badge])) (str cell))
        (is (identical? (get-in before [cell :display]) (get-in after [cell :display])) (str cell))))
    (testing "only the tooltips of badges with a zone change"
      (is (identical? (get-in before [[1 0] :tip]) (get-in after [[1 0] :tip])) "pinned: it doesn't read the map")
      (is (= "A · Beams → Odd · B → Even (map: Odd / Even)" (get-in after [[0 0] :tip])))
      (is (= "A + B → Odd + Even (map: Odd / Even)" (get-in after [[4 0] :tip]))))))


;; Zone map pads (zones plan Z4, 06 §7.4)

(deftest map-pad-sub-test
  (let [key48 {:source :midi :channel 0 :note 48}
        s (-> (domains/build-initial-state)
              (assoc-in [:zone-maps :map/custom-1] {:name "Crowd sweep" :lanes [:auto/left :crowd] :overrides {}})
              (assoc-in [:zone-map :active] :map/odd-even)
              (assoc-in [:zone-map :pending] {:id 3 :map :map/unison :at-beat 8.0 :bar? true})
              (assoc-in [:config :zone-map-pads :map/custom-1 :bar?] true)
              (assoc-in [:config :input :trigger-map key48] {:type :zone-map :target :map/left-right})
              (waiting-to-learn {:type :zone-map :target :map/custom-1}))
        ctx (context s)
        pad #(fx/sub-ctx ctx subs/map-pad %)]
    (is (= [:map/left-right :map/odd-even :map/unison :map/custom-1] (fx/sub-ctx ctx subs/map-pad-ids))
        "the built-ins, then yours")
    (is (= {:id :map/left-right :name "Left / Right" :active? false :pending nil :bar? false :learning? false
            :bindings [key48]}
           (pad :map/left-right)))
    (is (= {:id :map/odd-even :name "Odd / Even" :active? true :pending nil :bar? false :learning? false :bindings []}
           (pad :map/odd-even)))
    (is (= :bar (:pending (pad :map/unison))) "waiting for the bar")
    (is (= {:name "Crowd sweep" :bar? true :learning? true} (select-keys (pad :map/custom-1) [:name :bar? :learning?])))
    (testing "an active id that is no map: the pads show Left / Right"
      (is (:active? (fx/sub-ctx (context (assoc-in s [:zone-map :active] :map/gone)) subs/map-pad :map/left-right))))
    (testing "a pending switch to the beat: :beat"
      (is (= :beat (:pending (fx/sub-ctx (context (assoc-in s [:zone-map :pending :bar?] false)) subs/map-pad :map/unison)))))
    (testing "booleans and keys only: where the pending switch lands isn't in it"
      (let [ctx' (fx/swap-context ctx assoc-in [:zone-map :pending :at-beat] 12.0)]
        (is (= (pad :map/unison) (fx/sub-ctx ctx' subs/map-pad :map/unison)))))))


;; The pattern pads and the Map cycle (zones plan Z5, 07 §7.3, §7.4)

(deftest pattern-pad-sub-test
  (let [key40 {:source :midi :channel 0 :note 40}
        key41 {:source :midi :channel 0 :note 41}
        s (-> (domains/build-initial-state)
              (assoc :pattern-pads {:style :chase :pending {:id 3 :style :off :at-beat 33.0 :bar? false}})
              (assoc-in [:config :pattern-pads :random :bar?] true)
              (assoc-in [:config :input :trigger-map key40] {:type :pattern-pad :target :chase})
              (assoc-in [:config :input :trigger-map key41] {:type :zone-step})
              (waiting-to-learn {:type :pattern-pad :target :swap}))
        ctx (context s)
        pad #(fx/sub-ctx ctx subs/pattern-pad %)]
    (is (= {:id :chase :name "Chase" :active? true :pending nil :bar? false :learning? false :bindings [key40]}
           (pad :chase)))
    (is (= {:id :off :name "Off" :active? false :pending :beat :bar? false :learning? false :bindings []}
           (pad :off))
        "a switch to Off waits for the beat")
    (is (= {:id :as-set :name "As set" :active? false :pending nil :bar? false :learning? false :bindings []}
           (pad :as-set)))
    (is (:bar? (pad :random)) "Switch on the bar")
    (is (:learning? (pad :swap)))
    (testing "As set is the one in use while the pads set no style"
      (is (:active? (fx/sub-ctx (context (assoc s :pattern-pads {:style nil :pending nil})) subs/pattern-pad :as-set))))
    (testing "a switch back to As set waits on As set"
      (is (= :bar (:pending (fx/sub-ctx (context (assoc s :pattern-pads {:style :chase :pending {:id 4 :style nil :at-beat 36.0 :bar? true}}))
                                        subs/pattern-pad :as-set)))))
    (testing "STEP: its keys, and learning one"
      (is (= {:bindings [key41] :learning? false} (fx/sub-ctx ctx subs/step-pad)))
      (is (:learning? (fx/sub-ctx (context (waiting-to-learn s {:type :zone-step})) subs/step-pad))))
    (testing "booleans and keys only: where the waiting switch lands isn't in it"
      (let [ctx' (fx/swap-context ctx assoc-in [:pattern-pads :pending :at-beat] 40.0)]
        (is (= (pad :off) (fx/sub-ctx ctx' subs/pattern-pad :off)))))))

(deftest zone-patterns-in-use-test
  (let [s (domains/build-initial-state)
        in-use? #(fx/sub-ctx (context %) subs/zone-patterns-in-use?)
        cue-pattern (assoc-in s [:chains :cue-chains [0 0]] {:items [] :zone-patterns [(zone-patterns/preset-instance :alternate :cue)]})]
    (is (false? (in-use? s)) "a new show has none")
    (is (true? (in-use? cue-pattern)) "a cue's")
    (is (true? (in-use? (assoc-in s [:chains :effect-chains [1 1]] {:active? false :items [(zone-patterns/preset-instance :chase :cell)]})))
        "a cell's, off too: it can be switched on")
    (is (false? (in-use? (assoc-in s [:chains :effect-chains [1 1]] {:active? true :items [(zone-patterns/cycle-instance)]})))
        "a Map cycle is no pattern: the pattern pads don't switch it")
    (is (false? (in-use? (assoc-in s [:chains :effect-chains [1 1]] {:active? true :items [{:effect-id :scale :params {}}]}))))
    (testing "the map pads show with a pattern or a Map cycle, zones or not"
      (is (false? (fx/sub-ctx (context s) subs/zone-map-pads-shown?)))
      (is (true? (fx/sub-ctx (context cue-pattern) subs/zone-map-pads-shown?)))
      (is (true? (fx/sub-ctx (context (assoc-in s [:chains :effect-chains [1 1]] {:active? false :items [(zone-patterns/cycle-instance)]}))
                             subs/zone-map-pads-shown?))))
    (testing "stored, though disabled: the pads show for what can be switched back on (plan 11 §7)"
      (let [nested (assoc-in s [:chains :effect-chains [1 1]]
                             {:active? true :items [{:type :group :items [{:type :group :enabled? false
                                                                           :items [(zone-patterns/preset-instance :chase :cell)
                                                                                   {:effect-id :scale}]}]}]})
            cycle (assoc-in s [:chains :effect-chains [1 1]] {:active? true :items [{:effect-id :zone-map-cycle :enabled? false}]})
            gone (update-in cycle [:chains :effect-chains] dissoc [1 1])
            pads (fn [s] {:pattern-pads? (in-use? s) :map-pads? (fx/sub-ctx (context s) subs/zone-map-pads-shown?)})]
        (is (= {:pattern-pads? true :map-pads? true} (pads nested)) "a pattern in a disabled group")
        (is (= {:pattern-pads? false :map-pads? true} (pads cycle)) "a disabled Map cycle instead: the map pads only")
        (is (false? (:on? (fx/sub-ctx (context cycle) subs/map-cycle-state))) "and CYCLE stays off: it doesn't run")
        (is (= {:pattern-pads? false :map-pads? false} (pads gone)) "the last one gone: neither")))))

(deftest pattern-only-zone-catalogue-follows-edits-in-either-grid-test
  ;; plans/2026-11-23-cljfx-reactivity/28 §7.5: no cue, column or show target;
  ;; a cue pattern's source, an inactive effect cell's lane and a sparse
  ;; keyframe's steps name the zones
  (let [s (-> (domains/build-initial-state)
              (assoc-in [:chains :cue-chains [0 0]] {:items [{:id "i" :type :preset :preset-id :circle}]
                                                     :zone-patterns [{:effect-id :zone-pattern :enabled? true
                                                                      :params {:style :chase :zones [:lane/lasers] :from :zone/aa}}]})
              (assoc-in [:chains :effect-chains [2 1]] {:active? false
                                                        :items [{:effect-id :zone-pattern :enabled? true
                                                                 :params {:zones [:zone/f :auto.laser/gone]}
                                                                 :keyframe-modulator {:enabled? true
                                                                                      :keyframes [{:position 0.0 :params {:steps [[:zone/c] [:auto.laser/gone] []]}}]}}]})
              (assoc-in [:chains :effect-chains [3 1]] {:active? true :items [{:effect-id :scale :params {:x-scale 1.0} :note :zone/g}]})
              (assoc-in [:zones :labels :zone/q] "Unused")
              (assoc-in [:zone-maps :map/custom-1] {:name "Mine" :lanes [:auto/left] :overrides {:zone/r :graphics}}))
        ctx (context s)
        zones #(fx/sub-ctx % subs/zones-in-use)
        map-zones #(fx/sub-ctx % subs/map-zones)]
    (is (= [:zone/c :zone/f :zone/aa] (zones ctx))
        "a source, a place and a lit step, in index order; not a label, an override, nor an unrelated effect's keyword")
    (is (= [:zone/a :zone/b :zone/c :zone/f :zone/aa] (map-zones ctx)) "the map editor adds A and B")
    (testing "the effect cell's keyframe alone"
      (let [ctx' (fx/swap-context ctx assoc-in [:chains :effect-chains [2 1] :items 0 :keyframe-modulator :keyframes 0 :params :steps]
                                  [[:zone/d] [:auto.laser/gone] []])]
        (is (= [:zone/d :zone/f :zone/aa] (zones ctx')) "C goes, D comes in its place")
        (testing "then the cue's source alone"
          (let [ctx'' (fx/swap-context ctx' assoc-in [:chains :cue-chains [0 0] :zone-patterns 0 :params :from] :zone/b)]
            (is (= [:zone/b :zone/d :zone/f] (zones ctx'')) "AA goes; B is named now")
            (is (= [:zone/a :zone/b :zone/d :zone/f] (map-zones ctx'')))))))
    (testing "switching the cell on or off isn't a structural edit"
      (let [inputs (fx/sub-ctx ctx subs/effect-ref-inputs)
            listed (zones ctx)
            ctx' (fx/swap-context ctx assoc-in [:chains :effect-chains [2 1] :active?] true)]
        (is (= {[2 1] {:items (get-in s [:chains :effect-chains [2 1] :items])}
                [3 1] {:items (get-in s [:chains :effect-chains [3 1] :items])}}
               inputs))
        (is (= inputs (fx/sub-ctx ctx' subs/effect-ref-inputs)))
        (is (identical? listed (zones ctx')) "so the zones aren't worked out again")))))

(deftest map-cycle-state-sub-test
  (let [s (domains/build-initial-state)
        cell {:active? true :items [(zone-patterns/cycle-instance)]}
        on (assoc-in s [:chains :effect-chains [1 2]] cell)
        cycle-state #(fx/sub-ctx (context %) subs/map-cycle-state)]
    (is (= {:on? false :running? false :paused? false :pending nil :bar? false :learning? false :bindings [] :maps []
            :description nil}
           (cycle-state s))
        "no cycle cell on")
    (is (= {:on? true :running? true :paused? false :maps ["Left / Right" "Odd / Even"]
            :description "Map cycle: Left / Right, Odd / Even, a bar each"}
           (select-keys (cycle-state on) [:on? :running? :paused? :maps :description])))
    (is (= {:running? false :paused? true} (select-keys (cycle-state (assoc-in on [:zone-map :cycle-paused?] true)) [:running? :paused?]))
        "a map pad's press paused it")
    (is (= :beat (:pending (cycle-state (assoc-in on [:zone-map :pending] {:id 1 :map :cycle :at-beat 4.0 :bar? false}))))
        "CYCLE waits for the beat")
    (is (false? (:on? (cycle-state (assoc-in on [:chains :effect-chains [1 2] :active?] false)))) "its cell off")
    (testing "while it runs no map pad is the one in use: CYCLE is"
      (is (:active? (fx/sub-ctx (context s) subs/map-pad :map/left-right)))
      (is (not (:active? (fx/sub-ctx (context on) subs/map-pad :map/left-right))))
      (is (:active? (fx/sub-ctx (context (assoc-in on [:zone-map :cycle-paused?] true)) subs/map-pad :map/left-right))))))

(defn- tick-map
  "The map a tick at beats routes by (show-clock's order, the cycle's
   stored parameters): an oracle beside the editor, whose subs read no
   tick."
  [s beats]
  (zone-maps/effective-map-id s beats (some-> (zone-patterns/active-cycle s)
                                              :params
                                              (zone-patterns/cycle-pick {} beats (mapv :id (zone-maps/all-maps s))))))

(defn- manual-marks
  "{id [list? pad?]} for each map the Manual mark is on: in the map
   editor's list (manual-map-marker-id) or on its pad (map-pad :active?)."
  [ctx]
  (let [marker (fx/sub-ctx ctx subs/manual-map-marker-id)]
    (into {}
          (keep (fn [id]
                  (let [marks [(= id marker) (:active? (fx/sub-ctx ctx subs/map-pad id))]]
                    (when (some true? marks) [id marks]))))
          (fx/sub-ctx ctx subs/map-pad-ids))))

(deftest map-editor-mode-and-takeover-test
  ;; plans/2026-11-23-cljfx-reactivity/29 §7: the editor says how the map is
  ;; chosen, never which one a tick routed by; Use now takes over whenever it
  ;; would change something
  (let [s (domains/build-initial-state)
        on (-> s
               (assoc-in [:chains :effect-chains [1 1]] {:active? true :items [(zone-patterns/cycle-instance)]})
               (assoc-in [:zone-map :active] :map/unison))
        lists #(assoc-in on [:chains :effect-chains [1 1] :items 0 :params :maps] %)
        editor #(fx/sub-ctx %1 subs/editor-map-switch %2)
        use-now #(handle %1 {:event/type :zone-maps/use-now :map %2})
        slot #(select-keys (fx/sub-val % :zone-map) [:active :pending :cycle-paused?])
        cycle-mode {:mode :cycle :manual-name "Unison" :queued? false :can-use-now? true}
        taken-over {:active :map/unison :pending nil :cycle-paused? true}]
    (doseq [[label s edited beats tick before marks after]
            [["cycling off: Left / Right manual, Odd / Even edited"
              s :map/odd-even 4.0 :map/left-right
              {:mode :manual :manual-name "Left / Right" :queued? false :can-use-now? true} {:map/left-right [true true]}
              {:active :map/odd-even :pending nil :cycle-paused? false}]
             ["on, the default cycle at beat 4: it picks Odd / Even, Unison edited"
              on :map/unison 4.0 :map/odd-even cycle-mode {} taken-over]
             ["on, a cycle of Unison alone: it picks the manual map"
              (lists [:map/unison]) :map/unison 4.0 :map/unison cycle-mode {} taken-over]
             ["on, a cycle of no known map: no pick, so the manual map routes"
              (lists [:map/gone]) :map/unison 4.0 :map/unison cycle-mode {} taken-over]
             ["paused, CYCLE waiting for beat 8, which a tick reaches before its commit"
              (-> on
                  (assoc-in [:zone-map :cycle-paused?] true)
                  (assoc-in [:zone-map :pending] {:id 7 :map :cycle :at-beat 8.0 :bar? false}))
              :map/unison 8.0 :map/left-right
              {:mode :manual :manual-name "Unison" :queued? true :can-use-now? true} {:map/unison [true true]}
              taken-over]
             ["cycling off: Unison manual and edited, a switch to Odd / Even waiting"
              (-> s
                  (assoc-in [:zone-map :active] :map/unison)
                  (assoc-in [:zone-map :pending] {:id 8 :map :map/odd-even :at-beat 8.0 :bar? false}))
              :map/unison 4.0 :map/unison
              {:mode :manual :manual-name "Unison" :queued? true :can-use-now? true} {:map/unison [true true]}
              {:active :map/unison :pending nil :cycle-paused? false}]]]
      (testing label
        (let [ctx (context s)]
          (is (= tick (tick-map s beats)) "the tick's map, for the record: no editor sub reads it")
          (is (= before (editor ctx edited)))
          (is (= marks (manual-marks ctx)) "the list's Manual mark and the pads' agree: on one map, or none while cycling")
          (is (false? (:can-use-now? (editor ctx :map/gone))) "a map that isn't there: nothing to use")
          (testing "Use now, then the same context's next state: Manual, nothing left to do"
            (let [ctx' (use-now ctx edited)]
              (is (= after (slot ctx')))
              (is (= {:mode :manual :manual-name (:name (fx/sub-ctx ctx' subs/zone-map edited)) :queued? false :can-use-now? false}
                     (editor ctx' edited)))
              (is (= {edited [true true]} (manual-marks ctx'))))))))
    (testing "the waiting CYCLE's report, after Use now cleared it, changes nothing"
      (let [taken (use-now (context (-> on
                                        (assoc-in [:zone-map :cycle-paused?] true)
                                        (assoc-in [:zone-map :pending] {:id 7 :map :cycle :at-beat 8.0 :bar? false})))
                           :map/unison)
            late (handle taken {:event/type :show-clock/zone-map-due :id 7 :map :cycle})]
        (is (= taken-over (slot late)))
        (is (= {:mode :manual :manual-name "Unison" :queued? false :can-use-now? false} (editor late :map/unison)))))
    (testing "a stored id that is no map shows as Left / Right, which Use now can choose"
      (let [ctx (context (assoc-in s [:zone-map :active] :map/gone))
            shown (fx/sub-ctx ctx subs/selected-zone-map-id)
            ctx' (use-now ctx shown)]
        (is (= :map/left-right shown))
        (is (= {:mode :manual :manual-name "Left / Right" :queued? false :can-use-now? true} (editor ctx shown)))
        (is (= {:map/left-right [true true]} (manual-marks ctx)))
        (is (= :map/left-right (fx/sub-val ctx' get-in [:zone-map :active])) "chosen, so stored")
        (is (false? (:can-use-now? (editor ctx' shown))))))
    (testing "browsing chooses nothing"
      (let [ctx (context on)
            ctx' (handle ctx {:event/type :zone-maps/select :map :map/odd-even})]
        (is (= :map/odd-even (fx/sub-ctx ctx' subs/selected-zone-map-id)))
        (is (= (slot ctx) (slot ctx')))
        (is (= cycle-mode (editor ctx' :map/unison)))
        (is (= cycle-mode (editor ctx' :map/odd-even)) "while cycling, Use now takes over on any map")
        (is (= {} (manual-marks ctx')))))))


(deftest vibe-pick-fx-blocks-audience-cells-test
  (let [s (-> (domains/build-initial-state)
              (templates/apply-starter-cue-chains)
              (templates/apply-starter-vibes)
              (assoc :projectors {:p1 {:name "P1" :host "10.0.0.1" :zone-groups [] :enabled? true}
                                  :p5 {:name "P5" :host "10.0.0.5" :zone-groups [] :tags #{:crowd-scanning} :enabled? true}})
              (assoc-in [:chains :effect-chains [2 1]] {:active? false :items [(zone-patterns/preset-instance :chase :cell)]})
              (assoc-in [:chains :effect-chains [3 1]] {:active? false :items [(assoc-in (zone-patterns/preset-instance :chase :cell)
                                                                                         [:params :zones] [:auto.laser/p1 :auto/left])]})
              (assoc-in [:chains :effect-chains [4 1]] {:active? false :items [{:effect-id :scale :params {}}]}))
        ctx (context s)
        blocked (fn [ctx col] (:blocked (fx/sub-ctx ctx subs/vibe-pick-fx col 1)))]
    (is (= :crowd (blocked ctx 2)) "Every laser reaches the scanner")
    (is (nil? (blocked ctx 3)) "P1, and Left: not the scanner, which is on the right")
    (is (nil? (blocked ctx 4)) "a point effect")
    (is (nil? (blocked (handle ctx {:event/type :vibe/set-option :vibe :vibe-1 :key :allow-audience? :value true}) 2))
        "unless the vibe edited allows the audience")
    (testing "Perform says why a vibe left one off"
      (is (nil? (fx/sub-ctx ctx subs/vibe-refused-text)))
      (is (= "B3 (Zone pattern) can reach the crowd: tick Allow audience cells in this vibe"
             (fx/sub-ctx (fx/swap-context ctx assoc-in [:vibe-runtime :problem] {:why :audience-fx :cell [2 1]})
                         subs/vibe-refused-text))))))

(deftest vibe-tap-hints-sub-test
  (testing "vibe Phase 8b (plans/2026-11-19-zones/11 §12.4): why the Tap reactions menus grey a zone reaction"
    (let [s (-> (domains/build-initial-state)
                (templates/apply-starter-cue-chains)
                (templates/apply-starter-vibes))
          laser (fn [n & {:as more}] (merge {:name (str "P" n) :host (str "10.0.0." n) :zone-groups [] :enabled? true} more))
          hints (fn [projectors] (fx/sub-ctx (context (assoc s :projectors projectors)) subs/vibe-tap-hints))
          no-laser "needs a laser that's on and in All (Projectors tab)"
          too-few "needs two lasers in All that don't scan the audience (Projectors tab)"]
      (is (= {:zone-hop no-laser :zone-chase no-laser} (hints {}))
          "no laser: the hop and the chase have nowhere to go; the swap and the shift have zones")
      (is (= {} (hints {:p1 (laser 1)})) "one laser: its two halves")
      (is (= {:zone-hop too-few :zone-chase too-few} (hints {:p1 (laser 1) :p2 (laser 2 :tags #{:crowd-scanning})}))
          "a scanner in All leaves one laser")
      (is (= {} (hints {:p1 (laser 1) :p2 (laser 2 :tags #{:crowd-scanning} :exclude-from-all? true)}))
          "out of All, Every laser never reaches it"))))


;; Who uses a group (zones plan Z4, 06 §8.7)

(deftest ref-usage-direct-and-through-test
  (let [cue (fn [t] {:items [{:type :preset :preset-id :circle :target t}]})
        cells (concat (for [c (range 3)] [[c 0] (cue :auto/left)])
                      (for [c (range 3 8)] [[c 0] (cue :zone/a)])
                      (for [c (range 4)] [[c 1] (cue :zone/a)])
                      (for [c (range 4 6)] [[c 1] (cue :zone/c)]))
        s (assoc-in (domains/build-initial-state) [:chains :cue-chains] (into {} cells))
        usage #(fx/sub-ctx (context %1) subs/ref-usage %2)]
    (is (= {:direct 3 :through 11 :zones [:zone/a :zone/c]} (usage s :auto/left))
        "3 name Left; under Left / Right, A and C go there too")
    (is (= {:direct 0 :through 0 :zones []} (usage s :auto/odd)))
    (testing "under Odd / Even, A and C go to Odd"
      (let [odd-even (assoc-in s [:zone-map :active] :map/odd-even)]
        (is (= {:direct 3 :through 0 :zones []} (usage odd-even :auto/left)))
        (is (= {:direct 0 :through 11 :zones [:zone/a :zone/c]} (usage odd-even :auto/odd)))))
    (testing "a cue that does both counts once, as direct"
      (is (= {:direct 4 :through 11 :zones [:zone/a :zone/c]}
             (usage (assoc-in s [:chains :cue-chains [6 1]] {:items [{:type :preset :preset-id :circle :target :auto/left}
                                                                     {:type :preset :preset-id :wave :target :zone/a}]})
                    :auto/left))))
    (testing "a column's default counts"
      (is (= {:direct 0 :through 1 :zones [:zone/b]}
             (usage (-> s
                        (assoc-in [:chains :cue-chains [7 3]] {:items [{:type :preset :preset-id :circle}]})
                        (assoc-in [:grid :column-targets 7] :zone/b))
                    :auto/right))))))


;; The Vibes tab's picker and zones (zones plan Z4, 06 §8.6)

(deftest picker-blocks-a-zone-cue-that-reaches-the-crowd-test
  (let [s (-> (domains/build-initial-state)
              (assoc :projectors {:p1 {:name "P1" :host "10.0.0.1" :zone-groups []}
                                  :p5 {:name "P5" :host "10.0.0.5" :zone-groups [:crowd] :tags #{:crowd-scanning}
                                       :exclude-from-all? true}})
              (assoc-in [:zone-groups :crowd] {:id :crowd :name "Crowd Scanning"})
              (assoc-in [:zone-maps :map/custom-1] {:name "Crowd sweep" :lanes [:auto/left :crowd] :overrides {}})
              (assoc-in [:chains :cue-chains [2 0]] {:items [{:type :preset :preset-id :circle :target :zone/b}]})
              (assoc-in [:chains :cue-chains [3 0]] {:items [{:type :preset :preset-id :circle}]}))
        ctx (context s)
        blocked (fn [ctx col] (fx/sub-ctx ctx subs/vibe-cell-blocked col 0))]
    (is (= :crowd (blocked ctx 2)) "Crowd sweep sends B to the scanner")
    (is (nil? (blocked ctx 3)))
    (testing "once the map goes, B reaches it no more"
      (is (nil? (blocked (fx/swap-context ctx update :zone-maps dissoc :map/custom-1) 2))))
    (testing "a column's default on B, and the show's"
      (is (= :crowd (blocked (fx/swap-context ctx assoc-in [:grid :column-targets 3] :zone/b) 3)))
      (is (= :crowd (blocked (fx/swap-context ctx assoc-in [:config :default-target] :zone/b) 3))))
    (testing "a switch of the map in use changes nothing: every map counts"
      (is (= :crowd (blocked (fx/swap-context ctx assoc-in [:zone-map :active] :map/odd-even) 2))))))


;; Scan finds every driver's devices (output drivers D4, 01 §5.3)

(def ^:private nova-descriptor
  {:id :acme/nova :name "Acme Nova (test)" :short-name "Nova" :transport :usb :fields [] :discover? true :addon nil})

(defn- nova-p [key output & {:as more}]
  (merge {:name (str key "/" output) :driver :acme/nova :driver-config {}
          :stable-id [:device :acme/nova key output] :enabled? false}
         more))

(def ^:private driver-rig
  (-> base-state
      (assoc-in [:backend :output :drivers] (assoc (laser-show.output.driver/descriptors) :acme/nova nova-descriptor))
      (assoc :projectors {:l {:name "Laser L" :host "10.0.0.5" :service-id 1 :enabled? true}
                          :n1a (nova-p "NV-1" 0 :enabled? true)
                          :n1b (nova-p "NV-1" 1)
                          :n2 (nova-p "NV-2" 0)
                          :hand {:name "Hand" :driver :acme/nova :driver-config {} :stable-id [:manual :acme/nova :hand]}
                          :b {:name "Bridge" :driver :lsps :driver-config {:host "127.0.0.1" :port 7266}
                              :stable-id [:manual :lsps :b] :enabled? true}
                          :lost {:name "Lost" :driver :lsps :driver-config {} :stable-id [:device :lsps "X" 0]}})))

(deftest rig-cards-find-driver-outputs-test
  (let [by-id #(into {} (map (juxt :id %)) (fx/sub-ctx (context %2) subs/rig-cards))
        scanned (-> driver-rig
                    (assoc-in [:rig-ui :scanned?] true)
                    (assoc-in [:projector-ui :discovered-devices]
                              [{:address "10.0.0.5" :services [{:service-id 1}] :driver :idn}
                               {:driver :acme/nova :device-key "NV-1" :outputs [{:output-id 0 :driver-config {}}]}]))]
    (testing "before any scan, every card is found"
      (is (every? true? (vals (by-id :found? driver-rig)))))
    (testing "after one: found, or not found, for a driver with :discover; never for one without"
      (is (= {:l true :n1a true :n1b false :n2 false :hand true :b true :lost true} (by-id :found? scanned))
          "NV-1's output 1 and NV-2 weren't found; one added by hand isn't looked for; LSPS has no :discover"))
    (testing "a device's outputs are grouped by its driver and key"
      (is (= {:l false :n1a true :n1b true :n2 false :hand false :b false :lost false} (by-id :multi-output? driver-rig)))
      (is (false? (:n2 (by-id :multi-output? (assoc-in driver-rig [:projectors :lost :stable-id]
                                                       [:device :lsps "NV-2" 0]))))
          "the same key under a different driver does not make a group")
      (is (= {:l nil :n1a "NV-1" :n1b "NV-1" :n2 "NV-2" :hand nil :b nil :lost "X"} (by-id :device-key driver-rig))
          "the card knows its device, for the menu"))))

(deftest enabled-outputs-cover-drivers-test
  (is (= #{["10.0.0.5" 1] [:acme/nova "NV-1" 0] [:lsps "X" 0]}
         (fx/sub-ctx (context (-> driver-rig
                                  (assoc-in [:projectors :lost :enabled?] true)
                                  (assoc-in [:projectors :hand :enabled?] true)))
                     subs/enabled-projector-services))
      "the found-output keys of the outputs that are on: [host service] for IDN, [driver key output] for a device; none for one added by hand"))

;; The step grid window (zones plan Z6, 08 §4, §7)

(deftest step-grid-sub-test
  (let [g1 #uuid "00000000-0000-0000-0000-0000000013a1"
        target {:domain :cue-zone-patterns :entity-key [0 0] :effect-instance-id g1}
        grid (-> (zone-patterns/preset-instance :steps :cue)
                 (assoc :id g1)
                 (update :params merge {:zones [:zone/a :zone/b :zone/c :graphics]
                                        :steps [[:zone/a] [] [:zone/b :zone/c] [:graphics]]}))
        s (-> (domains/build-initial-state)
              (assoc-in [:chains :cue-chains [0 0]] {:items [] :zone-patterns [grid]})
              (assoc :zone-groups {:graphics {:name "Graphics"}})
              (assoc-in [:zones :labels :zone/a] "Beams")
              (assoc-in [:ui :dialogs :step-grid-editor] {:open? true :target target :cursor [1 2]}))
        ctx (context s)
        sg (fx/sub-ctx ctx subs/step-grid target)]
    (testing "rows with where they go under the map in use"
      (is (= [{:ref :zone/a :text "A · Beams → Left"} {:ref :zone/b :text "B → Right"} {:ref :zone/c :text "C → Left"}
              {:ref :graphics :text "Graphics"}]
             (:rows sg))))
    (is (= g1 (:id sg)) "the pattern's id, for the playhead")
    (is (= 4 (:length sg)))
    (is (= [#{:zone/a} #{} #{:zone/b :zone/c} #{:graphics}] (:lit sg)) "the refs each step lights")
    (is (= [1 2] (:cursor sg)))
    (is (true? (:grid? sg)))
    (is (false? (:keyframes? sg)))
    (is (false? (:locked? sg)))
    (testing "the wrap note (S-5): two rows that go to one lane"
      (is (= "A and C both go to Left under Left / Right." (:wrap-note sg)))
      (is (nil? (:wrap-note (fx/sub-ctx (fx/swap-context ctx assoc-in [:chains :cue-chains [0 0] :zone-patterns 0 :params :zones] [:zone/a :zone/b])
                                        subs/step-grid target))) "none shared: no note")
      (is (= "A, C and E all go to Left; B and D both go to Right under Left / Right."
             (:wrap-note (fx/sub-ctx (fx/swap-context ctx assoc-in [:chains :cue-chains [0 0] :zone-patterns 0 :params :zones]
                                                      [:zone/a :zone/b :zone/c :zone/d :zone/e])
                                     subs/step-grid target)))))
    (testing "the keyframe guard"
      (let [kf (fx/swap-context ctx assoc-in [:chains :cue-chains [0 0] :zone-patterns 0 :keyframe-modulator] {:enabled? true :keyframes []})]
        (is (true? (:keyframes? (fx/sub-ctx kf subs/step-grid target))))))
    (testing "the show lock"
      (is (true? (:locked? (fx/sub-ctx (fx/swap-context ctx assoc-in [:ui :show-lock?] true) subs/step-grid target)))))
    (testing "a pattern that isn't a grid yet shows the grid it becomes"
      (let [chase (fx/swap-context ctx assoc-in [:chains :cue-chains [0 0] :zone-patterns]
                                   [(assoc (zone-patterns/preset-instance :chase :cue) :id g1 :params
                                           (assoc (:params (zone-patterns/preset-instance :chase :cue)) :zones [:zone/a :zone/b :zone/c]))])
            sg (fx/sub-ctx chase subs/step-grid target)]
        (is (= g1 (:id sg)) "the same pattern, its style switched")
        (is (false? (:grid? sg)))
        (is (= [#{:zone/a} #{:zone/b} #{:zone/c} #{:zone/a} #{:zone/b} #{:zone/c} #{:zone/a} #{:zone/b}] (:lit sg)))))
    (testing "another pattern in its place, with an id of its own, isn't the window's: nothing"
      (is (nil? (fx/sub-ctx (fx/swap-context ctx assoc-in [:chains :cue-chains [0 0] :zone-patterns 0 :id]
                                             #uuid "00000000-0000-0000-0000-0000000013a2")
                            subs/step-grid target))))
    (testing "Every laser's note, once it became zones"
      (is (= "Every laser can't be a row, because the number of lasers changes. It became zones A–D: pick a zone map that sends them where you want."
             (:every-laser-note (fx/sub-ctx (fx/swap-context ctx assoc-in [:ui :dialogs :step-grid-editor :every-laser]
                                                             [:zone/a :zone/b :zone/c :zone/d])
                                            subs/step-grid target))))
      (is (= "Every laser can't be a row, because the number of lasers changes. It became zones A and B: pick a zone map that sends them where you want."
             (:every-laser-note (fx/sub-ctx (fx/swap-context ctx assoc-in [:ui :dialogs :step-grid-editor :every-laser] [:zone/a :zone/b])
                                            subs/step-grid target)))))
    (testing "no zone pattern there: nothing"
      (is (nil? (fx/sub-ctx ctx subs/step-grid (assoc target :entity-key [5 0]))))
      (is (nil? (fx/sub-ctx ctx subs/step-grid {:domain :cue-zone-patterns :entity-key [0 0] :effect-path [0]}))
          "a place is no target"))
    (testing "the window's pattern where it is now (reactivity plan 05, examples 2 and 5)"
      (let [p-id #uuid "00000000-0000-0000-0000-0000000013b1"
            q-id #uuid "00000000-0000-0000-0000-0000000013b2"
            p (-> (zone-patterns/preset-instance :steps :cell) (assoc :id p-id)
                  (update :params merge {:zones [:zone/a] :steps [[] [:zone/a]]}))
            q (-> (zone-patterns/preset-instance :steps :cell) (assoc :id q-id)
                  (update :params merge {:zones [:zone/b] :steps [[:zone/b] [:zone/b] []]}))
            t {:domain :effect-chains :entity-key [2 1] :effect-instance-id p-id}
            items #(fx/swap-context %1 assoc-in [:chains :effect-chains [2 1] :items] %2)
            c0 (context (-> (domains/build-initial-state)
                            (assoc-in [:chains :effect-chains [2 1]] {:active? true :items [p q]})
                            (assoc-in [:ui :dialogs :step-grid-editor] {:open? true :target t :cursor [0 1]})))
            c1 (items c0 [q {:type :group :id #uuid "00000000-0000-0000-0000-0000000013b3" :items [p]}])
            c2 (items c1 [q])
            c3 (fx/swap-context c2 assoc-in [:chains :effect-chains [3 1]] {:active? true :items [p]})
            c4 (items c3 [q p])]
        (doseq [[why c] [["at first" c0] ["in a group behind Q" c1] ["back in its cell, after Q" c4]]]
          (let [sg (fx/sub-ctx c subs/step-grid t)]
            (is (= p-id (:id sg)) why)
            (is (= [#{} #{:zone/a}] (:lit sg)) why)
            (is (= [0 1] (:cursor sg)) why)))
        (is (nil? (fx/sub-ctx c2 subs/step-grid t)) "gone from its cell: nothing, and not Q in its place")
        (is (nil? (fx/sub-ctx c3 subs/step-grid t)) "in another cell: still nothing")))))

(deftest can-paste-test
  ;; Keyboard plan Phase 4, Step 4a (D13 B): Paste follows the clipboard; an
  ;; empty one is a maybe, since a paste then reads the OS clipboard
  (let [targets [:cue-chain-items :item-effects :cue-chain :effects-cell :step-grid]
        with-clip #(context (assoc-in base-state [:ui :clipboard] %))
        lit (fn [ctx] (set (filter #(fx/sub-ctx ctx subs/can-paste? %) targets)))]
    (testing "nothing copied in this app yet: every Paste is on"
      (is (= (set targets) (lit (context (assoc-in base-state [:ui :clipboard] nil))))))
    (testing "a clip: only the targets that take it"
      (is (= #{:cue-chain-items}
             (lit (with-clip {:type :cue-chain-items :items [{:type :preset :preset-id :circle}] :copied-at 1}))))
      (is (= #{:item-effects}
             (lit (with-clip {:type :item-effects :effects [{:effect-id :scale}] :copied-at 1}))))
      (is (= #{:cue-chain :cue-chain-items}
             (lit (with-clip {:type :cue-chain :data {:items [{:type :preset :preset-id :circle}]} :copied-at 1})))
          "Step 4b: a cue list takes a copied cell's items")
      (is (= #{:effects-cell :item-effects}
             (lit (with-clip {:type :effects-cell :data {:items [{:effect-id :scale}]} :copied-at 1})))
          "Step 4b: an effect list takes a copied effects cell's effects")
      (is (= #{} (lit (with-clip {:type :cue-chain :data {:items []} :copied-at 1})))
          "Step 4b: an empty cell's clip, nowhere")
      (is (= #{:step-grid} (lit (with-clip {:type :step-grid :grid {:zones [:zone/a] :steps [[:zone/a]]} :copied-at 1})))))))


;; The registries as data (plans/2026-11-23-cljfx-reactivity/17): what the
;; effect banks offer and what the tap reactions follow come from what
;; :registries/published last put in app state

(defn- published
  "ctx after the effect and modulator registries, as they are now, are
   published (:registries/published)."
  [ctx]
  (handle ctx (registries/published-event)))

(deftest effect-bank-follows-published-effects-test
  (let [scale (effects/get-effect :scale)
        n (count (effects/list-effects))
        bank #(fx/sub-ctx %1 subs/effect-bank-categories %2)
        in-bank (fn [ctx scope id] (some #(when (= id (:id %)) %) (apply concat (vals (bank ctx scope)))))
        ctx (published (context base-state))
        offered (bank ctx :item)]
    (is (= {} (bank (context base-state) :item)) "nothing before the registries are published")
    (testing "what each bank offers"
      (is (= #{:shape :color :intensity} (set (keys offered))) "a cue's: no Zone tab, nothing for calibration alone")
      (is (= #{:zone-pattern :zone-map-cycle} (set (map :id (:zone (bank ctx :global))))) "the effects grid's Zone tab")
      (is (nil? (in-bank ctx :global :zone-selector)) "hidden: in none")
      (is (some? (in-bank ctx :item :decimate)) "a shape effect that calibrates too")
      (is (nil? (in-bank ctx :item :axis-transform)) "a calibration effect")
      (is (= (dissoc scale :apply-transducer) (in-bank ctx :item :scale)) "an effect as data: its definition without its function"))
    (is (identical? offered (bank (published ctx) :item)) "published again, unchanged: the same")
    (try
      (effects/register-effect! (-> scale (assoc :name "Size") (assoc-in [:parameters 0 :default] 7.0)))
      (is (= n (count (effects/list-effects))) "as many effects as before")
      (let [ctx' (published ctx)]
        (is (= "Size" (:name (in-bank ctx' :item :scale))) "re-registered, and published: its new name")
        (is (= 7.0 (get-in (in-bank ctx' :item :scale) [:parameters 0 :default])) "and its new default")
        (is (= "Size" (:name (in-bank ctx' :global :scale))) "in the effects grid's bank too"))
      (finally
        (effects/register-effect! scale)))))

(deftest tap-reactions-follow-published-modulators-test
  (let [envelope (modulator-registry/get-modulator :envelope)
        default-source (fn [source] (update envelope :params (fn [ps] (mapv #(cond-> % (= :source (:key %)) (assoc :default source)) ps))))
        ;; Swell in Groove's FX set: an envelope with no source of its own, so its type's default
        s (-> groove
              (assoc-in [:chains :effect-chains [7 3]]
                        {:name "Groove · Swell" :active? false
                         :items [{:effect-id :scale :enabled? true
                                  :params {:x-scale {:type :envelope :active? true :min 1.0 :max 1.25}}}]})
              (assoc-in [:config :vibes :library :groove :fx] [[7 3]]))
        shown (fn [ctx] {:pads (into {} (for [role [:hit :accent]] [role (:reactions (fx/sub-ctx ctx subs/vibe-pad role))]))
                         :edited (fx/sub-ctx ctx subs/vibe-edited-reactions)
                         :badge (fx/sub-ctx ctx subs/vibes-tab-label)
                         :lint (fx/sub-ctx ctx subs/vibe-lint)})
        swell {:grid :effects :cell [7 3] :name "Swell"}
        ctx (published (context s))
        before (shown ctx)]
    (is (= {:hit ["Swell"] :accent []} (:pads before)) "an envelope follows HIT, its type's default source")
    (is (= {:hit [swell]} (:edited before)))
    (is (= "Vibes" (:badge before)) "HIT moves effects: no error")
    (try
      (testing "the default source re-registered as ACCENT: Perform's pads, the Vibes tab's reactions and its lint follow, once published"
        (modulator-registry/register-modulator! (default-source :accent))
        (let [ctx' (published ctx)
              {:keys [pads edited badge lint]} (shown ctx')]
          (is (= {:hit [] :accent ["Swell"]} pads))
          (is (= {:accent [swell]} edited))
          (is (= "Vibes ⚠ 1" badge) "HIT does nothing now")
          (is (= (library/lint (in-state ctx')) lint))))
      (testing "its category re-registered as a wave's, which follows no press: no reaction at all"
        (modulator-registry/register-modulator! (assoc envelope :category :wave))
        (let [{:keys [pads edited]} (shown (published ctx))]
          (is (= {:hit [] :accent []} pads))
          (is (= {} edited))))
      (finally
        (modulator-registry/register-modulator! envelope)))
    (is (= before (shown (published ctx))) "put back, and published: as before")))
