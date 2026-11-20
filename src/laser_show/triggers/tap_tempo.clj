(ns laser-show.triggers.tap-tempo
  "Pure tap tempo from timestamped taps: the one algorithm for the TAP
   button, a bound tap-tempo key and HIT (events/handlers/timing.clj), as
   beyond-vibe proved it (engine/tap_tempo.rs). A tap closer than
   :debounce-ms to the last one is a bounce and changes nothing; a gap longer
   than :reset-ms starts a new series and keeps the tempo until the series has
   an interval; the tempo is the median of the intervals of the last
   :max-taps taps, clamped to :min-bpm-:max-bpm, so one missed or doubled tap
   doesn't move it.")

(def default-options {:debounce-ms 100 :reset-ms 1500 :max-taps 5 :min-bpm 40.0 :max-bpm 300.0})

(defn make
  "A tap tempo with no taps yet."
  []
  {:taps [] :bpm nil :last-tap nil})

(defn- median [xs]
  (let [v (vec (sort xs))
        mid (quot (count v) 2)]
    (if (even? (count v))
      (/ (+ (v (dec mid)) (v mid)) 2.0)
      (double (v mid)))))

(defn tap
  "state after a tap at timestamp-ms (options: default-options). A bounce,
   or a tap from before the last one, returns state itself."
  ([state timestamp-ms] (tap state timestamp-ms default-options))
  ([state timestamp-ms {:keys [debounce-ms reset-ms max-taps min-bpm max-bpm]}]
   (let [last-tap (peek (:taps state))
         gap (when last-tap (- timestamp-ms last-tap))]
     (if (and gap (< gap debounce-ms))
       state
       (let [series (if (and gap (> gap reset-ms)) [] (:taps state))
             taps (conj series timestamp-ms)
             ;; A fresh vector: a subvec would hold the old one
             taps (if (> (count taps) max-taps) (vec (take-last max-taps taps)) taps)
             interval (when (next taps) (median (map - (rest taps) taps)))]
         (assoc state
                :taps taps
                :bpm (if (and interval (pos? interval))
                       (min (double max-bpm) (max (double min-bpm) (/ 60000.0 interval)))
                       (:bpm state))
                :last-tap timestamp-ms))))))

(defn bpm [state] (:bpm state))
(defn reset [state] (assoc state :taps [] :bpm nil :last-tap nil))

(defn beats-per-minute
  "The tempo taps at timestamps give, one after another, or nil while they
   make no interval."
  ([timestamps] (beats-per-minute timestamps default-options))
  ([timestamps options]
   (:bpm (reduce #(tap %1 %2 options) (make) timestamps))))
