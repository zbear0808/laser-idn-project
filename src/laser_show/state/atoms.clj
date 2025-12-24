(ns laser-show.state.atoms
  "Core atom definitions and accessor functions for runtime state.
   
   This file is the single source of truth for all volatile runtime state
   that is not persisted between sessions.
   
   Contains:
   - Default constants
   - Initial state definitions
   - Atom definitions
   - Reset functions
   - Accessor functions for all state operations
   
   Usage:
   - Import as [laser-show.state.atoms :as state]
   - Use accessor functions: (state/get-bpm), (state/set-bpm! 120)
   - Access atoms directly when needed: state/!timing, state/!playback")

;; ============================================================================
;; Default Constants
;; ============================================================================

(def default-bpm
  "Default beats per minute for timing. Standard DJ tempo."
  120.0)

(def default-osc-port
  "Default UDP port for OSC server."
  8000)

(def default-window-width
  "Default main window width in pixels."
  1200)

(def default-window-height
  "Default main window height in pixels."
  800)

(def default-grid-cols
  "Default number of columns in the cue grid."
  8)

(def default-grid-rows
  "Default number of rows in the cue grid."
  4)

(def default-log-path
  "Default file path for IDN packet logging."
  "idn-packets.log")

;; ============================================================================
;; Initial State Definitions
;; ============================================================================

(def initial-timing-state
  "Initial state for timing/BPM management.
   
   Keys:
   - :bpm - Current beats per minute
   - :tap-times - Vector of tap-tempo timestamps for BPM calculation
   - :beat-position - Current position within beat (0.0 to 1.0)
   - :bar-position - Current position within bar
   - :last-beat-time - Timestamp of last beat
   - :beats-elapsed - Total beats elapsed since start
   - :quantization - Quantization mode (:beat, :bar, :none)"
  {:bpm default-bpm
   :tap-times []
   :beat-position 0.0
   :bar-position 0.0
   :last-beat-time 0
   :beats-elapsed 0
   :quantization :beat})

(def initial-playback-state
  "Initial state for playback control.
   
   Keys:
   - :playing? - Whether playback is active
   - :trigger-time - Timestamp when current animation was triggered
   - :active-cell - [col row] of currently playing grid cell
   - :active-cue - Currently active cue (for cue-based playback)
   - :cue-queue - Queue of pending cues"
  {:playing? false
   :trigger-time 0
   :active-cell nil
   :active-cue nil
   :cue-queue []})

(def initial-grid-state
  "Initial state for the cue grid.
   
   Keys:
   - :cells - Map of [col row] -> {:preset-id keyword} for each occupied cell
   - :selected-cell - [col row] of currently selected cell, or nil
   - :size - [cols rows] dimensions of the grid"
  {:cells {[0 0] {:preset-id :circle}
           [1 0] {:preset-id :spinning-square}
           [2 0] {:preset-id :triangle}
           [3 0] {:preset-id :star}
           [4 0] {:preset-id :spiral}
           [5 0] {:preset-id :wave}
           [6 0] {:preset-id :beam-fan}
           [7 0] {:preset-id :rainbow-circle}}
   :selected-cell nil
   :size [default-grid-cols default-grid-rows]})

(def initial-streaming-state
  "Initial state for IDN streaming engines.
   
   Keys:
   - :engines - Map of projector-id -> streaming engine instances
   - :running? - Whether streaming is currently active
   - :connected-targets - Set of connected projector targets
   - :frame-stats - Statistics about frame generation/sending
   - :multi-engine-state - State for multi-projector coordination"
  {:engines {}
   :running? false
   :connected-targets #{}
   :frame-stats {}
   :multi-engine-state nil})

(def initial-idn-state
  "Initial state for IDN connection management.
   
   Keys:
   - :connected? - Whether connected to an IDN target
   - :target - Current target hostname/IP
   - :streaming-engine - Active streaming engine instance"
  {:connected? false
   :target nil
   :streaming-engine nil})

(def initial-input-state
  "Initial state for input handling (MIDI, OSC, keyboard).
   
   Keys:
   - :midi - MIDI input configuration and state
     - :enabled, :connected-devices, :learn-mode, :device, :receiver
   - :osc - OSC input configuration and state
     - :enabled, :server-running, :learn-mode, :server, :port
   - :keyboard - Keyboard input configuration
     - :enabled, :attached-components
   - :router - Input router state
     - :handlers, :event-log, :enabled"
  {:midi {:enabled true
          :connected-devices #{}
          :learn-mode nil
          :device nil
          :receiver nil}
   :osc {:enabled false
         :server-running false
         :learn-mode nil
         :server nil
         :port default-osc-port}
   :keyboard {:enabled true
              :attached-components #{}}
   :router {:handlers {}
            :event-log []
            :enabled true}})

(def initial-ui-state
  "Initial state for UI components and interaction.
   
   Keys:
   - :selected-preset - Currently selected preset in browser
   - :clipboard - Clipboard data for copy/paste
   - :preview - Preview panel state (:frame, :last-render-time)
   - :active-tab - Currently active tab in UI
   - :window - Window dimensions (:width, :height)
   - :drag - Drag operation state (:active?, :source-type, :source-id, :source-key, :data)
   - :components - References to UI components for programmatic access"
  {:selected-preset nil
   :clipboard nil
   :preview {:frame nil
             :last-render-time 0}
   :active-tab :grid
   :window {:width default-window-width
            :height default-window-height}
   :drag {:active? false
          :source-type nil
          :source-id nil
          :source-key nil
          :data nil}
   :components {:main-frame nil
                :preview-panel nil
                :grid-panel nil
                :effects-panel nil
                :status-bar nil
                :toolbar nil}})

(def initial-logging-state
  "Initial state for packet logging.
   
   Keys:
   - :enabled? - Whether logging is active
   - :file - Current log file handle
   - :path - Path to log file"
  {:enabled? false
   :file nil
   :path default-log-path})

(def initial-effects-state
  "Initial state for effects grid.
   
   Keys:
   - :cells - Map of [col row] -> cell-data for effects grid
     Cell data structure: {:effects [{:effect-id :scale :params {...}} ...] :active true}
   
   Each cell can contain a chain of effects that are applied in order.
   The :active flag toggles the entire chain on/off.
   
   This is the single source of truth for all effects grid data.
   UI-transient state (like :selected) is NOT stored here."
  {:cells {}})

(def initial-config-state
  "Initial state for application configuration.
   
   Keys:
   - :grid - Grid dimensions {:cols :rows}
   - :window - Window dimensions {:width :height}
   - :preview - Preview panel dimensions {:width :height}
   - :idn - IDN connection settings {:host :port}
   - :osc - OSC settings {:enabled :port}
   - :midi - MIDI settings {:enabled :device}"
  {:grid {:cols default-grid-cols :rows default-grid-rows}
   :window {:width default-window-width :height default-window-height}
   :preview {:width 400 :height 400}
   :idn {:host nil :port 7255}
   :osc {:enabled false :port default-osc-port}
   :midi {:enabled false :device nil}})

(def initial-projectors-state
  "Initial state for projector configurations.
   Map of projector-id -> projector-config."
  {})

(def initial-zones-state
  "Initial state for zone configurations.
   Map of zone-id -> zone-config."
  {})

(def initial-zone-groups-state
  "Initial state for zone group configurations.
   Map of group-id -> group-config."
  {})

(def initial-cues-state
  "Initial state for cue definitions.
   Map of cue-id -> cue-definition."
  {})

(def initial-cue-lists-state
  "Initial state for cue lists.
   Map of list-id -> cue-list."
  {})

(def initial-effect-registry-state
  "Initial state for the effect registry.
   Map of effect-id -> effect-definition."
  {})

(def initial-project-state
  "Initial state for project file management.
   
   Keys:
   - :current-folder - Path to current project folder (nil = no project)
   - :dirty? - Whether changes have been made since last save
   - :last-saved - Timestamp of last save"
  {:current-folder nil
   :dirty? false
   :last-saved nil})

;; ============================================================================
;; Atom Definitions
;; ============================================================================

(defonce ^{:doc "Atom for timing/BPM state. See `initial-timing-state` for structure."}
  !timing (atom initial-timing-state))

(defonce ^{:doc "Atom for playback state. See `initial-playback-state` for structure."}
  !playback (atom initial-playback-state))

(defonce ^{:doc "Atom for cue grid state. See `initial-grid-state` for structure."}
  !grid (atom initial-grid-state))

(defonce ^{:doc "Atom for streaming engine state. See `initial-streaming-state` for structure."}
  !streaming (atom initial-streaming-state))

(defonce ^{:doc "Atom for IDN connection state. See `initial-idn-state` for structure."}
  !idn (atom initial-idn-state))

(defonce ^{:doc "Atom for input handling state. See `initial-input-state` for structure."}
  !input (atom initial-input-state))

(defonce ^{:doc "Atom for UI state. See `initial-ui-state` for structure."}
  !ui (atom initial-ui-state))

(defonce ^{:doc "Atom for packet logging state. See `initial-logging-state` for structure."}
  !logging (atom initial-logging-state))

(defonce ^{:doc "Atom for active effects state. See `initial-effects-state` for structure."}
  !effects (atom initial-effects-state))

(defonce ^{:doc "Atom for application configuration. See `initial-config-state` for structure."}
  !config (atom initial-config-state))

(defonce ^{:doc "Atom for projector configurations. See `initial-projectors-state` for structure."}
  !projectors (atom initial-projectors-state))

(defonce ^{:doc "Atom for zone configurations. See `initial-zones-state` for structure."}
  !zones (atom initial-zones-state))

(defonce ^{:doc "Atom for zone group configurations. See `initial-zone-groups-state` for structure."}
  !zone-groups (atom initial-zone-groups-state))

(defonce ^{:doc "Atom for cue definitions. See `initial-cues-state` for structure."}
  !cues (atom initial-cues-state))

(defonce ^{:doc "Atom for cue lists. See `initial-cue-lists-state` for structure."}
  !cue-lists (atom initial-cue-lists-state))

(defonce ^{:doc "Atom for effect registry. See `initial-effect-registry-state` for structure."}
  !effect-registry (atom initial-effect-registry-state))

(defonce ^{:doc "Atom for project management state. See `initial-project-state` for structure."}
  !project (atom initial-project-state))

;; ============================================================================
;; Reset Functions
;; ============================================================================

(defn reset-timing!
  "Reset timing state to initial values."
  []
  (reset! !timing initial-timing-state))

(defn reset-playback!
  "Reset playback state to initial values."
  []
  (reset! !playback initial-playback-state))

(defn reset-grid!
  "Reset grid state to initial values (includes default presets)."
  []
  (reset! !grid initial-grid-state))

(defn reset-streaming!
  "Reset streaming state to initial values."
  []
  (reset! !streaming initial-streaming-state))

(defn reset-idn!
  "Reset IDN connection state to initial values."
  []
  (reset! !idn initial-idn-state))

(defn reset-input!
  "Reset input handling state to initial values."
  []
  (reset! !input initial-input-state))

(defn reset-ui!
  "Reset UI state to initial values."
  []
  (reset! !ui initial-ui-state))

(defn reset-logging!
  "Reset logging state to initial values."
  []
  (reset! !logging initial-logging-state))

(defn reset-effects!
  "Reset effects state to initial values."
  []
  (reset! !effects initial-effects-state))

(defn reset-config!
  "Reset config state to initial values."
  []
  (reset! !config initial-config-state))

(defn reset-projectors!
  "Reset projectors state to initial values."
  []
  (reset! !projectors initial-projectors-state))

(defn reset-zones!
  "Reset zones state to initial values."
  []
  (reset! !zones initial-zones-state))

(defn reset-zone-groups!
  "Reset zone-groups state to initial values."
  []
  (reset! !zone-groups initial-zone-groups-state))

(defn reset-cues!
  "Reset cues state to initial values."
  []
  (reset! !cues initial-cues-state))

(defn reset-cue-lists!
  "Reset cue-lists state to initial values."
  []
  (reset! !cue-lists initial-cue-lists-state))

(defn reset-effect-registry!
  "Reset effect-registry state to initial values."
  []
  (reset! !effect-registry initial-effect-registry-state))

(defn reset-project!
  "Reset project state to initial values."
  []
  (reset! !project initial-project-state))

(defn reset-all!
  "Reset all state atoms to their initial values.
   USE WITH CAUTION - mainly for testing."
  []
  (reset-timing!)
  (reset-playback!)
  (reset-grid!)
  (reset-streaming!)
  (reset-idn!)
  (reset-input!)
  (reset-ui!)
  (reset-logging!)
  (reset-effects!)
  (reset-config!)
  (reset-projectors!)
  (reset-zones!)
  (reset-zone-groups!)
  (reset-cues!)
  (reset-cue-lists!)
  (reset-effect-registry!)
  (reset-project!))

;; ============================================================================
;; Accessor Functions - Timing
;; ============================================================================

(defn get-bpm
  "Get the current BPM (beats per minute).
   Returns: BPM as a double."
  []
  (:bpm @!timing))

(defn set-bpm!
  "Set the BPM (beats per minute).
   Parameters:
   - bpm: New BPM value (will be converted to double)"
  [bpm]
  (swap! !timing assoc :bpm (double bpm)))

(defn get-tap-times
  "Get the vector of tap-tempo timestamps.
   Returns: Vector of timestamps for BPM calculation."
  []
  (:tap-times @!timing))

(defn add-tap-time!
  "Add a tap-tempo timestamp for BPM calculation.
   Parameters:
   - timestamp: System timestamp of the tap"
  [timestamp]
  (swap! !timing update :tap-times conj timestamp))

(defn clear-tap-times!
  "Clear all tap-tempo timestamps."
  []
  (swap! !timing assoc :tap-times []))

(defn get-beat-position
  "Get the current position within the beat (0.0 to 1.0).
   Returns: Beat position as a double."
  []
  (:beat-position @!timing))

(defn update-beat-position!
  "Update the current beat position.
   Parameters:
   - position: New beat position (0.0 to 1.0)"
  [position]
  (swap! !timing assoc :beat-position position))

;; ============================================================================
;; Accessor Functions - Playback
;; ============================================================================

(defn playing?
  "Check if playback is currently active.
   Returns: true if playing, false otherwise."
  []
  (:playing? @!playback))

(defn get-trigger-time
  "Get the timestamp when the current animation was triggered.
   Returns: Timestamp in milliseconds."
  []
  (:trigger-time @!playback))

(defn trigger!
  "Set trigger-time to current timestamp. KEY function for retriggering animations."
  []
  (swap! !playback assoc :trigger-time (System/currentTimeMillis)))

(defn set-trigger-time!
  "Set trigger time to a specific timestamp.
   Parameters:
   - time-ms: Timestamp in milliseconds"
  [time-ms]
  (swap! !playback assoc :trigger-time time-ms))

(defn start-playback!
  "Start playback and reset trigger time."
  []
  (swap! !playback #(-> %
                        (assoc :playing? true)
                        (assoc :trigger-time (System/currentTimeMillis)))))

(defn stop-playback!
  "Stop playback and clear active cell."
  []
  (swap! !playback #(-> %
                        (assoc :playing? false)
                        (assoc :active-cell nil))))

