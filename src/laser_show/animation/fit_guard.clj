(ns laser-show.animation.fit-guard
  "The fit guard (plans/2026-11-19-zones/01 §8, 05 §4): below its minimum
   fps a laser draws fewer points, then dims if it may. Pure.

   A laser's settings are [:projectors id :fit], read through settings: the
   routing (routing.rig/routing) puts them under each laser's :outputs, so an
   engine and the preview read the same ones for a tick.

   guard runs on each laser's composed frame, before its projector effects
   (backend/multi_engine.clj). A frame of N points at a scan rate of r draws
   about r/N times a second. Over the budget, ⌊r / min-fps⌋ points (S-5),
   thinning takes points out of the lit runs, never below ⌈point-floor · N⌉
   of them and never the ones keep-mask keeps; then, if :dim? is on and it
   is still below the minimum, it dims. The record says what it did, and
   smooth averages it over about 10 frames for the laser's card and its low
   flag, which a frame nobody sees (no lit point) never sets or clears (S-14)."
  (:require [laser-show.animation.types :as t]))

(def defaults
  "A laser's fit settings when it has none (D-18): a minimum of 20 fps, fewer
   points below it but never under 60% of them, and no dimming unless it is
   turned on, then never under half the brightness."
  {:min-fps 20 :reduce-points? true :point-floor 0.6 :dim? false :dim-floor 0.5})

(defn- clamped
  "v clamped to lo-hi, or default unless v is a finite number."
  [v lo hi default]
  (if (and (number? v) (Double/isFinite (double v)))
    (max lo (min hi v))
    default))

(defn settings
  "A laser's :fit over the defaults, each clamped to its range: :min-fps
   5-60, :point-floor 0.3-1.0, :dim-floor 0.2-1.0. A value of the wrong type
   takes its default, so a bad :fit can't stop the routing."
  [fit]
  (let [fit (if (map? fit) fit {})
        flag (fn [k] (let [v (get fit k)] (if (boolean? v) v (get defaults k))))
        min-fps (clamped (:min-fps fit) 5 60 (:min-fps defaults))]
    {:min-fps (if (integer? min-fps) (long min-fps) (double min-fps))
     :reduce-points? (flag :reduce-points?)
     :point-floor (double (clamped (:point-floor fit) 0.3 1.0 (:point-floor defaults)))
     :dim? (flag :dim?)
     :dim-floor (double (clamped (:dim-floor fit) 0.2 1.0 (:dim-floor defaults)))}))


;; The budget

(defn budget
  "The most points a frame can have and still draw at min-fps at scan-rate:
   ⌊scan-rate / min-fps⌋, the floor, so a frame thinned to it reaches the
   minimum (S-5)."
  [scan-rate min-fps]
  (long (Math/floor (/ (double scan-rate) (double min-fps)))))

(defn target-points
  "How many of n points thinning leaves: the budget, but never fewer than
   ⌈point-floor · n⌉."
  [n budget settings]
  (max (long budget)
       (long (Math/ceil (- (* (double (:point-floor settings)) (double n)) 1e-9)))))


;; What thinning keeps

(defn- lit? [p] (not (t/blanked? p)))

(def ^:private ^:const same-place 1e-6)

(def ^:private ^:const corner-cos
  "A turn of more than 45°: the directions in and out have a·b < cos 45°·|a||b|."
  0.7071)

(defn- same-place? [p q]
  (and (< (Math/abs (- (double (nth p 0)) (double (nth q 0)))) same-place)
       (< (Math/abs (- (double (nth p 1)) (double (nth q 1)))) same-place)))

(defn- corner? [prev p next]
  (let [ax (- (double (nth p 0)) (double (nth prev 0)))
        ay (- (double (nth p 1)) (double (nth prev 1)))
        bx (- (double (nth next 0)) (double (nth p 0)))
        by (- (double (nth next 1)) (double (nth p 1)))]
    (< (+ (* ax bx) (* ay by))
       (* corner-cos (Math/sqrt (+ (* ax ax) (* ay ay))) (Math/sqrt (+ (* bx bx) (* by by)))))))

