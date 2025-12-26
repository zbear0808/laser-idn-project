(ns laser-show.ui.colors
  "Color definitions for the laser show UI.
   Provides colors for categories, themes, and conversion utilities.")

;; ============================================================================
;; Category Colors (for preset browser)
;; ============================================================================

(def category-geometric [100 200 100])  ;; Green
(def category-beam [255 150 50])        ;; Orange
(def category-wave [50 200 255])        ;; Cyan
(def category-abstract [200 100 255])   ;; Purple
(def category-text [255 255 100])       ;; Yellow

;; ============================================================================
;; Color Conversion Utilities
;; ============================================================================

(defn color->rgb-vec
  "Convert a color definition to [r g b] vector.
   Accepts:
   - [r g b] vector (passes through)
   - map with :r :g :b keys
   - single hex integer"
  [color]
  (cond
    (vector? color) color
    (map? color) [(:r color 255) (:g color 255) (:b color 255)]
    (integer? color) [(bit-and (bit-shift-right color 16) 0xFF)
                      (bit-and (bit-shift-right color 8) 0xFF)
                      (bit-and color 0xFF)]
    :else [255 255 255]))

(defn rgb-vec->hex
  "Convert [r g b] vector to hex string."
  [[r g b]]
  (format "#%02X%02X%02X" (int r) (int g) (int b)))

(defn rgb-vec->css
  "Convert [r g b] vector to CSS rgb() string."
  [[r g b]]
  (str "rgb(" (int r) "," (int g) "," (int b) ")"))
