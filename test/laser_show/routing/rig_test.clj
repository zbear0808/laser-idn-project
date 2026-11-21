(ns laser-show.routing.rig-test
  "The rig as data (plans/2026-11-19-zones/04 §4, spec 01 §3): the order of
   the projectors, the automatic groups folded from it, the refs that reach
   each projector, stable identity and the scan's matching; and the routing
   each tick carries (05 §4, spec 01 §5): every ref's lasers, halves and
   sides, each laser's scan rate, fit settings and the boxes its halves fit
   into, worked out again only when the rig or a group changes."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [laser-show.animation.fit-guard :as fit-guard]
            [laser-show.animation.safety :as safety]
            [laser-show.routing.compose :as compose]
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

(defn- driver-device [driver key & outputs]
  {:driver driver :device-key key :name (str "Nova " key)
   :outputs (mapv (fn [[o cfg]] {:output-id o :name (str "Output " o) :driver-config cfg}) (partition 2 outputs))})

(deftest driver-projector-id-test
  (testing "01 §5.3's table"
    (is (= :projector-acme-nova-NV-00123-0 (rig/driver-projector-id :acme/nova "NV-00123" 0)))
    (is (= :projector-zeta-beam-0x1A2B-0 (rig/driver-projector-id :zeta/beam "0x1A2B" 0)))
    (is (= :projector-acme-nova-10-0-0-9-2 (rig/driver-projector-id :acme/nova "10.0.0.9" 2))))
  (testing "an unqualified driver, a number for a key, odd characters"
    (is (= :projector-lsps-7-a (rig/driver-projector-id :lsps 7 "a")))
    (is (= :projector-acme-nova-bus-1-port--3-0 (rig/driver-projector-id :acme/nova "bus 1/port #3" 0))))
  (testing "made unique as unique-projector-id does"
    (is (= :projector-acme-nova-NV-1-0-3
           (rig/unique-projector-id #{:projector-acme-nova-NV-1-0 :projector-acme-nova-NV-1-0-2}
                                    (rig/driver-projector-id :acme/nova "NV-1" 0))))))

(deftest match-discovered-driver-devices-test
  (let [known {:stage {:name "Stage" :driver :acme/nova :driver-config {:serial "NV-1" :host "10.0.0.9"}
                       :stable-id [:device :acme/nova "NV-1" 0]}
               :hand {:name "Hand" :driver :acme/nova :driver-config {:serial "NV-1"}
                      :stable-id [:manual :acme/nova :hand]}}]
    (testing "new: each output its stable id and a free id"
      (let [{:keys [moves upgrades new notices]} (rig/match-discovered {} [(driver-device :acme/nova "NV-1" 0 {:serial "NV-1"} 1 {:serial "NV-1" :o 1})])]
        (is (= [{:id :projector-acme-nova-NV-1-0 :stable-id [:device :acme/nova "NV-1" 0]}
                {:id :projector-acme-nova-NV-1-1 :stable-id [:device :acme/nova "NV-1" 1]}]
               (mapv #(select-keys % [:id :stable-id]) new)))
        (is (= [{:output-id 1 :name "Output 1" :driver-config {:serial "NV-1" :o 1}} "NV-1"]
               ((juxt :output (comp :device-key :device)) (second new))))
        (is (= [{} {} []] [moves upgrades notices]))))
    (testing "known, with the same settings: nothing to do; one added by hand is never matched"
      (is (= {:moves {} :upgrades {} :new [] :notices []}
             (rig/match-discovered known [(driver-device :acme/nova "NV-1" 0 {:serial "NV-1" :host "10.0.0.9"})]))))
    (testing "moved: the complete new config from the output, including a key's removal"
      (is (= {:moves {:stage {:driver-config {:host "10.0.0.20"}}} :upgrades {} :new []
              :notices ["Stage moved (same device)."]}
             (rig/match-discovered known [(driver-device :acme/nova "NV-1" 0 {:host "10.0.0.20"})])))
      (is (= ["2 lasers moved (same devices): Stage, Two."]
             (:notices (rig/match-discovered (assoc known :two {:name "Two" :driver :acme/nova :driver-config {:host "a"}
                                                                :stable-id [:device :acme/nova "NV-2" 0]})
                                             [(driver-device :acme/nova "NV-1" 0 {:host "b"})
                                              (driver-device :acme/nova "NV-2" 0 {:host "b"})])))))
    (testing "an id that is taken, and another driver's device with the same key"
      (let [{:keys [new]} (rig/match-discovered {:projector-acme-nova-NV-1-0 {:name "Old" :driver :acme/nova
                                                                              :stable-id [:manual :acme/nova :projector-acme-nova-NV-1-0]}}
                                                [(driver-device :acme/nova "NV-1" 0 {})
                                                 (driver-device :acme/nova "NV.1" 0 {})
                                                 (driver-device :zeta/beam "NV-1" 0 {})])]
        (is (= [:projector-acme-nova-NV-1-0-2 :projector-acme-nova-NV-1-0-3 :projector-zeta-beam-NV-1-0]
               (mapv :id new)))))
    (testing "IDN devices and driver devices in one scan"
      (let [{:keys [new]} (rig/match-discovered {} [(device "10.0.0.5" unit-l 1) (driver-device :acme/nova "NV-1" 0 {})])]
        (is (= [:projector-10-0-0-5-1 :projector-acme-nova-NV-1-0] (mapv :id new)))
        (is (= [[:unit unit-l 1] [:device :acme/nova "NV-1" 0]] (mapv :stable-id new)))))))


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


;; The routing each tick carries (zones plan Z3, 05 §4)

(defn- close-fits?
  "Are two {:L [cx cy s] :R [cx cy s]} the same, to 1e-9?"
  [a b]
  (and (= (set (keys a)) (set (keys b)))
       (every? (fn [k] (every? true? (map #(< (Math/abs (- (double %1) (double %2))) 1e-9) (get a k) (get b k))))
               (keys a))))

(defn- routing-of [state] (rig/routing (rig/routing-inputs state nil)))

(deftest normalize-target-test
  (is (nil? (rig/normalize-target nil)))
  (is (= :auto/left (rig/normalize-target :auto/left)) "a keyword stays itself")
  (is (= :graphics (rig/normalize-target [:graphics])) "one ref left: that ref")
  (is (= :all (rig/normalize-target [:all :all])))
  (is (nil? (rig/normalize-target [])) "none left: nil")
  (is (= [:all :graphics :auto/left :auto/right :zone/a]
         (rig/normalize-target [:zone/a :auto/right :graphics :all :auto/left :graphics]))
      "distinct, sorted by namespace and name, the refs with none first")
  (is (= (rig/normalize-target [:auto/right :auto/left]) (rig/normalize-target [:auto/left :auto/right]))
      "written in another order: the same target, so the same frame key")
  (is (= [:auto/left :auto/right] (rig/normalize-target #{:auto/right :auto/left})) "a set too")
  (is (= :graphics (rig/normalize-target [:graphics "left" nil])) "only keywords count")
  (testing "a key's refs"
    (is (= [:all] (rig/key-refs :all)))
    (is (= [:all :graphics] (rig/key-refs [:all :graphics])))))

(deftest zone-index-test
  (is (= [0 1 25 26 27 51 52] (mapv rig/zone-index [:zone/a :zone/b :zone/z :zone/aa :zone/ab :zone/az :zone/ba])))
  (is (every? nil? (map rig/zone-index [:all :auto/left :graphics :zone/A :zone/a1 :zone.x/a nil "zone/a"]))
      "nil for any other ref"))

(deftest routing-has-every-ref-the-rig-knows-test
  (testing "one laser: Left and Right, Odd and Even, are its halves"
    (let [r (routing-of (rig-state 1))]
      (is (= {:all [(w 1)] :auto/left [[:p1 :L nil]] :auto/right [[:p1 :R nil]] :auto/center [(w 1)]
              :auto/odd [[:p1 :L nil]] :auto/even [[:p1 :R nil]] :auto.laser/p1 [(w 1)]}
             (:refs r)))
      (is (nil? (:map r)) "no map until Z4")))
  (testing "three, the middle split"
    (is (= {:auto/left [(w 1) [:p2 :L nil]] :auto/right [[:p2 :R nil] (w 3)] :auto/center [(w 2)]}
           (select-keys (:refs (routing-of (rig-state 3))) [:auto/left :auto/right :auto/center]))))
  (testing "three, the middle in both: whole, with its side"
    (is (= {:auto/left [(w 1) [:p2 nil :left]] :auto/right [[:p2 nil :right] (w 3)]}
           (select-keys (:refs (routing-of (rig-state 3 :middle :both))) [:auto/left :auto/right]))))
  (testing "All leaves out a laser that is excluded or off; its own group is there while it is on"
    (let [refs (:refs (routing-of (rig-state 4 :excluded #{2} :disabled #{4})))]
      (is (= [(w 1) (w 3)] (:all refs)))
      (is (= [(w 2)] (:auto.laser/p2 refs)))
      (is (not (contains? refs :auto.laser/p4)))))
  (testing "your groups: their members that are on, whole, in rig order; an empty one reaches nothing"
    (let [refs (:refs (routing-of (-> (rig-state 3 :disabled #{3})
                                      (assoc-in [:projectors :p3 :zone-groups] [:graphics])
                                      (assoc-in [:projectors :p1 :zone-groups] [:graphics])
                                      (assoc :zone-groups {:graphics {:name "Graphics"} :crowd {:name "Crowd"}}))))]
      (is (= [(w 1)] (:graphics refs)))
      (is (= [] (:crowd refs)))
      (is (= [] (rig/resolve-ref {:refs refs} :nobody)) "a ref the rig doesn't know: nothing")
      (is (= [(w 1)] (rig/resolve-ref {:refs refs} :graphics)))))
  (testing "a stray :all in a laser's list never overrides All"
    (let [refs (:refs (routing-of (-> (rig-state 2 :excluded #{2})
                                      (assoc-in [:projectors :p1 :zone-groups] [:all :all])
                                      (assoc-in [:projectors :p2 :zone-groups] [:all]))))]
      (is (= [(w 1)] (:all refs)) "once, and never the excluded laser"))))

(deftest outputs-carry-scan-rate-fit-and-region-fits-test
  (let [state (-> (rig-state 4 :excluded #{3} :disabled #{4})
                  (assoc-in [:projectors :p1 :scan-rate] 40000)
                  (assoc-in [:projectors :p2 :scan-rate] 10)
                  (assoc-in [:projectors :p2 :fit] {:min-fps 30 :dim? true})
                  (assoc-in [:projectors :p1 :safety] {:limit [-0.8 -0.5 0.8 0.5] :blocked []}))
        {:keys [outputs]} (routing-of state)]
    (is (= #{:p1 :p2 :p3} (set (keys outputs))) "every laser that is on, an excluded one too, and none that is off")
    (is (= [0 1 nil] (mapv #(get-in outputs [% :index]) [:p1 :p2 :p3])) "its place in E; an excluded laser has none")
    (is (= [40000 1000 25000] (mapv #(get-in outputs [% :scan-rate]) [:p1 :p2 :p3]))
        "clamped as the engine clamps it, 25,000 without one")
    (is (= fit-guard/defaults (get-in outputs [:p1 :fit])))
    (is (= (assoc fit-guard/defaults :min-fps 30 :dim? true) (get-in outputs [:p2 :fit])) "its own over the defaults")
    (is (close-fits? {:L [-0.2 0.0 0.5] :R [0.2 0.0 0.5]} (get-in outputs [:p1 :regions])) "from its safety limit")
    (is (close-fits? {:L [-0.25 0.0 0.75] :R [0.25 0.0 0.75]} (get-in outputs [:p3 :regions])) "the whole range without one")))

(defn- decimate [fps] {:id (random-uuid) :effect-id :decimate :enabled? true :params {:kpps 25 :fps fps}})

(deftest outputs-carry-each-lasers-decimate-budget-test
  (testing "Q-D-2 B: the budget of the Decimate in a laser's calibration chain, the smallest of several, read from its params"
    (let [state (-> (rig-state 5)
                    (assoc-in [:chains :projector-effects :p1 :items] [(decimate 30)])
                    (assoc-in [:chains :projector-effects :p2 :items] [(decimate 30) (decimate 60)])
                    (assoc-in [:chains :projector-effects :p3 :items] [(assoc (decimate 30) :enabled? false)])
                    (assoc-in [:chains :projector-effects :p4 :items] 5))
          {:keys [outputs]} (routing-of state)]
      (is (= [833 416] (mapv #(get-in outputs [% :decimate-budget]) [:p1 :p2])))
      (is (= [[25.0 30.0] [25.0 60.0]] (mapv #((juxt :decimate-kpps :decimate-fps) (get outputs %)) [:p1 :p2]))
          "and that Decimate's scan rate and target, for the cue warnings' advice (Q-D-4 B)")
      (is (not-any? #(some (partial contains? (get outputs %)) [:decimate-budget :decimate-kpps :decimate-fps]) [:p3 :p4 :p5])
          "none for a disabled one, for a chain that can't be read (the routing is worked out all the same), or without one")
      (is (= (dissoc (:outputs (routing-of (rig-state 5))) :p1 :p2) (dissoc outputs :p1 :p2)) "and nothing else changes"))))

(deftest region-fits-follow-the-safety-limit-test
  (is (close-fits? {:L [-0.25 0.0 0.75] :R [0.25 0.0 0.75]} (rig/region-fits [-1.0 -1.0 1.0 1.0] 0.75))
      "L is -1…0.5 and R -0.5…1, each scaled by min(1.5, 2)/2")
  (is (close-fits? {:L [-0.2 0.0 0.5] :R [0.2 0.0 0.5]} (rig/region-fits [-0.8 -0.5 0.8 0.5] 0.75))
      "min(1.2, 1.0)/2: the height limits it")
  (is (close-fits? {:L [0.0 0.0 1.0] :R [0.0 0.0 1.0]} (rig/region-fits [-1.0 -1.0 1.0 1.0] 1.0))
      "a split width of 1: each half is the whole width")
  (is (nil? (rig/region-fits [0.0 -1.0 0.04 1.0] 0.75)) "under 0.05 wide: no halves (S-9)")
  (is (nil? (rig/region-fits [-1.0 0.0 1.0 0.04] 0.75)) "or under 0.05 high")
  (testing "a limit with its corners swapped: the box safety/normalize makes of it"
    (let [regions #(get-in (routing-of (assoc-in (rig-state 1) [:projectors :p1 :safety] {:limit %})) [:outputs :p1 :regions])]
      (is (some? (regions [0.8 0.5 -0.8 -0.5])))
      (is (= (regions [-0.8 -0.5 0.8 0.5]) (regions [0.8 0.5 -0.8 -0.5]))))))

(deftest unreadable-safety-zone-draws-no-halves-test
  (let [state (rig-state 3)
        unreadable {:limit [0.0 0.0]}
        good (routing-of state)
        r (routing-of (assoc-in state [:projectors :p2 :safety] unreadable))]
    (is (thrown? clojure.lang.ExceptionInfo (safety/normalize unreadable)) "a zone safety/normalize can't read")
    (is (contains? (:outputs r) :p2))
    (is (nil? (get-in r [:outputs :p2 :regions])) "takes that laser's halves away (S-9)")
    (is (= (dissoc (get-in good [:outputs :p2]) :regions) (dissoc (get-in r [:outputs :p2]) :regions))
        "and nothing else of it")
    (is (= (:refs good) (:refs r)) "every group is as it was")
    (is (= (dissoc (:outputs good) :p2) (dissoc (:outputs r) :p2)) "and so is every other laser (D-29)")
    (testing "so is a zone that isn't a map at all"
      (is (nil? (get-in (routing-of (assoc-in state [:projectors :p2 :safety] "wide")) [:outputs :p2 :regions]))))))

(deftest cached-routing-is-reused-while-inputs-are-identical-test
  (let [state (rig-state 3)
        [cache r] (rig/cached-routing nil (rig/routing-inputs state nil))
        [cache' r'] (rig/cached-routing cache (rig/routing-inputs state nil))]
    (is (= (routing-of state) r))
    (is (identical? r r') "the same routing, not an equal one")
    (is (identical? cache cache'))
    (testing "nothing but the rig, the projectors, the groups, the maps, the map id and the lasers' calibration chains counts"
      (is (identical? r (second (rig/cached-routing cache (rig/routing-inputs (assoc state :chains {:cue-chains {}}
                                                                                     :playback {:playing? true})
                                                                              nil)))))
      (let [state (assoc-in state [:chains :projector-effects :p1 :items] [(decimate 30)])
            [cache r] (rig/cached-routing nil (rig/routing-inputs state nil))]
        (is (identical? r (second (rig/cached-routing cache (rig/routing-inputs (assoc-in state [:chains :cue-chains [0 0]] {:items []})
                                                                                nil))))
            "a cue's chain isn't one")))))

(deftest cached-routing-follows-an-edit-test
  (let [state (rig-state 3)
        [cache r] (rig/cached-routing nil (rig/routing-inputs state nil))]
    (doseq [[what edited map-id] [["a reorder" (assoc-in state [:rig :order] [:p3 :p2 :p1]) nil]
                                  ["the middle laser" (assoc-in state [:rig :middle] :both) nil]
                                  ["the split width" (assoc-in state [:rig :split-width] 0.6) nil]
                                  ["a laser turned off" (assoc-in state [:projectors :p2 :enabled?] false) nil]
                                  ["a laser put in a group" (assoc-in state [:projectors :p1 :zone-groups] [:graphics]) nil]
                                  ["a group defined" (assoc state :zone-groups {:graphics {:name "Graphics"}}) nil]
                                  ["a fit setting" (assoc-in state [:projectors :p1 :fit :min-fps] 30) nil]
                                  ["a safety limit" (assoc-in state [:projectors :p1 :safety :limit] [-0.5 -0.5 0.5 0.5]) nil]
                                  ["a laser's calibration chain" (assoc-in state [:chains :projector-effects :p1 :items] [(decimate 30)]) nil]
                                  ["the map" state :map/odd-even]]]
      (testing what
        (let [inputs (rig/routing-inputs edited map-id)
              [cache' r'] (rig/cached-routing cache inputs)]
          (is (not (identical? r r')))
          (is (= (rig/routing inputs) r'))
          (is (identical? r' (second (rig/cached-routing cache' inputs))) "and kept from then on"))))
    (testing "Decimate put in a laser's calibration chain, then its target fps changed (Q-D-2 B): that laser's outputs follow"
      (let [with (assoc-in state [:chains :projector-effects :p1 :items] [(decimate 30)])
            [cache' r'] (rig/cached-routing cache (rig/routing-inputs with nil))
            [_ r''] (rig/cached-routing cache' (rig/routing-inputs (assoc-in with [:chains :projector-effects :p1 :items 0 :params :fps] 60)
                                                                   nil))]
        (is (= [nil 833 416] (mapv #(get-in % [:outputs :p1 :decimate-budget]) [r r' r''])))))
    (testing "an equal state that isn't identical is worked out again, to the same routing"
      (let [[_ r'] (rig/cached-routing cache (rig/routing-inputs (update state :projectors #(into {} %)) nil))]
        (is (not (identical? r r')))
        (is (= r r'))))))

(deftest routing-of-an-invented-rig-test
  (testing "the resolver is pure: data no app holds gives the same routing every time (invariant 9)"
    (let [inputs {:rig {:order [:x :y] :middle :both :split-width 0.6}
                  :projectors {:x {:name "X" :host "a" :zone-groups [:wall]}
                               :y {:name "Y" :host "b" :scan-rate 30000 :safety {:limit [-0.5 -0.5 0.5 0.5]}}}
                  :zone-groups {:wall {:name "Wall"}}
                  :zone-maps nil
                  :map nil}
          r (rig/routing inputs)]
      (is (= r (rig/routing inputs) (rig/routing (into {} inputs))))
      (is (= {:all [[:x nil nil] [:y nil nil]] :auto/left [[:x nil nil]] :auto/right [[:y nil nil]]
              :auto/center [[:x nil nil] [:y nil nil]] :auto/odd [[:x nil nil]] :auto/even [[:y nil nil]]
              :auto.laser/x [[:x nil nil]] :auto.laser/y [[:y nil nil]] :wall [[:x nil nil]]}
             (:refs r)))
      (is (= {:index 0 :scan-rate 25000 :fit fit-guard/defaults} (dissoc (get-in r [:outputs :x]) :regions)))
      (is (close-fits? {:L [-0.4 0.0 0.6] :R [0.4 0.0 0.6]} (get-in r [:outputs :x :regions])))
      (is (= {:index 1 :scan-rate 30000 :fit fit-guard/defaults} (dissoc (get-in r [:outputs :y]) :regions)))
      (is (close-fits? {:L [-0.2 0.0 0.3] :R [0.2 0.0 0.3]} (get-in r [:outputs :y :regions]))))))


;; Zones plan Z4: a zone reaches the lasers of its lane in the effective map
;; (spec 01 §4, §5)

(defn- zone-routing [state map-id] (rig/routing (rig/routing-inputs state map-id)))

(deftest zone-resolves-through-the-effective-map-test
  (let [state (assoc (rig-state 4) :zone-maps {:map/custom-1 {:name "Crowd sweep" :lanes [:auto/left :graphics]
                                                              :overrides {:zone/c :auto/center}}})
        state (assoc-in state [:projectors :p4 :zone-groups] [:graphics])
        lr (zone-routing state :map/left-right)]
    (is (= {:map :map/left-right :lanes [:auto/left :auto/right] :overrides {}} (select-keys lr [:map :lanes :overrides]))
        "the routing names the map and carries its lanes")
    (is (= [(w 1) (w 2)] (rig/resolve-ref lr :zone/a)) "A is Left under Left / Right")
    (is (= [(w 3) (w 4)] (rig/resolve-ref lr :zone/b)) "B is Right")
    (let [oe (zone-routing state :map/odd-even)]
      (is (= [(w 1) (w 3)] (rig/resolve-ref oe :zone/a)) "the same zone, another map: Odd")
      (is (= [(w 2) (w 4)] (rig/resolve-ref oe :zone/b))))
    (let [mine (zone-routing state :map/custom-1)]
      (is (= [(w 4)] (rig/resolve-ref mine :zone/b)) "your map: B is Graphics")
      (is (= [(w 2) (w 3)] (rig/resolve-ref mine :zone/c)) "and its override sends C to Center"))
    (testing "a zone never resolves to a zone; an empty lane, or none, reaches nothing"
      (is (= [] (rig/resolve-ref (zone-routing (assoc-in state [:zone-maps :map/custom-1 :lanes] [:zone/b nil]) :map/custom-1) :zone/a)))
      (is (= [] (rig/resolve-ref (zone-routing (assoc-in state [:zone-maps :map/custom-1 :lanes] [:auto/left nil]) :map/custom-1) :zone/b)))
      (is (= [] (rig/resolve-ref (zone-routing (assoc-in state [:zone-maps :map/custom-1 :lanes] []) :map/custom-1) :zone/a))))
    (testing "an id that is no map, or none: Left / Right's lanes"
      (is (= [(w 1) (w 2)] (rig/resolve-ref (zone-routing state :map/gone) :zone/a)))
      (is (= [(w 1) (w 2)] (rig/resolve-ref (zone-routing state nil) :zone/a))))
    (testing "the other refs as before"
      (is (= [(w 1) (w 2)] (rig/resolve-ref lr :auto/left)))
      (is (= [] (rig/resolve-ref lr :nothing))))))

(deftest unison-sends-every-zone-to-all-test
  (let [state (rig-state 3 :excluded #{3})
        r (zone-routing state :map/unison)]
    (doseq [zone [:zone/a :zone/b :zone/c :zone/z :zone/aa]]
      (is (= (rig/resolve-ref r :all) (rig/resolve-ref r zone)) (name zone)))
    (is (= [(w 1) (w 2)] (rig/resolve-ref r :zone/a)) "All: the lasers that aren't excluded")))

(deftest wrapped-zones-share-a-lane-test
  (let [r (zone-routing (rig-state 2) :map/left-right)]
    (is (= (rig/resolve-ref r :zone/a) (rig/resolve-ref r :zone/c) (rig/resolve-ref r :zone/e) [(w 1)]) "A, C, E: Left")
    (is (= (rig/resolve-ref r :zone/b) (rig/resolve-ref r :zone/d) [(w 2)]) "B, D: Right")
    (testing "one laser: the zones take its halves"
      (let [one (zone-routing (rig-state 1) :map/left-right)]
        (is (= [[:p1 :L nil]] (rig/resolve-ref one :zone/a) (rig/resolve-ref one :zone/c)))
        (is (= [[:p1 :R nil]] (rig/resolve-ref one :zone/b)))))))

(deftest resolve-ref-callers-see-zones-test
  (let [state (rig-state 2)
        frame [[0.0 0.0 1.0 1.0 1.0] [0.1 0.0 1.0 1.0 1.0]]
        cues [{:cell [0 0] :zone-frames {:zone/a frame}}]]
    (testing "the engines' composition"
      (is (= frame (:frame (compose/projector-frame cues (zone-routing state :map/left-right) :p1))))
      (is (nil? (compose/projector-frame cues (zone-routing state :map/left-right) :p2)))
      (is (= frame (:frame (compose/projector-frame cues (zone-routing state :map/unison) :p2))) "Unison: both"))
    (testing "the cue warnings"
      (let [heavy (vec (repeat 3000 [0.0 0.0 1.0 1.0 1.0]))
            warned (compose/cue-warnings [{:cell [0 0] :zone-frames {:zone/b heavy}}] (zone-routing state :map/left-right))]
        (is (= :p2 (get-in warned [[0 0] :projector])) "B is on the right laser")))))
