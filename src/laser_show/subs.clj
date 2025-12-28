(ns laser-show.subs
  "Subscription functions for cljfx components.
   
   This module provides subscription functions that:
   - Extract data from state for UI components
   - Compute derived values efficiently
   - Integrate with cljfx's memoization system
   
   Subscription Levels:
   
   Level 1: fx/sub-val - Direct value access (fast, always recalculated)
            Use for simple key lookups
   
   Level 2: fx/sub-ctx - Computed subscriptions (memoized, dependency-tracked)
            Use for derived values that depend on multiple state keys
   
   Usage in components:
   
   (defn my-component [{:keys [fx/context]}]
     (let [bpm (fx/sub-val context :timing)   ;; Direct access
           data (fx/sub-ctx context cell-display-data 0 0)]  ;; Computed
       ...))"
  (:require [cljfx.api :as fx]
            [laser-show.ui.styles :as styles]))

;; ============================================================================
;; Level 1: Direct Value Subscriptions
;; These are simple accessors, always recalculated but very fast
;; ============================================================================

;; --- Timing ---

(defn bpm
  "Get current BPM."
  [context]
  (:bpm (fx/sub-val context :timing)))

(defn beat-position
  "Get current beat position (0.0-1.0)."
  [context]
  (:beat-position (fx/sub-val context :timing)))

(defn bar-position
  "Get current bar position (0.0-1.0)."
  [context]
  (:bar-position (fx/sub-val context :timing)))

(defn quantization
  "Get current quantization mode."
  [context]
  (:quantization (fx/sub-val context :timing)))

;; --- Playback ---

(defn playing?
  "Check if playback is active."
  [context]
  (:playing? (fx/sub-val context :playback)))

(defn active-cell
  "Get currently active [col row] or nil."
  [context]
  (:active-cell (fx/sub-val context :playback)))

(defn trigger-time
  "Get trigger timestamp."
  [context]
  (:trigger-time (fx/sub-val context :playback)))

;; --- Grid ---

(defn grid-cells
  "Get all grid cells map."
  [context]
  (:cells (fx/sub-val context :grid)))

(defn grid-size
  "Get grid [cols rows] dimensions."
  [context]
  (:size (fx/sub-val context :grid)))

(defn selected-cell
  "Get selected [col row] or nil."
  [context]
  (:selected-cell (fx/sub-val context :grid)))

;; --- Effects ---

(defn effects-cells
  "Get all effects cells map."
  [context]
  (:cells (fx/sub-val context :effects)))

;; --- UI ---

(defn active-tab
  "Get active tab keyword."
  [context]
  (:active-tab (fx/sub-val context :ui)))

(defn selected-preset
  "Get selected preset in browser."
  [context]
  (:selected-preset (fx/sub-val context :ui)))

(defn clipboard
  "Get clipboard data."
  [context]
  (:clipboard (fx/sub-val context :ui)))

(defn drag-state
  "Get current drag operation state."
  [context]
  (:drag (fx/sub-val context :ui)))

(defn dialogs
  "Get all dialog states."
  [context]
  (:dialogs (fx/sub-val context :ui)))

;; --- Project ---

(defn project-folder
  "Get current project folder."
  [context]
  (:current-folder (fx/sub-val context :project)))

(defn project-dirty?
  "Check if project has unsaved changes."
  [context]
  (:dirty? (fx/sub-val context :project)))

;; --- Backend ---

(defn idn-connected?
  "Check if IDN is connected."
  [context]
  (get-in (fx/sub-val context :backend) [:idn :connected?]))

(defn idn-connecting?
  "Check if IDN is connecting."
  [context]
  (get-in (fx/sub-val context :backend) [:idn :connecting?]))

(defn idn-target
  "Get IDN target hostname."
  [context]
  (get-in (fx/sub-val context :backend) [:idn :target]))

(defn streaming-running?
  "Check if streaming is running."
  [context]
  (get-in (fx/sub-val context :backend) [:streaming :running?]))

;; --- Config ---

(defn grid-config
  "Get grid config {:cols :rows}."
  [context]
  (:grid (fx/sub-val context :config)))

(defn window-config
  "Get window config {:width :height}."
  [context]
  (:window (fx/sub-val context :config)))

(defn preview-config
  "Get preview config {:width :height}."
  [context]
  (:preview (fx/sub-val context :config)))

;; ============================================================================
;; Level 2: Computed Subscriptions
;; These are memoized and only recalculated when dependencies change
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

