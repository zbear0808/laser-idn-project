(ns laser-show.backend.zone-groups
  "Zone group configuration and management.
   Zone groups are collections of zones that can be targeted as a unit.
   
   Zone groups now support effect chains for group-wide effects:
   - :effect-chain {:effects [{:effect-id ... :enabled true :params {...}} ...]}"
  (:require [laser-show.state.persistent :as persist]
            [laser-show.backend.zones :as zones]
            [laser-show.animation.effects :as fx]))

;; ============================================================================
;; Zone Group Registry (now delegated to database/persistent)
;; ============================================================================

;; ============================================================================
;; Zone Group Data Structure
;; ============================================================================

(defn make-zone-group
  "Create a zone group definition.
   
   Parameters:
   - id: Unique keyword identifier (e.g., :left-side, :all-zones)
   - name: Human-readable name
   - zone-ids: Set of zone IDs in this group
   - opts: Optional map with:
     - :type - Classification type (:spatial, :functional, etc.)"
  [id name zone-ids & {:keys [type]
                       :or {type :custom}}]
  {:id id
   :name name
   :zone-ids (set zone-ids)
   :type type
   :created-at (System/currentTimeMillis)})

(defn valid-zone-group?
  "Validate a zone group definition."
  [group]
  (and (keyword? (:id group))
       (string? (:name group))
       (set? (:zone-ids group))
       (keyword? (:type group))))

;; ============================================================================
;; CRUD Operations
;; ============================================================================

(defn create-group!
  "Create and register a zone group.
   Can accept either a group map or individual parameters."
  ([group]
   (when (valid-zone-group? group)
     (persist/add-zone-group! (:id group) group)
     group))
  ([id name zone-ids & opts]
   (let [group (apply make-zone-group id name zone-ids opts)]
     (create-group! group))))

(defn get-group
  "Get a zone group by ID."
  [group-id]
  (persist/get-zone-group group-id))

(defn update-group!
  "Update a zone group's properties."
  [group-id updates]
  (when (get-group group-id)
    (let [updated (merge (get-group group-id) updates)]
      (persist/add-zone-group! group-id updated)
      updated)))

(defn remove-group!
  "Remove a zone group from the registry."
  [group-id]
  (persist/remove-zone-group! group-id))

(defn list-groups
  "Get all zone groups as a sequence."
  []
  (vals (persist/get-zone-groups)))

(defn get-group-ids
  "Get all zone group IDs."
  []
  (keys (persist/get-zone-groups)))

(defn clear-groups!
  "Clear all zone groups from the registry."
  []
  (reset! persist/!zone-groups {})
  (persist/save-zone-groups!))

;; ============================================================================
;; Zone Membership
;; ============================================================================

(defn get-zones-in-group
  "Get the zone IDs in a group."
  [group-id]
  (:zone-ids (get-group group-id)))

