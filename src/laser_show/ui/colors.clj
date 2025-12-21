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

(def cell-colors
  "Map of cell state keywords to colors."
  {:empty cell-empty
   :assigned cell-assigned
   :selected cell-selected
   :active cell-active
   :queued cell-queued})

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
;; Preview Panel Colors
;; ============================================================================

(def preview-background Color/BLACK)
(def preview-grid-lines (rgb 30 30 30))
