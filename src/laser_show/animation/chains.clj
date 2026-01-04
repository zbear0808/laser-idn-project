(ns laser-show.animation.chains
  "Generic chain management for hierarchical item lists with groups.
   
   Supports both effect chains and cue chains (preset chains). Chains are
   vectors of items where each item can be either:
   
   1. A leaf item (effect, preset, etc.) - any map without :type :group
   2. A group - {:type :group :items [...] :enabled? bool :name \"...\" ...}
   
   Groups can be nested up to `max-nesting-depth` levels deep.
   
   Key concepts:
   - Items are identified by UUID in their :id field
   - Paths are vectors like [0], [1 :items 0], [1 :items 2 :items 0]
   - Items can be enabled/disabled via :enabled? field (default: true)
   - Flattening respects enabled? at all levels")


;; Type Predicates


(defn group?
  "Check if an item is a group (contains nested items).
   Groups have {:type :group :items [...]}."
  [item]
  (= :group (:type item)))

(defn item?
  "Check if an item is a leaf item (not a group).
   Items without :type or with any :type other than :group are leaf items."
  [item]
  (not= :group (:type item)))

(defn item-enabled?
  "Check if an item is enabled.
   Returns true if :enabled? is true or missing (default enabled)."
  [item]
  (:enabled? item true))


;; Nesting Depth


(def max-nesting-depth
  "Maximum allowed nesting depth for groups (0 = flat, 3 = up to 3 levels)."
  3)

(defn nesting-depth
  "Calculate the maximum nesting depth of a chain.
   Returns 0 for flat chains, 1 for one level of groups, etc."
  [items]
  (if (empty? items)
    0
    (apply max
      (map (fn [item]
             (if (group? item)
               (inc (nesting-depth (:items item [])))
               0))
           items))))

(defn can-add-group-at-path?
  "Check if a new group can be added at the given path without exceeding max depth.
   Path is a vector like [1 :items 0] where :items segments indicate group nesting."
  [_chain path]
  (let [current-depth (count (filter #(= :items %) path))]
    (< current-depth max-nesting-depth)))


;; Chain Flattening


(defn flatten-chain
  "Flatten a nested chain into a sequence of leaf items for processing.
   Respects enabled? flags at both item and group level.
   Groups with enabled?=false have all their items skipped.
   
   This is used at processing time to get the linear list of items to process."
  [items]
  (mapcat
    (fn [item]
      (cond
        ;; Disabled item - skip entirely
        (not (:enabled? item true))
        []
        
        ;; Group - recursively flatten if enabled
        (group? item)
        (flatten-chain (:items item []))
        
        ;; Leaf item - include it
        :else
        [item]))
    items))


;; Path Operations


(defn paths-in-chain
  "Generate all paths in a chain for iteration or select-all operations.
   Returns a sequence of paths like [[0] [1] [1 :items 0] [1 :items 1] [2]].
   
   Parameters:
   - items: Vector of items (the chain)
   - prefix: (optional) Path prefix for recursion
   
   Returns: Sequence of path vectors"
  ([items] (paths-in-chain items []))
  ([items prefix]
   (mapcat
     (fn [idx item]
       (let [path (conj prefix idx)]
         (if (group? item)
           (cons path (paths-in-chain (:items item []) (conj path :items)))
           [path])))
     (range)
     items)))

(defn get-item-at-path
  "Get an item from a chain at the given path.
   Path is a vector like [1 :items 0].
   
   Parameters:
   - items: Vector of items (the chain)
   - path: Path vector to the item
   
   Returns: Item at path, or nil if not found"
  [items path]
  (get-in items path))

(defn find-path-by-id
  "Find the path to an item with the given ID in a chain.
   Returns nil if not found.
   
   Parameters:
   - items: Vector of items (the chain)
   - id: UUID or other ID to find
   
   Returns: Path vector like [1 :items 0] or nil if not found"
  ([items id] (find-path-by-id items id []))
  ([items id prefix]
   (reduce
     (fn [_ idx]
       (let [item (nth items idx)
             path (conj prefix idx)]
         (cond
           ;; Found it!
           (= id (:id item))
           (reduced path)
           
           ;; It's a group - search recursively
           (group? item)
           (if-let [found (find-path-by-id (:items item []) id (conj path :items))]
             (reduced found)
             nil)
           
           ;; Not this item
           :else nil)))
     nil
     (range (count items)))))


;; Counting


(defn count-items-recursive
  "Count total leaf items in a chain, including those inside groups.
   
   Parameters:
   - items: Vector of items (the chain)
   
   Returns: Integer count of leaf items"
  [items]
  (reduce
    (fn [acc item]
      (if (group? item)
        (+ acc (count-items-recursive (:items item [])))
        (inc acc)))
    0
    items))


;; ID Management


(defn ensure-item-id
  "Ensure an item has an :id field. Returns item with ID.
   If item already has an ID, returns it unchanged.
   Otherwise, adds a random UUID as the ID."
  [item]
  (if (:id item)
    item
    (assoc item :id (random-uuid))))

(defn ensure-all-ids
  "Ensure all items in a chain have IDs, recursively.
   Returns a new chain with IDs added where missing."
  [items]
  (mapv
    (fn [item]
      (let [with-id (ensure-item-id item)]
        (if (group? with-id)
          (update with-id :items ensure-all-ids)
          with-id)))
    items))


;; Item Manipulation


(defn remove-at-path
  "Remove an item at the given path from a chain.
   
   Parameters:
   - items: Vector of items (the chain)
   - path: Path vector to the item to remove
   
   Returns: Updated chain with item removed"
  [items path]
  (if (= 1 (count path))
    ;; Direct child - remove from vector
    (let [idx (first path)]
      (into (subvec items 0 idx) (subvec items (inc idx))))
    ;; Nested - recurse into parent
    (let [[idx & rest-path] path]
      (if (= :items (first rest-path))
        (update-in items [idx :items] remove-at-path (vec (rest rest-path)))
        items))))

(defn insert-at-path
  "Insert an item at the given path in a chain.
   The item will be inserted at the path index, pushing existing items down.
   
   Parameters:
   - items: Vector of items (the chain)
   - path: Path vector where to insert
   - item: Item to insert
   
   Returns: Updated chain with item inserted"
  [items path item]
  (if (= 1 (count path))
    ;; Direct child - insert into vector
    (let [idx (first path)]
      (into (conj (subvec items 0 idx) item) (subvec items idx)))
    ;; Nested - recurse into parent
    (let [[idx & rest-path] path]
      (if (= :items (first rest-path))
        (update-in items [idx :items] insert-at-path (vec (rest rest-path)) item)
        items))))

(defn update-at-path
  "Update an item at the given path in a chain.
   
   Parameters:
   - items: Vector of items (the chain)
   - path: Path vector to the item
   - f: Function to apply to the item (receives item, returns updated item)
   
   Returns: Updated chain"
  [items path f]
  (update-in items path f))

(defn move-item
  "Move an item from one path to another in a chain.
   
   Parameters:
   - items: Vector of items (the chain)
   - from-path: Path vector of item to move
   - to-path: Path vector of destination (item will be inserted here)
   
   Returns: Updated chain with item moved"
  [items from-path to-path]
  (let [item (get-item-at-path items from-path)
        ;; Remove first
        after-remove (remove-at-path items from-path)
        ;; Adjust to-path if it was affected by the removal
        ;; (This is a simplified version - more complex adjustment may be needed)
        adjusted-to-path to-path]
    (insert-at-path after-remove adjusted-to-path item)))


;; Group Operations


(defn create-group
  "Create a new group with the given items.
   
   Parameters:
   - items: Vector of items to include in group
   - opts: (optional) Map with :name, :enabled?, :collapsed?
   
   Returns: New group map"
  ([items] (create-group items {}))
  ([items {:keys [name enabled? collapsed?]
           :or {name "New Group" enabled? true collapsed? false}}]
   {:type :group
    :id (random-uuid)
    :name name
    :items (vec items)
    :enabled? enabled?
    :collapsed? collapsed?}))

(defn ungroup
  "Replace a group at path with its contents.
   The group's items are spliced into the parent at the group's position.
   
   Parameters:
   - items: Vector of items (the chain)
   - path: Path vector to the group to ungroup
   
   Returns: Updated chain with group replaced by its contents"
  [items path]
  (let [group (get-item-at-path items path)]
    (if (group? group)
      (if (= 1 (count path))
        ;; Direct child
        (let [idx (first path)
              before (subvec items 0 idx)
              after (subvec items (inc idx))]
          (into (into before (:items group [])) after))
        ;; Nested
        (let [[idx & rest-path] path]
          (if (= :items (first rest-path))
            (update-in items [idx :items] ungroup (vec (rest rest-path)))
            items)))
      ;; Not a group - return unchanged
      items)))


;; Selection Helpers


(defn select-range
  "Get all paths between two paths (inclusive) for Shift+Click selection.
   Returns paths in document order.
   
   Parameters:
   - items: Vector of items (the chain)
   - path1: First path
   - path2: Second path
   
   Returns: Vector of paths in the range"
  [items path1 path2]
  (let [all-paths (vec (paths-in-chain items))
        idx1 (.indexOf all-paths path1)
        idx2 (.indexOf all-paths path2)]
    (if (or (neg? idx1) (neg? idx2))
      []
      (let [start (min idx1 idx2)
            end (max idx1 idx2)]
        (subvec all-paths start (inc end))))))
