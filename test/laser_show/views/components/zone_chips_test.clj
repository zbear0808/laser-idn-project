(ns laser-show.views.components.zone-chips-test
  (:require [clj-font-awesome.core :as fa]
            [clojure.test :refer [deftest is]]
            [laser-show.views.components.zone-chips :as zone-chips]))

(deftest zone-options-list-all-once-test
  (let [groups [{:id :all :name "All" :color "#808080"} {:id :left :name "Left" :color "#4A90D9"}]]
    (is (= [:all :left] (mapv :id (zone-chips/zone-options groups)))
        "All is listed once when the groups have it")
    (is (= "#808080" (:color (first (zone-chips/zone-options groups)))) "the group's own All"))
  (is (= [:all :left] (mapv :id (zone-chips/zone-options [{:id :left :name "Left"}])))
      "a show without an All group still offers All, first")
  (is (= [:all] (mapv :id (zone-chips/zone-options [])))))

(deftest built-in-groups-show-a-lock-test
  (is (every? zone-chips/locked? [{:kind :all} {:kind :auto} {:kind :laser}]))
  (is (not-any? zone-chips/locked? [{:kind :custom} {:id :inherit :name "Inherit"} nil]))
  (is (= (fa/unicode! :lock :solid) (get-in (zone-chips/with-lock {:text "Left"} {:id :auto/left :kind :auto}) [:graphic :text])))
  (is (= {:text "Graphics"} (zone-chips/with-lock {:text "Graphics"} {:id :graphics :kind :custom}))
      "no :graphic at all for your groups: a menu item can't take a nil one"))
