(ns laser-show.ui-test.grid-test
  "Cue grid on the grid tab, and the effects grid."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene Node]
           [javafx.scene.control Labeled SplitPane]
           [javafx.scene.input KeyCode MouseButton]
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
    (state/swap-state! assoc-in [:chains :effect-chains [0 0]]
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
      (is (= 10 (count (filter #(let [id (str (.getId ^Node %))]
                                  (and (.startsWith id "new-reaction-") (not= "new-reaction-menu" id)))
                               (ui/query-all ".menu-item"))))
          "the ten templates, under their roles")
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
