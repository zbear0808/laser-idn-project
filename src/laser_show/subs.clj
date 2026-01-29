(ns laser-show.subs
  "Subscription functions for cljfx components.
   
   ALL subscriptions use fx/sub-val or fx/sub-ctx to integrate with cljfx's
   memoization system. This is the single source of derived state for the UI.
   
   Subscription Levels:
   
   Level 1 (fx/sub-val): Direct domain access
           Fast, always recalculated, use for simple domain lookups
           Example: (fx/sub-val context :timing)
   
   Level 2 (fx/sub-ctx): Composed subscriptions (memoized, dependency-tracked)
           Use for derived values, computations, and data transformations
           These compose from Level 1 or other Level 2 subscriptions
           Example: (fx/sub-ctx context cell-display-data 0 0)
   
   For shared extraction logic, we use laser-show.state.extractors which provides
   pure functions that work on raw state data. This eliminates duplication between
   queries.clj and subs.clj.
   
   Usage in components:
   
   (defn my-component [{:keys [fx/context]}]
     (let [timing (fx/sub-val context :timing)   ; Direct domain access
           cell (fx/sub-ctx context cell-display-data 0 0)]  ; Computed
       ...))"
  (:require [cljfx.api :as fx]
            [laser-show.state.extractors :as ex]
            [laser-show.css.core :as css]
            [laser-show.animation.chains :as chains]))


;; Level 1: Domain Accessors (fx/sub-val wrappers)
;; These use fx/sub-val for direct domain access.
;; Fast but always recalculated on context change.


;; --- Timing ---

(defn bpm [context]
  (fx/sub-val context ex/bpm))


;; --- Playback ---


(defn playing? [context]
  (fx/sub-val context ex/playing?))

(defn active-cell [context]
  (fx/sub-val context ex/active-cell))

;; --- Grid ---


(defn grid-size [context]
  (fx/sub-val context ex/grid-size))

(defn selected-cell [context]
  (fx/sub-val context ex/selected-cell))

;; --- UI ---


(defn active-tab [context]
  (fx/sub-val context ex/active-tab))

(defn clipboard [context]
  (fx/sub-val context ex/clipboard))



;; --- Project ---

(defn project-file [context]
  (fx/sub-val context ex/project-file))

(defn project-dirty? [context]
  (fx/sub-val context ex/project-dirty?))

;; --- Config ---


(defn window-config [context]
  (fx/sub-val context ex/window-config))

(defn preview-config [context]
  (fx/sub-val context ex/preview-config))

(defn preview-zone-filter
  "Get the current preview zone group filter.
   Returns:
   - nil: show all content (master view)
   - :all: show only content routed to :all zone group
   - :left, :right, etc.: show only content routed to that zone group"
  [context]
  (fx/sub-val context get-in [:config :preview :zone-group-filter]))


;; Level 2: Computed Subscriptions
;; These compose multiple Level 1 subscriptions or perform computations.
;; Memoized via fx/sub-ctx, only recalculated when dependencies change.

;; Level 2: Computed Grid Cell Subscriptions
;; These compose from simpler subscriptions for better cache reuse.


(defn cell-display-data
  "Computed display data for a grid cell.
   
   Depends on:
   - chains domain (for cue chain content)
   - active-cell (for active state)
   
   Returns map with:
   - :col, :row - position
   - :name - custom name for the cue chain (nil if not set)
   - :cue-chain - the full cue chain data
   - :preset-count - number of presets in the cue chain
   - :first-preset-id - id of first preset (for display)
   - :active? - is this cell playing?
   - :has-content? - does cell have any presets?"
  [context col row]
  (let [;; Read cue chain from unified :chains domain
        chains-data (fx/sub-val context :chains)
        cue-chain-data (get-in chains-data [:cue-chains [col row]] {:items []})
        items (:items cue-chain-data [])
        active (fx/sub-ctx context active-cell)
        ;; Flatten to get all presets (excluding groups)
        flat-items (filter #(= :preset (:type %))
                          (tree-seq #(= :group (:type %))
                                   #(:items % [])
                                   {:type :group :items items}))
        first-preset (first flat-items)
        preset-count (count flat-items)]
    {:col col
     :row row
     :name (:name cue-chain-data)
     :cue-chain cue-chain-data
     :preset-count preset-count
     :first-preset-id (:preset-id first-preset)
     :active? (= [col row] active)
     :has-content? (pos? preset-count)}))

