(ns laser-show.events.handlers.chain
  "Generic handler helpers for hierarchical chain operations.
   
   Supports effects chains, projector chains, and cue chains with
   minimal domain-specific configuration.
   
   Configuration map structure:
   {:items-path [:effects :cells [col row] :effects]  ; Path to items vector in state
    :ui-path [:ui :dialogs :effect-chain-editor]      ; Path to UI state (FLATTENED - no :data)
    :clipboard-key :effects-clipboard}                 ; Optional clipboard key
   
   All helpers are pure functions that take state and config, and return
   updated state (not effect maps). Callers wrap results in {:state ...}."
  (:require
   [clojure.tools.logging :as log]
   [laser-show.events.helpers :as h]
   [laser-show.events.handlers.effect-params :as effect-params]
   [laser-show.animation.chains :as chains]))


;; Helper Functions (internal)


(defn- get-items
  "Get the items vector from state using config."
  [state config]
  (get-in state (:items-path config) []))

(defn- set-items
  "Set the items vector in state using config."
  [state config items]
  (assoc-in state (:items-path config) items))

(defn- get-ui-state
  "Get the UI state map from state using config."
  [state config]
  (get-in state (:ui-path config) {}))

(defn- set-ui-state
  "Set the UI state map in state using config."
  [state config ui-state]
  (assoc-in state (:ui-path config) ui-state))

(defn- update-ui-state
  "Update the UI state map in state using config."
  [state config f & args]
  (apply update-in state (:ui-path config) f args))

