(ns laser-show.animation.effects.intensity
  "Intensity effects for laser frames.
   Includes intensity control, blackout, threshold, and trace-fade effects.
   
   NOTE: All color values are NORMALIZED (0.0-1.0), not 8-bit (0-255).
   This provides full precision for intensity calculations.
   
   Points are 5-element vectors: [x y r g b]
   - x, y: position in [-1.0, 1.0]
   - r, g, b: color intensity in [0.0, 1.0]
   
   All effects support both static values and modulators:
   ;; Static
   {:effect-id :intensity :params {:amount 0.5}}
   
   ;; Modulated (modulator config map)
   {:effect-id :intensity :params {:amount {:type :sine :min 0.3 :max 1.0 :period 2.0}}}
   
   ;; Strobe:
   {:effect-id :intensity :params {:amount {:type :square :min 0.0 :max 1.0 :period 0.25}}}
   
   Per-point modulation (radial fade, wave pattern):
   {:effect-id :intensity :params {:amount {:type :radial :min 1.0 :max 0.3}}}
   {:effect-id :intensity :params {:amount {:type :pos-wave :axis :x :min 0.5 :max 1.0 :frequency 4.0}}}
   
   Implementation uses make-param-resolver for efficient per-point handling:
   - Static values: resolved once, no per-point overhead
   - Global modulators: resolved once, cached
   - Per-point modulators: evaluated per-point with (fn [x y idx] -> value)"
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.modulation :as mod]
            [laser-show.animation.time :as time]
            [laser-show.animation.types :as t]))
  
  (set! *warn-on-reflection* true)
  (set! *unchecked-math* :warn-on-boxed)
  
  
  ;; Intensity Effect (replaces dim/brighten)
  
  
  (defn- intensity-xf [time-ms bpm params ctx]
    (let [get-amount (effects/make-param-resolver :amount params time-ms bpm ctx)]
      (map-indexed
       (fn [idx pt]
         (let [x (pt t/X) y (pt t/Y)
               amount (double (get-amount x y idx))
               r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))]
           (t/update-point-rgb pt (* r amount) (* g amount) (* b amount)))))))

(effects/register-effect!
 {:id :intensity
  :name "Intensity"
  :category :intensity
  :timing :static
  :parameters [{:key :amount
                :label "Amount"
                :type :float
                :default 1.0
                :min 0.0
                :max 3.0}]
  :apply-transducer intensity-xf})


;; Blackout Effect


(defn- blackout-xf [time-ms bpm params ctx]
  (let [resolved (effects/resolve-params-global params time-ms bpm ctx)
        enabled (:enabled? resolved)]
    (if enabled
      (map (fn [pt]
             (t/update-point-rgb pt 0.0 0.0 0.0)))
      (map identity))))

(effects/register-effect!
 {:id :blackout
  :name "Blackout"
  :category :intensity
  :timing :static
  :parameters [{:key :enabled
                :label "Enabled"
                :type :bool
                :default false}]
  :apply-transducer blackout-xf})


;; Threshold Effect (cut low intensities)


(defn- threshold-xf [time-ms bpm params ctx]
  (let [get-threshold (effects/make-param-resolver :threshold params time-ms bpm ctx)]
    (map-indexed
     (fn [idx pt]
       (let [x (pt t/X) y (pt t/Y)
             r (double (pt t/R)) g (double (pt t/G)) b (double (pt t/B))
             threshold (double (get-threshold x y idx))
             max-val (Math/max (Math/max r g) b)]
         (if (< max-val threshold)
           (t/update-point-rgb pt 0.0 0.0 0.0)
           pt))))))

(effects/register-effect!
 {:id :threshold
  :name "Threshold"
  :category :intensity
  :timing :static
  :parameters [{:key :threshold
                :label "Threshold"
                :type :float
                :default 0.04
                :min 0.0
                :max 1.0}]
  :apply-transducer threshold-xf})


;; Trace-Fade Effect (Comet Trail)


(defn- calculate-fade
  "Calculate fade value based on wave shape.
   t is normalized distance from head (0.0 at head, 1.0 at tail end)."
  ^double [wave-shape ^double t]
  (case wave-shape
    :linear (- 1.0 t)
    :exp (Math/exp (* (- t) 3.0))
    :smooth (let [inv (- 1.0 t)] (* inv inv))
    (- 1.0 t)))

