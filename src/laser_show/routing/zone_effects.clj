(ns laser-show.routing.zone-effects
  "Zone effect processing - modifies routing targets before zone matching.
   
   Zone effects are special effects that operate at the routing level,
   not the frame generation level. They are processed BEFORE zone matching
   to determine the final target specification for a cue.
   
   Zone effects use the :zone-reroute type and have modes:
   - :replace - Completely override the cue's destination with new targets
   - :add - Union the effect's targets with the cue's destination
   - :filter - Intersect to restrict routing to zones matching both
   
   This namespace is called by routing/core before zone matching."
  (:require [laser-show.common.util :as u]))


;; Zone Effect Extraction


(defn extract-zone-effects
  "Extract zone effects from a cue's effect chain.
   Zone effects have :type :zone-reroute.
   Returns vector of zone effects in order (preserving chain order)."
  [effects]
  (filterv #(= :zone-reroute (:type %)) effects))


;; Target Format Conversion


(defn destination-zone->target
  "Convert cue's :destination-zone to internal target format for routing.
   
   Input formats (cue's :destination-zone):
   {:mode :zone-group, :zone-group-id :left}
   {:mode :zone, :zone-id #uuid \"...\"}
   
   Output format (internal target):
   {:mode :zone-groups
    :zone-groups [:left]
    :zone-ids []
    :preferred-zone-type :default}"
  [destination-zone]
  (let [mode (or (:mode destination-zone) :zone-group)]
    (case mode
      :zone-group {:mode :zone-groups
                   :zone-groups [(or (:zone-group-id destination-zone) :all)]
                   :zone-ids []
                   :preferred-zone-type (or (:preferred-zone-type destination-zone) :default)}
      :zone {:mode :zones
             :zone-groups []
             :zone-ids [(or (:zone-id destination-zone) nil)]
             :preferred-zone-type (or (:preferred-zone-type destination-zone) :default)}
      ;; Default: route to all
      {:mode :zone-groups
       :zone-groups [:all]
       :zone-ids []
       :preferred-zone-type :default})))


;; Zone Effect Mode Implementations


(defn apply-replace-mode
  "Replace mode: completely override target with effect's target.
   The effect's params determine the new routing destination."
  [_current-target effect-params]
  (let [target-mode (or (:target-mode effect-params) :zone-groups)]
    {:mode target-mode
     :zone-groups (or (:target-zone-groups effect-params) [])
     :zone-ids (or (:target-zones effect-params) [])
     :preferred-zone-type (or (:preferred-zone-type effect-params) :default)}))

(defn apply-add-mode
  "Add mode: union current target with effect's target.
   Results in routing to all zones that match either target."
  [current-target effect-params]
  (-> current-target
      (update :zone-groups (fn [groups]
                             (vec (distinct (concat groups
                                                    (or (:target-zone-groups effect-params) []))))))
      (update :zone-ids (fn [ids]
                          (vec (distinct (concat ids
                                                 (or (:target-zones effect-params) []))))))))

(defn apply-filter-mode
  "Filter mode: intersect current target with effect's target.
   
   This is more complex because we need to resolve which zones match
   both criteria. For now, we implement a simplified version that
   just intersects the zone-groups lists.
   
   Full implementation will need access to zones-db to properly
   resolve the intersection at the zone level."
  [current-target effect-params _zones-db]
  (let [filter-groups (set (or (:target-zone-groups effect-params) []))
        filter-ids (set (or (:target-zones effect-params) []))]
    (cond-> current-target
      ;; If filtering by zone groups, keep only those in both
      (seq filter-groups)
      (update :zone-groups (fn [groups]
                             (vec (filter filter-groups groups))))
      ;; If filtering by specific zones, keep only those in both
      (seq filter-ids)
      (update :zone-ids (fn [ids]
                          (vec (filter filter-ids ids)))))))


;; Zone Effect Application


(defn apply-zone-effect
  "Apply a single zone effect to current target specification.
   Returns updated target specification.
   
   Disabled effects are skipped (return current target unchanged)."
  [current-target zone-effect zones-db]
  (if-not (:enabled? zone-effect true)
    current-target
    (let [params (:params zone-effect {})
          mode (or (:mode params) :replace)]
      (case mode
        :replace (apply-replace-mode current-target params)
        :add (apply-add-mode current-target params)
        :filter (apply-filter-mode current-target params zones-db)
        ;; Unknown mode, return unchanged
        current-target))))


;; Main Entry Point


(defn resolve-final-target
  "Process all zone effects and produce final target specification.
   
   This is the main entry point for zone effect processing.
   Called by routing/core before zone matching.
   
   Converts cue's :destination-zone to internal target format,
   then applies any zone effects to produce final routing target.
   
   Args:
   - cue: The cue map with :destination-zone and :effects
   - zones-db: Map of zone-id -> zone for resolving filter mode
   
   Returns: Final target specification
   {:mode :zone-groups or :zones
    :zone-groups [...]
    :zone-ids [...]
    :preferred-zone-type :default/:graphics/:crowd-scanning}"
  [cue zones-db]
  (let [;; Get base target from cue's destination-zone
        ;; If not specified, default to {:mode :zone-group :zone-group-id :all}
        destination-zone (or (:destination-zone cue)
                             {:mode :zone-group :zone-group-id :all})
        base-target (destination-zone->target destination-zone)
        ;; Extract zone effects from cue's effect chain
        zone-effects (extract-zone-effects (or (:effects cue) []))]
    ;; Apply each zone effect in order
    (reduce
      (fn [target effect]
        (apply-zone-effect target effect zones-db))
      base-target
      zone-effects)))


;; Utility Functions


(defn has-zone-effects?
  "Check if a cue has any zone effects in its effect chain."
  [cue]
  (boolean (seq (extract-zone-effects (or (:effects cue) [])))))

(defn preview-final-target
  "Preview what the final target would be for a cue.
   Useful for UI to show routing preview before triggering."
  [cue zones-db]
  (resolve-final-target cue zones-db))
