(ns laser-show.events.helpers
  "Shared helper functions for event handlers.
   
   These utilities are used across multiple event handler modules to maintain
   consistent behavior and avoid code duplication."
  (:require [laser-show.animation.chains :as chains]
            [laser-show.state.clipboard :as clipboard]))


;; State Modification Helpers


(defn mark-dirty
  "Mark project as having unsaved changes."
  [state]
  (assoc-in state [:project :dirty?] true))

(defn current-time-ms
  "Get current time from event or system.
   
   Accepts an event map and returns:
   - The :time key from the event if present
   - The current system time in milliseconds otherwise"
  [event]
  (or (:time event) (System/currentTimeMillis)))


;; Path-Based Tree Manipulation


(defn remove-at-path
  "Remove an item at the given path from a nested vector structure.
   Used for group operations in hierarchical chains.
   
   Example:
   (remove-at-path [{:id 1} {:id 2 :items [{:id 3}]}] [1 :items 0])
   => [{:id 1} {:id 2 :items []}]"
  [items path]
  (let [items-vec (vec items)   ;; Ensure items is a vector for subvec
        path-vec (vec path)]    ;; Ensure path is a vector
    (if (= 1 (count path-vec))
      (let [idx (first path-vec)]
        (if (and (>= idx 0) (< idx (count items-vec)))
          (vec (concat (subvec items-vec 0 idx)
                       (subvec items-vec (inc idx))))
          items-vec))
      (let [[parent-idx & rest-path] path-vec]
        (if (and (>= parent-idx 0) (< parent-idx (count items-vec))
                 (= :items (first rest-path)))
          (update items-vec parent-idx
                  #(update % :items
                           (fn [sub-items]
                             (remove-at-path (vec (or sub-items [])) (vec (rest rest-path))))))
          items-vec)))))

(defn insert-at-path
  "Insert an item at the given path in a nested vector structure.
   If into-group? is true, insert into the group's items at the path.
   
   Example:
   (insert-at-path [{:id 1}] [1] {:id 2} false)
   => [{:id 1} {:id 2}]"
  [items path item into-group?]
  (let [items-vec (vec items)   ;; Ensure items is a vector for subvec
        path-vec (vec path)]    ;; Ensure path is a vector
    (if into-group?
      ;; Insert into group's items
      (update-in items-vec (conj path-vec :items) #(conj (vec (or % [])) item))
      ;; Insert before position
      (if (= 1 (count path-vec))
        (let [idx (first path-vec)
              safe-idx (min idx (count items-vec))]
          (vec (concat (subvec items-vec 0 safe-idx)
                       [item]
                       (subvec items-vec safe-idx))))
        (let [[parent-idx & rest-path] path-vec]
          (if (and (>= parent-idx 0) (< parent-idx (count items-vec))
                   (= :items (first rest-path)))
            (update items-vec parent-idx
                    #(update % :items
                             (fn [sub-items]
                               (insert-at-path (vec (or sub-items [])) (vec (rest rest-path)) item false))))
            items-vec))))))


;; ID Regeneration for Copy/Paste


