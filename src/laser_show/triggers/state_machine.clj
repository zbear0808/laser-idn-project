(ns laser-show.triggers.state-machine
  "Pure finite state machine for trigger sequences."
  (:require [laser-show.triggers.matcher :as matcher]))

(defn make
  ([initial-state transitions] (make initial-state transitions {}))
  ([initial-state transitions options]
   (merge {:state initial-state :transitions transitions :history []} options)))

(defn transition
  "Applies event and returns [result new-machine]. Result is nil if no transition.
   Transition values may be a keyword, a map, or a function of [machine event]."
  [machine event]
  (let [state (:state machine)
        candidates (get-in machine [:transitions state] [])
        found (some #(when (matcher/matches? event (:match %)) %) candidates)]
    (if-not found
      [nil machine]
      (let [next-state (:to found state)
            result (:emit found)
            result (if (fn? result) (result machine event) result)
            updated (-> machine
                        (assoc :state next-state)
                        (update :history conj {:from state :to next-state :event event}))]
        [result updated]))))

(def step transition)
(def current-state :state)
