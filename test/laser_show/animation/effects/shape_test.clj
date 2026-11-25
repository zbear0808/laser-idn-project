(ns laser-show.animation.effects.shape-test
  "Smart Size: Scale around the centre of the frame's lit content rather
   than the origin."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.shape :as shape]))


(defn- lit [x y] [x y 1.0 1.0 1.0])

(defn- dark [x y] [x y 0.0 0.0 0.0])

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(defn- xy-close? [expected actual]
  (and (= (count expected) (count actual))
       (every? true? (map (fn [[ex ey] [ax ay]] (and (close? ex ax) (close? ey ay)))
                          expected actual))))

(defn- smart-size [frame params]
  (effects/apply-effect-chain frame {:effects [{:effect-id :smart-size :enabled? true :params params}]}))

;; A square of side 0.4 centred on (0.5, 0.5), led in by a blanked point at the origin
(def ^:private off-centre-square
  [(dark 0.0 0.0)
   (lit 0.3 0.3) (lit 0.7 0.3) (lit 0.7 0.7) (lit 0.3 0.7)])

(deftest content-center-test
  (testing "the bounding box centre of the lit points, ignoring blanked travel"
    (let [[cx cy] (shape/content-center off-centre-square)]
      (is (close? 0.5 cx))
      (is (close? 0.5 cy))))
  (testing "all points when none is lit"
    (is (= [0.0 0.5] (shape/content-center [(dark -1.0 0.0) (dark 1.0 1.0)]))))
  (testing "an empty frame"
    (is (= [0.0 0.0] (shape/content-center [])))))

(deftest smart-size-scales-around-content-test
  (testing "halving shrinks the square towards its own centre, not the frame's"
    (is (xy-close? [[0.25 0.25] [0.4 0.4] [0.6 0.4] [0.6 0.6] [0.4 0.6]]
                   (smart-size off-centre-square {:x-scale 0.5 :y-scale 0.5}))))
  (testing "colours are kept"
    (is (= [0.0 0.0 0.0] (subvec (first (smart-size off-centre-square {:x-scale 0.5 :y-scale 0.5})) 2))))
  (testing "a centred frame scales as Scale does"
    (let [frame [(lit -0.5 -0.5) (lit 0.5 -0.5) (lit 0.5 0.5) (lit -0.5 0.5)]
          scale (effects/apply-effect-chain frame {:effects [{:effect-id :scale :enabled? true
                                                              :params {:x-scale 0.5 :y-scale 2.0}}]})]
      (is (xy-close? scale (smart-size frame {:x-scale 0.5 :y-scale 2.0})))))
  (testing "defaults leave the frame as it is"
    (is (xy-close? off-centre-square (smart-size off-centre-square {})))))

(deftest smart-size-in-a-chain-test
  (testing "effects after it get the scaled points"
    (let [out (effects/apply-effect-chain
               off-centre-square
               {:effects [{:effect-id :smart-size :enabled? true :params {:x-scale 0.5 :y-scale 0.5}}
                          {:effect-id :translate :enabled? true :params {:x -0.5 :y -0.5}}]})]
      (is (xy-close? [[-0.25 -0.25] [-0.1 -0.1] [0.1 -0.1] [0.1 0.1] [-0.1 0.1]] out)))))
