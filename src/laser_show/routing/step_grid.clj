(ns laser-show.routing.step-grid
  "The step grid (zones plan Z6, plans/2026-11-19-zones/08 §4, §8): a zone
   pattern whose style is Steps. Its rows are its :zones, top to bottom, and
   its :steps a vector with the refs lit on each step, in row order ([] is a
   rest). Step k lights steps[k mod L] (routing.zone-remap/lit-set).

   Pure: every edit takes the pattern's params and returns them, in normal
   form (normalize): a step names only rows, each once, in row order, so no
   step names a ref that isn't a row (08 invariant 2). An edit that can't
   apply (a ref that is a row already, a step outside the grid) returns the
   params as they were. A pattern of another style becomes a grid with
   as-grid: its own style's first steps, so it plays the same for them."
  (:require [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-remap :as zone-remap]))

(def min-length "The shortest grid." 1)
(def max-length "The longest grid (Z6-Q4 A)." 64)
(def new-length "A new grid's length, at 2 steps a beat a bar of eighths (Z6-Q1 A)." 8)

(def start-styles
  "What a grid can start from (08 §7): Z5's styles that light places, then
   Center-out and All on."
  [:alternate :chase :ping-pong :random :center-out :all-on])


;; What a grid is

(defn rows
  "A grid's rows, top to bottom: its :zones, each once."
  [params]
  (vec (distinct (filter keyword? (:zones params)))))

