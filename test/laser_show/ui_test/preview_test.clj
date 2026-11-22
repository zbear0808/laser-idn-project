(ns laser-show.ui-test.preview-test
  "Preview panel: layout selector, the per-cell zone picker, and the cells
   drawing the show clock's newest tick (plans/2026-11-17-show-clock/03 §8)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.clock-view :as clock-view])
  (:import [javafx.scene Node]
           [javafx.scene.canvas Canvas]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest picker-offers-the-zones-the-show-uses-test
  ;; zones plan Z4 (06 §4.5): the zones the show draws into, by name
  (let [robot (ui/robot)]
    (events/write-state! #(-> %
                              (assoc-in [:chains :cue-chains [0 0] :items 0 :target] :zone/a)
                              (assoc-in [:zones :labels :zone/a] "Beams")))
    (ui/click! robot "#preview-zone-label-0")
    (ui/wait-for! #(= 0 (get-in (state/get-raw-state) [:ui :preview-zone-selector-open])))
    (ui/assert-text! "#preview-zone-option-0-zone-a .label" "A · Beams")
    (is (nil? (ui/query "#preview-zone-option-0-zone-b")) "only the zones the show uses")
    (ui/assert-visible! "#preview-zone-option-0-all")
    (ui/click! robot "#preview-zone-option-0-zone-a")
    (ui/wait-for! #(= {:target :zone/a} (get-in (state/get-raw-state) [:config :preview :grid-cells 0])))
    (ui/assert-text! "#preview-zone-label-0 .label" "A · Beams")))

(deftest preview-layout-selector-and-zone-picker-are-clickable
  (let [robot (ui/robot)]
    (ui/select! robot "#preview-layout-selector" "2x2")
    (ui/wait-for! #(= [2 2] (get-in (state/get-raw-state)
                                     [:config :preview :grid-layout])))
    (ui/click! robot "#preview-zone-label-0")
    (ui/wait-for! #(= 0 (get-in (state/get-raw-state)
                                [:ui :preview-zone-selector-open])))
    (ui/assert-visible! "#preview-zone-option-0-all")
    (ui/assert-text! "#preview-zone-option-0-master .label" "Master (everything)")
    (ui/click! robot "#preview-zone-option-0-master")
    (ui/wait-for! #(nil? (get-in (state/get-raw-state)
                                 [:ui :preview-zone-selector-open])))))


;; The cells draw the show clock's tick (app/start-test! runs no clock: the
;; tests tick it from a thread of their own, with-clock)

(def ^:private stretching-square
  "A square on :all whose width follows a 1-beat sine, which starts at its
   peak (time/sine-wave-normalized): x-scale 1.0 at beat 0, 0.6 a quarter
   beat later."
  {:target :all
   :items [{:id (random-uuid) :type :preset :preset-id :square :enabled? true
            :effects [{:id (random-uuid) :effect-id :scale :enabled? true
                       :params {:x-scale {:type :sine :min 0.2 :max 1.0 :period 1.0}}}]}]})

