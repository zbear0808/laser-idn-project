(ns laser-show.animation.types
  "Core types for laser animation system.
   Defines LaserPoint, LaserFrame, and Animation abstractions.
   
   IDN-Stream Specification Compliance (Revision 002, July 2025)
   =============================================================
   
   This namespace defines the internal data structures used by the application.
   These structures are designed to map directly to IDN-Stream packet format.
   
   LaserPoint -> IDN-Stream Sample (Section 6.2)
   ---------------------------------------------
   Our LaserPoint record maps to IDN-Stream frame samples as follows:
   
   | LaserPoint Field | IDN-Stream Field | Size    | Range              |
   |------------------|------------------|---------|---------------------|
   | :x               | X coordinate     | 16-bit  | -32768 to 32767    |
   | :y               | Y coordinate     | 16-bit  | -32768 to 32767    |
   | :r               | Red (638nm)      | 8-bit   | 0 to 255           |
   | :g               | Green (532nm)    | 8-bit   | 0 to 255           |
   | :b               | Blue (460nm)     | 8-bit   | 0 to 255           |
   
   Coordinate System (Section 3.4.7):
   - X: Positive = right, Negative = left (front projection)
   - Y: Positive = up, Negative = down
   - Origin (0,0) is at center of projection area
   
   Blanking (Section 3.4.11):
   - Blanking is indicated by r=g=b=0 (all color intensities zero)
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
   - Empty frame (no points) voids the frame buffer
   
   Animation -> IDN-Stream Channel (Section 2.1)
   ---------------------------------------------
   Our Animation abstraction generates frames over time. Each frame is
   converted to an IDN-Stream channel message for transmission:
   
   - Animation runs on a single IDN channel
   - Frames are sent in Discrete Graphic Mode (Service Mode 0x02)
   - Channel configuration is sent every 200ms per spec requirement")

;; ============================================================================
;; Core Types
;; ============================================================================

(defrecord LaserPoint
  [^short x          ; X coordinate (-32768 to 32767)
   ^short y          ; Y coordinate (-32768 to 32767)
   ^byte r           ; Red (0-255)
   ^byte g           ; Green (0-255)
   ^byte b])         ; Blue (0-255)
                     ; Note: Blanking is indicated by r=g=b=0 per IDN-Stream spec

(defrecord LaserFrame
  [points            ; Vector of LaserPoint
   timestamp         ; Frame timestamp in milliseconds
   metadata])        ; Additional frame metadata (duration, etc.)

;; ============================================================================
;; Point Construction Helpers
;; ============================================================================

(defn make-point
  "Create a LaserPoint with normalized coordinates.
   x, y should be in range [-1.0, 1.0] and will be scaled to 16-bit range.
   Values outside this range are clamped to prevent overflow.
   r, g, b should be in range [0, 255] or [0.0, 1.0].
   Per IDN-Stream spec, blanking is indicated by r=g=b=0."
  ([x y]
   (make-point x y 255 255 255))
  ([x y r g b]
   (let [;; Clamp coordinates to [-1.0, 1.0] to prevent short overflow
         scale-coord (fn [v] (short (* (max -1.0 (min 1.0 (double v))) 32767)))
         scale-color (fn [v] (if (float? v)
                               (unchecked-byte (* (max 0.0 (min 1.0 v)) 255))
                               (unchecked-byte (max 0 (min 255 v)))))]
     (->LaserPoint
      (scale-coord x)
      (scale-coord y)
      (scale-color r)
      (scale-color g)
      (scale-color b))))
  ([x y r g b _intensity]
   ;; Backward compatibility: intensity parameter is ignored
   ;; Blanking should be done by setting r=g=b=0
   (make-point x y r g b)))

(defn make-point-raw
  "Create a LaserPoint with raw 16-bit coordinates and 8-bit colors.
   Per IDN-Stream spec, blanking is indicated by r=g=b=0."
  ([x y r g b]
   (->LaserPoint (short x) (short y) (unchecked-byte r) (unchecked-byte g) (unchecked-byte b)))
  ([x y r g b _intensity]
   ;; Backward compatibility: intensity parameter is ignored
   (make-point-raw x y r g b)))

(defn blanked-point
  "Create a blanked (invisible) point for beam repositioning.
   Per IDN-Stream spec Section 3.4.11, blanking is done by setting all color intensities to 0."
  [x y]
  (make-point x y 0 0 0))

(defn blanked?
  "Check if a point is blanked (invisible)."
  [point]
  (and (zero? (:r point))
       (zero? (:g point))
       (zero? (:b point))))

;; ============================================================================
;; Frame Construction
;; ============================================================================

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

;; ============================================================================
;; Animation Protocol
;; ============================================================================

(defprotocol IAnimation
  "Protocol for laser animations."
  (get-frame [this time-ms]
    "Get the frame at the given time in milliseconds.")
  (get-duration [this]
    "Get the total duration of the animation in milliseconds, or nil for infinite.")
  (get-name [this]
    "Get the name of the animation.")
  (get-parameters [this]
    "Get the current parameters of the animation."))

;; ============================================================================
;; Animation Record
;; ============================================================================

(defrecord Animation
  [name              ; Display name
   generator-fn      ; Function (time-ms, params) -> LaserFrame
   parameters        ; Map of parameter values
   duration]         ; Duration in ms, nil for infinite
  
  IAnimation
  (get-frame [this time-ms]
    (generator-fn time-ms parameters))
  (get-duration [this]
    duration)
  (get-name [this]
    name)
  (get-parameters [this]
    parameters))

(defn make-animation
  "Create an Animation with the given generator function.
   generator-fn takes (time-ms, params) and returns a LaserFrame."
  ([name generator-fn]
   (make-animation name generator-fn {} nil))
  ([name generator-fn parameters]
   (make-animation name generator-fn parameters nil))
  ([name generator-fn parameters duration]
   (->Animation name generator-fn parameters duration)))

;; ============================================================================
;; Animation Utilities
;; ============================================================================

(defn with-parameters
  "Return a new animation with updated parameters."
  [animation new-params]
  (assoc animation :parameters (merge (:parameters animation) new-params)))

(defn looping-time
  "Convert absolute time to looping time based on duration.
   Useful for creating looping animations."
  [time-ms duration-ms]
  (if duration-ms
    (mod time-ms duration-ms)
    time-ms))
