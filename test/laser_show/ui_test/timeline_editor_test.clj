(ns laser-show.ui-test.timeline-editor-test
  "Timeline editor window on a show copied from the cue at [0 0] (New Show
   from Cue): track, content and loop controls, canvas clicks and the Delete
   key, drags from the browser, and the footer and the placeholder while the
   show is gone."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging :as log]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.input.mtc :as mtc]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.history :as history]
            [laser-show.timeline.layout :as layout]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.shows :as shows]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.drag-drop-cell :as dd]
            [laser-show.views.keys :as keys]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- tick-clock!
  "app/start-test! runs no show clock: tick it by hand. The MTC status reaches
   app state through the :show-clock/status event."
  []
  (show-clock/tick! (System/nanoTime)))

(defn- position
  "Where the show clock has the cue playing under playback key k."
  [k]
  (get (show-time/positions @show-time/!runtime) k))

(def ^:private !show
  "The show the test's editor was opened on (open-timeline!)."
  (atom nil))

(defn- sid [] @!show)

(defn- show-path [& ks] (into (shows/show-path (sid)) ks))

(defn- ui-path [& ks] (into [:ui :timeline-editor (sid)] ks))

(defn- play-key [] (shows/play-key (sid)))

(defn- play-path [& ks] (into [:playback :active-cues (play-key)] ks))

(defn- tracks-list-id
  ([] (tracks-list-id (sid)))
  ([id] [:timeline-tracks id]))

(defn- dialog-show [] (get-in (state/get-raw-state) [:ui :dialogs :timeline-cue-editor :show-id]))

(defn- editor-shown? []
  (some (fn [title] (and title (.startsWith ^String title "Timeline Editor")))
        (ui/window-titles)))

(defn- new-show-from-cue!
  "Copy the cue at [col row] into a new show, which opens the editor on it
   (or shows it in the open editor); its id."
  [col row]
  (let [before (dialog-show)]
    (events/dispatch! {:event/type :shows/new-from-cue :col col :row row})
    (ui/wait-for! #(let [id (dialog-show)]
                     (and (some? id) (not= before id)
                          (get-in (state/get-raw-state) (shows/show-path id))
                          (get-in (state/get-raw-state) [:ui :dialogs :timeline-cue-editor :open?]))))
    (dialog-show)))

(defn- open-timeline!
  "A new show copied from the starter cue at [0 0] (its zone folder, clips
   and content width), its editor open: the test's show."
  []
  (reset! !show (new-show-from-cue! 0 0))
  (ui/wait-for! editor-shown?)
  (ui/assert-visible! "#timeline-lanes"))

(defn- reopen-timeline!
  "Open the editor on the test's show again (:shows/open-editor)."
  []
  (events/dispatch! {:event/type :shows/open-editor :show-id (sid)})
  (ui/wait-for! editor-shown?)
  (ui/assert-visible! "#timeline-lanes"))

