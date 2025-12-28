(ns laser-show.animation.colors-test
  "Tests for color utilities and conversions."
  (:require [clojure.test :refer [deftest testing]]
            [laser-show.animation.colors :as colors]))

;; ============================================================================
;; Bit Depth Conversion Tests
;; ============================================================================

(deftest test-8bit-ilda-conversion
  (testing "8-bit to ILDA conversion"
    ;; TODO: Add tests for rgb8->ilda
    ;; Should test: [255 128 0] -> [65535 32896 0]
    ))

(deftest test-ilda-8bit-conversion
  (testing "ILDA to 8-bit conversion"
    ;; TODO: Add tests for ilda->8bit
    ;; Should test: [65535 32896 0] -> [255 128 0]
    ))

;; ============================================================================
;; HSV Conversion Tests
;; ============================================================================

(deftest test-hsv-rgb-roundtrip
  (testing "HSV to RGB and back preserves values"
    ;; TODO: Add roundtrip tests
    ;; Test colors: red, green, blue, white, gray
    ))

;; ============================================================================
;; Rainbow Generation Tests
;; ============================================================================

(deftest test-rainbow-generation
  (testing "Rainbow color generation"
    ;; TODO: Test rainbow function at key positions
    ;; 0.0 -> red, 0.33 -> green, 0.66 -> blue, 1.0 -> red
    ))
