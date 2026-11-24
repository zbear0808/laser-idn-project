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
            [laser-show.state.clipboard :as clipboard-rules]
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
            [laser-show.events.handlers.chain.helpers :as chain-helpers]
            [laser-show.output.driver :as driver]
            [laser-show.routing.refs :as refs]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.step-grid :as step-grid]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-maps :as zone-maps]
            [laser-show.routing.zone-patterns :as zone-patterns]
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

(defn can-paste?
  "Is a Paste into target (a clipboard target: :cue-chain-items,
   :item-effects, :cue-chain, :effects-cell, :step-grid) on? When the
   clipboard holds a clip target takes (state.clipboard/accepts?), and while
   nothing is copied in this app yet: a paste then reads the OS clipboard,
   and says when it finds nothing (keyboard plan D13 B)."
  [context target]
  (let [clip (fx/sub-ctx context clipboard)]
    (or (nil? clip) (clipboard-rules/accepts? clip target))))

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

(defn preview-visible?
  "Is the preview panel on (View > Show Preview, [:ui :preview-visible?];
   unset means on)? The tabs that never show it still don't (root)."
  [context]
  (not (false? (fx/sub-val context get-in [:ui :preview-visible?]))))

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


(defn cell-selected?
  "Is [col row] its grid's selected cell: the cue grid's
   (selected-grid-cell) for action-type :trigger-cue, the effects grid's
   (selected-effects-cell) for :trigger-effect? true or false, false while
   none is. The cells' display data read this, not the selection, so a
   move rebuilds only the old cell's and the new one's."
  [context action-type col row]
  (= [col row] (case action-type
                 :trigger-cue (fx/sub-ctx context selected-grid-cell)
                 :trigger-effect (fx/sub-ctx context selected-effects-cell))))

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
   - cell-selected? (whether this cell is the selected one)
   
   Returns map with:
   - :col, :row - position
   - :name - custom name for the cue chain (nil if not set)
   - :cue-chain - the full cue chain data
   - :preset-count - number of presets in the cue chain
   - :first-preset-id - id of first preset (for display)
   - :active? - is this cell playing?
   - :selected? - is this cell currently selected?
   - :timeline? - is this a timeline cue?
   - :has-content? - does cell have any presets or timeline configuration?
   The presets, :timeline? and :has-content? are cue-chains/grid-content's,
   the rule a press of the cell follows too."
  [context col row]
  (let [cue-chain-data (fx/sub-ctx context cue-cell-chain col row)]
    (merge {:col col
            :row row
            :name (:name cue-chain-data)
            :cue-chain cue-chain-data
            :active? (fx/sub-ctx context cue-cell-active? col row)
            :selected? (fx/sub-ctx context cell-selected? :trigger-cue col row)}
           (cue-chains/grid-content cue-chain-data))))

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

(defn cue-label-data
  "What the cue at [col row] shows as its label (views/components/
   grid_cell.clj cell-label's keys, and :has-content?): {:name
   :first-preset-id :preset-count :timeline? :has-content?}, all but :name
   cue-chains/grid-content's. Only that cell's chain: not whether it plays,
   nor the grid's selection."
  [context col row]
  (let [cue (fx/sub-ctx context cue-cell-chain col row)]
    (assoc (cue-chains/grid-content cue) :name (:name cue))))

(defn active-cue-cells
  "The cells with an intent ([:playback :active-cues]), as a set. A pause,
   a seek or a new run inside an intent leaves it equal."
  [context]
  (set (keys (fx/sub-val context ex/active-cues))))

(defn- cue-cells-in-grid-order
  "The cells with an intent (active-cue-cells), row by row as the grid
   reads: A1, A2 … B1 … Sorted again only when that set changes."
  [context]
  (vec (sort-by (fn [[col row]] [row col]) (fx/sub-ctx context active-cue-cells))))

(defn playing-cues
  "The cues that play, in grid order, each as what its cell's label is made
   from (cue-label-data): {:col :row :name :first-preset-id :preset-count
   :timeline?}. None while Stop holds them (output-held?): nothing is drawn
   then."
  [context]
  (if (fx/sub-ctx context playing?)
    (mapv (fn [[col row]]
            (-> (fx/sub-ctx context cue-label-data col row)
                (select-keys [:name :first-preset-id :preset-count :timeline?])
                (assoc :col col :row row)))
          (fx/sub-ctx context cue-cells-in-grid-order))
    []))

;; Level 2: Computed Effect Cell Subscriptions


(defn effect-cell-chain
  "Effect chain stored at [col row], or nil when the cell is empty.
   Only depends on this cell's entry, not the whole :chains domain."
  [context col row]
  (fx/sub-val context get-in [:chains :effect-chains [col row]]))

(defn stored-effect-cell
  "What the effects cell at [col row] stores, on or off
   (chains/stored-effect-content of its saved :items): {:has-content?
   :leaves}. Only depends on that cell's items."
  [context col row]
  (chains/stored-effect-content (fx/sub-val context get-in [:chains :effect-chains [col row] :items])))