(defn- trace-fade-xf
  "Transducer for trace-fade (comet trail) effect.
   Creates a traveling bright head with a fading tail through point sequence.
   
   The cycle extends beyond 1.0 to (1.0 + tail-length) so the entire trail
   passes through before looping. This ensures the frame goes completely
   blank before the next cycle starts."
  [time-ms bpm params ctx]
  (let [;; Resolve modulatable parameters
        get-period (effects/make-param-resolver :period params time-ms bpm ctx)
        get-tail-length (effects/make-param-resolver :tail-length params time-ms bpm ctx)
        get-min (effects/make-param-resolver :min-brightness params time-ms bpm ctx)
        
        ;; Get timing context
        timing-ctx (:timing-ctx ctx)
        
        ;; Static params (not modulatable per-point)
        time-unit (get params :time-unit :beats)
        wave-shape (get params :wave-shape :exp)
        direction (get params :direction :forward)
        point-count (long (or (:point-count ctx) 1))
        
        ;; Calculate time value based on time-unit
        time-value (double
                    (if (= time-unit :seconds)
                      ;; For seconds, use accumulated-ms or time-ms
                      (/ (double (or (:accumulated-ms timing-ctx) time-ms 0.0)) 1000.0)
                      ;; For beats, use effective-beats
                      (double (or (:effective-beats timing-ctx)
                                  (when (and time-ms bpm (pos? (double bpm)))
                                    (time/ms->beats time-ms bpm))
                                  0.0))))]
    
    (map-indexed
     (fn [idx pt]
       (let [x (pt t/X)
             y (pt t/Y)
             ;; Resolve per-point modulatable params
             period (double (get-period x y idx))
             tail-length (double (get-tail-length x y idx))
             min-val (double (get-min x y idx))
             
             ;; Total cycle length: head travels from 0 to (1 + tail-length)
             ;; This ensures tail fully passes the last point before looping
             cycle-length (+ 1.0 tail-length)
             
             ;; Calculate head position (0.0 to cycle-length, then wraps)
             head-pos (double (mod (/ time-value (max 0.001 period)) cycle-length))
             
             ;; Calculate this point's position in sequence (0.0 to 1.0)
             pos (/ (double idx) (max 1.0 (dec (double point-count))))
             
             ;; For reverse direction, flip the point positions
             effective-pos (double (if (= direction :reverse)
                                     (- 1.0 pos)
                                     pos))
             
             ;; Calculate distance from head (no wrap-around)
             ;; Distance is how far behind the head this point is
             distance (- head-pos effective-pos)
             
             ;; Calculate intensity based on distance and tail-length
             ;; Point is visible if 0 <= distance <= tail-length
             ;; Peak brightness is always 1.0
             intensity (if (and (>= distance 0.0)
                                (<= distance tail-length))
                         (let [t (/ distance tail-length)
                               fade (calculate-fade wave-shape t)]
                           (+ min-val (* (- 1.0 min-val) fade)))
                         min-val)
             
             ;; Apply intensity to RGB
             r (double (pt t/R))
             g (double (pt t/G))
             b (double (pt t/B))]
         (t/update-point-rgb pt (* r intensity) (* g intensity) (* b intensity)))))))

(effects/register-effect!
 {:id :trace-fade
  :name "Trace Fade (Comet)"
  :category :intensity
  :timing :bpm
  :parameters [{:key :period
                :label "Period"
                :type :float
                :default 1.0
                :min 0.1
                :max 10.0}
               {:key :time-unit
                :label "Time Unit"
                :type :choice
                :default :beats
                :choices [:beats :seconds]}
               {:key :tail-length
                :label "Tail Length"
                :type :float
                :default 0.3
                :min 0.05
                :max 1.0}
               {:key :min-brightness
                :label "Min Brightness"
                :type :float
                :default 0.0
                :min 0.0
                :max 1.0}
               {:key :wave-shape
                :label "Fade Shape"
                :type :choice
                :default :exp
                :choices [:linear :exp :smooth]}
               {:key :direction
                :label "Direction"
                :type :choice
                :default :forward
                :choices [:forward :reverse]}]
  :apply-transducer trace-fade-xf})
