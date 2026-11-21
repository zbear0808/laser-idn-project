(ns laser-show.routing.zone-remap-test
  "The zone stage (zones plan Z5, 07 §4.1, §8.2-§8.4, §8.9): steps, lanes,
   the styles' tables, moving, copying (one union key), hiding, gating,
   trading, the join's order, and the invariants on generated frames."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.frames :as frames]
            [laser-show.animation.pulses :as pulses]
            [laser-show.routing.zone-remap :as zr]))

(defn- pic
  "A picture of n lit points, told apart by its x."
  [n x]
  (mapv (fn [i] [(double x) (/ (double i) 100.0) 1.0 0.5 0.0]) (range n)))

(def ^:private P (pic 33 0.1))
(def ^:private X (pic 33 0.2))
(def ^:private Y (pic 10 0.3))
(def ^:private Z (pic 21 0.4))

(def ^:private abcd [:zone/a :zone/b :zone/c :zone/d])

(defn- ctx
  "A stage context at beats t (both clocks), every ref known."
  ([t] (ctx t nil))
  ([t pulse-view]
   {:beats {:cue t :global t} :pulses pulse-view :known? (constantly true) :lasers [:p1 :p2 :p3]}))

(defn- run [frames params t]
  (zr/apply-pattern frames params (ctx t)))


;; Steps

