(ns laser-show.views.dialogs.common-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.dialogs.common :as common]))

(deftest cell-label-test
  (is (= "A1" (common/cell-label 0 0)))
  (is (= "D8" (common/cell-label 7 3)) "the row is the letter, the column the number")
  (is (= "B3" (common/cell-label 2 1)))
  (is (= "A1" (common/cell-label nil nil)) "no cell counts as the first"))
