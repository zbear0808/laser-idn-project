(ns laser-show.animation.safety
  "Safety zones: where a projector's light may go. Pure frame-to-frame
   functions, no state (plans/2026-11-19-zones/03-phase-z1-safety.md §4).

   A zone is {:limit [x0 y0 x1 y1] :blocked [[x0 y0 x1 y1] …]} in the
   projector's output coordinates (-1..1, after its calibration effects):
   light stays inside the limit and out of every blocked area. The limit's
   edges count as inside; a blocked area's edges count as blocked.

   apply-safety runs last on every frame a projector sends: a lit move that
   leaves the limit or enters a blocked area is cut at the edge, and the part
   outside stays dark; every point, dark or lit, is moved into the limit; and
   with blocked areas, around each dark jump that crosses anything not
   allowed the light switches only while the beam stands still (edge-dwell).
   The default zone changes nothing: the frame comes back identical?."
  (:require [laser-show.animation.types :as t]))


(def default-safety
  "The zone that changes nothing: the whole range, nothing blocked."
  {:limit [-1.0 -1.0 1.0 1.0] :blocked []})

(def ^:const edge-dwell
  "How many dark samples hold the beam still at each end of a dark jump that
   crosses something not allowed. 1 in v1; the hardware check sets the real
   value."
  1)

(def ^:private ^:const eps 1e-12)

(def ^:private ^:const min-allowed
  "Allowed parts of a move shorter than this (in t, 0..1) are dropped."
  1e-6)

(def ^:private ^:const min-blocked
  "Blocked parts of a move shorter than this (in t) are ignored: a touch at a
   corner. Smaller than min-allowed, so a near miss stays cut."
  1e-9)


;; normalize


(defn- bad [message data]
  (throw (ex-info message (assoc data ::unreadable true))))

(defn- finite-number? [v]
  (and (number? v) (Double/isFinite (double v))))

(defn- clamp1 ^double [^double v]
  (Math/max -1.0 (Math/min 1.0 v)))

(defn- rect
  "A rectangle as four doubles, corners ordered, clamped to -1..1. Throws for
   anything that isn't four finite numbers."
  [r what]
  (when-not (and (sequential? r) (= 4 (count r)) (every? finite-number? r))
    (bad (str "A safety " what " needs four numbers: " (pr-str r)) {:rect r}))
  (let [[a b c d] (map double r)]
    [(clamp1 (Math/min a c)) (clamp1 (Math/min b d)) (clamp1 (Math/max a c)) (clamp1 (Math/max b d))]))

