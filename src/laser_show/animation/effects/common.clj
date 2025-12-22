(ns laser-show.animation.effects.common
  "Common utility functions shared across effect implementations.
   Extracted to avoid code duplication.")

;; ============================================================================
;; Byte Clamping
;; ============================================================================

(defn clamp-byte
  "Clamp a value to valid byte range (0-255).
   Used for color channel values to prevent overflow."
  [v]
  (max 0 (min 255 (int v))))

;; ============================================================================
;; Coordinate Utilities
;; ============================================================================

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
