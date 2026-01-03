(ns laser-show.animation.test-patterns
  "Test pattern generators for projector calibration.
   
   These patterns help visualize the projection area while adjusting
   corner pin safety zones and color calibration.
   
   Available patterns:
   - Grid: Shows coordinate grid for alignment
   - Corners: Shows corner markers and boundary box
   - Crosshair: Center reference point
   - Color bars: For color calibration"
  (:require [laser-show.animation.types :as t]))


;; Constants


(def ^:const WHITE {:r 1.0 :g 1.0 :b 1.0})
(def ^:const RED {:r 1.0 :g 0.0 :b 0.0})
(def ^:const GREEN {:r 0.0 :g 1.0 :b 0.0})
(def ^:const BLUE {:r 0.0 :g 0.0 :b 1.0})
(def ^:const YELLOW {:r 1.0 :g 1.0 :b 0.0})
(def ^:const CYAN {:r 0.0 :g 1.0 :b 1.0})
(def ^:const MAGENTA {:r 1.0 :g 0.0 :b 1.0})


;; Helper Functions


(defn- make-point
  "Create a laser point with color."
  [x y {:keys [r g b]}]
  (t/->LaserPoint x y r g b))

(defn- make-blank
  "Create a blank (blanked/invisible) point for moves."
  [x y]
  (t/->LaserPoint x y 0.0 0.0 0.0))

(defn- line-points
  "Generate points for a line from (x1,y1) to (x2,y2).
   Includes blank point at start for clean line transition."
  [x1 y1 x2 y2 color steps]
  (let [dx (/ (- x2 x1) steps)
        dy (/ (- y2 y1) steps)]
    (concat
      [(make-blank x1 y1)]
      (for [i (range (inc steps))]
        (make-point (+ x1 (* i dx))
                    (+ y1 (* i dy))
                    color)))))

(defn- box-points
  "Generate points for a rectangular box outline."
  [x-min y-min x-max y-max color steps-per-side]
  (concat
    (line-points x-min y-min x-max y-min color steps-per-side) ; Bottom
    (line-points x-max y-min x-max y-max color steps-per-side) ; Right
    (line-points x-max y-max x-min y-max color steps-per-side) ; Top
    (line-points x-min y-max x-min y-min color steps-per-side))) ; Left


;; Test Pattern Generators


(defn grid-pattern
  "Generate a grid test pattern.
   
   Shows the full -1 to 1 coordinate space with grid lines.
   Useful for checking alignment and distortion.
   
   Options:
   - :divisions - Number of grid divisions per axis (default 4)
   - :color - Grid line color (default white)"
  ([]
   (grid-pattern {}))
  ([{:keys [divisions color]
     :or {divisions 4
          color WHITE}}]
   (let [step (/ 2.0 divisions)
         pts-per-line 20
         vertical-lines (for [i (range (inc divisions))
                              :let [x (+ -1.0 (* i step))]]
                          (line-points x -1.0 x 1.0 color pts-per-line))
         horizontal-lines (for [i (range (inc divisions))
                                 :let [y (+ -1.0 (* i step))]]
                             (line-points -1.0 y 1.0 y color pts-per-line))]
      (t/make-frame
        (vec (apply concat (concat vertical-lines horizontal-lines)))))))

(defn corners-pattern
  "Generate corner markers and boundary box.
   
   Shows the four corners with distinct colors:
   - Top-left: Yellow
   - Top-right: Blue  
   - Bottom-left: Red
   - Bottom-right: Green
   
   Plus a white boundary box connecting them.
   
   Options:
   - :marker-size - Size of corner markers (default 0.1)"
  ([]
   (corners-pattern {}))
  ([{:keys [marker-size]
     :or {marker-size 0.1}}]
   (let [;; Corner marker points (cross at each corner)
         tl-marker (concat
                     (line-points -1.0 1.0 (+ -1.0 marker-size) 1.0 YELLOW 5)
                     (line-points -1.0 1.0 -1.0 (- 1.0 marker-size) YELLOW 5))
         tr-marker (concat
                     (line-points (- 1.0 marker-size) 1.0 1.0 1.0 BLUE 5)
                     (line-points 1.0 1.0 1.0 (- 1.0 marker-size) BLUE 5))
         bl-marker (concat
                     (line-points -1.0 -1.0 (+ -1.0 marker-size) -1.0 RED 5)
                     (line-points -1.0 -1.0 -1.0 (+ -1.0 marker-size) RED 5))
         br-marker (concat
                     (line-points (- 1.0 marker-size) -1.0 1.0 -1.0 GREEN 5)
                     (line-points 1.0 -1.0 1.0 (+ -1.0 marker-size) GREEN 5))
         ;; Boundary box
         boundary (box-points -1.0 -1.0 1.0 1.0 WHITE 20)]
      (t/make-frame
        (vec (concat boundary tl-marker tr-marker bl-marker br-marker))))))