(defn keep-mask
  "Which points of frame thinning must keep, as a boolean array: every
   blanked point; the first and last point of each lit run (a maximal
   stretch of lit points); dwell points (the same x and y as a neighbour,
   to 1e-6); and corners, where the line turns by more than 45° (against the
   original neighbours). A colour change isn't kept."
  ^booleans [frame]
  (let [frame (vec frame)
        n (count frame)
        mask (boolean-array n)]
    (dotimes [i n]
      (let [p (nth frame i)]
        (aset mask i
              (boolean
               (or (not (lit? p))
                   (zero? i)
                   (= i (dec n))
                   (let [prev (nth frame (dec i))
                         next (nth frame (inc i))]
                     (or (not (lit? prev))
                         (not (lit? next))
                         (same-place? p prev)
                         (same-place? p next)
                         (corner? prev p next))))))))
    mask))

(defn- removable-runs
  "The indexes of the points mask doesn't keep, a vector for each lit run,
   in frame order."
  [frame ^booleans mask]
  (let [n (count frame)]
    (loop [i 0 run (transient []) runs (transient [])]
      (if (= i n)
        (persistent! (cond-> runs (pos? (count run)) (conj! (persistent! run))))
        (if (lit? (nth frame i))
          (recur (inc i) (if (aget mask i) run (conj! run i)) runs)
          (recur (inc i) (transient [])
                 (cond-> runs (pos? (count run)) (conj! (persistent! run)))))))))

