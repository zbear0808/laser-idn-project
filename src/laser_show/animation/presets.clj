(ns laser-show.animation.presets
  "Pre-built animation presets for the laser show application.
   Each preset is a map containing name, category, parameters spec, and generator."
  (:require [laser-show.animation.types :as t]
            [laser-show.animation.generators :as gen]
            [laser-show.ui.colors :as ui-colors]))

;; ============================================================================
;; Preset Categories
;; ============================================================================

(def categories
  "Category definitions with names and colors.
   Colors are stored as [r g b] vectors for compatibility."
  {:geometric {:name "Geometric" :color (ui-colors/color->rgb-vec ui-colors/category-geometric)}
   :beam      {:name "Beams" :color (ui-colors/color->rgb-vec ui-colors/category-beam)}
   :wave      {:name "Waves" :color (ui-colors/color->rgb-vec ui-colors/category-wave)}
   :abstract  {:name "Abstract" :color (ui-colors/color->rgb-vec ui-colors/category-abstract)}
   :text      {:name "Text" :color (ui-colors/color->rgb-vec ui-colors/category-text)}})

;; ============================================================================
;; Parameter Types
;; ============================================================================

(defn float-param
  "Define a float parameter."
  [key label default min-val max-val]
  {:key key :label label :type :float :default default :min min-val :max max-val})

(defn int-param
  "Define an integer parameter."
  [key label default min-val max-val]
  {:key key :label label :type :int :default default :min min-val :max max-val})

(defn color-param
  "Define a color parameter."
  [key label default]
  {:key key :label label :type :color :default default})

(defn enum-param
  "Define an enum parameter."
  [key label options default]
  {:key key :label label :type :enum :options options :default default})

;; ============================================================================
;; Built-in Presets
;; ============================================================================

(def preset-circle
  {:id :circle
   :name "Circle"
   :category :geometric
   :parameters [(float-param :radius "Radius" 0.5 0.1 1.0)
                (color-param :color "Color" [255 255 255])]
   :generator (gen/circle-animation)})

(def preset-square
  {:id :square
   :name "Square"
   :category :geometric
   :parameters [(float-param :size "Size" 0.5 0.1 1.0)
                (color-param :color "Color" [255 255 255])]
   :generator (gen/square-animation)})

(def preset-triangle
  {:id :triangle
   :name "Triangle"
   :category :geometric
   :parameters [(float-param :size "Size" 0.5 0.1 1.0)
                (color-param :color "Color" [255 255 255])]
   :generator (gen/triangle-animation)})

(def preset-spiral
  {:id :spiral
   :name "Spiral"
   :category :geometric
   :parameters [(int-param :turns "Turns" 3 1 10)
                (float-param :start-radius "Inner Radius" 0.1 0.0 0.5)
                (float-param :end-radius "Outer Radius" 0.5 0.2 1.0)
                (color-param :color "Color" [255 255 255])]
   :generator (gen/spiral-animation)})

(def preset-star
  {:id :star
   :name "Star"
   :category :geometric
   :parameters [(int-param :spikes "Spikes" 5 3 12)
                (float-param :outer-radius "Outer Radius" 0.5 0.2 1.0)
                (float-param :inner-radius "Inner Radius" 0.25 0.1 0.5)
                (color-param :color "Color" [255 255 255])]
   :generator (gen/star-animation)})

(def preset-wave
  {:id :wave
   :name "Wave"
   :category :wave
   :parameters [(float-param :amplitude "Amplitude" 0.3 0.1 0.8)
                (int-param :frequency "Frequency" 2 1 8)
                (color-param :color "Color" [0 255 255])]
   :generator (gen/wave-animation)})

(def preset-beam-fan
  {:id :beam-fan
   :name "Beam Fan"
   :category :beam
   :parameters [(float-param :length "Length" 0.8 0.3 1.0)
                (color-param :color "Color" [255 100 0])]
   :generator (gen/beam-fan-animation)})

(def preset-rainbow-circle
  {:id :rainbow-circle
   :name "Rainbow Circle"
   :category :abstract
   :parameters [(float-param :radius "Radius" 0.5 0.1 1.0)]
   :generator (gen/rainbow-circle-animation)})

;; ============================================================================
;; Preset Registry
;; ============================================================================

(def all-presets
  "Vector of all available presets."
  [preset-circle
   preset-square
   preset-triangle
   preset-spiral
   preset-star
   preset-wave
   preset-beam-fan
   preset-rainbow-circle])

(def presets-by-id
  "Map of preset ID to preset definition."
  (into {} (map (juxt :id identity) all-presets)))

(defn get-preset
  "Get a preset by ID."
  [preset-id]
  (get presets-by-id preset-id))

(defn get-presets-by-category
  "Get all presets in a category."
  [category]
  (filter #(= (:category %) category) all-presets))

(defn create-animation-from-preset
  "Create an Animation instance from a preset with default parameters."
  [preset-id]
  (when-let [preset (get-preset preset-id)]
    (let [default-params (into {} (map (fn [p] [(:key p) (:default p)])
                                       (:parameters preset)))]
      (t/make-animation (:name preset)
                        (:generator preset)
                        default-params
                        nil))))

(defn create-animation-with-params
  "Create an Animation instance from a preset with custom parameters."
  [preset-id params]
  (when-let [preset (get-preset preset-id)]
    (let [default-params (into {} (map (fn [p] [(:key p) (:default p)])
                                       (:parameters preset)))
          merged-params (merge default-params params)]
      (t/make-animation (:name preset)
                        (:generator preset)
                        merged-params
                        nil))))
