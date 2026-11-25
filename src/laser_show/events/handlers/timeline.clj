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
   - The show's name and target (the dialog's footer)
   - The wrap-normalize-timelines pipeline middleware
   
   Every addressed event names a show by :show-id (a uuid in the library,
   plans/2026-11-25-timeline-shows 05), or, without one, the open editor's
   show. None reads :col or :row, and none creates a show: an event that
   names a show that isn't there changes nothing.

   State paths:
   - Timeline UI: [:ui :timeline-editor show-id] (per show), [:ui :timeline-prefs]
   - Tracks: [:chains :shows show-id :tracks]
   - Item timing: [:chains :shows show-id :items ... :timeline/start :timeline/duration]
   - Playback: [:playback :active-cues [:show show-id]] (shows/play-key)"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.animation.effects :as effects]
            [laser-show.timeline.layout :as layout]
            [laser-show.timeline.playback :as playback]
            [laser-show.events.handlers.list.helpers :as list-helpers]
            [laser-show.routing.targets :as targets]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.shows :as shows]))


;; Helpers


;; UI state: per show at [:ui :timeline-editor show-id] (selection, cursor),
;; editor-wide prefs at [:ui :timeline-prefs] (snap, add panel, inspector),
;; and the track sidebar's list state at [:list-ui [:timeline-tracks show-id]].
;; The viewport (zoom, scroll) is view-local state in the editor.