(defn- steps
  "A grid's steps, each a vector (anything that isn't a collection of refs
   is a rest)."
  [params]
  (mapv #(if (sequential? %) (vec %) []) (zone-remap/grid-steps params)))

(defn length
  "How many steps a grid has."
  [params]
  (count (zone-remap/grid-steps params)))

(defn grid?
  "Is params a step grid with steps (a pattern of style Steps that has some)?"
  [params]
  (and (= :steps (:style params)) (pos? (length params))))

(defn- in-row-order
  "The rows among refs, once each, in rows' order."
  [rows refs]
  (let [lit (set refs)]
    (filterv lit rows)))

(defn normalize
  "params with its rows once each and each step only rows, once each, in
   row order (08 invariant 2)."
  [params]
  (let [rs (rows params)]
    (assoc params
           :zones rs
           :steps (mapv #(in-row-order rs %) (steps params)))))

(defn lit?
  "Does ref light on step of the grid?"
  [params ref step]
  (boolean (and (integer? step)
                (some #{ref} (get (steps params) step)))))

(defn- in-grid? [params ref step]
  (boolean (and (some #{ref} (rows params))
                (integer? step)
                (< -1 step (length params)))))


;; One cell

(defn paint
  "ref on step lit (on? true) or not, whatever it was: what a drag does to
   each cell it crosses (Z6-Q3 A)."
  [params ref step on?]
  (if (in-grid? params ref step)
    (normalize (update params :steps (fn [ss]
                                       (update (mapv #(if (sequential? %) (vec %) []) ss) step
                                               (fn [s] (if on? (conj s ref) (filterv #(not= ref %) s)))))))
    params))

(defn toggle
  "ref on step lit if it wasn't, else not: a click, or Space on the
   cursor."
  [params ref step]
  (paint params ref step (not (lit? params ref step))))


;; Length

(defn set-length
  "The grid n steps long, from 1 to 64 (Z6-Q4 A): longer adds rests at the
   end, shorter cuts the end."
  [params n]
  (if (number? n)
    (let [n (max min-length (min max-length (long n)))
          ss (steps params)
          l (count ss)]
      (normalize (assoc params :steps (if (<= n l) (subvec ss 0 n) (into ss (repeat (- n l) []))))))
    params))


;; Rows

(defn- row-ref? [ref]
  (and (keyword? ref) (not= :lane/lasers ref)))

(defn next-row
  "The row + Row adds: the first zone letter that isn't a row (A, B, C…)."
  [params]
  (targets/next-zone (rows params)))

(defn add-row
  "ref as a new row at the bottom, unlit; refused for a row already there
   and for Every laser, which can't be a row (S-4)."
  [params ref]
  (if (and (row-ref? ref) (not (some #{ref} (rows params))))
    (normalize (assoc params :zones (conj (rows params) ref)))
    params))

(defn remove-row
  "ref's row gone, and out of every step."
  [params ref]
  (if (some #{ref} (rows params))
    (normalize (assoc params :zones (filterv #(not= ref %) (rows params))))
    params))

(defn replace-row
  "Row old as new, in its place, every step that lit old lighting new;
   refused when new is a row already (or Every laser)."
  [params old new]
  (let [rs (rows params)]
    (if (and (some #{old} rs) (row-ref? new) (not (some #{new} rs)))
      (let [swap #(if (= old %) new %)]
        (normalize (assoc params
                          :zones (mapv swap rs)
                          :steps (mapv #(mapv swap %) (steps params)))))
      params)))

(defn move-row
  "ref's row delta places down (up when negative), stopping at the top and
   the bottom; each step re-sorted into the new row order."
  [params ref delta]
  (let [rs (rows params)
        i (.indexOf ^java.util.List rs ref)]
    (if (or (neg? i) (not (integer? delta)))
      params
      (let [j (max 0 (min (dec (count rs)) (+ i (long delta))))]
        (if (= i j)
          params
          (let [others (into (subvec rs 0 i) (subvec rs (inc i)))]
            (normalize (assoc params :zones (into (conj (subvec others 0 j) ref) (subvec others j))))))))))

(defn fill-row
  "ref's row lit on every every-th step from the first (every 1, 2 or 4:
   Fill every step, Every 2nd, Every 4th), and unlit on the others."
  [params ref every]
  (if (and (some #{ref} (rows params)) (pos-int? every))
    (normalize (assoc params :steps (vec (map-indexed (fn [k s]
                                                         (let [s (filterv #(not= ref %) s)]
                                                           (if (zero? (mod k every)) (conj s ref) s)))
                                                       (steps params)))))
    params))

(defn clear-row
  "ref's row unlit on every step."
  [params ref]
  (if (some #{ref} (rows params))
    (normalize (assoc params :steps (mapv (fn [s] (filterv #(not= ref %) s)) (steps params))))
    params))


;; Whole steps, the whole grid

(defn clear
  "Every step a rest; the rows and the length stay."
  [params]
  (normalize (assoc params :steps (vec (repeat (length params) [])))))

(defn clear-step
  "step a rest, in every row."
  [params step]
  (if (and (integer? step) (< -1 step (length params)))
    (normalize (assoc params :steps (assoc (steps params) step [])))
    params))

(defn fill-step
  "Every row lit on step."
  [params step]
  (if (and (integer? step) (< -1 step (length params)))
    (normalize (assoc params :steps (assoc (steps params) step (rows params))))
    params))

(defn rotate
  "Every row by steps later (earlier when negative), wrapping: step k shows
   what step k − by showed."
  [params by]
  (let [ss (steps params)
        n (count ss)]
    (if (or (zero? n) (not (integer? by)))
      params
      (normalize (assoc params :steps (vec (take n (drop (mod (- (long by)) n) (cycle ss)))))))))


;; Starting points (08 §8's Start from table)

(defn center-out
  "n steps over rows from the middle out: the middle row (the middle two
   with an even count), then the pair one further out, and so on, then the
   middle again."
  [rows n]
  (let [c (count rows)]
    (if (zero? c)
      (vec (repeat n []))
      (let [lo (quot (dec c) 2)
            hi (quot c 2)
            rings (vec (for [i (range (inc lo))]
                         (in-row-order rows [(nth rows (- lo i)) (nth rows (+ hi i))])))]
        (mapv #(nth rings (mod % (count rings))) (range n))))))

(defn from-style
  "params with n steps of style over its rows: Z5's lit-set (Alternate,
   Chase, Ping-pong, Random, with the pattern's own :width, :path and
   :seed), Center-out (center-out) or All on (every row on every step), each
   step in row order (08 invariant 6). Any other style gives rests."
  [params style n]
  (let [rs (rows params)
        n (max min-length (min max-length (long n)))]
    (normalize
     (assoc params :steps
            (case style
              :center-out (center-out rs n)
              :all-on (vec (repeat n rs))
              (mapv #(in-row-order rs (zone-remap/lit-set style params rs %)) (range n)))))))

(defn grid-rows
  "refs as a grid's rows: Every laser (:lane/lasers) can't be a row, since
   the number of lasers changes, so it becomes that many zones (at least
   2), the first letters refs doesn't use, in its place (S-4); the others
   once each."
  [refs n-lasers]
  (let [refs (vec (distinct (filter keyword? refs)))]
    (if-not (some #{:lane/lasers} refs)
      refs
      (let [zones (loop [zs [] used (set refs)]
                    (if (= (count zs) (max 2 (long n-lasers)))
                      zs
                      (let [z (targets/next-zone used)]
                        (recur (conj zs z) (conj used z)))))]
        (vec (distinct (mapcat #(if (= :lane/lasers %) zones [%]) refs)))))))

(defn became-zones
  "The zones Every laser became in grid-rows, for the window's note; nil
   when refs don't hold it."
  [refs n-lasers]
  (when (some #{:lane/lasers} refs)
    (let [own (set refs)]
      (filterv #(not (contains? own %)) (grid-rows refs n-lasers)))))

(defn as-grid
  "params as a step grid. A grid stays itself. Any other pattern takes its
   rows from its zones (grid-rows, n-lasers the rig's lasers now) and 8
   steps of its own style (from-style), so it plays the same for those 8
   steps at its own rate and clock; a style a grid can't copy (Shift and
   Swap trade what's in the zones, a style a modulator picks, Steps with no
   steps yet) starts as a new grid does, Alternate. A grid doesn't follow
   the pattern pads (Z6-Q2 A)."
  [params n-lasers]
  (if (grid? params)
    (normalize params)
    (let [style (:style params)
          from (if (contains? #{:alternate :chase :ping-pong :random} style) style :alternate)]
      (-> (assoc params :zones (grid-rows (:zones params) n-lasers))
          (from-style from new-length)
          (assoc :style :steps :follow-pads? false)))))


;; Copy and paste

(defn copy
  "What Copy keeps of a grid: {:zones (its rows) :steps}."
  [params]
  (let [p (normalize params)]
    {:zones (:zones p) :steps (:steps p)}))

(defn paste
  "clip (copy's) into params: the rows it lacks added at the bottom, and its
   steps, each ref lighting where it did (rows are matched by ref, not by
   place); the grid takes clip's length, and its other rows go dark. Nothing
   without steps to paste."
  [params {clip-rows :zones clip-steps :steps}]
  (if (and (sequential? clip-steps) (seq clip-steps))
    (let [rs (rows params)
          added (into rs (comp (filter row-ref?) (remove (set rs))) clip-rows)]
      (normalize (assoc params :zones added :steps (vec (take max-length clip-steps)))))
    params))


;; The mouse

(defn cell-at
  "The [row step] at (x, y) of a grid of rows × len cells, each cell-w ×
   cell-h, (0, 0) its top-left corner; nil outside."
  [x y cell-w cell-h rows len]
  (when (and (number? x) (number? y) (number? cell-w) (number? cell-h)
             (pos? cell-w) (pos? cell-h) (>= x 0) (>= y 0))
    (let [step (long (Math/floor (/ (double x) (double cell-w))))
          row (long (Math/floor (/ (double y) (double cell-h))))]
      (when (and (< row (long rows)) (< step (long len)))
        [row step]))))

(defn line-cells
  "The cells from cell from to cell to, both included, one step at a time
   (so a quick drag paints every cell it crosses); just to without from."
  [from [r1 s1 :as to]]
  (if-let [[r0 s0] from]
    (let [n (max (Math/abs (long (- r1 r0))) (Math/abs (long (- s1 s0))))]
      (if (zero? n)
        [to]
        (vec (distinct (for [i (range (inc n))]
                         [(Math/round (double (+ r0 (/ (* (- r1 r0) i) n))))
                          (Math/round (double (+ s0 (/ (* (- s1 s0) i) n))))])))))
    [to]))
