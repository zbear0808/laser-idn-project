(ns laser-show.animation.types
  "Core types for laser animation system.
   Defines LaserPoint and LaserFrame.
   
   Internal Data Format
   ====================
   
   LaserPoint uses NORMALIZED VALUES internally for maximum precision:
   - Coordinates (x, y): -1.0 to 1.0 (normalized)
   - Colors (r, g, b): 0.0 to 1.0 (normalized)
   
   This allows effects and calculations to work with full floating-point
   precision. Conversion to hardware-specific formats (8-bit or 16-bit)
   happens at the IDN output stage.
   
   IDN-Stream Specification Compliance (Revision 002, July 2025)
   =============================================================
   
   At output time, LaserPoint values are converted to IDN-Stream format:
   
   | LaserPoint Field | IDN-Stream Range    | Configurable |
   |------------------|---------------------|--------------|
   | :x (-1.0 to 1.0) | 8-bit or 16-bit     | Yes          |
   | :y (-1.0 to 1.0) | 8-bit or 16-bit     | Yes          |
   | :r (0.0 to 1.0)  | 8-bit or 16-bit     | Yes          |
   | :g (0.0 to 1.0)  | 8-bit or 16-bit     | Yes          |
   | :b (0.0 to 1.0)  | 8-bit or 16-bit     | Yes          |
   
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
   Our LaserFrame record maps to IDN-Stream frame samples data chunk:
   
   | LaserFrame Field | IDN-Stream Usage                              |
   |------------------|-----------------------------------------------|
   | :points          | Sample array in Frame Samples chunk           |
   | :timestamp       | Used for timing (not directly in packet)      |
   | :metadata        | Application-specific, not sent to hardware    |
   
   Frame Structure Notes:
   - First point is the start position (should be blanked for repositioning)
   - Last point is the end position
   - Consumer handles movement between frames
   - Empty frame (no points) voids the frame buffer"
  (:require [laser-show.common.util :as u]))


;; Core Types


(defrecord LaserPoint
  [^double x    ; X coordinate, normalized (-1.0 to 1.0)
   ^double y    ; Y coordinate, normalized (-1.0 to 1.0)
   ^double r    ; Red, normalized (0.0 to 1.0)
   ^double g    ; Green, normalized (0.0 to 1.0)
   ^double b])  ; Blue, normalized (0.0 to 1.0)
               ; Note: Blanking is indicated by r=g=b=0.0 per IDN-Stream spec

(defrecord LaserFrame
  [points            ; Vector of LaserPoint
   timestamp         ; Frame timestamp in milliseconds
   metadata])        ; Additional frame metadata (duration, etc.)


;; Point Construction Helpers


(defn make-point
  "Create a LaserPoint with normalized coordinates and colors.
   
   All values must be normalized:
   - x, y: range [-1.0, 1.0], clamped if outside
   - r, g, b: range [0.0, 1.0], clamped if outside
   
   Per IDN-Stream spec, blanking is indicated by r=g=b=0.0."
  ([x y]
   (make-point x y 1.0 1.0 1.0))
  ([x y r g b]
   (->LaserPoint
    (u/clamp (double x) -1.0 1.0)
    (u/clamp (double y) -1.0 1.0)
    (u/clamp (double r) 0.0 1.0)
    (u/clamp (double g) 0.0 1.0)
    (u/clamp (double b) 0.0 1.0))))

(defn blanked-point
  "Create a blanked (invisible) point for beam repositioning.
   Per IDN-Stream spec Section 3.4.11, blanking is done by setting all color intensities to 0."
  [x y]
  (make-point x y 0.0 0.0 0.0))

(defn blanked?
  "Check if a point is blanked (invisible).
   A point is blanked when all color channels are zero (or very close to zero)."
  [point]
  (let [epsilon 1e-6]
    (and (< (:r point) epsilon)
         (< (:g point) epsilon)
         (< (:b point) epsilon))))


;; Point Value Extraction (for output conversion)


(defn point->8bit-color
  "Extract color from a LaserPoint as 8-bit values [r g b]."
  [point]
  [(int (* (:r point) 255))
   (int (* (:g point) 255))
   (int (* (:b point) 255))])

(defn point->16bit-color
  "Extract color from a LaserPoint as 16-bit values [r g b]."
  [point]
  [(int (* (:r point) 65535))
   (int (* (:g point) 65535))
   (int (* (:b point) 65535))])

(defn point->16bit-xy
  "Extract coordinates from a LaserPoint as 16-bit signed values [x y]."
  [point]
  [(short (* (:x point) 32767))
   (short (* (:y point) 32767))])

(defn point->8bit-xy
  "Extract coordinates from a LaserPoint as 8-bit unsigned values [x y].
   Maps -1.0 to 0, 0.0 to 128, 1.0 to 255."
  [point]
  [(int (* (+ (:x point) 1.0) 127.5))
   (int (* (+ (:y point) 1.0) 127.5))])


;; Frame Construction


(defn make-frame
  "Create a LaserFrame from a sequence of points."
  ([points]
   (make-frame points (System/currentTimeMillis) {}))
  ([points timestamp]
   (make-frame points timestamp {}))
  ([points timestamp metadata]
   (->LaserFrame (vec points) timestamp metadata)))

(defn empty-frame
  "Create an empty frame."
  []
  (make-frame []))


