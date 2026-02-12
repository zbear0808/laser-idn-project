(ns laser-show.services.frame-concatenation-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.services.frame-service :as sut]
            [laser-show.animation.types :as t]))

(def p1 [0.1 0.1 1.0 0.0 0.0])
(def p2 [0.2 0.2 0.0 1.0 0.0])
(def p3 [0.3 0.3 0.0 0.0 1.0])
(def p4 [0.4 0.4 1.0 1.0 1.0])

(deftest concatenate-frames-test
  (testing "returns nil for empty input"
    (is (nil? (sut/concatenate-frames [] 0)))
    (is (nil? (sut/concatenate-frames nil 0))))

  (testing "returns single frame unchanged"
    (let [frame [p1 p2]
          result (sut/concatenate-frames [frame] 0)]
      (is (= frame result))))

  (testing "concatenates two frames with blanking"
    (let [frame1 [p1 p2]
          frame2 [p3 p4]
          result (sut/concatenate-frames [frame1 frame2] 0)]
      ;; Expected structure:
      ;; frame1 points (2)
      ;; blanking at end of frame1 (1)
      ;; blanking at start of frame2 (1)
      ;; frame2 points (2)
      ;; Total: 6 points

      (is (= 6 (count result)))

      ;; First two points are frame1
      (is (= p1 (nth result 0)))
      (is (= p2 (nth result 1)))

      ;; Blanking point 1: at p2's position, but black
      (let [b1 (nth result 2)]
        (is (= (p2 t/X) (b1 t/X)))
        (is (= (p2 t/Y) (b1 t/Y)))
        (is (t/blanked? b1)))

      ;; Blanking point 2: at p3's position, but black
      (let [b2 (nth result 3)]
        (is (= (p3 t/X) (b2 t/X)))
        (is (= (p3 t/Y) (b2 t/Y)))
        (is (t/blanked? b2)))

      ;; Last two points are frame2
      (is (= p3 (nth result 4)))
      (is (= p4 (nth result 5)))))

  (testing "handles empty intermediate frames"
    (let [frame1 [p1]
          frame2 []
          frame3 [p4]
          result (sut/concatenate-frames [frame1 frame2 frame3] 0)]
      ;; Should behave like concatenating [frame1 frame3]
      ;; p1, blank@p1, blank@p4, p4
      (is (= 4 (count result)))
      (is (= p1 (first result)))
      (is (= p4 (last result))))))
