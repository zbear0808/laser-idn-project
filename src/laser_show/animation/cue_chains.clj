(ns laser-show.animation.cue-chains
  "Cue chain management for combining multiple presets with effects.
   
   A cue chain is a sequential list of presets that render one after another
   when triggered. Each preset in the chain can:
   - Have its own parameters (radius, size, color, etc.)
   - Have its own effect chain
   - Be grouped with other presets
   - Be enabled/disabled independently
   
   Data Structure:
   {:items [{:type :preset
             :id (uuid)
             :preset-id :circle
             :params {:radius 0.5 :red 1.0 :green 1.0 :blue 1.0}
             :effects []
             :enabled? true}
            {:type :group
             :id (uuid)
             :name \"Wave Group\"
             :items [...]
             :effects []
             :enabled? true
             :collapsed? false}]}"
  (:require
   [laser-show.animation.chains :as chains]
   [laser-show.animation.presets :as presets]
   [laser-show.common.util :as u]))

(defn get-default-params
  "Get default parameter values for a preset."
  [preset-id]
  (when-let [preset-def (presets/presets-by-id preset-id)]
    (u/map-into :key :default (:parameters preset-def))))

(defn create-preset-instance
  "Create a preset instance with default params and empty effects.
   
   Parameters:
   - preset-id: Keyword identifying the preset (e.g., :circle, :wave)
   - opts: (optional) Map with :params, :effects, :enabled?
   
   Returns: Preset instance map
   
   Note: where a preset goes is its :target, else its group's, track's,
   cue's, column's or the show's (routing.targets, zones plan Z4); a new one
   has none, and inherits."
  ([preset-id] (create-preset-instance preset-id {}))
  ([preset-id {:keys [params effects enabled?]
               :or {params {} effects [] enabled? true}}]
   (let [default-params (get-default-params preset-id)
         merged-params (merge default-params params)]
     {:type :preset
      :id (random-uuid)
      :preset-id preset-id
      :params merged-params
      :effects effects
      :enabled? enabled?})))

(defn timeline-cue?
  "Check whether a cue-chain map is configured as a timeline cue.
   Returns true if :editor-type is explicitly :timeline,
   or if :editor-type is not :cue-chain and the cue has tracks or timeline items."
  [cue-chain]
  (boolean
   (and (map? cue-chain)
        (case (:editor-type cue-chain)
          :timeline true
          :cue-chain false
          ;; Default when :editor-type is not set:
          (or (seq (:tracks cue-chain))
              (some (fn [item]
                      (or (contains? item :timeline/start)
                          (contains? item :timeline/duration)
                          (contains? item :track-id)))
                    (:items cue-chain [])))))))

(defn grid-content
  "What the cue-grid cell holding cue (its cue-chain map, nil when there is
   none) holds: {:timeline? :preset-count :first-preset-id :has-content?}.
   Its presets are the :type :preset items at the top of :items and in
   groups' :items, in order, enabled or not; a preset's params and effects
   aren't looked in. It has content when it is a timeline cue
   (timeline-cue?) or holds a preset: a name, a mode or an empty group alone
   isn't a cue. The cell's label (subs/cell-display-data) and a press of the
   cell (handlers/grid.clj) both use it, so a press does what the cell holds
   when it is handled, not what it showed when it was clicked."
  [cue]
  (let [presets (filterv #(= :preset (:type %))
                         (tree-seq chains/group? #(:items % []) {:type :group :items (:items cue [])}))
        timeline? (timeline-cue? cue)]
    {:timeline? timeline?
     :preset-count (count presets)
     :first-preset-id (:preset-id (first presets))
     :has-content? (or timeline? (pos? (count presets)))}))
