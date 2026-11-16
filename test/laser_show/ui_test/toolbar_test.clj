(ns laser-show.ui-test.toolbar-test
  "Toolbar: transport, BPM input, trigger mode and tap tempo."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

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
