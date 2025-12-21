(ns laser-show.animation.generators
  "Shape generators for laser animations.
   Each generator produces a sequence of LaserPoints that form a shape."
  (:require [laser-show.animation.types :as t]))

;; ============================================================================
;; Geometry Helpers
;; ============================================================================

(def ^:const TWO-PI (* 2 Math/PI))

(defn lerp
  "Linear interpolation between a and b by factor t (0.0 to 1.0)."
  [a b t]
  (+ a (* (- b a) t)))

(defn rotate-point
  "Rotate a 2D point [x y] around origin by angle (radians)."
  [[x y] angle]
  (let [cos-a (Math/cos angle)
        sin-a (Math/sin angle)]
    [(- (* x cos-a) (* y sin-a))
     (+ (* x sin-a) (* y cos-a))]))

(defn scale-point
  "Scale a 2D point [x y] by factor."
  [[x y] factor]
  [(* x factor) (* y factor)])

(defn translate-point
  "Translate a 2D point [x y] by offset [dx dy]."
  [[x y] [dx dy]]
  [(+ x dx) (+ y dy)])

;; ============================================================================
;; Basic Shape Generators
;; ============================================================================

(defn generate-circle
  "Generate points for a circle.
   Options:
   - :num-points - number of points (default 64)
   - :radius - radius in normalized coords (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points radius center color]
      :or {num-points 64
           radius 0.5
           center [0 0]
           color [255 255 255]}}]
  (let [[cx cy] center
        [r g b] color]
    (for [i (range num-points)]
      (let [angle (* TWO-PI (/ i num-points))
            x (+ cx (* radius (Math/cos angle)))
            y (+ cy (* radius (Math/sin angle)))]
        (t/make-point x y r g b)))))

(defn generate-line
  "Generate points for a line segment.
   Options:
   - :num-points - number of points (default 32)
   - :start - [x1 y1] start position (default [-0.5 0])
   - :end - [x2 y2] end position (default [0.5 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points start end color]
      :or {num-points 32
           start [-0.5 0]
           end [0.5 0]
           color [255 255 255]}}]
  (let [[x1 y1] start
        [x2 y2] end
        [r g b] color]
    (for [i (range num-points)]
      (let [t (/ i (dec num-points))
            x (lerp x1 x2 t)
            y (lerp y1 y2 t)]
        (t/make-point x y r g b)))))

(defn generate-square
  "Generate points for a square.
   Options:
   - :num-points - points per side (default 16)
   - :size - side length in normalized coords (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points size center color]
      :or {num-points 16
           size 0.5
           center [0 0]
           color [255 255 255]}}]
  (let [[cx cy] center
        [r g b] color
        half (/ size 2)
        corners [[(- cx half) (- cy half)]   ; bottom-left
                 [(+ cx half) (- cy half)]   ; bottom-right
                 [(+ cx half) (+ cy half)]   ; top-right
                 [(- cx half) (+ cy half)]   ; top-left
                 [(- cx half) (- cy half)]]] ; back to start
    (mapcat (fn [[[x1 y1] [x2 y2]]]
              (for [i (range num-points)]
                (let [t (/ i num-points)
                      x (lerp x1 x2 t)
                      y (lerp y1 y2 t)]
                  (t/make-point x y r g b))))
            (partition 2 1 corners))))

(defn generate-triangle
  "Generate points for a triangle.
   Options:
   - :num-points - points per side (default 21)
   - :size - size in normalized coords (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points size center color]
      :or {num-points 21
           size 0.5
           center [0 0]
           color [255 255 255]}}]
  (let [[cx cy] center
        [r g b] color
        ;; Equilateral triangle vertices
        top [cx (+ cy (* size 0.577))]        ; top vertex
        left [(- cx (/ size 2)) (- cy (* size 0.289))]   ; bottom-left
        right [(+ cx (/ size 2)) (- cy (* size 0.289))]  ; bottom-right
        corners [top right left top]]  ; close the triangle
    (mapcat (fn [[[x1 y1] [x2 y2]]]
              (for [i (range num-points)]
                (let [t (/ i num-points)
                      x (lerp x1 x2 t)
                      y (lerp y1 y2 t)]
                  (t/make-point x y r g b))))
            (partition 2 1 corners))))

(defn generate-spiral
  "Generate points for a spiral.
   Options:
   - :num-points - total number of points (default 128)
   - :turns - number of spiral turns (default 3)
   - :start-radius - inner radius (default 0.1)
   - :end-radius - outer radius (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points turns start-radius end-radius center color]
      :or {num-points 128
           turns 3
           start-radius 0.1
           end-radius 0.5
           center [0 0]
           color [255 255 255]}}]
  (let [[cx cy] center
        [r g b] color]
    (for [i (range num-points)]
      (let [t (/ i (dec num-points))
            angle (* TWO-PI turns t)
            radius (lerp start-radius end-radius t)
            x (+ cx (* radius (Math/cos angle)))
            y (+ cy (* radius (Math/sin angle)))]
        (t/make-point x y r g b)))))

