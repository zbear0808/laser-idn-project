(ns laser-show.animation.colors-test
  "Tests for color utilities and conversions.
   
   Tests cover:
   - Normalized color conversions (0.0-1.0 <-> 8-bit <-> 16-bit)
   - HSV color space conversions
   - Rainbow generation
   - Round-trip precision"
  (:require [clojure.test :refer [deftest testing is are]]
            [laser-show.animation.colors :as colors]))



;;; Normalized Color Conversion Tests



(deftest test-normalized-to-8bit
  (testing "Normalized (0.0-1.0) to 8-bit (0-255) conversion"
    (are [normalized expected]
        (= expected (colors/normalized->8bit normalized))
      [0.0 0.0 0.0]       [0 0 0]
      [1.0 1.0 1.0]       [255 255 255]
      [0.5 0.5 0.5]       [127 127 127]
      [1.0 0.0 0.0]       [255 0 0]
      [0.0 1.0 0.0]       [0 255 0]
      [0.0 0.0 1.0]       [0 0 255])))

(deftest test-8bit-to-normalized
  (testing "8-bit (0-255) to normalized (0.0-1.0) conversion"
    (are [rgb8 expected]
        (let [[r g b] (colors/color-8bit->normalized rgb8)]
          (and (< (Math/abs (- r (first expected))) 0.01)
               (< (Math/abs (- g (second expected))) 0.01)
               (< (Math/abs (- b (nth expected 2))) 0.01)))
      [0 0 0]         [0.0 0.0 0.0]
      [255 255 255]   [1.0 1.0 1.0]
      [127 127 127]   [0.498 0.498 0.498]
      [255 0 0]       [1.0 0.0 0.0])))

(deftest test-normalized-to-16bit
  (testing "Normalized (0.0-1.0) to 16-bit (0-65535) conversion"
    (are [normalized expected]
        (= expected (colors/normalized->16bit normalized))
      [0.0 0.0 0.0]       [0 0 0]
      [1.0 1.0 1.0]       [65535 65535 65535]
      [0.5 0.5 0.5]       [32767 32767 32767]
      [1.0 0.0 0.0]       [65535 0 0])))

(deftest test-16bit-to-normalized
  (testing "16-bit (0-65535) to normalized (0.0-1.0) conversion"
    (are [rgb16 expected]
        (let [[r g b] (colors/color-16bit->normalized rgb16)]
          (and (< (Math/abs (- r (first expected))) 0.0001)
               (< (Math/abs (- g (second expected))) 0.0001)
               (< (Math/abs (- b (nth expected 2))) 0.0001)))
      [0 0 0]             [0.0 0.0 0.0]
      [65535 65535 65535] [1.0 1.0 1.0]
      [32767 32767 32767] [0.5 0.5 0.5])))




;;; HSV Conversion Tests



(deftest test-hsv-to-normalized
  (testing "HSV to normalized RGB conversion"
    ;; Red (hue 0)
    (let [[r g b] (colors/hsv->normalized 0 1.0 1.0)]
      (is (> r 0.99) "Red channel should be ~1.0")
      (is (< g 0.01) "Green channel should be ~0.0")
      (is (< b 0.01) "Blue channel should be ~0.0"))
    ;; Green (hue 120)
    (let [[r g b] (colors/hsv->normalized 120 1.0 1.0)]
      (is (< r 0.01) "Red channel should be ~0.0")
      (is (> g 0.99) "Green channel should be ~1.0")
      (is (< b 0.01) "Blue channel should be ~0.0"))
    ;; Blue (hue 240)
    (let [[r g b] (colors/hsv->normalized 240 1.0 1.0)]
      (is (< r 0.01) "Red channel should be ~0.0")
      (is (< g 0.01) "Green channel should be ~0.0")
      (is (> b 0.99) "Blue channel should be ~1.0"))
    ;; White (any hue, s=0, v=1)
    (let [[r g b] (colors/hsv->normalized 0 0.0 1.0)]
      (is (> r 0.99) "Red channel should be ~1.0")
      (is (> g 0.99) "Green channel should be ~1.0")
      (is (> b 0.99) "Blue channel should be ~1.0"))
    ;; Black (any hue, any s, v=0)
    (let [[r g b] (colors/hsv->normalized 0 1.0 0.0)]
      (is (< r 0.01) "Red channel should be ~0.0")
      (is (< g 0.01) "Green channel should be ~0.0")
      (is (< b 0.01) "Blue channel should be ~0.0"))))

(deftest test-normalized-to-hsv
  (testing "Normalized RGB to HSV conversion"
    ;; Red
    (let [[h s v] (colors/normalized->hsv 1.0 0.0 0.0)]
      (is (or (< h 1) (> h 359)) "Hue should be ~0")
      (is (> s 0.99) "Saturation should be ~1.0")
      (is (> v 0.99) "Value should be ~1.0"))
    ;; Green
    (let [[h s v] (colors/normalized->hsv 0.0 1.0 0.0)]
      (is (and (> h 119) (< h 121)) "Hue should be ~120")
      (is (> s 0.99) "Saturation should be ~1.0")
      (is (> v 0.99) "Value should be ~1.0"))
    ;; Blue
    (let [[h s v] (colors/normalized->hsv 0.0 0.0 1.0)]
      (is (and (> h 239) (< h 241)) "Hue should be ~240")
      (is (> s 0.99) "Saturation should be ~1.0")
      (is (> v 0.99) "Value should be ~1.0"))))

