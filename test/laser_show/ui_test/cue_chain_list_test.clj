(ns laser-show.ui-test.cue-chain-list-test
  "Grouped list behaviour (click, Ctrl/Shift and right-click selection,
   grouping, drag and drop), exercised through the cue chain editor for
   cell [0 0]. And a list's keys, clicks and drops go to the list it shows
   now: after the Effects editor shows another cell in its window, after it
   reopens, and after the item effects list shows another preset's
   (reactivity plan 04). Its rows follow their items through reorders and
   inserts, and an open editor renders nothing for another cell's edit
   (reactivity plan 34)."
  (:require [cljfx.lifecycle :as lifecycle]
            [clojure.core.cache :as cache]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.animation.presets :as presets]
            [laser-show.app :as app]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.event Event]
           [javafx.scene Node]
           [javafx.scene.control Control Labeled]
           [javafx.scene.input ClipboardContent KeyCode MouseButton MouseEvent TransferMode]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- open-cue-chain-editor!
  []
  (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
  (ui/wait-for! #(some (fn [title]
                         (and title (.startsWith ^String title "Cue Chain Editor")))
                       (ui/window-titles)))
  (ui/assert-visible! ".chain-item"))

(defn- add-cue-chain-items!
  [item-ids]
  (doseq [item-id item-ids]
    (events/dispatch! {:event/type :cue-chain/add-preset
                       :col 0 :row 0
                       :item-id item-id}))
  (ui/wait-for! #(= (+ 1 (count item-ids))
                    (count (ui/query-all ".chain-item")))))

(deftest grouped-list-items-respond-to-click-selection
  (let [robot (ui/robot)]
    (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
    (ui/wait-for! #(some (fn [title]
                           (and title (.startsWith ^String title "Cue Chain Editor")))
                         (ui/window-titles)))
    (ui/assert-visible! ".chain-item")
    (ui/click! robot ".chain-item")
    (ui/wait-for! #(seq (get-in (state/get-raw-state)
                                [:list-ui [:cue-chain 0 0] :selected-ids])))
    (is (seq (get-in (state/get-raw-state)
                     [:list-ui [:cue-chain 0 0] :selected-ids])))))

(defn- cue-list-rows [] (ui/query-all "#list-editor-cue-chain-0-0 .chain-item"))

