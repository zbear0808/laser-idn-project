(ns laser-show.ui-test.grid-test
  "Cue grid on the grid tab, and the effects grid."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene Node]
           [javafx.scene.control Labeled]
           [javafx.scene.layout BackgroundFill BorderStroke Region]
           [javafx.scene.paint Color]
           [org.testfx.api FxRobotInterface]))

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