(defn effect-cell-display-data
  "Computed display data for an effects grid cell.
   
   Depends on: effect-cell-chain (this cell's effect chain),
   stored-effect-cell (what it stores) and cell-selected? (whether it is
   the selected one)
   
   Returns map with:
   - :col, :row - position
   - :name - custom name for the effect chain (nil if not set)
   - :effect-count - number of effects it stores (in groups too, enabled or not)
   - :first-effect-id - id of first effect (not group)
   - :effect-ids - ids of the effects, in chain order
   - :active? - is effect chain active?
   - :selected? - is effect chain cell selected?
   - :has-effects? - does cell store any effects?
   - :has-content? - does cell hold anything, effects or groups? A cell
     that doesn't is empty: New offers to fill it, and it can't be dragged
   - :display-text - text to display in the cell"
  [context col row]
  (let [cell-data (fx/sub-ctx context effect-cell-chain col row)
        {:keys [has-content? leaves]} (fx/sub-ctx context stored-effect-cell col row)
        first-effect (first leaves)
        effect-count (count leaves)
        selected? (fx/sub-ctx context cell-selected? :trigger-effect col row)]
    {:col col
     :row row
     :name (:name cell-data)
     :effect-count effect-count
     :first-effect-id (:effect-id first-effect)
     :effect-ids (mapv :effect-id leaves)
     :active? (:active? cell-data false)
     :selected? selected?
     :has-effects? (pos? effect-count)
     :has-content? has-content?
     :display-text (when first-effect
                     (str (name (:effect-id first-effect))
                          (when (> effect-count 1)
                            (str " +" (dec effect-count)))))}))

(defn effects-grid-empty?
  "True while no effects cell stores an effect, on or off (groups don't
   count), but for Masters: a cell whose one stored effect is a Master
   (vibes.faders/master-only?) doesn't count, so a new project's grid, which
   has one, still says what effect cells are for. A Master with a disabled
   effect beside it, or disabled itself, counts. Only the Effects tab's
   hint follows it."
  [context]
  (not-any? (fn [cell]
              (let [leaves (:leaves (chains/stored-effect-content (:items cell)))]
                (and (seq leaves)
                     (not (and (= 1 (count leaves)) (faders/master-only? cell))))))
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
  "Streaming status for the toolbar, from one published status.
   
   Depends on: [:backend :streaming :running?] (the operator's intent, which
   picks the button) and the output runtime's status (what each output
   reports: :streaming, :starting or :error). Only a :streaming report
   counts: a drained command queue doesn't mean an output streams. The
   rig's names only label the errors.
   
   Returns map with:
   - :running? - streaming is on
   - :state - :stopped, :stopping, :starting, :mixed (some outputs stream,
     the others still start), :streaming (every wanted output streams),
     :error or :idle (on, with no enabled projectors)
   - :status-text - e.g. \"Streaming to 1 of 2 · 1 starting\", \"0 of 2
     streaming · 1 error\", \"Updating outputs…\" or \"No projectors
     enabled\" (:idle). It says N of M only of a settled status: nothing
     pending, and a report for each of the M wanted outputs, none for an
     engine still being retired
   - :errors - [\"<projector name>: <error>\" …], by projector id"
  [context]
  (let [running? (boolean (fx/sub-val context get-in [:backend :streaming :running?]))
        {:keys [engines wanted pending? projectors] :as status} (fx/sub-ctx context streaming-status)
        names (fx/sub-val context :projectors)
        wanted (or wanted 0)
        {streaming :streaming starting :starting :or {streaming 0 starting 0}}
        (frequencies (map :state (vals projectors)))
        errors (vec (for [[id {:keys [state error]}] (sort-by key projectors)
                          :when (= :error state)]
                      (str (or (get-in names [id :name]) (name id)) ": " error)))
        settled? (and (some? status) (not pending?) (= wanted (count projectors)))
        ratio (when (and settled? (pos? wanted))
                (str (if (pos? streaming)
                       (str "Streaming to " streaming " of " wanted)
                       (str "0 of " wanted " streaming"))
                     (when (pos? starting) (str " · " starting " starting"))))
        state (cond
                (not running?) (if (pos? (or engines 0)) :stopping :stopped)
                (seq errors) :error
                (not settled?) :starting
                (zero? wanted) :idle
                (= streaming wanted) :streaming
                (and (pos? streaming) (= wanted (+ streaming starting))) :mixed
                :else :starting)]
    {:running? running?
     :state state
     :errors errors
     :status-text (case state
                    :stopped "Not streaming"
                    :stopping "Stopping…"
                    :idle "No projectors enabled"
                    :starting (if (or (pos? streaming) (> (count projectors) wanted))
                                "Updating outputs…"
                                "Starting…")
                    (:mixed :streaming) ratio
                    :error (str (or ratio "Updating outputs…")
                                " · " (count errors) (if (= 1 (count errors)) " error" " errors")))}))


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
  "Get the full config of the currently selected projector, with its :id;
   nil when none is selected or the selected id has no projector (an undo
   took it away), so the inspector shows no selection.
   Depends on: projector-ui domain and projectors domain"
  [context]
  (let [active-id (:active-projector (fx/sub-val context :projector-ui))
        projector (when active-id (get (fx/sub-val context :projectors) active-id))]
    (when projector
      (assoc projector :id active-id))))

(defn discovered-devices
  "Get the devices from the last scan.
   Depends on: projector-ui domain"
  [context]
  (:discovered-devices (fx/sub-val context :projector-ui) []))

(defn projector-scanning?
  "Check if a device scan is in progress.
   Depends on: projector-ui domain"
  [context]
  (:scanning? (fx/sub-val context :projector-ui) false))


