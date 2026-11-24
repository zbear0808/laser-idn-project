(ns laser-show.ui-test.grid-test
  "Cue grid on the grid tab, and the effects grid."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging :as log]
            [laser-show.events.core :as events]
            [laser-show.input.router :as input-router]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.drag-drop-cell :as drag-drop])
  (:import [java.io File]
           [javafx.scene Group Node Scene]
           [javafx.scene.control Labeled MenuItem SplitPane]
           [javafx.scene.input ClipboardContent KeyCode MouseButton TransferMode]
           [javafx.scene.layout BackgroundFill BorderStroke Region]
           [javafx.scene.paint Color]
           [javafx.stage Stage]
           [org.testfx.api FxRobot FxRobotInterface]
           [org.testfx.robot Motion]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest grid-renders-stable-cell-locators
  (ui/assert-visible! "#grid-cell-0-0")
  (ui/assert-visible! "#grid-cell-7-3")
  (is (= 32 (count (ui/query-all ".grid-cell")))))

(defn- node
  "The first node matching selector in any window. ui/query looks in the top
   window only, which is a tooltip's while one shows (an effects cell has one)."
  ^Node [selector]
  (first (ui/query-all selector)))

(defn- hex
  "A colour as #RRGGBB."
  [^Color c]
  (let [byte (fn [v] (Math/round (* 255.0 (double v))))]
    (format "#%02X%02X%02X" (byte (.getRed c)) (byte (.getGreen c)) (byte (.getBlue c)))))

(defn- fill-hex
  "The node's background fill as #RRGGBB, with its CSS applied."
  [selector]
  (let [^Region region (node selector)]
    (ui/on-fx-thread
     #(do (.applyCss region)
          (when-let [^Color c (some-> region .getBackground .getFills ^BackgroundFill first .getFill)]
            (hex c))))))

(defn- outline
  "The node's border, with its CSS applied: {:color \"#RRGGBB\" :dashed? bool}."
  [selector]
  (let [^Region region (node selector)]
    (ui/on-fx-thread
     #(do (.applyCss region)
          (when-let [^BorderStroke s (some-> region .getBorder .getStrokes first)]
            {:color (hex (.getTopStroke s))
             :dashed? (boolean (seq (.getDashArray (.getTopStyle s))))})))))

