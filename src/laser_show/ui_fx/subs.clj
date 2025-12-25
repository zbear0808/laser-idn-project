(ns laser-show.ui-fx.subs
  "Context-based subscriptions for cljfx UI.
   
   These subscription functions work with cljfx contexts to provide
   efficient, memoized access to UI state.
   
   Two types of subscriptions:
   - fx/sub-val: Simple value lookups (fast, always recalculated)
   - fx/sub-ctx: Computed subscriptions (memoized, dependency-tracked)
   
   Usage in components:
   (let [bpm (fx/sub-val context :timing :bpm)
         cell-data (fx/sub-ctx context subs/cell-display-data col row)]
     ...)"
  (:require [cljfx.api :as fx]
            [laser-show.animation.presets :as presets]))

;; ============================================================================
;; Grid Subscriptions
;; ============================================================================

(defn grid
  "Subscribe to the entire grid state.
   Returns: {:cells {} :selected-cell nil :size [cols rows]}"
  [context]
  (fx/sub-val context :grid))

(defn grid-cells
  "Subscribe to grid cells map.
   Returns: Map of [col row] -> {:preset-id keyword}"
  [context]
  (:cells (fx/sub-val context :grid)))

(defn grid-cell
  "Subscribe to a specific grid cell.
   Returns: {:preset-id keyword} or nil"
  [context col row]
  (get (:cells (fx/sub-val context :grid)) [col row]))

(defn grid-size
  "Subscribe to grid dimensions.
   Returns: [cols rows]"
  [context]
  (:size (fx/sub-val context :grid)))

(defn selected-cell
  "Subscribe to the currently selected cell.
   Returns: [col row] or nil"
  [context]
  (:selected-cell (fx/sub-val context :grid)))

(defn active-cell
  "Subscribe to the currently playing cell.
   Returns: [col row] or nil"
  [context]
  (:active-cell (fx/sub-val context :playback)))

(defn cell-display-data
  "Compute complete display data for a cell.
   This is a computed subscription - memoized based on dependencies.
   
   Returns: {:col col :row row :preset-id keyword :preset preset-map
             :active? bool :selected? bool :has-content? bool}"
  [context col row]
  (let [grid (fx/sub-val context :grid)
        playback (fx/sub-val context :playback)
        cell (get-in grid [:cells [col row]])
        ;; Handle case where preset-id might be incorrectly nested
        raw-preset-id (:preset-id cell)
        preset-id (cond
                    (keyword? raw-preset-id) raw-preset-id
                    (and (map? raw-preset-id) (:preset-id raw-preset-id)) (:preset-id raw-preset-id)
                    :else nil)
        preset (when preset-id (presets/get-preset preset-id))
        active (get playback :active-cell)
        selected (get grid :selected-cell)]
    {:col col
     :row row
     :preset-id preset-id
     :preset preset
     :preset-name (or (:name preset) (when (keyword? preset-id) (name preset-id)) "")
     :category (or (:category preset) :geometric)
     :active? (= [col row] active)
     :selected? (= [col row] selected)
     :has-content? (boolean preset-id)}))

;; ============================================================================
;; Playback Subscriptions
;; ============================================================================

(defn playback
  "Subscribe to the entire playback state.
   Returns: {:playing? bool :trigger-time ms :active-cell [col row]}"
  [context]
  (fx/sub-val context :playback))

(defn playing?
  "Subscribe to playback active state.
   Returns: true if playing"
  [context]
  (:playing? (fx/sub-val context :playback)))

(defn trigger-time
  "Subscribe to animation trigger time.
   Returns: timestamp in ms"
  [context]
  (:trigger-time (fx/sub-val context :playback)))

;; ============================================================================
;; Timing Subscriptions
;; ============================================================================

(defn timing
  "Subscribe to the entire timing state.
   Returns: {:bpm 120 :beat-position 0.0 ...}"
  [context]
  (fx/sub-val context :timing))

(defn bpm
  "Subscribe to BPM.
   Returns: BPM as double"
  [context]
  (:bpm (fx/sub-val context :timing)))

(defn beat-position
  "Subscribe to beat position (0.0 to 1.0).
   Returns: double"
  [context]
  (:beat-position (fx/sub-val context :timing)))

;; ============================================================================
;; Connection Subscriptions
;; ============================================================================

(defn connection
  "Subscribe to connection state.
   Returns: {:connected? bool :target string}"
  [context]
  (fx/sub-val context :connection))

