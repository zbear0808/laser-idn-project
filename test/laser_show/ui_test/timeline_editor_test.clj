(ns laser-show.ui-test.timeline-editor-test
  "Timeline editor window for cell [0 0]: track, content and loop controls,
   canvas clicks and the Delete key."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

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

(deftest timeline-delete-key-removes-selected-clip
  (let [robot (ui/robot)
        items-path [:chains :cue-chains [0 0] :items]]
    (events/dispatch! {:event/type :timeline-cue-editor/open :col 0 :row 0})
    (ui/wait-for! #(some (fn [title]
                           (and title (.startsWith ^String title "Timeline Editor")))
                         (ui/window-titles)))
    (ui/assert-visible! "#timeline-canvas")
    (let [count-before (count (get-in (state/get-raw-state) items-path))]
      (events/dispatch! {:event/type :timeline/add-preset-to-track
                         :col 0 :row 0 :item-id :square})
      (ui/wait-for! #(and (= (inc count-before)
                             (count (get-in (state/get-raw-state) items-path)))
                          (seq (get-in (state/get-raw-state) [:ui :timeline :selection]))))
      (let [added-id (first (get-in (state/get-raw-state) [:ui :timeline :selection]))]
        (ui/focus! robot "#timeline-canvas")
        (ui/press! robot javafx.scene.input.KeyCode/DELETE)
        (ui/wait-for! #(= count-before (count (get-in (state/get-raw-state) items-path))))
        (is (not-any? #(= added-id (:id %)) (get-in (state/get-raw-state) items-path)))
        (is (empty? (get-in (state/get-raw-state) [:ui :timeline :selection])))))))
