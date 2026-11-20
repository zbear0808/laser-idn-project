(ns laser-show.animation.fit-guard-test
  "The fit guard (plans/2026-11-19-zones/01 §8, 05 §4): a laser's fit
   settings, clamped."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.fit-guard :as fg]))

(deftest settings-are-clamped-test
  (is (= {:min-fps 20 :reduce-points? true :point-floor 0.6 :dim? false :dim-floor 0.5} (fg/settings nil))
      "no :fit: the defaults (D-18)")
  (is (= fg/defaults (fg/settings {})))
  (is (= (assoc fg/defaults :min-fps 30 :dim? true :dim-floor 0.7)
         (fg/settings {:min-fps 30 :dim? true :dim-floor 0.7 :colour "red"}))
      "a laser's own over the defaults, and nothing else")
  (testing "clamped to 5-60, 0.3-1.0 and 0.2-1.0"
    (is (= [5 60 22.5] (mapv #(:min-fps (fg/settings {:min-fps %})) [1 100 22.5])))
    (is (= [0.3 1.0] (mapv #(:point-floor (fg/settings {:point-floor %})) [0.1 2])))
    (is (= [0.2 1.0] (mapv #(:dim-floor (fg/settings {:dim-floor %})) [0.0 5]))))
  (testing "a value of the wrong type takes its default"
    (is (= fg/defaults (fg/settings {:min-fps "fast" :reduce-points? "yes" :point-floor nil :dim? 1 :dim-floor ##NaN})))
    (is (= fg/defaults (fg/settings {:min-fps ##Inf})))
    (is (= fg/defaults (fg/settings "not a map")))))
