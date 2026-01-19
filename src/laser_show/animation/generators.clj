(ns laser-show.animation.generators
  "Shape generators for laser animations.
   Each generator produces a sequence of LaserPoints that form a shape.
   
   All colors use NORMALIZED VALUES (0.0 to 1.0) for maximum precision."
  (:require [laser-show.animation.types :as t]
            [laser-show.common.util :as u]))


;; Geometry Helpers


(def ^:const TWO-PI (* 2 Math/PI))

(defn lerp
  "Linear interpolation between a and b by factor t (0.0 to 1.0)."
  [a b t]
  (+ a (* (- b a) t)))


;; Basic Shape Generators

(defn generate-circle
  "Generate points for a circle.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Options:
   - :num-points - number of points (default 64)
   - :radius - radius in normalized coords (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] normalized color (0.0-1.0)"
  [& {:keys [num-points radius center color]
      :or {num-points 64  ; 64 points provides smooth circle at typical scan rates (20-30kpps)
           radius 0.5
           center [0 0]
           color [1.0 1.0 1.0]}}]
  (let [[cx cy] center
        [r g b] color
        ;; First point at angle 0 for blanked positioning
        first-x (+ cx radius)
        first-y cy
        num-segments (dec num-points)]  ; Divide by (n-1) to reach full circle at last point
    (->> (range num-points)
         (mapv (fn [i]
                 (let [angle (* TWO-PI (/ i num-segments))
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
   - :color - [r g b] normalized color (0.0-1.0)"
  [& {:keys [num-points start end color]
      :or {num-points 32
           start [-0.5 0]
           end [0.5 0]
           color [1.0 1.0 1.0]}}]
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
   - :color - [r g b] normalized color (0.0-1.0)"
  [& {:keys [num-points size center color]
      :or {num-points 16
           size 0.5
           center [0 0]
           color [1.0 1.0 1.0]}}]
  (let [[cx cy] center
        [r g b] color
        half (/ size 2)
        corners [[(- cx half) (- cy half)]   ; bottom-left
                 [(+ cx half) (- cy half)]   ; bottom-right
                 [(+ cx half) (+ cy half)]   ; top-right
                 [(- cx half) (+ cy half)]   ; top-left
                 [(- cx half) (- cy half)]]  ; back to start
        [first-x first-y] (first corners)
        num-segments (dec num-points)]  ; Divide by (n-1) to reach corner at last point
    (->> corners
         (partition 2 1)
         (u/mapcatv (fn [[[x1 y1] [x2 y2]]]
                      (mapv (fn [i]
                              (let [t (/ i num-segments)
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
   - :color - [r g b] normalized color (0.0-1.0)"
  [& {:keys [num-points size center color]
      :or {num-points 21  ; 21 per side = 63 total points for 3 sides (good balance for sharp corners)
           size 0.5
           center [0 0]
           color [1.0 1.0 1.0]}}]
  (let [[cx cy] center
        [r g b] color
        ;; Equilateral triangle vertices
        top [cx (+ cy (* size 0.577))]        ; top vertex
        left [(- cx (/ size 2)) (- cy (* size 0.289))]   ; bottom-left
        right [(+ cx (/ size 2)) (- cy (* size 0.289))]  ; bottom-right
        corners [top right left top]  ; close the triangle
        [first-x first-y] (first corners)
        num-segments (dec num-points)]  ; Divide by (n-1) to reach corner at last point
    (->> corners
         (partition 2 1)
         (u/mapcatv (fn [[[x1 y1] [x2 y2]]]
                      (mapv (fn [i]
                              (let [t (/ i num-segments)
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
   - :color - [r g b] normalized color (0.0-1.0)"
  [& {:keys [num-points turns start-radius end-radius center color]
      :or {num-points 128  ; Higher point count for smooth spiral curves with multiple turns
           turns 3
           start-radius 0.1
           end-radius 0.5
           center [0 0]
           color [1.0 1.0 1.0]}}]
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
   - :color - [r g b] normalized color (0.0-1.0)"
  [& {:keys [num-points spikes outer-radius inner-radius center color]
      :or {num-points 8
           spikes 5
           outer-radius 0.5
           inner-radius 0.25
           center [0 0]
           color [1.0 1.0 1.0]}}]
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
        [first-x first-y] (first vertices)
        num-segments (dec num-points)]  ; Divide by (n-1) to reach vertex at last point
    (->> vertices
         (partition 2 1)
         (u/mapcatv (fn [[[x1 y1] [x2 y2]]]
                      (mapv (fn [i]
                              (let [t (/ i num-segments)
                                    x (lerp x1 x2 t)
                                    y (lerp y1 y2 t)]
                                (t/make-point x y r g b)))
                            (range num-points))))
         (u/consv (t/blanked-point first-x first-y)))))




;; Frame Generators
;; These take a params map and return a LaserFrame.
;; Shape generators above handle the actual point generation.
;; These functions serve as the preset entry points.

(defn- shape-generator->frame-generator
  "Wraps a shape generator (that returns points) into a frame generator (that returns LaserFrame).
   The returned fn takes a params map and returns a LaserFrame."
  [generator-fn]
  (fn [params]
    (->> (apply generator-fn (mapcat identity params))
         (t/make-frame))))

(def circle-frame
  "Generate a circle frame. Params: :radius, :color, :center, :num-points"
  (shape-generator->frame-generator generate-circle))

(def square-frame
  "Generate a square frame. Params: :size, :color, :center, :num-points"
  (shape-generator->frame-generator generate-square))

(def triangle-frame
  "Generate a triangle frame. Params: :size, :color, :center, :num-points"
  (shape-generator->frame-generator generate-triangle))

(def spiral-frame
  "Generate a spiral frame. Params: :turns, :start-radius, :end-radius, :color, :center, :num-points"
  (shape-generator->frame-generator generate-spiral))

(def star-frame
  "Generate a star frame. Params: :spikes, :outer-radius, :inner-radius, :color, :center, :num-points"
  (shape-generator->frame-generator generate-star))


(defn horizontal-line-frame
  "Generate a horizontal line spanning the projection area.
   Params: :length (default 1.0), :color"
  [{:keys [length color]
    :or {length 1.0 color [1.0 1.0 1.0]}}]
  (let [half-length (/ length 2)]
    (->> (generate-line :num-points 64
                        :start [(- half-length) 0]
                        :end [half-length 0]
                        :color color)
         (t/make-frame))))

(defn wave-frame
  "Generate a sine wave frame.
   Params: :amplitude (default 0.3), :frequency (default 2), :color"
  [{:keys [amplitude frequency color]
    :or {amplitude 0.3 frequency 2 color [1.0 1.0 1.0]}}]
  (let [[r g b] color]
    (->> (range 64)
         (mapv (fn [i]
                 (let [t (/ (double i) 63.0)
                       x (- t 0.5)
                       y (* amplitude (Math/sin (* TWO-PI frequency t)))]
                   (t/make-point x y r g b))))
         (t/make-frame))))

(defn beam-fan-frame
  "Generate discrete beam points in a horizontal fan pattern.
   Points snake left-to-right then back for seamless looping.
   Per IDN-Stream spec Section 6.2: Blanking points separate each beam.
   Params: :num-points (default 8), :color"
  [{:keys [num-points color]
    :or {num-points 8 color [1.0 1.0 1.0]}}]
  (let [[r g b] color
        x-min -0.99
        x-max 0.99
        y 0.0
        forward-positions (if (= num-points 1)
                            [0.0]
                            (mapv #(lerp x-min x-max (/ (double %) (dec num-points)))
                                  (range num-points)))
        backward-positions (when (> num-points 2)
                             (vec (reverse (subvec forward-positions 1 (dec num-points)))))
        all-positions (into forward-positions backward-positions)]
    (->> all-positions
         (u/mapcatv (fn [x]
                      [(t/blanked-point x y)
                       (t/make-point x y r g b)
                       (t/make-point x y r g b)
                       (t/blanked-point x y)]))
         (t/make-frame))))
