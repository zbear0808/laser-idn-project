(ns laser-show.ui.colors
  "UI color constants and helpers for the laser show application.
   Defines colors for UI elements like grid cells, backgrounds, and category indicators."
  (:import [java.awt Color]))

;; ============================================================================
;; Color Construction Helpers
;; ============================================================================

(defn rgb
  "Create a Java AWT Color from RGB values (0-255)."
  ([r g b]
   (Color. (int r) (int g) (int b)))
  ([r g b a]
   (Color. (int r) (int g) (int b) (int a))))

(defn rgb-vec->color
  "Convert an [r g b] vector to a Java AWT Color."
  [[r g b]]
  (Color. (int r) (int g) (int b)))

(defn color->rgb-vec
  "Convert a Java AWT Color to an [r g b] vector."
  [^Color color]
  [(.getRed color) (.getGreen color) (.getBlue color)])

(defn darken
  "Darken a color by a factor (0.0 to 1.0)."
  [^Color color factor]
  (let [f (- 1.0 factor)]
    (Color. (int (* (.getRed color) f))
            (int (* (.getGreen color) f))
            (int (* (.getBlue color) f)))))

(defn lighten
  "Lighten a color by a factor (0.0 to 1.0)."
  [^Color color factor]
  (let [r (.getRed color)
        g (.getGreen color)
        b (.getBlue color)]
    (Color. (int (+ r (* (- 255 r) factor)))
            (int (+ g (* (- 255 g) factor)))
            (int (+ b (* (- 255 b) factor))))))

;; ============================================================================
;; UI Theme Colors
;; ============================================================================

(def background-dark (rgb 30 30 30))
(def background-medium (rgb 45 45 45))
(def background-light (rgb 60 60 60))

(def border-dark (rgb 20 20 20))
(def border-light (rgb 80 80 80))
(def border-highlight Color/WHITE)

(def text-primary Color/WHITE)
(def text-secondary (rgb 180 180 180))
(def text-muted (rgb 120 120 120))

;; ============================================================================
;; Grid Cell State Colors
;; ============================================================================

(def cell-empty (rgb 40 40 40))
(def cell-assigned (rgb 60 60 80))
(def cell-selected (rgb 80 80 120))
(def cell-active (rgb 0 200 100))
(def cell-queued (rgb 200 200 0))


;; ============================================================================
;; Preset Category Colors
;; ============================================================================

(def category-geometric (rgb 0 200 255))
(def category-beam (rgb 255 100 0))
(def category-wave (rgb 0 255 100))
(def category-abstract (rgb 255 0 255))
(def category-text (rgb 255 255 0))

(def category-colors
  "Map of category keywords to colors."
  {:geometric category-geometric
   :beam category-beam
   :wave category-wave
   :abstract category-abstract
   :text category-text})

(def category-colors-vec
  "Map of category keywords to [r g b] vectors."
  {:geometric [0 200 255]
   :beam [255 100 0]
   :wave [0 255 100]
   :abstract [255 0 255]
   :text [255 255 0]})

(defn get-category-color
  "Get the Color for a preset category keyword."
  [category]
  (get category-colors category cell-assigned))

(defn get-category-color-vec
  "Get the [r g b] vector for a preset category keyword."
  [category]
  (get category-colors-vec category [100 100 100]))

;; ============================================================================
;; Effect Category Colors
;; ============================================================================

(def effect-category-shape (rgb 70 130 200))
(def effect-category-shape-dim (rgb 40 75 115))
(def effect-category-color (rgb 180 80 180))
(def effect-category-color-dim (rgb 100 45 100))
(def effect-category-intensity (rgb 220 160 50))
(def effect-category-intensity-dim (rgb 125 90 30))
(def effect-category-calibration (rgb 100 100 100))
(def effect-category-calibration-dim (rgb 60 60 60))

(def effect-category-colors
  "Map of effect category keywords to colors."
  {:shape effect-category-shape
   :color effect-category-color
   :intensity effect-category-intensity
   :calibration effect-category-calibration})

(def effect-category-colors-dim
  "Map of effect category keywords to dimmed colors (for disabled effects)."
  {:shape effect-category-shape-dim
   :color effect-category-color-dim
   :intensity effect-category-intensity-dim
   :calibration effect-category-calibration-dim})


(defn get-effect-category-color
  "Get the Color for an effect category keyword.
   If dimmed? is true, returns the dimmed version for disabled effects."
  ([category]
   (get-effect-category-color category false))
  ([category dimmed?]
   (if dimmed?
     (get effect-category-colors-dim category cell-assigned)
     (get effect-category-colors category cell-assigned))))


;; ============================================================================
;; Preview Panel Colors
;; ============================================================================

(def preview-background Color/BLACK)
(def preview-grid-lines (rgb 30 30 30))

;; ============================================================================
;; Modulator Category Colors
;; ============================================================================

(def modulator-category-time (rgb 100 180 255))        ;; Blue - temporal
(def modulator-category-time-dim (rgb 60 110 155))

(def modulator-category-space (rgb 255 140 100))       ;; Orange - spatial
(def modulator-category-space-dim (rgb 155 85 60))

(def modulator-category-animated (rgb 150 255 150))    ;; Green - dynamic
(def modulator-category-animated-dim (rgb 90 155 90))

(def modulator-category-control (rgb 200 200 100))     ;; Yellow - external control
(def modulator-category-control-dim (rgb 120 120 60))

(def modulator-category-colors
  "Map of modulator category keywords to colors."
  {:time modulator-category-time
   :space modulator-category-space
   :animated modulator-category-animated
   :control modulator-category-control})

(def modulator-category-colors-dim
  "Map of modulator category keywords to dimmed colors."
  {:time modulator-category-time-dim
   :space modulator-category-space-dim
   :animated modulator-category-animated-dim
   :control modulator-category-control-dim})

(defn get-modulator-category-color
  "Get the Color for a modulator category keyword.
   If dimmed? is true, returns the dimmed version."
  ([category]
   (get-modulator-category-color category false))
  ([category dimmed?]
   (if dimmed?
     (get modulator-category-colors-dim category cell-assigned)
     (get modulator-category-colors category cell-assigned))))
