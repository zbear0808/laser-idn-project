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
