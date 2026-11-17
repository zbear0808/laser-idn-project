(ns laser-show.animation.types-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.animation.types :as t]))

(deftest nan-colour-is-not-lit-test
  (is (t/blanked? [0.0 0.0 ##NaN ##NaN ##NaN]))
  (is (t/blanked? [0.0 0.0 ##NaN 0.0 0.0]))
  (is (not (t/blanked? [0.0 0.0 ##NaN 1.0 0.0])) "another channel is lit")
  (is (t/blanked? [0.0 0.0 0.0 0.0 0.0]))
  (is (t/blanked? [0.0 0.0 1e-7 0.0 0.0]))
  (is (not (t/blanked? [0.0 0.0 0.0 0.0 1e-6]))))
