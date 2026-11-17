(ns laser-show.idn.frame-rules
  "The last check before a frame is encoded for IDN-Stream.

   sanitize-frame drops points the encoder can't send safely and enforces the
   frame rules of IDN-Stream §6.2: a non-empty frame starts with an invisible
   sample and has at least 2 samples. Range clamping stays in the encoder."
  (:require [laser-show.animation.types :as t]))

(defn- good-value? [v]
  (and (number? v) (Double/isFinite (double v))))

(defn- good-point? [pt]
  (and (good-value? (get pt t/X))
       (good-value? (get pt t/Y))
       (good-value? (get pt t/R))
       (good-value? (get pt t/G))
       (good-value? (get pt t/B))))

(defn sanitize-frame
  "Drop every point with a nil or non-finite x, y, r, g or b. If the first
   remaining point is lit, put a dark copy of it in front; if only one point
   remains, send it twice. An empty frame stays empty."
  [frame]
  (let [points (if (every? good-point? frame) (vec frame) (filterv good-point? frame))
        first-pt (first points)
        points (if (and first-pt (not (t/blanked? first-pt)))
                 (into [[(get first-pt t/X) (get first-pt t/Y) 0.0 0.0 0.0]] points)
                 points)]
    (if (= 1 (count points))
      (conj points (first points))
      points)))