(defn connected?
  "Subscribe to connection status.
   Returns: true if connected"
  [context]
  (:connected? (fx/sub-val context :connection)))

(defn connection-target
  "Subscribe to connection target.
   Returns: hostname/IP string or nil"
  [context]
  (:target (fx/sub-val context :connection)))

;; ============================================================================
;; Effects Subscriptions
;; ============================================================================

(defn effects
  "Subscribe to entire effects state.
   Returns: {:cells {}}"
  [context]
  (fx/sub-val context :effects))

(defn effects-cells
  "Subscribe to effects grid cells.
   Returns: Map of [col row] -> {:effects [...] :active bool}"
  [context]
  (:cells (fx/sub-val context :effects)))

(defn effect-cell
  "Subscribe to a specific effect cell.
   Returns: {:effects [...] :active bool} or nil"
  [context col row]
  (get (:cells (fx/sub-val context :effects)) [col row]))

(defn effect-cell-display-data
  "Compute display data for an effect cell.
   This is a computed subscription.
   
   Returns: {:col col :row row :effects [...] :effect-count int
             :active? bool :has-effects? bool}"
  [context col row]
  (let [effects-state (fx/sub-val context :effects)
        cell (get-in effects-state [:cells [col row]])
        effects-list (:effects cell)]
    {:col col
     :row row
     :effects effects-list
     :effect-count (count effects-list)
     :active? (boolean (:active cell))
     :has-effects? (boolean (seq effects-list))
     :first-effect-id (some-> effects-list first :effect-id)}))

(defn all-active-effect-instances
  "Compute all active effect instances from the effects grid.
   Returns: Vector of {:effect-id keyword :params map}"
  [context]
  (let [effects-state (fx/sub-val context :effects)
        cells (:cells effects-state)
        sorted-keys (sort-by (fn [[col row]] [row col]) (keys cells))]
    (into []
          (comp
           (map #(get cells %))
           (filter :active)
           (mapcat :effects)
           (map #(select-keys % [:effect-id :params])))
          sorted-keys)))

;; ============================================================================
;; UI Subscriptions
;; ============================================================================

(defn ui
  "Subscribe to UI state.
   Returns: {:selected-preset :clipboard :drag}"
  [context]
  (fx/sub-val context :ui))

(defn selected-preset
  "Subscribe to selected preset in UI.
   Returns: preset-id keyword or nil"
  [context]
  (:selected-preset (fx/sub-val context :ui)))

(defn clipboard
  "Subscribe to clipboard contents.
   Returns: clipboard data or nil"
  [context]
  (:clipboard (fx/sub-val context :ui)))

(defn dragging?
  "Subscribe to drag state.
   Returns: true if drag in progress"
  [context]
  (get-in (fx/sub-val context :ui) [:drag :active?]))

(defn drag-data
  "Subscribe to drag operation data.
   Returns: {:active? bool :source-type :source-id :source-key :data}"
  [context]
  (:drag (fx/sub-val context :ui)))

;; ============================================================================
;; Project Subscriptions
;; ============================================================================

(defn project
  "Subscribe to project state.
   Returns: {:current-folder path :dirty? bool}"
  [context]
  (fx/sub-val context :project))

(defn project-folder
  "Subscribe to project folder path.
   Returns: path string or nil"
  [context]
  (:current-folder (fx/sub-val context :project)))

(defn project-dirty?
  "Subscribe to project dirty state.
   Returns: true if unsaved changes"
  [context]
  (:dirty? (fx/sub-val context :project)))

(defn has-project?
  "Subscribe to whether a project is open.
   Returns: true if project folder is set"
  [context]
  (some? (:current-folder (fx/sub-val context :project))))

;; ============================================================================
;; Preset Subscriptions (Static - not from context)
;; ============================================================================

(defn all-presets
  "Get all available presets.
   Returns: Vector of preset maps.
   Note: This is static data, not from context."
  []
  presets/all-presets)

(defn presets-by-category
  "Get presets grouped by category.
   Returns: Map of category-keyword -> [preset ...]
   Note: This is static data, not from context."
  []
  (group-by :category presets/all-presets))

(defn preset-categories
  "Get all preset category definitions.
   Returns: Map of category-keyword -> {:name string :color [r g b]}
   Note: This is static data, not from context."
  []
  presets/categories)
