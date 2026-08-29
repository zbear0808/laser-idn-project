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
                   :collapsed? false
                   :items [{:id "t2"
                            :type :track} ;; Should inherit :z2
                           {:id "t3"
                            :type :track
                            :zone-group-id :z3}]} ;; Should override to :z3
                  {:id "g2"
                   :type :group
                   :zone-group-id :z4
                   :collapsed? true
                   :items [{:id "t4"
                            :type :track}]}] ;; Children omitted, but group row remains
          result (tl/flatten-visible-tracks tracks)]

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
