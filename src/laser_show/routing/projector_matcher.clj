(ns laser-show.routing.projector-matcher
  "Projector matching logic - determines which projectors receive a cue based on zone groups.
   
   SIMPLIFIED ARCHITECTURE (v2):
   - Projectors and virtual projectors are directly assigned to zone groups
   - No intermediate 'zone' abstraction
   - Cues target zone groups, which resolve to projector outputs
   
   Key concepts:
   - Projectors have calibration effects (corner-pin, color curves) stored in effect chains
   - Virtual projectors inherit calibration from their parent projector
   - Both can be assigned to multiple zone groups
   
   NOTE: Corner-pin and color curve effects are applied as normal effects during
   frame rendering in multi_engine.clj, not here in the routing layer."
  (:require [clojure.tools.logging :as log]))


;; Debug Logging

(def ^:private debug-enabled? (atom false))

(defn enable-routing-debug! [] (reset! debug-enabled? true))
(defn disable-routing-debug! [] (reset! debug-enabled? false))


;; Output Config Building


(defn projector->output-config
  "Convert a projector to an output configuration.
   Note: Corner-pin is not included here - it's applied as an effect in multi_engine.clj."
  [projector-id projector]
  {:type :projector
   :id projector-id
   :projector-id projector-id
   :name (:name projector)
   :zone-groups (:zone-groups projector [])
   :tags (:tags projector #{})
   :enabled? (:enabled? projector true)})


(defn virtual-projector->output-config
  "Convert a virtual projector to an output configuration.
   Includes the parent projector ID for effect chain lookup.
   Note: Corner-pin is not included here - it's applied as an effect in multi_engine.clj."
  [vp-id vp]
  {:type :virtual-projector
   :id vp-id
   :projector-id (:parent-projector-id vp)
   :name (:name vp)
   :zone-groups (:zone-groups vp [])
   :tags (:tags vp #{})
   :enabled? (:enabled? vp true)})


;; Zone Group Matching


(defn output-matches-zone-group?
  "Check if an output (projector or VP) belongs to a zone group."
  [output zone-group-id]
  (some #(= % zone-group-id) (:zone-groups output [])))


(defn filter-enabled-outputs
  "Filter to only enabled outputs."
  [outputs]
  (filterv #(:enabled? % true) outputs))


;; Main Matching Functions


(defn build-all-outputs
  "Build a list of all possible outputs from projectors and virtual projectors.
   
   Args:
   - projectors-items: Map of projector-id -> projector config
   - virtual-projectors: Map of vp-id -> virtual projector config (can be nil)
   
   Returns: Vector of output configs"
  [projectors-items virtual-projectors]
  (let [proj-outputs (mapv (fn [[pid p]] (projector->output-config pid p))
                           projectors-items)
        vp-outputs (when virtual-projectors
                     (mapv (fn [[vpid vp]] (virtual-projector->output-config vpid vp))
                           virtual-projectors))]
    (into proj-outputs (or vp-outputs []))))





(defn find-outputs-for-target
  "Find all outputs that match a target specification.
   
   Target can specify:
   - :zone-groups - Vector of zone group IDs to match (OR logic)
   - :projector-ids - Vector of specific projector IDs (for direct targeting)
   
   Args:
   - all-outputs: Vector of output configs
   - target: Target specification map
   
   Returns: Vector of matching output configs (empty if no criteria specified)"
  [all-outputs target]
  (let [{:keys [zone-groups projector-ids]} target
        enabled-outputs (filter-enabled-outputs all-outputs)
        result (cond
                 ;; Direct projector targeting
                 (seq projector-ids)
                 (filterv #(some #{(:projector-id %)} projector-ids) enabled-outputs)
                 
                 ;; Zone group targeting (OR - match any of the specified groups)
                 (seq zone-groups)
                 (filterv (fn [output]
                            (some (fn [zg-id]
                                    (output-matches-zone-group? output zg-id))
                                  zone-groups))
                          enabled-outputs)
                 
                 ;; No target criteria specified - route to nothing
                 :else
                 [])]
    ;; Debug logging when enabled
    (when @debug-enabled?
      (log/debug (format "find-outputs-for-target: target=%s, enabled-outputs=%d, zone-groups-in-outputs=%s, matched=%d -> %s"
                         (pr-str target)
                         (count enabled-outputs)
                         (pr-str (mapv (fn [o] [(:id o) (:zone-groups o)]) enabled-outputs))
                         (count result)
                         (pr-str (mapv :id result)))))
    result))
