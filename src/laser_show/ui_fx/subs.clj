(ns laser-show.ui-fx.subs
  "Subscriptions for cljfx - derived state from existing atoms.
   These read from the state atoms and compute UI-relevant data."
  (:require [laser-show.state.atoms :as state]
            [laser-show.animation.presets :as presets]))

;; ============================================================================
;; Grid Subscriptions
;; ============================================================================

(defn grid-cells
  "Get all grid cells.
   Returns: Map of [col row] -> {:preset-id keyword}"
  []
  (state/get-grid-cells))

(defn grid-cell
  "Get a single grid cell.
   Returns: {:preset-id keyword} or nil"
  [col row]
  (state/get-cell col row))

(defn grid-size
  "Get grid dimensions.
   Returns: [cols rows]"
  []
  (state/get-grid-size))

(defn selected-cell
  "Get the currently selected cell.
   Returns: [col row] or nil"
  []
  (state/get-selected-cell))

(defn active-cell
  "Get the currently playing cell.
   Returns: [col row] or nil"
  []
  (state/get-active-cell))

(defn cell-display-data
  "Get complete display data for a cell.
   Returns: {:col col :row row :preset-id keyword :preset preset-map
             :active? bool :selected? bool :has-content? bool}"
  [col row]
  (let [cell (state/get-cell col row)
        preset-id (:preset-id cell)
        preset (when preset-id (presets/get-preset preset-id))
        active (state/get-active-cell)
        selected (state/get-selected-cell)]
    {:col col
     :row row
     :preset-id preset-id
     :preset preset
     :preset-name (or (:name preset) (when preset-id (name preset-id)) "")
     :category (or (:category preset) :geometric)
     :active? (= [col row] active)
     :selected? (= [col row] selected)
     :has-content? (boolean preset-id)}))

(defn all-cells-display-data
  "Get display data for all cells in the grid.
   Returns: Vector of cell display data maps in row-major order."
  []
  (let [[cols rows] (state/get-grid-size)]
    (for [row (range rows)
          col (range cols)]
      (cell-display-data col row))))

;; ============================================================================
;; Playback Subscriptions
;; ============================================================================

(defn playing?
  "Check if playback is active."
  []
  (state/playing?))

(defn trigger-time
  "Get the trigger time for the current animation."
  []
  (state/get-trigger-time))

;; ============================================================================
;; Timing Subscriptions
;; ============================================================================

(defn bpm
  "Get the current BPM."
  []
  (state/get-bpm))

(defn beat-position
  "Get the current beat position (0.0 to 1.0)."
  []
  (state/get-beat-position))

;; ============================================================================
;; IDN/Connection Subscriptions
;; ============================================================================

(defn connected?
  "Check if connected to IDN target."
  []
  (state/idn-connected?))

(defn connection-target
  "Get the current connection target."
  []
  (state/get-idn-target))

(defn connection-status
  "Get complete connection status.
   Returns: {:connected? bool :target string}"
  []
  {:connected? (state/idn-connected?)
   :target (state/get-idn-target)})

;; ============================================================================
;; Effects Subscriptions
;; ============================================================================

(defn effects-cells
  "Get all effects grid cells.
   Returns: Map of [col row] -> {:effects [...] :active bool}"
  []
  (state/get-effects-cells))

(defn effect-cell
  "Get a single effect cell.
   Returns: {:effects [...] :active bool} or nil"
  [col row]
  (state/get-effect-cell col row))

(defn effect-cell-display-data
  "Get display data for an effect cell.
   Returns: {:col col :row row :effects [...] :effect-count int
             :active? bool :has-effects? bool}"
  [col row]
  (let [cell (state/get-effect-cell col row)
        effects (:effects cell)]
    {:col col
     :row row
     :effects effects
     :effect-count (count effects)
     :active? (boolean (:active cell))
     :has-effects? (boolean (seq effects))
     :first-effect-id (some-> effects first :effect-id)}))

(defn all-effect-cells-display-data
  "Get display data for all effect cells.
   Returns: Vector of effect cell display data maps."
  []
  (let [cells (state/get-effects-cells)]
    (mapv (fn [[key cell]]
            (let [[col row] key]
              (assoc (effect-cell-display-data col row)
                     :key key)))
          cells)))

(defn active-effects
  "Get all active effect instances from the effects grid.
   Returns: Vector of {:effect-id keyword :params map}"
  []
  (state/get-all-active-effect-instances))

;; ============================================================================
;; UI State Subscriptions
;; ============================================================================

(defn selected-preset
  "Get the currently selected preset in the UI."
  []
  (state/get-selected-preset))

(defn clipboard
  "Get clipboard contents."
  []
  (state/get-clipboard))

(defn dragging?
  "Check if a drag operation is in progress."
  []
  (state/dragging?))

(defn drag-data
  "Get current drag operation data."
  []
  (state/get-drag-data))

;; ============================================================================
;; Preset Subscriptions
;; ============================================================================

(defn all-presets
  "Get all available presets.
   Returns: Vector of preset maps."
  []
  presets/all-presets)

(defn presets-by-category
  "Get presets grouped by category.
   Returns: Map of category-keyword -> [preset ...]"
  []
  (group-by :category presets/all-presets))

(defn preset-categories
  "Get all preset category definitions.
   Returns: Map of category-keyword -> {:name string :color [r g b]}"
  []
  presets/categories)

;; ============================================================================
;; Project Subscriptions
;; ============================================================================

(defn project-folder
  "Get current project folder path."
  []
  (state/get-project-folder))

(defn project-dirty?
  "Check if project has unsaved changes."
  []
  (state/project-dirty?))

(defn has-project?
  "Check if a project is currently open."
  []
  (state/has-current-project?))

;; ============================================================================
;; Combined State for Main View
;; ============================================================================

(defn main-view-state
  "Get combined state for the main view.
   This is the primary subscription for the app renderer."
  []
  {:grid {:cells (grid-cells)
          :size (grid-size)
          :selected (selected-cell)
          :active (active-cell)}
   :playback {:playing? (playing?)
              :trigger-time (trigger-time)}
   :timing {:bpm (bpm)
            :beat-position (beat-position)}
   :connection (connection-status)
   :effects {:cells (effects-cells)}
   :ui {:selected-preset (selected-preset)
        :dragging? (dragging?)}
   :project {:folder (project-folder)
             :dirty? (project-dirty?)}})

;; ============================================================================
;; cljfx Map Event State
;; ============================================================================

(defn fx-state
  "Create a cljfx-compatible state map.
   This is what gets passed to the root component."
  []
  (main-view-state))