(defn effect-cell-display-data
  "Computed display data for an effects grid cell.
   
   Returns map with:
   - :col, :row - position
   - :effect-count - number of effects in chain
   - :first-effect-id - id of first effect
   - :active? - is effect chain active?
   - :has-effects? - does cell have any effects?"
  [context col row]
  (let [effects (fx/sub-val context :effects)
        cell (get-in effects [:cells [col row]])
        effect-chain (:effects cell [])]
    {:col col
     :row row
     :effect-count (count effect-chain)
     :first-effect-id (:effect-id (first effect-chain))
     :active? (:active cell false)
     :has-effects? (pos? (count effect-chain))}))

(defn playback-status
  "Computed playback status for status bar.
   
   Returns map with:
   - :playing? - is playback active?
   - :active-cell - [col row] or nil
   - :bpm - current BPM
   - :beat-position - beat position 0.0-1.0"
  [context]
  (let [playback (fx/sub-val context :playback)
        timing (fx/sub-val context :timing)]
    {:playing? (:playing? playback)
     :active-cell (:active-cell playback)
     :bpm (:bpm timing)
     :beat-position (:beat-position timing)}))

(defn project-status
  "Computed project status for title bar.
   
   Returns map with:
   - :dirty? - has unsaved changes?
   - :has-project? - is a project open?
   - :folder - project folder path
   - :title - computed window title"
  [context]
  (let [project (fx/sub-val context :project)
        folder (:current-folder project)
        dirty? (:dirty? project)]
    {:dirty? dirty?
     :has-project? (some? folder)
     :folder folder
     :title (str "Laser Show"
                 (when folder (str " - " folder))
                 (when dirty? " *"))}))

(defn connection-status
  "Computed connection status for toolbar.
   
   Returns map with:
   - :connected? - is connected?
   - :connecting? - is connecting?
   - :target - target hostname
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

(defn active-preset
  "Get the preset of the currently active cell."
  [context]
  (when-let [[col row] (fx/sub-ctx context active-cell)]
    (:preset-id (fx/sub-ctx context cell-display-data col row))))

;; ============================================================================
;; Level 3: Dialog State Subscriptions
;; ============================================================================

(defn dialog-open?
  "Check if a specific dialog is open."
  [context dialog-id]
  (get-in (fx/sub-val context :ui) [:dialogs dialog-id :open?] false))

(defn dialog-data
  "Get data associated with a dialog."
  [context dialog-id]
  (get-in (fx/sub-val context :ui) [:dialogs dialog-id :data]))

(defn zone-editor-state
  "Get zone editor dialog state."
  [context]
  (get-in (fx/sub-val context :ui) [:dialogs :zone-editor]))

(defn effect-editor-state
  "Get effect editor dialog state."
  [context]
  (get-in (fx/sub-val context :ui) [:dialogs :effect-editor]))

;; ============================================================================
;; Grid Iteration Helpers
;; ============================================================================

(defn grid-positions
  "Get all grid positions as sequence of [col row] vectors.
   Useful for rendering grid."
  [context]
  (let [[cols rows] (fx/sub-ctx context grid-size)]
    (for [row (range rows)
          col (range cols)]
      [col row])))

(defn grid-rows
  "Get grid as rows, each row is a vector of [col row] positions.
   Useful for row-based rendering."
  [context]
  (let [[cols rows] (fx/sub-ctx context grid-size)]
    (for [row (range rows)]
      (vec (for [col (range cols)]
             [col row])))))

;; ============================================================================
;; Frame/Preview Subscriptions
;; ============================================================================

(defn current-frame
  "Get the current preview frame data.
   Returns frame with :points vector."
  [context]
  ;; TODO: This should be computed from active cell + effects
  ;; For now, return nil
  (get-in (fx/sub-val context :backend) [:streaming :current-frame]))

(defn frame-stats
  "Get frame rendering statistics."
  [context]
  (get-in (fx/sub-val context :backend) [:streaming :frame-stats]))

;; ============================================================================
;; Stylesheet Subscriptions (CSS Hot-Reload)
;; ============================================================================

(defn menu-theme-url
  "Get the menu theme CSS URL from state.
   Returns the cljfx-css URL string, or nil if not yet initialized."
  [context]
  (get-in (fx/sub-val context :styles) [:menu-theme]))

(defn stylesheet-urls
  "Get all stylesheet URLs for scenes.
   Combines static and dynamic (hot-reloadable) stylesheets.
   
   Static stylesheets:
   - base-theme-css: Inline base theme
   - tabs.css: Resource file for tab styling
   
   Dynamic stylesheets:
   - menu-theme: cljfx-css registered style (hot-reloadable)"
  [context]
  (let [menu-url (fx/sub-ctx context menu-theme-url)]
    (filterv some?
             [(styles/inline-stylesheet styles/base-theme-css)
              (styles/resource-stylesheet "styles/tabs.css")
              menu-url])))
