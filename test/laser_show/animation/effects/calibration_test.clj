(ns laser-show.animation.effects.calibration-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.calibration]
            [laser-show.animation.types :as t]))

(def ^:private raised-red
  "Diode threshold compensation: red starts at 0.1"
  {:effects [{:id (random-uuid) :effect-id :rgb-curves :enabled? true
              :params {:r-curve-points [[0.0 0.1] [1.0 1.0]]
                       :g-curve-points [[0.0 0.0] [1.0 1.0]]
                       :b-curve-points [[0.0 0.0] [1.0 1.0]]}}]})

(deftest rgb-curves-keep-blanked-points-dark-test
  (let [[blank lit nan-red] (effects/apply-effect-chain [(t/blanked-point 0.5 0.5)
                                                         [0.2 0.2 0.0 1.0 0.0]
                                                         [0.3 0.3 ##NaN 1.0 0.0]]
                                                        raised-red)]
    (testing "a blanked point stays blanked"
      (is (= [0.5 0.5 0.0 0.0 0.0] blank))
      (is (t/blanked? blank)))
    (testing "a lit point is still mapped through the curve"
      (is (< 0.09 (lit t/R) 0.11)))
    (testing "a NaN channel stays NaN, for the sanitiser to drop"
      (is (Double/isNaN (nan-red t/R))))))
