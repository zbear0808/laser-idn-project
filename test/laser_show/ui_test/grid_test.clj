(ns laser-show.ui-test.grid-test
  "Cue grid on the grid tab."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest grid-renders-stable-cell-locators
  (ui/assert-visible! "#grid-cell-0-0")
  (ui/assert-visible! "#grid-cell-7-3")
  (is (= 32 (count (ui/query-all ".grid-cell")))))