(defn- shares
  "k shared among runs in proportion to counts, by largest remainder (ties
   in frame order)."
  [k counts]
  (let [total (reduce + counts)
        base (mapv #(quot (* k %) total) counts)
        rems (mapv #(rem (* k %) total) counts)
        extra (set (take (- k (reduce + base))
                         (sort-by (fn [i] [(- (long (rems i))) i]) (range (count counts)))))]
    (into [] (map-indexed (fn [i b] (if (extra i) (inc b) b))) base)))

(defn- thin-with
  [frame ^booleans mask k]
  (let [frame (vec frame)
        runs (removable-runs frame mask)
        total (reduce + (map count runs))
        k (min (long k) total)]
    (if (<= k 0)
      frame
      (let [gone (boolean-array (count frame))]
        (doseq [[run q] (map vector runs (shares k (mapv count runs)))
                :let [c (count run)]]
          (dotimes [j c]
            (when (> (quot (* (inc j) q) c) (quot (* j q) c))
              (aset gone (long (nth run j)) true))))
        (into [] (keep-indexed (fn [i p] (when-not (aget gone i) p))) frame)))))

(defn thin
  "frame with up to k of the points keep-mask doesn't keep taken out, all of
   them when there are fewer. Each lit run loses a share of k in proportion
   to its removable points (largest remainder, ties in frame order), spread
   evenly along it: of a run's c removable points with share q, the j-th
   goes when ⌊(j+1)·q/c⌋ > ⌊j·q/c⌋. Always a subsequence of frame."
  [frame k]
  (thin-with frame (keep-mask frame) k))

(defn dim
  "frame with r, g and b times factor; dark points stay dark."
  [frame factor]
  (let [f (double factor)]
    (mapv (fn [p]
            (let [r (* f (double (nth p 2))) g (* f (double (nth p 3))) b (* f (double (nth p 4)))]
              (if (== 5 (count p))
                [(nth p 0) (nth p 1) r g b]
                (assoc p 2 r 3 g 4 b))))
          frame)))


;; The guard

(defn guard
  "Spec §8 on one laser's frame at scan-rate (pts/s) with settings
   (settings): {:frame f :fit record}. Under the budget f is frame itself.
   Over it, with :reduce-points?, thin to target-points; then, with :dim?
   and still under :min-fps, dim by max(dim-floor, guard-fps / min-fps). The
   record, for every frame: {:points-in :points :budget :min-fps :scan-rate
   :est-fps :guard-fps :reduced-pct :dim-pct}, the fps r/N before and after
   (nil for no points), and :dark? true for a frame with no lit point, which
   is never thinned or dimmed and leaves the low flag as it was (S-14)."
  [frame scan-rate settings]
  (let [{:keys [min-fps reduce-points? dim? dim-floor]} settings
        r (double scan-rate)
        n (count frame)
        b (budget r min-fps)
        fps (fn [k] (when (pos? k) (/ r k)))
        record (fn [points dim-pct]
                 {:points-in n :points points :budget b :min-fps min-fps :scan-rate scan-rate
                  :est-fps (fps n) :guard-fps (fps points)
                  :reduced-pct (if (pos? n) (* 100.0 (/ (- n points) (double n))) 0.0)
                  :dim-pct dim-pct})
        dark? (not-any? lit? frame)]
    (cond
      dark? {:frame frame :fit (assoc (record n 0.0) :dark? true)}
      (<= n b) {:frame frame :fit (record n 0.0)}
      :else
      (let [thinned (if reduce-points?
                      (let [k (- n (target-points n b settings))]
                        (if (pos? k) (thin frame k) frame))
                      frame)
            points (count thinned)
            guard-fps (/ r points)
            factor (when (and dim? (< guard-fps (double min-fps)))
                     (max (double dim-floor) (/ guard-fps (double min-fps))))]
        {:frame (if factor (dim thinned factor) thinned)
         :fit (record points (if factor (* 100.0 (- 1.0 factor)) 0.0))}))))

(defn recount
  "guard's record for a frame that something after the guard left with
   points: a projector effect that changes the count (Decimate, zones plan
   12). :points, :guard-fps and :reduced-pct are counted for points against
   :points-in, as guard counts them; :budget, :min-fps, :dim-pct and
   :est-fps stay the guard's. A frame left with no point is :dark?, its fps
   nil, as guard has it. The same record when the count is the same."
  [record points]
  (if (= points (:points record))
    record
    (let [n (long (:points-in record))
          points (long points)]
      (cond-> (assoc record
                     :points points
                     :guard-fps (when (pos? points) (/ (double (:scan-rate record)) points))
                     :reduced-pct (if (pos? n) (* 100.0 (/ (- n points) (double n))) 0.0))
        (zero? points) (assoc :dark? true)))))


;; The record over time

(def ^:private ^:const alpha
  "The weight of the newest frame: about 10 frames' worth, as the draw rate
   is smoothed (backend/streaming_engine.clj update-stats)."
  0.1)

(def ^:private ^:const low-margin
  "Once low, a laser is low until its averaged fps is this much above its
   minimum (S-6)."
  1.0)

(defn smooth
  "The newest record with :est-fps and :guard-fps averaged over about 10
   frames (prev: the last smoothed record, or nil) and :low?, on while the
   averaged guard fps is under the minimum and off again only at 1 fps above
   it (S-6). A :dark? record leaves the averages and :low? as they were
   (none yet: not low, S-14). :samples counts the frames averaged. nil for
   no record (nothing playing)."
  [prev record]
  (when record
    (let [samples (long (or (:samples prev) 0))
          averaged? (pos? samples)]
      (if (:dark? record)
        (if averaged?
          (assoc record :est-fps (:est-fps prev) :guard-fps (:guard-fps prev) :low? (boolean (:low? prev)) :samples samples)
          (assoc record :low? false :samples 0))
        (let [avg (fn [k] (let [v (double (get record k))]
                            (if averaged? (+ (* (- 1.0 alpha) (double (get prev k))) (* alpha v)) v)))
              guard-fps (avg :guard-fps)
              min-fps (double (:min-fps record))]
          (assoc record
                 :est-fps (avg :est-fps)
                 :guard-fps guard-fps
                 :low? (if (and averaged? (:low? prev))
                         (< guard-fps (+ min-fps low-margin))
                         (< guard-fps min-fps))
                 :samples (inc samples)))))))