(defn regenerate-ids
  "Recursively regenerate :id fields for effects and groups.
   Ensures pasted items have unique IDs to prevent drag/drop issues
   when the same item is copied multiple times.
   
   Example:
   (regenerate-ids {:id #uuid \"old\" :items [{:id #uuid \"old2\"}]})
   => {:id #uuid \"new1\" :items [{:id #uuid \"new2\"}]}"
  [item]
  (cond
    ;; Group - regenerate ID and recurse into items
    (chains/group? item)
    (-> item
        (assoc :id (random-uuid))
        (update :items #(mapv regenerate-ids %)))
    
    ;; Effect or other item - just regenerate ID
    :else
    (assoc item :id (random-uuid))))


;; Path Filtering for Multi-Selection Operations


(defn path-is-ancestor?
  "Check if ancestor-path is an ancestor of descendant-path.
   
   Example:
   (path-is-ancestor? [0] [0 :items 1]) => true
   (path-is-ancestor? [1] [0 :items 1]) => false"
  [ancestor-path descendant-path]
  (and (< (count ancestor-path) (count descendant-path))
       (= ancestor-path (take (count ancestor-path) descendant-path))))

(defn filter-to-root-paths
  "Filter a set of paths to only include root paths (no descendants).
   This prevents deleting/moving items multiple times when a parent
   and its children are both selected.
   
   Example:
   (filter-to-root-paths #{[0] [0 :items 1] [2]})
   => #{[0] [2]}"
  [paths]
  (let [sorted-paths (sort-by count paths)]
    (reduce
      (fn [acc path]
        (if (some #(path-is-ancestor? % path) acc)
          acc  ;; Skip - this path has an ancestor already included
          (conj acc path)))
      #{}
      sorted-paths)))


;; Two-Phase Operations (Collect Then Modify)
;; These avoid index-shifting bugs by collecting items first, then operating


(defn collect-items-by-ids
  "Recursively collect all items matching the given ID set.
   Returns a vector of items (preserving order as encountered).
   
   Example:
   (collect-items-by-ids [{:id 1} {:id 2 :items [{:id 3}]}] #{1 3})
   => [{:id 1} {:id 3}]"
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
   Returns the chain with matching items filtered out.
   
   Example:
   (remove-items-by-ids [{:id 1} {:id 2}] #{1})
   => [{:id 2}]"
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
  "Insert items at a specific index in the vector.
   
   Example:
   (insert-items-at-index [{:id 1} {:id 3}] 1 [{:id 2}])
   => [{:id 1} {:id 2} {:id 3}]"
  [vec idx items]
  (let [safe-idx (max 0 (min idx (count vec)))]
    (into (subvec vec 0 safe-idx)
          (concat items (subvec vec safe-idx)))))


;; Effect/Item Initialization


(defn ensure-item-fields
  "Ensure item has :id and :enabled? fields.
   Used when adding effects, presets, or other items to chains.
   
   Example:
   (ensure-item-fields {:effect-id :scale})
   => {:effect-id :scale :id #uuid \"...\" :enabled? true}"
  [item]
  (cond-> item
    (not (contains? item :id)) (assoc :id (random-uuid))
    (not (contains? item :enabled?)) (assoc :enabled? true)))


;; Text Field Parsing


(defn parse-and-clamp-from-text-event
  "Extract text from ActionEvent, parse as double, clamp to bounds.
   Returns nil if parsing fails.
   
   Example:
   (parse-and-clamp-from-text-event action-event 0.0 1.0)
   => 0.5"
  [fx-event min max]
  (let [text-field (.getSource fx-event)
        text (.getText text-field)]
    (when-let [parsed (try (Double/parseDouble text) (catch Exception _ nil))]
      (-> parsed (clojure.core/max min) (clojure.core/min max)))))


;; Group Creation


(defn make-group
  "Create a new group with given name and items.
   
   Example:
   (make-group \"My Group\" [{:id 1}])
   => {:type :group :id #uuid \"...\" :name \"My Group\" :collapsed? false :enabled? true :items [{:id 1}]}"
  [name items]
  {:type :group
   :id (random-uuid)
   :name name
   :collapsed? false
   :enabled? true
   :items (vec items)})


;; Clipboard Operations


(defn handle-copy-to-clipboard
  "Generic copy handler that reads data from state and copies to clipboard.
   
   Parameters:
   - state: Application state
   - source-path: Path to read data from (e.g., [:chains :effect-chains [col row]])
   - clipboard-type: Keyword for clipboard type (e.g., :effects-cell)
   - system-clipboard-fn: Function to call for system clipboard (e.g., clipboard/copy-effects-cell!)
   
   Returns: {:state updated-state} with internal clipboard set
   
   Example:
   (handle-copy-to-clipboard state
                             [:chains :effect-chains [0 1]]
                             :effects-cell
                             clipboard/copy-effects-cell!)"
  [state source-path clipboard-type system-clipboard-fn]
  (let [data (get-in state source-path)
        clip-data {:type clipboard-type :data data}]
    ;; Copy to system clipboard (side effect)
    (when (and system-clipboard-fn data)
      (system-clipboard-fn data))
    ;; Return state update for internal clipboard
    {:state (assoc-in state [:ui :clipboard] clip-data)}))

(defn handle-paste-from-clipboard
  "Generic paste handler that writes clipboard data to target path.
   
   Parameters:
   - state: Application state
   - target-path: Path to write data to (e.g., [:chains :effect-chains [col row]])
   - expected-type: Expected clipboard type keyword (e.g., :effects-cell)
   - transform-fn: Optional function to transform data before pasting (e.g., regenerate-ids)
   
   Returns: {:state updated-state} or {:state state} if clipboard type doesn't match
   
   Example:
   (handle-paste-from-clipboard state
                                [:chains :effect-chains [0 2]]
                                :effects-cell
                                identity)"
  [state target-path expected-type & [transform-fn]]
  (let [cb (get-in state [:ui :clipboard])]
    (if (and cb (= expected-type (:type cb)))
      (let [data (:data cb)
            transformed-data (if transform-fn (transform-fn data) data)]
        {:state (-> state
                    (assoc-in target-path transformed-data)
                    mark-dirty)})
      {:state state})))

(defn handle-copy-items-to-clipboard
  "Generic copy handler for items (effects, presets) where data is passed directly.
   Used when copying selected items rather than a whole cell/chain.
   
   Parameters:
   - state: Application state
   - items: Vector of items to copy
   - clipboard-type: Keyword for clipboard type (e.g., :cue-chain-items, :item-effects)
   - system-clipboard-fn: Function to call for system clipboard
   
   Returns: {:state updated-state}
   
   Example:
   (handle-copy-items-to-clipboard state selected-items :cue-chain-items clipboard/copy-cue-chain-items!)"
  [state items clipboard-type system-clipboard-fn]
  (when (seq items)
    (system-clipboard-fn items))
  {:state (assoc-in state [:ui :clipboard] {:type clipboard-type :items items})})
