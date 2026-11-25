(ns laser-show.ui-test.item-effects-test
  "The cue chain editor's item effects (views/dialogs/cue_chain_editor.clj,
   follow-up N14): the selected item's effects list, the effect bank and
   the selected effect's parameters name the item by its id and the effect
   by its instance, never their places, so a reorder or a delete queued
   ahead of an input (in one FX turn, before the editor renders it) can't
   move it onto another item. A drop in the list, a bank click and a typed
   parameter each land on their own item where it is now, one undo step
   each, and on nothing once it is gone."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.event ActionEvent Event]
           [javafx.scene Node]
           [javafx.scene.control Button Labeled TextField]
           [javafx.scene.input ClipboardContent DragEvent TransferMode]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(def ^:private item-effects-list "#list-editor-item-effects-0-0")

(defn- items [] (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :items]))
(defn- item [id] (some #(when (= id (:id %)) %) (items)))
(defn- effect-ids [id] (mapv :id (:effects (item id))))
(defn- amounts [id] (mapv #(get-in % [:params :amount]) (:effects (item id))))
(defn- undo-depth [] (count (get-in (state/get-raw-state) [:history :undo-stack])))

(defn- node
  "The first node matching selector in any window."
  ^Node [selector]
  (first (ui/query-all selector)))

(defn- intensity [id] {:id id :effect-id :intensity :enabled? true :params {:amount 1.0}})

(defn- open-on-circle!
  "The cue at [0 0]: Circle with the Intensity effects A and B, then Square
   with C; Circle and its A selected, in the cue chain editor once A's
   Amount shows. Returns their ids and items."
  []
  (let [[circle square a b c] (repeatedly 5 random-uuid)
        circle-item {:type :preset :id circle :preset-id :circle :enabled? true
                     :params {:radius 0.5 :num-points 64} :effects [(intensity a) (intensity b)]}
        square-item {:type :preset :id square :preset-id :square :enabled? true
                     :params {:size 0.5 :num-points 16} :effects [(intensity c)]}]
    (events/write-state! #(-> %
                              (assoc-in [:chains :cue-chains [0 0]] {:items [circle-item square-item]})
                              (assoc-in [:list-ui [:cue-chain 0 0]] {:selected-ids #{circle} :last-selected-id circle})
                              (assoc-in [:list-ui [:item-effects 0 0]] {:selected-ids #{a} :last-selected-id a})))
    (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
    (ui/wait-for! #(and (some? (node "#param-amount-field")) (= 2 (count (ui/query-all (str item-effects-list " .chain-item"))))))
    {:circle circle :square square :a a :b b :c c :circle-item circle-item :square-item square-item}))

(defn- set-items!
  "What the cue list sends for the cue's items: a reorder, or a delete.
   FX thread, so what follows in the same turn is queued behind it."
  [new-items]
  (events/dispatch! {:event/type :chain/set-items :domain :cue-chains :entity-key [0 0] :items new-items}))

(defn- start-drag!
  "Start a list drag of effect-id in the item effects list, as a row's
   drag-detected does (the headless platform can't start a native one),
   while Circle is first."
  [effect-id]
  (events/dispatch! {:event/type :list/start-drag :component-id [:item-effects 0 0] :item-id effect-id
                     :items-path [:chains :cue-chains [0 0] :items 0 :effects]})
  (ui/wait-for! #(seq (get-in (state/get-raw-state) [:list-ui [:item-effects 0 0] :dragging-ids]))))

(defn- drop-after!
  "Drag the list drag's item, effect-id, over the lower half of row and drop
   it there (after the row's effect), as the platform does: DRAG_ENTERED,
   DRAG_OVER, DRAG_DROPPED. FX thread."
  [^Node row effect-id]
  (let [db (ui/test-dragboard (doto (ClipboardContent.) (.putString (pr-str effect-id))) [TransferMode/MOVE])
        y (- (.getHeight (.getBoundsInLocal row)) 2.0)
        scene (.localToScene row 20.0 y)
        screen (.localToScreen row 20.0 y)]
    (doseq [type [DragEvent/DRAG_ENTERED DragEvent/DRAG_OVER DragEvent/DRAG_DROPPED]]
      (Event/fireEvent row (DragEvent. type db (.getX scene) (.getY scene) (.getX screen) (.getY screen)
                                       TransferMode/MOVE nil row nil)))))

(defn- type-into!
  "Type text into field and press Enter (commit-field commits on its
   action). FX thread."
  [^TextField field text]
  (.setText field text)
  (.fireEvent field (ActionEvent.)))

(defn- bank-button
  "The effect bank's button for the effect named effect-name."
  ^Button [effect-name]
  (some #(when (= effect-name (.getText ^Labeled %)) %) (ui/query-all ".bank-item-btn")))

(defn- effect-rows [] (ui/query-all (str item-effects-list " .chain-item")))

(deftest item-effect-inputs-behind-a-reorder-land-on-their-item-test
  (let [{:keys [circle square a b c]} (open-on-circle!)
        reorder! #(set-items! (vec (reverse (items))))]
    (testing "a drop in the item effects list, queued behind the cue list's reorder"
      (start-drag! a)
      (let [before (undo-depth)
            b-row (second (effect-rows))]
        (ui/on-fx-thread #(do (reorder!)
                              (drop-after! b-row a)))
        (is (ui/wait-for! #(= [b a] (effect-ids circle))) "Circle's effects, now second")
        (is (= [square circle] (mapv :id (items))))
        (is (= [c] (effect-ids square)) "Square's, in Circle's place, as they were")
        (is (= (+ before 2) (undo-depth)) "the reorder's undo step, and the drop's")))
    (testing "a click in the effect bank, queued behind the reorder back"
      (let [before (undo-depth)
            scale (bank-button "Scale")]
        (ui/on-fx-thread #(do (reorder!)
                              (.fire scale)))
        (is (ui/wait-for! #(= 3 (count (effect-ids circle)))) "a new effect on Circle, first again")
        (is (= [b a] (pop (effect-ids circle))))
        (is (= :scale (:effect-id (peek (:effects (item circle))))))
        (is (= [circle square] (mapv :id (items))))
        (is (= [c] (effect-ids square)) "Square's as they were")
        (is (= (+ before 2) (undo-depth)) "the reorder's undo step, and the add's")))
    (testing "A's Amount, typed, queued behind another reorder"
      ;; The add selected the new effect: A again, so its Amount shows
      (events/dispatch! {:event/type :list/select-item :component-id [:item-effects 0 0] :item-id a :mode :single})
      (ui/wait-for! #(some? (node "#param-amount-field")))
      (let [before (undo-depth)
            field (node "#param-amount-field")]
        (ui/on-fx-thread #(do (reorder!)
                              (type-into! field "0.4")))
        (is (ui/wait-for! #(= [1.0 0.4] (pop (amounts circle)))) "A's (Circle's second effect), Circle now second")
        (is (= [square circle] (mapv :id (items))))
        (is (= [1.0] (amounts square)) "Square's C, where A was shown, as it was")
        (is (= (+ before 2) (undo-depth)) "the reorder's undo step, and the edit's")))))

(deftest a-deleted-items-late-inputs-do-nothing-test
  (let [{:keys [circle square a]} (open-on-circle!)]
    (start-drag! a)
    (let [before (undo-depth)
          square-before (item square)
          b-row (second (effect-rows))
          scale (bank-button "Scale")
          field (node "#param-amount-field")
          delete (node (str item-effects-list " #chain-delete-selection"))]
      ;; The cue list's delete of Circle, then Circle's inputs, in one turn:
      ;; a drop in its effects list, a bank click, A's typed Amount and the
      ;; list's Delete (A is selected)
      (ui/on-fx-thread #(do (set-items! (filterv (fn [i] (not= circle (:id i))) (items)))
                            (drop-after! b-row a)
                            (.fire ^Button scale)
                            (type-into! field "0.4")
                            (.fire ^Button delete)))
      (is (ui/wait-for! #(= [square] (mapv :id (items)))))
      (is (events/flush!))
      (is (= [square-before] (items)) "Square, in Circle's place, exactly as it was")
      (is (= (inc before) (undo-depth)) "the delete's undo step, and no other"))))
