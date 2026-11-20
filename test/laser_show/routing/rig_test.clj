(ns laser-show.routing.rig-test
  "The rig as data (plans/2026-11-19-zones/04 §4, spec 01 §3): the order of
   the projectors, the automatic groups folded from it, the refs that reach
   each projector, stable identity and the scan's matching."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [laser-show.routing.rig :as rig]))

(defn- pid [k] (keyword (str "p" k)))

(defn- w [k] [(pid k) nil nil])

(defn- rig-state
  "n projectors :p1 … :pn at 10.0.0.1 … 10.0.0.n (so in that order), enabled,
   with opts: :middle, :disabled and :excluded (sets of numbers)."
  [n & {:keys [middle disabled excluded]}]
  {:projectors (into {} (for [k (range 1 (inc n))]
                          [(pid k) (cond-> {:name (str "Laser " k) :host (str "10.0.0." k) :service-id 1
                                            :enabled? (not (contains? disabled k))
                                            :zone-groups []}
                                     (contains? excluded k) (assoc :exclude-from-all? true))]))
   :rig (cond-> {:order []} middle (assoc :middle middle))})

(def ^:private five [:auto/left :auto/right :auto/center :auto/odd :auto/even])

(defn- folded [state]
  (select-keys (rig/auto-groups (rig/rig-inputs state)) five))

(def ^:private table
  "Spec 01 §3's table, :split middle."
  {0 {:auto/left [] :auto/right [] :auto/center [] :auto/odd [] :auto/even []}
   1 {:auto/left [[:p1 :L nil]] :auto/right [[:p1 :R nil]] :auto/center [(w 1)]
      :auto/odd [[:p1 :L nil]] :auto/even [[:p1 :R nil]]}
   2 {:auto/left [(w 1)] :auto/right [(w 2)] :auto/center [(w 1) (w 2)] :auto/odd [(w 1)] :auto/even [(w 2)]}
   3 {:auto/left [(w 1) [:p2 :L nil]] :auto/right [[:p2 :R nil] (w 3)] :auto/center [(w 2)]
      :auto/odd [(w 1) (w 3)] :auto/even [(w 2)]}
   4 {:auto/left [(w 1) (w 2)] :auto/right [(w 3) (w 4)] :auto/center [(w 2) (w 3)]
      :auto/odd [(w 1) (w 3)] :auto/even [(w 2) (w 4)]}
   5 {:auto/left [(w 1) (w 2) [:p3 :L nil]] :auto/right [[:p3 :R nil] (w 4) (w 5)] :auto/center [(w 3)]
      :auto/odd [(w 1) (w 3) (w 5)] :auto/even [(w 2) (w 4)]}
   6 {:auto/left [(w 1) (w 2) (w 3)] :auto/right [(w 4) (w 5) (w 6)] :auto/center [(w 3) (w 4)]
      :auto/odd [(w 1) (w 3) (w 5)] :auto/even [(w 2) (w 4) (w 6)]}})

(deftest auto-groups-table-test
  (doseq [[n expected] table]
    (testing (str "n = " n)
      (is (= expected (folded (rig-state n))))
      (testing "and one group per laser"
        (is (= (into {} (for [k (range 1 (inc n))] [(keyword "auto.laser" (str "p" k)) [(w k)]]))
               (apply dissoc (rig/auto-groups (rig/rig-inputs (rig-state n))) five)))))))

(deftest middle-policy-test
  (testing "n = 3"
    (doseq [[middle left right] [[:split [(w 1) [:p2 :L nil]] [[:p2 :R nil] (w 3)]]
                                 [:both [(w 1) [:p2 nil :left]] [[:p2 nil :right] (w 3)]]
                                 [:left [(w 1) (w 2)] [(w 3)]]
                                 [:right [(w 1)] [(w 2) (w 3)]]
                                 [:none [(w 1)] [(w 3)]]]]
      (testing (name middle)
        (is (= {:auto/left left :auto/right right :auto/center [(w 2)]
                :auto/odd [(w 1) (w 3)] :auto/even [(w 2)]}
               (folded (rig-state 3 :middle middle)))))))
  (testing "n = 5"
    (doseq [[middle left right] [[:split [(w 1) (w 2) [:p3 :L nil]] [[:p3 :R nil] (w 4) (w 5)]]
                                 [:both [(w 1) (w 2) [:p3 nil :left]] [[:p3 nil :right] (w 4) (w 5)]]
                                 [:left [(w 1) (w 2) (w 3)] [(w 4) (w 5)]]
                                 [:right [(w 1) (w 2)] [(w 3) (w 4) (w 5)]]
                                 [:none [(w 1) (w 2)] [(w 4) (w 5)]]]]
      (testing (name middle)
        (is (= {:auto/left left :auto/right right :auto/center [(w 3)]
                :auto/odd [(w 1) (w 3) (w 5)] :auto/even [(w 2) (w 4)]}
               (folded (rig-state 5 :middle middle)))))))
  (testing "the policy doesn't matter with an even count"
    (doseq [middle [:split :both :left :right :none]]
      (is (= (table 4) (folded (rig-state 4 :middle middle))))))
  (testing "anything else is the default, :split, and the split width is clamped"
    (is (= :split (:middle (rig/rig-inputs (rig-state 3 :middle :sideways)))))
    (is (= (table 3) (folded (rig-state 3 :middle :sideways))))
    (is (= 0.75 (:split-width (rig/rig-inputs {}))))
    (is (= 0.5 (:split-width (rig/rig-inputs {:rig {:split-width 0.1}}))))
    (is (= 1.0 (:split-width (rig/rig-inputs {:rig {:split-width 3}}))))
    (is (= 0.75 (:split-width (rig/rig-inputs {:rig {:split-width "wide"}}))))))

(deftest one-laser-splits-left-right-and-odd-even-test
  (let [state (rig-state 1)]
    (is (= (table 1) (folded state)) "Left and Right, and Odd and Even, are its halves (D-13)")
    (is (= [:all :auto/left :auto/right :auto/center :auto/odd :auto/even :auto.laser/p1]
           (rig/refs-for state :p1))
        "so it draws both halves' content (whole until Z3)")
    (is (= (table 1) (folded (rig-state 1 :middle :none))) "whatever the middle policy")))

(deftest disabled-projectors-are-skipped-test
  (let [state (rig-state 3 :disabled #{2})]
    (is (= {:auto/left [(w 1)] :auto/right [(w 3)] :auto/center [(w 1) (w 3)] :auto/odd [(w 1)] :auto/even [(w 3)]}
           (folded state))
        "the two that are on fold as two lasers")
    (is (not (contains? (rig/auto-groups (rig/rig-inputs state)) :auto.laser/p2)) "no group for a laser that is off")
    (is (= [:p1 :p2 :p3] (:order (rig/rig-inputs state))) "it keeps its place")
    (testing "a missing :enabled? counts as on, as the engines say"
      (is (rig/enabled? {}))
      (is (not (rig/enabled? {:enabled? false})))
      (is (= (table 3) (folded (update-in (rig-state 3) [:projectors :p2] dissoc :enabled?)))))))

(deftest offline-laser-keeps-its-place-test
  (let [state (rig-state 3)
        offline (assoc-in state [:backend :streaming :status :projectors :p2] {:state :error :error "unreachable"})]
    (is (= (folded state) (folded offline)) "the streaming status is never read (D-21)")
    (is (= (table 3) (folded offline)))))

(deftest excluded-laser-leaves-all-and-the-folded-groups-test
  (let [state (rig-state 3 :excluded #{2})
        groups (rig/auto-groups (rig/rig-inputs state))]
    (is (= {:auto/left [(w 1)] :auto/right [(w 3)] :auto/center [(w 1) (w 3)] :auto/odd [(w 1)] :auto/even [(w 3)]}
           (select-keys groups five))
        "the others fold without it (D-33)")
    (is (= [(w 2)] (:auto.laser/p2 groups)) "it keeps its own group")
    (is (= [(w 1) (w 3)] (:all (rig/ref-entries (rig/rig-inputs state)))))
    (is (= [:auto.laser/p2] (rig/refs-for state :p2)))
    (is (= [:graphics :auto.laser/p2]
           (rig/refs-for (assoc-in state [:projectors :p2 :zone-groups] [:graphics]) :p2))
        "and your groups")))

(deftest ref-entries-test
  (let [state (-> (rig-state 3 :disabled #{3})
                  (assoc-in [:projectors :p1 :zone-groups] [:graphics])
                  (assoc-in [:projectors :p3 :zone-groups] [:graphics])
                  (assoc :zone-groups {:graphics {:name "Graphics"} :crowd {:name "Crowd Scanning"}}))
        entries (rig/ref-entries (rig/rig-inputs state))]
    (is (= [(w 1) (w 2)] (:all entries)) "All: the lasers that are on, whole")
    (is (= [(w 1)] (:graphics entries)) "a group: its members that are on, whole, in rig order")
    (is (= [] (:crowd entries)) "a group nobody is in")
    (is (= [(w 1)] (:auto/left entries)))))

(deftest effective-order-test
  (let [projectors {:a {:host "10.0.0.10"} :b {:host "10.0.0.9" :name "Beta"} :c {:host "10.0.0.9" :name "alpha"}
                    :d {:host "laser-b.local"} :e {:host "laser-a.local"} :f {:host "10.0.0.2"}}]
    (is (= [:f :c :b :a :e :d] (rig/effective-order [] projectors))
        "by IP as numbers (.9 before .10), then name; host names after the IPs")
    (is (= [:a :d :f :c :b :e] (rig/effective-order [:a :gone :d :a] projectors))
        "the stored order first, without ids that are gone, each once; then the others as above")
    (is (= [] (rig/effective-order [:a] {})))
    (testing "the same IP and name: by output, then id"
      (is (= [:x2 :x1 :x3] (rig/effective-order nil {:x1 {:host "10.0.0.5" :service-id 2}
                                                      :x2 {:host "10.0.0.5" :service-id 1}
                                                      :x3 {:host "10.0.0.5" :service-id 2}}))))))

(def ^:private old-show
  "04 §8's rig, as the migration leaves it."
  {:projectors {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :service-id 1 :enabled? true
                                       :zone-groups [:graphics] :exclude-from-all? false}
                :projector-10-0-0-12-1 {:name "Aud" :host "10.0.0.12" :service-id 1 :enabled? true
                                        :zone-groups [:crowd] :tags #{:crowd-scanning} :exclude-from-all? true}
                :projector-10-0-0-7-1 {:name "Laser R" :host "10.0.0.7" :service-id 1 :enabled? true
                                       :zone-groups [:right] :exclude-from-all? false}}
   :rig {:order [] :middle :split :split-width 0.75}})

(deftest refs-for-order-test
  (is (= [:projector-10-0-0-5-1 :projector-10-0-0-7-1 :projector-10-0-0-12-1]
         (:order (rig/rig-inputs old-show)))
      "10.0.0.12 last: as text it would come first")
  (is (= [:all :right :auto/right :auto/center :auto/even :auto.laser/projector-10-0-0-7-1]
         (rig/refs-for old-show :projector-10-0-0-7-1))
      "All, its groups in their order, then the automatic ones it is in, then its own")
  (is (= [:all :graphics :auto/left :auto/center :auto/odd :auto.laser/projector-10-0-0-5-1]
         (rig/refs-for old-show :projector-10-0-0-5-1)))
  (is (= [:crowd :auto.laser/projector-10-0-0-12-1] (rig/refs-for old-show :projector-10-0-0-12-1))
      "excluded: no All, no folded groups")
  (testing "All and the automatic groups are never taken from the stored list, and nothing twice"
    (is (= [:all :graphics :auto/left :auto/center :auto/odd :auto.laser/projector-10-0-0-5-1]
           (rig/refs-for (assoc-in old-show [:projectors :projector-10-0-0-5-1 :zone-groups]
                                   [:all :graphics :auto/right :graphics :auto.laser/x])
                         :projector-10-0-0-5-1)))))

(deftest refs-for-is-cached-by-identity-test
  (let [state (rig-state 3)
        refs (rig/refs-for state :p1)]
    (is (identical? refs (rig/refs-for state :p1)))
    (is (identical? refs (rig/refs-for (assoc state :chains {:cue-chains {}} :zone-groups {}) :p1))
        "nothing but the projectors and [:rig] counts")
    (testing "a reorder gives the new refs"
      (let [moved (assoc-in state [:rig :order] [:p2 :p1 :p3])]
        (is (= [:all :auto/left :auto/right :auto/center :auto/even :auto.laser/p1]
               (rig/refs-for moved :p1)))
        (is (identical? (rig/refs-for moved :p1) (rig/refs-for moved :p1)))))
    (testing "an equal state that isn't identical computes the same refs"
      (is (= refs (rig/refs-for (update state :projectors #(into {} %)) :p1))))))

(deftest refs-for-disabled-is-nil-test
  (let [state (rig-state 2 :disabled #{2})]
    (is (nil? (rig/refs-for state :p2)) "a laser that is off draws nothing")
    (is (nil? (rig/refs-for state :nope)) "nor does one that doesn't exist")
    (is (nil? (rig/refs-for {} :p1)))))

(deftest refs-reaching-test
  (let [state (-> (rig-state 3)
                  (assoc-in [:projectors :p3 :tags] #{:crowd-scanning})
                  (assoc-in [:projectors :p2 :enabled?] false)
                  (assoc-in [:projectors :p2 :tags] #{:crowd-scanning})
                  (assoc-in [:projectors :p2 :zone-groups] [:balcony]))
        crowd? #(contains? (set (:tags %)) :crowd-scanning)]
    (is (= (into #{:all :balcony :auto.laser/p2} (rig/refs-for state :p3))
           (rig/refs-reaching state crowd?))
        "an enabled one: its refs; one that is off: All, its groups and its own, as today's rule counts it")
    (is (= #{} (rig/refs-reaching state (constantly false))))))

(deftest stable-id-test
  (is (= [:unit "0A 1B 2C 3D" 1] (rig/stable-id {:unit-id " 0a 1b  2c 3D " :service-id 1 :host "10.0.0.5"}))
      "the unit id, upper case, single spaces, and the output")
  (doseq [unusable ["00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00" "FF FF FF FF" "ff ff" "" "   " nil]]
    (is (= [:host "10.0.0.5" 1] (rig/stable-id {:unit-id unusable :service-id 1 :host "10.0.0.5"}))
        (pr-str unusable)))
  (is (= [:host "10.0.0.5" 0] (rig/stable-id {:host "10.0.0.5"})) "a projector added by hand")
  (testing "the label says which"
    (is (= "Unit 0A 1B 2C 3D … · output 1"
           (rig/identity-label {:stable-id [:unit "0A 1B 2C 3D 4E 5F 60 71" 1]})))
    (is (= "Unit 0A 1B · output 2" (rig/identity-label {:unit-id "0a 1b" :service-id 2 :host "10.0.0.5"})))
    (is (= "IP 10.0.0.5 · output 1 (the device sends no unit id)"
           (rig/identity-label {:stable-id [:host "10.0.0.5" 1]})))))

(deftest refs-and-names-test
  (is (= :auto.laser/projector-10-0-0-5-1 (rig/laser-ref :projector-10-0-0-5-1)))
  (is (= :projector-10-0-0-5-1 (rig/laser-of :auto.laser/projector-10-0-0-5-1)))
  (is (nil? (rig/laser-of :auto/left)))
  (is (every? rig/derived-ref? [:all :auto/left :auto/even :auto.laser/p1]))
  (is (not-any? rig/derived-ref? [:left :graphics :group-balcony nil "all"]))
  (let [state (assoc old-show :zone-groups {:right {:name "Right (manual)"} :crowd {:name "Crowd Scanning"}})]
    (is (= ["All" "Left" "Even" "Laser R" "Right (manual)" "Crowd Scanning" "balcony"]
           (mapv #(rig/ref-name state %) [:all :auto/left :auto/even :auto.laser/projector-10-0-0-7-1
                                          :right :crowd :balcony])))
    (is (= "#4A90D9" (get-in rig/group-defs [:auto/left :color])))))


;; The scan's matching

(def ^:private unit-l "0A 1B 2C 3D 4E 5F 60 71 82 93 A4 B5 C6 D7 E8 F9")
(def ^:private unit-r "1F 2E 3D 4C 5B 6A 79 88 97 A6 B5 C4 D3 E2 F1 00")

(defn- device [address unit-id & service-ids]
  {:address address :port 7255 :unit-id unit-id :host-name "DAC"
   :services (mapv (fn [s] {:service-id s :name (str "Out " s)}) service-ids)})

(deftest unique-projector-id-test
  (is (= :projector-10-0-0-5-1 (rig/unique-projector-id {} "10.0.0.5" 1)))
  (is (= :projector-10-0-0-5-0 (rig/unique-projector-id {} "10.0.0.5" nil)))
  (is (= :projector-10-0-0-5-1-3 (rig/unique-projector-id {:projector-10-0-0-5-1 {} :projector-10-0-0-5-1-2 {}}
                                                          "10.0.0.5" 1))))

(deftest match-discovered-test
  (testing "a move: the same unit and output at a new address"
    (let [projectors {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :port 7255 :service-id 1
                                             :stable-id [:unit unit-l 1]}}]
      (is (= {:moves {:projector-10-0-0-5-1 {:host "10.0.0.57" :port 7255}} :upgrades {} :new []
              :notices ["Laser L moved to 10.0.0.57 (same unit)."]}
             (rig/match-discovered projectors [(device "10.0.0.57" unit-l 1)])))
      (is (= {:moves {} :upgrades {} :new [] :notices []}
             (rig/match-discovered projectors [(device "10.0.0.5" unit-l 1)]))
          "found where it was: nothing to do")))
  (testing "two moves, one notice"
    (let [projectors {:l {:name "Laser L" :host "10.0.0.5" :service-id 1 :stable-id [:unit unit-l 1]}
                      :r {:name "Laser R" :host "10.0.0.7" :service-id 1 :stable-id [:unit unit-r 1]}}]
      (is (= ["2 lasers have new addresses: Laser L, Laser R."]
             (:notices (rig/match-discovered projectors [(device "10.0.0.51" unit-l 1) (device "10.0.0.71" unit-r 1)]))))))
  (testing "an upgrade: a laser added by hand, then found by a scan"
    (let [projectors {:projector-1700000000000 {:name "Stage" :host "10.0.0.5" :port 7255 :enabled? true}}]
      (is (= {:moves {} :upgrades {:projector-1700000000000 [:unit unit-l 1]} :new [] :notices []}
             (rig/match-discovered projectors [(device "10.0.0.5" unit-l 1)])))
      (is (= {:projector-1700000000000 [:unit unit-l 0]}
             (:upgrades (rig/match-discovered projectors [(device "10.0.0.5" unit-l)])))
          "a device with no outputs")
      (let [{:keys [upgrades new]} (rig/match-discovered projectors [(device "10.0.0.5" unit-l 1 2)])]
        (is (= {:projector-1700000000000 [:host "10.0.0.5" 0]} upgrades)
            "with two outputs it can't tell which one: only its own identity is written")
        (is (= [:projector-10-0-0-5-1 :projector-10-0-0-5-2] (mapv :id new))))))
  (testing "a new laser gets a free id, its stable id, and never a moved laser's id"
    (let [projectors {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.57" :service-id 1 :stable-id [:unit unit-l 1]}}
          {:keys [moves upgrades new]} (rig/match-discovered projectors [(device "10.0.0.5" unit-r 1)])]
      (is (= [{:id :projector-10-0-0-5-1-2 :stable-id [:unit unit-r 1]}] (mapv #(select-keys % [:id :stable-id]) new)))
      (is (= ["10.0.0.5" {:service-id 1 :name "Out 1"}] ((juxt (comp :address :device) :service) (first new))))
      (is (= {} moves))
      (is (= {} upgrades)))
    (testing "ids are free of each other in one scan too"
      (is (= [:projector-a-1-2 :projector-a-1-2-2]
             (mapv :id (:new (rig/match-discovered {:projector-a-1 {:host "zzz" :stable-id [:unit "ZZ" 1]}}
                                                   [(device "a" "AA" 1) (device "a-1" "BB" 2)])))))))
  (testing "two devices in one scan with one unit id are matched by IP"
    (let [projectors {:a {:name "A" :host "10.0.0.5" :service-id 1 :stable-id [:host "10.0.0.5" 1]}}
          {:keys [moves upgrades new notices]} (rig/match-discovered projectors [(device "10.0.0.6" unit-l 1)
                                                                                 (device "10.0.0.5" unit-l 1)])]
      (is (= ["Two devices report the same unit id (10.0.0.5, 10.0.0.6): matched by IP."] notices))
      (is (= {} moves))
      (is (= {} upgrades) ":a is found by its address")
      (is (= [[:host "10.0.0.6" 1]] (mapv :stable-id new)))))
  (testing "two saved projectors with one unit id (an old show made one per IP)"
    (let [projectors {:projector-10-0-0-5-1 {:name "Old" :host "10.0.0.5" :service-id 1 :unit-id unit-l :enabled? false}
                      :projector-10-0-0-6-1 {:name "New" :host "10.0.0.6" :service-id 1 :unit-id unit-l :enabled? true}}]
      (is (= {:projector-10-0-0-6-1 [:unit unit-l 1] :projector-10-0-0-5-1 [:host "10.0.0.5" 1]}
             (rig/settled-ids projectors))
          "the enabled one keeps it, the other falls back to its address")
      (is (= {:moves {} :new [] :notices []
              :upgrades {:projector-10-0-0-6-1 [:unit unit-l 1] :projector-10-0-0-5-1 [:host "10.0.0.5" 1]}}
             (rig/match-discovered projectors [(device "10.0.0.6" unit-l 1)]))
          "both get their identity written; the other stays where it is")
      (is (= {:projector-10-0-0-5-1 [:unit unit-l 1] :projector-10-0-0-6-1 [:host "10.0.0.6" 1]}
             (rig/settled-ids (assoc-in projectors [:projector-10-0-0-6-1 :enabled?] false)))
          "neither on: the smaller id keeps it"))))


;; Properties of the fold, over generated rigs

(defn- ids-of [entries] (mapv first entries))

(defn- in-order?
  "Are xs, each in v, in the order they have in v?"
  [v xs]
  (let [index (zipmap v (range))]
    (and (every? index xs) (apply < -1 (map index xs)))))

(defn- seeded-shuffle [^java.util.Random rng coll]
  (let [al (java.util.ArrayList. ^java.util.Collection (vec coll))]
    (java.util.Collections/shuffle al rng)
    (vec al)))

(deftest fold-properties-test
  (let [rng (java.util.Random. 20261119)]
    (doseq [n (range 0 13)
            middle [:split :both :left :right :none]
            _ (range 4)]
      (let [extra (.nextInt rng 3)
            total (+ n extra)
            ;; extra lasers that aren't in E: off (an even number) or excluded (odd)
            out (take extra (seeded-shuffle rng (range 1 (inc total))))
            state (-> (rig-state total :middle middle)
                      (update :projectors (fn [ps] (reduce #(assoc-in %1 [(pid %2) (if (even? %2) :enabled? :exclude-from-all?)]
                                                                      (odd? %2))
                                                           ps out)))
                      (assoc-in [:rig :order] (mapv pid (seeded-shuffle rng (range 1 (inc total))))))
            inputs (rig/rig-inputs state)
            e (filterv #(and ((:enabled inputs) %) (not ((:excluded inputs) %))) (:order inputs))
            m (count e)
            {:auto/keys [left right center odd even]} (rig/auto-groups inputs)
            regions (fn [entries] (set (keep second entries)))]
        (testing (str "n = " m ", " middle)
          (is (= n m))
          (is (= (set e) (into (set (ids-of odd)) (ids-of even))) "Odd and Even cover E")
          (when (not= 1 m)
            (is (empty? (set/intersection (set (ids-of odd)) (set (ids-of even)))) "and share no laser"))
          (is (= (take-nth 2 e) (ids-of odd)) "by position")
          (is (<= (count (set/intersection (set (ids-of left)) (set (ids-of right)))) 1)
              "Left and Right share at most one laser")
          (when (= 1 m)
            (is (= #{:L} (regions left) (regions odd)))
            (is (= #{:R} (regions right) (regions even))))
          (when (and (odd? m) (> m 1))
            (is (= (if (= :split middle) #{:L} #{}) (regions left)))
            (is (= (if (= :split middle) #{:R} #{}) (regions right))))
          (when (even? m)
            (is (every? empty? (map regions [left right center odd even])) "halves only for n = 1 and a split middle"))
          (is (every? #(in-order? e (ids-of %)) [left right center odd even]) "each in rig order")
          (is (= (pos? m) (boolean (seq center))) "Center is never empty when n ≥ 1"))))))
