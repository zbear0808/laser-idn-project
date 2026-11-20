(ns laser-show.ui-test.vibes-test
  "The Vibes tab: the vibes, the editor and its picker, Make from rows,
   Learn all, learning a fader and the faders' buttons, deleting the vibe
   that plays and undoing a delete, and the tap reaction menus. And the
   Grid and Effects tabs stay as they were (V8 B: the vibe stuff has its own
   tabs), with cells that are in vibes.

   The picker's cells are grid cells (views/components/grid_cell.clj
   generic-grid-cell), whose plain click handler takes the robot's clicks."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.events :as input-events]
            [laser-show.input.router :as input-router]
            [laser-show.input.store :as store]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.vibes.faders :as faders])
  (:import [javafx.scene Node Parent]
           [javafx.scene.control Labeled MenuButton MenuItem]))

(defn- with-input-mapper
  "start-test! doesn't start the input mapper (app.clj:252); Learn all's
   notes go through it, as a controller's do."
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

(defn- app-state [] (state/get-raw-state))
(defn- vibes [& ks] (get-in (app-state) (into [:config :vibes] ks)))
(defn- rt [& ks] (get-in (app-state) (into [:vibe-runtime] ks)))
(defn- edited [] (get-in (app-state) [:ui :vibes :selected]))
(defn- undo-stack [] (get-in (app-state) [:history :undo-stack]))
(defn- undo-label [] (:description (peek (undo-stack))))
(defn- learning-target [] (get-in (app-state) [:backend :input :learning-target]))
(defn- fx-on? [cell] (true? (get-in (app-state) [:chains :effect-chains cell :active?])))

(defn- flush-event-queue!
  "Wait until every event dispatched so far has been handled: they are
   handled in order, so a marker landing means the rest did."
  []
  (ui/sync-fx!)
  (let [marker (+ 60.25 (rand-int 100))]
    (events/dispatch! {:event/type :timing/set-bpm :bpm marker})
    (ui/wait-for! #(= marker (get-in (app-state) [:timing :bpm])))))

(defn- open-vibes! [robot]
  (ui/click! robot "#vibes-tab")
  (ui/wait-for! #(= :vibes (get-in (app-state) [:ui :active-tab])))
  (ui/assert-visible! "#vibe-editor"))

(defn- texts
  "The texts of the nodes matching selector, in scene order."
  [selector]
  (mapv #(.getText ^Labeled %) (ui/query-all selector)))

(defn- with-effect-cell
  "The app with an effect cell at cell, off."
  [cell]
  (state/swap-state! assoc-in [:chains :effect-chains cell]
                     {:active? false :items [{:id (random-uuid) :type :effect :effect-id :scale :enabled? true :params {}}]}))

(defn- note [type n]
  {:type type :source :midi :channel 0 :note n :velocity (if (= :note-on type) 1.0 0.0)
   :timestamp (System/currentTimeMillis)})

(defn- note-key [n] {:source :midi :channel 0 :note n})


(deftest vibes-tab-shows-the-vibes-and-edits-the-first-test
  (let [robot (ui/robot)]
    (is (= ["Grid" "Vibes" "Perform" "Effects" "Projectors" "Zone Groups" "Settings"]
           (mapv #(.getText ^Labeled %) (ui/children ".tab-header")))
        "Grid, Vibes, Perform, then the rest")
    (open-vibes! robot)
    (is (= ["#vibe-card-vibe-1" "#vibe-card-vibe-2"] (mapv #(str "#" (.getId ^Node %)) (ui/query-all ".vibe-card"))))
    (is (ui/has-style-class? "#vibe-card-vibe-1" "vibe-card-selected") "nothing selected yet: the first")
    (ui/assert-text! "#vibe-name-field" "Shapes")
    (is (ui/has-style-class? "#vibe-slot-hit" "vibe-slot-active"))
    (ui/assert-count! ".vibe-pick" 32)
    (is (= #{"#vibe-pick-0-0" "#vibe-pick-1-0" "#vibe-pick-2-0" "#vibe-pick-3-0"}
           (set (map #(str "#" (.getId ^Node %)) (ui/query-all ".vibe-pick-in"))))
        "Shapes' HIT pool is in")
    (ui/assert-text! "#vibe-pick-2-0-pos" "3")
    (is (not (ui/visible? "#preview-panel")) "no preview beside the Vibes tab: the editor and the picker need the width")
    (is (= ["✓ Make cues on the Grid tab" "✓ Add a vibe" "✓ Put HIT cues in every vibe" "○ Learn the five controls (0 of 5)"]
           (texts ".vibe-check")))
    (is (= ["0 of 5 controls learned"] (texts ".vibe-lint-row")))
    (ui/assert-visible! "#vibe-rules")
    (ui/assert-count! ".vibe-control" 5)))

(deftest new-vibe-is-selected-test
  (let [robot (ui/robot)]
    (open-vibes! robot)
    (ui/click! robot "#vibe-add")
    (ui/wait-for! #(= [:vibe-1 :vibe-2 :vibe-3] (vibes :order)))
    (ui/wait-for! #(= :vibe-3 (edited)))
    (ui/wait-for! #(ui/has-style-class? "#vibe-card-vibe-3" "vibe-card-selected"))
    (is (not (ui/has-style-class? "#vibe-card-vibe-1" "vibe-card-selected")))
    (ui/assert-text! "#vibe-name-field" "Vibe 3")
    (is (= "Add Vibe" (undo-label)))
    (is (empty? (ui/query-all ".vibe-pick-in")) "a new vibe has nothing in HIT")
    (testing "renamed with Enter"
      (ui/fill! robot "#vibe-name-field" "Warm-up")
      (ui/submit! robot "#vibe-name-field")
      (ui/wait-for! #(= "Warm-up" (vibes :library :vibe-3 :name)))
      (ui/wait-for! #(str/includes? (ui/node-text "#vibe-card-vibe-3") "Warm-up"))
      (is (= "Rename Vibe" (undo-label))))
    (testing "colour and energy"
      (ui/click! robot "#vibe-color-3")
      (ui/wait-for! #(= "#1ABC9C" (vibes :library :vibe-3 :color)))
      (ui/click! robot "#vibe-energy-4")
      (ui/wait-for! #(= 4 (vibes :library :vibe-3 :energy)))
      (is (= "Change Vibe" (undo-label))))))

(deftest clicking-picker-cells-fills-the-hit-pool-test
  (let [robot (ui/robot)
        hit #(vibes :library :vibe-3 :hit :cells)]
    (open-vibes! robot)
    (ui/click! robot "#vibe-add")
    (ui/wait-for! #(= :vibe-3 (edited)))
    (ui/click! robot "#pick-cell-5-0")
    (ui/wait-for! #(= [[5 0]] (hit)))
    (ui/click! robot "#pick-cell-2-0")
    (ui/wait-for! #(= [[5 0] [2 0]] (hit)))
    (is (= [[5 0] [2 0]] (hit)) "a pool's order is the order of the clicks")
    (is (= "Edit Vibe Cues" (undo-label)))
    (ui/wait-for! #(ui/has-style-class? "#vibe-pick-2-0" "vibe-pick-in"))
    (ui/assert-text! "#vibe-pick-2-0-pos" "2")
    (testing "a click on a cell in the pool takes it out"
      (ui/click! robot "#pick-cell-5-0")
      (ui/wait-for! #(= [[2 0]] (hit)))
      (ui/wait-for! #(not (ui/has-style-class? "#vibe-pick-5-0" "vibe-pick-in"))))
    (testing "an empty cell isn't put in"
      (let [steps (count (undo-stack))]
        (ui/click! robot "#pick-cell-0-2")
        (flush-event-queue!)
        (is (= [[2 0]] (hit)))
        (is (= steps (count (undo-stack))))))
    (testing "the BED slot holds one cell; HIT's cells say they are HIT's"
      (ui/click! robot "#vibe-slot-bed")
      (ui/wait-for! #(ui/has-style-class? "#vibe-slot-bed" "vibe-slot-active"))
      (ui/assert-text! "#vibe-pick-2-0-roles" "HIT")
      (ui/click! robot "#pick-cell-1-0")
      (ui/wait-for! #(= [1 0] (vibes :library :vibe-3 :bed)))
      (ui/click! robot "#pick-cell-3-0")
      (ui/wait-for! #(= [3 0] (vibes :library :vibe-3 :bed)))
      (ui/wait-for! #(= #{"#vibe-pick-3-0"} (set (map (fn [n] (str "#" (.getId ^Node n))) (ui/query-all ".vibe-pick-in"))))))))

(deftest fx-picker-switches-the-playing-vibes-effects-test
  (let [robot (ui/robot)]
    (with-effect-cell [0 0])
    (events/dispatch! {:event/type :vibe/select :vibe :vibe-1})
    (ui/wait-for! #(= :vibe-1 (rt :current)))
    (open-vibes! robot)
    (ui/click! robot "#vibe-slot-fx")
    (ui/wait-for! #(ui/has-style-class? "#vibe-slot-fx" "vibe-slot-active"))
    (ui/assert-count! ".vibe-pick-fx" 32)
    (ui/click! robot "#pick-fx-cell-0-0")
    (ui/wait-for! #(= [[0 0]] (vibes :library :vibe-1 :fx)))
    (ui/wait-for! #(fx-on? [0 0]))
    (is (= "Edit Vibe Effects" (undo-label)))
    (ui/wait-for! #(ui/has-style-class? "#vibe-pick-fx-0-0" "vibe-pick-in"))
    (testing "Shapes plays: out of its FX set, the cell goes off at once"
      (ui/click! robot "#pick-fx-cell-0-0")
      (ui/wait-for! #(= [] (vibes :library :vibe-1 :fx)))
      (ui/wait-for! #(not (fx-on? [0 0]))))))

(deftest make-from-rows-button-test
  (let [robot (ui/robot)]
    (open-vibes! robot)
    (ui/click! robot "#vibe-make-from-rows")
    (ui/wait-for! #(= [:vibe-1 :vibe-2 :vibe-3] (vibes :order)))
    (is (= ["Row A" (mapv #(vector % 0) (range 8))]
           ((juxt :name (comp :cells :hit)) (vibes :library :vibe-3))))
    (is (= "Make Vibes From Rows" (undo-label)))
    (ui/wait-for! #(ui/has-style-class? "#vibe-card-vibe-3" "vibe-card-selected"))
    (ui/assert-text! "#vibe-name-field" "Row A")
    (testing "again: the row is a pool already, so nothing changes"
      (let [steps (count (undo-stack))
            before (vibes)]
        (ui/click! robot "#vibe-make-from-rows")
        (flush-event-queue!)
        (is (= before (vibes)))
        (is (= steps (count (undo-stack))))))))

(deftest learn-all-binds-five-roles-in-order-test
  (let [robot (ui/robot)
        roles [:hit :accent :flash :vibe :blackout]
        learn-all! (fn []
                     (ui/click! robot "#vibe-learn-all")
                     (doseq [[i role] (map-indexed vector roles)]
                       (ui/wait-for! #(= {:type :vibe :role role} (learning-target)))
                       (ui/assert-text! "#vibe-wizard-status"
                                        (str "Press the key for " (str/upper-case (name role)) " (" (inc i) " of 5)"))
                       (input-router/dispatch! (note :note-on (+ 36 i)))
                       (ui/wait-for! #(= {:type :vibe :role role}
                                         (get-in (app-state) [:config :input :trigger-map (note-key (+ 36 i))])))
                       (input-router/dispatch! (note :note-off (+ 36 i))))
                     (ui/wait-for! #(nil? (learning-target)))
                     (flush-event-queue!))]
    (open-vibes! robot)
    (learn-all!)
    (is (nil? (get-in (app-state) [:ui :vibes :wizard])) "invariant 8: it ends, nothing left learning")
    (ui/wait-for! #(not (ui/visible? "#vibe-wizard-status")))
    (ui/assert-text! "#vibe-control-hit-keys" "MIDI Ch 1 Note 36")
    (ui/assert-text! "#vibe-control-blackout-keys" "MIDI Ch 1 Note 40")
    (ui/assert-text! "#vibe-check-controls" "✓ Learn the five controls (5 of 5)")
    (testing "again with the same keys: each binds as before, and no press plays its old role (K-15)"
      (learn-all!)
      (is (= 5 (count (get-in (app-state) [:config :input :trigger-map]))))
      (is (empty? (rt :held)))
      (is (zero? (rt :started)) "no HIT, ACCENT or FLASH played")
      (is (false? (rt :blackout?)) "BLACKOUT's own key didn't black the room out"))))

(deftest learn-a-fader-binds-a-slot-test
  (let [robot (ui/robot)
        cc! (fn [control & vs] (doseq [v vs] (input-router/dispatch! (input-events/control-change :midi 0 control v))))
        macro-map #(get-in (app-state) [:config :input :macro-map])
        learning-slider-1? #(= {:type :macro :slot :slider-1} (learning-target))]
    (try
      (store/clear-continuous-values!)
      (open-vibes! robot)
      (ui/assert-count! ".vibe-fader" 2)
      (ui/assert-text! "#vibe-control-slider-1-keys" "Not learned")
      (ui/assert-disabled! "#vibe-control-slider-1-clear")
      (ui/click! robot "#vibe-control-slider-1-learn")
      (ui/wait-for! learning-slider-1?)
      (ui/assert-text! "#vibe-control-slider-1-keys" "Move a fader…")
      (ui/assert-text! "#vibe-control-slider-1-learn" "Cancel")
      (testing "invariants 8 and 9: a note, and a fader's wiggle, bind nothing"
        (input-router/dispatch! (note :note-on 36))
        (input-router/dispatch! (note :note-off 36))
        (cc! 7 0.50 0.52)
        (flush-event-queue!)
        (is (learning-slider-1?) "still waiting")
        (is (empty? (macro-map)))
        (is (empty? (get-in (app-state) [:config :input :trigger-map]))))
      (cc! 1 0.10 0.31)
      (ui/wait-for! #(= {[:midi 0 1] :slider-1} (macro-map)))
      (ui/assert-text! "#vibe-control-slider-1-keys" "MIDI Ch 1 CC 1")
      (ui/wait-for! #(nil? (learning-target)))
      (is (= "Learn Fader" (undo-label)))
      (testing "the fader is where the learn left it, at once (05 §11), and follows the control through the mapper"
        (is (= {:slider-1 0.31} (faders/fader-values (store/get-all-continuous-values) (macro-map))))
        (cc! 1 0.4)
        (is (= {:slider-1 0.4} (faders/fader-values (store/get-all-continuous-values) (macro-map)))))
      (testing "learned again on another fader: the slot moves to it"
        (ui/click! robot "#vibe-control-slider-1-learn")
        (ui/wait-for! learning-slider-1?)
        (cc! 9 0.9 0.5)
        (ui/wait-for! #(= {[:midi 0 9] :slider-1} (macro-map)))
        (ui/assert-text! "#vibe-control-slider-1-keys" "MIDI Ch 1 CC 9")
        (is (= {:slider-1 0.5} (faders/fader-values (store/get-all-continuous-values) (macro-map)))))
      (testing "Clear"
        (ui/click! robot "#vibe-control-slider-1-clear")
        (ui/wait-for! #(empty? (macro-map)))
        (ui/assert-text! "#vibe-control-slider-1-keys" "Not learned")
        (is (= "Clear Control" (undo-label))))
      (finally
        (store/clear-continuous-values!)))))

(deftest fader-buttons-add-the-master-and-a-morph-test
  (let [robot (ui/robot)]
    (open-vibes! robot)
    (ui/assert-visible! "#vibe-add-master")
    (ui/assert-disabled! "#vibe-add-master")
    (is (= "Master" (get-in (app-state) [:chains :effect-chains [0 3] :name])) "a new project has its Master already")
    (ui/assert-text! "#vibe-add-morph" "Add Morph to Shapes")
    (ui/click! robot "#vibe-add-morph")
    (ui/click! robot "#vibe-add-morph-size")
    (ui/wait-for! #(= [[1 3]] (vibes :library :vibe-1 :fx)))
    (is (= "Shapes · Morph: Size" (get-in (app-state) [:chains :effect-chains [1 3] :name])))
    (is (= "Add Morph" (undo-label)))
    (testing "the Master gone, Add master intensity puts one back"
      (flush-event-queue!)
      (state/swap-state! update-in [:chains :effect-chains] dissoc [0 3])
      (ui/assert-enabled! "#vibe-add-master")
      (ui/click! robot "#vibe-add-master")
      (ui/wait-for! #(= "Master" (get-in (app-state) [:chains :effect-chains [0 3] :name])))
      (is (true? (get-in (app-state) [:chains :effect-chains [0 3] :active?])))
      (is (= "Add Master Intensity" (undo-label)))
      (ui/assert-disabled! "#vibe-add-master"))))

(deftest deleting-the-current-vibe-keeps-perform-working-test
  (let [robot (ui/robot)]
    (with-effect-cell [0 3])
    (state/swap-state! assoc-in [:config :vibes :library :vibe-1 :fx] [[0 3]])
    (events/dispatch! {:event/type :vibe/select :vibe :vibe-1})
    (ui/wait-for! #(fx-on? [0 3]))
    (open-vibes! robot)
    (ui/click! robot "#vibe-delete")
    (ui/wait-for! #(= [:vibe-2] (vibes :order)))
    (is (= "Delete Vibe" (undo-label)))
    (ui/wait-for! #(= :vibe-2 (rt :current)))
    (is (not (fx-on? [0 3])) "the vibe that played left its look")
    (ui/assert-text! "#vibe-name-field" "Lines")
    (ui/click! robot "#perform-tab")
    (ui/assert-text! "#vibe-header-name" "LINES")
    ;; A busy machine can't stretch the click into a hold, which cuts the cue
    (state/swap-state! assoc-in [:config :vibes :settings :quick-tap-ms] 2000)
    (ui/mouse-click! robot "#vibe-pad-hit")
    (ui/wait-for! #(#{[5 0] [7 0]} (rt :voices :hit :cell)))))

(deftest undo-restores-a-deleted-vibe-test
  (let [robot (ui/robot)
        before (vibes)]
    (open-vibes! robot)
    (ui/click! robot "#vibe-card-vibe-2")
    (ui/wait-for! #(= :vibe-2 (edited)))
    (ui/click! robot "#vibe-delete")
    (ui/wait-for! #(= [:vibe-1] (vibes :order)))
    (ui/wait-for! #(empty? (ui/query-all "#vibe-card-vibe-2")))
    (ui/assert-text! "#vibe-name-field" "Shapes")
    (events/dispatch! {:event/type :edit/undo})
    (ui/wait-for! #(= before (vibes)))
    (ui/assert-visible! "#vibe-card-vibe-2")
    (is (false? (get-in (app-state) [:project :dirty?])) "deleted and put back: as saved")))

(defn- menu-button-texts
  "The texts of the items of the menu button found by selector, separators
   left out."
  [selector]
  (let [^MenuButton menu (first (ui/query-all selector))]
    (ui/on-fx-thread #(into [] (keep (fn [^MenuItem item] (.getText item))) (.getItems menu)))))

(deftest tap-reaction-menu-adds-a-cell-to-the-vibe-test
  (let [robot (ui/robot)
        fx #(vibes :library :vibe-1 :fx)]
    (state/swap-state! assoc-in [:config :input :trigger-map (note-key 37)] {:type :vibe :role :accent})
    (events/dispatch! {:event/type :vibe/select :vibe :vibe-1})
    (ui/wait-for! #(= :vibe-1 (rt :current)))
    (open-vibes! robot)
    (ui/assert-text! "#vibe-reactions-hit" "none yet")
    (is (= ["HIT" "Punch: size" "Pump: brightness (35 % between hits)" "Step: colour" "Step: spin"
            "HIT, ACCENT or FLASH" "Energy: size"]
           (menu-button-texts "#vibe-tap-menu-hit"))
        "HIT's templates, and the one that follows any of the three")
    (ui/click! robot "#vibe-tap-menu-hit")
    (ui/click! robot "#vibe-tap-punch-size")
    ;; A new project's Master holds [0 3] (05 §7), so the first empty cell is [1 3]
    (ui/wait-for! #(= [[1 3]] (fx)))
    (is (= "Shapes · Punch size" (get-in (app-state) [:chains :effect-chains [1 3] :name])) "the first empty effect cell")
    (ui/wait-for! #(fx-on? [1 3]))
    (is (fx-on? [1 3]) "Shapes plays: on at once")
    (is (= "Add Tap Reaction" (undo-label)))
    (ui/assert-text! "#vibe-reactions-hit" "Punch size")
    (ui/assert-text! "#vibe-slot-fx" "FX set  1")
    (testing "FLASH's: the Strobe goes into the flash effects, off until FLASH is held"
      (ui/click! robot "#vibe-slot-flash-cue")
      (ui/wait-for! #(ui/has-style-class? "#vibe-slot-flash-cue" "vibe-slot-active"))
      (ui/assert-text! "#vibe-reactions-flash" "none yet")
      (ui/click! robot "#vibe-tap-menu-flash")
      (ui/click! robot "#vibe-tap-strobe")
      (ui/wait-for! #(= [[2 3]] (vibes :library :vibe-1 :flash :fx)))
      (is (not (fx-on? [2 3])))
      (ui/assert-text! "#vibe-reactions-flash" "Strobe"))
    (testing "VIBE's, beside the name"
      (is (= ["VIBE" "Fade in"] (menu-button-texts "#vibe-tap-menu-vibe")))
      (ui/click! robot "#vibe-tap-menu-vibe")
      (ui/click! robot "#vibe-tap-fade-in")
      (ui/wait-for! #(= [[1 3] [3 3]] (fx)))
      (ui/assert-text! "#vibe-reactions-vibe" "Fade in"))
    (testing "the lint names a reaction whose control has no key yet (ACCENT has one)"
      (ui/wait-for! #(some #{"Shapes · Punch size listens to HIT, which has no key yet"} (texts ".vibe-lint-row"))))))


;; V8 B: nothing vibe-related on the Grid tab or the Effects tab

(defn- style-classes-under
  "Every style class of node and the nodes under it."
  [^Node node]
  (ui/on-fx-thread
   #(letfn [(walk [^Node n]
              (concat (.getStyleClass n)
                      (when (instance? Parent n)
                        (mapcat walk (.getChildrenUnmodifiable ^Parent n)))))]
      (vec (walk node)))))

(defn- menu-texts
  "The texts of the menu of the grid cell found by selector (on the label
   that holds it), separators left out."
  [selector]
  (let [cell (first (ui/query-all selector))]
    (ui/on-fx-thread #(->> (.getItems (.getContextMenu ^Labeled (.getParent cell)))
                           (keep (fn [^MenuItem item] (.getText item)))
                           vec))))

(deftest grid-and-effects-tabs-show-no-vibe-marks-test
  (let [robot (ui/robot)
        vibe-text? #(re-find #"(?i)vibe|\bHIT\b|ACCENT|FLASH|\bBED\b|Shapes|Lines" %)]
    (with-effect-cell [1 0])
    (state/swap-state! assoc-in [:config :vibes :library :vibe-1 :fx] [[1 0]])
    (state/swap-state! assoc-in [:config :vibes :library :vibe-1 :bed] [0 1])
    (testing "the Grid tab: cells in vibes (A1 in Shapes' HIT, A7 its FLASH cue, A5 in both vibes) look and read as any cell"
      (ui/assert-visible! "#grid-cell-0-0")
      (is (not-any? #(str/starts-with? % "vibe") (style-classes-under (first (ui/query-all "#main-layout"))))
          "no vibe class anywhere in the window")
      (doseq [cell ["#grid-cell-0-0" "#grid-cell-6-0" "#grid-cell-4-0" "#grid-cell-0-1"]]
        (is (not-any? vibe-text? (menu-texts cell)) (str cell "'s menu"))
        (is (not-any? vibe-text? (texts (str cell " .label"))) (str cell "'s labels"))))
    (testing "the Effects tab: an effect cell in Shapes' FX set too"
      (ui/click! robot "#effects-tab")
      (ui/assert-visible! "#effects-cell-1-0")
      (is (not-any? #(str/starts-with? % "vibe") (style-classes-under (first (ui/query-all "#main-layout")))))
      (is (not-any? vibe-text? (menu-texts "#effects-cell-1-0")))
      (is (not-any? vibe-text? (texts "#effects-cell-1-0 .label"))))))