(defn get-active-cell
  "Get the currently active (playing) cell.
   Returns: [col row] or nil if nothing is playing."
  []
  (:active-cell @!playback))

(defn set-active-cell!
  "Set the active cell.
   Parameters:
   - col: Column index (or nil to clear)
   - row: Row index (or nil to clear)"
  [col row]
  (swap! !playback assoc :active-cell (when (and col row) [col row])))

(defn get-active-cue
  "Get the currently active cue.
   Returns: Cue data or nil."
  []
  (:active-cue @!playback))

(defn set-active-cue!
  "Set the active cue.
   Parameters:
   - cue: Cue data to set"
  [cue]
  (swap! !playback assoc :active-cue cue))

(defn trigger-cell!
  "Trigger a grid cell - sets it as active and updates trigger time."
  [col row]
  (swap! !playback #(-> %
                        (assoc :active-cell [col row])
                        (assoc :playing? true)
                        (assoc :trigger-time (System/currentTimeMillis)))))

;; ============================================================================
;; Accessor Functions - Grid
;; ============================================================================

(defn get-grid-cells
  "Get all cells in the grid.
   Returns: Map of [col row] -> cell-data."
  []
  (:cells @!grid))

(defn get-cell
  "Get a cell from the grid by position.
   Parameters:
   - col: Column index
   - row: Row index
   Returns: Cell data map or nil if empty."
  [col row]
  (get-in @!grid [:cells [col row]]))

