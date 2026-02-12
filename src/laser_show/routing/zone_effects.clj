(ns laser-show.routing.zone-effects
  "Zone effect processing - determines routing targets per item.
   
   Zone effects are evaluated at render time. The zone-selector effect
   specifies a static zone group destination.
   
   Main Entry Point: group-items-by-zone - groups cue chain items by destination zone"
  (:require [clojure.tools.logging :as log]
            [laser-show.animation.effects.zone :as zone]
            [laser-show.common.util :as u]))


;; Debug Logging (one-shot per playback session to avoid per-frame spam)
;;
;; Each log point has its own key, so multiple log points can each fire once.

(def ^:private debug-enabled? (atom false))
(def ^:private !debug-logged-keys (atom #{}))  ;; Set of keys that have already logged

(defn enable-routing-debug! [] (reset! debug-enabled? true))
(defn disable-routing-debug! [] (reset! debug-enabled? false))

(defn reset-zone-debug!
  "Call this when starting playback to enable one debug log cycle.
   Clears all logged keys so each log point can fire once."
  []
  (reset! !debug-logged-keys #{}))

(defn log-once
  "Log a message only once per playback session for the given key.
   Each unique key can log once until reset-zone-debug! is called.
   Public so it can be used from frame-service for consistent debug logging."
  [log-key msg]
  (when (and @debug-enabled?
             (not (contains? @!debug-logged-keys log-key)))
    (swap! !debug-logged-keys conj log-key)
    (log/info msg)))


;; Zone Effect Identification


(def zone-effect-ids
  "Set of effect IDs that are zone routing effects"
  #{:zone-selector})

(defn zone-effect?
  "Check if an effect is a zone routing effect."
  [effect]
  (contains? zone-effect-ids (:effect-id effect)))

(defn extract-zone-effects
  "Extract enabled zone effects from an effect chain.
   Returns seq of zone effects in order."
  [effects]
  (->> effects
       (filter zone-effect?)
       (filter #(:enabled? % true))
       vec))


;; Per-Item Zone Resolution


(defn- item-enabled?
  "Check if an item is enabled. Defaults to true if :enabled? is not present."
  [item]
  (:enabled? item true))

(defn- item-zone-effects
  "Extract enabled zone effects from an item's :effects vector.
   Returns vector of zone effects (may be empty)."
  [item]
  (extract-zone-effects (:effects item [])))

(defn resolve-item-zone-destination
  "Determine which zone group an item should route to.
   
   Finds the first zone-selector effect on the item and reads its
   :target-zone parameter. If no zone-selector effect exists,
   returns the cue chain's default destination.
   
   Args:
   - item: A cue chain item with :effects vector
   - cue-chain-destination: The cue chain's :destination-zone map
   - timing-ctx: Timing context (kept for API compatibility)
   
   Returns: Single zone-group-id keyword"
  [item cue-chain-destination timing-ctx]
  (let [zone-effects (item-zone-effects item)
        default-zone (or (:zone-group-id cue-chain-destination) :all)
        zone-selector (u/seek #(= :zone-selector (:effect-id %)) zone-effects)
        result (if zone-selector
                 (zone/evaluate-zone (:params zone-selector))
                 default-zone)]
    (log-once [:resolve-item (:id item)]
              (format "[zone-debug] resolve-item: id=%s result=%s"
                      (:id item) result))
    result))

(defn group-items-by-zone
  "Group top-level items by their resolved zone destination.
   
   For each enabled item, resolves its zone destination at the current
   beat position. Each item routes to exactly ONE zone.
   
   Args:
   - items: Vector of top-level cue chain items
   - cue-chain-destination: The cue chain's :destination-zone map
   - timing-ctx: Timing context for keyframe evaluation
   
   Returns: Map of zone-group-id → vector of items"
  [items cue-chain-destination timing-ctx]
  (log-once :group-items-inputs
            (format "[zone-debug] group-items: destination=%s item-count=%d"
                    (pr-str cue-chain-destination) (count items)))
  (let [result (reduce
                 (fn [acc item]
                   (if-not (item-enabled? item)
                     acc
                     (let [zone-id (resolve-item-zone-destination 
                                    item cue-chain-destination timing-ctx)]
                       (update acc zone-id (fnil conj []) item))))
                 {}
                 items)]
    (log-once :group-items-result
              (format "[zone-debug] group-items result: %s"
                      (pr-str (into {} (map (fn [[k v]] [k (count v)]) result)))))
    result))
