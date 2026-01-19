(ns laser-show.idn.output-config
  "Configuration for IDN stream output format.
   
   IDN-Stream supports various data precisions. This module allows
   configuring the output bit depth for both position (XY) and color (RGB).
   
   Supported configurations:
   - Color: 8-bit (0-255) or 16-bit (0-65535)
   - XY Position: 8-bit (0-255) or 16-bit (-32768 to 32767)
   
   The default configuration is 16-bit for both color and position (maximum precision).
   Use standard-config for ISP-DB25 compatibility (8-bit color, 16-bit XY)."
  (:require [laser-show.common.util :as u]))


;; Bit Depth Constants


(def ^:const BIT_DEPTH_8 8)
(def ^:const BIT_DEPTH_16 16)


;; Output Configuration Record


(defrecord OutputConfig
  [color-bit-depth    ; 8 or 16
   xy-bit-depth])     ; 8 or 16


;; Predefined Configurations


(def default-config
  "Default configuration: 16-bit color, 16-bit XY (maximum precision).
   Use standard-config for ISP-DB25 compatibility."
  (->OutputConfig BIT_DEPTH_16 BIT_DEPTH_16))

(def standard-config
  "Standard ISP-DB25 configuration: 8-bit color, 16-bit XY.
   Use this for maximum compatibility with existing laser hardware."
  (->OutputConfig BIT_DEPTH_8 BIT_DEPTH_16))

(def compact-config
  "Compact configuration: 8-bit color, 8-bit XY.
   Smallest packet size, but reduced position precision."
  (->OutputConfig BIT_DEPTH_8 BIT_DEPTH_8))




;; Configuration Factory Functions


(defn make-config
  "Create an OutputConfig with the specified bit depths.
   
   Parameters:
   - color-bit-depth: 8 or 16 (default 8)
   - xy-bit-depth: 8 or 16 (default 16)"
  ([]
   default-config)
  ([color-bit-depth xy-bit-depth]
   {:pre [(contains? #{8 16} color-bit-depth)
          (contains? #{8 16} xy-bit-depth)]}
   (->OutputConfig color-bit-depth xy-bit-depth)))


;; Packet Size Calculations


(defn bytes-per-color
  "Get the number of bytes per color channel for the given config."
  [{:keys [color-bit-depth]}]
  (if (= color-bit-depth BIT_DEPTH_16) 2 1))

(defn bytes-per-xy
  "Get the number of bytes per XY coordinate for the given config."
  [{:keys [xy-bit-depth]}]
  (if (= xy-bit-depth BIT_DEPTH_16) 2 1))

(defn bytes-per-sample
  "Calculate bytes per sample for given config.
   
   Sample consists of: X + Y + R + G + B
   Each component can be 1 or 2 bytes depending on bit depth."
  [{:keys [color-bit-depth xy-bit-depth] :as config}]
  (+ (* 2 (bytes-per-xy config))     ; X + Y
     (* 3 (bytes-per-color config)))) ; R + G + B


;; Value Conversion Functions


(defn normalized->output-xy
  "Convert normalized coordinate (-1.0 to 1.0) to output format.
   
   For 16-bit: maps to -32768 to 32767 (signed)
   For 8-bit: maps to 0 to 255 (unsigned, -1.0->0, 0.0->128, 1.0->255)"
  [value bit-depth]
  (let [clamped (u/clamp (double value) -1.0 1.0)]
    (if (= bit-depth BIT_DEPTH_16)
      ;; 16-bit signed: -1.0 -> -32767, 0.0 -> 0, 1.0 -> 32767
      (short (* clamped 32767))
      ;; 8-bit unsigned: -1.0 -> 0, 0.0 -> 128, 1.0 -> 255
      (int (* (+ clamped 1.0) 127.5)))))

(defn normalized->output-color
  "Convert normalized color (0.0 to 1.0) to output format.
   
   For 16-bit: maps to 0 to 65535
   For 8-bit: maps to 0 to 255"
  [value bit-depth]
  (let [clamped (u/clamp (double value) 0.0 1.0)]
    (if (= bit-depth BIT_DEPTH_16)
      (int (* clamped 65535))
      (int (* clamped 255)))))


;; Point Conversion


(defn point->output-values
  "Convert a normalized LaserPoint to output values based on config.
   
   Returns a map with:
   - :x - X coordinate in output format
   - :y - Y coordinate in output format
   - :r - Red in output format
   - :g - Green in output format
   - :b - Blue in output format"
  [point {:keys [color-bit-depth xy-bit-depth]}]
  {:x (normalized->output-xy (:x point) xy-bit-depth)
   :y (normalized->output-xy (:y point) xy-bit-depth)
   :r (normalized->output-color (:r point) color-bit-depth)
   :g (normalized->output-color (:g point) color-bit-depth)
   :b (normalized->output-color (:b point) color-bit-depth)})


;; Configuration Queries

(defn config-name
  "Get a human-readable name for the configuration."
  [{:keys [color-bit-depth xy-bit-depth]}]
  (str xy-bit-depth "-bit XY, " color-bit-depth "-bit color"))