(defn set-cell!
  "Set cell data at a position.
   Parameters:
   - col: Column index
   - row: Row index
   - cell-data: Map of cell data (e.g., {:preset-id :circle})"
  [col row cell-data]
  (swap! !grid assoc-in [:cells [col row]] cell-data))

(defn set-cell-preset!
  "Set a preset for a cell.
   Parameters:
   - col: Column index
   - row: Row index
   - preset-id: Keyword identifying the preset"
  [col row preset-id]
  (swap! !grid assoc-in [:cells [col row]] {:preset-id preset-id}))

(defn clear-cell!
  "Clear a cell, removing its content.
   Parameters:
   - col: Column index
   - row: Row index"
  [col row]
  (swap! !grid update :cells dissoc [col row]))

(defn get-selected-cell
  "Get the currently selected cell.
   Returns: [col row] or nil if nothing selected."
  []
  (:selected-cell @!grid))

(defn set-selected-cell!
  "Set the selected cell.
   Parameters:
   - col: Column index (or nil to clear)
   - row: Row index (or nil to clear)"
  [col row]
  (swap! !grid assoc :selected-cell (when (and col row) [col row])))

(defn clear-selected-cell!
  "Clear the cell selection."
  []
  (swap! !grid assoc :selected-cell nil))

(defn get-grid-size
  "Get the grid dimensions.
   Returns: [cols rows]."
  []
  (:size @!grid))

