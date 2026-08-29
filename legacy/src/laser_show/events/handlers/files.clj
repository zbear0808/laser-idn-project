(ns laser-show.events.handlers.files
  "Event handlers for project state management.
   
   Handles:
   - Save timestamps
   - Project folder/file path"
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]))


(defn- handle-project-mark-clean
  "Mark project as saved."
  [{:keys [state] :as event}]
  (let [now (h/current-time-ms event)]
    {:state (-> state
                (assoc-in [:project :dirty?] false)
                (assoc-in [:project :last-saved] now))}))


;; TODO: Implement :project/set-folder handler - set project folder/file path after loading
(defn- handle-project-set-folder
  [event]
  (log/error "TODO: Handler not implemented for :project/set-folder" event)
  {})


;; Public API


(defn handle
  "Dispatch project events to their handlers.
   
   Accepts events with :event/type in the :project/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :project/mark-clean (handle-project-mark-clean event)
    :project/set-folder (handle-project-set-folder event)
    
    ;; Unknown event in this domain
    {}))
