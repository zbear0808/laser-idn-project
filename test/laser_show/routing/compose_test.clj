(ns laser-show.routing.compose-test
  "What one laser draws from a tick (plans/2026-11-19-zones/01 §6, §7; 05 §4,
   §8): the spec's worked examples on real refs, the rules behind them, and
   seeded properties."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.frames :as frames]
            [laser-show.routing.compose :as compose]
            [laser-show.routing.rig :as rig]))

(defn- pid [k] (keyword (str "p" k)))

(defn- circle
  "n lit points on a circle (blue)."
  [n]
  (mapv (fn [i] (let [a (/ (* 2.0 Math/PI i) n)] [(* 0.5 (Math/cos a)) (* 0.5 (Math/sin a)) 0.0 0.0 1.0]))
        (range n)))

(def ^:private A "Circle A: 33 points." (circle 33))

(def ^:private B "Wave B: 64 points."
  (mapv (fn [i] [(- (/ i 32.0) 1.0) (* 0.5 (Math/sin (* i 0.3))) 1.0 0.0 0.0]) (range 64)))

(defn- rig-state
  "n lasers :p1 … :pn at 10.0.0.1 … (so in that order), on, with opts:
   :middle, :groups {k [group …]}, :limits {k limit}, :excluded #{k}."
  [n & {:keys [middle groups limits excluded]}]
  {:projectors (into {} (for [k (range 1 (inc n))]
                          [(pid k) (cond-> {:name (str "Laser " k) :host (str "10.0.0." k) :service-id 1
                                            :enabled? true :zone-groups (get groups k [])}
                                     (get limits k) (assoc :safety {:limit (get limits k)})
                                     (contains? excluded k) (assoc :exclude-from-all? true))]))
   :rig (cond-> {:order []} middle (assoc :middle middle))
   :zone-groups {:graphics {:name "Graphics"} :crowd {:name "Crowd"}}})

(defn- routing [state] (rig/routing (rig/routing-inputs state nil)))

(defn- cue
  ([zone-frames] (cue [0 0] zone-frames))
  ([cell zone-frames] {:cell cell :zone-frames zone-frames}))

(defn- draws
  "What laser p draws of cues: [frame regions], or nil."
  [cues r p]
  (when-let [{:keys [frame regions]} (compose/projector-frame cues r p)]
    [frame regions]))

(def ^:private L1 "One laser's left half, the whole range at 75%." [-0.25 0.0 0.75])
(def ^:private R1 [0.25 0.0 0.75])

(defn- fit [frame region] (compose/fit-region frame region))

(defn- join [& fs] (frames/concatenate-frames (vec fs)))


;; The spec's examples (01 §6.2) in their Z3 form (05 §8)

(defn- routing-on
  "The routing of state under zone map map-id (zones plan Z4)."
  [state map-id]
  (rig/routing (rig/routing-inputs state map-id)))

(deftest example-a-a-picture-moves-between-halves-test
  (let [r (routing (rig-state 1))]
    (is (= [(fit A L1) 1] (draws [(cue {:auto/left A})] r :p1)) "A under Left: fitted in the left half")
    (is (= [(fit A R1) 1] (draws [(cue {:auto/right A})] r :p1)) "the next tick under Right: in the right half")
    (is (= 33 (count (fit A L1))))))

(deftest example-b-one-laser-draws-two-fitted-halves-test
  (let [r (routing (rig-state 1))
        [frame regions] (draws [(cue {:auto/left A :auto/right B})] r :p1)]
    (is (= (join (fit A L1) (fit B R1)) frame) "the circle in L, a jump, the wave in R")
    (is (= 99 (count frame)))
    (is (= 2 regions))))

(deftest example-c-both-halves-of-one-laser-is-the-whole-laser-test
  (let [r (routing (rig-state 1))]
    (is (= [A 0] (draws [(cue {[:auto/left :auto/right] A})] r :p1)) "one key on both halves: once, whole")))

