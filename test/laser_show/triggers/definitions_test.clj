(ns laser-show.triggers.definitions-test
  (:require [clojure.test :refer [deftest is]]
            [laser-show.triggers.definitions :as definitions]))

(deftest constructors-and-validation
  (let [definition (definitions/trigger :cue {:type :go})]
    (is (definitions/validate definition))
    (is (definitions/enabled? definition))
    (is (not (definitions/enabled? (assoc definition :enabled? false))))))
