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
  (let [robot (ui/robot)]
    (dotimes [i 3]
      (ui/click! robot "#tap-tempo-btn")
      (ui/wait-for! #(= (inc i)
                        (count (get-in (state/get-raw-state) [:timing :tap-times])))))
    (is (= 3 (count (get-in (state/get-raw-state) [:timing :tap-times]))))))
