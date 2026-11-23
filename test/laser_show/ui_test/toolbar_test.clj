(ns laser-show.ui-test.toolbar-test
  "Toolbar: transport, BPM input, trigger mode, tap tempo and the beat
   indicator."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene.control Button Labeled TextInputControl]
           [javafx.scene.input KeyCode]))

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

(deftest toolbar-and-settings-follow-a-steady-link-tempo-test
  (let [robot (ui/robot)]
    ;; start-test! never connects to a peer. Supply the service's status only.
    (ui/assert-disabled! "#link-sync-btn")
    (events/dispatch! {:event/type :timing/carabiner-connected :connected? true})
    (try
      (events/dispatch! {:event/type :timing/link-bpm-changed :bpm 128.0})
      (ui/assert-enabled! "#link-sync-btn")
      (ui/click! robot "#link-sync-btn")
      (ui/wait-for! #(true? (get-in (state/get-raw-state) [:backend :link :link-enabled?])))
      (ui/assert-text! "#bpm-input" "120.0")
      (ui/click! robot "#link-sync-btn")
      (ui/assert-text! "#bpm-input" "128.0")
      (ui/click! robot "#settings-tab")
      (ui/assert-selected! "#link-sync-bpm")
      (ui/uncheck! robot "#link-sync-bpm")
      (events/dispatch! {:event/type :timing/link-bpm-changed :bpm 135.0})
      (is (events/flush!))
      (ui/assert-text! "#bpm-input" "128.0")
      (ui/check! robot "#link-sync-bpm")
      (ui/assert-text! "#bpm-input" "135.0")
      (ui/click! robot "#link-sync-btn")
      (ui/wait-for! #(not (ui/selected? "#link-sync-bpm")))
      (is (true? (get-in (state/get-raw-state) [:backend :link :link-enabled?]))
          "stop following tempo without turning off Link")
      (finally
        (events/dispatch! {:event/type :timing/carabiner-connected :connected? false})
        (events/flush!)))))

(deftest bpm-commits-on-focus-loss-test
  (let [robot (ui/robot)
        bpm #(get-in (state/get-raw-state) [:timing :bpm])
        leave! #(ui/focus! robot "#trigger-mode-selector")]
    (testing "typing alone changes nothing; leaving the field sets it, as Enter does"
      (ui/fill! robot "#bpm-input" "128")
      (ui/sync-fx!)
      (is (= 120.0 (bpm)))
      (leave!)
      (ui/wait-for! #(= 128.0 (bpm)))
      (ui/assert-text! "#bpm-input" "128.0"))
    (testing "text that is no BPM puts the BPM back"
      (doseq [text ["fast" "0" "-90"]]
        (ui/fill! robot "#bpm-input" text)
        (leave!)
        (ui/assert-text! "#bpm-input" "128.0")
        (is (= 128.0 (bpm)) text)))
    (testing "passing through the field without typing leaves the BPM as it is, not as it shows"
      (events/dispatch! {:event/type :timing/set-bpm :bpm 123.456})
      (ui/assert-text! "#bpm-input" "123.5")
      (ui/focus! robot "#bpm-input")
      (leave!)
      (ui/sync-fx!)
      (is (= 123.456 (bpm))))))

(deftest trigger-mode-selection-updates-state
  (let [robot (ui/robot)]
    (ui/select! robot "#trigger-mode-selector" "Toggle")
    (ui/wait-for! #(= :toggle
                      (get-in (state/get-raw-state) [:config :cue :trigger-mode])))
    (is (= :toggle (get-in (state/get-raw-state) [:config :cue :trigger-mode])))))

(deftest tap-tempo-button-records-taps
  ;; Every click records a tap: the clicks are more than 100 ms apart, as a
  ;; faster one is a bounce, which changes nothing (and the wait below would
  ;; never end). Whether taps add up depends on the gaps between them (a gap
  ;; over 1.5 s starts a new series; timing-tap-tempo-test covers that with
  ;; fixed times), and a slow CI machine can take that long between clicks.
  (let [robot (ui/robot)
        taps #(get-in (state/get-raw-state) [:timing :tap :taps])]
    (dotimes [_ 3]
      (let [before (taps)]
        (Thread/sleep 150)
        (ui/click! robot "#tap-tempo-btn")
        (ui/wait-for! #(not= before (taps)))))
    (let [kept (taps)]
      (is (<= 1 (count kept) 3))
      (is (every? #(<= 100 % 1500) (map - (rest kept) kept)) "kept taps are 100 ms to 1.5 s apart"))))

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


;; Single keys after a click on the toolbar or the tab bar (keyboard plan 03,
;; D6 A): the buttons and the trigger-mode combo are mouse-only, so a click
;; leaves the focus where it was, and Space, R and Escape still reach the
;; Transport menu's accelerators

(defn- playing? [] (true? (get-in (state/get-raw-state) [:playback :playing?])))

(defn- focus-owner []
  (ui/on-fx-thread #(.getFocusOwner (.getScene (ui/main-window)))))

(defn- focus-desc []
  (let [o (focus-owner)]
    (ui/on-fx-thread #(when o (str (.getSimpleName (class o)) (some->> (.getId ^javafx.scene.Node o) (str "#")))))))

(defn- space-toggles-play!
  "Space plays, and Space again stops."
  [robot what]
  (testing (str "after " what ", Space plays and stops")
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! playing?)
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! #(not (playing?)))))

(deftest space-plays-at-startup-test
  (let [robot (ui/robot)]
    (is (not (instance? TextInputControl (focus-owner)))
        (str "the window starts with the focus off the text fields, on " (focus-desc)))
    (space-toggles-play! robot "start-up")))

(deftest space-plays-after-clicking-tap-test
  (let [robot (ui/robot)
        taps #(count (get-in (state/get-raw-state) [:timing :tap :taps]))]
    (ui/click! robot "#tap-tempo-btn")
    (ui/wait-for! #(= 1 (taps)))
    (is (not= "Button#tap-tempo-btn" (focus-desc)) "TAP doesn't take the focus")
    (space-toggles-play! robot "a click on TAP")
    (is (= 1 (taps)) "Space doesn't tap")))

(deftest space-plays-after-clicking-a-tab-or-the-trigger-mode-combo-test
  (let [robot (ui/robot)
        active-tab #(get-in (state/get-raw-state) [:ui :active-tab])]
    (doseq [[tab id] [["#perform-tab" :perform] ["#grid-tab" :grid]]]
      (ui/click! robot tab)
      (ui/wait-for! #(= id (active-tab)))
      (is (not= (str "Button" tab) (focus-desc)) (str tab " doesn't take the focus"))
      (space-toggles-play! robot (str "a click on " tab)))
    (testing "the trigger-mode combo: pick Toggle from its list"
      (ui/click! robot "#trigger-mode-selector")
      (ui/wait-for! #(some (fn [n] (= "Toggle" (ui/on-fx-thread (fn [] (.getText ^Labeled n)))))
                           (ui/query-all ".list-cell")))
      (ui/click-node! robot (some (fn [n] (when (= "Toggle" (ui/on-fx-thread (fn [] (.getText ^Labeled n)))) n))
                                  (ui/query-all ".list-cell")))
      (ui/wait-for! #(= :toggle (get-in (state/get-raw-state) [:config :cue :trigger-mode])))
      (is (not= "ComboBox#trigger-mode-selector" (focus-desc)) "the combo doesn't take the focus"))
    (space-toggles-play! robot "a pick from the trigger-mode combo")
    (testing "and the other toolbar buttons"
      (doseq [sel ["#retrigger-btn" "#play-btn" "#stop-btn" "#stream-btn"]]
        (is (not (ui/on-fx-thread #(.isFocusTraversable ^javafx.scene.Node (ui/query sel)))) sel)
        (when (= sel "#play-btn")
          (ui/click! robot "#play-btn")
          (ui/wait-for! playing?))
        (when (= sel "#stop-btn")
          (ui/click! robot "#stop-btn")
          (ui/wait-for! #(not (playing?))))))))

(deftest single-keys-stay-in-the-bpm-field-test
  (let [robot (ui/robot)
        restart #(get-in (state/get-raw-state) [:playback :requests :restart :id])]
    (ui/focus! robot "#bpm-input")
    (ui/press! robot KeyCode/SPACE)
    (is (events/flush!))
    (is (not (playing?)) "Space types into the field")
    (events/write-state! #(-> % (assoc-in [:playback :playing?] true) (assoc-in [:grid :selected-cell] [0 0])))
    (let [before (restart)
          items (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :items])]
      (ui/press! robot KeyCode/R)
      (ui/press! robot KeyCode/DELETE)
      (is (events/flush!))
      (is (= before (restart)) "R doesn't retrigger")
      (is (= items (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :items])) "Delete doesn't clear the cell")
      (is (instance? TextInputControl (focus-owner))))))

(deftest escape-in-the-bpm-field-stops-the-show-test
  ;; Escape is the panic key: Stop fires whatever has the focus, a text field
  ;; too (keyboard plan 03 §7, 05 D10 A)
  (let [robot (ui/robot)]
    (ui/focus! robot "#bpm-input")
    (events/write-state! assoc-in [:playback :playing?] true)
    (ui/press! robot KeyCode/ESCAPE)
    (ui/wait-for! #(not (playing?)))
    (is (= "TextField#bpm-input" (focus-desc)) "the focus stays in the field")))

(deftest a-click-off-the-bpm-field-applies-it-test
  ;; The buttons don't take the focus (D6 A), so a click on one would leave
  ;; it in the field, with what was typed not applied and Space typing:
  ;; a press on something that doesn't take the focus moves it off the field
  (let [robot (ui/robot)
        bpm #(get-in (state/get-raw-state) [:timing :bpm])]
    (doseq [[sel typed] [["#grid-tab" "128"] ["#perform-tab" "131"] ["#tap-tempo-btn" "126"]]]
      (testing (str "a click on " sel)
        (ui/type! robot "#bpm-input" typed)
        (ui/sync-fx!)
        (is (= "TextField#bpm-input" (focus-desc)))
        (ui/click! robot sel)
        (ui/wait-for! #(not (instance? TextInputControl (focus-owner))))
        (when-not (= sel "#tap-tempo-btn")
          (ui/wait-for! #(= (parse-double typed) (bpm))))
        (space-toggles-play! robot (str "typing " typed " and a click on " sel))))
    (testing "a click in the field keeps the focus there"
      (ui/type! robot "#bpm-input" "140")
      (ui/click! robot "#bpm-input")
      (ui/sync-fx!)
      (is (= "TextField#bpm-input" (focus-desc)))
      (is (= "140" (ui/node-text "#bpm-input"))))))