(defn set-grid-size!
  "Set the grid dimensions.
   Parameters:
   - cols: Number of columns
   - rows: Number of rows"
  [cols rows]
  (swap! !grid assoc :size [cols rows]))

(defn move-cell!
  "Move a cell from one position to another.
   Parameters:
   - from-col, from-row: Source position
   - to-col, to-row: Destination position"
  [from-col from-row to-col to-row]
  (swap! !grid (fn [grid]
                 (let [cell-data (get-in grid [:cells [from-col from-row]])]
                   (if cell-data
                     (-> grid
                         (update :cells dissoc [from-col from-row])
                         (assoc-in [:cells [to-col to-row]] cell-data))
                     grid)))))

;; ============================================================================
;; Accessor Functions - Streaming
;; ============================================================================

(defn streaming?
  "Check if streaming is currently active.
   Returns: true if streaming, false otherwise."
  []
  (:running? @!streaming))

(defn get-streaming-engines
  "Get all active streaming engines.
   Returns: Map of projector-id -> engine."
  []
  (:engines @!streaming))

(defn add-streaming-engine!
  "Add a streaming engine for a projector.
   Parameters:
   - projector-id: Unique projector identifier
   - engine: Streaming engine instance"
  [projector-id engine]
  (swap! !streaming assoc-in [:engines projector-id] engine))

