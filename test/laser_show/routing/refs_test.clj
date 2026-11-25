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


;; Zone patterns' own fields (plans/2026-11-23-cljfx-reactivity/28): the
;; places, the source and the lit steps of every stored parameter map, on a
;; cue and in an effect cell

(def ^:private pattern-sites #'refs/pattern-ref-sites)
(def ^:private pattern-refs #'refs/pattern-refs)
(def ^:private map-pattern-refs #'refs/map-pattern-refs)

(def ^:private cue-grid
  "28 §7.1's cue pattern: a step grid that is off, from Laser A, its rows a
   zone and Laser A, a rest first."
  {:effect-id :zone-pattern :enabled? false
   :params {:style :steps :from :auto.laser/a
            :zones [:zone/c :auto.laser/a]
            :steps [[] [:zone/c :auto.laser/a]]}})

(def ^:private cue-chase
  "A second pattern on the same cue: Every laser, then Laser A, from what
   the cue draws."
  {:effect-id :zone-pattern :params {:style :chase :zones [:lane/lasers :auto.laser/a] :from :any}})

(def ^:private cell-items
  "28 §7.1's effect cell: its pattern in an effect group that is off, a
   source and two sparse keyframes that are off; then Laser A's literal
   where nothing stores a place (a point effect's metadata, a Map cycle's
   maps, an instance id), and a pattern whose only reference to it is a
   source in a keyframe that is off."
  [{:type :group :enabled? false
    :items [{:effect-id :zone-pattern :params {:from :auto.laser/a}
             :keyframe-modulator {:enabled? false
                                  :keyframes [{:position 0.0 :params {:zones [:auto.laser/a]}}
                                              {:position 1.0 :params {:steps [[:auto.laser/a] []]}}]}}]}
   {:effect-id :scale :params {:x-scale 1.0} :vendor/note :auto.laser/a}
   {:effect-id :zone-map-cycle :params {:maps [:auto.laser/a :map/odd-even]}}
   {:effect-id :zone-pattern :id :auto.laser/a :params {:style :alternate :step-on :hit :zones [:zone/d]}
    :keyframe-modulator {:enabled? false :keyframes [{:position 0.0 :params {:from :auto.laser/a}}]}}])

(deftest stored-pattern-refs-have-the-same-read-and-write-sites-test
  (let [cue-patterns [cue-grid cue-chase]]
    (testing "every stored place, source and lit step, by its path from the chain's root"
      (is (= #{{:path [0 :params :zones 0] :role :lane :ref :zone/c}
               {:path [0 :params :zones 1] :role :lane :ref :auto.laser/a}
               {:path [0 :params :from] :role :source :ref :auto.laser/a}
               {:path [0 :params :steps 1 0] :role :lit :ref :zone/c}
               {:path [0 :params :steps 1 1] :role :lit :ref :auto.laser/a}
               {:path [1 :params :zones 1] :role :lane :ref :auto.laser/a}}
             (set (pattern-sites cue-patterns)))
          "a pattern that is off counts; Every laser and Everything the cue draws are no refs")
      (is (= #{{:path [0 :items 0 :params :from] :role :source :ref :auto.laser/a}
               {:path [0 :items 0 :keyframe-modulator :keyframes 0 :params :zones 0] :role :lane :ref :auto.laser/a}
               {:path [0 :items 0 :keyframe-modulator :keyframes 1 :params :steps 0 0] :role :lit :ref :auto.laser/a}
               {:path [3 :params :zones 0] :role :lane :ref :zone/d}
               {:path [3 :keyframe-modulator :keyframes 0 :params :from] :role :source :ref :auto.laser/a}}
             (set (pattern-sites cell-items)))
          "through a group that is off, and every keyframe, on or off"))
    (testing "the literal elsewhere is no site"
      (is (= #{} (pattern-refs (subvec cell-items 1 3))) "a point effect's metadata, a Map cycle's maps")
      (is (= #{:zone/d} (pattern-refs [(dissoc (cell-items 3) :keyframe-modulator)])) "an instance id, a style, a step source")
      (is (= #{} (pattern-refs [{:effect-id :scale :params {:zones [:auto.laser/a] :from :auto.laser/a}}]))
          "another effect's params with a pattern's keys")
      (is (= #{} (pattern-refs [{:type :preset :preset-id :circle :effects [cue-grid]}])) "a cue item's point effects"))
    (testing "the rewrite touches the same sites, and only them"
      (let [cue' (map-pattern-refs cue-patterns {:auto.laser/a :auto.laser/b})
            cells' (map-pattern-refs cell-items {:auto.laser/a :auto.laser/b})
            moved (fn [sites] (set (map #(update % :ref {:auto.laser/a :auto.laser/b} (:ref %)) sites)))]
        (is (= (moved (pattern-sites cue-patterns)) (set (pattern-sites cue'))))
        (is (= (moved (pattern-sites cell-items)) (set (pattern-sites cells'))))
        (is (= [{:position 0.0 :params {:zones [:auto.laser/b]}} {:position 1.0 :params {:steps [[:auto.laser/b] []]}}]
               (get-in cells' [0 :items 0 :keyframe-modulator :keyframes])))
        (is (= {:from :auto.laser/b} (get-in cells' [0 :items 0 :params])))
        (is (= [(cell-items 1) (cell-items 2)] (subvec cells' 1 3)))
        (is (every? true? (map identical? (subvec cell-items 1 3) (subvec cells' 1 3)))
            "the point effect and the Map cycle keep their identity, the literal in them too")
        (is (= :auto.laser/a (get-in cells' [3 :id])))))
    (testing "the owners: a cue's patterns are the cue's, an effect cell's pattern its cell's"
      (let [cues {[2 0] {:items [] :zone-patterns cue-patterns}
                  [5 5] {:target :graphics :items []}}
            cells {[2 0] {:active? false :items cell-items}
                   [0 3] {:active? true :items [{:effect-id :scale :params {}}]}}]
        (is (= {:all {:cues #{[2 0]} :effect-cells #{}}
                :graphics {:cues #{[5 5]} :effect-cells #{}}
                :zone/c {:cues #{[2 0]} :effect-cells #{}}
                :auto.laser/a {:cues #{[2 0]} :effect-cells #{[2 0]}}
                :zone/d {:cues #{} :effect-cells #{[2 0]}}}
               (refs/chain-ref-usage cues cells))
            "a cue with no target of its own still names All, as cue-refs says")
        (is (= #{[2 0]} (refs/cues-using cues :auto.laser/a)) "cues-using: cues only")
        (is (= #{[2 0]} (refs/effect-cells-using cells :auto.laser/a)))
        (is (= #{} (refs/effect-cells-using cells :graphics)))
        (is (contains? (refs/cue-refs (get cues [2 0])) :auto.laser/a))))
    (testing "a show: re-pointed in both grids, everything else as it was"
      (let [state {:chains {:cue-chains {[2 0] {:items [] :zone-patterns cue-patterns}
                                         [5 5] {:target :graphics :items []}}
                            :effect-chains {[2 0] {:active? false :items cell-items}
                                            [0 3] {:active? true :items [{:effect-id :scale :params {}}]}}}}
            after (refs/replace-refs state {:auto.laser/a :auto.laser/b})]
        (is (not (names? (get-in after [:chains :cue-chains [2 0] :zone-patterns]) :auto.laser/a)))
        (is (= [:zone/c :auto.laser/b] (get-in after [:chains :cue-chains [2 0] :zone-patterns 0 :params :zones])))
        (is (= :auto.laser/b (get-in after [:chains :effect-chains [2 0] :items 3 :keyframe-modulator :keyframes 0 :params :from])))
        (is (identical? (get-in state [:chains :cue-chains [5 5]]) (get-in after [:chains :cue-chains [5 5]])))
        (is (identical? (get-in state [:chains :effect-chains [0 3]]) (get-in after [:chains :effect-chains [0 3]])))
        (is (identical? (get-in state [:chains :cue-chains [2 0] :items]) (get-in after [:chains :cue-chains [2 0] :items])))
        (is (false? (get-in after [:chains :effect-chains [2 0] :active?])) "nothing is switched on")
        (is (identical? state (refs/replace-refs state {:auto.laser/zz :auto.laser/b})) "nothing names it: the same state")))))

(defn- pattern [params] {:effect-id :zone-pattern :params params})

(defn- params-after
  "params as map-pattern-refs leaves them under replacements."
  [params replacements]
  (:params (first (map-pattern-refs [(pattern params)] replacements))))

(deftest pattern-replacements-preserve-order-rests-and-sparse-params-test
  (testing "28 §7.2: a swap, one lookup per original ref"
    (let [swap {:auto.laser/a :auto.laser/b :auto.laser/b :auto.laser/a}
          grid {:style :steps :zones [:auto.laser/a :auto.laser/b] :steps [[:auto.laser/b] [] [:auto.laser/a]]}]
      (is (= {:style :steps :zones [:auto.laser/b :auto.laser/a] :steps [[:auto.laser/a] [] [:auto.laser/b]]}
             (params-after grid swap)))
      (is (= grid (params-after (params-after grid swap) swap)) "applied twice, it swaps back: not idempotent")))
  (testing "28 §7.3: merged and cleared, the rhythm kept"
    (let [after (params-after {:zones [:group-old :zone/c :group-kept :group-gone] :from :group-gone
                               :steps [[:zone/c :group-kept] [:group-old :group-gone] [:group-gone] []]}
                              {:group-old :group-kept :group-gone nil})]
      (is (= {:zones [:group-kept :zone/c] :steps [[:group-kept :zone/c] [:group-kept] [] []]} after)
          "the merged row at the first place it had, its lights once a step, in the rows' new order; rests and length kept")
      (is (not (contains? after :from)) "a cleared source goes: the default, All")))
  (testing "many refs onto one"
    (is (= {:zones [:k :zone/c] :steps [[:k] [:k :zone/c] []]}
           (params-after {:zones [:x :zone/c :y] :steps [[:y :x] [:zone/c :y] []]} {:x :k :y :k}))))
  (testing "a style that isn't Steps keeps its grid, and its other params"
    (is (= {:style :chase :rate 2.0 :zones [:group-new :zone/c] :steps [[:group-new :zone/c] []]}
           (params-after {:style :chase :rate 2.0 :zones [:group-old :zone/c] :steps [[:group-old :zone/c] []]}
                         {:group-old :group-new}))))
  (testing "the last row cleared: an empty lane, not none, and every step a rest"
    (let [after (params-after {:zones [:a] :from :a :steps [[:a] []]} {:a nil})]
      (is (= {:zones [] :steps [[] []]} after))
      (is (contains? after :zones))))
  (testing "a sparse keyframe: no lane to put its steps in order by, none added"
    (is (= {:steps [[:zone/c] [] []]}
           (params-after {:steps [[:zone/c] [:auto.laser/gone] []]} {:auto.laser/gone nil})))
    (is (= {:steps [[:b :c]]} (params-after {:steps [[:b :a]]} {:a :c})) "its steps' order as it was"))
  (testing "what isn't a ref stays as it is"
    (let [wave {:type :sine :min 0.0 :max 1.0}
          after (params-after {:from wave :zones [:a {:not :a}] :steps [{:a 1} [:a]]} {:a :b})]
      (is (= {:from wave :zones [:b {:not :a}] :steps [{:a 1} [:b]]} after))
      (is (identical? wave (:from after)) "a map standing for a value"))
    (is (= {:zones [:lane/lasers :b] :from :any}
           (params-after {:zones [:lane/lasers :a] :from :any} {:a :b :lane/lasers :x :any :y}))
        "Every laser and Everything the cue draws are tokens, never re-pointed"))
  (testing "a list stays a list"
    (let [after (params-after {:zones '(:a :zone/c) :steps '((:a) ())} {:a :b})]
      (is (= '(:b :zone/c) (:zones after)))
      (is (list? (:zones after)))
      (is (list? (:steps after)))
      (is (list? (first (:steps after))))))
  (testing "nothing to do: the same items"
    (let [items [(pattern {:zones [:a :a] :steps [[:a] []] :from :a})]]
      (is (identical? items (map-pattern-refs items {})))
      (is (identical? items (map-pattern-refs items {:nothing :b})))
      (is (identical? items (map-pattern-refs items {:a :a})) "a ref onto itself")))
  (testing "no invented refs"
    (is (= #{} (pattern-refs [(pattern {:style :swap})])))
    (is (= #{} (pattern-refs [(pattern {:zones [:lane/lasers] :from :any})])))
    (is (= #{:all} (pattern-refs [(pattern {:from :all})])) "an explicit All is one")
    (let [state {:chains {:cue-chains {[0 0] {:items [] :zone-patterns [(pattern {:style :swap})]}}}}]
      (is (identical? state (refs/replace-refs state {:all :zone/a}))
          "the cue's implicit All is read only: no target, and no source, is put in"))))


;; Timeline shows ([:chains :shows], plans/2026-11-25-timeline-shows 04 §4.6)

(def ^:private show-a #uuid "00000000-0000-0000-0000-00000000000a")
(def ^:private show-b #uuid "00000000-0000-0000-0000-00000000000b")

(defn- shows-state []
  {:chains {:cue-chains {[0 0] {:target :left :items [(preset "p")]}}
            :shows {show-a {:editor-type :timeline
                            :target :graphics
                            :tracks [{:id "t1" :type :track :target :graphics}
                                     {:id "t2" :type :track}]
                            :items [(preset "clip" :track-id "t1")
                                    (preset "clip2" :track-id "t2" :target :graphics)]}
                    show-b {:editor-type :timeline
                            :target :left
                            :tracks [{:id "t1" :type :track}]
                            :items [(preset "clip" :track-id "t1")]}}}})

(deftest shows-are-a-ref-place-test
  (let [state (shows-state)
        after (refs/replace-refs state {:graphics :crowd})
        show (get-in after [:chains :shows show-a])]
    (testing "a show's :target, a track's and a clip's are re-pointed"
      (is (= :crowd (:target show)))
      (is (= :crowd (get-in show [:tracks 0 :target])))
      (is (= :crowd (get-in show [:items 1 :target])))
      (is (not (names? (get-in after [:chains :shows]) :graphics))))
    (testing "what names nothing keeps its identity"
      (is (identical? (get-in state [:chains :shows show-b]) (get-in after [:chains :shows show-b])))
      (is (identical? (get-in state [:chains :shows show-a :items 0]) (get-in show [:items 0])))
      (is (identical? (get-in state [:chains :cue-chains]) (get-in after [:chains :cue-chains]))))
    (testing "cleared: the show's levels inherit"
      (let [cleared (get-in (refs/replace-refs state {:graphics nil}) [:chains :shows show-a])]
        (is (not (contains? cleared :target)))
        (is (not (contains? (get-in cleared [:tracks 0]) :target)))
        (is (not (contains? (get-in cleared [:items 1]) :target)))))
    (testing "no ref named: the same state"
      (is (identical? state (refs/replace-refs state {:nothing-names-this :crowd}))))
    (testing "no shows, or none stored: fine"
      (let [no-key {:chains {:cue-chains {[0 0] {:target :graphics :items []}}}}
            nil-shows (assoc-in no-key [:chains :shows] nil)]
        (is (= :crowd (get-in (refs/replace-refs no-key {:graphics :crowd}) [:chains :cue-chains [0 0] :target])))
        (is (not (contains? (:chains (refs/replace-refs no-key {:graphics :crowd})) :shows)))
        (is (nil? (get-in (refs/replace-refs nil-shows {:graphics :crowd}) [:chains :shows])))
        (is (identical? no-key (refs/replace-refs no-key {:nothing :crowd})))))))

(deftest shows-using-test
  (let [shows (get-in (shows-state) [:chains :shows])]
    (is (= #{show-a} (refs/shows-using shows :graphics)))
    (is (= #{show-b} (refs/shows-using shows :left)))
    (is (sorted? (refs/shows-using shows :left)))
    (is (= #{} (refs/shows-using shows :crowd)))
    (is (= #{show-a} (refs/shows-using {show-a {:items []} show-b nil} :all))
        "a show with no target of its own names All, as a cue; an absent show none")
    (is (= #{} (refs/shows-using nil :graphics)))))

(deftest chain-ref-usage-with-shows-test
  (let [{:keys [chains]} (shows-state)
        cells {[1 1] {:items [{:effect-id :zone-pattern :params {:zones [:graphics]}}]}}]
    (is (= {:left {:cues #{[0 0]} :effect-cells #{} :shows #{show-b}}
            :graphics {:cues #{} :effect-cells #{[1 1]} :shows #{show-a}}}
           (refs/chain-ref-usage (:cue-chains chains) cells (:shows chains))))
    (is (= {:left {:cues #{[0 0]} :effect-cells #{}}
            :graphics {:cues #{} :effect-cells #{[1 1]}}}
           (refs/chain-ref-usage (:cue-chains chains) cells))
        "two grids only: the entries as before")
    (is (= {:left {:cues #{[0 0]} :effect-cells #{} :shows #{}}}
           (refs/chain-ref-usage (:cue-chains chains) nil nil))
        "no shows: :shows there, empty")))
