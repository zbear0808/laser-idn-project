(ns laser-show.backend.zone-transform
  "Zone transformation pipeline.
   Applies geometric transformations to laser frames based on zone configuration.
   Handles viewport clipping, scaling, offset, rotation, and blocked region masking."
  (:require [laser-show.animation.types :as t]))

;; ============================================================================
;; Coordinate Utilities
;; ============================================================================

(defn normalize-coord
  "Convert a 16-bit signed coordinate to normalized [-1.0, 1.0] range."
  [coord]
  (/ (double coord) 32767.0))

(defn denormalize-coord
  "Convert a normalized [-1.0, 1.0] coordinate to 16-bit signed range."
  [coord]
  (short (Math/round (* (double coord) 32767.0))))

(defn clamp
  "Clamp a value to the given range."
  [value min-val max-val]
  (max min-val (min max-val value)))

;; ============================================================================
;; Point Transformation Helpers
;; ============================================================================

(defn point-to-normalized
  "Convert a LaserPoint's coordinates to normalized space.
   Returns [x y] in range [-1.0, 1.0]."
  [point]
  [(normalize-coord (:x point))
   (normalize-coord (:y point))])

(defn normalized-to-point
  "Create a new point with normalized coordinates converted back to 16-bit.
   Preserves the original point's color values."
  [point norm-x norm-y]
  (assoc point
         :x (denormalize-coord norm-x)
         :y (denormalize-coord norm-y)))

;; ============================================================================
;; Viewport Transformation
;; ============================================================================

(defn point-in-viewport?
  "Check if a normalized point is within the viewport bounds."
  [norm-x norm-y viewport]
  (let [{:keys [x-min x-max y-min y-max]} viewport]
    (and (>= norm-x x-min) (<= norm-x x-max)
         (>= norm-y y-min) (<= norm-y y-max))))

(defn apply-viewport-to-point
  "Apply viewport transformation to a single point.
   Maps the viewport region to the full [-1, 1] output space.
   Returns nil if point is outside viewport."
  [point viewport]
  (let [[norm-x norm-y] (point-to-normalized point)
        {:keys [x-min x-max y-min y-max]} viewport]
    (when (point-in-viewport? norm-x norm-y viewport)
      (let [viewport-width (- x-max x-min)
            viewport-height (- y-max y-min)
            ;; Remap from viewport space to [-1,1] output space
            out-x (if (zero? viewport-width)
                    0.0
                    (- (* 2.0 (/ (- norm-x x-min) viewport-width)) 1.0))
            out-y (if (zero? viewport-height)
                    0.0
                    (- (* 2.0 (/ (- norm-y y-min) viewport-height)) 1.0))]
        (normalized-to-point point out-x out-y)))))