(defn expand-group
  "Expand a group ID to its constituent zone IDs.
   Returns a set of zone IDs."
  [group-id]
  (or (get-zones-in-group group-id) #{}))

(defn zone-in-group?
  "Check if a zone is in a specific group."
  [zone-id group-id]
  (contains? (get-zones-in-group group-id) zone-id))

(defn add-zone-to-group!
  "Add a zone to a group."
  [group-id zone-id]
  (when-let [group (get-group group-id)]
    (update-group! group-id {:zone-ids (conj (:zone-ids group) zone-id)})))

(defn remove-zone-from-group!
  "Remove a zone from a group."
  [group-id zone-id]
  (when-let [group (get-group group-id)]
    (update-group! group-id {:zone-ids (disj (:zone-ids group) zone-id)})))

(defn get-groups-containing-zone
  "Get all groups that contain a specific zone."
  [zone-id]
  (filter #(contains? (:zone-ids %) zone-id) (list-groups)))

;; ============================================================================
;; Group Validation
;; ============================================================================

(defn validate-group-zones
  "Check if all zones in a group actually exist.
   Returns a map with :valid? and :missing-zones."
  [group-id]
  (let [zone-ids (get-zones-in-group group-id)
        existing-zones (set (zones/get-zone-ids))
        missing (clojure.set/difference zone-ids existing-zones)]
    {:valid? (empty? missing)
     :missing-zones missing}))

(defn remove-invalid-zones-from-group!
  "Remove any zone IDs from a group that don't exist in the zone registry."
  [group-id]
  (let [{:keys [missing-zones]} (validate-group-zones group-id)]
    (when (seq missing-zones)
      (let [group (get-group group-id)
            valid-zones (clojure.set/difference (:zone-ids group) missing-zones)]
        (update-group! group-id {:zone-ids valid-zones})))))

;; ============================================================================
;; Group Queries
;; ============================================================================

(defn get-groups-by-type
  "Get all groups of a specific type."
  [type]
  (filter #(= type (:type %)) (list-groups)))

(defn get-spatial-groups
  "Get all spatial groups."
  []
  (get-groups-by-type :spatial))

(defn get-functional-groups
  "Get all functional groups."
  []
  (get-groups-by-type :functional))

;; ============================================================================
;; Preset Groups
;; ============================================================================

(defn create-all-zones-group!
  "Create a group containing all registered zones."
  []
  (let [all-zone-ids (set (zones/get-zone-ids))]
    (create-group!
     (make-zone-group :all-zones "All Zones" all-zone-ids :type :preset))))

(defn refresh-all-zones-group!
  "Update the :all-zones group to include all current zones."
  []
  (let [all-zone-ids (set (zones/get-zone-ids))]
    (if (get-group :all-zones)
      (update-group! :all-zones {:zone-ids all-zone-ids})
      (create-all-zones-group!))))

;; ============================================================================
;; Persistence (delegated to database/persistent)
;; ============================================================================

(defn save-groups!
  "Save all zone groups to disk."
  []
  (persist/save-zone-groups!))

(defn load-groups!
  "Load zone groups from disk."
  []
  (persist/load-zone-groups!))

;; ============================================================================
;; Default Groups
;; ============================================================================

(defn ensure-default-groups!
  "Ensure default zone groups exist."
  []
  (when (empty? (persist/get-zone-groups))
    (create-all-zones-group!)))

;; ============================================================================
;; Effect Chain Management
;; ============================================================================

(defn set-group-effect-chain!
  "Set the effect chain for a zone group."
  [group-id effect-chain]
  (update-group! group-id {:effect-chain effect-chain}))

(defn get-group-effect-chain
  "Get the effect chain for a zone group."
  [group-id]
  (:effect-chain (get-group group-id)))

(defn add-effect-to-zone-group!
  "Add an effect to a zone group's effect chain."
  [group-id effect-instance]
  (let [group (get-group group-id)
        current-chain (or (:effect-chain group) (fx/empty-effect-chain))
        new-chain (fx/add-effect-to-chain current-chain effect-instance)]
    (update-group! group-id {:effect-chain new-chain})))

(defn remove-effect-from-zone-group!
  "Remove an effect from a zone group's effect chain by index."
  [group-id effect-index]
  (when-let [group (get-group group-id)]
    (when-let [chain (:effect-chain group)]
      (let [new-chain (fx/remove-effect-at chain effect-index)]
        (update-group! group-id {:effect-chain new-chain})))))

(defn update-zone-group-effect!
  "Update an effect in a zone group's effect chain."
  [group-id effect-index updates]
  (when-let [group (get-group group-id)]
    (when-let [chain (:effect-chain group)]
      (let [new-chain (fx/update-effect-at chain effect-index updates)]
        (update-group! group-id {:effect-chain new-chain})))))

(defn get-zone-group-for-zone
  "Find the first zone group that contains a given zone.
   Returns the group-id or nil if zone is not in any group."
  [zone-id]
  (when-let [groups (seq (get-groups-containing-zone zone-id))]
    (:id (first groups))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize zone group system by loading from disk."
  []
  (load-groups!)
  (ensure-default-groups!))

(defn shutdown!
  "Save zone groups before shutdown."
  []
  (save-groups!))
