(ns laser-show.state.domains
  "State domain definitions for the laser show application.
   
   This file uses the defstate macro to declaratively define all state domains.
   Each domain generates:
   - Initial state map
   - Accessor functions (set-*!, update-*!)
   - Registration in the domain registry
   
   After loading this namespace, call (build-initial-state) to get the
   complete initial state map for the application.
   
   NOTE: This is the single source of truth for ALL application state."
  (:require [laser-show.state.core :refer [defstate build-initial-state-from-domains]]))


;; Default Constants


(def default-bpm 120.0)
(def default-osc-port 8000)
(def default-window-width 1200)
(def default-window-height 800)
(def default-grid-cols 8)
(def default-grid-rows 4)
(def default-log-path "idn-packets.log")


;; Core UI State Domains


(defstate timing
  "Timing and BPM management for animation synchronization."
  {:bpm {:default default-bpm
         :doc "Beats per minute"}
   :tap-times {:default []
               :doc "Vector of tap-tempo timestamps for BPM calculation"}
   :beat-position {:default 0.0
                   :doc "Position within current beat (0.0-1.0)"}
   :bar-position {:default 0.0
                  :doc "Position within current bar (0.0-1.0)"}
   :last-beat-time {:default 0
                    :doc "Timestamp of last beat"}
   :beats-elapsed {:default 0
                   :doc "Total beats elapsed since start"}
   :quantization {:default :beat
                  :doc "Quantization mode (:beat :bar :none)"}})

(defstate playback
  "Playback control state for animation triggering."
  {:playing? {:default false
              :doc "Whether playback is active"}
   :trigger-time {:default 0
                  :doc "Timestamp when current animation was triggered"}
   :active-cell {:default nil
                 :doc "[col row] of currently playing grid cell"}
   :active-cue {:default nil
                :doc "Currently active cue data"}
   :cue-queue {:default []
               :doc "Queue of pending cues"}})

(defstate grid
  "Cue grid state - the main trigger interface.
   
   NOTE: Cue chain items are stored in [:chains :cue-chains [col row] :items]
   This domain stores grid metadata (selection, size) only.
   See also: defstate chains for cue-chain item storage."
  {:cells {:default {}
           :doc "Map of [col row] -> cell metadata (not chain items)"}
   :selected-cell {:default nil
                   :doc "[col row] of selected cell for editing"}
   :size {:default [default-grid-cols default-grid-rows]
          :doc "[cols rows] grid dimensions"}})

(defstate cue-chain-editor
  "State for the cue chain editor dialog.
   Tracks selection, clipboard, and editing state."
  {:cell {:default nil
          :doc "[col row] of cell being edited, nil if editor closed"}
   :selected-paths {:default #{}
                    :doc "Set of paths to selected items (presets/groups)"}
   :last-selected-path {:default nil
                        :doc "Last clicked path for shift+click range selection"}
   :clipboard {:default nil
               :doc "Copied items: {:items [...] :copied-at timestamp}"}
   :editing-group-name {:default nil
                        :doc "Path to group currently being renamed, nil if not editing"}
   :active-preset-tab {:default :geometric
                       :doc "Currently active tab in preset bank"}
   :selected-effect-path {:default nil
                          :doc "Path to selected effect in current preset's effect chain"}})

(defstate ui
  "UI interaction state including window, preview, and component references."
  {:selected-preset {:default nil
                     :doc "currently selected preset in browser"}
   :clipboard {:default nil
               :doc "clipboard data for copy/paste"}
   :active-tab {:default :grid
                :doc "currently active tab (:grid :effects :projectors :settings)"}
   :drag {:default {:active? false
                    :source-type nil
                    :source-id nil
                    :source-key nil
                    :data nil}
          :doc "current drag operation state"}
   :dialogs {:default {:zone-editor {:open? false :zone-id nil}
                       :projector-config {:open? false}
                       :cue-chain-editor {:open? false :cell nil}
                       :settings {:open? false}}
             :doc "dialog visibility and data states"}
   :preview {:default {:frame nil
                       :last-render-time 0}
             :doc "preview panel state"}
   :window {:default {:width default-window-width
                      :height default-window-height}
            :doc "window dimensions"}
   :components {:default {:main-frame nil
                          :preview-panel nil
                          :grid-panel nil
                          :effects-panel nil
                          :status-bar nil
                          :toolbar nil}
                :doc "references to UI components for programmatic access"}})

(defstate project
  "Project file management state."
  {:current-folder {:default nil
                    :doc "path to current project folder"}
   :dirty? {:default false
            :doc "whether changes have been made since last save"}
   :last-saved {:default nil
                :doc "timestamp of last save"}})


;; Configuration Domains (persisted to disk)


(defstate config
  "Application configuration settings."
  {:grid {:default {:cols default-grid-cols :rows default-grid-rows}
          :doc "grid dimensions config"}
   :window {:default {:width default-window-width :height default-window-height}
            :doc "window dimensions"}
   :preview {:default {:width 400 :height 400}
             :doc "preview panel dimensions"}
   :idn {:default {:host nil :port 7255}
         :doc "default IDN connection settings"}
   :osc {:default {:enabled false :port default-osc-port}
         :doc "OSC server settings"}
   :midi {:default {:enabled false :device nil}
          :doc "MIDI input settings"}})

