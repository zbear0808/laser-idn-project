(ns laser-show.views.components.grid-tab-test
  "The grid's cell size and label size for the space the tab gives it, and
   which cells of both grids an edit describes again."
  (:require [cljfx.api :as fx]
            [clojure.core.cache :as cache]
            [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.views.components.grid-cell :as grid-cell]
            [laser-show.views.components.grid-tab :as grid-tab]))

(deftest cell-size-test
  (testing "before the area is laid out: 80×60"
    (is (= [80 60] (grid-tab/cell-size 8 4 nil nil))))
  (testing "the columns share the width; a cell is never taller than wide"
    (is (= [70 70] (grid-tab/cell-size 8 4 633 472)) "1024×700")
    (is (= [123 123] (grid-tab/cell-size 8 4 1056 772)) "1680×1000"))
  (testing "a short, wide area: the rows share its height"
    (is (= [166 62] (grid-tab/cell-size 8 4 1400 300))))
  (testing "never under 64×48"
    (is (= [64 48] (grid-tab/cell-size 8 4 400 200)))
    (is (= [64 48] (grid-tab/cell-size 8 4 0 0)))))

(deftest label-size-test
  (testing "12 px at the smallest cell, 16 px at most"
    (is (= 12 (grid-tab/label-size 64 48)))
    (is (= 12 (grid-tab/label-size 70 70)))
    (is (= 14 (grid-tab/label-size 84 84)))
    (is (= 16 (grid-tab/label-size 123 123))))
  (testing "a wide, short cell's size follows its height"
    (is (= 14 (grid-tab/label-size 166 62)))
    (is (= 12 (grid-tab/label-size 166 48)))))


;; Which cells an edit describes again (plans/2026-11-23-cljfx-reactivity/35)

(def ^:private described
  "The cells the two adapters below described, [action-type [col row]] each."
  (atom []))

(defn- cue-cell
  "The real cue cell at [col row] as a grid row describes it (80×60, 12 px
   labels), through the context the cache binds; noted in described. A
   stable fn: the cache keys on it, as on the row's description."
  [context col row]
  (swap! described conj [:trigger-cue [col row]])
  (grid-cell/grid-cell {:fx/context context :col col :row row :size [80 60] :font-size 12}))

(defn- effects-cell
  "The real effects cell at [col row], as cue-cell."
  [context col row]
  (swap! described conj [:trigger-effect [col row]])
  (grid-cell/effects-cell {:fx/context context :col col :row row :size [80 60] :font-size 12}))

(defn- grids
  "Both grids' cells in ctx, {[action-type [col row]] description}, and
   the cells described for them, #{[action-type [col row]]}."
  [ctx]
  (reset! described [])
  (let [descs (into {} (for [[type cell] [[:trigger-cue cue-cell] [:trigger-effect effects-cell]]
                             row (range 4)
                             col (range 8)]
                         [[type [col row]] (fx/sub-ctx ctx cell col row)]))]
    [descs (set @described)]))

