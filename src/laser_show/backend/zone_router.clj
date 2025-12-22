(ns laser-show.backend.zone-router
  "Zone routing and target resolution.
   Handles resolving cue targets to zones and routing frames to projectors.
   
   Effect Application Order:
   1. Cue effects (applied by cue system before routing)
   2. Zone group effects (applied during routing)
   3. Zone effects (applied during routing)
   4. Projector effects (applied by multi_projector_stream after routing)"
  (:require [laser-show.backend.zones :as zones]
            [laser-show.backend.zone-groups :as zone-groups]
            [laser-show.backend.projectors :as projectors]
            [laser-show.backend.zone-transform :as transform]
            [laser-show.animation.effects :as fx]))

;; ============================================================================
;; Target Resolution
;; ============================================================================

(defn resolve-target
  "Resolve a target specification to a set of zone IDs.
   
   Target types:
   - {:type :zone :zone-id :zone-1} -> #{:zone-1}
   - {:type :zone-group :group-id :left-side} -> #{:zone-1 :zone-2}
   - {:type :zones :zone-ids #{:zone-1 :zone-2}} -> #{:zone-1 :zone-2}
   - nil -> #{:zone-1} (default zone)"
  [target]
  (cond
    (nil? target)
    #{(zones/get-default-zone-id)}
    
    (= :zone (:type target))
    #{(:zone-id target)}
    
    (= :zone-group (:type target))
    (zone-groups/expand-group (:group-id target))
    
    (= :zones (:type target))
    (set (:zone-ids target))
    
    :else
    #{(zones/get-default-zone-id)}))

(defn resolve-target-to-zones
  "Resolve a target to actual zone configurations.
   Returns a sequence of zone maps."
  [target]
  (->> (resolve-target target)
       (map zones/get-zone)
       (remove nil?)
       (filter :enabled)))

;; ============================================================================
;; Projector Routing
;; ============================================================================

(defn group-zones-by-projector
  "Group a sequence of zones by their projector ID.
   Returns a map of projector-id -> [zones]."
  [zone-list]
  (group-by :projector-id zone-list))

(defn route-to-projectors
  "Map zone IDs to their projector IDs.
   Returns a map of projector-id -> #{zone-ids}."
  [zone-ids]
  (let [zone-list (map zones/get-zone zone-ids)
        grouped (group-zones-by-projector zone-list)]
    (into {}
          (map (fn [[proj-id zones]]
                 [proj-id (set (map :id zones))])
               grouped))))

;; ============================================================================
;; Priority Resolution
;; ============================================================================

(defn resolve-priority-conflicts
  "When multiple zones target the same projector, select the highest priority zone.
   Returns a map of projector-id -> zone (single zone per projector)."
  [zones-by-projector]
  (into {}
        (map (fn [[proj-id zone-list]]
               [proj-id (zones/get-highest-priority-zone zone-list)])
             zones-by-projector)))

(defn get-winning-zones-for-target
  "Given a target, resolve to zones and handle priority conflicts.
   Returns a map of projector-id -> zone."
  [target]
  (let [zone-list (resolve-target-to-zones target)
        grouped (group-zones-by-projector zone-list)]
    (resolve-priority-conflicts grouped)))

;; ============================================================================
;; Frame Preparation
;; ============================================================================

(defn apply-zone-group-effects
  "Apply zone group effects to a frame.
   Looks up the zone group that contains the zone and applies its effects."
  [frame zone-id time-ms bpm]
  (if-let [group-id (zone-groups/get-zone-group-for-zone zone-id)]
    (if-let [chain (zone-groups/get-group-effect-chain group-id)]
      (fx/apply-effect-chain frame chain time-ms bpm)
      frame)
    frame))

(defn apply-zone-effects
  "Apply zone-level effects to a frame."
  [frame zone-id time-ms bpm]
  (if-let [chain (zones/get-zone-effect-chain zone-id)]
    (fx/apply-effect-chain frame chain time-ms bpm)
    frame))

(defn prepare-frame-for-zone
  "Prepare a frame for a specific zone by applying transformations.
   Returns the transformed frame."
  [frame zone]
  (transform/transform-frame-for-zone frame zone))

(defn prepare-frame-for-zone-with-effects
  "Prepare a frame for a specific zone by applying effects and transformations.
   
   Effect application order:
   1. Zone group effects
   2. Zone effects
   3. Zone transformations (viewport mapping)
   
   Parameters:
   - frame: The base frame data
   - zone: The zone configuration map
   - time-ms: Current time in milliseconds
   - bpm: Current BPM for beat-synced effects"
  [frame zone time-ms bpm]
  (let [zone-id (:id zone)]
    (-> frame
        (apply-zone-group-effects zone-id time-ms bpm)
        (apply-zone-effects zone-id time-ms bpm)
        (transform/transform-frame-for-zone zone))))

(defn prepare-projector-frames
  "Given a base frame and target spec, prepare frames for each projector.
   
   Returns a map of projector-id -> transformed-frame.
   
   Process:
   1. Resolve target to zones
   2. Group zones by projector
   3. Resolve priority conflicts (one zone per projector)
   4. Transform frame for each winning zone
   5. Return map of projector-id -> frame"
  [frame target]
  (let [winning-zones (get-winning-zones-for-target target)]
    (into {}
          (map (fn [[proj-id zone]]
                 [proj-id (prepare-frame-for-zone frame zone)])
               winning-zones))))

(defn prepare-projector-frames-with-effects
  "Given a base frame and target spec, prepare frames for each projector with effects.
   
   Returns a map of projector-id -> transformed-frame.
   
   Process:
   1. Resolve target to zones
   2. Group zones by projector
   3. Resolve priority conflicts (one zone per projector)
   4. Apply zone group effects
   5. Apply zone effects
   6. Transform frame for each winning zone
   7. Return map of projector-id -> frame
   
   Parameters:
   - frame: The base frame (already has cue effects applied)
   - target: Target specification
   - time-ms: Current time in milliseconds
   - bpm: Current BPM for beat-synced effects"
  [frame target time-ms bpm]
  (let [winning-zones (get-winning-zones-for-target target)]
    (into {}
          (map (fn [[proj-id zone]]
                 [proj-id (prepare-frame-for-zone-with-effects frame zone time-ms bpm)])
               winning-zones))))

(defn prepare-frames-for-all-zones
  "Prepare frames for all zones in a target (without priority resolution).
   Useful when you want to send the same animation to multiple zones on the same projector.
   
   Returns a map of zone-id -> transformed-frame."
  [frame target]
  (let [zone-list (resolve-target-to-zones target)]
    (into {}
          (map (fn [zone]
                 [(:id zone) (prepare-frame-for-zone frame zone)])
               zone-list))))

(defn prepare-frames-for-all-zones-with-effects
  "Prepare frames for all zones in a target with effects (without priority resolution).
   Useful when you want to send the same animation to multiple zones on the same projector.
   
   Returns a map of zone-id -> transformed-frame.
   
   Parameters:
   - frame: The base frame (already has cue effects applied)
   - target: Target specification
   - time-ms: Current time in milliseconds
   - bpm: Current BPM for beat-synced effects"
  [frame target time-ms bpm]
  (let [zone-list (resolve-target-to-zones target)]
    (into {}
          (map (fn [zone]
                 [(:id zone) (prepare-frame-for-zone-with-effects frame zone time-ms bpm)])
               zone-list))))

;; ============================================================================
;; Active Projector Queries
;; ============================================================================

(defn get-projectors-for-target
  "Get the projector IDs that will receive frames for a given target."
  [target]
  (let [zone-list (resolve-target-to-zones target)]
    (set (map :projector-id zone-list))))

(defn get-active-projectors-for-target
  "Get only active projectors that will receive frames for a given target."
  [target]
  (let [proj-ids (get-projectors-for-target target)]
    (filter projectors/projector-active? proj-ids)))

;; ============================================================================
;; Validation
;; ============================================================================

(defn valid-target?
  "Check if a target specification is valid."
  [target]
  (cond
    (nil? target) true
    
    (= :zone (:type target))
    (and (keyword? (:zone-id target))
         (some? (zones/get-zone (:zone-id target))))
    
    (= :zone-group (:type target))
    (and (keyword? (:group-id target))
         (some? (zone-groups/get-group (:group-id target))))
    
    (= :zones (:type target))
    (and (set? (:zone-ids target))
         (every? #(some? (zones/get-zone %)) (:zone-ids target)))
    
    :else false))

(defn validate-target
  "Validate a target and return details about any issues.
   Returns {:valid? true/false :errors [...]}."
  [target]
  (cond
    (nil? target)
    {:valid? true :errors []}
    
    (= :zone (:type target))
    (let [zone-id (:zone-id target)
          zone (zones/get-zone zone-id)]
      (cond
        (nil? zone-id)
        {:valid? false :errors ["Missing :zone-id in target"]}
        
        (nil? zone)
        {:valid? false :errors [(str "Zone not found: " zone-id)]}
        
        (not (:enabled zone))
        {:valid? false :errors [(str "Zone is disabled: " zone-id)]}
        
        :else
        {:valid? true :errors []}))
    
    (= :zone-group (:type target))
    (let [group-id (:group-id target)
          group (zone-groups/get-group group-id)]
      (cond
        (nil? group-id)
        {:valid? false :errors ["Missing :group-id in target"]}
        
        (nil? group)
        {:valid? false :errors [(str "Zone group not found: " group-id)]}
        
        (empty? (:zone-ids group))
        {:valid? false :errors [(str "Zone group is empty: " group-id)]}
        
        :else
        (let [validation (zone-groups/validate-group-zones group-id)]
          (if (:valid? validation)
            {:valid? true :errors []}
            {:valid? false 
             :errors [(str "Zone group has missing zones: " (:missing-zones validation))]}))))
    
    (= :zones (:type target))
    (let [zone-ids (:zone-ids target)]
      (cond
        (nil? zone-ids)
        {:valid? false :errors ["Missing :zone-ids in target"]}
        
        (empty? zone-ids)
        {:valid? false :errors ["Empty :zone-ids in target"]}
        
        :else
        (let [missing (filter #(nil? (zones/get-zone %)) zone-ids)]
          (if (empty? missing)
            {:valid? true :errors []}
            {:valid? false :errors [(str "Zones not found: " (vec missing))]}))))
    
    :else
    {:valid? false :errors [(str "Unknown target type: " (:type target))]}))

;; ============================================================================
;; Target Construction Helpers
;; ============================================================================

(defn make-zone-target
  "Create a target for a single zone."
  [zone-id]
  {:type :zone :zone-id zone-id})

(defn make-group-target
  "Create a target for a zone group."
  [group-id]
  {:type :zone-group :group-id group-id})

(defn make-zones-target
  "Create a target for multiple specific zones."
  [zone-ids]
  {:type :zones :zone-ids (set zone-ids)})

(def default-target
  "The default target (zone-1)."
  {:type :zone :zone-id :zone-1})
