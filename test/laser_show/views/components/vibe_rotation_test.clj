(ns laser-show.views.components.vibe-rotation-test
  "What the Perform tab's countdown says, and how it pulses while armed
   (plans/2026-11-18-vibe-mode/06 §4)."
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.components.vibe-rotation :as vibe-rotation]))

(def ^:private active {:rotation :active :entered-at 1000 :seconds 45.0 :next "Build"})

(deftest countdown-text-test
  (is (= "ACTIVE 0:31 / 0:45" (vibe-rotation/countdown-text active 32500)))
  (is (= "ACTIVE 0:00 / 0:45" (vibe-rotation/countdown-text active 1999)))
  (is (= "ACTIVE 0:45 / 0:45" (vibe-rotation/countdown-text active 100000)) "never past its time while the arm is on its way")
  (is (= "ACTIVE 0:00 / 0:45" (vibe-rotation/countdown-text (assoc active :entered-at nil) 100000)) "before a vibe plays")
  (is (= "ACTIVE 1:05 / 2:00" (vibe-rotation/countdown-text (assoc active :seconds 120.0) 66000)))
  (is (= "ARMED → next: Build" (vibe-rotation/countdown-text (assoc active :rotation :armed) 50000))))

(deftest armed-countdown-pulses-twice-a-second-test
  (let [close? #(< (Math/abs (- (double %1) (double %2))) 1e-9)]
    (is (every? #(close? 1.0 (vibe-rotation/armed-opacity %)) [0 500 1000 1500]) "full every 500 ms")
    (is (every? #(close? 0.35 (vibe-rotation/armed-opacity %)) [250 750]) "faint in between")
    (is (close? 0.675 (vibe-rotation/armed-opacity 125)))))
