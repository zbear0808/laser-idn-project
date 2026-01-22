(ns laser-show.animation.effects.zone
  "Zone group routing effects - modify where cues are sent.
   
   Unlike regular effects that transform frame data (points, colors),
   zone routing effects modify ROUTING before frame generation occurs.
   
   These effects appear in the cue-chain editor under the 'Zone' tab
   and allow dynamic routing based on effect parameters.
   
   SIMPLIFIED ARCHITECTURE (v2):
   Only zone groups exist now - zones have been eliminated.
   Projectors are assigned directly to zone groups.
   
   Modes:
   - :replace - Completely override the cue's destination zone group
   - :add     - Add zone groups to the cue's destination (union)
   - :filter  - Restrict to zone groups matching both original AND effect target"
  (:require [laser-show.animation.effects :as effects]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;; Zone Reroute Effect
;;
;; This effect stores routing parameters that are read by the routing layer.
;; The transducer is identity because zone effects don't transform frame data.


(defn- zone-reroute-xf
  "Identity transducer - zone routing effects don't modify frame data.
   The routing parameters are read by routing/core.clj."
  [_time-ms _bpm _params _ctx]
  (map identity))

(effects/register-effect!
 {:id :zone-reroute
  :name "Zone Group Reroute"
  :category :zone
  :timing :static
  :parameters [{:key :mode
                :label "Mode"
                :type :choice
                :default :replace
                :choices [:replace :add :filter]}
               {:key :target-zone-groups
                :label "Target Zone Groups"
                :type :zone-groups
                :default [:all]}]
  :ui-hints {:renderer :zone-reroute
             :params [:mode :target-zone-groups]
             :show-routing-preview? true}
  :apply-transducer zone-reroute-xf})


;; Zone Broadcast Effect
;;
;; Convenience effect to send to all projectors.


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
  :ui-hints {:info "Sends this cue to ALL projectors. Equivalent to zone-reroute with mode :replace and target :all."}
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
