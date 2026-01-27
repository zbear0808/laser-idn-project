(ns laser-show.events.handlers.list
  "Event handlers for hierarchical list UI state.
   
   These handlers manage selection, drag-drop, rename state, and item operations
   for all hierarchical list components (effect chains, cue chains, item effects).
   
   State is stored directly in [:list-ui component-id]
   
   Item operations (delete, copy, paste, group, etc.) read items from state
   using :items-path to avoid stale closure issues."
  (:require [clojure.tools.logging :as log]
            [laser-show.animation.chains :as chains]
            [laser-show.state.clipboard :as clipboard]))


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
   - :items-path - Direct path to items in state (optional, overrides domain/entity-key construction)
   
   If :items-path is not provided, constructs path as [:chains domain entity-key :items]
   using :domain and :entity-key from on-change-params."
  [{:keys [component-id dragging-ids target-id drop-position
           on-change-event on-change-params items-path state]}]
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
                                      :items new-items)]
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


;; Item Operation Handlers
;; These handlers read items from state using :items-path to avoid stale closures


(defn- normalize-selected-ids
  "Remove redundant descendant IDs when a group AND all its descendants are selected.
   Returns the normalized set of IDs."
  [selected-ids items]
  (let [selected-ids (set selected-ids)
        id->path (chains/find-paths-by-ids items selected-ids)
        group-ids (filterv
                    (fn [id]
                      (when-let [path (get id->path id)]
                        (chains/group? (chains/get-item-at-path items path))))
                    selected-ids)]
    (reduce
      (fn [ids group-id]
        (let [path (get id->path group-id)
              group (chains/get-item-at-path items path)
              descendant-ids (chains/collect-descendant-ids group)]
          (if (and (seq descendant-ids)
                   (every? #(contains? selected-ids %) descendant-ids))
            (apply disj ids descendant-ids)
            ids)))
      (set selected-ids)
      group-ids)))


(defn- handle-delete-selected
  "Delete selected items from the chain.
   
   Reads items from state, finds selected items, deletes them,
   clears selection, and dispatches on-change-event with new items.
   
   Event keys:
   - :component-id - List component identifier
   - :items-path - Path to items in state
   - :on-change-event - Event type to dispatch (e.g., :chain/set-items)
   - :on-change-params - Base params for change event"
  [{:keys [component-id items-path on-change-event on-change-params state]}]
  (let [items (get-in state items-path [])
        selected-ids (get-in state [:list-ui component-id :selected-ids] #{})]
    (if (seq selected-ids)
      (let [id->path (chains/find-paths-by-ids items selected-ids)
            paths-to-delete (vals id->path)
            new-items (chains/delete-paths-safely items paths-to-delete)
            cleared-state (assoc-in state [:list-ui component-id]
                                    {:selected-ids #{}
                                     :last-selected-id nil
                                     :renaming-id nil})]
        {:state cleared-state
         :dispatch (assoc on-change-params
                          :event/type on-change-event
                          :items new-items)})
      {:state state})))


(defn- handle-copy-selected
  "Copy selected items to clipboard.
   
   Reads items from state, finds selected items, deep-copies them,
   and stores in centralized clipboard.
   
   Event keys:
   - :component-id - List component identifier
   - :items-path - Path to items in state
   - :clipboard-type - Type for clipboard (:cue-chain-items or :item-effects)"
  [{:keys [component-id items-path clipboard-type state]}]
  (let [items (get-in state items-path [])
        selected-ids (get-in state [:list-ui component-id :selected-ids] #{})]
    (if (seq selected-ids)
      (let [id->path (chains/find-paths-by-ids items selected-ids)
            selected-items (mapv #(chains/get-item-at-path items %) (vals id->path))
            copied-items (chains/deep-copy-items selected-items)]
        ;; Write to clipboard (side effect)
        (case clipboard-type
          :item-effects (clipboard/copy-item-effects! copied-items)
          ;; Default to cue-chain-items
          (clipboard/copy-cue-chain-items! copied-items))
        {:state state})
      {:state state})))


(defn- handle-paste-items
  "Paste items from clipboard after selected item (or at end).
   
   Reads clipboard, deep-copies items (new UUIDs), inserts after
   last selected item or at end of chain.
   
   Event keys:
   - :component-id - List component identifier
   - :items-path - Path to items in state
   - :on-change-event - Event type to dispatch
   - :on-change-params - Base params for change event
   - :clipboard-type - Type to check (:cue-chain-items or :item-effects)"
  [{:keys [component-id items-path on-change-event on-change-params clipboard-type state]}]
  (let [clip (clipboard/get-clipboard)
        expected-type (or clipboard-type :cue-chain-items)
        clipboard-items (when (= (:type clip) expected-type)
                          (if (= expected-type :item-effects)
                            (:effects clip)
                            (:items clip)))]
    (if (seq clipboard-items)
      (let [items (get-in state items-path [])
            last-selected-id (get-in state [:list-ui component-id :last-selected-id])
            items-to-paste (chains/deep-copy-items clipboard-items)
            path-for-last (when last-selected-id (chains/find-path-by-id items last-selected-id))
            insert-idx (if path-for-last
                         (inc (first path-for-last))
                         (count items))
            new-items (reduce-kv
                        (fn [chain idx item]
                          (chains/insert-at-path chain [(+ insert-idx idx)] item))
                        items
                        (vec items-to-paste))
            pasted-ids (set (map :id items-to-paste))
            last-pasted-id (:id (last items-to-paste))
            new-state (assoc-in state [:list-ui component-id]
                                {:selected-ids pasted-ids
                                 :last-selected-id last-pasted-id
                                 :renaming-id nil})]
        {:state new-state
         :dispatch (assoc on-change-params
                          :event/type on-change-event
                          :items new-items)})
      {:state state})))


(defn- handle-group-selected
  "Group selected items into a new folder.
   
   Items must be at the same nesting level to be grouped together.
   
   Event keys:
   - :component-id - List component identifier
   - :items-path - Path to items in state
   - :on-change-event - Event type to dispatch
   - :on-change-params - Base params for change event"
  [{:keys [component-id items-path on-change-event on-change-params state]}]
  (let [items (get-in state items-path [])
        selected-ids (get-in state [:list-ui component-id :selected-ids] #{})]
    (log/debug "handle-group-selected ENTER"
               {:component-id component-id
                :items-path items-path
                :items-count (count items)
                :selected-ids-count (count selected-ids)
                :selected-ids selected-ids
                :items-first-id (when (seq items) (:id (first items)))})
    (if (and (seq items) (seq selected-ids))
      (let [normalized-ids (normalize-selected-ids selected-ids items)
            id->path (chains/find-paths-by-ids items normalized-ids)
            all-paths (vals id->path)
            _ (log/debug "handle-group-selected - path lookup"
                        {:normalized-ids normalized-ids
                         :id->path id->path
                         :all-paths all-paths
                         :paths-count (count all-paths)})
            parent-paths (mapv (fn [path]
                                 (if (= 1 (count path))
                                   []
                                   (vec (butlast path))))
                               all-paths)
            unique-parents (set parent-paths)
            same-level? (= 1 (count unique-parents))
            common-parent (first unique-parents)]
        (log/debug "handle-group-selected - level check"
                   {:parent-paths parent-paths
                    :unique-parents unique-parents
                    :same-level? same-level?
                    :common-parent common-parent})
        (if (and same-level? (seq all-paths))
          (let [sorted-paths (sort (fn [a b] (compare (vec a) (vec b))) all-paths)
                items-to-group (mapv #(chains/get-item-at-path items %) sorted-paths)
                new-group (chains/create-group items-to-group)
                after-remove (chains/delete-paths-safely items sorted-paths)
                first-path (first sorted-paths)
                insert-path (if (empty? common-parent)
                              [(first first-path)]
                              (conj common-parent (last first-path)))
                new-items (chains/insert-at-path after-remove insert-path new-group)
                group-id (:id new-group)
                new-state (assoc-in state [:list-ui component-id]
                                    {:selected-ids #{group-id}
                                     :last-selected-id group-id
                                     :renaming-id nil})]
            (log/debug "handle-group-selected SUCCESS"
                       {:group-id group-id
                        :new-items-count (count new-items)})
            {:state new-state
             :dispatch (assoc on-change-params
                              :event/type on-change-event
                              :items new-items)})
          (do
            (log/warn "group-selected - items not at same level or no paths found"
                      {:same-level? same-level?
                       :all-paths-count (count all-paths)
                       :unique-parents unique-parents})
            {:state state})))
      (do
        (log/debug "handle-group-selected - no items or no selected ids"
                   {:items-count (count items)
                    :selected-ids-count (count selected-ids)})
        {:state state}))))


(defn- handle-create-empty-group
  "Create an empty group at the end of the chain.
   
   Event keys:
   - :component-id - List component identifier
   - :items-path - Path to items in state
   - :on-change-event - Event type to dispatch
   - :on-change-params - Base params for change event"
  [{:keys [component-id items-path on-change-event on-change-params state]}]
  (let [items (get-in state items-path [])
        new-group (chains/create-group [])
        new-items (conj items new-group)
        group-id (:id new-group)
        new-state (assoc-in state [:list-ui component-id]
                            {:selected-ids #{group-id}
                             :last-selected-id group-id
                             :renaming-id nil})]
    {:state new-state
     :dispatch (assoc on-change-params
                      :event/type on-change-event
                      :items new-items)}))


(defn- handle-ungroup
  "Ungroup a folder, splicing its contents into the parent.
   
   Event keys:
   - :component-id - List component identifier
   - :items-path - Path to items in state
   - :item-id - ID of the group to ungroup
   - :on-change-event - Event type to dispatch
   - :on-change-params - Base params for change event"
  [{:keys [component-id items-path item-id on-change-event on-change-params state]}]
  (let [items (get-in state items-path [])]
    (if-let [path (chains/find-path-by-id items item-id)]
      (if (chains/group? (chains/get-item-at-path items path))
        (let [new-items (chains/ungroup items path)
              cleared-state (assoc-in state [:list-ui component-id]
                                      {:selected-ids #{}
                                       :last-selected-id nil
                                       :renaming-id nil})]
          {:state cleared-state
           :dispatch (assoc on-change-params
                            :event/type on-change-event
                            :items new-items)})
        {:state state})
      {:state state})))


(defn- handle-toggle-collapse
  "Toggle collapse/expand state of a group.
   
   Event keys:
   - :items-path - Path to items in state
   - :item-id - ID of the group to toggle
   - :on-change-event - Event type to dispatch
   - :on-change-params - Base params for change event"
  [{:keys [items-path item-id on-change-event on-change-params state]}]
  (let [items (get-in state items-path [])]
    (if-let [path (chains/find-path-by-id items item-id)]
      (let [new-items (chains/update-at-path items path #(update % :collapsed? not))]
        {:state state
         :dispatch (assoc on-change-params
                          :event/type on-change-event
                          :items new-items)})
      {:state state})))


(defn- handle-commit-rename
  "Commit rename and update item name.
   
   Event keys:
   - :component-id - List component identifier
   - :items-path - Path to items in state
   - :item-id - ID of item to rename
   - :new-name - New name for the item
   - :on-change-event - Event type to dispatch
   - :on-change-params - Base params for change event"
  [{:keys [component-id items-path item-id new-name on-change-event on-change-params state]}]
  (let [items (get-in state items-path [])]
    (if-let [path (chains/find-path-by-id items item-id)]
      (let [new-items (chains/update-at-path items path #(assoc % :name new-name))
            new-state (assoc-in state [:list-ui component-id :renaming-id] nil)]
        {:state new-state
         :dispatch (assoc on-change-params
                          :event/type on-change-event
                          :items new-items)})
      {:state state})))


(defn- handle-set-enabled
  "Set enabled/disabled state for an item.
   
   Event keys:
   - :items-path - Path to items in state
   - :item-id - ID of item to update
   - :enabled? - New enabled state
   - :on-change-event - Event type to dispatch
   - :on-change-params - Base params for change event"
  [{:keys [items-path item-id enabled? on-change-event on-change-params state]}]
  (let [items (get-in state items-path [])]
    (if-let [path (chains/find-path-by-id items item-id)]
      (let [new-items (chains/update-at-path items path #(assoc % :enabled? enabled?))]
        {:state state
         :dispatch (assoc on-change-params
                          :event/type on-change-event
                          :items new-items)})
      {:state state})))


;; Public API


(defn handle
  "Dispatch list events to their handlers.
   
   Accepts events with :event/type in the :list/* namespace.
   
   Selection events:
   - :list/select-item - Select/toggle item
   - :list/select-all - Select all items
   - :list/clear-selection - Clear all selection
   
   Drag-drop events:
   - :list/start-drag - Begin drag
   - :list/update-drop-target - Update drop indicator
   - :list/clear-drag - Cancel/end drag
   - :list/perform-drop - Execute drop
   
   Rename events:
   - :list/start-rename - Enter rename mode
   - :list/cancel-rename - Cancel rename
   
   Item operation events:
   - :list/delete-selected - Delete selected items
   - :list/copy-selected - Copy to clipboard
   - :list/paste-items - Paste from clipboard
   - :list/group-selected - Group selected items
   - :list/create-empty-group - Create new empty group
   - :list/ungroup - Ungroup a folder
   - :list/toggle-collapse - Toggle group collapse
   - :list/commit-rename - Update item name
   - :list/set-enabled - Set enabled state
   
   Helper events:
   - :list/auto-select-single-item - Auto-select if single item"
  [{:keys [event/type] :as event}]
  (case type
    ;; Selection
    :list/select-item (handle-select-item event)
    :list/select-all (handle-select-all event)
    :list/clear-selection (handle-clear-selection event)
    
    ;; Drag-drop
    :list/start-drag (handle-start-drag event)
    :list/update-drop-target (handle-update-drop-target event)
    :list/clear-drag (handle-clear-drag event)
    :list/perform-drop (handle-perform-drop event)
    
    ;; Rename
    :list/start-rename (handle-start-rename event)
    :list/cancel-rename (handle-cancel-rename event)
    
    ;; Item operations
    :list/delete-selected (handle-delete-selected event)
    :list/copy-selected (handle-copy-selected event)
    :list/paste-items (handle-paste-items event)
    :list/group-selected (handle-group-selected event)
    :list/create-empty-group (handle-create-empty-group event)
    :list/ungroup (handle-ungroup event)
    :list/toggle-collapse (handle-toggle-collapse event)
    :list/commit-rename (handle-commit-rename event)
    :list/set-enabled (handle-set-enabled event)
    
    ;; Helpers
    :list/auto-select-single-item (handle-auto-select-single-item event)
    
    ;; Unknown event in this domain
    {}))
