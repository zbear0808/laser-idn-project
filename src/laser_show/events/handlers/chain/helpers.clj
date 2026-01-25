(ns laser-show.events.handlers.chain.helpers
  "Helper functions for chain operations.
   
   Contains:
   - State access helpers (get/set items, UI state, selection, etc.)
   - Path manipulation helpers (ancestors, filtering, descendants)
   - Collection operations (remove-at-path, insert, collect by IDs)
   
   This module has NO dependencies on other chain sub-modules to avoid cycles."
  (:require
   [laser-show.animation.chains :as chains]))


;; ============================================================================
;; State Access Helpers
;; ============================================================================


(defn get-items
  "Get the items vector from state using config."
  [state config]
  (get-in state (:items-path config) []))

(defn set-items
  "Set the items vector in state using config."
  [state config items]
  (assoc-in state (:items-path config) items))

(defn get-ui-state
  "Get the UI state map from state using config."
  [state config]
  (get-in state (:ui-path config) {}))

(defn set-ui-state
  "Set the UI state map in state using config."
  [state config ui-state]
  (assoc-in state (:ui-path config) ui-state))

(defn update-ui-state
  "Update the UI state map in state using config."
  [state config f & args]
  (apply update-in state (:ui-path config) f args))

(defn get-selected-paths
  "Get the currently selected paths from UI state."
  [state config]
  (get-in state (conj (:ui-path config) :selected-paths) #{}))

(defn set-selected-paths
  "Set the selected paths in UI state."
  [state config paths]
  (assoc-in state (conj (:ui-path config) :selected-paths) paths))

(defn get-last-selected-path
  "Get the last selected path (anchor for shift+click)."
  [state config]
  (get-in state (conj (:ui-path config) :last-selected-path)))

(defn set-last-selected-path
  "Set the last selected path (anchor for shift+click)."
  [state config path]
  (assoc-in state (conj (:ui-path config) :last-selected-path) path))

(defn get-dragging-paths
  "Get the currently dragging paths from UI state."
  [state config]
  (get-in state (conj (:ui-path config) :dragging-paths) #{}))

(defn set-dragging-paths
  "Set the dragging paths in UI state."
  [state config paths]
  (assoc-in state (conj (:ui-path config) :dragging-paths) paths))

(defn get-renaming-path
  "Get the path of item being renamed."
  [state config]
  (get-in state (conj (:ui-path config) :renaming-path)))

(defn set-renaming-path
  "Set the path of item being renamed."
  [state config path]
  (assoc-in state (conj (:ui-path config) :renaming-path) path))


;; ============================================================================
;; Path Manipulation Helpers
;; ============================================================================


(defn path-is-ancestor?
  "Check if ancestor-path is an ancestor of descendant-path.
   [1] is ancestor of [1 :items 0]."
  [ancestor-path descendant-path]
  (and (< (count ancestor-path) (count descendant-path))
       (= (vec ancestor-path) (vec (take (count ancestor-path) descendant-path)))))

(defn filter-to-root-paths
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

(defn get-descendant-paths
  "Get all descendant paths of a group at the given path.
   Returns paths like [1 :items 0], [1 :items 1], [1 :items 0 :items 0] etc."
  [items-vec group-path]
  (let [group (get-in items-vec (vec group-path))]
    (when (chains/group? group)
      (let [group-items (:items group [])]
        (chains/paths-in-chain group-items (conj (vec group-path) :items))))))

(defn normalize-selected-paths
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


;; ============================================================================
;; Collection Operations
;; ============================================================================


(defn remove-at-path
  "Remove an item at the given path from a nested vector structure."
  [items path]
  (chains/remove-at-path items path))

(defn insert-at-path
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

(defn collect-items-by-ids
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

(defn remove-items-by-ids
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

(defn insert-items-at-index
  "Insert items at a specific index in the vector."
  [v idx items]
  (let [safe-idx (max 0 (min idx (count v)))]
    (into (subvec v 0 safe-idx)
          (concat items (subvec v safe-idx)))))

(defn regenerate-ids
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

(defn make-group
  "Create a new group with given name and items."
  [name items]
  {:type :group
   :id (random-uuid)
   :name name
   :collapsed? false
   :enabled? true
   :items (vec items)})


;; ============================================================================
;; Configuration Factory
;; ============================================================================


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
