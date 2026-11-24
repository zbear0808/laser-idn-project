(ns laser-show.routing.step-grid-test
  "The step grid's edits (zones plan Z6, 08 §4, §8): every one takes a zone
   pattern's params and returns them, with every step naming only rows, each
   once, in row order (08 invariant 2)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [laser-show.routing.step-grid :as sg]
            [laser-show.routing.zone-patterns :as zone-patterns]))

(def ^:private A :zone/a)
(def ^:private B :zone/b)
(def ^:private C :zone/c)
(def ^:private D :zone/d)

(defn- grid
  "A grid's params: rows (A, B, C unless said) and steps."
  ([steps] (grid [A B C] steps))
  ([rows steps] {:style :steps :zones rows :steps steps :rate 2.0}))

(defn- lit
  "Each step's lit refs, as sets: what the grid lights, whatever the order."
  [params]
  (mapv set (:steps params)))


;; One cell

(deftest toggle-adds-and-removes-test
  (let [g (grid [[] [] []])]
    (is (= [[A] [] []] (:steps (sg/toggle g A 0))) "an unlit cell lights")
    (is (= [[] [] []] (:steps (sg/toggle (sg/toggle g A 0) A 0))) "a lit one clears")
    (is (= [[A C] [] []] (:steps (-> g (sg/toggle C 0) (sg/toggle A 0)))) "two rows on one step")
    (testing "outside the grid, or not a row: nothing changes"
      (is (= g (sg/toggle g D 0)) "D isn't a row")
      (is (= g (sg/toggle g A 3)) "there's no step 3")
      (is (= g (sg/toggle g A -1)))
      (is (= g (sg/toggle g nil 0))))))

