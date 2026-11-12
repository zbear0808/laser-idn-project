(ns laser-show.events.handlers.timeline
  "Event handlers for timeline editor operations.
   
   Handles:
   - View controls: zoom, scroll, snap
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
            [laser-show.events.handlers.list.helpers :as list-helpers]
            [laser-show.views.components.visual-editors.timeline.track-logic :as tl]
            [laser-show.timeline.model :as model])
  (:import [javafx.scene.control ScrollPane]
           [javafx.scene Node]
           [javafx.application Platform]))


;; Constants


(def ^:private min-zoom 10.0)
(def ^:private max-zoom 200.0)


;; Helpers


;; UI state: per cue at [:ui :timeline-editor [col row]] (selection, cursor,
;; zoom, scroll), editor-wide prefs at [:ui :timeline-prefs] (snap, add
;; panel), and the track sidebar's list state at [:list-ui [:timeline-tracks col row]].

(def default-view
  "Per-cue editor view state."
  {:selection #{} :cursor-beat 0.0 :zoom-x 50.0 :scroll-hvalue 0.0})

(def default-prefs
  "Editor-wide timeline preferences."
  {:snap {:enabled? true :value 0.25}
   :add-panel {:open? false :preset-tab :geometric :effect-tab :shape}})

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


(defn- handle-set-zoom
  "Set the horizontal zoom level (pixels per beat). Clamped to bounds.
   Takes :zoom, or the slider's new value from :fx/event."
  [{:keys [zoom state] :as event}]
  (let [[col row] (event-cell event)
        zoom (h/first-some zoom (:fx/event event) (view-get state col row :zoom-x))
        clamped (-> (double zoom) (max min-zoom) (min max-zoom))]
    {:state (view-assoc state col row :zoom-x clamped)}))

(defonce ^:private scroll-panes (atom {}))

(defn- handle-register-scroll-pane
  "Store the ScrollPane instances for synchronized scrolling."
  [{:keys [pane instance]}]
  (if instance
    (swap! scroll-panes assoc pane instance)
    (swap! scroll-panes dissoc pane))
  {})

(defn- run-on-fx!
  "Run function on JavaFX application thread safely."
  [f]
  (try
    (if (Platform/isFxApplicationThread)
      (f)
      (Platform/runLater f))
    (catch Exception _
      (try (f) (catch Exception _ nil)))))

(defn- handle-sync-scroll
  "Link the vertical scroll between the left list sidebar and the right timeline canvas."
  [{:keys [pane]}]
  (when-let [source-sp (get @scroll-panes pane)]
    (let [vval (.getVvalue source-sp)
          target-pane (if (= pane :left) :right :left)]
      (when-let [target-sp (get @scroll-panes target-pane)]
        (when (not= (.getVvalue target-sp) vval)
          (run-on-fx! #(.setVvalue target-sp vval))))
      {})))

(defn- handle-set-hvalue
  "Store the normalized horizontal scroll position (0.0 to 1.0).
   The ScrollPane is the source of truth; this never writes back to it."
  [{:keys [hvalue fx/event state] :as e}]
  (let [[col row] (event-cell e)
        val (or hvalue event 0.0)
        clamped (-> (double val) (max 0.0) (min 1.0))]
    {:state (view-assoc state col row :scroll-hvalue clamped)}))

(defn- handle-zoom-at-cursor
  "Zoom timeline horizontally centered on the mouse cursor beat position.
   Positive delta-y zooms in; negative delta-y zooms out."
  [{:keys [focal-beat viewport-x cursor-x x delta-y col row state]}]
  (let [current-zoom (view-get state col row :zoom-x)
        zoom-factor (if (pos? (double (or delta-y 0.0))) 1.15 (/ 1.0 1.15))]
    (if-let [^ScrollPane sp (get @scroll-panes :right)]
      (let [content-node ^Node (.getContent sp)
            content-width (if content-node (.getWidth (.getLayoutBounds content-node)) 0.0)
            viewport-bounds (.getViewportBounds sp)
            viewport-width (if viewport-bounds (.getWidth viewport-bounds) 0.0)
            cur-h (.getHvalue sp)
            items (get-in state [:chains :cue-chains [col row] :items] [])
            loop-config (get-in state [:chains :cue-chains [col row] :loop])
            candidate-zoom (-> (* current-zoom zoom-factor) (max min-zoom) (min max-zoom))
            new-content-width (tl/calculate-canvas-width
                               {:items items
                                :loop-config loop-config
                                :zoom-x candidate-zoom})
            {:keys [new-zoom new-hvalue]} (tl/calculate-zoom-at-cursor
                                           {:current-zoom current-zoom
                                            :zoom-factor zoom-factor
                                            :focal-beat focal-beat
                                            :viewport-x viewport-x
                                            :cursor-x (or cursor-x x)
                                            :current-hvalue cur-h
                                            :content-width content-width
                                            :viewport-width viewport-width
                                            :new-content-width new-content-width
                                            :min-zoom min-zoom
                                            :max-zoom max-zoom})]
        (run-on-fx! #(.setHvalue sp new-hvalue))
        {:state (-> state
                    (view-assoc col row :zoom-x new-zoom)
                    (view-assoc col row :scroll-hvalue new-hvalue))})
      ;; Fallback if scroll pane not registered (e.g. headless unit tests)
      (let [new-zoom (-> (* current-zoom zoom-factor) (max min-zoom) (min max-zoom))]
        {:state (view-assoc state col row :zoom-x new-zoom)}))))

(defn- handle-set-snap
  "Configure snap-to-grid settings.
   :enabled? comes from the event or, for the checkbox, from a boolean :fx/event."
  [{:keys [enabled? value state] :as event}]
  (let [{current-enabled? :enabled? current-value :value} (snap-opts state)
        fx-enabled? (let [e (:fx/event event)] (when (boolean? e) e))]
    {:state (prefs-assoc state [:snap]
                         {:enabled? (h/first-some enabled? fx-enabled? current-enabled?)
                          :value (or value current-value)})}))


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
    :timeline/set-zoom (handle-set-zoom event)
    :timeline/zoom-at-cursor (handle-zoom-at-cursor event)
    :timeline/set-hvalue (handle-set-hvalue event)
    :timeline/set-snap (handle-set-snap event)
    :timeline/register-scroll-pane (handle-register-scroll-pane event)
    :timeline/sync-scroll (handle-sync-scroll event)

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
