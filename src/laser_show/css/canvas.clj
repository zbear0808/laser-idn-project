(ns laser-show.css.canvas
  "Pre-built JavaFX Colors and Fonts for canvas drawing, derived from the theme.
   Canvas code uses these instead of calling Color/web or Font. while drawing.
   
   Timeline tokens follow plans/2026-11-08-timeline-rework/05-visual-design.md §3.1."
  (:require [laser-show.css.theme :as theme])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.function Function]
           [javafx.scene.paint Color]
           [javafx.scene.text Font FontWeight]))


(defn- web ^Color [hex] (Color/web hex))

(defn- web-a ^Color [hex alpha] (Color/web hex (double alpha)))

(def ^:private ^ConcurrentHashMap color-cache (ConcurrentHashMap.))

(defn color
  "Color for a hex string at an alpha (default 1.0), cached."
  (^Color [hex] (color hex 1.0))
  (^Color [hex alpha]
   (.computeIfAbsent color-cache [hex (double alpha)]
                     (reify Function
                       (apply [_ _] (web-a hex alpha))))))

(defn with-alpha
  "c with its opacity set to alpha, cached."
  ^Color [^Color c alpha]
  (.computeIfAbsent color-cache [c (double alpha)]
                    (reify Function
                      (apply [_ _] (Color. (.getRed c) (.getGreen c) (.getBlue c) (double alpha))))))

(defn luminance
  "Relative luminance (WCAG) of a Color."
  ^double [^Color c]
  (let [lin (fn [v] (let [v (double v)]
                      (if (<= v 0.03928) (/ v 12.92) (Math/pow (/ (+ v 0.055) 1.055) 2.4))))]
    (+ (* 0.2126 (lin (.getRed c)))
       (* 0.7152 (lin (.getGreen c)))
       (* 0.0722 (lin (.getBlue c))))))


(let [{:keys [bg-base blue orange red text-muted]} theme/base-colors
      {:keys [text-primary text-secondary]} theme/semantic-colors]
  (def tl-bg (web bg-base))
  (def tl-lane (web "#181818"))
  (def tl-lane-alt (web "#1C1C1C"))
  (def tl-lane-folder (web "#222222"))
  (def tl-lane-selected (web-a blue 0.10))
  (def tl-grid-bar (web "#3A3A3A"))
  (def tl-grid-beat (web "#2A2A2A"))
  (def tl-grid-sub (web "#222222"))
  (def tl-ruler-bg (web "#161616"))
  (def tl-ruler-text (web text-secondary))
  (def tl-ruler-text-dim (web text-muted))
  (def tl-playhead (web red))
  (def tl-cursor (web orange))
  (def tl-loop-on (web-a "#2EAD8F" 0.9))
  (def tl-loop-off (web-a "#2EAD8F" 0.35))
  (def tl-loop-tint (web-a "#2EAD8F" 0.05))
  (def tl-selection (web text-primary))
  (def tl-hover (web-a "#FFFFFF" 0.4))
  (def tl-hatch (web-a "#FFFFFF" 0.15))
  (def tl-marquee-fill (web-a blue 0.15))
  (def tl-marquee-stroke (web blue))
  (def tl-text-dark (web "#121212"))
  (def tl-text-light (web "#FFFFFF"))
  (def tl-text-muted (web text-muted))
  (def tl-ghost-text (web "#5A5A5A")))

(def tl-font-ruler (Font/font "System" FontWeight/BOLD 11.0))
(def tl-font-clip (Font/font "System" FontWeight/BOLD 11.0))
(def tl-font-small (Font/font "System" 10.0))
(def tl-font-hint (Font/font "System" 12.0))
