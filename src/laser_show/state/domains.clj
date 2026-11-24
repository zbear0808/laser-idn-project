(ns laser-show.state.domains
  "State domain definitions for the laser show application.
   
   This file uses the defstate macro to declaratively define all state domains.
   Each domain generates:
   - Initial state map (e.g., timing-initial)
   - Registration in the domain registry
   
   After loading this namespace, call (build-initial-state) to get the
   complete initial state map for the application.
   
   State writes come from event handlers, which return {:state new-state}
   for the event pipeline (laser-show.events.core) to write; tests and the
   REPL use events/write-state!.
   
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
            :doc "source -> {:id n :held? bool :src}: the presses tap reactions follow (laser-show.animation.pulses). The vibe engine counts each HIT, ACCENT and FLASH press, held until its release (:src while held), and each vibe change ({:id n}, :vibe); grid/start-cue each cue start (:cue); the STEP pad and its key each press of the Zone step key ({:id n}, :zone-step, zones plan Z5). The show clock stamps them on its pulse clock (show-time/step); their ages never come back here."}

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
   :keyboard-activations {:default {}
                          :doc "Key id -> action resolved on key-down, until key-up or a new show"}
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
          :doc "[cols rows] grid dimensions"}
   :column-targets {:default {}
                    :doc "{col target}: each column's default target (zones plan Z4, routing.targets), what a cue there without one of its own draws to. Positional: a cue moved to another column follows that one. Show content: undoable, unsaved, saved"}})

(defstate ui
  "UI interaction state including window, preview, and component references."
  {:selected-preset {:default nil
                     :doc "currently selected preset in browser"}
   :clipboard {:default nil
               :doc "The clipboard: the last clip copied in this app ({:type :copied-at ...}, state/clipboard.clj), or one a paste read from the OS clipboard while this was nil. Written only in the :state copy and paste handlers return; not saved, not undone, kept across Open and New"}
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
   :dialogs {:default {:cue-chain-editor {:open? false
                                          :col nil
                                          :row nil
                                          :active-preset-tab :geometric
                                          :selected-effect-id nil
                                          :item-effects-ui {}}
                       :effect-chain-editor {:open? false
                                             :col nil
                                             :row nil
                                             :active-bank-tab :shape}
                       :timeline-cue-editor {:open? false}
                       :zone-pattern-editor {:open? false}
                       :step-grid-editor {:open? false}
                       :add-projector-manual {:open? false
                                              :driver nil
                                              :name nil
                                              :host nil
                                              :port nil
                                              :driver-config nil}
                       :zone-group-editor {:open? false
                                           :editing? false
                                           :group-id nil
                                           :name ""
                                           :description ""
                                           :color "#808080"}
                       :trigger-editor {:open? false}
                       :unsaved-confirm {:open? false
                                         :pending nil}
                       :rig-report {:open? false}
                       :confirm {:open? false}
                       :about {:open? false}}
             :doc "dialog visibility and data states (fields alongside :open?, no nested :data key)"}
   :preview-visible? {:default true
                      :doc "whether the View menu shows the preview on tabs that support it"}
   :fullscreen? {:default false
                 :doc "whether the main window is in full screen"}
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
   :zone-maps {:default {:selected nil}
               :doc "the Zone Groups tab's zones and maps (views/components/zone_maps_panel.clj): :selected, the map its editor shows (nil: the one in use); for the session, not saved"}
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
                :doc "timestamp of last save"}
   :document-id {:default nil
                 :doc "The open show's identity (reactivity plan 20): a random UUID the starter show gets at start (app/starter-state), replaced by each File > New and Open that goes ahead (state.persistent/begin-document, from reset-session); Save, Save As, Undo and a cancelled or failed Open keep it. Work that comes back later for a show checks it (events.helpers/for-document and same-document?: an OS clipboard paste, Save As's chooser), and so does Open's chooser (handlers/menu.clj discard-authorization). Session only: never saved, undone or compared for unsaved changes. nil in a bare state (unit tests)"}})


;; Configuration Domains (persisted to disk)