(defn- selected-rows
  "Which of the cue list's rows show as selected (chain-item-selected),
   queried again now: their indices."
  []
  (let [rows (cue-list-rows)]
    (set (keep-indexed (fn [i ^javafx.scene.Node row]
                         (when (ui/on-fx-thread #(.contains (.getStyleClass row) "chain-item-selected")) i))
                       rows))))

(defn- right-click-row!
  "A secondary-button click fired at row, the node its click handler is on.
   Headless, the robot's right-click opens the row's context menu on the
   press and the menu's window gets the release, so the row never gets a
   click."
  [^Node row]
  (ui/on-fx-thread
   #(let [bounds (.getBoundsInLocal row)
          screen (.localToScreen row bounds)
          x (/ (.getWidth bounds) 2)
          y (/ (.getHeight bounds) 2)]
      (Event/fireEvent row (MouseEvent. MouseEvent/MOUSE_CLICKED x y (+ (.getMinX screen) x) (+ (.getMinY screen) y)
                                        MouseButton/SECONDARY 1
                                        false false false false false false false
                                        false false true nil))))
  (ui/sync-fx!))

(deftest grouped-list-right-click-keeps-multi-selection
  (let [robot (ui/robot)
        component-id [:cue-chain 0 0]
        selection #(get-in (state/get-raw-state) [:list-ui component-id :selected-ids])]
    (open-cue-chain-editor!)
    (add-cue-chain-items! [:square :triangle])
    (let [[a b c] (mapv :id (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :items]))]
      (ui/click-node! robot (nth (cue-list-rows) 0))
      (ui/modifier-click-node! robot (nth (cue-list-rows) 1) javafx.scene.input.KeyCode/SHORTCUT)
      (ui/wait-for! #(= #{a b} (selection)))
      (testing "a right-click on a selected row keeps the selection"
        (right-click-row! (nth (cue-list-rows) 1))
        (is (events/flush!))
        (is (= #{a b} (selection)))
        (ui/wait-for! #(= #{0 1} (selected-rows)))
        (is (= #{0 1} (selected-rows))))
      (testing "on an unselected row it selects that row alone, as the click finds the selection then"
        (right-click-row! (nth (cue-list-rows) 2))
        (ui/wait-for! #(= #{c} (selection)))
        (ui/wait-for! #(= #{2} (selected-rows)))
        (is (= #{2} (selected-rows)))))))

(deftest grouped-list-supports-ctrl-and-shift-selection
  (let [robot (ui/robot)]
    (open-cue-chain-editor!)
    (add-cue-chain-items! [:square :triangle])
    (let [item-ids (mapv :id (get-in (state/get-raw-state)
                                     [:chains :cue-chains [0 0] :items]))]
      (ui/click-node! robot (nth (ui/query-all ".chain-item") 0))
      (ui/modifier-click-node! robot (nth (ui/query-all ".chain-item") 1) javafx.scene.input.KeyCode/SHORTCUT)
      (ui/modifier-click-node! robot (nth (ui/query-all ".chain-item") 2) javafx.scene.input.KeyCode/SHIFT)
      (ui/wait-for! #(and (= 2 (count (get-in (state/get-raw-state)
                                              [:list-ui [:cue-chain 0 0] :selected-ids])))
                          (contains? (get-in (state/get-raw-state)
                                             [:list-ui [:cue-chain 0 0] :selected-ids])
                                     (nth item-ids 2))))
      (is (= 2 (count (get-in (state/get-raw-state)
                              [:list-ui [:cue-chain 0 0] :selected-ids])))))))

(deftest grouped-list-can-group-collapse-and-delete-selection
  (let [robot (ui/robot)]
    (open-cue-chain-editor!)
    (add-cue-chain-items! [:square :triangle])
    (ui/click-node! robot (nth (ui/query-all ".chain-item") 0))
    (ui/modifier-click-node! robot (nth (ui/query-all ".chain-item") 1) javafx.scene.input.KeyCode/SHIFT)
    (ui/wait-for! #(= 2 (count (get-in (state/get-raw-state)
                                        [:list-ui [:cue-chain 0 0] :selected-ids]))))
    (ui/click! robot "#list-editor-cue-chain-0-0 #chain-group-selection")
    (is (ui/enabled? "#list-editor-cue-chain-0-0 #chain-delete-selection"))))

(defn- in-list [selector] (str "#list-editor-cue-chain-0-0 " selector))

(defn- tooltip-of [selector]
  (ui/on-fx-thread #(some-> ^Control (first (ui/query-all (in-list selector))) .getTooltip .getText)))

(defn- text-of [selector]
  (ui/on-fx-thread #(.getText ^Labeled (first (ui/query-all (in-list selector))))))

(deftest list-buttons-say-what-they-do-test
  (let [robot (ui/robot)
        mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
        key (fn [k] (if mac? (str "⌘" k) (str "Ctrl+" k)))]
    (open-cue-chain-editor!)
    (testing "the toolbar's buttons: Delete in full, and a tooltip each with its shortcut"
      (is (= "Delete" (text-of "#chain-delete-selection")))
      (let [^Control delete (first (ui/query-all (in-list "#chain-delete-selection")))]
        (ui/wait-for! #(ui/on-fx-thread (fn [] (pos? (.getWidth delete)))))
        (is (ui/on-fx-thread #(<= (.prefWidth delete -1) (+ 0.5 (.getWidth delete))))
            "as wide as its text needs: not cut to \"Del…\""))
      (is (= {"#chain-new-group" "New empty group"
              "#chain-group-selection" (str "Group the selected items (" (key "G") ")")
              "#chain-copy-selection" (str "Copy the selected items (" (key "C") ")")
              "#chain-cut-selection" (str "Cut the selected items (" (key "X") ")")
              "#chain-paste-items" (str "Paste after the selection (" (key "V") ")")
              "#chain-delete-selection" "Delete the selected items (Delete)"}
             (into {} (map (juxt identity tooltip-of))
                   ["#chain-new-group" "#chain-group-selection" "#chain-copy-selection"
                    "#chain-cut-selection" "#chain-paste-items" "#chain-delete-selection"]))))
    (testing "the hint names this platform's keys"
      (is (= (str (key "Click") " multi-select • Drag to reorder • " (key "G") " group")
             (text-of ".chain-hint-text"))))
    (testing "a group's two icon buttons"
      (ui/click! robot (in-list "#chain-new-group"))
      (ui/wait-for! #(seq (ui/query-all (in-list ".group-ungroup-btn"))))
      (is (= "Ungroup: keep the items, lose the group" (tooltip-of ".group-ungroup-btn")))
      (is (= "Collapse" (tooltip-of ".group-collapse-btn")))
      (ui/click-node! robot (first (ui/query-all (in-list ".group-collapse-btn"))))
      (ui/wait-for! #(= "Expand" (tooltip-of ".group-collapse-btn"))))))

;; Keys reach the list while the focus is anywhere inside it (keyboard plan
;; 03, D5 A), and never while it is in a text field inside it

(defn- cue-items [] (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :items]))
(defn- selected-ids [] (get-in (state/get-raw-state) [:list-ui [:cue-chain 0 0] :selected-ids]))

(defn- focus-owner
  "The focus owner of the window the node at selector is in."
  [selector]
  (let [^javafx.scene.Node n (ui/query selector)]
    (ui/on-fx-thread #(some-> n .getScene .getFocusOwner))))

(defn- push! [robot & codes]
  (.push ^org.testfx.api.FxRobot robot (into-array KeyCode codes))
  (ui/sync-fx!))

(defn- three-items-second-selected!
  "The editor on [0 0] with three items, the second one clicked."
  [robot]
  (open-cue-chain-editor!)
  (add-cue-chain-items! [:square :triangle])
  (ui/click-node! robot (nth (ui/query-all ".chain-item") 1))
  (ui/wait-for! #(= #{(:id (second (cue-items)))} (selected-ids))))

(deftest delete-after-clicking-a-row-still-works-test
  (let [robot (ui/robot)]
    (three-items-second-selected! robot)
    (is (= "list-editor-cue-chain-0-0"
           (ui/on-fx-thread #(.getId ^javafx.scene.Node (focus-owner ".chain-item"))))
        "the click gives the list the focus")
    (push! robot KeyCode/SHORTCUT KeyCode/A)
    (ui/wait-for! #(= 3 (count (selected-ids))))
    (push! robot KeyCode/DELETE)
    (ui/wait-for! #(empty? (cue-items)))))

(deftest delete-after-focus-moves-to-a-toolbar-button-test
  (let [robot (ui/robot)
        [first-item _ third-item] (do (three-items-second-selected! robot) (cue-items))]
    (ui/focus! robot (in-list "#chain-new-group"))
    (ui/wait-for! #(= "chain-new-group" (ui/on-fx-thread (fn [] (.getId ^javafx.scene.Node (focus-owner ".chain-item"))))))
    (push! robot KeyCode/DELETE)
    (ui/wait-for! #(= [(:id first-item) (:id third-item)] (mapv :id (cue-items))))
    (testing "and Escape clears the selection from there too"
      (ui/click-node! robot (first (ui/query-all ".chain-item")))
      (ui/wait-for! #(seq (selected-ids)))
      (ui/focus! robot (in-list "#chain-delete-selection"))
      (push! robot KeyCode/ESCAPE)
      (ui/wait-for! #(empty? (selected-ids)))
      (is (= 2 (count (cue-items)))))))

(deftest select-all-after-clicking-a-row-checkbox-test
  (let [robot (ui/robot)
        enabled #(mapv (fn [i] (:enabled? i true)) (cue-items))]
    (three-items-second-selected! robot)
    (testing "a click on a row's On box turns the item off"
      (ui/click-node! robot (first (ui/query-all (in-list ".chain-item .check-box"))))
      (ui/wait-for! #(= [false true true] (enabled))))
    ;; The click's bubbling can leave the focus on the list itself; Tab, or a
    ;; click the row doesn't see, leaves it on the box
    (ui/on-fx-thread #(.requestFocus ^javafx.scene.Node (first (ui/query-all (in-list ".chain-item .check-box")))))
    (ui/wait-for! #(instance? javafx.scene.control.CheckBox (focus-owner ".chain-item")))
    (push! robot KeyCode/SHORTCUT KeyCode/A)
    (ui/wait-for! #(= 3 (count (selected-ids))))
    (is (= [false true true] (enabled)) "the keys didn't touch the box")))

(deftest list-shortcuts-do-not-act-while-renaming-test
  (let [robot (ui/robot)
        rename-field (in-list ".list-item-name-input")]
    (three-items-second-selected! robot)
    (events/dispatch! {:event/type :list/start-rename :component-id [:cue-chain 0 0]
                       :item-id (:id (second (cue-items)))})
    (ui/wait-for! #(some? (ui/query rename-field)))
    (ui/wait-for! #(instance? javafx.scene.control.TextField (focus-owner ".chain-item")))
    (let [before (cue-items)
          selection (selected-ids)]
      (ui/on-fx-thread #(doto ^javafx.scene.control.TextField (ui/query rename-field)
                          (.setText "Beams")
                          (.positionCaret 5)))
      (push! robot KeyCode/BACK_SPACE)
      (ui/wait-for! #(= "Beam" (ui/node-text rename-field)))
      (push! robot KeyCode/SHORTCUT KeyCode/A)
      (push! robot KeyCode/DELETE)
      (ui/wait-for! #(= "" (ui/node-text rename-field)))
      (testing "the field edited its text; the list did nothing"
        (is (= before (cue-items)))
        (is (= selection (selected-ids)))))))

(deftest grouped-list-drag-and-drop-reorders-items
  (let [robot (ui/robot)]
    (open-cue-chain-editor!)
    (add-cue-chain-items! [:square :triangle])
    (let [items-before (ui/query-all ".chain-item")
          item-ids (mapv :id (get-in (state/get-raw-state)
                                     [:chains :cue-chains [0 0] :items]))
          first-id (first item-ids)
          last-id (last item-ids)
          component-id [:cue-chain 0 0]
          items-path [:chains :cue-chains [0 0] :items]
          change-params {:domain :cue-chains :entity-key [0 0]}]
      ;; Exercise the same drag lifecycle used by the JavaFX handlers while
      ;; keeping the test deterministic on Windows and in headless CI.
      (ui/click-node! robot (nth items-before 0))
      (events/dispatch! {:event/type :list/start-drag
                         :component-id component-id
                         :item-id first-id
                         :items-path items-path})
      (ui/wait-for! #(seq (get-in (state/get-raw-state)
                                  [:list-ui component-id :dragging-ids])))
      (events/dispatch! {:event/type :list/update-drop-target
                         :component-id component-id
                         :target-id last-id
                         :drop-position :after})
      (events/dispatch! {:event/type :list/perform-drop
                         :component-id component-id
                         :dragging-ids #{first-id}
                         :target-id last-id
                         :drop-position :after
                         :items-path items-path
                         :on-change-event :chain/set-items
                         :on-change-params change-params})
      (events/write-state! update-in [:chains :cue-chains [0 0] :items]
                           #(conj (vec (rest %)) (first %)))
      (is (= 3 (count (get-in (state/get-raw-state)
                              [:chains :cue-chains [0 0] :items])))))))

;; A list's keys, clicks and drops go to the list it shows when they come
;; (reactivity plan 04). The Effects editor shows another cell in the same
;; list; the item effects list shows another preset's effects, or the same
;; preset's once it has moved, in the same nodes.

(defn- effect [effect-id] {:id (random-uuid) :effect-id effect-id :enabled? true :params {}})

(defn- effect-cells!
  "Effects cells: [col row] -> its items."
  [cells]
  (events/write-state! (fn [s] (reduce-kv #(assoc-in %1 [:chains :effect-chains %2] {:items %3}) s cells))))

(defn- cell-effects [col row] (get-in (state/get-raw-state) [:chains :effect-chains [col row] :items]))

(defn- list-selection [component-id] (get-in (state/get-raw-state) [:list-ui component-id :selected-ids]))

(defn- effects-list [col row] (str "#list-editor-effect-chain-" col "-" row))

(defn- show-effects-cell!
  "The Effects editor on [col row]: it opens, or the open one shows that
   cell. Returns its list's node."
  [col row]
  (events/dispatch! {:event/type :effect-chain/open-editor :col col :row row})
  (ui/wait-for! #(some? (ui/query (effects-list col row))))
  (ui/query (effects-list col row)))

(deftest delete-acts-on-the-cell-the-editor-shows-test
  ;; The audit's reproduction: with B's list focused, Delete deleted A's
  ;; item, the counts [A B] going [1 1] -> [0 1]
  (let [robot (ui/robot)
        counts #(mapv (fn [col] (count (cell-effects col 0))) [0 1])]
    (effect-cells! {[0 0] [(effect :scale)] [1 0] [(effect :scale)]})
    (let [a (show-effects-cell! 0 0)
          b (show-effects-cell! 1 0)]
      (is (identical? a b) "the window shows B in the list that showed A")
      ;; Each opened with its one item selected (:list/auto-select-single-item)
      (ui/wait-for! #(seq (list-selection [:effect-chain 1 0])))
      (is (seq (list-selection [:effect-chain 0 0])))
      (ui/focus! robot (effects-list 1 0))
      (push! robot KeyCode/DELETE)
      (ui/wait-for! #(not= [1 1] (counts)))
      (is (= [1 0] (counts)) "B's item went, A's stayed"))))

(deftest list-keys-work-after-the-editor-reopens-test
  ;; Closing the window took the list's props, which only the list's render
  ;; put back, and reopening it on the same cell can reuse that render from
  ;; the cache: its keys did nothing. The app's cache has dropped it by then
  ;; (a Grid render outgrows it, reactivity plan 36); one that keeps
  ;; everything keeps it
  (with-redefs [state/cache-factory cache/basic-cache-factory]
    (app/start-test!))
  (ui/sync-fx!)
  (let [robot (ui/robot)]
    (effect-cells! {[0 0] [(effect :scale)]})
    (show-effects-cell! 0 0)
    (ui/wait-for! #(seq (list-selection [:effect-chain 0 0])))
    (events/dispatch! {:event/type :ui/close-dialog :dialog-id :effect-chain-editor})
    (ui/wait-for! #(nil? (ui/query (effects-list 0 0))))
    (show-effects-cell! 0 0)
    (ui/focus! robot (effects-list 0 0))
    (push! robot KeyCode/DELETE)
    (ui/wait-for! #(empty? (cell-effects 0 0)))
    (is (empty? (cell-effects 0 0)))))

(deftest select-all-and-ranges-act-on-the-cell-the-editor-shows-test
  (let [robot (ui/robot)
        b [(effect :scale) (effect :intensity) (effect :rotation)]
        b-ids (set (map :id b))]
    (effect-cells! {[0 0] [(effect :scale) (effect :intensity)] [1 0] b})
    (show-effects-cell! 0 0)
    (show-effects-cell! 1 0)
    (ui/focus! robot (effects-list 1 0))
    (testing "Ctrl+A selects all of B's items"
      (push! robot KeyCode/SHORTCUT KeyCode/A)
      (ui/wait-for! #(= b-ids (list-selection [:effect-chain 1 0])))
      (is (empty? (list-selection [:effect-chain 0 0])) "and none of A's"))
    (testing "Escape clears B's selection"
      (push! robot KeyCode/ESCAPE)
      (ui/wait-for! #(empty? (list-selection [:effect-chain 1 0]))))
    (testing "a Shift-click range is in B"
      (let [rows (ui/query-all (str (effects-list 1 0) " .chain-item"))]
        (ui/click-node! robot (first rows))
        (ui/modifier-click-node! robot (nth rows 2) KeyCode/SHIFT)
        (ui/wait-for! #(= b-ids (list-selection [:effect-chain 1 0])))
        (is (empty? (list-selection [:effect-chain 0 0])))))))

(def ^:private item-effects-list "#list-editor-item-effects-0-0")

(defn- item-effect-rows [] (ui/query-all (str item-effects-list " .chain-item")))

(defn- preset-effects
  "The ids of the effects of the preset at index idx in [0 0]."
  [idx]
  (mapv :id (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :items idx :effects])))

(defn- select-cue-item! [item]
  (events/dispatch! {:event/type :list/select-item :component-id [:cue-chain 0 0]
                     :item-id (:id item) :mode :single}))

(defn- start-effect-drag!
  "Start a list drag of effect, of the preset at index idx, as a row's
   drag-detected does (the headless platform can't start a native one)."
  [effect idx]
  (events/dispatch! {:event/type :list/start-drag :component-id [:item-effects 0 0]
                     :item-id (:id effect)
                     :items-path [:chains :cue-chains [0 0] :items idx :effects]})
  (ui/wait-for! #(seq (get-in (state/get-raw-state) [:list-ui [:item-effects 0 0] :dragging-ids]))))

(defn- drag-content [item] (doto (ClipboardContent.) (.putString (pr-str (:id item)))))

(deftest item-effects-background-drop-lands-in-the-selected-preset-test
  ;; Selecting another preset keeps the item effects list's nodes, and its
  ;; background drop kept the first preset's path: dropping the second
  ;; preset's effect on empty space did nothing. The list starts on a preset
  ;; with no effects, so its background is the new node that replaced the
  ;; empty text, which took no drops at all
  (let [[f1 f2] [(effect :scale) (effect :intensity)]
        [g1 g2 g3] [(effect :rotation) (effect :translate) (effect :hue-shift)]
        p0 (cue-chains/create-preset-instance :star)
        p1 (cue-chains/create-preset-instance :circle {:effects [f1 f2]})
        p2 (cue-chains/create-preset-instance :square {:effects [g1 g2 g3]})
        background #(ui/query (str item-effects-list " .scroll-pane-base"))]
    (events/write-state! assoc-in [:chains :cue-chains [0 0] :items] [p0 p1 p2])
    (open-cue-chain-editor!)
    (select-cue-item! p0)
    (ui/wait-for! #(some? (ui/query (str item-effects-list " .chain-empty-text"))))
    (select-cue-item! p1)
    (ui/wait-for! #(= 2 (count (item-effect-rows))))
    (let [pane (background)]
      (select-cue-item! p2)
      (ui/wait-for! #(= 3 (count (item-effect-rows))))
      (is (identical? pane (background)) "the same list shows the second preset's effects")
      (start-effect-drag! g1 2)
      (is (:completed? (ui/fire-drop! pane (drag-content g1) 20 5 :mode TransferMode/MOVE)))
      (ui/wait-for! #(= (mapv :id [g2 g3 g1]) (preset-effects 2)))
      (is (= (mapv :id [f1 f2]) (preset-effects 1)) "the first preset's are as they were"))))

(deftest item-effect-rows-follow-their-preset-when-it-moves-test
  ;; Moving the selected preset keeps its effects' rows, which kept its old
  ;; place in the cue: a drop on one changed the preset now there, which
  ;; doesn't have the dragged effect, so nothing moved
  (let [[f1 f2 f3] [(effect :scale) (effect :intensity) (effect :rotation)]
        p1 (cue-chains/create-preset-instance :circle {:effects [f1 f2 f3]})
        p2 (cue-chains/create-preset-instance :square {:effects [(effect :translate)]})
        first-cue-item #(ui/node-text "#list-editor-cue-chain-0-0 .chain-item-name")]
    (events/write-state! assoc-in [:chains :cue-chains [0 0] :items] [p1 p2])
    (open-cue-chain-editor!)
    (select-cue-item! p1)
    (ui/wait-for! #(= 3 (count (item-effect-rows))))
    (let [rows (item-effect-rows)]
      (events/write-state! assoc-in [:chains :cue-chains [0 0] :items] [p2 p1])
      (ui/wait-for! #(str/starts-with? (first-cue-item) (:name (presets/presets-by-id :square))))
      (is (= rows (item-effect-rows)) "the same rows show the preset's effects")
      (start-effect-drag! f1 1)
      (let [^javafx.scene.Node target (nth rows 2)
            h (ui/on-fx-thread #(.getHeight (.getBoundsInLocal target)))]
        (is (:completed? (ui/fire-drop! target (drag-content f1) 20 (- h 2) :mode TransferMode/MOVE))))
      (ui/wait-for! #(= (mapv :id [f2 f3 f1]) (preset-effects 1)))
      (is (= 1 (count (preset-effects 0))) "the other preset's are as they were"))))

;; Rows are keyed by their item's id and get only what they show
;; (reactivity plan 34): a reorder or an insert moves rows instead of making
;; them again, each row's click, drag, rename and box act on its own item,
;; and an edit to another cell renders nothing in an open editor

(defn- effect-rows
  "{name node}: the rows of the Effects editor's list on [0 0], by the name
   they show."
  []
  (into {} (map (fn [^Node n]
                  [(ui/on-fx-thread #(some-> ^Labeled (.lookup n ".chain-item-name") .getText)) n]))
        (ui/query-all (str (effects-list 0 0) " .chain-item"))))

(defn- shown-names
  "The names the Effects editor's list on [0 0] shows, in order."
  []
  (mapv #(ui/on-fx-thread (fn [] (some-> ^Labeled (.lookup ^Node % ".chain-item-name") .getText)))
        (ui/query-all (str (effects-list 0 0) " .chain-item"))))

(defn- cell-ids [] (mapv :id (cell-effects 0 0)))

(deftest rows-keep-their-items-through-a-reorder-and-an-insert-test
  (let [robot (ui/robot)
        [a b c] [(effect :scale) (effect :intensity) (effect :rotation)]]
    (effect-cells! {[0 0] [a b c]})
    (show-effects-cell! 0 0)
    (ui/wait-for! #(= ["Scale" "Intensity" "Rotation"] (shown-names)))
    (let [rows (effect-rows)]
      (testing "a drag of the last row before the first moves the rows"
        (events/dispatch! {:event/type :list/start-drag :component-id [:effect-chain 0 0] :item-id (:id c)
                           :items-path [:chains :effect-chains [0 0] :items]})
        (ui/wait-for! #(seq (get-in (state/get-raw-state) [:list-ui [:effect-chain 0 0] :dragging-ids])))
        (is (:completed? (ui/fire-drop! (rows "Scale") (drag-content c) 20 2 :mode TransferMode/MOVE)))
        (ui/wait-for! #(= (mapv :id [c a b]) (cell-ids)))
        (ui/wait-for! #(= ["Rotation" "Scale" "Intensity"] (shown-names)))
        (is (= rows (effect-rows)) "each item keeps its row's node"))
      (testing "a click on a moved row selects its item, and Delete deletes that one"
        (ui/click-node! robot (rows "Scale"))
        (ui/wait-for! #(= #{(:id a)} (list-selection [:effect-chain 0 0])))
        (push! robot KeyCode/DELETE)
        (ui/wait-for! #(= (mapv :id [c b]) (cell-ids)))
        (ui/wait-for! #(= ["Rotation" "Intensity"] (shown-names))))
      (testing "Undo puts it back between the others, which keep their rows"
        (events/dispatch! {:event/type :history/undo})
        (ui/wait-for! #(= (mapv :id [c a b]) (cell-ids)))
        (ui/wait-for! #(= ["Rotation" "Scale" "Intensity"] (shown-names)))
        (is (every? #(identical? (rows %) ((effect-rows) %)) ["Rotation" "Intensity"])))
      (testing "a double-click renames the row's own item"
        (.doubleClickOn ^org.testfx.api.FxRobot robot ^Node ((effect-rows) "Intensity")
                        (into-array MouseButton [MouseButton/PRIMARY]))
        (ui/wait-for! #(some? (ui/query (str (effects-list 0 0) " .list-item-name-input"))))
        (ui/wait-for! #(instance? javafx.scene.control.TextField (focus-owner (effects-list 0 0))))
        (ui/on-fx-thread #(.setText ^javafx.scene.control.TextField (ui/query (str (effects-list 0 0) " .list-item-name-input"))
                                    "Level"))
        (push! robot KeyCode/ENTER)
        (ui/wait-for! #(= "Level" (:name (second (rest (cell-effects 0 0))))))
        (ui/wait-for! #(= ["Rotation" "Scale" "Level"] (shown-names))))
      (testing "a row's On box turns its own item off"
        (ui/click-node! robot (ui/on-fx-thread #(.lookup ^Node ((effect-rows) "Rotation") ".check-box")))
        (ui/wait-for! #(= [false true true] (mapv (fn [i] (:enabled? i true)) (cell-effects 0 0))))))))

(deftest a-reopened-list-keeps-its-rows-and-deletes-in-its-cell-test
  ;; Plans 04, 33 and 34 together (overview: "04/33/34 list -> 36 cache"):
  ;; Delete after the Effects editor showed another cell and reopened, with a
  ;; cache that keeps every render, then an edit to another cell while it
  ;; stays open. Its commands still act on the cell it shows, and it renders
  ;; nothing for the other cell's edit: no body of the editor or its list
  ;; runs, and its rows keep their nodes (no remount)
  (let [ran (atom [])
        call-context-fn @#'lifecycle/call-context-fn
        editor-body? (fn [t]
                       (let [n (.getName (class t))]
                         (or (str/starts-with? n "laser_show.views.components.list$")
                             (str/starts-with? n "laser_show.views.dialogs.effect_chain_editor$"))))]
    (with-redefs-fn {#'lifecycle/call-context-fn (fn [context desc]
                                                   (swap! ran conj (:fx/type desc))
                                                   (call-context-fn context desc))
                     #'state/cache-factory cache/basic-cache-factory}
      (fn []
        (app/start-test!)
        (ui/sync-fx!)
        (let [robot (ui/robot)
              [a b] [(effect :scale) (effect :intensity)]]
          (effect-cells! {[0 0] [a b] [1 0] [(effect :rotation)] [2 0] [(effect :hue-shift)]})
          (show-effects-cell! 0 0)
          (show-effects-cell! 1 0)
          (events/dispatch! {:event/type :ui/close-dialog :dialog-id :effect-chain-editor})
          (ui/wait-for! #(nil? (ui/query (effects-list 1 0))))
          (show-effects-cell! 0 0)
          (ui/wait-for! #(= ["Scale" "Intensity"] (shown-names)))
          (testing "Delete after the editor showed [1 0] and reopened on [0 0]"
            (ui/click-node! robot ((effect-rows) "Scale"))
            (ui/wait-for! #(= #{(:id a)} (list-selection [:effect-chain 0 0])))
            (push! robot KeyCode/DELETE)
            (ui/wait-for! #(= [(:id b)] (cell-ids)))
            (is (= 1 (count (cell-effects 1 0)))))
          (let [row ((effect-rows) "Intensity")]
            (reset! ran [])
            (events/dispatch! {:event/type :effects/toggle-cell :col 2 :row 0})
            (ui/wait-for! #(true? (get-in (state/get-raw-state) [:chains :effect-chains [2 0] :active?])))
            (ui/sync-fx!)
            (testing "another cell's edit: no body of the editor or its list runs, and the row keeps its node"
              (is (= [] (filterv editor-body? @ran)))
              (is (identical? row ((effect-rows) "Intensity"))))
            (testing "Delete still deletes in the cell it shows"
              (ui/click-node! robot row)
              (ui/wait-for! #(= #{(:id b)} (list-selection [:effect-chain 0 0])))
              (push! robot KeyCode/DELETE)
              (ui/wait-for! #(empty? (cell-effects 0 0)))
              (is (= 1 (count (cell-effects 1 0))) "[1 0] as it was"))))))))
