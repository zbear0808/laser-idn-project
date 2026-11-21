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
   pure functions that work on raw state data
   
   Usage in components:
   
   (defn my-component [{:keys [fx/context]}]
     (let [timing (fx/sub-val context :timing)   ; Direct domain access
           cell (fx/sub-ctx context cell-display-data 0 0)]  ; Computed
       ...))"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.state.extractors :as ex]
            [laser-show.vibes.faders :as faders]
            [laser-show.vibes.library :as library]
            [laser-show.vibes.reactions :as reactions]
            [laser-show.vibes.rotation :as rotation]
            [laser-show.css.core :as css]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.presets :as presets]
            [laser-show.animation.safety :as safety]
            [laser-show.output.driver :as driver]
            [laser-show.routing.refs :as refs]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-maps :as zone-maps]
            [laser-show.dev-config :as dev-config]))


;; Level 1: Domain Accessors (fx/sub-val wrappers)
;; These use fx/sub-val for direct domain access.
;; Fast but always recalculated on context change.


;; --- Timing ---

(defn bpm [context]
  (fx/sub-val context ex/bpm))


;; --- Playback ---


(defn playing? [context]
  (fx/sub-val context ex/playing?))

(defn beat-index
  "The beat in the bar, 0-3 (0 is the downbeat), from the show clock's
   global clock, which runs whether or not cues play. It changes on each
   beat only (:show-clock/status)."
  [context]
  (fx/sub-val context ex/beat-index))

;; --- Grid ---


(defn grid-size [context]
  (fx/sub-val context ex/grid-size))

(defn selected-grid-cell
  "Get the currently selected grid cell coordinates [col row] or nil."
  [context]
  (fx/sub-val context get-in [:grid :selected-cell]))

(defn selected-effects-cell
  "Get the currently selected effects cell coordinates [col row] or nil."
  [context]
  (fx/sub-val context get-in [:effects :selected-cell]))


;; --- UI ---


(defn active-tab [context]
  (fx/sub-val context ex/active-tab))

(defn clipboard [context]
  (fx/sub-val context ex/clipboard))

(defn notice
  "The status bar's notice, {:id :text :level}, or nil."
  [context]
  (fx/sub-val context get-in [:ui :notice]))



;; --- Project ---

(defn project-file [context]
  (fx/sub-val context ex/project-file))

(defn project-dirty? [context]
  (fx/sub-val context ex/project-dirty?))


;; --- History ---

(defn can-undo?
  "Check if undo is available."
  [context]
  (boolean (seq (fx/sub-val context get-in [:history :undo-stack]))))

(defn can-redo?
  "Check if redo is available."
  [context]
  (boolean (seq (fx/sub-val context get-in [:history :redo-stack]))))

(defn undo-label
  "Get the description of the action that will be undone, or nil."
  [context]
  (:description (peek (fx/sub-val context get-in [:history :undo-stack]))))

(defn redo-label
  "Get the description of the action that will be redone, or nil."
  [context]
  (:description (peek (fx/sub-val context get-in [:history :redo-stack]))))


;; --- Config ---


(defn window-config [context]
  (fx/sub-val context ex/window-config))

(defn grid-split
  "Where the divider between a tab and the preview sits, 0-1: where the
   user left it ([:config :window :grid-split]), or 0.65."
  [context]
  (fx/sub-val context get-in [:config :window :grid-split] 0.65))

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

(defn global-trigger-mode
  "Get the global trigger mode setting.
   Returns:
   - :default - Use per-cue trigger mode settings
   - :toggle - Override all cues to toggle mode
   - :retrigger - Override all cues to retrigger mode"
  [context]
  (fx/sub-val context get-in [:config :cue :trigger-mode] :default))


;; Level 2: Computed Subscriptions
;; These compose multiple Level 1 subscriptions or perform computations.
;; Memoized via fx/sub-ctx, only recalculated when dependencies change.

;; Level 2: Computed Grid Cell Subscriptions
;; These compose from simpler subscriptions for better cache reuse.


(defn cue-cell-chain
  "Cue chain stored at [col row], or {:items []} when the cell is empty.
   Only depends on this cell's entry, not the whole :chains domain."
  [context col row]
  (fx/sub-val context get-in [:chains :cue-chains [col row]] {:items []}))

(def ^:private timeline-view-defaults
  {:selection #{} :cursor-beat 0.0})

(defn timeline-ui
  "Timeline editor view state for the cue at [col row]
   ({:selection :cursor-beat}), with defaults."
  [context col row]
  (merge timeline-view-defaults
         (fx/sub-val context get-in [:ui :timeline-editor [col row]])))

(defn timeline-prefs
  "Timeline editor preferences: {:snap {:enabled? :value} :add-panel {...} :inspector-open?
   :ruler-mode :detail-height}."
  [context]
  (fx/sub-val context get-in [:ui :timeline-prefs]))

(defn timeline-transport
  "Transport status of the cue at [col row]: :stopped, :playing or :paused.
   Changes only when that does, not on every timing tick."
  [context col row]
  (let [t (get (fx/sub-val context ex/active-cues) [col row])]
    (cond
      (nil? t) :stopped
      (:paused? t) :paused
      :else :playing)))

(defn mtc-status
  "Incoming MIDI timecode for the UI: {:state (:running | :freewheel | :stopped)
   :fps :text}. From the show clock's status, at most 10 times a second."
  [context]
  (select-keys (fx/sub-val context get-in [:backend :input :mtc]) [:state :fps :text]))

(defn zone-groups-by-id
  "Zone groups as {id -> group (with :id)}."
  [context]
  (into {} (map (fn [[id config]] [id (assoc config :id id)])) (fx/sub-val context :zone-groups)))

(defn- active-cue? [state cell]
  (contains? (ex/active-cues state) cell))

(defn cue-cell-active?
  "True if the cue at [col row] is playing.
   Returns a boolean, so changes inside the cues' intents in active-cues (a
   pause, a seek) don't invalidate subscribers."
  [context col row]
  (fx/sub-val context active-cue? [col row]))

