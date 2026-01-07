(ns laser-show.events.handlers.ui
  "Event handlers for UI state management.
   
   Handles:
   - Tab switching
   - Dialog management
   - Drag and drop operations
   - Preset selection"
  (:require [clojure.tools.logging :as log]))


(defn- handle-ui-set-active-tab
  "Change the active tab."
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:ui :active-tab] tab-id)})

(defn- handle-ui-select-preset
  "Select a preset in the browser."
  [{:keys [preset-id state]}]
  {:state (assoc-in state [:ui :selected-preset] preset-id)})

(defn- handle-ui-open-dialog
  "Open a dialog."
  [{:keys [dialog-id data state]}]
  {:state (-> state
              (assoc-in [:ui :dialogs dialog-id :open?] true)
              (assoc-in [:ui :dialogs dialog-id :data] data))})

(defn- handle-ui-close-dialog
  "Close a dialog."
  [{:keys [dialog-id state]}]
  {:state (assoc-in state [:ui :dialogs dialog-id :open?] false)})

(defn- handle-ui-update-dialog-data
  "Update data associated with a dialog (e.g., selected item within dialog).
   Supports:
   - :updates - A map of keys to merge into dialog data
   - :tab-id - If present without :updates, sets :active-bank-tab to this value
               (used by styled-tab-bar in dialogs)"
  [{:keys [dialog-id updates tab-id state]}]
  (let [actual-updates (cond
                         updates updates
                         tab-id {:active-bank-tab tab-id}
                         :else {})]
    (when tab-id
      (log/debug "Tab clicked:" {:dialog-id dialog-id :tab-id tab-id}))
    {:state (update-in state [:ui :dialogs dialog-id :data] merge actual-updates)}))

(defn- handle-ui-start-drag
  "Start a drag operation."
  [{:keys [source-type source-key data state]}]
  {:state (assoc-in state [:ui :drag]
                    {:active? true
                     :source-type source-type
                     :source-key source-key
                     :data data})})

(defn- handle-ui-end-drag
  "End a drag operation."
  [{:keys [state]}]
  {:state (assoc-in state [:ui :drag]
                    {:active? false
                     :source-type nil
                     :source-key nil
                     :data nil})})


;; Public API


(defn handle
  "Dispatch UI events to their handlers.
   
   Accepts events with :event/type in the :ui/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :ui/set-active-tab (handle-ui-set-active-tab event)
    :ui/select-preset (handle-ui-select-preset event)
    :ui/open-dialog (handle-ui-open-dialog event)
    :ui/close-dialog (handle-ui-close-dialog event)
    :ui/update-dialog-data (handle-ui-update-dialog-data event)
    :ui/start-drag (handle-ui-start-drag event)
    :ui/end-drag (handle-ui-end-drag event)
    
    ;; Unknown event in this domain
    {}))
