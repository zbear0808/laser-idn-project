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
            [laser-show.routing.zone-effects :as ze]))

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