(defn remove-streaming-engine!
  "Remove a streaming engine.
   Parameters:
   - projector-id: Projector identifier to remove"
  [projector-id]
  (swap! !streaming update :engines dissoc projector-id))

;; ============================================================================
;; Accessor Functions - IDN
;; ============================================================================

(defn idn-connected?
  "Check if connected to an IDN target.
   Returns: true if connected, false otherwise."
  []
  (:connected? @!idn))

(defn get-idn-target
  "Get the current IDN target.
   Returns: Target hostname/IP or nil."
  []
  (:target @!idn))

(defn set-idn-connection!
  "Set the IDN connection state.
   Parameters:
   - connected?: Whether connected
   - target: Target hostname/IP
   - engine: Streaming engine instance"
  [connected? target engine]
  (reset! !idn {:connected? connected?
                :target target
                :streaming-engine engine}))

;; ============================================================================
;; Accessor Functions - Input
;; ============================================================================

(defn midi-enabled?
  "Check if MIDI input is enabled.
   Returns: true if enabled, false otherwise."
  []
  (get-in @!input [:midi :enabled]))

(defn enable-midi!
  "Enable or disable MIDI input.
   Parameters:
   - enabled: true to enable, false to disable"
  [enabled]
  (swap! !input assoc-in [:midi :enabled] enabled))

(defn osc-enabled?
  "Check if OSC input is enabled.
   Returns: true if enabled, false otherwise."
  []
  (get-in @!input [:osc :enabled]))

(defn enable-osc!
  "Enable or disable OSC input.
   Parameters:
   - enabled: true to enable, false to disable"
  [enabled]
  (swap! !input assoc-in [:osc :enabled] enabled))

;; ============================================================================
;; Accessor Functions - UI
;; ============================================================================

(defn get-selected-preset
  "Get the currently selected preset in the UI.
   Returns: Preset ID keyword or nil."
  []
  (:selected-preset @!ui))

(defn set-selected-preset!
  "Set the selected preset in the UI.
   Parameters:
   - preset-id: Preset ID keyword"
  [preset-id]
  (swap! !ui assoc :selected-preset preset-id))

(defn get-clipboard
  "Get the clipboard contents.
   Returns: Clipboard data or nil."
  []
  (:clipboard @!ui))

(defn set-clipboard!
  "Set the clipboard contents.
   Parameters:
   - data: Data to store in clipboard"
  [data]
  (swap! !ui assoc :clipboard data))

(defn get-ui-component
  "Get a reference to a UI component.
   Parameters:
   - component-key: Keyword identifying the component
     (:main-frame, :preview-panel, :grid-panel, :effects-panel, :status-bar, :toolbar)
   Returns: Component reference or nil."
  [component-key]
  (get-in @!ui [:components component-key]))

(defn set-ui-component!
  "Store a reference to a UI component.
   Parameters:
   - component-key: Keyword identifying the component
   - component: Component reference to store"
  [component-key component]
  (swap! !ui assoc-in [:components component-key] component))

(defn get-main-frame
  "Get the main application frame.
   Returns: JFrame instance or nil."
  []
  (get-ui-component :main-frame))

(defn set-main-frame!
  "Set the main application frame reference.
   Parameters:
   - frame: JFrame instance"
  [frame]
  (set-ui-component! :main-frame frame))

