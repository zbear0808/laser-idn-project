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
    (let [loop-path [:chains :cue-chains [0 0] :loop :enabled?]
          before (boolean (get-in (state/get-raw-state) loop-path))]
      (ui/click! robot "#timeline-loop-toggle")
      (ui/wait-for! #(= (not before) (boolean (get-in (state/get-raw-state) loop-path)))))
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

(defn- open-timeline! []
  (events/dispatch! {:event/type :timeline-cue-editor/open :col 0 :row 0})
  (ui/wait-for! #(some (fn [title]
                         (and title (.startsWith ^String title "Timeline Editor")))
                       (ui/window-titles)))
  (ui/assert-visible! "#timeline-canvas"))

(deftest timeline-zoom-slider-changes-zoom
  (let [robot (ui/robot)]
    (open-timeline!)
    (ui/slide! robot "#timeline-zoom-slider" 120)
    (ui/wait-for! #(= 120.0 (get-in (state/get-raw-state) [:ui :timeline :zoom-x])))
    (is (= 120.0 (get-in (state/get-raw-state) [:ui :timeline :zoom-x])))))

(deftest timeline-snap-checkbox-toggles-both-ways
  (let [robot (ui/robot)
        snap-path [:ui :timeline :snap-enabled?]]
    (open-timeline!)
    (ui/uncheck! robot "#timeline-snap-toggle")
    (ui/wait-for! #(false? (get-in (state/get-raw-state) snap-path)))
    (ui/check! robot "#timeline-snap-toggle")
    (ui/wait-for! #(true? (get-in (state/get-raw-state) snap-path)))
    (is (true? (get-in (state/get-raw-state) snap-path)))))

(deftest timeline-open-does-not-convert-twice
  (let [cue-path [:chains :cue-chains [0 0]]]
    (open-timeline!)
    (let [after-first (get-in (state/get-raw-state) cue-path)]
      (is (= :timeline (:editor-type after-first)))
      (events/dispatch! {:event/type :ui/close-dialog :dialog-id :timeline-cue-editor})
      (ui/wait-for! #(not (get-in (state/get-raw-state) [:ui :dialogs :timeline-cue-editor :open?])))
      (open-timeline!)
      (is (= after-first (get-in (state/get-raw-state) cue-path))))))

(deftest timeline-inspector-shows-folder-properties
  (let [robot (ui/robot)]
    (open-timeline!)
    (ui/click! robot "#timeline-add-content")
    (ui/wait-for! #(true? (get-in (state/get-raw-state) [:ui :timeline :add-panel-open?])))
    (let [folder (first (filter #(= :group (:type %))
                                (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :tracks])))]
      (is (some? folder) "conversion creates a zone folder")
      (events/dispatch! {:event/type :timeline/select-track :track-id (:id folder)})
      (ui/wait-for! #(= "FOLDER PROPERTIES" (ui/node-text "#timeline-inspector-title")))
      (is (= "FOLDER PROPERTIES" (ui/node-text "#timeline-inspector-title"))))))

(defn- add-clip-at-zero!
  "Add a clip at beat 0 on the first track; returns its id."
  []
  (let [items-path [:chains :cue-chains [0 0] :items]
        before (count (get-in (state/get-raw-state) items-path))]
    (events/dispatch! {:event/type :timeline/set-cursor-beat :beat 0.0})
    (events/dispatch! {:event/type :timeline/add-preset-to-track :col 0 :row 0 :item-id :square :start 0.0})
    (ui/wait-for! #(= (inc before) (count (get-in (state/get-raw-state) items-path))))
    (:id (last (get-in (state/get-raw-state) items-path)))))

(deftest timeline-click-leaves-no-open-undo-transaction
  (let [robot (ui/robot)]
    (open-timeline!)
    (add-clip-at-zero!)
    ;; Row 2 (the folder's first track) starts at ruler 24 + 32 px; the clip spans x 0..200
    (ui/drag-by! robot "#timeline-canvas" 100 (+ 24 32 16) 0 0)
    (ui/wait-for! #(nil? (get-in (state/get-raw-state) [:history :transaction])))
    (is (nil? (get-in (state/get-raw-state) [:history :transaction])))))

(defn- undo-count []
  (count (get-in (state/get-raw-state) [:history :undo-stack])))

(defn- clip-start [id]
  (some #(when (= id (:id %)) (:timeline/start %))
        (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :items])))

(def ^:private first-track-y
  "Middle of the first track lane (row 1, under the zone folder row)."
  (+ 24 32 16))

(deftest timeline-drag-clip-commits-once
  (let [robot (ui/robot)]
    (open-timeline!)
    (let [id (add-clip-at-zero!)
          before (undo-count)]
      ;; 50 px per beat: drag the clip 100 px right
      (ui/drag-by! robot "#timeline-canvas" 100 first-track-y 100 0 {:steps 6})
      (ui/wait-for! #(= 2.0 (clip-start id)))
      (is (= 2.0 (clip-start id)))
      (is (= (inc before) (undo-count)) "one undo entry for the whole drag"))))

(deftest timeline-escape-cancels-drag
  (let [robot (ui/robot)]
    (open-timeline!)
    (let [id (add-clip-at-zero!)
          before (undo-count)]
      ;; Key events go to the focused window (headless has no OS focus)
      (ui/focus! robot "#timeline-canvas")
      (ui/drag-by! robot "#timeline-canvas" 100 first-track-y 100 0 {:hold? true})
      (ui/press! robot javafx.scene.input.KeyCode/ESCAPE)
      (ui/release! robot)
      (ui/sync-fx!)
      (is (= 0.0 (clip-start id)) "the clip didn't move")
      (is (= before (undo-count)) "nothing was committed"))))