(defn generate-star
  "Generate points for a star.
   Options:
   - :num-points - points per segment (default 8)
   - :spikes - number of star spikes (default 5)
   - :outer-radius - outer radius (default 0.5)
   - :inner-radius - inner radius (default 0.25)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points spikes outer-radius inner-radius center color]
      :or {num-points 8
           spikes 5
           outer-radius 0.5
           inner-radius 0.25
           center [0 0]
           color [255 255 255]}}]
  (let [[cx cy] center
        [r g b] color
        angle-step (/ TWO-PI spikes)
        half-step (/ angle-step 2)
        ;; Generate vertices alternating outer/inner
        vertices (for [i (range (* 2 spikes))]
                   (let [angle (* i half-step)
                         radius (if (even? i) outer-radius inner-radius)]
                     [(+ cx (* radius (Math/cos angle)))
                      (+ cy (* radius (Math/sin angle)))]))
        ;; Close the star
        vertices (concat vertices [(first vertices)])]
    (mapcat (fn [[[x1 y1] [x2 y2]]]
              (for [i (range num-points)]
                (let [t (/ i num-points)
                      x (lerp x1 x2 t)
                      y (lerp y1 y2 t)]
                  (t/make-point x y r g b))))
            (partition 2 1 vertices))))

(defn generate-wave
  "Generate points for a sine wave.
   Options:
   - :num-points - number of points (default 64)
   - :amplitude - wave amplitude (default 0.3)
   - :frequency - wave frequency in cycles (default 2)
   - :width - horizontal span (default 1.0)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points amplitude frequency width center color]
      :or {num-points 64
           amplitude 0.3
           frequency 2
           width 1.0
           center [0 0]
           color [255 255 255]}}]
  (let [[cx cy] center
        [r g b] color
        half-width (/ width 2)]
    (for [i (range num-points)]
      (let [t (/ i (dec num-points))
            x (+ cx (- (* t width) half-width))
            y (+ cy (* amplitude (Math/sin (* TWO-PI frequency t))))]
        (t/make-point x y r g b)))))

;; ============================================================================
;; Beam Effects
;; ============================================================================

(defn generate-beam
  "Generate a single beam (line from center outward).
   Options:
   - :num-points - number of points (default 32)
   - :length - beam length (default 0.8)
   - :angle - beam angle in radians (default 0)
   - :origin - [x y] beam origin (default [0 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points length angle origin color]
      :or {num-points 32
           length 0.8
           angle 0
           origin [0 0]
           color [255 255 255]}}]
  (let [[ox oy] origin
        ex (+ ox (* length (Math/cos angle)))
        ey (+ oy (* length (Math/sin angle)))]
    (generate-line :num-points num-points
                   :start origin
                   :end [ex ey]
                   :color color)))

(defn generate-beam-fan
  "Generate multiple beams in a fan pattern.
   Options:
   - :num-beams - number of beams (default 8)
   - :num-points - points per beam (default 16)
   - :length - beam length (default 0.8)
   - :spread - angular spread in radians (default PI)
   - :start-angle - starting angle (default -PI/2)
   - :origin - [x y] beam origin (default [0 -0.5])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-beams num-points length spread start-angle origin color]
      :or {num-beams 8
           num-points 16
           length 0.8
           spread Math/PI
           start-angle (- (/ Math/PI 2))
           origin [0 -0.5]
           color [255 255 255]}}]
  (let [angle-step (/ spread (dec num-beams))]
    (mapcat (fn [i]
              (let [angle (+ start-angle (* i angle-step))]
                (concat
                 ;; Blanked point to jump to origin
                 [(t/blanked-point (first origin) (second origin))]
                 (generate-beam :num-points num-points
                                :length length
                                :angle angle
                                :origin origin
                                :color color))))
            (range num-beams))))

;; ============================================================================
;; Animation-Ready Generators (return functions)
;; ============================================================================

(defn circle-animation
  "Create a circle animation generator function.
   Returns (fn [time-ms params] -> LaserFrame)"
  []
  (fn [time-ms params]
    (let [{:keys [radius color rotation-speed]
           :or {radius 0.5 color [255 255 255] rotation-speed 0}} params
          rotation (if (pos? rotation-speed)
                     (* (/ time-ms 1000.0) rotation-speed TWO-PI)
                     0)
          points (generate-circle :radius radius :color color)]
      (if (pos? rotation)
        (t/make-frame
         (map (fn [pt]
                (let [[rx ry] (rotate-point [(:x pt) (:y pt)] rotation)]
                  (t/make-point-raw (short rx) (short ry) (:r pt) (:g pt) (:b pt) (:intensity pt))))
              points))
        (t/make-frame points)))))

