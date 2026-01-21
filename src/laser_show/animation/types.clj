(ns laser-show.animation.types
  "Core types for laser animation system.
   Defines LaserPoint (5-element vector) and LaserFrame (vector of points).
   
   Internal Data Format
   ====================
   
   LaserPoint is a 5-element vector [x y r g b] using NORMALIZED VALUES:
   - Coordinates (x, y): -1.0 to 1.0 (normalized)
   - Colors (r, g, b): 0.0 to 1.0 (normalized)
   
   LaserFrame is simply a vector of points - no wrapper needed!
   
   This allows effects and calculations to work with full floating-point
   precision. Conversion to hardware-specific formats (8-bit or 16-bit)
   happens at the IDN output stage.
   
   IDN-Stream Specification Compliance (Revision 002, July 2025)
   =============================================================
   
   At output time, LaserPoint values are converted to IDN-Stream format:
   
   | LaserPoint Index | IDN-Stream Range    | Configurable |
   |------------------|---------------------|--------------|
   | X (0)            | 8-bit or 16-bit     | Yes          |
   | Y (1)            | 8-bit or 16-bit     | Yes          |
   | R (2)            | 8-bit or 16-bit     | Yes          |
   | G (3)            | 8-bit or 16-bit     | Yes          |
   | B (4)            | 8-bit or 16-bit     | Yes          |
   
   Coordinate System (Section 3.4.7):
   - X: Positive = right, Negative = left (front projection)
   - Y: Positive = up, Negative = down
   - Origin (0,0) is at center of projection area
   
   Blanking (Section 3.4.11):
   - Blanking is indicated by r=g=b=0.0 (all color intensities zero)
   - There is no separate 'intensity' or 'blanking' field
   - Use `blanked-point` to create invisible points for beam repositioning
   
   Color Wavelengths (Section 3.4.10):
   - Red: 638nm (TAG_COLOR_RED = 0x527E)
   - Green: 532nm (TAG_COLOR_GREEN = 0x5214)
   - Blue: 460nm (TAG_COLOR_BLUE = 0x51CC)
   
   LaserFrame -> IDN-Stream Frame Samples Chunk (Section 6.2)
   ----------------------------------------------------------
   LaserFrame (vector of points) maps to IDN-Stream frame samples data chunk.
   
   Frame Structure Notes:
   - First point is the start position (should be blanked for repositioning)
   - Last point is the end position
   - Consumer handles movement between frames
   - Empty frame (no points) voids the frame buffer"
  (:require [laser-show.common.util :as u]))


;; Point index constants for readability and performance
(def ^:const X 0)
(def ^:const Y 1)
(def ^:const R 2)
(def ^:const G 3)
(def ^:const B 4)


;; Point Construction Helpers


(defn make-point
  "Create a LaserPoint vector with normalized coordinates and colors.
   
   All values must be normalized:
   - x, y: range [-1.0, 1.0], clamped if outside
   - r, g, b: range [0.0, 1.0], clamped if outside
   
   Per IDN-Stream spec, blanking is indicated by r=g=b=0.0."
  ([x y]
   (make-point x y 1.0 1.0 1.0))
  ([x y r g b]
   [(u/clamp (double x) -1.0 1.0)
    (u/clamp (double y) -1.0 1.0)
    (u/clamp (double r) 0.0 1.0)
    (u/clamp (double g) 0.0 1.0)
    (u/clamp (double b) 0.0 1.0)]))

(defn blanked-point
  "Create a blanked (invisible) point for beam repositioning.
   Per IDN-Stream spec Section 3.4.11, blanking is done by setting all color intensities to 0."
  [x y]
  [(u/clamp (double x) -1.0 1.0)
   (u/clamp (double y) -1.0 1.0)
   0.0 0.0 0.0])

(defn blanked?
  "Check if a point is blanked (invisible).
   A point is blanked when all color channels are zero (or very close to zero)."
  [point]
  (let [epsilon 1e-6]
    (and (< (point R) epsilon)
         (< (point G) epsilon)
         (< (point B) epsilon))))


;; Point Update Helpers (for effects operating on 5-element vectors)


(defn update-point-xy
 "Update x and y of a point, preserving color values.
  Clamps coordinates to [-1.0, 1.0]."
 [pt new-x new-y]
 [(u/clamp (double new-x) -1.0 1.0)
  (u/clamp (double new-y) -1.0 1.0)
  (pt R) (pt G) (pt B)])

(defn update-point-rgb
 "Update r, g, b of a point, preserving position.
  Clamps colors to [0.0, 1.0]."
 [pt new-r new-g new-b]
 [(pt X) (pt Y)
  (u/clamp (double new-r) 0.0 1.0)
  (u/clamp (double new-g) 0.0 1.0)
  (u/clamp (double new-b) 0.0 1.0)])

(defn update-point-all
 "Update all values of a point with clamping.
  Coordinates clamped to [-1.0, 1.0], colors to [0.0, 1.0]."
 [new-x new-y new-r new-g new-b]
 [(u/clamp (double new-x) -1.0 1.0)
  (u/clamp (double new-y) -1.0 1.0)
  (u/clamp (double new-r) 0.0 1.0)
  (u/clamp (double new-g) 0.0 1.0)
  (u/clamp (double new-b) 0.0 1.0)])


;; Frame Construction Helpers


(defn make-frame
  "Create a LaserFrame (vector of points) from a sequence of points."
  [points]
  (vec points))

(defn empty-frame
  "Create an empty frame."
  []
  [])
