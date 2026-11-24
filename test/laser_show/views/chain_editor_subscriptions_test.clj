(ns laser-show.views.chain-editor-subscriptions-test
  "An open chain editor follows its own cell, and a list renders only the
   rows whose item or own state changed (reactivity plan 34). Each editor
   reads its cell through the cell-addressed selectors, so another cell's
   edit runs no body in it. The list's label and menu functions are
   top-level functions with plain data, so equal inputs give equal
   descriptions. Each row gets what it shows (its item, its label, its own
   selection, drag and rename state), keyed by its item's id: a selection,
   an insert or a reorder runs only the rows whose item or state changed,
   and the others keep their nodes. Mounted with views.harness, which
   counts the bodies that ran for each render."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is testing]]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.animation.effects :as effects]
            ;; The effects the fixtures use, registered as the app's frame
            ;; service registers them
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.shape]
            [laser-show.state.domains :as domains]
            [laser-show.subs :as subs]
            [laser-show.views.components.list :as list]
            [laser-show.views.dialogs.cue-chain-editor :as cue-editor]
            [laser-show.views.dialogs.effect-chain-editor :as effect-editor]
            [laser-show.views.harness :as h])
  (:import [javafx.scene Node Parent]
           [javafx.scene.control Label]))


;; What ran


(defn- ran
  "What ran for a step's render: how many bodies in all, how many list
   editors and sidebars ran for each list (by component id), and the ids of
   the items whose rows, cards and group headers ran."
  [step]
  {:bodies (count (:ran step))
   :lists (frequencies (map :component-id (h/ran-of step list/list-editor)))
   :sidebars (frequencies (map :component-id (h/ran-of step @#'list/list-sidebar)))
   :rows (set (map (comp :id :item) (h/ran-of step @#'list/render-list-item)))
   :cards (set (map (comp :id :item) (h/ran-of step @#'list/list-item-card)))
   :headers (set (map (comp :id :group) (h/ran-of step @#'list/group-header)))})


;; Fixtures


(defn- fx-item
  "An effect named name (its row's label)."
  [id effect-id name]
  {:id id :type :effect :effect-id effect-id :enabled? true :name name
   :params (effects/get-default-params effect-id)})

(defn- group [id name items]
  {:id id :type :group :name name :collapsed? false :enabled? true :items items})

(defn- effects-editor-state
  "The Effects editor open at [0 0], whose chain is items; [1 0] another
   cell with one effect."
  [items]
  (-> (domains/build-initial-state)
      (assoc-in [:ui :dialogs :effect-chain-editor] {:open? true :col 0 :row 0 :active-bank-tab :shape :editing-name? false})
      (assoc-in [:chains :effect-chains [0 0]] {:items items})
      (assoc-in [:chains :effect-chains [1 0]] {:items [(fx-item (random-uuid) :intensity "Other")]})))

(defn- preset [preset-id name]
  (assoc (cue-chains/create-preset-instance preset-id) :name name))

(defn- cue-editor-state
  "The Cue Chain Editor open at [0 0], whose cue is items; [1 0] another
   cue."
  [items]
  (-> (domains/build-initial-state)
      (assoc-in [:ui :dialogs :cue-chain-editor] {:open? true :col 0 :row 0 :editing-name? false
                                                   :active-preset-tab :geometric})
      (assoc-in [:chains :cue-chains [0 0]] {:items items})
      (assoc-in [:chains :cue-chains [1 0]] {:items [(preset :circle "Other")]})))

(def ^:private effects-editor {:fx/type @#'effect-editor/effect-chain-editor-content})

(def ^:private cue-editor {:fx/type @#'cue-editor/cue-chain-editor-content})

(defn- selected [state component-id ids]
  (assoc-in state [:list-ui component-id :selected-ids] ids))

(defn- cell-list
  "[0 0]'s effects as the Effects editor lists them, its rows not in a
   scroll pane, so they are in the node tree without a skin."
  [{:keys [fx/context]}]
  {:fx/type list/list-editor
   :items (:items (fx/sub-ctx context subs/effect-cell-chain 0 0) [])
   :component-id [:effect-chain 0 0]
   :items-path [:chains :effect-chains [0 0] :items]
   :item-id-key :effect-id
   :item-registry-fn effects/get-effect
   :fallback-label "Unknown Effect"
   :on-change-event :chain/set-items
   :on-change-params {:domain :effect-chains :entity-key [0 0]}
   :header-label "CHAIN"
   :empty-text "No effects"
   :scrollable? false})

(defn- row-nodes
  "{label {:node :classes}}: the rows (cards and group headers) under root,
   by the text they show, with their style classes."
  [root]
  (into {}
        (for [^Node n (h/nodes root)
              :let [classes (set (.getStyleClass n))]
              :when (or (classes "chain-item") (classes "group-header"))
              :let [label (some #(when (and (instance? Label %)
                                            (some #{"chain-item-name" "group-name-label"} (.getStyleClass ^Label %)))
                                   (.getText ^Label %))
                                (.getChildrenUnmodifiable ^Parent n))]]
          [label {:node n :classes classes}])))


;; Another cell


(deftest another-cells-edit-runs-nothing-in-the-open-editors-test
  (testing "the Effects editor on [0 0]"
    (let [[a b g c d] (repeatedly 5 random-uuid)
          steps (h/each-render (effects-editor-state [(fx-item a :intensity "A") (fx-item b :scale "B")
                                                      (group g "G" [(fx-item c :rotation "C") (fx-item d :hue-shift "D")])])
                               h/lru-512 effects-editor
                               [#(assoc-in % [:chains :effect-chains [1 0] :name] "Another")
                                #(update-in % [:chains :effect-chains [1 0] :items] conj (fx-item (random-uuid) :scale "More"))
                                #(assoc-in % [:chains :effect-chains [0 0] :items 1 :enabled?] false)]
                               ran)]
      (is (= #{a b g c d} (:rows (first steps))) "every row rendered once")
      (testing ": [1 0]'s name, then a new effect in it, run no body in it"
        (is (= [0 0] (map :bodies (subvec steps 1 3)))))
      (testing "; [0 0]'s second effect turned off runs its list and that row, no other"
        (is (= {:lists {[:effect-chain 0 0] 1} :sidebars {[:effect-chain 0 0] 1} :rows #{b} :cards #{b} :headers #{}}
               (dissoc (nth steps 3) :bodies)))
        (is (= 5 (:bodies (nth steps 3))) "the editor, its list editor and sidebar, and B's row and card"))))
  (testing "the Cue Chain Editor on [0 0]"
    (let [[p1 p2 p3] [(preset :circle "P1") (preset :square "P2") (preset :triangle "P3")]
          g (group (random-uuid) "G" [p3])
          steps (h/each-render (cue-editor-state [p1 p2 g]) h/lru-512 cue-editor
                               [#(assoc-in % [:chains :cue-chains [1 0] :name] "Another")
                                #(update-in % [:chains :cue-chains [1 0] :items] conj (preset :star "More"))
                                #(assoc-in % [:chains :cue-chains [0 0] :items 1 :enabled?] false)]
                               ran)]
      (is (= #{(:id p1) (:id p2) (:id g) (:id p3)} (:rows (first steps))) "every row rendered once")
      (testing ": [1 0]'s name, then a new preset in it, run no body in it"
        (is (= [0 0] (map :bodies (subvec steps 1 3)))))
      (testing "; [0 0]'s second preset turned off runs its list and that row, no other"
        (is (= {:lists {[:cue-chain 0 0] 1} :sidebars {[:cue-chain 0 0] 1} :rows #{(:id p2)} :cards #{(:id p2)} :headers #{}}
               (dissoc (nth steps 3) :bodies)))
        (is (= 5 (:bodies (nth steps 3)))
            "the editor, its list editor and sidebar, and P2's row and card: no row's Target menu either")))))


;; Equal inputs, equal descriptions


(deftest the-editors-lists-take-equal-descriptions-test
  (testing "the Cue Chain Editor renders for its preset tab: its list doesn't"
    (let [[p1 p2] [(preset :circle "P1") (preset :square "P2")]
          [_ tab] (h/each-render (cue-editor-state [p1 p2]) h/lru-512 cue-editor
                                 [#(assoc-in % [:ui :dialogs :cue-chain-editor :active-preset-tab] :organic)]
                                 ran)]
      (is (pos? (:bodies tab)) "the editor renders")
      (is (= {:lists {} :sidebars {} :rows #{} :cards #{} :headers #{}} (dissoc tab :bodies)))))
  (testing "a preset selected in the Cue Chain Editor: the editor renders for it, its cue list's
            description stays the same, and the sidebar runs that row"
    (let [[p1 p2] [(preset :circle "P1") (preset :square "P2")]
          [_ sel] (h/each-render (cue-editor-state [p1 p2]) h/lru-512 cue-editor
                                 [#(selected % [:cue-chain 0 0] #{(:id p2)})]
                                 ran)]
      (is (= {:lists {[:item-effects 0 0] 1}
              :sidebars {[:cue-chain 0 0] 1 [:item-effects 0 0] 1}
              :rows #{(:id p2)} :cards #{(:id p2)} :headers #{}}
             (dissoc sel :bodies))
          "the preset's effects list is new"))))


;; Rows


(deftest rows-run-for-their-own-changes-test
  (let [[a b g c d x] (repeatedly 6 random-uuid)
        items [(fx-item a :intensity "A") (fx-item b :scale "B") (group g "G" [(fx-item c :rotation "C") (fx-item d :hue-shift "D")])]
        at [:chains :effect-chains [0 0] :items]
        ui #(update-in %1 [:list-ui [:effect-chain 0 0]] merge %2)
        steps (h/each-render (effects-editor-state items) h/lru-512 {:fx/type cell-list}
                             [#(selected % [:effect-chain 0 0] #{a})
                              #(selected % [:effect-chain 0 0] #{c})
                              #(update-in % at (fn [[i & more]] (into [i (fx-item x :translate "X")] more)))
                              #(update-in % at (fn [[i j k l]] [k i j l]))
                              #(update-in % at (fn [v] (assoc-in v [0 :name] "B2")))
                              #(ui % {:dragging-ids #{a} :drop-target-id d :drop-position :after})
                              #(ui % {:dragging-ids nil :drop-target-id nil :drop-position nil :renaming-id x})]
                             (fn [step]
                               (assoc (ran step)
                                      :renames (count (h/ran-of step @#'list/rename-text-field))
                                      :nodes (row-nodes (:root step)))))
        [first-render select-a select-c insert reorder rename drag start-rename] steps
        nodes (fn [step label] (get-in step [:nodes label :node]))]
    (is (= #{a b g c d} (:rows first-render)))
    (testing "a selection runs the rows whose selection changed: the row, and the group holding it"
      (is (= [#{a} #{a}] ((juxt :rows :cards) select-a)))
      ;; A's row is back to a description it had before: cljfx reuses it
      (is (= [#{g c} #{c} #{}] ((juxt :rows :cards :headers) select-c))))
    (testing "an insert runs the new row alone, and the rows below keep their nodes"
      (is (= [#{x} #{x}] ((juxt :rows :cards) insert)))
      (is (every? #(identical? (nodes select-c %) (nodes insert %)) ["A" "B" "G" "C" "D"])))
    (testing "a reorder runs no row; every row keeps its node"
      (is (= [{[:effect-chain 0 0] 1} #{} #{} #{}] ((juxt :sidebars :rows :cards :headers) reorder)))
      (is (every? #(identical? (nodes insert %) (nodes reorder %)) ["A" "X" "B" "G" "C" "D"])))
    (testing "a rename, a drag over a row and a rename field run those rows"
      (is (= [#{b} #{b}] ((juxt :rows :cards) rename)))
      (is (= [#{a g d} #{a d}] ((juxt :rows :cards) drag)))
      (is (= [#{x} #{x} 1] ((juxt :rows :cards :renames) start-rename))
          "and those back to a description they had before run nothing"))))

(deftest a-disabled-groups-items-look-disabled-test
  ;; A group's rows gave its items its state as :effectively-disabled?, and
  ;; each item read :parent-disabled?
  (let [[a g c d] (repeatedly 4 random-uuid)
        rows (h/mounted (effects-editor-state [(fx-item a :intensity "A")
                                               (assoc (group g "G" [(fx-item c :rotation "C") (fx-item d :hue-shift "D")])
                                                      :enabled? false)])
                        h/lru-512 {:fx/type cell-list} []
                        (fn [{:keys [root]}] (update-vals (row-nodes root) :classes)))]
    (is (contains? (rows "G") "group-header-disabled"))
    (is (every? #(contains? (rows %) "chain-item-disabled") ["C" "D"]) "the group's items look off too")
    (is (not (contains? (rows "A") "chain-item-disabled")))))
