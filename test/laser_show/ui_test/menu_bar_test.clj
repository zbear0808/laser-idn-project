(ns laser-show.ui-test.menu-bar-test
  "Menu bar in the title bar."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest menu-bar-opens-a-menu
  (let [robot (ui/robot)]
    (ui/click! robot "#transport-menu")
    (ui/wait-for! #(some? (ui/query ".menu-item")))
    (is (some? (ui/query ".menu-item")))
    (ui/press! robot javafx.scene.input.KeyCode/ESCAPE)))