(defn square-animation
  "Create a square animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [size color rotation-speed]
           :or {size 0.5 color [255 255 255] rotation-speed 1}} params
          rotation (* (/ time-ms 1000.0) rotation-speed TWO-PI)
          base-points (generate-square :size size :color color)
          rotated-points (map (fn [pt]
                                (let [x (/ (:x pt) 32767.0)
                                      y (/ (:y pt) 32767.0)
                                      [rx ry] (rotate-point [x y] rotation)]
                                  (t/make-point rx ry (:r pt) (:g pt) (:b pt) (:intensity pt))))
                              base-points)]
      (t/make-frame rotated-points))))

(defn triangle-animation
  "Create a triangle animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [size color rotation-speed]
           :or {size 0.5 color [255 255 255] rotation-speed 1}} params
          rotation (* (/ time-ms 1000.0) rotation-speed TWO-PI)
          base-points (generate-triangle :size size :color color)
          rotated-points (map (fn [pt]
                                (let [x (/ (:x pt) 32767.0)
                                      y (/ (:y pt) 32767.0)
                                      [rx ry] (rotate-point [x y] rotation)]
                                  (t/make-point rx ry (:r pt) (:g pt) (:b pt) (:intensity pt))))
                              base-points)]
      (t/make-frame rotated-points))))

(defn spiral-animation
  "Create a spiral animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [turns start-radius end-radius color rotation-speed]
           :or {turns 3 start-radius 0.1 end-radius 0.5 color [255 255 255] rotation-speed 0.5}} params
          rotation (* (/ time-ms 1000.0) rotation-speed TWO-PI)
          base-points (generate-spiral :turns turns :start-radius start-radius :end-radius end-radius :color color)
          rotated-points (map (fn [pt]
                                (let [x (/ (:x pt) 32767.0)
                                      y (/ (:y pt) 32767.0)
                                      [rx ry] (rotate-point [x y] rotation)]
                                  (t/make-point rx ry (:r pt) (:g pt) (:b pt) (:intensity pt))))
                              base-points)]
      (t/make-frame rotated-points))))

(defn star-animation
  "Create a star animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [spikes outer-radius inner-radius color rotation-speed]
           :or {spikes 5 outer-radius 0.5 inner-radius 0.25 color [255 255 255] rotation-speed 0.3}} params
          rotation (* (/ time-ms 1000.0) rotation-speed TWO-PI)
          base-points (generate-star :spikes spikes :outer-radius outer-radius :inner-radius inner-radius :color color)
          rotated-points (map (fn [pt]
                                (let [x (/ (:x pt) 32767.0)
                                      y (/ (:y pt) 32767.0)
                                      [rx ry] (rotate-point [x y] rotation)]
                                  (t/make-point rx ry (:r pt) (:g pt) (:b pt) (:intensity pt))))
                              base-points)]
      (t/make-frame rotated-points))))

(defn wave-animation
  "Create a wave animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [amplitude frequency color phase-speed]
           :or {amplitude 0.3 frequency 2 color [255 255 255] phase-speed 2}} params
          phase (* (/ time-ms 1000.0) phase-speed TWO-PI)
          points (for [i (range 64)]
                   (let [t (/ i 63.0)
                         x (- t 0.5)
                         y (* amplitude (Math/sin (+ phase (* TWO-PI frequency t))))]
                     (t/make-point x y (first color) (second color) (nth color 2))))]
      (t/make-frame points))))

(defn beam-fan-animation
  "Create a beam fan animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [num-beams length spread sweep-speed color]
           :or {num-beams 8 length 0.8 spread Math/PI sweep-speed 0.5 color [255 255 255]}} params
          sweep-offset (* (Math/sin (* (/ time-ms 1000.0) sweep-speed TWO-PI)) 0.3)
          points (generate-beam-fan :num-beams num-beams
                                    :length length
                                    :spread spread
                                    :start-angle (+ (- (/ Math/PI 2)) sweep-offset)
                                    :color color)]
      (t/make-frame points))))

(defn rainbow-circle-animation
  "Create a rainbow-colored circle animation."
  []
  (fn [time-ms params]
    (let [{:keys [radius rotation-speed color-speed]
           :or {radius 0.5 rotation-speed 0.5 color-speed 1}} params
          rotation (* (/ time-ms 1000.0) rotation-speed TWO-PI)
          color-offset (* (/ time-ms 1000.0) color-speed)
          num-points 64
          points (for [i (range num-points)]
                   (let [t (/ i num-points)
                         angle (+ rotation (* TWO-PI t))
                         x (* radius (Math/cos angle))
                         y (* radius (Math/sin angle))
                         [r g b] (t/rainbow-color (mod (+ t color-offset) 1.0))]
                     (t/make-point x y r g b)))]
      (t/make-frame points))))