(defn normalize
  "A zone in its stored form: the defaults filled in, every number a double,
   each rectangle's corners ordered and clamped to -1..1. It never makes the
   allowed area larger: nothing is grown, moved or dropped. Malformed data
   (a missing corner, a non-number, a NaN, a limit with no width or height)
   throws, so the safety stage fails closed."
  [s]
  (cond
    (nil? s) default-safety
    (not (map? s)) (bad "A safety zone must be a map" {:zone s})
    :else
    (let [limit (if (contains? s :limit) (rect (:limit s) "limit") (:limit default-safety))
          [x0 y0 x1 y1] limit
          blocked (:blocked s [])]
      (when-not (and (> x1 x0) (> y1 y0))
        (bad "A safety limit needs a width and a height" {:limit limit}))
      (when-not (sequential? blocked)
        (bad "Blocked areas must be a list of rectangles" {:blocked blocked}))
      {:limit limit
       :blocked (mapv #(rect % "blocked area") blocked)})))

(defn identity?
  "The whole range and nothing blocked: apply-safety changes nothing."
  [s]
  (let [{:keys [limit blocked]} (normalize s)]
    (and (= [-1.0 -1.0 1.0 1.0] limit) (empty? blocked))))


;; Geometry


(defn- in-rect? [[x0 y0 x1 y1] x y]
  (let [x (double x) y (double y)]
    (and (<= (double x0) x (double x1)) (<= (double y0) y (double y1)))))

(defn inside?
  "Is (x, y) allowed: inside the limit (edges count) and in no blocked area
   (edges count as blocked)?"
  [{:keys [limit blocked]} x y]
  (and (in-rect? (or limit (:limit default-safety)) x y)
       (not-any? #(in-rect? % x y) blocked)))

(defn- clip
  "Liang–Barsky: the part [t0 t1] of p + t·d, t in 0..1, inside the closed
   rectangle, or nil."
  [[x0 y0 x1 y1] px py dx dy]
  (let [px (double px) py (double py) dx (double dx) dy (double dy)]
    (loop [edges [[(- dx) (- px (double x0))]
                  [dx (- (double x1) px)]
                  [(- dy) (- py (double y0))]
                  [dy (- (double y1) py)]]
           t0 0.0
           t1 1.0]
      (if-let [[p q] (first edges)]
        (let [p (double p) q (double q)]
          (cond
            (zero? p) (if (< q (- eps)) nil (recur (rest edges) t0 t1))
            (neg? p) (recur (rest edges) (Math/max t0 (/ q p)) t1)
            :else (recur (rest edges) t0 (Math/min t1 (/ q p)))))
        (when (<= t0 t1) [t0 t1])))))

(defn- subtract
  "The parts of parts (sorted [a b] intervals) outside [b0 b1]."
  [parts b0 b1]
  (into []
        (mapcat (fn [[a b :as part]]
                  (if (or (<= b b0) (>= a b1))
                    [part]
                    (cond-> []
                      (< a b0) (conj [a b0])
                      (< b1 b) (conj [b1 b])))))
        parts))

(defn intervals
  "The parts [[t0 t1] …] of the move p→q (t from 0 to 1) that are allowed:
   the limit clipped with Liang–Barsky, then each blocked area's part taken
   out. Sorted, not overlapping. Allowed parts shorter than 1e-6 are dropped,
   and a blocked part shorter than 1e-9 (a touch at a corner) is ignored. A
   zero-length move (a dwell) is [[0 1]] where it is allowed, else []."
  [{:keys [limit blocked]} p q]
  (let [px (double (p t/X)) py (double (p t/Y))
        dx (- (double (q t/X)) px) dy (- (double (q t/Y)) py)
        limit (or limit (:limit default-safety))]
    (if (and (< (Math/abs dx) eps) (< (Math/abs dy) eps))
      (if (inside? {:limit limit :blocked blocked} (q t/X) (q t/Y)) [[0.0 1.0]] [])
      (if-let [[t0 t1] (clip limit px py dx dy)]
        (->> blocked
             (reduce (fn [parts b]
                       (if-let [[b0 b1] (clip b px py dx dy)]
                         (if (< (- (double b1) (double b0)) min-blocked) parts (subtract parts b0 b1))
                         parts))
                     [[t0 t1]])
             (filterv (fn [[a b]] (>= (- (double b) (double a)) min-allowed))))
        []))))


;; apply-safety


(defn- finite-point? [pt]
  (and (vector? pt) (<= 5 (count pt))
       (finite-number? (pt t/X)) (finite-number? (pt t/Y))
       (finite-number? (pt t/R)) (finite-number? (pt t/G)) (finite-number? (pt t/B))))

(defn- clamp-to [[x0 y0 x1 y1] x y]
  [(Math/max (double x0) (Math/min (double x1) (double x)))
   (Math/max (double y0) (Math/min (double y1) (double y)))])

(defn- dark-at [limit x y]
  (let [[cx cy] (clamp-to limit x y)]
    [cx cy 0.0 0.0 0.0]))

(defn- dark-copy [pt]
  [(pt t/X) (pt t/Y) 0.0 0.0 0.0])

(defn- in-limit-point? [limit pt]
  (in-rect? limit (pt t/X) (pt t/Y)))

(defn- along [p q t]
  (let [t (double t)
        px (double (p t/X)) py (double (p t/Y))]
    [(+ px (* t (- (double (q t/X)) px)))
     (+ py (* t (- (double (q t/Y)) py)))]))

(defn- full? [parts]
  (and (= 1 (count parts))
       (let [[a b] (first parts)] (and (== 0.0 (double a)) (== 1.0 (double b))))))

(defn- clip-frame
  "Steps 1-3 of 03 §4: drop non-finite points, move dark points into the
   limit, and cut every lit move to its allowed parts."
  [{:keys [limit] :as s} frame]
  (loop [points (seq frame)
         p nil
         out (transient [])]
    (if-let [q (first points)]
      (cond
        (not (finite-point? q))
        (recur (next points) p out)

        (t/blanked? q)
        (recur (next points) q
               (conj! out (if (in-limit-point? limit q) q (dark-at limit (q t/X) (q t/Y)))))

        :else
        (let [parts (intervals s (or p q) q)
              colour (subvec q t/R (inc t/B))
              lit-at (fn [tb]
                       (if (== 1.0 (double tb))
                         q
                         (let [[x y] (along (or p q) q tb)
                               [cx cy] (clamp-to limit x y)]
                           (into [cx cy] colour))))]
          (if (full? parts)
            (recur (next points) q (conj! out q))
            (let [out (reduce (fn [out [a b]]
                                (let [out (if (> (double a) 0.0)
                                            (let [[x y] (along (or p q) q a)]
                                              (conj! out (dark-at limit x y)))
                                            out)]
                                  (conj! out (lit-at b))))
                              out
                              parts)
                  ends-early? (or (empty? parts) (< (double (second (peek parts))) 1.0))]
              (recur (next points) q
                     (cond-> out ends-early? (conj! (dark-at limit (q t/X) (q t/Y)))))))))
      (persistent! out))))

(defn- allowed-move? [s a b]
  (full? (intervals s a b)))

(defn- hold-stretch
  "A dark stretch (stretch, after the lit point before it, or nil at the
   frame's start), held edge-dwell samples at both ends when one of its moves
   isn't allowed. The jumps into and out of the frame count as not allowed."
  [s before stretch boundary?]
  (let [moves (partition 2 1 (cond->> stretch before (cons before)))
        held? (or boundary? (some (fn [[a b]] (not (allowed-move? s a b))) moves))]
    (if held?
      (concat (when before (repeat edge-dwell (dark-copy before)))
              stretch
              (repeat edge-dwell (dark-copy (peek stretch))))
      stretch)))

(defn- hold-dark-jumps
  "Step 4 of 03 §4, for zones with blocked areas: the light switches only
   where the beam stands still."
  [s points]
  (if-not (some #(not (t/blanked? %)) points)
    points
    (loop [pts (seq points)
           before nil     ; the last lit point, nil before the first
           stretch []     ; the dark points since it
           out (transient [])]
      (if-let [pt (first pts)]
        (if (t/blanked? pt)
          (recur (next pts) before (conj stretch pt) out)
          (let [out (cond
                      (seq stretch) (reduce conj! out (hold-stretch s before stretch (nil? before)))
                      (nil? before) (reduce conj! out (repeat (inc edge-dwell) (dark-copy pt)))
                      :else out)]
            (recur (next pts) pt [] (conj! out pt))))
        (persistent!
         (if (seq stretch)
           (reduce conj! out (hold-stretch s before stretch true))
           (reduce conj! out (repeat edge-dwell (dark-copy before)))))))))

(defn apply-safety
  "frame inside zone s (03 §4): lit moves cut to their allowed parts, every
   point moved into the limit, non-finite points dropped, and with blocked
   areas the light switched only where the beam stands still. The default
   zone, or a frame that is already wholly inside a zone with no blocked
   areas, comes back as it is (identical?)."
  [s frame]
  (let [{:keys [limit blocked] :as s} (normalize s)]
    (cond
      (and (= [-1.0 -1.0 1.0 1.0] limit) (empty? blocked)) frame

      (and (empty? blocked)
           (every? #(and (finite-point? %) (in-limit-point? limit %)) frame))
      frame

      (empty? blocked) (clip-frame s frame)

      :else (hold-dark-jumps s (clip-frame s frame)))))


;; Editing


(defn- contains-rect? [[ox0 oy0 ox1 oy1] [ix0 iy0 ix1 iy1]]
  (let [e 1e-9]
    (and (<= (- ox0 e) ix0) (<= (- oy0 e) iy0) (<= ix1 (+ ox1 e)) (<= iy1 (+ oy1 e)))))

(defn- intersection [[ax0 ay0 ax1 ay1] [bx0 by0 bx1 by1]]
  (let [x0 (max ax0 bx0) y0 (max ay0 by0) x1 (min ax1 bx1) y1 (min ay1 by1)]
    (when (and (> (- x1 x0) 1e-9) (> (- y1 y0) 1e-9))
      [x0 y0 x1 y1])))

(defn- readable [s]
  (try (normalize s) (catch clojure.lang.ExceptionInfo _ nil)))

(defn widens?
  "May new let light somewhere old doesn't? True when new's limit pokes out
   of old's, or the part of an old blocked area that lies inside new's limit
   isn't inside one of new's blocked areas. Both are normalized first, with
   1e-9 for rounding; a zone that can't be read allows nothing. Sound (it
   never says no to a change that lets light somewhere new) and conservative
   (one blocked area split in two counts as widening)."
  [old new]
  (let [o (readable old)
        n (readable new)]
    (cond
      (nil? n) false
      (nil? o) true
      :else (boolean
             (or (not (contains-rect? (:limit o) (:limit n)))
                 (some (fn [b]
                         (when-let [part (intersection b (:limit n))]
                           (not-any? #(contains-rect? % part) (:blocked n))))
                       (:blocked o)))))))

(defn mirror-x
  "The zone mirrored left to right: each rectangle [x0 y0 x1 y1] becomes
   [-x1 y0 -x0 y1]."
  [s]
  (let [flip (fn [[x0 y0 x1 y1]] [(- x1) y0 (- x0) y1])]
    (-> (normalize s)
        (update :limit flip)
        (update :blocked #(mapv flip %)))))
