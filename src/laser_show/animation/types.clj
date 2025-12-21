(ns laser-show.animation.types
  "Core types for laser animation system.
   Defines LaserPoint, LaserFrame, and Animation abstractions.")

;; ============================================================================
;; Core Types
;; ============================================================================

(defrecord LaserPoint
  [^short x          ; X coordinate (-32768 to 32767)
   ^short y          ; Y coordinate (-32768 to 32767)
   ^byte r           ; Red (0-255)
   ^byte g           ; Green (0-255)
   ^byte b           ; Blue (0-255)
   ^byte intensity]) ; Intensity/blanking (0-255, 0 = blanked)

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
   r, g, b, intensity should be in range [0, 255] or [0.0, 1.0]."
  ([x y]
   (make-point x y 255 255 255 255))
  ([x y r g b]
   (make-point x y r g b 255))
  ([x y r g b intensity]
   (let [scale-coord (fn [v] (short (* v 32767)))
         scale-color (fn [v] (if (float? v)
                               (byte (* v 255))
                               (byte v)))]
     (->LaserPoint
      (scale-coord x)
      (scale-coord y)
      (scale-color r)
      (scale-color g)
      (scale-color b)
      (scale-color intensity)))))

(defn make-point-raw
  "Create a LaserPoint with raw 16-bit coordinates and 8-bit colors."
  [x y r g b intensity]
  (->LaserPoint (short x) (short y) (byte r) (byte g) (byte b) (byte intensity)))

(defn blanked-point
  "Create a blanked (invisible) point for beam repositioning."
  [x y]
  (make-point x y 0 0 0 0))

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

;; ============================================================================
;; Color Utilities
;; ============================================================================

(defn hsv-to-rgb
  "Convert HSV to RGB. h in [0, 360], s and v in [0, 1].
   Returns [r g b] with values in [0, 255]."
  [h s v]
  (let [h (mod h 360)
        c (* v s)
        x (* c (- 1 (Math/abs (- (mod (/ h 60) 2) 1))))
        m (- v c)
        [r' g' b'] (cond
                     (< h 60)  [c x 0]
                     (< h 120) [x c 0]
                     (< h 180) [0 c x]
                     (< h 240) [0 x c]
                     (< h 300) [x 0 c]
                     :else     [c 0 x])]
    [(int (* 255 (+ r' m)))
     (int (* 255 (+ g' m)))
     (int (* 255 (+ b' m)))]))

(defn rainbow-color
  "Get a rainbow color based on position (0.0 to 1.0)."
  [position]
  (hsv-to-rgb (* position 360) 1.0 1.0))
