(ns laser-show.animation.effects.common
  "Common utility functions shared across effect implementations.
   Extracted to avoid code duplication.
   
   NOTE: Colors are now NORMALIZED (0.0-1.0), not 8-bit (0-255).
   Use clamp-normalized for color values, not clamp-byte.
   
   For blanked? checking, use t/blanked? from laser-show.animation.types."
  (:require [laser-show.common.util :as u]))


;; Normalized Value Clamping (for colors 0.0-1.0)


(defn clamp-normalized
  "Clamp a value to valid normalized range (0.0-1.0).
   Used for color channel values."
  [v]
  (u/clamp (double v) 0.0 1.0))
