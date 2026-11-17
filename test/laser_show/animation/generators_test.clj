(ns laser-show.animation.generators-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.generators :as gen]
            [laser-show.animation.types :as t]))

(defn- finite-point? [pt]
  (every? #(Double/isFinite (double %)) pt))

(deftest wave-frame-starts-dark-test
  (let [frame (gen/wave-frame {})
        [first-pt second-pt] frame]
    (is (t/blanked? first-pt))
    (is (= (subvec first-pt 0 2) (subvec second-pt 0 2)) "the dark point sits where the wave starts")
    (is (not (t/blanked? second-pt)))
    (is (= 65 (count frame)) "64 wave points after the dark one")))

(deftest one-point-wave-is-finite-test
  (doseq [n [0 1 2]]
    (testing (str n " points")
      (let [frame (gen/wave-frame {:num-points n})]
        (is (every? finite-point? frame))
        (is (<= 3 (count frame)) "a dark point and at least 2 wave points")
        (is (t/blanked? (first frame)))))))
