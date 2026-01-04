(ns laser-show.animation.cue-chains
  "Cue chain management for combining multiple presets with effects.
   
   A cue chain is a sequential list of presets that render one after another
   when triggered. Each preset in the chain can:
   - Have its own parameters (radius, size, color, etc.)
   - Have its own effect chain
   - Be grouped with other presets
   - Be enabled/disabled independently
   
   Data Structure:
   {:items [{:type :preset
             :id (uuid)
             :preset-id :circle
             :params {:radius 0.5 :color [255 255 255]}
             :effects []
             :enabled? true}
            {:type :group
             :id (uuid)
             :name \"Wave Group\"
             :items [...]
             :effects []
             :enabled? true
             :collapsed? false}]}"
  (:require [laser-show.animation.chains :as chains]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.effects :as effects]
            [laser-show.common.util :as u]))


;; Type Predicates


(defn preset-item?
  "Check if an item is a preset instance (not a group).
   Preset items have {:type :preset :preset-id ...}."
  [item]
  (= :preset (:type item)))

(def group?
  "Check if an item is a group. Delegates to chains/group?."
  chains/group?)


;; Preset Instance Creation


(defn get-default-params
  "Get default parameter values for a preset."
  [preset-id]
  (when-let [preset-def (presets/get-preset preset-id)]
    (u/map-into :key :default (:parameters preset-def))))

(defn create-preset-instance
  "Create a preset instance with default params and empty effects.
   
   Parameters:
   - preset-id: Keyword identifying the preset (e.g., :circle, :wave)
   - opts: (optional) Map with :params, :effects, :enabled?
   
   Returns: Preset instance map"
  ([preset-id] (create-preset-instance preset-id {}))
  ([preset-id {:keys [params effects enabled?]
               :or {params {} effects [] enabled? true}}]
   (let [default-params (get-default-params preset-id)
         merged-params (merge default-params params)]
     {:type :preset
      :id (random-uuid)
      :preset-id preset-id
      :params merged-params
      :effects effects
      :enabled? enabled?})))

(defn create-group
  "Create a new preset group with the given items.
   
   Parameters:
   - items: Vector of preset instances or nested groups
   - opts: (optional) Map with :name, :effects, :enabled?, :collapsed?
   
   Returns: New group map"
  ([items] (create-group items {}))
  ([items {:keys [name effects enabled? collapsed?]
           :or {name "New Group" effects [] enabled? true collapsed? false}}]
   {:type :group
    :id (random-uuid)
    :name name
    :items (vec items)
    :effects effects
    :enabled? enabled?
    :collapsed? collapsed?}))


;; Cue Chain Creation


(defn create-cue-chain
  "Create a new cue chain.
   
   Parameters:
   - items: (optional) Vector of preset instances and groups
   
   Returns: Cue chain map {:items [...]}"
  ([] (create-cue-chain []))
  ([items]
   {:items (vec items)}))

(defn create-cue-chain-from-preset
  "Create a cue chain containing a single preset.
   Convenience function for migrating from single-preset cells.
   
   Parameters:
   - preset-id: Keyword identifying the preset
   
   Returns: Cue chain with single preset instance"
  [preset-id]
  (create-cue-chain [(create-preset-instance preset-id)]))


;; Validation


(defn valid-preset-instance?
  "Check if a preset instance is valid."
  [item]
  (and (map? item)
       (= :preset (:type item))
       (keyword? (:preset-id item))
       (presets/get-preset (:preset-id item))
       (map? (:params item))
       (vector? (:effects item))
       (boolean? (:enabled? item))))

(defn valid-group?
  "Check if a group is valid (shallow check, doesn't validate nested items)."
  [item]
  (and (map? item)
       (= :group (:type item))
       (string? (:name item))
       (vector? (:items item))
       (vector? (:effects item))
       (boolean? (:enabled? item))
       (boolean? (:collapsed? item))))

(defn valid-item?
  "Check if an item (preset or group) is valid."
  [item]
  (cond
    (preset-item? item) (valid-preset-instance? item)
    (group? item) (valid-group? item)
    :else false))

(defn valid-cue-chain?
  "Check if a cue chain is valid.
   Recursively validates all items."
  [cue-chain]
  (and (map? cue-chain)
       (vector? (:items cue-chain))
       (every? valid-item? (:items cue-chain))
       (<= (chains/nesting-depth (:items cue-chain)) chains/max-nesting-depth)))


;; Chain Operations (delegating to chains.clj)


(def flatten-chain
  "Flatten a nested cue chain into a sequence of preset instances.
   Delegates to chains/flatten-chain."
  chains/flatten-chain)

