(ns laser-show.state.subscriptions
  "Subscription functions for derived state data.
   
   UI components should use these instead of complex render logic.
   Each subscription function computes derived data from the raw atoms,
   providing a clean API for the view layer.
   
   Benefits:
   - UI components don't need to know about state structure
   - Complex logic moved out of render functions
   - Easy to test subscriptions independently
   - Single source of truth for derived data"
  (:require [laser-show.state.atoms :as state]))

;; ============================================================================
;; Grid Cell Subscriptions
;; ============================================================================

(defn active-cell-preset
  "Get the preset ID of the currently active cell.
   
   Returns: preset-id keyword or nil if no cell is active"
  []
  (when-let [[col row] (state/get-active-cell)]
    (:preset-id (state/get-cell col row))))

(defn grid-cell-display
  "Get cell data with computed display properties for rendering.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: Map with:
   - :preset-id - the preset keyword or nil
   - :active? - is this cell currently playing?
   - :selected? - is this cell currently selected?
   - :has-content? - does cell have a preset assigned?"
  [col row]
  (let [cell (state/get-cell col row)
        preset-id (:preset-id cell)]
    {:preset-id preset-id
     :active? (= [col row] (state/get-active-cell))
     :selected? (= [col row] (state/get-selected-cell))
     :has-content? (some? preset-id)}))

(defn all-grid-cells-display
  "Get display data for all cells in the grid.
   
   Returns: Map of [col row] -> cell-display-data"
  []
  (let [[cols rows] (state/get-grid-size)]
    (into {}
          (for [col (range cols)
                row (range rows)]
            [[col row] (grid-cell-display col row)]))))

;; ============================================================================
;; Effect Cell Subscriptions
;; ============================================================================

(defn effect-cell-display
  "Get effect cell data with computed display properties.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: Map with:
   - :has-effect? - does the cell have any effects?
   - :effect-count - number of effects in the chain
   - :first-effect-id - ID of the first effect in the chain
   - :active? - is the effect chain active?
   - :modulated? - does any effect have a modulator?
   - :display-text - text to display in the cell"
  [col row]
  (let [cell (state/get-effect-cell col row)
        effects (:effects cell)
        first-effect (first effects)
        effect-count (count effects)]
    {:has-effect? (pos? effect-count)
     :effect-count effect-count
     :first-effect-id (:effect-id first-effect)
     :active? (:active cell)
     :modulated? (boolean (some #(contains? (:params %) :modulator) effects))
     :display-text (when first-effect
                     (str (name (:effect-id first-effect))
                          (when (> effect-count 1)
                            (str " +" (dec effect-count)))))}))

(defn all-active-effects
  "Get all active effect instances in order.
   
   Returns: Vector of {:effect-id :params} maps
   Only includes effects from cells where :active is true."
  []
  (state/get-all-active-effect-instances))

;; ============================================================================
;; Playback Subscriptions
;; ============================================================================

(defn playback-status
  "Get current playback status.
   
   Returns: Map with:
   - :playing? - is playback active?
   - :active-cell - [col row] or nil if nothing playing
   - :preset-id - current preset keyword or nil
   - :elapsed-ms - milliseconds since trigger or nil"
  []
  (let [playing? (state/playing?)
        active-cell (state/get-active-cell)
        preset-id (active-cell-preset)
        trigger-time (state/get-trigger-time)
        elapsed-ms (when (and trigger-time (pos? trigger-time))
                     (- (System/currentTimeMillis) trigger-time))]
    {:playing? playing?
     :active-cell active-cell
     :preset-id preset-id
     :elapsed-ms elapsed-ms}))

;; ============================================================================
;; Timing Subscriptions
;; ============================================================================

(defn timing-info
  "Get current timing information.
   
   Returns: Map with:
   - :bpm - current beats per minute
   - :beat-position - position within current beat (0.0-1.0)
   - :ms-per-beat - milliseconds per beat"
  []
  (let [bpm (state/get-bpm)
        beat-position (state/get-beat-position)
        ms-per-beat (/ 60000.0 bpm)]
    {:bpm bpm
     :beat-position beat-position
     :ms-per-beat ms-per-beat}))

;; ============================================================================
;; Project Subscriptions
;; ============================================================================

(defn project-status
  "Get current project status.
   
   Returns: Map with:
   - :has-project? - is a project folder set?
   - :folder - project folder path or nil
   - :dirty? - has unsaved changes?
   - :last-saved - timestamp of last save or nil"
  []
  {:has-project? (state/has-current-project?)
   :folder (state/get-project-folder)
   :dirty? (state/project-dirty?)
   :last-saved (state/get-project-last-saved)})

;; ============================================================================
;; Connection Subscriptions
;; ============================================================================

(defn idn-status
  "Get IDN connection status.
   
   Returns: Map with:
   - :connected? - is connected to IDN target?
   - :target - hostname/IP of target or nil"
  []
  {:connected? (state/idn-connected?)
   :target (state/get-idn-target)})

(defn streaming-status
  "Get streaming status.
   
   Returns: Map with:
   - :streaming? - is streaming active?
   - :engine-count - number of active engines"
  []
  {:streaming? (state/streaming?)
   :engine-count (count (state/get-streaming-engines))})

;; ============================================================================
;; UI State Subscriptions
;; ============================================================================

(defn selection-state
  "Get current selection state for grids.
   
   Returns: Map with:
   - :selected-cell - [col row] or nil
   - :selected-preset - preset keyword or nil"
  []
  {:selected-cell (state/get-selected-cell)
   :selected-preset (state/get-selected-preset)})

(defn drag-state
  "Get current drag operation state.
   
   Returns: Map with drag operation details or nil if not dragging"
  []
  (let [drag (state/get-drag-data)]
    (when (:active? drag)
      drag)))
