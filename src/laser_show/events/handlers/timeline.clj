(ns laser-show.events.handlers.timeline
  "Event handlers for timeline editor operations.
   
   Handles:
   - View controls: snap, inspector and preset panel (the viewport itself is
     view-local in the editor; these handlers never touch JavaFX)
   - Item selection within the timeline
   - Item timing updates (start/duration)
   - Track CRUD: add, delete, update, move, mute
   - Item-to-track assignment
   - Nudge of selected items
   - Opening the editor, and the wrap-normalize-timelines pipeline middleware
   
   State paths:
   - Timeline UI: [:ui :timeline-editor [col row]] (per cue), [:ui :timeline-prefs]
   - Tracks: [:chains :cue-chains [col row] :tracks]
   - Item timing: [:chains :cue-chains [col row] :items ... :timeline/start :timeline/duration]"
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.timeline.playback :as playback]
            [laser-show.state.extractors :as ex]
            [laser-show.events.handlers.list.helpers :as list-helpers]
            [laser-show.timeline.model :as model]))


;; Helpers


;; UI state: per cue at [:ui :timeline-editor [col row]] (selection, cursor),
;; editor-wide prefs at [:ui :timeline-prefs] (snap, add panel, inspector),
;; and the track sidebar's list state at [:list-ui [:timeline-tracks col row]].
;; The viewport (zoom, scroll) is view-local state in the editor.

