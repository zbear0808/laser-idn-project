(ns laser-show.triggers.definitions
  "Constructors and validation for internal trigger definitions."
  (:refer-clojure :exclude [sequence]))

(defn trigger
  ([id match] (trigger id match {}))
  ([id match options]
   (merge {:id id :match match :enabled? true} options)))

(defn sequence
  ([id steps] (sequence id steps {}))
  ([id steps options]
   (merge {:id id :type :sequence :steps (vec steps) :enabled? true} options)))

(defn validate
  [definition]
  (and (map? definition)
       (some? (:id definition))
       (or (= :sequence (:type definition))
           (contains? definition :match))))

(defn enabled? [definition]
  (not (false? (:enabled? definition))))
