(ns laser-show.animation.effects.curves
  "Spline interpolation algorithms and LUT generation for RGB curve editor.
   
   Implements Catmull-Rom spline interpolation for smooth curve rendering
   and lookup table (LUT) generation for efficient color transformation.
   
   All control points use NORMALIZED values (0.0-1.0):
   
   Usage:
   (def points [[0.0 0.0] [0.25 0.31] [0.75 0.86] [1.0 1.0]])
   (def lut (generate-curve-lut points))
   ;; => 256-entry vector with smooth interpolation through control points
   
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
  "Generate 256-entry lookup table from NORMALIZED control points.
   
   Takes a vector of control points [[x0 y0] [x1 y1] ...] sorted by X,
   where X and Y are normalized values in [0.0, 1.0] range.
   Returns a vector of 256 normalized float values (0.0-1.0).
   
   Parameters:
   - control-points: Vector of [x y] pairs with normalized values, sorted by X
   
   Returns vector of 256 floats in [0.0, 1.0] range."
  [control-points]
  (if (or (nil? control-points) (empty? control-points))
    ;; Default: identity mapping (normalized)
    (vec (for [i (range 256)] (/ i 255.0)))
    (let [;; Ensure we have valid endpoints
          sorted-points (vec (sort-by first control-points))
          ;; Generate LUT - input is normalized 0.0-1.0, output is normalized
          lut (vec (for [input (range 256)]
                     (let [normalized-input (/ input 255.0)
                           y (interpolate-y-at-x sorted-points normalized-input)]
                       (clamp y 0.0 1.0))))]
      lut)))


;; Curve Sampling for Rendering


(defn sample-curve-for-display
  "Sample the curve at regular intervals for smooth rendering.
   
   Returns a sequence of [x y] points suitable for drawing a path.
   All values are normalized (0.0-1.0).
   Samples at higher resolution (typically 256 points) for smooth appearance.
   
   Parameters:
   - control-points: Vector of [x y] control points (normalized 0.0-1.0)
   - num-samples: Number of sample points (default 256)
   
   Returns vector of [x y] pairs for drawing (normalized 0.0-1.0)."
  ([control-points]
   (sample-curve-for-display control-points 256))
  ([control-points num-samples]
   (if (or (nil? control-points) (empty? control-points))
     ;; Default: diagonal line (normalized)
     [[0.0 0.0] [1.0 1.0]]
     (let [sorted-points (vec (sort-by first control-points))
           step (/ 1.0 (dec num-samples))]
       (vec (for [i (range num-samples)]
              (let [x (* i step)
                    y (interpolate-y-at-x sorted-points x)]
                [(clamp x 0.0 1.0)
                 (clamp y 0.0 1.0)])))))))


;; Utility Functions


(defn identity-curve-points
  "Returns the default identity curve control points.
   This is a straight diagonal line that maps each input to itself.
   Uses normalized values (0.0-1.0)."
  []
  [[0.0 0.0] [1.0 1.0]])

(defn validate-control-points
  "Validate and normalize control points.
   
   - Ensures all points have X and Y in [0.0, 1.0] range
   - Sorts by X coordinate
   - Ensures first point has X=0.0 and last has X=1.0
   
   Returns validated vector of control points."
  [points]
  (if (or (nil? points) (empty? points))
    (identity-curve-points)
    (let [;; Clamp and sort
          normalized (->> points
                          (map (fn [[x y]]
                                 [(clamp (double x) 0.0 1.0) (clamp (double y) 0.0 1.0)]))
                          (sort-by first)
                          vec)
          ;; Ensure first point has X=0.0
          first-pt (first normalized)
          with-start (if (< (first first-pt) 0.001)
                       normalized
                       (into [[0.0 (second first-pt)]] normalized))
          ;; Ensure last point has X=1.0
          last-pt (last with-start)
          with-end (if (> (first last-pt) 0.999)
                     with-start
                     (conj with-start [1.0 (second last-pt)]))]
      with-end)))

(defn add-point
  "Add a new control point and return sorted points.
   
   Parameters:
   - points: Current control points (normalized)
   - x, y: New point coordinates (normalized 0.0-1.0)
   
   Returns sorted vector with new point added."
  [points x y]
  (let [clamped-x (clamp (double x) 0.0 1.0)
        clamped-y (clamp (double y) 0.0 1.0)
        new-point [clamped-x clamped-y]
        updated (conj (vec points) new-point)]
    (vec (sort-by first updated))))

(defn update-point
  "Update a control point at index and return sorted points.
   
   For corner points (first and last), only Y can be modified.
   
   Parameters:
   - points: Current control points (normalized)
   - idx: Index of point to update
   - x, y: New coordinates (normalized 0.0-1.0)
   
   Returns sorted vector with updated point."
  [points idx x y]
  (let [n (count points)
        is-corner? (or (= idx 0) (= idx (dec n)))
        current-point (nth points idx)
        new-x (if is-corner? (first current-point) (clamp (double x) 0.0 1.0))
        new-y (clamp (double y) 0.0 1.0)
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
