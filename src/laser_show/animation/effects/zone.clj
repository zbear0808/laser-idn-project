(ns laser-show.animation.effects.zone
  "Zone group routing effects - modify where cues are sent.
   
   Zone effects operate at the routing level, not frame generation level.
   The zone-selector effect allows keyframeable zone group selection,
   evaluated at render time based on the current beat position.
   
   SIMPLIFIED ARCHITECTURE:
   Only zone groups exist - projectors are assigned directly to zone groups.
   A single zone-selector effect replaces the old zone-reroute, zone-broadcast,
   and zone-mirror effects with unified keyframe support."
  (:require [laser-show.animation.effects :as effects]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;; Zone Selection Helpers


(defn evaluate-zone-at-beat
  "Evaluate which zone group is active at the given beat position.
   
   Uses step interpolation - returns the value of the last keyframe
   at or before the current beat position.
   
   Parameters:
   - params: Effect params containing :target-zone and optional :keyframes
   - effective-beats: Current beat position (from timing-ctx)
   
   Returns: Zone group keyword (e.g., :left, :right, :all, :center)"
  [params effective-beats]
  (let [keyframes (get-in params [:target-zone :keyframes])
        base-value (if (map? (:target-zone params))
                     (get-in params [:target-zone :value] :all)
                     (:target-zone params :all))]
    (if (or (nil? keyframes) (empty? keyframes))
      base-value
      ;; Step interpolation: find last keyframe <= current beat
      (let [sorted (sort-by :beat keyframes)
            active (->> sorted
                        (filter #(<= (:beat %) effective-beats))
                        last)]
        (or (:value active) base-value)))))


;; Zone Selector Effect


(defn- zone-selector-xf
  "Identity transducer - zone selection affects routing, not frame data.
   
   The actual zone resolution happens in the routing layer (zone_effects.clj)
   which reads effect parameters directly and evaluates keyframes at the
   current beat position."
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
                :default :all
                :modulatable? true
                :interpolation :step}]
  :ui-hints {:renderer :zone-selector
             :keyframe-editor :step-selector}
  :apply-transducer zone-selector-xf})