(deftest example-d-two-keys-two-pictures-overlay-test
  (let [r (routing (rig-state 1 :groups {1 [:graphics]}))
        [frame regions] (draws [(cue {:graphics B :all A})] r :p1)]
    (is (= (join A B) frame) "both, whole, All before Graphics")
    (is (= [99 0] [(count frame) regions]))))

(deftest example-e-one-key-through-two-groups-is-drawn-once-test
  (let [r (routing (rig-state 1 :groups {1 [:graphics]}))]
    (is (= [A 0] (draws [(cue {[:all :graphics] A})] r :p1)) "33 points, where two keys holding it sent 68 (probe v22)")))

(deftest example-f-split-middle-laser-test
  (let [r (routing (rig-state 3))
        cues [(cue {:auto/left A :auto/right B})]
        L2 (get-in r [:outputs :p2 :regions :L])
        R2 (get-in r [:outputs :p2 :regions :R])]
    (is (= [A 0] (draws cues r :p1)))
    (is (= [(join (fit A L2) (fit B R2)) 2] (draws cues r :p2)) "the middle laser: A in its left half, B in its right")
    (is (= 99 (count (first (draws cues r :p2)))))
    (is (= [B 0] (draws cues r :p3)))))

(deftest example-g-both-middle-splits-when-both-sides-differ-test
  (let [r (routing (rig-state 3 :middle :both))
        cues [(cue {:auto/left A :auto/right B})]
        {:keys [L R]} (get-in r [:outputs :p2 :regions])]
    (is (= [A 0] (draws cues r :p1)))
    (is (= [(join (fit A L) (fit B R)) 2] (draws cues r :p2)) "two different pictures, one each side: it splits (D-27)")
    (is (= [B 0] (draws cues r :p3)))))

(deftest example-h-both-middle-with-one-side-stays-whole-test
  (let [r (routing (rig-state 3 :middle :both))
        cues [(cue {:auto/left A})]]
    (is (= [A 0] (draws cues r :p1)))
    (is (= [A 0] (draws cues r :p2)) "only Left is lit: whole")
    (is (nil? (draws cues r :p3)))))


;; The rules

(deftest both-middle-splits-across-two-cues-test
  (testing "one cue on Left, another on Right: the middle laser splits (S-1, Q-Z3-1 A)"
    (let [r (routing (rig-state 3 :middle :both))
          {:keys [L R]} (get-in r [:outputs :p2 :regions])
          cues [(cue [0 0] {:auto/left A}) (cue [1 0] {:auto/right B})]]
      (is (true? (compose/split-sides? cues r :p2)))
      (is (= [(join (fit A L) (fit B R)) 2] (draws cues r :p2)))
      (is (false? (compose/split-sides? [(first cues)] r :p2)) "one side alone doesn't"))))

(deftest example-i-unison-draws-every-zone-everywhere-test
  (testing "zones plan Z4, spec 01 §6.2 i: under Unison A and B are All, so every laser draws both, whole, as an old show on All"
    (doseq [n [1 3]]
      (let [r (routing-on (rig-state n) :map/unison)]
        (doseq [k (range 1 (inc n))]
          (is (= [(join A B) 0] (draws [(cue {:zone/a A :zone/b B})] r (pid k))) (str n " lasers, laser " k)))))))

(deftest example-j-zones-wrap-over-two-lanes-test
  (testing "zones plan Z4, spec 01 §6.2 j: two lasers, Left / Right, four zones lit"
    (let [C (circle 12) D (circle 9)
          r (routing-on (rig-state 2) :map/left-right)
          zone-frames {:zone/a A :zone/b B :zone/c C :zone/d D}]
      (is (= [(join A C) 0] (draws [(cue zone-frames)] r :p1)) "A and C on Left: overlaid, in zone order")
      (is (= [(join B D) 0] (draws [(cue zone-frames)] r :p2)) "B and D on Right"))))

