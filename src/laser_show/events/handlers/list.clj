(ns laser-show.events.handlers.list
  "Event handlers for hierarchical list UI state.
   
   These handlers manage selection, drag-drop, and rename state
   for all hierarchical list components (effect chains, cue chains, item effects).
   
   State is stored in [:list-ui :components component-id]")


;; Selection Handlers


(defn- handle-select-item
  "Handle item selection with different modes (single/ctrl/shift)."
  [{:keys [component-id item-id mode selected-ids-override last-id-override state]}]
  (let [current-state (get-in state [:list-ui :components component-id] {})
        {:keys [selected-ids last-selected-id]} current-state
        new-state (case mode
                    ;; Ctrl+Click - toggle selection
                    :ctrl
                    {:selected-ids (if (contains? selected-ids item-id)
                                     (disj selected-ids item-id)
                                     (conj selected-ids item-id))
                     :last-selected-id item-id}
                    
                    ;; Shift+Click - range select (pre-computed by component)
                    :shift
                    {:selected-ids selected-ids-override
                     :last-selected-id (or last-id-override last-selected-id)}
                    
                    ;; Single click - replace selection
                    :single
                    {:selected-ids #{item-id}
                     :last-selected-id item-id})]
    {:state (update-in state [:list-ui :components component-id] merge new-state)}))

(defn- handle-select-all
  "Select all items in the component."
  [{:keys [component-id all-ids state]}]
  {:state (update-in state [:list-ui :components component-id]
                     merge {:selected-ids (set all-ids)
                           :last-selected-id (first all-ids)})})

(defn- handle-clear-selection
  "Clear selection and cancel rename."
  [{:keys [component-id state]}]
  {:state (update-in state [:list-ui :components component-id]
                     merge {:selected-ids #{}
                           :last-selected-id nil
                           :renaming-id nil})})


;; Drag-Drop Handlers


(defn- handle-start-drag
  "Start a drag operation."
  [{:keys [component-id item-id state]}]
  (let [current-state (get-in state [:list-ui :components component-id] {})
        selected-ids (:selected-ids current-state #{})
        dragging-ids (if (contains? selected-ids item-id)
                       selected-ids
                       #{item-id})
        new-selected (if (contains? selected-ids item-id)
                       selected-ids
                       #{item-id})]
    {:state (update-in state [:list-ui :components component-id]
                       merge {:dragging-ids dragging-ids
                             :selected-ids new-selected
                             :last-selected-id item-id})}))

(defn- handle-update-drop-target
  "Update drop target during drag over."
  [{:keys [component-id target-id drop-position state]}]
  {:state (update-in state [:list-ui :components component-id]
                     merge {:drop-target-id target-id
                           :drop-position drop-position})})

(defn- handle-clear-drag
  "Clear drag state after drop or cancel."
  [{:keys [component-id state]}]
  {:state (update-in state [:list-ui :components component-id]
                     merge {:dragging-ids nil
                           :drop-target-id nil
                           :drop-position nil})})


;; Rename Handlers


(defn- handle-start-rename
  "Enter rename mode for an item."
  [{:keys [component-id item-id state]}]
  {:state (assoc-in state [:list-ui :components component-id :renaming-id] item-id)})

(defn- handle-cancel-rename
  "Cancel rename mode."
  [{:keys [component-id state]}]
  {:state (assoc-in state [:list-ui :components component-id :renaming-id] nil)})


;; Public API


(defn handle
  "Dispatch list events to their handlers.
   
   Accepts events with :event/type in the :list/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :list/select-item (handle-select-item event)
    :list/select-all (handle-select-all event)
    :list/clear-selection (handle-clear-selection event)
    :list/start-drag (handle-start-drag event)
    :list/update-drop-target (handle-update-drop-target event)
    :list/clear-drag (handle-clear-drag event)
    :list/start-rename (handle-start-rename event)
    :list/cancel-rename (handle-cancel-rename event)
    
    ;; Unknown event in this domain
    {}))
