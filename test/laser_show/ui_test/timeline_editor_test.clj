(ns laser-show.ui-test.timeline-editor-test
  "Timeline editor window for cell [0 0]: track, content and loop controls,
   canvas clicks and the Delete key."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.timeline.layout :as layout]
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
    (ui/assert-visible! "#timeline-lanes")
    (let [track-count (count (get-in (state/get-raw-state)
                                     [:chains :cue-chains [0 0] :tracks]))]
      (ui/click! robot "#timeline-add-track")
      (ui/wait-for! #(= (inc track-count)
                        (count (get-in (state/get-raw-state)
                                       [:chains :cue-chains [0 0] :tracks])))))
    (ui/click! robot "#timeline-add-content")
    (ui/wait-for! #(true? (get-in (state/get-raw-state)
                                  [:ui :timeline-prefs :add-panel :open?])))
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
    (ui/assert-visible! "#timeline-lanes")
    (let [count-before (count (get-in (state/get-raw-state) items-path))]
      (events/dispatch! {:event/type :timeline/add-preset-to-track
                         :col 0 :row 0 :item-id :square})
      (ui/wait-for! #(and (= (inc count-before)
                             (count (get-in (state/get-raw-state) items-path)))
                          (seq (get-in (state/get-raw-state) [:ui :timeline-editor [0 0] :selection]))))
      (let [added-id (first (get-in (state/get-raw-state) [:ui :timeline-editor [0 0] :selection]))]
        (ui/focus! robot "#timeline-canvas")
        (ui/press! robot javafx.scene.input.KeyCode/DELETE)
        (ui/wait-for! #(= count-before (count (get-in (state/get-raw-state) items-path))))
        (is (not-any? #(= added-id (:id %)) (get-in (state/get-raw-state) items-path)))
        (is (empty? (get-in (state/get-raw-state) [:ui :timeline-editor [0 0] :selection])))))))

(defn- open-timeline! []
  (events/dispatch! {:event/type :timeline-cue-editor/open :col 0 :row 0})
  (ui/wait-for! #(some (fn [title]
                         (and title (.startsWith ^String title "Timeline Editor")))
                       (ui/window-titles)))
  (ui/assert-visible! "#timeline-lanes"))

(defn- slider-value []
  (.getValue ^javafx.scene.control.Slider (ui/query "#timeline-zoom-slider")))

