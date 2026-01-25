(ns laser-show.events.handlers.chain.structure
  "Structure-related handlers for chain operations.
   
   Contains:
   - Group operations (create-empty-group)
   - Item CRUD (add-item, remove-item-at-path, reorder-items)
   
   NOTE: Selection-based operations (group-selected, delete-selected, DnD, copy/paste)
   are handled by the list.clj component directly using ID-based operations."
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


;; ============================================================================
;; Item CRUD Operations
;; ============================================================================


(defn handle-add-item
  "Add an item (effect/preset) to the chain.
   
   NOTE: This handler does not manage selection. Callers should dispatch
   a separate :list/select-item event if selection is needed.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path
   - event: Map with :item and optional :parent-path for nested insertion
            :item - Item to add (will be given :id and :enabled? if missing)
            :parent-path - Optional path within items to add to (e.g., [0 :effects])
   
   Returns: Updated state with item added"
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
                            (assoc-in target-path []))]
    (-> state-with-target
        (update-in target-path conj item-with-fields)
        h/mark-dirty)))

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
