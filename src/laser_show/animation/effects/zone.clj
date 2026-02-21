(ns laser-show.animation.effects.zone
  "Zone group routing effects - modify where cues are sent.
   
   Zone effects operate at the routing level, not frame generation level.
   The zone-selector effect allows selecting which zone group a cue chain
   item routes to.
   
   SIMPLIFIED ARCHITECTURE:
   Only zone groups exist - projectors are assigned directly to zone groups.
   A single zone-selector effect with a simple :target-zone parameter
   determines routing destination."
  (:require [laser-show.animation.effects :as effects]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;; Zone Selection


(defn evaluate-zone
  "Get which zone group is selected from effect params.
   
   Parameters:
   - params: Effect params containing :target-zone keyword
   
   Returns: Zone group keyword (e.g., :left, :right, :all, :center)"
  [params]
  (:target-zone params :all))


;; Zone Selector Effect


(defn- zone-selector-xf
  "Identity transducer - zone selection affects routing, not frame data.
   
   The actual zone resolution happens in the routing layer (zone_effects.clj)
   which reads the :target-zone parameter directly."
  [_time-ms _bpm _params _ctx]
  (map identity))

(effects/register-effect!
 {:id :zone-selector
  :name "Zone Selector"
  :category :zone
  :timing :bpm
  :parameters [{:key :target-zone
                :label "Target Zone"
                :type :zone-group-id
                :default :all}]
  :ui-hints {:renderer :zone-selector}
  :apply-transducer zone-selector-xf})
