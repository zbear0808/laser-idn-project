(ns laser-show.animation.generators
  "Shape generators for laser animations using 2D float arrays.
   Each generator produces a LaserFrame (2D float array) directly.
   
   All colors use NORMALIZED VALUES (0.0 to 1.0) for maximum precision."
  (:require [laser-show.animation.types :as t]))

(set! *unchecked-math* :warn-on-boxed)

;; Geometry Constants

(def ^:const TWO-PI (* 2.0 Math/PI))

(defn lerp
  "Linear interpolation between a and b by factor t (0.0 to 1.0)."
  [a b t]
  (let [a (double a)
        b (double b)
        t (double t)]
    (+ a (* (- b a) t))))


;; Basic Shape Generators

(defn generate-circle
  "Generate circle as 2D float array frame.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Options:
   - :num-points - number of points (default 64)
   - :radius - radius in normalized coords (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] normalized color (0.0-1.0)"
  ^"[[D" [& {:keys [num-points radius center color]
             :or {num-points 64
                  radius 0.5
                  center [0 0]
                  color [1.0 1.0 1.0]}}]
  (let [num-points (long num-points)
        [cx cy] center
        [cr cg cb] color
        cx (double cx)
        cy (double cy)
        cr (double cr)
        cg (double cg)
        cb (double cb)
        radius (double radius)
        ;; Add 1 for blanking point at start
        total-points (inc num-points)
        frame (make-array Double/TYPE total-points t/POINT_SIZE)
        ;; First point at angle 0 for blanked positioning
        first-x (+ cx radius)
        first-y cy
        num-segments (double (dec num-points))]
    ;; First point is blanked for beam positioning
    (aset-double frame 0 t/X first-x)
    (aset-double frame 0 t/Y first-y)
    (aset-double frame 0 t/R (double 0.0))
    (aset-double frame 0 t/G (double 0.0))
    (aset-double frame 0 t/B (double 0.0))
    ;; Generate circle points
    (dotimes [i num-points]
      (let [idx (inc i)
            angle (* TWO-PI (/ (double i) num-segments))
            x (+ cx (* radius (double (Math/cos angle))))
            y (+ cy (* radius (double (Math/sin angle))))]
        (aset-double frame idx t/X x)
        (aset-double frame idx t/Y y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    frame))

(defn generate-line
  "Generate line from point A to point B as 2D float array.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Options:
   - :num-points - number of points (default 32)
   - :start - [x1 y1] start position (default [-0.5 0])
   - :end - [x2 y2] end position (default [0.5 0])
   - :color - [r g b] normalized color (0.0-1.0)"
  ^"[[D" [& {:keys [num-points start end color]
             :or {num-points 32
                  start [-0.5 0]
                  end [0.5 0]
                  color [1.0 1.0 1.0]}}]
  (let [num-points (long num-points)
        [x1 y1] start
        [x2 y2] end
        [cr cg cb] color
        x1 (double x1)
        y1 (double y1)
        x2 (double x2)
        y2 (double y2)
        cr (double cr)
        cg (double cg)
        cb (double cb)
        ;; Add 1 for blanking point at start
        total-points (inc num-points)
        frame (make-array Double/TYPE total-points t/POINT_SIZE)
        divisor (double (dec num-points))]
    ;; First point is blanked for beam positioning
    (aset-double frame 0 t/X x1)
    (aset-double frame 0 t/Y y1)
    (aset-double frame 0 t/R (double 0.0))
    (aset-double frame 0 t/G (double 0.0))
    (aset-double frame 0 t/B (double 0.0))
    ;; Generate line points
    (dotimes [i num-points]
      (let [idx (inc i)
            t (/ (double i) divisor)
            x (lerp x1 x2 t)
            y (lerp y1 y2 t)]
        (aset-double frame idx t/X x)
        (aset-double frame idx t/Y y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    frame))

(defn generate-square
  "Generate square as 2D float array.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Options:
   - :num-points - points per side (default 16)
   - :size - side length in normalized coords (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] normalized color (0.0-1.0)"
  ^"[[D" [& {:keys [num-points size center color]
             :or {num-points 16
                  size 0.5
                  center [0 0]
                  color [1.0 1.0 1.0]}}]
  (let [num-points (long num-points)
        [cx cy] center
        [cr cg cb] color
        cx (double cx)
        cy (double cy)
        cr (double cr)
        cg (double cg)
        cb (double cb)
        half (/ (double size) 2.0)
        ;; Corners: bottom-left, bottom-right, top-right, top-left
        bl-x (- cx half) bl-y (- cy half)
        br-x (+ cx half) br-y (- cy half)
        tr-x (+ cx half) tr-y (+ cy half)
        tl-x (- cx half) tl-y (+ cy half)
        ;; 4 sides * num-points + 1 for blanking
        total-points (inc (* 4 num-points))
        frame (make-array Double/TYPE total-points t/POINT_SIZE)
        divisor (double num-points)]
    ;; First point is blanked for beam positioning
    (aset-double frame 0 t/X bl-x)
    (aset-double frame 0 t/Y bl-y)
    (aset-double frame 0 t/R (double 0.0))
    (aset-double frame 0 t/G (double 0.0))
    (aset-double frame 0 t/B (double 0.0))
    ;; Bottom: left to right
    (dotimes [i num-points]
      (let [idx (+ 1 i)
            t (/ (double i) divisor)
            x (lerp bl-x br-x t)]
        (aset-double frame idx t/X x)
        (aset-double frame idx t/Y bl-y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    ;; Right: bottom to top
    (dotimes [i num-points]
      (let [idx (+ 1 num-points i)
            t (/ (double i) divisor)
            y (lerp br-y tr-y t)]
        (aset-double frame idx t/X br-x)
        (aset-double frame idx t/Y y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    ;; Top: right to left
    (dotimes [i num-points]
      (let [idx (+ 1 (* 2 num-points) i)
            t (/ (double i) divisor)
            x (lerp tr-x tl-x t)]
        (aset-double frame idx t/X x)
        (aset-double frame idx t/Y tr-y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    ;; Left: top to bottom
    (dotimes [i num-points]
      (let [idx (+ 1 (* 3 num-points) i)
            t (/ (double i) divisor)
            y (lerp tl-y bl-y t)]
        (aset-double frame idx t/X tl-x)
        (aset-double frame idx t/Y y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    frame))

(defn generate-triangle
  "Generate triangle as 2D float array.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Options:
   - :num-points - points per side (default 21)
   - :size - size in normalized coords (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] normalized color (0.0-1.0)"
  ^"[[D" [& {:keys [num-points size center color]
             :or {num-points 21
                  size 0.5
                  center [0 0]
                  color [1.0 1.0 1.0]}}]
  (let [num-points (long num-points)
        [cx cy] center
        [cr cg cb] color
        cx (double cx)
        cy (double cy)
        cr (double cr)
        cg (double cg)
        cb (double cb)
        size (double size)
        ;; Equilateral triangle vertices
        top-x cx
        top-y (+ cy (* size (double 0.577)))
        left-x (- cx (/ size (double 2.0)))
        left-y (- cy (* size (double 0.289)))
        right-x (+ cx (/ size (double 2.0)))
        right-y (- cy (* size (double 0.289)))
        ;; 3 sides * num-points + 1 for blanking
        total-points (inc (* 3 num-points))
        frame (make-array Double/TYPE total-points t/POINT_SIZE)
        divisor (double num-points)]
    ;; First point is blanked for beam positioning
    (aset-double frame 0 t/X top-x)
    (aset-double frame 0 t/Y top-y)
    (aset-double frame 0 t/R (double 0.0))
    (aset-double frame 0 t/G (double 0.0))
    (aset-double frame 0 t/B (double 0.0))
    ;; Top to right
    (dotimes [i num-points]
      (let [idx (+ 1 i)
            t (/ (double i) divisor)
            x (lerp top-x right-x t)
            y (lerp top-y right-y t)]
        (aset-double frame idx t/X x)
        (aset-double frame idx t/Y y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    ;; Right to left
    (dotimes [i num-points]
      (let [idx (+ 1 num-points i)
            t (/ (double i) divisor)
            x (lerp right-x left-x t)
            y (lerp right-y left-y t)]
        (aset-double frame idx t/X x)
        (aset-double frame idx t/Y y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    ;; Left to top
    (dotimes [i num-points]
      (let [idx (+ 1 (* 2 num-points) i)
            t (/ (double i) divisor)
            x (lerp left-x top-x t)
            y (lerp left-y top-y t)]
        (aset-double frame idx t/X x)
        (aset-double frame idx t/Y y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    frame))

(defn generate-spiral
  "Generate spiral as 2D float array.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Options:
   - :num-points - total number of points (default 128)
   - :turns - number of spiral turns (default 3)
   - :start-radius - inner radius (default 0.1)
   - :end-radius - outer radius (default 0.5)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] normalized color (0.0-1.0)"
  ^"[[D" [& {:keys [num-points turns start-radius end-radius center color]
             :or {num-points 128
                  turns 3
                  start-radius 0.1
                  end-radius 0.5
                  center [0 0]
                  color [1.0 1.0 1.0]}}]
  (let [num-points (long num-points)
        [cx cy] center
        [cr cg cb] color
        cx (double cx)
        cy (double cy)
        cr (double cr)
        cg (double cg)
        cb (double cb)
        start-radius (double start-radius)
        end-radius (double end-radius)
        turns (double turns)
        ;; Add 1 for blanking point at start
        total-points (inc num-points)
        frame (make-array Double/TYPE total-points t/POINT_SIZE)
        ;; First point at center of spiral (start-radius, angle 0)
        first-x (+ cx start-radius)
        first-y cy
        divisor (double (dec num-points))]
    ;; First point is blanked for beam positioning
    (aset-double frame 0 t/X first-x)
    (aset-double frame 0 t/Y first-y)
    (aset-double frame 0 t/R (double 0.0))
    (aset-double frame 0 t/G (double 0.0))
    (aset-double frame 0 t/B (double 0.0))
    ;; Generate spiral points
    (dotimes [i num-points]
      (let [idx (inc i)
            t (/ (double i) divisor)
            angle (* TWO-PI turns t)
            radius (double (lerp start-radius end-radius t))
            x (+ cx (* radius (Math/cos angle)))
            y (+ cy (* radius (Math/sin angle)))]
        (aset-double frame idx t/X x)
        (aset-double frame idx t/Y y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    frame))

(defn generate-star
  "Generate star as 2D float array.
   Per IDN-Stream spec Section 6.2: First point must be blanked for beam positioning.
   Options:
   - :num-points - points per segment (default 8)
   - :spikes - number of star spikes (default 5)
   - :outer-radius - outer radius (default 0.5)
   - :inner-radius - inner radius (default 0.25)
   - :center - [cx cy] center position (default [0 0])
   - :color - [r g b] normalized color (0.0-1.0)"
  ^"[[D" [& {:keys [num-points spikes outer-radius inner-radius center color]
             :or {num-points 8
                  spikes 5
                  outer-radius 0.5
                  inner-radius 0.25
                  center [0 0]
                  color [1.0 1.0 1.0]}}]
  (let [num-points (long num-points)
        spikes (long spikes)
        [cx cy] center
        [cr cg cb] color
        cx (double cx)
        cy (double cy)
        cr (double cr)
        cg (double cg)
        cb (double cb)
        outer-radius (double outer-radius)
        inner-radius (double inner-radius)
        angle-step (/ TWO-PI (double spikes))
        half-step (/ angle-step 2.0)
        ;; Generate vertices alternating outer/inner
        num-vertices (inc (* 2 spikes)) ;; +1 to close star
        vertices (double-array (* num-vertices 2)) ;; x, y pairs
        two-spikes (* 2 spikes)
        _ (dotimes [i two-spikes]
            (let [angle (* (double i) half-step)
                  radius (if (even? i) outer-radius inner-radius)
                  vx (+ cx (* radius (Math/cos angle)))
                  vy (+ cy (* radius (Math/sin angle)))]
              (aset-double vertices (* i 2) vx)
              (aset-double vertices (inc (* i 2)) vy)))
        ;; Close the star (copy first vertex to last)
        _ (do (aset-double vertices (* two-spikes 2) (aget vertices 0))
              (aset-double vertices (inc (* two-spikes 2)) (aget vertices 1)))
        ;; num-vertices - 1 edges, each with num-points, + 1 for blanking
        num-segments (long (dec num-vertices))
        total-points (inc (* num-segments num-points))
        frame (make-array Double/TYPE total-points t/POINT_SIZE)
        divisor (double num-points)]
    ;; First point is blanked for beam positioning
    (aset-double frame 0 t/X (aget vertices 0))
    (aset-double frame 0 t/Y (aget vertices 1))
    (aset-double frame 0 t/R (double 0.0))
    (aset-double frame 0 t/G (double 0.0))
    (aset-double frame 0 t/B (double 0.0))
    ;; Generate star edges
    (dotimes [edge num-segments]
      (let [edge2 (* edge 2)
            v1-x (aget vertices edge2)
            v1-y (aget vertices (inc edge2))
            edge1-2 (* (inc edge) 2)
            v2-x (aget vertices edge1-2)
            v2-y (aget vertices (inc edge1-2))
            base-idx (+ 1 (* edge num-points))]
        (dotimes [i num-points]
          (let [idx (+ base-idx i)
                t (/ (double i) divisor)
                x (lerp v1-x v2-x t)
                y (lerp v1-y v2-y t)]
            (aset-double frame idx t/X x)
            (aset-double frame idx t/Y y)
            (aset-double frame idx t/R cr)
            (aset-double frame idx t/G cg)
            (aset-double frame idx t/B cb)))))
    frame))


;; Frame Generator Functions (for preset system)

(defn circle-frame
  "Generate a circle frame. Params: :radius, :color, :center, :num-points"
  ^"[[D" [params]
  (apply generate-circle (mapcat identity params)))

(defn square-frame
  "Generate a square frame. Params: :size, :color, :center, :num-points"
  ^"[[D" [params]
  (apply generate-square (mapcat identity params)))

(defn triangle-frame
  "Generate a triangle frame. Params: :size, :color, :center, :num-points"
  ^"[[D" [params]
  (apply generate-triangle (mapcat identity params)))

(defn spiral-frame
  "Generate a spiral frame. Params: :turns, :start-radius, :end-radius, :color, :center, :num-points"
  ^"[[D" [params]
  (apply generate-spiral (mapcat identity params)))

(defn star-frame
  "Generate a star frame. Params: :spikes, :outer-radius, :inner-radius, :color, :center, :num-points"
  ^"[[D" [params]
  (apply generate-star (mapcat identity params)))

(defn horizontal-line-frame
  "Generate a horizontal line spanning the projection area.
   Params: :length (default 1.0), :color"
  ^"[[D" [{:keys [length color]
           :or {length 1.0 color [1.0 1.0 1.0]}}]
  (let [half-length (/ (double length) (double 2.0))]
    (generate-line :num-points 64
                   :start [(- half-length) 0]
                   :end [half-length 0]
                   :color color)))

(defn wave-frame
  "Generate a sine wave frame.
   Params: :amplitude (default 0.3), :frequency (default 2), :color"
  ^"[[D" [{:keys [amplitude frequency color]
           :or {amplitude 0.3 frequency 2 color [1.0 1.0 1.0]}}]
  (let [[cr cg cb] color
        cr (double cr)
        cg (double cg)
        cb (double cb)
        amplitude (double amplitude)
        frequency (double frequency)
        num-points 64
        total-points (inc num-points) ;; +1 for blanking
        frame (make-array Double/TYPE total-points t/POINT_SIZE)
        divisor (double 63.0)]
    ;; First point is blanked for beam positioning
    (aset-double frame 0 t/X (double -0.5))
    (aset-double frame 0 t/Y (double 0.0))
    (aset-double frame 0 t/R (double 0.0))
    (aset-double frame 0 t/G (double 0.0))
    (aset-double frame 0 t/B (double 0.0))
    ;; Generate wave points
    (dotimes [i num-points]
      (let [idx (inc i)
            t (/ (double i) divisor)
            x (- t (double 0.5))
            y (* amplitude (double (Math/sin (* TWO-PI frequency t))))]
        (aset-double frame idx t/X x)
        (aset-double frame idx t/Y y)
        (aset-double frame idx t/R cr)
        (aset-double frame idx t/G cg)
        (aset-double frame idx t/B cb)))
    frame))

(defn beam-fan-frame
  "Generate discrete beam points in a horizontal fan pattern.
   Points snake left-to-right then back for seamless looping.
   Per IDN-Stream spec Section 6.2: Blanking points separate each beam.
   Params: :num-points (default 8), :color"
  ^"[[D" [{:keys [num-points color]
           :or {num-points 8 color [1.0 1.0 1.0]}}]
  (let [[cr cg cb] color
        cr (double cr)
        cg (double cg)
        cb (double cb)
        x-min (double -0.99)
        x-max (double 0.99)
        y (double 0.0)
        num-points (long num-points)
        ;; Calculate positions
        forward-count num-points
        backward-count (if (> num-points 2) (- num-points 2) 0)
        total-beams (+ forward-count backward-count)
        ;; Each beam: blank + lit + lit + blank = 4 points
        total-points (* total-beams 4)
        frame (make-array Double/TYPE total-points t/POINT_SIZE)]
    ;; Forward positions
    (dotimes [beam-i forward-count]
      (let [beam-x (if (= num-points 1)
                     (double 0.0)
                     (lerp x-min x-max (/ (double beam-i) (double (dec num-points)))))
            base-idx (* beam-i 4)]
        ;; Blanked at position
        (aset-double frame base-idx t/X beam-x)
        (aset-double frame base-idx t/Y y)
        (aset-double frame base-idx t/R (double 0.0))
        (aset-double frame base-idx t/G (double 0.0))
        (aset-double frame base-idx t/B (double 0.0))
        ;; Lit point 1
        (aset-double frame (+ base-idx 1) t/X beam-x)
        (aset-double frame (+ base-idx 1) t/Y y)
        (aset-double frame (+ base-idx 1) t/R cr)
        (aset-double frame (+ base-idx 1) t/G cg)
        (aset-double frame (+ base-idx 1) t/B cb)
        ;; Lit point 2
        (aset-double frame (+ base-idx 2) t/X beam-x)
        (aset-double frame (+ base-idx 2) t/Y y)
        (aset-double frame (+ base-idx 2) t/R cr)
        (aset-double frame (+ base-idx 2) t/G cg)
        (aset-double frame (+ base-idx 2) t/B cb)
        ;; Blanked at position
        (aset-double frame (+ base-idx 3) t/X beam-x)
        (aset-double frame (+ base-idx 3) t/Y y)
        (aset-double frame (+ base-idx 3) t/R (double 0.0))
        (aset-double frame (+ base-idx 3) t/G (double 0.0))
        (aset-double frame (+ base-idx 3) t/B (double 0.0))))
    ;; Backward positions (reverse middle positions)
    (when (> num-points 2)
      (dotimes [beam-i backward-count]
        (let [;; Reverse middle positions: (num-points-2) down to 1
              fwd-idx (- (- num-points 2) beam-i)
              beam-x (lerp x-min x-max (/ (double fwd-idx) (double (dec num-points))))
              base-idx (* (+ forward-count beam-i) 4)]
          ;; Blanked at position
          (aset-double frame base-idx t/X beam-x)
          (aset-double frame base-idx t/Y y)
          (aset-double frame base-idx t/R (double 0.0))
          (aset-double frame base-idx t/G (double 0.0))
          (aset-double frame base-idx t/B (double 0.0))
          ;; Lit point 1
          (aset-double frame (+ base-idx 1) t/X beam-x)
          (aset-double frame (+ base-idx 1) t/Y y)
          (aset-double frame (+ base-idx 1) t/R cr)
          (aset-double frame (+ base-idx 1) t/G cg)
          (aset-double frame (+ base-idx 1) t/B cb)
          ;; Lit point 2
          (aset-double frame (+ base-idx 2) t/X beam-x)
          (aset-double frame (+ base-idx 2) t/Y y)
          (aset-double frame (+ base-idx 2) t/R cr)
          (aset-double frame (+ base-idx 2) t/G cg)
          (aset-double frame (+ base-idx 2) t/B cb)
          ;; Blanked at position
          (aset-double frame (+ base-idx 3) t/X beam-x)
          (aset-double frame (+ base-idx 3) t/Y y)
          (aset-double frame (+ base-idx 3) t/R (double 0.0))
          (aset-double frame (+ base-idx 3) t/G (double 0.0))
          (aset-double frame (+ base-idx 3) t/B (double 0.0)))))
    frame))
