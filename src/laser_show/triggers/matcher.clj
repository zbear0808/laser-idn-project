(ns laser-show.triggers.matcher
  "Matching helpers for internal trigger events."
  (:require [clojure.walk :as walk]))

(defn- value-matches? [actual expected]
  (cond
    (= expected :*) true
    (fn? expected) (boolean (expected actual))
    (set? expected) (contains? expected actual)
    (and (map? expected) (map? actual))
    (every? (fn [[k v]] (value-matches? (get actual k) v)) expected)
    :else (= actual expected)))

(defn matches?
  "Returns true when event satisfies pattern. :* and predicate values are supported."
  [event pattern]
  (value-matches? event pattern))

(defn matching
  [event definitions]
  (filter #(matches? event (:match %)) definitions))

(defn normalize
  "Converts a shorthand keyword/string matcher into a trigger pattern."
  [matcher]
  (if (map? matcher) matcher {:type :trigger :id matcher}))
