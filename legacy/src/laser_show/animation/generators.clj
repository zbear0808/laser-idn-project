(ns laser-show.animation.generators
  "Shape generators for laser animations.
   Each generator produces a sequence of LaserPoints that form a shape.
   
   Per IDN-Stream spec Section 6.2: All generators produce a blanked first point
   for beam positioning before the visible shape points.
   
   All colors use NORMALIZED VALUES (0.0 to 1.0) for maximum precision."
  (:require [laser-show.animation.types :as t]
            [laser-show.common.util :as u]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(def ^:const TWO-PI (* 2 Math/PI))

(defn lerp
  "Linear interpolation between a and b by factor t (0.0 to 1.0)."
  ^double [^double a ^double b ^double t]
  (+ a (* (- b a) t)))

(defn- points-along-segment-xf
  "Transducer that generates points along line segments."
  [num-points make-point-fn]
  (let [num-pts (long num-points)
        num-segments (double (dec num-pts))]
    (mapcat (fn [[[x1 y1] [x2 y2]]]
              (let [x1' (double x1) y1' (double y1)
                    x2' (double x2) y2' (double y2)]
                (into []
                      (map (fn [i]
                             (let [t (/ (double i) num-segments)
                                   x (lerp x1' x2' t)
                                   y (lerp y1' y2' t)]
                               (make-point-fn x y))))
                      (range num-pts)))))))


(defn generate-circle
  "Generate points for a circle.
   Options: :num-points (64), :radius (0.5), :center ([0 0]), :red (1.0), :green (1.0), :blue (1.0)"
  [& {:keys [num-points radius center red green blue]
      :or {num-points 64
           radius 0.5
           center [0 0]
           red 1.0
           green 1.0
           blue 1.0}}]
  (let [[cx cy] center
        r (double red)
        g (double green)
        b (double blue)
        cx' (double cx)
        cy' (double cy)
        radius' (double radius)
        num-pts (long num-points)
        first-x (+ cx' radius')
        num-segments (double (dec num-pts))]
    (into [(t/blanked-point first-x cy')]
          (map (fn [i]
                 (let [angle (* TWO-PI (/ (double i) num-segments))
                       x (+ cx' (* radius' (Math/cos angle)))
                       y (+ cy' (* radius' (Math/sin angle)))]
                   (t/make-point x y r g b))))
          (range num-pts))))

(defn generate-line
  "Generate points for a line segment.
   Options: :num-points (32), :start ([-0.5 0]), :end ([0.5 0]), :red (1.0), :green (1.0), :blue (1.0)"
  [& {:keys [num-points start end red green blue]
      :or {num-points 32
           start [-0.5 0]
           end [0.5 0]
           red 1.0
           green 1.0
           blue 1.0}}]
  (let [[x1 y1] start
        [x2 y2] end
        r (double red)
        g (double green)
        b (double blue)
        x1' (double x1) y1' (double y1)
        x2' (double x2) y2' (double y2)
        num-pts (long num-points)
        num-segments (double (dec num-pts))]
    (into [(t/blanked-point x1' y1')]
          (map (fn [i]
                 (let [t (/ (double i) num-segments)
                       x (lerp x1' x2' t)
                       y (lerp y1' y2' t)]
                   (t/make-point x y r g b))))
          (range num-pts))))

(defn generate-square
  "Generate points for a square.
   Options: :num-points per side (16), :size (0.5), :center ([0 0]), :red (1.0), :green (1.0), :blue (1.0)"
  [& {:keys [num-points size center red green blue]
      :or {num-points 16
           size 0.5
           center [0 0]
           red 1.0
           green 1.0
           blue 1.0}}]
  (let [[cx cy] center
        r (double red)
        g (double green)
        b (double blue)
        cx' (double cx)
        cy' (double cy)
        half (/ (double size) 2.0)
        corners [[(- cx' half) (- cy' half)]
                 [(+ cx' half) (- cy' half)]
                 [(+ cx' half) (+ cy' half)]
                 [(- cx' half) (+ cy' half)]
                 [(- cx' half) (- cy' half)]]
        [first-x first-y] (nth corners 0)]
    (into [(t/blanked-point first-x first-y)]
          (comp
           (u/sliding 2 1)
           (points-along-segment-xf num-points #(t/make-point %1 %2 r g b)))
          corners)))

(defn generate-triangle
  "Generate points for a triangle.
   Options: :num-points per side (21), :size (0.5), :center ([0 0]), :red (1.0), :green (1.0), :blue (1.0)"
  [& {:keys [num-points size center red green blue]
      :or {num-points 21
           size 0.5
           center [0 0]
           red 1.0
           green 1.0
           blue 1.0}}]
  (let [[cx cy] center
        r (double red)
        g (double green)
        b (double blue)
        cx' (double cx)
        cy' (double cy)
        size' (double size)
        top [cx' (+ cy' (* size' 0.577))]
        left [(- cx' (/ size' 2.0)) (- cy' (* size' 0.289))]
        right [(+ cx' (/ size' 2.0)) (- cy' (* size' 0.289))]
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
            :center ([0 0]), :red (1.0), :green (1.0), :blue (1.0)"
  [& {:keys [num-points turns start-radius end-radius center red green blue]
      :or {num-points 128
           turns 3
           start-radius 0.1
           end-radius 0.5
           center [0 0]
           red 1.0
           green 1.0
           blue 1.0}}]
  (let [[cx cy] center
        r (double red)
        g (double green)
        b (double blue)
        cx' (double cx)
        cy' (double cy)
        start-radius' (double start-radius)
        end-radius' (double end-radius)
        turns' (double turns)
        num-pts (long num-points)
        first-x (+ cx' start-radius')
        num-segments (double (dec num-pts))]
    (into [(t/blanked-point first-x cy')]
          (map (fn [i]
                 (let [t (/ (double i) num-segments)
                       angle (* TWO-PI turns' t)
                       radius (lerp start-radius' end-radius' t)
                       x (+ cx' (* radius (Math/cos angle)))
                       y (+ cy' (* radius (Math/sin angle)))]
                   (t/make-point x y r g b))))
          (range num-pts))))

(defn generate-star
  "Generate points for a star.
   Options: :num-points per segment (8), :spikes (5), :outer-radius (0.5),
            :inner-radius (0.25), :center ([0 0]), :red (1.0), :green (1.0), :blue (1.0)"
  [& {:keys [num-points spikes outer-radius inner-radius center red green blue]
      :or {num-points 8
           spikes 5
           outer-radius 0.5
           inner-radius 0.25
           center [0 0]
           red 1.0
           green 1.0
           blue 1.0}}]
  (let [[cx cy] center
        r (double red)
        g (double green)
        b (double blue)
        cx' (double cx)
        cy' (double cy)
        outer-radius' (double outer-radius)
        inner-radius' (double inner-radius)
        spikes' (long spikes)
        half-step (/ Math/PI (double spikes'))
        vertex-count (* 2 spikes')
        ;; First vertex at angle 0 with outer-radius
        first-x (+ cx' outer-radius')]
    (into [(t/blanked-point first-x cy')]
          (comp
           ;; vertex pairs for star points
           (map (fn [i]
                  (let [idx (mod (long i) vertex-count)
                        angle (* (double idx) half-step)
                        radius (if (even? idx) outer-radius' inner-radius')]
                    [(+ cx' (* radius (Math/cos angle)))
                     (+ cy' (* radius (Math/sin angle)))])))
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
  "Generate a circle frame. Params: :radius, :red, :green, :blue, :center, :num-points"
  (shape-generator->frame-generator generate-circle))

(def square-frame
  "Generate a square frame. Params: :size, :red, :green, :blue, :center, :num-points"
  (shape-generator->frame-generator generate-square))

(def triangle-frame
  "Generate a triangle frame. Params: :size, :red, :green, :blue, :center, :num-points"
  (shape-generator->frame-generator generate-triangle))

(def spiral-frame
  "Generate a spiral frame. Params: :turns, :start-radius, :end-radius, :red, :green, :blue, :center, :num-points"
  (shape-generator->frame-generator generate-spiral))

(def star-frame
  "Generate a star frame. Params: :spikes, :outer-radius, :inner-radius, :red, :green, :blue, :center, :num-points"
  (shape-generator->frame-generator generate-star))


(defn horizontal-line-frame
  "Generate a horizontal line spanning the projection area.
   Params: :length (default 1.0), :num-points (default 64), :red (1.0), :green (1.0), :blue (1.0)"
  [{:keys [length num-points red green blue]
    :or {length 1.0 num-points 64 red 1.0 green 1.0 blue 1.0}}]
  (let [half-length (/ (double length) 2.0)]
    (->> (generate-line :num-points num-points
                        :start [(- half-length) 0]
                        :end [half-length 0]
                        :red red
                        :green green
                        :blue blue)
         t/make-frame)))

(defn wave-frame
  "Generate a sine wave frame.
   Params: :amplitude (default 0.3), :frequency (default 2), :num-points (default 64),
           :red (1.0), :green (1.0), :blue (1.0)"
  [{:keys [amplitude frequency num-points red green blue]
    :or {amplitude 0.3 frequency 2 num-points 64 red 1.0 green 1.0 blue 1.0}}]
  (let [r (double red)
        g (double green)
        b (double blue)
        amplitude' (double amplitude)
        frequency' (double frequency)
        num-pts (long num-points)
        divisor (double (dec num-pts))]
    (->> (range num-pts)
         (into [] (map (fn [i]
                         (let [t (/ (double i) divisor)
                               x (- t 0.5)
                               y (* amplitude' (Math/sin (* TWO-PI frequency' t)))]
                           (t/make-point x y r g b)))))
         t/make-frame)))

(defn beam-fan-frame
  "Generate discrete beam points in a horizontal fan pattern.
   Points snake left-to-right then back for seamless looping.
   Blanking points separate each beam position.
   Params: :num-points (8), :repeats-per-beam (1), :red (1.0), :green (1.0), :blue (1.0)
   
   The :repeats-per-beam parameter controls brightness by repeating visible points.
   Higher values = more repeated points = brighter beams relative to other cues."
  [{:keys [num-points repeats-per-beam red green blue]
    :or {num-points 8 repeats-per-beam 1 red 1.0 green 1.0 blue 1.0}}]
  (let [r (double red)
        g (double green)
        b (double blue)
        x-min -0.99
        x-max 0.99
        y 0.0
        num-pts (long num-points)
        repeats (long repeats-per-beam)
        visible-count (* 2 repeats)
        forward-positions (if (= num-pts 1)
                            [0.0]
                            (->> (range num-pts)
                                 (into [] (map #(lerp x-min x-max (/ (double %) (double (dec num-pts))))))))
        backward-positions (when (> num-pts 2)
                             (vec (reverse (subvec forward-positions 1 (dec num-pts)))))
        all-positions (into forward-positions backward-positions)]
    (->> all-positions
         (into [] (mapcat (fn [x]
                            (into [(t/blanked-point x y)]
                                  (conj (vec (repeat visible-count (t/make-point x y r g b)))
                                        (t/blanked-point x y))))))
         t/make-frame)))


