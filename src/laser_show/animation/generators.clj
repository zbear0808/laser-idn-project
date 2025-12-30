(ns laser-show.animation.generators
  "Shape generators for laser animations.
   Each generator produces a sequence of LaserPoints that form a shape."
  (:require [laser-show.animation.types :as t]
            [laser-show.animation.colors :as colors]
            [laser-show.common.util :as u]))

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
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Options:
   - :num-points - number of points (default 64)
   - :radius - radius in normalized coords (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points radius center color]
      :or {num-points 64  ; 64 points provides smooth circle at typical scan rates (20-30kpps)
           radius 0.5
           center [0 0]
           color [255 255 255]}}]
  (let [[cx cy] center
        [r g b] color
        ;; First point at angle 0 for blanked positioning
        first-x (+ cx radius)
        first-y cy]
    (->> (range num-points)
         (mapv (fn [i]
                 (let [angle (* TWO-PI (/ i num-points))
                       x (+ cx (* radius (Math/cos angle)))
                       y (+ cy (* radius (Math/sin angle)))]
                   (t/make-point x y r g b))))
         (u/consv (t/blanked-point first-x first-y)))))

(defn generate-line
  "Generate points for a line segment.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
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
    (->> (range num-points)
         (mapv (fn [i]
                 (let [t (/ i (dec num-points))
                       x (lerp x1 x2 t)
                       y (lerp y1 y2 t)]
                   (t/make-point x y r g b))))
         (u/consv (t/blanked-point x1 y1)))))

(defn generate-square
  "Generate points for a square.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
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
                 [(- cx half) (- cy half)]]  ; back to start
        [first-x first-y] (first corners)]
    (->> corners
         (partition 2 1)
         (u/mapcatv (fn [[[x1 y1] [x2 y2]]]
                      (mapv (fn [i]
                              (let [t (/ i num-points)
                                    x (lerp x1 x2 t)
                                    y (lerp y1 y2 t)]
                                (t/make-point x y r g b)))
                            (range num-points))))
         (u/consv (t/blanked-point first-x first-y)))))

(defn generate-triangle
  "Generate points for a triangle.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Options:
   - :num-points - points per side (default 21)
   - :size - size in normalized coords (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points size center color]
      :or {num-points 21  ; 21 per side = 63 total points for 3 sides (good balance for sharp corners)
           size 0.5
           center [0 0]
           color [255 255 255]}}]
  (let [[cx cy] center
        [r g b] color
        ;; Equilateral triangle vertices
        top [cx (+ cy (* size 0.577))]        ; top vertex
        left [(- cx (/ size 2)) (- cy (* size 0.289))]   ; bottom-left
        right [(+ cx (/ size 2)) (- cy (* size 0.289))]  ; bottom-right
        corners [top right left top]  ; close the triangle
        [first-x first-y] (first corners)]
    (->> corners
         (partition 2 1)
         (u/mapcatv (fn [[[x1 y1] [x2 y2]]]
                      (mapv (fn [i]
                              (let [t (/ i num-points)
                                    x (lerp x1 x2 t)
                                    y (lerp y1 y2 t)]
                                (t/make-point x y r g b)))
                            (range num-points))))
         (u/consv (t/blanked-point first-x first-y)))))

(defn generate-spiral
  "Generate points for a spiral.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Options:
   - :num-points - total number of points (default 128)
   - :turns - number of spiral turns (default 3)
   - :start-radius - inner radius (default 0.1)
   - :end-radius - outer radius (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-points turns start-radius end-radius center color]
      :or {num-points 128  ; Higher point count for smooth spiral curves with multiple turns
           turns 3
           start-radius 0.1
           end-radius 0.5
           center [0 0]
           color [255 255 255]}}]
  (let [[cx cy] center
        [r g b] color
        ;; First point at center of spiral (start-radius, angle 0)
        first-x (+ cx start-radius)
        first-y cy]
    (->> (range num-points)
         (mapv (fn [i]
                 (let [t (/ i (dec num-points))
                       angle (* TWO-PI turns t)
                       radius (lerp start-radius end-radius t)
                       x (+ cx (* radius (Math/cos angle)))
                       y (+ cy (* radius (Math/sin angle)))]
                   (t/make-point x y r g b))))
         (u/consv (t/blanked-point first-x first-y)))))

(defn generate-star
  "Generate points for a star.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
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
        vertices (->> (range (* 2 spikes))
                      (mapv (fn [i]
                              (let [angle (* i half-step)
                                    radius (if (even? i) outer-radius inner-radius)]
                                [(+ cx (* radius (Math/cos angle)))
                                 (+ cy (* radius (Math/sin angle)))]))))
        ;; Close the star
        vertices (conj vertices (first vertices))
        [first-x first-y] (first vertices)]
    (->> vertices
         (partition 2 1)
         (u/mapcatv (fn [[[x1 y1] [x2 y2]]]
                      (mapv (fn [i]
                              (let [t (/ i num-points)
                                    x (lerp x1 x2 t)
                                    y (lerp y1 y2 t)]
                                (t/make-point x y r g b)))
                            (range num-points))))
         (u/consv (t/blanked-point first-x first-y)))))