;; ============================================================================
;; Accessor Functions - Drag State
;; ============================================================================

(defn dragging?
  "Check if a drag operation is in progress.
   Returns: true if dragging, false otherwise."
  []
  (get-in @!ui [:drag :active?]))

(defn get-drag-data
  "Get the current drag operation data.
   Returns: Map with :active?, :source-type, :source-id, :source-key, :data."
  []
  (get-in @!ui [:drag]))

(defn start-drag!
  "Start a drag operation.
   Parameters:
   - source-type: :grid-cell, :effect-cell, :preset, etc.
   - source-id: identifier for the source grid
   - source-key: [col row] of source cell
   - data: the data being dragged"
  [source-type source-id source-key data]
  (swap! !ui assoc :drag {:active? true
                          :source-type source-type
                          :source-id source-id
                          :source-key source-key
                          :data data}))

(defn end-drag!
  "End the current drag operation."
  []
  (swap! !ui assoc :drag {:active? false
                          :source-type nil
                          :source-id nil
                          :source-key nil
                          :data nil}))

;; ============================================================================
;; Accessor Functions - Logging
;; ============================================================================

(defn logging-enabled?
  "Check if packet logging is enabled.
   Returns: true if enabled, false otherwise."
  []
  (:enabled? @!logging))

(defn set-logging-enabled!
  "Enable or disable packet logging.
   Parameters:
   - enabled: true to enable, false to disable"
  [enabled]
  (swap! !logging assoc :enabled? enabled))

(defn get-log-path
  "Get the path to the log file.
   Returns: File path string."
  []
  (:path @!logging))

;; ============================================================================
;; Accessor Functions - Effects (v2 - Chain-based)
;; ============================================================================
;; 
;; Effects grid cells now use a chain structure:
;; {:effects [{:effect-id :scale :params {...}} ...] :active true}
;; 
;; The :effects vector contains multiple effects applied in order.
;; The :active flag toggles the entire chain on/off.

;; --- Cell-Level Operations ---

(defn get-effect-cell
  "Get effect cell data at a specific grid position.
   Parameters:
   - col: Column index
   - row: Row index
   Returns: Cell data map {:effects [...] :active true} or nil."
  [col row]
  (get-in @!effects [:cells [col row]]))

(defn set-effect-cell!
  "Set entire cell data at a specific grid position.
   Parameters:
   - col: Column index
   - row: Row index
   - cell-data: Cell data map {:effects [...] :active true}"
  [col row cell-data]
  (swap! !effects assoc-in [:cells [col row]] cell-data))

(defn clear-effect-cell!
  "Clear the effect cell at a specific grid position.
   Parameters:
   - col: Column index
   - row: Row index"
  [col row]
  (swap! !effects update :cells dissoc [col row]))

(defn toggle-effect-cell-active!
  "Toggle the :active flag for an effect cell at a specific grid position.
   Parameters:
   - col: Column index
   - row: Row index
   Returns: New active state, or nil if no cell at position."
  [col row]
  (let [result (atom nil)]
    (swap! !effects
           (fn [effects]
             (if-let [cell-data (get-in effects [:cells [col row]])]
               (let [new-active (not (:active cell-data))]
                 (reset! result new-active)
                 (assoc-in effects [:cells [col row] :active] new-active))
               effects)))
    @result))

(defn ensure-effect-cell!
  "Ensure a cell exists at position, creating it if needed.
   Parameters:
   - col: Column index
   - row: Row index
   Returns: The cell data (existing or newly created)."
  [col row]
  (swap! !effects
         (fn [effects]
           (if (get-in effects [:cells [col row]])
             effects
             (assoc-in effects [:cells [col row]] {:effects [] :active true}))))
  (get-in @!effects [:cells [col row]]))

;; --- Effect Chain Operations (within a cell) ---

(defn add-effect-to-cell!
  "Append an effect to a cell's chain. Creates cell if it doesn't exist.
   Parameters:
   - col: Column index
   - row: Row index
   - effect: Effect map {:effect-id :keyword :params {...}}
   Returns: The updated cell data."
  [col row effect]
  (ensure-effect-cell! col row)
  (swap! !effects update-in [:cells [col row] :effects] conj effect)
  (get-in @!effects [:cells [col row]]))

