(ns laser-show.views.components.visual-editors.timeline.track-logic-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.views.components.visual-editors.timeline.track-logic :as tl]))

(deftest track-group?-test
  (testing "identifies groups correctly"
    (is (tl/track-group? {:type :group}))
    (is (tl/track-group? {:type :track :items []}))
    (is (not (tl/track-group? {:type :track})))))

(deftest flatten-visible-tracks-test
  (testing "flattens and inherits zones"
    (let [tracks [{:id "t1"
                   :type :track
                   :zone-group-id :z1}
                  {:id "g1"
                   :type :group
                   :zone-group-id :z2
                   :items [{:id "t2"
                            :type :track} ;; Should inherit :z2
                           {:id "t3"
                            :type :track
                            :zone-group-id :z3}]} ;; Should override to :z3
                  {:id "g2"
                   :type :group
                   :zone-group-id :z4
                   :items [{:id "t4"
                            :type :track}]}] ;; Children omitted, but group row remains
          expanded-tracks #{"g1"}
          result (tl/flatten-visible-tracks tracks expanded-tracks)]

      (is (= 5 (count result)) "t1, g1, t2, t3, g2")
      (is (= "t1" (:id (nth result 0))))
      (is (= "g1" (:id (nth result 1))))
      (is (= :z2 (:zone-group-id (nth result 1))))
      (is (= "t2" (:id (nth result 2))))
      (is (= :z2 (:zone-group-id (nth result 2))))
      (is (= "t3" (:id (nth result 3))))
      (is (= :z3 (:zone-group-id (nth result 3))))
      (is (= "g2" (:id (nth result 4))))
      (is (= :z4 (:zone-group-id (nth result 4)))))))

(deftest first-leaf-track-test
  (testing "finds the first non-group track at any depth"
    (let [tracks [{:id "g1"
                   :type :group
                   :items []} ;; Empty group, should skip
                  {:id "g2"
                   :type :group
                   :items [{:id "g3"
                            :type :group
                            :items [{:id "t1" :type :track}
                                    {:id "t2" :type :track}]}]}
                  {:id "t3" :type :track}]]
      (is (= "t1" (:id (tl/first-leaf-track tracks))))
      (is (nil? (tl/first-leaf-track [])))
      (is (nil? (tl/first-leaf-track [{:id "g" :type :group :items []}]))))))

(deftest ensure-leaf-track-test
  (testing "resolves candidate track or falls back to first leaf track"
    (let [tracks [{:id "g1"
                   :type :group
                   :items [{:id "t1" :type :track}
                           {:id "t2" :type :track}]}
                  {:id "t3" :type :track}]]
      (is (= "t1" (tl/ensure-leaf-track tracks "t1")) "Already leaf track")
      (is (= "t1" (tl/ensure-leaf-track tracks "g1")) "Folder resolves to child track")
      (is (= "t3" (tl/ensure-leaf-track tracks "t3")) "Other leaf track")
      (is (= "t1" (tl/ensure-leaf-track tracks nil)) "Nil resolves to first leaf track")
      (is (= "t1" (tl/ensure-leaf-track tracks "unknown")) "Unknown resolves to first leaf track"))))

