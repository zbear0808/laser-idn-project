(ns laser-show.animation.fit-guard-test
  "The fit guard (plans/2026-11-19-zones/01 §8, 05 §4, §8): a laser's fit
   settings, clamped; the budget; what thinning keeps and how it spreads the
   cut; dimming; the smoothed record and its low flag; seeded invariants."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.fit-guard :as fg]
            [laser-show.animation.types :as t]))

(deftest settings-are-clamped-test
  (is (= {:min-fps 20 :reduce-points? true :point-floor 0.6 :dim? false :dim-floor 0.5} (fg/settings nil))
      "no :fit: the defaults (D-18)")
  (is (= fg/defaults (fg/settings {})))
  (is (= (assoc fg/defaults :min-fps 30 :dim? true :dim-floor 0.7)
         (fg/settings {:min-fps 30 :dim? true :dim-floor 0.7 :colour "red"}))
      "a laser's own over the defaults, and nothing else")
  (testing "clamped to 5-60, 0.3-1.0 and 0.2-1.0"
    (is (= [5 60 22.5] (mapv #(:min-fps (fg/settings {:min-fps %})) [1 100 22.5])))
    (is (= [0.3 1.0] (mapv #(:point-floor (fg/settings {:point-floor %})) [0.1 2])))
    (is (= [0.2 1.0] (mapv #(:dim-floor (fg/settings {:dim-floor %})) [0.0 5]))))
  (testing "a value of the wrong type takes its default"
    (is (= fg/defaults (fg/settings {:min-fps "fast" :reduce-points? "yes" :point-floor nil :dim? 1 :dim-floor ##NaN})))
    (is (= fg/defaults (fg/settings {:min-fps ##Inf})))
    (is (= fg/defaults (fg/settings "not a map")))))


;; Frames

(defn- lit-circle
  "n lit points round a circle (white): no corner once n is 9 or more."
  [n]
  (mapv (fn [i] (let [a (/ (* 2.0 Math/PI i) n)] [(* 0.5 (Math/cos a)) (* 0.5 (Math/sin a)) 1.0 1.0 1.0]))
        (range n)))

(defn- dark [x y] [x y 0.0 0.0 0.0])

(defn- lit [x y] [x y 1.0 1.0 1.0])

(defn- line
  "n lit points along x from x0, step dx, at y."
  [n x0 dx y]
  (mapv #(lit (+ x0 (* % dx)) y) (range n)))

(defn- guarded [frame scan-rate & {:as fit}]
  (fg/guard frame scan-rate (fg/settings fit)))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))


;; The budget and the guard on the spec's table (05 §8)

(deftest under-budget-is-the-same-frame-test
  (let [frame (lit-circle 1000)
        {out :frame fit :fit} (guarded frame 25000)]
    (is (identical? frame out) "under budget: the same object")
    (is (= {:points-in 1000 :points 1000 :budget 1250 :min-fps 20 :scan-rate 25000
            :est-fps 25.0 :guard-fps 25.0 :reduced-pct 0.0 :dim-pct 0.0}
           fit)
        "and a record with no cut")
    (is (identical? frame (:frame (guarded frame 25000 :dim? true))) "dimming allowed or not")
    (is (identical? frame (:frame (guarded frame 20000))) "exactly at the minimum: 1,000 fit 20 fps at 20,000")))

(deftest budget-and-target-points-test
  (is (= 1250 (fg/budget 25000 20)))
  (is (= 1304 (fg/budget 30000 23)) "the floor, not the ceiling (S-5)")
  (is (= 1250 (fg/target-points 2000 1250 fg/defaults)))
  (is (= 1800 (fg/target-points 3000 1250 fg/defaults)) "never under 60% of the points")
  (is (= 7 (fg/target-points 10 1 (assoc fg/defaults :point-floor 0.7))) "⌈0.7 · 10⌉ is 7, though 0.7 · 10 is 7.000000000000001")
  (testing "1 dark + 1,999 lit (a circle): thinned to the budget, 12.5 → 20 fps, 750 of its 1,997 removable points"
    (let [{:keys [frame fit]} (guarded (into [(dark 0.5 0.0)] (lit-circle 1999)) 25000)]
      (is (= 1250 (count frame) (:points fit)))
      (is (= [2000 1250] [(:points-in fit) (:budget fit)]))
      (is (close? 12.5 (:est-fps fit)))
      (is (close? 20.0 (:guard-fps fit)))
      (is (close? 37.5 (:reduced-pct fit)))
      (is (zero? (:dim-pct fit)))
      (is (false? (:low? (fg/smooth nil fit))))))
  (testing "3,000 lit: the floor stops it at 1,800, 8.3 → 13.9 fps, still low"
    (let [{:keys [frame fit]} (guarded (lit-circle 3000) 25000)]
      (is (= 1800 (count frame)))
      (is (close? (/ 25000.0 3000) (:est-fps fit)))
      (is (close? (/ 25000.0 1800) (:guard-fps fit)))
      (is (close? 40.0 (:reduced-pct fit)))
      (is (true? (:low? (fg/smooth nil fit))))
      (testing "with dimming on: × max(0.5, 0.69), −31% bright"
        (let [{:keys [frame fit]} (guarded (lit-circle 3000) 25000 :dim? true)]
          (is (close? (* 100 (- 1 (/ (/ 25000.0 1800) 20))) (:dim-pct fit)))
          (is (= 31 (Math/round (double (:dim-pct fit)))))
          (is (every? #(close? (/ (/ 25000.0 1800) 20) (% 2)) frame))))))
  (testing "3,000 all dark: every point kept, and it says so (S-14)"
    (let [frame (vec (repeat 3000 (dark 0.1 0.1)))
          {out :frame fit :fit} (guarded frame 25000)]
      (is (identical? frame out))
      (is (= 3000 (:points fit)))
      (is (true? (:dark? fit)))
      (is (zero? (:reduced-pct fit)))))
  (testing "S-5: at 30,000 pts/s and a minimum of 23, 2,000 points thin to 1,304, which isn't low"
    (let [{:keys [frame fit]} (guarded (lit-circle 2000) 30000 :min-fps 23)]
      (is (= 1304 (count frame)))
      (is (< 23.0 (:guard-fps fit) 23.01))
      (is (false? (:low? (fg/smooth nil fit))) "with the ceiling's 1,305 it would read 22.99, low"))))


;; What thinning keeps

(def ^:private keeper-frame
  "Two lit runs. Kept: 0 (dark), 1 (a run's start), 3 and 4 (a dwell: one
   place twice), 6 (a 90° corner), 9 (a run's end), 10 and 11 (dark), 12 and
   14 (the second run's ends). Removable: 2, 5, 7, 8 and 13."
  [(dark 0.0 0.0)
   (lit 0.0 0.0) (lit 0.1 0.0) (lit 0.2 0.0) (lit 0.2 0.0) (lit 0.3 0.0) (lit 0.4 0.0)
   (lit 0.4 0.1) (lit 0.4 0.2) (lit 0.4 0.3)
   (dark 0.4 0.3) (dark -0.5 0.5)
   (lit -0.5 0.5) (lit -0.4 0.5) (lit -0.3 0.5)])

(deftest thinning-keeps-blanking-ends-dwell-and-corners-test
  (is (= [true true false true true false true false false true true true true false true]
         (vec (fg/keep-mask keeper-frame))))
  (is (= (mapv keeper-frame [0 1 3 4 6 9 10 11 12 14]) (fg/thin keeper-frame 100))
      "asked for more than it can take: every removable point goes, nothing else")
  (is (= (mapv keeper-frame [0 1 2 3 4 6 7 9 10 11 12 13 14]) (fg/thin keeper-frame 2))
      "two of them: both from the first run, which has 4 of the 5, its 2nd and 4th")
  (testing "a turn of more than 45° is a corner, a gentler one isn't"
    (let [turn (fn [deg] (let [r (Math/toRadians deg)]
                           [(lit -0.2 0.0) (lit -0.1 0.0) (lit 0.0 0.0)
                            (lit (* 0.1 (Math/cos r)) (* 0.1 (Math/sin r)))
                            (lit (* 0.2 (Math/cos r)) (* 0.2 (Math/sin r)))]))]
      (is (= [true false false false true] (vec (fg/keep-mask (turn 30)))))
      (is (= [true false false false true] (vec (fg/keep-mask (turn 44)))))
      (is (= [true false true false true] (vec (fg/keep-mask (turn 46)))))
      (is (= [true false true false true] (vec (fg/keep-mask (turn 120)))))))
  (testing "a colour change isn't kept"
    (is (= [true false true] (vec (fg/keep-mask [(lit 0.0 0.0) [0.1 0.0 1.0 0.0 0.0] [0.2 0.0 0.0 1.0 0.0]])))))
  (testing "a lone lit point is its run's start and end"
    (is (= [true true true] (vec (fg/keep-mask [(dark 0.0 0.0) (lit 0.1 0.0) (dark 0.2 0.0)]))))))

(deftest thinning-spreads-evenly-along-a-run-test
  (let [run (line 102 -1.0 0.01 0.0)
        out (fg/thin run 25)
        kept (set out)
        gone (vec (keep-indexed (fn [i p] (when-not (kept p) i)) run))]
    (is (= 77 (count out)))
    (is (= (vec (range 4 101 4)) gone) "every 4th of its 100 removable points: 4, 8 … 100")
    (is (= [(first run) (peek run)] [(first out) (peek out)]) "its ends stay")))

(deftest thinning-shares-the-cut-across-runs-test
  (let [run-a (line 62 -1.0 0.01 0.5)
        run-b (line 22 -1.0 0.01 -0.5)
        frame (vec (concat run-a [(dark 0.0 0.0)] run-b))
        out (set (fg/thin frame 40))
        removed (fn [run] (count (remove out run)))]
    (is (= [30 10] [(removed run-a) (removed run-b)]) "in proportion to their removable points, 60 and 20"))
  (testing "largest remainder, ties in frame order"
    (let [runs (mapv #(line 5 -1.0 0.01 (* 0.3 %)) (range 3))
          frame (vec (interpose (dark 0.0 0.0) runs))
          frame (vec (mapcat #(if (vector? (first %)) % [%]) frame))
          out (set (fg/thin frame 4))]
      (is (= [2 1 1] (mapv #(count (remove out %)) runs)) "3 removable points each, 4 to remove: 1 each, and the first one more"))))

(deftest floor-stops-the-thinning-test
  (let [frame (lit-circle 3000)]
    (is (= 1800 (count (:frame (guarded frame 25000)))) "60% of 3,000, though 1,250 would reach 20 fps")
    (is (= 1250 (count (:frame (guarded frame 25000 :point-floor 0.3)))) "with a floor of 30%: the budget")
    (is (identical? frame (:frame (guarded frame 25000 :point-floor 1.0))) "with a floor of 100%: nothing goes"))
  (testing "fewer removable points than the cut: all of them go, and no more"
    (let [frame (vec (concat (line 4 -1.0 0.1 0.0) (mapcat (fn [i] [(dark (* 0.001 i) 0.5) (lit (* 0.001 i) 0.5)]) (range 1000))))
          {out :frame fit :fit} (guarded frame 1000)]
      (is (= 2002 (:points fit) (count out)) "the line's 2 removable points; the lone lit points are each a run's ends")
      (is (= 2004 (:points-in fit))))))

(deftest dim-only-when-allowed-and-still-low-test
  (let [heavy (lit-circle 3000)]
    (is (every? #(= [1.0 1.0 1.0] (subvec % 2 5)) (:frame (guarded heavy 25000))) "not allowed: no dimming")
    (is (zero? (:dim-pct (:fit (guarded heavy 25000)))))
    (is (every? #(< (% 2) 1.0) (:frame (guarded heavy 25000 :dim? true))) "allowed, and still low after thinning: dimmed"))
  (testing "allowed, but thinning reached the minimum: not dimmed"
    (let [{:keys [frame fit]} (guarded (into [(dark 0.5 0.0)] (lit-circle 1999)) 25000 :dim? true)]
      (is (zero? (:dim-pct fit)))
      (is (every? #(= [1.0 1.0 1.0] (subvec % 2 5)) (remove t/blanked? frame)))))
  (testing "never under the dim floor"
    (let [{:keys [frame fit]} (guarded (lit-circle 6000) 25000 :dim? true :point-floor 1.0)]
      (is (close? 50.0 (:dim-pct fit)) "4.2 fps of 20 would be ×0.21; the floor is ×0.5")
      (is (every? #(close? 0.5 (% 2)) frame))))
  (testing "dark points stay dark"
    (let [frame (vec (concat [(dark 0.0 0.0)] (lit-circle 2999)))
          {out :frame} (guarded frame 25000 :dim? true :point-floor 1.0)]
      (is (= [0.0 0.0 0.0] (subvec (first out) 2 5))))))

(deftest reduce-off-keeps-every-point-test
  (let [frame (lit-circle 3000)
        {out :frame fit :fit} (guarded frame 25000 :reduce-points? false)]
    (is (identical? frame out))
    (is (= [3000 3000 0.0] [(:points-in fit) (:points fit) (:reduced-pct fit)]))
    (is (close? (:est-fps fit) (:guard-fps fit)) "it draws what it would")
    (is (true? (:low? (fg/smooth nil fit))))
    (testing "dimming alone"
      (let [{out :frame fit :fit} (guarded frame 25000 :reduce-points? false :dim? true)]
        (is (= 3000 (count out)))
        (is (close? 50.0 (:dim-pct fit)))))))


;; The record as the card shows it

(defn- record [guard-fps & {:keys [dark? min-fps] :or {min-fps 20}}]
  (cond-> {:points-in 1500 :points 1500 :budget 1250 :min-fps min-fps :scan-rate 25000
           :est-fps guard-fps :guard-fps guard-fps :reduced-pct 0.0 :dim-pct 0.0}
    dark? (assoc :dark? true)))

(defn- smoothed [records] (reductions fg/smooth nil records))

(deftest smooth-averages-and-low-has-hysteresis-test
  (is (nil? (fg/smooth nil nil)) "no record (nothing playing): none")
  (is (nil? (fg/smooth (fg/smooth nil (record 30.0)) nil)))
  (let [first-one (fg/smooth nil (record 15.0))]
    (is (= [15.0 15.0 true] ((juxt :est-fps :guard-fps :low?) first-one)) "the first record is its own average")
    (is (= 1500 (:points-in first-one)) "and the rest of the record is kept"))
  (let [rs (vec (rest (smoothed (concat (repeat 10 (record 25.0)) (repeat 10 (record 15.0))))))]
    (is (close? 25.0 (:guard-fps (nth rs 9))))
    (is (close? (+ (* 0.9 25.0) (* 0.1 15.0)) (:guard-fps (nth rs 10))) "about 10 frames' worth: an EMA of 0.1")
    (is (not-any? :low? (take 16 rs)) "the average takes 7 frames to fall under 20")
    (is (:low? (nth rs 16)))
    (testing "it stays low until 1 fps above the minimum"
      (let [after (vec (rest (reductions fg/smooth (peek rs) (repeat 40 (record 20.5)))))]
        (is (< 20.0 (:guard-fps (peek after)) 21.0))
        (is (every? :low? after) "20.5 fps on average is above 20, but not 21"))
      (let [after (vec (rest (reductions fg/smooth (peek rs) (repeat 40 (record 25.0)))))]
        (is (not (:low? (peek after))))
        (is (every? #(or (:low? %) (<= 21.0 (:guard-fps %))) after) "cleared only at 21")))))

(deftest dark-frames-leave-low-as-it-was-test
  (testing "lit frames at the minimum, every other one dark (a Strobe): never low (S-14)"
    (let [rs (rest (smoothed (take 200 (cycle [(record 20.0) (record 8.3 :dark? true)]))))]
      (is (not-any? :low? rs))
      (is (every? #(close? 20.0 (:guard-fps %)) rs) "the dark ones leave the average alone")
      (is (every? :dark? (take-nth 2 (rest rs))) "each still says what it is")))
  (testing "low, then the Master pulled down: still low, not cleared"
    (let [low (reduce fg/smooth nil (repeat 20 (record 10.0)))]
      (is (:low? low))
      (is (every? :low? (rest (reductions fg/smooth low (repeat 50 (record 8.3 :dark? true))))))))
  (testing "dark before anything lit: not low, and the next lit frame starts the average"
    (let [d (fg/smooth nil (record 8.3 :dark? true))]
      (is (false? (:low? d)))
      (is (close? 30.0 (:guard-fps (fg/smooth d (record 30.0)))))))
  (testing "the guard says a frame with no lit point is dark, under budget too"
    (is (:dark? (:fit (guarded (vec (repeat 100 (dark 0.0 0.0))) 25000))))
    (is (not (:dark? (:fit (guarded (lit-circle 100) 25000)))))))


;; Seeded properties (invariant 6)

(defn- random-frame
  "A frame of runs, dark points between them: lit stretches that are
   straight, round, zigzag (corners) or each place twice (dwells)."
  [^java.util.Random rng]
  (vec (mapcat (fn [_]
                 (let [n (.nextInt rng 400)
                       kind (.nextInt rng 4)
                       x0 (- (.nextDouble rng) 0.5) y0 (- (.nextDouble rng) 0.5)
                       c [(.nextDouble rng) (.nextDouble rng) (+ 0.1 (.nextDouble rng))]
                       run (vec (for [i (range n)]
                                  (let [[x y] (case kind
                                                0 [(+ x0 (* i 0.001)) y0]
                                                1 [(+ x0 (* 0.3 (Math/cos (* i 0.05)))) (+ y0 (* 0.3 (Math/sin (* i 0.05))))]
                                                2 [(+ x0 (* 0.002 i)) (+ y0 (* 0.01 (mod i 2)))]
                                                [(+ x0 (* 0.001 (quot i 2))) y0])]
                                    (into [x y] c))))
                       gap (repeatedly (.nextInt rng 3) #(dark (- (.nextDouble rng) 0.5) (- (.nextDouble rng) 0.5)))]
                   (concat gap run)))
               (range (inc (.nextInt rng 6))))))

(defn- subsequence?
  "Are ys, by (k point), in xs, in order?"
  [xs ys k]
  (loop [i 0 j 0]
    (cond
      (= j (count ys)) true
      (= i (count xs)) false
      (= (k (xs i)) (k (ys j))) (recur (inc i) (inc j))
      :else (recur (inc i) j))))

(defn- position [pt] (subvec pt 0 2))

(deftest guard-invariants-test
  (let [rng (java.util.Random. 20261120)]
    (dotimes [_ 2000]
      (let [frame (random-frame rng)
            settings (fg/settings {:min-fps (+ 5 (.nextInt rng 56))
                                   :reduce-points? (< (.nextDouble rng) 0.85)
                                   :point-floor (+ 0.3 (* 0.7 (.nextDouble rng)))
                                   :dim? (.nextBoolean rng)
                                   :dim-floor (+ 0.2 (* 0.8 (.nextDouble rng)))})
            scan-rate (+ 1000 (.nextInt rng 99001))
            {out :frame fit :fit} (fg/guard frame scan-rate settings)
            n (count frame)
            budget (fg/budget scan-rate (:min-fps settings))
            mask (fg/keep-mask frame)
            kept (filterv #(aget ^booleans mask %) (range n))
            dimmed? (pos? (:dim-pct fit))]
        (is (= (count out) (:points fit)))
        (is (= n (:points-in fit)))
        (if (<= n budget)
          (is (identical? frame out) "under budget: the same object")
          (do
            (is (subsequence? frame out position) "a subsequence of its input")
            (is (subsequence? out (mapv frame kept) position)
                "every blanked point, run end, dwell point and corner stays")
            (when (and (:reduce-points? settings) (not (:dark? fit)))
              (let [target (fg/target-points n budget settings)]
                (is (<= target (count out)) "never fewer than the floor's points")
                (is (= (max target (count kept)) (count out))
                    "as few as the floor allows, or every removable point gone")))
            (if dimmed?
              (do (is (:dim? settings) "dims only when allowed")
                  (is (< (:guard-fps fit) (:min-fps settings)) "and only while still low"))
              (is (subsequence? frame out identity) "no colour changes without dimming"))))
        (when (not-any? #(not (t/blanked? %)) frame)
          (is (:dark? fit))
          (doseq [low? [true false]]
            (let [prev (assoc (record (if low? 10.0 30.0)) :low? low? :samples 5)]
              (is (= low? (:low? (fg/smooth prev fit))) "a frame with no lit point never sets or clears low"))))))))
