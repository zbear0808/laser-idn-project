(ns laser-show.animation.effects-test
  "Tests for effect chain groups functionality."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.animation.effects :as effects]))

(def sample-effect-1
  {:effect-id :scale :params {:x-scale 1.5} :enabled? true})

(def sample-effect-2
  {:effect-id :rotate :params {:angle 45} :enabled? true})


(def sample-group
  {:type :group
   :id "group-1"
   :name "Test Group"
   :collapsed? false
   :enabled? true
   :items [sample-effect-1 sample-effect-2]})


(deftest effect?-test
  (testing "identifies effects correctly"
    (is (effects/effect? sample-effect-1))
    (is (effects/effect? {:effect-id :scale}))
    (is (effects/effect? {:type :effect :effect-id :scale})))

  (testing "returns false for groups"
    (is (not (effects/effect? sample-group)))
    (is (not (effects/effect? {:type :group :items []})))))