(defn- opacity [selector]
  (let [^Region region (node selector)]
    (ui/on-fx-thread #(do (.applyCss region) (.getOpacity region)))))

(defn- hovered? [selector]
  (let [^Region region (node selector)]
    (ui/on-fx-thread #(.isHover region))))

(defn- move-away!
  "Put the mouse over the tab bar, off every cell (cells: the ones to wait
   for, by default two on the cue grid)."
  ([robot] (move-away! robot "#grid-cell-0-0" "#grid-cell-0-1"))
  ([robot & cells]
   (ui/wait-for! #(some? (node "#grid-tab")))
   (.moveTo ^FxRobotInterface robot (node "#grid-tab"))
   (ui/wait-for! #(not-any? (fn [cell] (hovered? cell)) cells))))

(deftest filled-cell-differs-from-empty-test
  (let [robot (ui/robot)]
    (ui/assert-visible! "#grid-cell-0-0")
    (move-away! robot)
    (is (= "#3D3D3D" (fill-hex "#grid-cell-0-1")) "an empty cell")
    (is (= "#505050" (fill-hex "#grid-cell-0-0")) "a cell with a cue")
    (ui/hover! robot "#grid-cell-0-0")
    (ui/wait-for! #(hovered? "#grid-cell-0-0"))
    (is (= "#656565" (fill-hex "#grid-cell-0-0")) "a cell with a cue, hovered")))

(deftest playing-cell-stays-blue-on-hover-test
  (let [robot (ui/robot)]
    (ui/assert-visible! "#grid-cell-0-0")
    (move-away! robot)
    (ui/click! robot "#grid-cell-0-0")
    (ui/wait-for! #(ui/has-style-class? "#grid-cell-0-0" "grid-cell-active"))
    (ui/wait-for! #(hovered? "#grid-cell-0-0"))
    (is (= "#4992DB" (fill-hex "#grid-cell-0-0")) "playing, hovered")
    (is (< (Math/abs (- 0.9 (opacity "#grid-cell-0-0"))) 1e-6))
    (move-away! robot)
    (is (= "#4992DB" (fill-hex "#grid-cell-0-0")) "playing")
    (is (= 1.0 (opacity "#grid-cell-0-0")))))

(deftest held-cell-has-an-outline-and-no-fill-test
  (let [robot (ui/robot)
        held {:color "#4992DB" :dashed? true}]
    (ui/assert-visible! "#grid-cell-0-0")
    (ui/click! robot "#grid-cell-0-0")
    (ui/wait-for! #(ui/has-style-class? "#grid-cell-0-0" "grid-cell-active"))
    (events/dispatch! {:event/type :transport/stop})
    (ui/wait-for! #(ui/has-style-class? "#grid-cell-0-0" "grid-cell-held"))
    (move-away! robot)
    (is (= "#505050" (fill-hex "#grid-cell-0-0")) "no blue fill: nothing draws it")
    (is (= held (outline "#grid-cell-0-0")) "a blue outline, dashed")
    (testing "hovered, it still looks held"
      (ui/hover! robot "#grid-cell-0-0")
      (ui/wait-for! #(hovered? "#grid-cell-0-0"))
      (is (= "#656565" (fill-hex "#grid-cell-0-0")))
      (is (= held (outline "#grid-cell-0-0"))))
    (testing "a selected cell's outline is solid"
      (ui/click! robot "#grid-cell-0-1")
      (ui/wait-for! #(ui/has-style-class? "#grid-cell-0-1" "grid-cell-selected"))
      (move-away! robot)
      (is (= {:color "#4992DB" :dashed? false} (outline "#grid-cell-0-1")))
      (is (= held (outline "#grid-cell-0-0")) "selecting an empty cell resumes nothing"))))


;; The effects grid

(defn- cell-tooltip
  "The cell's tooltip text (on the label that carries the cell's menu)."
  [selector]
  (let [cell (node selector)]
    (ui/on-fx-thread #(let [^Labeled holder (.getParent cell)]
                        (some-> holder .getTooltip .getText)))))

(defn- label-text [selector]
  (let [^Labeled label (node selector)]
    (ui/on-fx-thread #(.getText label))))

(deftest effects-cell-on-and-off-look-different-test
  (let [robot (ui/robot)
        on? #(get-in (state/get-raw-state) [:chains :effect-chains [0 0] :active?])]
    (events/write-state! assoc-in [:chains :effect-chains [0 0]]
                         {:active? false
                          :items [{:id (random-uuid) :type :effect :effect-id :scale :enabled? true :params {}}
                                  {:id (random-uuid) :type :effect :effect-id :hue-shift :enabled? true :params {}}]})
    (ui/click! robot "#effects-tab")
    (ui/assert-visible! "#effects-cell-0-0")
    (move-away! robot "#effects-cell-0-0")
    (testing "off: filled like a cue cell, named by its first effect, the others in its tooltip"
      (is (= "#505050" (fill-hex "#effects-cell-0-0")))
      (is (= 1.0 (opacity "#effects-cell-0-0")))
      (ui/wait-for! #(= "scale +1" (label-text "#effects-cell-0-0 .grid-cell-label")))
      (is (= "scale +1" (label-text "#effects-cell-0-0 .grid-cell-label")))
      (is (= "scale\nhue shift" (cell-tooltip "#effects-cell-0-0"))))
    (testing "on: blue, hovered or not"
      (ui/click! robot "#effects-cell-0-0")
      (ui/wait-for! #(true? (on?)))
      (ui/wait-for! #(ui/has-style-class? "#effects-cell-0-0" "grid-cell-active"))
      (ui/wait-for! #(hovered? "#effects-cell-0-0"))
      (is (= "#4992DB" (fill-hex "#effects-cell-0-0")) "on, hovered")
      (move-away! robot "#effects-cell-0-0")
      (is (= "#4992DB" (fill-hex "#effects-cell-0-0")) "on")
      (is (= 1.0 (opacity "#effects-cell-0-0"))))
    (testing "off again"
      (ui/click! robot "#effects-cell-0-0")
      (ui/wait-for! #(false? (on?)))
      (ui/wait-for! #(not (ui/has-style-class? "#effects-cell-0-0" "grid-cell-active")))
      (ui/wait-for! #(hovered? "#effects-cell-0-0"))
      (is (= "#656565" (fill-hex "#effects-cell-0-0")) "off, hovered")
      (move-away! robot "#effects-cell-0-0")
      (is (= "#505050" (fill-hex "#effects-cell-0-0"))))))


;; A click names its cell: what the cell holds when the click is handled
;; decides what it does (plans/2026-11-23-cljfx-reactivity/10)

(defn- recorded-click!
  "The events a real primary click on selector dispatches, recorded instead
   of dispatched; the mouse is moved off the cells after, so the next click
   counts as a single one."
  [robot selector]
  (let [sent (atom [])]
    (with-redefs [events/dispatch! #(swap! sent conj %)]
      (ui/click! robot selector))
    (move-away! robot selector)
    @sent))

(defn- handled!
  "Dispatch events in turn and wait until the app has handled them."
  [& events]
  (run! events/dispatch! events)
  (is (events/flush!)))

(defn- edit-marks
  "How many undo steps, and whether there are unsaved changes."
  []
  (let [s (state/get-raw-state)]
    [(count (get-in s [:history :undo-stack])) (get-in s [:project :dirty?])]))

(deftest primary-click-resolves-current-cue-test
  (let [robot (ui/robot)
        A [2 1]
        cell "#grid-cell-2-1"
        circle {:id (random-uuid) :type :preset :preset-id :circle :enabled? true :params {} :effects []}
        click {:event/type :grid/trigger-cell :col 2 :row 1}
        cue #(get-in (state/get-raw-state) [:chains :cue-chains A])
        playback #(:playback (state/get-raw-state))
        selected #(get-in (state/get-raw-state) [:grid :selected-cell])]
    (ui/assert-visible! cell)
    (testing "shown empty, filled before the click is handled: the click plays the cue there (§7 case 2)"
      (ui/wait-for! #(ui/has-style-class? cell "grid-cell-empty"))
      (let [clicks (recorded-click! robot cell)
            items [{:id (random-uuid) :type :group :name "Group" :enabled? false :items [circle]}]]
        (is (= [click] clicks) "the cell's coordinate, and nothing it showed")
        (is (nil? (cue)))
        (handled! {:event/type :chain/set-items :domain :cue-chains :entity-key A :items items})
        (ui/wait-for! #(seq (:items (cue))))
        (let [marks (edit-marks)]
          (apply handled! clicks)
          (let [{:keys [active-cues active-cell playing? pulses]} (playback)
                run (get-in active-cues [A :run])]
            (is (uuid? run))
            (is (= {A {:run run}} active-cues))
            (is (= [A true {:id 1}] [active-cell playing? (:cue pulses)])))
          (is (= A (selected)))
          (is (= marks (edit-marks)) "no undo step, and the unsaved flag as it was")
          (ui/wait-for! #(and (ui/has-style-class? cell "grid-cell-active")
                              (ui/has-style-class? cell "grid-cell-content")))
          (is (= "circle" (label-text (str cell " .grid-cell-label"))) "one preset, the circle")
          (testing "the same click again stops it"
            (apply handled! clicks)
            (is (= [{} nil false {:id 1}]
                   ((juxt :active-cues :active-cell :playing? (comp :cue :pulses)) (playback))))
            (is (= marks (edit-marks)))
            (ui/wait-for! #(not (ui/has-style-class? cell "grid-cell-active")))))))
    (testing "shown with a cue, cleared before the click is handled: the click only selects the cell (§7 case 1)"
      (events/write-state! assoc-in [:chains :cue-chains A] {:items [circle]})
      (handled! {:event/type :grid/trigger-cell :col 0 :row 0})
      (ui/wait-for! #(and (ui/has-style-class? cell "grid-cell-content")
                          (ui/has-style-class? "#grid-cell-0-0" "grid-cell-active")))
      (let [clicks (recorded-click! robot cell)]
        (is (= [click] clicks) "the same click as the empty cell's")
        (handled! {:event/type :grid/clear-cell :col 2 :row 1})
        (ui/wait-for! #(nil? (cue)))
        (let [cleared (playback)
              marks (edit-marks)]
          (apply handled! clicks)
          (is (= A (selected)))
          (is (nil? (cue)) "the cue stays gone")
          (is (= cleared (playback)) "no run, no pulse")
          (is (some? (get-in (playback) [:active-cues [0 0]])) "the other cue plays on")
          (is (= marks (edit-marks)) "no undo step, and the unsaved flag as it was")
          (ui/wait-for! #(and (ui/has-style-class? cell "grid-cell-selected")
                              (ui/has-style-class? cell "grid-cell-empty")))
          (is (not (ui/has-style-class? cell "grid-cell-active")))
          (is (ui/has-style-class? "#grid-cell-0-0" "grid-cell-active")))))))


;; What the tabs say about the clicks

(defn- hint [selector]
  (when-let [^Labeled label (node selector)]
    (ui/on-fx-thread #(when (and (.isVisible label) (.isManaged label)) (.getText label)))))

(deftest hints-describe-the-clicks-test
  (let [robot (ui/robot)]
    (testing "the Grid tab: a click plays, a double-click or the menu edits"
      (ui/wait-for! #(some? (hint "#grid-hint")))
      (is (= "Click: play or stop · Double-click or right-click: edit · Drag: move" (hint "#grid-hint")))
      (is (nil? (hint "#grid-empty-hint"))))
    (testing "the Effects tab, while no cell has an effect but a new project's Master: what they are, and how to start"
      (is (= "Master" (get-in (state/get-raw-state) [:chains :effect-chains [0 3] :name])))
      (ui/click! robot "#effects-tab")
      (ui/wait-for! #(= "Click: on or off · Double-click or right-click: edit · Drag: move" (hint "#grid-hint")))
      (is (= "Effect cells change every playing cue. Double-click one to start" (hint "#grid-empty-hint"))))
    (testing "an empty grid too"
      (events/write-state! assoc-in [:chains :effect-chains] {})
      (ui/sync-fx!)
      (is (= "Effect cells change every playing cue. Double-click one to start" (hint "#grid-empty-hint"))))
    (testing "once a cell has one, the line goes"
      (events/write-state! assoc-in [:chains :effect-chains [3 1]]
                           {:active? false
                            :items [{:id (random-uuid) :type :effect :effect-id :scale :enabled? true :params {}}]})
      (ui/wait-for! #(nil? (hint "#grid-empty-hint")))
      (is (some? (hint "#grid-hint"))))))

(defn- menu-texts
  "The texts of the items in the menu a cell's right-click opens."
  [selector]
  (let [cell (node selector)]
    (ui/on-fx-thread #(let [^Labeled holder (.getParent cell)]
                        (into [] (keep (fn [^MenuItem item] (.getText item)))
                              (.getItems (.getContextMenu holder)))))))

(deftest cell-menus-name-the-platform-shortcut-test
  (let [robot (ui/robot)
        mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
        [copy paste] (if mac? ["⌘C" "⌘V"] ["Ctrl+C" "Ctrl+V"])]
    (ui/assert-visible! "#grid-cell-0-0")
    (testing "a cue cell, and an empty one"
      (is (= [(str "Copy Cue (" copy ")") (str "Paste Cue (" paste ")")]
             (take 2 (menu-texts "#grid-cell-0-0"))))
      (is (some #{(str "Paste Cue (" paste ")")} (menu-texts "#grid-cell-0-1"))))
    (testing "an effects cell"
      (ui/click! robot "#effects-tab")
      (ui/assert-visible! "#effects-cell-0-0")
      (is (= [(str "Copy Effects (" copy ")") (str "Paste Effects (" paste ")")]
             (take 2 (menu-texts "#effects-cell-0-0")))))
    (is (not-any? #(str/includes? % (if mac? "Ctrl+" "⌘"))
                  (concat (menu-texts "#effects-cell-0-0") (menu-texts "#effects-cell-1-1"))))))


;; Pads that grow with the window: the Grid tab is the performer's screen

(defn- bounds [selector] (ui/scene-bounds (node selector)))

(defn- size-of [selector]
  (let [[x0 y0 x1 y1] (bounds selector)] [(- x1 x0) (- y1 y0)]))

(defn- centre [selector]
  (let [[x0 y0 x1 y1] (bounds selector)] [(/ (+ x0 x1) 2) (/ (+ y0 y1) 2)]))

(defn- font-size [selector]
  (let [^Labeled label (node selector)]
    (ui/on-fx-thread #(do (.applyCss label) (.getSize (.getFont label))))))

(defn- cells-span-the-grid?
  "Do the 8 columns take the grid's width, less its 8 px padding and under
   a pixel a column? Just after a resize they don't: until the cells are
   described at the new size, they are too wide or too narrow."
  []
  (let [[_ _ area-right _] (bounds "#grid-area")
        [_ _ cell-right _] (bounds "#grid-cell-7-0")]
    (<= 7.5 (- area-right cell-right) 16)))

(defn- in-view?
  "Is the cell inside the window, left of the divider and above the status bar?"
  [col row]
  (let [[x0 y0 x1 y1] (bounds (str "#grid-cell-" col "-" row))
        [divider-x _ _ _] (bounds ".split-pane-divider")
        [_ status-y _ _] (bounds "#status-bar")]
    (and (<= 0 x0) (<= x1 divider-x) (<= 0 y0) (<= y1 status-y))))

(defn- at-window-size
  "Resize the main window, wait for the cells to take the grid's new width,
   and measure the grid."
  [w h]
  (ui/set-size! (ui/main-window) w h)
  (ui/wait-for! cells-span-the-grid?)
  {:cell (size-of "#grid-cell-0-0")
   :font (font-size "#grid-cell-0-0 .grid-cell-label")
   :in-view? (every? true? (for [col (range 8) row (range 4)] (in-view? col row)))
   :number-8 (first (centre "#grid-column-number-7"))
   :column-8 (first (centre "#grid-cell-7-0"))
   :letter-d (second (centre "#grid-row-letter-3"))
   :row-d (second (centre "#grid-cell-0-3"))})

(deftest cells-grow-with-the-window-test
  ;; 1680×990, not 1000: headless, a window can't reach the screen's last row
  ;; (ui/set-size!)
  (ui/assert-visible! "#grid-cell-7-3")
  (let [small (at-window-size 1024 700)
        default (at-window-size 1200 800)
        large (at-window-size 1680 990)
        width (comp first :cell)]
    (testing "the 8×4 cells show whole from 1024×700 up"
      (is (:in-view? small))
      (is (:in-view? default))
      (is (:in-view? large)))
    (testing "the cells grow with the window, never under 64×48"
      (is (< (width small) (width default) (width large)))
      (is (<= 64 (width small)))
      (is (<= 48 (second (:cell small)))))
    (testing "so does their text, 12 to 16 px"
      (is (<= 12 (:font small) (:font default) (:font large) 16))
      (is (< (:font small) (:font large))))
    (testing "the column numbers and row letters stay on their cells"
      (doseq [at [small default large]]
        (is (< (Math/abs (- (:number-8 at) (:column-8 at))) 1))
        (is (< (Math/abs (- (:letter-d at) (:row-d at))) 1))))))

(defn- divider-position []
  (let [^SplitPane split (node "#main-split")]
    (ui/on-fx-thread #(first (.getDividerPositions split)))))

(defn- remembered-split []
  (get-in (state/get-raw-state) [:config :window :grid-split]))

(deftest split-position-is-remembered-test
  (let [robot (ui/robot)
        near? (fn [a b] (< (Math/abs (- a b)) 0.002))]
    (ui/assert-visible! "#grid-cell-0-0")
    (move-away! robot)
    (testing "a layout pass that moves the divider is not remembered"
      (let [before (divider-position)]
        (at-window-size 1400 900)
        (is (not (near? before (divider-position))) "the resize moved it")
        (is (nil? (remembered-split)))))
    (testing "a drag of the divider is remembered once it is let go"
      (let [before (divider-position)
            let-go! (ui/fire-drag! (node ".split-pane-divider") -200 0 :hold? true)]
        (ui/wait-for! #(< (divider-position) (- before 0.1)))
        (is (nil? (remembered-split)) "not while the mouse holds it")
        (let-go!)
        (ui/wait-for! #(some? (remembered-split)))
        (is (near? (remembered-split) (divider-position)))
        (is (empty? (get-in (state/get-raw-state) [:history :undo-stack])) "a preference, not an edit to undo")
        (ui/wait-for! cells-span-the-grid?)))
    (testing "the divider is back where it was left after another tab"
      (ui/click! robot "#projectors-tab")
      (ui/wait-for! #(nil? (node "#main-split")))
      (ui/click! robot "#grid-tab")
      (ui/wait-for! #(some? (node "#main-split")))
      (ui/wait-for! #(near? (remembered-split) (divider-position)))
      (is (near? (remembered-split) (divider-position))))))


(defn- click-in-submenu!
  "Click the item found by selector in an open submenu, moving across first
   and then down, as a hand does: straight there crosses the parent menu's
   other items, which closes the submenu on the way."
  [robot selector]
  (ui/wait-for! #(ui/visible? selector))
  (.clickOn ^FxRobot robot (node selector) Motion/HORIZONTAL_FIRST (into-array MouseButton [MouseButton/PRIMARY]))
  (ui/sync-fx!))

(deftest new-tap-reaction-on-an-effects-cell-test
  (testing "an empty effects cell's menu makes it a tap reaction, on: no vibes needed (plans/2026-11-18-vibe-mode/04)"
    (let [robot (ui/robot)
          cell #(get-in (state/get-raw-state) [:chains :effect-chains [2 1]])]
      (ui/click! robot "#effects-tab")
      (ui/assert-visible! "#effects-cell-2-1")
      (ui/right-click-node! robot (node "#effects-cell-2-1"))
      (ui/click! robot "#new-reaction-menu")
      (ui/wait-for! #(ui/visible? "#new-reaction-punch-size"))
      (is (= 14 (count (filter #(let [id (str (.getId ^Node %))]
                                  (and (.startsWith id "new-reaction-") (not= "new-reaction-menu" id)))
                               (ui/query-all ".menu-item"))))
          "the fourteen templates, the four zone reactions too, under their roles")
      (click-in-submenu! robot "#new-reaction-punch-size")
      (ui/wait-for! #(= "Punch size" (:name (cell))))
      (is (true? (:active? (cell))))
      (is (= [:scale] (mapv :effect-id (:items (cell)))))
      (is (= :envelope (get-in (cell) [:items 0 :params :x-scale :type])))
      (ui/wait-for! #(ui/has-style-class? "#effects-cell-2-1" "grid-cell-active"))
      (ui/wait-for! #(= "Punch size" (label-text "#effects-cell-2-1 .grid-cell-label")))
      (testing "a cell with effects doesn't offer it"
        (ui/right-click-node! robot (node "#effects-cell-2-1"))
        (ui/wait-for! #(seq (ui/query-all ".context-menu")))
        (is (empty? (ui/query-all "#new-reaction-menu")))
        (ui/press! robot KeyCode/ESCAPE)))))


;; A cell that stores only disabled effects is occupied
;; (plans/2026-11-23-cljfx-reactivity/11)

(defn- menu-items
  "The items of the menu a cell's right-click opens."
  [selector]
  (let [cell (node selector)]
    (ui/on-fx-thread #(vec (.getItems (.getContextMenu ^Labeled (.getParent cell)))))))

(defn- menu-ids [selector]
  (ui/on-fx-thread #(into [] (keep (fn [^MenuItem item] (.getId item))) (menu-items selector))))

(defn- fire-menu-item!
  "Fire the item text of the menu a cell's right-click opens, as a click on
   it does."
  [selector text]
  (let [item (some #(when (= text (ui/on-fx-thread (fn [] (.getText ^MenuItem %)))) %) (menu-items selector))]
    (is (some? item) (str "a menu item " (pr-str text)))
    (ui/on-fx-thread #(.fire ^MenuItem item))
    (ui/sync-fx!)))

(defn- drags-started
  "What a drag gesture detected on the cell at selector starts, recorded
   instead of started: [drag-type payload modes] for each start-drag! call."
  [selector]
  (let [started (atom [])]
    (with-redefs [drag-drop/start-drag! (fn [_ drag-type payload modes]
                                          (swap! started conj [drag-type payload (vec modes)])
                                          nil)]
      (ui/fire-drag-detected! (node selector)))
    @started))

(defn- texts-of
  "The texts of the labels selector finds, in any window."
  [selector]
  (let [labels (ui/query-all selector)]
    (ui/on-fx-thread #(mapv (fn [^Labeled l] (.getText l)) labels))))

(deftest disabled-effect-cell-keeps-menu-and-drag-in-sync-test
  (let [robot (ui/robot)
        pattern (assoc (zone-patterns/preset-instance :chase :cell) :id #uuid "88888888-0000-0000-0000-000000000003")
        tree {:active? false
              :items [{:id #uuid "88888888-0000-0000-0000-000000000001" :type :group :name "Outer"
                       :items [{:id #uuid "88888888-0000-0000-0000-000000000002" :type :group :name "Off" :enabled? false
                                :items [pattern
                                        {:id #uuid "88888888-0000-0000-0000-000000000004" :effect-id :scale
                                         :enabled? true :params {}}]}]}]}
        effects-cell #(get-in (state/get-raw-state) [:chains :effect-chains [%1 %2]])
        source "#effects-cell-2-1"
        neighbour "#effects-cell-3-1"
        groups "#effects-cell-4-1"]
    (events/write-state! #(-> %
                              (assoc-in [:chains :effect-chains [2 1]] tree)
                              (assoc-in [:chains :effect-chains [4 1]]
                                        {:items [{:id #uuid "88888888-0000-0000-0000-000000000005" :type :group
                                                  :name "Empty" :items []}]})))
    (ui/click! robot "#effects-tab")
    (ui/assert-visible! source)
    (testing "it looks filled, named by what it stores, both effects in its tooltip"
      (ui/wait-for! #(ui/has-style-class? source "grid-cell-content"))
      (ui/wait-for! #(= "zone pattern +1" (label-text (str source " .grid-cell-label"))))
      (is (= "Zone pattern: Chase across every laser, 1 step a beat (show beats)\nscale" (cell-tooltip source))))
    (testing "it offers no New menu: it isn't empty; the empty cell beside it offers both"
      (is (not-any? #{"new-reaction-menu" "new-zone-pattern-menu"} (menu-ids source)))
      (is (ui/has-style-class? neighbour "grid-cell-empty"))
      (is (every? (set (menu-ids neighbour)) ["new-reaction-menu" "new-zone-pattern-menu"])))
    (testing "Edit opens the tree it stores"
      (fire-menu-item! source "Edit Effect Chain...")
      (ui/wait-for! #(= [true 2 1] ((juxt :open? :col :row) (get-in (state/get-raw-state) [:ui :dialogs :effect-chain-editor]))))
      (ui/wait-for! #(= ["Zone pattern" "Scale"] (texts-of ".chain-item-name")))
      (is (= ["Outer" "Off"] (texts-of ".group-name-label")))
      (is (= tree (effects-cell 2 1)))
      (events/dispatch! {:event/type :ui/close-dialog :dialog-id :effect-chain-editor})
      (ui/wait-for! #(empty? (ui/query-all ".chain-item"))))
    (testing "a drag starts from it, as a move, and not from the empty cell"
      (let [drags (drags-started source)]
        (is (= [[:effects-cell {:col 2 :row 1} [TransferMode/MOVE]]] drags))
        (is (= [] (drags-started neighbour)))
        (testing "; dropped on that cell, the whole tree moves there"
          (let [[[drag-type payload]] drags]
            (is (:completed? (ui/fire-drop! (node neighbour) (drag-drop/drag-content drag-type payload) 20 20
                                            :mode TransferMode/MOVE)))
            (ui/wait-for! #(and (nil? (effects-cell 2 1)) (= tree (effects-cell 3 1))))
            (ui/wait-for! #(= "zone pattern +1" (label-text (str neighbour " .grid-cell-label"))))
            (ui/wait-for! #(and (= "" (label-text (str source " .grid-cell-label")))
                                (ui/has-style-class? source "grid-cell-empty")))
            (is (ui/has-style-class? neighbour "grid-cell-content"))))))
    (testing "a cell of groups only is occupied too: filled, named so, no New menu, and it can be dragged"
      (ui/wait-for! #(= "Groups only" (label-text (str groups " .grid-cell-label"))))
      (is (ui/has-style-class? groups "grid-cell-content"))
      (is (not-any? #{"new-reaction-menu" "new-zone-pattern-menu"} (menu-ids groups)))
      (is (= [[:effects-cell {:col 4 :row 1} [TransferMode/MOVE]]] (drags-started groups))))
    (testing "the Vibes picker's effect cells say the same"
      (ui/click! robot "#vibes-tab")
      (ui/wait-for! #(= :vibes (get-in (state/get-raw-state) [:ui :active-tab])))
      (ui/click! robot "#vibe-slot-fx")
      (ui/wait-for! #(= "Groups only" (label-text "#pick-fx-cell-4-1 .grid-cell-label")))
      (is (= "zone pattern +1" (label-text "#pick-fx-cell-3-1 .grid-cell-label")))
      (is (ui/has-style-class? "#pick-fx-cell-3-1" "grid-cell-content"))
      (is (ui/has-style-class? "#pick-fx-cell-2-1" "grid-cell-empty")))))


;; A cell's LEARN badge and ⚡ follow its own learn and keys
;; (plans/2026-11-23-cljfx-reactivity/35)

(defn- key-press!
  "A press of the key id, as the router gets one from the keyboard."
  [id]
  (input-router/dispatch! {:type :trigger :source :keyboard :id id :state :pressed
                           :timestamp (System/currentTimeMillis)}))

(defn- trigger-marks
  "What the cell at selector shows of its learn and its keys: the LEARN
   badge, the ⚡, and the Learn, Cancel and Clear items of its menu."
  [selector]
  {:learn? (boolean (seq (ui/query-all (str selector " .grid-cell-learning-badge"))))
   :trigger? (boolean (seq (ui/query-all (str selector " .grid-cell-trigger-indicator"))))
   :menu (filterv #{"Learn Trigger..." "Cancel Learning Trigger" "Clear Triggers"} (menu-texts selector))})

(deftest cell-learn-and-trigger-badges-follow-the-target-test
  (let [robot (ui/robot)
        learning #(get-in (state/get-raw-state) [:backend :input :learning-target])
        bound (fn [id] (some-> (get-in (state/get-raw-state) [:config :input :trigger-map {:source :keyboard :id id}])
                               (select-keys [:type :target])))
        waiting {:learn? true :trigger? false :menu ["Cancel Learning Trigger"]}
        triggered {:learn? false :trigger? true :menu ["Learn Trigger..." "Clear Triggers"]}
        unmarked {:learn? false :trigger? false :menu ["Learn Trigger..."]}
        learn! (fn [cell target id]
                 (fire-menu-item! cell "Learn Trigger...")
                 (ui/wait-for! #(= target (learning)))
                 (ui/wait-for! #(= waiting (trigger-marks cell)))
                 (is (= waiting (trigger-marks cell)) "waiting: LEARN, and Cancel in its menu")
                 (key-press! id)
                 (ui/wait-for! #(and (nil? (learning)) (= target (bound id))))
                 (ui/wait-for! #(= triggered (trigger-marks cell)))
                 (is (= triggered (trigger-marks cell)) "the key bound: ⚡, and Clear in its menu"))
        clear! (fn [cell id]
                 (fire-menu-item! cell "Clear Triggers")
                 (ui/wait-for! #(nil? (bound id)))
                 (ui/wait-for! #(= unmarked (trigger-marks cell)))
                 (is (= unmarked (trigger-marks cell)) "cleared: neither"))
        cue-a2 "#grid-cell-1-0"
        fx-a2 "#effects-cell-1-0"]
    (try
      (testing "the Grid tab: a learn from cue A2's menu, then a key press"
        (ui/assert-visible! cue-a2)
        (is (= unmarked (trigger-marks cue-a2)))
        (learn! cue-a2 {:type :trigger-cue :target [1 0]} :h))
      (testing "the Effects tab: the effects cell at the same place is unmarked, and learns a key of its own"
        (ui/click! robot "#effects-tab")
        (ui/assert-visible! fx-a2)
        (is (= unmarked (trigger-marks fx-a2)))
        (learn! fx-a2 {:type :trigger-effect :target [1 0]} :j)
        (clear! fx-a2 :j)
        (is (= {:type :trigger-cue :target [1 0]} (bound :h)) "the cue's key stays"))
      (testing "back on the Grid tab: cue A2 keeps its ⚡ until its own Clear"
        (ui/click! robot "#grid-tab")
        (ui/assert-visible! cue-a2)
        (ui/wait-for! #(= triggered (trigger-marks cue-a2)))
        (clear! cue-a2 :h))
      (finally
        ;; Release a learn still waiting, when a step failed
        (when (learning)
          (events/dispatch! {:event/type :input/cancel-learn})
          (events/flush!))))))


;; A cell's drag when the target can't see its content: only the live node
;; that started it can supply it (plans/2026-11-23-cljfx-reactivity/38)

(defn- format-of
  "drag-type's private DataFormat, the one drag-content puts on a board."
  [drag-type]
  (key (first (drag-drop/drag-content drag-type {}))))

(defn- hidden
  "A board's content announcing drag-type's private format with the
   content itself hidden (nil), as a platform may until the drop."
  [drag-type]
  (doto (ClipboardContent.) (.put (format-of drag-type) nil)))

(defn- native
  "A board's content with v under drag-type's private format, as a
   platform hands it over (a readable payload is a string)."
  [drag-type v]
  (doto (ClipboardContent.) (.put (format-of drag-type) v)))

(def ^:private !native-starts
  "How often a drag start asked the platform for a dragboard."
  (atom 0))

(defn- start!
  "A drag detected on node, the platform's part of its start (drag-drop-cell's
   private open-dragboard!) answered by a test dragboard, how :ok, :refuse
   (it can't take the content) or :throw. The rest is the app's: the
   node's drag-detected handler, start-drag! and what it keeps."
  ([node] (start! node :ok))
  ([node how]
   (with-redefs-fn {#'drag-drop/open-dragboard!
                    (fn [_ modes]
                      (swap! !native-starts inc)
                      (case how
                        :ok (ui/test-dragboard {} modes)
                        :refuse (ui/test-dragboard {} modes :put? false)
                        :throw (throw (UnsupportedOperationException. "no drag-and-drop here"))))}
     #(ui/fire-drag-detected! node))))

(defn- main-scene ^Scene [] (.getScene (ui/main-window)))

(defn- kept?
  "Does scene keep a drag for a hidden drop at all?"
  [^Scene scene]
  (ui/on-fx-thread #(.containsKey (.getProperties scene) ::drag-drop/active-drag)))

(defn- kept
  "The drag scene keeps, as {:source :drag-type}, or nil."
  [^Scene scene]
  (ui/on-fx-thread #(some-> (.get (.getProperties scene) ::drag-drop/active-drag)
                            (select-keys [:source :drag-type]))))

(defn- doc
  "What a drop can change, once the events queued are handled: the grid's
   cells (chains: :cue-chains or :effect-chains), the undo depth and the
   unsaved flag."
  [chains]
  (is (events/flush!))
  (let [[undo dirty?] (edit-marks)]
    {:cells (get-in (state/get-raw-state) [:chains chains]) :undo undo :dirty? dirty?}))

(defn- cue [preset-id]
  {:items [{:id (random-uuid) :type :preset :preset-id preset-id :enabled? true :params {} :effects []}]})

(defn- fx [effect-id]
  {:active? false :items [{:id (random-uuid) :type :effect :effect-id effect-id :enabled? true :params {}}]})

(def ^:private grids
  "Both grids for the drag tests: the tab, the cells' id prefix, the chains,
   the drag type and the other grid's, and a cell's content."
  [{:tab "#grid-tab" :prefix "grid" :chains :cue-chains :drag-type :grid-cell :other :effects-cell
    :a (cue :circle) :b (cue :wave)}
   {:tab "#effects-tab" :prefix "effects" :chains :effect-chains :drag-type :effects-cell :other :grid-cell
    :a (fx :scale) :b (fx :rotation)}])

(def ^:private refused {:accepted? true :completed? false})
(def ^:private ignored {:accepted? false :completed? false})
(def ^:private moved {:accepted? true :completed? true})

(deftest hidden-cell-drop-requires-its-live-source-test
  (let [robot (ui/robot)]
    (doseq [{:keys [tab prefix chains drag-type other a b]} grids]
      (testing (str "the " prefix " grid:")
        (let [sel (fn [col row] (format "#%s-cell-%d-%d" prefix col row))
              cell (fn [col row] (node (sel col row)))
              drop! (fn [[col row] content & opts]
                      (apply ui/fire-drop! (cell col row) content 20 20 :mode TransferMode/MOVE opts))
              payload (fn [col row] (drag-drop/encode-payload drag-type col row))]
          (events/write-state! #(-> %
                                    (assoc-in [:chains chains [2 1]] a)
                                    (assoc-in [:chains chains [5 0]] b)
                                    (update-in [:chains chains] dissoc [7 3] [6 3] [6 2])))
          (ui/click! robot tab)
          (ui/wait-for! #(and (ui/has-style-class? (sel 2 1) "grid-cell-content")
                              (ui/has-style-class? (sel 7 3) "grid-cell-empty")))
          (let [A (cell 2 1)]
            (start! A)
            (is (= {:source A :drag-type drag-type} (kept (main-scene))) "A's drag, kept on its scene")
            (testing " hidden, from no origin or another node, or not this grid's: nothing (§7 case 3)"
              (let [before (doc chains)]
                (is (= refused (drop! [7 3] (hidden drag-type))) "no gesture source: a drag from outside")
                (is (= refused (drop! [7 3] (hidden drag-type) :gesture-source (cell 0 1))) "another cell")
                (is (= ignored (drop! [7 3] (doto (ClipboardContent.) (.putString (payload 2 1))) :gesture-source A))
                    "text only")
                (is (= ignored (drop! [7 3] (doto (ClipboardContent.) (.putFiles [(File. "show.zip")])) :gesture-source A))
                    "files only")
                (is (= ignored (drop! [7 3] (drag-drop/drag-content other {:col 2 :row 1}) :gesture-source A))
                    "the other grid's")
                (is (= before (doc chains)))))
            (testing " a readable board is the payload, whatever A's drag keeps (§7 case 2)"
              (let [before (doc chains)]
                (doseq [bad ["{" (drag-drop/encode-payload other 2 1) (payload -1 0) 42]]
                  (is (= refused (drop! [7 3] (native drag-type bad) :gesture-source A)) (pr-str bad)))
                (is (= before (doc chains)) "an unreadable one is none: never A's")
                (is (= moved (drop! [7 3] (native drag-type (payload 5 0)) :gesture-source A)))
                (let [after (doc chains)]
                  (is (= [b nil a] (map (:cells after) [[7 3] [5 0] [2 1]])) "the board's cell moved, not A")
                  (is (= [(inc (:undo before)) true] [(:undo after) (:dirty? after)]) "one undo step, unsaved")
                  (testing ", from no gesture source too"
                    (is (= moved (drop! [6 3] (native drag-type (payload 7 3)))))
                    (let [then (doc chains)]
                      (is (= [b nil a] (map (:cells then) [[6 3] [7 3] [2 1]])))
                      (is (= (inc (:undo after)) (:undo then))))))))
            (testing " hidden, from A itself: A's cell moves once; A's done ends it (§7 case 1)"
              (let [before (doc chains)]
                (is (= moved (drop! [7 3] (hidden drag-type) :gesture-source A)))
                (let [after (doc chains)]
                  (is (= [a nil] (map (:cells after) [[7 3] [2 1]])))
                  (is (= (dissoc (:cells before) [2 1]) (dissoc (:cells after) [7 3])) "no other cell changed")
                  (is (= (inc (:undo before)) (:undo after)) "one undo step")
                  (ui/wait-for! #(ui/has-style-class? (sel 2 1) "grid-cell-empty"))
                  (ui/fire-drag-done! A TransferMode/MOVE)
                  (is (not (kept? (main-scene))) "A's emptied cell still ends its drag")
                  (is (= refused (drop! [6 2] (hidden drag-type) :gesture-source A))
                      "the same hidden drop again: offered, never completed")
                  (is (= after (doc chains))))))))))))

(deftest done-detach-and-failed-start-retire-only-the-origin-test
  (let [robot (ui/robot)
        stage ^Stage (ui/main-window)
        scene (main-scene)
        a (cue :circle) b (cue :star)
        sel (fn [col row] (format "#grid-cell-%d-%d" col row))
        cell (fn [col row] (node (sel col row)))
        hidden-drop (fn [origin [col row]]
                      (ui/fire-drop! (cell col row) (hidden :grid-cell) 20 20
                                     :mode TransferMode/MOVE :gesture-source origin))]
    (events/write-state! #(-> %
                              (assoc-in [:chains :cue-chains [2 1]] a)
                              (assoc-in [:chains :cue-chains [4 2]] b)))
    (ui/wait-for! #(ui/has-style-class? (sel 4 2) "grid-cell-content"))
    (testing "the source's done ends its drag: dropped (MOVE), or cancelled or released on nothing (no mode)"
      (let [A (cell 2 1)
            before (doc :cue-chains)]
        (doseq [mode [TransferMode/MOVE nil]]
          (start! A)
          (is (kept? scene))
          (ui/fire-drag-done! A mode)
          (is (not (kept? scene)) (str "nothing kept after " (or mode "a cancel")))
          (is (= refused (hidden-drop A [7 3]))))
        (is (= before (doc :cue-chains)) "and nothing edited")))
    (testing "rendered again in between, the source still ends its drag"
      (let [A (cell 2 1)]
        (start! A)
        (events/write-state! assoc-in [:chains :cue-chains [2 1] :name] "Renamed")
        (ui/wait-for! #(= "Renamed" (label-text (str (sel 2 1) " .grid-cell-label"))))
        (is (identical? A (cell 2 1)) "the same node")
        (is (= {:source A :drag-type :grid-cell} (kept scene)))
        (ui/fire-drag-done! A nil)
        (is (not (kept? scene)))))
    (testing "§7 case 4: B's drag after A's of the same type; A's cleanup again leaves B's alone"
      (let [A (cell 2 1) B (cell 4 2)]
        (start! A)
        (ui/fire-drag-done! A nil)
        (start! B)
        (ui/fire-drag-done! A nil)
        (is (= {:source B :drag-type :grid-cell} (kept scene)))
        (is (= refused (hidden-drop A [7 3])) "A's drag is over")
        (is (= moved (hidden-drop B [7 3])))
        (is (events/flush!))
        (is (= [b nil] (map #(get-in (state/get-raw-state) [:chains :cue-chains %]) [[7 3] [4 2]])) "B's cell moved")
        (ui/fire-drag-done! B TransferMode/MOVE)
        (is (not (kept? scene)))))
    (testing "a start that fails keeps nothing, and the drag before it ended"
      (let [A (cell 2 1) C (cell 0 0)]
        (doseq [how [:throw :refuse]]
          (start! A)
          (is (kept? scene))
          (start! C how)
          (is (not (kept? scene)) (name how))
          (is (= refused (hidden-drop A [6 3])))
          (is (= refused (hidden-drop C [6 3]))))))
    (testing "the source leaves its scene, without a done: another tab"
      ;; Projectors, not Effects: the Effects tab's cells are the Grid tab's
      ;; nodes, described again
      (let [A (cell 2 1)
            before (doc :cue-chains)]
        (start! A)
        (ui/click! robot "#projectors-tab")
        (ui/wait-for! #(nil? (ui/on-fx-thread (fn [] (.getScene ^Node A)))))
        (is (not (kept? scene)))
        (ui/click! robot "#grid-tab")
        (ui/wait-for! #(some? (cell 2 1)))
        (is (not (identical? A (cell 2 1))) "the tab's cells are new ones")
        (is (= refused (hidden-drop A [6 3])) "the old node supplies nothing")
        (is (not (kept? scene)))
        (is (= before (doc :cue-chains)))))
    (testing "the scene leaves its window, and is back"
      (let [A (cell 2 1)]
        (start! A)
        (ui/on-fx-thread #(do (.setScene stage (Scene. (Group.))) (.setScene stage scene)))
        (is (not (kept? scene)))
        (is (= refused (hidden-drop A [6 3])))))
    (testing "the window hides, and shows again"
      (let [A (cell 2 1)]
        (start! A)
        (ui/on-fx-thread #(.hide stage))
        (is (not (kept? scene)))
        (ui/on-fx-thread #(.show stage))
        (ui/wait-for! #(some? (ui/main-window)))
        (is (not (kept? scene)) "and nothing comes back")
        (is (= refused (hidden-drop A [6 3])))))
    (testing "an empty cell starts no drag"
      (reset! !native-starts 0)
      (start! (cell 1 1))
      (is (zero? @!native-starts))
      (is (not (kept? scene))))
    (testing "the Vibes picker's cells have no drag handlers at all, and still take clicks"
      (let [library #(get-in (state/get-raw-state) [:config :vibes :library])]
        (ui/click! robot "#vibes-tab")
        (ui/wait-for! #(some? (node "#pick-cell-5-0")))
        (let [pick (node "#pick-cell-5-0")
              before (library)]
          (is (= [nil nil nil nil]
                 (ui/on-fx-thread #(vector (.getOnDragDetected pick) (.getOnDragDone pick)
                                           (.getOnDragOver pick) (.getOnDragDropped pick)))))
          (ui/click! robot "#pick-cell-5-0")
          (ui/wait-for! #(not= before (library))))))))

(defn- escape-drag!
  "A real drag from source towards target, cancelled with Escape before the
   button is released."
  [^FxRobot robot ^Node source ^Node target]
  (.moveTo robot source)
  (.press robot (into-array MouseButton [MouseButton/PRIMARY]))
  (.moveBy robot 12.0 0.0)
  (.moveTo robot target)
  (ui/sync-fx!)
  (ui/press! robot KeyCode/ESCAPE)
  (.release robot (into-array MouseButton [MouseButton/PRIMARY]))
  (ui/sync-fx!))

(deftest native-cell-drag-clears-on-drop-and-escape-test
  (if (ui/headless?)
    (log/info "Skipping native-cell-drag-clears-on-drop-and-escape-test: the headless glass platform can't run a real drag-and-drop (windowed only)")
    (let [robot (ui/robot)
          scene (main-scene)]
      (doseq [{:keys [tab prefix chains a]} grids]
        (testing (str "the " prefix " grid:")
          (let [sel (fn [col row] (format "#%s-cell-%d-%d" prefix col row))
                cell (fn [col row] (node (sel col row)))
                at (fn [col row] (get-in (state/get-raw-state) [:chains chains [col row]]))]
            (events/write-state! #(-> %
                                      (assoc-in [:chains chains [2 1]] a)
                                      (update-in [:chains chains] dissoc [7 3])))
            (ui/click! robot tab)
            (ui/wait-for! #(ui/has-style-class? (sel 2 1) "grid-cell-content"))
            (testing " a drop moves the cell, and the source's done ends its drag"
              (ui/drag-node-to! robot (cell 2 1) (cell 7 3) 20 20)
              (ui/wait-for! 5000 #(= a (at 7 3)))
              (is (nil? (at 2 1)))
              (ui/wait-for! #(not (kept? scene))))
            (testing " Escape cancels it: nothing moves, nothing is kept"
              (let [before (doc chains)]
                (escape-drag! robot (cell 7 3) (cell 2 1))
                (ui/wait-for! #(not (kept? scene)))
                (is (= before (doc chains)))))
            (testing " released where nothing takes it: the same"
              (let [before (doc chains)]
                (ui/drag-node-to! robot (cell 7 3) (node "#grid-hint") 5 5)
                (ui/wait-for! #(not (kept? scene)))
                (is (= before (doc chains)))))))))))
