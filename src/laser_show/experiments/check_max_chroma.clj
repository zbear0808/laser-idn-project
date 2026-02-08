(ns laser-show.experiments.check-max-chroma
  (:require [laser-show.animation.colors :as colors]
            [laser-show.common.util :as u]))

(defn check-max-chroma [L]
  (let [hues (range 0 360 1)
        valid-chromas (for [h hues]
                        (loop [c 0.4] ; Start high and decrease
                          (let [[L a b-ok] (colors/oklch->oklab L c h)
                                [r g b] (colors/oklab->rgb L a b-ok)]
                            (if (and (<= 0.0 r 1.0)
                                     (<= 0.0 g 1.0)
                                     (<= 0.0 b 1.0))
                              c
                              (if (> c 0.0)
                                (recur (- c 0.005))
                                0.0)))))
        min-valid-c (apply min valid-chromas)
        max-valid-c (apply max valid-chromas)
        avg-valid-c (/ (reduce + valid-chromas) (count valid-chromas))]
    (println "L=" L)
    (println "Min Valid C (intersection of all hues):" min-valid-c)
    (println "Max Valid C (some hues support this):" max-valid-c)
    (println "Avg Valid C:" avg-valid-c)))

(check-max-chroma 0.70)