(defn cell-display-data
  "Computed display data for a grid cell.
   
   Depends on:
   - cue-cell-chain (this cell's cue chain)
   - cue-cell-active? (whether this cell is playing)
   - selected-grid-cell
   
   Returns map with:
   - :col, :row - position
   - :name - custom name for the cue chain (nil if not set)
   - :cue-chain - the full cue chain data
   - :preset-count - number of presets in the cue chain
   - :first-preset-id - id of first preset (for display)
   - :active? - is this cell playing?
   - :selected? - is this cell currently selected?
   - :timeline? - is this a timeline cue?
   - :has-content? - does cell have any presets or timeline configuration?"
  [context col row]
  (let [cue-chain-data (fx/sub-ctx context cue-cell-chain col row)
        items (:items cue-chain-data [])
        active? (fx/sub-ctx context cue-cell-active? col row)
        selected? (= (fx/sub-ctx context selected-grid-cell) [col row])
        ;; Flatten to get all presets (excluding groups)
        flat-items (filterv #(= :preset (:type %))
                            (tree-seq #(= :group (:type %))
                                      #(:items % [])
                                      {:type :group :items items}))
        first-preset (first flat-items)
        preset-count (count flat-items)
        timeline? (cue-chains/timeline-cue? cue-chain-data)
        has-content? (or (pos? preset-count) timeline?)]
    {:col col
     :row row
     :name (:name cue-chain-data)
     :cue-chain cue-chain-data
     :preset-count preset-count
     :first-preset-id (:preset-id first-preset)
     :active? active?
     :selected? selected?
     :timeline? timeline?
     :has-content? has-content?}))

(defn- held-count [state]
  (if (ex/playing? state) 0 (count (ex/active-cues state))))

(defn held-cue-count
  "How many cues Stop holds. Stop is the blackout (handlers/timing.clj): it
   sets :playing? false and leaves the cues' intents, so they stay in play
   with nothing drawing them, and Play resumes them. 0 while playing, or with
   no cue in play."
  [context]
  (fx/sub-val context held-count))

(defn output-held?
  "True while Stop holds cues (held-cue-count above 0): the output is blacked
   out, and the held cues' cells and the toolbar say so."
  [context]
  (pos? (fx/sub-ctx context held-cue-count)))

(defn- cue-cells-in-grid-order
  "The cells with an intent, row by row as the grid reads: A1, A2 … B1 …"
  [state]
  (vec (sort-by (fn [[col row]] [row col]) (keys (ex/active-cues state)))))

(defn playing-cues
  "The cues that play, in grid order, each as what its cell's label is made
   from (views/components/grid_cell.clj cell-label):
   {:col :row :name :first-preset-id :preset-count :timeline?}. None while
   Stop holds them (output-held?): nothing is drawn then."
  [context]
  (if (fx/sub-ctx context playing?)
    (mapv (fn [[col row]]
            (select-keys (fx/sub-ctx context cell-display-data col row)
                         [:col :row :name :first-preset-id :preset-count :timeline?]))
          (fx/sub-val context cue-cells-in-grid-order))
    []))

;; Level 2: Computed Effect Cell Subscriptions


(defn effect-cell-chain
  "Effect chain stored at [col row], or nil when the cell is empty.
   Only depends on this cell's entry, not the whole :chains domain."
  [context col row]
  (fx/sub-val context get-in [:chains :effect-chains [col row]]))

(defn effect-cell-display-data
  "Computed display data for an effects grid cell.
   
   Depends on: effect-cell-chain (this cell's effect chain)
   
   Returns map with:
   - :col, :row - position
   - :name - custom name for the effect chain (nil if not set)
   - :effect-count - number of actual effects in chain (flattened, excludes groups)
   - :first-effect-id - id of first effect (not group)
   - :effect-ids - ids of the effects, in chain order (flattened)
   - :active? - is effect chain active?
   - :selected? - is effect chain cell selected?
   - :has-effects? - does cell have any effects?
   - :display-text - text to display in the cell"
  [context col row]
  (let [cell-data (fx/sub-ctx context effect-cell-chain col row)
        ;; Effect chain items are stored under :items key
        effect-chain (:items cell-data [])
        ;; Flatten the chain to get actual effects (not groups)
        flattened-effects (chains/flatten-chain effect-chain)
        first-effect (first flattened-effects)
        effect-count (count flattened-effects)
        selected? (= (fx/sub-ctx context selected-effects-cell) [col row])]
    {:col col
     :row row
     :name (:name cell-data)
     :effect-count effect-count
     :first-effect-id (:effect-id first-effect)
     :effect-ids (mapv :effect-id flattened-effects)
     :active? (:active? cell-data false)
     :selected? selected?
     :has-effects? (pos? effect-count)
     :display-text (when first-effect
                     (str (name (:effect-id first-effect))
                          (when (> effect-count 1)
                            (str " +" (dec effect-count)))))}))

(defn effects-grid-empty?
  "True while no effects cell has an effect in it (groups don't count), as
   effect-cell-display-data's :has-effects? would say for every cell; but
   for Masters, by the Master rule: a cell that is a Master and nothing more
   (vibes.faders/master-only?) doesn't count, so a new project's grid, which
   has one, still says what effect cells are for."
  [context]
  (not-any? #(and (seq (chains/flatten-chain (:items % []))) (not (faders/master-only? %)))
            (vals (fx/sub-val context get-in [:chains :effect-chains]))))


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


(defn streaming-status
  "The output runtime's last published status ([:backend :streaming :status],
   nil when nothing runs), see backend/output_runtime.clj status."
  [context]
  (fx/sub-val context get-in [:backend :streaming :status]))

(defn projector-output-status
  "{:state :streaming|:starting|:error :fps :error} of one projector, or nil
   when it isn't streaming."
  [context projector-id]
  (get-in (fx/sub-ctx context streaming-status) [:projectors projector-id]))

(defn projector-load
  "What the fit guard made of projector-id's frames, as its card shows it
   (zones plan Z3): {:load {:points-in :points :budget :min-fps :scan-rate
   :est-fps :guard-fps :reduced-pct :dim-pct :regions, and :effect-points
   when its projector effects changed the count} :low? bool}, or nil
   while it isn't streaming a frame that has one (output-runtime/status)."
  [context projector-id]
  (let [{:keys [load low?]} (fx/sub-ctx context projector-output-status projector-id)]
    (when load
      {:load load :low? (boolean low?)})))

(defn low-fps-projectors
  "The lasers still below their minimum fps after the fit guard, the lowest
   first: [{:id :name :fps :min-fps :reduced-pct}], :fps what they draw after
   the guard. The toolbar's LOW FPS chip."
  [context]
  (let [projectors (fx/sub-val context :projectors)]
    (->> (:projectors (fx/sub-ctx context streaming-status))
         (keep (fn [[id {:keys [low? load]}]]
                 (when (and low? load)
                   {:id id
                    :name (rig/projector-name {:projectors projectors} id)
                    :fps (:guard-fps load)
                    :min-fps (:min-fps load)
                    :reduced-pct (:reduced-pct load)})))
         (sort-by (juxt :fps (comp str :id)))
         vec)))

(defn- item-name
  "What a cue's editor calls its top-level item id: its own name, else its
   preset's (\"Circle\"), else \"Group\" for a group; nil when it isn't there."
  [chain id]
  (when-let [item (some #(when (= id (:id %)) %) (:items chain))]
    (let [n (:name item)]
      (cond
        (and (string? n) (not (str/blank? n))) n
        (:preset-id item) (or (get-in presets/presets-by-id [(:preset-id item) :name]) (name (:preset-id item)))
        :else "Group"))))

(defn cue-warning
  "The warning of the cue at col, row while it is too heavy for a laser (the
   show clock's [:backend :streaming :cue-warnings], zones plan Z3): {:projector
   :points :fps :min-fps :scan-rate :item {:id :points} :decimate} with :laser,
   its name, and while its editor is open :item-name, the name of its item
   with the most points; nil when it has none. :decimate is there when the
   laser's Decimate keeps the cue's count (routing.compose/cue-warnings)."
  [context col row]
  (when-let [w (fx/sub-val context get-in [:backend :streaming :cue-warnings [col row]])]
    (let [chain (fx/sub-val context get-in [:chains :cue-chains [col row]])
          item (when-let [id (get-in w [:item :id])] (item-name chain id))]
      (cond-> (assoc w :laser (rig/projector-name {:projectors (fx/sub-val context :projectors)} (:projector w)))
        item (assoc :item-name item)))))

(defn connection-status
  "Streaming status for the toolbar.
   
   Depends on: [:backend :streaming :running?] (the operator's intent, which
   picks the button) and the output runtime's status (what actually streams).
   
   Returns map with:
   - :running? - streaming is on
   - :state - :stopped, :stopping, :starting, :streaming, :error or :idle
     (on, with no enabled projectors)
   - :status-text - e.g. \"Streaming to 2 of 3 · 1 error\", or \"No
     projectors enabled\" (:idle)
   - :errors - [\"<projector name>: <error>\" …]"
  [context]
  (let [running? (boolean (fx/sub-val context get-in [:backend :streaming :running?]))
        {:keys [engines wanted pending? projectors] :as status} (fx/sub-ctx context streaming-status)
        names (fx/sub-val context :projectors)
        streaming (count (filter #(= :streaming (:state %)) (vals projectors)))
        errors (vec (for [[id {:keys [state error]}] (sort-by key projectors)
                          :when (= :error state)]
                      (str (or (get-in names [id :name]) (name id)) ": " error)))
        state (cond
                (not running?) (if (pos? (or engines 0)) :stopping :stopped)
                (or (nil? status) pending?) :starting
                (seq errors) :error
                (zero? (or wanted 0)) :idle
                :else :streaming)]
    {:running? running?
     :state state
     :errors errors
     :status-text (case state
                    :stopped "Not streaming"
                    :stopping "Stopping…"
                    :starting "Starting…"
                    :idle "No projectors enabled"
                    (str "Streaming to " streaming " of " wanted
                         (when (zero? wanted) " (no enabled projectors)")
                         (when (seq errors)
                           (str " · " (count errors) (if (= 1 (count errors)) " error" " errors")))))}))


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
;;
;; The preview frame itself never goes through app state: the preview cells
;; draw the show clock's newest tick (views/components/clock_view.clj).


(defn frame-stats
  "Get frame rendering statistics, from the show clock's status (at most 4
   updates a second).
   
   Depends on: streaming-data from backend domain"
  [context]
  (:frame-stats (fx/sub-val context ex/streaming-data)))


;; Preview Grid Subscriptions


(defn preview-grid-layout
  "Get the current grid layout [cols rows]."
  [context]
  (get-in (fx/sub-val context :config) [:preview :grid-layout] [1 1]))

(defn preview-grid-cells
  "Get the grid cell configurations."
  [context]
  (get-in (fx/sub-val context :config) [:preview :grid-cells] []))

(defn preview-cell-config
  "A grid cell's config by index: {:target ref} (nil: the master view).
   An old cell, {:zone-group-id x}, reads as {:target x} (zones plan Z3)."
  [context cell-index]
  (let [cell (get (fx/sub-ctx context preview-grid-cells) cell-index)]
    {:target (if (contains? cell :target) (:target cell) (:zone-group-id cell))}))

(defn preview-show-labels?
  "Get whether to show zone labels on preview cells."
  [context]
  (get-in (fx/sub-val context :config) [:preview :show-labels?] true))

(defn preview-zone-selector-open
  "Get the cell index whose zone selector popup is open, or nil if none."
  [context]
  (fx/sub-val context get-in [:ui :preview-zone-selector-open]))


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


;; Level 2: Output drivers


(defn driver-descriptors
  "{driver-id descriptor} of every registered output driver
   (laser-show.output.driver/descriptors, written by :addons/loaded).
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :output :drivers] {}))

(defn driver-descriptor
  "The descriptor of output driver id, or nil when it isn't installed."
  [context id]
  (get (fx/sub-ctx context driver-descriptors) id))

(defn add-manual-types
  "What the Add Projector dialog's Type offers: [{:id :name}], IDN first,
   then the other installed drivers by name."
  [context]
  (->> (vals (fx/sub-ctx context driver-descriptors))
       (map #(select-keys % [:id :name]))
       (sort-by (fn [{:keys [id name]}] [(if (= :idn id) 0 1) (str/lower-case (str name)) (str id)]))
       vec))

(defn add-manual-form
  "The Add Projector dialog's form as typed ([:ui :dialogs
   :add-projector-manual]): {:driver (:idn without one) :descriptor (nil for
   IDN's own fields, or a driver not installed) :fields :inputs {key text}
   :config :errors (driver/coerce-config of the inputs) :can-add? (IDN: a
   host; any other driver: no field error) :default-name (another driver:
   what the projector is called without a name,
   driver/default-projector-name)}."
  [context]
  (let [{:keys [driver driver-config host]} (fx/sub-ctx context dialog-data :add-projector-manual)
        id (or driver :idn)]
    (if (= :idn id)
      {:driver :idn :can-add? (not (str/blank? (str host)))}
      (let [descriptor (fx/sub-ctx context driver-descriptor id)
            fields (:fields descriptor [])
            inputs (or driver-config {})
            {:keys [config errors]} (driver/coerce-config fields inputs)]
        {:driver id
         :descriptor descriptor
         :fields fields
         :inputs inputs
         :config config
         :errors errors
         :can-add? (boolean (and descriptor (empty? errors)))
         :default-name (driver/default-projector-name (fx/sub-val context :projectors) descriptor id)}))))

(defn projector-driver
  "What the projector settings show of projector-id's output driver:
   {:driver :idn? :installed? :name (its descriptor's, or the id's text)
   :fields :config (its :driver-config, the field defaults filled in)
   :summary (driver/summary)}, or nil for an unknown projector."
  [context projector-id]
  (when-let [p (fx/sub-val context get-in [:projectors projector-id])]
    (let [id (driver/projector-driver-id p)
          descriptors (fx/sub-ctx context driver-descriptors)
          d (get descriptors id)]
      {:driver id
       :idn? (= :idn id)
       :installed? (or (= :idn id) (some? d))
       :name (or (:name d) (if (= :idn id) "IDN" (subs (str id) 1)))
       :fields (:fields d [])
       :config (if d (merge (driver/field-defaults (:fields d)) (:driver-config p)) (:driver-config p))
       :summary (driver/summary p descriptors)})))

(defn- info-value-text [v]
  (cond
    (keyword? v) (name v)
    (double? v) (if (== v (Math/rint v)) (str (long v)) (format "%.1f" v))
    :else (str v)))

(defn driver-info-text
  "A compact line of a driver's :driver-info (output-runtime/status, 01
   §1.5): its plain entries, a key and a value each, by key, joined by
   \" · \" (\"buffer-ms 40 · scan-rate 30303\"); nested maps and vectors,
   and nil, are left out. nil when nothing is left."
  [info]
  (when (map? info)
    (let [entries (->> info
                       (filter (fn [[_ v]] (or (string? v) (keyword? v) (number? v) (boolean? v))))
                       (sort-by (comp str key))
                       (map (fn [[k v]] (str (if (keyword? k) (name k) k) " " (info-value-text v)))))]
      (when (seq entries)
        (str/join " · " entries)))))

(defn projector-driver-info
  "driver-info-text of projector-id's :driver-info, or nil."
  [context projector-id]
  (driver-info-text (:driver-info (fx/sub-ctx context projector-output-status projector-id))))

(defn addons-dir
  "The add-ons folder's path as the OS writes it (written by :addons/loaded).
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :output :dir]))

