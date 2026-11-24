(ns laser-show.events.handlers.files
  "Event handlers for project state management.
   
   Handles:
   - Save timestamps
   - Project folder/file path"
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]))


(defn- handle-project-mark-clean
  "Mark project as saved: the current document becomes the saved baseline.
   With :file-path, the file a save has just written and checked (the
   :project/save effect dispatches this only then), which becomes the
   project's file (persistent/mark-saved). Then :then, when given: the event
   that waited for the save (the unsaved-changes dialog's New, Open or
   Exit)."
  [{:keys [state file-path then] :as event}]
  (let [now (h/current-time-ms event)]
    (cond-> {:state (if file-path
                      (persistent/mark-saved state file-path now)
                      (-> state
                          history/mark-clean
                          (assoc-in [:project :last-saved] now)))}
      then (assoc :dispatch then))))


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
