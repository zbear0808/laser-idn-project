(ns laser-show.ui-test.grid-test
  "Cue grid on the grid tab, and the effects grid."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.drag-drop-cell :as drag-drop])
  (:import [javafx.event Event]
           [javafx.scene Node]
           [javafx.scene.control Labeled MenuItem SplitPane]
           [javafx.scene.input KeyCode MouseButton MouseEvent TransferMode]
           [javafx.scene.layout BackgroundFill BorderStroke Region]
           [javafx.scene.paint Color]
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
  (let [started (atom [])
        cell (node selector)]
    (with-redefs [drag-drop/start-drag! (fn [_ drag-type payload modes]
                                          (swap! started conj [drag-type payload (vec modes)])
                                          nil)]
      (ui/on-fx-thread
       #(let [b (.getBoundsInLocal cell)
              x (/ (+ (.getMinX b) (.getMaxX b)) 2)
              y (/ (+ (.getMinY b) (.getMaxY b)) 2)
              scene (.localToScene cell (double x) (double y))
              screen (.localToScreen cell (double x) (double y))]
          (Event/fireEvent cell (MouseEvent. MouseEvent/DRAG_DETECTED (.getX scene) (.getY scene)
                                             (.getX screen) (.getY screen) MouseButton/PRIMARY 1
                                             false false false false true false false false false false nil)))))
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