(deftest steps-keep-row-order-test
  (is (= [[A B C]] (:steps (reduce #(sg/toggle %1 %2 0) (grid [[]]) [C A B]))) "lit in any order: in row order")
  (is (= [[C A]] (:steps (reduce #(sg/toggle %1 %2 0) (grid [C B A] [[]]) [A C]))) "the rows' order, not the zones'")
  (is (= [[B]] (:steps (sg/toggle (grid [[B B A]]) A 0))) "a ref twice is lit once"))

(deftest paint-sets-not-toggles-test
  (let [g (grid [[A] []])]
    (is (= [[A] []] (:steps (sg/paint g A 0 true))) "painting a lit cell on leaves it on")
    (is (= [[A] [A]] (:steps (sg/paint g A 1 true))))
    (is (= [[] []] (:steps (sg/paint g A 0 false))))
    (is (= [[A] []] (:steps (sg/paint g A 1 false))) "painting an unlit cell off leaves it off")))

(deftest lit-test
  (let [g (grid [[A C] []])]
    (is (true? (sg/lit? g A 0)))
    (is (false? (sg/lit? g B 0)))
    (is (false? (sg/lit? g A 1)))
    (is (false? (sg/lit? g A 7)) "outside the grid")))


;; Length

(deftest set-length-pads-and-cuts-test
  (let [g (grid [[A] [B] [C] [A B]])]
    (is (= [[A] [B] [C] [A B] [] [] [] []] (:steps (sg/set-length g 8))) "longer: rests at the end")
    (is (= [[A] [B]] (:steps (sg/set-length g 2))) "shorter: the end is cut")
    (is (= [[A]] (:steps (sg/set-length g 1))) "1 is the shortest")
    (is (= [[A]] (:steps (sg/set-length g 0))) "0 is 1")
    (is (= [[A]] (:steps (sg/set-length g -5))))
    (is (= 64 (count (:steps (sg/set-length g 64)))) "64 is the longest")
    (is (= 64 (count (:steps (sg/set-length g 99)))))
    (is (= g (sg/set-length g 4)) "the same length: the same grid")
    (is (= g (sg/set-length g "8")) "not a number: nothing")
    (is (= [8 16 32] [sg/new-length 16 32]) "8 steps a new grid (Z6-Q1 A)")))


;; Rows

(deftest add-row-refuses-a-row-twice-test
  (let [g (grid [A B] [[A] [B]])]
    (is (= [A B C] (:zones (sg/add-row g C))) "added at the bottom")
    (is (= [[A] [B]] (:steps (sg/add-row g C))) "unlit")
    (is (= g (sg/add-row g A)) "A is a row already")
    (is (= g (sg/add-row g :lane/lasers)) "Every laser can't be a row (S-4)")
    (is (= g (sg/add-row g nil)))
    (is (= [A B :graphics] (:zones (sg/add-row g :graphics))) "a group can be a row")
    (testing "+ Row: the next zone letter"
      (is (= C (sg/next-row g)))
      (is (= B (sg/next-row (grid [A C] [[]])))))))

(deftest remove-row-clears-its-steps-test
  (let [g (grid [[A B] [B] [A C]])
        out (sg/remove-row g A)]
    (is (= [B C] (:zones out)))
    (is (= [[B] [B] [C]] (:steps out)) "no step names it any more (invariant 2)")
    (is (= g (sg/remove-row g D)) "not a row: nothing")))

(deftest replace-row-rewrites-steps-test
  (let [g (grid [[A B] [B] [A C]])
        out (sg/replace-row g A D)]
    (is (= [D B C] (:zones out)) "in A's place")
    (is (= [[D B] [B] [D C]] (:steps out)) "every step that lit A lights D")
    (is (= g (sg/replace-row g A B)) "B is a row already: refused")
    (is (= g (sg/replace-row g D A)) "D isn't a row")
    (is (= g (sg/replace-row g A :lane/lasers)))
    (is (= [:graphics B C] (:zones (sg/replace-row g A :graphics))) "a group")))

(deftest move-row-keeps-steps-test
  (let [g (grid [[A B] [C] [A C]])]
    (is (= [B A C] (:zones (sg/move-row g A 1))) "down one")
    (is (= [[B A] [C] [A C]] (:steps (sg/move-row g A 1))) "each step in the new row order, lighting the same")
    (is (= (lit g) (lit (sg/move-row g A 1))))
    (is (= [C A B] (:zones (sg/move-row g C -2))) "up two")
    (is (= g (sg/move-row g A -1)) "the top row can't go up")
    (is (= g (sg/move-row g C 1)) "nor the bottom one down")
    (is (= [B C A] (:zones (sg/move-row g A 9))) "past the end: the bottom")
    (is (= g (sg/move-row g D 1)))))


;; Whole steps, whole rows

(deftest fill-row-test
  (let [g (grid [[B] [B] [] [] [] [] [] []])]
    (is (= (vec (repeat 8 #{A})) (mapv #(set (filter #{A} %)) (:steps (sg/fill-row g A 1)))) "every step")
    (is (= [[A B] [B] [A] [] [A] [] [A] []] (:steps (sg/fill-row g A 2))) "every 2nd, from the first; the others clear")
    (is (= [[A B] [B] [] [] [A] [] [] []] (:steps (sg/fill-row g A 4))) "every 4th")
    (is (= [[A B] [B] [A] [] [A] [] [A] []] (:steps (sg/fill-row (sg/fill-row g A 1) A 2))) "it sets the row, not adds to it")
    (is (= g (sg/fill-row g D 2)))
    (is (= g (sg/fill-row g A 0)))))

(deftest clear-row-step-and-grid-test
  (let [g (grid [[A B] [B C] [A]])]
    (is (= [[B] [B C] []] (:steps (sg/clear-row g A))))
    (is (= [[A B] [] [A]] (:steps (sg/clear-step g 1))))
    (is (= [[A B] [A B C] [A]] (:steps (sg/fill-step g 1))) "Light every row")
    (is (= [[] [] []] (:steps (sg/clear g))) "Clear keeps the length")
    (is (= [A B C] (:zones (sg/clear g))) "and the rows")
    (is (= g (sg/clear-step g 5)))))

(deftest rotate-test
  (let [g (grid [[A] [B] [C] []])]
    (is (= [[] [A] [B] [C]] (:steps (sg/rotate g 1))) "one step later, the last wrapping to the first")
    (is (= [[B] [C] [] [A]] (:steps (sg/rotate g -1))) "one step earlier")
    (is (= g (sg/rotate g 4)) "a whole turn")
    (is (= (sg/rotate g 1) (sg/rotate g 5)))
    (is (= (sg/rotate g -1) (sg/rotate (sg/rotate (sg/rotate g 1) 1) 1)))
    (is (= (grid []) (sg/rotate (grid []) 1)) "no steps")))


;; Starting points (08 §8's Start from table, rows A-D, 8 steps)

(def ^:private abcd [A B C D])

(defn- letters
  "Steps as the table writes them: \"A C · B D · …\"."
  [steps]
  (str/join " · " (map #(str/join " " (map (comp str/upper-case name) %)) steps)))

(deftest from-style-test
  (let [g (grid abcd (vec (repeat 8 [])))
        start #(letters (:steps (sg/from-style g % 8)))]
    (is (= "A C · B D · A C · B D · A C · B D · A C · B D" (start :alternate)))
    (is (= "A · B · C · D · A · B · C · D" (start :chase)))
    (is (= "A · B · C · D · C · B · A · B" (start :ping-pong)))
    (is (= "C · A · C · B · D · C · A · D" (start :random)) "seed 0")
    (is (= "B C · A D · B C · A D · B C · A D · B C · A D" (start :center-out)))
    (is (= (letters (repeat 8 abcd)) (start :all-on)))
    (testing "it is Z5's lit-set, step by step: invariant 6"
      (is (= "A B · B C · C D · A D" (letters (:steps (sg/from-style (assoc g :width 2) :chase 4))))
          "a two-wide Chase: the pattern's own width, each step in row order")
      (is (every? (fn [step] (= step (filterv (set step) abcd))) (:steps (sg/from-style g :random 8))) "in row order"))
    (is (= 16 (count (:steps (sg/from-style g :chase 16)))) "the length it is given")
    (is (= [[] []] (:steps (sg/from-style (grid [] [[]]) :chase 2))) "no rows: rests")))

(deftest center-out-test
  (is (= "B · A C · B · A C · B · A C" (letters (sg/center-out [A B C] 6))) "3 rows: the middle, then one further out")
  (is (= "B C · A D · B C · A D" (letters (sg/center-out abcd 4))) "4 rows: the middle two")
  (is (= "C · B D · A E · C · B D · A E" (letters (sg/center-out [A B C D :zone/e] 6))) "5 rows")
  (is (= "A · A" (letters (sg/center-out [A] 2))) "1 row")
  (is (= "A B · A B" (letters (sg/center-out [A B] 2))) "2 rows: both are the middle")
  (is (= [[] []] (sg/center-out [] 2)) "no rows"))

(deftest every-laser-becomes-zones-test
  (testing "Every laser can't be a row (its length changes with the rig): it becomes zones A, B, C… (S-4)"
    (is (= [A B C D] (sg/grid-rows [:lane/lasers] 4)) "as many as the rig has lasers")
    (is (= [A B] (sg/grid-rows [:lane/lasers] 1)) "at least 2")
    (is (= [A B] (sg/grid-rows [:lane/lasers] 0)))
    (is (= [:graphics A B C] (sg/grid-rows [:graphics :lane/lasers] 3)) "in its place")
    (is (= [B C A] (sg/grid-rows [:lane/lasers A] 2)) "the zones the lane doesn't use yet")
    (is (= [A :graphics] (sg/grid-rows [A :graphics A] 3)) "no Every laser: the refs, once each"))
  (testing "which zones it became, for the window's note"
    (is (= [A B C D] (sg/became-zones [:lane/lasers] 4)))
    (is (= [B C] (sg/became-zones [:lane/lasers A] 2)))
    (is (nil? (sg/became-zones [A B] 4)))))

(deftest as-grid-test
  (testing "a pattern becomes a grid of its own style's first 8 steps, at its own rate, so it plays the same for them"
    (let [chase (:params (zone-patterns/preset-instance :chase :cue))
          out (sg/as-grid (assoc chase :zones abcd :rate 1.5 :step-on :hit) 4)]
      (is (= :steps (:style out)))
      (is (= "A · B · C · D · A · B · C · D" (letters (:steps out))))
      (is (= [1.5 :hit :cue] ((juxt :rate :step-on :clock) out)) "its rate, its Step on, its clock")
      (is (false? (:follow-pads? out)) "a grid doesn't follow the pads (Z6-Q2 A)")))
  (testing "Every laser: the rig's lasers as zones"
    (let [out (sg/as-grid (:params (zone-patterns/preset-instance :chase :cell)) 3)]
      (is (= [A B C] (:zones out)))
      (is (= "A · B · C · A · B · C · A · B" (letters (:steps out))))))
  (testing "a pattern a grid can't copy (Shift and Swap trade what's in the zones) starts as a new grid does, Alternate"
    (is (= "A · B · A · B · A · B · A · B" (letters (:steps (sg/as-grid (:params (zone-patterns/preset-instance :swap :cue)) 1)))))
    (is (= "A C · B D · A C · B D · A C · B D · A C · B D"
           (letters (:steps (sg/as-grid (:params (zone-patterns/preset-instance :shift :cue)) 1)))))
    (is (= "A · B · A · B · A · B · A · B" (letters (:steps (sg/as-grid {:style :steps :zones [A B] :steps []} 1))))
        "Steps with no steps yet"))
  (testing "a grid stays as it is"
    (let [g (grid [[A] [] [B C]])]
      (is (= g (sg/as-grid g 4)))
      (is (sg/grid? g))
      (is (not (sg/grid? (assoc g :steps []))))
      (is (not (sg/grid? (assoc g :style :chase))))))
  (testing "a pattern that was a grid, its style switched since, gets its steps back"
    (let [g (assoc (grid [[A] [] [B C]]) :style :chase :follow-pads? true)]
      (is (= (assoc g :style :steps) (sg/as-grid g 4)) "the steps, the rows and whether it follows the pads, as they were"))))


;; Copy and paste

(deftest paste-matches-rows-by-ref-test
  (let [from (grid [B A] [[A] [B] [A B]])
        clip (sg/copy from)
        to (grid [A C] [[C] [C] [C] [C]])
        out (sg/paste to clip)]
    (is (= {:zones [B A] :steps [[A] [B] [B A]]} clip) "a copy is the rows and the steps")
    (is (= [A C B] (:zones out)) "its rows, then the rows it lacked")
    (is (= [[A] [B] [A B]] (:steps out)) "each ref lights where it did: A's row is A's, whatever its place")
    (is (= 3 (count (:steps out))) "the copied grid's length")
    (is (= 2.0 (:rate out)) "and the rest of the pattern stays")
    (is (= to (sg/paste to nil)) "nothing copied: nothing")
    (is (= to (sg/paste to {:zones [A] :steps []})))))


;; The mouse

(deftest cell-at-test
  (let [at #(sg/cell-at %1 %2 28.0 24.0 3 8)]
    (is (= [0 0] (at 0.0 0.0)) "the top-left corner")
    (is (= [0 0] (at 27.9 23.9)))
    (is (= [1 1] (at 28.0 24.0)) "a cell's edge is the next cell's")
    (is (= [2 7] (at 223.9 71.9)) "the bottom-right cell")
    (is (nil? (at 224.0 10.0)) "past the last step")
    (is (nil? (at 10.0 72.0)) "below the last row")
    (is (nil? (at -0.1 10.0)) "left of the grid")
    (is (nil? (at 10.0 -3.0)) "above it")
    (is (nil? (sg/cell-at 10.0 10.0 28.0 24.0 0 8)) "no rows")
    (is (nil? (sg/cell-at 10.0 10.0 0.0 24.0 3 8)) "no width")))

(deftest line-cells-test
  (is (= [[0 1] [0 2] [0 3] [0 4]] (sg/line-cells [0 1] [0 4])) "along a row, every cell crossed")
  (is (= [[0 4] [0 3] [0 2]] (sg/line-cells [0 4] [0 2])) "backwards")
  (is (= [[0 0] [1 1] [2 2]] (sg/line-cells [0 0] [2 2])))
  (is (= [[1 5]] (sg/line-cells [1 5] [1 5])))
  (is (= [[0 3]] (sg/line-cells nil [0 3])) "no cell before: just this one"))


;; Normal form (08 invariant 2)

(deftest normalize-test
  (let [messy {:style :steps :zones [A B A C :graphics] :steps [[C A A] [:zone/z B] (list B) nil :not-a-step [:graphics]]}
        out (sg/normalize messy)]
    (is (= [A B C :graphics] (:zones out)) "rows once each")
    (is (= [[A C] [B] [B] [] [] [:graphics]] (:steps out)) "each step: rows only, once each, in row order; anything else a rest")
    (is (= out (sg/normalize out)) "normal is normal")
    (is (= [] (:steps (sg/normalize {:style :steps :zones [A]}))) "no steps")
    (is (= [[] []] (:steps (sg/normalize {:zones [] :steps [[A] [B]]}))) "no rows: rests")))


;; Finding a grid (reactivity plan 05)

(deftest locate-pattern-test
  (let [p-id #uuid "00000000-0000-0000-0000-0000000005a1"
        q-id #uuid "00000000-0000-0000-0000-0000000005b2"
        p (assoc (zone-patterns/preset-instance :steps :cell) :id p-id)
        q (assoc (zone-patterns/preset-instance :chase :cell) :id q-id)
        scale {:effect-id :scale :id #uuid "00000000-0000-0000-0000-0000000005c3" :params {}}]
    (is (= {:effect-path [1] :instance p} (sg/locate-pattern [q p] p-id)) "by its id, wherever it is")
    (is (= {:effect-path [1 :items 0] :instance p}
           (sg/locate-pattern [q {:type :group :id #uuid "00000000-0000-0000-0000-0000000005d4" :items [p]}] p-id))
        "in a group too")
    (is (nil? (sg/locate-pattern [q] p-id)) "gone: nothing, not what took its place")
    (is (nil? (sg/locate-pattern [scale] (:id scale))) "not a zone pattern")
    (is (nil? (sg/locate-pattern [(dissoc q :id)] nil)) "no id finds nothing, not an item without one")
    (is (nil? (sg/locate-pattern [(assoc q :id :q)] :q)) "an id that isn't a UUID")
    (is (nil? (sg/locate-pattern nil p-id)))))
