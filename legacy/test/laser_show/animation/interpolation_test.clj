(ns laser-show.animation.interpolation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.animation.interpolation :as interp]))

(deftest interpolation-fns-linear-test
  (testing ":linear returns t unchanged"
    (let [linear-fn (:linear interp/interpolation-fns)]
      (is (= 0.0 (linear-fn 0.0)))
      (is (= 0.5 (linear-fn 0.5)))
      (is (= 1.0 (linear-fn 1.0))))))

(deftest interpolation-fns-exp-decay-test
  (testing ":exp-decay starts fast (ease-out)"
    (let [exp-decay-fn (:exp-decay interp/interpolation-fns)]
      (is (= 0.0 (exp-decay-fn 0.0)))
      (is (= 1.0 (exp-decay-fn 1.0)))
      (is (> (exp-decay-fn 0.5) 0.5) "Fast start means > 0.5 at midpoint"))))

(deftest interpolation-fns-exp-grow-test
  (testing ":exp-grow starts slow (ease-in)"
    (let [exp-grow-fn (:exp-grow interp/interpolation-fns)]
      (is (= 0.0 (exp-grow-fn 0.0)))
      (is (= 1.0 (exp-grow-fn 1.0)))
      (is (< (exp-grow-fn 0.5) 0.5) "Slow start means < 0.5 at midpoint"))))

(deftest interpolation-fns-step-test
  (testing ":step holds at 0 until t=1"
    (let [step-fn (:step interp/interpolation-fns)]
      (is (= 0.0 (step-fn 0.0)))
      (is (= 0.0 (step-fn 0.5)))
      (is (= 0.0 (step-fn 0.99)))
      (is (= 1.0 (step-fn 1.0))))))

(deftest apply-interpolation-test
  (testing "nil mode falls back to linear"
    (is (= 0.5 (interp/apply-interpolation 0.5 nil))))
  
  (testing "unknown mode falls back to linear"
    (is (= 0.5 (interp/apply-interpolation 0.5 :unknown-mode))))
  
  (testing "boundary values for all modes"
    (doseq [mode [:linear :exp-decay :exp-grow :step]]
      (is (= 0.0 (interp/apply-interpolation 0.0 mode)) (str mode " at t=0"))
      (is (= 1.0 (interp/apply-interpolation 1.0 mode)) (str mode " at t=1")))))

(deftest interpolate-value-test
  (testing "basic interpolation"
    (is (= 0.0 (interp/interpolate-value 0.0 10.0 0.0)))
    (is (= 5.0 (interp/interpolate-value 0.0 10.0 0.5)))
    (is (= 10.0 (interp/interpolate-value 0.0 10.0 1.0))))
  
  (testing "negative numbers"
    (is (= -10.0 (interp/interpolate-value -10.0 10.0 0.0)))
    (is (= 0.0 (interp/interpolate-value -10.0 10.0 0.5)))
    (is (= 10.0 (interp/interpolate-value -10.0 10.0 1.0))))
  
  (testing "same value"
    (is (= 5.0 (interp/interpolate-value 5.0 5.0 0.0)))
    (is (= 5.0 (interp/interpolate-value 5.0 5.0 0.5)))
    (is (= 5.0 (interp/interpolate-value 5.0 5.0 1.0)))))

(deftest interpolate-params-test
  (testing "interpolates numeric values"
    (let [p1 {:x 0.0 :y 0.0}
          p2 {:x 10.0 :y 20.0}
          result (interp/interpolate-params p1 p2 0.5)]
      (is (= 5.0 (:x result)))
      (is (= 10.0 (:y result)))))
  
  (testing "preserves non-numeric values from first map"
    (let [p1 {:name "test" :value 0.0}
          p2 {:name "other" :value 10.0}
          result (interp/interpolate-params p1 p2 0.5)]
      (is (= "test" (:name result)))
      (is (= 5.0 (:value result)))))
  
  (testing "missing keys use value from first map"
    (let [p1 {:x 5.0 :y 10.0}
          p2 {:x 15.0}
          result (interp/interpolate-params p1 p2 0.5)]
      (is (= 10.0 (:x result)))
      (is (= 10.0 (:y result)) "y not in p2, uses p1 value")))
  
  (testing "applies interpolation curve"
    (let [p1 {:x 0.0}
          p2 {:x 100.0}
          linear-result (interp/interpolate-params p1 p2 0.5 :linear)
          decay-result (interp/interpolate-params p1 p2 0.5 :exp-decay)]
      (is (= 50.0 (:x linear-result)))
      (is (> (:x decay-result) 50.0) "exp-decay at 0.5 should be > 50")))
  
  (testing "empty params maps"
    (is (= {} (interp/interpolate-params {} {} 0.5)))
    (is (= {} (interp/interpolate-params {} {:x 10.0} 0.5)))))

(deftest interpolate-params-default-mode-test
  (testing "2-arity defaults to linear"
    (let [p1 {:x 0.0}
          p2 {:x 100.0}
          result (interp/interpolate-params p1 p2 0.5)]
      (is (= 50.0 (:x result))))))