(deftest same-picture-under-two-keys-is-drawn-once-test
  (let [r (routing (rig-state 1))]
    (is (= [A 0] (draws [(cue {:auto/left A :auto/right A})] r :p1))
        "the same object in both halves: once, on the whole laser")
    (is (= [{:keys [:auto/left :auto/right] :frame A :region nil :side nil}]
           (compose/merge-pictures (compose/contributions {:auto/right A :auto/left A} r :p1))))
    (testing "an equal frame that isn't the same object is another picture"
      (let [copy (into [] A)]
        (is (= [(join (fit A L1) (fit copy R1)) 2] (draws [(cue {:auto/left A :auto/right copy})] r :p1)))))
    (testing "the same picture on both sides of a :both middle doesn't split it"
      (let [r3 (routing (rig-state 3 :middle :both))]
        (is (= [A 0] (draws [(cue {:auto/left A :auto/right A})] r3 :p2)))))))

(deftest side-is-kept-only-when-every-entry-agrees-test
  (let [r (routing (rig-state 3 :middle :both))]
    (is (= [{:keys [:auto/left] :frame A :region nil :side :left}] (compose/contributions {:auto/left A} r :p2)))
    (is (= [{:keys [[:all :auto/left]] :frame A :region nil :side nil}]
           (compose/contributions {[:all :auto/left] A} r :p2))
        "All reaches it with no side: the key has none")
    (is (= [{:keys [[:auto/left :auto/right]] :frame A :region nil :side nil}]
           (compose/contributions {[:auto/left :auto/right] A} r :p2)))
    (testing "so it takes no part in the split"
      (is (= [(join B A) 0] (draws [(cue {[:all :auto/left] A :auto/right B})] r :p2))
          "Right's B alone is tagged: both whole, the single key first"))))

(deftest buckets-go-left-whole-right-in-key-order-test
  (let [r (routing (rig-state 1))
        [a b c d e f] (map (fn [k] (mapv (fn [[x y]] [x y 0.1 (* 0.1 k) 1.0]) (circle 5))) (range 6))
        entries [[:auto/right a] [:auto/even b] [:all c] [:auto/center d] [:auto/left e] [:auto/odd f]]
        expected (join (fit (join e f) L1) (join c d) (fit (join b a) R1))]
    (is (= [expected 2] (draws [(cue (into {} entries))] r :p1))
        "Left's then Odd's in the left half, All's then Center's whole, Even's then Right's in the right half")
    (is (= [expected 2] (draws [(cue (apply array-map (mapcat identity (reverse entries))))] r :p1))
        "whatever the order of the keys in the cue's map")))

(deftest key-order-test
  (let [ks [[:auto/left :zone/a] :graphics [:all :graphics :zone/c] :zone/aa :auto/left :zone/b
            [:all :graphics] :all :zone/a :auto.laser/p1 [:zone/a :zone/b]]]
    (is (= [:zone/a :zone/b :zone/aa :all :graphics :auto/left :auto.laser/p1
            [:zone/a :zone/b] [:all :graphics] [:all :graphics :zone/c] [:auto/left :zone/a]]
           (sort-by compose/key-order ks))
        "zones by index, then single refs by namespace and name, then vector keys ref by ref (a key that starts another first)")
    (is (= (sort-by compose/key-order ks) (sort-by compose/key-order (reverse ks))))))

(deftest fit-region-test
  (is (= [[0.5 0.75 0.1 0.2 0.3] [-1.0 0.0 0.0 0.0 0.0] [-0.25 0.0 1.0 1.0 1.0]]
         (compose/fit-region [[1.0 1.0 0.1 0.2 0.3] [-1.0 0.0 0.0 0.0 0.0] [0.0 0.0 1.0 1.0 1.0]] [-0.25 0.0 0.75]))
      "(x, y) to (cx + s·x, cy + s·y); colours unchanged")
  (is (= [] (compose/fit-region [] [0.0 0.0 1.0]))))