(defn crosshair-pattern
  "Generate a center crosshair pattern.
   
   Shows center reference point with horizontal and vertical lines.
   Useful for checking center alignment.
   
   Options:
   - :size - Crosshair size (default 0.3)
   - :color - Color (default white)"
  ([]
   (crosshair-pattern {}))
  ([{:keys [size color]
     :or {size 0.3
          color WHITE}}]
   (let [h-line (line-points (- size) 0 size 0 color 15)
         v-line (line-points 0 (- size) 0 size color 15)
         ;; Small center dot (circle approximation)
         center-size 0.02
         center (for [i (range 8)
                      :let [angle (* i (/ Math/PI 4))
                            x (* center-size (Math/cos angle))
                            y (* center-size (Math/sin angle))]]
                  (make-point x y color))]
     (t/make-frame
       (vec (concat h-line v-line center))))))

(defn color-bars-pattern
  "Generate color test bars for calibration.
   
   Shows vertical bars of primary and secondary colors:
   Red, Green, Blue, Yellow, Cyan, Magenta, White
   
   Useful for color calibration and checking individual color channels."
  []
  (let [colors [RED GREEN BLUE YELLOW CYAN MAGENTA WHITE]
        bar-count (count colors)
        bar-width (/ 2.0 bar-count)
        bars (for [[idx color] (map-indexed vector colors)
                   :let [x-left (+ -1.0 (* idx bar-width))
                         x-right (+ x-left bar-width)
                         x-center (+ x-left (/ bar-width 2))]]
               (concat
                 ;; Vertical line for the bar
                 (line-points x-center -0.8 x-center 0.8 color 30)))]
    (t/make-frame
      (vec (apply concat bars)))))

(defn safety-zone-pattern
  "Generate a pattern showing the current safety zone.
   
   Takes corner pin parameters and draws the resulting quadrilateral
   so you can see exactly where the safe output area is.
   
   Parameters:
   - corners: Map with :tl-x :tl-y :tr-x :tr-y :bl-x :bl-y :br-x :br-y"
  [{:keys [tl-x tl-y tr-x tr-y bl-x bl-y br-x br-y]
    :or {tl-x -1.0 tl-y 1.0
         tr-x 1.0 tr-y 1.0
         bl-x -1.0 bl-y -1.0
         br-x 1.0 br-y -1.0}}]
  (let [;; Corner markers with distinct colors
        tl-marker (concat
                    [(make-blank tl-x tl-y)]
                    (for [i (range 5)]
                      (make-point tl-x tl-y YELLOW)))
        tr-marker (concat
                    [(make-blank tr-x tr-y)]
                    (for [i (range 5)]
                      (make-point tr-x tr-y BLUE)))
        bl-marker (concat
                    [(make-blank bl-x bl-y)]
                    (for [i (range 5)]
                      (make-point bl-x bl-y RED)))
        br-marker (concat
                    [(make-blank br-x br-y)]
                    (for [i (range 5)]
                      (make-point br-x br-y GREEN)))
        ;; Boundary lines
        bottom (line-points bl-x bl-y br-x br-y WHITE 20)
        right (line-points br-x br-y tr-x tr-y WHITE 20)
        top (line-points tr-x tr-y tl-x tl-y WHITE 20)
        left (line-points tl-x tl-y bl-x bl-y WHITE 20)]
    (t/make-frame
      (vec (concat bottom right top left tl-marker tr-marker bl-marker br-marker)))))


;; Pattern Selection


(defn get-test-pattern
  "Get a test pattern frame by keyword.
   
   Available patterns:
   - :grid - Coordinate grid
   - :corners - Corner markers and boundary
   - :crosshair - Center crosshair
   - :color-bars - Color test bars
   - :safety-zone - Safety zone visualization (requires :corner-params)
   
   Returns nil for unknown pattern or nil input."
  [pattern-key & {:keys [corner-params]}]
  (case pattern-key
    :grid (grid-pattern)
    :corners (corners-pattern)
    :crosshair (crosshair-pattern)
    :color-bars (color-bars-pattern)
    :safety-zone (safety-zone-pattern (or corner-params {}))
    nil))
