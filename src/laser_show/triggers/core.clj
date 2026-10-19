(ns laser-show.triggers.core
  "Pure orchestration for internal trigger definitions."
  (:require [laser-show.triggers.definitions :as definitions]
            [laser-show.triggers.matcher :as matcher]
            [laser-show.triggers.state-machine :as state-machine]))

(defn make
  ([] (make []))
  ([definitions]
   {:definitions (vec (filter definitions/enabled? definitions))
    :machines {}}))

(defn matching-definitions [system event]
  (filter #(matcher/matches? event (:match %)) (:definitions system)))

(defn- ensure-machine [system definition]
  (if (contains? (:machines system) (:id definition))
    system
    (assoc-in system [:machines (:id definition)]
              (state-machine/make (:initial-state definition)
                                  (:transitions definition)))))

(defn dispatch
  "Dispatches event and returns {:effects [...] :system updated}.
   Effects are the :emit values from matching state machines."
  [system event]
  (reduce
   (fn [{:keys [system effects]} definition]
     (let [system (ensure-machine system definition)
           [effect machine] (state-machine/transition
                             (get-in system [:machines (:id definition)]) event)]
       {:system (assoc-in system [:machines (:id definition)] machine)
        :effects (cond-> effects (some? effect) (conj {:trigger-id (:id definition)
                                                       :effect effect}))}))
   {:system system :effects []}
   (matching-definitions system event)))

(defn add-definition [system definition]
  (if (definitions/validate definition)
    (update system :definitions conj definition)
    (throw (ex-info "Invalid trigger definition" {:definition definition}))))

(defn remove-definition [system id]
  (-> system
      (update :definitions #(vec (remove (comp #{id} :id) %)))
      (update :machines dissoc id)))
