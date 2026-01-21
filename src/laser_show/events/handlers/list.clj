(ns laser-show.events.handlers.list
  "Event handlers for hierarchical list UI state.
   
   These handlers manage selection, drag-drop, and rename state
   for all hierarchical list components (effect chains, cue chains, item effects).
   
   State is stored directly in [:list-ui component-id]"
  (:require [clojure.tools.logging :as log]
            [laser-show.animation.chains :as chains]))


;; Selection Handlers


(defn- handle-select-item
  "Handle item selection with different modes (single/ctrl/shift).
   
   When selecting in a cue-chain component, also clears the item-effects
   selection since the selected cue item changed and old effects selection
   is no longer relevant."
  [{:keys [component-id item-id mode selected-ids-override last-id-override state]}]
  (let [current-state (get-in state [:list-ui component-id] {})
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
                     :last-selected-id item-id})
        state-with-selection (update-in state [:list-ui component-id] merge new-state)
        ;; If this is a cue-chain component, clear the item-effects selection
        ;; The item-effects list shows effects for the selected cue item, so
        ;; when cue selection changes, old effects selection is no longer valid
        state-final (if (and (vector? component-id)
                             (= :cue-chain (first component-id)))
                      (let [[_ col row] component-id
                            item-effects-component-id [:item-effects col row]]
                        (assoc-in state-with-selection
                                  [:list-ui item-effects-component-id]
                                  {:selected-ids #{} :last-selected-id nil}))
                      state-with-selection)]
    {:state state-final}))

(defn- handle-select-all
  "Select all items in the component."
  [{:keys [component-id all-ids state]}]
  {:state (update-in state [:list-ui component-id]
                     merge {:selected-ids (set all-ids)
                           :last-selected-id (first all-ids)})})

(defn- handle-clear-selection
  "Clear selection and cancel rename."
  [{:keys [component-id state]}]
  {:state (update-in state [:list-ui component-id]
                     merge {:selected-ids #{}
                           :last-selected-id nil
                           :renaming-id nil})})


;; Drag-Drop Handlers


(defn- handle-start-drag
  "Start a drag operation."
  [{:keys [component-id item-id state]}]
  (let [current-state (get-in state [:list-ui component-id] {})
        selected-ids (:selected-ids current-state #{})
        dragging-ids (if (contains? selected-ids item-id)
                       selected-ids
                       #{item-id})
        new-selected (if (contains? selected-ids item-id)
                       selected-ids
                       #{item-id})]
    {:state (update-in state [:list-ui component-id]
                       merge {:dragging-ids dragging-ids
                             :selected-ids new-selected
                             :last-selected-id item-id})}))

(defn- handle-update-drop-target
  "Update drop target during drag over."
  [{:keys [component-id target-id drop-position state]}]
  {:state (update-in state [:list-ui component-id]
                     merge {:drop-target-id target-id
                           :drop-position drop-position})})

(defn- handle-clear-drag
  "Clear drag state after drop or cancel."
  [{:keys [component-id state]}]
  {:state (update-in state [:list-ui component-id]
                     merge {:dragging-ids nil
                           :drop-target-id nil
                           :drop-position nil})})

(defn- handle-perform-drop
  "Perform the heavy computation for drag-drop operation.
   
   This runs on the agent thread (async), moving computation off the FX thread.
   After computing new items, dispatches the on-change-event with the result.
   
   IMPORTANT: Items are read from state using items-path to avoid stale
   closure issues. The items passed in the event are ignored.
   
   Event keys:
   - :component-id - List component identifier
   - :dragging-ids - Set of IDs being dragged
   - :target-id - Drop target item ID
   - :drop-position - :before, :after, or :into
   - :on-change-event - Event type to dispatch with result (e.g., :chain/set-items)
   - :on-change-params - Base params for the change event
   - :items-key - Key for items in dispatched event (default :items)
   - :items-path - Direct path to items in state (optional, overrides domain/entity-key construction)
   
   If :items-path is not provided, constructs path as [:chains domain entity-key :items]
   using :domain and :entity-key from on-change-params."
  [{:keys [component-id dragging-ids target-id drop-position
           on-change-event on-change-params items-key items-path state]
    :or {items-key :items}}]
  ;; Read items DIRECTLY from state to avoid stale closure issues
  ;; The items passed in the event may be stale from drag handler setup
  (let [{:keys [domain entity-key]} on-change-params
        ;; Use explicit items-path if provided, otherwise construct from domain/entity-key
        items-path (or items-path [:chains domain entity-key :items])
        items (get-in state items-path [])
        clear-drag-state {:dragging-ids nil
                          :drop-target-id nil
                          :drop-position nil}]
    (log/debug "handle-perform-drop ENTER - thread:" (.getName (Thread/currentThread))
               "component-id:" component-id
               "on-change-event:" on-change-event
               "on-change-params:" on-change-params
               "items-key:" items-key
               "items-path:" items-path
               "items count:" (count items)
               "dragging-ids:" dragging-ids)
    (cond
      ;; No items or dragging IDs - just clear drag state
      (not (and (seq dragging-ids) (seq items)))
      (do
        (log/debug "handle-perform-drop - no items or dragging IDs")
        {:state (update-in state [:list-ui component-id] merge clear-drag-state)})
      
      ;; Have items and dragging IDs - try to perform drop
      :else
      (let [id->path (chains/find-paths-by-ids items dragging-ids)
            from-paths (set (vals id->path))]
        (log/debug "handle-perform-drop - from-paths:" from-paths)
        (if (seq from-paths)
          ;; Valid paths - perform move and dispatch
          (let [new-items (chains/move-items-to-target items from-paths target-id drop-position)
                dispatch-event (assoc on-change-params
                                      :event/type on-change-event
                                      items-key new-items)]
            (log/debug "handle-perform-drop SUCCESS - dispatching:" (:event/type dispatch-event)
                       "new-items count:" (count new-items))
            {:state (update-in state [:list-ui component-id] merge clear-drag-state)
             :dispatch dispatch-event})
          ;; No valid paths - just clear drag state
          (do
            (log/debug "handle-perform-drop - no valid from-paths, clearing drag state")
            {:state (update-in state [:list-ui component-id] merge clear-drag-state)}))))))


;; Rename Handlers


(defn- handle-start-rename
  "Enter rename mode for an item."
  [{:keys [component-id item-id state]}]
  {:state (assoc-in state [:list-ui component-id :renaming-id] item-id)})

(defn- handle-cancel-rename
  "Cancel rename mode."
  [{:keys [component-id state]}]
  {:state (assoc-in state [:list-ui component-id :renaming-id] nil)})


;; Auto-Select Helpers


(defn- handle-auto-select-single-item
  "Auto-select an item if the list has exactly 1 item.
   
   This is a convenience event for editors that want to auto-select
   when opening with a single item. It reads items from state and
   dispatches :list/select-item if there's exactly one item.
   
   Event keys:
   - :component-id - List component identifier (e.g., [:effect-chain 0 0])
   - :items-path - Path in state to the items vector (e.g., [:chains :effect-chains [0 0] :items])"
  [{:keys [component-id items-path state]}]
  (let [items (get-in state items-path [])
        single-item? (= 1 (count items))
        first-item-id (when single-item? (:id (first items)))]
    (if single-item?
      {:state state
       :dispatch {:event/type :list/select-item
                  :component-id component-id
                  :item-id first-item-id
                  :mode :single}}
      {:state state})))


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
    :list/perform-drop (handle-perform-drop event)
    :list/start-rename (handle-start-rename event)
    :list/cancel-rename (handle-cancel-rename event)
    :list/auto-select-single-item (handle-auto-select-single-item event)
    
    ;; Unknown event in this domain
    {}))
