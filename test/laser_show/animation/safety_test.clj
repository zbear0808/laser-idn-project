(ns laser-show.animation.safety-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.safety :as safety]
            [laser-show.animation.types :as t]
            [laser-show.idn.frame-rules :as rules]))

(defn- d [x y] [x y 0.0 0.0 0.0])
(defn- w [x y] [x y 1.0 1.0 1.0])

(def ^:private zone
  "The zone of 03 §8: a limit and one blocked area across the bottom middle."
  {:limit [-0.9 -0.8 0.9 0.95] :blocked [[-0.2 -1.0 0.2 -0.4]]})


;; normalize


(deftest normalize-test
  (is (= {:limit [-1.0 -1.0 1.0 1.0] :blocked []} (safety/normalize nil)))
  (is (= {:limit [-1.0 -1.0 1.0 1.0] :blocked []} (safety/normalize {})))
  (testing "corners are ordered, numbers become doubles, rectangles are clamped to -1..1"
    (is (= {:limit [-0.5 -0.25 0.5 1.0] :blocked [[-1.0 0.0 0.0 0.5]]}
           (safety/normalize {:limit [0.5 1.5 -0.5 -0.25] :blocked [[0 1/2 -2 0]]})))))

(deftest normalize-never-widens-test
  (let [s {:limit [-0.01 -0.01 0.01 0.01]
           :blocked (vec (for [i (range 9)] [(* i 0.001) 0.0 (+ (* i 0.001) 0.0001) 0.0001]))}]
    (is (= [-0.01 -0.01 0.01 0.01] (:limit (safety/normalize s))) "a tiny limit is kept")
    (is (= 9 (count (:blocked (safety/normalize s)))) "a ninth and a tiny blocked area are kept")))

