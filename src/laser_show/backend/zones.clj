(ns laser-show.backend.zones
  "Zone configuration and management.
   Zones are logical projection spaces that can receive animations.
   Each zone maps to a projector and can have transformations applied.
   
   Zones now support effect chains for per-zone effects:
   - :effect-chain {:effects [{:effect-id ... :enabled true :params {...}} ...]}"
  (:require [laser-show.state.persistent :as persist]
            [laser-show.backend.projectors :as projectors]
            [laser-show.animation.effects :as fx]))

;; ============================================================================
;; Constants
;; ============================================================================

(def standard-tags
  "Standard zone tags for safety and functionality classification."
  #{:safe           ; Zone is in a safe area (default)
    :crowd-scanning ; Zone may scan over crowd areas (requires special attention)
    :graphics       ; Zone for graphical content (beams, shapes, animations)
    :effects        ; Zone for atmospheric effects
    :restricted})   ; Zone has restricted usage (requires authorization)

(def default-tags #{:safe :graphics})

(def default-transformations
  "Default transformation settings (identity transform)."
  {:viewport {:x-min -1.0 :x-max 1.0 :y-min -1.0 :y-max 1.0}
   :scale {:x 1.0 :y 1.0}
   :offset {:x 0.0 :y 0.0}
   :rotation 0.0})

;; ============================================================================
;; Zone Registry (now delegated to database/persistent)
;; ============================================================================

;; ============================================================================
;; Zone Data Structure
;; ============================================================================

(defn make-zone
  "Create a zone definition.
   
   Parameters:
   - id: Unique keyword identifier (e.g., :zone-1, :zone-left)
   - name: Human-readable name
   - projector-id: Which projector displays this zone
   - opts: Optional map with:
     - :tags - Set of classification tags (default #{:safe :graphics})
     - :transformations - Geometric transformations map
     - :blocked-regions - List of blocked areas for safety
     - :priority - Priority when multiple zones map to same projector (default 1)
     - :enabled - Whether zone is enabled (default true)"
  [id name projector-id & {:keys [tags transformations blocked-regions priority enabled]
                           :or {tags default-tags
                                transformations default-transformations
                                blocked-regions []
                                priority 1
                                enabled true}}]
  {:id id
   :name name
   :projector-id projector-id
   :tags (set tags)
   :transformations (merge default-transformations transformations)
   :blocked-regions (vec blocked-regions)
   :priority priority
   :enabled enabled
   :created-at (System/currentTimeMillis)})

(defn valid-zone?
  "Validate a zone definition."
  [zone]
  (and (keyword? (:id zone))
       (string? (:name zone))
       (keyword? (:projector-id zone))
       (set? (:tags zone))
       (map? (:transformations zone))
       (vector? (:blocked-regions zone))
       (integer? (:priority zone))
       (boolean? (:enabled zone))))

;; ============================================================================
;; CRUD Operations
;; ============================================================================

(defn create-zone!
  "Create and register a zone.
   Can accept either a zone map or individual parameters."
  ([zone]
   (when (valid-zone? zone)
     (persist/add-zone! (:id zone) zone)
     zone))
  ([id name projector-id & opts]
   (let [zone (apply make-zone id name projector-id opts)]
     (create-zone! zone))))

(defn get-zone
  "Get a zone by ID."
  [zone-id]
  (persist/get-zone zone-id))

(defn update-zone!
  "Update a zone's properties."
  [zone-id updates]
  (when (get-zone zone-id)
    (let [updated (merge (get-zone zone-id) updates)]
      (persist/add-zone! zone-id updated)
      updated)))

(defn remove-zone!
  "Remove a zone from the registry."
  [zone-id]
  (persist/remove-zone! zone-id))

(defn list-zones
  "Get all zones as a sequence."
  []
  (vals (persist/get-zones)))

(defn get-zone-ids
  "Get all zone IDs."
  []
  (keys (persist/get-zones)))

(defn clear-zones!
  "Clear all zones from the registry."
  []
  (reset! persist/!zones {})
  (persist/save-zones!))

;; ============================================================================
;; Zone Queries
;; ============================================================================

(defn get-enabled-zones
  "Get all enabled zones."
  []
  (filter :enabled (list-zones)))

(defn get-zones-for-projector
  "Get all zones mapped to a specific projector."
  [projector-id]
  (filter #(= projector-id (:projector-id %)) (list-zones)))

(defn get-enabled-zones-for-projector
  "Get all enabled zones mapped to a specific projector."
  [projector-id]
  (filter :enabled (get-zones-for-projector projector-id)))

;; ============================================================================
;; Tag Management
;; ============================================================================

(defn zone-has-tag?
  "Check if a zone has a specific tag."
  [zone-id tag]
  (contains? (:tags (get-zone zone-id)) tag))

(defn add-zone-tag!
  "Add a tag to a zone."
  [zone-id tag]
  (when-let [zone (get-zone zone-id)]
    (update-zone! zone-id {:tags (conj (:tags zone) tag)})))

(defn remove-zone-tag!
  "Remove a tag from a zone."
  [zone-id tag]
  (when-let [zone (get-zone zone-id)]
    (update-zone! zone-id {:tags (disj (:tags zone) tag)})))

(defn get-zones-by-tag
  "Get all zones that have a specific tag."
  [tag]
  (filter #(contains? (:tags %) tag) (list-zones)))

(defn get-zones-by-tags
  "Get all zones that have ALL of the specified tags."
  [tags]
  (let [tag-set (set tags)]
    (filter #(every? (fn [t] (contains? (:tags %) t)) tag-set) (list-zones))))

(defn get-crowd-scanning-zones
  "Get all zones marked for crowd scanning (safety-critical)."
  []
  (get-zones-by-tag :crowd-scanning))

(defn get-safe-zones
  "Get all zones marked as safe."
  []
  (get-zones-by-tag :safe))

;; ============================================================================
;; Transformation Helpers
;; ============================================================================

(defn get-zone-transformations
  "Get the transformations for a zone."
  [zone-id]
  (:transformations (get-zone zone-id)))

(defn set-zone-viewport!
  "Set the viewport for a zone."
  [zone-id viewport]
  (when-let [zone (get-zone zone-id)]
    (update-zone! zone-id 
                  {:transformations (assoc (:transformations zone) :viewport viewport)})))

(defn set-zone-scale!
  "Set the scale for a zone."
  [zone-id scale]
  (when-let [zone (get-zone zone-id)]
    (update-zone! zone-id 
                  {:transformations (assoc (:transformations zone) :scale scale)})))

(defn set-zone-offset!
  "Set the offset for a zone."
  [zone-id offset]
  (when-let [zone (get-zone zone-id)]
    (update-zone! zone-id 
                  {:transformations (assoc (:transformations zone) :offset offset)})))

(defn set-zone-rotation!
  "Set the rotation for a zone (in radians)."
  [zone-id rotation]
  (when-let [zone (get-zone zone-id)]
    (update-zone! zone-id 
                  {:transformations (assoc (:transformations zone) :rotation rotation)})))

;; ============================================================================
;; Blocked Regions
;; ============================================================================

(defn make-blocked-rect
  "Create a rectangular blocked region.
   Coordinates are in normalized space [-1.0, 1.0]."
  [x-min x-max y-min y-max]
  {:type :rect
   :x-min x-min
   :x-max x-max
   :y-min y-min
   :y-max y-max})

(defn make-blocked-circle
  "Create a circular blocked region.
   Coordinates are in normalized space [-1.0, 1.0]."
  [center-x center-y radius]
  {:type :circle
   :center-x center-x
   :center-y center-y
   :radius radius})

(defn add-blocked-region!
  "Add a blocked region to a zone."
  [zone-id region]
  (when-let [zone (get-zone zone-id)]
    (update-zone! zone-id {:blocked-regions (conj (:blocked-regions zone) region)})))

(defn clear-blocked-regions!
  "Clear all blocked regions from a zone."
  [zone-id]
  (update-zone! zone-id {:blocked-regions []}))

(defn get-blocked-regions
  "Get the blocked regions for a zone."
  [zone-id]
  (:blocked-regions (get-zone zone-id)))

;; ============================================================================
;; Priority Management
;; ============================================================================

(defn set-zone-priority!
  "Set the priority for a zone (higher = wins when multiple zones target same projector)."
  [zone-id priority]
  (update-zone! zone-id {:priority priority}))

(defn get-highest-priority-zone
  "Get the highest priority zone from a collection of zones."
  [zones]
  (when (seq zones)
    (apply max-key :priority zones)))

;; ============================================================================
;; Enable/Disable
;; ============================================================================

(defn enable-zone!
  "Enable a zone."
  [zone-id]
  (update-zone! zone-id {:enabled true}))

(defn disable-zone!
  "Disable a zone."
  [zone-id]
  (update-zone! zone-id {:enabled false}))

(defn zone-enabled?
  "Check if a zone is enabled."
  [zone-id]
  (:enabled (get-zone zone-id)))

;; ============================================================================
;; Persistence (delegated to database/persistent)
;; ============================================================================

(defn save-zones!
  "Save all zones to disk."
  []
  (persist/save-zones!))

(defn load-zones!
  "Load zones from disk."
  []
  (persist/load-zones!))

;; ============================================================================
;; Default Zone
;; ============================================================================

(defn ensure-default-zone!
  "Ensure at least one default zone exists.
   Creates :zone-1 mapped to the default projector if no zones are registered."
  []
  (when (empty? (persist/get-zones))
    (let [default-projector-id (projectors/get-default-projector-id)]
      (create-zone!
       (make-zone :zone-1 "Default Zone" default-projector-id)))))

(defn get-default-zone-id
  "Get the default zone ID (first registered or :zone-1)."
  []
  (or (first (get-zone-ids))
      :zone-1))

;; ============================================================================
;; Effect Chain Management
;; ============================================================================

(defn set-zone-effect-chain!
  "Set the effect chain for a zone."
  [zone-id effect-chain]
  (update-zone! zone-id {:effect-chain effect-chain}))

(defn get-zone-effect-chain
  "Get the effect chain for a zone."
  [zone-id]
  (:effect-chain (get-zone zone-id)))

(defn add-effect-to-zone!
  "Add an effect to a zone's effect chain."
  [zone-id effect-instance]
  (let [zone (get-zone zone-id)
        current-chain (or (:effect-chain zone) (fx/empty-effect-chain))
        new-chain (fx/add-effect-to-chain current-chain effect-instance)]
    (update-zone! zone-id {:effect-chain new-chain})))

(defn remove-effect-from-zone!
  "Remove an effect from a zone's effect chain by index."
  [zone-id effect-index]
  (when-let [zone (get-zone zone-id)]
    (when-let [chain (:effect-chain zone)]
      (let [new-chain (fx/remove-effect-at chain effect-index)]
        (update-zone! zone-id {:effect-chain new-chain})))))

(defn update-zone-effect!
  "Update an effect in a zone's effect chain."
  [zone-id effect-index updates]
  (when-let [zone (get-zone zone-id)]
    (when-let [chain (:effect-chain zone)]
      (let [new-chain (fx/update-effect-at chain effect-index updates)]
        (update-zone! zone-id {:effect-chain new-chain})))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize zone system by loading from disk."
  []
  (load-zones!)
  (ensure-default-zone!))

(defn shutdown!
  "Save zones before shutdown."
  []
  (save-zones!))
