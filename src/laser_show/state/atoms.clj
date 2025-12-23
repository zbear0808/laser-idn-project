(ns laser-show.state.atoms
  "Core atom definitions for runtime state.
   
   This file defines all runtime state atoms used by the application.
   These atoms are the single source of truth for volatile runtime state
   that is not persisted between sessions.
   
   Usage:
   - Import this namespace to access raw atoms directly
   - For higher-level operations, use laser-show.state.dynamic")

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
  "Initial state for active effects.
   
   Keys:
   - :active-effects - Map of [col row] -> effect-data for effects grid"
  {:active-effects {}})

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
  (reset-effects!))