(deftest degenerate-safe-area-draws-no-halves-test
  (testing "one laser with a safe area 0.04 wide: no halves (S-9), its whole content still drawn"
    (let [r (routing (rig-state 1 :limits {1 [0.0 -1.0 0.04 1.0]}))]
      (is (nil? (get-in r [:outputs :p1 :regions])))
      (is (= {:frame A :regions 0 :no-room? true}
             (compose/projector-frame [(cue {:auto/left B :all A :auto/right (circle 7)})] r :p1)))
      (is (= {:frame [] :regions 0 :no-room? true} (compose/projector-frame [(cue {:auto/left A})] r :p1))
          "only halves to draw: nothing, and it says why")
      (is (= {:frame A :regions 0} (compose/projector-frame [(cue {:all A})] r :p1)) "whole content says nothing")))
  (testing "a :both middle with no halves never splits: both pictures whole"
    (let [r (routing (rig-state 3 :middle :both :limits {2 [0.0 -1.0 0.04 1.0]}))]
      (is (false? (compose/split-sides? [(cue {:auto/left A :auto/right B})] r :p2)))
      (is (= [(join A B) 0] (draws [(cue {:auto/left A :auto/right B})] r :p2))))))

(deftest cues-join-in-their-order-test
  (let [r (routing (rig-state 1))]
    (is (= [(join A B) 0] (draws [(cue [0 0] {:all A}) (cue [1 0] {:all B})] r :p1)))
    (is (= [(join B A) 0] (draws [(cue [1 0] {:all B}) (cue [0 0] {:all A})] r :p1)))
    (is (= [(join (fit A L1) B) 1] (draws [(cue [0 0] {:auto/left A}) (cue [1 0] {:all B})] r :p1))
        "each cue built, then joined")))

(deftest nothing-reaches-the-laser-is-nil-test
  (let [state (rig-state 2 :groups {1 [:graphics]})
        r (routing state)]
    (is (nil? (compose/projector-frame [(cue {:auto/left A})] r :p2)) "Left is the other laser")
    (is (nil? (compose/projector-frame [(cue {:graphics A})] r :p2)))
    (is (nil? (compose/projector-frame [(cue {:nowhere A})] r :p1)) "a ref the rig doesn't know")
    (is (nil? (compose/projector-frame [] r :p1)) "no cue")
    (is (nil? (compose/projector-frame [(cue {})] r :p1)) "a cue that rendered nothing")
    (is (nil? (compose/projector-frame [(cue {:all A})] r :p9)) "a laser the routing doesn't have")
    (is (nil? (compose/projector-frame [(cue {:all A})] (routing (assoc-in state [:projectors :p1 :enabled?] false)) :p1))
        "one that is off")
    (is (nil? (compose/projector-frame [(cue {:all A})] nil :p1)) "no routing")
    (is (zero? (compose/cue-points {:auto/left A} r :p2)))))

(deftest plan-points-equal-the-built-frame-test
  (let [r1 (routing (rig-state 1))
        r3 (routing (rig-state 3 :middle :both))]
    (doseq [[r p zone-frames split?] [[r1 :p1 {:auto/left A :auto/right B :all A} false]
                                      [r1 :p1 {:auto/left A :auto/right A} false]
                                      [r3 :p2 {:auto/left A :auto/right B} true]
                                      [r3 :p2 {:auto/left A :auto/right B} false]
                                      [r3 :p1 {:auto/odd B :all A :auto/left B} false]]]
      (let [plan (compose/cue-plan zone-frames r p split?)
            built (compose/build plan (get-in r [:outputs p :regions]))]
        (is (= (count built) (compose/plan-points plan)))
        (is (= (count built) (compose/cue-points zone-frames r p)) "cue-points: the same count, split or not")))
    (is (= 99 (compose/plan-points {:L [A] :whole [] :R [B]})) "every frame's points and 2 per join")
    (is (zero? (compose/plan-points nil)))))


;; Invariant 7: an old show draws the same points

