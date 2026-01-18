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
               :doc "Queue of pending cues"}
   
   ;; Beat accumulation fields
   :accumulated-beats {:default 0.0
                       :doc "Running total of beats since cue trigger"}
   :accumulated-ms {:default 0.0
                    :doc "Running total of ms since cue trigger"}
   :last-frame-time {:default 0
                     :doc "Timestamp of last frame for delta calculation"}
   
   ;; Phase resync fields
   :phase-offset {:default 0.0
                  :doc "Current phase correction offset"}
   :phase-offset-target {:default 0.0
                         :doc "Target phase offset from tap resync"}
   :resync-rate {:default 4.0
                 :doc "Beats to reach ~63% of target phase correction"}})

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
   :dialogs {:default {:zone-editor {:open? false :zone-id nil}
                        :projector-config {:open? false}
                        :cue-chain-editor {:open? false
                                           :data {:col nil
                                                  :row nil
                                                  :selected-paths #{}
                                                  :last-selected-path nil
                                                  :clipboard nil
                                                  :active-preset-tab :geometric
                                                  :selected-effect-id nil
                                                  :item-effects-ui {}}}
                        :effect-chain-editor {:open? false :data nil}
                        :add-projector-manual {:open? false :data nil}
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

(defstate list-ui
  "UI state for hierarchical list components (selection, drag-drop, rename).
   
   State is keyed by component-id, which is typically:
   - [:effect-chain col row] for effect chain editor
   - [:cue-chain col row] for cue chain editor
   - [:item-effects col row item-path] for item effects in cue chain
   
   Each component instance maintains its own selection, drag, and rename state.
   This state is separate from the actual item data (which lives in :chains domain)."
  {:components {:default {}
                :doc "Map of component-id -> {:selected-ids #{} :last-selected-id nil :dragging-ids nil :drop-target-id nil :drop-position nil :renaming-id nil}"}})

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
   - :zone-ids - Vector of zone UUIDs created for this projector (3 zones: default, graphics, crowd)
   - :status - Runtime connection status (not persisted)
   
   See also: defstate chains for effect chain storage.
   See also: defstate zones for zone definitions."
  {:items {:default {}
           :doc "Map of projector-id -> projector configuration (includes :zone-ids refs)"}
   :active-projector {:default nil
                      :doc "Currently selected projector ID for editing"}
   :test-pattern-mode {:default nil
                       :doc "Active test pattern: nil, :grid, or :corners"}
   :discovered-devices {:default []
                        :doc "List of devices from last network scan"}
   :scanning? {:default false
               :doc "Whether a network scan is in progress"}
   :broadcast-address {:default "255.255.255.255"
                       :doc "Broadcast address for device discovery"}})

(defstate zones
  "Zone definitions - output destinations with effect chains.
   
   Each projector automatically creates 3 zones: default, graphics, crowd-scanning.
   Zones are first-class entities referenced by UUID.
   
   Zone effects are stored in [:chains :zone-effects zone-id :items] just like
   projector effects. This allows using the same UI and effect system for zones.
   
   Recommended zone effects: corner-pin, flip, scale, offset, rotation
   (These are the same calibration effects available for projectors)
   
   Zone structure:
   {:id zone-id                          ; UUID
    :name \"Projector 1 - Default\"       ; User-friendly name
    :projector-id projector-id           ; Parent projector keyword
    :type :default                       ; :default, :graphics, or :crowd-scanning
    :enabled? true                       ; Whether this zone is active
    :zone-groups [:all :left]}           ; Zone groups this zone belongs to (ordered by priority)
   
   NOTE: Zone effects (geometry, etc.) are stored in :chains :zone-effects, NOT inline.
   See also: defstate chains for zone effect chain storage."
  {:items {:default {}
           :doc "Map of zone-id (UUID) -> zone configuration"}
   :selected-zone {:default nil
                   :doc "Currently selected zone ID for editing"}})

(defstate zone-groups
  "Zone group definitions - category tags for organizing zones.
   
   Users assign zones to zone groups, then target cues to zone groups.
   Zone groups have metadata: name, description, color for UI.
   
   Default zone groups are provided:
   - :all - All zones
   - :left - Left side zones
   - :right - Right side zones
   - :center - Center zones
   - :graphics - Graphics-specific zones
   - :crowd - Audience scanning zones"
  {:items {:default {:all {:id :all
                           :name "All"
                           :description "All zones"
                           :color "#808080"}
                     :left {:id :left
                            :name "Left"
                            :description "Left side zones"
                            :color "#4A90D9"}
                     :right {:id :right
                             :name "Right"
                             :description "Right side zones"
                             :color "#D94A4A"}
                     :center {:id :center
                              :name "Center"
                              :description "Center zones"
                              :color "#4AD94A"}
                     :graphics {:id :graphics
                                :name "Graphics"
                                :description "Graphics-specific zones"
                                :color "#9B59B6"}
                     :crowd {:id :crowd
                             :name "Crowd Scanning"
                             :description "Audience scanning zones"
                             :color "#E67E22"}}
           :doc "Map of zone-group-id (keyword) -> {:id :name :description :color}"}
   :selected-group {:default nil
                    :doc "Currently selected zone group ID for editing"}})

;; Unified Chain Storage Domain


(defstate chains
  "Unified chain storage for all hierarchical lists.
   
   Four chain types with consistent structure:
   - :effect-chains - Effect modifiers for grid cells
   - :cue-chains - Cue presets/groups for grid cells
   - :projector-effects - Output effects for projectors
   - :zone-effects - Geometry/calibration effects for zones
   
   All chains use the same structure: {:items [...] :active? bool (optional)}
   This enables generic handlers and simplified subscriptions."
  {:effect-chains {:default {}
                   :doc "Map of [col row] -> {:items [...] :active? bool}"}
   :cue-chains {:default {}
                :doc "Map of [col row] -> {:items [...]}. Starter content is applied via laser-show.state.templates/apply-starter-cue-chains"}
   :projector-effects {:default {}
                       :doc "Map of projector-id -> {:items [...]}"}
   :zone-effects {:default {}
                  :doc "Map of zone-id (UUID) -> {:items [...]}"}})


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
