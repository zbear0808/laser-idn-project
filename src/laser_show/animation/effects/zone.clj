(ns laser-show.animation.effects.zone
  "Zone selector effect logic.
   Allows an individual item to override its track or chain-level zone destination."
  (:require [laser-show.animation.effects :as effects]))

;; The zone-selector effect is unique because it doesn't modify points.
;; Instead, it acts as metadata that the routing system (zone-effects.clj)
;; looks for when determining where to send the item's rendered frames.
(defn- zone-selector-xf
  [params _ctx]
  ;; This effect is a no-op on the actual points
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