(defn apply-viewport
  "Apply viewport clipping and mapping to a frame.
   Points outside the viewport are removed.
   Points inside are mapped to fill the full output space."
  [frame viewport]
  (if (nil? viewport)
    frame
    (let [transformed-points (->> (:points frame)
                                  (map #(apply-viewport-to-point % viewport))
                                  (remove nil?)
                                  vec)]
      (assoc frame :points transformed-points))))

;; ============================================================================
;; Scale Transformation
;; ============================================================================

(defn apply-scale-to-point
  "Apply scale transformation to a single point."
  [point scale]
  (let [[norm-x norm-y] (point-to-normalized point)
        {:keys [x y]} scale
        scaled-x (* norm-x x)
        scaled-y (* norm-y y)]
    (normalized-to-point point scaled-x scaled-y)))

(defn apply-scale
  "Apply scale transformation to a frame."
  [frame scale]
  (if (or (nil? scale) (and (= 1.0 (:x scale)) (= 1.0 (:y scale))))
    frame
    (let [scaled-points (mapv #(apply-scale-to-point % scale) (:points frame))]
      (assoc frame :points scaled-points))))

;; ============================================================================
;; Offset Transformation
;; ============================================================================

(defn apply-offset-to-point
  "Apply offset (translation) to a single point."
  [point offset]
  (let [[norm-x norm-y] (point-to-normalized point)
        {:keys [x y]} offset
        offset-x (+ norm-x x)
        offset-y (+ norm-y y)
        ;; Clamp to valid range
        clamped-x (clamp offset-x -1.0 1.0)
        clamped-y (clamp offset-y -1.0 1.0)]
    (normalized-to-point point clamped-x clamped-y)))

(defn apply-offset
  "Apply offset transformation to a frame."
  [frame offset]
  (if (or (nil? offset) (and (zero? (:x offset)) (zero? (:y offset))))
    frame
    (let [offset-points (mapv #(apply-offset-to-point % offset) (:points frame))]
      (assoc frame :points offset-points))))

;; ============================================================================
;; Rotation Transformation
;; ============================================================================

(defn apply-rotation-to-point
  "Apply rotation to a single point around the origin.
   Rotation is in radians, positive = counter-clockwise."
  [point rotation]
  (let [[norm-x norm-y] (point-to-normalized point)
        cos-r (Math/cos rotation)
        sin-r (Math/sin rotation)
        rotated-x (- (* norm-x cos-r) (* norm-y sin-r))
        rotated-y (+ (* norm-x sin-r) (* norm-y cos-r))
        ;; Clamp to valid range (rotation can push points outside)
        clamped-x (clamp rotated-x -1.0 1.0)
        clamped-y (clamp rotated-y -1.0 1.0)]
    (normalized-to-point point clamped-x clamped-y)))

(defn apply-rotation
  "Apply rotation transformation to a frame."
  [frame rotation]
  (if (or (nil? rotation) (zero? rotation))
    frame
    (let [rotated-points (mapv #(apply-rotation-to-point % rotation) (:points frame))]
      (assoc frame :points rotated-points))))

;; ============================================================================
;; Blocked Region Masking
;; ============================================================================

(defn point-in-rect?
  "Check if a normalized point is inside a rectangular region."
  [norm-x norm-y rect]
  (let [{:keys [x-min x-max y-min y-max]} rect]
    (and (>= norm-x x-min) (<= norm-x x-max)
         (>= norm-y y-min) (<= norm-y y-max))))

(defn point-in-circle?
  "Check if a normalized point is inside a circular region."
  [norm-x norm-y circle]
  (let [{:keys [center-x center-y radius]} circle
        dx (- norm-x center-x)
        dy (- norm-y center-y)
        distance-sq (+ (* dx dx) (* dy dy))]
    (<= distance-sq (* radius radius))))

(defn point-in-region?
  "Check if a normalized point is inside a blocked region."
  [norm-x norm-y region]
  (case (:type region)
    :rect (point-in-rect? norm-x norm-y region)
    :circle (point-in-circle? norm-x norm-y region)
    false))

(defn point-in-any-blocked-region?
  "Check if a point is inside any of the blocked regions."
  [point blocked-regions]
  (when (seq blocked-regions)
    (let [[norm-x norm-y] (point-to-normalized point)]
      (some #(point-in-region? norm-x norm-y %) blocked-regions))))

(defn blank-point
  "Create a blanked version of a point (r=g=b=0).
   Per IDN-Stream spec, blanking is indicated by all colors being zero."
  [point]
  (assoc point :r (unchecked-byte 0) :g (unchecked-byte 0) :b (unchecked-byte 0)))

(defn apply-blocked-regions-to-point
  "Apply blocked region masking to a single point.
   Points in blocked regions are blanked (made invisible)."
  [point blocked-regions]
  (if (point-in-any-blocked-region? point blocked-regions)
    (blank-point point)
    point))

(defn apply-blocked-regions
  "Apply blocked region masking to a frame.
   Points in blocked regions are blanked (r=g=b=0)."
  [frame blocked-regions]
  (if (empty? blocked-regions)
    frame
    (let [masked-points (mapv #(apply-blocked-regions-to-point % blocked-regions) 
                              (:points frame))]
      (assoc frame :points masked-points))))

;; ============================================================================
;; Combined Transformation Pipeline
;; ============================================================================

(defn transform-frame-for-zone
  "Transform a LaserFrame according to zone configuration.
   Applies transformations in order: viewport -> scale -> offset -> rotation -> blocked regions.
   
   Parameters:
   - frame: The LaserFrame to transform
   - zone: Zone configuration map with :transformations and :blocked-regions"
  [frame zone]
  (let [{:keys [transformations blocked-regions]} zone
        {:keys [viewport scale offset rotation]} transformations]
    (-> frame
        (apply-viewport viewport)
        (apply-scale scale)
        (apply-offset offset)
        (apply-rotation rotation)
        (apply-blocked-regions blocked-regions))))

(defn transform-frame
  "Transform a frame using explicit transformation parameters.
   Useful for preview or when not using full zone configuration."
  [frame & {:keys [viewport scale offset rotation blocked-regions]}]
  (cond-> frame
    viewport (apply-viewport viewport)
    scale (apply-scale scale)
    offset (apply-offset offset)
    rotation (apply-rotation rotation)
    (seq blocked-regions) (apply-blocked-regions blocked-regions)))

;; ============================================================================
;; Validation and Safety
;; ============================================================================

(defn frame-has-points-in-blocked-regions?
  "Check if any visible (non-blanked) points in a frame are in blocked regions.
   This is a safety check that can be used before sending frames."
  [frame blocked-regions]
  (when (seq blocked-regions)
    (some (fn [point]
            (and (not (t/blanked? point))
                 (point-in-any-blocked-region? point blocked-regions)))
          (:points frame))))

(defn validate-frame-safety
  "Validate that a frame has no visible points in blocked regions.
   Returns {:safe? true/false :violations [...]} where violations are points in blocked areas."
  [frame blocked-regions]
  (if (empty? blocked-regions)
    {:safe? true :violations []}
    (let [violations (filter (fn [point]
                               (and (not (t/blanked? point))
                                    (point-in-any-blocked-region? point blocked-regions)))
                             (:points frame))]
      {:safe? (empty? violations)
       :violations (vec violations)})))
