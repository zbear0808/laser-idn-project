(ns laser-show.animation.test-pattern-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.safety :as safety]
            [laser-show.animation.test-pattern :as tp]
            [laser-show.animation.types :as t]))

(defn- lit [frame] (remove t/blanked? frame))

(deftest content-layer-is-white-at-brightness-test
  (let [frame (tp/content-layer 0.2)]
    (is (< 300 (count frame) 600) "about 400 points")
    (is (seq (lit frame)))
    (is (every? #(= [0.2 0.2 0.2] (subvec % 2 5)) (lit frame)) "white at the brightness")
    (is (every? (fn [[x y]] (and (<= -0.9 x 0.9) (<= -0.9 y 0.9))) frame) "a grid across -0.9..0.9")))

(deftest safety-layer-outlines-stay-visible-test
  (let [s {:limit [-0.8 -0.7 0.8 0.9] :blocked [[-0.2 -0.6 0.2 -0.2] [0.4 0.4 0.6 0.6]]}
        out (safety/apply-safety s (tp/safety-layer s 0.3))
        lit-near (fn [[x0 y0 x1 y1] margin]
                   (some (fn [[x y]] (and (<= (- x0 margin) x (+ x1 margin)) (<= (- y0 margin) y (+ y1 margin))
                                          (not (and (< (+ x0 margin) x (- x1 margin)) (< (+ y0 margin) y (- y1 margin))))))
                         (lit out)))]
    (is (seq (lit out)) "safety leaves the outlines lit")
    (testing "the limit's outline"
      (is (lit-near [-0.8 -0.7 0.8 0.9] 0.001)))
    (testing "each blocked area's outline, just outside it"
      (is (lit-near [-0.2 -0.6 0.2 -0.2] 0.02))
      (is (lit-near [0.4 0.4 0.6 0.6] 0.02)))
    (is (every? #(= [0.3 0.3 0.3] (subvec % 2 5)) (lit out)))))

(deftest identify-flashes-test
  (let [until 10000
        start (- until tp/identify-ms)]
    (is (tp/identify-on? start until) "lit at the start")
    (is (tp/identify-on? (+ start 249) until))
    (is (not (tp/identify-on? (+ start 250) until)) "dark for the next 250 ms")
    (is (tp/identify-on? (+ start 500) until) "and lit again")
    (is (not (tp/identify-on? until until)) "over at its end time")
    (is (not (tp/identify-on? (+ until 1) until)))
    (is (not (tp/identify-on? start nil)) "nothing to show without an end time")))
