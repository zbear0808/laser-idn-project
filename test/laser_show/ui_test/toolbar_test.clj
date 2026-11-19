(ns laser-show.ui-test.toolbar-test
  "Toolbar: transport, BPM input, trigger mode, tap tempo and the beat
   indicator."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene.control Button]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest transport-play-stop-flow
  (let [robot (ui/robot)]
    (ui/click! robot "#play-btn")
    (ui/wait-for! #(true? (get-in (state/get-raw-state) [:playback :playing?])))
    (ui/assert-visible! "#stop-btn")
    (ui/click! robot "#stop-btn")
    (ui/wait-for! #(false? (get-in (state/get-raw-state) [:playback :playing?])))
    (ui/assert-visible! "#play-btn")))

(deftest retrigger-button-has-its-own-id
  (let [robot (ui/robot)]
    (ui/assert-count! "#play-btn" 1)
    (ui/assert-count! "#retrigger-btn" 1)
    (is (not (identical? (ui/query "#play-btn") (ui/query "#retrigger-btn"))))
    (ui/click! robot "#retrigger-btn")
    (ui/sync-fx!)
    (is (not (get-in (state/get-raw-state) [:playback :playing?])))))

(defn- tooltip-text [selector]
  (let [^Button button (ui/query selector)]
    (ui/on-fx-thread #(some-> button .getTooltip .getText))))

(defn- click-then-rest!
  "Click selector, then rest the mouse on the status bar, so the button's own
   tooltip doesn't come up over the window (ui/query looks in the top window
   only)."
  [robot selector]
  (ui/click! robot selector)
  (ui/hover! robot "#status-bar"))

(deftest stop-shows-output-off-and-held-cells-test
  (let [robot (ui/robot)
        cells ["#grid-cell-0-0" "#grid-cell-1-0"]
        all-have? (fn [style-class] (every? #(ui/has-style-class? % style-class) cells))
        none-has? (fn [style-class] (not-any? #(ui/has-style-class? % style-class) cells))
        playing? #(get-in (state/get-raw-state) [:playback :playing?])]
    (ui/click! robot "#grid-cell-0-0")
    (ui/wait-for! #(ui/has-style-class? "#grid-cell-0-0" "grid-cell-active"))
    (ui/click! robot "#grid-cell-1-0")
    (ui/wait-for! #(all-have? "grid-cell-active"))
    (is (not (ui/visible? "#output-held-chip")))
    (is (= "Stop (blackout)" (tooltip-text "#stop-btn")))
    (click-then-rest! robot "#stop-btn")
    (ui/wait-for! #(false? (playing?)))
    (testing "Stop is the blackout: the cues stay in play, held, and the toolbar says so"
      (ui/assert-visible! "#output-held-chip")
      (ui/assert-text! "#output-held-chip" "OUTPUT OFF · 2 cues held")
      (ui/wait-for! #(all-have? "grid-cell-held"))
      (is (none-has? "grid-cell-active") "held cells don't look like they play")
      (is (= "Resume 2 cues" (tooltip-text "#play-btn"))))
    (testing "Play brings them back"
      (click-then-rest! robot "#play-btn")
      (ui/wait-for! #(true? (playing?)))
      (ui/wait-for! #(all-have? "grid-cell-active"))
      (is (none-has? "grid-cell-held"))
      (ui/wait-for! #(not (ui/visible? "#output-held-chip")))
      (is (= "Stop (blackout)" (tooltip-text "#stop-btn"))))
    (testing "with nothing in play, Play is just Play"
      (ui/click! robot "#grid-cell-0-0")
      (ui/click! robot "#grid-cell-1-0")
      (ui/wait-for! #(false? (playing?)))
      (ui/wait-for! #(= "Play" (tooltip-text "#play-btn")))
      (is (not (ui/visible? "#output-held-chip"))))))

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

(deftest tap-tempo-button-records-taps
  ;; Every click records a tap. Whether taps add up depends on the gaps between
  ;; them (a gap over 2 s starts over; timing-tap-tempo-test covers that with
  ;; fixed times), and a slow CI machine can take that long between clicks.
  (let [robot (ui/robot)
        tap-times #(get-in (state/get-raw-state) [:timing :tap-times])]
    (dotimes [_ 3]
      (let [before (tap-times)]
        (ui/click! robot "#tap-tempo-btn")
        (ui/wait-for! #(not= before (tap-times)))))
    (let [taps (tap-times)]
      (is (<= 1 (count taps) 3))
      (is (every? #(<= % 2000) (map - (rest taps) taps)) "kept taps are at most 2 s apart"))))

(deftest beat-indicator-moves-on-each-beat-test
  ;; app/start-test! runs no show clock: tick it by hand, on a fake clock
  (let [t0 (System/nanoTime)
        tick! #(show-clock/tick! (+ t0 (* % 1000000)))
        active #(set (keep (fn [i] (when (ui/has-style-class? (str "#beat-square-" i) "beat-active") i))
                           (range 4)))]
    (tick! 0)
    (ui/wait-for! #(= #{0} (active)))
    (doseq [[ms beat] [[600 1] [1100 2] [1600 3] [2100 0]]]
      (tick! ms)
      (ui/wait-for! #(= #{beat} (active)))
      (is (= #{beat} (active)) (str (/ ms 500.0) " beats at 120 BPM")))))