(defn- get-selected-paths
  "Get the currently selected paths from UI state."
  [state config]
  (get-in state (conj (:ui-path config) :selected-paths) #{}))

(defn- set-selected-paths
  "Set the selected paths in UI state."
  [state config paths]
  (assoc-in state (conj (:ui-path config) :selected-paths) paths))

(defn- get-last-selected-path
  "Get the last selected path (anchor for shift+click)."
  [state config]
  (get-in state (conj (:ui-path config) :last-selected-path)))

(defn- set-last-selected-path
  "Set the last selected path (anchor for shift+click)."
  [state config path]
  (assoc-in state (conj (:ui-path config) :last-selected-path) path))

(defn- get-dragging-paths
  "Get the currently dragging paths from UI state."
  [state config]
  (get-in state (conj (:ui-path config) :dragging-paths) #{}))

(defn- set-dragging-paths
  "Set the dragging paths in UI state."
  [state config paths]
  (assoc-in state (conj (:ui-path config) :dragging-paths) paths))

(defn- get-renaming-path
  "Get the path of item being renamed."
  [state config]
  (get-in state (conj (:ui-path config) :renaming-path)))

(defn- set-renaming-path
  "Set the path of item being renamed."
  [state config path]
  (assoc-in state (conj (:ui-path config) :renaming-path) path))

(defn- path-is-ancestor?
  "Check if ancestor-path is an ancestor of descendant-path.
   [1] is ancestor of [1 :items 0]."
  [ancestor-path descendant-path]
  (and (< (count ancestor-path) (count descendant-path))
       (= (vec ancestor-path) (vec (take (count ancestor-path) descendant-path)))))

(defn- filter-to-root-paths
  "Remove paths that are descendants of other paths in the set.
   If [1] is selected, [1 :items 0] should be excluded since it moves with its parent."
  [paths]
  (set (filter
         (fn [path]
           (not-any? (fn [other]
                       (and (not= other path)
                            (path-is-ancestor? other path)))
                     paths))
         paths)))

(defn- get-descendant-paths
  "Get all descendant paths of a group at the given path.
   Returns paths like [1 :items 0], [1 :items 1], [1 :items 0 :items 0] etc."
  [items-vec group-path]
  (let [group (get-in items-vec (vec group-path))]
    (when (chains/group? group)
      (let [group-items (:items group [])]
        (chains/paths-in-chain group-items (conj (vec group-path) :items))))))

(defn- normalize-selected-paths
  "Remove redundant child paths when a group AND all its children are selected.
   
   This handles the case where selecting a group plus all its children
   should be treated as just selecting the group, allowing grouping with
   other items at the same level.
   
   Algorithm:
   1. For each selected path that is a group
   2. Get all descendant paths of that group
   3. If ALL descendants are also selected, remove them (keep only the group)
   
   Example: Given chain [empty-group, group-b{child}] where selected = #{[0] [1] [1 :items 0]}
   - [1] is a group with descendant [1 :items 0]
   - [1 :items 0] is in selected, so it's redundant
   - Result: #{[0] [1]} - both at top level, can be grouped"
  [selected-paths items-vec]
  (let [;; Find which selected paths are groups
        group-paths (filter #(chains/group? (get-in items-vec (vec %))) selected-paths)]
    (reduce
      (fn [paths group-path]
        (let [descendant-paths (set (get-descendant-paths items-vec group-path))]
          (if (and (seq descendant-paths)
                   (every? #(contains? paths %) descendant-paths))
            ;; All descendants are selected - remove them as redundant
            (apply disj paths descendant-paths)
            ;; Not all descendants selected - keep as is
            paths)))
      (set selected-paths)
      group-paths)))

(defn- remove-at-path
  "Remove an item at the given path from a nested vector structure."
  [items path]
  (chains/remove-at-path items path))

(defn- insert-at-path
  "Insert an item at the given path in a nested vector structure.
   If into-group? is true, insert into the group's items at the path."
  [items path item into-group?]
  (let [items-vec (vec items)
        path-vec (vec path)]
    (if into-group?
      ;; Insert into group's items
      (update-in items-vec (conj path-vec :items) #(conj (vec (or % [])) item))
      ;; Insert before position - use chains module
      (chains/insert-at-path items-vec path-vec item))))

(defn- collect-items-by-ids
  "Recursively collect all items matching the given ID set.
   Returns a vector of items (preserving order as encountered)."
  [items id-set]
  (reduce
    (fn [acc item]
      (let [acc (if (contains? id-set (:id item))
                  (conj acc item)
                  acc)]
        ;; Recurse into groups
        (if (chains/group? item)
          (into acc (collect-items-by-ids (:items item []) id-set))
          acc)))
    []
    items))

(defn- remove-items-by-ids
  "Recursively remove all items matching the given ID set.
   Returns the chain with matching items filtered out."
  [items id-set]
  (vec
    (keep
      (fn [item]
        (when-not (contains? id-set (:id item))
          (if (chains/group? item)
            (update item :items #(remove-items-by-ids % id-set))
            item)))
      items)))

(defn- insert-items-at-index
  "Insert items at a specific index in the vector."
  [v idx items]
  (let [safe-idx (max 0 (min idx (count v)))]
    (into (subvec v 0 safe-idx)
          (concat items (subvec v safe-idx)))))

(defn- regenerate-ids
  "Recursively regenerate :id fields for items and groups.
   Ensures pasted items have unique IDs."
  [item]
  (cond
    ;; Group - regenerate ID and recurse into items
    (chains/group? item)
    (-> item
        (assoc :id (random-uuid))
        (update :items #(mapv regenerate-ids %)))
    
    ;; Leaf item - just regenerate ID
    :else
    (assoc item :id (random-uuid))))

(defn- make-group
  "Create a new group with given name and items."
  [name items]
  {:type :group
   :id (random-uuid)
   :name name
   :collapsed? false
   :enabled? true
   :items (vec items)})


;; Selection Operations


(defn handle-select-item
  "Generic item selection handler with Ctrl/Shift support.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path and :ui-path
   - path: Path to select (e.g., [0] or [1 :items 0])
   - ctrl?: If true, toggle selection
   - shift?: If true, range select from anchor
   
   Returns: Updated state"
  [state config path ctrl? shift?]
  (log/debug "handle-select-item: path=" path "ctrl?=" ctrl? "shift?=" shift?)
  (let [current-paths (get-selected-paths state config)
        last-selected (get-last-selected-path state config)
        items-vec (get-items state config)
        new-paths (cond
                    ;; Ctrl+click: toggle selection
                    ctrl?
                    (if (contains? current-paths path)
                      (disj current-paths path)
                      (conj current-paths path))
                    
                    ;; Shift+click with anchor: range select
                    (and shift? last-selected)
                    (let [all-paths (vec (chains/paths-in-chain items-vec))
                          anchor-idx (.indexOf all-paths last-selected)
                          target-idx (.indexOf all-paths path)]
                      (if (and (>= anchor-idx 0) (>= target-idx 0))
                        (let [start-idx (min anchor-idx target-idx)
                              end-idx (max anchor-idx target-idx)]
                          (into #{} (subvec all-paths start-idx (inc end-idx))))
                        ;; Fallback if paths not found - just select clicked
                        #{path}))
                    
                    ;; Shift+click without anchor, or regular click: select single
                    :else
                    #{path})
        ;; Only update anchor on regular click or ctrl+click, NOT on shift+click
        update-anchor? (not shift?)]
    (-> state
        (set-selected-paths config new-paths)
        (cond-> update-anchor?
          (set-last-selected-path config path)))))

(defn handle-select-all
  "Generic select-all handler.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: Updated state"
  [state config]
  (let [items-vec (get-items state config)
        all-paths (into #{} (chains/paths-in-chain items-vec))]
    (set-selected-paths state config all-paths)))

(defn handle-clear-selection
  "Generic clear-selection handler.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: Updated state"
  [state config]
  (-> state
      (set-selected-paths config #{})
      (set-last-selected-path config nil)))


;; Copy/Paste Operations


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
  (let [selected-paths (get-selected-paths state config)
        items-vec (get-items state config)
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
          items-with-new-ids (mapv regenerate-ids items)
          current-items (vec (get-items state config))
          selected-paths (get-selected-paths state config)
          
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
          (set-items config new-items)
          (set-selected-paths config new-paths)
          h/mark-dirty))))


;; Delete Operations


(defn handle-delete-selected
  "Generic delete handler - removes all selected items.
   
   When deleting a group, all children are deleted with it.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: Updated state"
  [state config]
  (let [selected-paths (get-selected-paths state config)
        items-vec (get-items state config)]
    (if (seq selected-paths)
      (let [;; Filter to root paths only - if a group is selected, don't separately delete
            ;; its children (they'll be deleted with the group)
            root-paths (filter-to-root-paths selected-paths)
            ;; Sort paths by depth (deepest first) to avoid index issues
            sorted-paths (sort-by (comp - count) root-paths)
            ;; Remove each path
            new-items (reduce remove-at-path items-vec sorted-paths)]
        (-> state
            (set-items config new-items)
            (set-selected-paths config #{})
            h/mark-dirty))
      state)))


;; Group Operations


(defn handle-create-empty-group
  "Create a new empty group at the end of the chain.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - name: Optional group name (defaults to 'New Group')
   
   Returns: Updated state"
  [state config & [name]]
  (let [group-name (or name "New Group")
        new-group (make-group group-name [])]
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
        selected-paths (get-selected-paths state config)
        items-vec (get-items state config)]
    (if (seq selected-paths)
      (let [;; Normalize selection: if a group + ALL its children are selected,
            ;; treat it as just the group being selected. This allows grouping
            ;; items at different visual levels when their parent groups are also selected.
            normalized-paths (normalize-selected-paths selected-paths items-vec)
            ;; Filter to root paths only
            root-paths (filter-to-root-paths normalized-paths)
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
            after-remove (remove-items-by-ids items-vec ids-to-remove)
            
            ;; Calculate adjusted insert position
            items-removed-before-insert (count
                                          (filter
                                            (fn [path]
                                              (and (= 1 (count path))
                                                   (< (first path) insert-at-top-level)))
                                            sorted-paths))
            adjusted-insert (- insert-at-top-level items-removed-before-insert)
            
            ;; Create the new group
            new-group (make-group name selected-items)
            
            ;; PHASE 3: Insert new group
            new-items (insert-items-at-index after-remove adjusted-insert [new-group])
            
            ;; Select the new group
            new-group-path [adjusted-insert]]
        (-> state
            (set-items config new-items)
            (set-selected-paths config #{new-group-path})
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
  (let [items-vec (get-items state config)
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
            (set-items config new-items)
            (set-selected-paths config #{})
            h/mark-dirty)))))

(defn handle-toggle-collapse
  "Toggle a group's collapsed state.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - path: Path to the group
   
   Returns: Updated state"
  [state config path]
  (let [items-vec (get-items state config)
        item (get-in items-vec path)]
    (if (chains/group? item)
      (update-in state (:items-path config)
                 (fn [items]
                   (update-in items (conj path :collapsed?) not)))
      state)))


;; Rename Operations


(defn handle-start-rename
  "Start renaming a group (shows inline text field).
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - path: Path to the group
   
   Returns: Updated state"
  [state config path]
  (set-renaming-path state config path))

(defn handle-cancel-rename
  "Cancel renaming a group.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: Updated state"
  [state config]
  (set-renaming-path state config nil))

(defn handle-rename-item
  "Rename a group.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - path: Path to the group
   - new-name: New name for the group
   
   Returns: Updated state"
  [state config path new-name]
  (let [items-vec (get-items state config)
        item (get-in items-vec path)]
    (if (chains/group? item)
      (-> state
          (assoc-in (into (:items-path config) (conj path :name)) new-name)
          (set-renaming-path config nil)
          h/mark-dirty)
      state)))


;; Drag-and-Drop Operations


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
  (let [selected-paths (get-selected-paths state config)
        ;; If dragging a selected item, drag all selected items
        dragging-paths (if (contains? selected-paths initiating-path)
                         selected-paths
                         #{initiating-path})
        ;; If dragging unselected item, update selection
        new-selected (if (contains? selected-paths initiating-path)
                       selected-paths
                       #{initiating-path})]
    (-> state
        (set-dragging-paths config dragging-paths)
        (set-selected-paths config new-selected))))

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
  (let [items-vec (get-items state config)
        dragging-paths (get-dragging-paths state config)
        root-paths (filter-to-root-paths dragging-paths)
        target-path (chains/find-path-by-id items-vec target-id)
        ;; Check if target is in dragging paths or descendant
        target-in-drag? (or (contains? root-paths target-path)
                           (some #(path-is-ancestor? % target-path) root-paths))]
    (if (or (empty? root-paths) target-in-drag?)
      ;; Invalid move - just clear dragging state
      (set-dragging-paths state config nil)
      ;; Use centralized move-items-to-target
      (let [new-items (chains/move-items-to-target items-vec root-paths target-id drop-position)]
        (-> state
            (set-items config new-items)
            (set-dragging-paths config nil)
            (set-selected-paths config #{})
            h/mark-dirty)))))

(defn handle-clear-drag-state
  "Clear the dragging state.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: Updated state"
  [state config]
  (set-dragging-paths state config nil))


;; Enabled State Operations


(defn handle-set-item-enabled
  "Set the enabled state of an item at a path.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - path: Path to the item
   - enabled?: New enabled state
   
   Returns: Updated state"
  [state config path enabled?]
  (-> state
      (assoc-in (into (:items-path config) (conj (vec path) :enabled?)) enabled?)
      (h/mark-dirty)))




;; Effect Parameter Operations (Delegate to effect-params)


(defn handle-add-curve-point
  "Thin wrapper that delegates to effect-params.
   Extracts params-path from config and effect-path, then calls effect-params."
  [state config {:keys [effect-path channel x y]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/add-curve-point state params-path channel x y)))

(defn handle-update-curve-point
  "Thin wrapper that delegates to effect-params.
   Extracts params-path from config and effect-path, then calls effect-params."
  [state config {:keys [effect-path channel point-idx x y]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/update-curve-point state params-path channel point-idx x y)))

(defn handle-remove-curve-point
  "Thin wrapper that delegates to effect-params.
   Extracts params-path from config and effect-path, then calls effect-params."
  [state config {:keys [effect-path channel point-idx]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/remove-curve-point state params-path channel point-idx)))

(defn handle-set-active-curve-channel
  "Thin wrapper that delegates to effect-params.
   Extracts UI path from config and effect-path, then calls effect-params."
  [state config {:keys [effect-path tab-id]}]
  (let [ui-path (conj (:ui-path config) :ui-modes effect-path)]
    (effect-params/set-active-curve-channel state ui-path tab-id)))

(defn handle-update-spatial-params
  "Thin wrapper that delegates to effect-params.
   Extracts params-path from config and effect-path, then calls effect-params."
  [state config {:keys [effect-path point-id x y param-map]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/update-spatial-params state params-path point-id x y param-map)))

(defn handle-update-scale-params
  "Thin wrapper that delegates to effect-params.
   Updates x-scale and y-scale parameters from scale drag operation."
  [state config {:keys [effect-path x-scale y-scale]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/update-scale-params state params-path x-scale y-scale)))

(defn handle-update-rotation-param
  "Thin wrapper that delegates to effect-params.
   Updates angle parameter from rotation drag operation."
  [state config {:keys [effect-path angle]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/update-rotation-param state params-path angle)))

(defn handle-reset-params
  "Thin wrapper that delegates to effect-params.
   Resets effect parameters to their default values."
  [state config {:keys [effect-path defaults-map]}]
  (let [items-path (:items-path config)
        params-path (vec (concat items-path effect-path [:params]))]
    (effect-params/reset-params state params-path defaults-map)))


;; Phase 2: Parameter Update Handlers


(defn handle-update-param
  "Update an effect parameter value.
   Extracts value from :value or :fx/event.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event: Map with :effect-path, :param-key, and :value or :fx/event
   
   Returns: Updated state"
  [state config {:keys [effect-path param-key] :as event}]
  (let [value (or (:fx/event event) (:value event))
        items-path (:items-path config)
        items-vec (vec (get-in state items-path []))
        updated-items (assoc-in items-vec (conj (vec effect-path) :params param-key) value)]
    (assoc-in state items-path updated-items)))

(defn handle-update-param-from-text
  "Update a parameter from text field input.
   Uses h/parse-and-clamp-from-text-event for parsing.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event: Map with :effect-path, :param-key, :min, :max, and :fx/event
   
   Returns: Updated state (unchanged if parsing fails)"
  [state config {:keys [effect-path param-key min max] :as event}]
  (if-let [clamped (h/parse-and-clamp-from-text-event (:fx/event event) min max)]
    (let [items-path (:items-path config)
          items-vec (vec (get-in state items-path []))
          updated-items (assoc-in items-vec (conj (vec effect-path) :params param-key) clamped)]
      (assoc-in state items-path updated-items))
    state))

(defn handle-update-color-param
  "Update a color parameter from ColorPicker's ActionEvent.
   Extracts the color from the ColorPicker source and converts to RGB vector.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event: Map with :effect-path, :param-key, and :fx/event (ActionEvent)
   
   Returns: Updated state"
  [state config {:keys [effect-path param-key] :as event}]
  (let [action-event (:fx/event event)
        color-picker (.getSource action-event)
        color (.getValue color-picker)
        rgb-value [(int (* 255 (.getRed color)))
                   (int (* 255 (.getGreen color)))
                   (int (* 255 (.getBlue color)))]
        items-path (:items-path config)
        items-vec (vec (get-in state items-path []))
        updated-items (assoc-in items-vec (conj (vec effect-path) :params param-key) rgb-value)]
    (assoc-in state items-path updated-items)))


;; Phase 3: Effect CRUD Handlers


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


;; Phase 4: UI Mode Handlers


(defn handle-set-ui-mode
  "Set visual/numeric mode for effect parameter UI.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :ui-path
   - effect-path: Path to the effect
   - mode: :visual or :numeric
   
   Returns: Updated state"
  [state config effect-path mode]
  (assoc-in state (conj (:ui-path config) :ui-modes effect-path) mode))


;; Convenience Functions for Creating Configs


(defn chain-config
  "Create a unified chain configuration for any domain.
   
   This is the primary config factory for the new :chains-based architecture.
   All three chain types use consistent paths under [:chains <domain> <key>].
   
   Usage:
   (chain-config :effect-chains [col row])
   (chain-config :cue-chains [col row])
   (chain-config :projector-effects projector-id)
   
   Parameters:
   - domain: One of :effect-chains, :cue-chains, :projector-effects
   - entity-key: The key within the domain ([col row] or projector-id)
   
   Returns: Configuration map with :items-path, :ui-path, :domain, :entity-key"
  [domain entity-key]
  (let [base-path [:chains domain entity-key]]
    {:items-path (conj base-path :items)
     :metadata-path base-path
     ;; FLATTENED: Dialog fields live alongside :open?, not under :data
     :ui-path (case domain
                :effect-chains [:ui :dialogs :effect-chain-editor]
                :cue-chains [:ui :dialogs :cue-chain-editor]
                :projector-effects [:ui :projector-effect-ui-state entity-key])
     :domain domain
     :entity-key entity-key}))

;; Generic Chain Event Handler


(defn handle
 "Handle generic chain events that work across all chain types.
  
  These events use :domain and :entity-key to create a config and
  delegate to chain-handlers functions.
  
  This is the main entry point for :chain/* events from the dispatcher."
 [{:keys [event/type domain entity-key state] :as event}]
 (log/debug "chain/handle ENTER - type:" type "domain:" domain "entity-key:" entity-key)
 (let [config (chain-config domain entity-key)]
   (case type
     :chain/set-items
     (do
       (log/debug "chain/set-items - items count:" (count (:items event))
                  "items-path:" (:items-path config))
       {:state (-> state
                   (assoc-in (:items-path config) (:items event))
                   (h/mark-dirty))})
     
     ;; NOTE: :chain/update-selection removed - selection state is now managed
     ;; directly in [:list-ui component-id] and read via subs/list-ui-state
     ;; See list-selection-state-consolidation plan for details.
     
     :chain/select-item
     {:state (handle-select-item state config (:path event) (:ctrl? event) (:shift? event))}
     
     :chain/select-all
     {:state (handle-select-all state config)}
     
     :chain/clear-selection
     {:state (handle-clear-selection state config)}
     
     :chain/delete-selected
     {:state (handle-delete-selected state config)}
     
     :chain/group-selected
     {:state (handle-group-selected state config (:name event))}
     
     :chain/ungroup
     {:state (handle-ungroup state config (:path event))}
     
     :chain/toggle-collapse
     {:state (handle-toggle-collapse state config (:path event))}
     
     :chain/start-rename
     {:state (handle-start-rename state config (:path event))}
     
     :chain/rename-item
     {:state (handle-rename-item state config (:path event) (:new-name event))}
     
     :chain/cancel-rename
     {:state (handle-cancel-rename state config)}
     
     :chain/set-item-enabled
     {:state (handle-set-item-enabled state config (:path event) (:enabled? event))}
     
     :chain/start-drag
     {:state (handle-start-drag state config (:initiating-path event))}
     
     :chain/move-items
     {:state (handle-move-items state config (:target-id event) (:drop-position event))}
     
     :chain/clear-drag-state
     {:state (handle-clear-drag-state state config)}
     
     :chain/add-curve-point
     {:state (handle-add-curve-point state config event)}
     
     :chain/update-curve-point
     {:state (handle-update-curve-point state config event)}
     
     :chain/remove-curve-point
     {:state (handle-remove-curve-point state config event)}
     
     :chain/set-active-curve-channel
     {:state (handle-set-active-curve-channel state config event)}
     
     :chain/update-spatial-params
     {:state (handle-update-spatial-params state config event)}
     
     :chain/update-scale-params
     {:state (handle-update-scale-params state config event)}
     
     :chain/update-rotation-param
     {:state (handle-update-rotation-param state config event)}
     
     :chain/reset-params
     {:state (handle-reset-params state config event)}
     
     :chain/create-empty-group
     {:state (handle-create-empty-group state config (:name event))}
     
     ;; Phase 2: Parameter updates
     :chain/update-param
     {:state (handle-update-param state config event)}
     
     :chain/update-param-from-text
     {:state (handle-update-param-from-text state config event)}
     
     :chain/update-color-param
     {:state (handle-update-color-param state config event)}
     
     ;; Phase 3: Effect CRUD
     :chain/add-item
     {:state (handle-add-item state config event)}
     
     :chain/remove-item-at-path
     {:state (handle-remove-item-at-path state config (:path event))}
     
     :chain/reorder-items
     {:state (handle-reorder-items state config (:from-idx event) (:to-idx event))}
     
     ;; Phase 4: UI mode
     :chain/set-ui-mode
     {:state (handle-set-ui-mode state config (:effect-path event) (:mode event))}
     
     ;; Unknown chain event
     (do
       (log/warn "Unknown chain event type:" type)
       {}))))
