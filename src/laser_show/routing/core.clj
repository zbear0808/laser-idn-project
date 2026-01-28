(ns laser-show.routing.core
  "Core routing logic - orchestrates projector matching for cue routing.
   
   SIMPLIFIED ARCHITECTURE (v2):
   - Cues target zone groups directly
   - Projectors and virtual projectors are matched to zone groups
   - No intermediate 'zone' abstraction
   
   ROUTING FLOW:
   1. Cue specifies destination (zone-group)
   2. Zone effects can modify the target (reroute, broadcast, mirror)
   3. Find all projectors/VPs in the final target zone group(s)
   4. Return output configs with corner-pin and projector reference
   5. Frame service applies corner-pin transform and color curves"
  (:require [laser-show.routing.projector-matcher :as pm]
            [laser-show.routing.zone-effects :as ze]
            [laser-show.state.core :as state]
            [laser-show.state.extractors :as ex]))


;; Routing Map Building


(defn build-routing-map
  "Build a routing map for a cue.
   
   This is the main routing function called by frame service.
   
   NOW processes zone effects to determine final target:
   1. Read :destination-zone from cue (nil means route to nothing)
   2. Read :effects from cue
   3. Apply zone effects to get final target zone groups
   4. Match projectors to final target
   
   Args:
   - cue: The cue to route (with :destination-zone and optionally :effects)
   - projectors-items: Map of projector-id -> projector config
   - virtual-projectors: Map of vp-id -> virtual projector config (can be nil)
   - all-zone-group-ids: Set of all zone group IDs in the system (for broadcast effect)
   
   Returns: Vector of output configs, each containing:
   {:type :projector or :virtual-projector
    :id output-id
    :projector-id physical-projector-id
    :corner-pin geometry config
    :enabled? boolean}"
  [cue projectors-items virtual-projectors all-zone-group-ids]
  (let [all-outputs (pm/build-all-outputs projectors-items virtual-projectors)
        
        ;; Process zone effects to get final target (no default destination)
        destination (:destination-zone cue)
        effects (or (:effects cue) [])
        final-target-groups (ze/resolve-final-target destination effects all-zone-group-ids)
        
        ;; Match using final target - convert set to vector for find-outputs-for-target
        target {:zone-groups (vec final-target-groups)}]
    (pm/find-outputs-for-target all-outputs target)))