(defn remove-effect-from-cell!
  "Remove an effect at a specific index from a cell's chain.
   Parameters:
   - col: Column index
   - row: Row index
   - idx: Index of effect to remove
   Returns: The updated cell data, or nil if cell doesn't exist."
  [col row idx]
  (swap! !effects
         (fn [effects]
           (if-let [cell (get-in effects [:cells [col row]])]
             (let [effects-vec (:effects cell)
                   new-effects (vec (concat (subvec effects-vec 0 idx)
                                            (subvec effects-vec (inc idx))))]
               (assoc-in effects [:cells [col row] :effects] new-effects))
             effects)))
  (get-in @!effects [:cells [col row]]))

(defn update-effect-in-cell!
  "Replace an effect at a specific index in a cell's chain.
   Parameters:
   - col: Column index
   - row: Row index
   - idx: Index of effect to replace
   - effect: New effect map {:effect-id :keyword :params {...}}
   Returns: The updated cell data, or nil if cell doesn't exist."
  [col row idx effect]
  (swap! !effects assoc-in [:cells [col row] :effects idx] effect)
  (get-in @!effects [:cells [col row]]))

(defn reorder-effects-in-cell!
  "Move an effect from one position to another in the chain.
   Parameters:
   - col: Column index
   - row: Row index
   - from-idx: Current index of effect
   - to-idx: Target index to move to
   Returns: The updated cell data, or nil if cell doesn't exist."
  [col row from-idx to-idx]
  (swap! !effects
         (fn [effects]
           (if-let [cell (get-in effects [:cells [col row]])]
             (let [effects-vec (:effects cell)
                   effect (nth effects-vec from-idx)
                   without (vec (concat (subvec effects-vec 0 from-idx)
                                        (subvec effects-vec (inc from-idx))))
                   reordered (vec (concat (subvec without 0 to-idx)
                                          [effect]
                                          (subvec without to-idx)))]
               (assoc-in effects [:cells [col row] :effects] reordered))
             effects)))
  (get-in @!effects [:cells [col row]]))

;; --- Param-Level Operations ---

(defn update-effect-param!
  "Update a single parameter in an effect within a cell's chain.
   Parameters:
   - col: Column index
   - row: Row index
   - effect-idx: Index of effect in chain
   - param-key: Parameter key to update
   - value: New value for the parameter
   Returns: The updated cell data."
  [col row effect-idx param-key value]
  (swap! !effects assoc-in [:cells [col row] :effects effect-idx :params param-key] value)
  (get-in @!effects [:cells [col row]]))

(defn get-effect-param
  "Get a parameter value from an effect within a cell's chain.
   Parameters:
   - col: Column index
   - row: Row index
   - effect-idx: Index of effect in chain
   - param-key: Parameter key to read
   Returns: Parameter value or nil."
  [col row effect-idx param-key]
  (get-in @!effects [:cells [col row] :effects effect-idx :params param-key]))

;; --- Query Operations ---

