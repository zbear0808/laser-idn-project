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
   - Flattening respects enabled? at all levels"
  (:require [clojure.tools.logging :as log]))


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

(defn- collect-path-item-pairs
  "Internal helper - returns seq of [path item] pairs by walking a chain."
  ([items] (collect-path-item-pairs items []))
  ([items prefix]
   (mapcat
     (fn [[idx item]]
       (let [path (conj prefix idx)]
         (if (group? item)
           (cons [path item]
                 (collect-path-item-pairs (:items item []) (conj path :items)))
           [[path item]])))
     (map-indexed vector items))))

(defn find-paths-by-ids
  "Find paths for multiple IDs in a chain. Returns map of {id -> path}.
   IDs not found in chain will not be in result map.
   
   Parameters:
   - items: Vector of items (the chain)
   - ids: Collection of IDs to find
   
   Returns: Map of {id -> path}"
  [items ids]
  (let [id-set (set ids)]
    (reduce
      (fn [result [path item]]
        (if (contains? id-set (:id item))
          (assoc result (:id item) path)
          result))
      {}
      (collect-path-item-pairs items))))

(defn collect-all-ids-set
 "Collect all item IDs in a chain as a set, recursively including groups.
  
  Parameters:
  - items: Vector of items (the chain)
  
  Returns: Set of UUIDs"
 [items]
 (reduce
   (fn [ids item]
     (let [with-current (if (:id item) (conj ids (:id item)) ids)]
       (if (group? item)
         (into with-current (collect-all-ids-set (:items item [])))
         with-current)))
   #{}
   items))

(defn collect-all-ids
 "Collect all item IDs in a chain in document order, recursively including groups.
  
  Parameters:
  - items: Vector of items (the chain)
  
  Returns: Vector of UUIDs in document order (for indexOf operations)"
 ([items] (collect-all-ids items []))
 ([items acc]
  (reduce
    (fn [acc item]
      (let [with-current (if (:id item) (conj acc (:id item)) acc)]
        (if (group? item)
          (collect-all-ids (:items item []) with-current)
          with-current)))
    acc
    items)))

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