(deftest timeline-zoom-buttons-change-zoom
  (let [robot (ui/robot)]
    (open-timeline!)
    (let [before (slider-value)]
      (ui/click! robot "#timeline-zoom-in")
      (ui/wait-for! #(> (slider-value) before))
      (ui/click! robot "#timeline-zoom-out")
      (ui/click! robot "#timeline-zoom-out")
      (ui/wait-for! #(< (slider-value) before))
      (is (< (slider-value) before)))))

(deftest timeline-snap-toggle-flips-both-ways
  (let [robot (ui/robot)
        snap-path [:ui :timeline-prefs :snap :enabled?]
        snap-on? #(not (false? (get-in (state/get-raw-state) snap-path)))]
    (open-timeline!)
    (is (snap-on?))
    (ui/click! robot "#timeline-snap-toggle")
    (ui/wait-for! #(not (snap-on?)))
    (ui/click! robot "#timeline-snap-toggle")
    (ui/wait-for! snap-on?)
    (is (snap-on?))))

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
    (ui/wait-for! #(true? (get-in (state/get-raw-state) [:ui :timeline-prefs :add-panel :open?])))
    (let [folder (first (filter #(= :group (:type %))
                                (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :tracks])))]
      (is (some? folder) "conversion creates a zone folder")
      (events/dispatch! {:event/type :timeline/select-track :col 0 :row 0 :track-id (:id folder)})
      (ui/wait-for! #(= "FOLDER PROPERTIES" (ui/node-text "#timeline-inspector-title")))
      (is (= "FOLDER PROPERTIES" (ui/node-text "#timeline-inspector-title"))))))

(defn- add-clip-at-zero!
  "Add a clip at beat 0 on the first track; returns its id."
  []
  (let [items-path [:chains :cue-chains [0 0] :items]
        before (count (get-in (state/get-raw-state) items-path))]
    (events/dispatch! {:event/type :timeline/set-cursor-beat :col 0 :row 0 :beat 0.0})
    (events/dispatch! {:event/type :timeline/add-preset-to-track :col 0 :row 0 :item-id :square :start 0.0})
    (ui/wait-for! #(= (inc before) (count (get-in (state/get-raw-state) items-path))))
    (:id (last (get-in (state/get-raw-state) items-path)))))

(deftest timeline-click-leaves-no-open-undo-transaction
  (let [robot (ui/robot)]
    (open-timeline!)
    (add-clip-at-zero!)
    ;; The folder's first track: ruler 36 + folder row 26 + 1 px gap, 36 px tall
    (ui/drag-by! robot "#timeline-canvas" 100 (+ 36 27 18) 0 0)
    (ui/wait-for! #(nil? (get-in (state/get-raw-state) [:history :transaction])))
    (is (nil? (get-in (state/get-raw-state) [:history :transaction])))))

(defn- undo-count []
  (count (get-in (state/get-raw-state) [:history :undo-stack])))

(defn- clip-start [id]
  (some #(when (= id (:id %)) (:timeline/start %))
        (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :items])))

(def ^:private first-track-y
  "Middle of the first track lane (row 1, under the zone folder row):
   ruler 36 + folder row 26 + 1 px gap + half of the 36 px row."
  (+ 36 27 18))

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


(defn- scene-min-y ^double [^javafx.scene.Node n]
  (.getMinY (.localToScene n (.getBoundsInLocal n))))

(deftest timeline-headers-align-with-lanes
  (let [robot (ui/robot)
        tracks-path [:chains :cue-chains [0 0] :tracks]]
    (open-timeline!)
    ;; Enough tracks to scroll: 2 starter rows + 24 tracks of 37 px
    (dotimes [_ 24] (events/dispatch! {:event/type :timeline/add-track :col 0 :row 0}))
    (ui/wait-for! #(<= 26 (count (layout/rows (get-in (state/get-raw-state) tracks-path) []))))
    (let [check! (fn [scroll-y]
                   (let [rows (layout/rows (get-in (state/get-raw-state) tracks-path) [])
                         canvas (ui/query "#timeline-canvas")
                         canvas-top (scene-min-y canvas)]
                     (doseq [[i r] (map-indexed vector rows)
                             :let [header (ui/query (str "#timeline-track-header-" i))
                                   lane-y (+ canvas-top 36 (- (:y r) scroll-y))]
                             :when (< -1 (- (:y r) scroll-y) 300)]
                       (is (< (Math/abs (- (scene-min-y header) lane-y)) 1.01)
                           (str "row " i " at scroll " scroll-y)))))]
      (check! 0.0)
      ;; Scroll the lanes down 100 px with the vertical scroll bar
      (.interact robot (reify Runnable
                         (run [_] (.setValue ^javafx.scene.control.ScrollBar (ui/query "#timeline-vscroll") 100.0))))
      (ui/wait-for! #(< (Math/abs (- 100.0 (.getValue ^javafx.scene.control.ScrollBar (ui/query "#timeline-vscroll")))) 0.01))
      (ui/sync-fx!)
      (check! 100.0))))

(deftest timeline-scrollbar-does-not-snap-back
  (let [robot (ui/robot)]
    (open-timeline!)
    (let [^javafx.scene.control.ScrollBar bar (ui/query "#timeline-hscroll")
          target (/ (.getMax bar) 2.0)]
      (is (pos? (.getMax bar)) "there's something to scroll")
      (.interact robot (reify Runnable (run [_] (.setValue bar target))))
      (dotimes [_ 3] (ui/sync-fx!))
      (Thread/sleep 200)
      (ui/sync-fx!)
      (is (< (Math/abs (- target (.getValue ^javafx.scene.control.ScrollBar (ui/query "#timeline-hscroll")))) 1e-6)))))

(deftest timeline-long-show-reachable
  (let [robot (ui/robot)
        items-path [:chains :cue-chains [0 0] :items]
        start 300.0]
    (open-timeline!)
    (events/dispatch! {:event/type :timeline/add-preset-to-track :col 0 :row 0 :item-id :square :start start})
    (ui/wait-for! #(some (fn [c] (= start (:timeline/start c))) (get-in (state/get-raw-state) items-path)))
    (let [id (:id (first (filter #(= start (:timeline/start %)) (get-in (state/get-raw-state) items-path))))]
      (events/dispatch! {:event/type :timeline/clear-selection :col 0 :row 0})
      (ui/wait-for! #(empty? (get-in (state/get-raw-state) [:ui :timeline-editor [0 0] :selection])))
      (ui/click! robot "#timeline-zoom-fit")
      (ui/sync-fx!)
      (let [canvas (ui/query "#timeline-canvas")
            width (.getWidth ^javafx.scene.canvas.Canvas canvas)
            ;; Fit spreads the whole content over the canvas width
            beats (layout/content-beats (get-in (state/get-raw-state) [:chains :cue-chains [0 0]]))
            zoom (/ width beats)
            x (+ (* start zoom) 1 (/ (* 4 zoom) 2))]
        (is (> zoom 2.0) "fits without hitting the minimum zoom")
        (ui/drag-by! robot "#timeline-canvas" x first-track-y 0 0)
        (ui/wait-for! #(= #{id} (get-in (state/get-raw-state) [:ui :timeline-editor [0 0] :selection])))
        (is (= #{id} (get-in (state/get-raw-state) [:ui :timeline-editor [0 0] :selection])))))))