(def default-view
  "Per-cue editor view state. Zoom and scroll are view-local in the editor."
  {:selection #{} :cursor-beat 0.0})

(def default-prefs
  "Editor-wide timeline preferences."
  {:snap {:enabled? true :value 0.25}
   :add-panel {:open? false :preset-tab :geometric :effect-tab :shape}
   :inspector-open? true
   :ruler-mode :bars})

(defn view-path [col row] [:ui :timeline-editor [col row]])

(defn tracks-list-id
  "list-editor component id of the track sidebar for [col row]."
  [col row]
  [:timeline-tracks col row])

(defn- view-get [state col row k]
  (get-in state (conj (view-path col row) k) (get default-view k)))

(defn- view-assoc [state col row k v]
  (assoc-in state (conj (view-path col row) k) v))

(defn- prefs-get [state ks]
  (get-in state (into [:ui :timeline-prefs] ks) (get-in default-prefs ks)))

(defn- prefs-assoc [state ks v]
  (assoc-in state (into [:ui :timeline-prefs] ks) v))

(defn- select-track-in-sidebar [state col row track-id]
  (update-in state [:list-ui (tracks-list-id col row)] assoc
             :selected-ids (if track-id #{track-id} #{})
             :last-selected-id track-id))

(defn- event-cell
  "[col row] of the event, or of the open timeline editor when the event
   doesn't say (view events from controls that only know the editor)."
  [{:keys [col row state]}]
  (if (and (some? col) (some? row))
    [col row]
    (let [{dcol :col drow :row} (get-in state [:ui :dialogs :timeline-cue-editor])]
      [dcol drow])))

(defn- snap-opts
  "Current snap settings in the shape model/snap expects."
  [state]
  (prefs-get state [:snap]))


;; View Controls


(defn- handle-set-snap
  "Configure snap-to-grid settings: :enabled? (or a boolean :fx/event),
   :toggle? to flip it, and :value (beats)."
  [{:keys [enabled? toggle? value state] :as event}]
  (let [{current-enabled? :enabled? current-value :value} (snap-opts state)
        fx-enabled? (let [e (:fx/event event)] (when (boolean? e) e))]
    {:state (prefs-assoc state [:snap]
                         {:enabled? (if toggle?
                                      (not current-enabled?)
                                      (h/first-some enabled? fx-enabled? current-enabled?))
                          :value (or value current-value)})}))

(defn- handle-toggle-inspector
  "Show or hide the inspector (an editor preference)."
  [{:keys [state]}]
  {:state (prefs-assoc state [:inspector-open?] (not (prefs-get state [:inspector-open?])))})


;; Edits: one op per committed edit (model/apply-op)


(defn- chain-path [col row] [:chains :cue-chains [col row]])

(defn- prune-selection
  "Drop selected clip ids that no longer exist in the cue."
  [state col row cue]
  (let [ids (into #{} (map :id) (:items cue []))
        sel (view-get state col row :selection)]
    (if (every? ids sel)
      state
      (view-assoc state col row :selection (into #{} (filter ids) sel)))))

(defn apply-op-to-state
  "Apply a model op to the cue at [col row]. Returns the new state; identical
   when the op changes nothing. Shared by :timeline/apply-op and the adapters."
  [state col row op]
  (let [path (chain-path col row)
        cue (get-in state path)]
    (if (nil? cue)
      state
      (let [cue' (model/apply-op cue op {:snap (snap-opts state)})]
        (if (identical? cue cue')
          state
          (-> state
              (assoc-in path cue')
              (prune-selection col row cue')))))))

(defn- op-effects
  "Effects for an edit: the new state, labelled for undo."
  [state col row op]
  (let [new-state (apply-op-to-state state col row op)]
    (cond-> {:state new-state}
      (not (identical? state new-state)) (assoc :history/description (model/op-label op)))))

(defn- handle-apply-op
  "Apply one edit op ({:op ...}, see model/apply-op) to the cue at [col row].
   One undo entry, labelled by model/op-label unless the event has its own
   :history/description."
  [{:keys [col row op state]}]
  (if (contains? model/op-types (:op op))
    (op-effects state col row op)
    (do (log/warn "Unknown timeline op:" (:op op))
        {:state state})))


;; Selection


(defn- handle-select-items
  "Update selection state.
   Modes:
   - :replace - Set selection to exactly these IDs
   - :add     - Add IDs to existing selection
   - :toggle  - Toggle each ID in/out of selection"
  [{:keys [ids mode state] :as event}]
  (let [[col row] (event-cell event)
        current (view-get state col row :selection)
        id-set (set ids)
        new-selection (case (or mode :replace)
                        :replace id-set
                        :add (into current id-set)
                        :toggle (reduce (fn [sel id]
                                          (if (contains? sel id)
                                            (disj sel id)
                                            (conj sel id)))
                                        current id-set))]
    {:state (view-assoc state col row :selection new-selection)}))

(defn- handle-clear-selection
  "Clear all selected items."
  [{:keys [state] :as event}]
  (let [[col row] (event-cell event)]
    {:state (view-assoc state col row :selection #{})}))

(defn- handle-delete-selection
  "Delete the selected clips. No-op when nothing is selected."
  [{:keys [col row state]}]
  (let [selection (view-get state col row :selection)]
    (if (empty? selection)
      {:state state}
      (op-effects state col row {:op :delete-clips :clip-ids selection}))))

(defn- handle-select-track
  "Set the currently selected track ID."
  [{:keys [track-id state] :as event}]
  (let [[col row] (event-cell event)]
    {:state (select-track-in-sidebar state col row track-id)}))

(defn- handle-set-cursor-beat
  "Set the timeline cursor beat position, snapping if enabled."
  [{:keys [beat state] :as event}]
  (let [[col row] (event-cell event)
        snapped (max 0.0 (model/snap (or beat 0.0) (snap-opts state)))]
    {:state (view-assoc state col row :cursor-beat snapped)}))


;; Loop


(defn- handle-toggle-loop
  "Toggle the loop enabled state for a cue-chain."
  [{:keys [col row state]}]
  (let [enabled? (boolean (get-in state (conj (chain-path col row) :loop :enabled?)))]
    (op-effects state col row {:op :set-loop :enabled? (not enabled?)})))


;; Clip timing


(defn- handle-nudge-selection
  "Move all selected clips by delta-beats (arrow keys). Bursts coalesce."
  [{:keys [col row delta-beats state]}]
  (let [selection (view-get state col row :selection)]
    (assoc (op-effects state col row {:op :move-clips :clip-ids selection :delta delta-beats})
           :history/coalesce-key [:timeline/nudge-selection col row])))


;; Tracks


(defn- handle-update-tracks
  "Replaces the entire tracks vector for a cue chain.
   This is the callback fired by the `list-editor` component when tracks
   are grouped, reordered, deleted, or pasted. Clips on tracks that no
   longer exist are deleted in the same edit (decision D4)."
  [{:keys [col row items state]}]
  (let [chain-path [:chains :cue-chains [col row]]
        old-tracks (get-in state (conj chain-path :tracks) [])
        new-tracks (vec items)
        removed (reduce disj (model/leaf-track-ids old-tracks) (model/leaf-track-ids new-tracks))
        orphan-ids (into #{}
                         (comp (filter #(contains? removed (:track-id %))) (map :id))
                         (get-in state (conj chain-path :items) []))]
    {:state (cond-> (assoc-in state (conj chain-path :tracks) new-tracks)
              (seq orphan-ids)
              (-> (update-in (conj chain-path :items) list-helpers/remove-by-ids orphan-ids)
                  (view-assoc col row :selection
                              (reduce disj (view-get state col row :selection) orphan-ids))))}))

(defn- handle-set-track-muted
  "Mute or unmute a track or folder (a muted folder mutes everything in it).
   :muted? is a live field (state.history/live-item-paths): not undoable and
   not an unsaved change, but saved with the project."
  [{:keys [col row track-id muted? state]}]
  (op-effects state col row {:op :set-track-muted :track-id track-id :muted? muted?}))

(defn- handle-set-track-zone
  "Set or clear (nil / :inherit) the zone on a track or folder."
  [{:keys [col row track-id zone-group-id state]}]
  (op-effects state col row {:op :set-track-zone :id track-id :zone-group-id zone-group-id}))

(defn- handle-set-clip-zone
  "Set or clear (nil / :inherit) the zone override on a clip."
  [{:keys [col row item-id zone-group-id state]}]
  (op-effects state col row {:op :set-clip-zone :id item-id :zone-group-id zone-group-id}))

(defn- handle-add-track
  "Append a track and select it. Optional :zone-group-id sets its zone;
   without it the track inherits (its folder's zone, or the cue default)."
  [{:keys [col row zone-group-id state]}]
  (let [track-id (random-uuid)
        effects (op-effects state col row {:op :add-track :track-id track-id :zone-group-id zone-group-id})]
    (update effects :state select-track-in-sidebar col row track-id)))

(defn- handle-add-folder
  "Append a track folder. Optional :zone-group-id sets its zone."
  [{:keys [col row zone-group-id state]}]
  (op-effects state col row {:op :add-folder :zone-group-id zone-group-id}))


;; Add Panel (preset/effect addition from timeline)


(defn- handle-toggle-add-panel
  "Toggle the bottom add-content panel open/closed."
  [{:keys [state]}]
  {:state (prefs-assoc state [:add-panel :open?] (not (prefs-get state [:add-panel :open?])))})

(defn- handle-set-add-panel-tab
  "Set the active tab in the add panel's preset or effect bank.
   :panel is :preset or :effect, :tab-id is the category keyword."
  [{:keys [panel tab-id state]}]
  {:state (prefs-assoc state [:add-panel (if (= panel :effect) :effect-tab :preset-tab)] tab-id)})

(defn- handle-add-preset-to-track
  "Add a preset clip at :start (default: the cursor) on :track-id (resolved
   to a leaf; default: the first leaf). Moves the cursor to the clip's end
   and selects the new clip."
  [{:keys [col row item-id track-id start state]}]
  (let [clip-id (random-uuid)
        cursor (view-get state col row :cursor-beat)
        new-state (apply-op-to-state state col row {:op :add-clip :clip-id clip-id :preset-id item-id
                                                    :track-id track-id :start (or start cursor 0.0)})
        clip (list-helpers/find-by-id (get-in new-state (conj (chain-path col row) :items) []) clip-id)]
    (if-not clip
      {:state state}
      {:state (-> new-state
                  (view-assoc col row :cursor-beat (model/clip-end clip))
                  (view-assoc col row :selection #{clip-id})
                  (select-track-in-sidebar col row (:track-id clip)))
       :history/description "Add Clip"})))

(defn- handle-add-effect-to-timeline-item
  "Add an effect (from the effect bank: :item is its definition) to the clip
   :target-item-id, with the definition's default params."
  [{:keys [col row item item-id target-item-id state]}]
  (op-effects state col row
              {:op :add-effect
               :clip-id target-item-id
               :effect-id (or item-id (:id item))
               :params (reduce (fn [acc {:keys [key default]}] (assoc acc key default))
                               {} (:parameters item []))}))


;; Transport: play / pause / stop / seek one timeline cue.
;; These only touch [:playback ...] and the per-cue cursor, so they're never
;; undo steps and never mark the project unsaved.


(defn- with-cell [event]
  (let [[col row] (event-cell event)]
    (assoc event :col col :row row)))

(defn- unless-chasing [handler {:keys [col row state] :as event}]
  (if (playback/chases-mtc? (get-in state (chain-path col row)))
    {:state state}
    (handler event)))

(defn- active-timing [state col row]
  (get-in state [:playback :active-cues [col row]]))

(defn- start-beat
  "Where Play starts: :from, else the cursor; a position at or past the end
   of a timeline that doesn't loop starts from 0."
  [state col row from]
  (let [cue (get-in state (chain-path col row))
        beat (double (or from (view-get state col row :cursor-beat) 0.0))
        length (model/effective-length cue)]
    (if (and (not (get-in cue [:loop :enabled?])) (pos? length) (>= beat length))
      0.0
      beat)))

(defn- handle-play
  "Play the timeline at [col row] from :from (default: the cursor).
   Paused: resume (seeking to :from if given). Playing: seek to :from."
  [{:keys [col row from state] :as event}]
  (let [timing (active-timing state col row)]
    {:state
     (cond
       (nil? (get-in state (chain-path col row)))
       state

       (nil? timing)
       (update state :playback cue-timing/activate-cue [col row] (h/current-time-ms event)
               (ex/global-accumulated-beats state)
               {:timeline? true :start-beat (start-beat state col row from)})

       :else
       (update-in state [:playback :active-cues [col row]]
                  (fn [t]
                    (cond-> (assoc t :paused? false :timeline? true :phase-offset 0.0 :phase-offset-target 0.0)
                      (some? from) (cue-timing/seek from)))))}))

(defn- handle-pause
  "Pause or resume the playing timeline at [col row]. No-op when it isn't playing."
  [{:keys [col row state]}]
  {:state (if (active-timing state col row)
            (update-in state [:playback :active-cues [col row] :paused?] not)
            state)})

(defn- handle-toggle-play
  "Play/pause button and Space: pause while playing, otherwise play."
  [{:keys [col row state] :as event}]
  (let [timing (active-timing state col row)]
    (if (and timing (not (:paused? timing)))
      (handle-pause event)
      (handle-play (dissoc event :from)))))

(defn- handle-stop
  "Stop the timeline at [col row]. The cursor stays where it is."
  [{:keys [col row state]}]
  {:state (update state :playback cue-timing/deactivate-cue [col row])})

(defn- handle-seek
  "Move the cursor to :beat; a playing (or paused) timeline jumps there too."
  [{:keys [col row beat state] :as event}]
  (let [[col row] (if (and col row) [col row] (event-cell event))
        beat (max 0.0 (double (or beat 0.0)))]
    {:state (cond-> (view-assoc state col row :cursor-beat beat)
              (active-timing state col row)
              (update-in [:playback :active-cues [col row]] cue-timing/seek beat))}))

(defn- handle-set-ruler-mode
  "Ruler labels: :bars (bar.beat) or :time (show timecode)."
  [{:keys [mode state]}]
  {:state (prefs-assoc state [:ruler-mode] (if (= mode :time) :time :bars))})


;; Open Editor


(defn- handle-open-editor
  "Open the timeline cue editor for a cell.
   
   - Timeline cue: the document isn't changed. (A cue that somehow skipped
     normalization is normalized here as a backfill: no undo step, not unsaved.)
   - Any other cue (or an empty cell): converted with model/convert-to-timeline,
     which keeps what it renders. That's an undoable \"Convert to Timeline Cue\".
   Then chains into :ui/open-dialog."
  [{:keys [col row state]}]
  (let [chain-path [:chains :cue-chains [col row]]
        cue-chain (get-in state chain-path)
        timeline? (cue-chains/timeline-cue? cue-chain)
        new-track-id (random-uuid)
        normalize #(model/normalize-timeline-cue % {:new-track-id new-track-id})
        final-chain (if timeline?
                      (normalize cue-chain)
                      (model/convert-to-timeline cue-chain (get state :zone-groups {})))
        changed? (not (identical? final-chain cue-chain))
        effects {:state (cond-> state
                          changed? (assoc-in chain-path final-chain))
                 :dispatch {:event/type :ui/open-dialog
                            :dialog-id :timeline-cue-editor
                            :data {:col col :row row}}}]
    (cond
      (not changed?) effects
      (not timeline?) (assoc effects :history/description "Convert to Timeline Cue")
      :else (assoc effects :history/backfill
                   (fn [doc]
                     (if (get-in doc chain-path)
                       (update-in doc chain-path normalize)
                       doc))))))


;; Pipeline middleware


(defn- restores-history?
  "Undo/redo restore a snapshot exactly; normalizing it again could differ
   from the saved baseline (e.g. a fresh track id)."
  [{:keys [event/type]}]
  (or (#{:edit/undo :edit/redo} type)
      (= "history" (some-> type namespace))))

(defn wrap-normalize-timelines
  "Middleware: after the handler runs, enforce the timeline invariants
   (model/normalize-timeline-cue) on every cue chain the event changed. Runs
   innermost, so undo snapshots and the unsaved flag see normalized state.
   Events that don't touch cue chains only pay one identical? check."
  [handler]
  (fn [event]
    (let [effects (handler event)
          old-ccs (get-in event [:state :chains :cue-chains])
          new-ccs (get-in effects [:state :chains :cue-chains])]
      (if (or (nil? (:state effects))
              (identical? old-ccs new-ccs)
              (restores-history? event))
        effects
        (let [normalized (model/normalize-timeline-cues old-ccs new-ccs)]
          (if (identical? normalized new-ccs)
            effects
            (assoc-in effects [:state :chains :cue-chains] normalized)))))))


;; Public API


(defn handle
  "Dispatch timeline events to their handlers.
   
   Accepts events with :event/type in the :timeline/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    ;; View controls
    :timeline/set-snap (handle-set-snap event)
    :timeline/toggle-inspector (handle-toggle-inspector event)

    ;; Selection
    :timeline/select-items (handle-select-items event)
    :timeline/clear-selection (handle-clear-selection event)
    :timeline/delete-selection (handle-delete-selection event)
    :timeline/select-track (handle-select-track event)
    :timeline/set-cursor-beat (handle-set-cursor-beat event)

    ;; Item timing
    :timeline/nudge-selection (handle-nudge-selection event)

    ;; Loop timing
    :timeline/toggle-loop (handle-toggle-loop event)

    ;; Edits as data
    :timeline/apply-op (handle-apply-op event)

    ;; Transport (without :col/:row: the open editor's cell). A timeline
    ;; following MIDI timecode ignores them: the timecode drives it.
    :timeline/play (unless-chasing handle-play (with-cell event))
    :timeline/pause (unless-chasing handle-pause (with-cell event))
    :timeline/toggle-play (unless-chasing handle-toggle-play (with-cell event))
    :timeline/stop (unless-chasing handle-stop (with-cell event))
    :timeline/seek (handle-seek event)
    :timeline/set-ruler-mode (handle-set-ruler-mode event)

    ;; Track CRUD
    :timeline/update-tracks (handle-update-tracks event)
    :timeline/set-track-muted (handle-set-track-muted event)
    :timeline/add-track (handle-add-track event)
    :timeline/add-folder (handle-add-folder event)
    :timeline/set-track-zone (handle-set-track-zone event)
    :timeline/set-clip-zone (handle-set-clip-zone event)

    ;; Add panel
    :timeline/toggle-add-panel (handle-toggle-add-panel event)
    :timeline/set-add-panel-tab (handle-set-add-panel-tab event)
    :timeline/add-preset-to-track (handle-add-preset-to-track event)
    :timeline/add-effect-to-item (handle-add-effect-to-timeline-item event)

    ;; Open editor (timeline-cue-editor domain routed here)
    :timeline-cue-editor/open (handle-open-editor event)

    ;; Unknown event in this domain
    (do
      (log/warn "Unknown timeline event:" type)
      {})))
