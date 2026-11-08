(ns laser-show.ui-test.preview-test
  "Preview panel: layout selector and the per-cell zone picker."
  (:require [clojure.test :refer [deftest use-fixtures]]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest preview-layout-selector-and-zone-picker-are-clickable
  (let [robot (ui/robot)]
    (ui/select! robot "#preview-layout-selector" "2x2")
    (ui/wait-for! #(= [2 2] (get-in (state/get-raw-state)
                                     [:config :preview :grid-layout])))
    (ui/click! robot "#preview-zone-label-0")
    (ui/wait-for! #(= 0 (get-in (state/get-raw-state)
                                [:ui :preview-zone-selector-open])))
    (ui/assert-visible! "#preview-zone-option-0-all")
    (ui/click! robot "#preview-zone-option-0-all")
    (ui/wait-for! #(nil? (get-in (state/get-raw-state)
                                 [:ui :preview-zone-selector-open])))))
