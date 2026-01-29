(ns laser-show.events.handlers.project
  "Event handlers for project state management.
   
   Handles:
   - Save timestamps"
  (:require [laser-show.events.helpers :as h]))


(defn- handle-project-mark-clean
  "Mark project as saved."
  [{:keys [state] :as event}]
  (let [now (h/current-time-ms event)]
    {:state (-> state
                (assoc-in [:project :dirty?] false)
                (assoc-in [:project :last-saved] now))}))


;; Public API


(defn handle
  "Dispatch project events to their handlers.
   
   Accepts events with :event/type in the :project/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :project/mark-clean (handle-project-mark-clean event)
    
    ;; Unknown event in this domain
    {}))
