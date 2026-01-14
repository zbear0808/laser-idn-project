(ns laser-show.routing.zone-matcher
  "Zone matching logic - determines which zones receive a cue based on targets.
   
   After zone effects have resolved the final target specification,
   this namespace matches that target against actual zones to determine
   which projector+zone combinations should receive the cue.
   
   Key concepts:
   - Each projector has multiple zones (default, graphics, crowd-scanning)
   - Each zone belongs to one or more zone groups
   - Matching checks if zone's groups intersect with target groups
   - Priority determines which zone wins when multiple match"
  (:require [laser-show.common.util :as u]))


;; Zone Group Matching


(defn zone-matches-groups?
  "Check if a zone's groups intersect with target groups.
   Returns true if any of the zone's zone-groups are in the target set."
  [zone target-groups]
  (let [zone-groups (set (:zone-groups zone []))]
    (boolean (some zone-groups target-groups))))

(defn zone-matches-id?
  "Check if a zone's ID is in the target zone IDs set."
  [zone target-zone-ids]
  (contains? (set target-zone-ids) (:id zone)))


;; Zone Filtering


(defn filter-enabled-zones
  "Filter to only enabled zones."
  [zones]
  (filterv #(:enabled? % true) zones))

(defn filter-by-target
  "Filter zones by target specification.
   
   A zone matches if:
   - Target mode is :zone-groups AND zone's groups intersect with target groups
   - OR target mode is :zones AND zone's ID is in target zone IDs
   
   Returns seq of matching zones."
  [zones final-target]
  (let [{:keys [mode zone-groups zone-ids]} final-target
        target-groups (set zone-groups)
        target-ids (set zone-ids)]
    (filterv
      (fn [zone]
        (case mode
          :zone-groups (zone-matches-groups? zone target-groups)
          :zones (zone-matches-id? zone target-ids)
          ;; Default: match by zone groups
          (zone-matches-groups? zone target-groups)))
      zones)))


;; Priority Resolution


(defn resolve-priority
  "Pick winning zone when multiple zones match, considering preferred type.
   
   Priority logic:
   1. If preferred-zone-type is specified and a zone of that type matches, use it
   2. Otherwise use first matching zone (zones should be in priority order)
   
   Args:
   - matching-zones: Vector of zones that match the target
   - preferred-type: Preferred zone type (:default, :graphics, :crowd-scanning)
   
   Returns: Single zone or nil"
  [matching-zones preferred-type]
  (if (empty? matching-zones)
    nil
    (or
      ;; Try to find a zone of the preferred type
      (when preferred-type
        (first (filter #(= preferred-type (:type %)) matching-zones)))
      ;; Fall back to first matching zone
      (first matching-zones))))


;; Main Matching Functions


(defn find-matching-zone-for-projector
  "Given a projector's zones and final target, find the matching zone.
   
   Args:
   - projector-zones: Vector of zones belonging to this projector
   - final-target: Target specification from zone-effects/resolve-final-target
   
   Returns: Matching zone or nil if no match."
  [projector-zones final-target]
  (let [enabled-zones (filter-enabled-zones projector-zones)
        matching-zones (filter-by-target enabled-zones final-target)
        preferred-type (:preferred-zone-type final-target :default)]
    (resolve-priority matching-zones preferred-type)))

(defn find-all-matching-zones
  "Find all zones that match a target specification across all projectors.
   
   Args:
   - zones-by-projector: Map of projector-id -> vector of zones
   - final-target: Target specification from zone-effects/resolve-final-target
   
   Returns: Map of projector-id -> matching-zone (or nil if no match)"
  [zones-by-projector final-target]
  (into {}
    (for [[projector-id projector-zones] zones-by-projector
          :let [matching-zone (find-matching-zone-for-projector projector-zones final-target)]
          :when matching-zone]
      [projector-id matching-zone])))


;; Zone Lookup Helpers


(defn group-zones-by-projector
  "Group a flat map of zones by their projector-id.
   
   Args:
   - zones-items: Map of zone-id -> zone
   
   Returns: Map of projector-id -> vector of zones (sorted by type for consistent priority)"
  [zones-items]
  (let [zones (vals zones-items)
        by-projector (group-by :projector-id zones)
        ;; Sort zones within each projector by type for consistent priority
        ;; :default first, then :graphics, then :crowd-scanning
        type-order {:default 0 :graphics 1 :crowd-scanning 2}]
    (u/map-into {}
      (fn [[proj-id proj-zones]]
        [proj-id (vec (sort-by #(get type-order (:type %) 99) proj-zones))])
      by-projector)))

(defn get-zones-for-projector-id
  "Get zones for a specific projector from zones-items map.
   
   Args:
   - zones-items: Map of zone-id -> zone
   - projector-id: Projector ID to filter by
   
   Returns: Vector of zones for this projector"
  [zones-items projector-id]
  (let [type-order {:default 0 :graphics 1 :crowd-scanning 2}]
    (->> (vals zones-items)
         (filterv #(= projector-id (:projector-id %)))
         (sort-by #(get type-order (:type %) 99))
         vec)))


;; Diagnostic Functions


(defn explain-match
  "Explain why a zone did or didn't match a target.
   Useful for debugging routing issues.
   
   Returns: Map with :matched? boolean and :reason string"
  [zone final-target]
  (let [{:keys [mode zone-groups zone-ids]} final-target
        zone-enabled? (:enabled? zone true)
        zone-zone-groups (set (:zone-groups zone []))
        target-groups (set zone-groups)
        target-ids (set zone-ids)]
    (cond
      (not zone-enabled?)
      {:matched? false :reason "Zone is disabled"}
      
      (= mode :zone-groups)
      (if (some zone-zone-groups target-groups)
        {:matched? true 
         :reason (str "Zone groups " (vec zone-zone-groups) " intersect with target " (vec target-groups))}
        {:matched? false 
         :reason (str "Zone groups " (vec zone-zone-groups) " don't intersect with target " (vec target-groups))})
      
      (= mode :zones)
      (if (contains? target-ids (:id zone))
        {:matched? true :reason "Zone ID is in target set"}
        {:matched? false :reason "Zone ID is not in target set"})
      
      :else
      {:matched? false :reason (str "Unknown target mode: " mode)})))

(defn routing-diagnostics
  "Generate diagnostics for a routing operation.
   Shows which zones matched and why.
   
   Returns: Map with diagnostic information"
  [zones-items final-target]
  (let [zones-by-projector (group-zones-by-projector zones-items)]
    {:target final-target
     :projectors
     (into {}
       (for [[proj-id proj-zones] zones-by-projector]
         [proj-id
          {:zones (mapv (fn [zone]
                          (merge
                            {:id (:id zone)
                             :name (:name zone)
                             :type (:type zone)}
                            (explain-match zone final-target)))
                        proj-zones)
           :selected-zone (when-let [match (find-matching-zone-for-projector proj-zones final-target)]
                            {:id (:id match)
                             :name (:name match)
                             :type (:type match)})}]))}))