(defn select-range-by-ids
 "Select range between two IDs in document order.
  Returns set of all IDs between from-id and to-id (inclusive).
  
  Parameters:
  - items: Vector of items (the chain)
  - from-id: Starting ID
  - to-id: Ending ID
  
  Returns: Set of IDs in range"
 [items from-id to-id]
 (let [from-path (find-path-by-id items from-id)
       to-path (find-path-by-id items to-id)]
   (if (and from-path to-path)
     (let [path-range (select-range items from-path to-path)]
       (set (keep #(:id (get-item-at-path items %)) path-range)))
     ;; Fallback if IDs not found
     #{from-id to-id})))


;; Deep Copy Operations


(defn deep-copy-item
  "Deep copy an item, generating new UUIDs for it and all nested children.
   This is essential for paste operations to avoid ID collisions.
   
   Parameters:
   - item: Item to copy (effect, preset, or group)
   
   Returns: New item with fresh UUIDs at all levels"
  [item]
  (let [with-new-id (assoc item :id (random-uuid))]
    (if (group? with-new-id)
      (update with-new-id :items #(mapv deep-copy-item %))
      with-new-id)))

(defn deep-copy-items
  "Deep copy multiple items, generating new UUIDs for all items and nested children.
   
   Parameters:
   - items: Vector of items to copy
   
   Returns: Vector of copied items with fresh UUIDs"
  [items]
  (mapv deep-copy-item items))


;; Safe Multi-Item Operations


(defn- compare-paths
  "Compare two paths for sorting. Returns negative if a < b, positive if a > b, 0 if equal.
   Sorts by depth first (deeper paths first), then by index within same depth (higher indices first)."
  [a b]
  (let [depth-cmp (compare (count b) (count a))]
    (if (zero? depth-cmp)
      ;; Same depth - compare indices from end to start (reverse order for deletion)
      (let [a-indices (filter number? a)
            b-indices (filter number? b)]
        (compare (vec (reverse b-indices)) (vec (reverse a-indices))))
      depth-cmp)))

(defn delete-paths-safely
  "Delete multiple items by path, handling index shifts correctly.
   
   Items are removed deepest-first and highest-index-first to prevent path invalidation.
   
   Parameters:
   - items: Vector of items (the chain)
   - paths: Collection of paths to delete
   
   Returns: Updated chain with items removed"
  [items paths]
  (let [sorted-paths (sort compare-paths paths)]
    (reduce remove-at-path items sorted-paths)))

(defn- compare-paths-document-order
  "Compare two paths for document order (visual order in the UI).
   Returns negative if a comes before b, positive if a comes after b, 0 if equal.
   
   Document order is depth-first traversal order, comparing indices at each level."
  [a b]
  (let [;; Compare element by element
        max-len (max (count a) (count b))]
    (loop [i 0]
      (if (>= i max-len)
        0  ;; Paths are equal up to this point
        (let [a-elem (get a i)
              b-elem (get b i)]
          (cond
            ;; a is shorter - a comes first
            (nil? a-elem) -1
            ;; b is shorter - b comes first
            (nil? b-elem) 1
            ;; Both are :items keywords - continue
            (and (= :items a-elem) (= :items b-elem)) (recur (inc i))
            ;; Compare numeric indices
            (and (number? a-elem) (number? b-elem))
            (let [cmp (compare a-elem b-elem)]
              (if (zero? cmp)
                (recur (inc i))
                cmp))
            ;; :items vs number - :items indicates nested, so compare parent indices first
            :else (recur (inc i))))))))

(defn- describe-item
  "Get a brief description of an item for logging."
  [item]
  (cond
    (nil? item) "nil"
    (group? item) (str "Group(" (:name item "unnamed") ", id=" (subs (str (:id item)) 0 8) "...)")
    :else (str "Item(" (:effect-id item (:preset-id item :unknown)) ", id=" (subs (str (:id item)) 0 8) "...)")))

(defn- log-chain-state
  "Log the current state of a chain with paths."
  [label items]
  (log/debug (str "\n=== " label " ==="))
  (doseq [[idx item] (map-indexed vector items)]
    (log/debug (str "  [" idx "] " (describe-item item)))
    (when (group? item)
      (doseq [[child-idx child] (map-indexed vector (:items item []))]
        (log/debug (str "    [" idx " :items " child-idx "] " (describe-item child)))))))

(defn move-items-to-target
  "Move multiple items to a target location safely.
   
   Algorithm:
   1. Sort source paths by document order (visual order) to preserve relative ordering
   2. Collect items in document order
   3. Sort paths by depth (deepest first) for safe removal
   4. Remove items from sources
   5. Find target location in updated chain
   6. Insert items at destination in document order
   
   Parameters:
   - items: Vector of items (the chain)
   - from-paths: Collection of paths to move
   - target-id: UUID of target item
   - drop-position: :before or :into
   
   Returns: Updated chain with items moved"
  [items from-paths target-id drop-position]
  (log/debug "\n========== DRAG-AND-DROP DEBUG ==========")
  (log/debug "Target ID:" (subs (str target-id) 0 8) "...")
  (log/debug "Drop position:" drop-position)
  (log/debug "Source paths (raw):" (vec from-paths))
  (log-chain-state "BEFORE MOVE" items)
  
  (let [;; Sort paths by document order first to preserve visual ordering
        document-ordered-paths (sort compare-paths-document-order from-paths)
        _ (log/debug "Source paths (document order):" (vec document-ordered-paths))
        
        ;; Get items in document order (before any modifications)
        items-to-move (mapv #(get-item-at-path items %) document-ordered-paths)
        _ (log/debug "Items to move:" (mapv describe-item items-to-move))
        
        ;; Sort paths for safe removal (deepest and highest index first)
        removal-ordered-paths (sort compare-paths from-paths)
        _ (log/debug "Removal order:" (vec removal-ordered-paths))
        
        ;; Remove all items (deepest/highest first to avoid index shifting)
        after-remove (reduce remove-at-path items removal-ordered-paths)
        _ (log-chain-state "AFTER REMOVE" after-remove)
        
        ;; Find target location in updated chain
        target-path (find-path-by-id after-remove target-id)
        _ (log/debug "Target path after removal:" target-path)]
    
    (if target-path
      ;; Insert at appropriate location
      (let [insert-path (case drop-position
                          :before target-path
                          :after (if (= 1 (count target-path))
                                   ;; Top-level item - insert after by incrementing index
                                   [(inc (first target-path))]
                                   ;; Nested item - insert after by incrementing last index
                                   (update target-path (dec (count target-path)) inc))
                          :into (let [target-item (get-item-at-path after-remove target-path)]
                                  (if (group? target-item)
                                    (conj target-path :items (count (:items target-item [])))
                                    target-path))
                          target-path)
            _ (log/debug "Insert path:" insert-path)
            
            ;; Insert all items at destination (in document order)
            result (reduce-kv
                     (fn [chain idx item]
                       (let [path-with-offset (if (= 1 (count insert-path))
                                                [(+ (first insert-path) idx)]
                                                ;; For nested paths, adjust the last index
                                                (update insert-path (dec (count insert-path)) + idx))]
                         (log/debug (str "  Inserting item " idx " at path: " path-with-offset))
                         (insert-at-path chain path-with-offset item)))
                     after-remove
                     (vec items-to-move))]
        (log-chain-state "AFTER INSERT (RESULT)" result)
        (log/debug "==========================================\n")
        result)
      ;; Target not found - append to end
      (do
        (log/warn "Target not found, appending to end")
        (let [result (into after-remove items-to-move)]
          (log-chain-state "AFTER APPEND (RESULT)" result)
          (log/debug "==========================================\n")
          result)))))