(defn- play-square!
  "The stretching square at [7 3], playing. Written on the event thread
   (events/write-state!), so no event's write-back can lose it."
  []
  (events/write-state! assoc-in [:chains :cue-chains [7 3]] stretching-square)
  (events/dispatch! {:event/type :grid/trigger-cell :col 7 :row 3})
  (ui/wait-for! #(some? (get-in (state/get-raw-state) [:playback :active-cues [7 3]]))))

(defn- canvas [i] (str "#preview-canvas-" i))

(defn- lit
  "How many pixels are brighter than the grid lines and the grey message
   text: the ones the laser lights."
  [pixels]
  (count (filter (fn [argb]
                   (or (> (bit-and (bit-shift-right argb 16) 0xFF) 100)
                       (> (bit-and (bit-shift-right argb 8) 0xFF) 100)
                       (> (bit-and argb 0xFF) 100)))
                 pixels)))

(defn- lit-in [i] (lit (ui/node-pixels (canvas i))))

(defn- message
  "What the cell says in place of a frame (its accessible text)."
  [i]
  (ui/on-fx-thread #(.getAccessibleText ^Node (ui/query (canvas i)))))

(def ^:private with-clock fixtures/with-clock)

(deftest preview-draws-the-clocks-tick-test
  (play-square!)
  (with-clock (System/nanoTime)
    (fn [at-ms]
      (let [first-tick (atom nil)
            second-tick (atom nil)]
        (ui/wait-for! #(let [pixels (ui/node-pixels (canvas 0))]
                         (when (pos? (lit pixels))
                           (reset! first-tick pixels))))
        (testing "a quarter beat later the square is narrower: the cell draws the new tick"
          (reset! at-ms 125)
          (ui/wait-for! #(let [pixels (ui/node-pixels (canvas 0))]
                           (when (and (pos? (lit pixels)) (not= @first-tick pixels)
                                      (= "Laser preview" (message 0)))
                             (reset! second-tick pixels))))
          (is (> (lit @first-tick) (lit @second-tick)) "x-scale 1.0, then 0.6"))))))

(deftest preview-cell-shows-only-its-zone-test
  (play-square!)
  (with-clock (System/nanoTime)
    (fn [_]
      (ui/wait-for! #(pos? (lit-in 0)))
      (testing "the 2x2 grid's other cells show :left, :right and :center, which nothing plays to"
        (doseq [i [1 2 3]]
          (is (ui/wait-for! #(and (= "No Preview" (message i)) (zero? (lit-in i))))
              (str "cell " i))))
      (testing "switched to the cue's zone, a cell shows it"
        (events/dispatch! {:event/type :preview/set-cell-zone :cell-index 1 :target :all})
        (ui/wait-for! #(pos? (lit-in 1)))))))

(deftest preview-shows-a-stalled-clock-test
  (play-square!)
  (let [t0 (System/nanoTime)]
    (with-clock t0 (fn [_] (ui/wait-for! #(pos? (lit-in 0)))))
    (testing "no tick for longer than STALE_MS: the engines send empty frames, and the cell says why"
      (ui/wait-for! #(= "Show clock stalled" (message 0)))
      (is (zero? (lit-in 0))))
    (testing "ticks again: the frame is back"
      (with-clock t0 (fn [_] (ui/wait-for! #(pos? (lit-in 0))))))))

(deftest preview-timers-stop-with-their-cells-test
  (let [robot (ui/robot)
        with-preview (clock-view/running-timers)]
    (is (<= 4 with-preview) "one for each cell of the 2x2 grid, and the timeline editor's")
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(not (ui/visible? "#preview-panel")))
    (ui/wait-for! #(= (- with-preview 4) (clock-view/running-timers)))
    (ui/click! robot "#grid-tab")
    (ui/wait-for! #(ui/visible? "#preview-panel"))
    (ui/wait-for! #(= with-preview (clock-view/running-timers)))))


;; The cells take their size from the pane, not from the config

(defn- canvas-size
  "[width height] of a cell's canvas."
  [i]
  (let [^Canvas c (ui/query (canvas i))]
    (ui/on-fx-thread #(vector (.getWidth c) (.getHeight c)))))

(defn- inside-the-panel?
  "Is cell i's canvas inside the preview panel?"
  [i]
  (let [[px0 py0 px1 py1] (ui/scene-bounds (ui/query "#preview-panel"))
        [x0 y0 x1 y1] (ui/scene-bounds (ui/query (canvas i)))]
    (and (<= px0 x0) (<= x1 px1) (<= py0 y0) (<= y1 py1))))

(defn- panel-width []
  (let [[x0 _ x1 _] (ui/scene-bounds (ui/query "#preview-panel"))] (- x1 x0)))

(defn- square-side
  "Wait for every canvas of the layout to be square and inside the panel,
   and return their side."
  [cells]
  (ui/wait-for! #(and (apply = (map canvas-size (range cells)))
                      (let [[w h] (canvas-size 0)] (= w h))
                      (every? inside-the-panel? (range cells))))
  (first (canvas-size 0)))

(deftest preview-cells-fill-their-pane-test
  (let [robot (ui/robot)]
    (ui/assert-visible! (canvas 3))
    (testing "the 2x2 grid's canvases are square, as the laser draws, and share the pane's width"
      (let [side (square-side 4)]
        (is (< (- (/ (panel-width) 2) 20) side))
        (testing "they grow with the window"
          (ui/set-size! (ui/main-window) 1680 990)
          (ui/wait-for! #(< side (first (canvas-size 0))))
          (is (< side (square-side 4))))))
    (testing "one cell takes the whole pane"
      (let [two-by-two (square-side 4)]
        (ui/select! robot "#preview-layout-selector" "1x1")
        (ui/wait-for! #(nil? (ui/query (canvas 1))))
        (ui/wait-for! #(< two-by-two (first (canvas-size 0))))
        (is (< (* 1.5 two-by-two) (square-side 1)))))))


;; A cell names a group or a laser (zones plan Z3, 05 §7)

(defn- ring
  "A small lit circle (radius r) on target."
  [target r]
  {:id (random-uuid) :type :preset :preset-id :circle :enabled? true :effects []
   :params {:radius r :num-points 64} :target target})

(defn- lit-in-band
  "Lit pixels of cell i's canvas whose column is within lo-hi of its width
   (0 the left edge, 1 the right)."
  [i lo hi]
  (let [^Node node (ui/query (canvas i))
        width (long (ui/on-fx-thread #(.getWidth (.getLayoutBounds node))))]
    (count (filter true? (map-indexed (fn [k argb]
                                        (let [x (/ (double (mod k width)) width)]
                                          (and (<= lo x hi)
                                               (or (> (bit-and (bit-shift-right argb 16) 0xFF) 100)
                                                   (> (bit-and (bit-shift-right argb 8) 0xFF) 100)
                                                   (> (bit-and argb 0xFF) 100)))))
                                      (ui/node-pixels (canvas i)))))))

(defn- play-left-and-right!
  "One laser, and a cue with a circle on Left and another on Right, playing."
  []
  (events/write-state! #(-> %
                            (assoc :projectors {:p1 {:name "Laser A" :host "10.0.0.1" :enabled? true :zone-groups []}})
                            (assoc-in [:chains :cue-chains [7 3]] {:target :all
                                                                    :items [(ring :auto/left 0.2) (ring :auto/right 0.2)]})))
  (events/dispatch! {:event/type :grid/trigger-cell :col 7 :row 3})
  (ui/wait-for! #(some? (get-in (state/get-raw-state) [:playback :active-cues [7 3]]))))

(deftest laser-cell-shows-both-halves-of-a-split-laser-test
  (play-left-and-right!)
  (events/dispatch! {:event/type :preview/set-cell-zone :cell-index 3 :target :auto.laser/p1})
  (with-clock (System/nanoTime)
    (fn [_]
      (ui/wait-for! #(pos? (lit-in-band 3 0.0 0.46)))
      (is (pos? (lit-in-band 3 0.54 1.0)) "the circle in its left half, the other in its right")
      (is (zero? (lit-in-band 3 0.47 0.53)) "each fitted into its half: nothing in the middle")
      (is (= "Laser preview" (message 3)))
      (ui/assert-text! "#preview-zone-label-3 .label" "Laser A"))))

(deftest group-cell-shows-what-is-sent-to-it-test
  (play-left-and-right!)
  (with-clock (System/nanoTime)
    (fn [_]
      (testing "cell 1 is Left: the circle sent there, as drawn, in the middle"
        (ui/wait-for! #(pos? (lit-in-band 1 0.4 0.6)))
        (is (zero? (lit-in-band 1 0.0 0.3))))
      (testing "Center: nothing is sent to it, though the laser draws both"
        (is (= "No Preview" (message 3)))))))

(deftest old-cell-still-shows-its-group-test
  (play-square!)
  (events/write-state! assoc-in [:config :preview :grid-cells 2] {:zone-group-id :all})
  (with-clock (System/nanoTime)
    (fn [_]
      (ui/wait-for! #(pos? (lit-in 2)))
      (ui/assert-text! "#preview-zone-label-2 .label" "All"))))