(defstate config
  "Application configuration settings."
  {:grid {:default {:cols default-grid-cols :rows default-grid-rows}
          :doc "grid dimensions config"}
   :window {:default {:width default-window-width :height default-window-height}
            :doc "window dimensions, and :grid-split (0-1), where the user left the divider between a tab and the preview (absent until it is moved: subs/grid-split gives 0.65)"}
   :preview {:default {:zone-group-filter :all
                       :grid-layout [2 2]
                       :grid-cells [{:target nil}    ;; nil = master view (show all)
                                    {:target :auto/left}
                                    {:target :auto/right}
                                    {:target :auto/center}]
                       :show-labels? true}
             :doc "Preview grid configuration. grid-layout is [cols rows], grid-cells is a vector of cells {:target ref}: nil is the master view (everything); a group shows what is sent to it; a laser's own ref (:auto.laser/<id>) shows what that laser draws, its halves fitted, before its calibration (zones plan Z3). An old cell {:zone-group-id x} reads as {:target x} (subs/preview-cell-config). The cells take their size from the preview's pane (views/components/preview.clj canvas-side)"}
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
   :zone-map-pads {:default {}
                   :doc "{map-id {:bar? true}}: a zone map pad's setting (zones plan Z4): with :bar?, its presses (and its learned keys') wait for the next bar. Show content"}
   :pattern-pads {:default {}
                  :doc "{pad-id {:bar? true}}: a pattern pad's setting (zones plan Z5): with :bar?, its presses (and its learned keys') wait for the next bar. Show content"}
   :default-target {:default nil
                    :doc "The show's default target (zones plan Z4, routing.targets): what a cue without one of its own, in a column without one, draws to; nil is All. Show content"}
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
   - :driver - The output driver it sends through (laser-show.output.driver),
     set when it is created and never changed (OD-11). Absent for IDN, whose
     connection keys are :host, :port, :service-id and :output-config
   - :driver-config - Any other driver's settings, {key value} of its field
     specs (e.g. LSPS {:host \"127.0.0.1\" :port 7266 :max-packet-bytes 1400});
     kept as it is when the driver isn't installed (OD-10)
   - :host - IP address (IDN)
   - :port - IDN port (default 7255)
   - :unit-id - Hardware unit ID from discovery
   - :enabled? - Whether to send output to this projector
   - :output-config - Bit depth settings
   - :scan-rate - Points per second (pps) this projector draws: 25 000 for new
     projectors, 1 000-100 000. It sets each frame's duration on the wire
   - :fit - Its fit guard (zones plan Z3), {:min-fps :reduce-points?
     :point-floor :dim? :dim-floor}, each optional (animation.fit-guard
     defaults and settings): below :min-fps it draws fewer points, never
     under :point-floor of them, then dims if :dim?, never under :dim-floor
   - :zone-groups - Vector of custom zone group IDs this projector belongs to
     (e.g., [:graphics]); All and the automatic groups are never listed
   - :exclude-from-all? - true keeps it out of All and the automatic groups
     (laser-show.routing.rig); it still plays its own group and its groups
   - :stable-id - [:unit \"0A 1B …\" output] or [:host address output]: how a
     scan knows it again (routing.rig/match-discovered). Another driver's:
     [:manual driver projector-id] when added by hand (never matched by a
     scan), [:device driver device-key output-id] when a scan found it
     (output drivers D4)
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
                        :doc "Devices from the last scan: IDN addresses and services, or a driver's :device-key and :outputs. Each device carries :driver."}
   :scanning? {:default false
               :doc "Whether output drivers are being scanned"}
   :scan-id {:default nil
             :doc "The id of the scan in progress (a random UUID each Scan, and the one at start-up, gets), which its result brings back: a result with another id (an earlier scan's, or one already in) changes nothing (handlers/projector.clj current-scan?). nil when no scan runs"}
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

(defstate zones
  "The show's zones (zones plan Z4, spec 01 §1): :zone/a, :zone/b … are
   letters, each with an optional label. Show content: undoable, unsaved,
   saved."
  {:labels {:default {}
            :doc "{zone \"text\"}: a zone's label, shown after its letters (\"A · Beams\")"}})

(defstate zone-maps
  "Your zone maps (zones plan Z4, routing.zone-maps): {map-id {:id :name
   :lanes [ref …] :overrides {zone ref}}}, ids :map/custom-1 …. The built-in
   ones (Left / Right, Odd / Even, Unison) are derived, never stored. Show
   content: undoable, unsaved, saved."
  {})

(defstate zone-map
  "The map in use (zones plan Z4 §5). Not a document root: a switch is live,
   as BPM is, never an undo step and never unsaved; :active is still saved
   with the show (persistent-state-mapping), so it reopens on it (D-14)."
  {:active {:default :map/left-right
            :doc "The map in use: a built-in's id or one of yours; one that is no map is Left / Right (routing.zone-maps/effective-map-id)"}
   :pending {:default nil
             :doc "{:id :map :at-beat :bar?}: a switch waiting for its beat (routing.zone-maps/schedule); the show clock routes by it from that beat until :show-clock/zone-map-due commits it. :map :cycle is the CYCLE pad's (zones plan Z5): it resumes the Map cycle. Never saved, cleared by Open and New"}
   :cycle-paused? {:default false
                   :doc "A map switch that landed while a Map cycle ran paused it (zones plan Z5 S-2, Z5-Q2 A): the effective map is :active until CYCLE lands or a Map cycle cell is switched on (routing.zone-maps/commit, resume-cycle). Runtime: never saved, cleared by Open and New"}})

(defstate pattern-pads
  "The pattern pads (zones plan Z5, 07 §7.3): the style every zone pattern
   that follows them plays. Not a document root: a switch is live, never an
   undo step and never unsaved, and it isn't saved (Z5-Q3 A: a show opens As
   set); Open and New reset it."
  {:style {:default nil
           :doc "nil As set (every pattern its own style), :off (those that follow the pads don't move), or a style (routing.zone-patterns/styles)"}
   :pending {:default nil
             :doc "{:id :style :at-beat :bar?}: a switch waiting for its beat (routing.zone-patterns/schedule-style); the show clock plays it from that beat until :show-clock/pattern-due commits it"}})

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
              :doc "A device scan finished this session: until then, no card says the scan didn't find it"}
   :divider {:default nil
             :doc "Where the user left the divider between the strip and the inspector (0-1, the split pane's position; :rig/set-divider), for the session: New and Open keep it. nil until it is moved: the inspector gets the room its safety zone needs and the strip the rest (views/tabs/projectors.clj default-divider)"}})

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
  "Backend services state - streaming, output drivers, input, logging."
  {:streaming {:default {:running? false
                         :status nil
                         :frame-stats {}
                         :cue-warnings nil}
               :doc "IDN output. :running? is the operator's intent (Stream/Stop); :status is what actually streams, pure data from backend/output_runtime.clj (the engines live there), each laser's fit-guard :load and :low? too; :cue-warnings {cell {:projector :points :fps :min-fps :scan-rate :item}}, the playing cues too heavy for a laser (the show clock's status, zones plan Z3), nil when none"}
   :output {:default {:drivers {}
                      :addons []
                      :dir nil
                      :pending-installs []}
            :doc "The output drivers (laser-show.output.driver), as pure data: :drivers {id {:id :name :transport :fields :capabilities :discover? :addon}}, a copy of every registered driver's descriptor; :addons, a status per add-on the loader tried; :dir, the add-ons folder's path as the OS writes it. Written by :addons/loaded at startup. :pending-installs [{:id :name :version :file :replaced?}], the jars Install add-on… copied in since (each a <name>.jar.new that loads at the next start), written by :addons/installed. Never saved, never undone (the drivers themselves live in the registry)"}
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
                     :keyboard {:enabled? true}
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


(defstate rig-file
  "This machine's rig file (plans/2026-11-19-zones/09, state.rig-store): where
   it is and how saving it goes. Runtime: not a document root, never saved
   with a show, never reset by New or Open."
  {:path {:default nil
          :doc "The rig file (persistent/default-rig-file, set at startup); nil: no rig file (unit and UI tests unless they name one, the REPL), so nothing is read or written"}
   :status {:default :none
            :doc "How it went: the startup read's (rig-store/read!: :missing :ok :recovered :reset :newer), :locked (another window has it), then :saved or :write-failed after each write"}
   :read-only? {:default false
                :doc "Another window has the rig file, or it comes from a newer build: the rig is used, but never written"}
   :written-at {:default nil
                :doc "When the file was last written (ms): by this window, or before it started"}
   :error {:default nil
           :doc "Why the last write failed, for the status line and Exit's question"}
   :requested {:default 0
               :doc "Writes asked for this session (rig-autosave/write-request numbers each)"}
   :written {:default 0
             :doc "The newest request a write answered (:rig/written or :rig/write-failed): a write is pending while it is below :requested"}
   :group-copy {:default {}
                :doc "This window's copy of the definitions of the groups its lasers are in (rig-file/group-copy): kept to the rig and the show's groups after every event that changes them (handlers/rig_autosave.clj), Undo and Redo (history/restore-snapshot) and Open, whether or not the file can be written; what the next write of the file holds. Not a written file's acknowledgement"}})


;; State Builder


(defn build-initial-state
  "Build the complete initial state map from all registered domains.
   Call this after all defstate forms have been evaluated.
   
   Also includes :styles, view configuration rather than a domain: its
   :reload-trigger, bumped by a dev style reload, makes the stylesheet
   subscriptions re-run."
  []
  (merge
   (build-initial-state-from-domains)
   {:styles {}}))