(defn build-routing-map-with-filter
  "Build routing map, filtering to only enabled projectors.
   
   Args:
   - cue: The cue to route
   - projectors-items: Map of projector-id -> projector config
   - virtual-projectors: Map of vp-id -> virtual projector config
   - enabled-projector-ids: Set of projector IDs that are enabled
   
   Returns: Vector of output configs (only for enabled projectors)"
  [cue projectors-items virtual-projectors enabled-projector-ids]
  (let [outputs (pm/build-routing-map cue projectors-items virtual-projectors)]
    (filterv #(contains? enabled-projector-ids (:projector-id %)) outputs)))


;; Active Routes


(defn get-route-for-cue
  "Get the routes (outputs) for a single cue.
   
   Returns: Vector of route maps [{:cue cue :output output-config} ...]"
  [cue projectors-items virtual-projectors]
  (let [outputs (pm/build-routing-map cue projectors-items virtual-projectors)]
    (mapv (fn [output]
            {:cue cue
             :projector-id (:projector-id output)
             :output-id (:id output)
             :output output})
          outputs)))


(defn get-active-routes
  "Get all active output configurations for current cues.
   Used by frame service to know where to send frames.
   
   Args:
   - active-cues: Seq of currently active cue maps
   - projectors-items: Map of projector-id -> projector config
   - virtual-projectors: Map of vp-id -> virtual projector config
   
   Returns: Vector of route maps, each containing:
   {:cue-id uuid, :cue map, :projector-id keyword, :output-id id, :output config}"
  [active-cues projectors-items virtual-projectors]
  (vec
    (mapcat
      (fn [cue]
        (let [outputs (pm/build-routing-map cue projectors-items virtual-projectors)]
          (for [output outputs]
            {:cue-id (:id cue)
             :cue cue
             :projector-id (:projector-id output)
             :output-id (:id output)
             :output output})))
      active-cues)))


;; Query-Based Routing (uses state queries)


(defn get-current-routes-from-state
  "Get routes for a cue using current state.
   Convenience function that fetches projectors from state.
   
   Args:
   - cue: The cue to route
   
   Returns: Vector of output configs"
  [cue]
  (let [raw-state (state/get-raw-state)
        projectors-items (ex/projectors-items raw-state)
        virtual-projectors (ex/virtual-projectors raw-state)]
    (pm/build-routing-map cue projectors-items virtual-projectors)))


(defn get-enabled-projectors-from-state
  "Get set of enabled projector IDs from state."
  []
  (->> (ex/enabled-projectors (state/get-raw-state))
       (map first)
       set))


;; Route Preview (for UI)


(defn preview-cue-routing
  "Preview routing for a cue without actually triggering it.
   Useful for UI to show where a cue would be sent.
   
   Args:
   - cue: The cue to preview
   - projectors-items: Map of projector-id -> projector config
   - virtual-projectors: Map of vp-id -> virtual projector config
   
   Returns: Map with routing details:
   {:outputs [{:id ... :name ... :type ... :projector-id ...} ...]
    :count number-of-outputs}"
  [cue projectors-items virtual-projectors]
  (let [outputs (pm/build-routing-map cue projectors-items virtual-projectors)]
    {:outputs (mapv (fn [o]
                      {:id (:id o)
                       :name (:name o)
                       :type (:type o)
                       :projector-id (:projector-id o)})
                    outputs)
     :count (count outputs)}))


(defn preview-cue-routing-from-state
  "Preview routing for a cue using current state.
   
   Args:
   - cue: The cue to preview
   
   Returns: Map with routing details"
  [cue]
  (let [raw-state (state/get-raw-state)
        projectors-items (ex/projectors-items raw-state)
        virtual-projectors (ex/virtual-projectors raw-state)]
    (preview-cue-routing cue projectors-items virtual-projectors)))


;; Routing Validation


(defn validate-cue-routing
  "Validate that a cue will route to at least one output.
   
   Returns: {:valid? boolean :warnings [...] :errors [...]}"
  [cue projectors-items virtual-projectors]
  (let [outputs (pm/build-routing-map cue projectors-items virtual-projectors)
        crowd-outputs (filter #(contains? (:tags % #{}) :crowd-scanning) outputs)]
    {:valid? (seq outputs)
     :route-count (count outputs)
     :warnings (cond-> []
                 (empty? outputs)
                 (conj {:type :no-routes
                        :message "Cue will not route to any outputs"})
                 
                 (seq crowd-outputs)
                 (conj {:type :crowd-scanning
                        :message (str "Cue routes to " (count crowd-outputs) " crowd-scanning output(s)")}))
     :errors []}))


;; Zone Group Utilities


(defn get-outputs-for-zone-group
  "Get all outputs assigned to a zone group.
   
   Args:
   - zone-group-id: The zone group to query
   - projectors-items: Map of projector-id -> projector config
   - virtual-projectors: Map of vp-id -> virtual projector config
   
   Returns: Vector of output configs"
  [zone-group-id projectors-items virtual-projectors]
  (let [all-outputs (pm/build-all-outputs projectors-items virtual-projectors)]
    (pm/find-outputs-for-zone-group all-outputs zone-group-id)))


(defn get-outputs-for-zone-group-from-state
  "Get all outputs for a zone group using current state."
  [zone-group-id]
  (let [raw-state (state/get-raw-state)
        projectors-items (ex/projectors-items raw-state)
        virtual-projectors (ex/virtual-projectors raw-state)]
    (get-outputs-for-zone-group zone-group-id projectors-items virtual-projectors)))


;; Diagnostics


(defn routing-diagnostics
  "Generate diagnostics for routing to a zone group.
   Shows which outputs match and why."
  [zone-group-id projectors-items virtual-projectors]
  (pm/routing-diagnostics
    projectors-items
    virtual-projectors
    {:zone-groups [zone-group-id]}))


;; Debug Logging Control


(defn enable-routing-debug!
  "Enable debug logging for all routing modules.
   Use this from REPL to troubleshoot routing issues."
  []
  (ze/enable-routing-debug!)
  (pm/enable-routing-debug!)
  :routing-debug-enabled)

(defn disable-routing-debug!
  "Disable debug logging for all routing modules."
  []
  (ze/disable-routing-debug!)
  (pm/disable-routing-debug!)
  :routing-debug-disabled)