(defstate projectors
  "Projector configurations for color calibration and safety zoning.
   
   NOTE: Projector effect chains are stored in [:chains :projector-effects projector-id :items]
   
   Each projector entry contains:
   - :name - User-friendly name
   - :host - IP address
   - :port - IDN port (default 7255)
   - :unit-id - Hardware unit ID from discovery
   - :enabled? - Whether to send output to this projector
   - :output-config - Bit depth settings
   - :status - Runtime connection status (not persisted)
   
   See also: defstate chains for effect chain storage."
  {:items {:default {}
           :doc "Map of projector-id -> projector configuration (no :effects, see :chains)"}
   :active-projector {:default nil
                      :doc "Currently selected projector ID for editing"}
   :selected-effect-idx {:default nil
                         :doc "Index of currently selected effect in projector's chain"}
   :test-pattern-mode {:default nil
                       :doc "Active test pattern: nil, :grid, or :corners"}
   :discovered-devices {:default []
                        :doc "List of devices from last network scan"}
   :scanning? {:default false
               :doc "Whether a network scan is in progress"}
   :broadcast-address {:default "255.255.255.255"
                       :doc "Broadcast address for device discovery"}})

(defstate zones
  "Zone configurations for spatial mapping."
  {:items {:default {}
           :doc "map of zone-id -> {:corners [...] :transform {...} ...}"}})

(defstate zone-groups
  "Zone group configurations for multi-zone output."
  {:items {:default {}
           :doc "map of group-id -> {:name :zone-ids [...]}"}})

(defstate cues
  "Cue definitions."
  {:items {:default {}
           :doc "map of cue-id -> {:name :preset-id :animation ...}"}})

(defstate cue-lists
  "Cue list definitions."
  {:items {:default {}
           :doc "map of list-id -> {:name :cues [...]}"}})

(defstate effect-registry
  "Effect type registry."
  {:items {:default {}
           :doc "map of effect-id -> effect-definition"}})


;; Unified Chain Storage Domain


(defstate chains
  "Unified chain storage for all hierarchical lists.
   
   Three chain types with consistent structure:
   - :effect-chains - Effect modifiers for grid cells
   - :cue-chains - Cue presets/groups for grid cells
   - :projector-effects - Output effects for projectors
   
   All chains use the same structure: {:items [...] :active? bool (optional)}
   This enables generic handlers and simplified subscriptions."
  {:effect-chains {:default {}
                   :doc "Map of [col row] -> {:items [...] :active? bool}"}
   :cue-chains {:default {;; Row 0: Basic shapes - initial default cues
                          [0 0] {:items [{:type :preset
                                          :id #uuid "00000000-0000-0000-0000-000000000001"
                                          :preset-id :circle
                                          :params {}
                                          :effects []
                                          :enabled? true}]}
                          [1 0] {:items [{:type :preset
                                          :id #uuid "00000000-0000-0000-0000-000000000002"
                                          :preset-id :square
                                          :params {}
                                          :effects []
                                          :enabled? true}]}
                          [2 0] {:items [{:type :preset
                                          :id #uuid "00000000-0000-0000-0000-000000000003"
                                          :preset-id :triangle
                                          :params {}
                                          :effects []
                                          :enabled? true}]}
                          [3 0] {:items [{:type :preset
                                          :id #uuid "00000000-0000-0000-0000-000000000004"
                                          :preset-id :star
                                          :params {}
                                          :effects []
                                          :enabled? true}]}
                          [4 0] {:items [{:type :preset
                                          :id #uuid "00000000-0000-0000-0000-000000000005"
                                          :preset-id :spiral
                                          :params {}
                                          :effects []
                                          :enabled? true}]}
                          [5 0] {:items [{:type :preset
                                          :id #uuid "00000000-0000-0000-0000-000000000006"
                                          :preset-id :wave
                                          :params {}
                                          :effects []
                                          :enabled? true}]}
                          [6 0] {:items [{:type :preset
                                          :id #uuid "00000000-0000-0000-0000-000000000007"
                                          :preset-id :beam-fan
                                          :params {}
                                          :effects []
                                          :enabled? true}]}
                          [7 0] {:items [{:type :preset
                                          :id #uuid "00000000-0000-0000-0000-000000000008"
                                          :preset-id :rainbow-circle
                                          :params {}
                                          :effects []
                                          :enabled? true}]}}
                :doc "Map of [col row] -> {:items [...]}"}
   :projector-effects {:default {}
                       :doc "Map of projector-id -> {:items [...]}"}})


;; Backend State Domain


(defstate backend
  "Backend services state - IDN, streaming, input, logging."
  {:idn {:default {:connected? false
                   :connecting? false
                   :target nil
                   :streaming-engine nil
                   :error nil}
         :doc "IDN connection state"}
   :streaming {:default {:engines {}
                         :running? false
                         :connected-targets #{}
                         :frame-stats {}
                         :multi-engine-state nil
                         :current-frame nil}
               :doc "Streaming engine state for IDN output"}
   :input {:default {:midi {:enabled true
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
                              :enabled true}}
           :doc "Input handling state for MIDI, OSC, and keyboard"}
   :logging {:default {:enabled? false
                       :file nil
                       :path default-log-path}
             :doc "Packet logging state"}})


;; State Builder


(defn build-initial-state
  "Build the complete initial state map from all registered domains.
   Call this after all defstate forms have been evaluated.
   
   Also includes :styles for CSS hot-reload support. Styles are stored
   in context for reactivity but not as a domain (they're view configuration,
   not application domain state)."
  []
  (merge
    (build-initial-state-from-domains)
    ;; Styles: stored in context for hot-reload reactivity
    ;; Initialized to nil, set at app startup with actual CSS URLs
    {:styles {:menu-theme nil}}))
