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
   :tap {:default {:taps [] :bpm nil :last-tap nil}
         :doc "Tap tempo (laser-show.triggers.tap-tempo): the last 5 taps of the series (ms), the tempo they give and the last tap's time. Session only: not saved, and the BPM it sets is [:timing :bpm]"}
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

   ;; The beat indicator
   :beat-index {:default 0
                :doc "The beat in the bar, 0-3 (0 = downbeat), from the show clock's global clock (:show-clock/status, on each beat). The clock itself is show time (show-time/global-clock): only reset by Stop and Retrigger."}})

(defstate playback
  "Playback control state for animation triggering.
   
   MULTI-CUE ARCHITECTURE:
   - active-cues: Map of [col row] -> intent; the show clock
     (laser-show.animation.show-time) owns each cue's timing
   - requests: one-shot transport requests the show clock applies
   - where each cue is never reaches app state: views draw it from the show
     clock's newest tick (show-time/latest)"
  {:playing? {:default false
              :doc "Whether playback is active (any cues playing)"}
   :active-cue {:default nil
                :doc "Currently active cue data (legacy, may remove)"}
   :cue-queue {:default []
               :doc "Queue of pending cues for quantized triggering"}

   ;; Multi-cue playback
   :active-cues {:default {}
                 :doc "Map of [col row] -> intent, with no position:
                       {:run uuid :timeline? bool :start-beat double
                        :paused? bool :seek {:id uuid :beat double}
                        :decay-beats double :hold? bool}
                       or {:chasing? true :timeline? true} for a timeline
                       following MIDI timecode. The show clock starts, moves
                       and drops runs to match (show-time/step); a run with
                       :decay-beats ends after them once :hold? is false."}
   :requests {:default {}
              :doc "{:restart {:id} :align {:id} :shift {:id :offset}}: the show clock applies each id once (Stop and Retrigger, tap tempo, Link)"}
   :pulses {:default {}
            :doc "source -> {:id n :held? bool :src}: the presses tap reactions follow (laser-show.animation.pulses). The vibe engine counts each HIT, ACCENT and FLASH press, held until its release (:src while held), and each vibe change ({:id n}, :vibe); grid/start-cue each cue start (:cue). The show clock stamps them on its pulse clock (show-time/step); their ages never come back here."}

   ;; Shared timing settings
   :resync-rate {:default 4.0
                 :doc "Beats to reach ~63% of target phase correction (shared across all cues)"}})

(defstate triggers
  "Internal trigger definitions (laser-show.events.handlers.triggers, :trigger/fire)."
  {:definitions {:default {}
                 :doc "Map of trigger ID to trigger definition"}})

(defstate trigger-runtime
  "Transient runtime state for active internal triggers."
  {:active-triggers {:default {}
                     :doc "Map of trigger ID to runtime timing state"}
   :held-inputs {:default #{}
                 :doc "Input identities currently held"}
   :last-input-time {:default nil
                     :doc "Timestamp of the most recent discrete input"}})

(defstate vibe-runtime
  "What vibe mode is doing (laser-show.vibes.engine, the :vibe/* events).
   Session only: not saved, not a document root (no undo, never unsaved),
   reset with the document (state.persistent/reset-session)."
  {:current {:default nil
             :doc "Id of the vibe playing, nil until the first press or :vibe/select"}
   :entered-at {:default nil
                :doc "Event time (ms) the current vibe began, or its time began again (an unlock, AUTO on, BLACKOUT off): the rotation's :seconds count from it"}
   :rotation {:default :active
              :doc ":active, :armed (auto-advance waits for a phrase gap or a FLASH release, laser-show.vibes.rotation) or :locked (VIBE held)"}
   :auto? {:default nil
           :doc "The Perform tab's AUTO switch, for the session; nil until it is used, which means the saved [:config :vibes :settings :rotation :mode]"}
   :blackout? {:default false
               :doc "BLACKOUT is on: every other press is ignored until it is pressed again"}
   :held {:default {}
          :doc "role -> {:at ms :src :input|:pad}: the roles pressed and not let go yet"}
   :voices {:default {}
            :doc "role -> what its press started: {:cell :run :mode} (:hit :accent), {:cell :run} (:bed), {:cell :run :fx :fx-on :solo?} (:flash)"}
   :duck {:default {}
          :doc "role -> what a held solo flash keeps waiting, started when it lets go: {:cell :mode} it stepped aside, or {:mode} for a HIT or ACCENT pressed while it was held (picked then)"}
   :fx-on {:default []
           :doc "Effect cells the current vibe's FX set switched on (only those are switched off)"}
   :bags {:default {}
          :doc "[vibe-id role] -> shuffle bag (laser-show.triggers.shuffle-bag) with its :seed and :n"}
   :started {:default 0
             :doc "Cues the engine has started this session: names each run, so the same press gives the same state"}
   :last-hit-at {:default nil
                 :doc "Event time (ms) of the last HIT press, whatever it played: the rotation's phrase gap counts from it"}
   :problem {:default nil
             :doc "{:role :why} of the last press that had nothing to play ({:why :no-vibes} without vibes), or of a key held too long that the watchdog let go of (:why :stuck-key), until the next good one"}})

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
   :notice {:default nil
            :doc "the notice at the left of the status bar, {:id uuid :text :level (:error :warning :info)}, or nil; set by failure handlers (events/helpers.clj show-notice), cleared by :ui/clear-notice after 6 s or on a click"}
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
                       :unsaved-confirm {:open? false
                                         :pending nil}
                       :confirm {:open? false}
                       :about {:open? false}
                       :settings {:open? false}}
             :doc "dialog visibility and data states (fields alongside :open?, no nested :data key)"}
   :preview {:default {:frame nil
                       :last-render-time 0}
             :doc "preview panel state"}
   :timeline-editor {:default {}
                     :doc "timeline editor view state per cue: [col row] -> {:selection #{clip-id} :cursor-beat}"}
   :timeline-prefs {:default {:snap {:enabled? true :value 0.25}
                              :add-panel {:open? false :preset-tab :geometric :effect-tab :shape}
                              :inspector-open? true
                              :ruler-mode :bars
                              :detail-height 300.0}
                    :doc "timeline editor preferences shared by every cue (snap, preset drawer, inspector, ruler mode, detail panel height); kept for the session, not saved"}
   :preview-zone-selector-open {:default nil
                                :doc "Cell index whose zone selector popup is open, or nil if none"}
   :vibes {:default {:selected nil :slot :hit :wizard nil}
           :doc "the Vibes tab (views/tabs/vibes.clj): :selected, the vibe edited (nil: the first); :slot, what a click on the picker fills (vibes.library/slots); :wizard, Learn all while it runs: {:queue roles to learn, the first being learned :done learned roles :refused the role a key pressed again is already}; for the session, not saved"}
   :show-lock? {:default false
                :doc "the Perform tab's show lock (events/show_lock.clj): while true, the edits that would move or remove what the show is built from are refused; for the session, never saved"}
   :show-lock-notice {:default nil
                      :doc "the last event the show lock refused, {:text :event}, shown under the switch until it is unlocked"}
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
            :doc "window dimensions, and :grid-split (0-1), where the user left the divider between a tab and the preview (absent until it is moved: subs/grid-split gives 0.65)"}
   :preview {:default {:zone-group-filter :all
                       :grid-layout [2 2]
                       :grid-cells [{:zone-group-id nil}    ;; nil = master view (show all)
                                    {:zone-group-id :auto/left}
                                    {:zone-group-id :auto/right}
                                    {:zone-group-id :auto/center}]
                       :show-labels? true}
             :doc "Preview grid configuration. grid-layout is [cols rows], grid-cells is vector of cell configs with :zone-group-id (nil = show all/master view). The cells take their size from the preview's pane (views/components/preview.clj canvas-side)"}
   :idn {:default {:host nil :port 7255}
         :doc "default IDN connection settings"}
   :osc {:default {:enabled? false :port default-osc-port}
         :doc "OSC server settings"}
   :midi {:default {:enabled? false :device nil}
          :doc "MIDI input settings"}
   :input {:default {:trigger-map {} :macro-map {}}
           :doc "Map of generic input triggers (MIDI/OSC/Key -> actions), and :macro-map {input store key -> :slider-1 or :slider-2}: the control learned for each fader (plans/2026-11-18-vibe-mode/05), from which the render takes each fader's value at every tick (vibes.faders/fader-values). A project saved before it has no :macro-map ([:config] merges shallowly on load): read nil as {}"}
   :cue {:default {:trigger-mode :default}
         :doc "Cue playback settings. :trigger-mode can be :default (use per-cue setting), :toggle (click ON/OFF), or :retrigger (always restart)"}
   :vibes {:default {:order []
                     :next-id 1
                     :library {}
                     :settings {:quick-tap-ms 150
                                :lock-hold-ms 600
                                :hit-taps-tempo? true
                                :rotation {:mode :off :seconds 45 :pause-beats 2.0}
                                :max-hold-secs 60}}
           :doc "Vibe mode: cues grouped by feel (laser-show.vibes.library). :library {vibe-id vibe} in :order; new ids count from :next-id. :settings: a press shorter than :quick-tap-ms is a tap, a VIBE press of :lock-hold-ms or more locks, :hit-taps-tempo? (not while Link sync is on); :rotation, auto-advance (laser-show.vibes.rotation): :mode :auto or :off (the Perform tab's switch overrides it for the session), a vibe armed after :seconds, changing at a gap of :pause-beats; a role held :max-hold-secs or longer is let go of as a stuck key (laser-show.vibes.watchdog). Saved, undoable content"}})

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
   - :scan-rate - Points per second (pps) this projector draws: 25 000 for new
     projectors, 1 000-100 000. It sets each frame's duration on the wire
   - :zone-groups - Vector of custom zone group IDs this projector belongs to
     (e.g., [:graphics]); All and the automatic groups are never listed
   - :exclude-from-all? - true keeps it out of All and the automatic groups
     (laser-show.routing.rig); it still plays its own group and its groups
   - :stable-id - [:unit \"0A 1B …\" output] or [:host address output]: how a
     scan knows it again (routing.rig/match-discovered)
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
   :identify {:default {}
              :doc "{projector-id until-ms}: projectors whose test pattern flashes until then (Identify)"}
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
   
   These are your groups. All (:all) and the automatic groups (Left, Right,
   Center, Odd, Even and one per laser) are built in and derived from the
   rig (laser-show.routing.rig), never stored here; shows from before keep
   their built-in Left, Right or Center only while a projector is in it, as
   \"Left (manual)\" (state.migration).
   
   Default zone groups are provided:
   - :graphics - Graphics-specific projectors (for smaller scan areas)
   - :crowd - Audience scanning projectors"
  {:graphics {:default {:id :graphics
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

(defstate rig
  "The rig: the projectors from left to right and how the automatic groups
   fold over them (laser-show.routing.rig, plans/2026-11-19-zones/01 §3).
   Saved in hardware.edn, undoable content. All and the automatic groups
   are derived from it, never stored."
  {:order {:default []
           :doc "[projector-id …] left to right, as the user placed them; projectors not in it follow, by IP then name (rig/effective-order). Written whole by the first move"}
   :middle {:default :split
            :doc "How the middle laser of 3, 5, 7… joins Left and Right: :split (its halves), :both (whole in both), :left, :right or :none"}
   :split-width {:default 0.75
                 :doc "How wide each half is, 0.5-1.0 of the safe width, when one laser draws Left and Right"}})

(defstate rig-ui
  "The rig strip's UI state (views/components/rig_strip.clj). Never saved,
   never undone."
  {:drop {:default nil
          :doc "{:projector-id :side} while a dragged card is over another (:before or :after it), so a bar shows where it would land; nil otherwise"}
   :scanned? {:default false
              :doc "A network scan finished this session: until then, no card says the scan didn't find it"}})

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
  "Backend services state - streaming, input, logging."
  {:streaming {:default {:running? false
                         :status nil
                         :frame-stats {}}
               :doc "IDN output. :running? is the operator's intent (Stream/Stop); :status is what actually streams, pure data from backend/output_runtime.clj (the engines live there)"}
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
                     :mtc {:state :stopped :fps nil :text nil} ; the show clock's status of laser-show.input.mtc, see mirror-status
                     :gamepad {:enabled? true}
                     :keyboard {:enabled? true
                                :attached-components #{}}
                     :router {:handlers {}
                              :event-log []
                              :enabled? true}
                     :values {}}
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


(defstate history
  "Undo and redo history management.
   Stores stack of pre-action document snapshots and in-progress transactions.
   Transient domain (not saved to project files)."
  {:undo-stack {:default []
                :doc "Vector of HistorySnapshot maps available for undo"}
   :redo-stack {:default []
                :doc "Vector of HistorySnapshot maps available for redo"}
   :transaction {:default nil
                 :doc "Active continuous interaction transaction ({:baseline-snapshot ... :label ... :source-id ...}) or nil"}})


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
