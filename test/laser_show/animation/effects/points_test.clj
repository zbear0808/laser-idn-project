(ns laser-show.animation.effects.points-test
  "Decimate (plans/2026-11-19-zones/12): a frame thinned to a target fps at
   a given scan rate, ⌊kpps · 1000 / fps⌋ points, never losing an anchor (a
   blanked point, a lit run's ends, a dwell point or a corner:
   fit-guard/keep-mask); in a chain, through the chain's one-by-one fallback,
   and with a modulated target fps."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.points :as points]
            [laser-show.animation.effects.shape]
            [laser-show.animation.fit-guard :as fg]
            [laser-show.animation.modulation :as mod]
            [laser-show.backend.output-errors :as oe]))


;; Frames

(defn- lit [x y] [x y 1.0 1.0 1.0])

(defn- dark [x y] [x y 0.0 0.0 0.0])

(defn- lit-circle
  "n lit points round a circle: one lit run with no corner, so only its two
   ends are anchors."
  [n]
  (mapv (fn [i] (let [a (/ (* 2.0 Math/PI i) n)] (lit (* 0.5 (Math/cos a)) (* 0.5 (Math/sin a)))))
        (range n)))

(defn- square
  "A lit square of side 2r round (cx, cy), drawn from its bottom-left corner
   round and back to it: n points an edge. The beam dwells (three points in
   one place) at the bottom-left and top-right corners, and turns 90°
   without dwelling at the other two."
  [cx cy r n]
  (let [corners [[(- cx r) (- cy r)] [(+ cx r) (- cy r)] [(+ cx r) (+ cy r)] [(- cx r) (+ cy r)]]
        dwell [3 1 3 1]
        edge (fn [[x0 y0] [x1 y1]]
               (for [i (range 1 n)
                     :let [s (/ (double i) n)]]
                 (lit (+ x0 (* s (- x1 x0))) (+ y0 (* s (- y1 y0))))))]
    (vec (concat (mapcat (fn [i]
                           (let [a (corners i) b (corners (mod (inc i) 4))]
                             (concat (repeatedly (dwell i) #(apply lit a)) (edge a b))))
                         (range 4))
                 [(apply lit (corners 0))]))))

(def ^:private anchored
  "Four squares (square), each a lit run reached by a blanked jump: a dark
   point where the beam was, then one where the square starts. 1,628
   points, 44 of them anchors: the 8 dark points, 24 dwell points (each
   run's start among them), 8 corners and the 4 runs' ends. The other 1,584
   are along the edges."
  (let [squares (mapv (fn [[cx cy]] (square cx cy 0.3 100)) [[-0.5 -0.5] [0.5 -0.5] [0.5 0.5] [-0.5 0.5]])]
    (vec (mapcat (fn [[px py] sq]
                   (let [[x y] (first sq)]
                     (concat [(dark px py) (dark x y)] sq)))
                 (cons [0.0 0.0] (map peek squares))
                 squares))))

(defn- tagged
  "frame with each point's index after its five values, so the points that
   come out can be told from equal ones (a dwell's)."
  [frame]
  (into [] (map-indexed (fn [i p] (conj p i))) frame))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))


;; Chains

(defn- decimate
  "An enabled Decimate, with params over its defaults."
  [& {:as params}]
  {:id (random-uuid) :effect-id :decimate :enabled? true :params (or params {})})

(defn- decimated
  "frame through a chain of one Decimate with params."
  [frame & params]
  (effects/apply-effect-chain frame {:effects [(apply decimate params)]}))

(defn- scale [s] {:id (random-uuid) :effect-id :scale :enabled? true :params {:x-scale s :y-scale s}})

(defn- translate [dx] {:id (random-uuid) :effect-id :translate :enabled? true :params {:x dx :y 0.0}})


;; The budget

(deftest decimate-reaches-the-target-fps-test
  (let [circle (lit-circle 3000)]
    (testing "the defaults, 25 kpps and 30 fps: 833 points"
      (is (= {:kpps 25 :fps 30} (effects/get-default-params :decimate)))
      (let [out (decimated circle)]
        (is (= 833 (count out)))
        (is (<= 30.0 (/ 25000.0 (count out))) "25,000 / 833 is 30.01 fps")))
    (is (= 1333 (count (decimated circle :kpps 40))) "at 40 kpps: ⌊40,000 / 30⌋")
    (is (= 416 (count (decimated circle :fps 60))) "at 60 fps: ⌊25,000 / 60⌋")))

(deftest decimate-keeps-every-anchor-test
  (let [frame (tagged anchored)
        out (decimated frame)
        at (mapv #(nth % 5) out)
        anchors (vec (keep-indexed (fn [i keep?] (when keep? i)) (fg/keep-mask anchored)))]
    (is (= [1628 44] [(count anchored) (count anchors)]) "over the budget, 833")
    (is (= 833 (count out)) "thinned to it: the edges have points enough to lose")
    (is (apply < at) "in order, each point once")
    (is (= out (mapv frame at)) "the frame's own points: a subsequence of it")
    (is (every? (set at) anchors) "with every anchor")
    (is (= (mapv #(subvec % 0 5) out) (decimated anchored)) "the index after each point changes nothing")))

(deftest under-budget-is-unchanged-test
  (let [frame (lit-circle 500)
        out (decimated frame)]
    (is (= frame out))
    (is (every? true? (map identical? frame out)) "the same points")
    (testing "at the budget too; one more and one goes"
      (is (= (lit-circle 833) (decimated (lit-circle 833))))
      (is (= 833 (count (decimated (lit-circle 834))))))))

(deftest anchors-win-over-the-budget-test
  (let [dashes (vec (mapcat (fn [i] (let [x (- (* 0.004 i) 0.8)] [(dark x 0.0) (lit x 0.1) (lit x 0.2)]))
                            (range 400)))]
    (is (every? true? (fg/keep-mask dashes)) "short dashes: every point dark or a dash's end")
    (is (= dashes (decimated dashes)) "back whole, 1,200 points, though the budget is 833")))


;; In a chain

(deftest decimate-in-a-chain-test
  (let [circle (lit-circle 3000)
        thinned (fg/thin (effects/apply-effect-chain circle {:effects [(scale 0.5)]}) (- 3000 833))
        out (effects/apply-effect-chain circle {:effects [(scale 0.5) (decimate) (translate 0.2)]})]
    (is (= 833 (count out)) "the count drops to the budget")
    (is (= (effects/apply-effect-chain thinned {:effects [(translate 0.2)]}) out)
        "Decimate thins what Scale made, and Translate gets the points it kept")
    (is (every? true? (map (fn [[x y] [x' y']] (and (close? (+ x 0.2) x') (close? y y'))) thinned out))
        "each moved 0.2 to the right")))

(def ^:private failing-effects
  [{:id :test-fails-at-point-100 :name "Test: fails at point 100" :category :shape :timing :static :parameters []
    :apply-transducer (fn [_ _ _ _] (map-indexed (fn [i pt] (if (= 100 i) (throw (ex-info "test: point 100" {})) pt))))}
   {:id :test-fails-per-point :name "Test: fails per point" :category :shape :timing :static :parameters []
    :apply-transducer (fn [_ _ _ _] (map (fn [_] (throw (ex-info "test: per point" {})))))}])

(defn- failing [effect-id] {:id effect-id :effect-id effect-id :enabled? true :params {}})

(deftest decimate-through-the-one-by-one-fallback-test
  (run! effects/register-effect! failing-effects)
  (oe/clear!)
  (try
    (log-test/with-log
      (let [circle (lit-circle 3000)
            chain [(scale 0.5) (decimate) (translate 0.2)]
            [s d t] chain
            expected (effects/apply-effect-chain circle {:effects chain})]
        (is (= 833 (count expected)))
        (testing "an effect before it fails 100 points in: the chain runs one effect at a time, and Decimate starts again from the whole frame"
          (is (= expected (effects/apply-effect-chain circle {:effects [s (failing :test-fails-at-point-100) d t]}))))
        (testing "an effect after it fails on the points it emits"
          (is (= expected (effects/apply-effect-chain circle {:effects [s d (failing :test-fails-per-point) t]}))))
        (testing "only the failing effects are reported"
          (is (= #{[:effect :test-fails-at-point-100] [:effect :test-fails-per-point]} (set (keys (oe/snapshot)))))))
      (testing "one at a time with no error is the composed pass"
        (let [circle (lit-circle 3000)
              chain {:effects [(scale 0.5) (decimate) (translate 0.2)]}]
          (is (= (effects/apply-effect-chain circle chain)
                 (#'effects/apply-effects-one-by-one circle chain {:point-count 3000 :timing-ctx nil}))))))
    (finally
      (oe/clear!)
      (swap! effects/!effect-registry #(apply dissoc % (map :id failing-effects))))))

(deftest decimate-passes-reduced-on-test
  (let [circle (lit-circle 3000)
        xf (effects/make-effect-transducer (decimate) {:point-count 3000 :timing-ctx nil})
        kept (into [] xf circle)]
    (is (= 833 (count kept)))
    (is (= (subvec kept 0 10) (into [] (comp xf (take 10)) circle))
        "an effect after it that stops early stops it: the first 10 points it kept")
    (is (= (subvec circle 0 10) (into [] (comp (take 10) xf) circle))
        "one before it that stops early leaves it 10 points, under the budget")))


;; Modulated

(defn- at-beat
  "A cue's timing context beats in, at 120 BPM."
  [beats]
  {:accumulated-beats beats :accumulated-ms (* 500.0 beats) :phase-offset 0.0 :effective-beats beats
   :time-ms (* 500.0 beats) :bpm 120.0})

(deftest modulated-fps-test
  (let [circle (lit-circle 3000)
        chain {:effects [(decimate :fps {:type :sine :min 30.0 :max 60.0 :period 1.0})]}
        evaluations (atom 0)
        evaluate mod/evaluate-modulator]
    (with-redefs [mod/evaluate-modulator (fn [config ctx] (swap! evaluations inc) (evaluate config ctx))]
      (is (= 416 (count (effects/apply-effect-chain circle chain (at-beat 0.0))))
          "on the beat the sine is at its top, 60 fps: ⌊25,000 / 60⌋")
      (is (= 1 @evaluations) "read once for the frame's 3,000 points")
      (is (= 833 (count (effects/apply-effect-chain circle chain (at-beat 0.5))))
          "half a beat on, at its bottom, 30 fps")
      (is (= 2 @evaluations) "and once for the next frame"))))


;; Read without running it: a laser's budget for the cue warnings (Q-D-2 B)

(deftest chain-budget-reads-the-params-test
  (is (= 833 (points/chain-budget [(decimate)])) "the defaults: ⌊25,000 / 30⌋")
  (is (= [1333 416] [(points/chain-budget [(decimate :kpps 40)]) (points/chain-budget [(decimate :fps 60)])]))
  (is (= (count (decimated (lit-circle 3000) :kpps 30 :fps 45)) (points/chain-budget [(decimate :kpps 30 :fps 45)]))
      "the count it leaves a frame with points to lose")
  (is (= 416 (points/chain-budget [(decimate) (scale 0.5) (decimate :fps 60)])) "several: the smallest")
  (is (= [208 33] [(points/chain-budget [(decimate :fps 500)]) (points/chain-budget [(decimate :kpps 0)])])
      "clamped as the effect clamps them: 120 fps, 1 kpps")
  (testing "modulated: at its default"
    (is (= 833 (points/chain-budget [(decimate :fps {:type :sine :min 30.0 :max 60.0 :period 1.0})])))
    (is (= 1333 (points/chain-budget [(decimate :kpps 40 :fps {:type :sine :min 30.0 :max 60.0 :period 1.0})]))
        "only the modulated one")
    (is (= 833 (points/chain-budget [(assoc (decimate :fps 60) :keyframe-modulator {:enabled? true :keyframes []})]))
        "under a keyframe modulator its params are the keyframes'"))
  (testing "only an enabled one counts, a group's too"
    (is (nil? (points/chain-budget [(assoc (decimate) :enabled? false)])))
    (is (= 416 (points/chain-budget [{:type :group :enabled? true :items [(decimate :fps 60)]}])))
    (is (nil? (points/chain-budget [{:type :group :enabled? false :items [(decimate :fps 60)]}]))))
  (is (nil? (points/chain-budget [(scale 0.5)])) "no Decimate: nil")
  (is (nil? (points/chain-budget nil))))
