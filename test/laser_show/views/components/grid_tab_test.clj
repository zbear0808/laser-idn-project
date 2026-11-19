(ns laser-show.views.components.grid-tab-test
  "The grid's cell size and label size for the space the tab gives it."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.views.components.grid-tab :as grid-tab]))

(deftest cell-size-test
  (testing "before the area is laid out: 80×60"
    (is (= [80 60] (grid-tab/cell-size 8 4 nil nil))))
  (testing "the columns share the width; a cell is never taller than wide"
    (is (= [70 70] (grid-tab/cell-size 8 4 633 472)) "1024×700")
    (is (= [123 123] (grid-tab/cell-size 8 4 1056 772)) "1680×1000"))
  (testing "a short, wide area: the rows share its height"
    (is (= [166 62] (grid-tab/cell-size 8 4 1400 300))))
  (testing "never under 64×48"
    (is (= [64 48] (grid-tab/cell-size 8 4 400 200)))
    (is (= [64 48] (grid-tab/cell-size 8 4 0 0)))))

(deftest label-size-test
  (testing "12 px at the smallest cell, 16 px at most"
    (is (= 12 (grid-tab/label-size 64 48)))
    (is (= 12 (grid-tab/label-size 70 70)))
    (is (= 14 (grid-tab/label-size 84 84)))
    (is (= 16 (grid-tab/label-size 123 123))))
  (testing "a wide, short cell's size follows its height"
    (is (= 14 (grid-tab/label-size 166 62)))
    (is (= 12 (grid-tab/label-size 166 48)))))
