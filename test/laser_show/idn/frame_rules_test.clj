(ns laser-show.idn.frame-rules-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.types :as t]
            [laser-show.idn.frame-rules :as rules]))

(defn- d [x y] [x y 0.0 0.0 0.0])

(deftest sanitize-examples-test
  (doseq [[input output]
          [[[] []]
           [[[0.5 0.5 1.0 1.0 1.0]] [[0.5 0.5 0.0 0.0 0.0] [0.5 0.5 1.0 1.0 1.0]]]
           [[[0.0 0.0 0.0 0.0 0.0]] [[0.0 0.0 0.0 0.0 0.0] [0.0 0.0 0.0 0.0 0.0]]]
           [[(d 0.0 0.0) [##NaN ##NaN 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]] [(d 0.0 0.0) [0.5 0.0 1.0 1.0 1.0]]]
           [[(d 0.0 0.0) [0.2 nil 1.0 1.0 1.0]] [(d 0.0 0.0) (d 0.0 0.0)]]
           [[(d 0.0 0.0) [0.2 0.2 ##NaN 1.0 1.0]] [(d 0.0 0.0) (d 0.0 0.0)]]
           [[[##NaN ##NaN 1.0 1.0 1.0]] []]
           [[[##Inf 0.0 1.0 1.0 1.0] [0.5 0.0 1.0 1.0 1.0]] [[0.5 0.0 0.0 0.0 0.0] [0.5 0.0 1.0 1.0 1.0]]]
           [[(d -0.5 0.0) [2.0 0.0 1.0 1.0 1.0]] [(d -0.5 0.0) [2.0 0.0 1.0 1.0 1.0]]]]]
    (is (= output (rules/sanitize-frame input)) (pr-str input))))

(def ^:private bad-values [##NaN ##Inf ##-Inf nil])

(defn- random-frame
  "A frame of 0-8 points, about one value in six bad, about half the points dark."
  [^java.util.Random rng]
  (vec (for [_ (range (.nextInt rng 9))]
         (let [dark? (.nextBoolean rng)
               value (fn [lo hi]
                       (if (zero? (.nextInt rng 6))
                         (nth bad-values (.nextInt rng (count bad-values)))
                         (+ lo (* (- hi lo) (.nextDouble rng)))))]
           [(value -1.2 1.2) (value -1.2 1.2)
            (if dark? 0.0 (value 0.0 1.0)) (if dark? 0.0 (value 0.0 1.0)) (if dark? 0.0 (value 0.0 1.0))]))))

(defn- finite-point? [pt]
  (every? #(and (number? %) (Double/isFinite (double %))) pt))

(deftest sanitize-invariants-test
  (let [rng (java.util.Random. 20261116)]
    (dotimes [_ 2000]
      (let [input (random-frame rng)
            output (rules/sanitize-frame input)
            good (filterv finite-point? input)]
        (testing (pr-str input)
          (is (every? finite-point? output) "no nil, NaN or ±Inf value")
          (when (seq output)
            (is (t/blanked? (first output)) "starts dark")
            (is (<= 2 (count output)) "at least 2 samples"))
          (is (= (empty? good) (empty? output)) "empty only when no good point is left")
          (is (= output (rules/sanitize-frame output)) "idempotent")
          (is (= (set (map #(subvec % 0 2) good)) (set (map #(subvec % 0 2) output)))
              "never moves a point and adds no new position")
          (is (= (set (remove t/blanked? good)) (set (remove t/blanked? output)))
              "never lights a dark point, never darkens a lit one")
          (is (= good (subvec output (- (count output) (count good))))
              "every good point is kept, in order, at the end")
          (is (<= (- (count output) (count good)) 1) "at most one point added"))))))
