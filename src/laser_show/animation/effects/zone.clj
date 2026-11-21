(ns laser-show.animation.effects.zone
  "The Zone Selector, as old shows have it. Where content goes is its
   Target since zones plan Z4 (routing.targets): loading or pasting an old
   cue turns its first enabled top-level selector into the item's :target
   and takes every selector out (state.migration). The id stays registered,
   hidden, so the bank offers it nowhere and an old effect still reads; no
   routing reads it."
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
