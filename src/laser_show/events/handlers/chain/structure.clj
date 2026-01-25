(ns laser-show.events.handlers.chain.structure
  "Structure-related handlers for chain operations.
   
   Contains:
   - Group operations (create-empty-group, group-selected, ungroup, toggle-collapse)
   - Copy/Paste operations (copy-selected, paste-items)
   - Delete operations (delete-selected)
   - Drag-and-Drop operations (start-drag, move-items, clear-drag-state)
   - Item CRUD (add-item, remove-item-at-path, reorder-items)"
  (:require
   [laser-show.events.helpers :as h]
   [laser-show.events.handlers.chain.helpers :as helpers]
   [laser-show.animation.chains :as chains]))


;; ============================================================================
;; Group Operations
;; ============================================================================


(defn handle-create-empty-group
  "Create a new empty group at the end of the chain.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - name: Optional group name (defaults to 'New Group')
   
   Returns: Updated state"
  [state config & [name]]
  (let [group-name (or name "New Group")
        new-group (helpers/make-group group-name [])]
    (-> state
        (update-in (:items-path config) conj new-group)
        h/mark-dirty)))

(defn handle-group-selected
  "Group currently selected items into a new group.
   
   Uses two-phase operation to avoid index-shifting bugs:
   1. Normalize selection (remove redundant children when group + all children selected)
   2. Filter to root paths only
   3. Collect selected items by ID
   4. Remove items by ID set
   5. Insert new group at computed position
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - group-name: Optional group name (defaults to 'New Group')
   
   Returns: Updated state"
  [state config & [group-name]]
  (let [name (or group-name "New Group")
        selected-paths (helpers/get-selected-paths state config)
        items-vec (helpers/get-items state config)]
    (if (seq selected-paths)
      (let [;; Normalize selection: if a group + ALL its children are selected,
            ;; treat it as just the group being selected. This allows grouping
            ;; items at different visual levels when their parent groups are also selected.
            normalized-paths (helpers/normalize-selected-paths selected-paths items-vec)
            ;; Filter to root paths only
            root-paths (helpers/filter-to-root-paths normalized-paths)
            ;; Sort by visual order to maintain relative ordering
            all-paths (vec (chains/paths-in-chain items-vec))
            sorted-paths (sort-by #(.indexOf all-paths %) root-paths)
            
            ;; PHASE 1: Extract items in visual order
            selected-items (mapv #(get-in items-vec (vec %)) sorted-paths)
            
            ;; Collect IDs for removal
            ids-to-remove (into #{} (map :id selected-items))
            
            ;; Find insertion point
            first-path (first sorted-paths)
            insert-at-top-level (first first-path)
            
            ;; PHASE 2: Remove all selected items by ID
            after-remove (helpers/remove-items-by-ids items-vec ids-to-remove)
            
            ;; Calculate adjusted insert position
            items-removed-before-insert (count
                                          (filter
                                            (fn [path]
                                              (and (= 1 (count path))
                                                   (< (first path) insert-at-top-level)))
                                            sorted-paths))
            adjusted-insert (- insert-at-top-level items-removed-before-insert)
            
            ;; Create the new group
            new-group (helpers/make-group name selected-items)
            
            ;; PHASE 3: Insert new group
            new-items (helpers/insert-items-at-index after-remove adjusted-insert [new-group])
            
            ;; Select the new group
            new-group-path [adjusted-insert]]
        (-> state
            (helpers/set-items config new-items)
            (helpers/set-selected-paths config #{new-group-path})
            h/mark-dirty))
      state)))

(defn handle-ungroup
  "Ungroup a group, moving its contents to the parent level.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - path: Path to the group to ungroup
   
   Returns: Updated state"
  [state config path]
  (let [items-vec (helpers/get-items state config)
        item (get-in items-vec path)]
    (if-not (chains/group? item)
      state
      (let [group-items (:items item [])
            is-top-level? (= 1 (count path))
            group-idx (if is-top-level? (first path) (last path))
            parent-path (when-not is-top-level? (vec (butlast (butlast path))))
            parent-vec (cond-> items-vec
                         (not is-top-level?) (get-in (conj parent-path :items) []))
            ;; Remove group and insert its items
            new-parent (vec (concat (subvec parent-vec 0 group-idx)
                                    group-items
                                    (subvec parent-vec (inc group-idx))))
            ;; Update the items
            new-items (cond-> new-parent
                        (not is-top-level?)
                        (->> (assoc-in items-vec (conj parent-path :items))))]
        (-> state
            (helpers/set-items config new-items)
            (helpers/set-selected-paths config #{})
            h/mark-dirty)))))

(defn handle-toggle-collapse
  "Toggle a group's collapsed state.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - path: Path to the group
   
   Returns: Updated state"
  [state config path]
  (let [items-vec (helpers/get-items state config)
        item (get-in items-vec path)]
    (if (chains/group? item)
      (update-in state (:items-path config)
                 (fn [items]
                   (update-in items (conj path :collapsed?) not)))
      state)))


;; ============================================================================
;; Copy/Paste Operations
;; ============================================================================


(defn handle-copy-selected
  "Generic copy handler - collects selected items for clipboard.
   
   Returns a map with:
   - :state - Unchanged state
   - :items - Vector of items to copy (for caller to handle clipboard)
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: {:state state :items [items-to-copy]}"
  [state config]
  (let [selected-paths (helpers/get-selected-paths state config)
        items-vec (helpers/get-items state config)
        valid-items (when (seq selected-paths)
                      (vec (keep #(get-in items-vec %) selected-paths)))]
    {:state state
     :items (or valid-items [])}))

(defn handle-paste-items
  "Generic paste handler - inserts items at appropriate position.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - items: Vector of items to paste
   
   Returns: Updated state with items inserted and selected"
  [state config items]
  (if (empty? items)
    state
    (let [;; Regenerate IDs on all pasted items
          items-with-new-ids (mapv helpers/regenerate-ids items)
          current-items (vec (helpers/get-items state config))
          selected-paths (helpers/get-selected-paths state config)
          
          ;; Calculate insert position - after last selected, or at end
          insert-pos (if (seq selected-paths)
                       (let [top-level-indices (map first selected-paths)]
                         (inc (apply max top-level-indices)))
                       (count current-items))
          safe-pos (min insert-pos (count current-items))
          
          ;; Insert items
          new-items (vec (concat (subvec current-items 0 safe-pos)
                                 items-with-new-ids
                                 (subvec current-items safe-pos)))
          
          ;; Select the newly pasted items
          new-paths (into #{} (map (fn [i] [(+ safe-pos i)]) 
                                   (range (count items-with-new-ids))))]
      (-> state
          (helpers/set-items config new-items)
          (helpers/set-selected-paths config new-paths)
          h/mark-dirty))))


;; ============================================================================
;; Delete Operations
;; ============================================================================


(defn handle-delete-selected
  "Generic delete handler - removes all selected items.
   
   When deleting a group, all children are deleted with it.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: Updated state"
  [state config]
  (let [selected-paths (helpers/get-selected-paths state config)
        items-vec (helpers/get-items state config)]
    (if (seq selected-paths)
      (let [;; Filter to root paths only - if a group is selected, don't separately delete
            ;; its children (they'll be deleted with the group)
            root-paths (helpers/filter-to-root-paths selected-paths)
            ;; Sort paths by depth (deepest first) to avoid index issues
            sorted-paths (sort-by (comp - count) root-paths)
            ;; Remove each path
            new-items (reduce helpers/remove-at-path items-vec sorted-paths)]
        (-> state
            (helpers/set-items config new-items)
            (helpers/set-selected-paths config #{})
            h/mark-dirty))
      state)))


;; ============================================================================
;; Drag-and-Drop Operations
;; ============================================================================


(defn handle-start-drag
  "Start a drag operation.
   
   If the initiating item is part of the selection, drag all selected items.
   If it's not selected, auto-select just that item and drag it alone.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - initiating-path: Path of the item that was dragged
   
   Returns: Updated state"
  [state config initiating-path]
  (let [selected-paths (helpers/get-selected-paths state config)
        ;; If dragging a selected item, drag all selected items
        dragging-paths (if (contains? selected-paths initiating-path)
                         selected-paths
                         #{initiating-path})
        ;; If dragging unselected item, update selection
        new-selected (if (contains? selected-paths initiating-path)
                       selected-paths
                       #{initiating-path})]
    (-> state
        (helpers/set-dragging-paths config dragging-paths)
        (helpers/set-selected-paths config new-selected))))

(defn handle-move-items
  "Move multiple items to a new position.
   
   Uses chains/move-items-to-target for correct ordering.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - target-id: ID of the target item
   - drop-position: :before | :into
   
   Returns: Updated state"
  [state config target-id drop-position]
  (let [items-vec (helpers/get-items state config)
        dragging-paths (helpers/get-dragging-paths state config)
        root-paths (helpers/filter-to-root-paths dragging-paths)
        target-path (chains/find-path-by-id items-vec target-id)
        ;; Check if target is in dragging paths or descendant
        target-in-drag? (or (contains? root-paths target-path)
                           (some #(helpers/path-is-ancestor? % target-path) root-paths))]
    (if (or (empty? root-paths) target-in-drag?)
      ;; Invalid move - just clear dragging state
      (helpers/set-dragging-paths state config nil)
      ;; Use centralized move-items-to-target
      (let [new-items (chains/move-items-to-target items-vec root-paths target-id drop-position)]
        (-> state
            (helpers/set-items config new-items)
            (helpers/set-dragging-paths config nil)
            (helpers/set-selected-paths config #{})
            h/mark-dirty)))))

(defn handle-clear-drag-state
  "Clear the dragging state.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: Updated state"
  [state config]
  (helpers/set-dragging-paths state config nil))


;; ============================================================================
;; Item CRUD Operations
;; ============================================================================


(defn handle-add-item
  "Add an item (effect/preset) to the chain with auto-selection.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path and :ui-path
   - event: Map with :item and optional :parent-path for nested insertion
            :item - Item to add (will be given :id and :enabled? if missing)
            :parent-path - Optional path within items to add to (e.g., [0 :effects])
   
   Returns: Updated state with item added and selected"
  [state config {:keys [item parent-path] :as event}]
  (let [item-with-fields (h/ensure-item-fields item)
        base-items-path (:items-path config)
        ;; If parent-path provided, add to nested location within items
        ;; e.g., for cue-chain item effects: parent-path = [0 :effects]
        target-path (cond-> base-items-path
                      parent-path (-> (concat parent-path) vec))
        ;; Ensure the chain exists at base level
        state-with-chain (cond-> state
                           (nil? (get-in state base-items-path))
                           (assoc-in base-items-path []))
        ;; Ensure nested path exists if specified
        state-with-target (cond-> state-with-chain
                            (and parent-path (nil? (get-in state-with-chain target-path)))
                            (assoc-in target-path []))
        current-items (get-in state-with-target target-path [])
        new-item-idx (count current-items)
        ;; Selection path is relative to items-path, so include parent-path
        selection-path (cond->> [new-item-idx]
                         parent-path (into (vec parent-path)))]
    (-> state-with-target
        (update-in target-path conj item-with-fields)
        (assoc-in (conj (:ui-path config) :selected-paths) #{selection-path})
        (assoc-in (conj (:ui-path config) :last-selected-path) selection-path)
        (h/mark-dirty))))

(defn handle-remove-item-at-path
  "Remove an item at a specific path.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - path: Path to the item to remove
   
   Returns: Updated state"
  [state config path]
  (let [items-path (:items-path config)
        items-vec (get-in state items-path [])
        new-items (chains/remove-at-path items-vec path)]
    (-> state
        (assoc-in items-path new-items)
        (h/mark-dirty))))

(defn handle-reorder-items
  "Reorder items using from-idx and to-idx.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - from-idx: Source index
   - to-idx: Target index
   
   Returns: Updated state"
  [state config from-idx to-idx]
  (let [items-path (:items-path config)
        items-vec (get-in state items-path [])
        item (nth items-vec from-idx)
        without (vec (concat (subvec items-vec 0 from-idx)
                             (subvec items-vec (inc from-idx))))
        reordered (vec (concat (subvec without 0 to-idx)
                               [item]
                               (subvec without to-idx)))]
    (-> state
        (assoc-in items-path reordered)
        (h/mark-dirty))))
