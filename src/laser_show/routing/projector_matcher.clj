(ns laser-show.routing.projector-matcher
  "Projector matching logic - determines which projectors receive a cue based on zone groups.
   
   SIMPLIFIED ARCHITECTURE (v2):
   - Projectors and virtual projectors are directly assigned to zone groups
   - No intermediate 'zone' abstraction
   - Cues target zone groups, which resolve to projector outputs
   
   Key concepts:
   - Projectors have corner-pin geometry and color curves
   - Virtual projectors have alternate corner-pin but inherit parent's color curves
   - Both can be assigned to multiple zone groups"
  (:require [clojure.tools.logging :as log]))


;; Debug Logging

(def ^:private debug-enabled? (atom false))

(defn enable-routing-debug! [] (reset! debug-enabled? true))
(defn disable-routing-debug! [] (reset! debug-enabled? false))


;; Output Config Building


(def default-corner-pin
  "Default corner-pin with no transformation (identity mapping)."
  {:tl-x -1.0 :tl-y 1.0
   :tr-x 1.0 :tr-y 1.0
   :bl-x -1.0 :bl-y -1.0
   :br-x 1.0 :br-y -1.0})


(defn projector->output-config
  "Convert a projector to an output configuration."
  [projector-id projector]
  {:type :projector
   :id projector-id
   :projector-id projector-id
   :name (:name projector)
   :corner-pin (or (:corner-pin projector) default-corner-pin)
   :zone-groups (:zone-groups projector [])
   :tags (:tags projector #{})
   :enabled? (:enabled? projector true)})


(defn virtual-projector->output-config
  "Convert a virtual projector to an output configuration.
   Includes the parent projector ID for color curve inheritance."
  [vp-id vp]
  {:type :virtual-projector
   :id vp-id
   :projector-id (:parent-projector-id vp)
   :name (:name vp)
   :corner-pin (or (:corner-pin vp) default-corner-pin)
   :zone-groups (:zone-groups vp [])
   :tags (:tags vp #{})
   :enabled? (:enabled? vp true)})


;; Zone Group Matching


(defn output-matches-zone-group?
  "Check if an output (projector or VP) belongs to a zone group."
  [output zone-group-id]
  (some #(= % zone-group-id) (:zone-groups output [])))


(defn filter-outputs-by-zone-group
  "Filter outputs to only those belonging to a specific zone group."
  [outputs zone-group-id]
  (filterv #(output-matches-zone-group? % zone-group-id) outputs))


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


(defn find-outputs-for-zone-group
  "Find all outputs (projectors and VPs) that match a zone group.
   
   Args:
   - all-outputs: Vector of output configs (from build-all-outputs)
   - zone-group-id: The zone group to match
   
   Returns: Vector of matching output configs"
  [all-outputs zone-group-id]
  (-> all-outputs
      filter-enabled-outputs
      (filter-outputs-by-zone-group zone-group-id)))


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


;; Routing Map Building


(defn build-routing-map
  "Build a routing map from a cue's destination to output configs.
   
   Args:
   - cue: The cue with :destination-zone
   - projectors-items: Map of projector-id -> projector config
   - virtual-projectors: Map of vp-id -> virtual projector config
   
   Returns: Vector of output configs that should receive this cue
            (empty if no destination specified)"
  [cue projectors-items virtual-projectors]
  (let [all-outputs (build-all-outputs projectors-items virtual-projectors)
        destination (:destination-zone cue)
        zone-group-id (:zone-group-id destination)
        target (if zone-group-id
                 {:zone-groups [zone-group-id]}
                 {:zone-groups []})]
    (find-outputs-for-target all-outputs target)))


;; Diagnostic Functions


(defn explain-output-match
  "Explain why an output did or didn't match a target.
   Useful for debugging routing issues."
  [output target]
  (let [{:keys [zone-groups projector-ids]} target
        output-groups (set (:zone-groups output []))
        enabled? (:enabled? output true)]
    (cond
      (not enabled?)
      {:matched? false :reason "Output is disabled"}
      
      (seq projector-ids)
      (if (some #{(:projector-id output)} projector-ids)
        {:matched? true :reason "Projector ID matched direct targeting"}
        {:matched? false :reason "Projector ID not in target list"})
      
      (seq zone-groups)
      (let [matching-groups (filter output-groups zone-groups)]
        (if (seq matching-groups)
          {:matched? true 
           :reason (str "Zone groups " (vec matching-groups) " matched target")}
          {:matched? false 
           :reason (str "Output groups " (vec output-groups) 
                        " don't intersect with target " (vec zone-groups))}))
      
      :else
      {:matched? false :reason "No target criteria specified (routes to nothing)"})))


(defn routing-diagnostics
  "Generate diagnostics for a routing operation.
   Shows which outputs matched and why."
  [projectors-items virtual-projectors target]
  (let [all-outputs (build-all-outputs projectors-items virtual-projectors)]
    {:target target
     :outputs (mapv (fn [output]
                      (merge
                        {:id (:id output)
                         :name (:name output)
                         :type (:type output)
                         :projector-id (:projector-id output)}
                        (explain-output-match output target)))
                    all-outputs)
     :matching-outputs (find-outputs-for-target all-outputs target)}))
