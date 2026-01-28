(ns laser-show.animation.presets
  "Pre-built shape presets for the laser show application.
   Each preset has a name, category, parameters spec, and frame generator."
  (:require [laser-show.animation.generators :as gen]
            [laser-show.common.util :as u]))

;; Parameter Types


(defn float-param
  "Define a float parameter."
  [key label default min-val max-val]
  {:key key :label label :type :float :default default :min min-val :max max-val})

(defn int-param
  "Define an integer parameter."
  [key label default min-val max-val]
  {:key key :label label :type :int :default default :min min-val :max max-val})

(defn color-params
  "Define RGB color parameters as separate float components.
   Returns a vector of three param definitions for :red, :green, :blue.
   This format enables per-channel modulator support.
   
   The :color-group? marker allows UI components to detect these as a
   unified color and render a single color picker instead of three sliders."
  [label [r g b]]
  [{:key :red :label label :type :float :default r :min 0.0 :max 1.0 :color-group? true}
   {:key :green :label (str label " G") :type :float :default g :min 0.0 :max 1.0 :color-group? true}
   {:key :blue :label (str label " B") :type :float :default b :min 0.0 :max 1.0 :color-group? true}])


;; Built-in Presets
;; Each preset defines:
;;   :id - unique identifier
;;   :name - display name
;;   :category - grouping category
;;   :parameters - parameter definitions with defaults
;;   :generator - function that takes params map and returns LaserFrame


(def preset-circle
  {:id :circle
   :name "Circle"
   :category :geometric
   :parameters (into [(float-param :radius "Radius" 0.5 0.1 1.0)]
                     (color-params "Color" [1.0 1.0 1.0]))
   :generator gen/circle-frame})

(def preset-square
  {:id :square
   :name "Square"
   :category :geometric
   :parameters (into [(float-param :size "Size" 0.5 0.1 1.0)]
                     (color-params "Color" [1.0 1.0 1.0]))
   :generator gen/square-frame})

(def preset-triangle
  {:id :triangle
   :name "Triangle"
   :category :geometric
   :parameters (into [(float-param :size "Size" 0.5 0.1 1.0)]
                     (color-params "Color" [1.0 1.0 1.0]))
   :generator gen/triangle-frame})

(def preset-spiral
  {:id :spiral
   :name "Spiral"
   :category :geometric
   :parameters (into [(int-param :turns "Turns" 3 1 10)
                      (float-param :start-radius "Inner Radius" 0.1 0.0 0.5)
                      (float-param :end-radius "Outer Radius" 0.5 0.2 1.0)]
                     (color-params "Color" [1.0 1.0 1.0]))
   :generator gen/spiral-frame})

(def preset-star
  {:id :star
   :name "Star"
   :category :geometric
   :parameters (into [(int-param :spikes "Spikes" 5 3 12)
                      (float-param :outer-radius "Outer Radius" 0.5 0.2 1.0)
                      (float-param :inner-radius "Inner Radius" 0.25 0.1 0.5)]
                     (color-params "Color" [1.0 1.0 1.0]))
   :generator gen/star-frame})

(def preset-wave
  {:id :wave
   :name "Wave"
   :category :wave
   :parameters (into [(float-param :amplitude "Amplitude" 0.3 0.1 0.8)
                      (int-param :frequency "Frequency" 2 1 8)]
                     (color-params "Color" [1.0 1.0 1.0]))
   :generator gen/wave-frame})

(def preset-beam-fan
  {:id :beam-fan
   :name "Beam Fan"
   :category :beam
   :parameters (into [(int-param :num-points "Points" 8 2 32)]
                     (color-params "Color" [1.0 1.0 1.0]))
   :generator gen/beam-fan-frame})

(def preset-horizontal-line
  {:id :horizontal-line
   :name "Horizontal Line"
   :category :beam
   :parameters (into [(float-param :length "Length" 1.0 0.1 2.0)]
                     (color-params "Color" [1.0 1.0 1.0]))
   :generator gen/horizontal-line-frame})

;; Preset Registry


(def all-presets
  "Vector of all available presets."
  [preset-circle
   preset-square
   preset-triangle
   preset-spiral
   preset-star
   preset-wave
   preset-beam-fan
   preset-horizontal-line])

(def presets-by-id
  "Map of preset ID to preset definition."
  (u/map-into :id identity all-presets))

(defn get-preset
  "Get a preset by ID."
  [preset-id]
  (get presets-by-id preset-id))

(defn get-default-params
  "Get the default parameters for a preset as a map."
  [preset-id]
  (when-let [preset (get-preset preset-id)]
    (u/map-into :key :default (:parameters preset))))

(defn generate-frame
  "Generate a frame from a preset with default parameters."
  [preset-id]
  (when-let [preset (get-preset preset-id)]
    (let [params (get-default-params preset-id)]
      ((:generator preset) params))))

(defn generate-frame-with-params
  "Generate a frame from a preset with custom parameters merged over defaults."
  [preset-id params]
  (when-let [preset (get-preset preset-id)]
    (let [default-params (get-default-params preset-id)
          merged-params (merge default-params params)]
      ((:generator preset) merged-params))))
