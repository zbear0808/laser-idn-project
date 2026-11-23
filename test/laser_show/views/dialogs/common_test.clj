(ns laser-show.views.dialogs.common-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.views.dialogs.common :as common]))

(deftest cell-label-test
  (is (= "A1" (common/cell-label 0 0)))
  (is (= "D8" (common/cell-label 7 3)) "the row is the letter, the column the number")
  (is (= "B3" (common/cell-label 2 1)))
  (is (= "A1" (common/cell-label nil nil)) "no cell counts as the first"))

(deftest default-name-test
  (is (= "Cell A1" (common/default-name nil nil)))
  (is (= "Cell D8" (common/default-name 7 3))))

(deftest window-title-test
  (is (= "Cue Chain Editor - Intro (Cell A1)" (common/window-title "Cue Chain Editor" "Intro" 0 0)))
  (is (= "Cue Chain Editor - Cell A1" (common/window-title "Cue Chain Editor" nil 0 0)))
  (is (= "Effects Chain - Cell B3" (common/window-title "Effects Chain" "" 2 1))
      "an empty name is no name")
  (is (= "Timeline Editor - Run (Cell D8)" (common/window-title "Timeline Editor" "Run" 7 3))))

(deftest undo-redo-event-test
  (is (= :edit/undo (common/undo-redo-event {:code :z :shortcut? true})))
  (is (= :edit/redo (common/undo-redo-event {:code :z :shortcut? true :shift? true})))
  (is (= :edit/redo (common/undo-redo-event {:code :y :shortcut? true})))
  (is (= :edit/redo (common/undo-redo-event {:code :y :shortcut? true :shift? true})))
  (testing "never without the shortcut modifier, so typing z or y in a field is untouched"
    (is (nil? (common/undo-redo-event {:code :z})))
    (is (nil? (common/undo-redo-event {:code :z :shift? true})))
    (is (nil? (common/undo-redo-event {:code :y}))))
  (is (nil? (common/undo-redo-event {:code :x :shortcut? true})) "other chords aren't undo or redo"))
