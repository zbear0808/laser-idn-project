(ns laser-show.animation.effects.zone
  "Zone routing effects - modify where cues are sent.
   
   Unlike regular effects that transform frame data (points, colors),
   zone effects modify ROUTING before frame generation occurs.
   
   Zone effects are processed by routing/zone_effects.clj during the
   routing phase, not during frame generation. The effect transducer
   here is an identity function since no frame transformation occurs.
   
   These effects appear in the cue-chain editor under the 'Zone' tab
   and allow dynamic routing based on effect parameters.
   
   Modes:
   - :replace - Completely override the cue's destination zone
   - :add     - Add zones to the cue's destination (union)
   - :filter  - Restrict to zones matching both original AND effect target"
  (:require [laser-show.animation.effects :as effects]))


;; Zone Reroute Effect
;;
;; This effect stores routing parameters that are read by the routing layer.
;; The transducer is identity because zone effects don't transform frame data.


(defn- zone-reroute-xf
  "Identity transducer - zone effects don't modify frame data.
   The routing parameters are read by routing/zone_effects.clj."
  [_time-ms _bpm _params _ctx]
  (map identity))

(effects/register-effect!
 {:id :zone-reroute
  :name "Zone Reroute"
  :category :zone
  :timing :static
  :parameters [{:key :mode
                :label "Mode"
                :type :choice
                :default :replace
                :choices [:replace :add :filter]}
               {:key :target-mode
                :label "Target Mode"
                :type :choice
                :default :zone-groups
                :choices [:zone-groups :zones]}
               {:key :target-zone-groups
                :label "Target Zone Groups"
                :type :zone-groups
                :default [:all]}
               {:key :target-zones
                :label "Target Zones"
                :type :zones
                :default []}]
  :ui-hints {:renderer :zone-reroute
             :params [:mode :target-mode :target-zone-groups :target-zones]
             :show-routing-preview? true}
  :apply-transducer zone-reroute-xf})


;; Zone Broadcast Effect
;;
;; Convenience effect to send to all zones.


(defn- zone-broadcast-xf
  "Identity transducer - just marks this cue as broadcast."
  [_time-ms _bpm _params _ctx]
  (map identity))

(effects/register-effect!
 {:id :zone-broadcast
  :name "Broadcast to All"
  :category :zone
  :timing :static
  :parameters []
  :ui-hints {:info "Sends this cue to ALL zones. Equivalent to zone-reroute with mode :replace and target :all."}
  :apply-transducer zone-broadcast-xf})


;; Zone Mirror Effect
;;
;; Send to opposite side (left <-> right).


(defn- zone-mirror-xf
  "Identity transducer - routing logic handles the mirror mapping."
  [_time-ms _bpm _params _ctx]
  (map identity))

(effects/register-effect!
 {:id :zone-mirror
  :name "Mirror Left/Right"
  :category :zone
  :timing :static
  :parameters [{:key :source-group
                :label "Source Group"
                :type :choice
                :default :left
                :choices [:left :right]}
               {:key :include-original?
                :label "Include Original"
                :type :bool
                :default false}]
  :ui-hints {:info "Sends to the opposite side. :left becomes :right and vice versa."}
  :apply-transducer zone-mirror-xf})
