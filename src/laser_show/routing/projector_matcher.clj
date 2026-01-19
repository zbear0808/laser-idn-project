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
  (:require [laser-show.common.util :as u]))


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
   
   Returns: Vector of matching output configs"
  [all-outputs target]
  (let [{:keys [zone-groups projector-ids]} target
        enabled-outputs (filter-enabled-outputs all-outputs)]
    (cond
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
      
      ;; Default: route to :all zone group
      :else
      (filter-outputs-by-zone-group enabled-outputs :all))))


(defn group-outputs-by-physical-projector
  "Group outputs by their physical projector ID.
   Virtual projectors group with their parent.
   
   Returns: Map of projector-id -> vector of output configs"
  [outputs]
  (group-by :projector-id outputs))


;; Routing Map Building


(defn build-routing-map
  "Build a routing map from a cue's destination to output configs.
   
   Args:
   - cue: The cue with :destination-zone
   - projectors-items: Map of projector-id -> projector config
   - virtual-projectors: Map of vp-id -> virtual projector config
   
   Returns: Vector of output configs that should receive this cue"
  [cue projectors-items virtual-projectors]
  (let [all-outputs (build-all-outputs projectors-items virtual-projectors)
        destination (or (:destination-zone cue) {:zone-group-id :all})
        target {:zone-groups [(or (:zone-group-id destination) :all)]}]
    (find-outputs-for-target all-outputs target)))


(defn build-routing-map-multi-target
  "Build a routing map for multiple zone group targets.
   Used when a cue targets multiple zone groups.
   
   Args:
   - target-zone-groups: Vector of zone group IDs
   - projectors-items: Map of projector-id -> projector config
   - virtual-projectors: Map of vp-id -> virtual projector config
   
   Returns: Vector of output configs (deduplicated by output id)"
  [target-zone-groups projectors-items virtual-projectors]
  (let [all-outputs (build-all-outputs projectors-items virtual-projectors)
        target {:zone-groups target-zone-groups}
        matching-outputs (find-outputs-for-target all-outputs target)]
    ;; Deduplicate by output id (in case an output belongs to multiple target groups)
    (vec (vals (into {} (map (fn [o] [(:id o) o]) matching-outputs))))))


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
      {:matched? false :reason "No target criteria specified"})))


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