(deftest step-index-test
  (is (= [0 0 1 1 2] (mapv #(zr/step-index 0.0 1.0 %) [0.0 0.99 1.0 1.5 2.0])) "the floor")
  (is (= 3 (zr/step-index 0.0 1.0 2.9999999999)) "a Step's 2.9999999999 is on 3")
  (is (= 2 (zr/step-index 0.0 1.0 2.999)) "but 2.999 isn't")
  (is (= [0 -1 -2 -3] (mapv #(zr/step-index 0.0 -1.0 %) [0.0 0.5 1.5 2.5])) "a backwards rate: negative steps")
  (is (= [0 0 1 1] (mapv #(zr/step-index 0.0 0.5 %) [0.0 1.0 2.0 3.0])) "rate 0.5: a step every 2 beats")
  (is (= [0 1 2 3] (mapv #(zr/step-index 0.0 2.0 %) [0.0 0.5 1.0 1.5])) "rate 2: every half beat")
  (is (= 4 (zr/step-index 3.5 1.0 0.5)) "the offset is steps at t = 0")
  (is (= 7 (zr/step-index 0.0 1.0 7)) "presses: a count steps one a press"))

(deftest ring-index-loop-and-bounce-test
  (is (= [0 1 2 0 1 2] (mapv #(zr/ring-index % 3 :loop) (range 6))))
  (is (= [0 1 2 1 0 1] (mapv #(zr/ring-index % 3 :bounce) (range 6))))
  (is (= [2 1 0 2] (mapv #(zr/ring-index % 3 :loop) [-1 -2 -3 -4])) "backwards")
  (is (= [1 2 1 0] (mapv #(zr/ring-index % 3 :bounce) [-1 -2 -3 -4])) "backwards, there and back")
  (is (= [0 1 0 1] (mapv #(zr/ring-index % 2 :bounce) (range 4))) "two places bounce as they loop")
  (is (= [0 0 0] (mapv #(zr/ring-index % 1 :loop) [0 5 -3])) "one place: always it")
  (is (= 0 (zr/ring-index 4 0 :loop)) "none: 0"))

(deftest scatter-sequence-test
  (is (= [:zone/c :zone/a :zone/c :zone/b :zone/d :zone/c]
         (mapv #(nth abcd (zr/scatter-index % 4 0)) (range 6)))
      "07 §8.2: C A C B D C for four zones")
  (testing "Phase 4a's Step :scatter, at step + seed"
    (doseq [n [3 4 5 9 16] seed [0 3 7] k (range 50)]
      (is (= (pulses/step-index :scatter n (+ k seed)) (zr/scatter-index k n seed)))))
  (is (= [1 0 1 0] (mapv #(zr/scatter-index % 2 1) (range 4))) "two: (step + seed) mod 2")
  (is (= [0 0] [(zr/scatter-index 5 1 3) (zr/scatter-index -5 0 0)]) "one place, or none"))

(deftest scatter-never-repeats-test
  (doseq [n (range 2 17) seed (range 10)]
    (let [forwards (mapv #(zr/scatter-index % n seed) (range 10001))
          backwards (mapv #(zr/scatter-index (- %) n seed) (range 10001))]
      (is (not-any? (fn [[a b]] (= a b)) (partition 2 1 forwards)) (str n " places, seed " seed))
      (is (not-any? (fn [[a b]] (= a b)) (partition 2 1 backwards)) (str n " places, seed " seed ", backwards")))))

(deftest scatter-runs-backwards-test
  (doseq [n (range 2 17) k (range -500 0)]
    (is (< -1 (zr/scatter-index k n 0) n) (str n " places, step " k)))
  (is (= [1 0 1 0] (mapv #(zr/scatter-index % 2 0) [-1 -2 -3 -4])) "two places backwards: never negative"))


;; Lanes

(deftest lane-expands-every-laser-and-drops-unknown-test
  (let [known? #{:zone/a :zone/b :graphics :auto/left :auto/right :auto.laser/p1 :auto.laser/p2 :auto.laser/p3}]
    (is (= [:auto.laser/p1 :auto.laser/p2 :auto.laser/p3] (zr/lane [:lane/lasers] known? [:p1 :p2 :p3]))
        "Every laser, left to right")
    (is (= [:auto/left :auto/right] (zr/lane [:lane/lasers] known? [:p1])) "one laser: its halves")
    (is (= [] (zr/lane [:lane/lasers] known? [])) "no laser: no place")
    (is (= [:zone/a :graphics :zone/b] (zr/lane [:zone/a :front :graphics :zone/a :zone/b :graphics] known? [:p1 :p2]))
        "unknown refs (Front) and repeats go, the order stays")
    (is (= [:auto.laser/p1 :auto.laser/p2 :graphics] (zr/lane [:lane/lasers :graphics :auto.laser/p2] known? [:p1 :p2])))
    (is (= [:zone/a :zone/b] (zr/lane [:zone/a nil "x" :zone/b] nil [])) "with no known?, every ref; nothing that isn't one")))


;; The styles (07 §8.2's table, lane A-D)

(deftest styles-table-test
  (let [lit (fn [style params k] (set (zr/lit-set style params abcd k)))
        z #(set (map (fn [c] (keyword "zone" (str c))) %))]
    (is (= (mapv z ["ac" "bd" "ac" "bd" "ac" "bd"]) (mapv #(lit :alternate {} %) (range 6))) "Alternate")
    (is (= (mapv z ["a" "b" "c" "d" "a" "b"]) (mapv #(lit :chase {} %) (range 6))) "Chase")
    (is (= (mapv z ["ab" "bc" "cd" "da" "ab" "bc"]) (mapv #(lit :chase {:width 2} %) (range 6))) "Chase, width 2")
    (is (= (mapv z ["a" "b" "c" "d" "c" "b"]) (mapv #(lit :ping-pong {} %) (range 6))) "Ping-pong")
    (is (= (mapv z ["a" "b" "c" "d" "c" "b"]) (mapv #(lit :chase {:path :bounce} %) (range 6))) "Chase with :bounce")
    (is (= (mapv z ["c" "a" "c" "b" "d" "c"]) (mapv #(lit :random {} %) (range 6))) "Random, seed 0")
    (is (= (mapv #(lit :random {} (+ % 3)) (range 6)) (mapv #(lit :random {:seed 3} %) (range 6))) "a seed starts it that many steps on")
    (is (= [:zone/a :zone/b :zone/c :zone/d :zone/a :zone/b]
           (mapv #(get (zr/permutation :shift abcd %) :zone/a :zone/a) (range 6)))
        "Shift: where A's content is")
    (is (= [{} {:zone/a :zone/b :zone/b :zone/a} {} {:zone/a :zone/b :zone/b :zone/a} {} {:zone/a :zone/b :zone/b :zone/a}]
           (mapv #(zr/permutation :swap abcd %) (range 6)))
        "Swap: A ⇄ B on odd steps, as drawn on even ones")
    (testing "a bouncing window stays inside the lane"
      (is (= (mapv z ["ab" "bc" "cd" "bc" "ab" "bc"]) (mapv #(lit :chase {:width 2 :path :bounce} %) (range 6)))))
    (testing "widths past the lane light all of it; Alternate on one place lights it on even steps only; none, nothing"
      (is (= (z "abcd") (lit :chase {:width 8} 3)))
      (is (= [#{:zone/a} #{}] (mapv #(set (zr/lit-set :alternate {} [:zone/a] %)) [0 1])))
      (is (= [#{:zone/a} #{:zone/a}] (mapv #(set (zr/lit-set :random {} [:zone/a] %)) [0 1])))
      (is (= #{} (set (zr/lit-set :chase {} [] 2)))))))


;; Moving, copying, hiding

(deftest move-takes-the-picture-from-all-test
  (testing "spec §6.2 a's frames: Alternate over A, B moves the All picture"
    (is (= {:zone/a P} (run {:all P} {:style :alternate :zones [:zone/a :zone/b]} 0.0)))
    (is (= {:zone/b P} (run {:all P} {:style :alternate :zones [:zone/a :zone/b]} 1.0)))
    (is (identical? P (:zone/b (run {:all P} {:style :alternate :zones [:zone/a :zone/b]} 1.0))) "the same picture, moved"))
  (testing "a Chase over three zones: one at a time, All left empty"
    (is (= [{:zone/a P} {:zone/b P} {:zone/c P} {:zone/a P}]
           (mapv #(run {:all P} {:style :chase :zones [:zone/a :zone/b :zone/c]} %) [0.0 1.0 2.0 3.0]))))
  (testing "a key that holds All with another place keeps that place"
    (is (= {[:graphics :zone/a] P} (run {[:all :graphics] P} {:style :chase :zones [:zone/a :zone/b] :source :picture} 0.0)))))

(deftest copy-is-one-union-key-test
  (testing "07 §8.4: Alternate over A-D lights A and C on even steps: one frame under [A C]"
    (let [out (run {:zone/a X :all P} {:style :alternate :zones abcd :source :picture} 0.0)]
      (is (= {:zone/a X [:zone/a :zone/c] P} out))
      (is (identical? P (get out [:zone/a :zone/c])) "one object, never several keys")
      (is (= 1 (count (filter #(identical? P %) (vals out))))))
    (is (= {:zone/a X [:zone/b :zone/d] P} (run {:zone/a X :all P} {:style :alternate :zones abcd :source :picture} 1.0))))
  (testing "a two-wide Chase: one union key, in normal form"
    (is (= {[:zone/a :zone/d] P} (run {:all P} {:style :chase :zones abcd :width 2} 3.0)))))

(deftest move-into-an-occupied-zone-joins-test
  (let [out (run {:zone/a X :all P} {:style :chase :zones abcd} 0.0)]
    (is (= {:zone/a (frames/concatenate-frames [X P])} out) "the picture joins A's, A's first (key order)")
    (is (= (+ 33 2 33) (count (:zone/a out))) "a join adds its blanking jump only")))

(deftest empty-step-hides-the-picture-test
  (let [one {:style :alternate :zones [:zone/a] :source :picture}]
    (is (= {:zone/a P} (run {:all P} one 0.0)) "Alternate on one place: lit on even steps")
    (is (= {} (run {:all P} one 1.0)) "on odd steps nothing is: the moved picture is hidden")
    (is (= {:graphics Y} (run {:all P :graphics Y} one 1.0)) "what the pattern doesn't move stays")
    (is (= {} (run {:zone/a X} (assoc one :source :zones) 1.0)) "a gate hides the zone's own content the same way"))
  (testing "a one-place Chase sends the picture there on every step"
    (is (= [{:zone/a P} {:zone/a P}] (mapv #(run {:all P} {:style :chase :zones [:zone/a]} %) [0.0 1.0]))))
  (testing "an empty lane is the identity"
    (let [frames {:all P :graphics Y}]
      (is (identical? frames (run frames {:style :chase :zones []} 1.0)))
      (is (identical? frames (zr/apply-pattern frames {:style :chase :zones [:front]} (assoc (ctx 1.0) :known? #{:graphics})))
          "unknown places go first"))))

(deftest gate-keeps-lit-content-test
  (let [frames {:zone/a X :zone/b Y :zone/c Z}
        params {:style :chase :zones [:zone/a :zone/b :zone/c] :source :zones}]
    (is (= [{:zone/a X} {:zone/b Y} {:zone/c Z}] (mapv #(run frames params %) [0.0 1.0 2.0]))
        "the zones' own content takes turns")
    (is (= {:zone/a X :zone/c Z} (run frames (assoc params :style :alternate) 0.0)))
    (is (identical? Y (:zone/b (run frames params 1.0))) "what stays is the same picture")))

(deftest gate-narrows-a-union-key-test
  (is (= {:zone/a X} (run {[:zone/a :zone/b] X} {:style :alternate :zones [:zone/a :zone/b] :source :zones} 0.0)))
  (is (= {:zone/b X} (run {[:zone/a :zone/b] X} {:style :alternate :zones [:zone/a :zone/b] :source :zones} 1.0)))
  (is (= {[:graphics :zone/b] X} (run {[:graphics :zone/a :zone/b] X} {:style :alternate :zones [:zone/a :zone/b] :source :zones} 1.0))
      "a place outside the lane stays in the key"))

(deftest auto-source-test
  (testing "Automatic: the All picture when the cue draws into All"
    (is (= {:zone/b P :zone/a X} (run {:all P :zone/a X} {:style :alternate :zones [:zone/a :zone/b]} 1.0))))
  (testing "else what's in the zones"
    (is (= {:zone/b Y} (run {:zone/a X :zone/b Y} {:style :alternate :zones [:zone/a :zone/b]} 1.0))))
  (testing ":from names the picture: a zone's moves along"
    (is (= {:zone/c X :zone/b Y} (run {:zone/a X :zone/b Y} {:style :chase :zones [:zone/b :zone/c] :from :zone/a} 1.0)))))

(deftest from-any-moves-everything-test
  (let [out (run {:zone/a X :graphics Y :all P} {:style :chase :zones [:zone/c :zone/d] :from :any} 1.0)]
    (is (= [:zone/d] (keys out)) "every key becomes the lit places")
    (is (= (frames/concatenate-frames [X P Y]) (:zone/d out)) "joined in key order: A, then All, then Graphics")))

(deftest shift-and-swap-test
  (let [frames {:zone/a Z :zone/b Y :zone/c X}
        shift {:style :shift :zones [:zone/a :zone/b :zone/c]}]
    (is (= [{:zone/a Z :zone/b Y :zone/c X}
            {:zone/b Z :zone/c Y :zone/a X}
            {:zone/c Z :zone/a Y :zone/b X}]
           (mapv #(run frames shift %) [0.0 1.0 2.0]))
        "probe v21: 21/10/49 points turn")
    (is (identical? frames (run frames shift 3.0)) "a whole turn is the same map")
    (is (= (run frames shift 1.0) (run frames shift 4.0)) "a shift by k and by k + n are equal")
    (is (identical? frames (run frames shift 0.0)) "a shift by 0 is the same map"))
  (let [frames {:zone/a X :zone/b Y :zone/c Z}
        swap {:style :swap :zones [:zone/a :zone/b]}]
    (is (= {:zone/a Y :zone/b X :zone/c Z} (run frames swap 1.0)))
    (is (identical? frames (run frames swap 2.0)) "even steps: the same map")
    (is (= frames (run (run frames swap 1.0) swap 1.0)) "a swap is its own inverse")
    (is (= {:zone/b X} (run {:zone/a X} swap 1.0)) "with one side empty it moves the other across")))

(deftest keys-outside-the-lane-stay-test
  (let [frames {:graphics Y :auto/center Z :all P}
        out (run frames {:style :alternate :zones [:zone/a :zone/b]} 1.0)]
    (is (identical? Y (:graphics out)))
    (is (identical? Z (:auto/center out)))
    (is (= {:graphics Y :auto/center Z :zone/b P} out)))
  (is (identical? Y (:graphics (run {:graphics Y :zone/a X :zone/b Z} {:style :shift :zones [:zone/a :zone/b]} 1.0)))))

(deftest join-order-is-key-order-test
  (testing "frames that land on one key join in the order of their old keys, whatever the map's order"
    (let [params {:style :alternate :zones [:zone/a :zone/b] :source :zones}
          a (run (array-map [:zone/a :zone/b] Y :zone/a X) params 0.0)
          b (run (array-map :zone/a X [:zone/a :zone/b] Y) params 0.0)]
      (is (= {:zone/a (frames/concatenate-frames [X Y])} a) "A before [A B]")
      (is (= a b))))
  (testing "zones by index, then other refs by name, then unions"
    (is (= (frames/concatenate-frames [X Z Y P])
           (:zone/c (run {:graphics Y :zone/b Z :zone/a X [:graphics :zone/a] P} {:style :chase :zones [:zone/c] :from :any} 0.0))))))

(deftest join-never-holds-a-frame-twice-test
  (let [out (run {:zone/a X :zone/b X :zone/c Y} {:style :chase :zones [:zone/d] :from :any} 0.0)]
    (is (= {:zone/d (frames/concatenate-frames [X Y])} out) "one object under two keys joins once")
    (is (= (+ 33 2 10) (count (:zone/d out)))))
  (is (identical? X (:zone/c (run {:zone/a X :zone/b X} {:style :chase :zones [:zone/c] :from :any} 0.0)))
      "two keys of one object land as that object"))

(deftest no-pattern-is-the-same-map-test
  (let [frames {:all P :zone/a X}]
    (is (identical? frames (zr/apply-zone-stage frames [] (ctx 3.0))) "no pattern")
    (is (identical? frames (zr/apply-zone-stage frames nil (ctx 3.0))))
    (is (identical? frames (zr/apply-pattern frames {:style :shift :zones [:zone/q :zone/r]} (ctx 1.0)))
        "a pattern that touches no key")
    (let [none {}]
      (is (identical? none (zr/apply-pattern none {:style :chase :zones abcd} (ctx 1.0))) "nothing drawn"))))

(deftest off-and-pads-only-change-followers-test
  (let [frames {:all P}
        follower {:style :alternate :zones [:zone/a :zone/b]}
        own {:style :alternate :zones [:zone/a :zone/b] :follow-pads? false}
        at (fn [params live t] (zr/apply-pattern frames params (assoc (ctx t) :live-style live)))]
    (is (identical? frames (at follower :off 1.0)) "Off: a follower doesn't move")
    (is (= {:zone/b P} (at own :off 1.0)) "one that doesn't follow plays its own")
    (is (= {:zone/b P} (at follower nil 1.0)) "As set (nil): its own style")
    (is (= {:zone/b P} (at follower :chase 1.0)) "Chase over its own zones")
    (is (identical? frames (at follower :swap 1.0)) "Swap trades what's in A and B: nothing, so All stays")
    (is (= :off (zr/effective-style follower :off)))
    (is (= :alternate (zr/effective-style own :chase)))
    (is (= :chase (zr/effective-style follower :chase)))))

(deftest item-points-follow-the-keys-test
  (let [stage (zr/run-stage {:all P :zone/a X} [{:id :p1 :params {:style :chase :zones [:zone/a :zone/b] :source :picture}}] (ctx 1.0))
        item-points [[:i1 {:all 33}] [:i2 {:zone/a 33}] [:i3 {:graphics 9}]]]
    (is (= {:zone/a X :zone/b P} (:frames stage)))
    (is (= {:p1 1} (:steps stage)) "each pattern's step")
    (is (= [[:i1 {:zone/b 33}] [:i2 {:zone/a 33}] [:i3 {:graphics 9}]]
           (reduce zr/rekey-item-points item-points (:fs stage))))
    (is (nil? (zr/rekey-item-points nil (first (:fs stage)))))))


;; The invariants, on generated frames (07 §8.9: 1, 3, 5)

(def ^:private pool
  [:zone/a :zone/b :zone/c :zone/d :zone/e :all :graphics :auto/left :auto.laser/p2
   [:zone/a :zone/b] [:all :zone/c] [:graphics :zone/a :zone/d]])

(defn- generated
  "A seeded case: {:frames :params :t}, every frame its own picture (an x of
   its own), its points 1-40."
  [^java.util.Random rnd i]
  (let [pick (fn [xs] (nth xs (.nextInt rnd (count xs))))
        ks (distinct (repeatedly (inc (.nextInt rnd 5)) #(pick pool)))
        frames (into {} (map-indexed (fn [j k] [k (pic (inc (.nextInt rnd 40)) (+ i (/ (inc j) 10.0)))])) ks)
        style (pick [:alternate :chase :ping-pong :random :shift :swap])
        zones (vec (distinct (repeatedly (.nextInt rnd 6) #(pick [:zone/a :zone/b :zone/c :zone/d :zone/e :graphics :lane/lasers :auto/left]))))]
    {:frames frames
     :params {:style style :zones zones :source (pick [:auto :picture :zones]) :from (pick [:all :zone/a :graphics :any])
              :width (inc (.nextInt rnd 3)) :seed (.nextInt rnd 10) :path (pick [:loop :bounce])
              :rate (pick [1.0 0.5 2.0 -1.0 0.0]) :offset (pick [0.0 1.0 2.5 -3.0])}
     :t (* 0.25 (.nextInt rnd 64))}))

(defn- pictures-in
  "The pictures a frame holds, by x, in order: [x n] for each run of points
   with one x; blanking (dark) points are left out."
  [frame]
  (->> frame
       (remove (fn [[_ _ r g b]] (and (zero? (double r)) (zero? (double g)) (zero? (double b)))))
       (partition-by first)
       (mapv (fn [run] [(ffirst run) (count run)]))))

(deftest remap-invariants-test
  (let [rnd (java.util.Random. 20261120)]
    (dotimes [i 600]
      (let [{:keys [frames params t]} (generated rnd i)
            input (into {} (map (fn [[_ f]] [(ffirst f) (count f)])) frames)
            out (run frames params t)
            out-pictures (mapcat (comp pictures-in val) out)]
        (testing (pr-str [i (keys frames) params t])
          (testing "1: no point and no colour change; dark points are a join's jumps only"
            (is (every? (fn [[x n]] (= n (get input x))) out-pictures) "each picture whole, as it came in")
            (doseq [[_ f] out]
              (is (= (count f) (+ (reduce + (map second (pictures-in f))) (* 2 (dec (count (pictures-in f))))))
                  "every other point is a jump")))
          (testing "3: each picture once, never under two keys, never twice in a join"
            (is (apply distinct? (cons ::none (map first out-pictures)))))
          (testing "permutations hide nothing"
            (when (#{:shift :swap} (:style params))
              (is (= (set (keys input)) (set (map first out-pictures))))))
          (testing "5: the order of the keys in the map doesn't matter"
            (is (= out (run (into (array-map) (reverse (vec frames))) params t)))))))))