(defn active-cell-preset
  "Get the preset ID of the currently active cell.
   
   Depends on:
   - active-cell
   - chains domain (cue-chains)
   
   Returns: preset-id keyword of first preset, or nil if no cell is active"
  [context]
  (when-let [[col row] (fx/sub-ctx context active-cell)]
    ;; Get first preset from cue chain
    (let [chains-data (fx/sub-val context :chains)
          cue-chain (get-in chains-data [:cue-chains [col row] :items] [])]
      ;; Return first preset's ID (filter out groups)
      (:preset-id (first (filter #(= :preset (:type %)) cue-chain))))))

(defn active-preset
  "Alias for active-cell-preset. Get preset of currently active cell."
  [context]
  (fx/sub-ctx context active-cell-preset))

;; Level 2: Computed Effect Cell Subscriptions


(defn effect-cell-display-data
  "Computed display data for an effects grid cell.
   
   Depends on: chains domain (for effect chain content)
   
   Returns map with:
   - :col, :row - position
   - :name - custom name for the effect chain (nil if not set)
   - :effect-count - number of actual effects in chain (flattened, excludes groups)
   - :first-effect-id - id of first effect (not group)
   - :active? - is effect chain active?
   - :has-effects? - does cell have any effects?
   - :display-text - text to display in the cell"
  [context col row]
  (let [;; Read effect chain from unified :chains domain
        chains-data (fx/sub-val context :chains)
        cell-data (get-in chains-data [:effect-chains [col row]])
        ;; Effect chain items are stored under :items key
        effect-chain (:items cell-data [])
        ;; Flatten the chain to get actual effects (not groups)
        flattened-effects (chains/flatten-chain effect-chain)
        first-effect (first flattened-effects)
        effect-count (count flattened-effects)]
    {:col col
     :row row
     :name (:name cell-data)
     :effect-count effect-count
     :first-effect-id (:effect-id first-effect)
     :active? (:active cell-data false)
     :has-effects? (pos? effect-count)
     :display-text (when first-effect
                     (str (name (:effect-id first-effect))
                          (when (> effect-count 1)
                            (str " +" (dec effect-count)))))}))


;; Level 2: Computed Project Subscriptions


(defn project-status
  "Get current project status.
   
   Depends on: project domain
   
   Returns map with:
   - :has-project? - is a project file set?
   - :file - project file path or nil
   - :dirty? - has unsaved changes?
   - :last-saved - timestamp of last save or nil
   - :title - computed window title"
  [context]
  (let [p (fx/sub-val context ex/project)
        file (:current-file p)
        dirty? (:dirty? p)]
    {:has-project? (some? file)
     :file file
     :dirty? dirty?
     :last-saved (:last-saved p)
     :title (str "Laser Show"
                 (when file (str " - " file))
                 (when dirty? " *"))}))


;; Level 2: Computed Connection Subscriptions


(defn connection-status
  "Computed connection status for toolbar.
   
   Depends on: idn-data from backend domain
   
   Returns map with:
   - :connected? - is connected?
   - :connecting? - is connecting?
   - :target - target hostname
   - :error - error message
   - :status-text - human-readable status"
  [context]
  (let [i (fx/sub-val context ex/idn-data)]
    {:connected? (:connected? i)
     :connecting? (:connecting? i)
     :target (:target i)
     :error (:error i)
     :status-text (cond
                    (:connected? i) (str "Connected: " (:target i))
                    (:connecting? i) "Connecting..."
                    (:error i) (str "Error: " (:error i))
                    :else "Disconnected")}))


;; Level 2: Computed UI State Subscriptions


(defn dialog-open?
  "Check if a specific dialog is open.
   
   Depends on: dialogs from ui domain"
  [context dialog-id]
  (get-in (fx/sub-val context ex/dialogs) [dialog-id :open?] false))

(defn dialog-data
  "Get data associated with a dialog.
   
   Depends on: dialogs from ui domain
   
   Returns the dialog map without :open? - all fields are at the same level as :open?."
  [context dialog-id]
  (dissoc (get (fx/sub-val context ex/dialogs) dialog-id) :open?))


;; Frame/Preview Subscriptions


(defn current-frame
  "Get the current preview frame data.
   
   Depends on: streaming-data from backend domain
   
   Returns frame with :points vector."
  [context]
  (:current-frame (fx/sub-val context ex/streaming-data)))

(defn frame-stats
  "Get frame rendering statistics.
   
   Depends on: streaming-data from backend domain"
  [context]
  (:frame-stats (fx/sub-val context ex/streaming-data)))


;; Level 2: Projector Subscriptions


(defn projectors-list
  "Get list of configured projectors as [{:id :proj-1 :name ...} ...].
   Sorted by service-id (projectors with no service-id or 0 first).
   Depends on: projectors domain (which IS the map of projector-id -> config)"
  [context]
  (let [items (fx/sub-val context :projectors)]
    (->> items
         (mapv (fn [[id config]] (assoc config :id id)))
         (sort-by (fn [proj] [(or (:service-id proj) 0)])))))

(defn active-projector-id
  "Get the ID of the currently selected projector.
   Depends on: projector-ui domain"
  [context]
  (:active-projector (fx/sub-val context :projector-ui)))

(defn active-projector
  "Get the full config of the currently selected projector.
   Depends on: projector-ui domain and projectors domain"
  [context]
  (let [active-id (:active-projector (fx/sub-val context :projector-ui))
        projectors (fx/sub-val context :projectors)]
    (when active-id
      (assoc (get projectors active-id) :id active-id))))

(defn discovered-devices
  "Get list of devices from the last network scan.
   Depends on: projector-ui domain"
  [context]
  (:discovered-devices (fx/sub-val context :projector-ui) []))

(defn projector-scanning?
  "Check if a network scan is in progress.
   Depends on: projector-ui domain"
  [context]
  (:scanning? (fx/sub-val context :projector-ui) false))

(defn configured-projector-hosts
  "Get set of already-configured projector hosts.
   Useful for indicating which discovered devices are already configured.
   Depends on: projectors domain (which IS the map of projector-id -> config)"
  [context]
  (let [items (fx/sub-val context :projectors)]
    (into #{} (map :host (vals items)))))

(defn configured-projector-services
  "Get set of [host service-id] pairs for already-configured projectors.
   Allows checking if a specific service/output is already configured.
   Depends on: projectors domain (which IS the map of projector-id -> config)"
  [context]
  (let [items (fx/sub-val context :projectors)]
    (into #{}
          (map (fn [proj]
                 [(:host proj) (or (:service-id proj) 0)])
               (vals items)))))

(defn enabled-projector-services
  "Get set of [host service-id] pairs for ENABLED projectors only.
   Used by discovery panel to show accurate enabled state for each service.
   Depends on: projectors domain (which IS the map of projector-id -> config)"
  [context]
  (let [items (fx/sub-val context :projectors)]
    (into #{}
          (comp (filter :enabled?)
                (map (fn [proj]
                       [(:host proj) (or (:service-id proj) 0)])))
          (vals items))))

(defn expanded-discovered-devices
  "Get set of device addresses that are expanded in the discovery panel.
   Used for showing/hiding service lists on multi-output devices.
   Depends on: projector-ui domain"
  [context]
  (:expanded-devices (fx/sub-val context :projector-ui) #{}))

(defn calibrating-projector-id
  "Get the ID of the projector currently in calibration mode.
   Returns nil if no projector is calibrating.
   Depends on: projector-ui domain"
  [context]
  (fx/sub-val context get-in [:projector-ui :calibrating-projector-id]))

(defn calibration-brightness
  "Get the current calibration test pattern brightness.
   Depends on: projector-ui domain"
  [context]
  (fx/sub-val context get-in [:projector-ui :calibration-brightness] 0.1))

(defn projector-calibrating?
  "Check if a specific projector is in calibration mode.
   Depends on: calibrating-projector-id"
  [context projector-id]
  (= projector-id (fx/sub-ctx context calibrating-projector-id)))

(defn enabled-projectors
  "Get list of enabled projectors.
   Depends on: projectors-list (computed)"
  [context]
  (filterv :enabled? (fx/sub-ctx context projectors-list)))


(defn projector-effect-ui-state
  "Get the effect chain UI state for a specific projector.
   Used by visual editors for UI mode state (e.g., active curve channel).
   
   Depends on: ui domain
   
   NOTE: Selection state is now stored in list-ui domain at
   [:list-ui [:projector-effects projector-id]] and accessed via list-ui-state.
   
   Returns map with:
   - :ui-modes - map of effect-path to UI mode settings (e.g., active curve channel)"
  [context projector-id]
  (get-in (fx/sub-val context :ui)
          [:projector-effect-ui-state projector-id]
          {:ui-modes {}}))

(defn projector-effect-chain
  "Get the effects chain for a specific projector.
   Depends on: chains domain"
  [context projector-id]
  (get-in (fx/sub-val context :chains)
          [:projector-effects projector-id :items]
          []))


;; Hierarchical List UI Subscriptions


(defn list-ui-state
  "Get UI state for a specific hierarchical list component.
   
   Depends on: list-ui domain
   
   Component IDs are typically:
   - [:effect-chain col row] for effect chain editor
   - [:cue-chain col row] for cue chain editor
   - [:item-effects col row item-path] for item effects
   
   Returns map with:
   - :selected-ids - Set of selected item UUIDs
   - :last-selected-id - UUID of anchor for shift+click
   - :dragging-ids - Set of UUIDs being dragged
   - :drop-target-id - UUID of current drop target
   - :drop-position - :before, :after, or :into
   - :renaming-id - UUID of item being renamed"
  [context component-id]
  (get (fx/sub-val context :list-ui)
       component-id
       {:selected-ids #{}
        :last-selected-id nil
        :dragging-ids nil
        :drop-target-id nil
        :drop-position nil
        :renaming-id nil}))


;; Level 2: Virtual Projector Subscriptions


(defn virtual-projectors-list
  "Get all virtual projectors as a flat list.
   Depends on: virtual-projectors domain (which IS the map of vp-id -> config)"
  [context]
  (let [items (fx/sub-val context :virtual-projectors)]
    (mapv (fn [[id config]] (assoc config :id id)) items)))

(defn virtual-projectors-for-projector
  "Get virtual projectors for a specific parent projector.
   Depends on: virtual-projectors-list"
  [context parent-projector-id]
  (filterv #(= parent-projector-id (:parent-projector-id %))
           (fx/sub-ctx context virtual-projectors-list)))

(defn virtual-projector
  "Get a single virtual projector by ID.
   Depends on: virtual-projectors domain"
  [context vp-id]
  (when vp-id
    (when-let [vp (get (fx/sub-val context :virtual-projectors) vp-id)]
      (assoc vp :id vp-id))))

(defn active-virtual-projector-id
  "Get the ID of the currently selected virtual projector.
   Depends on: projector-ui domain"
  [context]
  (:active-virtual-projector (fx/sub-val context :projector-ui)))

(defn active-virtual-projector
  "Get the full config of the currently selected virtual projector.
   Depends on: active-virtual-projector-id, virtual-projector"
  [context]
  (when-let [id (fx/sub-ctx context active-virtual-projector-id)]
    (fx/sub-ctx context virtual-projector id)))

(defn projector-corner-pin
  "Get the corner-pin geometry for a projector.
   Depends on: projectors domain (which IS the map of projector-id -> config)"
  [context projector-id]
  (get-in (fx/sub-val context :projectors)
          [projector-id :corner-pin]))

(defn projector-zone-groups
  "Get the zone groups a projector belongs to.
   Depends on: projectors domain (which IS the map of projector-id -> config)"
  [context projector-id]
  (get-in (fx/sub-val context :projectors)
          [projector-id :zone-groups]
          []))

(defn projector-tags
  "Get the tags for a projector.
   Depends on: projectors domain (which IS the map of projector-id -> config)"
  [context projector-id]
  (get-in (fx/sub-val context :projectors)
          [projector-id :tags]
          #{}))


;; Level 2: Zone Group Subscriptions


(defn zone-groups-list
  "Get all zone groups as a flat list.
   Depends on: zone-groups domain (which IS the map of group-id -> config)"
  [context]
  (let [items (fx/sub-val context :zone-groups)]
    (mapv (fn [[id config]] (assoc config :id id)) items)))

(defn zone-group
  "Get a single zone group by ID.
   Depends on: zone-groups domain"
  [context group-id]
  (when group-id
    (when-let [g (get (fx/sub-val context :zone-groups) group-id)]
      (assoc g :id group-id))))

(defn selected-zone-group-id
  "Get the currently selected zone group ID.
   Depends on: zone-group-ui domain"
  [context]
  (:selected-group (fx/sub-val context :zone-group-ui)))

(defn selected-zone-group
  "Get the currently selected zone group.
   Depends on: selected-zone-group-id, zone-group"
  [context]
  (when-let [id (fx/sub-ctx context selected-zone-group-id)]
    (fx/sub-ctx context zone-group id)))

(defn projectors-in-zone-group
  "Get all projectors that belong to a specific zone group.
   Depends on: projectors-list"
  [context group-id]
  (filterv #(some #{group-id} (:zone-groups %))
           (fx/sub-ctx context projectors-list)))

(defn virtual-projectors-in-zone-group
  "Get all virtual projectors that belong to a specific zone group.
   Depends on: virtual-projectors-list"
  [context group-id]
  (filterv #(some #{group-id} (:zone-groups %))
           (fx/sub-ctx context virtual-projectors-list)))

(defn zone-group-usage
  "Get usage info for a zone group: which projectors and VPs use it.
   Returns: {:projector-count n :vp-count n :cue-count n :projectors [...] :virtual-projectors [...]}"
  [context group-id]
  (let [projectors (fx/sub-ctx context projectors-in-zone-group group-id)
        vps (fx/sub-ctx context virtual-projectors-in-zone-group group-id)]
    {:projector-count (count projectors)
     :vp-count (count vps)
     :cue-count 0 ;; TODO: Count cues targeting this group
     :projectors projectors
     :virtual-projectors vps}))

(defn all-outputs-for-zone-group
  "Get all outputs (projectors + VPs) for a zone group.
   Returns unified list for routing preview."
  [context group-id]
  (let [projectors (fx/sub-ctx context projectors-in-zone-group group-id)
        vps (fx/sub-ctx context virtual-projectors-in-zone-group group-id)]
    (into
      (mapv #(assoc % :output-type :projector) projectors)
      (mapv #(assoc % :output-type :virtual-projector) vps))))


;; Level 2: Input Device Subscriptions (MIDI, OSC)


(defn midi-config
  "Get the complete MIDI configuration.
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :midi]))

(defn midi-enabled?
  "Check if MIDI input is enabled.
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :midi :enabled]))

(defn midi-connected-devices
  "Get set of connected MIDI device names.
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :midi :connected-devices]))

(defn midi-available-devices
  "Get list of available MIDI device names.
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :midi :available-devices]))

(defn midi-channel-filter
  "Get the current MIDI channel filter (nil = all channels).
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :midi :channel-filter]))

(defn midi-learning?
  "Check if MIDI learn mode is active.
   Depends on: backend domain"
  [context]
  (boolean (fx/sub-val context get-in [:backend :input :midi :learn-mode])))

(defn osc-config
  "Get the complete OSC configuration.
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :osc]))

(defn osc-enabled?
  "Check if OSC input is enabled.
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :osc :enabled]))

(defn osc-server-running?
  "Check if the OSC server is running.
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :osc :server-running?]))

(defn osc-port
  "Get the OSC server port.
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :osc :port]))

(defn osc-address-mappings
  "Get the OSC address mappings.
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :osc :address-mappings]))

(defn osc-learning?
  "Check if OSC learn mode is active.
   Depends on: backend domain"
  [context]
  (boolean (fx/sub-val context get-in [:backend :input :osc :learn-mode])))


;; Stylesheet Subscriptions (CSS Hot-Reload)


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