(def default-view
  "Per-show editor view state. Zoom and scroll are view-local in the editor."
  {:selection #{} :cursor-beat 0.0})

(def default-prefs
  "Editor-wide timeline preferences."
  {:snap {:enabled? true :value 0.25}
   :add-panel {:open? false :preset-tab :geometric :effect-tab :shape}
   :inspector-open? true
   :ruler-mode :bars
   :detail-height layout/default-detail-height})

(defn view-path [show-id] [:ui :timeline-editor show-id])

(defn tracks-list-id
  "list-editor component id of the track sidebar for show show-id."
  [show-id]
  [:timeline-tracks show-id])

(defn- view-get [state show-id k]
  (get-in state (conj (view-path show-id) k) (get default-view k)))

(defn- view-assoc [state show-id k v]
  (assoc-in state (conj (view-path show-id) k) v))

(defn- prefs-get [state ks]
  (get-in state (into [:ui :timeline-prefs] ks) (get-in default-prefs ks)))

(defn- prefs-assoc [state ks v]
  (assoc-in state (into [:ui :timeline-prefs] ks) v))

(defn- select-track-in-sidebar [state show-id track-id]
  (update-in state [:list-ui (tracks-list-id show-id)] assoc
             :selected-ids (if track-id #{track-id} #{})
             :last-selected-id track-id))

(defn- event-show
  "The show id of the event, or of the open timeline editor when the event
   doesn't say (view events from controls that only know the editor). nil
   when neither names one."
  [{:keys [show-id state]}]
  (or show-id (get-in state [:ui :dialogs :timeline-cue-editor :show-id])))

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

(defn- handle-set-detail-height
  "Height of the editor's bottom detail panel (an editor preference). The
   view dispatches it once per resize drag, on release."
  [{:keys [height state]}]
  {:state (prefs-assoc state [:detail-height] (layout/clamp-detail-height height))})


;; Edits: one op per committed edit (model/apply-op)


(defn- chain-path [show-id] (shows/show-path show-id))

(defn- show-at [state show-id]
  (when (some? show-id)
    (get-in state (chain-path show-id))))

(defn- prune-selection
  "Drop selected clip ids that no longer exist in the show."
  [state show-id cue]
  (let [ids (into #{} (map :id) (:items cue []))
        sel (view-get state show-id :selection)]
    (if (every? ids sel)
      state
      (view-assoc state show-id :selection (into #{} (filter ids) sel)))))

(defn apply-op-to-state
  "Apply a model op to show show-id. Returns the new state; identical when
   the op changes nothing or the show isn't there. Shared by
   :timeline/apply-op and the adapters."
  [state show-id op]
  (if-let [cue (show-at state show-id)]
    (let [cue' (model/apply-op cue op {:snap (snap-opts state)})]
      (if (identical? cue cue')
        state
        (-> state
            (assoc-in (chain-path show-id) cue')
            (prune-selection show-id cue'))))
    state))

(defn- op-effects
  "Effects for an edit: the new state, labelled for undo."
  [state show-id op]
  (let [new-state (apply-op-to-state state show-id op)]
    (cond-> {:state new-state}
      (not (identical? state new-state)) (assoc :history/description (model/op-label op)))))

(defn- handle-apply-op
  "Apply one edit op ({:op ...}, see model/apply-op) to show :show-id.
   One undo entry, labelled by model/op-label unless the event has its own
   :history/description."
  [{:keys [show-id op state]}]
  (if (contains? model/op-types (:op op))
    (op-effects state show-id op)
    (do (log/warn "Unknown timeline op:" (:op op))
        {:state state})))


;; Selection


(defn- handle-select-items
  "Update selection state.
   Modes:
   - :replace - Set selection to exactly these IDs
   - :add     - Add IDs to existing selection
   - :toggle  - Toggle each ID in/out of selection"
  [{:keys [show-id ids mode state]}]
  (let [current (view-get state show-id :selection)
        id-set (set ids)
        new-selection (case (or mode :replace)
                        :replace id-set
                        :add (into current id-set)
                        :toggle (reduce (fn [sel id]
                                          (if (contains? sel id)
                                            (disj sel id)
                                            (conj sel id)))
                                        current id-set))]
    {:state (view-assoc state show-id :selection new-selection)}))

(defn- handle-clear-selection
  "Clear all selected items."
  [{:keys [show-id state]}]
  {:state (view-assoc state show-id :selection #{})})

(defn- handle-delete-selection
  "Delete the selected clips. No-op when nothing is selected."
  [{:keys [show-id state]}]
  (let [selection (view-get state show-id :selection)]
    (if (empty? selection)
      {:state state}
      (op-effects state show-id {:op :delete-clips :clip-ids selection}))))

(defn- handle-select-track
  "Set the currently selected track ID."
  [{:keys [show-id track-id state]}]
  {:state (select-track-in-sidebar state show-id track-id)})

(defn- handle-set-cursor-beat
  "Set the timeline cursor beat position, snapping if enabled."
  [{:keys [show-id beat state]}]
  (let [snapped (max 0.0 (model/snap (or beat 0.0) (snap-opts state)))]
    {:state (view-assoc state show-id :cursor-beat snapped)}))


;; Loop


(defn- handle-toggle-loop
  "Toggle the loop enabled state of the show."
  [{:keys [show-id state]}]
  (let [enabled? (boolean (get-in state (conj (chain-path show-id) :loop :enabled?)))]
    (op-effects state show-id {:op :set-loop :enabled? (not enabled?)})))


;; Clip timing


(defn- handle-nudge-selection
  "Move all selected clips by delta-beats (arrow keys). Bursts coalesce."
  [{:keys [show-id delta-beats state]}]
  (let [selection (view-get state show-id :selection)]
    (assoc (op-effects state show-id {:op :move-clips :clip-ids selection :delta delta-beats})
           :history/coalesce-key [:timeline/nudge-selection show-id])))


;; Tracks


(defn- handle-update-tracks
  "Replaces the entire tracks vector of the show.
   This is the callback fired by the `list-editor` component when tracks
   are grouped, reordered, deleted, or pasted. Clips on tracks that no
   longer exist are deleted in the same edit (decision D4)."
  [{:keys [show-id items state]}]
  (let [path (chain-path show-id)
        old-tracks (get-in state (conj path :tracks) [])
        new-tracks (vec items)
        removed (reduce disj (model/leaf-track-ids old-tracks) (model/leaf-track-ids new-tracks))
        orphan-ids (into #{}
                         (comp (filter #(contains? removed (:track-id %))) (map :id))
                         (get-in state (conj path :items) []))]
    {:state (cond-> (assoc-in state (conj path :tracks) new-tracks)
              (seq orphan-ids)
              (-> (update-in (conj path :items) list-helpers/remove-by-ids orphan-ids)
                  (view-assoc show-id :selection
                              (reduce disj (view-get state show-id :selection) orphan-ids))))}))

(defn- handle-set-track-muted
  "Mute or unmute a track or folder (a muted folder mutes everything in it).
   :muted? is a live field (state.history/live-item-paths): not undoable and
   not an unsaved change, but saved with the project."
  [{:keys [show-id track-id muted? state]}]
  (op-effects state show-id {:op :set-track-muted :track-id track-id :muted? muted?}))

(defn- handle-set-track-target
  "Set or clear (nil, :inherit) the target of a track or folder (zones plan
   Z4)."
  [{:keys [show-id track-id target state]}]
  (op-effects state show-id {:op :set-track-target :id track-id :target target}))

(defn- handle-set-clip-target
  "Set or clear (nil, :inherit) a clip's own target (zones plan Z4)."
  [{:keys [show-id item-id target state]}]
  (op-effects state show-id {:op :set-clip-target :id item-id :target target}))

(defn- handle-add-track
  "Append a track and select it. Optional :target sets where its clips go;
   without it the track inherits (its folder's, or the show's)."
  [{:keys [show-id target state]}]
  (let [track-id (random-uuid)
        effects (op-effects state show-id {:op :add-track :track-id track-id :target target})]
    (update effects :state select-track-in-sidebar show-id track-id)))

(defn- handle-add-folder
  "Append a track folder. Optional :target sets where its tracks' clips go."
  [{:keys [show-id target state]}]
  (op-effects state show-id {:op :add-folder :target target}))


;; The footer: the show's name and target


(defn- handle-set-name
  "Set the show's :name; \"\" (or none) removes it. Ends the dialog's name
   edit, as :cue-chain/set-name does."
  [{:keys [show-id name state]}]
  {:state (-> state
              (update-in (chain-path show-id) #(if (seq name) (assoc % :name name) (dissoc % :name)))
              (assoc-in [:ui :dialogs :timeline-cue-editor :editing-name?] false))})

(defn- handle-set-cue-target
  "Set the show's own target (:target), in normal form; :inherit or none
   clears it, so the show follows the show default, then All. A show has no
   grid column."
  [{:keys [show-id target state]}]
  (op-effects state show-id {:op :set-cue-target
                             :target (when-not (= :inherit target) (targets/normalize-target target))}))


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
  [{:keys [show-id item-id track-id start state]}]
  (let [clip-id (random-uuid)
        cursor (view-get state show-id :cursor-beat)
        new-state (apply-op-to-state state show-id {:op :add-clip :clip-id clip-id :preset-id item-id
                                                    :track-id track-id :start (or start cursor 0.0)})
        clip (list-helpers/find-by-id (get-in new-state (conj (chain-path show-id) :items) []) clip-id)]
    (if-not clip
      {:state state}
      {:state (-> new-state
                  (view-assoc show-id :cursor-beat (model/clip-end clip))
                  (view-assoc show-id :selection #{clip-id})
                  (select-track-in-sidebar show-id (:track-id clip)))
       :history/description "Add Clip"})))

(defn- handle-add-effect-to-timeline-item
  "Add the effect :item-id to the clip :target-item-id, or the track or
   folder :target-track-id, with its definition's default params as they
   are when the event is handled (effects/get-default-params: a bank click
   or drop carries the id alone, reactivity plan 17). Optional :index
   inserts at that position instead of appending. :select-target? (drops
   from the browser) also selects the target, as pressing it on the canvas
   would. No target, or an id no effect has: nothing happens."
  [{:keys [show-id item-id target-item-id target-track-id index select-target? state]}]
  (if-let [params (when (or target-item-id target-track-id) (effects/get-default-params item-id))]
    (let [result (op-effects state show-id
                             (cond-> {:op :add-effect
                                      :effect-id item-id
                                      :params params}
                               target-item-id (assoc :clip-id target-item-id)
                               (not target-item-id) (assoc :track-id target-track-id)
                               (some? index) (assoc :index index)))]
      (if (and select-target? (not (identical? state (:state result))))
        (update result :state
                (fn [s]
                  (if target-item-id
                    (let [clip (list-helpers/find-by-id (get-in s (conj (chain-path show-id) :items) []) target-item-id)]
                      (-> (view-assoc s show-id :selection #{target-item-id})
                          (select-track-in-sidebar show-id (:track-id clip))))
                    (-> (view-assoc s show-id :selection #{})
                        (select-track-in-sidebar show-id target-track-id)))))
        result))
    {:state state}))


;; Transport: play / pause / stop / seek one show.
;; These only touch [:playback ...] and the per-show cursor, so they're never
;; undo steps and never mark the project unsaved. The show plays under its
;; playback key, (shows/play-key show-id).


(defn- unless-chasing [handler {:keys [show-id state] :as event}]
  (if (playback/chases-mtc? (show-at state show-id))
    {:state state}
    (handler event)))

(defn- active-timing [state show-id]
  (get-in state [:playback :active-cues (shows/play-key show-id)]))

(defn- start-beat
  "Where Play starts: :from, else the cursor; a position at or past the end
   of a timeline that doesn't loop starts from 0."
  [state show-id from]
  (let [cue (show-at state show-id)
        beat (double (or from (view-get state show-id :cursor-beat) 0.0))
        length (model/effective-length cue)]
    (if (and (not (get-in cue [:loop :enabled?])) (pos? length) (>= beat length))
      0.0
      beat)))

(defn- handle-play
  "Play show :show-id from :from (default: the cursor), as a new run.
   Paused: resume (seeking to :from if given). Playing: seek to :from.
   The show clock does the moving; this only writes the intent."
  [{:keys [show-id from state]}]
  (let [k (shows/play-key show-id)]
    {:state
     (if (nil? (active-timing state show-id))
       (update state :playback cue-timing/activate-cue k (random-uuid)
               {:timeline? true :start-beat (start-beat state show-id from)})
       (update-in state [:playback :active-cues k]
                  (fn [i]
                    (cond-> (assoc i :paused? false :timeline? true)
                      (some? from) (cue-timing/request-seek from (random-uuid))))))}))

(defn- handle-pause
  "Pause or resume the playing show. No-op when it isn't playing."
  [{:keys [show-id state]}]
  {:state (if (active-timing state show-id)
            (update-in state [:playback :active-cues (shows/play-key show-id) :paused?] not)
            state)})

(defn- handle-toggle-play
  "Play/pause button and Space: pause while playing, otherwise play."
  [{:keys [show-id state] :as event}]
  (let [timing (active-timing state show-id)]
    (if (and timing (not (:paused? timing)))
      (handle-pause event)
      (handle-play (dissoc event :from)))))

(defn- handle-stop
  "Stop the show. The cursor stays where it is."
  [{:keys [show-id state]}]
  {:state (update state :playback cue-timing/deactivate-cue (shows/play-key show-id))})

(defn- handle-seek
  "Move the cursor to :beat; a playing (or paused) show jumps there too, at
   the show clock's next tick."
  [{:keys [show-id beat state]}]
  (let [beat (max 0.0 (double (or beat 0.0)))]
    {:state (cond-> (view-assoc state show-id :cursor-beat beat)
              (active-timing state show-id)
              (update-in [:playback :active-cues (shows/play-key show-id)]
                         cue-timing/request-seek beat (random-uuid)))}))

(defn- handle-set-ruler-mode
  "Ruler labels: :bars (bar.beat) or :time (show timecode)."
  [{:keys [mode state]}]
  {:state (prefs-assoc state [:ruler-mode] (if (= mode :time) :time :bars))})


;; Pipeline middleware


(defn- restores-history?
  "Undo/redo restore a snapshot exactly; normalizing it again could differ
   from the saved baseline (e.g. a fresh track id)."
  [{:keys [event/type]}]
  (or (#{:edit/undo :edit/redo} type)
      (= "history" (some-> type namespace))))

(defn- versioned
  "state with the cue at cell following timecode as the next version."
  [state cell]
  (let [n (get-in state [:playback :chase-sources :next] 1)]
    (-> state
        (assoc-in [:playback :chase-sources :cells cell] n)
        (assoc-in [:playback :chase-sources :next] (inc n)))))

(defn- unversioned
  "state with no version for cell, whose cue is gone or doesn't follow
   timecode, and without the chase marker there that still followed the old
   one, stopped as any cue stops (cue-timing/deactivate-cue: the last one
   stops the transport). Another intent there (a run the same edit started
   or moved in) stays."
  [state cell]
  (cond-> state
    (contains? (get-in state [:playback :chase-sources :cells]) cell)
    (update-in [:playback :chase-sources :cells] dissoc cell)

    (:chasing? (get-in state [:playback :active-cues cell]))
    (update :playback cue-timing/deactivate-cue cell)))

(def ^:private cue-domains
  "Where the cues that can follow timecode live, each with the playback key
   of one of its entries: the library's shows, and the grid's cues (an older
   file's grid timeline still plays from its cell)."
  [[[:chains :shows] shows/play-key]
   [[:chains :cue-chains] identity]])

(defn- followers
  "{playback key 0} for every cue in state that follows timecode."
  [state]
  (into {}
        (for [[path key-of] cue-domains
              [k cue] (get-in state path)
              :when (playback/chases-mtc? cue)]
          [(key-of k) 0])))

(defn reconcile-chase-sources
  "after, the state an event left (normalized, or as undo or redo restored
   it), with [:playback :chase-sources] following its cues (reactivity plan
   15): every cue the event changed (not identical? to before's), a show in
   [:chains :shows] or a grid cue in [:chains :cue-chains], that follows
   timecode gets the next version under its playback key, even one equal to
   the cue it replaced; one gone or not following loses its version, and
   its chase marker goes (unversioned), so the next toggle or editor Play
   finds the key free before any tick. The show clock chases a cue only at
   its current version (show-time/step), so an old episode never draws a
   new cue. A follower's marker stays for the clock to rebind or retire.
   Other keys keep theirs; :next never goes back. A new show (another
   [:project :document-id]: New, Open) starts its followers at 0."
  [before after]
  (if (not= (h/document-id before) (h/document-id after))
    (assoc-in after [:playback :chase-sources]
              {:next (get-in after [:playback :chase-sources :next] 1)
               :cells (followers after)})
    (reduce (fn [state [path key-of]]
              (let [old (get-in before path)
                    new (get-in after path)]
                (if (identical? old new)
                  state
                  (reduce (fn [state k]
                            (let [cue (get new k ::none)]
                              (cond
                                (identical? cue (get old k ::none)) state
                                (playback/chases-mtc? (get new k)) (versioned state (key-of k))
                                :else (unversioned state (key-of k)))))
                          state
                          (distinct (concat (keys new) (keys old)))))))
            after
            cue-domains)))

(defn- normalize-domain
  "after with the timelines of the cue domain at path the event changed
   normalized (model/normalize-timeline-cues); identical when none needs it."
  [before after path]
  (let [old (get-in before path)
        new (get-in after path)]
    (if (identical? old new)
      after
      (let [normalized (model/normalize-timeline-cues old new)]
        (if (identical? normalized new)
          after
          (assoc-in after path normalized))))))

(defn- turn-off-reason
  "Why a show's timecode went off, after its title: \"needs a BPM\"…"
  [shows {:keys [reason with]}]
  (case reason
    :no-bpm "needs a BPM"
    :empty "empty"
    :overlap (str "overlaps '" (shows/title (get shows with)) "'")))

(defn- turn-off-text
  "The notice for the shows whose timecode an edit turned off: off is
   invalid-timecode-ids' [[id problem] …], shows the library before the
   turn-off (07 §4.2)."
  [shows off]
  (if (= 1 (count off))
    (let [[id {:keys [reason with range]}] (first off)]
      (str "Timecode off for '" (shows/title (get shows id)) "': "
           (case reason
             :no-bpm "it needs a BPM"
             :empty "it is empty"
             :overlap (str "it now overlaps '" (shows/title (get shows with)) "' ("
                           (shows/tc-range-text range) ")"))))
    (str "Timecode off for " (count off) " shows: "
         (str/join ", " (map (fn [[id p]] (str "'" (shows/title (get shows id)) "' (" (turn-off-reason shows p) ")"))
                             off)))))

(defn- turn-off-invalid
  "TS-6 after an edit: [library' off], library' the library (new) with
   timecode turned off for each show the edit changed (not identical? to
   old's) that now breaks it, off those as invalid-timecode-ids' pairs."
  [old new]
  (if (identical? old new)
    [new []]
    (let [changed (keep (fn [[id show]] (when-not (identical? show (get old id)) id)) new)
          off (shows/invalid-timecode-ids new changed)]
      [(reduce (fn [m [id]] (update m id dissoc :timeline/chase)) new off) off])))

(defn wrap-normalize-timelines
  "Middleware: after the handler runs, enforce the timeline invariants
   (model/normalize-timeline-cue) on every show and every cue chain the
   event changed, turn timecode off for each show the edit left breaking
   TS-6 (turn-off-invalid: it overlaps another, or lost its BPM or length),
   saying so in a notice merged into the handler's (h/merge-notice), and
   follow the change with the versions of the cues that follow timecode
   (reconcile-chase-sources), which also drops a turned-off show's chase
   marker in this same event. Undo and redo restore theirs as they were
   (not normalized again, no turn-off), and are versioned too, as is a new
   show (New, Open: no turn-off either, so Open leaves the file saved).
   Runs innermost, so undo snapshots and the unsaved flag see normalized
   state, the turn-off in the edit's own step. Events that touch neither or
   replace the document only pay two identical? checks and a document id
   comparison."
  [handler]
  (fn [event]
    (let [effects (handler event)
          before (:state event)
          after (:state effects)]
      (if (or (nil? after)
              (and (every? (fn [[path]] (identical? (get-in before path) (get-in after path))) cue-domains)
                   (= (h/document-id before) (h/document-id after))))
        effects
        (let [restore? (restores-history? event)
              normalized (if restore?
                           after
                           (reduce (fn [s [path]] (normalize-domain before s path)) after cue-domains))
              library (get-in normalized [:chains :shows])
              [library' off] (if (or restore? (not= (h/document-id before) (h/document-id after)))
                               [library []]
                               (turn-off-invalid (get-in before [:chains :shows]) library))
              checked (if (seq off) (assoc-in normalized [:chains :shows] library') normalized)
              after' (reconcile-chase-sources before checked)
              effects' (if (identical? after' after) effects (assoc effects :state after'))]
          (if (seq off)
            (h/merge-notice effects' before (turn-off-text library off) :warning)
            effects'))))))


;; Public API


(def ^:private show-handlers
  "The events that address a show, by type: each runs with the event's
   :show-id (event-show), and only when that show is there."
  {;; Selection
   :timeline/select-items handle-select-items
   :timeline/clear-selection handle-clear-selection
   :timeline/delete-selection handle-delete-selection
   :timeline/select-track handle-select-track
   :timeline/set-cursor-beat handle-set-cursor-beat

   ;; Item timing
   :timeline/nudge-selection handle-nudge-selection

   ;; Loop timing
   :timeline/toggle-loop handle-toggle-loop

   ;; Edits as data
   :timeline/apply-op handle-apply-op

   ;; Transport. A show following MIDI timecode ignores them: the timecode
   ;; drives it
   :timeline/play (partial unless-chasing handle-play)
   :timeline/pause (partial unless-chasing handle-pause)
   :timeline/toggle-play (partial unless-chasing handle-toggle-play)
   :timeline/stop (partial unless-chasing handle-stop)
   :timeline/seek (partial unless-chasing handle-seek)

   ;; Track CRUD
   :timeline/update-tracks handle-update-tracks
   :timeline/set-track-muted handle-set-track-muted
   :timeline/add-track handle-add-track
   :timeline/add-folder handle-add-folder
   :timeline/set-track-target handle-set-track-target
   :timeline/set-clip-target handle-set-clip-target

   ;; Add panel
   :timeline/add-preset-to-track handle-add-preset-to-track
   :timeline/add-effect-to-item handle-add-effect-to-timeline-item

   ;; The footer
   :timeline/set-name handle-set-name
   :timeline/set-cue-target handle-set-cue-target})

(defn- handle-show-event
  "Run handler on the event's show (event-show). A show that isn't there,
   or none named: nothing changes."
  [handler {:keys [state] :as event}]
  (let [show-id (event-show event)]
    (if (show-at state show-id)
      (handler (assoc event :show-id show-id))
      {:state state})))

(defn handle
  "Dispatch timeline events to their handlers.
   
   Accepts events with :event/type in the :timeline/* namespace."
  [{:keys [event/type] :as event}]
  (if-let [handler (show-handlers type)]
    (handle-show-event handler event)
    (case type
      ;; View controls and editor preferences: no show
      :timeline/set-snap (handle-set-snap event)
      :timeline/toggle-inspector (handle-toggle-inspector event)
      :timeline/set-detail-height (handle-set-detail-height event)
      :timeline/set-ruler-mode (handle-set-ruler-mode event)
      :timeline/toggle-add-panel (handle-toggle-add-panel event)
      :timeline/set-add-panel-tab (handle-set-add-panel-tab event)

      ;; Unknown event in this domain
      (do
        (log/warn "Unknown timeline event:" type)
        {}))))
