(ns laser-show.ui-test.trigger-editor-test
  "Cell trigger editor dialog: opening and closing, learning an input and
   setting its behaviour."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.input.router :as input-router]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

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

(deftest trigger-editor-learns-input-and-configures-behavior
  (let [robot (ui/robot)
        target {:type :trigger-cue :target [0 0]}]
    (events/dispatch! {:event/type :ui/open-dialog
                       :dialog-id :trigger-editor
                       :data {:col 0 :row 0 :cell-mode :cue}})
    (ui/wait-for! #(true? (get-in (state/get-raw-state)
                                  [:ui :dialogs :trigger-editor :open?])))
    (ui/wait-for! #(some (fn [title]
                           (and title (.startsWith ^String title "Triggers")))
                         (ui/window-titles)))
    (ui/assert-visible! "#trigger-learn-button")
    (ui/click! robot "#trigger-learn-button")
    (ui/assert-visible! "#trigger-learning-panel")
    (input-router/dispatch! {:type :trigger
                              :source :keyboard
                              :id :space
                              :state :pressed
                              :timestamp (System/currentTimeMillis)})
    (ui/wait-for! #(some? (get-in (state/get-raw-state)
                                  [:config :input :trigger-map
                                   {:source :keyboard :id :space}])))
    (is (= target
           (select-keys (get-in (state/get-raw-state)
                                [:config :input :trigger-map
                                 {:source :keyboard :id :space}])
                        [:type :target])))
    (ui/assert-visible! "#trigger-behavior-selector")
    (ui/select! robot "#trigger-behavior-selector" ":momentary")
    (ui/wait-for! #(= :momentary
                      (get-in (state/get-raw-state)
                              [:config :input :trigger-map
                               {:source :keyboard :id :space} :behavior])))
    (let [remove-button (first (ui/query-all "#trigger-mapped-list .button-danger"))]
      (ui/click-node! robot remove-button))
    (ui/wait-for! #(nil? (get-in (state/get-raw-state)
                                 [:config :input :trigger-map
                                  {:source :keyboard :id :space}])))
    (is (nil? (get-in (state/get-raw-state)
                      [:config :input :trigger-map
                       {:source :keyboard :id :space}])))))
