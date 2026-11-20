(ns laser-show.routing.refs-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.routing.refs :as refs]))

(defn- selector
  ([zone] (selector zone true))
  ([zone enabled?]
   {:effect-id :zone-selector :enabled? enabled? :params {:target-zone zone}}))

(defn- preset [id & {:as fields}]
  (merge {:id id :type :preset :preset-id :circle :enabled? true :params {} :effects []} fields))

(def ^:private one-per-place
  "One cue per place a zone group can be named, each naming :balcony there only."
  {[0 0] {:destination-zone {:zone-group-id :balcony} :items [(preset "p")]}
   [1 0] {:items [(preset "p" :zone-group-id :balcony)]}
   [2 0] {:items [{:id "g" :type :group :zone-group-id :balcony :items [(preset "p")]}]}
   [3 0] {:items [{:id "g" :type :group :items [(preset "p" :zone-group-id :balcony)]}]}
   [4 0] {:items [(preset "p" :effects [(selector :balcony)])]}
   [5 0] {:items [(preset "p" :effects [(selector :balcony false)])]}
   [6 0] {:items [(preset "p" :effects [{:effect-id :zone-selector :enabled? true
                                         :params {:target-zone :left}
                                         :keyframe-modulator {:enabled? true
                                                              :keyframes [{:position 0.0 :params {:target-zone :left}}
                                                                          {:position 0.5 :params {:target-zone :balcony}}]}}])]}
   [7 0] {:items [(preset "p" :effects [{:id "fx-group" :type :group
                                         :items [(selector :balcony)]}])]}
   [0 1] {:editor-type :timeline
          :tracks [{:id "t1" :type :track :zone-group-id :balcony}]
          :items [(preset "clip" :track-id "t1")]}
   [1 1] {:editor-type :timeline
          :tracks [{:id "f1" :type :group :zone-group-id :balcony
                    :items [{:id "t1" :type :track}]}]
          :items [(preset "clip" :track-id "t1")]}
   [2 1] {:editor-type :timeline
          :tracks [{:id "t1" :type :track :effects [(selector :balcony)]}]
          :items [(preset "clip" :track-id "t1")]}
   [3 1] {:editor-type :timeline
          :tracks [{:id "t1" :type :track}]
          :items [{:id "gclip" :type :group :track-id "t1"
                   :items [(preset "p" :zone-group-id :balcony)]}]}})

(defn- names?
  "Does x name kw anywhere?"
  [x kw]
  (boolean (some #{kw} (tree-seq coll? seq x))))

(defn- show-state []
  {:chains {:cue-chains (assoc one-per-place [9 9] {:destination-zone {:zone-group-id :left}
                                                    :items [(preset "other" :zone-group-id :right)]})
            :effect-chains {[0 0] {:items [(selector :balcony)]}
                            [1 0] {:items [(selector :left)]}}}
   :config {:preview {:zone-group-filter :balcony
                      :grid-cells [{:zone-group-id nil} {:zone-group-id :balcony} {:zone-group-id :right}]}}
   :projectors {:p1 {:zone-groups [:all :balcony]}}
   :virtual-projectors {:vp {:zone-groups [:balcony]}}})

(deftest cues-using-finds-every-place-test
  (is (= (set (keys one-per-place)) (refs/cues-using one-per-place :balcony)))
  (testing "sorted by cell"
    (is (= (sort (keys one-per-place)) (seq (refs/cues-using one-per-place :balcony)))))
  (testing "each place on its own"
    (doseq [[cell cue] one-per-place]
      (is (contains? (refs/cue-refs cue) :balcony) (str cell)))))

(deftest cue-without-destination-names-all-test
  (is (contains? (refs/cue-refs {:items []}) :all))
  (is (contains? (refs/cue-refs {:destination-zone {} :items []}) :all))
  (is (not (contains? (refs/cue-refs {:destination-zone {:zone-group-id :left} :items []}) :all)))
  (is (= #{[0 0]} (refs/cues-using {[0 0] {:items []} [1 0] nil} :all))))

(deftest replace-refs-rewrites-every-place-test
  (let [state (refs/replace-refs (show-state) {:balcony :left})]
    (is (not (names? (:chains state) :balcony)))
    (is (not (names? (get-in state [:config :preview]) :balcony)))
    (is (= (conj (set (keys one-per-place)) [9 9])
           (refs/cues-using (get-in state [:chains :cue-chains]) :left))
        "every cue that named :balcony now names :left, beside [9 9], which already did")
    (is (= :left (get-in state [:chains :cue-chains [0 0] :destination-zone :zone-group-id])))
    (is (= :left (get-in state [:chains :cue-chains [6 0] :items 0 :effects 0
                                :keyframe-modulator :keyframes 1 :params :target-zone])))
    (is (= :left (get-in state [:chains :cue-chains [7 0] :items 0 :effects 0 :items 0 :params :target-zone])))
    (is (= :left (get-in state [:chains :effect-chains [0 0] :items 0 :params :target-zone])))
    (is (= [{:zone-group-id nil} {:zone-group-id :left} {:zone-group-id :right}]
           (get-in state [:config :preview :grid-cells])))
    (is (= :left (get-in state [:config :preview :zone-group-filter])))))

(deftest replace-refs-to-nil-inherits-test
  (let [state (refs/replace-refs (show-state) {:balcony nil})
        cues (get-in state [:chains :cue-chains])]
    (is (not (names? (:chains state) :balcony)))
    (testing "the cue falls back to All"
      (is (not (contains? (get cues [0 0]) :destination-zone))))
    (testing "items, groups, tracks and folders inherit"
      (is (not (contains? (get-in cues [[1 0] :items 0]) :zone-group-id)))
      (is (not (contains? (get-in cues [[2 0] :items 0]) :zone-group-id)))
      (is (not (contains? (get-in cues [[3 0] :items 0 :items 0]) :zone-group-id)))
      (is (not (contains? (get-in cues [[0 1] :tracks 0]) :zone-group-id)))
      (is (not (contains? (get-in cues [[1 1] :tracks 0]) :zone-group-id)))
      (is (not (contains? (get-in cues [[3 1] :items 0 :items 0]) :zone-group-id))))
    (testing "a Zone Selector says All"
      (is (= :all (get-in cues [[4 0] :items 0 :effects 0 :params :target-zone])))
      (is (= :all (get-in cues [[6 0] :items 0 :effects 0 :keyframe-modulator :keyframes 1 :params :target-zone]))))
    (testing "the preview cell shows the master view, the filter All"
      (is (= {:zone-group-id nil} (get-in state [:config :preview :grid-cells 1])))
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
        (is (identical? (get-in state [:chains :effect-chains [1 0]])
                        (get-in after [:chains :effect-chains [1 0]])))))))

(deftest effects-grid-and-preview-dont-count-as-cues-test
  (let [state (show-state)
        cues (dissoc (get-in state [:chains :cue-chains]) [0 0])]
    (is (not (contains? (refs/cues-using cues :balcony) [0 0])))
    (is (= #{} (refs/cues-using {[9 9] (get-in state [:chains :cue-chains [9 9]])} :balcony))
        "the effects grid and the preview name :balcony, but no cue does")))
