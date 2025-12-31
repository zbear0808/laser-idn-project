(ns laser-show.subs
  "Subscription functions for cljfx components.
   
   ALL subscriptions use fx/sub-val or fx/sub-ctx to integrate with cljfx's
   memoization system. This is the single source of derived state for the UI.
   
   Subscription Levels:
   
   Level 1: fx/sub-val - Direct domain access
            Fast, always recalculated, use for simple domain lookups
            Example: (fx/sub-val context :timing)
   
   Level 2: fx/sub-ctx - Computed subscriptions (memoized, dependency-tracked)
            Use for derived values, computations, and data transformations
            Example: (fx/sub-ctx context cell-display-data 0 0)
   
   Usage in components:
   
   (defn my-component [{:keys [fx/context]}]
     (let [timing (fx/sub-val context :timing)   ; Direct domain access
           cell (fx/sub-ctx context cell-display-data 0 0)]  ; Computed
       ...))"
  (:require [cljfx.api :as fx]
            [laser-show.css.core :as css]))

;; ============================================================================
;; Simple Direct Accessors (Level 1: fx/sub-val)
;; These directly access domain fields without computation
;; ============================================================================

;; --- Timing ---
(defn bpm [context] (:bpm (fx/sub-val context :timing)))
(defn beat-position [context] (:beat-position (fx/sub-val context :timing)))

;; --- Playback ---
(defn playing? [context] (:playing? (fx/sub-val context :playback)))
(defn active-cell [context] (:active-cell (fx/sub-val context :playback)))

;; --- Grid ---
(defn grid-size [context] (:size (fx/sub-val context :grid)))
(defn selected-cell [context] (:selected-cell (fx/sub-val context :grid)))

;; --- UI ---
(defn active-tab [context] (:active-tab (fx/sub-val context :ui)))
(defn clipboard [context] (:clipboard (fx/sub-val context :ui)))

;; --- Project ---
(defn project-folder [context] (:current-folder (fx/sub-val context :project)))
(defn project-dirty? [context] (:dirty? (fx/sub-val context :project)))

;; --- Config ---
(defn window-config [context] (:window (fx/sub-val context :config)))
(defn preview-config [context] (:preview (fx/sub-val context :config)))

;; ============================================================================
;; Grid Cell Subscriptions (Computed)
;; ============================================================================

(defn cell-display-data
  "Computed display data for a grid cell.
   
   Returns map with:
   - :col, :row - position
   - :preset-id - preset keyword or nil
   - :active? - is this cell playing?
   - :selected? - is this cell selected?
   - :has-content? - does cell have a preset?"
  [context col row]
  (let [grid (fx/sub-val context :grid)
        playback (fx/sub-val context :playback)
        cell (get-in grid [:cells [col row]])]
    {:col col
     :row row
     :preset-id (:preset-id cell)
     :active? (= [col row] (:active-cell playback))
     :selected? (= [col row] (:selected-cell grid))
     :has-content? (boolean (:preset-id cell))}))

(defn active-cell-preset
  "Get the preset ID of the currently active cell.
   Returns: preset-id keyword or nil if no cell is active"
  [context]
  (let [playback (fx/sub-val context :playback)]
    (when-let [[col row] (:active-cell playback)]
      (let [grid (fx/sub-val context :grid)]
        (get-in grid [:cells [col row] :preset-id])))))

(defn active-preset
  "Alias for active-cell-preset. Get preset of currently active cell."
  [context]
  (fx/sub-ctx context active-cell-preset))

(defn all-grid-cells-display
  "Get display data for all cells in the grid.
   Returns: Map of [col row] -> cell-display-data"
  [context]
  (let [grid (fx/sub-val context :grid)
        [cols rows] (:size grid)]
    (into {}
          (for [col (range cols)
                row (range rows)]
            [[col row] (fx/sub-ctx context cell-display-data col row)]))))

(defn grid-positions
  "Get all grid positions as sequence of [col row] vectors.
   Useful for rendering grid."
  [context]
  (let [grid (fx/sub-val context :grid)
        [cols rows] (:size grid)]
    (for [row (range rows)
          col (range cols)]
      [col row])))

(defn grid-rows
  "Get grid as rows, each row is a vector of [col row] positions.
   Useful for row-based rendering."
  [context]
  (let [grid (fx/sub-val context :grid)
        [cols rows] (:size grid)]
    (for [row (range rows)]
      (vec (for [col (range cols)]
             [col row])))))

;; ============================================================================
;; Effect Cell Subscriptions (Computed)
;; ============================================================================

