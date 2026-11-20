(ns laser-show.views.components.target-control-test
  "The Target menu's rows (zones plan Z4, 06 §7.1), as data: what each scope
   offers, which row is ticked, and what each sets."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.routing.targets :as targets]
            [laser-show.views.components.target-control :as target-control]))

(def ^:private groups
  [{:id :all :name "All" :kind :all}
   {:id :auto/left :name "Left" :kind :auto}
   {:id :auto/right :name "Right" :kind :auto}
   {:id :auto/center :name "Center" :kind :auto}
   {:id :auto/odd :name "Odd" :kind :auto}
   {:id :auto/even :name "Even" :kind :auto}
   {:id :auto.laser/p1 :name "Laser 1" :kind :laser}
   {:id :auto.laser/p2 :name "Laser 2" :kind :laser}
   {:id :crowd :name "Crowd Scanning" :kind :custom}
   {:id :graphics :name "Graphics" :kind :custom}])

(def ^:private ctx
  {:scope :item
   :own nil
   :inherited {:target :zone/a :from :column :via 2}
   :names (targets/names groups {:zone/a "Beams"})
   :zone-map {:id :map/left-right :name "Left / Right" :lanes [:auto/left :auto/right]}
   :zones #{:zone/a}
   :audience #{:crowd}})

(defn- kinds [rows] (mapv :kind rows))

(defn- row [rows kind ref] (some #(when (and (= kind (:kind %)) (= ref (:ref %))) %) rows))

(deftest menu-model-sections-test
  (let [rows (target-control/menu-model ctx)]
    (is (= [:inherit :ref :header :zone :zone :zone :header
            :group :group :group :group :group :group :group :group :group
            :separator :combine :pin]
           (kinds rows))
        "Inherit, All, the zones, the groups, then Combine and Pin")
    (testing "Inherit says what it inherits and from where, ticked while the level sets nothing"
      (is (= {:kind :inherit :text "Inherit (A · Beams → Left · from column 3)" :checked? true :target nil}
             (first rows))))
    (testing "the zones: A and B, those the show uses, and a new one with the next letter, each with its lane in the map in use"
      (is (= "ZONES · map: Left / Right" (:text (nth rows 2))))
      (is (= [["A · Beams → Left" :zone/a false] ["B → Right" :zone/b false] ["New zone (C → Left)" :zone/c true]]
             (mapv (juxt :text :target :new?) (filter #(= :zone (:kind %)) rows)))))
    (testing "the groups in Z2's order, pinned, the audience marked; All on its own row"
      (is (= {:kind :ref :ref :all :text "All" :checked? false :target :all} (second rows)))
      (is (= "GROUPS" (:text (nth rows 6))))
      (is (= ["Left" "Right" "Center" "Odd" "Even" "Laser 1" "Laser 2" "Crowd Scanning" "Graphics"]
             (mapv :text (filter #(= :group (:kind %)) rows))))
      (is (:audience? (row rows :group :crowd)))
      (is (not (:audience? (row rows :group :graphics)))))
    (testing "Pin swaps the inherited zone for its lane"
      (is (= {:kind :pin :text "Pin: A → Left" :target :auto/left} (peek rows)))))
  (testing "the level's own target is ticked"
    (let [rows (target-control/menu-model (assoc ctx :own :zone/b))]
      (is (false? (:checked? (first rows))))
      (is (:checked? (row rows :zone :zone/b)))
      (is (= {:kind :pin :text "Pin: B → Right" :target :auto/right} (peek rows)))))
  (testing "a group the target names that the show no longer has comes first, ticked"
    (let [rows (target-control/menu-model (assoc ctx :own :front))
          groups (filter #(= :group (:kind %)) rows)]
      (is (= {:kind :group :ref :front :text "⚠ Front (missing)" :missing? true :checked? true :target :front}
             (select-keys (first groups) [:kind :ref :text :missing? :checked? :target])))
      (is (not-any? #(= :pin (:kind %)) rows) "no zone: no Pin")))
  (testing "zones the show uses are listed, in order"
    (let [rows (target-control/menu-model (assoc ctx :zones #{:zone/a :zone/d :zone/c}))]
      (is (= [:zone/a :zone/b :zone/c :zone/d :zone/e] (mapv :target (filter #(= :zone (:kind %)) rows))))
      (is (= "D → Right" (:text (row rows :zone :zone/d))))))
  (testing "an empty lane reaches nothing"
    (let [rows (target-control/menu-model (assoc ctx :zone-map {:name "Mine" :lanes [:auto/left nil]}))]
      (is (= "B → nothing ⚠" (:text (row rows :zone :zone/b)))))))

(deftest menu-model-scopes-test
  (testing "every row for an item, a group, a track, a clip, the cue and a column"
    (doseq [scope [:item :group :track :clip :cue :column]]
      (is (= [:inherit :ref :header] (take 3 (kinds (target-control/menu-model (assoc ctx :scope scope))))) (name scope))
      (is (some #{:combine} (kinds (target-control/menu-model (assoc ctx :scope scope)))))))
  (testing "the show: no Inherit, and nothing set is All"
    (let [rows (target-control/menu-model (assoc ctx :scope :show :inherited nil))]
      (is (= :ref (:kind (first rows))))
      (is (:checked? (first rows)) "All, ticked")
      (is (some #{:combine} (kinds rows)))))
  (testing "a map's lane: All or a group, never a zone, nothing to combine or pin"
    (let [rows (target-control/menu-model (assoc ctx :scope :lane :own :auto/left))]
      (is (= #{:ref :header :group} (set (kinds rows))))
      (is (= "GROUPS" (:text (second rows))))
      (is (:checked? (row rows :group :auto/left)))))
  (testing "a preview cell: Master instead of Inherit, the zones and groups, nothing to combine or pin"
    (let [rows (target-control/menu-model (assoc ctx :scope :preview :inherited nil))]
      (is (= {:kind :master :text "Master (everything)" :checked? true :target nil} (first rows)))
      (is (some #{:zone} (kinds rows)))
      (is (not-any? #{:combine :pin :inherit} (kinds rows)))
      (is (not-any? :new? (filter #(= :zone (:kind %)) rows)) "no new zone: nothing draws into it"))))

(deftest combine-rows-toggle-test
  (let [combine (fn [own] (:rows (some #(when (= :combine (:kind %)) %) (target-control/menu-model (assoc ctx :own own)))))]
    (testing "each row adds or takes out its ref"
      (let [rows (combine :zone/a)]
        (is (= [[:zone/a true nil] [:zone/b false [:zone/a :zone/b]]]
               (mapv (juxt :ref :checked? :target) (take 2 rows))))
        (is (= [:graphics :zone/a] (:target (some #(when (= :graphics (:ref %)) %) rows))))
        (is (= "A · Beams → Left" (:text (first rows))))))
    (testing "a union is ticked ref by ref"
      (let [rows (combine [:zone/a :zone/b])]
        (is (= [true true] (mapv :checked? (take 2 rows))))
        (is (= :zone/b (:target (first rows))) "unticking A leaves B")))
    (testing "from nothing, a row sets its ref"
      (is (= :zone/b (:target (second (combine nil))))))
    (testing "the zones and groups, no All and no new zone"
      (is (= [:zone/a :zone/b :auto/left :auto/right :auto/center :auto/odd :auto/even
              :auto.laser/p1 :auto.laser/p2 :crowd :graphics]
             (mapv :ref (combine nil)))))))
