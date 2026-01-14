(ns laser-show.routing.core
  "Core routing logic - orchestrates zone effect processing and zone matching.
   
   ROUTING FLOW:
   1. resolve-final-target (zone-effects.clj) - Apply zone effects to get final target
   2. build-routing-map (this ns) - Match zones to final target
   3. Frame service uses routing map to generate per-zone frames
   
   This namespace is the main entry point for routing operations.
   It coordinates zone-effects and zone-matcher to produce routing decisions."
  (:require [laser-show.routing.zone-effects :as zone-effects]
            [laser-show.routing.zone-matcher :as zone-matcher]
            [laser-show.state.queries :as queries]))


;; Routing Map Building


(defn build-routing-map
  "Build map of projector-id -> zone for a cue.
   
   This is the main routing function called by frame service.
   
   Args:
   - cue: The cue to route (with :destination-zone and :effects)
   - zones-items: Map of zone-id -> zone configuration
   
   Returns: Map of projector-id -> zone (full zone config, not just id)"
  [cue zones-items]
  ;; Step 1: Apply zone effects to get final target
  (let [final-target (zone-effects/resolve-final-target cue zones-items)
        ;; Step 2: Group zones by projector
        zones-by-projector (zone-matcher/group-zones-by-projector zones-items)]
    ;; Step 3: For each projector, find matching zone
    (zone-matcher/find-all-matching-zones zones-by-projector final-target)))

(defn build-routing-map-with-projector-filter
  "Build routing map, filtering to only enabled projectors.
   
   Args:
   - cue: The cue to route
   - zones-items: Map of zone-id -> zone configuration  
   - enabled-projector-ids: Set of projector IDs that are enabled
   
   Returns: Map of projector-id -> zone (only for enabled projectors)"
  [cue zones-items enabled-projector-ids]
  (let [routing-map (build-routing-map cue zones-items)]
    (select-keys routing-map enabled-projector-ids)))


;; Active Routes


(defn get-route-for-cue
  "Get the route (projector+zone) for a single cue.
   
   Returns: Vector of route maps [{:cue cue :projector-id pid :zone zone} ...]"
  [cue zones-items]
  (let [routing-map (build-routing-map cue zones-items)]
    (mapv
      (fn [[projector-id zone]]
        {:cue cue
         :projector-id projector-id
         :zone zone
         :zone-id (:id zone)})
      routing-map)))

(defn get-active-routes
  "Get all active projector+zone combinations for current cues.
   Used by frame service to know where to send frames.
   
   Args:
   - active-cues: Seq of currently active cue maps
   - zones-items: Map of zone-id -> zone configuration
   
   Returns: Vector of route maps, each containing:
   {:cue-id uuid, :projector-id keyword, :zone-id uuid, :zone map}"
  [active-cues zones-items]
  (vec
    (mapcat
      (fn [cue]
        (let [routing-map (build-routing-map cue zones-items)]
          (for [[proj-id zone] routing-map]
            {:cue-id (:id cue)
             :cue cue
             :projector-id proj-id
             :zone-id (:id zone)
             :zone zone})))
      active-cues)))


;; Query-Based Routing (uses state queries)


(defn get-current-routes-from-state
  "Get routes for a cue using current state.
   Convenience function that fetches zones from state.
   
   Args:
   - cue: The cue to route
   
   Returns: Map of projector-id -> zone"
  [cue]
  (let [zones-items (queries/zones-items)]
    (build-routing-map cue zones-items)))

(defn get-enabled-projectors-from-state
  "Get set of enabled projector IDs from state."
  []
  (->> (queries/projectors-items)
       (filter (fn [[_id proj]] (:enabled? proj true)))
       (map first)
       set))


;; Route Preview (for UI)


(defn preview-cue-routing
  "Preview routing for a cue without actually triggering it.
   Useful for UI to show where a cue would be sent.
   
   Args:
   - cue: The cue to preview
   - zones-items: Map of zone-id -> zone configuration
   
   Returns: Map with routing details:
   {:final-target {...}
    :routes [{:projector-id ... :zone ...} ...]
    :diagnostics {...}}"
  [cue zones-items]
  (let [final-target (zone-effects/resolve-final-target cue zones-items)
        routing-map (build-routing-map cue zones-items)
        diagnostics (zone-matcher/routing-diagnostics zones-items final-target)]
    {:final-target final-target
     :routes (vec (for [[proj-id zone] routing-map]
                    {:projector-id proj-id
                     :zone-id (:id zone)
                     :zone-name (:name zone)
                     :zone-type (:type zone)}))
     :diagnostics diagnostics}))

(defn preview-cue-routing-from-state
  "Preview routing for a cue using current state.
   
   Args:
   - cue: The cue to preview
   
   Returns: Map with routing details"
  [cue]
  (let [zones-items (queries/zones-items)]
    (preview-cue-routing cue zones-items)))


;; Routing Validation


(defn validate-cue-routing
  "Validate that a cue will route to at least one zone.
   
   Returns: {:valid? boolean :warnings [...] :errors [...]}"
  [cue zones-items]
  (let [final-target (zone-effects/resolve-final-target cue zones-items)
        routing-map (build-routing-map cue zones-items)
        routed-zones (vals routing-map)
        crowd-zones (filter #(= :crowd-scanning (:type %)) routed-zones)]
    {:valid? (seq routing-map)
     :route-count (count routing-map)
     :warnings (cond-> []
                 (empty? routing-map)
                 (conj {:type :no-routes
                        :message "Cue will not route to any zones"})
                 
                 (seq crowd-zones)
                 (conj {:type :crowd-scanning
                        :message (str "Cue routes to " (count crowd-zones) " crowd-scanning zone(s)")}))
     :errors []}))


;; Zone Effect Utilities


(defn cue-has-zone-effects?
  "Check if a cue has any zone effects in its effect chain."
  [cue]
  (zone-effects/has-zone-effects? cue))

(defn get-zone-effects-from-cue
  "Get zone effects from a cue's effect chain."
  [cue]
  (zone-effects/extract-zone-effects (or (:effects cue) [])))