(defn effect-cell-display-data
  "Computed display data for an effects grid cell.
   
   Returns map with:
   - :col, :row - position
   - :effect-count - number of effects in chain
   - :first-effect-id - id of first effect
   - :active? - is effect chain active?
   - :has-effects? - does cell have any effects?
   - :display-text - text to display in the cell"
  [context col row]
  (let [effects (fx/sub-val context :effects)
        cell (get-in effects [:cells [col row]])
        effect-chain (:effects cell [])
        first-effect (first effect-chain)
        effect-count (count effect-chain)]
    {:col col
     :row row
     :effect-count effect-count
     :first-effect-id (:effect-id first-effect)
     :active? (:active cell false)
     :has-effects? (pos? effect-count)
     :display-text (when first-effect
                     (str (name (:effect-id first-effect))
                          (when (> effect-count 1)
                            (str " +" (dec effect-count)))))}))

(defn all-active-effects
  "Get all active effect instances in order.
   Returns: Vector of {:effect-id :params} maps
   Only includes effects from cells where :active is true."
  [context]
  (let [effects (fx/sub-val context :effects)
        cells (:cells effects)
        sorted-keys (sort-by (fn [[col row]] [row col]) (keys cells))]
    (into []
          (comp
           (map #(get cells %))
           (filter :active)
           (mapcat :effects)
           (map #(select-keys % [:effect-id :params])))
          sorted-keys)))

;; ============================================================================
;; Playback Subscriptions (Computed)
;; ============================================================================

(defn playback-status
  "Computed playback status.
   
   Returns map with:
   - :playing? - is playback active?
   - :active-cell - [col row] or nil if nothing playing
   - :preset-id - current preset keyword or nil
   - :elapsed-ms - milliseconds since trigger or nil"
  [context]
  (let [playback (fx/sub-val context :playback)
        active-cell (:active-cell playback)
        preset-id (fx/sub-ctx context active-cell-preset)
        trigger-time (:trigger-time playback)
        elapsed-ms (when (and trigger-time (pos? trigger-time))
                     (- (System/currentTimeMillis) trigger-time))]
    {:playing? (:playing? playback)
     :active-cell active-cell
     :preset-id preset-id
     :elapsed-ms elapsed-ms}))

;; ============================================================================
;; Timing Subscriptions (Computed)
;; ============================================================================

(defn timing-info
  "Get current timing information.
   
   Returns map with:
   - :bpm - current beats per minute
   - :beat-position - position within current beat (0.0-1.0)
   - :bar-position - position within current bar (0.0-1.0)
   - :ms-per-beat - milliseconds per beat
   - :quantization - quantization mode"
  [context]
  (let [timing (fx/sub-val context :timing)
        bpm (:bpm timing)
        ms-per-beat (/ 60000.0 bpm)]
    {:bpm bpm
     :beat-position (:beat-position timing)
     :bar-position (:bar-position timing)
     :ms-per-beat ms-per-beat
     :quantization (:quantization timing)}))

;; ============================================================================
;; Project Subscriptions (Computed)
;; ============================================================================

(defn project-status
  "Get current project status.
   
   Returns map with:
   - :has-project? - is a project folder set?
   - :folder - project folder path or nil
   - :dirty? - has unsaved changes?
   - :last-saved - timestamp of last save or nil
   - :title - computed window title"
  [context]
  (let [project (fx/sub-val context :project)
        folder (:current-folder project)
        dirty? (:dirty? project)]
    {:has-project? (some? folder)
     :folder folder
     :dirty? dirty?
     :last-saved (:last-saved project)
     :title (str "Laser Show"
                 (when folder (str " - " folder))
                 (when dirty? " *"))}))

;; ============================================================================
;; Connection Subscriptions (Computed)
;; ============================================================================

(defn idn-status
  "Get IDN connection status.
   
   Returns map with:
   - :connected? - is connected to IDN target?
   - :connecting? - is connection in progress?
   - :target - hostname/IP of target or nil
   - :error - error message or nil"
  [context]
  (let [backend (fx/sub-val context :backend)
        idn (:idn backend)]
    {:connected? (:connected? idn)
     :connecting? (:connecting? idn)
     :target (:target idn)
     :error (:error idn)}))

(defn streaming-status
  "Get streaming status.
   
   Returns map with:
   - :streaming? - is streaming active?
   - :engine-count - number of active engines
   - :connected-targets - set of connected target IDs"
  [context]
  (let [backend (fx/sub-val context :backend)
        streaming (:streaming backend)]
    {:streaming? (:running? streaming)
     :engine-count (count (:engines streaming))
     :connected-targets (:connected-targets streaming)}))

(defn connection-status
  "Computed connection status for toolbar.
   
   Returns map with:
   - :connected? - is connected?
   - :connecting? - is connecting?
   - :target - target hostname
   - :error - error message
   - :status-text - human-readable status"
  [context]
  (let [backend (fx/sub-val context :backend)
        idn (:idn backend)]
    {:connected? (:connected? idn)
     :connecting? (:connecting? idn)
     :target (:target idn)
     :error (:error idn)
     :status-text (cond
                    (:connected? idn) (str "Connected: " (:target idn))
                    (:connecting? idn) "Connecting..."
                    (:error idn) (str "Error: " (:error idn))
                    :else "Disconnected")}))

;; ============================================================================
;; UI State Subscriptions (Computed)
;; ============================================================================

(defn selection-state
  "Get current selection state for grids.
   
   Returns map with:
   - :selected-cell - [col row] or nil
   - :selected-preset - preset keyword or nil"
  [context]
  (let [grid (fx/sub-val context :grid)
        ui (fx/sub-val context :ui)]
    {:selected-cell (:selected-cell grid)
     :selected-preset (:selected-preset ui)}))

(defn drag-state
  "Get current drag operation state.
   Returns map with drag operation details or nil if not dragging"
  [context]
  (let [ui (fx/sub-val context :ui)
        drag (:drag ui)]
    (when (:active? drag)
      drag)))

(defn dialog-open?
  "Check if a specific dialog is open."
  [context dialog-id]
  (let [ui (fx/sub-val context :ui)]
    (get-in ui [:dialogs dialog-id :open?] false)))

(defn dialog-data
  "Get data associated with a dialog."
  [context dialog-id]
  (let [ui (fx/sub-val context :ui)]
    (get-in ui [:dialogs dialog-id :data])))

;; ============================================================================
;; Parameterized Entity Subscriptions
;; ============================================================================

(defn cell
  "Get a grid cell by position."
  [context col row]
  (get-in (fx/sub-val context :grid) [:cells [col row]]))

(defn effect-cell
  "Get an effect cell by position."
  [context col row]
  (get-in (fx/sub-val context :effects) [:cells [col row]]))

(defn projector
  "Get a projector by ID."
  [context projector-id]
  (get-in (fx/sub-val context :projectors) [:items projector-id]))

(defn zone
  "Get a zone by ID."
  [context zone-id]
  (get-in (fx/sub-val context :zones) [:items zone-id]))

(defn zone-group
  "Get a zone group by ID."
  [context group-id]
  (get-in (fx/sub-val context :zone-groups) [:items group-id]))

(defn cue
  "Get a cue by ID."
  [context cue-id]
  (get-in (fx/sub-val context :cues) [:items cue-id]))

(defn cue-list
  "Get a cue list by ID."
  [context list-id]
  (get-in (fx/sub-val context :cue-lists) [:items list-id]))

(defn registered-effect
  "Get a registered effect by ID."
  [context effect-id]
  (get-in (fx/sub-val context :effect-registry) [:items effect-id]))

;; ============================================================================
;; Collection Subscriptions
;; ============================================================================

(defn projectors
  "Get all projectors."
  [context]
  (get-in (fx/sub-val context :projectors) [:items]))

(defn projector-ids
  "Get all projector IDs."
  [context]
  (keys (fx/sub-ctx context projectors)))

(defn zones
  "Get all zones."
  [context]
  (get-in (fx/sub-val context :zones) [:items]))

(defn zone-ids
  "Get all zone IDs."
  [context]
  (keys (fx/sub-ctx context zones)))

(defn zone-groups
  "Get all zone groups."
  [context]
  (get-in (fx/sub-val context :zone-groups) [:items]))

(defn zone-group-ids
  "Get all zone group IDs."
  [context]
  (keys (fx/sub-ctx context zone-groups)))

(defn cues
  "Get all cues."
  [context]
  (get-in (fx/sub-val context :cues) [:items]))

(defn cue-ids
  "Get all cue IDs."
  [context]
  (keys (fx/sub-ctx context cues)))

(defn cue-lists
  "Get all cue lists."
  [context]
  (get-in (fx/sub-val context :cue-lists) [:items]))

(defn streaming-engines
  "Get all streaming engines."
  [context]
  (get-in (fx/sub-val context :streaming) [:engines]))

;; ============================================================================
;; Frame/Preview Subscriptions
;; ============================================================================

(defn current-frame
  "Get the current preview frame data.
   Returns frame with :points vector."
  [context]
  (let [backend (fx/sub-val context :backend)]
    (get-in backend [:streaming :current-frame])))

(defn frame-stats
  "Get frame rendering statistics."
  [context]
  (let [backend (fx/sub-val context :backend)]
    (get-in backend [:streaming :frame-stats])))

;; ============================================================================
;; Stylesheet Subscriptions (CSS Hot-Reload)
;; ============================================================================

(defn stylesheet-urls
  "Get all stylesheet URLs for scenes.
   Uses the centralized CSS system from laser-show.css.core.
   
   Includes all application CSS:
   - Theme (colors, typography, containers)
   - Buttons (transport, action, tab)
   - Forms (text fields, labels, sliders)
   - Grid cells (cue and effect grids)
   - Layout (toolbar, status bar, panels)
   - Dialogs (dialog-specific styles)
   - Menus (context menu styling)"
  [_context]
  ;; Use centralized CSS system
  (css/all-stylesheet-urls))