(defn enabled-projector-services
  "The found-output keys (rig/output-key: [host service-id] for IDN, [driver
   device-key output-id] for another driver's device) of the ENABLED
   projectors. No view uses it since zones Z2's rig strip.
   Depends on: projectors domain (which IS the map of projector-id -> config)"
  [context]
  (into #{} (comp (filter rig/enabled?) (keep rig/output-key)) (vals (fx/sub-val context :projectors))))

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
  "The manual map (06 SQ-7): [:zone-map :active] as the UI shows it, Left
   / Right for an id that is no map. A state fact, not the map a tick
   routes by: a due switch or a running Map cycle's pick comes first
   (routing.zone-maps/effective-map-id)."
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

(defn effect-ref-inputs
  "The effect cells' chains as their stored references read them
   (routing.refs/chain-ref-usage: the zone catalogue, the rig report):
   {cell {:items items}} for each cell with items, so switching a cell on or
   off leaves it equal."
  [context]
  (into {}
        (keep (fn [[cell chain]]
                (when (and (map? chain) (seq (:items chain)))
                  [cell {:items (:items chain)}])))
        (fx/sub-val context get-in [:chains :effect-chains])))

(defn zones-in-use
  "Every zone the show names (Z4-Q1): in a cue (routing.refs/cue-refs, its
   zone patterns too), in an effect cell's zone patterns (a place, a source
   or a lit step, a keyframe's too, the pattern on or off), a column's
   default and the show's, sorted. Not a zone a label, a map's override or
   the preview alone names."
  [context]
  (let [usage (refs/chain-ref-usage (fx/sub-val context get-in [:chains :cue-chains])
                                    (fx/sub-ctx context effect-ref-inputs))
        refs (concat (keys usage)
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

(defn map-cycle-running?
  "Is a Map cycle enabled and unpaused (zones plan Z5): an enabled Map
   cycle in a cell that is on, and no switch that landed paused it? A
   state fact: it says nothing of the map a tick picks, which can be none."
  [context]
  (and (zone-maps/cycle-on? {:chains {:effect-chains (fx/sub-val context get-in [:chains :effect-chains])}})
       (not (fx/sub-val context get-in [:zone-map :cycle-paused?]))))

(defn manual-map-marker-id
  "The map the Manual mark is on, the map editor's list's and the pads'
   alike: the manual map's id (active-zone-map), or nil while a Map cycle
   runs (map-cycle-running?). nil means no manual mark, not no map routed."
  [context]
  (when-not (fx/sub-ctx context map-cycle-running?)
    (:id (fx/sub-ctx context active-zone-map))))

(defn map-pad
  "One zone map pad, its booleans and keys only (06 §4.5): {:id :name
   :active? (the Manual mark is on it, manual-map-marker-id: on none while
   a Map cycle runs, zones plan Z5) :pending (nil, or :beat or :bar while
   a switch to it waits) :bar? (its Switch on the bar) :learning?
   :bindings}."
  [context id]
  (let [pending (fx/sub-val context get-in [:zone-map :pending])
        marker (fx/sub-ctx context manual-map-marker-id)]
    {:id id
     :name (:name (fx/sub-ctx context zone-map id))
     :active? (and (some? marker) (= id marker))
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
  "The map the map editor shows, and its Use now chooses: the one selected
   while it is there, else the manual map (active-zone-map). Selecting one
   switches nothing."
  [context]
  (let [id (fx/sub-val context get-in [:ui :zone-maps :selected])]
    (if (and id (fx/sub-ctx context zone-map id))
      id
      (:id (fx/sub-ctx context active-zone-map)))))

(defn editor-map-switch
  "What the map editor says of how the map is chosen, editing map id
   (plans/2026-11-23-cljfx-reactivity/29): {:mode (:manual while the
   Manual mark shows, manual-map-marker-id; :cycle while a Map cycle runs)
   :manual-name (the manual map's, active-zone-map) :queued? (a switch
   waits: [:zone-map :pending], CYCLE's too) :can-use-now? (would Use now
   change anything: id is a map, and a Map cycle runs, a switch waits, or
   the stored manual id isn't id, a dangling one too)}. State facts only:
   never the map a tick routed by, which it can't know."
  [context id]
  (let [cycle? (nil? (fx/sub-ctx context manual-map-marker-id))
        queued? (some? (fx/sub-val context get-in [:zone-map :pending]))]
    {:mode (if cycle? :cycle :manual)
     :manual-name (:name (fx/sub-ctx context active-zone-map))
     :queued? queued?
     :can-use-now? (boolean (and (fx/sub-ctx context zone-map id)
                                 (or cycle? queued? (not= id (fx/sub-val context get-in [:zone-map :active])))))}))

(defn cue-target-explain
  "How each level of the cue at [col row] resolves, its column's and the
   show's defaults included (routing.targets/explain-cue)."
  [context col row]
  (targets/explain-cue (fx/sub-ctx context cue-cell-chain col row)
                       (fx/sub-ctx context column-target col)
                       (fx/sub-ctx context default-target)
                       col))


;; Level 2: Zone patterns (zones plan Z5)


(defn cue-zone-pattern
  "The zone pattern of the cue at [col row]: the first of its
   :zone-patterns (v1 edits one), or nil."
  [context col row]
  (first (fx/sub-val context get-in [:chains :cue-chains [col row] :zone-patterns])))

(defn zone-pattern-at
  "The effect at effect-path of domain's entity (:cue-zone-patterns, a
   cue's patterns; :effect-chains, a cell's items), or nil."
  [context domain entity-key effect-path]
  (when (sequential? effect-path)
    (case domain
      :cue-zone-patterns (fx/sub-val context get-in (into [:chains :cue-chains entity-key :zone-patterns] effect-path))
      :effect-chains (fx/sub-val context get-in (into [:chains :effect-chains entity-key :items] effect-path))
      nil)))

(defn pattern-world
  "What a pattern's lane and warnings read of the show
   (routing.zone-patterns): the rig, the groups, the maps and the map in
   use, the keys learned. Its fold line reads less: pattern-fold-world."
  [context]
  {:projectors (fx/sub-val context :projectors)
   :rig (fx/sub-val context :rig)
   :zone-groups (fx/sub-val context :zone-groups)
   :zone-maps (fx/sub-val context :zone-maps)
   :zone-map (fx/sub-val context :zone-map)
   :config {:input {:trigger-map (fx/sub-val context get-in [:config :input :trigger-map])}}})

(def ^:private fold-projector-keys
  "What a fold line reads of a projector: its name, where one with no place
   yet goes in the order (routing.rig/placement-key: its host and service),
   its switches and its groups."
  [:name :host :service-id :enabled? :exclude-from-all? :zone-groups])

(defn- fold-map-id
  "The map a fold line says: a tick's at beat 0 without a Map cycle's pick
   (routing.zone-maps/effective-map-id), as routing.zone-patterns/fold-text
   works it out."
  [state]
  (zone-maps/effective-map-id state 0.0 nil))

(defn pattern-fold-world
  "What a pattern's fold line reads of the show (routing.zone-patterns/
   fold-text), as a state: the projectors' names, order, switches and
   groups (fold-projector-keys); the rig's order and middle laser; the
   groups' names; and the map the line says (fold-map-id: a switch due by
   beat 0, else the manual map, Left / Right for one that is gone) as
   [:zone-map :active], with its name, lanes and overrides when it is
   yours. Not the keys learned, the zones' labels, a Map cycle, nor what
   else a projector or a map holds."
  [context]
  (let [map-id (fx/sub-val context fold-map-id)]
    {:projectors (into {} (map (fn [[id p]] [id (select-keys p fold-projector-keys)])) (fx/sub-val context :projectors))
     :rig (select-keys (fx/sub-val context :rig) [:order :middle])
     :zone-groups (into {} (map (fn [[id g]] [id (select-keys g [:name])])) (fx/sub-val context :zone-groups))
     :zone-maps (if (zone-maps/built-in? map-id)
                  {}
                  {map-id (select-keys (fx/sub-ctx context zone-map map-id) [:name :lanes :overrides])})
     :zone-map {:active map-id}}))

(defn- fold-names
  "What a fold line names refs by (routing.targets/names of
   pattern-fold-world's target groups). It says a zone by its letters, so
   no labels."
  [context]
  (targets/names (rig/target-groups (fx/sub-ctx context pattern-fold-world)) {}))

(defn pattern-fold
  "How a pattern whose lane is zones folds onto this rig under the map in
   use, the ⓘ line of the pattern window and of the step grid window
   (routing.zone-patterns/fold-text, which reads a pattern's lane alone).
   zones: the lane as the window shows it, in order. Keyed by it, over
   pattern-fold-world: the pattern's rate or steps, a key learned or a
   zone's label leave the line be."
  [context zones]
  (zone-patterns/fold-text (fx/sub-ctx context pattern-fold-world) {:zones zones} (fx/sub-ctx context fold-names)))

(defn- holds-effect?
  "Does an effects cell's chain store an effect-id, on or off, in a
   disabled group or not (chains/stored-effect-content)?"
  [effect-id chain]
  (boolean (some #(= effect-id (:effect-id %)) (:leaves (chains/stored-effect-content (:items chain))))))

(defn zone-patterns-in-use?
  "Does the show have a zone pattern anywhere, on or off: on a cue, or in
   an effects cell (07 §7.3)? The pattern pads show then."
  [context]
  (boolean
   (or (some (comp seq :zone-patterns) (vals (fx/sub-val context get-in [:chains :cue-chains])))
       (some #(holds-effect? :zone-pattern %) (vals (fx/sub-val context get-in [:chains :effect-chains]))))))

(defn- map-cycles?
  "Is there a Map cycle in an effects cell, on or off?"
  [context]
  (boolean (some #(holds-effect? :zone-map-cycle %) (vals (fx/sub-val context get-in [:chains :effect-chains])))))

(defn zone-map-pads-shown?
  "Do the zone map pads show: while the show draws into a zone (Z4-Q1 A),
   or has a zone pattern or a Map cycle anywhere (zones plan Z5), which
   move pictures between zones or pick the map."
  [context]
  (boolean (or (fx/sub-ctx context zones-in-use?)
               (fx/sub-ctx context zone-patterns-in-use?)
               (fx/sub-ctx context map-cycles?))))

(defn pattern-pad-bindings
  "The trigger-map keys learned for pattern pad pad (:step: the Zone step
   key's), in a stable order."
  [context pad]
  (->> (fx/sub-val context get-in [:config :input :trigger-map])
       (keep (fn [[k action]]
               (when (if (= :step pad)
                       (= :zone-step (:type action))
                       (and (= :pattern-pad (:type action)) (= pad (:target action))))
                 k)))
       (sort-by pr-str)
       vec))

(defn pattern-pad
  "One pattern pad (07 §7.3), its booleans and keys only: {:id :name
   :active? (the pads' style is its; As set while they set none) :pending
   (nil, or :beat or :bar while a switch to it waits) :bar? (its Switch on
   the bar) :learning? :bindings}."
  [context pad]
  (let [{:keys [style pending]} (fx/sub-val context :pattern-pads)]
    {:id pad
     :name (zone-patterns/pad-name pad)
     :active? (= pad (zone-patterns/style-pad style))
     :pending (when (and pending (= pad (zone-patterns/style-pad (:style pending))))
                (if (:bar? pending) :bar :beat))
     :bar? (true? (fx/sub-val context get-in [:config :pattern-pads pad :bar?]))
     :learning? (= {:type :pattern-pad :target pad} (fx/sub-val context get-in [:backend :input :learning-target]))
     :bindings (fx/sub-ctx context pattern-pad-bindings pad)}))

(defn step-pad
  "STEP (07 §7.3): {:bindings (the Zone step key's) :learning?}; the count
   it shows is the newest tick's."
  [context]
  {:bindings (fx/sub-ctx context pattern-pad-bindings :step)
   :learning? (= {:type :zone-step} (fx/sub-val context get-in [:backend :input :learning-target]))})

(defn map-cycle-state
  "The CYCLE pad (07 §7.4): {:on? (a Map cycle cell is on) :running? (it
   picks the map: on, and no map pad's press paused it) :paused? :pending
   (nil, or :beat or :bar while CYCLE waits) :bar? :learning? :bindings
   :maps (the running cycle's maps by name) :description (what it does,
   routing.zone-patterns/describe-cycle)}."
  [context]
  (let [state {:chains {:effect-chains (fx/sub-val context get-in [:chains :effect-chains])}
               :zone-maps (fx/sub-val context :zone-maps)}
        on? (zone-maps/cycle-on? state)
        paused? (true? (fx/sub-val context get-in [:zone-map :cycle-paused?]))
        pending (fx/sub-val context get-in [:zone-map :pending])
        running-cycle (when on? (zone-patterns/active-cycle state))]
    {:on? on?
     :running? (and on? (not paused?))
     :paused? (and on? paused?)
     :pending (when (= :cycle (:map pending)) (if (:bar? pending) :bar :beat))
     :bar? (true? (fx/sub-val context get-in [:config :zone-map-pads :cycle :bar?]))
     :learning? (= {:type :zone-map :target :cycle} (fx/sub-val context get-in [:backend :input :learning-target]))
     :bindings (fx/sub-ctx context map-pad-bindings :cycle)
     :maps (if running-cycle
             (into [] (keep #(:name (zone-maps/map-by-id state %))) (:maps (:params running-cycle)))
             [])
     :description (when running-cycle (zone-patterns/describe-cycle (:params running-cycle) state))}))


;; Level 2: The step grid window (zones plan Z6, 08 §4, §7)


(defn- grid-row-text
  "A row as its label says it: a zone and where it goes under zone-map
   (\"A → Left\"), a group or laser by name."
  [names zone-map ref]
  (if (targets/zone-ref? ref)
    (targets/target-text names zone-map ref)
    (targets/ref-name names ref)))

(defn- and-words [xs]
  (if (< (count xs) 3)
    (str/join " and " xs)
    (str (str/join ", " (butlast xs)) " and " (last xs))))

(defn- wrap-note
  "The rows that go to one lane under zone-map (S-5): \"A and C both go to
   Left under Left / Right.\"; nil when none share one, and under Unison,
   whose fold line says every zone is All."
  [names zone-map rows]
  (let [shared (->> rows
                    (filter targets/zone-ref?)
                    (group-by #(targets/zone-lane zone-map %))
                    (filter (fn [[lane zones]] (and lane (next zones))))
                    (sort-by (comp targets/zone-index first second)))]
    (when (and (seq shared) (not= :map/unison (:id zone-map)))
      (str (str/join "; " (for [[lane zones] shared]
                            (str (and-words (map targets/zone-letter zones))
                                 (if (next (next zones)) " all go to " " both go to ")
                                 (targets/ref-name names lane))))
           " under " (:name zone-map) "."))))

(defn- every-laser-note
  "What became of Every laser in a new grid (S-4), zones the zones it became."
  [zones]
  (let [letters (mapv targets/zone-letter zones)
        idx (mapv targets/zone-index zones)
        run? (and (< 2 (count zones)) (= idx (vec (range (first idx) (inc (peek idx))))))]
    (str "Every laser can't be a row, because the number of lasers changes. It became zones "
         (if run? (str (first letters) "–" (peek letters)) (and-words letters))
         ": pick a zone map that sends them where you want.")))

(defn step-grid
  "The step grid window's grid at target ({:domain :entity-key
   :effect-instance-id}: the zone pattern with that id among a cue's
   patterns or an effects cell's items, wherever it is among them now,
   routing.step-grid/locate-pattern), or nil when they hold no zone pattern
   with that id: {:id (the pattern's, for the playhead) :params
   (the grid as it shows, routing.step-grid/as-grid: a pattern that isn't
   one yet shows the grid it becomes) :grid? (it is one already) :rows
   [{:ref :text}] (each row and where it goes under the map in use) :lit
   (each step's refs, as sets) :length :cursor [row step] :keyframes? (its
   keyframes are on: the grid is greyed out) :locked? (the show lock is on)
   :wrap-note (S-5) :every-laser-note (S-4, once it became zones)}."
  [context {:keys [domain entity-key effect-instance-id] :as target}]
  (let [items-path (chain-helpers/zone-pattern-items-path domain entity-key)
        fx (when items-path
             (:instance (step-grid/locate-pattern (fx/sub-val context get-in items-path) effect-instance-id)))]
    (when fx
      (let [world (fx/sub-ctx context pattern-world)
            params (step-grid/as-grid (:params fx) (count (rig/e-order (rig/rig-inputs world))))
            names (fx/sub-ctx context names-ctx)
            zone-map (fx/sub-ctx context active-zone-map)
            rows (step-grid/rows params)
            len (step-grid/length params)
            dialog (fx/sub-ctx context dialog-data :step-grid-editor)
            [r s] (or (when (= target (:target dialog)) (:cursor dialog)) [0 0])]
        {:id (:id fx)
         :params params
         :grid? (step-grid/grid? (:params fx))
         :rows (mapv (fn [ref] {:ref ref :text (grid-row-text names zone-map ref)}) rows)
         :lit (mapv set (:steps params))
         :length len
         :cursor [(max 0 (min (dec (max 1 (count rows))) (long r))) (max 0 (min (dec (max 1 len)) (long s)))]
         :keyframes? (true? (get-in fx [:keyframe-modulator :enabled?]))
         :locked? (true? (fx/sub-val context get-in [:ui :show-lock?]))
         :wrap-note (wrap-note names zone-map rows)
         :every-laser-note (when (and (= target (:target dialog)) (seq (:every-laser dialog)))
                             (every-laser-note (:every-laser dialog)))}))))



;; Level 2: The rig strip (views/components/rig_strip.clj)


(defn- found-outputs
  "#{key} of the outputs the last scan found, as rig/output-key has them:
   [host service-id] of an IDN device's (service 0 for a device with none),
   [driver device-key output-id] of another driver's."
  [devices]
  (into #{} (mapcat (fn [{:keys [driver address services device-key outputs]}]
                      (cond
                        (not= :idn (or driver :idn)) (map (fn [o] [driver device-key (:output-id o)]) outputs)
                        (seq services) (map (fn [s] [address (or (:service-id s) 0)]) services)
                        :else [[address 0]])))
        devices))

(defn rig-cards
  "One map per card of the rig strip, in rig order (routing.rig): {:id
   :position (the laser's place in E, from 1; nil when it is off or excluded
   from All) :name :host :service-id :driver :driver-installed? :host-label (driver/summary:
   \"10.0.0.7 #1\" for IDN, \"LSPS · 127.0.0.1:7266\" for another driver)
   :address-lines (driver/summary-parts: [\"LSPS\" \"127.0.0.1:7266\"])
   :enabled? :excluded? :tags :identity (rig/identity-label) :selected?
   :found? (false once a scan this session didn't find it: an IDN laser, or
   another driver's output a scan found ([:device …] stable id) when its
   driver has :discover; never one added by hand, or of a driver without
   :discover, which no scan looks for)
   :multi-output? (another output of its device is a card too: by host for
   IDN, by driver and device key for another driver's)
   :device-key (another driver's device's key, for its card menu; nil for
   IDN and one added by hand)
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
        devices (frequencies (keep #(some-> (rig/output-key %) pop) (vals projectors)))]
    (mapv (fn [id]
            (let [{:keys [host service-id tags] :as p} (get projectors id)
                  driver-id (driver/projector-driver-id p)
                  idn (= :idn driver-id)
                  output (rig/output-key p)
                  looked-for? (or idn (and output (get-in descriptors [driver-id :discover?])))]
              {:id id
               :position (position id)
               :name (rig/projector-name {:projectors projectors} id)
               :host host
               :service-id service-id
               :driver driver-id
               :driver-installed? (or idn (contains? descriptors driver-id))
               :host-label (driver/summary p descriptors)
               :address-lines (driver/summary-parts p descriptors)
               :enabled? (rig/enabled? p)
               :excluded? (true? (:exclude-from-all? p))
               :tags (set tags)
               :identity (rig/identity-label p)
               :selected? (= id selected)
               :found? (or (nil? found) (not looked-for?) (contains? found output))
               :multi-output? (< 1 (get devices (some-> output pop) 0))
               :device-key (when-not idn (second output))
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

(def ^:private cell-action-types
  "The actions that trigger a grid cell: a cue cell, an effects cell."
  #{:trigger-cue :trigger-effect})

(defn cell-trigger-targets
  "The grid cells an input triggers, #{[action-type [col row]]}: an entry
   for each cell a :trigger-cue or :trigger-effect binding of the trigger
   map names, whatever its key and :behavior, and whether the cell plays
   or holds anything. Both grids share it, so a change to the trigger map
   walks it once; each cell reads its own entry (cell-has-trigger?). Only
   the cells' ⚡ reads it: the trigger editor lists the keys themselves,
   and the resolver plays the bindings."
  [context]
  (into #{}
        (keep (fn [{:keys [type target]}]
                (when (contains? cell-action-types type) [type target])))
        (vals (fx/sub-ctx context input-trigger-map))))

(defn cell-has-trigger?
  "Does an input trigger the cell [col row] of action-type's grid
   (cell-trigger-targets)? true or false, so another cell's bindings
   changing, or a binding's :behavior, leaves it be."
  [context action-type col row]
  (contains? (fx/sub-ctx context cell-trigger-targets) [action-type [col row]]))

(defn cell-learning?
  "Is a learn waiting for the input of the cell [col row] of action-type's
   grid (input-learning-target)? true or false: a learn for anything else,
   or none, is false, so one starting or ending leaves the other cells be."
  [context action-type col row]
  (= {:type action-type :target [col row]} (fx/sub-ctx context input-learning-target)))

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

;; Who a cue can reach (vibes.library's audience rule, V6; zones plans Z4,
;; Z5), as the pads and the Vibes tab's picker read it: one routing
;; projection, so they agree with each other and with the full state.

(defn- audience-rig-inputs
  "What the audience groups are folded from (vibes.library/audience-groups):
   the projectors, the virtual projectors (one counts when it or its
   projector scans the crowd) and the rig (the automatic groups a scanner
   is in follow the order)."
  [context]
  {:projectors (fx/sub-val context :projectors)
   :virtual-projectors (fx/sub-val context :virtual-projectors)
   :rig (fx/sub-val context :rig)})

(defn vibe-audience-groups
  "The refs that reach the audience (vibes.library/audience-groups), from
   audience-rig-inputs alone: a zone map or a default changing leaves them
   be."
  [context]
  (library/audience-groups (fx/sub-ctx context audience-rig-inputs)))

(defn vibe-audience-routing
  "What the audience rule reads besides the cue or effect cell it asks
   about (vibes.library/audience-cue?, unplayable, playable-cells,
   audience-fx?), as a state: audience-rig-inputs, the group definitions
   (a zone pattern's lane drops a group that isn't one, zones plan Z5), the
   zone maps, and the column defaults and the show's (zones plan Z4). The
   pads and the picker add the chains they ask about, so they re-run when
   one of these changes, and only then: not for the map in use or the show
   clock's status."
  [context]
  (assoc (fx/sub-ctx context audience-rig-inputs)
         :zone-groups (fx/sub-val context :zone-groups)
         :zone-maps (fx/sub-val context :zone-maps)
         :grid {:column-targets (fx/sub-val context get-in [:grid :column-targets])}
         :config {:default-target (fx/sub-val context get-in [:config :default-target])}))

(defn- slot-chains
  "{cell chain} of the cues at cells (cue-cell-chain), each read now."
  [context cells]
  (into {} (map (fn [[col row :as cell]] [cell (fx/sub-ctx context cue-cell-chain col row)])) cells))

(defn- vibe-reactions
  "The current vibe's tap reactions by role (vibes.reactions/reactions): its
   FX set, its flash FX, the effects grid's cells that are on and the cues
   its slots name, whether those play or not; {} without vibes. Recomputed
   when the vibe, those cues or the effects grid change."
  [context]
  (let [id (fx/sub-ctx context vibe-current-id)]
    (if-let [vibe (fx/sub-val context library/vibe id)]
      (reactions/reactions {:config {:vibes {:library {id vibe}}}
                            :chains {:effect-chains (fx/sub-val context get-in [:chains :effect-chains])
                                     :cue-chains (slot-chains context (into [] (mapcat #(library/slot-cells vibe %))
                                                                            library/cue-slots))}}
                           id)
      {})))

(def ^:private cue-slot
  "The slot whose cues a role's press may start (vibes.library/slot-cells)."
  {:hit :hit :accent :accent :flash :flash-cue})

(defn- vibe-pad-cue-status
  "What role's press can start, for its pad: {:quiet? (no cue: none of its
   cues is one the vibe that plays may start, vibes.library/playable-cells)
   :audience? (the vibe allows audience cells, and one it may start can
   reach the crowd, vibes.library/audience-cell?)}. HIT's and ACCENT's
   pools and FLASH's cue, over vibe-audience-routing with their chains
   alone; FLASH's effects never mark it. Both false for VIBE and BLACKOUT;
   a cue role with no cue, or no vibe, is quiet."
  [context role]
  (if-let [slot (cue-slot role)]
    (let [vibe (fx/sub-val context library/vibe (fx/sub-ctx context vibe-current-id))
          cells (library/slot-cells vibe slot)
          state (assoc (fx/sub-ctx context vibe-audience-routing) :chains {:cue-chains (slot-chains context cells)})
          playable (library/playable-cells state vibe cells)
          groups (when (:allow-audience? vibe) (fx/sub-ctx context vibe-audience-groups))]
      {:quiet? (empty? playable)
       :audience? (boolean (and groups (some #(library/audience-cell? state % groups) playable)))})
    {:quiet? false :audience? false}))

(defn- vibe-pad-reactions
  "role's pad: the names of its reactions, and :effects-only? when its
   press plays no cue (vibe-pad-cue-status) but moves some."
  [context role]
  (let [names (mapv :name (get (fx/sub-ctx context vibe-reactions) role))
        {:keys [quiet?]} (fx/sub-ctx context vibe-pad-cue-status role)]
    {:reactions names
     :effects-only? (boolean (and quiet? (seq names)))}))

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

(defn- fx-blocked
  "Why the vibe edited never switches on the effect cell at [col row]: :crowd
   (it holds a zone pattern or Map cycle that can send pictures to the
   audience, which the vibe doesn't allow, zones plan Z5); nil otherwise."
  [context col row]
  (when-not (fx/sub-val context edited-allows-audience?)
    (when (library/audience-fx? (assoc (fx/sub-ctx context vibe-audience-routing)
                                       :chains {:effect-chains {[col row] (fx/sub-ctx context effect-cell-chain col row)}})
                                [col row]
                                (fx/sub-ctx context vibe-audience-groups))
      :crowd)))

(defn vibe-pick-fx
  "An effect cell of the Vibes tab's picker: {:in? :pos :roles :others
   :blocked}, as vibe-pick-cell's (:blocked :crowd, fx-blocked)."
  [context col row]
  (assoc (pick-state (fx/sub-ctx context vibe-edit-target) (fx/sub-ctx context cell-vibes :fx col row))
         :blocked (fx/sub-ctx context fx-blocked col row)))

(defn vibe-refused-text
  "What Perform says of an effect cell the vibe left off, because it can
   send pictures to the crowd (zones plan Z5, vibes.engine's :audience-fx
   problem): \"B3 (Zone pattern) can reach the crowd: tick Allow audience
   cells in this vibe\"; nil otherwise."
  [context]
  (let [{:keys [why cell]} (fx/sub-val context get-in [:vibe-runtime :problem])]
    (when (and (= :audience-fx why) cell)
      (let [[col row] cell
            kinds (set (map :effect-id (chains/flatten-chain (:items (fx/sub-ctx context effect-cell-chain col row)))))]
        (str (char (+ 65 row)) (inc col) (if (contains? kinds :zone-pattern) " (Zone pattern)" " (Map cycle)")
             " can reach the crowd: tick Allow audience cells in this vibe")))))

(defn vibe-pick-label
  "What a cue cell of the picker shows of its cue (cue-label-data: views/
   components/grid_cell.clj cell-label's keys, and :has-content?): not
   whether it plays, nor the grid's selection."
  [context col row]
  (fx/sub-ctx context cue-label-data col row))

(defn- pick-fx-label [context col row]
  (select-keys (fx/sub-ctx context effect-cell-display-data col row)
               [:name :first-effect-id :effect-count :effect-ids :has-content?]))

(defn vibe-pick-fx-label
  "What an effect cell of the picker shows of what it stores (grid_cell.clj
   effects-label's keys, and :has-content?): not whether it is on."
  [context col row]
  (fx/sub-ctx context pick-fx-label col row))

;; What the lint, the checklist and the Vibes tab's tap reactions read
;; (plans/2026-11-23-cljfx-reactivity/37): a vibe's slots and the cells
;; they name, not which cell is on, so an effect cell switched on or off
;; (by hand, or by a vibe as it plays) runs none of them again. One lint
;; serves the tab's badge and its rows. vibes.reactions also reads the
;; modulator registry, which isn't in the context (plan 17).

(defn- lint-vibe
  "What the lint reads of a vibe (vibes.library/lint): its id, name and
   :allow-audience?, and the cells its slots name."
  [vibe]
  (if (map? vibe)
    (cond-> (select-keys vibe [:id :name :allow-audience? :bed :fx])
      (contains? vibe :hit) (assoc :hit (select-keys (:hit vibe) [:cells]))
      (contains? vibe :accent) (assoc :accent (select-keys (:accent vibe) [:cells]))
      (contains? vibe :flash) (assoc :flash (select-keys (:flash vibe) [:cue :fx])))
    vibe))

(defn lint-vibes
  "The vibes as their lint, checklist and tap reactions read them: :order
   as it is, a repeat too, and every vibe of the library (lint-vibe), one
   :order leaves out too. Not a vibe's colour, energy or play options, nor
   the vibe settings."
  [context]
  (let [{:keys [order library]} (fx/sub-ctx context vibe-config)]
    {:order order
     :library (some-> library (update-vals lint-vibe))}))

(defn vibe-fx-content
  "The effects cell at [col row] as a vibe's lint and tap reactions read
   it: its chain without its live switch (:active?, its own, not a
   modulator's), nil without one. Switching the cell on or off leaves it
   equal."
  [context col row]
  (let [chain (fx/sub-ctx context effect-cell-chain col row)]
    (if (map? chain) (dissoc chain :active?) chain)))

(defn vibe-owned-chains
  "The chains of the cells the vibe with vibe-id names, as its lint and its
   own tap reactions (vibes.reactions/reactions :own?) read them:
   {:cue-chains {cell cue} (its pools', FLASH cue's and BED's,
   cue-cell-chain: an empty cell's reads as one without items)
   :effect-chains {cell chain} (its FX set's and FLASH effects',
   vibe-fx-content)}. Follows those cells alone: a cell it doesn't name,
   or one of its effect cells switched on or off, leaves it equal."
  [context vibe-id]
  (let [vibe (fx/sub-val context library/vibe vibe-id)
        cells (fn [slots] (into [] (comp (mapcat #(library/slot-cells vibe %)) (distinct)) slots))]
    {:cue-chains (slot-chains context (cells library/cue-slots))
     :effect-chains (into {} (map (fn [[col row :as cell]] [cell (fx/sub-ctx context vibe-fx-content col row)]))
                          (cells library/fx-slots))}))

(defn- lint-world
  "What the lint reads (vibes.library/lint), as a state: lint-vibes, the
   chains every vibe of the library names (vibe-owned-chains), the
   audience routing (vibe-audience-routing: the projectors, virtual
   projectors, rig, groups, zone maps, column and show defaults), the keys
   learned, the zones' labels and the manual map ([:zone-map :active]: the
   rows say what the map the show is on does, not one a switch waits
   for). A cell switched on or off, a cell no vibe names, a cue that plays
   and the show clock's status leave it equal."
  [context]
  (let [vibes (fx/sub-ctx context lint-vibes)]
    (-> (fx/sub-ctx context vibe-audience-routing)
        (update :config assoc
                :vibes vibes
                :input {:trigger-map (fx/sub-ctx context input-trigger-map)})
        (assoc :chains (reduce (fn [chains id] (merge-with merge chains (fx/sub-ctx context vibe-owned-chains id)))
                               {:cue-chains {} :effect-chains {}}
                               (keys (:library vibes)))
               :zone-map {:active (fx/sub-val context get-in [:zone-map :active])}
               :zones {:labels (fx/sub-ctx context zone-labels)}))))

(defn vibe-lint
  "What to fix before the show, worst first (vibes.library/lint of
   lint-world): the Vibes tab's rows, and the errors its badge counts
   (vibes-tab-label)."
  [context]
  (library/lint (fx/sub-ctx context lint-world)))

(defn- edited-reactions-world
  "What the tap reactions of the vibe the Vibes tab edits read: {:id :state
   (its lint-vibes entry and its vibe-owned-chains, as a state)}."
  [context]
  (let [id (fx/sub-ctx context vibe-edited-id)]
    {:id id
     :state {:config {:vibes {:library {id (get-in (fx/sub-ctx context lint-vibes) [:library id])}}}
             :chains (fx/sub-ctx context vibe-owned-chains id)}}))

(defn vibe-edited-reactions
  "The tap reactions of the vibe the Vibes tab edits, by role: its own cells
   (vibes.reactions/reactions :own?), as its lint counts them. Not the
   effects grid's cells that are on: those are the Perform pads'
   (vibe-reactions)."
  [context]
  (let [{:keys [id state]} (fx/sub-ctx context edited-reactions-world)]
    (reactions/reactions state id :own? true)))

(defn vibe-tap-hints
  "Why the Vibes tab's Tap reactions menus grey a zone reaction (vibe Phase
   8b, plans/2026-11-19-zones/11 §12.4): {kind hint} of each one whose
   places on this rig are fewer than two (vibes.reactions/zone-hint), {}
   while none is. Follows the projectors, the rig, the groups and the
   audience groups only."
  [context]
  (let [rig {:projectors (fx/sub-val context :projectors)
             :rig (fx/sub-val context :rig)
             :zone-groups (fx/sub-val context :zone-groups)}
        groups (fx/sub-ctx context vibe-audience-groups)]
    (into {}
          (keep (fn [{:keys [kind]}]
                  (when-let [hint (reactions/zone-hint rig groups kind)]
                    [kind hint])))
          reactions/templates)))

(defn- checklist-world
  "What the checklist reads (vibes.library/checklist), as a state:
   lint-vibes, the audience routing, the keys learned and the whole cue
   grid (Make cues counts a cue no vibe names). No effect cell."
  [context]
  (-> (fx/sub-ctx context vibe-audience-routing)
      (update :config assoc
              :vibes (fx/sub-ctx context lint-vibes)
              :input {:trigger-map (fx/sub-ctx context input-trigger-map)})
      (assoc :chains {:cue-chains (fx/sub-val context get-in [:chains :cue-chains])})))

(defn vibe-checklist
  "What is left to set up (vibes.library/checklist of checklist-world)."
  [context]
  (library/checklist (fx/sub-ctx context checklist-world)))

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
   (vibe-pad-cue-status): HIT's or ACCENT's pool, FLASH's cue."
  [context role]
  (:audience? (fx/sub-ctx context vibe-pad-cue-status role)))

(defn vibes-tab-label
  "The Vibes tab's label: \"Vibes\", and the lint's error count while it
   has errors (\"Vibes ⚠ 2\")."
  [context]
  (let [n (count (filter #(= :error (:level %)) (fx/sub-ctx context vibe-lint)))]
    (if (pos? n) (str "Vibes ⚠ " n) "Vibes")))
