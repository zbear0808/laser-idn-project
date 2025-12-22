(ns laser-show.ui.colors-test
  "Unit tests for UI color helper functions."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.ui.colors :as colors])
  (:import [java.awt Color]))

;; ============================================================================
;; Color Construction Tests
;; ============================================================================

(deftest rgb-test
  (testing "Creates Color from RGB values"
    (let [color (colors/rgb 255 128 64)]
      (is (instance? Color color))
      (is (= [255 128 64] [(.getRed color) (.getGreen color) (.getBlue color)])))
    (let [color-alpha (colors/rgb 255 128 64 200)]
      (is (= 200 (.getAlpha color-alpha))))))

(deftest rgb-vec-conversion-test
  (testing "Converts between vectors and Colors"
    (let [original [100 150 200]
          color (colors/rgb-vec->color original)
          back (colors/color->rgb-vec color)]
      (is (instance? Color color))
      (is (= original back)))))

;; ============================================================================
;; Darken/Lighten Tests
;; ============================================================================

(deftest darken-test
  (testing "Darkens colors correctly"
    (let [color (Color. 200 100 50)]
      (is (= [200 100 50] (colors/color->rgb-vec (colors/darken color 0))))
      (is (= [0 0 0] (colors/color->rgb-vec (colors/darken color 1))))
      (is (= [100 50 25] (colors/color->rgb-vec (colors/darken color 0.5)))))))

(deftest lighten-test
  (testing "Lightens colors correctly"
    (let [color (Color. 100 100 100)]
      (is (= [100 100 100] (colors/color->rgb-vec (colors/lighten color 0))))
      (is (= [255 255 255] (colors/color->rgb-vec (colors/lighten color 1))))
      (let [lightened (colors/lighten color 0.5)]
        (is (> (.getRed lightened) 100))
        (is (< (.getRed lightened) 255))))))

;; ============================================================================
;; Category Color Lookup Tests
;; ============================================================================

(deftest get-category-color-test
  (testing "Returns correct colors for categories"
    (is (= colors/category-geometric (colors/get-category-color :geometric)))
    (is (= colors/category-beam (colors/get-category-color :beam)))
    (is (= colors/cell-assigned (colors/get-category-color :unknown)))))

(deftest get-category-color-vec-test
  (testing "Returns correct vectors for categories"
    (is (= [0 200 255] (colors/get-category-color-vec :geometric)))
    (is (= [255 100 0] (colors/get-category-color-vec :beam)))
    (is (= [100 100 100] (colors/get-category-color-vec :unknown)))))