(defn- source-file-name
  "The file name in an add-on status's :source: a path (either separator) or
   a classpath descriptor's jar: URL."
  [source]
  (-> (str source)
      (str/replace #"!/laser-show-addon\.edn$" "")
      (str/split #"[/\\]")
      last))

(defn- addon-title
  "\"Acme Nova 0.3.0\": the name (else the id, else fallback) and the version."
  [{:keys [id name version]} fallback]
  (str/join " " (remove str/blank? [(or name (some-> id str) fallback) version])))

(defn addon-rows
  "Settings → Add-ons: a row per add-on the loader tried, in its order:
   {:title :state :state-text \"Loaded\" | \"Failed: …\" | \"Skipped: …\"
    :drivers \"Name (:id), …\" (nil when none) :file \"acme-nova.jar\"}.
   Depends on: backend domain"
  [context]
  (let [descriptors (fx/sub-ctx context driver-descriptors)]
    (mapv (fn [{:keys [state error drivers source] :as status}]
            (let [file (source-file-name source)]
              {:title (addon-title status file)
               :state state
               :state-text (case state
                             :loaded "Loaded"
                             :skipped (str "Skipped: " error)
                             (str "Failed: " error))
               :drivers (when (seq drivers)
                          (str/join ", " (for [id drivers]
                                           (if-let [n (get-in descriptors [id :name])]
                                             (str n " (" id ")")
                                             (str id)))))
               :file file}))
          (fx/sub-val context get-in [:backend :output :addons] []))))

(defn pending-install-rows
  "Settings → Add-ons: the jars Install add-on… copied in since startup,
   {:title :state-text :file}.
   Depends on: backend domain"
  [context]
  (mapv (fn [{:keys [file] :as pending}]
          (let [file-name (source-file-name file)]
            {:title (addon-title pending file-name)
             :state-text "Installed, loads at next start"
             :file file-name}))
        (fx/sub-val context get-in [:backend :output :pending-installs] [])))

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

(defn list-ordered-ids
  "Get the ordered vector of item IDs for a hierarchical list component or items-path.
   Calculates visual document-order IDs directly from the state context.
   Returns an eager vector of UUIDs in document order."
  ([context component-id]
   (list-ordered-ids context component-id nil))
  ([context component-id items-path]
   (let [target (or items-path (ex/resolve-items-path component-id))]
     (if (and (vector? target) (= :chains (first target)))
       (let [chains (fx/sub-val context :chains)
             sub-path (subvec target 1)]
         (chains/collect-all-ids (get-in chains sub-path [])))
       (let [state (fx/sub-val context identity)]
         (ex/chain-ordered-ids state (or target component-id)))))))


;; Level 2: Virtual Projector Subscriptions


(defn virtual-projectors-list
  "Get all virtual projectors as a flat list.
   Depends on: virtual-projectors domain (which IS the map of vp-id -> config)"
  [context]
  (let [items (fx/sub-val context :virtual-projectors)]
    (mapv (fn [[id config]] (assoc config :id id)) items)))


(defn projector-zone-groups
  "Get the zone groups a projector belongs to.
   Depends on: projectors domain (which IS the map of projector-id -> config)"
  [context projector-id]
  (get-in (fx/sub-val context :projectors)
          [projector-id :zone-groups]
          []))


;; Level 2: Zone Group Subscriptions


(defn zone-groups-list
  "All (built in, laser-show.routing.rig) first, then the stored zone groups,
   as a flat list.
   Depends on: zone-groups domain"
  [context]
  (let [items (fx/sub-val context :zone-groups)]
    (into [(assoc (rig/group-defs :all) :id :all)]
          (keep (fn [[id config]] (when-not (rig/derived-ref? id) (assoc config :id id))))
          items)))

(defn zone-group
  "Get a single zone group by ID: a stored one, or All or an automatic
   group (routing.rig/group-defs), which are built in.
   Depends on: zone-groups domain"
  [context group-id]
  (when group-id
    (if-let [g (get (fx/sub-val context :zone-groups) group-id)]
      (assoc g :id group-id)
      (when-let [d (get rig/group-defs group-id)]
        (assoc d :id group-id)))))

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

(defn ref-reach
  "{ref n}: how many lasers each ref the rig knows reaches (routing.rig/
   ref-entries: All, the automatic groups, each laser's that is on, your
   groups' members that are on)."
  [context]
  (let [entries (rig/ref-entries (rig/rig-inputs {:projectors (fx/sub-val context :projectors)
                                                  :rig (fx/sub-val context :rig)
                                                  :zone-groups (fx/sub-val context :zone-groups)}))]
    (into {} (map (fn [[ref es]] [ref (count (distinct (map first es)))])) entries)))

(defn zone-group-usage
  "Usage info for a zone group: the projectors and VPs in it, and the cues
   that name it anywhere (routing.refs/cues-using: a cue with no destination
   counts for :all; effect cells and the preview aren't cues). For All and
   the automatic groups, :projector-count is how many lasers they reach.
   Returns: {:projector-count n :vp-count n :cue-count n :cues #{cell} :projectors [...] :virtual-projectors [...]}"
  [context group-id]
  (let [projectors (fx/sub-ctx context projectors-in-zone-group group-id)
        vps (fx/sub-ctx context virtual-projectors-in-zone-group group-id)
        cues (refs/cues-using (fx/sub-val context get-in [:chains :cue-chains]) group-id)]
    {:projector-count (if (rig/derived-ref? group-id)
                        (get (fx/sub-ctx context ref-reach) group-id 0)
                        (count projectors))
     :vp-count (count vps)
     :cue-count (count cues)
     :cues cues
     :projectors projectors
     :virtual-projectors vps}))


;; Level 2: Where cues can go (routing.rig)


(defn target-groups
  "Everything a zone picker offers, in order (routing.rig/target-groups):
   All; Left, Right, Center, Odd, Even; each laser; your groups by name.
   Each {:id :name :color :description :kind}."
  [context]
  (rig/target-groups {:projectors (fx/sub-val context :projectors)
                      :rig (fx/sub-val context :rig)
                      :zone-groups (fx/sub-val context :zone-groups)}))

(defn target-groups-by-id
  "target-groups as {id group}."
  [context]
  (into {} (map (juxt :id identity)) (fx/sub-ctx context target-groups)))


;; Level 2: Zones, zone maps and targets (zones plan Z4)


(defn zone-labels
  "{zone \"text\"}: the zones' labels."
  [context]
  (fx/sub-val context get-in [:zones :labels] {}))

(defn names-ctx
  "What the Target texts read (routing.targets/names): the target groups
   and the zones' labels."
  [context]
  (targets/names (fx/sub-ctx context target-groups) (fx/sub-ctx context zone-labels)))

(defn zone-map-state
  "[:zone-map]: {:active :pending}, the map in use and a switch waiting."
  [context]
  (fx/sub-val context :zone-map))

(defn zone-maps-list
  "Every map: the built-ins, then yours by name (routing.zone-maps/all-maps)."
  [context]
  (zone-maps/all-maps {:zone-maps (fx/sub-val context :zone-maps)}))

(defn zone-map
  "The map id names (a built-in or yours), or nil."
  [context id]
  (zone-maps/map-by-id {:zone-maps (fx/sub-val context :zone-maps)} id))

(defn active-zone-map
  "The map in use, as the UI shows it (06 SQ-7): [:zone-map :active], Left
   / Right for an id that is no map."
  [context]
  (or (fx/sub-ctx context zone-map (fx/sub-val context get-in [:zone-map :active]))
      (fx/sub-ctx context zone-map zone-maps/default-map-id)))

(defn column-target
  "Column col's default target, or nil."
  [context col]
  (fx/sub-val context get-in [:grid :column-targets col]))

(defn default-target
  "The show's default target, or nil (All)."
  [context]
  (fx/sub-val context get-in [:config :default-target]))

(defn zones-in-use
  "Every zone the show draws into (Z4-Q1): those a cue names anywhere, a
   column's default and the show's, sorted."
  [context]
  (let [cues (fx/sub-val context get-in [:chains :cue-chains])
        refs (concat (mapcat refs/cue-refs (remove nil? (vals cues)))
                     (mapcat targets/target-refs (vals (fx/sub-val context get-in [:grid :column-targets])))
                     (targets/target-refs (fx/sub-ctx context default-target)))]
    (vec (sort-by targets/zone-index (into #{} (filter targets/zone-ref?) refs)))))

(defn zones-in-use?
  "Does the show draw into a zone anywhere (the map pads show, Z4-Q1 A)?"
  [context]
  (boolean (seq (fx/sub-ctx context zones-in-use))))

(defn cell-target-badge
  "The badge of the cue at [col row] (routing.targets/cell-badge), or nil.
   It never reads the zone map: a switch leaves it as it is (06 invariant
   14)."
  [context col row]
  (when-let [cue (fx/sub-val context get-in [:chains :cue-chains [col row]])]
    (targets/cell-badge (fx/sub-ctx context names-ctx) cue
                        (fx/sub-ctx context column-target col)
                        (fx/sub-ctx context default-target)
                        col)))

(defn cell-target-tooltip
  "The badge's line in its cell's tooltip, or nil. Only a badge with a zone
   reads the map in use: its line names their lanes."
  [context col row]
  (when-let [badge (fx/sub-ctx context cell-target-badge col row)]
    (let [zone-map (when (:zones badge) (fx/sub-ctx context active-zone-map))]
      (targets/cell-badge-tooltip (fx/sub-ctx context names-ctx) zone-map (:name zone-map) badge))))

(defn map-pad-ids
  "The maps the pads show, in order (zone-maps-list): the built-ins, then
   yours."
  [context]
  (mapv :id (fx/sub-ctx context zone-maps-list)))

(defn map-pad-bindings
  "The trigger-map keys learned for map id's pad, in a stable order."
  [context id]
  (->> (fx/sub-val context get-in [:config :input :trigger-map])
       (keep (fn [[k action]] (when (and (= :zone-map (:type action)) (= id (:target action))) k)))
       (sort-by pr-str)
       vec))

(defn map-pad
  "One zone map pad, its booleans and keys only (06 §4.5): {:id :name
   :active? (the map in use, as the UI shows it) :pending (nil, or :beat or
   :bar while a switch to it waits) :bar? (its Switch on the bar)
   :learning? :bindings}."
  [context id]
  (let [pending (fx/sub-val context get-in [:zone-map :pending])]
    {:id id
     :name (:name (fx/sub-ctx context zone-map id))
     :active? (= id (:id (fx/sub-ctx context active-zone-map)))
     :pending (when (= id (:map pending)) (if (:bar? pending) :bar :beat))
     :bar? (true? (fx/sub-val context get-in [:config :zone-map-pads id :bar?]))
     :learning? (= {:type :zone-map :target id} (fx/sub-val context get-in [:backend :input :learning-target]))
     :bindings (fx/sub-ctx context map-pad-bindings id)}))

(defn- cue-ref-sets
  "Each cue's refs (routing.targets/cue-refs), its column's default and the
   show's included."
  [context]
  (let [cols (fx/sub-val context get-in [:grid :column-targets])
        show (fx/sub-ctx context default-target)]
    (into []
          (keep (fn [[[col _] cue]] (when cue (targets/cue-refs cue (get cols col) show))))
          (fx/sub-val context get-in [:chains :cue-chains]))))

(defn ref-usage
  "Who uses ref, through the map in use (06 §8.7): {:direct :through
   :zones} (routing.zone-maps/usage)."
  [context ref]
  (zone-maps/usage (fx/sub-ctx context active-zone-map) (fx/sub-ctx context cue-ref-sets) ref))

(defn map-zones
  "The zones the Zone Groups tab lists, for their labels and in the map
   editor: A and B, and every zone the show uses, in order."
  [context]
  (vec (sort-by targets/zone-index (into #{:zone/a :zone/b} (fx/sub-ctx context zones-in-use)))))

(defn selected-zone-map-id
  "The map the map editor shows: the one selected while it is there, else
   the one in use."
  [context]
  (let [id (fx/sub-val context get-in [:ui :zone-maps :selected])]
    (if (and id (fx/sub-ctx context zone-map id))
      id
      (:id (fx/sub-ctx context active-zone-map)))))

(defn cue-target-explain
  "How each level of the cue at [col row] resolves, its column's and the
   show's defaults included (routing.targets/explain-cue)."
  [context col row]
  (targets/explain-cue (fx/sub-ctx context cue-cell-chain col row)
                       (fx/sub-ctx context column-target col)
                       (fx/sub-ctx context default-target)
                       col))


;; Level 2: The rig strip (views/components/rig_strip.clj)


(defn- found-outputs
  "#{[host service-id]} of the outputs the last scan found (service 0 for a
   device with none)."
  [devices]
  (into #{} (mapcat (fn [{:keys [address services]}]
                      (if (seq services)
                        (map (fn [s] [address (or (:service-id s) 0)]) services)
                        [[address 0]])))
        devices))

(defn rig-cards
  "One map per card of the rig strip, in rig order (routing.rig): {:id
   :position (the laser's place in E, from 1; nil when it is off or excluded
   from All) :name :host :service-id :driver :driver-installed? :host-label (driver/summary:
   \"10.0.0.7 #1\" for IDN, \"LSPS · 127.0.0.1:7266\" for another driver)
   :address-lines (driver/summary-parts: [\"LSPS\" \"127.0.0.1:7266\"])
   :enabled? :excluded? :tags :identity (rig/identity-label) :selected?
   :found? (false once a scan this session didn't find it; another driver's
   is never not found, as no scan looks for it before output drivers D4)
   :multi-output? (another output of its IDN device is a card too)
   :identifiable? (it can be reached to Identify: IDN with a host, or any
   other driver) :limit (its safety limit as safety/normalize reads it, nil
   when it can't, for the thumbnail's outline)}."
  [context]
  (let [projectors (fx/sub-val context :projectors)
        descriptors (fx/sub-ctx context driver-descriptors)
        inputs (rig/rig-inputs {:projectors projectors :rig (fx/sub-val context :rig)})
        position (zipmap (rig/e-order inputs) (iterate inc 1))
        selected (fx/sub-ctx context active-projector-id)
        found (when (fx/sub-val context get-in [:rig-ui :scanned?])
                (found-outputs (fx/sub-ctx context discovered-devices)))
        idn? #(= :idn (driver/projector-driver-id %))
        hosts (frequencies (keep #(when (idn? %) (:host %)) (vals projectors)))]
    (mapv (fn [id]
            (let [{:keys [host service-id tags] :as p} (get projectors id)
                  idn (idn? p)]
              {:id id
               :position (position id)
               :name (rig/projector-name {:projectors projectors} id)
               :host host
               :service-id service-id
               :driver (driver/projector-driver-id p)
               :driver-installed? (or (idn? p) (contains? descriptors (driver/projector-driver-id p)))
               :host-label (driver/summary p descriptors)
               :address-lines (driver/summary-parts p descriptors)
               :enabled? (rig/enabled? p)
               :excluded? (true? (:exclude-from-all? p))
               :tags (set tags)
               :identity (rig/identity-label p)
               :selected? (= id selected)
               :found? (or (nil? found) (not idn) (contains? found [host (or service-id 0)]))
               :multi-output? (and idn (< 1 (get hosts host 0)))
               :identifiable? (or (not idn) (not (str/blank? (str host))))
               :limit (try (:limit (safety/normalize (:safety p))) (catch Exception _ nil))}))
          (:order inputs))))

(defn rig-matrix
  "The group rows under the cards (routing.rig/matrix): {:columns [id …]
   :rows [{:id :name :color :kind :cells {id mark}}]}."
  [context]
  (rig/matrix {:projectors (fx/sub-val context :projectors)
               :rig (fx/sub-val context :rig)
               :zone-groups (fx/sub-val context :zone-groups)}))

(defn rig-settings
  "The middle laser and the split width as the strip offers them: {:middle
   :split-width :n (lasers in E) :middle-on? (n is 3, 5, 7…) :split-on? (one
   laser, or the middle split or in both)}."
  [context]
  (let [inputs (rig/rig-inputs {:projectors (fx/sub-val context :projectors) :rig (fx/sub-val context :rig)})
        n (count (rig/e-order inputs))
        middle (:middle inputs)
        middle-on? (and (odd? n) (<= 3 n))]
    {:middle middle
     :split-width (:split-width inputs)
     :n n
     :middle-on? middle-on?
     :split-on? (or (= 1 n) (and middle-on? (contains? #{:split :both} middle)))
     :middle-name (when middle-on?
                    (rig/projector-name {:projectors (fx/sub-val context :projectors)}
                                        (nth (rig/e-order inputs) (quot n 2))))}))


;; Level 2: The Zone Groups tab (views/tabs/zones.clj), group first


(defn zone-groups-tab-list
  "The tab's list: target-groups (All, the automatic groups, each laser,
   yours), each with :count, how many lasers it reaches (ref-reach)."
  [context]
  (let [reach (fx/sub-ctx context ref-reach)]
    (mapv #(assoc % :count (get reach (:id %) 0)) (fx/sub-ctx context target-groups))))

(defn selected-target-group
  "The group the Zone Groups tab shows (target-groups' entry), or nil."
  [context]
  (when-let [id (fx/sub-ctx context selected-zone-group-id)]
    (get (fx/sub-ctx context target-groups-by-id) id)))

(defn group-members
  "The lasers of ref in rig order, as the tab's middle shows them:
   {:lasers [{:id :name :position :mark :enabled? :excluded?}] :vps [{:id
   :name :member?}]}, the marks as the rows under the cards have them
   (routing.rig/ref-marks)."
  [context ref]
  (let [projectors (fx/sub-val context :projectors)
        inputs (rig/rig-inputs {:projectors projectors :rig (fx/sub-val context :rig)})
        marks (rig/ref-marks inputs (rig/auto-groups inputs) ref)
        position (zipmap (rig/e-order inputs) (iterate inc 1))]
    {:lasers (mapv (fn [id]
                     {:id id
                      :name (rig/projector-name {:projectors projectors} id)
                      :position (position id)
                      :mark (get marks id)
                      :enabled? (contains? (:enabled inputs) id)
                      :excluded? (contains? (:excluded inputs) id)})
                   (:order inputs))
     :vps (mapv (fn [{:keys [id] vp-name :name :as vp}]
                  {:id id :name (or vp-name (str id)) :member? (boolean (some #{ref} (:zone-groups vp)))})
                (fx/sub-ctx context virtual-projectors-list))}))


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
  (fx/sub-val context get-in [:backend :input :midi :enabled?]))

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
  (fx/sub-val context get-in [:backend :input :osc :enabled?]))

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


;; Level 2: Generic Input Subscriptions


(defn input-trigger-map
  "Get the global trigger mapping for generalized inputs.
   Depends on: config domain"
  [context]
  (fx/sub-val context get-in [:config :input :trigger-map] {}))

(defn input-learning-target
  "Get the current learning target for generalized inputs, if any.
   Depends on: backend domain"
  [context]
  (fx/sub-val context get-in [:backend :input :learning-target]))

(defn trigger-definitions
  "Get configured internal trigger definitions."
  [context]
  (fx/sub-val context get-in [:triggers :definitions] {}))

(defn trigger-runtime
  "Get transient internal trigger runtime state."
  [context]
  (fx/sub-val context :trigger-runtime))

(defn active-trigger-count
  "Get the number of currently active internal triggers."
  [context]
  (count (fx/sub-val context get-in [:trigger-runtime :active-triggers] {})))


;; Level 2: Link Subscriptions


(defn link-connected?
  "Check if Ableton Link is connected.
   Depends on: backend domain"
  [context]
  (fx/sub-val context ex/link-connected?))

(defn link-sync-enabled?
  "Check if Link BPM sync is enabled.
   Depends on: backend domain"
  [context]
  (fx/sub-val context ex/link-sync-enabled?))

(defn link-bpm
  "Get the current BPM from Ableton Link (if connected).
   Depends on: backend domain"
  [context]
  (fx/sub-val context ex/link-bpm))

(defn link-status
  "Get complete Link status information.
   Depends on: backend domain
   
   Returns map with:
   - :connected? - Whether Link is connected
   - :sync-enabled? - Whether BPM sync is enabled
   - :link-bpm - Current Link BPM (or nil)"
  [context]
  (fx/sub-val context ex/link-data))


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
  [context]
  ;; Depend on the reload trigger so Help > Reload Styles invalidates this cached sub.
  (fx/sub-val context get-in [:styles :reload-trigger])
  (css/all-stylesheet-urls))


;; Dev Mode


(defn dev-mode?
  "True when running with -Dlaser-show.dev=true. Constant for the life of the
   JVM, so it has no context dependencies."
  [_context]
  (dev-config/dev-mode?))


;; Vibe mode
;;
;; The Perform tab (views/tabs/perform.clj). The header, the strip and each
;; pad subscribe to what they show only, so a press re-renders its own pad,
;; and a vibe change the strip, the header and VIBE's pad.


(defn vibe-config
  "[:config :vibes]: the vibes, their order and settings (vibes.library)."
  [context]
  (fx/sub-val context get-in [:config :vibes]))

(defn vibe-current-id
  "The vibe playing (vibes.library/current-id): the first of :order until a
   press or a select makes one current; nil without vibes."
  [context]
  (fx/sub-val context library/current-id))

(defn- next-vibe-id [state]
  (library/next-id state (library/current-id state)))

(defn vibe-next-id
  "The vibe a VIBE tap goes to; nil without vibes or with one."
  [context]
  (let [next-id (fx/sub-val context next-vibe-id)]
    (when (not= next-id (fx/sub-ctx context vibe-current-id))
      next-id)))

(defn vibe-blackout?
  "Is BLACKOUT on?"
  [context]
  (boolean (fx/sub-val context get-in [:vibe-runtime :blackout?])))

(defn vibe-strip
  "One chip per vibe, in order: [{:id :name :color :energy :current? :next?}]."
  [context]
  (let [{:keys [order library]} (fx/sub-ctx context vibe-config)
        current (fx/sub-ctx context vibe-current-id)
        next-id (fx/sub-ctx context vibe-next-id)]
    (mapv (fn [id]
            (let [{vibe-name :name :keys [color energy]} (get library id)]
              {:id id :name vibe-name :color color :energy energy
               :current? (= id current) :next? (= id next-id)}))
          order)))

(defn- lit-cue-count
  "The cues lighting the room: those in active-cues while the transport plays."
  [state]
  (if (ex/playing? state) (count (ex/active-cues state)) 0))

(defn- learning-role
  "The vibe role a learn is for, or nil."
  [state]
  (let [{:keys [type role]} (get-in state [:backend :input :learning-target])]
    (when (= :vibe type) role)))

(defn vibe-header
  "The Perform tab's header: the current vibe's {:name :color :energy}, and
   :rotation, :blackout?, :banner and :learning (the role being learned).
   :banner is nil unless BLACKOUT is on, and it says dark only while nothing
   plays: a cue started from the grid during blackout lights the room though
   the vibe keys stay off (V2 A), and then it says that."
  [context]
  (let [{:keys [library]} (fx/sub-ctx context vibe-config)
        {vibe-name :name :keys [color energy]} (get library (fx/sub-ctx context vibe-current-id))
        blackout? (fx/sub-ctx context vibe-blackout?)
        lit (when blackout? (fx/sub-val context lit-cue-count))]
    {:name vibe-name :color color :energy energy
     :rotation (fx/sub-val context get-in [:vibe-runtime :rotation])
     :blackout? blackout?
     :banner (cond
               (not blackout?) nil
               (zero? lit) "BLACKOUT"
               (= 1 lit) "BLACKOUT · vibe keys off · a grid cue is playing"
               :else (str "BLACKOUT · vibe keys off · " lit " grid cues are playing"))
     :learning (fx/sub-val context learning-role)}))

(defn vibe-rotation
  "The Perform tab's auto-advance (vibes.rotation): {:auto? (the switch, or
   the saved :mode) :rotation (:active, :armed or :locked) :entered-at (ms,
   when the vibe's time began; nil before a vibe plays) :seconds :next (the
   name of the vibe it goes to; nil with one vibe) :blackout?}."
  [context]
  {:auto? (fx/sub-val context rotation/auto?)
   :rotation (fx/sub-val context get-in [:vibe-runtime :rotation])
   :entered-at (fx/sub-val context get-in [:vibe-runtime :entered-at])
   :seconds (fx/sub-val context rotation/seconds)
   :next (some->> (fx/sub-ctx context vibe-next-id) (get (:library (fx/sub-ctx context vibe-config))) :name)
   :blackout? (fx/sub-ctx context vibe-blackout?)})

(def ^:private vibe-role-labels
  {:hit "HIT" :accent "ACCENT" :flash "FLASH" :vibe "VIBE" :blackout "BLACKOUT"})

(defn vibe-role-bindings
  "The trigger-map keys of the controls bound to role, in a stable order."
  [context role]
  (->> (fx/sub-ctx context input-trigger-map)
       (keep (fn [[k action]] (when (library/binds-role? action role) k)))
       (sort-by pr-str)
       vec))

(defn- vibe-held?
  "Is role held: by the engine, or by the pulse of a press that played
   nothing (an effects-only role)?"
  [state role]
  (or (some? (get-in state [:vibe-runtime :held role]))
      (true? (get-in state [:playback :pulses role :held?]))))

(defn- vibe-playing-cell
  "The cell role's voice plays, while that cue still plays the voice's run:
   not once the clock ended it (a decay) or something started it again."
  [state role]
  (let [{:keys [cell run]} (get-in state [:vibe-runtime :voices role])]
    (when (and run (= run (get-in state [:playback :active-cues cell :run])))
      cell)))

(defn- vibe-learning? [state role]
  (= role (learning-role state)))

(defn- vibe-problem
  "Why role's last press had nothing to play (:empty-pool), until the next
   good one."
  [state role]
  (let [{problem-role :role why :why} (get-in state [:vibe-runtime :problem])]
    (when (= role problem-role) why)))

(defn- vibes? [state]
  (boolean (seq (get-in state [:config :vibes :order]))))

(defn- vibe-locked? [state]
  (= :locked (get-in state [:vibe-runtime :rotation])))

(defn- reaction-inputs
  "What the cells that react to a press depend on (vibes.reactions/reactions
   and what may play: the audience rule reads the rig's order, and since
   zones plan Z4 the zone maps and the column and show defaults)."
  [state]
  (-> (select-keys state [:chains :projectors :virtual-projectors :rig :zone-maps])
      (assoc :grid (select-keys (:grid state) [:column-targets])
             :config (select-keys (:config state) [:default-target]))))

(defn- vibe-reactions
  "The current vibe's tap reactions by role (vibes.reactions/reactions), and
   :quiet, the roles whose press plays no cue: HIT or ACCENT with nothing
   playable in its pool, FLASH without a playable cue. Recomputed when the
   chains, the vibes, the projectors or the current vibe change."
  [context]
  (let [id (fx/sub-ctx context vibe-current-id)
        {:keys [library]} (fx/sub-ctx context vibe-config)
        state (assoc-in (fx/sub-val context reaction-inputs) [:config :vibes] {:library library})
        vibe (get library id)]
    {:by-role (if vibe (reactions/reactions state id) {})
     :quiet (set (for [role [:hit :accent :flash]
                       :let [cells (if (= :flash role)
                                     (keep identity [(get-in vibe [:flash :cue])])
                                     (get-in vibe [role :cells]))]
                       :when (empty? (library/playable-cells state vibe cells))]
                   role))}))

(defn- vibe-pad-reactions
  "role's pad: the names of its reactions, and :effects-only? when its
   press plays no cue but moves some."
  [context role]
  (let [{:keys [by-role quiet]} (fx/sub-ctx context vibe-reactions)
        names (mapv :name (get by-role role))]
    {:reactions names
     :effects-only? (boolean (and (contains? quiet role) (seq names)))}))

(defn vibe-pad
  "One of the Perform tab's five pads: {:role :label :bindings (trigger-map
   keys) :held? :playing (the cell its voice plays, while it does)
   :learning? :problem :dim? :reactions (the names of the current vibe's
   cells that react to it) :effects-only?}, with :next (the next vibe's
   name) and :locked? for VIBE, and :on? for BLACKOUT. A pad lights while
   its role is held, whatever pressed it. Every pad is dim without vibes,
   and all but BLACKOUT during blackout."
  [context role]
  (let [blackout? (fx/sub-ctx context vibe-blackout?)]
    (cond-> {:role role
             :label (vibe-role-labels role)
             :bindings (fx/sub-ctx context vibe-role-bindings role)
             :held? (fx/sub-val context vibe-held? role)
             :playing (fx/sub-val context vibe-playing-cell role)
             :learning? (fx/sub-val context vibe-learning? role)
             :problem (fx/sub-val context vibe-problem role)
             :dim? (or (not (fx/sub-val context vibes?))
                       (and blackout? (not= :blackout role)))
             :reactions (:reactions (fx/sub-ctx context vibe-pad-reactions role))
             :effects-only? (:effects-only? (fx/sub-ctx context vibe-pad-reactions role))}
      (= :vibe role) (assoc :next (some->> (fx/sub-ctx context vibe-next-id)
                                           (get (:library (fx/sub-ctx context vibe-config)))
                                           :name)
                            :locked? (fx/sub-val context vibe-locked?))
      (= :blackout role) (assoc :on? blackout?))))


;; The Vibes tab (views/tabs/vibes.clj): its list, editor, picker
;; (views/components/vibe_picker.clj), Controls panel
;; (views/components/vibe_controls.clj), checklist and lint. A cell of the
;; picker subscribes to its own memberships, its own cue and what is edited,
;; and each level of its subs keeps its value while the level under it
;; stays equal, so a click on one cell re-renders that cell only (03
;; invariant 6).

(defn vibe-edited-id
  "The vibe the Vibes tab edits (vibes.library/edited-id): the one selected,
   or the first."
  [context]
  (fx/sub-val context library/edited-id))

(defn vibe-edit-target
  "What the Vibes tab edits: {:vibe (vibe-edited-id) :slot (what a click on
   its picker fills, vibes.library/slots)}."
  [context]
  {:vibe (fx/sub-ctx context vibe-edited-id)
   :slot (fx/sub-val context get-in [:ui :vibes :slot] :hit)})

(defn vibe-editor
  "The vibe the Vibes tab edits, as the library has it, with :slot and
   :current? (it is the vibe that plays); nil without vibes."
  [context]
  (let [{:keys [vibe slot]} (fx/sub-ctx context vibe-edit-target)]
    (when-let [v (get-in (fx/sub-ctx context vibe-config) [:library vibe])]
      (assoc v :slot slot :current? (= vibe (fx/sub-ctx context vibe-current-id))))))

(defn vibe-list
  "The Vibes tab's list: vibe-strip's entries, with :selected? for the one
   edited."
  [context]
  (let [edited (fx/sub-ctx context vibe-edited-id)]
    (mapv #(assoc % :selected? (= edited (:id %))) (fx/sub-ctx context vibe-strip))))

(defn vibe-cell-index
  "Who names each cell of the cue grid (grid :cue) or the effects grid
   (:fx), and as what (vibes.library/cell-index). Follows the vibes only."
  [context grid]
  (library/cell-index (fx/sub-ctx context vibe-config) grid))

(defn- indexed-cell-vibes [context grid col row]
  (get (fx/sub-ctx context vibe-cell-index grid) [col row] []))

(defn cell-vibes
  "Who names the cell [col row] of grid, and as what: [{:vibe :color :role
   :pos}]. The same vector while the cell's own memberships stay as they
   are, whatever another cell's do."
  [context grid col row]
  (fx/sub-ctx context indexed-cell-vibes grid col row))

(defn vibe-audience-groups
  "The refs that reach the audience (vibes.library/audience-groups), from
   the projectors and the rig (the automatic groups a scanner is in follow
   the order)."
  [context]
  (library/audience-groups {:projectors (fx/sub-val context :projectors)
                            :virtual-projectors (fx/sub-val context :virtual-projectors)
                            :rig (fx/sub-val context :rig)}))

(defn map-warnings
  "What the map editor says of map id (routing.zone-maps/map-warnings): its
   empty lanes, the ones with no projector or that reach the audience, the
   overrides to a group that's gone."
  [context id]
  (when-let [m (fx/sub-ctx context zone-map id)]
    (zone-maps/map-warnings m {:names (fx/sub-ctx context names-ctx)
                               :zones (fx/sub-ctx context map-zones)
                               :reach (fx/sub-ctx context ref-reach)
                               :audience (fx/sub-ctx context vibe-audience-groups)})))

(defn vibe-audience-routing
  "What the audience rule reads besides a cue and the audience groups
   (vibes.library/audience-cue?, zones plan Z4): the zone maps, the column
   defaults and the show's; so the picker re-runs when one changes, and
   only then."
  [context]
  {:zone-maps (fx/sub-val context :zone-maps)
   :grid {:column-targets (fx/sub-val context get-in [:grid :column-targets])}
   :config {:default-target (fx/sub-val context get-in [:config :default-target])}})

(defn- edited-allows-audience? [state]
  (boolean (:allow-audience? (library/vibe state (library/edited-id state)))))

(defn- cell-blocked [context col row]
  (let [groups (when-not (fx/sub-val context edited-allows-audience?)
                 (fx/sub-ctx context vibe-audience-groups))]
    (#{:timecode :crowd} (library/unplayable (fx/sub-ctx context vibe-audience-routing) [col row]
                                             (fx/sub-ctx context cue-cell-chain col row) groups))))

(defn vibe-cell-blocked
  "Why the vibe edited never plays the cue at [col row]: :timecode (it
   follows MIDI timecode) or :crowd (it can reach the audience, which the
   vibe doesn't allow); nil when it plays it, or the cell is empty."
  [context col row]
  (fx/sub-ctx context cell-blocked col row))

(defn- pick-state
  "A picker cell for what is edited ({:vibe :slot}), from the cell's
   memberships (cell-vibes)."
  [{:keys [vibe slot]} memberships]
  (let [mine (filterv #(= vibe (:vibe %)) memberships)
        in (some #(when (= slot (:role %)) %) mine)]
    {:in? (some? in)
     :pos (:pos in)
     :roles (into [] (comp (map :role) (remove #{slot})) mine)
     :others (into [] (comp (remove #(= vibe (:vibe %))) (map (juxt :vibe :color)) (distinct) (map second))
                   memberships)}))

(defn vibe-pick-cell
  "A cue cell of the Vibes tab's picker: {:in? (in the slot edited) :pos
   (its place in a HIT or ACCENT pool, from 1) :roles (its other roles in
   the vibe) :others (the colours of the other vibes it is in) :blocked
   (vibe-cell-blocked)}."
  [context col row]
  (assoc (pick-state (fx/sub-ctx context vibe-edit-target) (fx/sub-ctx context cell-vibes :cue col row))
         :blocked (fx/sub-ctx context vibe-cell-blocked col row)))

(defn vibe-pick-fx
  "An effect cell of the Vibes tab's picker: {:in? :pos :roles :others}, as
   vibe-pick-cell's."
  [context col row]
  (pick-state (fx/sub-ctx context vibe-edit-target) (fx/sub-ctx context cell-vibes :fx col row)))

(defn- pick-label [context col row]
  (select-keys (fx/sub-ctx context cell-display-data col row)
               [:name :first-preset-id :preset-count :timeline? :has-content?]))

(defn vibe-pick-label
  "What a cue cell of the picker shows of its cue (views/components/
   grid_cell.clj cell-label's keys, and :has-content?): not whether it
   plays, nor the grid's selection."
  [context col row]
  (fx/sub-ctx context pick-label col row))

(defn- pick-fx-label [context col row]
  (let [data (fx/sub-ctx context effect-cell-display-data col row)]
    (assoc (select-keys data [:name :first-effect-id :effect-count :effect-ids])
           :has-content? (:has-effects? data))))

(defn vibe-pick-fx-label
  "What an effect cell of the picker shows of its effects (grid_cell.clj
   effects-label's keys, and :has-content?): not whether it is on."
  [context col row]
  (fx/sub-ctx context pick-fx-label col row))

(defn- lint-world
  "What the lint, the checklist and the Vibes tab's tap reactions read
   (vibes.library/lint)."
  [state]
  {:config {:vibes (get-in state [:config :vibes])
            :input {:trigger-map (get-in state [:config :input :trigger-map])}}
   :chains (select-keys (:chains state) [:cue-chains :effect-chains])
   :projectors (:projectors state)
   :virtual-projectors (:virtual-projectors state)
   :zone-groups (:zone-groups state)
   :rig (:rig state)})

(defn vibe-lint
  "What to fix before the show, worst first (vibes.library/lint)."
  [context]
  (library/lint (fx/sub-val context lint-world)))

(defn vibe-edited-reactions
  "The tap reactions of the vibe the Vibes tab edits, by role: its own cells
   (vibes.reactions/reactions :own?), as its lint counts them."
  [context]
  (reactions/reactions (fx/sub-val context lint-world) (fx/sub-ctx context vibe-edited-id) :own? true))

(defn vibe-checklist
  "What is left to set up (vibes.library/checklist)."
  [context]
  (library/checklist (fx/sub-val context lint-world)))

(defn vibe-control
  "A row of the Vibes tab's Controls panel: {:role :label :bindings
   (trigger-map keys) :learning?}."
  [context role]
  {:role role
   :label (vibe-role-labels role)
   :bindings (fx/sub-ctx context vibe-role-bindings role)
   :learning? (fx/sub-val context vibe-learning? role)})

(defn vibe-wizard
  "Learn all, while it waits for a key: {:role (the one learned now) :step
   (from 1) :of :refused (the role a key pressed for it is already)}; nil
   otherwise."
  [context]
  (let [{:keys [queue done refused]} (fx/sub-val context get-in [:ui :vibes :wizard])
        learning (fx/sub-val context learning-role)]
    (when (and (seq queue) (= learning (first queue)))
      {:role learning :step (inc (count done)) :of (+ (count queue) (count done)) :refused refused})))


;; The faders (plans/2026-11-18-vibe-mode/05): the Controls panel's fader
;; rows and buttons, and the Perform tab's meters. Where a fader is never
;; comes here: the meters draw it from the input store.

(def ^:private fader-labels {:slider-1 "INTENSITY" :slider-2 "MORPH"})

(def ^:private meter-labels {:slider-1 "Intensity" :slider-2 "Morph"})

(defn vibe-fader-bindings
  "The input store keys of the controls learned for the fader slot
   ([:config :input :macro-map]), in a stable order."
  [context slot]
  (->> (fx/sub-val context get-in [:config :input :macro-map])
       (keep (fn [[k s]] (when (= slot s) k)))
       (sort-by pr-str)
       vec))

(defn- fader-learning? [state slot]
  (= {:type :macro :slot slot} (get-in state [:backend :input :learning-target])))

(defn vibe-fader-control
  "A fader row of the Vibes tab's Controls panel: {:slot :label :bindings
   (input store keys) :learning?}."
  [context slot]
  {:slot slot
   :label (fader-labels slot)
   :bindings (fx/sub-ctx context vibe-fader-bindings slot)
   :learning? (fx/sub-val context fader-learning? slot)})

(defn vibe-meter
  "A meter of the Perform tab: {:slot :label :wired?}, :wired? while
   something the vibe that plays plays follows the fader
   (vibes.faders/slot-wired?). Recomputed when the chains, the vibes or the
   vibe that plays change."
  [context slot]
  (let [{:keys [library]} (fx/sub-ctx context vibe-config)
        state {:chains (fx/sub-val context :chains) :config {:vibes {:library library}}}]
    {:slot slot
     :label (meter-labels slot)
     :wired? (faders/slot-wired? state (fx/sub-ctx context vibe-current-id) slot)}))

(defn- fader-cells [state]
  {:chains {:effect-chains (get-in state [:chains :effect-chains])}
   :grid {:size (get-in state [:grid :size])}})

(defn vibe-fader-actions
  "What the Controls panel's fader buttons can do: {:master (the cell of
   the Master, or nil) :free? (an effect cell is free for one) :vibe (the
   vibe the tab edits, for Add Morph) :vibe-name}."
  [context]
  (let [cells (fx/sub-val context fader-cells)
        {:keys [id] vibe-name :name} (fx/sub-ctx context vibe-editor)]
    {:master (faders/master-at cells)
     :free? (some? (reactions/first-empty-effect-cell cells))
     :vibe id
     :vibe-name vibe-name}))


;; Safety (plans/2026-11-18-vibe-mode/07): the show lock, the NO OUTPUT
;; banner and the audience marks on the Perform tab, and the Vibes tab's
;; lint badge

(defn vibe-show-lock
  "The Perform tab's show lock (events/show_lock.clj): {:locked? :notice
   (what it refused last, \"Move Cell refused\"; nil)}."
  [context]
  {:locked? (true? (fx/sub-val context get-in [:ui :show-lock?]))
   :notice (fx/sub-val context get-in [:ui :show-lock-notice :text])})

(defn- vibe-played?
  "Has vibe mode played in this session (it resets with the document): a
   vibe made current, or HIT, ACCENT or FLASH pressed?"
  [state]
  (let [pulses (get-in state [:playback :pulses])]
    (boolean (or (get-in state [:vibe-runtime :current])
                 (:hit pulses) (:accent pulses) (:flash pulses)))))

(defn vibe-output-banner
  "The Perform tab's red banner, once vibe mode has played in this session
   and while nothing goes to the lasers, as the toolbar says
   (connection-status): \"NO OUTPUT: press Stream\" while not streaming,
   \"NO OUTPUT: no projectors enabled\" while streaming to none; nil
   otherwise. A press still plays, into nothing."
  [context]
  (when (fx/sub-val context vibe-played?)
    (case (:state (fx/sub-ctx context connection-status))
      (:stopped :stopping) "NO OUTPUT: press Stream"
      :idle "NO OUTPUT: no projectors enabled"
      nil)))

(defn vibe-audience?
  "Does the vibe that plays allow audience cells (V6: its picks may then
   reach the crowd)?"
  [context]
  (boolean (:allow-audience? (get (:library (fx/sub-ctx context vibe-config)) (fx/sub-ctx context vibe-current-id)))))

(defn vibe-pad-audience?
  "Can role's press start a cue that reaches the audience: the vibe that
   plays allows audience cells, and one its role may play is one
   (vibes.library/audience-cell?): HIT's or ACCENT's pool, FLASH's cue."
  [context role]
  (boolean
   (when (and (#{:hit :accent :flash} role) (fx/sub-ctx context vibe-audience?))
     (let [vibe (get (:library (fx/sub-ctx context vibe-config)) (fx/sub-ctx context vibe-current-id))
           state (fx/sub-val context reaction-inputs)
           cells (if (= :flash role) (keep identity [(get-in vibe [:flash :cue])]) (get-in vibe [role :cells]))
           groups (library/audience-groups state)]
       (some #(library/audience-cell? state % groups) (library/playable-cells state vibe cells))))))

(defn vibes-tab-label
  "The Vibes tab's label: \"Vibes\", and the lint's error count while it
   has errors (\"Vibes ⚠ 2\")."
  [context]
  (let [n (count (filter #(= :error (:level %)) (fx/sub-ctx context vibe-lint)))]
    (if (pos? n) (str "Vibes ⚠ " n) "Vibes")))