(deftest auto-initialize-tracks-test
  (testing "creates zone-group folders with child tracks"
    (let [zone-groups {:all {:name "All" :color "#FFFFFF"}
                       :front {:name "Front" :color "#FF0000"}
                       :back {:name "Back" :color "#00FF00"}}
          cue-chain {:destination-zone {:zone-group-id :all}
                     :items [{:id "item1"
                              :effects []}
                             {:id "item2"
                              :effects [{:effect-id :zone-selector
                                         :params {:target-zone :front}}]}
                             {:id "item3"
                              :effects [{:effect-id :zone-selector
                                         :params {:target-zone :front}}]}]}
          result (tl/auto-initialize-tracks cue-chain zone-groups)
          tracks (:tracks result)
          items (:items result)]

      ;; 1. Check folder structure
      (is (= 2 (count tracks)) "Should create 2 folders: :all (default) and :front")
      (let [all-folder (first (filter #(= :all (:zone-group-id %)) tracks))
            front-folder (first (filter #(= :front (:zone-group-id %)) tracks))]

        (is (= :group (:type all-folder)))
        (is (= "All" (:name all-folder)))
        (is (= 1 (count (:items all-folder))) "Should have one child track")
        (is (= :track (:type (first (:items all-folder)))))
        (is (= :all (:zone-group-id (first (:items all-folder)))))

        (is (= :group (:type front-folder)))
        (is (= 1 (count (:items front-folder))))
        (is (= :track (:type (first (:items front-folder)))))
        (is (= :front (:zone-group-id (first (:items front-folder))))))

      ;; 2. Check item routing to the child tracks
      (let [all-track-id (:id (first (:items (first (filter #(= :all (:zone-group-id %)) tracks)))))
            front-track-id (:id (first (:items (first (filter #(= :front (:zone-group-id %)) tracks)))))]

        (is (= all-track-id (:track-id (nth items 0))) "Item 1 goes to default zone child track")
        (is (= front-track-id (:track-id (nth items 1))) "Item 2 goes to front zone child track")
        (is (= front-track-id (:track-id (nth items 2))) "Item 3 goes to front zone child track")))))

(deftest droppable-track?-test
  (testing "validates leaf track rows with and without clips"
    (is (tl/droppable-track? {:id "t1" :type :track :items []}))
    (is (tl/droppable-track? {:id "t1" :type :track :items [{:id "c1" :timeline/start 0.0}]}))
    (is (tl/droppable-track? {:id "t1" :type :track :track {:id "t1" :type :track} :items [{:id "c1"}]})))

  (testing "rejects group folders"
    (is (not (tl/droppable-track? {:id "g1" :type :group :items []})))
    (is (not (tl/droppable-track? {:id "g1" :type :group :track {:id "g1" :type :group :items []} :items []}))))

  (testing "rejects unassigned synthetic rows and invalid inputs"
    (is (not (tl/droppable-track? {:id :laser-show.views.components.visual-editors.timeline.track-logic/unassigned
                                   :type :track
                                   :track nil
                                   :items []})))
    (is (not (tl/droppable-track? nil)))
    (is (not (tl/droppable-track? {})))))

(deftest resolve-active-track-test
  (testing "resolves leaf track or falls back according to hierarchy"
    (let [tracks [{:id "g1"
                   :type :group
                   :items [{:id "t1" :type :track}
                           {:id "t2" :type :track}]}
                  {:id "t3" :type :track}]]
      (is (= "t2" (tl/resolve-active-track {:track-defs tracks :selected-track-id "t2"})))
      (is (= "t1" (tl/resolve-active-track {:track-defs tracks :selected-track-id "g1"})))
      (is (= "t1" (tl/resolve-active-track {:track-defs tracks :selected-track-id nil})))
      (is (= "t1" (tl/resolve-active-track {:track-defs tracks :selected-track-id "deleted-id"})))
      (is (nil? (tl/resolve-active-track {:track-defs [] :selected-track-id "t1"}))))))

(deftest track-at-y-test
  (testing "calculates track row and index from y coordinate"
    (let [tracks [{:id "t1" :type :track}
                  {:id "t2" :type :track}
                  {:id "t3" :type :track}]]
      ;; In ruler
      (is (nil? (tl/track-at-y {:y 10 :ruler-height 24 :track-height 32 :track-rows tracks})))
      ;; First track lane (y between 24 and 56)
      (let [res (tl/track-at-y {:y 30 :ruler-height 24 :track-height 32 :track-rows tracks})]
        (is (= 0 (:track-idx res)))
        (is (= "t1" (:id (:track res)))))
      ;; Second track lane (y between 56 and 88)
      (let [res (tl/track-at-y {:y 60 :ruler-height 24 :track-height 32 :track-rows tracks})]
        (is (= 1 (:track-idx res)))
        (is (= "t2" (:id (:track res)))))
      ;; Out of bounds below tracks
      (is (nil? (tl/track-at-y {:y 200 :ruler-height 24 :track-height 32 :track-rows tracks})))
      ;; Nil input
      (is (nil? (tl/track-at-y {:y nil :track-rows tracks}))))))