(def ^:private doc
  "The show the grids are in (reactivity plan 20): what a Learn and a
   Cancel name."
  #uuid "00000000-0000-0000-0000-0000000035d1")

(def ^:private plain-grids
  "No cues, no effects, no bindings, both selections on A1."
  (-> (domains/build-initial-state)
      (assoc-in [:chains :cue-chains] {})
      (assoc-in [:chains :effect-chains] {})
      (assoc-in [:config :input :trigger-map] {})
      (assoc-in [:grid :selected-cell] [0 0])
      (assoc-in [:effects :selected-cell] [0 0])
      (assoc-in [:project :document-id] doc)))

(def ^:private key-h {:source :keyboard :id :h})
(def ^:private note-60 {:source :midi :channel 0 :note 60})
(def ^:private cue-a2 {:type :trigger-cue :target [1 0]})
(def ^:private fx-a2 {:type :trigger-effect :target [1 0]})
(def ^:private vibe-hit {:type :vibe :role :hit})
(def ^:private l1 #uuid "00000000-0000-0000-0000-0000000035a1")
(def ^:private l2 #uuid "00000000-0000-0000-0000-0000000035a2")

(defn- bindings [m] #(assoc-in % [:config :input :trigger-map] m))

(defn- learning
  "A learn attempt (id, l1 unless given) waiting to bind target
   ([:ui :input-learn :active], reactivity plan 24); nil: none."
  ([target] (learning target l1))
  ([target id]
   #(assoc-in % [:ui :input-learn :active]
              (when target
                {:learn-id id :owner :binding :capture :press :document-id doc :target-action target}))))

(defn- showing
  "The cells whose description shows k of its display data (:learning?,
   :has-trigger?)."
  [descs k]
  (set (keep (fn [[cell desc]] (when (get-in desc [:display-data k]) cell)) descs)))

(defn- trigger-items
  "The Learn, Cancel and Clear items of a cell's menu: {text on-action}."
  [desc]
  (into {}
        (keep (fn [{:keys [text on-action]}]
                (when (#{"Learn Trigger..." "Cancel Learning Trigger" "Clear Triggers"} text)
                  [text on-action])))
        (:context-menu-items desc)))

(defn- walk
  "Apply each [label f ran seen] of steps in turn, from ctx: in testing
   label, both grids described exactly the cells ran again, and (observe
   descs) is seen. The last context."
  [ctx observe steps]
  (reduce (fn [ctx [label f ran seen]]
            (let [ctx' (fx/swap-context ctx f)
                  [descs described] (grids ctx')]
              (testing label
                (is (= ran described) "the cells described again")
                (is (= seen (observe descs))))
              ctx'))
          ctx
          steps))

(deftest cell-wrappers-rerun-only-for-changed-indicators-test
  (let [C [:trigger-cue [1 0]]
        E [:trigger-effect [1 0]]
        ctx (fx/create-context plain-grids #(cache/lru-cache-factory % :threshold 4096))
        [base warm] (grids ctx)]
    (is (= 64 (count warm)) "warm: each cell of both grids described once")
    (is (= #{} (showing base :has-trigger?) (showing base :learning?)))
    (testing "a selection: the old cell and the new"
      (walk ctx #(showing % :selected?)
            [["cue A1 to A2" #(assoc-in % [:grid :selected-cell] [1 0]) #{[:trigger-cue [0 0]] C}
              #{C [:trigger-effect [0 0]]}]]))
    (testing "a key for a Vibe role (§7 input 2): no cell described, none gains ⚡ or Clear Triggers"
      (let [[descs ran] (grids (fx/swap-context ctx (bindings {key-h vibe-hit})))]
        (is (= #{} ran))
        (is (= #{} (showing descs :has-trigger?)))
        (is (not-any? #(contains? (trigger-items %) "Clear Triggers") (vals descs)))))
    (testing "keys for a cell (§7 input 3): the cells whose ⚡ changes, no other"
      (walk ctx (fn [descs] (mapv #(get-in descs [% :display-data :has-trigger?]) [C E]))
            [["two keys for cue A2" (bindings {key-h cue-a2 note-60 cue-a2}) #{C} [true false]]
             ["one of them gone: it still has one" (bindings {note-60 cue-a2}) #{} [true false]]
             ["its last key on the effects cell at the same place" (bindings {note-60 fx-a2}) #{C E} [false true]]
             ["and on a Vibe role" (bindings {note-60 vibe-hit}) #{E} [false false]]
             ["a key that follows the cue's mode" (bindings {note-60 (assoc cue-a2 :behavior :follow)}) #{C}
              [true false]]
             ["its behavior alone" (bindings {note-60 (assoc cue-a2 :behavior :momentary)}) #{} [true false]]])
      (let [[descs] (grids (fx/swap-context ctx (bindings {key-h cue-a2})))]
        (is (= {"Learn Trigger..." {:event/type :input/start-learn :target-action cue-a2 :document-id doc}
                "Clear Triggers" {:event/type :input/clear-cell-triggers :target-action cue-a2}}
               (trigger-items (descs C))))
        (is (= {"Learn Trigger..." {:event/type :input/start-learn :target-action fx-a2 :document-id doc}}
               (trigger-items (descs E))))))
    (testing "learns (§7 input 4): the cells whose LEARN changes, no other"
      (walk ctx #(showing % :learning?)
            [["a Vibe role's" (learning vibe-hit) #{} #{}]
             ["cue A2's" (learning cue-a2) #{C} #{C}]
             ["the effects cell's at the same place" (learning fx-a2) #{C E} #{E}]
             ["none" (learning nil) #{E} #{}]
             ["cue A2's again" (learning cue-a2) #{C} #{C}]
             ["a Vibe role's while cue A2's waits: its LEARN goes" (learning vibe-hit) #{C} #{}]]))
    (testing "Learn restarted on the same cell, a new attempt (reactivity plan 24): only that cell's Cancel changes"
      (let [waiting (fx/swap-context ctx (learning cue-a2 l1))
            [descs] (grids waiting)]
        (is (= {"Cancel Learning Trigger" {:event/type :input/cancel-learn :learn-id l1 :document-id doc}}
               (trigger-items (descs C))))
        (walk waiting #(trigger-items (% C))
              [["restarted" (learning cue-a2 l2) #{C}
                {"Cancel Learning Trigger" {:event/type :input/cancel-learn :learn-id l2 :document-id doc}}]])))
    (testing "a capture at the effects cell (§7 input 5): LEARN and Cancel go, ⚡ and Clear come, in one state"
      (let [waiting (fx/swap-context ctx (learning fx-a2))
            [descs] (grids waiting)
            momentary (assoc fx-a2 :behavior :momentary)
            captured (fx/swap-context waiting (comp (bindings {key-h momentary}) (learning nil)))
            [descs' ran] (grids captured)]
        (is (= [true false] ((juxt :learning? :has-trigger?) (:display-data (descs E)))))
        (is (= {"Cancel Learning Trigger" {:event/type :input/cancel-learn :learn-id l1 :document-id doc}}
               (trigger-items (descs E)))
            "Cancel names the attempt it shows")
        (is (= #{E} ran))
        (is (= [false true] ((juxt :learning? :has-trigger?) (:display-data (descs' E)))))
        (is (= {"Learn Trigger..." {:event/type :input/start-learn :target-action fx-a2 :document-id doc}
                "Clear Triggers" {:event/type :input/clear-cell-triggers :target-action fx-a2}}
               (trigger-items (descs' E))))
        (is (= momentary (fx/sub-val captured get-in [:config :input :trigger-map key-h]))
            "the binding keeps its behavior")))
    (testing "a cell with content, renamed: it is described again, with its new name"
      (let [labels (fn [descs] [(grid-cell/cell-label (:display-data (descs [:trigger-cue [3 2]])))
                                (grid-cell/effects-label (:display-data (descs [:trigger-effect [3 2]])))])
            both #{[:trigger-cue [3 2]] [:trigger-effect [3 2]]}]
        (walk ctx labels
              [["filled" #(-> %
                              (assoc-in [:chains :cue-chains [3 2]] {:name "Intro" :items [{:type :preset :preset-id :circle}]})
                              (assoc-in [:chains :effect-chains [3 2]] {:name "Wobble" :items [{:type :effect :effect-id :scale}]}))
                both ["Intro" "Wobble"]]
               ["renamed" #(-> %
                               (assoc-in [:chains :cue-chains [3 2] :name] "Outro")
                               (assoc-in [:chains :effect-chains [3 2] :name] "Wiggle"))
                both ["Outro" "Wiggle"]]])))))