(defn- close-timeline! []
  (events/dispatch! {:event/type :ui/close-dialog :dialog-id :timeline-cue-editor})
  (ui/wait-for! #(not (get-in (state/get-raw-state) [:ui :dialogs :timeline-cue-editor :open?]))))

(deftest timeline-editor-controls-and-canvas-respond-to-clicks
  (let [robot (ui/robot)]
    (open-timeline!)
    (let [track-count (count (get-in (state/get-raw-state) (show-path :tracks)))]
      (ui/click! robot "#timeline-add-track")
      (ui/wait-for! #(= (inc track-count)
                        (count (get-in (state/get-raw-state) (show-path :tracks))))))
    (ui/click! robot "#timeline-add-content")
    (ui/wait-for! #(true? (get-in (state/get-raw-state)
                                  [:ui :timeline-prefs :add-panel :open?])))
    (let [loop-path (show-path :loop :enabled?)
          before (boolean (get-in (state/get-raw-state) loop-path))]
      (ui/click! robot "#timeline-loop-toggle")
      (ui/wait-for! #(= (not before) (boolean (get-in (state/get-raw-state) loop-path)))))
    (ui/click! robot ".timeline-canvas")
    (is (some? (ui/query ".timeline-canvas")))))

(deftest timeline-delete-key-removes-selected-clip
  (let [robot (ui/robot)]
    (open-timeline!)
    (let [items-path (show-path :items)
          count-before (count (get-in (state/get-raw-state) items-path))]
      (events/dispatch! {:event/type :timeline/add-preset-to-track
                         :show-id (sid) :item-id :square})
      (ui/wait-for! #(and (= (inc count-before)
                             (count (get-in (state/get-raw-state) items-path)))
                          (seq (get-in (state/get-raw-state) (ui-path :selection)))))
      (let [added-id (first (get-in (state/get-raw-state) (ui-path :selection)))]
        (ui/focus! robot "#timeline-canvas")
        (let [canvas (ui/query "#timeline-canvas")
              claims? (fn [id] (ui/on-fx-thread #(keys/claims? (.getFocusOwner (.getScene canvas)) id)))]
          (is (every? claims? [:left :right :delete :back_space :space :escape]))
          (is (not-any? claims? [:up :down :a :d]) "only horizontal nudges; Select All and Duplicate are chords"))
        (ui/press! robot javafx.scene.input.KeyCode/DELETE)
        (ui/wait-for! #(= count-before (count (get-in (state/get-raw-state) items-path))))
        (is (not-any? #(= added-id (:id %)) (get-in (state/get-raw-state) items-path)))
        (is (empty? (get-in (state/get-raw-state) (ui-path :selection))))))))

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
  (open-timeline!)
  (let [after-first (get-in (state/get-raw-state) (show-path))]
    (is (= :timeline (:editor-type after-first)))
    (close-timeline!)
    (reopen-timeline!)
    (is (= after-first (get-in (state/get-raw-state) (show-path))))))

(deftest empty-timeline-opens-the-browser-test
  (let [browser-open? #(get-in (state/get-raw-state) [:ui :timeline-prefs :add-panel :open?])]
    (testing "a cue with clips: the browser stays closed, as it starts"
      (open-timeline!)
      (is (false? (browser-open?)))
      (is (not (ui/visible? "#timeline-browser")))
      (close-timeline!))
    (testing "a new, empty show has no clips: the browser opens, so the hint's browser is there"
      (let [ids #(set (keys (get-in (state/get-raw-state) [:chains :shows])))
            before (ids)]
        (events/dispatch! {:event/type :shows/create})
        (ui/wait-for! #(seq (remove before (ids))))
        (reset! !show (first (remove before (ids))))
        (events/dispatch! {:event/type :shows/open-editor :show-id (sid)})
        (ui/wait-for! editor-shown?)
        (ui/wait-for! #(ui/visible? "#timeline-browser"))
        (is (empty? (get-in (state/get-raw-state) (show-path :items))))
        (is (true? (browser-open?)))))))

(deftest timeline-inspector-shows-folder-properties
  (let [robot (ui/robot)]
    (open-timeline!)
    (ui/click! robot "#timeline-add-content")
    (ui/wait-for! #(true? (get-in (state/get-raw-state) [:ui :timeline-prefs :add-panel :open?])))
    (let [folder (first (filter #(= :group (:type %))
                                (get-in (state/get-raw-state) (show-path :tracks))))]
      (is (some? folder) "conversion creates a zone folder")
      (events/dispatch! {:event/type :timeline/select-track :show-id (sid) :track-id (:id folder)})
      (ui/wait-for! #(= "FOLDER PROPERTIES" (ui/node-text "#timeline-inspector-title")))
      (is (= "FOLDER PROPERTIES" (ui/node-text "#timeline-inspector-title"))))))

(defn- add-clip-at-zero!
  "Add a clip at beat 0 on the first track; returns its id."
  []
  (let [items-path (show-path :items)
        before (count (get-in (state/get-raw-state) items-path))]
    (events/dispatch! {:event/type :timeline/set-cursor-beat :show-id (sid) :beat 0.0})
    (events/dispatch! {:event/type :timeline/add-preset-to-track :show-id (sid) :item-id :square :start 0.0})
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
        (get-in (state/get-raw-state) (show-path :items))))

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
        tracks-path #(show-path :tracks)]
    (open-timeline!)
    ;; Enough tracks to scroll: 2 starter rows + 24 tracks of 37 px
    (dotimes [_ 24] (events/dispatch! {:event/type :timeline/add-track :show-id (sid)}))
    (ui/wait-for! #(<= 26 (count (layout/rows (get-in (state/get-raw-state) (tracks-path)) []))))
    (let [check! (fn [scroll-y]
                   (let [rows (layout/rows (get-in (state/get-raw-state) (tracks-path)) [])
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
        items-path #(show-path :items)
        start 300.0]
    (open-timeline!)
    (events/dispatch! {:event/type :timeline/add-preset-to-track :show-id (sid) :item-id :square :start start})
    (ui/wait-for! #(some (fn [c] (= start (:timeline/start c))) (get-in (state/get-raw-state) (items-path))))
    (let [id (:id (first (filter #(= start (:timeline/start %)) (get-in (state/get-raw-state) (items-path)))))]
      (events/dispatch! {:event/type :timeline/clear-selection :show-id (sid)})
      (ui/wait-for! #(empty? (get-in (state/get-raw-state) (ui-path :selection))))
      (ui/click! robot "#timeline-zoom-fit")
      (ui/sync-fx!)
      (let [canvas (ui/query "#timeline-canvas")
            width (.getWidth ^javafx.scene.canvas.Canvas canvas)
            ;; Fit spreads the whole content over the canvas width
            beats (layout/content-beats (get-in (state/get-raw-state) (show-path)))
            zoom (/ width beats)
            x (+ (* start zoom) 1 (/ (* 4 zoom) 2))]
        (is (> zoom 2.0) "fits without hitting the minimum zoom")
        (ui/drag-by! robot "#timeline-canvas" x first-track-y 0 0)
        (ui/wait-for! #(= #{id} (get-in (state/get-raw-state) (ui-path :selection))))
        (is (= #{id} (get-in (state/get-raw-state) (ui-path :selection))))))))

(deftest timeline-play-button-starts-from-cursor
  (let [robot (ui/robot)
        timing-path #(play-path)]
    (open-timeline!)
    (events/dispatch! {:event/type :timeline/seek :show-id (sid) :beat 6.0})
    (ui/wait-for! #(= 6.0 (get-in (state/get-raw-state) (ui-path :cursor-beat))))
    (ui/click! robot "#timeline-play")
    (ui/wait-for! #(some? (get-in (state/get-raw-state) (timing-path))))
    (tick-clock!)
    (Thread/sleep 50)
    (tick-clock!)
    (is (>= (position (play-key)) 6.0))
    (testing "the play button becomes pause, and pause freezes the cue"
      (ui/click! robot "#timeline-play")
      (ui/wait-for! #(true? (get-in (state/get-raw-state) (conj (timing-path) :paused?)))))
    (testing "stop deactivates it and keeps the cursor"
      (ui/click! robot "#timeline-stop")
      (ui/wait-for! #(nil? (get-in (state/get-raw-state) (timing-path))))
      (is (= 6.0 (get-in (state/get-raw-state) (ui-path :cursor-beat)))))))

(deftest timeline-readout-follows-the-clock-test
  ;; The readout draws from the show clock's newest tick, on a fake clock here
  (let [robot (ui/robot)
        t0 (System/nanoTime)
        tick! #(show-clock/tick! (+ t0 (* % 1000000)))
        readout #(ui/node-text "#timeline-readout")]
    (open-timeline!)
    (events/dispatch! {:event/type :timeline/apply-op :show-id (sid) :op {:op :set-length :beats 64.0}})
    (ui/wait-for! #(= "1.1.1" (readout)))
    (ui/click! robot "#timeline-play")
    (ui/wait-for! #(some? (get-in (state/get-raw-state) (play-path))))
    (tick! 0)
    (doseq [[ms text] [[600 "1.2.1"] [1100 "1.3.1"]]]
      (tick! ms)
      (ui/wait-for! #(= text (readout)))
      (is (= text (readout)) (str (/ ms 500.0) " beats from the cursor at 120 BPM")))
    (testing "stopped: the cursor's position again"
      (ui/click! robot "#timeline-stop")
      (ui/wait-for! #(nil? (get-in (state/get-raw-state) (play-path))))
      (tick! 1200)
      (ui/wait-for! #(= "1.1.1" (readout))))))

(deftest timeline-ruler-time-mode-shows-timecode
  (let [robot (ui/robot)]
    (open-timeline!)
    (is (re-matches #"≈00:00:00:00" (ui/node-text "#timeline-readout-tc"))
        "without a locked tempo the timecode is an estimate")
    (ui/click! robot "#timeline-ruler-time")
    (ui/wait-for! #(= :time (get-in (state/get-raw-state) [:ui :timeline-prefs :ruler-mode])))
    (ui/click! robot "#timeline-tempo-lock")
    (ui/wait-for! #(some? (get-in (state/get-raw-state) (show-path :timeline/tempo-bpm))))
    (events/dispatch! {:event/type :timeline/seek :show-id (sid) :beat 4.0})
    ;; 4 beats at the locked 120 BPM = 2 s
    (ui/wait-for! #(= "00:00:02:00" (ui/node-text "#timeline-readout-tc")))
    (is (= "00:00:02:00" (ui/node-text "#timeline-readout-tc")))))

(deftest timeline-follows-midi-timecode
  (let [robot (ui/robot)
        timing-path #(play-path)
        tick! tick-clock!]
    (mtc/reset-decoder!)
    (open-timeline!)
    (events/dispatch! {:event/type :timeline/apply-op :show-id (sid) :op {:op :set-length :beats 64.0}})
    (ui/click! robot "#timeline-chase-mtc")
    (ui/wait-for! #(= :mtc (get-in (state/get-raw-state) (show-path :timeline/chase))))
    (ui/wait-for! #(ui/visible? "#timeline-mtc-chip"))
    (tick!)
    (ui/wait-for! #(= "MTC —" (ui/node-text "#timeline-mtc-chip")))
    (is (not (ui/enabled? "#timeline-play")) "the timecode drives the transport")
    (testing "running timecode starts the timeline at its position"
      ;; 00:00:01:00 at 25 fps, as 8 quarter-frames
      (doseq [b [0x00 0x10 0x21 0x30 0x40 0x50 0x60 0x72]]
        (mtc/on-quarter-frame! b))
      (tick!)
      (ui/wait-for! #(some? (get-in (state/get-raw-state) (timing-path))))
      (ui/wait-for! #(some? (position (play-key))))
      (is (< 2.0 (position (play-key)) 2.3) "1.07 s at 120 BPM")
      (ui/wait-for! #(re-matches #"MTC 00:00:01:\d\d" (ui/node-text "#timeline-mtc-chip")))
      (is (ui/has-style-class? "#timeline-mtc-chip" "tl-mtc-running"))
      (ui/wait-for! #(ui/visible? "#timeline-mtc-rate-warning"))
      (is (= "Incoming 25 fps, timeline set to 30 fps" (ui/node-text "#timeline-mtc-rate-warning"))))
    (testing "no more timecode: the timeline stops"
      (mtc/reset-decoder!)
      (tick!)
      (ui/wait-for! #(nil? (get-in (state/get-raw-state) (timing-path))))
      (ui/wait-for! #(= "MTC —" (ui/node-text "#timeline-mtc-chip"))))
    (mtc/reset-decoder!)))

(defn- click-bank-button! [robot text]
  (ui/wait-for! #(some (fn [n] (= text (.getText ^javafx.scene.control.Labeled n)))
                       (ui/query-all ".bank-item-btn")))
  (ui/click-node! robot (first (filter #(= text (.getText ^javafx.scene.control.Labeled %))
                                       (ui/query-all ".bank-item-btn")))))

(deftest timeline-effects-from-browser-show-in-detail-panel
  (let [robot (ui/robot)
        cue-path #(show-path)
        clip-fx (fn [id] (:effects (first (filter #(= id (:id %)) (get-in (state/get-raw-state) (conj (cue-path) :items))))))]
    (open-timeline!)
    (when-not (get-in (state/get-raw-state) [:ui :timeline-prefs :add-panel :open?])
      (ui/click! robot "#timeline-add-content"))
    (ui/wait-for! #(ui/visible? "#timeline-browser"))
    (let [clip-id (add-clip-at-zero!)]
      (ui/wait-for! #(= "Adds to Square · or drag onto a clip or track" (ui/node-text "#timeline-browser-effect-hint")))
      (testing "clicking an effect in the browser adds it to the selected clip"
        (click-bank-button! robot "Scale")
        (ui/wait-for! #(= [:scale] (mapv :effect-id (clip-fx clip-id))))
        (ui/wait-for! #(= "Scale" (ui/node-text "#timeline-device-name-0"))))
      (testing "the card's switch turns it off"
        (ui/click! robot "#timeline-device-enabled-0")
        (ui/wait-for! #(false? (:enabled? (first (clip-fx clip-id))))))
      (testing "and its x removes it"
        (ui/click! robot "#timeline-device-remove-0")
        (ui/wait-for! #(empty? (clip-fx clip-id)))))
    (testing "with a track selected, effects go to the track"
      (let [track-id (:id (model/first-leaf-track (get-in (state/get-raw-state) (conj (cue-path) :tracks))))]
        (events/dispatch! {:event/type :timeline/clear-selection :show-id (sid)})
        (events/dispatch! {:event/type :timeline/select-track :show-id (sid) :track-id track-id})
        (ui/wait-for! #(= "TRACK PROPERTIES" (ui/node-text "#timeline-inspector-title")))
        (click-bank-button! robot "Rotation")
        (ui/wait-for! #(= [:rotation] (mapv :effect-id (:effects (model/find-track (get-in (state/get-raw-state) (conj (cue-path) :tracks)) track-id)))))
        (is (= "Rotation" (ui/node-text "#timeline-device-name-0")))))))


;; Detail panel height and show switches (plans/2026-11-08-timeline-rework/10-detail-panel-size.md)

(defn- canvas-width []
  (.getWidth ^javafx.scene.canvas.Canvas (ui/query "#timeline-canvas")))

(defn- detail-height []
  (.getHeight ^javafx.scene.layout.Region (ui/query "#timeline-detail")))

(defn- near? [a b] (< (Math/abs (- (double a) (double b))) 1.0))

(defn- switch-show!
  "Show another show in the open editor: a second one, copied from the cue
   at [col row] (New Show from Cue opens it in the open editor). Its id."
  [col row]
  (new-show-from-cue! col row))

(deftest timeline-cell-switch-resets-zoom-keeps-lanes
  (let [robot (ui/robot)]
    (open-timeline!)
    (ui/wait-for! #(pos? (canvas-width)))
    (let [initial (slider-value)]
      (ui/click! robot "#timeline-zoom-in")
      (ui/wait-for! #(> (slider-value) initial))
      (switch-show! 1 0)
      (ui/wait-for! #(< (Math/abs (- (slider-value) initial)) 1e-6))
      (dotimes [_ 3] (ui/sync-fx!))
      (is (pos? (canvas-width)) "the lanes are still drawn after the switch"))))

(def ^:private detail-pref-path [:ui :timeline-prefs :detail-height])

(defn- drag-detail-up!
  "Drag the detail panel's top edge up by px. 1 px steps: in headless mode a
   bigger step jumps off the 5 px strip and the rest of the drag is lost."
  ([robot px] (drag-detail-up! robot px nil))
  ([robot px opts]
   (ui/drag-by! robot "#timeline-detail-resize" 300 2 0 (- px) (merge {:steps px} opts))))

(deftest timeline-detail-height-survives-reopen
  (let [robot (ui/robot)]
    (open-timeline!)
    (ui/wait-for! #(near? 300.0 (detail-height)))
    (drag-detail-up! robot 80 {:hold? true})
    (testing "the drag previews locally and commits nothing yet"
      (ui/wait-for! #(near? 380.0 (detail-height)))
      (is (= 300.0 (get-in (state/get-raw-state) detail-pref-path))))
    (ui/release! robot)
    (testing "the release commits the height"
      (ui/wait-for! #(near? 380.0 (get-in (state/get-raw-state) detail-pref-path)))
      (is (near? 380.0 (detail-height))))
    (close-timeline!)
    (reopen-timeline!)
    (ui/wait-for! #(near? 380.0 (detail-height)))
    (is (near? 380.0 (detail-height)) "reopening keeps the height")))

(deftest timeline-cell-switch-keeps-detail-height
  (let [robot (ui/robot)]
    (open-timeline!)
    (ui/wait-for! #(pos? (canvas-width)))
    (let [initial (slider-value)]
      (drag-detail-up! robot 80)
      (ui/wait-for! #(near? 380.0 (get-in (state/get-raw-state) detail-pref-path)))
      (ui/click! robot "#timeline-zoom-in")
      (ui/wait-for! #(> (slider-value) initial))
      (switch-show! 1 0)
      (ui/wait-for! #(< (Math/abs (- (slider-value) initial)) 1e-6))
      (dotimes [_ 3] (ui/sync-fx!))
      (is (near? 380.0 (detail-height)) "the panel keeps its height")
      (is (pos? (canvas-width)) "and the lanes are still drawn"))))


;; Drag from the browser (plans/2026-11-08-timeline-rework/08-drag-from-browser.md).
;; Headless, drops are synthetic DragEvents (ui/fire-drop!): the headless glass
;; platform can't run a real drag-and-drop. timeline-real-drag-from-browser
;; drags with the mouse and only runs in windowed mode.

(defn- bank-content [kind id category]
  (dd/drag-content :bank-item {:kind kind :id id :category category}))

(defn- items [] (get-in (state/get-raw-state) (show-path :items)))

(defn- tracks [] (get-in (state/get-raw-state) (show-path :tracks)))

(defn- first-leaf-id [] (:id (model/first-leaf-track (tracks))))

(defn- canvas-drop-preview []
  (:drop-preview @(:interaction (.getUserData ^javafx.scene.Node (ui/query "#timeline-canvas")))))

(defn- pseudo-class? [^javafx.scene.Node node pc]
  (.contains (.getPseudoClassStates node) (javafx.css.PseudoClass/getPseudoClass pc)))

(defn- undo-label [] (history/undo-label (:history (state/get-raw-state))))

(defn- open-browser! [robot]
  (when-not (get-in (state/get-raw-state) [:ui :timeline-prefs :add-panel :open?])
    (ui/click! robot "#timeline-add-content"))
  (ui/wait-for! #(ui/visible? "#timeline-browser")))

(deftest timeline-drag-preset-into-lane
  (let [robot (ui/robot)]
    (open-timeline!)
    (open-browser! robot)
    (ui/wait-for! #(pos? (canvas-width)))
    (let [canvas (ui/query "#timeline-canvas")
          square (bank-content :preset :square :geometric)
          track (first-leaf-id)
          before (count (items))
          undo-before (undo-count)]
      (testing "hovering shows a ghost where the clip will go"
        (is (:accepted? (ui/fire-drop! canvas square 413 first-track-y :drop? false :exit? false)))
        (is (= {:ghost {:row-id track :start 8.25 :length 4.0}} (canvas-drop-preview))))
      (testing "the drop adds one clip at the snapped beat, as one undo step"
        (is (:completed? (ui/fire-drop! canvas square 413 first-track-y)))
        (ui/wait-for! #(= (inc before) (count (items))))
        (let [clip (last (items))]
          (is (= [:square track 8.25] [(:preset-id clip) (:track-id clip) (:timeline/start clip)]))
          (is (= #{(:id clip)} (get-in (state/get-raw-state) (ui-path :selection)))))
        (is (= (inc undo-before) (undo-count)))
        (is (= "Add Clip" (undo-label)))
        (is (nil? (canvas-drop-preview)) "the ghost is gone"))
      (testing "the ruler, foreign text and other drag types are refused"
        (is (not (:accepted? (ui/fire-drop! canvas square 150 20))))
        (is (not (:accepted? (ui/fire-drop! canvas (doto (javafx.scene.input.ClipboardContent.) (.putString "square"))
                                            413 first-track-y))))
        (is (not (:accepted? (ui/fire-drop! canvas (dd/drag-content :grid-cell {:col 0 :row 0}) 413 first-track-y))))
        (ui/sync-fx!)
        (is (= (inc before) (count (items)))))
      (testing "a bank click after a drop still adds exactly one clip"
        (click-bank-button! robot "Square")
        (ui/wait-for! #(= (+ 2 before) (count (items))))
        (Thread/sleep 100)
        (ui/sync-fx!)
        (is (= (+ 2 before) (count (items))))))))

(defn- effect-ids [entity] (mapv :effect-id (:effects entity)))

(defn- clip-by-id [id] (some #(when (= id (:id %)) %) (items)))

(deftest timeline-drag-effect-onto-header-and-chain
  (let [robot (ui/robot)
        scale (bank-content :effect :scale :shape)
        rotation (bank-content :effect :rotation :shape)]
    (open-timeline!)
    (open-browser! robot)
    (ui/wait-for! #(pos? (canvas-width)))
    (let [clip-id (add-clip-at-zero!)]
      (ui/wait-for! #(ui/visible? "#timeline-device-hint"))
      (testing "an empty chain takes a drop, and highlights its hint while hovering"
        (is (:accepted? (ui/fire-drop! (ui/query "#timeline-device-chain") scale 100 60 :drop? false :exit? false)))
        (is (pseudo-class? (ui/query "#timeline-device-hint") "drop-target"))
        (is (:completed? (ui/fire-drop! (ui/query "#timeline-device-chain") scale 100 60)))
        (ui/wait-for! #(= [:scale] (effect-ids (clip-by-id clip-id))))
        (ui/wait-for! #(= "Scale" (ui/node-text "#timeline-device-name-0"))))
      (testing "a drop on the left half of a card inserts before it"
        (let [card (first (ui/query-all ".tl-device"))]
          (is (:accepted? (ui/fire-drop! card rotation 20 20 :drop? false :exit? false)))
          (is (pseudo-class? card "drop-before"))
          (ui/fire-drop! (ui/query "#timeline-device-chain") rotation 5 5 :drop? false)
          (is (not (pseudo-class? card "drop-before")) "leaving the chain clears the line")
          (is (:completed? (ui/fire-drop! card rotation 20 20)))
          (ui/wait-for! #(= [:rotation :scale] (effect-ids (clip-by-id clip-id))))
          (is (= "Add Effect" (undo-label)))))
      (testing "the right half of the last card: after it"
        (ui/wait-for! #(= 2 (count (ui/query-all ".tl-device"))))
        (let [card (last (ui/query-all ".tl-device"))]
          (ui/fire-drop! card scale 300 20 :drop? false :exit? false)
          (is (pseudo-class? card "drop-after"))
          (ui/fire-drop! card scale 300 20 :drop? false))))
    (testing "a header drop puts it on the track and selects the track"
      (let [track (first-leaf-id)
            header (ui/query "#timeline-track-header-1")]
        (is (:accepted? (ui/fire-drop! header scale 100 10 :drop? false :exit? false)))
        (is (pseudo-class? header "drop-target"))
        (is (:completed? (ui/fire-drop! header scale 100 10)))
        (ui/wait-for! #(= [:scale] (effect-ids (model/find-track (tracks) track))))
        (is (not (pseudo-class? header "drop-target")))
        (ui/wait-for! #(= #{track} (get-in (state/get-raw-state) [:list-ui (tracks-list-id) :selected-ids])))
        (is (empty? (get-in (state/get-raw-state) (ui-path :selection))))
        (ui/wait-for! #(= "TRACK PROPERTIES" (ui/node-text "#timeline-inspector-title")))))
    (testing "zone effects don't go on tracks"
      (is (not (:accepted? (ui/fire-drop! (ui/query "#timeline-track-header-1")
                                          (bank-content :effect :zone-selector :zone) 100 10)))))
    (testing "an effect dropped on a clip in the lanes goes on the clip and selects it"
      (let [fx-count (fn [] (into {} (map (juxt :id #(count (:effects %)))) (items)))
            before (fx-count)]
        (is (:completed? (ui/fire-drop! (ui/query "#timeline-canvas") scale 100 first-track-y)))
        (ui/wait-for! #(= 1 (count (get-in (state/get-raw-state) (ui-path :selection)))))
        (let [id (first (get-in (state/get-raw-state) (ui-path :selection)))]
          (ui/wait-for! #(= (assoc before id (inc (before id))) (fx-count))))))))

(deftest timeline-real-drag-from-browser
  (if (ui/headless?)
    (log/info "Skipping timeline-real-drag-from-browser: the headless glass platform can't run a real drag-and-drop (windowed only)")
    (let [robot (ui/robot)]
      (open-timeline!)
      (open-browser! robot)
      (ui/wait-for! #(pos? (canvas-width)))
      (let [before (count (items))
            button (fn [] (first (filter #(= "Square" (.getText ^javafx.scene.control.Labeled %))
                                         (ui/query-all ".bank-item-btn"))))]
        (ui/wait-for! #(some? (button)))
        (ui/drag-node-to! robot (button) (ui/query "#timeline-canvas") 413 first-track-y)
        (ui/wait-for! 5000 #(= (inc before) (count (items))))
        (is (= 8.25 (:timeline/start (last (items)))))
        (is (not (.isArmed ^javafx.scene.control.ButtonBase (button))) "the button isn't left armed")
        (testing "a click right after the drag adds exactly one clip"
          (click-bank-button! robot "Square")
          (ui/wait-for! #(= (+ 2 before) (count (items))))
          (Thread/sleep 200)
          (ui/sync-fx!)
          (is (= (+ 2 before) (count (items)))))))))

;; A bank drag whose content the targets can't see: the button that started
;; it supplies it until its drag is done (plans/2026-11-23-cljfx-reactivity/38)

(defn- hidden-bank
  "A board's content announcing the :bank-item format with the content
   itself hidden (nil), as a platform may until the drop."
  []
  (doto (javafx.scene.input.ClipboardContent.)
    (.put (key (first (bank-content :effect :scale :shape))) nil)))

(defn- bank-button [text]
  (first (filter #(= text (.getText ^javafx.scene.control.Labeled %)) (ui/query-all ".bank-item-btn"))))

(defn- start-bank-drag!
  "A drag detected on a bank button, the platform's part of its start
   (drag-drop-cell's private open-dragboard!) answered by a test board: the
   button's handler and start-drag! run."
  [button]
  (with-redefs-fn {#'dd/open-dragboard! (fn [_ modes] (ui/test-dragboard {} modes))}
    #(ui/fire-drag-detected! button)))

(defn- drag-kept?
  "Does scene keep a drag for a hidden drop?"
  [^javafx.scene.Scene scene]
  (ui/on-fx-thread #(.containsKey (.getProperties scene) ::dd/active-drag)))

(deftest hidden-bank-preview-follows-the-gesture-source-test
  (let [robot (ui/robot)
        hidden (hidden-bank)]
    (open-timeline!)
    (open-browser! robot)
    (ui/wait-for! #(pos? (canvas-width)))
    (let [clip-id (add-clip-at-zero!)]
      (ui/wait-for! #(ui/visible? "#timeline-device-hint"))
      (ui/wait-for! #(some? (bank-button "Scale")))
      (let [scale (bank-button "Scale")
            scene (ui/on-fx-thread #(.getScene ^javafx.scene.Node scale))
            canvas #(ui/query "#timeline-canvas")
            header #(ui/query "#timeline-track-header-1")
            chain #(ui/query "#timeline-device-chain")
            marked? #(boolean (or (some-> (ui/query "#timeline-device-hint") (pseudo-class? "drop-target"))
                                  (some (fn [card] (or (pseudo-class? card "drop-before") (pseudo-class? card "drop-after")))
                                        (ui/query-all ".tl-device"))))
            over! (fn [target x y] (ui/fire-drop! (target) hidden x y :drop? false :exit? false :gesture-source scale))
            leave! (fn [target x y] (ui/fire-drop! (target) hidden x y :drop? false :gesture-source scale))
            drop! (fn [target x y & opts] (apply ui/fire-drop! (target) hidden x y opts))
            doc #(vector (items) (tracks) (undo-count))
            before (doc)]
        (start-bank-drag! scale)
        (is (drag-kept? scene) "the button's drag, kept on its window's scene")
        (testing "over the lanes, the clip under the pointer is marked; leaving clears the mark, not the drag"
          (is (:accepted? (over! canvas 100 first-track-y)))
          (is (= {:highlight-clip clip-id} (canvas-drop-preview)))
          (leave! canvas 100 first-track-y)
          (is (nil? (canvas-drop-preview)))
          (is (drag-kept? scene)))
        (testing "over a track header: that header, until the drag leaves it"
          (is (:accepted? (over! header 100 10)))
          (is (pseudo-class? (header) "drop-target"))
          (leave! header 100 10)
          (is (not (pseudo-class? (header) "drop-target"))))
        (testing "over the selected clip's device chain: where it would go; the drop adds once"
          (is (:accepted? (over! chain 100 60)))
          (is (marked?) "the empty chain's hint")
          (is (= before (doc)) "no edit while it hovers")
          (is (:completed? (drop! chain 100 60 :gesture-source scale)))
          (ui/wait-for! #(= [:scale] (effect-ids (clip-by-id clip-id))))
          (is (= (inc (peek before)) (undo-count)) "one undo step")
          (is (not (marked?)) "and no mark left"))
        (testing "the button's done ends its drag, the button unarmed; the next click adds once"
          (ui/fire-drag-done! scale javafx.scene.input.TransferMode/COPY)
          (is (not (drag-kept? scene)))
          (is (not (.isArmed ^javafx.scene.control.ButtonBase scale)))
          (click-bank-button! robot "Scale")
          (ui/wait-for! #(= [:scale :scale] (effect-ids (clip-by-id clip-id))))
          (Thread/sleep 100)
          (ui/sync-fx!)
          (is (= [:scale :scale] (effect-ids (clip-by-id clip-id)))))
        (testing "after that, the hidden board has no source: no target offers to take it"
          (let [then (doc)]
            (is (not (:accepted? (drop! canvas 100 first-track-y :gesture-source scale))))
            (is (nil? (canvas-drop-preview)))
            (is (not (:accepted? (drop! header 100 10 :gesture-source scale))))
            (is (not (:accepted? (drop! chain 100 60 :gesture-source scale))))
            (is (= then (doc)))))
        (testing "a readable board needs no source"
          (is (:completed? (ui/fire-drop! (canvas) (bank-content :effect :rotation :shape) 100 first-track-y)))
          (ui/wait-for! #(= [:scale :scale :rotation] (effect-ids (clip-by-id clip-id))))))
      (testing "a button that goes away (the browser closes) ends the drag it started"
        (let [old (bank-button "Scale")
              scene (ui/on-fx-thread #(.getScene ^javafx.scene.Node old))
              then (vector (items) (tracks) (undo-count))]
          (start-bank-drag! old)
          (is (drag-kept? scene))
          (ui/click! robot "#timeline-add-content")
          (ui/wait-for! #(nil? (ui/on-fx-thread (fn [] (.getScene ^javafx.scene.Node old)))))
          (is (not (drag-kept? scene)))
          (is (not (:accepted? (ui/fire-drop! (ui/query "#timeline-canvas") hidden 100 first-track-y
                                              :gesture-source old))))
          (is (= then (vector (items) (tracks) (undo-count))))))
      (testing "and so does closing the window it is in"
        (open-browser! robot)
        (ui/wait-for! #(some? (bank-button "Scale")))
        (let [button (bank-button "Scale")
              scene (ui/on-fx-thread #(.getScene ^javafx.scene.Node button))]
          (start-bank-drag! button)
          (is (drag-kept? scene))
          (events/dispatch! {:event/type :ui/close-dialog :dialog-id :timeline-cue-editor})
          (ui/wait-for! #(not-any? (fn [t] (and t (.startsWith ^String t "Timeline Editor"))) (ui/window-titles)))
          (is (not (drag-kept? scene))))))))

(deftest timeline-track-reorder-survives-bank-drop-targets
  (open-timeline!)
  (events/dispatch! {:event/type :timeline/add-track :show-id (sid)})
  (ui/wait-for! #(= 2 (count (tracks))))
  (ui/wait-for! #(some? (ui/query "#timeline-track-header-2")))
  (let [moved (:id (last (tracks)))
        before (vec (tracks))]
    (testing "a bank drop on a header is the header's alone: list-dnd ignores it"
      (let [undo-before (undo-count)]
        (is (:completed? (ui/fire-drop! (ui/query "#timeline-track-header-0")
                                        (bank-content :effect :scale :shape) 100 2)))
        (ui/wait-for! #(= [:scale] (effect-ids (first (tracks)))))
        (is (= (map :id before) (map :id (tracks))) "no reorder")
        (is (= (inc undo-before) (undo-count)))))
    (testing "a list drag (a string, MOVE) still reorders the tracks"
      ;; The source side of a header drag, as list-dnd's drag-detected does it
      (events/dispatch! {:event/type :list/start-drag :component-id (tracks-list-id)
                         :item-id moved :items-path (show-path :tracks)})
      (ui/wait-for! #(seq (get-in (state/get-raw-state) [:list-ui (tracks-list-id) :dragging-ids])))
      (is (:completed? (ui/fire-drop! (ui/query "#timeline-track-header-0")
                                      (doto (javafx.scene.input.ClipboardContent.) (.putString (pr-str moved)))
                                      100 2 :mode javafx.scene.input.TransferMode/MOVE)))
      (ui/wait-for! #(= moved (:id (first (tracks)))))
      (is (= 2 (count (tracks)))))))

(deftest track-drop-on-the-column-reorders-the-cell-shown-test
  ;; Reactivity plan 04: showing another show keeps the header column, whose
  ;; drop on empty space kept the first show's tracks, so it moved nothing
  (let [tracks-of #(get-in (state/get-raw-state) (conj (shows/show-path %) :tracks))
        column #(some-> ^javafx.scene.Node (ui/query "#timeline-track-header-0") .getParent .getParent)]
    (open-timeline!)
    (ui/wait-for! #(some? (column)))
    (let [pane (column)
          first-show (sid)
          first-tracks (tracks-of first-show)
          other (switch-show! 1 0)]
      (events/dispatch! {:event/type :timeline/add-track :show-id other})
      (ui/wait-for! #(= 2 (count (tracks-of other))))
      (is (identical? pane (column)) "the same column shows the other show's tracks")
      (let [[t1 t2] (map :id (tracks-of other))]
        (events/dispatch! {:event/type :list/start-drag :component-id (tracks-list-id other)
                           :item-id t1 :items-path (conj (shows/show-path other) :tracks)})
        (ui/wait-for! #(seq (get-in (state/get-raw-state) [:list-ui (tracks-list-id other) :dragging-ids])))
        (is (:completed? (ui/fire-drop! pane
                                        (doto (javafx.scene.input.ClipboardContent.) (.putString (pr-str t1)))
                                        20 5 :mode javafx.scene.input.TransferMode/MOVE)))
        (ui/wait-for! #(= [t2 t1] (map :id (tracks-of other))))
        (is (= first-tracks (tracks-of first-show)) "the first show's tracks are as they were")))))

(deftest escape-cancels-a-track-rename-test
  ;; Keyboard plan 03: the rename field keeps its keys (Delete, letters) and
  ;; Escape ends the rename, leaving the name as it was
  (let [robot (ui/robot)
        track-name #(:name (first (get-in (state/get-raw-state) (show-path :tracks))))]
    (open-timeline!)
    (ui/wait-for! #(some? (ui/query "#timeline-track-header-0")))
    (let [before (track-name)]
      (.doubleClickOn ^org.testfx.api.FxRobotInterface robot ^javafx.scene.Node (ui/query "#timeline-track-header-0")
                      (into-array javafx.scene.input.MouseButton [javafx.scene.input.MouseButton/PRIMARY]))
      (ui/wait-for! #(some? (ui/query "#timeline-track-rename")))
      (ui/type! robot "#timeline-track-rename" "Lead")
      (ui/press! robot javafx.scene.input.KeyCode/BACK_SPACE)
      (ui/wait-for! #(= "Lea" (ui/node-text "#timeline-track-rename")))
      (ui/press! robot javafx.scene.input.KeyCode/DELETE)
      (ui/press! robot javafx.scene.input.KeyCode/ESCAPE)
      (ui/wait-for! #(nil? (ui/query "#timeline-track-rename")))
      (is (= before (track-name)))
      (is (some? (ui/query "#timeline-track-header-0")) "no track deleted"))))


;; The show the editor addresses (plans/2026-11-25-timeline-shows/05-editor-rekey.md §5.1-§5.3)

(defn- title-showing? [title] (some #{title} (ui/window-titles)))

(deftest deleted-show-shows-placeholder-test
  (open-timeline!)
  (is (title-showing? "Timeline Editor - Untitled") "a cue with no name makes an Untitled show")
  (events/dispatch! {:event/type :timeline/set-cursor-beat :show-id (sid) :beat 4.0})
  (ui/wait-for! #(= "2.1.1" (ui/node-text "#timeline-readout")))
  (testing "undoing New Show from Cue takes the show away: the window stays, with a placeholder"
    (is (= "New Show from Cue" (undo-label)))
    (events/dispatch! {:event/type :edit/undo})
    (ui/wait-for! #(nil? (get-in (state/get-raw-state) (show-path))))
    (ui/wait-for! #(ui/visible? "#timeline-show-gone"))
    (is (= "This show is no longer in the library." (ui/node-text "#timeline-show-gone")))
    (ui/wait-for! #(title-showing? "Timeline Editor - (deleted)"))
    (is (nil? (ui/query "#timeline-lanes")) "no editor for a show that isn't there")
    (is (= (sid) (dialog-show)) "the dialog still names the show"))
  (testing "redo brings it back in the same window, at the same cursor"
    (events/dispatch! {:event/type :edit/redo})
    (ui/wait-for! #(some? (get-in (state/get-raw-state) (show-path))))
    (ui/wait-for! #(ui/visible? "#timeline-lanes"))
    (is (nil? (ui/query "#timeline-show-gone")))
    (ui/wait-for! #(title-showing? "Timeline Editor - Untitled"))
    (is (= 4.0 (get-in (state/get-raw-state) (ui-path :cursor-beat))))
    (ui/wait-for! #(= "2.1.1" (ui/node-text "#timeline-readout")))))

(defn- labeled-texts
  "The texts of the labels and buttons found by selector."
  [selector]
  (ui/on-fx-thread #(into [] (keep (fn [n] (when (instance? javafx.scene.control.Labeled n)
                                             (.getText ^javafx.scene.control.Labeled n))))
                          (ui/query-all selector))))

(defn- fire-chip-item!
  "Fire the item of the menu button at selector whose text, without its
   tick, is text, as a click on it does."
  [selector text]
  (let [^javafx.scene.control.MenuButton chip (ui/query selector)
        items (ui/on-fx-thread #(vec (.getItems chip)))
        text-of (fn [^javafx.scene.control.MenuItem i]
                  (str/trim (str/replace (str (ui/on-fx-thread #(.getText i))) #"^✓ " "")))
        item (some #(when (= text (text-of %)) %) items)]
    (is (some? item) (str "an item " (pr-str text) " in " (mapv text-of items)))
    (when item
      (ui/on-fx-thread #(.fire ^javafx.scene.control.MenuItem item))
      (ui/sync-fx!))))

(deftest footer-target-and-pattern-on-a-show-test
  (open-timeline!)
  ;; Set after the copy, which would take the column's default as its own
  (events/write-state! #(-> %
                            (assoc-in [:grid :column-targets] {0 :zone/a})
                            (assoc-in [:config :default-target] :graphics)))
  (testing "Target: a show has no column, so Inherit is the show default"
    (ui/wait-for! #(some? (ui/query "#target-chip-cue")))
    (ui/wait-for! #(= "(Graphics)" (ui/node-text "#target-chip-cue")))
    (ui/assert-text! "#target-from-cue" "from the show default"))
  (testing "Pattern: the show's chip is there"
    (is (ui/visible? "#pattern-chip-cue")))
  (testing "no trigger mode and no way to the cue chain editor"
    (let [texts (into (labeled-texts ".dialog-footer .label") (labeled-texts ".dialog-footer .button"))]
      (is (some #{"Close"} texts))
      (is (not-any? #(and % (str/starts-with? % "Trigger")) texts))
      (is (not-any? #{"Open in Cue Chain Editor"} texts))))
  (testing "choosing a Target sets the show's own, as one undo step"
    (fire-chip-item! "#target-chip-cue" "B → Right")
    (ui/wait-for! #(= :zone/b (get-in (state/get-raw-state) (show-path :target))))
    (ui/wait-for! #(= "B → Right" (ui/node-text "#target-chip-cue")))
    (is (empty? (ui/query-all "#target-from-cue")) "the show's own: no from")
    (is (= "Change Show Target" (undo-label))))
  (testing "Inherit takes it away again"
    (fire-chip-item! "#target-chip-cue" "Inherit (Graphics · from the show default)")
    (ui/wait-for! #(not (contains? (get-in (state/get-raw-state) (show-path)) :target)))
    (ui/wait-for! #(= "(Graphics)" (ui/node-text "#target-chip-cue")))))

(deftest name-field-renames-the-show-test
  (open-timeline!)
  (events/dispatch! {:event/type :timeline/set-name :show-id (sid) :name "One More Time"})
  (ui/wait-for! #(= "One More Time" (get-in (state/get-raw-state) (show-path :name))))
  (ui/wait-for! #(title-showing? "Timeline Editor - One More Time"))
  (is (= "Rename Show" (undo-label))))
