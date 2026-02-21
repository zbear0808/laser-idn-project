(ns laser-show.routing.zone-effects
  "Zone effect processing - determines routing targets per item.
   
   Zone routing priority (highest to lowest):
   1. Item's :track-id → track's :zone-group-id (when tracks are defined)
   2. Item's :zone-selector effect
   3. Cue chain's :destination-zone default
   
   Main Entry Point: group-items-by-zone - groups cue chain items by destination zone"
  (:require [clojure.tools.logging :as log]
            [laser-show.animation.chains :as chains]
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




(def ^:private zone-effect-ids #{:zone-selector})

(defn zone-effect?
  "Check if an effect definition is a zone routing effect.
   Returns true if the effect's ID is one of the known zone effects."
  [effect]
  (contains? zone-effect-ids (:effect-id effect)))

(defn extract-zone-effects
  "Given a list of effect definitions, return a sequence of active zone effects."
  [effects]
  (filter #(and (zone-effect? %)
                (chains/item-enabled? %))
          effects))

(defn item-zone-effects
  "Given a cue item, return a sequence of its active zone effects."
  [item]
  (extract-zone-effects (:effects item)))

(defn resolve-item-zone-destination
  "Determine which zone group an item should route to.
   
   Routing priority (highest to lowest):
   1. Item's active :zone-selector effect(s) -> uses the first one found
   2. Item's assigned track's :zone-group-id -> when :track-id is present
   3. Fallback to default destination -> from cue chain
   
   Args:
   - item: A cue chain item
   - timing-ctx: Timing context for keyframe evaluation
   - opts: Map with :tracks (track definitions), and :default-zone-id
   
   Returns: Single zone-group-id keyword"
  [item timing-ctx {:keys [tracks default-zone-id]}]
  (let [zone-effects (item-zone-effects item)
        first-zone-fx (first zone-effects)
        result (if first-zone-fx
                 ;; 1. Item-level override
                 (:target-zone (:params first-zone-fx))
                 ;; 2. Track definition
                 (if-let [tid (:track-id item)]
                   (if-let [track (u/seek #(= (:id %) tid) tracks)]
                     (:zone-group-id track)
                     default-zone-id)
                   ;; 3. Fallback
                   default-zone-id))]
    (log-once [:resolve-item (:id item)]
              (format "[zone-debug] resolve-item: id=%s result=%s"
                      (:id item) result))
    result))

(defn group-items-by-zone
  "Group top-level items by their assigned destination zone.
   
   Items without a valid track assignment will fallback to the chain default.
   
   Args:
   - items: Vector of top-level cue chain items
   - timing-ctx: Timing context for keyframe evaluation
   - opts: Map with :tracks and :default-zone-id
   
   Returns: Map of zone-group-id → vector of items"
  [items timing-ctx opts]
  (log-once :group-items-inputs
            (format "[zone-debug] group-items: item-count=%d tracks=%d"
                    (count items)
                    (count (:tracks opts))))
  (let [result (reduce
                (fn [acc item]
                  (if-not (chains/item-enabled? item)
                    acc
                    (let [zone-id (resolve-item-zone-destination item timing-ctx opts)]
                      (if zone-id
                        (update acc zone-id (fnil conj []) item)
                        acc))))
                {}
                items)]
    (log-once :group-items-result
              (format "[zone-debug] group-items result: %s"
                      (pr-str (into {} (map (fn [[k v]] [k (count v)]) result)))))
    result))
