(ns laser-show.state.domains
  "State domain definitions for the laser show application.
   
   This file uses the defstate macro to declaratively define all state domains.
   Each domain generates:
   - Initial state map (e.g., timing-initial)
   - Registration in the domain registry
   
   After loading this namespace, call (build-initial-state) to get the
   complete initial state map for the application.
   
   State mutations use low-level primitives from laser-show.state.core:
   - assoc-in-state! for setting values
   - update-in-state! for updating values
   - swap-state! for complex updates
   
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


#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
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
                  :doc "Quantization mode (:beat :bar :none)"}
   
   ;; Global clock for BPM visualization
   :global-clock {:default {:accumulated-beats 0.0
                            :accumulated-ms 0.0
                            :last-frame-time 0}
                  :doc "Global clock for BPM visualization, independent of cue triggers. Only reset by retrigger button."}})

(defstate playback
  "Playback control state for animation triggering.
   
   MULTI-CUE ARCHITECTURE:
   - active-cues: Map of [col row] -> per-cue timing state
   - Each cue has independent accumulated-beats, phase-offset, etc.
   - Global clock is in [:timing :global-clock] for BPM visualization"
  {:playing? {:default false
              :doc "Whether playback is active (any cues playing)"}
   :active-cue {:default nil
                :doc "Currently active cue data (legacy, may remove)"}
   :cue-queue {:default []
               :doc "Queue of pending cues for quantized triggering"}
   
   ;; Multi-cue timing state
   :active-cues {:default {}
                 :doc "Map of [col row] -> cue timing state. Each cue has:
                       {:trigger-time long
                        :accumulated-beats double
                        :accumulated-ms double
                        :phase-offset double
                        :phase-offset-target double
                        :last-frame-time long}"}
   
   ;; Shared timing settings
   :resync-rate {:default 4.0
                 :doc "Beats to reach ~63% of target phase correction (shared across all cues)"}})

(defstate grid
  "Cue grid state - the main trigger interface.
   
   NOTE: All cue content is stored in [:chains :cue-chains [col row] :items]
   This domain only stores grid metadata (selection, size).
   See also: defstate chains for cue-chain item storage."
  {:selected-cell {:default nil
                   :doc "[col row] of selected cell for editing"}
   :size {:default [default-grid-cols default-grid-rows]
          :doc "[cols rows] grid dimensions"}})

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
   :dialogs {:default {:projector-config {:open? false}
                         :cue-chain-editor {:open? false
                                            :col nil
                                            :row nil
                                            :clipboard nil
                                            :active-preset-tab :geometric
                                            :selected-effect-id nil
                                            :item-effects-ui {}}
                         :effect-chain-editor {:open? false
                                               :col nil
                                               :row nil
                                               :active-bank-tab :shape}
                         :add-projector-manual {:open? false}
                         :add-virtual-projector {:open? false}
                         :zone-group-editor {:open? false
                                             :editing? false
                                             :group-id nil
                                             :name ""
                                             :description ""
                                             :color "#808080"}
                         :about {:open? false}
                         :settings {:open? false}}
               :doc "dialog visibility and data states (fields alongside :open?, no nested :data key)"}
   :preview {:default {:frame nil
                       :last-render-time 0}
             :doc "preview panel state"}
   :preview-zone-selector-open {:default nil
                                :doc "Cell index whose zone selector popup is open, or nil if none"}
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

(defstate list-ui
  "UI state for hierarchical list components (selection, drag-drop, rename).
   
   This domain IS the map of component-id -> component-state.
   Component IDs are typically:
   - [:effect-chain col row] for effect chain editor
   - [:cue-chain col row] for cue chain editor
   - [:item-effects col row item-path] for item effects in cue chain
   
   Each component instance maintains its own selection, drag, and rename state.
   This state is separate from the actual item data (which lives in :chains domain).
   
   Component state structure:
   {:selected-ids #{} :last-selected-id nil :dragging-ids nil
    :drop-target-id nil :drop-position nil :renaming-id nil}"
  {})

(defstate project
  "Project file management state."
  {:current-file {:default nil
                  :doc "path to current project zip file"}
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
   :preview {:default {:width 400
                       :height 400
                       :zone-group-filter :all
                       :grid-layout [2 2]
                       :grid-cells [{:zone-group-id nil}    ;; nil = master view (show all)
                                    {:zone-group-id :left}
                                    {:zone-group-id :right}
                                    {:zone-group-id :center}]
                       :show-labels? true}
             :doc "Preview grid configuration. grid-layout is [cols rows], grid-cells is vector of cell configs with :zone-group-id (nil = show all/master view)"}
   :idn {:default {:host nil :port 7255}
         :doc "default IDN connection settings"}
   :osc {:default {:enabled? false :port default-osc-port}
         :doc "OSC server settings"}
   :midi {:default {:enabled? false :device nil}
          :doc "MIDI input settings"}
   :cue {:default {:trigger-mode :default}
         :doc "Cue playback settings. :trigger-mode can be :default (use per-cue setting), :toggle (click ON/OFF), or :retrigger (always restart)"}})

(defstate projectors
  "Projector configurations for color calibration and routing.
   
   SIMPLIFIED ARCHITECTURE (v2):
   Projectors are directly assigned to zone groups - no intermediate 'zone' abstraction.
   
   This domain IS the map of projector-id -> projector config.
   UI selection state is stored in :projector-ui domain.
   
   NOTE: Corner-pin geometry and color curves are stored as effects in the projector
   effect chain at [:chains :projector-effects projector-id :items]. They are applied
   as normal effects during frame rendering in multi_engine.clj.
   
   Each projector entry contains:
   - :name - User-friendly name
   - :host - IP address
   - :port - IDN port (default 7255)
   - :unit-id - Hardware unit ID from discovery
   - :enabled? - Whether to send output to this projector
   - :output-config - Bit depth settings
   - :scan-rate - Points per second (pps) for this projector
   - :zone-groups - Vector of zone group IDs this projector belongs to (e.g., [:all :left])
   - :tags - Set of optional tags (:graphics, :crowd-scanning) for categorization
   - :status - Runtime connection status (not persisted)"
  {})

(defstate virtual-projectors
  "Virtual projector configurations - alternate outputs for physical projectors.
   
   This domain IS the map of virtual-projector-id (UUID) -> virtual projector config.
   Virtual projectors inherit color curves from their parent projector.
   
   NOTE: Corner-pin geometry is stored as an effect in the parent projector's
   effect chain at [:chains :projector-effects parent-projector-id :items].
   
   Each virtual projector entry contains:
   - :name - User-friendly name
   - :parent-projector-id - ID of the parent physical projector
   - :zone-groups - Vector of zone group IDs
   - :tags - Set of optional tags
   - :enabled? - Whether to send output to this virtual projector"
  {})

(defstate projector-ui
  "UI state for projector management.
   
   Stores transient UI state separately from persisted projector data."
  {:active-projector {:default nil
                      :doc "Currently selected projector ID for editing"}
   :active-virtual-projector {:default nil
                              :doc "Currently selected virtual projector ID for editing"}
   :calibrating-projector-id {:default nil
                              :doc "ID of projector currently in calibration mode, or nil"}
   :calibration-brightness {:default 0.1
                            :doc "Brightness for calibration test pattern (0.0-1.0)"}
   :discovered-devices {:default []
                        :doc "List of devices from last network scan"}
   :scanning? {:default false
               :doc "Whether a network scan is in progress"}
   :broadcast-address {:default "255.255.255.255"
                       :doc "Broadcast address for device discovery"}
   :expanded-devices {:default #{}
                      :doc "Set of device addresses expanded in discovery panel"}})


(defstate zone-groups
  "Zone group definitions - routing targets for cues.
   
   SIMPLIFIED ARCHITECTURE (v2):
   Projectors and virtual projectors are directly assigned to zone groups.
   No intermediate 'zone' abstraction.
   
   Users assign projectors to zone groups, then target cues to zone groups.
   Zone groups have metadata: name, description, color for UI.
   
   This domain IS the map of zone-group-id -> group config.
   UI selection state is stored in :zone-group-ui domain.
   
   Default zone groups are provided:
   - :all - All projectors
   - :left - Left side projectors
   - :right - Right side projectors
   - :center - Center projectors
   - :graphics - Graphics-specific projectors (for smaller scan areas)
   - :crowd - Audience scanning projectors"
  {:all {:default {:id :all
                   :name "All"
                   :description "All projectors"
                   :color "#808080"}
         :doc "All projectors group"}
   :left {:default {:id :left
                    :name "Left"
                    :description "Left side projectors"
                    :color "#4A90D9"}
          :doc "Left side projectors group"}
   :right {:default {:id :right
                     :name "Right"
                     :description "Right side projectors"
                     :color "#D94A4A"}
           :doc "Right side projectors group"}
   :center {:default {:id :center
                      :name "Center"
                      :description "Center projectors"
                      :color "#4AD94A"}
            :doc "Center projectors group"}
   :graphics {:default {:id :graphics
                        :name "Graphics"
                        :description "Graphics-specific projectors"
                        :color "#9B59B6"}
              :doc "Graphics-specific projectors group"}
   :crowd {:default {:id :crowd
                     :name "Crowd Scanning"
                     :description "Audience scanning projectors"
                     :color "#E67E22"}
           :doc "Audience scanning projectors group"}})

(defstate zone-group-ui
  "UI state for zone group selection.
   
   Stores transient UI selection state separately from persisted zone group data."
  {:selected-group {:default nil
                    :doc "Currently selected zone group ID for editing"}})

;; Unified Chain Storage Domain


(defstate chains
  "Unified chain storage for all hierarchical lists.
   
   SIMPLIFIED ARCHITECTURE (v2):
   Three chain types with consistent structure:
   - :effect-chains - Effect modifiers for grid cells
   - :cue-chains - Cue presets/groups for grid cells
   - :projector-effects - Calibration effects for projectors (RGB curves, corner-pin)
   
   Corner-pin geometry IS stored as an effect in [:chains :projector-effects projector-id :items].
   It is applied as a normal effect during frame rendering in multi_engine.clj.
   
   All chains use the same structure: {:items [...] :active? bool (optional)}
   This enables generic handlers and simplified subscriptions."
  {:effect-chains {:default {}
                   :doc "Map of [col row] -> {:items [...] :active? bool}"}
   :cue-chains {:default {}
                :doc "Map of [col row] -> {:items [...]}. Starter content is applied via laser-show.state.templates/apply-starter-cue-chains"}
   :projector-effects {:default {}
                       :doc "Map of projector-id -> {:items [...]} - RGB curves and corner-pin for calibration"}})


;; Backend State Domain


(defstate backend
  "Backend services state - IDN, streaming, input, logging."
  {:idn {:default {:connected? false
                   :connecting? false
                   :error nil}
         :doc "IDN connection state"}
   :streaming {:default {:engines {}
                         :running? false
                         :connected-targets #{}
                         :frame-stats {}
                         :multi-engine-state nil
                         :current-frame nil}
               :doc "Streaming engine state for IDN output"}
   :input {:default {:midi {:enabled? true
                            :devices {}                ; Map of device-name -> device instance
                            :connected-devices #{}     ; Set of connected device names
                            :handlers {}               ; Map of device-name -> handler fn
                            :channel-filter nil        ; nil = all channels, or set of channel numbers (0-15)
                            :learn-mode nil            ; Promise when learning, nil otherwise
                            :note-mappings {}          ; Optional note remapping
                            :cc-mappings {}}           ; Optional CC remapping
                     :osc {:enabled? false
                           :server nil                 ; OSC server instance
                           :server-running? false
                           :port 9000
                           :address-mappings {}        ; Map of OSC address -> event config
                           :handlers {}                ; Map of handler-id -> {:pattern :handler}
                           :learn-mode nil}            ; Promise when learning, nil otherwise
                     :keyboard {:enabled? true
                                :attached-components #{}}
                     :router {:handlers {}
                              :event-log []
                              :enabled? true}}
           :doc "Input handling state for MIDI, OSC, and keyboard"}
   :logging {:default {:enabled? false
                       :file nil
                       :path default-log-path}
             :doc "Packet logging state"}
   :link {:default {:carabiner-connected? false    ;; Carabiner daemon connected (auto on startup)
                    :link-enabled? false           ;; Link sync enabled (user toggles via button)
                    :sync-enabled? false           ;; Whether to apply Link BPM to app
                    :beat-sync? true               ;; Whether to sync downbeat alignment
                    :auto-connect? true            ;; Auto-connect Carabiner on startup
                    :latency-ms 0                  ;; Latency compensation
                    :link-bpm nil                  ;; Current Link session BPM
                    :link-peers 0}                 ;; Number of Link peers
          :doc "Ableton Link state. Carabiner auto-connects on startup. Button toggles Link sync mode."}})


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
