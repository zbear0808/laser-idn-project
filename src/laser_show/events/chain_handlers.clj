(ns laser-show.events.chain-handlers
  "Generic handler helpers for hierarchical chain operations.
   
   Supports effects chains, projector chains, and cue chains with
   minimal domain-specific configuration.
   
   Configuration map structure:
   {:items-path [:effects :cells [col row] :effects]  ; Path to items vector in state
    :ui-path [:ui :dialogs :effect-chain-editor :data] ; Path to UI state
    :clipboard-key :effects-clipboard}                 ; Optional clipboard key
   
   All helpers are pure functions that take state and config, and return
   updated state (not effect maps). Callers wrap results in {:state ...}."
  (:require
   [clojure.tools.logging :as log]
   [laser-show.animation.chains :as chains]
   [laser-show.animation.effects :as effects]))


;; Helper Functions (internal)


(defn- mark-dirty
  "Mark project as having unsaved changes."
  [state]
  (assoc-in state [:project :dirty?] true))

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
                    ctrl?
                    (if (contains? current-paths path)
                      (disj current-paths path)
                      (conj current-paths path))
                    
                    shift?
                    (if last-selected
                      ;; Range select: get all paths between anchor and clicked in visual order
                      (let [all-paths (vec (chains/paths-in-chain items-vec))
                            anchor-idx (.indexOf all-paths last-selected)
                            target-idx (.indexOf all-paths path)]
                        (if (and (>= anchor-idx 0) (>= target-idx 0))
                          (let [start-idx (min anchor-idx target-idx)
                                end-idx (max anchor-idx target-idx)]
                            (into #{} (subvec all-paths start-idx (inc end-idx))))
                          ;; Fallback if paths not found - just select clicked
                          #{path}))
                      ;; No anchor - just select clicked
                      #{path})
                    
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
          mark-dirty))))


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
            mark-dirty))
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
        mark-dirty)))

(defn handle-group-selected
  "Group currently selected items into a new group.
   
   Uses two-phase operation to avoid index-shifting bugs:
   1. Collect selected items by ID
   2. Remove items by ID set
   3. Insert new group at computed position
   
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
      (let [;; Filter to root paths only
            root-paths (filter-to-root-paths selected-paths)
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
            mark-dirty))
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
    (if (chains/group? item)
      (let [group-items (:items item [])
            is-top-level? (= 1 (count path))
            group-idx (if is-top-level? (first path) (last path))
            parent-path (if is-top-level? [] (vec (butlast (butlast path))))
            parent-vec (if is-top-level?
                         items-vec
                         (get-in items-vec (conj parent-path :items) []))
            ;; Remove group and insert its items
            new-parent (vec (concat (subvec parent-vec 0 group-idx)
                                    group-items
                                    (subvec parent-vec (inc group-idx))))
            ;; Update the items
            new-items (if is-top-level?
                        new-parent
                        (assoc-in items-vec (conj parent-path :items) new-parent))]
        (-> state
            (set-items config new-items)
            (set-selected-paths config #{})
            mark-dirty))
      state)))

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
          mark-dirty)
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
            mark-dirty)))))

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
      mark-dirty))


;; Curve Editor Handlers


(defn handle-add-curve-point
  "Generic curve point addition for any chain.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event-data: {:effect-path [...] :channel :r :x 128 :y 200}
   
   Returns: Updated state"
  [state config {:keys [effect-path channel x y]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        items-vec (vec (get-items state config))
        current-points (get-in items-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        new-point [(int x) (int y)]
        new-points (->> (conj current-points new-point)
                        (sort-by first)
                        vec)
        updated-items (assoc-in items-vec (conj (vec effect-path) :params param-key) new-points)]
    (set-items state config updated-items)))

(defn handle-update-curve-point
  "Generic curve point update for any chain.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event-data: {:effect-path [...] :channel :r :point-idx 1 :x 128 :y 200}
   
   Returns: Updated state"
  [state config {:keys [effect-path channel point-idx x y]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        items-vec (vec (get-items state config))
        current-points (get-in items-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        num-points (count current-points)
        ;; Corner points (first and last) can only move in Y
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))
        current-point (nth current-points point-idx [0 0])
        updated-point (if is-corner?
                        [(first current-point) (int y)]  ;; Keep original X for corners
                        [(int x) (int y)])
        updated-points (assoc current-points point-idx updated-point)
        sorted-points (->> updated-points
                          (sort-by first)
                          vec)
        updated-items (assoc-in items-vec (conj (vec effect-path) :params param-key) sorted-points)]
    (set-items state config updated-items)))

(defn handle-remove-curve-point
  "Generic curve point removal for any chain.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event-data: {:effect-path [...] :channel :r :point-idx 1}
   
   Returns: Updated state"
  [state config {:keys [effect-path channel point-idx]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        items-vec (vec (get-items state config))
        current-points (get-in items-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        num-points (count current-points)
        ;; Cannot remove corner points (first and last)
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))]
    (if is-corner?
      state  ;; No change for corner points
      (let [updated-points (vec (concat (subvec current-points 0 point-idx)
                                        (subvec current-points (inc point-idx))))
            updated-items (assoc-in items-vec (conj (vec effect-path) :params param-key) updated-points)]
        (set-items state config updated-items)))))

(defn handle-set-active-curve-channel
  "Generic active curve channel setter for any chain.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :ui-path
   - event-data: {:effect-path [...] :tab-id :r}
   
   Returns: Updated state"
  [state config {:keys [effect-path tab-id]}]
  (let [ui-path (:ui-path config)]
    (assoc-in state (conj ui-path :ui-modes effect-path :active-curve-channel) tab-id)))

(defn handle-update-spatial-params
  "Generic spatial parameter update for any chain.
   Updates multiple related parameters from spatial drag (e.g., x and y together).
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event-data: {:effect-path [...] :point-id :center :x 0.5 :y 0.3 :param-map {...}}
   
   Returns: Updated state"
  [state config {:keys [effect-path point-id x y param-map]}]
  (let [point-params (get param-map point-id)]
    (if point-params
      (let [x-key (:x point-params)
            y-key (:y point-params)
            items-vec (vec (get-items state config))
            updated-items (-> items-vec
                             (assoc-in (conj (vec effect-path) :params x-key) x)
                             (assoc-in (conj (vec effect-path) :params y-key) y))]
        (set-items state config updated-items))
      state)))


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
     :ui-path (case domain
                :effect-chains [:ui :dialogs :effect-chain-editor :data]
                :cue-chains [:cue-chain-editor]
                :projector-effects [:ui :projector-effect-ui-state entity-key])
     :domain domain
     :entity-key entity-key}))


;; Item Effects Config (for effects within cue chain items)


(defn item-effects-config
  "Create configuration for effects within a cue chain item.
   
   This is for managing effects attached to presets/groups within a cue chain.
   
   Parameters:
   - col: Grid column
   - row: Grid row
   - item-path: Path to the item within the cue chain (e.g., [0] or [1 :items 0])
   
   Returns: Configuration map"
  [col row item-path]
  {:items-path (vec (concat [:chains :cue-chains [col row] :items] item-path [:effects]))
   :ui-path [:cue-chain-editor :item-effects-ui (vec item-path)]
   :domain :item-effects
   :entity-key {:col col :row row :item-path item-path}})
