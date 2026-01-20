(ns laser-show.animation.generators
  "Shape generators for laser animations.
   Each generator produces a sequence of LaserPoints that form a shape.
   
   Per IDN-Stream spec Section 6.2: All generators produce a blanked first point
   for beam positioning before the visible shape points.
   
   All colors use NORMALIZED VALUES (0.0 to 1.0) for maximum precision."
  (:require [laser-show.animation.types :as t]
            [laser-show.common.util :as u]))


(def ^:const TWO-PI (* 2 Math/PI))

(defn lerp
  "Linear interpolation between a and b by factor t (0.0 to 1.0)."
  ^double [^double a ^double b ^double t]
  (+ a (* (- b a) t)))

(defn- points-along-segment-xf
  "Transducer that generates points along line segments."
  [num-points make-point-fn]
  (let [num-segments (dec num-points)]
    (mapcat (fn [[[x1 y1] [x2 y2]]]
              (into []
                    (map (fn [i]
                           (let [t (/ (double i) num-segments)
                                 x (lerp x1 x2 t)
                                 y (lerp y1 y2 t)]
                             (make-point-fn x y))))
                    (range num-points))))))


(defn generate-circle
  "Generate points for a circle.
   Options: :num-points (64), :radius (0.5), :center ([0 0]), :color ([1.0 1.0 1.0])"
  [& {:keys [num-points radius center color]
      :or {num-points 64
           radius 0.5
           center [0 0]
           color [1.0 1.0 1.0]}}]
  (let [[cx cy] center
        [r g b] color
        first-x (+ cx radius)
        first-y cy
        num-segments (dec num-points)]
    (into [(t/blanked-point first-x first-y)]
          (map (fn [i]
                 (let [angle (* TWO-PI (/ (double i) num-segments))
                       x (+ cx (* radius (Math/cos angle)))
                       y (+ cy (* radius (Math/sin angle)))]
                   (t/make-point x y r g b))))
          (range num-points))))

(defn generate-line
  "Generate points for a line segment.
   Options: :num-points (32), :start ([-0.5 0]), :end ([0.5 0]), :color ([1.0 1.0 1.0])"
  [& {:keys [num-points start end color]
      :or {num-points 32
           start [-0.5 0]
           end [0.5 0]
           color [1.0 1.0 1.0]}}]
  (let [[x1 y1] start
        [x2 y2] end
        [r g b] color
        num-segments (dec num-points)]
    (into [(t/blanked-point x1 y1)]
          (map (fn [i]
                 (let [t (/ (double i) num-segments)
                       x (lerp x1 x2 t)
                       y (lerp y1 y2 t)]
                   (t/make-point x y r g b))))
          (range num-points))))

