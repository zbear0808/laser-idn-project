(ns laser-show.ui-test.perform-test
  "The Perform tab: five pads played by mouse, touch or a learned control,
   the vibe strip, the banner, learning a pad's control, a pointer's hold
   ending when the window loses focus, the faders' meters, and safety: the
   show lock, the NO OUTPUT banner, the audience marks and a stuck pad.
   
   The headless robot marks every mouse event synthesized, as JavaFX marks
   the ones it makes from a touch, and the pads leave those out (a touch
   counts through its touch events), so the pads are pressed with
   ui/press-node! and ui/release-node!, which fire a real mouse's events
   headless."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.events :as input-events]
            [laser-show.input.router :as input-router]
            [laser-show.input.store :as store]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [com.sun.javafx.stage WindowHelper]
           [com.sun.javafx.tk.quantum WindowStage]
           [javafx.event Event]
           [javafx.scene Node]
           [javafx.scene.control ProgressBar]
           [javafx.scene.input MouseButton MouseEvent TouchEvent TouchPoint TouchPoint$State]
           [javafx.stage Stage]))

(defn- with-input-mapper
  "start-test! doesn't start the input mapper (app.clj:252), and a learned
   note reaches the app only through it."
  [tests]
  (input-mapper/set-dispatch-fn! events/dispatch!)
  (input-mapper/start-mapper!)
  (try
    (tests)
    (finally
      (input-mapper/stop-mapper!)
      (input-mapper/set-dispatch-fn! nil))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app with-input-mapper)

(def ^:private starter-names
  "What the HIT pad says for each of the starter vibe's HIT cells."
  {[0 0] "circle" [1 0] "square" [2 0] "triangle" [3 0] "star"})

(defn- app-state [] (state/get-raw-state))
(defn- rt [& ks] (get-in (app-state) (into [:vibe-runtime] ks)))
(defn- intent [cell] (get-in (app-state) [:playback :active-cues cell]))

(defn- open-perform! [robot]
  (ui/click! robot "#perform-tab")
  (ui/wait-for! #(= :perform (get-in (app-state) [:ui :active-tab])))
  (ui/assert-visible! "#vibe-pad-row"))

(defn- flush-event-queue!
  "Wait until every event dispatched so far has been handled: they are
   handled in order, so a marker landing means the rest did."
  []
  (ui/sync-fx!)
  (let [marker (+ 60.25 (rand-int 100))]
    (events/dispatch! {:event/type :timing/set-bpm :bpm marker})
    (ui/wait-for! #(= marker (get-in (app-state) [:timing :bpm])))))

(defn- note [type n]
  {:type type :source :midi :channel 0 :note n :velocity (if (= :note-on type) 1.0 0.0)
   :timestamp (System/currentTimeMillis)})

(defn- bind! [n role]
  (state/swap-state! assoc-in [:config :input :trigger-map {:source :midi :channel 0 :note n}] {:type :vibe :role role}))

(defn- fire!
  "Fire events at the node found by selector, in order, on the FX thread."
  [selector & evs]
  (let [^Node node (ui/query selector)]
    (ui/on-fx-thread #(doseq [e evs] (Event/fireEvent node e))))
  (ui/sync-fx!))

(defn- mouse
  "A mouse event of type at a pad: :synthesized? as JavaFX makes them from a
   touch, :button for another button than the primary."
  [type & {:keys [button synthesized?] :or {button MouseButton/PRIMARY}}]
  (MouseEvent. type 60.0 60.0 60.0 60.0 button 1 false false false false
               (and (= MouseEvent/MOUSE_PRESSED type) (= MouseButton/PRIMARY button)) false
               (and (= MouseEvent/MOUSE_PRESSED type) (= MouseButton/SECONDARY button))
               (boolean synthesized?) false true nil))

(defn- touch [type id point-state]
  (let [point (TouchPoint. (int id) point-state 60.0 60.0 60.0 60.0 nil nil)]
    (TouchEvent. type point [point] 1 false false false false)))

(defn- main-stage ^Stage []
  (some-> (first (ui/query-all "#main-layout")) .getScene .getWindow))

(defn- notify-focus!
  "Tell the main window it gained or lost the focus the way the OS does: the
   glass window's notifyFocus, from where JavaFX's own path sets the Stage's
   focused property. Headless glass has no other window to take the focus
   (requestFocus only ever gains it)."
  [gained?]
  (ui/on-fx-thread
   #(let [glass (.getPlatformWindow ^WindowStage (WindowHelper/getPeer (main-stage)))
          notify (doto (.getDeclaredMethod com.sun.glass.ui.Window "notifyFocus" (into-array Class [Integer/TYPE]))
                   (.setAccessible true))]
      (.invoke notify glass (object-array [(int (if gained?
                                                  com.sun.glass.events.WindowEvent/FOCUS_GAINED
                                                  com.sun.glass.events.WindowEvent/FOCUS_LOST))]))))
  (ui/sync-fx!))


(deftest perform-tab-shows-five-pads-test
  (let [robot (ui/robot)]
    (open-perform! robot)
    (ui/assert-count! ".vibe-pad" 5)
    (doseq [role ["hit" "accent" "flash" "vibe" "blackout"]]
      (ui/assert-visible! (str "#vibe-pad-" role)))
    (ui/assert-text! "#vibe-header-name" "SHAPES")
    (ui/assert-text! "#vibe-header-energy" "energy ●●○○")
    (is (ui/has-style-class? "#vibe-chip-vibe-1" "vibe-chip-current"))
    (ui/assert-visible! "#vibe-chip-vibe-2")
    (ui/assert-text! "#vibe-pad-vibe-now" "→ Lines")
    (ui/assert-text! "#vibe-pad-hit-now" "–")
    (ui/assert-text! "#vibe-pad-hit-keys" "Right-click to learn a control")
    (is (not (ui/visible? "#vibe-banner")))
    (ui/assert-visible! "#preview-panel")
    (is (not-any? #(.isFocusTraversable ^Node %) (ui/query-all ".vibe-pad"))
        "invariant 7: Space or Enter can never press a pad")))

(deftest hit-pad-hold-plays-then-release-cuts-test
  (let [robot (ui/robot)]
    (open-perform! robot)
    (ui/press-node! robot "#vibe-pad-hit")
    (ui/wait-for! #(some? (rt :voices :hit)))
    (let [{:keys [cell run]} (rt :voices :hit)]
      (is (= {:run run :decay-beats 1.0 :hold? true} (intent cell)))
      (is (= :pad (rt :held :hit :src)))
      (ui/wait-for! #(ui/has-style-class? "#vibe-pad-hit" "vibe-pad-held"))
      (ui/assert-text! "#vibe-pad-hit-now" (starter-names cell))
      (Thread/sleep 400)
      (ui/release-node! robot "#vibe-pad-hit")
      (ui/wait-for! #(nil? (intent cell)))
      (is (nil? (rt :held :hit)) "held past the quick-tap time: cut on release")
      (ui/wait-for! #(not (ui/has-style-class? "#vibe-pad-hit" "vibe-pad-held")))
      (ui/assert-text! "#vibe-pad-hit-now" "–"))))

(deftest hit-pad-quick-click-leaves-a-decay-test
  (let [robot (ui/robot)]
    ;; A busy machine can't stretch the click into a hold
    (state/swap-state! assoc-in [:config :vibes :settings :quick-tap-ms] 2000)
    (open-perform! robot)
    (ui/mouse-click! robot "#vibe-pad-hit")
    (ui/wait-for! #(and (some? (rt :voices :hit)) (nil? (rt :held :hit))))
    (let [{:keys [cell run]} (rt :voices :hit)]
      (is (= {:run run :decay-beats 1.0 :hold? false} (intent cell))
          "no show clock runs here, so the decay waits for its ticks")
      (ui/wait-for! #(not (ui/has-style-class? "#vibe-pad-hit" "vibe-pad-held")))
      (ui/assert-text! "#vibe-pad-hit-now" (starter-names cell)))))

(deftest hit-pad-touch-presses-once-test
  (testing "one finger: its touch events press and release, the mouse events JavaFX makes from it don't"
    (let [robot (ui/robot)]
      (open-perform! robot)
      (fire! "#vibe-pad-hit"
             (touch TouchEvent/TOUCH_PRESSED 1 TouchPoint$State/PRESSED)
             (mouse MouseEvent/MOUSE_PRESSED :synthesized? true))
      (ui/wait-for! #(= :pad (rt :held :hit :src)))
      (fire! "#vibe-pad-hit"
             (touch TouchEvent/TOUCH_RELEASED 1 TouchPoint$State/RELEASED)
             (mouse MouseEvent/MOUSE_RELEASED :synthesized? true)
             (mouse MouseEvent/MOUSE_CLICKED :synthesized? true))
      (ui/wait-for! #(nil? (rt :held :hit)))
      (flush-event-queue!)
      (is (= 1 (rt :started)) "one cue picked"))))

(deftest vibe-pad-tap-switches-and-long-press-locks-test
  (let [robot (ui/robot)]
    (open-perform! robot)
    (ui/mouse-click! robot "#vibe-pad-vibe")
    (ui/wait-for! #(= :vibe-2 (rt :current)))
    (ui/assert-text! "#vibe-header-name" "LINES")
    (ui/wait-for! #(ui/has-style-class? "#vibe-chip-vibe-2" "vibe-chip-current"))
    (ui/assert-text! "#vibe-pad-vibe-now" "→ Shapes")
    (testing "a long press locks the rotation and keeps the vibe"
      (ui/press-node! robot "#vibe-pad-vibe")
      (ui/wait-for! #(some? (rt :held :vibe)))
      (Thread/sleep 800)
      (ui/release-node! robot "#vibe-pad-vibe")
      (ui/wait-for! #(= :locked (rt :rotation)))
      (is (= :vibe-2 (rt :current)))
      (ui/assert-visible! "#vibe-header-locked")
      (ui/assert-text! "#vibe-pad-vibe-badge" "LOCKED"))))

(deftest blackout-pad-shows-banner-and-dims-the-rest-test
  (let [robot (ui/robot)]
    (open-perform! robot)
    (ui/mouse-click! robot "#vibe-pad-blackout")
    (ui/wait-for! #(true? (rt :blackout?)))
    (ui/assert-visible! "#vibe-banner")
    (ui/assert-text! "#vibe-banner" "BLACKOUT")
    (ui/wait-for! #(ui/has-style-class? "#vibe-pad-blackout" "vibe-pad-on"))
    (doseq [role ["hit" "accent" "flash" "vibe"]]
      (is (ui/has-style-class? (str "#vibe-pad-" role) "vibe-pad-dim") role))
    (is (not (ui/has-style-class? "#vibe-pad-blackout" "vibe-pad-dim")))
    (testing "a cue started from the grid during blackout lights the room: the banner says so"
      (events/dispatch! {:event/type :grid/activate-cell :col 0 :row 0})
      (ui/assert-text! "#vibe-banner" "BLACKOUT · vibe keys off · a grid cue is playing")
      (is (true? (rt :blackout?))))
    (testing "pressed again: the banner goes and the pads come back"
      (ui/mouse-click! robot "#vibe-pad-blackout")
      (ui/wait-for! #(false? (rt :blackout?)))
      (ui/wait-for! #(not (ui/visible? "#vibe-banner")))
      (ui/wait-for! #(not (ui/has-style-class? "#vibe-pad-hit" "vibe-pad-dim")))
      (is (not (ui/has-style-class? "#vibe-pad-blackout" "vibe-pad-on"))))))

(deftest learn-binds-a-role-to-a-note-test
  (let [robot (ui/robot)
        note-key {:source :midi :channel 0 :note 36}]
    (open-perform! robot)
    (testing "a right-click presses nothing"
      (fire! "#vibe-pad-hit"
             (mouse MouseEvent/MOUSE_PRESSED :button MouseButton/SECONDARY)
             (mouse MouseEvent/MOUSE_RELEASED :button MouseButton/SECONDARY))
      (flush-event-queue!)
      (is (empty? (rt :held)))
      (is (zero? (rt :started))))
    (ui/right-click-node! robot (ui/query "#vibe-pad-hit"))
    (ui/click! robot "#vibe-pad-hit-learn")
    (ui/wait-for! #(= {:type :vibe :role :hit} (get-in (app-state) [:backend :input :learning-target])))
    (ui/assert-text! "#vibe-pad-hit-badge" "LEARN")
    (ui/assert-text! "#vibe-learn-prompt" "Press the key for HIT")
    (is (ui/has-style-class? "#vibe-pad-hit" "vibe-pad-learning"))
    (is (empty? (rt :held)) "the menu pressed nothing")
    (input-router/dispatch! (note :note-on 36))
    (ui/wait-for! #(= {:type :vibe :role :hit} (get-in (app-state) [:config :input :trigger-map note-key])))
    (ui/assert-text! "#vibe-pad-hit-keys" "MIDI Ch 1 Note 36")
    (ui/wait-for! #(not (ui/visible? "#vibe-pad-hit-badge")))
    (is (not (ui/visible? "#vibe-learn-prompt")))
    (input-router/dispatch! (note :note-off 36))))

(deftest a-learned-note-plays-through-the-mapper-test
  (let [robot (ui/robot)]
    (bind! 36 :hit)
    (open-perform! robot)
    (ui/assert-text! "#vibe-pad-hit-keys" "MIDI Ch 1 Note 36")
    (input-router/dispatch! (note :note-on 36))
    (ui/wait-for! #(= :input (rt :held :hit :src)))
    (is (some? (intent (rt :voices :hit :cell))))
    (ui/wait-for! #(ui/has-style-class? "#vibe-pad-hit" "vibe-pad-held"))
    (input-router/dispatch! (note :note-off 36))
    (ui/wait-for! #(nil? (rt :held :hit)))
    (ui/wait-for! #(not (ui/has-style-class? "#vibe-pad-hit" "vibe-pad-held")))))

(deftest strip-chip-click-selects-test
  (let [robot (ui/robot)]
    (open-perform! robot)
    (ui/click! robot "#vibe-chip-vibe-2")
    (ui/wait-for! #(= :vibe-2 (rt :current)))
    (ui/assert-text! "#vibe-header-name" "LINES")
    (ui/wait-for! #(ui/has-style-class? "#vibe-chip-vibe-2" "vibe-chip-current"))
    (is (not (ui/has-style-class? "#vibe-chip-vibe-1" "vibe-chip-current")))))

(deftest pad-hold-ends-when-the-window-loses-focus-test
  (let [robot (ui/robot)]
    (bind! 36 :hit)
    (bind! 37 :accent)
    (open-perform! robot)
    (notify-focus! true)
    (ui/press-node! robot "#vibe-pad-hit")
    (input-router/dispatch! (note :note-on 37))
    (ui/wait-for! #(and (= :pad (rt :held :hit :src)) (= :input (rt :held :accent :src))))
    (notify-focus! false)
    (is (false? (.isFocused (main-stage))))
    (ui/wait-for! #(nil? (rt :held :hit)))
    (is (= :input (rt :held :accent :src)) "invariant 2: a MIDI hold doesn't end on focus loss")
    (ui/wait-for! #(not (ui/has-style-class? "#vibe-pad-hit" "vibe-pad-held")))
    (is (ui/has-style-class? "#vibe-pad-accent" "vibe-pad-held"))
    (testing "the mouse's late release doesn't let go of a key's hold"
      (input-router/dispatch! (note :note-on 36))
      (ui/wait-for! #(= :input (rt :held :hit :src)))
      (ui/release-node! robot "#vibe-pad-hit")
      (flush-event-queue!)
      (is (= :input (rt :held :hit :src))))
    (input-router/dispatch! (note :note-off 36))
    (input-router/dispatch! (note :note-off 37))
    (ui/wait-for! #(empty? (rt :held)))))


;; Auto-advance (plans/2026-11-18-vibe-mode/06 §4)

(defn- vibe-runtime! [m]
  (state/swap-state! update :vibe-runtime merge m))

(defn- countdown-opacity []
  (let [^Node n (first (ui/query-all "#vibe-countdown"))]
    (ui/on-fx-thread #(.getOpacity n))))

(deftest auto-switch-and-countdown-test
  (let [robot (ui/robot)]
    (open-perform! robot)
    (is (not (ui/selected? "#vibe-auto")) "off in a new project (V4 A)")
    (is (not (ui/visible? "#vibe-countdown")))
    (ui/click! robot "#vibe-auto")
    (ui/wait-for! #(true? (rt :auto?)))
    (ui/assert-selected! "#vibe-auto")
    (ui/assert-visible! "#vibe-countdown")
    (ui/assert-text! "#vibe-countdown" "ACTIVE 0:00 / 0:45")
    (testing "it counts the vibe's time"
      (vibe-runtime! {:current :vibe-1 :entered-at (- (System/currentTimeMillis) 31500)})
      (ui/wait-for! #(re-matches #"ACTIVE 0:3[12] / 0:45" (str (ui/node-text "#vibe-countdown")))))
    (testing "a session's choice: no undo step, nothing unsaved"
      (is (empty? (get-in (app-state) [:history :undo-stack])))
      (is (false? (get-in (app-state) [:project :dirty?]))))
    (testing "locked, there is nothing to count"
      (vibe-runtime! {:rotation :locked})
      (ui/wait-for! #(not (ui/visible? "#vibe-countdown")))
      (vibe-runtime! {:rotation :active})
      (ui/assert-visible! "#vibe-countdown"))
    (ui/click! robot "#vibe-auto")
    (ui/wait-for! #(false? (rt :auto?)))
    (ui/wait-for! #(not (ui/visible? "#vibe-countdown")))
    (is (not (ui/selected? "#vibe-auto")))))

(deftest armed-state-shows-the-next-vibe-test
  (let [robot (ui/robot)
        t0 (System/nanoTime)
        tick! #(show-clock/tick! (+ t0 (* % 1000000)))
        now #(System/currentTimeMillis)]
    (open-perform! robot)
    (ui/click! robot "#vibe-auto")
    (ui/wait-for! #(true? (rt :auto?)))
    (testing "Shapes has played its 45 s: the show clock arms it, and the countdown says what comes next"
      (vibe-runtime! {:current :vibe-1 :entered-at (- (now) 46000) :last-hit-at (now)})
      (tick! 0)
      (ui/wait-for! #(= :armed (rt :rotation)))
      (ui/assert-text! "#vibe-countdown" "ARMED → next: Lines")
      (is (ui/has-style-class? "#vibe-countdown" "vibe-countdown-armed"))
      (is (<= 0.35 (countdown-opacity) 1.0) "it pulses")
      (is (= :vibe-1 (rt :current)) "a HIT a moment ago: no gap yet"))
    (testing "at a phrase gap the vibe changes, and its time starts again"
      (vibe-runtime! {:last-hit-at (- (now) 5000)})
      (tick! 17)
      (ui/wait-for! #(= :vibe-2 (rt :current)))
      (ui/assert-text! "#vibe-header-name" "LINES")
      (ui/wait-for! #(= "ACTIVE 0:00 / 0:45" (ui/node-text "#vibe-countdown")))
      (is (not (ui/has-style-class? "#vibe-countdown" "vibe-countdown-armed")))
      (is (= 1.0 (countdown-opacity))))))


;; Tap reactions (plans/2026-11-18-vibe-mode/04 §4)

(defn- opacity [selector]
  (let [^Node n (first (ui/query-all selector))]
    (ui/on-fx-thread #(.getOpacity n))))

(defn- pulse [role] (get-in (app-state) [:playback :pulses role]))

(deftest pad-pulse-bar-flashes-on-a-press-test
  (let [robot (ui/robot)
        t0 (System/nanoTime)
        tick! #(show-clock/tick! (+ t0 (* % 1000000)))]
    (open-perform! robot)
    (tick! 0)
    (is (= 0.0 (opacity "#vibe-pad-hit-pulse")) "dark before any press")
    (ui/mouse-click! robot "#vibe-pad-hit")
    (ui/wait-for! #(= {:id 1 :held? false} (pulse :hit)))
    (tick! 17)
    (ui/wait-for! #(= 1.0 (opacity "#vibe-pad-hit-pulse")))
    (is (= 0.0 (opacity "#vibe-pad-accent-pulse")) "only its own pad's")
    (is (= 0.0 (opacity "#vibe-header-flash")) "a press isn't a vibe change")
    (tick! 142)
    (ui/wait-for! #(< 0.45 (opacity "#vibe-pad-hit-pulse") 0.55))
    (tick! 267)
    (ui/wait-for! #(= 0.0 (opacity "#vibe-pad-hit-pulse")))
    (is (= 0.0 (opacity "#vibe-pad-hit-pulse")) "gone half a beat after the press")
    (testing "a vibe change flashes the header, and VIBE's bar"
      (ui/mouse-click! robot "#vibe-pad-vibe")
      (ui/wait-for! #(= {:id 1} (pulse :vibe)))
      (tick! 300)
      (ui/wait-for! #(= 1.0 (opacity "#vibe-header-flash")))
      (is (= 1.0 (opacity "#vibe-pad-vibe-pulse"))))))

(deftest pad-names-its-reactions-test
  (let [robot (ui/robot)]
    (doseq [kind [:punch-size :step-colour :fade-in]]
      (events/dispatch! {:event/type :vibe/add-reaction :vibe :vibe-1 :kind kind}))
    (ui/wait-for! #(= 3 (count (get-in (app-state) [:config :vibes :library :vibe-1 :fx]))))
    (open-perform! robot)
    (ui/assert-text! "#vibe-pad-hit-reactions" "Punch size · Step colour")
    (ui/assert-visible! "#vibe-pad-hit-reactions")
    (ui/assert-text! "#vibe-pad-vibe-reactions" "Fade in")
    (is (not (ui/visible? "#vibe-pad-accent-reactions")) "ACCENT has none")
    (testing "another vibe: its own"
      (ui/click! robot "#vibe-chip-vibe-2")
      (ui/wait-for! #(not (ui/visible? "#vibe-pad-hit-reactions"))))))

(deftest effects-only-pad-says-so-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc-in [:config :vibes :library :vibe-1 :accent :cells] [])
    (events/dispatch! {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :scatter})
    (ui/wait-for! #(seq (get-in (app-state) [:config :vibes :library :vibe-1 :fx])))
    (open-perform! robot)
    (ui/assert-text! "#vibe-pad-accent-now" "Effects only")
    (ui/assert-text! "#vibe-pad-accent-reactions" "Scatter")
    (ui/press-node! robot "#vibe-pad-accent")
    (ui/wait-for! #(= {:id 1 :held? true :src :pad} (pulse :accent)))
    (ui/wait-for! #(ui/has-style-class? "#vibe-pad-accent" "vibe-pad-held"))
    (is (empty? (get-in (app-state) [:playback :active-cues])) "it plays no cue")
    (is (true? (get-in (app-state) [:chains :effect-chains [1 3] :active?])) "the vibe's reactions are on (the Master has [0 3])")
    (ui/assert-text! "#vibe-pad-accent-now" "Effects only")
    (ui/release-node! robot "#vibe-pad-accent")
    (ui/wait-for! #(= {:id 1 :held? false} (pulse :accent)))
    (is (= {:id 1 :held? false} (pulse :accent)) "the pad's release lets go of the pulse")
    (ui/wait-for! #(not (ui/has-style-class? "#vibe-pad-accent" "vibe-pad-held")))
    (testing "a role with neither cues nor reactions says it has nothing to play"
      (state/swap-state! assoc-in [:config :vibes :library :vibe-1 :hit :cells] [])
      (ui/mouse-click! robot "#vibe-pad-hit")
      (ui/assert-text! "#vibe-pad-hit-now" "Nothing to play"))))


;; The faders' meters (plans/2026-11-18-vibe-mode/05)

(defn- progress [selector]
  (let [^ProgressBar bar (first (ui/query-all selector))]
    (ui/on-fx-thread #(.getProgress bar))))

(defn- meter-ticks
  "A tick! fn on a fake clock: the meters draw the newest tick (no clock runs
   in these tests)."
  []
  (let [t0 (System/nanoTime)
        n (atom 0)]
    #(show-clock/tick! (+ t0 (* (swap! n inc) 17 1000000)))))

(deftest meters-follow-the-store-and-say-not-wired-test
  (let [robot (ui/robot)
        tick! (meter-ticks)
        controls {:slider-1 1 :slider-2 2}
        fader! (fn [slot v]
                 (input-router/dispatch! (input-events/control-change :midi 0 (controls slot) v))
                 (tick!))]
    (try
      (store/clear-continuous-values!)
      (flush-event-queue!)
      (state/swap-state! assoc-in [:config :input :macro-map] {[:midi 0 1] :slider-1 [:midi 0 2] :slider-2})
      (open-perform! robot)
      (tick!)
      (ui/assert-text! "#vibe-meter-slider-1-text" "Intensity –")
      (ui/assert-text! "#vibe-meter-slider-2-text" "Morph – · not wired")
      (is (ui/has-style-class? "#vibe-meter-slider-2" "vibe-meter-off") "invariant 6: dimmed, and says so")
      (is (not (ui/has-style-class? "#vibe-meter-slider-1" "vibe-meter-off")) "a new project's Master wires Intensity")
      (testing "a learned control's value, through the input mapper, drawn from the next tick"
        (fader! :slider-1 0.45)
        (ui/assert-text! "#vibe-meter-slider-1-text" "Intensity 45 %")
        (is (< (Math/abs (- 0.45 (progress "#vibe-meter-slider-1-bar"))) 1e-9))
        (fader! :slider-1 0.0)
        (ui/assert-text! "#vibe-meter-slider-1-text" "Intensity 0 %")
        (is (= 0.0 (progress "#vibe-meter-slider-1-bar")) "invariant 3: dark, and Perform says so"))
      (testing "a Morph in the vibe that plays wires fader 2"
        (events/dispatch! {:event/type :vibe/add-morph :vibe :vibe-1 :kind :size})
        (ui/wait-for! #(not (ui/has-style-class? "#vibe-meter-slider-2" "vibe-meter-off")))
        (ui/assert-text! "#vibe-meter-slider-2-text" "Morph –")
        (fader! :slider-2 1.0)
        (ui/assert-text! "#vibe-meter-slider-2-text" "Morph 100 %"))
      (testing "in a vibe that doesn't, it does nothing, and the meter says so"
        (ui/click! robot "#vibe-chip-vibe-2")
        (ui/wait-for! #(= :vibe-2 (rt :current)))
        (ui/wait-for! #(ui/has-style-class? "#vibe-meter-slider-2" "vibe-meter-off"))
        (ui/assert-text! "#vibe-meter-slider-2-text" "Morph 100 % · not wired"))
      (finally
        (store/clear-continuous-values!)))))

(deftest meters-say-a-dash-for-an-unbound-slot-test
  (let [robot (ui/robot)
        tick! (meter-ticks)]
    (try
      (store/clear-continuous-values!)
      (flush-event-queue!)
      (state/swap-state! assoc-in [:config :input :macro-map] {[:midi 0 7] :slider-1})
      (open-perform! robot)
      (input-router/dispatch! (input-events/control-change :midi 0 7 0.3))
      (tick!)
      (ui/assert-text! "#vibe-meter-slider-1-text" "Intensity 30 %")
      (testing "Cleared: the meter says – (the Master is at its default), though the control keeps its value"
        (events/dispatch! {:event/type :vibe/clear-control :slot :slider-1})
        (ui/wait-for! #(empty? (get-in (app-state) [:config :input :macro-map])))
        (tick!)
        (ui/assert-text! "#vibe-meter-slider-1-text" "Intensity –")
        (is (= 0.0 (progress "#vibe-meter-slider-1-bar")))
        (is (= 0.3 (store/get-continuous-value [:midi 0 7] nil))))
      (testing "and for a slot nothing was ever learned for, whatever the store holds"
        (store/set-continuous-value! [:macro :slider-2] 0.9)
        (tick!)
        (ui/assert-text! "#vibe-meter-slider-2-text" "Morph – · not wired"))
      (finally
        (store/clear-continuous-values!)))))


;; Safety (plans/2026-11-18-vibe-mode/07)

(deftest show-lock-switch-blocks-a-cell-move-test
  (let [robot (ui/robot)
        move {:event/type :grid/move-cell :from-col 0 :from-row 0 :to-col 5 :to-row 2}
        cue-at #(get-in (app-state) [:chains :cue-chains %])]
    ;; A busy machine can't stretch the pad's click into a hold
    (state/swap-state! assoc-in [:config :vibes :settings :quick-tap-ms] 2000)
    (open-perform! robot)
    (is (not (ui/selected? "#vibe-show-lock")) "unlocked in a new project")
    (ui/click! robot "#vibe-show-lock")
    (ui/wait-for! #(true? (get-in (app-state) [:ui :show-lock?])))
    (ui/assert-selected! "#vibe-show-lock")
    (testing "a drag's drop on the grid is refused, and the header says so"
      (events/dispatch! move)
      (ui/assert-text! "#vibe-lock-notice" "Move Cell refused")
      (is (some? (cue-at [0 0])) "the cell stays where it was")
      (is (nil? (cue-at [5 2])))
      (is (empty? (get-in (app-state) [:history :undo-stack])) "and no undo step"))
    (testing "the pads still play"
      (ui/mouse-click! robot "#vibe-pad-hit")
      (ui/wait-for! #(some? (rt :voices :hit))))
    (testing "unlocked: the notice goes, and the move works again"
      (ui/click! robot "#vibe-show-lock")
      (ui/wait-for! #(false? (get-in (app-state) [:ui :show-lock?])))
      (ui/wait-for! #(not (ui/visible? "#vibe-lock-notice")))
      (events/dispatch! move)
      (ui/wait-for! #(some? (cue-at [5 2]))))))

(deftest no-output-banner-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc-in [:config :vibes :settings :quick-tap-ms] 2000)
    (open-perform! robot)
    (is (not (ui/visible? "#vibe-no-output")) "nothing played yet: the status line alone says it")
    (ui/mouse-click! robot "#vibe-pad-hit")
    (ui/wait-for! #(some? (rt :voices :hit)))
    (ui/assert-text! "#vibe-no-output" "NO OUTPUT: press Stream")
    (is (some? (intent (rt :voices :hit :cell))) "invariant 6: the press plays, into nothing")
    (try
      (testing "Stream with no projector enabled: the banner and the toolbar agree"
        (ui/click! robot "#stream-btn")
        (ui/assert-text! "#streaming-status-label" "No projectors enabled")
        (ui/assert-text! "#vibe-no-output" "NO OUTPUT: no projectors enabled"))
      (ui/click! robot "#stream-btn")
      (ui/assert-text! "#streaming-status-label" "Not streaming")
      (ui/assert-text! "#vibe-no-output" "NO OUTPUT: press Stream")
      (finally
        (output-runtime/drain! 2000)))))

(deftest audience-badge-and-pad-mark-test
  (let [robot (ui/robot)
        crowd {:items [{:type :preset :preset-id :circle}] :target :crowd}]
    (state/swap-state! #(-> %
                            (assoc-in [:chains :cue-chains [0 1]] crowd)
                            (assoc-in [:config :vibes :library :vibe-1 :hit :cells] [[0 0] [0 1]])))
    (open-perform! robot)
    (is (not (ui/visible? "#vibe-header-audience")) "Shapes doesn't allow audience cells")
    (is (not (ui/visible? "#vibe-pad-hit-audience")) "its HIT pool's crowd cue never plays (V6)")
    (events/dispatch! {:event/type :vibe/set-option :vibe :vibe-1 :key :allow-audience? :value true})
    (ui/wait-for! #(ui/visible? "#vibe-header-audience"))
    (ui/assert-text! "#vibe-header-audience" "AUDIENCE ALLOWED")
    (ui/wait-for! #(ui/visible? "#vibe-pad-hit-audience"))
    (is (not (ui/visible? "#vibe-pad-accent-audience")) "ACCENT's pool reaches no crowd")
    (testing "another vibe: its own rule"
      (ui/click! robot "#vibe-chip-vibe-2")
      (ui/wait-for! #(not (ui/visible? "#vibe-header-audience")))
      (is (not (ui/visible? "#vibe-pad-hit-audience"))))))

(deftest stuck-pad-shows-its-problem-test
  (let [robot (ui/robot)]
    ;; Half a second is a stuck key here
    (state/swap-state! assoc-in [:config :vibes :settings :max-hold-secs] 0.5)
    (open-perform! robot)
    (ui/press-node! robot "#vibe-pad-hit")
    (ui/wait-for! #(= :pad (rt :held :hit :src)))
    (ui/wait-for! #(ui/has-style-class? "#vibe-pad-hit" "vibe-pad-held"))
    (Thread/sleep 600)
    (show-clock/tick! (System/nanoTime))
    (ui/wait-for! #(= {:role :hit :why :stuck-key} (rt :problem)))
    (is (nil? (rt :held :hit)) "the show clock's watchdog let go of it")
    (ui/assert-text! "#vibe-pad-hit-now" "Stuck key released")
    (ui/wait-for! #(not (ui/has-style-class? "#vibe-pad-hit" "vibe-pad-held")))
    (testing "the mouse's late release changes nothing"
      (ui/release-node! robot "#vibe-pad-hit")
      (flush-event-queue!)
      (is (= {:role :hit :why :stuck-key} (rt :problem))))
    (testing "the next good press clears it"
      (state/swap-state! assoc-in [:config :vibes :settings :quick-tap-ms] 2000)
      (ui/mouse-click! robot "#vibe-pad-hit")
      (ui/wait-for! #(nil? (rt :problem)))
      (ui/wait-for! #(not= "Stuck key released" (ui/node-text "#vibe-pad-hit-now"))))))


;; The ZONE MAP row (zones plan Z4, 06 §7.4, Z4-Q1 A)

(defn- scene-y [selector]
  (let [^Node n (ui/query selector)]
    (ui/on-fx-thread #(.getMinY (.localToScene n (.getBoundsInLocal n))))))

(deftest map-row-shows-when-zones-are-used-test
  (let [robot (ui/robot)]
    (open-perform! robot)
    (testing "no zone anywhere: no ZONE MAP row"
      (is (not (ui/visible? "#zone-map-row"))))
    (testing "a cue draws into a zone: the row, one pad per map"
      (state/swap-state! assoc-in [:chains :cue-chains [0 0] :items 0 :target] :zone/a)
      (ui/wait-for! #(ui/visible? "#zone-map-row"))
      (ui/assert-count! "#zone-map-row .zone-map-pad" 4) ; and CYCLE (zones plan Z5)
      (is (< (scene-y "#vibe-meter-row") (scene-y "#zone-map-row") (scene-y "#vibe-status"))
          "under the faders' meters, over the status line"))
    (testing "a column's default counts too"
      (state/swap-state! #(-> %
                              (update-in [:chains :cue-chains [0 0] :items 0] dissoc :target)
                              (assoc-in [:grid :column-targets] {3 :zone/b})))
      (ui/wait-for! #(ui/visible? "#zone-map-row")))
    (testing "no zone again: gone"
      (state/swap-state! assoc-in [:grid :column-targets] {})
      (ui/wait-for! #(not (ui/visible? "#zone-map-row"))))))


;; The ZONE PATTERN row (zones plan Z5, 07 §7.3)

(deftest pattern-row-shows-when-patterns-are-used-test
  (let [robot (ui/robot)]
    (open-perform! robot)
    (testing "no zone pattern anywhere: no ZONE PATTERN row"
      (is (not (ui/visible? "#pattern-row"))))
    (testing "a cue's pattern: the row, under ZONE MAP, over the status line"
      (state/swap-state! assoc-in [:chains :cue-chains [0 0] :zone-patterns] [(zone-patterns/preset-instance :alternate :cue)])
      (ui/wait-for! #(ui/visible? "#pattern-row"))
      (ui/assert-count! "#pattern-row .pattern-pad" 9)
      (is (ui/visible? "#zone-map-row") "a pattern moves pictures between zones: the map pads show too")
      (is (< (scene-y "#vibe-meter-row") (scene-y "#zone-map-row") (scene-y "#pattern-row") (scene-y "#vibe-status"))))
    (testing "a cell's, off, counts too"
      (state/swap-state! #(-> %
                              (update-in [:chains :cue-chains [0 0]] dissoc :zone-patterns)
                              (assoc-in [:chains :effect-chains [1 1]] {:active? false :items [(zone-patterns/preset-instance :chase :cell)]})))
      (ui/wait-for! #(ui/visible? "#pattern-row")))
    (testing "none again: gone"
      (state/swap-state! update-in [:chains :effect-chains] dissoc [1 1])
      (ui/wait-for! #(not (ui/visible? "#pattern-row"))))))
