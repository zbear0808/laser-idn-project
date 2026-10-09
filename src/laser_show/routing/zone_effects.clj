(ns laser-show.routing.zone-effects
  "Zone effect processing - determines routing targets per item.
   
   Zone routing priority (highest to lowest):
   1. Item's explicit :zone-group-id or active :zone-selector effect
   2. Item's assigned track's effective zone (with folder inheritance)
   3. Inherited zone from parent group (if inside a group)
   4. Fallback to default destination (from cue chain, fallback :all)
   
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

(defn- index-tracks
  "Recursively index tracks into:
   - :id->track map of id to track
   - :id->parent map of child id to parent id"
  [tracks]
  (let [id->track (transient {})
        id->parent (transient {})]
    (letfn [(traverse [parent-id items]
              (doseq [t items
                      :when (map? t)]
                (let [tid (:id t)
                      direct-parent (or parent-id
                                        (:parent-id t)
                                        (last (:path t)))]
                  (when tid
                    (assoc! id->track tid t)
                    (when direct-parent
                      (assoc! id->parent tid direct-parent)))
                  (when (seq (:items t))
                    (traverse tid (:items t))))))]
      (traverse nil tracks)
      {:id->track (persistent! id->track)
       :id->parent (persistent! id->parent)})))

(defn resolve-track-effective-zone
  "Resolve the effective zone for a track by checking the track and
   traversing up through parent track folders if not explicitly set.
   Falls back to default-zone-id if no zone is found."
  ([tracks track-id]
   (resolve-track-effective-zone tracks track-id nil))
  ([tracks track-id default-zone-id]
   (cond
     (string? tracks)
     (resolve-track-effective-zone track-id tracks default-zone-id)

     (or (nil? track-id) (empty? tracks))
     default-zone-id

     :else
     (let [{:keys [id->track id->parent]} (index-tracks tracks)]
       (loop [curr-id track-id
              visited #{}]
         (if (or (nil? curr-id) (contains? visited curr-id))
           default-zone-id
           (if-let [t (get id->track curr-id)]
             (or (:zone-group-id t)
                 (recur (get id->parent curr-id) (conj visited curr-id)))
             default-zone-id)))))))

(defn resolve-item-zone-destination
  "Determine which zone group an item should route to.
   
   Routing priority (highest to lowest):
   1. Item's explicit :zone-group-id or active :zone-selector effect
   2. Item's assigned track's effective zone (with folder inheritance)
   3. Inherited zone from parent group (if inside a group)
   4. Fallback to default destination (from cue chain, fallback :all)
   
   Args:
   - item: A cue chain item or group
   - timing-ctx: Timing context for keyframe evaluation
   - opts: Map with :tracks, :default-zone-id, and optional :inherited-zone
   
   Returns: Single zone-group-id keyword"
  [item timing-ctx {:keys [tracks default-zone-id inherited-zone]}]
  (let [explicit-zone (or (:zone-group-id item)
                          (when-let [fx (first (item-zone-effects item))]
                            (get-in fx [:params :target-zone])))
        track-zone (when-let [tid (:track-id item)]
                     (resolve-track-effective-zone tracks tid nil))
        result (or explicit-zone
                   track-zone
                   inherited-zone
                   default-zone-id
                   :all)]
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