(defn generate-wave
  "Generate points for a sine wave.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
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
        half-width (/ width 2)
        ;; First point at start of wave
        first-x (+ cx (- half-width))
        first-y cy]
    (->> (range num-points)
         (mapv (fn [i]
                 (let [t (/ i (dec num-points))
                       x (+ cx (- (* t width) half-width))
                       y (+ cy (* amplitude (Math/sin (* TWO-PI frequency t))))]
                   (t/make-point x y r g b))))
         (u/consv (t/blanked-point first-x first-y)))))

;; ============================================================================
;; Beam Effects
;; ============================================================================

(defn generate-beam
  "Generate a single beam (line from center outward).
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Note: generate-line already adds the blanked first point.
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
  "Generate multiple beams as disconnected endpoint dots in a fan pattern.
   Each beam is a single point at the endpoint with blanking in between.
   Options:
   - :num-beams - number of beams (default 8)
   - :length - beam length / distance from origin (default 0.8)
   - :spread - angular spread in radians (default PI)
   - :start-angle - starting angle (default -PI/2)
   - :origin - [x y] beam origin (default [0 -0.5])
   - :color - [r g b] color (default [255 255 255])"
  [& {:keys [num-beams length spread start-angle origin color]
      :or {num-beams 8
           length 0.8
           spread Math/PI
           start-angle (- (/ Math/PI 2))
           origin [0 -0.5]
           color [255 255 255]}}]
  (let [[ox oy] origin
        [r g b] color
        angle-step (if (> num-beams 1)
                     (/ spread (dec num-beams))
                     0)]
    (->> (range num-beams)
         (u/mapcatv (fn [i]
                      (let [angle (+ start-angle (* i angle-step))
                            ex (+ ox (* length (Math/cos angle)))
                            ey (+ oy (* length (Math/sin angle)))]
                        ;; Blanked point to move to endpoint position, then lit point at endpoint
                        [(t/blanked-point ex ey)
                         (t/make-point ex ey r g b)]))))))

;; ============================================================================
;; Animation-Ready Generators (return functions)
;; ============================================================================

(defn circle-animation
  "Create a circle animation generator function.
   Returns (fn [time-ms params] -> LaserFrame)"
  []
  (fn [time-ms params]
    (let [{:keys [radius color]
           :or {radius 0.5 color [255 255 255]}} params
          points (generate-circle :radius radius :color color)]
      (t/make-frame points))))

(defn square-animation
  "Create a square animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [size color]
           :or {size 0.5 color [255 255 255]}} params
          points (generate-square :size size :color color)]
      (t/make-frame points))))

(defn triangle-animation
  "Create a triangle animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [size color]
           :or {size 0.5 color [255 255 255]}} params
          points (generate-triangle :size size :color color)]
      (t/make-frame points))))

(defn spiral-animation
  "Create a spiral animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [turns start-radius end-radius color]
           :or {turns 3 start-radius 0.1 end-radius 0.5 color [255 255 255]}} params
          points (generate-spiral :turns turns :start-radius start-radius :end-radius end-radius :color color)]
      (t/make-frame points))))

(defn star-animation
  "Create a star animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [spikes outer-radius inner-radius color]
           :or {spikes 5 outer-radius 0.5 inner-radius 0.25 color [255 255 255]}} params
          points (generate-star :spikes spikes :outer-radius outer-radius :inner-radius inner-radius :color color)]
      (t/make-frame points))))

(defn wave-animation
  "Create a wave animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [amplitude frequency color]
           :or {amplitude 0.3 frequency 2 color [255 255 255]}} params
          [r g b] color]
      (->> (range 64)
           (mapv (fn [i]
                   (let [t (/ (double i) 63.0)
                         x (- t 0.5)
                         y (* amplitude (Math/sin (* TWO-PI frequency t)))]
                     (t/make-point x y r g b))))
           (t/make-frame)))))

(defn beam-fan-animation
  "Create a beam fan animation generator function."
  []
  (fn [time-ms params]
    (let [{:keys [length color]
           :or {length 0.8 color [255 255 255]}} params
          half-length (/ length 2)
          points (generate-line :num-points 32
                               :start [(- half-length) 0]
                               :end [half-length 0]
                               :color color)]
      (t/make-frame points))))

(defn horizontal-line-animation
  "Create a horizontal line animation generator function.
   Line spans horizontally across the projection area."
  []
  (fn [time-ms params]
    (let [{:keys [length color]
           :or {length 1.0 color [255 255 255]}} params
          half-length (/ length 2)
          points (generate-line :num-points 64
                               :start [(- half-length) 0]
                               :end [half-length 0]
                               :color color)]
      (t/make-frame points))))

(defn rainbow-circle-animation
  "Create a rainbow-colored circle animation."
  []
  (fn [time-ms params]
    (let [{:keys [radius]
           :or {radius 0.5}} params
          num-points 64]
      (->> (range num-points)
           (mapv (fn [i]
                   (let [t (/ (double i) num-points)
                         angle (* TWO-PI t)
                         x (* radius (Math/cos angle))
                         y (* radius (Math/sin angle))
                         [r g b] (colors/rainbow t)]
                     (t/make-point x y r g b))))
           (t/make-frame)))))