(def paths-in-chain
  "Generate all paths in a cue chain. Delegates to chains/paths-in-chain."
  chains/paths-in-chain)

(def find-path-by-id
  "Find the path to an item by ID. Delegates to chains/find-path-by-id."
  chains/find-path-by-id)

(def get-item-at-path
  "Get an item at a path. Delegates to chains/get-item-at-path."
  chains/get-item-at-path)

(def count-items-recursive
  "Count total preset instances. Delegates to chains/count-items-recursive."
  chains/count-items-recursive)

(def remove-at-path
  "Remove an item at path. Delegates to chains/remove-at-path."
  chains/remove-at-path)

(def insert-at-path
  "Insert an item at path. Delegates to chains/insert-at-path."
  chains/insert-at-path)

(def update-at-path
  "Update an item at path. Delegates to chains/update-at-path."
  chains/update-at-path)

(def move-item
  "Move an item from one path to another. Delegates to chains/move-item."
  chains/move-item)

(def ungroup
  "Replace a group with its contents. Delegates to chains/ungroup."
  chains/ungroup)

(def select-range
  "Get all paths between two paths for range selection. Delegates to chains/select-range."
  chains/select-range)


;; Preset Parameter Updates


(defn update-preset-params
  "Update parameters for a preset instance at the given path.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path to the preset instance
   - param-updates: Map of param key -> new value
   
   Returns: Updated cue chain"
  [cue-chain path param-updates]
  (update-in cue-chain (into [:items] path)
             (fn [item]
               (update item :params merge param-updates))))

(defn set-preset-param
  "Set a single parameter for a preset instance.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path to the preset instance
   - param-key: Parameter key
   - value: New value
   
   Returns: Updated cue chain"
  [cue-chain path param-key value]
  (assoc-in cue-chain (concat [:items] path [:params param-key]) value))


;; Effect Chain Operations


(defn add-effect-to-preset
  "Add an effect to a preset's effect chain.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path to the preset or group
   - effect: Effect instance {:effect-id :scale :params {...} :enabled? true}
   
   Returns: Updated cue chain"
  [cue-chain path effect]
  (update-in cue-chain (concat [:items] path [:effects])
             (fn [effects]
               (conj (or effects []) (effects/ensure-item-id effect)))))

(defn remove-effect-from-preset
  "Remove an effect from a preset's effect chain by effect path.
   
   Parameters:
   - cue-chain: The cue chain
   - preset-path: Path to the preset or group
   - effect-path: Path within the effect chain (e.g., [0] or [1 :items 0])
   
   Returns: Updated cue chain"
  [cue-chain preset-path effect-path]
  (update-in cue-chain (concat [:items] preset-path [:effects])
             (fn [effects]
               (chains/remove-at-path effects effect-path))))

(defn update-effect-in-preset
  "Update an effect in a preset's effect chain.
   
   Parameters:
   - cue-chain: The cue chain
   - preset-path: Path to the preset or group
   - effect-path: Path within the effect chain
   - f: Update function
   
   Returns: Updated cue chain"
  [cue-chain preset-path effect-path f]
  (update-in cue-chain (concat [:items] preset-path [:effects])
             (fn [effects]
               (chains/update-at-path effects effect-path f))))


;; Enabled State Management


(defn set-item-enabled
  "Set the enabled state of an item (preset or group).
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path to the item
   - enabled?: Boolean enabled state
   
   Returns: Updated cue chain"
  [cue-chain path enabled?]
  (assoc-in cue-chain (concat [:items] path [:enabled?]) enabled?))

(defn toggle-item-enabled
  "Toggle the enabled state of an item.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path to the item
   
   Returns: Updated cue chain"
  [cue-chain path]
  (update-in cue-chain (concat [:items] path [:enabled?]) not))


;; Group Collapse Management


(defn set-group-collapsed
  "Set the collapsed state of a group.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path to the group
   - collapsed?: Boolean collapsed state
   
   Returns: Updated cue chain"
  [cue-chain path collapsed?]
  (assoc-in cue-chain (concat [:items] path [:collapsed?]) collapsed?))

(defn toggle-group-collapsed
  "Toggle the collapsed state of a group.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path to the group
   
   Returns: Updated cue chain"
  [cue-chain path]
  (update-in cue-chain (concat [:items] path [:collapsed?]) not))

(defn rename-group
  "Rename a group.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path to the group
   - new-name: New group name
   
   Returns: Updated cue chain"
  [cue-chain path new-name]
  (assoc-in cue-chain (concat [:items] path [:name]) new-name))


;; Preset Addition/Removal


