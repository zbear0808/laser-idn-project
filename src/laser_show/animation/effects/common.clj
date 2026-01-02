(ns laser-show.animation.effects.common
  "Common utility functions shared across effect implementations.
   Extracted to avoid code duplication.
   
   NOTE: Colors are now NORMALIZED (0.0-1.0), not 8-bit (0-255).
   Use clamp-normalized for color values, not clamp-byte.")


;; Normalized Value Clamping (for colors 0.0-1.0)


(defn clamp-normalized
  "Clamp a value to valid normalized range (0.0-1.0).
   Used for color channel values."
  [v]
  (max 0.0 (min 1.0 (double v))))


;; Legacy Byte Clamping (deprecated, use clamp-normalized)


(defn clamp-byte
  "Clamp a value to valid byte range (0-255).
   DEPRECATED: Colors are now normalized. Use clamp-normalized.
   This function is kept for backward compatibility only."
  [v]
  (max 0 (min 255 (int v))))


;; Coordinate Utilities


(defn clamp
  "Clamp a value to the given range."
  [v min-val max-val]
  (max min-val (min max-val v)))

(defn normalize-coord
  "Convert a 16-bit signed coordinate (-32768 to 32767) to normalized [-1.0, 1.0] range."
  [coord]
  (/ (double coord) 32767.0))

(defn denormalize-coord
  "Convert a normalized [-1.0, 1.0] coordinate to 16-bit signed range."
  [coord]
  (short (Math/round (* (clamp (double coord) -1.0 1.0) 32767.0))))


;; Blanked Point Detection


(defn blanked?
  "Check if a point is blanked (all colors near zero).
   Works with normalized colors (0.0-1.0)."
  [{:keys [r g b]}]
  (let [epsilon 1e-6]
    (and (< r epsilon) (< g epsilon) (< b epsilon))))
