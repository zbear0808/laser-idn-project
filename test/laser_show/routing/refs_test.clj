(ns laser-show.routing.refs-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.routing.refs :as refs]))

(defn- preset [id & {:as fields}]
  (merge {:id id :type :preset :preset-id :circle :enabled? true :params {} :effects []} fields))

(def ^:private one-per-place
  "One cue per place a zone group can be named, each naming :balcony there
   only, in Z4's form (:target)."
  {[0 0] {:target :balcony :items [(preset "p")]}
   [1 0] {:items [(preset "p" :target :balcony)]}
   [2 0] {:items [{:id "g" :type :group :target :balcony :items [(preset "p")]}]}
   [3 0] {:items [{:id "g" :type :group :items [(preset "p" :target :balcony)]}]}
   [0 1] {:editor-type :timeline
          :tracks [{:id "t1" :type :track :target :balcony}]
          :items [(preset "clip" :track-id "t1")]}
   [1 1] {:editor-type :timeline
          :tracks [{:id "f1" :type :group :target :balcony
                    :items [{:id "t1" :type :track}]}]
          :items [(preset "clip" :track-id "t1")]}
   [3 1] {:editor-type :timeline
          :tracks [{:id "t1" :type :track}]
          :items [{:id "gclip" :type :group :track-id "t1"
                   :items [(preset "p" :target :balcony)]}]}})

