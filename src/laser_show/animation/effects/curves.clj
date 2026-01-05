(ns laser-show.animation.effects.curves
  "Spline interpolation algorithms and LUT generation for RGB curve editor.
   
   Implements Catmull-Rom spline interpolation for smooth curve rendering
   and lookup table (LUT) generation for efficient color transformation.
   
   Usage:
   (def points [[0 0] [64 80] [192 220] [255 255]])
   (def lut (generate-curve-lut points))
   ;; => [0 1 2 ... 255] with smooth interpolation through control points
   
   The Catmull-Rom spline is chosen because:
   - It passes through all control points (interpolating spline)
   - Smooth tangent continuity (C1 continuous)
   - Simple to implement and compute
   - Commonly used in graphics applications (Photoshop, etc.)")

;; Catmull-Rom Spline Implementation


(defn clamp
  "Clamp value to [min-val, max-val] range."
  [value min-val max-val]
  (-> value (max min-val) (min max-val)))

(defn- catmull-rom-segment
  "Calculate Catmull-Rom spline segment between p1 and p2.
   Uses p0 and p3 as control points for tangent calculation.
   
   The standard Catmull-Rom formula with tension τ = 0.5:
   p(t) = 0.5 * [(2*p1) + (-p0+p2)*t + (2*p0-5*p1+4*p2-p3)*t² + (-p0+3*p1-3*p2+p3)*t³]
   
   Parameters:
   - p0, p1, p2, p3: Four points [x y] for the spline segment
   - t: Parameter in [0, 1] representing position between p1 and p2
   
   Returns [x y] interpolated point."
  [[x0 y0] [x1 y1] [x2 y2] [x3 y3] t]
  (let [t2 (* t t)
        t3 (* t2 t)
        ;; Catmull-Rom basis functions
        b0 (* 0.5 (+ (- t3) (* 2 t2) (- t)))
        b1 (* 0.5 (+ (* 3 t3) (* -5 t2) 2))
        b2 (* 0.5 (+ (* -3 t3) (* 4 t2) t))
        b3 (* 0.5 (+ t3 (- t2)))
        ;; Interpolated values
        x (+ (* b0 x0) (* b1 x1) (* b2 x2) (* b3 x3))
        y (+ (* b0 y0) (* b1 y1) (* b2 y2) (* b3 y3))]
    [x y]))

(defn interpolate-y-at-x
  "Interpolate Y value at a specific X coordinate using Catmull-Rom spline.
   
   Control points must be sorted by X coordinate.
   For X values outside the control point range, clamps to endpoint values.
   
   Parameters:
   - control-points: Sorted vector of [x y] points
   - target-x: X coordinate to interpolate at
   
   Returns interpolated Y value."
  [control-points target-x]
  (let [n (count control-points)]
    (cond
      ;; Single point - return its Y value
      (= n 1)
      (second (first control-points))
      
      ;; Two points - linear interpolation
      (= n 2)
      (let [[x0 y0] (first control-points)
            [x1 y1] (second control-points)]
        (if (= x0 x1)
          y0
          (let [t (/ (- target-x x0) (- x1 x0))
                t-clamped (clamp t 0.0 1.0)]
            (+ y0 (* t-clamped (- y1 y0))))))
      
      ;; Three or more points - Catmull-Rom spline
      :else
      (let [;; Find the segment containing target-x
            segment-idx (loop [i 0]
                          (if (>= i (dec n))
                            (- n 2) ;; Clamp to last segment
                            (let [[x-curr _] (nth control-points i)
                                  [x-next _] (nth control-points (inc i))]
                              (if (and (>= target-x x-curr) (< target-x x-next))
                                i
                                (recur (inc i))))))
            ;; Get the four points for Catmull-Rom (p0, p1, p2, p3)
            ;; For boundary segments, duplicate endpoints
            p0 (nth control-points (max 0 (dec segment-idx)))
            p1 (nth control-points segment-idx)
            p2 (nth control-points (min (dec n) (inc segment-idx)))
            p3 (nth control-points (min (dec n) (+ segment-idx 2)))
            ;; Calculate t parameter within segment
            [x1 _] p1
            [x2 _] p2
            t (if (= x1 x2)
                0.0
                (clamp (/ (- target-x x1) (- x2 x1)) 0.0 1.0))
            ;; Interpolate
            [_ y] (catmull-rom-segment p0 p1 p2 p3 t)]
        y))))


;; LUT Generation


(defn generate-curve-lut
  "Generate 256-entry lookup table from control points.
   
   Takes a vector of control points [[x0 y0] [x1 y1] ...] sorted by X,
   and returns a vector of 256 integer values representing the output
   for each input value 0-255.
   
   Control points must have X and Y in [0, 255] range.
   The first point should be at x=0 and last at x=255 for full coverage.
   
   Parameters:
   - control-points: Vector of [x y] pairs, sorted by X
   
   Returns vector of 256 integers in [0, 255] range."
  [control-points]
  (if (or (nil? control-points) (empty? control-points))
    ;; Default: identity mapping
    (vec (range 256))
    (let [;; Ensure we have valid endpoints
          sorted-points (vec (sort-by first control-points))
          ;; Generate LUT
          lut (vec (for [input (range 256)]
                     (let [y (interpolate-y-at-x sorted-points (double input))]
                       (int (clamp (Math/round y) 0 255)))))]
      lut)))


;; Curve Sampling for Rendering


(defn sample-curve-for-display
  "Sample the curve at regular intervals for smooth rendering.
   
   Returns a sequence of [x y] points suitable for drawing a path.
   Samples at higher resolution (typically 256 points) for smooth appearance.
   
   Parameters:
   - control-points: Vector of [x y] control points
   - num-samples: Number of sample points (default 256)
   
   Returns vector of [x y] pairs for drawing."
  ([control-points]
   (sample-curve-for-display control-points 256))
  ([control-points num-samples]
   (if (or (nil? control-points) (empty? control-points))
     ;; Default: diagonal line
     [[0 0] [255 255]]
     (let [sorted-points (vec (sort-by first control-points))
           step (/ 255.0 (dec num-samples))]
       (vec (for [i (range num-samples)]
              (let [x (* i step)
                    y (interpolate-y-at-x sorted-points x)]
                [(clamp x 0.0 255.0)
                 (clamp y 0.0 255.0)])))))))


;; Utility Functions


(defn identity-curve-points
  "Returns the default identity curve control points.
   This is a straight diagonal line that maps each input to itself."
  []
  [[0 0] [255 255]])

(defn validate-control-points
  "Validate and normalize control points.
   
   - Ensures all points have X and Y in [0, 255] range
   - Sorts by X coordinate
   - Ensures first point has X=0 and last has X=255
   
   Returns normalized vector of control points."
  [points]
  (if (or (nil? points) (empty? points))
    (identity-curve-points)
    (let [;; Clamp and sort
          normalized (->> points
                          (map (fn [[x y]]
                                 [(clamp x 0 255) (clamp y 0 255)]))
                          (sort-by first)
                          vec)
          ;; Ensure first point has X=0
          first-pt (first normalized)
          with-start (if (= 0 (first first-pt))
                       normalized
                       (into [[0 (second first-pt)]] normalized))
          ;; Ensure last point has X=255
          last-pt (last with-start)
          with-end (if (= 255 (first last-pt))
                     with-start
                     (conj with-start [255 (second last-pt)]))]
      with-end)))

(defn add-point
  "Add a new control point and return sorted points.
   
   Parameters:
   - points: Current control points
   - x, y: New point coordinates
   
   Returns sorted vector with new point added."
  [points x y]
  (let [clamped-x (clamp x 0 255)
        clamped-y (clamp y 0 255)
        new-point [clamped-x clamped-y]
        updated (conj (vec points) new-point)]
    (vec (sort-by first updated))))

(defn update-point
  "Update a control point at index and return sorted points.
   
   For corner points (first and last), only Y can be modified.
   
   Parameters:
   - points: Current control points
   - idx: Index of point to update
   - x, y: New coordinates
   
   Returns sorted vector with updated point."
  [points idx x y]
  (let [n (count points)
        is-corner? (or (= idx 0) (= idx (dec n)))
        current-point (nth points idx)
        new-x (if is-corner? (first current-point) (clamp x 0 255))
        new-y (clamp y 0 255)
        updated (assoc points idx [new-x new-y])]
    (vec (sort-by first updated))))

(defn remove-point
  "Remove a control point at index.
   
   Corner points (first and last) cannot be removed.
   
   Parameters:
   - points: Current control points
   - idx: Index of point to remove
   
   Returns vector with point removed, or unchanged if corner."
  [points idx]
  (let [n (count points)
        is-corner? (or (= idx 0) (= idx (dec n)))]
    (if is-corner?
      points ;; Cannot remove corners
      (vec (concat (subvec points 0 idx)
                   (subvec points (inc idx)))))))
