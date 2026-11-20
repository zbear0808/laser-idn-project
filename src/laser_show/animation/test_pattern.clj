(ns laser-show.animation.test-pattern
  "The test pattern a projector shows while it is calibrated or identified
   (zones plan Z1, 03 §4). Pure: frames from numbers.

   Two layers. The content layer (a grid and a small circle) goes through the
   projector's calibration effects, so the corner pin shows. The safety
   layer (the limit's outline and a dashed outline just outside each blocked
   area) is drawn in output space, after them, so it shows where the limit
   really is. Both then go through the safety stage."
  (:require [laser-show.animation.safety :as safety]))

(def ^:const identify-ms
  "How long Identify flashes the test pattern."
  3000)

(def ^:private ^:const flash-ms
  "Identify's pattern is lit this long, then dark this long."
  250)

(defn- white [brightness]
  (let [b (double brightness)] [b b b]))

(defn- line
  "A lit line from a to b in n steps, starting with a dark point at a."
  [[ax ay] [bx by] n colour]
  (into [[(double ax) (double ay) 0.0 0.0 0.0]]
        (for [i (range (inc n))]
          (let [f (/ (double i) n)]
            (into [(+ ax (* f (- bx ax))) (+ ay (* f (- by ay)))] colour)))))

(defn content-layer
  "A 5 × 5 grid of lines across -0.9..0.9 and a small circle in the middle,
   white at brightness: about 400 points."
  [brightness]
  (let [colour (white brightness)
        ticks [-0.9 -0.45 0.0 0.45 0.9]
        lines (concat (for [x ticks] (line [x -0.9] [x 0.9] 32 colour))
                      (for [y ticks] (line [-0.9 y] [0.9 y] 32 colour)))
        circle (let [r 0.1 n 48]
                 (into [[r 0.0 0.0 0.0 0.0]]
                       (for [i (range (inc n))]
                         (let [a (* 2.0 Math/PI (/ (double i) n))]
                           (into [(* r (Math/cos a)) (* r (Math/sin a))] colour)))))]
    (into [] cat (concat lines [circle]))))

(defn- outline
  "A rectangle's outline, lit along each side in steps, starting dark at its
   first corner. With dashes, every other step is dark."
  [[x0 y0 x1 y1] steps colour dashes?]
  (let [corners [[x0 y0] [x1 y0] [x1 y1] [x0 y1] [x0 y0]]]
    (into [[(double x0) (double y0) 0.0 0.0 0.0]]
          (for [[[ax ay] [bx by]] (partition 2 1 corners)
                i (range 1 (inc steps))]
            (let [f (/ (double i) steps)
                  c (if (and dashes? (odd? (quot (dec i) 2))) [0.0 0.0 0.0] colour)]
              (into [(+ ax (* f (- bx ax))) (+ ay (* f (- by ay)))] c))))))

(defn safety-layer
  "The zone's limit outlined, and each blocked area outlined in dashes 0.01
   outside it (so the stage doesn't blank it), white at brightness, about
   150 points per rectangle."
  [s brightness]
  (let [{:keys [limit blocked]} (safety/normalize s)
        colour (white brightness)
        grow (fn [[x0 y0 x1 y1]] [(- x0 0.01) (- y0 0.01) (+ x1 0.01) (+ y1 0.01)])]
    (into (outline limit 36 colour false)
          cat
          (for [b blocked] (outline (grow b) 36 colour true)))))

(defn identify-on?
  "Is Identify's flash lit at now-ms, for a flash that ends at until-ms? Lit
   for 250 ms, dark for 250 ms, from until-ms minus identify-ms to until-ms."
  [now-ms until-ms]
  (boolean
   (when until-ms
     (let [elapsed (- (long now-ms) (- (long until-ms) identify-ms))]
       (and (<= 0 elapsed) (< (long now-ms) (long until-ms))
            (< (mod elapsed (* 2 flash-ms)) flash-ms))))))