(defn- names?
  "Does x name kw anywhere?"
  [x kw]
  (boolean (some #{kw} (tree-seq coll? seq x))))

(defn- show-state []
  {:chains {:cue-chains (assoc one-per-place [9 9] {:target :left
                                                    :items [(preset "other" :target :right)]})
            :effect-chains {[0 0] {:items [{:effect-id :scale :params {}}]}}}
   :config {:preview {:zone-group-filter :balcony
                      :grid-cells [{:target nil} {:target :balcony} {:target :right}]}}
   :projectors {:p1 {:zone-groups [:all :balcony]}}
   :virtual-projectors {:vp {:zone-groups [:balcony]}}})

(deftest cues-using-finds-every-place-test
  (is (= (set (keys one-per-place)) (refs/cues-using one-per-place :balcony)))
  (testing "sorted by cell"
    (is (= (sort (keys one-per-place)) (seq (refs/cues-using one-per-place :balcony)))))
  (testing "each place on its own"
    (doseq [[cell cue] one-per-place]
      (is (contains? (refs/cue-refs cue) :balcony) (str cell)))))

(deftest cue-without-target-names-all-test
  (is (contains? (refs/cue-refs {:items []}) :all))
  (is (not (contains? (refs/cue-refs {:target :left :items []}) :all)))
  (is (= #{[0 0]} (refs/cues-using {[0 0] {:items []} [1 0] nil} :all))))

(deftest replace-refs-rewrites-every-place-test
  (let [state (refs/replace-refs (show-state) {:balcony :left})]
    (is (not (names? (:chains state) :balcony)))
    (is (not (names? (get-in state [:config :preview]) :balcony)))
    (is (= (conj (set (keys one-per-place)) [9 9])
           (refs/cues-using (get-in state [:chains :cue-chains]) :left))
        "every cue that named :balcony now names :left, beside [9 9], which already did")
    (is (= :left (get-in state [:chains :cue-chains [0 0] :target])))
    (is (= :left (get-in state [:chains :cue-chains [3 1] :items 0 :items 0 :target])) "a clip in a group clip")
    (is (= [{:target nil} {:target :left} {:target :right}] (get-in state [:config :preview :grid-cells])))
    (is (= :left (get-in state [:config :preview :zone-group-filter])))))

(deftest replace-refs-to-nil-inherits-test
  (let [state (refs/replace-refs (show-state) {:balcony nil})
        cues (get-in state [:chains :cue-chains])]
    (is (not (names? (:chains state) :balcony)))
    (testing "the cue falls back to its column's, the show's, or All"
      (is (not (contains? (get cues [0 0]) :target))))
    (testing "items, groups, tracks and folders inherit"
      (is (not (contains? (get-in cues [[1 0] :items 0]) :target)))
      (is (not (contains? (get-in cues [[2 0] :items 0]) :target)))
      (is (not (contains? (get-in cues [[3 0] :items 0 :items 0]) :target)))
      (is (not (contains? (get-in cues [[0 1] :tracks 0]) :target)))
      (is (not (contains? (get-in cues [[1 1] :tracks 0]) :target)))
      (is (not (contains? (get-in cues [[3 1] :items 0 :items 0]) :target))))
    (testing "the preview cell shows the master view, the filter All"
      (is (= {:target nil} (get-in state [:config :preview :grid-cells 1])))
      (is (= :all (get-in state [:config :preview :zone-group-filter]))))))

(deftest replace-refs-leaves-membership-alone-test
  (let [before (show-state)
        after (refs/replace-refs before {:balcony :left})]
    (is (= (:projectors before) (:projectors after)))
    (is (= (:virtual-projectors before) (:virtual-projectors after)))))

(deftest replace-refs-unused-is-identical-test
  (let [state (show-state)]
    (is (identical? state (refs/replace-refs state {:nothing-names-this :left})))
    (testing "a cue it doesn't touch keeps its identity"
      (let [after (refs/replace-refs state {:balcony :left})]
        (is (identical? (get-in state [:chains :cue-chains [9 9]])
                        (get-in after [:chains :cue-chains [9 9]])))
        (is (identical? (get-in state [:chains :effect-chains]) (get-in after [:chains :effect-chains])))))))

(deftest effects-grid-and-preview-dont-count-as-cues-test
  (let [state (show-state)
        cues (dissoc (get-in state [:chains :cue-chains]) [0 0])]
    (is (not (contains? (refs/cues-using cues :balcony) [0 0])))
    (is (= #{} (refs/cues-using {[9 9] (get-in state [:chains :cue-chains [9 9]])} :balcony))
        "the preview names :balcony, but no cue does")))

(deftest replace-refs-rewrites-a-cells-target-test
  (let [state (assoc-in (show-state) [:config :preview :grid-cells] [{:target nil} {:target :balcony} {:target :right}])]
    (is (= [{:target nil} {:target :left} {:target :right}]
           (get-in (refs/replace-refs state {:balcony :left}) [:config :preview :grid-cells])))
    (is (= {:target nil} (get-in (refs/replace-refs state {:balcony nil}) [:config :preview :grid-cells 1]))
        "cleared: the master view")
    (is (identical? state (refs/replace-refs state {:graphics :left})) "nothing it names: the same state")))

(deftest old-keys-are-no-places-test
  (testing "Z0's keys are neither read nor re-pointed (zones plan Z5, Z4-Q7 A): loading converts them, and an
            old show's Z2 rows re-point them there (state.migration)"
    (let [old {[0 0] {:destination-zone {:zone-group-id :balcony}
                      :items [(preset "p" :zone-group-id :balcony
                                      :effects [{:effect-id :zone-selector :enabled? true :params {:target-zone :balcony}}])]
                      :tracks [{:id "t1" :type :track :zone-group-id :balcony}]}}
          state {:chains {:cue-chains old
                          :effect-chains {[0 0] {:items [{:effect-id :zone-selector :params {:target-zone :balcony}}]}}}
                 :config {:preview {:grid-cells [{:zone-group-id :balcony}]}}}]
      (is (= #{} (refs/cues-using old :balcony)))
      (is (= #{:all} (refs/cue-refs (get old [0 0]))) "no target of its own: All")
      (is (identical? state (refs/replace-refs state {:balcony :left}))))))


;; Zones plan Z4: :target everywhere, the grid's columns, the show default,
;; and the zone maps' lanes and overrides (06 §4.4, SQ-13, spec R5)

(defn- z4-state []
  {:chains {:cue-chains {[0 0] {:target [:balcony :zone/a] :items [(preset "p")]}
                         [1 0] {:items [(preset "p" :target :balcony)
                                        {:id "g" :type :group :target [:balcony :graphics]
                                         :items [(preset "q" :target :zone/b)]}]}
                         [2 0] {:editor-type :timeline
                                :tracks [{:id "f" :type :group :target :balcony :items [{:id "t" :type :track :target [:balcony :zone/c]}]}]
                                :items [(preset "clip" :track-id "t" :target :balcony)]}
                         [3 0] {:target :zone/a :items [(preset "p" :target :graphics)]}}}
   :grid {:size [8 4] :column-targets {2 :balcony 3 [:balcony :zone/d] 4 :zone/a}}
   :config {:default-target :balcony}
   :zone-maps {:map/custom-1 {:name "Crowd sweep" :lanes [:auto/left :balcony :graphics] :overrides {:zone/c :balcony :zone/d :auto/center}}
               :map/custom-2 {:name "Untouched" :lanes [:all] :overrides {}}}})

(deftest z4-places-are-re-targeted-test
  (let [before (z4-state)
        state (refs/replace-refs before {:balcony :auto/right})
        cues (get-in state [:chains :cue-chains])]
    (is (not (names? state :balcony)))
    (testing "a target, inside a union too, in normal form again"
      (is (= [:auto/right :zone/a] (get-in cues [[0 0] :target])))
      (is (= :auto/right (get-in cues [[1 0] :items 0 :target])))
      (is (= [:graphics :auto/right] (get-in cues [[1 0] :items 1 :target])))
      (is (= :auto/right (get-in cues [[2 0] :tracks 0 :target])) "a folder")
      (is (= [:auto/right :zone/c] (get-in cues [[2 0] :tracks 0 :items 0 :target])) "a track")
      (is (= :auto/right (get-in cues [[2 0] :items 0 :target])) "a clip"))
    (testing "a column, the show default, a lane and an override"
      (is (= {2 :auto/right 3 [:auto/right :zone/d] 4 :zone/a} (get-in state [:grid :column-targets])))
      (is (= :auto/right (get-in state [:config :default-target])))
      (is (= [:auto/left :auto/right :graphics] (get-in state [:zone-maps :map/custom-1 :lanes])))
      (is (= {:zone/c :auto/right :zone/d :auto/center} (get-in state [:zone-maps :map/custom-1 :overrides]))))
    (testing "what doesn't name it keeps its identity"
      (is (identical? (get-in before [:chains :cue-chains [3 0]]) (get cues [3 0])))
      (is (identical? (get-in before [:zone-maps :map/custom-2]) (get-in state [:zone-maps :map/custom-2])))
      (is (identical? before (refs/replace-refs before {:nothing :left}))))
    (testing "the cues that name it, by their own data or a union"
      (is (= #{[0 0] [1 0] [2 0]} (refs/cues-using (get-in before [:chains :cue-chains]) :balcony)))
      (is (= #{:balcony :zone/a} (refs/cue-refs (get-in before [:chains :cue-chains [0 0]]))))
      (is (= #{:zone/a :graphics} (refs/cue-refs (get-in before [:chains :cue-chains [3 0]])))
          "a cue with a target of its own names it, not All"))))

(deftest nothing-empties-a-lane-and-drops-an-override-test
  (let [state (refs/replace-refs (z4-state) {:balcony nil})
        cues (get-in state [:chains :cue-chains])]
    (is (not (names? state :balcony)))
    (testing "SQ-13: the lane is left empty, so the zones after it keep their lanes"
      (is (= [:auto/left nil :graphics] (get-in state [:zone-maps :map/custom-1 :lanes]))))
    (testing "the override goes, so its zone wraps again"
      (is (= {:zone/d :auto/center} (get-in state [:zone-maps :map/custom-1 :overrides]))))
    (testing "a target loses the ref, and with none left inherits"
      (is (= :zone/a (get-in cues [[0 0] :target])))
      (is (not (contains? (get-in cues [[1 0] :items 0]) :target)))
      (is (= :graphics (get-in cues [[1 0] :items 1 :target])))
      (is (not (contains? (get-in cues [[2 0] :tracks 0]) :target)))
      (is (= :zone/c (get-in cues [[2 0] :tracks 0 :items 0 :target]))))
    (testing "a column default goes, and the show's is All again"
      (is (= {3 :zone/d 4 :zone/a} (get-in state [:grid :column-targets])))
      (is (nil? (get-in state [:config :default-target]))))))
