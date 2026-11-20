(ns laser-show.views.components.zone-chips-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.components.zone-chips :as zone-chips]))

(deftest zone-options-list-all-once-test
  (let [groups [{:id :all :name "All" :color "#808080"} {:id :left :name "Left" :color "#4A90D9"}]]
    (is (= [:all :left] (mapv :id (zone-chips/zone-options groups)))
        "All is listed once when the groups have it")
    (is (= "#808080" (:color (first (zone-chips/zone-options groups)))) "the group's own All"))
  (is (= [:all :left] (mapv :id (zone-chips/zone-options [{:id :left :name "Left"}])))
      "a show without an All group still offers All, first")
  (is (= [:all] (mapv :id (zone-chips/zone-options [])))))