(defn get-all-active-effect-instances
  "Get all effect instances from active cells, flattened in row-major order.
   Returns: Vector of effect instances (maps with :effect-id and :params).
   Only includes effects from cells where :active is true."
  []
  (let [cells (:cells @!effects)
        sorted-keys (sort-by (fn [[col row]] [row col]) (keys cells))]
    (into []
          (comp
           (map #(get cells %))
           (filter :active)
           (mapcat :effects)
           (map #(select-keys % [:effect-id :params])))
          sorted-keys)))

(defn cell-has-effects?
  "Check if a cell has any effects in its chain.
   Parameters:
   - col: Column index
   - row: Row index
   Returns: true if cell exists and has at least one effect."
  [col row]
  (let [cell (get-in @!effects [:cells [col row]])]
    (boolean (and cell (seq (:effects cell))))))

(defn get-effect-count
  "Get the number of effects in a cell's chain.
   Parameters:
   - col: Column index
   - row: Row index
   Returns: Number of effects, or 0 if cell doesn't exist."
  [col row]
  (let [cell (get-in @!effects [:cells [col row]])]
    (count (:effects cell))))

(defn get-effects-cells
  "Get all cells from the effects grid.
   Returns: Map of [col row] -> cell-data."
  []
  (:cells @!effects))

;; ============================================================================
;; Accessor Functions - Config
;; ============================================================================

(defn get-config
  "Get the full config map."
  []
  @!config)

(defn get-grid-config
  "Get grid configuration {:cols :rows}."
  []
  (:grid @!config))

(defn get-window-config
  "Get window configuration {:width :height}."
  []
  (:window @!config))

(defn get-preview-config
  "Get preview panel configuration {:width :height}."
  []
  (:preview @!config))

(defn get-idn-config
  "Get IDN configuration {:host :port}."
  []
  (:idn @!config))

(defn update-config!
  "Update a config value at the given path.
   Parameters:
   - path: Vector path into config (e.g., [:grid :cols])
   - value: New value to set"
  [path value]
  (swap! !config assoc-in path value))

;; ============================================================================
;; Accessor Functions - Projectors
;; ============================================================================

(defn get-projectors
  "Get all projector configurations."
  []
  @!projectors)

(defn get-projector
  "Get a projector configuration by ID."
  [projector-id]
  (get @!projectors projector-id))

(defn add-projector!
  "Add or update a projector configuration."
  [projector-id config]
  (swap! !projectors assoc projector-id config))

(defn remove-projector!
  "Remove a projector configuration."
  [projector-id]
  (swap! !projectors dissoc projector-id))

;; ============================================================================
;; Accessor Functions - Zones
;; ============================================================================

(defn get-zones
  "Get all zone configurations."
  []
  @!zones)

(defn get-zone
  "Get a zone configuration by ID."
  [zone-id]
  (get @!zones zone-id))

(defn add-zone!
  "Add or update a zone configuration."
  [zone-id config]
  (swap! !zones assoc zone-id config))

(defn remove-zone!
  "Remove a zone configuration."
  [zone-id]
  (swap! !zones dissoc zone-id))

;; ============================================================================
;; Accessor Functions - Zone Groups
;; ============================================================================

(defn get-zone-groups
  "Get all zone group configurations."
  []
  @!zone-groups)

(defn get-zone-group
  "Get a zone group configuration by ID."
  [group-id]
  (get @!zone-groups group-id))

(defn add-zone-group!
  "Add or update a zone group configuration."
  [group-id config]
  (swap! !zone-groups assoc group-id config))

(defn remove-zone-group!
  "Remove a zone group configuration."
  [group-id]
  (swap! !zone-groups dissoc group-id))

;; ============================================================================
;; Accessor Functions - Cues
;; ============================================================================

(defn get-cues
  "Get all cue definitions."
  []
  @!cues)

(defn get-cue
  "Get a cue definition by ID."
  [cue-id]
  (get @!cues cue-id))

(defn add-cue!
  "Add or update a cue definition."
  [cue-id definition]
  (swap! !cues assoc cue-id definition))

(defn remove-cue!
  "Remove a cue definition."
  [cue-id]
  (swap! !cues dissoc cue-id))

;; ============================================================================
;; Accessor Functions - Cue Lists
;; ============================================================================

(defn get-cue-lists
  "Get all cue lists."
  []
  @!cue-lists)

(defn get-cue-list
  "Get a cue list by ID."
  [list-id]
  (get @!cue-lists list-id))

(defn add-cue-list!
  "Add or update a cue list."
  [list-id cue-list]
  (swap! !cue-lists assoc list-id cue-list))

(defn remove-cue-list!
  "Remove a cue list."
  [list-id]
  (swap! !cue-lists dissoc list-id))

;; ============================================================================
;; Accessor Functions - Effect Registry
;; ============================================================================

(defn get-effect-registry
  "Get all registered effects."
  []
  @!effect-registry)

(defn get-registered-effect
  "Get a registered effect by ID."
  [effect-id]
  (get @!effect-registry effect-id))

(defn register-effect!
  "Register an effect definition."
  [effect-id definition]
  (swap! !effect-registry assoc effect-id definition))

(defn unregister-effect!
  "Unregister an effect."
  [effect-id]
  (swap! !effect-registry dissoc effect-id))

;; ============================================================================
;; Accessor Functions - Project
;; ============================================================================

(defn get-project-folder
  "Get the current project folder path.
   Returns: Folder path string or nil if no project is open."
  []
  (:current-folder @!project))

(defn set-project-folder!
  "Set the current project folder path.
   Parameters:
   - folder-path: Path to project folder, or nil to clear"
  [folder-path]
  (swap! !project assoc :current-folder folder-path))

(defn project-dirty?
  "Check if the project has unsaved changes.
   Returns: true if dirty, false otherwise."
  []
  (:dirty? @!project))

(defn mark-project-dirty!
  "Mark the project as having unsaved changes."
  []
  (swap! !project assoc :dirty? true))

(defn mark-project-clean!
  "Mark the project as saved (no unsaved changes).
   Also updates the last-saved timestamp."
  []
  (swap! !project #(-> %
                       (assoc :dirty? false)
                       (assoc :last-saved (System/currentTimeMillis)))))

(defn has-current-project?
  "Check if a project folder is currently set.
   Returns: true if a project is open, false otherwise."
  []
  (some? (:current-folder @!project)))

(defn get-project-last-saved
  "Get the timestamp when the project was last saved.
   Returns: Timestamp in milliseconds or nil."
  []
  (:last-saved @!project))