(deftest malformed-zone-throws-test
  (doseq [bad [{:limit [-1 -1 1]}
               {:limit [-1 -1 1 "1"]}
               {:limit [-1 -1 1 ##NaN]}
               {:limit [0.5 -1 0.5 1]}
               {:limit [-1 0.2 1 0.2]}
               {:blocked [[0 0 1]]}
               {:blocked {:a 1}}
               {:blocked [[0 0 ##Inf 1]]}
               [:not-a-map]]]
    (is (thrown? clojure.lang.ExceptionInfo (safety/normalize bad)) (pr-str bad))))


;; intervals


(deftest intervals-test
  (let [limit-only {:limit [-0.5 -0.5 0.5 0.5] :blocked []}
        near (fn [expected actual]
               (and (= (count expected) (count actual))
                    (every? true? (map (fn [[a b] [c e]] (and (< (Math/abs (- a c)) 1e-9) (< (Math/abs (- b e)) 1e-9)))
                                       expected actual))))]
    (testing "inside the limit: all of it"
      (is (= [[0.0 1.0]] (safety/intervals limit-only (w -0.2 0.0) (w 0.2 0.0)))))
    (testing "through the limit: the middle"
      (is (near [[0.25 0.75]] (safety/intervals limit-only (w -1.0 0.0) (w 1.0 0.0)))))
    (testing "outside the limit: nothing"
      (is (= [] (safety/intervals limit-only (w 0.6 0.6) (w 0.9 0.9)))))
    (testing "one blocked area cuts a gap"
      (is (near [[0.0 0.3] [0.7 1.0]] (safety/intervals zone (w -0.5 -0.7) (w 0.5 -0.7)))))
    (testing "two blocked areas cut two gaps"
      (is (near [[0.0 0.2] [0.4 0.6] [0.8 1.0]]
                (safety/intervals {:limit [-1 -1 1 1] :blocked [[-0.6 -0.1 -0.2 0.1] [0.2 -0.1 0.6 0.1]]}
                                  (w -1.0 0.0) (w 1.0 0.0)))))
    (testing "along a blocked area's edge: blocked (edges count as blocked)"
      (is (= [] (safety/intervals zone (w -0.1 -0.4) (w 0.1 -0.4)))))
    (testing "along the limit's edge: allowed (edges count as inside)"
      (is (= [[0.0 1.0]] (safety/intervals limit-only (w -0.5 0.5) (w 0.5 0.5)))))
    (testing "a touch at a blocked corner doesn't cut the line"
      (is (near [[0.0 1.0]] (safety/intervals {:limit [-1 -1 1 1] :blocked [[0.0 0.0 0.5 0.5]]}
                                              (w -0.5 0.5) (w 0.5 -0.5)))))
    (testing "a zero-length segment: all of it where it's allowed, nothing where it isn't"
      (is (= [[0.0 1.0]] (safety/intervals zone (w 0.5 0.5) (w 0.5 0.5))))
      (is (= [] (safety/intervals zone (w 0.0 -0.6) (w 0.0 -0.6))))
      (is (= [] (safety/intervals zone (w 0.95 0.0) (w 0.95 0.0)))))))


;; apply-safety


(deftest apply-safety-example-test
  (is (= [(d -0.5 -0.7) (d -0.5 -0.7)
          (w -0.2 -0.7) (d -0.2 -0.7)
          (d 0.2 -0.7) (d 0.2 -0.7)
          (w 0.5 -0.7) (d 0.5 -0.7)]
         (mapv (fn [pt] (mapv #(/ (Math/round (* % 1e9)) 1e9) pt))
               (safety/apply-safety zone [(d -0.5 -0.7) (w 0.5 -0.7)])))))

(deftest identity-returns-the-same-frame-test
  (let [frame [(d 0.0 0.0) (w 0.95 0.95) (w -0.95 -0.95) [2.0 0.0 1.0 1.0 1.0]]]
    (is (identical? frame (safety/apply-safety nil frame)))
    (is (identical? frame (safety/apply-safety {:limit [-1 -1 1 1] :blocked []} frame))))
  (testing "a frame wholly inside a zone with only a limit comes back as it is"
    (let [frame [(d -0.4 -0.4) (w 0.4 -0.4) (w 0.4 0.4) (w -0.4 0.4)]]
      (is (identical? frame (safety/apply-safety {:limit [-0.5 -0.5 0.5 0.5]} frame))))))

(deftest non-finite-points-are-dropped-before-clipping-test
  (let [out (safety/apply-safety {:limit [-0.5 -0.5 0.5 0.5]}
                                 [(d 0.0 0.0) [##NaN 0.0 1.0 1.0 1.0] (w 0.2 0.0) [0.3 nil 1.0 1.0 1.0] (w 0.3 0.0)])]
    (is (= [(d 0.0 0.0) (w 0.2 0.0) (w 0.3 0.0)] out))))


;; The invariants, on seeded random frames and zones


(defn- random-rect [^java.util.Random rng]
  (let [v #(- (* 2.0 (.nextDouble rng)) 1.0)
        x0 (v) x1 (v) y0 (v) y1 (v)]
    [(min x0 x1) (min y0 y1) (max x0 x1) (max y0 y1)]))

(defn- random-zone [^java.util.Random rng]
  (let [limit (loop [r (random-rect rng)]
                (let [[x0 y0 x1 y1] r]
                  (if (and (> (- x1 x0) 0.1) (> (- y1 y0) 0.1)) r (recur (random-rect rng)))))]
    {:limit limit
     :blocked (vec (repeatedly (.nextInt rng 4) #(random-rect rng)))}))

(defn- random-frame [^java.util.Random rng]
  (vec (for [_ (range (inc (.nextInt rng 12)))]
         (let [x (- (* 2.4 (.nextDouble rng)) 1.2)
               y (- (* 2.4 (.nextDouble rng)) 1.2)]
           (if (< (.nextDouble rng) 0.35)
             (d x y)
             [x y (.nextDouble rng) 1.0 (.nextDouble rng)])))))

(def ^:private tol 1e-9)

(defn- in-limit? [{[x0 y0 x1 y1] :limit} [x y]]
  (and (<= (- x0 tol) x (+ x1 tol)) (<= (- y0 tol) y (+ y1 tol))))

(defn- in-interior? [[x0 y0 x1 y1] [x y]]
  (and (< (+ x0 tol) x (- x1 tol)) (< (+ y0 tol) y (- y1 tol))))

(defn- samples
  "Points along a→b, ends included."
  [[ax ay] [bx by]]
  (for [i (range 33)]
    (let [f (/ i 32.0)]
      [(+ ax (* f (- bx ax))) (+ ay (* f (- by ay)))])))

(defn- lit-moves
  "[from to] for every move of out lit by colours, where colour-of gives the
   colour each point shows."
  [out colour-of]
  (for [k (range 1 (count out))
        :let [c (colour-of k)]
        :when (and c (not (t/blanked? (into [0.0 0.0] c))))]
    [(out (dec k)) (out k)]))

(defn- colour [out k]
  (when (< -1 k (count out)) (subvec (out k) 2 5)))

(defn- check-zones! [check]
  (let [rng (java.util.Random. 20261119)]
    (dotimes [_ 2000]
      (let [s (safety/normalize (random-zone rng))
            frame (random-frame rng)]
        (check s frame (safety/apply-safety s frame))))))

(deftest nothing-lit-outside-test
  (check-zones!
   (fn [s frame out]
     (doseq [[a b] (lit-moves out #(colour out %))
             p (samples a b)]
       (is (in-limit? s p) (pr-str {:zone s :frame frame :move [a b]}))
       (is (not-any? #(in-interior? % p) (:blocked s)) (pr-str {:zone s :frame frame :move [a b]}))))))

(deftest nothing-aimed-outside-test
  (check-zones!
   (fn [s frame out]
     (is (every? #(in-limit? s %) out) (pr-str {:zone s :frame frame :out out})))))

(defn- on-segment? [[px py] [qx qy] [x y]]
  (let [dx (- qx px) dy (- qy py)
        len2 (+ (* dx dx) (* dy dy))]
    (if (< len2 1e-18)
      (and (< (Math/abs (- x qx)) 1e-6) (< (Math/abs (- y qy)) 1e-6))
      (let [t (/ (+ (* (- x px) dx) (* (- y py) dy)) len2)
            cx (+ px (* t dx)) cy (+ py (* t dy))]
        (and (<= -1e-6 t (+ 1 1e-6))
             (< (Math/abs (- x cx)) 1e-6) (< (Math/abs (- y cy)) 1e-6))))))

(deftest nothing-new-lit-test
  (check-zones!
   (fn [s frame out]
     (doseq [pt out :when (not (t/blanked? pt))]
       (is (some (fn [i]
                   (let [q (frame i)
                         p (if (pos? i) (frame (dec i)) q)]
                     (and (= (subvec q 2 5) (subvec pt 2 5))
                          (on-segment? p q pt))))
                 (range (count frame)))
           (pr-str {:zone s :frame frame :point pt}))))))

(deftest colour-shift-stays-out-test
  (check-zones!
   (fn [s frame out]
     (when (seq (:blocked s))
       (doseq [shift (range (- safety/edge-dwell) (inc safety/edge-dwell))
               [a b] (lit-moves out #(colour out (+ % shift)))
               p (samples a b)]
         (is (not-any? #(in-interior? % p) (:blocked s))
             (pr-str {:shift shift :zone s :frame frame :move [a b]})))))))

(deftest jump-across-a-blocked-area-is-held-at-both-ends-test
  (let [s {:limit [-1 -1 1 1] :blocked [[-0.1 -1.0 0.1 1.0]]}
        out (safety/apply-safety s [(d -0.6 0.0) (w -0.4 0.0) (d 0.4 0.0) (w 0.6 0.0)])]
    (is (= [(d -0.6 0.0) (d -0.6 0.0)
            (w -0.4 0.0) (d -0.4 0.0)
            (d 0.4 0.0) (d 0.4 0.0)
            (w 0.6 0.0) (d 0.6 0.0)]
           out)
        "the beam turns off where it stands, crosses dark, and stops before it turns on")))

(deftest frame-starts-and-ends-dark-with-blocked-areas-test
  (let [s {:limit [-1 -1 1 1] :blocked [[0.8 0.8 0.9 0.9]]}
        out (safety/apply-safety s [(w -0.5 0.0) (w 0.0 0.0) (w 0.5 0.0)])]
    (is (= [(d -0.5 0.0) (d -0.5 0.0) (w -0.5 0.0) (w 0.0 0.0) (w 0.5 0.0) (d 0.5 0.0)] out)))
  (testing "with only a limit nothing is added"
    (is (= [(w -0.5 0.0) (w 0.5 0.0)]
           (safety/apply-safety {:limit [-0.9 -0.9 0.9 0.9]} [(w -0.5 0.0) (w 0.5 0.0)])))))

(deftest sanitize-changes-nothing-but-a-dark-start-test
  (check-zones!
   (fn [s frame out]
     (let [sanitized (rules/sanitize-frame out)]
       (is (or (= out sanitized)
               (and (seq out) (= (into [(d ((first out) 0) ((first out) 1))] out) sanitized))
               (and (= 1 (count out)) (= [(first out) (first out)] sanitized)))
           (pr-str {:zone s :frame frame :out out}))))))


;; widens?


(deftest widens-test
  (doseq [[old new expected]
          [[{:limit [-0.9 -0.9 0.9 0.9]} {:limit [-1 -0.9 0.9 0.9]} true]
           [{:limit [-0.9 -0.9 0.9 0.9]} {:limit [-0.8 -0.9 0.9 0.9]} false]
           [{:limit [-0.9 -0.9 0.9 0.9] :blocked [[-1.0 -1.0 -0.5 -0.5]]}
            {:limit [-0.9 -0.9 0.9 0.9] :blocked [[-0.9 -0.9 -0.5 -0.5]]} false]
           [{:blocked [[0 0 0.5 0.5]]} {:blocked []} true]
           [{:blocked []} {:blocked [[0 0 0.5 0.5]]} false]
           [{:blocked [[0 0 0.5 0.5]]} {:blocked [[0 0 0.4 0.5]]} true]
           [{:blocked [[0 0 0.5 0.5]]} {:blocked [[-0.1 -0.1 0.6 0.6]]} false]
           [{:blocked [[0 0 0.5 0.5]]} {:blocked [[0 0 0.25 0.5] [0.25 0 0.5 0.5]]} true]
           [{:limit [-1 -1 1 1] :blocked [[0.5 -1 1 1]]} {:limit [-1 -1 0.4 1]} false]
           [{:limit [0.5 0.5 0.6 0.6]} {:limit "unreadable"} false]
           [{:limit "unreadable"} {:limit [0.5 0.5 0.6 0.6]} true]]]
    (is (= expected (safety/widens? old new)) (pr-str [old new]))))

(deftest widens-is-sound-test
  (let [rng (java.util.Random. 1119)
        allowed? (fn [s [x y]] (safety/inside? s x y))]
    (dotimes [_ 1500]
      (let [old (random-zone rng)
            new (if (.nextBoolean rng)
                  (random-zone rng)
                  ;; a small change of old, so "no" answers are common
                  (update old :limit (fn [[x0 y0 x1 y1]] [(+ x0 0.05) y0 x1 (- y1 0.05)])))]
        (when-not (safety/widens? old new)
          (dotimes [_ 40]
            (let [p [(- (* 2.0 (.nextDouble rng)) 1.0) (- (* 2.0 (.nextDouble rng)) 1.0)]]
              (is (or (not (allowed? new p)) (allowed? old p))
                  (pr-str {:old old :new new :point p})))))))))


;; mirror-x


(deftest mirror-x-test
  (is (= {:limit [-0.8 -0.9 0.7 0.95] :blocked [[0.5 -1.0 0.9 -0.4]]}
         (safety/mirror-x {:limit [-0.7 -0.9 0.8 0.95] :blocked [[-0.9 -1.0 -0.5 -0.4]]}))))
