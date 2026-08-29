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


(defn get-item-at-path
  "Get an item from a chain at the given path.
   Path is a vector like [1 :items 0].
   
   Parameters:
   - items: Vector of items (the chain)
   - path: Path vector to the item
   
   Returns: Item at path, or nil if not found"
  [items path]
  (get-in (vec items) (vec path)))

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

(defn collect-descendant-ids
  "Collect all descendant IDs from a group's children recursively.
  Does NOT include the group's own ID, only its descendants.
  Returns nil if item is not a group.
  
  Parameters:
  - group: A group item (must have :type :group)
  
  Returns: Set of UUIDs of all descendants, or nil if not a group"
  [group]
  (when (group? group)
    (reduce
     (fn [ids item]
       (let [with-item (if (:id item) (conj ids (:id item)) ids)]
         (if (group? item)
           (into with-item (collect-descendant-ids item))
           with-item)))
     #{}
     (:items group []))))


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
