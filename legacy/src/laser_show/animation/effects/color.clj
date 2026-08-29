(ns laser-show.animation.effects.color
  "Color effects for laser frames.
   
   Points are 5-element vectors [x y r g b]. Access via t/X, t/Y, t/R, t/G, t/B.
   Use t/update-point-rgb for color updates."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.common :as common]
            [laser-show.animation.colors :as colors]
            [laser-show.animation.types :as t]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)



(defn- hue-shift-xf [time-ms bpm params ctx]
  (let [get-degrees (effects/make-param-resolver :degrees params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (if (t/blanked? pt)
         pt
         (let [x (pt t/X) y (pt t/Y)
               degrees (double (get-degrees x y idx))
               r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))
               [h s v] (colors/normalized->hsv r g b)
               new-h (rem (+ (double h) degrees) 360.0)
               [nr ng nb] (colors/hsv->normalized new-h s v)]
           (t/update-point-rgb pt nr ng nb)))))))

(effects/register-effect!
 {:id :hue-shift
  :name "Hue Shift"
  :category :color
  :timing :static
  :ui-hints {:renderer :hue-shift-strip
             :default-mode :visual}
  :parameters [{:key :degrees
                :label "Degrees"
                :type :float
                :default 0.0
                :min -180.0
                :max 180.0}]
  :apply-transducer hue-shift-xf})



(defn- saturation-xf [time-ms bpm params ctx]
  (let [get-amount (effects/make-param-resolver :amount params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (if (t/blanked? pt)
         pt
         (let [x (pt t/X) y (pt t/Y)
               amount (double (get-amount x y idx))
               r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))
               [h s v] (colors/normalized->hsv r g b)
               new-s (common/clamp-normalized (* (double s) amount))
               [nr ng nb] (colors/hsv->normalized h new-s v)]
           (t/update-point-rgb pt nr ng nb)))))))

(effects/register-effect!
 {:id :saturation
  :name "Saturation"
  :category :color
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}]
  :apply-transducer saturation-xf})



(defn- color-filter-xf [time-ms bpm params ctx]
  (let [get-r-mult (effects/make-param-resolver :r-mult params time-ms bpm ctx)
        get-g-mult (effects/make-param-resolver :g-mult params time-ms bpm ctx)
        get-b-mult (effects/make-param-resolver :b-mult params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (let [x (pt t/X) y (pt t/Y)
             r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))
             r-mult (double (get-r-mult x y idx))
             g-mult (double (get-g-mult x y idx))
             b-mult (double (get-b-mult x y idx))]
         (t/update-point-rgb pt
                             (common/clamp-normalized (* r r-mult))
                             (common/clamp-normalized (* g g-mult))
                             (common/clamp-normalized (* b b-mult))))))))

(effects/register-effect!
 {:id :color-filter
  :name "Color Filter"
  :category :color
  :timing :static
  :parameters [{:key :r-mult
                :label "Red Multiplier"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}
               {:key :g-mult
                :label "Green Multiplier"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}
               {:key :b-mult
                :label "Blue Multiplier"
                :type :float
                :default 1.0
                :min 0.0
                :max 2.0}]
  :apply-transducer color-filter-xf})


(defn- set-hue-xf [time-ms bpm params ctx]
  (let [get-hue (effects/make-param-resolver :hue params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (let [r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))
             [_h s v] (colors/normalized->hsv r g b)
             v-dbl (double v)]
         ;; Only apply to non-black points with some saturation
         (if (and (pos? v-dbl) (not (t/blanked? pt)))
           (let [x (pt t/X) y (pt t/Y)
                 hue (get-hue x y idx)
                 [nr ng nb] (colors/hsv->normalized hue s v)]
             (t/update-point-rgb pt nr ng nb))
           pt))))))

(effects/register-effect!
 {:id :set-hue
  :name "Set Hue"
  :category :color
  :timing :static
  :ui-hints {:renderer :hue-slider
             :default-mode :visual}
  :parameters [{:key :hue
                :label "Hue"
                :type :float
                :default 0.0
                :min 0.0
                :max 360.0}]
  :apply-transducer set-hue-xf})

(defn- set-color-xf [time-ms bpm params ctx]
  (let [get-red (effects/make-param-resolver :red params time-ms bpm ctx)
        get-green (effects/make-param-resolver :green params time-ms bpm ctx)
        get-blue (effects/make-param-resolver :blue params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (if (t/blanked? pt)
         pt
         (let [x (pt t/X) y (pt t/Y)
               red (double (get-red x y idx))
               green (double (get-green x y idx))
               blue (double (get-blue x y idx))]
           (t/update-point-rgb pt red green blue)))))))

(effects/register-effect!
 {:id :set-color
  :name "Set Color"
  :category :color
  :timing :static
  :ui-hints {:renderer :set-color-picker
             :default-mode :visual}
  :parameters [{:key :red
                :label "Red"
                :type :float
                :default 1.0
                :min 0.0
                :max 1.0}
               {:key :green
                :label "Green"
                :type :float
                :default 1.0
                :min 0.0
                :max 1.0}
               {:key :blue
                :label "Blue"
                :type :float
                :default 1.0
                :min 0.0
                :max 1.0}]
  :apply-transducer set-color-xf})

(defn- oklab-hue-shift-xf [time-ms bpm params ctx]
  (let [get-degrees (effects/make-param-resolver :degrees params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (if (t/blanked? pt)
         pt
         (let [x (pt t/X) y (pt t/Y)
               degrees (double (get-degrees x y idx))
               r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))
               [L a b] (colors/rgb->oklab r g b)
               [L' C' h'] (colors/oklab->oklch L a b)
               new-h (rem (+ (double h') degrees) 360.0)
               [L'' a'' b''] (colors/oklch->oklab L' C' new-h)
               [nr ng nb] (colors/oklab->rgb L'' a'' b'')]
           (t/update-point-rgb pt nr ng nb)))))))

(effects/register-effect!
 {:id :oklab-hue-shift
  :name "Oklab Hue Shift"
  :category :color
  :timing :static
  :ui-hints {:renderer :oklab-hue-shift-strip
             :default-mode :visual}
  :parameters [{:key :degrees
                :label "Degrees"
                :type :float
                :default 0.0
                :min -180.0
                :max 180.0}]
  :apply-transducer oklab-hue-shift-xf})
