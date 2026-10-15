(ns laser-show.ui-test.e2e-test
  "Headless/local TestFX smoke tests for the production cljfx view."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.app :as app]
  [laser-show.events.core :as events]
  [laser-show.state.core :as state]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures
  :once
  (fn [tests]
    (ui/init-toolkit!)
    (try
      (tests)
      (finally
        (ui/cleanup-toolkit!)))))

(use-fixtures
  :each
  (fn [tests]
    (app/start-test!)
    (try
      (ui/sync-fx!)
      (tests)
      (finally
        (app/stop!)
        (ui/sync-fx!)))))

(deftest user-can-type-and-switch-tabs
  (let [robot (ui/robot)]
    (ui/fill! robot "#bpm-input" "140")
    (is (= "140" (ui/node-text "#bpm-input")))
    (is (some? (ui/query "#settings-tab")))
    (ui/click! robot "#settings-tab")))

(deftest application-starts-with-default-layout
  (is (ui/visible? "#main-layout"))
  (ui/assert-visible! "#play-btn")
  (ui/assert-visible! "#bpm-input")
  (ui/assert-visible! "#grid-tab")
  (ui/assert-visible! "#preview-panel")
  (ui/assert-visible! "#status-bar")
  (ui/assert-text! "#bpm-input" "120.0")
  (is (false? (get-in (state/get-raw-state) [:playback :playing?])))
  (ui/assert-no-node! "#stop-btn"))

(deftest top-level-tabs-update-content-and-preview
  (let [robot (ui/robot)]
    (doseq [[tab preview?] [["effects" true]
                            ["projectors" false]
                            ["zones" false]
                            ["settings" false]
                            ["grid" true]]]
      (ui/click! robot (str "#" tab "-tab"))
      (is (= (keyword tab)
             (get-in (state/get-raw-state) [:ui :active-tab])))
      (is (= preview? (ui/visible? "#preview-panel"))))))

(deftest transport-play-stop-flow
  (let [robot (ui/robot)]
    (ui/click! robot "#play-btn")
    (ui/wait-for! #(true? (get-in (state/get-raw-state) [:playback :playing?])))
    (ui/assert-visible! "#stop-btn")
    (ui/click! robot "#stop-btn")
    (ui/wait-for! #(false? (get-in (state/get-raw-state) [:playback :playing?])))
    (ui/assert-visible! "#play-btn")))

(deftest bpm-input-commits-valid-value
  (let [robot (ui/robot)]
    (ui/fill! robot "#bpm-input" "140")
    (ui/submit! robot "#bpm-input")
    (ui/wait-for! #(= 140.0 (get-in (state/get-raw-state) [:timing :bpm])))
    (ui/assert-text! "#bpm-input" "140.0")))

(deftest trigger-mode-selection-updates-state
  (let [robot (ui/robot)]
    (ui/select! robot "#trigger-mode-selector" "Toggle")
    (ui/wait-for! #(= :toggle
                      (get-in (state/get-raw-state) [:config :cue :trigger-mode])))
    (is (= :toggle (get-in (state/get-raw-state) [:config :cue :trigger-mode])))))

(deftest grid-renders-stable-cell-locators
  (ui/assert-visible! "#grid-cell-0-0")
  (ui/assert-visible! "#grid-cell-7-3")
  (is (= 32 (count (ui/query-all ".grid-cell")))))

(deftest cell-trigger-editor-opens-and-closes-modal
  ;; Open the modal through the same event used by production controls.
  (events/dispatch! {:event/type :ui/open-dialog
                     :dialog-id :trigger-editor
                     :data {:col 0 :row 0 :cell-mode :cue}})
  (ui/wait-for! #(true? (get-in (state/get-raw-state)
                                [:ui :dialogs :trigger-editor :open?])))
  (ui/wait-for! #(some (fn [title]
                         (and title (.startsWith ^String title "Triggers")))
                       (ui/window-titles)))
  (ui/assert-visible! ".dialog-content")
  (ui/assert-visible! "#trigger-editor-done")
  (is (some (fn [title]
              (and title (.startsWith ^String title "Triggers")))
            (ui/window-titles)))
  (ui/click! (ui/robot) "#trigger-editor-done")
  (ui/wait-for! #(false? (get-in (state/get-raw-state)
                                 [:ui :dialogs :trigger-editor :open?])))
  (ui/assert-no-node! ".dialog-content"))

(deftest menu-bar-opens-a-menu
  (let [robot (ui/robot)]
    (ui/click! robot "#transport-menu")
    (ui/wait-for! #(some? (ui/query ".menu-item")))
    (is (some? (ui/query ".menu-item")))
    (ui/press! robot javafx.scene.input.KeyCode/ESCAPE)))

(deftest preview-layout-selector-and-zone-picker-are-clickable
  (let [robot (ui/robot)]
    (ui/select! robot "#preview-layout-selector" "2x2")
    (ui/wait-for! #(= [2 2] (get-in (state/get-raw-state)
                                     [:config :preview :grid-layout])))
    (ui/click! robot "#preview-zone-label-0")
    (ui/wait-for! #(= 0 (get-in (state/get-raw-state)
                                [:ui :preview-zone-selector-open])))
    (ui/assert-visible! "#preview-zone-option-0-all")
    (ui/click! robot "#preview-zone-option-0-all")
    (ui/wait-for! #(nil? (get-in (state/get-raw-state)
                                 [:ui :preview-zone-selector-open])))))

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

(deftest timeline-editor-controls-and-canvas-respond-to-clicks
  (let [robot (ui/robot)]
    (events/dispatch! {:event/type :timeline-cue-editor/open :col 0 :row 0})
    (ui/wait-for! #(some (fn [title]
                           (and title (.startsWith ^String title "Timeline Editor")))
                         (ui/window-titles)))
    (ui/assert-visible! ".timeline-canvas")
    (let [track-count (count (get-in (state/get-raw-state)
                                     [:chains :cue-chains [0 0] :tracks]))]
      (ui/click! robot "#timeline-add-track")
      (ui/wait-for! #(= (inc track-count)
                        (count (get-in (state/get-raw-state)
                                       [:chains :cue-chains [0 0] :tracks])))))
    (ui/click! robot "#timeline-add-content")
    (ui/wait-for! #(true? (get-in (state/get-raw-state)
                                  [:ui :timeline :add-panel-open?])))
    (ui/click! robot "#timeline-loop-toggle")
    (ui/wait-for! #(true? (get-in (state/get-raw-state)
                                  [:chains :cue-chains [0 0] :loop :enabled?])))
    (ui/click! robot ".timeline-canvas")
    (is (some? (ui/query ".timeline-canvas")))))

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

(deftest grouped-list-supports-ctrl-and-shift-selection
  (let [robot (ui/robot)]
    (open-cue-chain-editor!)
    (add-cue-chain-items! [:square :triangle])
    (let [item-ids (mapv :id (get-in (state/get-raw-state)
                                     [:chains :cue-chains [0 0] :items]))]
    (ui/click-node! robot (nth (ui/query-all ".chain-item") 0))
    (ui/modifier-click-node! robot (nth (ui/query-all ".chain-item") 1) javafx.scene.input.KeyCode/CONTROL)
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
