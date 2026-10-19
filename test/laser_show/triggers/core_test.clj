(ns laser-show.triggers.core-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.triggers.core :as core]
            [laser-show.triggers.definitions :as definitions]))

(deftest dispatches-matching-definition
  (let [definition (merge (definitions/trigger :cue {:type :go})
                          {:initial-state :idle
                           :transitions {:idle [{:match {:type :go}
                                                 :to :active
                                                 :emit {:action :play}}]}})
        result (core/dispatch (core/make [definition]) {:type :go})]
    (is (= [{:trigger-id :cue :effect {:action :play}}]
           (:effects result)))
    (is (= :active (get-in result [:system :machines :cue :state])))))
