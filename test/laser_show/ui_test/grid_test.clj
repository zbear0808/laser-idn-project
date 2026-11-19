(ns laser-show.ui-test.grid-test
  "Cue grid on the grid tab."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene.layout BackgroundFill Region]
           [javafx.scene.paint Color]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest grid-renders-stable-cell-locators
  (ui/assert-visible! "#grid-cell-0-0")
  (ui/assert-visible! "#grid-cell-7-3")
  (is (= 32 (count (ui/query-all ".grid-cell")))))

(defn- fill-hex
  "The node's background fill as #RRGGBB, with its CSS applied."
  [selector]
  (let [^Region node (ui/query selector)]
    (ui/on-fx-thread
     #(do (.applyCss node)
          (let [^Color c (some-> node .getBackground .getFills ^BackgroundFill first .getFill)
                byte (fn [v] (Math/round (* 255.0 (double v))))]
            (when c
              (format "#%02X%02X%02X" (byte (.getRed c)) (byte (.getGreen c)) (byte (.getBlue c)))))))))

(defn- opacity [selector]
  (let [^Region node (ui/query selector)]
    (ui/on-fx-thread #(do (.applyCss node) (.getOpacity node)))))

(defn- hovered? [selector]
  (let [^Region node (ui/query selector)]
    (ui/on-fx-thread #(.isHover node))))

(defn- move-away!
  "Put the mouse over the tab bar, off every cell."
  [robot]
  (ui/hover! robot "#grid-tab")
  (ui/wait-for! #(not-any? (fn [cell] (hovered? cell)) ["#grid-cell-0-0" "#grid-cell-0-1"])))

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
