(ns laser-show.routing.zone-effects
  "Zone effect processing - modifies routing targets before projector matching.
   
   Zone effects are special effects that operate at the routing level,
   not the frame generation level. They are extracted from effect chains
   and processed BEFORE projector matching to determine the final target.
   
   The zone routing effects (:zone-reroute, :zone-broadcast, :zone-mirror)
   are registered in laser-show.animation.effects.zone but their routing
   parameters are processed HERE.
   
   Zone Effect Modes:
   - :replace - Completely override the cue's destination zone group
   - :add     - Add zone groups to the cue's destination (union)
   - :filter  - Restrict to zone groups matching both original AND effect target"
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]))


;; Debug Logging (throttled by caller in multi_engine)

(def ^:private debug-enabled? (atom false))

(defn enable-routing-debug! [] (reset! debug-enabled? true))
(defn disable-routing-debug! [] (reset! debug-enabled? false))


;; Zone Effect Identification


(def zone-effect-ids
  "Set of effect IDs that are zone routing effects"
  #{:zone-reroute :zone-broadcast :zone-mirror})

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


;; Zone Effect Application Functions


(defn apply-replace-mode
  "Replace mode: completely override target with effect's target."
  [_current-target params]
  (set (:target-zone-groups params [:all])))

(defn apply-add-mode
  "Add mode: union current target with effect's target."
  [current-target params]
  (into current-target (:target-zone-groups params [])))

(defn apply-filter-mode
  "Filter mode: intersect current target with effect's target."
  [current-target params]
  (set/intersection 
    current-target 
    (set (:target-zone-groups params []))))

(defn apply-broadcast
  "Broadcast effect: replace with [:all]"
  [_current-target _params]
  #{:all})

(defn apply-mirror
  "Mirror effect: swap left<->right based on source-group."
  [current-target params]
  (let [{:keys [source-group include-original?]} params
        mirror-map {:left :right, :right :left}
        mirrored (if (contains? current-target source-group)
                   (conj (disj current-target source-group)
                         (get mirror-map source-group source-group))
                   current-target)]
    (if include-original?
      (into current-target mirrored)
      mirrored)))

(defn apply-zone-effect
  "Apply a single zone effect to current target set.
   Returns updated target set."
  [current-target effect]
  (let [params (:params effect)
        effect-id (:effect-id effect)]
    (case effect-id
      :zone-reroute
      (case (:mode params :replace)
        :replace (apply-replace-mode current-target params)
        :add (apply-add-mode current-target params)
        :filter (apply-filter-mode current-target params)
        current-target)
      
      :zone-broadcast
      (apply-broadcast current-target params)
      
      :zone-mirror
      (apply-mirror current-target params)
      
      ;; Unknown effect, pass through
      current-target)))

(defn resolve-final-target
  "Process all zone effects and produce final target zone groups.
   
   Args:
   - base-destination: The cue chain's :destination-zone map (e.g., {:zone-group-id :left})
   - effects: Vector of effects (may include zone effects)
   
   Returns: Set of zone group IDs that should receive the cue"
  [base-destination effects]
  (let [;; Start with base destination zone group(s)
        base-groups (if-let [zg-id (:zone-group-id base-destination)]
                      #{zg-id}
                      #{:all})
        ;; Extract and apply zone effects
        zone-effects (extract-zone-effects effects)
        final-groups (reduce apply-zone-effect base-groups zone-effects)]
    ;; Debug logging when enabled
    (when @debug-enabled?
      (log/debug (format "resolve-final-target: base-dest=%s -> base-groups=%s, zone-effects=%d -> final=%s"
                         (pr-str base-destination)
                         (pr-str base-groups)
                         (count zone-effects)
                         (pr-str final-groups))))
    final-groups))


;; Effect Collection from Cue Chains


(defn collect-effects-from-cue-chain
  "Collect all effects from a cue chain's items (presets and groups).
   Flattens nested groups to get ALL effects in the chain."
  [cue-chain-items]
  (->> cue-chain-items
       (filter #(:enabled? % true))
       (mapcat (fn [item]
                 (if (= :group (:type item))
                   ;; Recursively get effects from group items
                   (into (:effects item [])
                         (collect-effects-from-cue-chain (:items item [])))
                   (:effects item []))))
       vec))
