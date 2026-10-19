(ns laser-show.triggers.matcher-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.triggers.matcher :as matcher]))

(deftest supports-wildcards-predicates-and-nested-maps
  (let [event {:type :note-on :channel 2 :data {:velocity 0.8}}]
    (is (matcher/matches? event {:type :note-on :channel :*}))
    (is (matcher/matches? event {:data {:velocity #(> % 0.5)}}))
    (is (not (matcher/matches? event {:channel 1})))))
