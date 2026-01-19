(ns laser-show.idn.output-config-test
  "Tests for IDN output configuration."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.idn.output-config :as oc]))

(deftest test-normalized-to-output-xy-16bit
  (testing "Normalized XY to 16-bit output"
    ;; Center (0.0) -> 0
    (is (= 0 (oc/normalized->output-xy 0.0 16)))
    ;; Right edge (1.0) -> 32767
    (is (= 32767 (oc/normalized->output-xy 1.0 16)))
    ;; Left edge (-1.0) -> -32767
    (is (= -32767 (oc/normalized->output-xy -1.0 16)))
    ;; Half (0.5) -> 16383-16384
    (is (< (Math/abs (- 16383 (oc/normalized->output-xy 0.5 16))) 2))))

(deftest test-normalized-to-output-xy-8bit
  (testing "Normalized XY to 8-bit output"
    ;; Center (0.0) -> 127-128
    (is (< (Math/abs (- 127 (oc/normalized->output-xy 0.0 8))) 2))
    ;; Right edge (1.0) -> 255
    (is (= 255 (oc/normalized->output-xy 1.0 8)))
    ;; Left edge (-1.0) -> 0
    (is (= 0 (oc/normalized->output-xy -1.0 8)))))

(deftest test-xy-clamping
  (testing "XY values outside range are clamped"
    (is (= 32767 (oc/normalized->output-xy 2.0 16)))
    (is (= -32767 (oc/normalized->output-xy -2.0 16)))
    (is (= 255 (oc/normalized->output-xy 2.0 8)))
    (is (= 0 (oc/normalized->output-xy -2.0 8)))))



;;; Color Conversion Tests



(deftest test-normalized-to-output-color-16bit
  (testing "Normalized color to 16-bit output"
    (is (= 0 (oc/normalized->output-color 0.0 16)))
    (is (= 65535 (oc/normalized->output-color 1.0 16)))
    (is (< (Math/abs (- 32767 (oc/normalized->output-color 0.5 16))) 2))))

(deftest test-normalized-to-output-color-8bit
  (testing "Normalized color to 8-bit output"
    (is (= 0 (oc/normalized->output-color 0.0 8)))
    (is (= 255 (oc/normalized->output-color 1.0 8)))
    (is (< (Math/abs (- 127 (oc/normalized->output-color 0.5 8))) 2))))

(deftest test-color-clamping
  (testing "Color values outside range are clamped"
    (is (= 65535 (oc/normalized->output-color 2.0 16)))
    (is (= 0 (oc/normalized->output-color -0.5 16)))
    (is (= 255 (oc/normalized->output-color 2.0 8)))
    (is (= 0 (oc/normalized->output-color -0.5 8)))))



;;; Point Conversion Tests



(deftest test-point-to-output-values
  (testing "Full point conversion with default config (16-bit color and XY)"
    (let [point {:x 0.5 :y -0.5 :r 1.0 :g 0.5 :b 0.0}
          output (oc/point->output-values point oc/default-config)]
      ;; 16-bit XY
      (is (> (:x output) 16000))
      (is (< (:y output) -16000))
      ;; 16-bit RGB (default config uses 16-bit color)
      (is (= 65535 (:r output)))
      (is (< (Math/abs (- 32767 (:g output))) 2))
      (is (= 0 (:b output)))))
  
  (testing "Full point conversion with standard config (8-bit color, 16-bit XY)"
    (let [point {:x 0.5 :y -0.5 :r 1.0 :g 0.5 :b 0.0}
          output (oc/point->output-values point oc/standard-config)]
      ;; 16-bit XY
      (is (> (:x output) 16000))
      (is (< (:y output) -16000))
      ;; 8-bit RGB
      (is (= 255 (:r output)))
      (is (< (Math/abs (- 127 (:g output))) 2))
      (is (= 0 (:b output))))))