(defn- old-frame
  "What an engine drew before Z3: the frames of the refs that reach p, in
   their join order (routing.rig/refs-for), each cue's joined, then the cues."
  [state cues p]
  (frames/concatenate-frames
   (into [] (keep (fn [{:keys [zone-frames]}]
                    (frames/concatenate-frames (into [] (keep #(get zone-frames %)) (rig/refs-for state p)))))
         cues)))

(deftest old-show-draws-the-same-points-test
  (let [state (rig-state 3 :groups {1 [:graphics] 2 [:graphics :crowd] 3 [:crowd :graphics]} :excluded #{3})
        r (routing state)]
    (testing "each cue reaching a laser through one key: the very same frame (S-4)"
      (let [cues [(cue [0 0] {:all A}) (cue [1 0] {:graphics B}) (cue [2 0] {:crowd A})]]
        (doseq [p [:p1 :p2 :p3]]
          (is (= (old-frame state cues p) (:frame (compose/projector-frame cues r p))) (name p)))))
    (testing "a cue reaching one through two keys: the same points, in the spec's order"
      (let [cues [(cue {:graphics A :crowd B})]
            old (old-frame state cues :p2)
            new (:frame (compose/projector-frame cues r :p2))]
        (is (= (join A B) old) "its list put Graphics first")
        (is (= (join B A) new) "Crowd before Graphics, by name")
        (is (= (frequencies (remove #(= [0.0 0.0 0.0] (subvec % 2 5)) old))
               (frequencies (remove #(= [0.0 0.0 0.0] (subvec % 2 5)) new))))
        (is (= (count old) (count new)))))))


;; Seeded properties

(defn- pick [^java.util.Random rng xs] (nth xs (.nextInt rng (count xs))))

(defn- random-frame
  "A frame of 1-20 points, some dark, colours from a small palette so they
   repeat."
  [^java.util.Random rng]
  (vec (repeatedly (inc (.nextInt rng 20))
                   #(let [lit? (< (.nextDouble rng) 0.8)
                          c (if lit? (pick rng [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0] [1.0 1.0 1.0]]) [0.0 0.0 0.0])]
                      (into [(- (* 2.0 (.nextDouble rng)) 1.0) (- (* 2.0 (.nextDouble rng)) 1.0)] c)))))

(defn- random-state [^java.util.Random rng]
  (let [n (inc (.nextInt rng 6))]
    (rig-state n
               :middle (pick rng [:split :both :left :right :none])
               :groups (into {} (for [k (range 1 (inc n))]
                                  [k (vec (distinct (repeatedly (.nextInt rng 3) #(pick rng [:graphics :crowd]))))]))
               :limits (into {} (for [k (range 1 (inc n)) :when (< (.nextDouble rng) 0.15)]
                                  [k (pick rng [[0.0 -1.0 0.04 1.0] [-0.8 -0.5 0.8 0.5]])]))
               :excluded (set (for [k (range 1 (inc n)) :when (< (.nextDouble rng) 0.15)] k)))))

(defn- random-cue [^java.util.Random rng refs pool cell]
  (let [single #(pick rng refs)
        k (fn [] (if (< (.nextDouble rng) 0.3)
                   (rig/normalize-target (repeatedly (+ 2 (.nextInt rng 2)) single))
                   (single)))]
    (cue cell (into {} (repeatedly (inc (.nextInt rng 5)) #(vector (k) (pick rng pool)))))))

(defn- reordered
  "The cue with its keys in another order in the map."
  [^java.util.Random rng c]
  (let [entries (java.util.ArrayList. ^java.util.Collection (vec (:zone-frames c)))]
    (java.util.Collections/shuffle entries rng)
    (assoc c :zone-frames (apply array-map (mapcat identity entries)))))

(defn- dark? [pt] (= [0.0 0.0 0.0] (subvec pt 2 5)))

(deftest composition-invariants-test
  (let [rng (java.util.Random. 20261120)]
    (dotimes [_ 2000]
      (let [state (random-state rng)
            r (routing state)
            refs (into [:all :graphics :crowd :nowhere] (concat rig/auto-refs (map rig/laser-ref (keys (:projectors state)))))
            pool (vec (repeatedly 4 #(random-frame rng)))
            cues (mapv #(random-cue rng refs pool [% 0]) (range (inc (.nextInt rng 3))))]
        (doseq [p (keys (:outputs r))]
          (let [result (compose/projector-frame cues r p)
                regions (get-in r [:outputs p :regions])
                pictures (mapv #(compose/merge-pictures (compose/contributions (:zone-frames %) r p)) cues)
                drawn (into [] (comp cat (filter #(or regions (nil? (:region %))))) pictures)
                reaches? (fn [k] (some #(= p (first %)) (mapcat #(rig/resolve-ref r %) (rig/key-refs k))))]
            (doseq [[c ps] (map vector cues pictures)]
              (is (= (set (filter reaches? (keys (:zone-frames c)))) (set (mapcat :keys ps))) "every key that reaches it")
              (is (apply distinct? nil (mapcat :keys ps)) "each key once")
              (is (apply distinct? nil (map #(System/identityHashCode (:frame %)) ps)) "each picture once in a cue"))
            (if (every? empty? pictures)
              (is (nil? result))
              (let [frame (:frame result)]
                (is (<= 0 (:regions result) 2) "at most two regions")
                (is (= (if (seq drawn) (+ (reduce + (map (comp count :frame) drawn)) (* 2 (dec (count drawn)))) 0)
                       (count frame))
                    "the distinct pictures' points, and 2 per join")
                (is (= (merge-with + (frequencies (map #(subvec % 2 5) (mapcat :frame drawn)))
                                   (when (< 1 (count drawn)) {[0.0 0.0 0.0] (* 2 (dec (count drawn)))}))
                       (frequencies (map #(subvec % 2 5) frame)))
                    "no colour changed")
                (is (= result (compose/projector-frame (mapv #(reordered rng %) cues) r p))
                    "the same frame whatever the order of each cue's keys")))))))))


;; Cue warnings (spec §8): a playing cue too heavy for a laser

(deftest cue-warnings-name-the-worst-laser-test
  (let [heavy (circle 2000)
        r (routing (-> (rig-state 3)
                       (assoc-in [:projectors :p2 :fit] {:min-fps 10})
                       (assoc-in [:projectors :p3 :scan-rate] 30000)))]
    (is (= {[0 0] {:projector :p1 :points 2000 :fps 12.5 :min-fps 20 :scan-rate 25000}}
           (compose/cue-warnings [(cue [0 0] {:all heavy}) (cue [1 0] {:all (circle 100)})] r))
        "12.5 fps on p1 and 15 on p3, both under 20, and p2's minimum is 10; the light cue: nothing")
    (is (= {:projector :p3 :points 2000 :fps 15.0 :min-fps 20 :scan-rate 30000}
           (get (compose/cue-warnings [(cue [2 0] {:auto/right heavy})] r) [2 0]))
        "Right: p2's half (fine at its 10) and p3 (15 fps)")
    (testing "ties go to the lower index, an excluded laser's last"
      (let [r (routing (rig-state 3 :excluded #{1}))]
        (is (= :p2 (get-in (compose/cue-warnings [(cue {:all heavy :auto.laser/p1 heavy})] r) [[0 0] :projector])))
        (is (= :p1 (get-in (compose/cue-warnings [(cue {:auto.laser/p1 heavy})] r) [[0 0] :projector])))))
    (testing "a cue nothing reaches, or that rendered nothing, has none"
      (is (= {} (compose/cue-warnings [(cue {:nowhere heavy}) (cue [1 0] {})] r)))
      (is (= {} (compose/cue-warnings [(cue {:all heavy})] nil))))
    (testing "what the laser draws counts: each picture once, both halves of a split laser"
      (let [r1 (routing (rig-state 1))]
        (let [c (circle 1300)]
          (is (= 1300 (get-in (compose/cue-warnings [(cue {:auto/left c :auto/right c})] r1) [[0 0] :points]))
              "one object in both halves: drawn once"))
        (is (= 1502 (get-in (compose/cue-warnings [(cue {:auto/left (circle 750) :auto/right (circle 750)})] r1)
                            [[0 0] :points])))))))

(deftest cue-warnings-name-the-heaviest-item-test
  (let [r (routing (rig-state 2))
        frames (with-meta {:all (circle 1500) :auto/left (circle 600) :auto/right (circle 900)}
                 {:item-points [[:ring {:all 1500}] [:left-ring {:auto/left 600}] [:right-ring {:auto/right 900}]]})]
    (is (= {:id :ring :points 1500} (get-in (compose/cue-warnings [(cue frames)] r) [[0 0] :item])))
    (testing "only the items that reach that laser count"
      (let [frames (with-meta {:auto/left (circle 1300) :auto/right (circle 1300)}
                     {:item-points [[:a {:auto/left 1300}] [:b {:auto/right 1300}]]})
            warnings (compose/cue-warnings [(cue frames)] r)]
        (is (= :p1 (get-in warnings [[0 0] :projector])) "both at 19 fps; p1 has the lower index")
        (is (= {:id :a :points 1300} (get-in warnings [[0 0] :item])) "p1 draws only Left")))
    (testing "an item under two keys counts on both"
      (let [frames (with-meta {:all (circle 700) :auto/left (circle 700)}
                     {:item-points [[:a {:all 700 :auto/left 700}] [:b {:all 100}]]})]
        (is (= {:id :a :points 1400} (get-in (compose/cue-warnings [(cue frames)] r) [[0 0] :item])))))
    (testing "without the counts (the cue's editor is closed), no item"
      (is (not (contains? (get (compose/cue-warnings [(cue (with-meta frames nil))] r) [0 0]) :item))))))

(deftest cue-warnings-count-a-decimated-cue-at-its-budget-test
  (testing "Q-D-2 B: a laser whose calibration chain has Decimate counts a cue at no more than Decimate's budget"
    (let [decimate (fn [& {:as params}] {:id (random-uuid) :effect-id :decimate :enabled? true
                                         :params (merge {:kpps 25 :fps 30} params)})
          state (rig-state 2)
          on (fn [chains] (routing (reduce-kv #(assoc-in %1 [:chains :projector-effects %2 :items] %3) state chains)))
          heavy (cue {:all (circle 2000)})]
      (is (= {[0 0] {:projector :p1 :points 2000 :fps 12.5 :min-fps 20 :scan-rate 25000}}
             (compose/cue-warnings [heavy] (routing state)))
          "without Decimate: 12.5 fps on both, p1 first")
      (is (= {[0 0] {:projector :p2 :points 2000 :fps 12.5 :min-fps 20 :scan-rate 25000}}
             (compose/cue-warnings [heavy] (on {:p1 [(decimate)]})))
          "p1 decimates it to its target, 833 points at 30 fps: only p2 warns")
      (is (= {} (compose/cue-warnings [heavy] (on {:p1 [(decimate)] :p2 [(decimate)]}))) "both do: no ⚠")
      (is (= {[0 0] {:projector :p1 :points 1666 :fps (/ 25000.0 1666) :min-fps 20 :scan-rate 25000}}
             (compose/cue-warnings [heavy] (on {:p1 [(decimate :fps 15)] :p2 [(decimate)]})))
          "a target under the laser's minimum: counted at its 1,666 points, still too heavy")
      (is (= :p1 (get-in (compose/cue-warnings [heavy] (on {:p1 [(assoc (decimate) :enabled? false)]})) [[0 0] :projector]))
          "a disabled Decimate counts for nothing")
      (testing "a cue under the budget counts as it is"
        (let [state (assoc-in state [:projectors :p1 :fit] {:min-fps 30})
              light (cue {:auto.laser/p1 (circle 900)})]
          (is (= {:points 900 :fps (/ 25000.0 900)} (select-keys (get (compose/cue-warnings [light] (routing state)) [0 0]) [:points :fps]))
              "900 points: 27.8 fps, under its 30")
          (is (= {} (compose/cue-warnings [light] (routing (assoc-in state [:chains :projector-effects :p1 :items] [(decimate)]))))
              "Decimate takes it to 833, its 30 fps"))))))
