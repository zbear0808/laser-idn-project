(ns laser-show.events.handlers.list.helpers
  "Pure helper functions for ID-based tree manipulation.

   All functions operate on item trees (nested vectors of maps with :id fields)
   using IDs instead of paths. This eliminates index-invalidation bugs that
   plague path-based operations.

   Item tree structure:
   - Leaf item: any map with :id (UUID), e.g. {:id #uuid \"...\" :effect-id :scale}
   - Group: {:id #uuid \"...\" :type :group :name \"...\" :items [child1 child2 ...]}
   - Tree: a vector of items and groups, with groups nesting recursively"
  (:require [laser-show.animation.chains :as chains]))


;; Core: Collect items matching a predicate in document order


(defn- collect-items-in-order
  "Walk a tree and collect items matching `pred` in document order.
   If a group matches `pred`, the entire group is collected and its
   children are not traversed separately to avoid duplicating them as siblings.
   Returns a vector of items."
  [items pred]
  (reduce
   (fn [acc item]
     (cond
       (pred item) (conj acc item)
       (chains/group? item) (into acc (collect-items-in-order (:items item []) pred))
       :else acc))
   []
   items))


(defn collect-by-ids
  "Collect items matching `ids-set` in document order from the tree.
   Returns a vector of items."
  [items ids-set]
  (let [ids-set (set ids-set)]
    (collect-items-in-order items #(contains? ids-set (:id %)))))


(defn find-by-id
  "Find a single item by ID in the tree. Returns the item map or nil."
  [items id]
  (reduce
   (fn [_ item]
     (cond
       (= id (:id item)) (reduced item)
       (chains/group? item) (when-let [found (find-by-id (:items item []) id)]
                              (reduced found))
       :else nil))
   nil
   items))


(defn normalize-selected-ids
  "Remove redundant descendant IDs from `selected-ids`.
   If a group is in `selected-ids`, all of its descendant IDs are removed
   since the group already encompasses them."
  [selected-ids items]
  (let [selected-ids (set selected-ids)]
    (reduce
     (fn [ids id]
       (let [item (find-by-id items id)]
         (if (and item (chains/group? item))
           (if-let [descendant-ids (seq (chains/collect-descendant-ids item))]
             (apply disj ids descendant-ids)
             ids)
           ids)))
     selected-ids
     selected-ids)))


;; Selection Calculation


(defn calculate-selection
  "Pure calculation of new selection state given current selection state,
   items tree, clicked item ID, and selection mode (:single, :ctrl, :shift, :ctrl-shift).
   Returns updated selection map {:selected-ids #{...} :last-selected-id <id>}."
  ([current-selection params]
   (let [{:keys [items item-id mode]} params]
     (calculate-selection current-selection items item-id mode)))
  ([current-selection items item-id mode]
   (let [{:keys [selected-ids last-selected-id]
          :or {selected-ids #{}}} current-selection]
     (case mode
       :ctrl
       {:selected-ids (if (contains? selected-ids item-id)
                        (disj selected-ids item-id)
                        (conj selected-ids item-id))
        :last-selected-id item-id}

       (:shift :ctrl-shift)
       (let [all-ids (chains/collect-all-ids items)
             anchor-id (or last-selected-id item-id)
             anchor-idx (.indexOf all-ids anchor-id)
             target-idx (.indexOf all-ids item-id)
             valid-anchor? (>= anchor-idx 0)
             valid-target? (>= target-idx 0)
             range-ids (if (and valid-anchor? valid-target?)
                         (set (subvec all-ids (min anchor-idx target-idx) (inc (max anchor-idx target-idx))))
                         #{item-id})
             new-selected (if (= mode :ctrl-shift)
                            (into selected-ids range-ids)
                            range-ids)]
         {:selected-ids new-selected
          :last-selected-id (if valid-anchor? anchor-id item-id)})

       ;; Default to :single
       {:selected-ids #{item-id}
        :last-selected-id item-id}))))


;; ID-Based Removal


(defn remove-by-ids
  "Remove all items with IDs in `ids-set` from the tree.
   Single recursive walk — no path computation needed.

   Parameters:
   - items: Vector of items (the tree)
   - ids-set: Set of UUIDs to remove

   Returns: Updated tree with matching items removed"
  [items ids-set]
  (let [ids-set (set ids-set)]
    (reduce
     (fn [acc item]
       (if (contains? ids-set (:id item))
         acc
         (conj acc
               (if (chains/group? item)
                 (update item :items remove-by-ids ids-set)
                 item))))
     []
     items)))


;; ID-Based Update


(defn update-by-id
  "Find item with `id` and apply `f` to it. Single recursive walk.

   Parameters:
   - items: Vector of items (the tree)
   - id: UUID of item to update
   - f: Function to apply to the found item

   Returns: Updated tree"
  [items id f]
  (mapv
   (fn [item]
     (cond
       (= id (:id item))
       (f item)

       (chains/group? item)
       (update item :items update-by-id id f)

       :else item))
   items))


(defn set-item-field
  "Set a field on an item found by ID.

   Parameters:
   - items: Vector of items (the tree)
   - id: UUID of item to update
   - k: Keyword field to set
   - v: Value to set

   Returns: Updated tree"
  [items id k v]
  (update-by-id items id #(assoc % k v)))


;; ID-Based Insertion


(defn- insert-relative-to-id
  "Insert `new-items` relative to the item with `target-id`.
   `position` is :before or :after.

   Walks the tree level by level. When target-id is found at a level,
   splices new-items before or after it."
  [items target-id position new-items]
  (let [new-items (vec new-items)
        target-here? (some #(= target-id (:id %)) items)]
    (if target-here?
      ;; Target is at this level — rebuild with splice
      (persistent!
       (reduce
        (fn [acc item]
          (if (= target-id (:id item))
            (case position
              :before (reduce conj! (reduce conj! acc new-items) [item])
              :after (reduce conj! (conj! acc item) new-items))
            (conj! acc item)))
        (transient [])
        items))
      ;; Target not at this level — recurse into groups
      (mapv
       (fn [item]
         (if (chains/group? item)
           (update item :items insert-relative-to-id target-id position new-items)
           item))
       items))))


(defn insert-after-id
  "Insert `new-items` after the item with `target-id` in the tree.

   Parameters:
   - items: Vector of items (the tree)
   - target-id: UUID of the item to insert after
   - new-items: Vector of items to insert

   Returns: Updated tree with new-items inserted after target"
  [items target-id new-items]
  (insert-relative-to-id items target-id :after new-items))


(defn insert-before-id
  "Insert `new-items` before the item with `target-id` in the tree.

   Parameters:
   - items: Vector of items (the tree)
   - target-id: UUID of the item to insert before
   - new-items: Vector of items to insert

   Returns: Updated tree with new-items inserted before target"
  [items target-id new-items]
  (insert-relative-to-id items target-id :before new-items))


(defn insert-into-group
  "Insert `new-items` at the end of a group's children.

   Parameters:
   - items: Vector of items (the tree)
   - group-id: UUID of the group to insert into
   - new-items: Vector of items to insert

   Returns: Updated tree with new-items appended to group's children"
  [items group-id new-items]
  (update-by-id items group-id
                (fn [group]
                  (update group :items #(into (vec (or % [])) new-items)))))


;; Compound Operations


(defn- descendant-of-any?
  "Check if target-id is a descendant of any item in source-items."
  [source-items target-id]
  (boolean
   (some
    (fn [item]
      (when (chains/group? item)
        (contains? (or (chains/collect-descendant-ids item) #{}) target-id)))
    source-items)))


(defn move-items
  "Move items with `source-ids` to `target-id` at `position`.

   Algorithm:
   1. Normalize source-ids (strip descendants of already-selected groups)
   2. Collect source items in document order
   3. Check for illegal moves:
      - target-id is inside source-ids for :into
      - target-id is a descendant of any source item (cyclic move)
      If illegal, returns items unchanged.
   4. Remove source items from tree
   5. Insert collected items at target:
      - :append or target-id = :root-end -> append to root items
      - :before / :after / :into -> insert at target
      If target-id is not found in the remaining tree, appends items to root
      so items are never deleted.

   Parameters:
   - items: Vector of items (the tree)
   - source-ids: Collection of UUIDs to move
   - target-id: UUID of the target item, or :root-end
   - position: :before, :after, :into, or :append

   Returns: Updated tree with items moved"
  [items source-ids target-id position]
  (let [normalized-sources (normalize-selected-ids source-ids items)
        ;; If single item is moved before/after itself, it's a no-op
        self-move? (and (= 1 (count normalized-sources))
                        (contains? normalized-sources target-id)
                        (#{:before :after} position))
        ;; If target is in sources with relative positioning, keep target as anchor
        effective-sources (if (and (contains? normalized-sources target-id)
                                   (#{:before :after} position))
                            (disj normalized-sources target-id)
                            normalized-sources)
        items-to-move (collect-items-in-order items #(contains? effective-sources (:id %)))
        into-self? (and (contains? effective-sources target-id)
                        (= :into position))
        into-descendant? (descendant-of-any? items-to-move target-id)]
    (cond
      self-move?
      items

      (or into-self? into-descendant?)
      items

      (empty? items-to-move)
      items

      :else
      (let [after-remove (remove-by-ids items effective-sources)]
        (cond
          (or (= position :append) (= target-id :root-end))
          (into after-remove items-to-move)

          (= position :before)
          (if (find-by-id after-remove target-id)
            (insert-before-id after-remove target-id items-to-move)
            (into after-remove items-to-move))

          (= position :after)
          (if (find-by-id after-remove target-id)
            (insert-after-id after-remove target-id items-to-move)
            (into after-remove items-to-move))

          (= position :into)
          (if (find-by-id after-remove target-id)
            (insert-into-group after-remove target-id items-to-move)
            (into after-remove items-to-move))

          :else
          (into after-remove items-to-move))))))


(defn- find-ids-at-level
  "Check which of `ids-set` are direct children at a given items level.
   Returns a set of found IDs."
  [items ids-set]
  (into #{} (comp (map :id) (filter #(contains? ids-set %))) (map identity items)))


(defn- items-at-same-level?
  "Check if all items with given IDs are siblings (at the same nesting level).
   Returns true if they are, false otherwise."
  [items ids-set]
  (let [ids-here (find-ids-at-level items ids-set)]
    (if (= ids-here ids-set)
      true
      (boolean
       (some
        (fn [item]
          (when (chains/group? item)
            (items-at-same-level? (:items item []) ids-set)))
        items)))))


(defn group-items-by-ids
  "Group selected items into a new group.

   Items must be at the same nesting level. They are removed from their
   current positions and placed into a new group at the position of the
   first selected item.

   Parameters:
   - items: Vector of items (the tree)
   - ids-to-group: Set of UUIDs to group

   Returns: {:items updated-tree :group-id new-group-uuid}
            or nil if items aren't at the same level"
  [items ids-to-group]
  (let [ids-set (set ids-to-group)]
    (when (items-at-same-level? items ids-set)
      (letfn [(do-group [items]
                (let [ids-here (find-ids-at-level items ids-set)]
                  (if (= ids-here ids-set)
                    ;; All target items are at this level
                    (let [items-to-group (filterv #(contains? ids-set (:id %)) items)
                          new-group (chains/create-group items-to-group)
                          first-idx (reduce-kv
                                     (fn [_ idx item]
                                       (when (contains? ids-set (:id item))
                                         (reduced idx)))
                                     nil
                                     (vec items))
                          result (reduce-kv
                                  (fn [acc idx item]
                                    (cond
                                      (and (= idx first-idx) (contains? ids-set (:id item)))
                                      (conj acc new-group)

                                      (contains? ids-set (:id item))
                                      acc

                                      :else
                                      (conj acc item)))
                                  []
                                  (vec items))]
                      {:items result :group-id (:id new-group)})
                    ;; Recurse into groups to find the right level
                    (reduce
                     (fn [_ item]
                       (when (chains/group? item)
                         (when-let [sub-result (do-group (:items item []))]
                           (reduced
                            {:items (mapv (fn [i]
                                            (if (= (:id i) (:id item))
                                              (assoc item :items (:items sub-result))
                                              i))
                                          items)
                             :group-id (:group-id sub-result)}))))
                     nil
                     items))))]
        (do-group items)))))


(defn ungroup-by-id
  "Replace a group with its contents (splice children into parent).

   Parameters:
   - items: Vector of items (the tree)
   - group-id: UUID of the group to ungroup

   Returns: Updated tree with group replaced by its children,
            or items unchanged if group-id not found or not a group"
  [items group-id]
  (let [found-here? (some #(= group-id (:id %)) items)]
    (if found-here?
      ;; Group is at this level — splice
      (reduce
       (fn [acc item]
         (if (and (= group-id (:id item)) (chains/group? item))
           (into acc (:items item []))
           (conj acc item)))
       []
       items)
      ;; Recurse into groups
      (mapv
       (fn [item]
         (if (chains/group? item)
           (update item :items ungroup-by-id group-id)
           item))
       items))))