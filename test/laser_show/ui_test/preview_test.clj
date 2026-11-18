(ns laser-show.ui-test.preview-test
  "Preview panel: layout selector, the per-cell zone picker, and the cells
   drawing the show clock's newest tick (plans/2026-11-17-show-clock/03 §8)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.clock-view :as clock-view])
  (:import [javafx.scene Node]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

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


;; The cells draw the show clock's tick (app/start-test! runs no clock: the
;; tests tick it from a thread of their own, with-clock)

(def ^:private stretching-square
  "A square on :all whose width follows a 1-beat sine, which starts at its
   peak (time/sine-wave-normalized): x-scale 1.0 at beat 0, 0.6 a quarter
   beat later."
  {:destination-zone {:zone-group-id :all}
   :items [{:id (random-uuid) :type :preset :preset-id :square :enabled? true
            :effects [{:id (random-uuid) :effect-id :scale :enabled? true
                       :params {:x-scale {:type :sine :min 0.2 :max 1.0 :period 1.0}}}]}]})

(defn- play-square!
  "The stretching square at [7 3], playing. Written before any event is
   queued, so no event's write-back can lose it."
  []
  (state/swap-state! assoc-in [:chains :cue-chains [7 3]] stretching-square)
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

(defn- with-clock
  "Call (f at-ms) while a thread ticks the show clock every 16 ms, as the
   real clock does, at show time t0 + @at-ms (the test moves it). A cell
   draws only a tick newer than STALE_MS, so the clock has to keep ticking
   while a test reads the canvases: on a loaded machine one read can take
   longer than that (the macOS CI runner)."
  [t0 f]
  (let [at-ms (atom 0)
        running (atom true)
        ticker (future (while @running
                         (show-clock/tick! (+ t0 (* @at-ms 1000000)))
                         (Thread/sleep 16)))]
    (try
      (f at-ms)
      (finally
        (reset! running false)
        @ticker))))

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
        (events/dispatch! {:event/type :preview/set-cell-zone :cell-index 1 :zone-group-id :all})
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
