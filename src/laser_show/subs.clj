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

(defn project-folder [context]
  (fx/sub-val context ex/project-folder))

(defn project-dirty? [context]
  (fx/sub-val context ex/project-dirty?))

;; --- Config ---


(defn window-config [context]
  (fx/sub-val context ex/window-config))

(defn preview-config [context]
  (fx/sub-val context ex/preview-config))


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
   - selected-cell (for selection state)
   
   Returns map with:
   - :col, :row - position
   - :cue-chain - the full cue chain data
   - :preset-count - number of presets in the cue chain
   - :first-preset-id - id of first preset (for display)
   - :active? - is this cell playing?
   - :selected? - is this cell selected?
   - :has-content? - does cell have any presets?"
  [context col row]
  (let [;; Read cue chain from unified :chains domain
        chains-data (fx/sub-val context :chains)
        cue-chain-data (get-in chains-data [:cue-chains [col row]] {:items []})
        items (:items cue-chain-data [])
        active (fx/sub-ctx context active-cell)
        selected (fx/sub-ctx context selected-cell)
        ;; Flatten to get all presets (excluding groups)
        flat-items (filter #(= :preset (:type %))
                          (tree-seq #(= :group (:type %))
                                   #(:items % [])
                                   {:type :group :items items}))
        first-preset (first flat-items)
        preset-count (count flat-items)]
    {:col col
     :row row
     :cue-chain cue-chain-data
     :preset-count preset-count
     :first-preset-id (:preset-id first-preset)
     :active? (= [col row] active)
     :selected? (= [col row] selected)
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
   - :has-project? - is a project folder set?
   - :folder - project folder path or nil
   - :dirty? - has unsaved changes?
   - :last-saved - timestamp of last save or nil
   - :title - computed window title"
  [context]
  (let [p (fx/sub-val context ex/project)
        folder (:current-folder p)
        dirty? (:dirty? p)]
    {:has-project? (some? folder)
     :folder folder
     :dirty? dirty?
     :last-saved (:last-saved p)
     :title (str "Laser Show"
                 (when folder (str " - " folder))
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
   
   Depends on: dialogs from ui domain"
  [context dialog-id]
  (get-in (fx/sub-val context ex/dialogs) [dialog-id :data]))


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
   Depends on: projectors domain"
  [context]
  (let [data (fx/sub-val context :projectors)
        items (:items data {})]
    (mapv (fn [[id config]] (assoc config :id id)) items)))

(defn active-projector-id
  "Get the ID of the currently selected projector.
   Depends on: projectors domain"
  [context]
  (:active-projector (fx/sub-val context :projectors)))

(defn active-projector
  "Get the full config of the currently selected projector.
   Depends on: projectors domain"
  [context]
  (let [data (fx/sub-val context :projectors)
        active-id (:active-projector data)]
    (when active-id
      (assoc (get-in data [:items active-id]) :id active-id))))

(defn discovered-devices
  "Get list of devices from the last network scan.
   Depends on: projectors domain"
  [context]
  (:discovered-devices (fx/sub-val context :projectors) []))

(defn projector-scanning?
  "Check if a network scan is in progress.
   Depends on: projectors domain"
  [context]
  (:scanning? (fx/sub-val context :projectors) false))

(defn configured-projector-hosts
  "Get set of already-configured projector hosts.
   Useful for indicating which discovered devices are already configured.
   Depends on: projectors domain"
  [context]
  (let [items (:items (fx/sub-val context :projectors) {})]
    (into #{} (map :host (vals items)))))

(defn configured-projector-services
  "Get set of [host service-id] pairs for already-configured projectors.
   Allows checking if a specific service/output is already configured.
   Depends on: projectors domain"
  [context]
  (let [items (:items (fx/sub-val context :projectors) {})]
    (into #{}
          (map (fn [proj]
                 [(:host proj) (or (:service-id proj) 0)])
               (vals items)))))

(defn expanded-discovered-devices
  "Get set of device addresses that are expanded in the discovery panel.
   Used for showing/hiding service lists on multi-output devices.
   Depends on: projectors domain"
  [context]
  (:expanded-devices (fx/sub-val context :projectors) #{}))

(defn test-pattern-mode
  "Get the current test pattern mode (:grid, :corners, or nil).
   Depends on: projectors domain"
  [context]
  (:test-pattern-mode (fx/sub-val context :projectors)))

(defn enabled-projectors
  "Get list of enabled projectors.
   Depends on: projectors-list (computed)"
  [context]
  (filterv :enabled? (fx/sub-ctx context projectors-list)))


(defn projector-effect-ui-state
  "Get the effect chain UI state for a specific projector.
   Used by the shared effect-chain-sidebar component.
   
   Depends on: ui domain (NOT projectors-data, to avoid subscription cascade)
   
   UI state is stored separately from projector config to prevent
   drag-and-drop updates from invalidating all projector-related subscriptions.
   
   Returns map with:
   - :selected-paths - set of selected item paths
   - :last-selected-path - anchor for shift+click range select
   - :dragging-paths - paths being dragged (during drag-and-drop)
   - :drop-target-path - current drop target (for visual feedback)
   - :drop-position - :before or :into
   - :renaming-path - path of group being renamed"
  [context projector-id]
  (get-in (fx/sub-val context :ui)
          [:projector-effect-ui-state projector-id]
          {:selected-paths #{}
           :last-selected-path nil
           :dragging-paths nil
           :drop-target-path nil
           :drop-position nil
           :renaming-path nil}))

(defn projector-selected-effect-paths
  "Get the set of selected effect paths for a projector.
   Depends on: projector-effect-ui-state"
  [context projector-id]
  (:selected-paths (fx/sub-ctx context projector-effect-ui-state projector-id) #{}))

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
  (get-in (fx/sub-val context :list-ui)
          [:components component-id]
          {:selected-ids #{}
           :last-selected-id nil
           :dragging-ids nil
           :drop-target-id nil
           :drop-position nil
           :renaming-id nil}))


;; Level 2: Zone Subscriptions


(defn zones-list
  "Get all zones as a flat list.
   Depends on: zones domain"
  [context]
  (let [items (:items (fx/sub-val context :zones) {})]
    (mapv (fn [[id config]] (assoc config :id id)) items)))

(defn zones-by-projector
  "Get zones grouped by projector ID.
   Depends on: zones domain
   Returns: {projector-id [zone1 zone2 zone3] ...}"
  [context]
  (let [zones (fx/sub-ctx context zones-list)]
    (group-by :projector-id zones)))

(defn zones-for-projector
  "Get zones for a specific projector.
   Depends on: zones-by-projector"
  [context projector-id]
  (get (fx/sub-ctx context zones-by-projector) projector-id []))

(defn zone
  "Get a single zone by ID.
   Depends on: zones domain"
  [context zone-id]
  (when zone-id
    (let [data (fx/sub-val context :zones)]
      (when-let [z (get-in data [:items zone-id])]
        (assoc z :id zone-id)))))

(defn selected-zone-id
  "Get the currently selected zone ID.
   Depends on: zones domain"
  [context]
  (:selected-zone (fx/sub-val context :zones)))

(defn selected-zone
  "Get the currently selected zone.
   Depends on: selected-zone-id, zone"
  [context]
  (when-let [id (fx/sub-ctx context selected-zone-id)]
    (fx/sub-ctx context zone id)))

(defn zone-effect-chain
  "Get the effects chain for a specific zone.
   Depends on: chains domain"
  [context zone-id]
  (get-in (fx/sub-val context :chains)
          [:zone-effects zone-id :items]
          []))


;; Level 2: Zone Group Subscriptions


(defn zone-groups-list
  "Get all zone groups as a flat list.
   Depends on: zone-groups domain"
  [context]
  (let [items (:items (fx/sub-val context :zone-groups) {})]
    (mapv (fn [[id config]] (assoc config :id id)) items)))

(defn zone-group
  "Get a single zone group by ID.
   Depends on: zone-groups domain"
  [context group-id]
  (when group-id
    (let [data (fx/sub-val context :zone-groups)]
      (when-let [g (get-in data [:items group-id])]
        (assoc g :id group-id)))))

(defn selected-zone-group-id
  "Get the currently selected zone group ID.
   Depends on: zone-groups domain"
  [context]
  (:selected-group (fx/sub-val context :zone-groups)))

(defn selected-zone-group
  "Get the currently selected zone group.
   Depends on: selected-zone-group-id, zone-group"
  [context]
  (when-let [id (fx/sub-ctx context selected-zone-group-id)]
    (fx/sub-ctx context zone-group id)))

(defn zones-in-group
  "Get all zones that belong to a specific zone group.
   Depends on: zones-list"
  [context group-id]
  (filterv #(some #{group-id} (:zone-groups %))
           (fx/sub-ctx context zones-list)))

(defn zone-group-usage
  "Get usage info for a zone group: which zones and how many cues use it.
   Depends on: zones-list
   Returns: {:zone-count n :cue-count n :zones [...]}"
  [context group-id]
  (let [zones (fx/sub-ctx context zones-in-group group-id)]
    {:zone-count (count zones)
     :cue-count 0 ;; TODO: Count cues targeting this group
     :zones zones}))


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
