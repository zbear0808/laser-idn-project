(ns laser-show.state.dynamic
  "Dynamic runtime state accessor functions for the laser show application.
   
   This namespace provides accessor functions for all runtime state.
   The actual atom definitions are in laser-show.state.atoms.
   
   This is the public API for runtime state access."
  (:require [laser-show.state.atoms :as atoms]))

;; ============================================================================
;; Re-export Constants for Backward Compatibility
;; ============================================================================

(def default-bpm atoms/default-bpm)
(def default-osc-port atoms/default-osc-port)
(def default-window-width atoms/default-window-width)
(def default-window-height atoms/default-window-height)
(def default-grid-cols atoms/default-grid-cols)
(def default-grid-rows atoms/default-grid-rows)
(def default-log-path atoms/default-log-path)

;; ============================================================================
;; Re-export Atoms for Direct Access (when needed by watchers, etc.)
;; ============================================================================

(def !timing atoms/!timing)
(def !playback atoms/!playback)
(def !grid atoms/!grid)
(def !streaming atoms/!streaming)
(def !idn atoms/!idn)
(def !input atoms/!input)
(def !ui atoms/!ui)
(def !logging atoms/!logging)
(def !effects atoms/!effects)

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

(defn set-trigger-time! [time-ms]
  (swap! !playback assoc :trigger-time time-ms))

(defn start-playback!
  "Start playback and reset trigger time."
  []
  (swap! !playback #(-> %
                        (assoc :playing? true)
                        (assoc :trigger-time (System/currentTimeMillis)))))

(defn stop-playback! []
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
;; Accessor Functions - Effects
;; ============================================================================

(defn get-active-effects
  "Get all active effects from the effects grid.
   Returns: Map of [col row] -> effect-data."
  []
  (:active-effects @!effects))

(defn get-effect-at
  "Get the effect at a specific grid position.
   Parameters:
   - col: Column index
   - row: Row index
   Returns: Effect data map or nil."
  [col row]
  (get-in @!effects [:active-effects [col row]]))

(defn set-effect-at!
  "Set an effect at a specific grid position.
   Parameters:
   - col: Column index
   - row: Row index
   - effect-data: Effect configuration map"
  [col row effect-data]
  (swap! !effects assoc-in [:active-effects [col row]] effect-data))

(defn clear-effect-at!
  "Clear the effect at a specific grid position.
   Parameters:
   - col: Column index
   - row: Row index"
  [col row]
  (swap! !effects update :active-effects dissoc [col row]))

;; ============================================================================
;; State Reset (for testing)
;; ============================================================================

(defn reset-all-dynamic-state!
  "Reset all dynamic state to initial values. USE WITH CAUTION - mainly for testing."
  []
  (reset! !timing {:bpm default-bpm
                   :tap-times []
                   :beat-position 0.0
                   :bar-position 0.0
                   :last-beat-time 0
                   :beats-elapsed 0
                   :quantization :beat})
  (reset! !playback {:playing? false
                     :trigger-time 0
                     :active-cell nil
                     :active-cue nil
                     :cue-queue []})
  (reset! !grid {:cells {[0 0] {:preset-id :circle}
                         [1 0] {:preset-id :spinning-square}
                         [2 0] {:preset-id :triangle}
                         [3 0] {:preset-id :star}
                         [4 0] {:preset-id :spiral}
                         [5 0] {:preset-id :wave}
                         [6 0] {:preset-id :beam-fan}
                         [7 0] {:preset-id :rainbow-circle}}
                 :selected-cell nil
                 :size [8 4]})
  (reset! !streaming {:engines {}
                      :running? false
                      :connected-targets #{}
                      :frame-stats {}
                      :multi-engine-state nil})
  (reset! !idn {:connected? false
                :target nil
                :streaming-engine nil})
  (reset! !input {:midi {:enabled true
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
  (reset! !ui {:selected-preset nil
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
  (reset! !logging {:enabled? false
                    :file nil
                    :path default-log-path})
  (reset! !effects {:active-effects {}}))
