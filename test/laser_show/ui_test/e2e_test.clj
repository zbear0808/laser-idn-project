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
