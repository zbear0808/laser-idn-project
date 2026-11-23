(ns laser-show.ui-test.keyboard-trigger-test
  "Keyboard triggers through the mounted app: JavaFX focus and accelerators,
   the input router and mapper, and the actions the musician actually plays."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.router :as router]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [com.sun.javafx.stage WindowHelper]
           [com.sun.javafx.tk FocusCause]
           [javafx.event Event]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.stage WindowEvent]
           [org.testfx.api FxRobot]))

(defn- at [& path] (get-in (state/get-raw-state) path))
(defn- learning-target [] (at :backend :input :learning-target))

(def ^:dynamic ^:private *key-edges* nil)

(defn- with-input-mapper [test]
  ;; start-test! mounts the real UI without hardware or the input mapper.
  ;; Observe the router alongside the mapper: silence (and exactly one edge)
  ;; can't always be inferred from an action's final state.
  (let [edges (atom [])]
    (input-mapper/set-dispatch-fn! events/dispatch!)
    (input-mapper/start-mapper!)
    (router/on-any! ::key-edges :keyboard #(swap! edges conj [(:id %) (:state %)]))
    (try
      (binding [*key-edges* edges] (test))
      (finally
        (when (learning-target)
          ;; Release the waiting learn future, including when a test failed.
          (events/dispatch! {:event/type :input/cancel-learn})
          (events/flush!))
        (router/unregister-handler! ::key-edges)
        (input-mapper/stop-mapper!)
        (input-mapper/set-dispatch-fn! nil)))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app with-input-mapper)

(defn- cue [cell] (at :playback :active-cues cell))
(defn- playing? [] (true? (at :playback :playing?)))
(defn- binding-path [id] [:config :input :trigger-map {:source :keyboard :id id}])
(defn- binding-for [id] (get-in (state/get-raw-state) (binding-path id)))
(defn- bind! [id action] (events/write-state! assoc-in (binding-path id) action))
(defn- main-robot! [] (ui/focus! (ui/robot) "#main-layout"))

(defn- fire-key!
  "Fire one edge at the focused node. This can model OS repeat or an Alt
   flag appearing on key-up, neither of which needs a real OS keyboard."
  [selector event-type code & {:keys [alt?]}]
  (let [node (ui/query selector)]
    (ui/on-fx-thread #(Event/fireEvent node (KeyEvent. event-type "" "" code false false (boolean alt?) false))))
  (ui/sync-fx!))

(defn- while-held!
  "Run f between the robot's physical key down and key up, even on failure."
  [^FxRobot robot code f]
  (let [codes (into-array KeyCode [code])]
    (.press robot codes)
    (try
      (ui/sync-fx!)
      (f)
      (finally
        (.release robot codes)
        (ui/sync-fx!)))))

(deftest bound-key-triggers-its-cue-test
  (bind! :digit1 {:type :trigger-cue :target [0 0]})
  (ui/press! (main-robot!) KeyCode/DIGIT1)
  (ui/wait-for! #(some? (cue [0 0])))
  (is (= [[:digit1 :pressed] [:digit1 :released]] @*key-edges*)))

(deftest momentary-binding-releases-on-key-up-test
  (bind! :h {:type :trigger-cue :target [0 0] :behavior :momentary})
  (while-held! (main-robot!) KeyCode/H
               #(ui/wait-for! (fn [] (some? (cue [0 0])))))
  (ui/wait-for! #(nil? (cue [0 0])))
  (is (= [[:h :pressed] [:h :released]] @*key-edges*)))

(deftest bound-key-holds-and-releases-a-vibe-role-test
  (bind! :j {:type :vibe :role :hit})
  (while-held! (main-robot!) KeyCode/J
               (fn []
                 (ui/wait-for! #(= :input (at :vibe-runtime :held :hit :src)))
                 (is (true? (:hold? (cue (at :vibe-runtime :voices :hit :cell)))))))
  (ui/wait-for! #(nil? (at :vibe-runtime :held :hit)))
  (is (= [[:j :pressed] [:j :released]] @*key-edges*)))

(deftest unbound-key-passes-through-test
  (let [robot (main-robot!)]
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! playing?)
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! #(not (playing?)))
    (is (empty? @*key-edges*) "unbound Space reaches Play/Stop without entering the router")))

(deftest bound-space-overrides-play-stop-test
  ;; A cue would itself start the transport. A map pad lets us see both
  ;; effects independently: its map changes, but the transport stays stopped.
  (bind! :space {:type :zone-map :target :map/odd-even})
  (events/write-state! assoc-in [:chains :cue-chains [0 0] :items 0 :target] :zone/a)
  (ui/assert-text! "#zone-map-chip-left-right" "● Left / Right")
  (ui/press! (main-robot!) KeyCode/SPACE)
  (ui/wait-for! #(= :map/odd-even (at :zone-map :active)))
  (ui/assert-text! "#zone-map-chip-odd-even" "● Odd / Even")
  (is (events/flush!))
  (is (not (playing?)) "the same Space did not run Play"))

(deftest text-field-focus-blocks-triggers-test
  (bind! :digit1 {:type :trigger-cue :target [0 0]})
  (let [robot (ui/robot)]
    (ui/focus! robot "#bpm-input")
    (ui/fill! robot "#bpm-input" "12")
    (ui/press! robot KeyCode/DIGIT1)
    (ui/assert-text! "#bpm-input" "121")
    (is (events/flush!))
    (is (nil? (cue [0 0])))
    (is (empty? @*key-edges*))))

(deftest escape-always-stops-test
  ;; Even a project with a stale Escape binding can't take over the panic key.
  (bind! :escape {:type :trigger-cue :target [0 0]})
  (let [robot (ui/robot)]
    (doseq [selector ["#main-layout" "#bpm-input"]]
      (testing selector
        (events/dispatch! {:event/type :transport/play})
        (ui/wait-for! playing?)
        (ui/focus! robot selector)
        (ui/press! robot KeyCode/ESCAPE)
        (ui/wait-for! #(not (playing?)))
        (is (nil? (cue [0 0])))
        (is (empty? @*key-edges*))))))

(deftest repeated-press-emits-one-trigger-test
  (bind! :space {:type :trigger-cue :target [0 0] :behavior :momentary})
  (let [robot (main-robot!)]
    (fire-key! "#main-layout" KeyEvent/KEY_PRESSED KeyCode/SPACE)
    (ui/wait-for! #(some? (cue [0 0])))
    (let [first-run (cue [0 0])]
      (fire-key! "#main-layout" KeyEvent/KEY_PRESSED KeyCode/SPACE)
      (is (events/flush!))
      (is (= [[:space :pressed]] @*key-edges*) "the OS's second down was not routed")
      (is (= first-run (cue [0 0])) "the cue didn't restart")
      (is (playing?) "the repeat didn't leak through to Stop either"))
    (fire-key! "#main-layout" KeyEvent/KEY_RELEASED KeyCode/SPACE)
    (ui/wait-for! #(nil? (cue [0 0])))
    (ui/press! robot KeyCode/SPACE)
    (is (= [[:space :pressed] [:space :released] [:space :pressed] [:space :released]] @*key-edges*)
        "a new physical press works again")))

(deftest focus-loss-releases-held-keys-test
  (bind! :h {:type :trigger-cue :target [0 0] :behavior :momentary})
  (bind! :j {:type :vibe :role :hit})
  (events/write-state! assoc-in [:config :vibes :library :vibe-1 :hit :cells] [[1 0]])
  (main-robot!)
  (let [window (ui/main-window)
        ;; Deliver the native peer's focus notification on the FX thread.
        ;; Headless Glass has no desktop application to take the focus.
        focus! (fn [gained?]
                 (ui/on-fx-thread #(.changedFocused (WindowHelper/getPeerListener window) gained?
                                                    (if gained? FocusCause/ACTIVATED FocusCause/DEACTIVATED)))
                 (ui/sync-fx!))]
    (focus! true)
    (try
      (fire-key! "#main-layout" KeyEvent/KEY_PRESSED KeyCode/H)
      (fire-key! "#main-layout" KeyEvent/KEY_PRESSED KeyCode/J)
      (ui/wait-for! #(and (some? (cue [0 0])) (= :input (at :vibe-runtime :held :hit :src))))
      (focus! false)
      (is (false? (ui/on-fx-thread #(.isFocused window))))
      (ui/wait-for! #(and (nil? (cue [0 0])) (nil? (at :vibe-runtime :held :hit))))
      (finally (focus! true)))
    ;; The OS can still send a late key up after the window regains focus.
    (doseq [code [KeyCode/H KeyCode/J]] (fire-key! "#main-layout" KeyEvent/KEY_RELEASED code))
    (is (= {[:h :pressed] 1 [:j :pressed] 1 [:h :released] 1 [:j :released] 1}
           (frequencies @*key-edges*)))))

(deftest learn-captures-a-key-without-firing-the-old-binding-test
  (let [robot (ui/robot)
        target {:type :trigger-cue :target [1 0]}
        momentary (assoc target :behavior :momentary)]
    (bind! :j {:type :trigger-cue :target [0 0]})
    (events/dispatch! {:event/type :grid/activate-cell :col 1 :row 0})
    (ui/wait-for! #(some? (cue [1 0])))
    (let [already-playing (cue [1 0])]
      (events/dispatch! {:event/type :ui/open-dialog :dialog-id :trigger-editor
                         :data {:col 1 :row 0 :cell-mode :cue :behavior :momentary}})
      (ui/click! robot "#trigger-learn-button")
      (ui/wait-for! #(= target (learning-target)))
      (ui/focus! robot "#trigger-editor-dialog")
      (while-held! robot KeyCode/J
                   (fn []
                     (ui/wait-for! #(= momentary (binding-for :j)))
                     ;; Learn has completed but this is still the learning press.
                     (fire-key! "#trigger-editor-dialog" KeyEvent/KEY_PRESSED KeyCode/J)))
      (is (events/flush!))
      (is (= [[:j :pressed]] @*key-edges*) "neither its repeat nor its release belongs to the new binding")
      (is (nil? (cue [0 0])) "learning didn't fire the old binding")
      (is (= already-playing (cue [1 0])) "the new momentary binding didn't restart or release an independent run")
      (while-held! robot KeyCode/J
                   #(ui/wait-for! (fn [] (and (some? (cue [1 0])) (not= already-playing (cue [1 0]))))))
      (ui/wait-for! #(nil? (cue [1 0]))))))

(deftest escape-cancels-learn-test
  (let [robot (main-robot!)
        target {:type :trigger-cue :target [0 0]}]
    (events/dispatch! {:event/type :transport/play})
    (events/dispatch! {:event/type :input/start-learn :target-action target})
    (ui/wait-for! #(and (playing?) (= target (learning-target))))
    (ui/press! robot KeyCode/ESCAPE)
    (ui/wait-for! #(and (nil? (learning-target)) (not (playing?))))
    (is (nil? (binding-for :escape)))
    (is (empty? @*key-edges*) "Escape cancels the learn and reaches Stop, without becoming a trigger")))

(deftest learn-captures-unbound-space-instead-of-play-test
  (let [robot (main-robot!)
        target {:type :vibe :role :hit}]
    (events/dispatch! {:event/type :input/start-learn :target-action target})
    (ui/wait-for! #(= target (learning-target)))
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! #(= target (binding-for :space)))
    (is (events/flush!))
    (is (not (playing?)) "learning Space didn't run Play")
    (is (= [[:space :pressed]] @*key-edges*))))

(deftest fader-learn-leaves-keys-alone-test
  (let [robot (main-robot!)
        fader {:type :macro :slot :slider-1}]
    (bind! :space {:type :trigger-cue :target [0 0]})
    (events/dispatch! {:event/type :input/start-learn :target-action fader :capture :continuous})
    (ui/wait-for! #(= fader (learning-target)))
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! playing?)
    (is (events/flush!))
    (is (nil? (cue [0 0])) "this was the Play shortcut")
    (is (= fader (learning-target)) "a fader movement is still needed")
    (is (empty? @*key-edges*))))

(deftest bound-key-works-in-the-timeline-window-test
  ;; A definition is a binding even without an entry in the trigger map.
  (events/write-state! assoc-in [:triggers :definitions :hold-square]
                       {:match {:source :keyboard :id :h} :behavior :momentary
                        :action {:type :trigger-cue :target [1 0]}})
  (events/dispatch! {:event/type :timeline-cue-editor/open :col 0 :row 0})
  (let [robot (ui/focus! (ui/robot) "#timeline-canvas")]
    (while-held! robot KeyCode/H
                 (fn []
                   (ui/wait-for! #(some? (cue [1 0])))
                   (testing "closing the editor releases the hold before any physical key up"
                     (let [canvas (ui/query "#timeline-canvas")]
                       (ui/on-fx-thread #(let [stage (.getWindow (.getScene canvas))]
                                           (Event/fireEvent stage (WindowEvent. stage WindowEvent/WINDOW_CLOSE_REQUEST)))))
                     (ui/wait-for! #(not (ui/visible? "#timeline-canvas")))
                     (ui/wait-for! #(nil? (cue [1 0])))
                     (ui/focus! robot "#main-layout"))))
    (is (= [[:h :pressed] [:h :released]] @*key-edges*) "the late up in the main window didn't release it twice")))

(deftest timeline-window-keeps-its-own-space-test
  (bind! :space {:type :trigger-cue :target [1 0]})
  (events/dispatch! {:event/type :timeline-cue-editor/open :col 0 :row 0})
  ;; A label has no Space handling of its own: this is the timeline window's
  ;; fallback Play shortcut. The editor plays A1, not the bound A2.
  (let [robot (ui/focus! (ui/robot) "#timeline-readout")]
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! #(some? (cue [0 0])))
    (is (nil? (cue [1 0])))
    (is (empty? @*key-edges*))))

(deftest focused-step-grid-keeps-its-space-test
  (bind! :space {:type :trigger-cue :target [1 0]})
  (events/dispatch! {:event/type :step-grid/open
                     :target {:domain :cue-zone-patterns :entity-key [0 0] :effect-path [0]}})
  (let [robot (ui/focus! (ui/robot) "#step-grid-body")
        first-step #(at :chains :cue-chains [0 0] :zone-patterns 0 :params :steps 0)]
    (ui/wait-for! #(= [:zone/a] (first-step)))
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! #(= [] (first-step)))
    (is (nil? (cue [1 0])))
    (is (empty? @*key-edges*) "Space toggled the grid cell without reaching the router")
    (testing "elsewhere in the very same window, the binding works"
      (ui/focus! robot "#step-grid-now")
      (ui/press! robot KeyCode/SPACE)
      (ui/wait-for! #(some? (cue [1 0])))
      (is (= [] (first-step))))))

(deftest modal-windows-do-not-play-cues-test
  (bind! :j {:type :trigger-cue :target [0 0]})
  (let [robot (ui/robot)]
    (doseq [[id data selector] [[:confirm {:title "Rename A1?"} "#confirm-title"]
                                [:unsaved-confirm {:pending {:event/type :file/new-project}} "#unsaved-message"]
                                [:rig-report {:mode :report :file "friday.zip" :hardware {} :chains {} :pins {}} "#rig-report-title"]
                                [:about {} "#about-title"]]]
      (testing (name id)
        (events/dispatch! {:event/type :ui/open-dialog :dialog-id id :data data})
        (ui/focus! robot selector)
        (ui/press! robot KeyCode/J)
        (ui/press! robot KeyCode/ESCAPE)
        (ui/wait-for! #(not (ui/visible? selector)))
        (is (events/flush!))
        (is (nil? (cue [0 0])))
        (is (empty? @*key-edges*) "the modal's Escape worked and its J reached no trigger")))))

(deftest command-modifier-is-reserved-but-shift-plays-the-bound-key-test
  (let [robot (main-robot!)]
    (bind! :p {:type :pattern-pad :target :chase})
    (events/write-state! assoc-in [:chains :cue-chains [0 0] :zone-patterns]
                         [(zone-patterns/preset-instance :alternate :cue)])
    (ui/assert-visible! "#preview-panel")
    (.push ^FxRobot robot (into-array KeyCode [KeyCode/SHORTCUT KeyCode/P]))
    (ui/wait-for! #(not (ui/visible? "#preview-panel")))
    (is (nil? (at :pattern-pads :style)) "Shortcut+P toggled the preview instead of the pattern")
    (is (empty? @*key-edges*))
    (.push ^FxRobot robot (into-array KeyCode [KeyCode/SHIFT KeyCode/P]))
    (ui/wait-for! #(= :chase (at :pattern-pads :style)))
    (ui/assert-text! "#pattern-chip-chase" "● Chase")
    (is (= [[:p :pressed] [:p :released]] @*key-edges*) "Shift+P still plays the P binding")))

(deftest disabling-keyboard-triggers-restores-the-command-test
  (let [robot (main-robot!)
        enabled? #(at :backend :input :keyboard :enabled?)]
    (bind! :space {:type :trigger-cue :target [0 0]})
    (events/dispatch! {:event/type :input/keyboard-disable})
    (ui/wait-for! #(false? (enabled?)))
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! playing?)
    (is (nil? (cue [0 0])) "the binding was bypassed, but Play worked")
    (is (empty? @*key-edges*))
    (events/dispatch! {:event/type :input/keyboard-enable})
    (ui/wait-for! enabled?)
    (ui/press! robot KeyCode/SPACE)
    (ui/wait-for! #(some? (cue [0 0])))
    (is (= [[:space :pressed] [:space :released]] @*key-edges*) "the saved binding works again")))

(deftest existing-holds-release-after-context-changes-test
  (let [robot (main-robot!)
        active? #(some? (cue [0 0]))]
    (bind! :h {:type :trigger-cue :target [0 0] :behavior :momentary})
    (testing "the user moved to a text field before lifting the key"
      (while-held! robot KeyCode/H (fn [] (ui/wait-for! active?) (ui/focus! robot "#bpm-input")))
      (ui/wait-for! #(not (active?))))
    (testing "the user is holding Alt when the key comes up"
      (ui/focus! robot "#main-layout")
      (fire-key! "#main-layout" KeyEvent/KEY_PRESSED KeyCode/H)
      (ui/wait-for! active?)
      (fire-key! "#main-layout" KeyEvent/KEY_RELEASED KeyCode/H :alt? true)
      (ui/wait-for! #(not (active?))))
    (testing "the keyboard was disabled during the hold"
      (while-held! robot KeyCode/H
                   (fn []
                     (ui/wait-for! active?)
                     (events/dispatch! {:event/type :input/keyboard-disable})
                     (ui/wait-for! #(false? (at :backend :input :keyboard :enabled?)))))
      (ui/wait-for! #(not (active?))))
    (is (= (take 6 (cycle [[:h :pressed] [:h :released]])) @*key-edges*))))
