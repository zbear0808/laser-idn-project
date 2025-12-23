(ns laser-show.common.registry
  "Generic registry pattern for managing collections of items by ID.
   
   This module provides both mutable (atom-based) and immutable registry patterns
   that can be used for effects, presets, projectors, and other registered items.
   
   Mutable registries are useful for runtime registration.
   Immutable registries are useful for static/compile-time definitions.")

;; ============================================================================
;; Mutable Registry (Atom-based)
;; ============================================================================

(defn create-registry
  "Create a new mutable registry.
   Returns an atom containing an empty map."
  []
  (atom {}))

(defn register!
  "Register an item in a mutable registry.
   
   Parameters:
   - registry: Atom containing the registry map
   - id: Unique identifier (typically a keyword)
   - item: The item to register
   
   Returns: The registered item"
  [registry id item]
  (swap! registry assoc id item)
  item)

(defn unregister!
  "Remove an item from a mutable registry.
   
   Parameters:
   - registry: Atom containing the registry map
   - id: The ID of the item to remove
   
   Returns: true if item existed, false otherwise"
  [registry id]
  (let [existed? (contains? @registry id)]
    (swap! registry dissoc id)
    existed?))

(defn get-item
  "Get an item from a registry by ID.
   Works with both mutable (atom) and immutable (map) registries.
   
   Parameters:
   - registry: Atom or map containing items
   - id: The ID to look up
   
   Returns: The item or nil if not found"
  [registry id]
  (let [items (if (instance? clojure.lang.Atom registry) @registry registry)]
    (get items id)))

(defn list-items
  "List all items in a registry.
   Works with both mutable (atom) and immutable (map) registries.
   
   Parameters:
   - registry: Atom or map containing items
   
   Returns: Sequence of all items"
  [registry]
  (let [items (if (instance? clojure.lang.Atom registry) @registry registry)]
    (vals items)))

(defn list-ids
  "List all IDs in a registry.
   Works with both mutable (atom) and immutable (map) registries.
   
   Parameters:
   - registry: Atom or map containing items
   
   Returns: Sequence of all IDs"
  [registry]
  (let [items (if (instance? clojure.lang.Atom registry) @registry registry)]
    (keys items)))

(defn contains-id?
  "Check if an ID exists in a registry.
   Works with both mutable (atom) and immutable (map) registries.
   
   Parameters:
   - registry: Atom or map containing items
   - id: The ID to check
   
   Returns: true if ID exists, false otherwise"
  [registry id]
  (let [items (if (instance? clojure.lang.Atom registry) @registry registry)]
    (contains? items id)))

(defn count-items
  "Get the number of items in a registry.
   Works with both mutable (atom) and immutable (map) registries.
   
   Parameters:
   - registry: Atom or map containing items
   
   Returns: Number of items"
  [registry]
  (let [items (if (instance? clojure.lang.Atom registry) @registry registry)]
    (count items)))

(defn clear-registry!
  "Remove all items from a mutable registry.
   
   Parameters:
   - registry: Atom containing the registry map"
  [registry]
  (reset! registry {}))

;; ============================================================================
;; Filtered Queries
;; ============================================================================

(defn list-items-by
  "List items filtered by a predicate.
   Works with both mutable (atom) and immutable (map) registries.
   
   Parameters:
   - registry: Atom or map containing items
   - pred: Predicate function (fn [item] -> boolean)
   
   Returns: Sequence of matching items"
  [registry pred]
  (filter pred (list-items registry)))

(defn list-items-by-key
  "List items where a specific key equals a value.
   Works with both mutable (atom) and immutable (map) registries.
   
   Parameters:
   - registry: Atom or map containing items
   - k: The key to check in each item
   - v: The value to match
   
   Returns: Sequence of matching items
   
   Example: (list-items-by-key registry :category :shape)"
  [registry k v]
  (list-items-by registry #(= (get % k) v)))

(defn find-item-by
  "Find the first item matching a predicate.
   Works with both mutable (atom) and immutable (map) registries.
   
   Parameters:
   - registry: Atom or map containing items
   - pred: Predicate function (fn [item] -> boolean)
   
   Returns: First matching item or nil"
  [registry pred]
  (first (filter pred (list-items registry))))

;; ============================================================================
;; Immutable Registry (Map-based)
;; ============================================================================

(defn build-registry
  "Build an immutable registry from a collection of items.
   Each item must have an :id key.
   
   Parameters:
   - items: Sequence of items with :id keys
   
   Returns: Map of id -> item"
  [items]
  (into {} (map (juxt :id identity) items)))

(defn merge-registries
  "Merge multiple registries into one.
   Later registries override earlier ones for duplicate IDs.
   Works with both atoms and maps.
   
   Parameters:
   - registries: Variable number of registries (atoms or maps)
   
   Returns: Merged map (not an atom)"
  [& registries]
  (apply merge
         (map (fn [r]
                (if (instance? clojure.lang.Atom r) @r r))
              registries)))

;; ============================================================================
;; Validation Helpers
;; ============================================================================

(defn validate-item
  "Validate an item before registration.
   
   Parameters:
   - item: The item to validate
   - required-keys: Set of required keys
   
   Returns: {:valid? true/false :errors [...]}"
  [item required-keys]
  (let [missing (filter #(not (contains? item %)) required-keys)
        errors (map #(str "Missing required key: " %) missing)]
    {:valid? (empty? missing)
     :errors (vec errors)}))

(defn register-validated!
  "Register an item only if it passes validation.
   
   Parameters:
   - registry: Atom containing the registry map
   - id: Unique identifier
   - item: The item to register
   - required-keys: Set of required keys for validation
   
   Returns: {:success? true/false :item item :errors [...]}"
  [registry id item required-keys]
  (let [{:keys [valid? errors]} (validate-item item required-keys)]
    (if valid?
      (do
        (register! registry id item)
        {:success? true :item item :errors []})
      {:success? false :item nil :errors errors})))
