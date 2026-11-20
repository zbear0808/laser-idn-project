(ns laser-show.routing.targets-test
  "Targets (zones plan Z4, 06 §4.1, §8.2, §8.5; spec 01 §1, §2): the normal
   form, zones and their letters, inheriting with where it came from, the
   tracks' folders, the refs a cue can draw to, and what the UI says of a
   target."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.routing.targets :as targets]))

(def ^:private groups
  "Z2's target-groups for a rig of two lasers and Graphics."
  [{:id :all :name "All" :kind :all}
   {:id :auto/left :name "Left" :kind :auto}
   {:id :auto/right :name "Right" :kind :auto}
   {:id :auto/center :name "Center" :kind :auto}
   {:id :auto/odd :name "Odd" :kind :auto}
   {:id :auto/even :name "Even" :kind :auto}
   {:id :auto.laser/p1 :name "Laser 1" :kind :laser :projector-id :p1}
   {:id :auto.laser/p2 :name "Laser 2" :kind :laser :projector-id :p2}
   {:id :beams :name "Beams" :kind :custom}
   {:id :graphics :name "Graphics" :kind :custom}])

(def ^:private names (targets/names groups {:zone/a "Beams"}))

(def ^:private plain (targets/names groups {}))

(def ^:private left-right {:id :map/left-right :name "Left / Right" :lanes [:auto/left :auto/right]})

(deftest normal-form-test
  (is (nil? (targets/normalize-target nil)))
  (is (= :zone/a (targets/normalize-target :zone/a)))
  (is (= [:zone/a :zone/b] (targets/normalize-target [:zone/b :zone/a])))
  (is (= :zone/a (targets/normalize-target [:zone/a :zone/a])) "one ref left: the keyword")
  (is (nil? (targets/normalize-target [])) "none left: nil, it inherits")
  (is (= [:all :graphics :auto/left :zone/b] (targets/normalize-target #{:zone/b :auto/left :graphics :all}))
      "refs with no namespace first, then by namespace and name")
  (is (= [:graphics :zone/a] (targets/normalize-target [:graphics "x" nil :zone/a])) "only keywords")
  (testing "idempotent"
    (doseq [t [nil :all [:zone/b :zone/a] #{:graphics :auto/left} [:x] []]]
      (let [n (targets/normalize-target t)]
        (is (= n (targets/normalize-target n)) (pr-str t)))))
  (is (= [] (targets/target-refs nil)))
  (is (= [:zone/a] (targets/target-refs :zone/a)))
  (is (= [:zone/a :zone/b] (targets/target-refs [:zone/b :zone/a]))))

(deftest zone-index-round-trip-test
  (doseq [[zone i letters] [[:zone/a 0 "A"] [:zone/z 25 "Z"] [:zone/aa 26 "AA"] [:zone/az 51 "AZ"] [:zone/ba 52 "BA"]
                            [:zone/zz 701 "ZZ"] [:zone/aaa 702 "AAA"]]]
    (testing (name zone)
      (is (= i (targets/zone-index zone)))
      (is (= zone (targets/zone-ref i)))
      (is (= letters (targets/zone-letter zone)))
      (is (targets/zone-ref? zone))))
  (testing "every index comes back"
    (is (every? #(= % (targets/zone-index (targets/zone-ref %))) (range 2000))))
  (testing "anything else is no zone"
    (doseq [ref [:all :auto/left :graphics :zone/A :zone/a1 (keyword "zone" "") nil "zone/a"]]
      (is (nil? (targets/zone-index ref)) (pr-str ref))
      (is (nil? (targets/zone-letter ref)))))
  (testing "the next zone"
    (is (= :zone/a (targets/next-zone #{})))
    (is (= :zone/c (targets/next-zone #{:zone/a :zone/b})))
    (is (= :zone/b (targets/next-zone [:zone/a :zone/c :graphics])))
    (is (= :zone/aa (targets/next-zone (map targets/zone-ref (range 26)))))))

(def ^:private section-8-1-cue
  "06 §8.1's cue, in column 3."
  {:name "Beams"
   :items [{:type :preset :id :i1 :preset-id :beam-fan}
           {:type :preset :id :i2 :preset-id :wave :target :zone/b}
           {:type :group :id :i3 :name "Pair" :target [:zone/a :zone/b]
            :items [{:type :preset :id :i4 :preset-id :square}]}
           {:type :preset :id :i5 :preset-id :star :target :graphics}]})

(defn- shown
  "A resolution as the editors say it: its target, then where it came from."
  [res]
  (let [from (targets/from-text res)]
    (str (targets/target-text names left-right (:target res)) (when from (str " · " from)))))

(deftest resolve-levels-test
  (let [{:keys [cue items]} (targets/explain-cue section-8-1-cue :zone/a nil 2)]
    (is (= {:target :zone/a :from :column :via 2} cue) "the cue has none: its column's")
    (testing "beam-fan: nothing of its own, the column's"
      (is (= {:target :zone/a :from :column :via 2} (items :i1)))
      (is (= "A · Beams → Left · from column 3" (shown (items :i1)))))
    (testing "wave: its own"
      (is (= {:target :zone/b :from :item} (items :i2)))
      (is (= "B → Right" (shown (items :i2)))))
    (testing "Pair, a group: its own union"
      (is (= {:target [:zone/a :zone/b] :from :item} (items :i3)))
      (is (= "A + B → Left + Right" (shown (items :i3)))))
    (testing "square, in Pair: the group's"
      (is (= {:target [:zone/a :zone/b] :from :group :via :i3 :via-name "Pair"} (items :i4)))
      (is (= "A + B → Left + Right · from group Pair" (shown (items :i4)))))
    (testing "star: its own pin"
      (is (= {:target :graphics :from :item} (items :i5)))
      (is (= "Graphics" (shown (items :i5))))))
  (testing "a clip on track Kick, in folder Beat (on zone C): the folder's"
    (let [cue {:editor-type :timeline
               :tracks [{:id :beat :type :group :name "Beat" :target :zone/c
                         :items [{:id :kick :type :track :name "Kick"}]}]
               :items [{:type :preset :id :clip :track-id :kick}]}
          {:keys [items tracks]} (targets/explain-cue cue nil nil)]
      (is (= {:target :zone/c :from :track :via :beat :via-name "Beat" :via-folder? true} (items :clip)))
      (is (= "C → Left · from folder Beat" (shown (items :clip))))
      (is (= {:target :zone/c :from :item} (tracks :beat)) "the folder's own")
      (is (= "from folder Beat" (targets/from-text (tracks :kick))) "the track's, from its folder")))
  (testing "nothing set anywhere: All"
    (let [{:keys [cue items]} (targets/explain-cue {:items [{:type :preset :id :x}]} nil nil)]
      (is (= {:target :all :from :default} cue (items :x)))
      (is (= "All" (shown (items :x))))
      (is (nil? (targets/from-text (items :x))))))
  (testing "the fallback's order: the cue, its column, the show, All"
    (is (= {:target :graphics :from :cue} (targets/cue-fallback :graphics :zone/a :zone/b)))
    (is (= {:target :zone/a :from :column} (targets/cue-fallback nil :zone/a :zone/b)))
    (is (= {:target :zone/b :from :show} (targets/cue-fallback [] nil :zone/b)) "an empty target is none")
    (is (= {:target :all :from :default} (targets/cue-fallback nil nil nil)))
    (is (= "from the cue" (targets/from-text {:from :cue})))
    (is (= "from the show default" (targets/from-text {:from :show}))))
  (testing "resolve-item: own, then track, then parent, then fallback"
    (let [fallback {:target :zone/d :from :cue}
          parent {:target :zone/e :from :group :via :g}
          track {:target :zone/f :via :t}]
      (is (= {:target :zone/a :from :item} (targets/resolve-item [:zone/a] track parent fallback)))
      (is (= {:target :zone/f :from :track :via :t} (targets/resolve-item nil track parent fallback)))
      (is (= parent (targets/resolve-item nil nil parent fallback)))
      (is (= fallback (targets/resolve-item [] nil nil fallback)))
      (is (= {:target :all :from :default} (targets/resolve-item nil nil nil nil))))))

(deftest explain-says-what-each-level-would-inherit-test
  (let [cue {:editor-type :timeline
             :target :graphics
             :tracks [{:id :beat :type :group :name "Beat" :target :zone/c
                       :items [{:id :kick :type :track :name "Kick" :target :zone/a}
                               {:id :snare :type :track :name "Snare"}]}
                      {:id :lead :type :track :name "Lead" :target :zone/b}]
             :items [{:type :preset :id :c1 :track-id :kick :target :auto/left}
                     {:type :preset :id :c2 :track-id :snare}
                     {:type :preset :id :c3 :track-id :lead}]}
        {:keys [item-inherits track-inherits]} (targets/explain-cue cue nil nil)]
    (testing "a clip: its track's, whatever its own"
      (is (= {:target :zone/a :from :track :via :kick :via-name "Kick" :via-folder? false} (item-inherits :c1)))
      (is (= {:target :zone/c :from :track :via :beat :via-name "Beat" :via-folder? true} (item-inherits :c2))))
    (testing "a track: its folder's, whatever its own; at the top, the cue's"
      (is (= {:target :zone/c :from :track :via :beat :via-name "Beat" :via-folder? true} (track-inherits :kick)))
      (is (= {:target :graphics :from :cue} (track-inherits :lead) (track-inherits :beat))))
    (testing "list items: their group's, or the cue's fallback"
      (let [{:keys [item-inherits]} (targets/explain-cue {:items [{:type :group :id :g :name "G" :target :zone/b
                                                                    :items [{:type :preset :id :x :target :zone/a}]}
                                                                   {:type :preset :id :y :target :zone/a}]}
                                                          :zone/d nil 2)]
        (is (= {:target :zone/b :from :group :via :g :via-name "G"} (item-inherits :x)))
        (is (= {:target :zone/d :from :column :via 2} (item-inherits :y) (item-inherits :g)))))))

(deftest group-passes-through-what-it-inherits-test
  (let [cue {:target :zone/c
             :items [{:type :group :id :outer :name "Outer"
                      :items [{:type :preset :id :a}
                              {:type :group :id :inner :name "Inner" :target :zone/b
                               :items [{:type :group :id :deep :name "Deep"
                                        :items [{:type :preset :id :b}]}]}]}]}
        {:keys [items]} (targets/explain-cue cue nil nil)]
    (is (= {:target :zone/c :from :cue} (items :outer)))
    (is (= {:target :zone/c :from :cue} (items :a)) "Outer has none of its own: the cue's passes through")
    (is (= {:target :zone/b :from :group :via :inner :via-name "Inner"} (items :deep)))
    (is (= {:target :zone/b :from :group :via :inner :via-name "Inner"} (items :b))
        "through a group with none: still Inner's")
    (is (= (targets/inherited {:target :zone/b :from :track :via :t} :g) {:target :zone/b :from :track :via :t}))))

(deftest track-targets-walk-folders-test
  (let [tracks [{:id :f :type :group :name "F" :target :zone/a
                 :items [{:id :t1 :type :track}
                         {:id :t2 :type :track :target :graphics}
                         {:id :g :type :group :items [{:id :t3 :type :track}]}]}
                {:id :t4 :type :track}
                ;; the old flat form
                {:id :pf :type :folder :target :auto/right :path []}
                {:id :pt :type :track :path [:pf]}
                {:id :pu :type :track :parent-id :pf}
                ;; a loop of parents
                {:id :x :type :track :parent-id :y}
                {:id :y :type :track :parent-id :x}
                {:id :u :type :track :parent-id :v}
                {:id :v :type :track :parent-id :u :target :zone/z}]
        result (targets/track-targets tracks)]
    (is (= {:target :zone/a :via :f} (result :f)))
    (is (= {:target :zone/a :via :f} (result :t1)) "a track in a folder: the folder's")
    (is (= {:target :graphics :via :t2} (result :t2)) "its own beats the folder's")
    (is (= {:target :zone/a :via :f} (result :t3)) "two folders up")
    (is (nil? (result :t4)) "none anywhere")
    (is (= {:target :auto/right :via :pf} (result :pt)) "by :path")
    (is (= {:target :auto/right :via :pf} (result :pu)) "by :parent-id")
    (is (nil? (result :x)) "a loop with no target stops")
    (is (= {:target :zone/z :via :v} (result :u)) "one with a target in it is found")
    (is (identical? result (targets/track-targets tracks)) "kept while the tracks are the same")
    (is (= {} (targets/track-targets nil)))))

(deftest toggle-builds-unions-test
  (is (= :zone/a (targets/toggle nil :zone/a)))
  (is (= [:zone/a :zone/b] (targets/toggle :zone/a :zone/b)))
  (is (= :zone/b (targets/toggle [:zone/a :zone/b] :zone/a)))
  (is (nil? (targets/toggle :zone/b :zone/b)) "the last one out: it inherits")
  (is (= [:graphics :zone/a :zone/b] (targets/toggle [:zone/b :graphics] :zone/a)) "in normal form"))

(deftest cue-refs-include-disabled-items-test
  (let [cue {:tracks [{:id :m :type :track :muted? true}]
             :items [{:type :preset :id :a :enabled? false :target :zone/c}
                     {:type :preset :id :b :track-id :m :target :crowd}
                     {:type :preset :id :c}
                     {:type :group :id :g :target [:zone/a :graphics]
                      :items [{:type :preset :id :d}
                              {:type :preset :id :e :target :auto/left}]}]}]
    (is (= #{:zone/c :crowd :zone/b :zone/a :graphics :auto/left} (targets/cue-refs cue :zone/b nil))
        "off, muted, inherited from the column, through a group")
    (is (= #{:zone/c :crowd :zone/d :zone/a :graphics :auto/left} (targets/cue-refs cue nil :zone/d)) "the show's")
    (is (= #{:zone/c :crowd :all :zone/a :graphics :auto/left} (targets/cue-refs cue nil nil)) "All by default")
    (is (= #{} (targets/cue-refs {:target :zone/a :items []} nil nil)) "no preset: nowhere")))

(deftest badge-text-test
  (testing "06 §8.5's table"
    (doseq [[ts badge tooltip] [[#{:all} nil nil]
                                [#{:zone/a} {:zones "A" :pins nil} "A · Beams → Left"]
                                [#{:zone/a :zone/b} {:zones "A B" :pins nil} "A → Left · B → Right"]
                                [#{[:zone/a :zone/b]} {:zones "A+B" :pins nil} "A + B → Left + Right"]
                                [#{:zone/a :zone/b :zone/c :zone/d} {:zones "A–D" :pins nil}
                                 "A → Left · B → Right · C → Left · D → Right"]
                                [#{:auto/left :auto/right} {:zones nil :pins "L/R"} "Pinned: ignores the zone map"]
                                [#{:zone/a :graphics} {:zones "A" :pins "Graph…"} "A → Left · Graphics (pinned)"]]]
      (let [n (if (= #{:zone/a} ts) names plain)]
        (testing (pr-str ts)
          (is (= badge (targets/badge n ts)))
          (is (= tooltip (targets/badge-tooltip n left-right nil ts)))))))
  (testing "short names"
    (is (= {:zones nil :pins "Ctr/Even/Odd"} (targets/badge plain #{:auto/center :auto/odd :auto/even})))
    (is (= {:zones nil :pins "#2"} (targets/badge plain #{:auto.laser/p2})) "a laser by its place")
    (is (= {:zones nil :pins "Beams"} (targets/badge plain #{:beams})) "five letters stay")
    (is (= {:zones "A C" :pins nil} (targets/badge plain #{:zone/a :zone/c})) "not in a row")
    (is (= {:zones "A–C E" :pins nil} (targets/badge plain #{:zone/a :zone/b :zone/c :zone/e}))))
  (testing "the map's name, when it is given"
    (is (= "A → Left · B → Right (map: Left / Right)" (targets/badge-tooltip plain left-right "Left / Right" #{:zone/a :zone/b}))))
  (testing "as plain text"
    (is (= "A pinned L/R" (targets/badge-text {:zones "A" :pins "L/R"})))
    (is (= "A B" (targets/badge-text {:zones "A B"})))
    (is (= "pinned Graph…" (targets/badge-text {:pins "Graph…"})))))

(deftest ref-name-test
  (is (= "A" (targets/ref-name plain :zone/a)))
  (is (= "A · Beams" (targets/ref-name names :zone/a)) "a label")
  (is (= "B" (targets/ref-name (targets/names groups {:zone/b "  "}) :zone/b)) "a blank one isn't")
  (is (= "Left" (targets/ref-name names :auto/left)))
  (is (= "Laser 2" (targets/ref-name names :auto.laser/p2)))
  (is (= "All" (targets/ref-name names :all)))
  (is (= "All" (targets/ref-name {} :all)) "All even with no groups")
  (is (= "Front (missing)" (targets/ref-name names :front)) "one the show no longer has")
  (testing "a target's text"
    (is (= "A · Beams → Left" (targets/target-text names left-right :zone/a)))
    (is (= "B → nothing ⚠" (targets/target-text names {:lanes [:auto/left nil :auto/right]} :zone/b))
        "an empty lane")
    (is (= "A + Graphics → Left + Graphics" (targets/target-text names left-right [:zone/a :graphics])))
    (is (= "A + C → Left" (targets/target-text names left-right [:zone/a :zone/c])) "where they go, once")
    (is (nil? (targets/target-text names left-right nil)))))
