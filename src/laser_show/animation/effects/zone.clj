(ns laser-show.animation.effects.zone
  "The zone category: effects that decide where content goes, never what a
   point is.

   - The Zone Selector, as old shows have it. Where content goes is its
     Target since zones plan Z4 (routing.targets): loading or pasting an old
     cue turns its first enabled top-level selector into the item's :target
     and takes every selector out (state.migration). The id stays
     registered, hidden, so the bank offers it nowhere and an old effect
     still reads; no routing reads it.
   - Zone pattern and Map cycle (zones plan Z5, 07 §4.3), offered on the
     effects grid (:scope #{:global}): a pattern moves a cue's pictures
     between places on the beat or on a press (routing.zone-remap, its
     :apply-zones; a cue keeps its own in :zone-patterns), and a Map cycle
     picks the zone map (routing.zone-patterns/cycle-pick, its :select-map,
     which the show clock asks). As point effects they are identity: the
     render runs the stage before the point effects, and no transducer reads
     their parameters (routing.zone-patterns has them). A zone pattern can be
     a step grid (zones plan Z6): its style Steps, its :steps the refs each
     step lights."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.routing.zone-remap :as zone-remap]))

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
  "Identity transducer: a selector never changed frame data, and nothing
   reads its :target-zone any more."
  [_time-ms _bpm _params _ctx]
  (map identity))

(effects/register-effect!
 {:id :zone-selector
  :name "Zone Selector"
  :category :zone
  :hidden? true
  :timing :bpm
  :parameters [{:key :target-zone
                :label "Target Zone"
                :type :zone-group-id
                :default :all}]
  :ui-hints {:renderer :zone-selector}
  :apply-transducer zone-selector-xf})


;; Zone pattern and Map cycle (zones plan Z5)


(defn- zone-stage-xf
  "Identity transducer: a zone-stage effect moves frames between places, or
   picks the map, and never touches a point."
  [_time-ms _bpm _params _ctx]
  (map identity))

(effects/register-effect!
 {:id :zone-pattern
  :name "Zone pattern"
  :category :zone
  :timing :bpm
  :scope #{:global}
  :parameters zone-patterns/pattern-parameters
  :ui-hints {:renderer :zone-pattern}
  :apply-zones zone-remap/apply-pattern
  :apply-transducer zone-stage-xf})

(effects/register-effect!
 {:id :zone-map-cycle
  :name "Map cycle"
  :category :zone
  :timing :bpm
  :scope #{:global}
  :parameters zone-patterns/cycle-parameters
  :ui-hints {:renderer :map-cycle}
  :select-map zone-patterns/cycle-pick
  :apply-transducer zone-stage-xf})
