(ns laser-show.idn.output-config-test
  "Tests for IDN output configuration."
  (:require [clojure.test :refer [deftest testing is are]]
            [laser-show.idn.output-config :as oc]))


;;; ============================================================
;;; Configuration Creation Tests
;;; ============================================================


(deftest test-default-config
  (testing "Default configuration values"
    (is (= 8 (:color-bit-depth oc/default-config)))
    (is (= 16 (:xy-bit-depth oc/default-config)))))

(deftest test-high-precision-config
  (testing "High precision configuration values"
    (is (= 16 (:color-bit-depth oc/high-precision-config)))
    (is (= 16 (:xy-bit-depth oc/high-precision-config)))))

(deftest test-compact-config
  (testing "Compact configuration values"
    (is (= 8 (:color-bit-depth oc/compact-config)))
    (is (= 8 (:xy-bit-depth oc/compact-config)))))

(deftest test-make-config
  (testing "Custom configuration creation"
    (let [config (oc/make-config 16 8)]
      (is (= 16 (:color-bit-depth config)))
      (is (= 8 (:xy-bit-depth config))))))

(deftest test-make-config-validation
  (testing "Configuration validation rejects invalid bit depths"
    (is (thrown? AssertionError (oc/make-config 12 16)))
    (is (thrown? AssertionError (oc/make-config 8 10)))))


;;; ============================================================
;;; Bytes Per Sample Tests
;;; ============================================================


(deftest test-bytes-per-sample
  (testing "Bytes per sample calculation"
    ;; 16-bit XY (4 bytes) + 8-bit RGB (3 bytes) = 7 bytes
    (is (= 7 (oc/bytes-per-sample oc/default-config)))
    ;; 16-bit XY (4 bytes) + 16-bit RGB (6 bytes) = 10 bytes
    (is (= 10 (oc/bytes-per-sample oc/high-precision-config)))
    ;; 8-bit XY (2 bytes) + 8-bit RGB (3 bytes) = 5 bytes
    (is (= 5 (oc/bytes-per-sample oc/compact-config)))
    ;; 8-bit XY (2 bytes) + 16-bit RGB (6 bytes) = 8 bytes
    (is (= 8 (oc/bytes-per-sample oc/high-color-config)))))


;;; ============================================================
;;; Coordinate Conversion Tests
;;; ============================================================


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


;;; ============================================================
;;; Color Conversion Tests
;;; ============================================================


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


;;; ============================================================
;;; Point Conversion Tests
;;; ============================================================


(deftest test-point-to-output-values
  (testing "Full point conversion with default config"
    (let [point {:x 0.5 :y -0.5 :r 1.0 :g 0.5 :b 0.0}
          output (oc/point->output-values point oc/default-config)]
      ;; 16-bit XY
      (is (> (:x output) 16000))
      (is (< (:y output) -16000))
      ;; 8-bit RGB
      (is (= 255 (:r output)))
      (is (< (Math/abs (- 127 (:g output))) 2))
      (is (= 0 (:b output)))))
  
  (testing "Full point conversion with high-precision config"
    (let [point {:x 0.0 :y 0.0 :r 0.5 :g 0.5 :b 0.5}
          output (oc/point->output-values point oc/high-precision-config)]
      ;; 16-bit XY
      (is (= 0 (:x output)))
      (is (= 0 (:y output)))
      ;; 16-bit RGB
      (is (< (Math/abs (- 32767 (:r output))) 2))
      (is (< (Math/abs (- 32767 (:g output))) 2))
      (is (< (Math/abs (- 32767 (:b output))) 2)))))


;;; ============================================================
;;; Config Query Tests
;;; ============================================================


(deftest test-high-precision-queries
  (testing "High precision configuration queries"
    (is (not (oc/high-precision-color? oc/default-config)))
    (is (oc/high-precision-xy? oc/default-config))
    (is (oc/high-precision-color? oc/high-precision-config))
    (is (oc/high-precision-xy? oc/high-precision-config))
    (is (not (oc/high-precision-color? oc/compact-config)))
    (is (not (oc/high-precision-xy? oc/compact-config)))))

(deftest test-config-name
  (testing "Configuration name generation"
    (is (= "16-bit XY, 8-bit color" (oc/config-name oc/default-config)))
    (is (= "16-bit XY, 16-bit color" (oc/config-name oc/high-precision-config)))
    (is (= "8-bit XY, 8-bit color" (oc/config-name oc/compact-config)))))