(defn generate-square
  "Generate points for a square.
   Options: :num-points per side (16), :size (0.5), :center ([0 0]), :color ([1.0 1.0 1.0])"
  [& {:keys [num-points size center color]
      :or {num-points 16
           size 0.5
           center [0 0]
           color [1.0 1.0 1.0]}}]
  (let [[cx cy] center
        [r g b] color
        half (/ size 2)
        corners [[(- cx half) (- cy half)]
                 [(+ cx half) (- cy half)]
                 [(+ cx half) (+ cy half)]
                 [(- cx half) (+ cy half)]
                 [(- cx half) (- cy half)]]
        [first-x first-y] (nth corners 0)]
    (into [(t/blanked-point first-x first-y)]
          (comp
           (u/sliding 2 1)
           (points-along-segment-xf num-points #(t/make-point %1 %2 r g b)))
          corners)))

(defn generate-triangle
  "Generate points for a triangle.
   Options: :num-points per side (21), :size (0.5), :center ([0 0]), :color ([1.0 1.0 1.0])"
  [& {:keys [num-points size center color]
      :or {num-points 21
           size 0.5
           center [0 0]
           color [1.0 1.0 1.0]}}]
  (let [[cx cy] center
        [r g b] color
        top [cx (+ cy (* size 0.577))]
        left [(- cx (/ size 2)) (- cy (* size 0.289))]
        right [(+ cx (/ size 2)) (- cy (* size 0.289))]
        corners [top right left top]
        [first-x first-y] (nth corners 0)]
    (into [(t/blanked-point first-x first-y)]
          (comp
           (u/sliding 2 1)
           (points-along-segment-xf num-points #(t/make-point %1 %2 r g b)))
          corners)))

(defn generate-spiral
  "Generate points for a spiral.
   Options: :num-points (128), :turns (3), :start-radius (0.1), :end-radius (0.5),
            :center ([0 0]), :color ([1.0 1.0 1.0])"
  [& {:keys [num-points turns start-radius end-radius center color]
      :or {num-points 128
           turns 3
           start-radius 0.1
           end-radius 0.5
           center [0 0]
           color [1.0 1.0 1.0]}}]
  (let [[cx cy] center
        [r g b] color
        first-x (+ cx start-radius)
        first-y cy
        num-segments (dec num-points)]
    (into [(t/blanked-point first-x first-y)]
          (map (fn [i]
                 (let [t (/ (double i) num-segments)
                       angle (* TWO-PI turns t)
                       radius (lerp start-radius end-radius t)
                       x (+ cx (* radius (Math/cos angle)))
                       y (+ cy (* radius (Math/sin angle)))]
                   (t/make-point x y r g b))))
          (range num-points))))

(defn generate-star
  "Generate points for a star.
   Options: :num-points per segment (8), :spikes (5), :outer-radius (0.5),
            :inner-radius (0.25), :center ([0 0]), :color ([1.0 1.0 1.0])"
  [& {:keys [num-points spikes outer-radius inner-radius center color]
      :or {num-points 8
           spikes 5
           outer-radius 0.5
           inner-radius 0.25
           center [0 0]
           color [1.0 1.0 1.0]}}]
  (let [[cx cy] center
        [r g b] color
        half-step (/ Math/PI spikes)
        vertex-count (* 2 spikes)
        ;; First vertex at angle 0 with outer-radius
        first-x (+ cx outer-radius)
        first-y cy]
    (into [(t/blanked-point first-x first-y)]
          (comp
           ;; vertex pairs for star points
           (map (fn [i]
                  (let [idx (mod i vertex-count)
                        angle (* idx half-step)
                        radius (if (even? idx) outer-radius inner-radius)]
                    [(+ cx (* radius (Math/cos angle)))
                     (+ cy (* radius (Math/sin angle)))])))
           ;; rest of star
           (u/sliding 2 1)
           (points-along-segment-xf num-points #(t/make-point %1 %2 r g b)))
          (range (inc vertex-count)))))




;; Frame Generators
;; These take a params map and return a LaserFrame.
;; Shape generators above handle the actual point generation.
;; These functions serve as the preset entry points.

(defn- shape-generator->frame-generator
  "Wraps a shape generator (that returns points) into a frame generator (that returns LaserFrame).
   The returned fn takes a params map and returns a LaserFrame."
  [generator-fn]
  (fn [params]
    (->> (into [] cat params)
         (apply generator-fn)
         t/make-frame)))

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
         t/make-frame)))

(defn wave-frame
  "Generate a sine wave frame.
   Params: :amplitude (default 0.3), :frequency (default 2), :color"
  [{:keys [amplitude frequency color]
    :or {amplitude 0.3 frequency 2 color [1.0 1.0 1.0]}}]
  (let [[r g b] color]
    (->> (range 64)
         (into [] (map (fn [i]
                         (let [t (/ (double i) 63.0)
                               x (- t 0.5)
                               y (* amplitude (Math/sin (* TWO-PI frequency t)))]
                           (t/make-point x y r g b)))))
         t/make-frame)))

(defn beam-fan-frame
  "Generate discrete beam points in a horizontal fan pattern.
   Points snake left-to-right then back for seamless looping.
   Blanking points separate each beam position.
   Params: :num-points (8), :color ([1.0 1.0 1.0])"
  [{:keys [num-points color]
    :or {num-points 8 color [1.0 1.0 1.0]}}]
  (let [[r g b] color
        x-min -0.99
        x-max 0.99
        y 0.0
        forward-positions (if (= num-points 1)
                            [0.0]
                            (->> (range num-points)
                                 (into [] (map #(lerp x-min x-max (/ (double %) (dec num-points)))))))
        backward-positions (when (> num-points 2)
                             (vec (reverse (subvec forward-positions 1 (dec num-points)))))
        all-positions (into forward-positions backward-positions)]
    (->> all-positions
         (into [] (mapcat (fn [x]
                            [(t/blanked-point x y)
                             (t/make-point x y r g b)
                             (t/make-point x y r g b)
                             (t/blanked-point x y)])))
         t/make-frame)))
