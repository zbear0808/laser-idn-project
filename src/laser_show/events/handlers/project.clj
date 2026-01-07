(ns laser-show.events.handlers.project
  "Event handlers for project state management.
   
   Handles:
   - Dirty flag tracking
   - Project folder management
   - Save timestamps"
  (:require [laser-show.events.helpers :as h]))


(defn- handle-project-mark-dirty
  "Mark project as having unsaved changes."
  [{:keys [state]}]
  {:state (assoc-in state [:project :dirty?] true)})

(defn- handle-project-mark-clean
  "Mark project as saved."
  [{:keys [state] :as event}]
  (let [now (h/current-time-ms event)]
    {:state (-> state
                (assoc-in [:project :dirty?] false)
                (assoc-in [:project :last-saved] now))}))

(defn- handle-project-set-folder
  "Set the current project folder."
  [{:keys [folder state]}]
  {:state (assoc-in state [:project :current-folder] folder)})


;; Public API


(defn handle
  "Dispatch project events to their handlers.
   
   Accepts events with :event/type in the :project/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :project/mark-dirty (handle-project-mark-dirty event)
    :project/mark-clean (handle-project-mark-clean event)
    :project/set-folder (handle-project-set-folder event)
    
    ;; Unknown event in this domain
    {}))