(deftest test-hsv-roundtrip
  (testing "HSV to RGB and back preserves values"
    (doseq [h [0 60 120 180 240 300]
            s [0.0 0.5 1.0]
            v [0.0 0.5 1.0]]
      (let [[r g b] (colors/hsv->normalized h s v)
            [h2 s2 v2] (colors/normalized->hsv r g b)]
        ;; For black (v=0), both hue and saturation are undefined
        ;; For desaturated colors (s=0), hue is undefined
        (when (and (> v 0.01) (> s 0.01))
          (is (< (Math/abs (- (mod h 360) (mod h2 360))) 1.0)
              (str "Hue should roundtrip: " h " vs " h2)))
        ;; Saturation can only roundtrip when v > 0 (non-black)
        (when (> v 0.01)
          (is (< (Math/abs (- s s2)) 0.01)
              (str "Saturation should roundtrip: " s " vs " s2)))
        (is (< (Math/abs (- v v2)) 0.01)
            (str "Value should roundtrip: " v " vs " v2))))))



;;; Rainbow Generation Tests



(deftest test-rainbow-normalized
  (testing "Rainbow color generation (normalized)"
    ;; Position 0.0 should be red
    (let [[r g b] (colors/rainbow-normalized 0.0)]
      (is (> r 0.99) "Position 0 red channel")
      (is (< g 0.01) "Position 0 green channel")
      (is (< b 0.01) "Position 0 blue channel"))
    ;; Position 0.333 should be green
    (let [[r g b] (colors/rainbow-normalized 0.333)]
      (is (< r 0.1) "Position 0.333 red channel")
      (is (> g 0.9) "Position 0.333 green channel")
      (is (< b 0.1) "Position 0.333 blue channel"))
    ;; Position 0.666 should be blue
    (let [[r g b] (colors/rainbow-normalized 0.666)]
      (is (< r 0.1) "Position 0.666 red channel")
      (is (< g 0.1) "Position 0.666 green channel")
      (is (> b 0.9) "Position 0.666 blue channel"))
    ;; Position 1.0 should be back to red
    (let [[r g b] (colors/rainbow-normalized 1.0)]
      (is (> r 0.99) "Position 1.0 red channel")
      (is (< g 0.01) "Position 1.0 green channel")
      (is (< b 0.01) "Position 1.0 blue channel"))))



;;; Color Interpolation Tests



(deftest test-lerp-color-normalized
  (testing "Linear interpolation of normalized colors"
    ;; Black to white at t=0.5 should be gray
    (let [[r g b] (colors/lerp-color-normalized [0.0 0.0 0.0] [1.0 1.0 1.0] 0.5)]
      (is (< (Math/abs (- r 0.5)) 0.01))
      (is (< (Math/abs (- g 0.5)) 0.01))
      (is (< (Math/abs (- b 0.5)) 0.01)))
    ;; Red to blue at t=0 should be red
    (let [[r g b] (colors/lerp-color-normalized [1.0 0.0 0.0] [0.0 0.0 1.0] 0.0)]
      (is (> r 0.99))
      (is (< b 0.01)))
    ;; Red to blue at t=1 should be blue
    (let [[r g b] (colors/lerp-color-normalized [1.0 0.0 0.0] [0.0 0.0 1.0] 1.0)]
      (is (< r 0.01))
      (is (> b 0.99)))))



;;; Precision Round-trip Tests



(deftest test-8bit-normalized-roundtrip
  (testing "8-bit to normalized and back preserves values"
    ;; Test sample values instead of exhaustive
    (doseq [original [[0 0 0] [255 255 255] [127 127 127] [255 0 0] [0 255 0] [0 0 255]]]
      (let [normalized (colors/color-8bit->normalized original)
            back (colors/normalized->8bit normalized)]
        (is (= original back)
            (str "8-bit roundtrip failed for " original))))))

(deftest test-16bit-normalized-roundtrip
  (testing "16-bit to normalized and back preserves values"
    ;; Test sample values instead of exhaustive
    (doseq [original [[0 0 0] [65535 65535 65535] [32767 32767 32767] [65535 0 0] [0 65535 0] [0 0 65535]]]
      (let [normalized (colors/color-16bit->normalized original)
            back (colors/normalized->16bit normalized)]
        (is (= original back)
            (str "16-bit roundtrip failed for " original))))))

(deftest test-precision-preservation
  (testing "Normalized doubles have enough precision for 16-bit"
    ;; Test that all 16-bit values can be represented distinctly
    (is (= 65536
           (count (distinct (map #(first (colors/color-16bit->normalized [% 0 0]))
                                 (range 65536)))))
        "All 65536 16-bit red values should map to distinct normalized values")))
