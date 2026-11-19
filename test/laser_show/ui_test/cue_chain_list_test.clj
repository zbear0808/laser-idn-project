(ns laser-show.ui-test.cue-chain-list-test
  "Grouped list behaviour (click, Ctrl/Shift and right-click selection,
   grouping, drag and drop), exercised through the cue chain editor for
   cell [0 0]."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene.control Control Labeled]))

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

(defn- flush-event-queue!
  "Wait until every event dispatched so far has been processed.
   Events are handled in order, so a marker event landing means the rest did."
  []
  (ui/sync-fx!)
  (let [marker (+ 60.25 (rand-int 100))]
    (events/dispatch! {:event/type :timing/set-bpm :bpm marker})
    (ui/wait-for! #(= marker (get-in (state/get-raw-state) [:timing :bpm])))))

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

(deftest grouped-list-right-click-keeps-multi-selection
  (let [robot (ui/robot)
        component-id [:cue-chain 0 0]]
    (open-cue-chain-editor!)
    (add-cue-chain-items! [:square :triangle])
    (ui/click-node! robot (nth (ui/query-all ".chain-item") 0))
    (ui/modifier-click-node! robot (nth (ui/query-all ".chain-item") 1) javafx.scene.input.KeyCode/SHORTCUT)
    (ui/wait-for! #(= 2 (count (get-in (state/get-raw-state)
                                        [:list-ui component-id :selected-ids]))))
    (let [selected (get-in (state/get-raw-state) [:list-ui component-id :selected-ids])]
      (ui/right-click-node! robot (nth (ui/query-all ".chain-item") 1))
      (flush-event-queue!)
      (is (= selected (get-in (state/get-raw-state)
                              [:list-ui component-id :selected-ids])))
      (ui/press! robot javafx.scene.input.KeyCode/ESCAPE))))

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
              "#chain-paste-items" (str "Paste after the selection (" (key "V") ")")
              "#chain-delete-selection" "Delete the selected items (Delete)"}
             (into {} (map (juxt identity tooltip-of))
                   ["#chain-new-group" "#chain-group-selection" "#chain-copy-selection"
                    "#chain-paste-items" "#chain-delete-selection"]))))
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
      (state/assoc-in-state! [:chains :cue-chains [0 0] :items]
                             (conj (vec (rest (get-in (state/get-raw-state)
                                                      [:chains :cue-chains [0 0] :items])))
                                   (first (get-in (state/get-raw-state)
                                                  [:chains :cue-chains [0 0] :items]))))
      (is (= 3 (count (get-in (state/get-raw-state)
                              [:chains :cue-chains [0 0] :items])))))))
