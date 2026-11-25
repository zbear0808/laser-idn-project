(ns laser-show.views.dialogs.zone-pattern-editor-test
  "The pattern window's and the step grid window's titles, for a grid cue
   and for a show (plans/2026-11-25-timeline-shows/05 §4.11)."
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.dialogs.step-grid-editor :as step-grid-editor]
            [laser-show.views.dialogs.zone-pattern-editor :as zone-pattern-editor]))

(def u #uuid "6f1c0000-0000-0000-0000-000000000001")

(deftest window-title-test
  (is (= "Zone Pattern — Cue A1 \"Beams\"" (zone-pattern-editor/window-title [0 0] "Beams")))
  (is (= "Zone Pattern — Cue B3" (zone-pattern-editor/window-title [2 1] nil)))
  (is (= "Zone Pattern — Show \"Intro\"" (zone-pattern-editor/window-title [:show u] "Intro")))
  (is (= "Zone Pattern — Show \"Untitled\"" (zone-pattern-editor/window-title [:show u] nil))))

(deftest step-grid-window-title-test
  (is (= "Step Grid — Cue A1 \"Beams\" · Zone pattern"
         (step-grid-editor/window-title {:domain :cue-zone-patterns :entity-key [0 0]} "Beams")))
  (is (= "Step Grid — Effects B3 · Zone pattern"
         (step-grid-editor/window-title {:domain :effect-chains :entity-key [2 1]} "")))
  (is (= "Step Grid — Show \"Intro\" · Zone pattern"
         (step-grid-editor/window-title {:domain :cue-zone-patterns :entity-key [:show u]} "Intro")))
  (is (= "Step Grid — Show \"Untitled\" · Zone pattern"
         (step-grid-editor/window-title {:domain :cue-zone-patterns :entity-key [:show u]} nil))))