(defn add-preset
  "Add a preset instance to the cue chain at the end.
   
   Parameters:
   - cue-chain: The cue chain
   - preset-id: Keyword identifying the preset to add
   - opts: (optional) Options for create-preset-instance
   
   Returns: Updated cue chain"
  ([cue-chain preset-id]
   (add-preset cue-chain preset-id {}))
  ([cue-chain preset-id opts]
   (update cue-chain :items conj (create-preset-instance preset-id opts))))

(defn add-preset-at-path
  "Add a preset instance at a specific path in the cue chain.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path where to insert (item will be inserted at this index)
   - preset-id: Keyword identifying the preset to add
   - opts: (optional) Options for create-preset-instance
   
   Returns: Updated cue chain"
  ([cue-chain path preset-id]
   (add-preset-at-path cue-chain path preset-id {}))
  ([cue-chain path preset-id opts]
   (update cue-chain :items insert-at-path path (create-preset-instance preset-id opts))))

(defn remove-item
  "Remove an item (preset or group) from the cue chain.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path to the item to remove
   
   Returns: Updated cue chain"
  [cue-chain path]
  (update cue-chain :items remove-at-path path))


;; Grouping Operations


(defn group-items-at-paths
  "Create a new group from items at the given paths.
   Items are removed from their original positions and placed in a new group.
   The group is inserted at the position of the first (topmost) selected item.
   
   Parameters:
   - cue-chain: The cue chain
   - paths: Vector of paths to items to group
   - opts: (optional) Options for create-group
   
   Returns: Updated cue chain"
  ([cue-chain paths] (group-items-at-paths cue-chain paths {}))
  ([cue-chain paths opts]
   (when (seq paths)
     (let [;; Sort paths by document order
           all-paths (vec (paths-in-chain (:items cue-chain)))
           sorted-paths (sort-by #(.indexOf all-paths %) paths)
           
           ;; Collect items to group
           items-to-group (mapv #(get-item-at-path (:items cue-chain) %) sorted-paths)
           
           ;; Create the group
           new-group (create-group items-to-group opts)
           
           ;; Insert position is the first path
           insert-path (first sorted-paths)
           
           ;; Remove items in reverse order (to preserve indices)
           chain-without-items (reduce
                                 (fn [chain path]
                                   (update chain :items remove-at-path path))
                                 cue-chain
                                 (reverse sorted-paths))]
       
       ;; Insert the new group
       (update chain-without-items :items insert-at-path insert-path new-group)))))

(defn ungroup-at-path
  "Ungroup a group, splicing its contents into the parent.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path to the group to ungroup
   
   Returns: Updated cue chain"
  [cue-chain path]
  (update cue-chain :items ungroup path))


;; Clipboard Support


(defn copy-items
  "Copy items at the given paths for clipboard.
   Returns a vector of copied items (deep copy with new IDs).
   
   Parameters:
   - cue-chain: The cue chain
   - paths: Vector of paths to items to copy
   
   Returns: Vector of items ready for pasting"
  [cue-chain paths]
  (let [items (:items cue-chain)]
    (mapv (fn [path]
            (let [item (get-item-at-path items path)]
              ;; Deep copy with new IDs
              (chains/ensure-all-ids
                (assoc item :id (random-uuid)))))
          paths)))

(defn paste-items
  "Paste items at the given path.
   
   Parameters:
   - cue-chain: The cue chain
   - path: Path where to insert (items will be inserted starting here)
   - items: Vector of items to paste
   
   Returns: Updated cue chain"
  [cue-chain path items]
  (reduce-kv
    (fn [chain idx item]
      ;; Insert each item, incrementing the path index
      (let [insert-path (if (= 1 (count path))
                          [(+ (first path) idx)]
                          ;; For nested paths, append to parent's items
                          path)]
        (update chain :items insert-at-path insert-path
                (chains/ensure-all-ids item))))
    cue-chain
    (vec items)))


;; Utility Functions


(defn get-preset-info
  "Get preset definition info for display purposes.
   
   Parameters:
   - preset-id: Keyword identifying the preset
   
   Returns: Map with :id, :name, :category, :parameters or nil"
  [preset-id]
  (when-let [preset (presets/get-preset preset-id)]
    (select-keys preset [:id :name :category :parameters])))

(defn list-available-presets
  "List all available presets for the preset bank.
   
   Returns: Vector of preset info maps grouped by category"
  []
  (let [all (presets/all-presets)]
    (->> all
         (group-by :category)
         (map (fn [[cat items]]
                {:category cat
                 :presets (mapv #(select-keys % [:id :name]) items)}))
         (vec))))

(defn preset-categories
  "Get ordered list of preset categories.
   
   Returns: Vector of category keywords"
  []
  [:geometric :wave :beam :abstract])
