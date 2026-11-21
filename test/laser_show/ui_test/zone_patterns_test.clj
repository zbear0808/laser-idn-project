(ns laser-show.ui-test.zone-patterns-test
  "Zone patterns (zones plan Z5, 07 §7): the Pattern chip in both cue
   editors, the pattern window and how it says the pattern folds onto this
   rig, Zone pattern and Map cycle on the effects grid's Zone tab and an
   empty effects cell's menu; the pattern pads, STEP, and CYCLE on the map
   pads."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.router :as input-router]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.core :as state]
            [laser-show.state.history :as history]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.css PseudoClass]
           [javafx.scene Node]
           [javafx.scene.control Control Labeled Menu MenuButton MenuItem]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- app-state [] (state/get-raw-state))

(defn- cue [col row] (get-in (app-state) [:chains :cue-chains [col row]]))

(defn- cue-pattern [] (first (:zone-patterns (cue 0 0))))

(defn- open-editor! [col row]
  (events/dispatch! {:event/type :cue-chain/open-editor :col col :row row})
  (ui/wait-for! #(some? (ui/query "#target-chip-cue"))))

(defn- chip-items [selector]
  (let [^MenuButton chip (ui/query selector)]
    (ui/on-fx-thread #(vec (.getItems chip)))))

(defn- item-text [^MenuItem i]
  (ui/on-fx-thread #(str/trim (str/replace (str (.getText i)) #"^✓ " ""))))

(defn- item-texts [selector]
  (mapv item-text (chip-items selector)))

(defn- fire-item!
  "Fire the item of the menu button at selector whose text, without its
   tick, is text, as a click on it does."
  [selector text]
  (let [item (some #(when (= text (item-text %)) %) (chip-items selector))]
    (is (some? item) (str "an item " (pr-str text) " in " (item-texts selector)))
    (when item
      (ui/on-fx-thread #(.fire ^MenuItem item))
      (ui/sync-fx!))))

(defn- texts [selector]
  (ui/on-fx-thread #(into [] (keep (fn [n] (when (instance? Labeled n) (.getText ^Labeled n)))) (ui/query-all selector))))

(defn- tooltip-of [selector]
  (ui/on-fx-thread #(some-> ^Control (ui/query selector) .getTooltip .getText)))

(defn- lasers! [n]
  (state/swap-state! assoc :projectors
                     (into {} (for [k (range 1 (inc n))]
                                [(keyword (str "p" k)) {:name (str "Laser " k) :host (str "10.0.0." k) :enabled? true
                                                        :zone-groups [] :exclude-from-all? false :scan-rate 25000}]))))

(def ^:private window-title "Zone Pattern — Cue A1 \"Beams\"")

(defn- window-open? [] (boolean (some #{window-title} (ui/window-titles))))


;; A cue's pattern (07 §7.1)

(deftest footer-chip-adds-a-pattern-test
  (open-editor! 0 0)
  (ui/wait-for! #(some? (ui/query "#pattern-chip-cue")))
  (ui/assert-text! "#pattern-chip-cue" "None")
  (is (some #{"Pattern:"} (texts ".dialog-footer .target-field-label")))
  (is (= "No zone pattern: the cue's pictures stay in the zones they're drawn into. Pick a style to move them on the beat or on a press."
         (tooltip-of "#pattern-chip-cue")))
  (is (= ["None" "Alternate   A ⇄ B" "Chase   across every laser" "Ping-pong   across every laser"
          "Random   across every laser" "Shift   A → B → C → D" "Swap   A ⇄ B (what's in them)" "" "Edit pattern…"]
         (item-texts "#pattern-chip-cue")))
  (testing "a style adds its preset, on the cue's clock"
    (fire-item! "#pattern-chip-cue" "Alternate   A ⇄ B")
    (ui/wait-for! #(= :alternate (get-in (cue-pattern) [:params :style])))
    (is (= [:zone/a :zone/b] (get-in (cue-pattern) [:params :zones])))
    (is (= :cue (get-in (cue-pattern) [:params :clock])) "from the cue's start (Z5-Q6 A)")
    (ui/assert-text! "#pattern-chip-cue" "Alternate · A ⇄ B")
    (is (= "Zone pattern: Alternate. Moves the All picture A ⇄ B, 1 step a beat, from the cue's start."
           (tooltip-of "#pattern-chip-cue")))
    (is (= "Change Zone Pattern" (history/undo-label (:history (app-state))))))
  (testing "another style keeps the zones"
    (fire-item! "#pattern-chip-cue" "Chase   across every laser")
    (ui/wait-for! #(= :chase (get-in (cue-pattern) [:params :style])))
    (is (= [:zone/a :zone/b] (get-in (cue-pattern) [:params :zones])))
    (ui/assert-text! "#pattern-chip-cue" "Chase · A → B"))
  (testing "None takes it away"
    (fire-item! "#pattern-chip-cue" "None")
    (ui/wait-for! #(nil? (cue-pattern)))
    (ui/assert-text! "#pattern-chip-cue" "None"))
  (testing "the timeline editor's footer has it too"
    (events/dispatch! {:event/type :ui/close-dialog :dialog-id :cue-chain-editor})
    (ui/wait-for! #(nil? (ui/query "#pattern-chip-cue")))
    (events/dispatch! {:event/type :timeline-cue-editor/open :col 0 :row 0})
    (ui/wait-for! #(some? (ui/query "#pattern-chip-cue")))
    (fire-item! "#pattern-chip-cue" "Swap   A ⇄ B (what's in them)")
    (ui/wait-for! #(= :swap (get-in (cue-pattern) [:params :style])))
    (ui/assert-text! "#pattern-chip-cue" "Swap · A ⇄ B")))

(deftest pattern-window-shows-the-fold-test
  (lasers! 3)
  (state/swap-state! assoc-in [:chains :cue-chains [0 0] :name] "Beams")
  (open-editor! 0 0)
  (ui/wait-for! #(some? (ui/query "#pattern-chip-cue")))
  (let [^MenuItem edit (some #(when (= "Edit pattern…" (item-text %)) %) (chip-items "#pattern-chip-cue"))]
    (is (and edit (ui/on-fx-thread #(.isDisable edit))) "no pattern: nothing to edit"))
  (fire-item! "#pattern-chip-cue" "Alternate   A ⇄ B")
  (ui/wait-for! #(some? (cue-pattern)))
  (fire-item! "#pattern-chip-cue" "Edit pattern…")
  (ui/wait-for! window-open?)
  (ui/wait-for! #(some? (ui/query "#zone-pattern-fold")))
  (ui/assert-text! "#zone-pattern-fold"
                   "This rig (3 lasers, Left / Right): A is Laser 1 and the left half of Laser 2; B the right half of Laser 2 and Laser 3.")
  (ui/assert-text! "#zone-pattern-now" "– (not playing)")
  (testing "the rows Alternate uses"
    (is (some? (ui/query "#zone-pattern-row-source")) "Moves")
    (is (some? (ui/query "#zone-pattern-row-restart-on")) "Restart on, while Step on is the beat")
    (is (nil? (ui/query "#zone-pattern-row-path")) "no Path")
    (is (nil? (ui/query "#zone-pattern-row-width")) "no Width"))
  (testing "the map switches, and so does the line"
    (state/swap-state! assoc-in [:zone-map :active] :map/odd-even)
    (ui/wait-for! #(= "This rig (3 lasers, Odd / Even): A is Laser 1 and Laser 3; B Laser 2." (ui/node-text "#zone-pattern-fold")))
    (state/swap-state! assoc-in [:zone-map :active] :map/unison)
    (ui/wait-for! #(= "Under Unison every zone is All, so this pattern doesn't move." (ui/node-text "#zone-pattern-fold")))
    (state/swap-state! assoc-in [:zone-map :active] :map/left-right))
  (testing "a place added from the lane's + menu"
    (fire-item! "#zone-pattern-lane-add" "New zone (C → Left)")
    (ui/wait-for! #(= [:zone/a :zone/b :zone/c] (get-in (cue-pattern) [:params :zones])))
    (ui/wait-for! #(= ["A → Left" "B → Right" "C → Left"] (texts "#zone-pattern-lane .zone-pattern-place-name"))))
  (testing "and taken out with its ×"
    (ui/click! (ui/robot) "#zone-pattern-place-remove-2")
    (ui/wait-for! #(= [:zone/a :zone/b] (get-in (cue-pattern) [:params :zones]))))
  (testing "Chase shows Path and Width"
    (state/swap-state! assoc-in [:chains :cue-chains [0 0] :zone-patterns 0 :params :style] :chase)
    (ui/wait-for! #(some? (ui/query "#zone-pattern-row-path")))
    (is (some? (ui/query "#zone-pattern-row-width"))))
  (testing "Remove takes the pattern away and closes the window"
    (ui/click! (ui/robot) "#zone-pattern-remove")
    (ui/wait-for! #(nil? (cue-pattern)))
    (ui/wait-for! #(not (window-open?)))
    (is (= "Change Zone Pattern" (history/undo-label (:history (app-state)))))))

(deftest offset-takes-a-modulator-test
  (let [robot (ui/robot)
        param #(get-in (cue-pattern) [:params %])]
    (state/swap-state! #(-> %
                            (assoc-in [:chains :cue-chains [0 0] :name] "Beams")
                            (assoc-in [:chains :cue-chains [0 0] :zone-patterns]
                                      [(assoc (zone-patterns/preset-instance :chase :cue) :id #uuid "00000000-0000-0000-0000-0000000000c1")])))
    (events/dispatch! {:event/type :ui/open-dialog :dialog-id :zone-pattern-editor :data {:col 0 :row 0}})
    (ui/wait-for! window-open?)
    (ui/wait-for! #(some? (ui/query "#zone-pattern-row-offset .modulator-toggle")))
    (ui/click! robot "#zone-pattern-row-offset .modulator-toggle")
    (ui/wait-for! #(map? (param :offset)))
    (is (= :sine (:type (param :offset))))
    (testing "the window stays visual and shows the modulator's settings"
      (ui/wait-for! #(some? (ui/query "#modulator-type-offset")))
      (is (some? (ui/query "#zone-pattern-visual"))))
    (testing "Style takes one too: a modulator picks the style by index"
      (ui/click! robot "#zone-pattern-row-style .modulator-toggle")
      (ui/wait-for! #(map? (param :style)))
      (is (= [0.0 5.0] (mapv double ((juxt :min :max) (param :style)))) "Alternate 0 … Swap 5")
      (ui/wait-for! #(some #{"0 Alternate · 1 Chase · 2 Ping-pong · 3 Random · 4 Shift · 5 Swap"}
                           (texts "#zone-pattern-row-style .label"))))))


;; The effects grid (07 §7.2)

(defn- cell-holder
  "The label around the effects cell at [col row]: it holds its menu and
   tooltip."
  ^Labeled [col row]
  (ui/on-fx-thread #(.getParent ^Node (ui/query (format "#effects-cell-%d-%d" col row)))))

(defn- effects-cell [col row] (get-in (app-state) [:chains :effect-chains [col row]]))

(deftest effects-zone-tab-lists-pattern-and-cycle-test
  (let [robot (ui/robot)]
    (testing "the effects grid's editor: Zone pattern and Map cycle, and the order they run in"
      (events/dispatch! {:event/type :effect-chain/open-editor :col 1 :row 0})
      (ui/wait-for! #(seq (ui/query-all "#zone-tab")))
      (ui/click! robot "#zone-tab")
      (ui/wait-for! #(some #{"Zone pattern"} (texts ".bank-item-btn")))
      (is (= ["Map cycle" "Zone pattern"] (sort (texts ".bank-item-btn"))))
      (ui/assert-text! "#effect-bank-hint" "Zone patterns run after each cue's own pattern, cell by cell, row by row, left to right.")
      (ui/click-node! robot (some #(when (= "Zone pattern" (.getText ^Labeled %)) %) (ui/query-all ".bank-item-btn")))
      (ui/wait-for! #(= :zone-pattern (get-in (effects-cell 1 0) [:items 0 :effect-id])))
      (is (= :global (get-in (effects-cell 1 0) [:items 0 :params :clock])) "a cell's counts the show's beats")
      (ui/wait-for! #(some? (ui/query "#zone-pattern-visual")))
      (events/dispatch! {:event/type :ui/close-dialog :dialog-id :effect-chain-editor})
      (ui/wait-for! #(nil? (ui/query "#zone-pattern-visual"))))
    (testing "an empty cell's menu: New zone pattern ▸ Chase makes the cell a pattern, off, and opens it"
      (ui/click! robot "#effects-tab")
      (ui/wait-for! #(some? (ui/query "#effects-cell-3-1")))
      (let [items (ui/on-fx-thread #(vec (.getItems (.getContextMenu (cell-holder 3 1)))))
            ^Menu menu (some #(when (= "new-zone-pattern-menu" (.getId ^MenuItem %)) %) items)]
        (is (some? menu))
        (is (= ["Alternate" "Chase" "Ping-pong" "Random" "Shift" "Swap" "" "Map cycle"]
               (ui/on-fx-thread #(mapv (fn [^MenuItem i] (str (.getText i))) (.getItems menu)))))
        (ui/on-fx-thread #(.fire ^MenuItem (some (fn [^MenuItem i] (when (= "new-zone-pattern-chase" (.getId i)) i)) (.getItems menu)))))
      (ui/wait-for! #(= :chase (get-in (effects-cell 3 1) [:items 0 :params :style])))
      (is (false? (:active? (effects-cell 3 1))))
      (ui/wait-for! #(some? (ui/query "#zone-pattern-visual")))
      (events/dispatch! {:event/type :ui/close-dialog :dialog-id :effect-chain-editor}))
    (testing "its tooltip names the style and the lane"
      (ui/wait-for! #(= "Zone pattern: Chase across every laser, 1 step a beat (show beats)"
                        (ui/on-fx-thread (fn [] (some-> (cell-holder 3 1) .getTooltip .getText))))))))

(deftest item-bank-has-no-zone-tab-test
  (testing "the cue editor's item bank: zone patterns are the effects grid's"
    (open-editor! 0 0)
    (ui/wait-for! #(seq (ui/query-all "#shape-tab")))
    (is (empty? (ui/query-all "#zone-tab")))
    (is (not-any? #{"Zone pattern" "Map cycle"} (texts ".bank-item-btn"))))
  (testing "nor the timeline's browser"
    (events/dispatch! {:event/type :ui/close-dialog :dialog-id :cue-chain-editor})
    (events/dispatch! {:event/type :timeline-cue-editor/open :col 1 :row 0})
    (ui/wait-for! #(some? (ui/query "#timeline-track-target-0")))
    (state/swap-state! assoc-in [:ui :timeline-prefs :add-panel :open?] true)
    (ui/wait-for! #(seq (ui/query-all "#timeline-browser-effects #shape-tab")))
    (is (empty? (ui/query-all "#zone-tab")))))


;; The pattern pads (07 §7.3)

(def ^:private pattern-id #uuid "00000000-0000-0000-0000-0000000000c1")

(defn- uses-a-pattern!
  "The cue at A1 alternates A ⇄ B; extra merges into its params."
  ([] (uses-a-pattern! {}))
  ([extra]
   (state/swap-state! assoc-in [:chains :cue-chains [0 0] :zone-patterns]
                      [(-> (zone-patterns/preset-instance :alternate :cue)
                           (assoc :id pattern-id)
                           (update :params merge extra))])))

(defn- pads-style [] (get-in (app-state) [:pattern-pads :style]))

(defn- has-class? [selector cls]
  (ui/on-fx-thread #(boolean (some-> ^Node (ui/query selector) .getStyleClass (.contains cls)))))

(defn- pseudo? [selector pseudo-class]
  (ui/on-fx-thread #(boolean (some-> ^Node (ui/query selector) .getPseudoClassStates
                                     (.contains (PseudoClass/getPseudoClass pseudo-class))))))

(defn- open-perform! []
  (events/dispatch! {:event/type :ui/set-active-tab :tab-id :perform})
  (ui/wait-for! #(ui/visible? "#vibe-pad-row")))

(defn- with-input-mapper
  "(f) with the input mapper running (zones-test's): a learned note reaches
   the app only through it."
  [f]
  (input-mapper/set-dispatch-fn! events/dispatch!)
  (input-mapper/start-mapper!)
  (try
    (f)
    (finally
      (input-mapper/stop-mapper!)
      (input-mapper/set-dispatch-fn! nil))))

(defn- note [type n]
  {:type type :source :midi :channel 0 :note n :velocity (if (= :note-on type) 1.0 0.0)
   :timestamp (System/currentTimeMillis)})

(defn- press! [robot selector]
  (ui/press-node! robot selector)
  (ui/release-node! robot selector))

(deftest pattern-pad-switches-test
  (let [robot (ui/robot)]
    (testing "no pattern anywhere: no strip, no map pads"
      (is (not (ui/visible? "#pattern-strip")))
      (is (not (ui/visible? "#zone-map-strip"))))
    (uses-a-pattern!)
    (ui/wait-for! #(ui/visible? "#pattern-chip-chase"))
    (is (= ["● As set" "Off" "Alternate" "Chase" "Ping-pong" "Random" "Shift" "Swap" "STEP"]
           (texts "#pattern-strip .pattern-pad")))
    (is (= "Every pattern plays the style it was made with" (tooltip-of "#pattern-chip-as-set")))
    (is (= "One zone after another" (tooltip-of "#pattern-chip-chase")))
    (is (ui/visible? "#zone-map-chip-odd-even") "a pattern moves pictures between zones: the map pads show too")
    (let [undo-depth (count (get-in (app-state) [:history :undo-stack]))]
      (press! robot "#pattern-chip-chase")
      (testing "no clock thread: at once"
        (ui/wait-for! #(= :chase (pads-style)))
        (is (nil? (get-in (app-state) [:pattern-pads :pending]))))
      (ui/assert-text! "#pattern-chip-chase" "● Chase")
      (ui/assert-text! "#pattern-chip-as-set" "As set")
      (is (has-class? "#pattern-chip-chase" "pattern-pad-active"))
      (is (= undo-depth (count (get-in (app-state) [:history :undo-stack]))) "a switch is live: no undo step"))
    (is (= :alternate (get-in (cue 0 0) [:zone-patterns 0 :params :style])) "the cue keeps its own style")
    (testing "on Perform: ZONE PATTERN, under ZONE MAP"
      (open-perform!)
      (ui/wait-for! #(ui/visible? "#pattern-pad-chase"))
      (ui/assert-text! "#pattern-pad-chase-state" "now")
      (is (not-any? #(.isFocusTraversable ^Node %) (ui/query-all ".pattern-pad")) "Space or Enter never presses one")
      (press! robot "#pattern-pad-off")
      (ui/wait-for! #(= :off (pads-style)))
      (ui/assert-text! "#pattern-pad-off-state" "now")
      (press! robot "#pattern-pad-as-set")
      (ui/wait-for! #(nil? (pads-style)))
      (ui/assert-text! "#pattern-pad-as-set-state" "now"))))

(deftest pending-pattern-pad-blinks-test
  (uses-a-pattern!)
  (state/swap-state! assoc-in [:pattern-pads :pending] {:id 7 :style :chase :at-beat 1.0e6 :bar? false})
  (ui/wait-for! #(ui/visible? "#pattern-chip-chase"))
  (testing "waiting: amber, and it says so"
    (is (has-class? "#pattern-chip-chase" "pattern-pad-pending"))
    (ui/assert-text! "#pattern-chip-chase" "Chase ◌"))
  (testing "no clock: it stays lit"
    (is (not (pseudo? "#pattern-chip-chase" "blink"))))
  (testing "while the clock runs it blinks, and only it"
    (fixtures/with-clock
     (fn [_]
       (ui/wait-for! #(pseudo? "#pattern-chip-chase" "blink"))
       (ui/wait-for! #(not (pseudo? "#pattern-chip-chase" "blink")))
       (ui/wait-for! #(pseudo? "#pattern-chip-chase" "blink"))
       (is (not (pseudo? "#pattern-chip-as-set" "blink"))))))
  (testing "on Perform: next beat, or next bar"
    (open-perform!)
    (ui/wait-for! #(ui/visible? "#pattern-pad-chase"))
    (ui/assert-text! "#pattern-pad-chase-state" "next beat")
    (state/swap-state! assoc-in [:pattern-pads :pending :bar?] true)
    (ui/assert-text! "#pattern-pad-chase-state" "next bar")))

(deftest pattern-pad-learns-a-note-test
  (with-input-mapper
    (fn []
      (let [robot (ui/robot)
            k40 {:source :midi :channel 0 :note 40}
            bound #(get-in (app-state) [:config :input :trigger-map k40])]
        (uses-a-pattern!)
        (open-perform!)
        (ui/wait-for! #(ui/visible? "#pattern-pad-chase"))
        (ui/assert-text! "#pattern-pad-chase-keys" "Right-click to learn")
        (ui/right-click-node! robot (ui/query "#pattern-pad-chase"))
        (ui/click! robot "#pattern-pad-chase-learn")
        (ui/wait-for! #(= {:type :pattern-pad :target :chase} (get-in (app-state) [:backend :input :learning-target])))
        (is (has-class? "#pattern-pad-chase" "pattern-pad-learning"))
        (is (nil? (pads-style)) "the menu pressed nothing")
        (input-router/dispatch! (note :note-on 40))
        (ui/wait-for! #(= {:type :pattern-pad :target :chase} (bound)))
        (ui/assert-text! "#pattern-pad-chase-keys" "MIDI Ch 1 Note 40")
        (ui/wait-for! #(not (has-class? "#pattern-pad-chase" "pattern-pad-learning")))
        (input-router/dispatch! (note :note-off 40))
        (testing "the key presses the pad"
          (input-router/dispatch! (note :note-on 40))
          (ui/wait-for! #(= :chase (pads-style)))
          (input-router/dispatch! (note :note-off 40)))
        (testing "learning it again for another pad moves it"
          (ui/right-click-node! robot (ui/query "#pattern-pad-swap"))
          (ui/click! robot "#pattern-pad-swap-learn")
          (ui/wait-for! #(= {:type :pattern-pad :target :swap} (get-in (app-state) [:backend :input :learning-target])))
          (input-router/dispatch! (note :note-on 40))
          (ui/wait-for! #(= {:type :pattern-pad :target :swap} (bound)))
          (input-router/dispatch! (note :note-off 40))
          (ui/assert-text! "#pattern-pad-chase-keys" "Right-click to learn")
          (ui/assert-text! "#pattern-pad-swap-keys" "MIDI Ch 1 Note 40"))
        (testing "Clear control"
          (ui/right-click-node! robot (ui/query "#pattern-pad-swap"))
          (ui/click! robot "#pattern-pad-swap-clear")
          (ui/wait-for! #(nil? (bound)))
          (ui/assert-text! "#pattern-pad-swap-keys" "Right-click to learn"))))))

(deftest step-pad-steps-a-pattern-test
  (let [robot (ui/robot)
        step #(get-in (show-time/latest) [:zone-steps pattern-id])]
    (uses-a-pattern! {:step-on :zone-step})
    (open-perform!)
    (ui/wait-for! #(ui/visible? "#pattern-pad-step"))
    (is (= "Steps the patterns that step on the Zone step key" (tooltip-of "#pattern-pad-step")))
    (ui/assert-text! "#pattern-pad-step-count" "0")
    (events/dispatch! {:event/type :grid/trigger-cell :col 0 :row 0})
    (ui/wait-for! #(some? (get-in (app-state) [:playback :active-cues [0 0]])))
    (fixtures/with-clock
     (fn [_]
       (testing "the cue plays its pattern at step 0 before a press"
         (ui/wait-for! #(= 0 (step))))
       (press! robot "#pattern-pad-step")
       (ui/wait-for! #(= 1 (get-in (app-state) [:playback :pulses :zone-step :id])))
       (testing "the clock stamps the press: the count, and the pattern's next step"
         (ui/wait-for! #(= "1" (ui/node-text "#pattern-pad-step-count")))
         (ui/wait-for! #(= 1 (step))))
       (press! robot "#pattern-pad-step")
       (ui/wait-for! #(= 2 (step)))
       (ui/wait-for! #(= "2" (ui/node-text "#pattern-pad-step-count")))))))


;; The Map cycle on the map pads (07 §7.4)

(def ^:private cycle-cell [1 2])

(defn- cycle-on! []
  (state/swap-state! assoc-in [:chains :effect-chains cycle-cell]
                     {:active? true :items [(assoc (zone-patterns/cycle-instance) :id #uuid "00000000-0000-0000-0000-0000000000d1")]}))

(defn- paused? [] (true? (get-in (app-state) [:zone-map :cycle-paused?])))

(deftest cycle-pad-resumes-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc-in [:chains :cue-chains [0 0] :items 0 :target] :zone/a)
    (ui/wait-for! #(ui/visible? "#zone-map-chip-cycle"))
    (testing "no Map cycle cell on: CYCLE is dim and says why"
      (ui/assert-text! "#zone-map-chip-cycle" "↻ CYCLE")
      (is (has-class? "#zone-map-chip-cycle" "zone-map-pad-dim"))
      (is (= "Switch on a Map cycle cell on the Effects grid" (tooltip-of "#zone-map-chip-cycle"))))
    (cycle-on!)
    (testing "a cycle cell on: CYCLE is lit, and no map pad is the one in use"
      (ui/wait-for! #(has-class? "#zone-map-chip-cycle" "zone-map-pad-active"))
      (is (not (has-class? "#zone-map-chip-left-right" "zone-map-pad-active")))
      (is (= "Map cycle: Left / Right, Odd / Even, a bar each" (tooltip-of "#zone-map-chip-cycle"))))
    (testing "a map pad's press pauses it (no clock: at once)"
      (press! robot "#zone-map-chip-unison")
      (ui/wait-for! #(= :map/unison (get-in (app-state) [:zone-map :active])))
      (ui/wait-for! paused?)
      (ui/wait-for! #(= "↻ CYCLE · paused" (ui/node-text "#zone-map-chip-cycle")))
      (is (not (has-class? "#zone-map-chip-cycle" "zone-map-pad-active")))
      (is (has-class? "#zone-map-chip-unison" "zone-map-pad-active")))
    (testing "CYCLE carries on"
      (press! robot "#zone-map-chip-cycle")
      (ui/wait-for! #(not (paused?)))
      (ui/wait-for! #(has-class? "#zone-map-chip-cycle" "zone-map-pad-active"))
      (ui/assert-text! "#zone-map-chip-cycle" "↻ CYCLE"))
    (testing "the Target menu says the map is cycling"
      (open-editor! 0 0)
      (ui/wait-for! #(some? (ui/query "#target-chip-cue")))
      (is (some #{"ZONES · map: cycling (Left / Right, Odd / Even)"} (item-texts "#target-chip-cue")))
      (events/dispatch! {:event/type :ui/close-dialog :dialog-id :cue-chain-editor}))
    (testing "on Perform, and the map the cycle shows now is marked ↻, from the clock"
      (open-perform!)
      (ui/wait-for! #(ui/visible? "#zone-map-pad-cycle"))
      (ui/assert-text! "#zone-map-pad-cycle-state" "cycling")
      (fixtures/with-clock
       (fn [_]
         (ui/wait-for! #(some (fn [id] (pseudo? (str "#zone-map-pad-" id) "cycling")) ["left-right" "odd-even"]))
         (is (not (pseudo? "#zone-map-pad-unison" "cycling")) "Unison isn't in the cycle")
         (ui/wait-for! #(some (fn [[id text]] (and (pseudo? (str "#zone-map-pad-" id) "cycling") (= text (ui/node-text (str "#zone-map-pad-" id "-name")))))
                              [["left-right" "↻ LEFT / RIGHT"] ["odd-even" "↻ ODD / EVEN"]])))))))
