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
   - Timeline UI: [:ui :timeline]
   - Tracks: [:chains :cue-chains [col row] :tracks]
   - Item timing: [:chains :cue-chains [col row] :items ... :timeline/start :timeline/duration]"
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.animation.chains :as chains]
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


(defn- snap-opts
  "Current snap settings in the shape model/snap expects."
  [state]
  {:enabled? (get-in state [:ui :timeline :snap-enabled?] true)
   :value (get-in state [:ui :timeline :snap-value] 0.25)})

(defn- update-item-at-id
  "Update an item by ID within a nested chain structure.
   Returns updated items vector, or original if ID not found."
  [items id update-fn]
  (if-let [path (chains/find-path-by-id items id)]
    (update-in (vec items) path update-fn)
    items))


;; View Controls


(defn- handle-set-zoom
  "Set the horizontal zoom level (pixels per beat). Clamped to bounds.
   Takes :zoom, or the slider's new value from :fx/event."
  [{:keys [zoom state] :as event}]
  (let [zoom (h/first-some zoom (:fx/event event) (get-in state [:ui :timeline :zoom-x]) 50.0)
        clamped (-> (double zoom) (max min-zoom) (min max-zoom))]
    {:state (assoc-in state [:ui :timeline :zoom-x] clamped)}))

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
  [{:keys [pane state]}]
  (when-let [source-sp (get @scroll-panes pane)]
    (let [vval (.getVvalue source-sp)
          target-pane (if (= pane :left) :right :left)]
      (when-let [target-sp (get @scroll-panes target-pane)]
        (when (not= (.getVvalue target-sp) vval)
          (run-on-fx! #(.setVvalue target-sp vval))))
      {:state (assoc-in state [:ui :timeline :sync-scroll-y] vval)})))

(defn- handle-set-hvalue
  "Store the normalized horizontal scroll position (0.0 to 1.0).
   The ScrollPane is the source of truth; this never writes back to it."
  [{:keys [hvalue fx/event state]}]
  (let [val (or hvalue event 0.0)
        clamped (-> (double val) (max 0.0) (min 1.0))]
    {:state (assoc-in state [:ui :timeline :scroll-hvalue] clamped)}))

(defn- handle-zoom-at-cursor
  "Zoom timeline horizontally centered on the mouse cursor beat position.
   Positive delta-y zooms in; negative delta-y zooms out."
  [{:keys [focal-beat viewport-x cursor-x x delta-y col row state]}]
  (let [current-zoom (get-in state [:ui :timeline :zoom-x] 50.0)
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
                    (assoc-in [:ui :timeline :zoom-x] new-zoom)
                    (assoc-in [:ui :timeline :scroll-hvalue] new-hvalue))})
      ;; Fallback if scroll pane not registered (e.g. headless unit tests)
      (let [new-zoom (-> (* current-zoom zoom-factor) (max min-zoom) (min max-zoom))
            cur-h (get-in state [:ui :timeline :scroll-hvalue] 0.0)]
        {:state (-> state
                    (assoc-in [:ui :timeline :zoom-x] new-zoom)
                    (assoc-in [:ui :timeline :scroll-hvalue] cur-h))}))))

(defn- handle-set-snap
  "Configure snap-to-grid settings.
   :enabled? comes from the event or, for the checkbox, from a boolean :fx/event."
  [{:keys [enabled? value state] :as event}]
  (let [current-enabled? (get-in state [:ui :timeline :snap-enabled?] true)
        current-value (get-in state [:ui :timeline :snap-value] 0.25)
        fx-enabled? (let [e (:fx/event event)] (when (boolean? e) e))]
    {:state (-> state
                (assoc-in [:ui :timeline :snap-enabled?]
                          (h/first-some enabled? fx-enabled? current-enabled?))
                (assoc-in [:ui :timeline :snap-value]
                          (or value current-value)))}))


;; Selection


(defn- handle-select-items
  "Update selection state.
   Modes:
   - :replace - Set selection to exactly these IDs
   - :add     - Add IDs to existing selection
   - :toggle  - Toggle each ID in/out of selection"
  [{:keys [ids mode state]}]
  (let [current (get-in state [:ui :timeline :selection] #{})
        id-set (set ids)
        new-selection (case (or mode :replace)
                        :replace id-set
                        :add (into current id-set)
                        :toggle (reduce (fn [sel id]
                                          (if (contains? sel id)
                                            (disj sel id)
                                            (conj sel id)))
                                        current id-set))]
    {:state (assoc-in state [:ui :timeline :selection] new-selection)}))

(defn- handle-clear-selection
  "Clear all selected items."
  [{:keys [state]}]
  {:state (assoc-in state [:ui :timeline :selection] #{})})

(defn- handle-delete-selection
  "Delete the selected clips from the cue chain and clear the selection.
   No-op when nothing is selected."
  [{:keys [col row state]}]
  (let [items-path [:chains :cue-chains [col row] :items]
        selection (get-in state [:ui :timeline :selection] #{})]
    (if (empty? selection)
      {:state state}
      {:state (-> state
                  (update-in items-path #(list-helpers/remove-by-ids (or % []) selection))
                  (assoc-in [:ui :timeline :selection] #{}))})))


;; Loop Timing

(defn- handle-update-loop-timing
  "Update the loop brace from a drag.
   :edge :center moves the loop to :start, :left trims its start to :start,
   :right trims its end to :end. A missing loop starts out disabled."
  [{:keys [col row edge start end state]}]
  (let [loop-path [:chains :cue-chains [col row] :loop]
        opts (snap-opts state)
        current-loop (get-in state loop-path {:enabled? false :start 0.0 :duration 4.0})
        updated-loop (case edge
                       :center (model/move-loop current-loop (- (double start) (double (:start current-loop 0.0))) opts)
                       :left (model/trim-loop-start current-loop start opts)
                       :right (model/trim-loop-end current-loop end opts)
                       current-loop)]
    {:state (assoc-in state loop-path updated-loop)}))

(defn- handle-toggle-loop
  "Toggle the loop enabled state for a cue-chain."
  [{:keys [col row state]}]
  (let [loop-path [:chains :cue-chains [col row] :loop]
        current-loop (get-in state loop-path {:enabled? false :start 0.0 :duration 4.0})
        updated-loop (update current-loop :enabled? not)]
    {:state (-> state
                (assoc-in loop-path updated-loop))}))


;; Item Timing


(defn- handle-update-item-timing
  "Move or trim a single clip by ID from a drag.
   :edge :center moves the clip to :start (keeping its length),
   :left trims its start to :start (keeping its end),
   :right trims its end to :end (keeping its start).
   Snaps when enabled; start >= 0 and length >= model/min-clip-length."
  [{:keys [col row id edge start end state]}]
  (let [items-path [:chains :cue-chains [col row] :items]
        items (get-in state items-path [])
        opts (snap-opts state)
        updated-items (update-item-at-id
                       items id
                       (fn [item]
                         (case edge
                           :center (model/move-clip item (- (double start) (double (or (model/clip-start item) 0.0))) opts)
                           :left (model/trim-start item start opts)
                           :right (model/trim-end item end opts)
                           item)))]
    {:state (assoc-in state items-path updated-items)}))

(defn- handle-nudge-selection
  "Move all selected items by delta-beats."
  [{:keys [col row delta-beats state]}]
  (let [items-path [:chains :cue-chains [col row] :items]
        items (get-in state items-path [])
        selection (get-in state [:ui :timeline :selection] #{})
        opts (snap-opts state)
        updated-items (reduce
                       (fn [acc id]
                         (update-item-at-id acc id #(model/move-clip % delta-beats opts)))
                       items
                       selection)]
    {:state (-> state
                (assoc-in items-path updated-items))
     :history/coalesce-key [:timeline/nudge-selection col row]}))


;; Track CRUD


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
                  (update-in [:ui :timeline :selection] #(reduce disj (or % #{}) orphan-ids))))}))

(defn- handle-set-track-muted
  "Mute or unmute a track or folder (a muted folder mutes everything in it).
   :muted? is a live field (state.history/live-item-paths): not undoable and
   not an unsaved change, but saved with the project."
  [{:keys [col row track-id muted? state]}]
  (let [tracks-path [:chains :cue-chains [col row] :tracks]
        tracks (get-in state tracks-path [])]
    (if-let [path (chains/find-path-by-id tracks track-id)]
      {:state (assoc-in state tracks-path
                        (update-in (vec tracks) path
                                   #(if muted? (assoc % :muted? true) (dissoc % :muted?))))}
      {:state state})))

(defn- handle-move-item-to-track
  "Reassign an item to a different track.
   This effectively changes the item's zone routing."
  [{:keys [col row item-id track-id state]}]
  (let [items-path [:chains :cue-chains [col row] :items]
        items (get-in state items-path [])
        updated-items (update-item-at-id
                       items item-id
                       #(assoc % :track-id track-id))]
    {:state (-> state
                (assoc-in items-path updated-items))}))

(defn- handle-set-track-zone
  "Set or clear the zone group assignment on a track or folder.
   If :zone-group-id is nil or :inherit, removes the key so the track inherits from its parent folder."
  [{:keys [col row track-id zone-group-id state]}]
  (let [tracks-path [:chains :cue-chains [col row] :tracks]
        tracks (get-in state tracks-path [])]
    (if-let [path (chains/find-path-by-id tracks track-id)]
      (let [updated-tracks (if (and zone-group-id (not= zone-group-id :inherit))
                             (assoc-in (vec tracks) (conj path :zone-group-id) zone-group-id)
                             (update-in (vec tracks) path dissoc :zone-group-id))]
        {:state (-> state
                    (assoc-in tracks-path updated-tracks))})
      {:state state})))

(defn- handle-set-clip-zone
  "Set or clear an explicit zone override on a clip/item.
   If :zone-group-id is nil or :inherit, removes the key so the clip inherits from its assigned track."
  [{:keys [col row item-id zone-group-id state]}]
  (let [items-path [:chains :cue-chains [col row] :items]
        items (get-in state items-path [])]
    (if-let [path (chains/find-path-by-id items item-id)]
      (let [updated-items (if (and zone-group-id (not= zone-group-id :inherit))
                            (assoc-in (vec items) (conj path :zone-group-id) zone-group-id)
                            (update-in (vec items) path dissoc :zone-group-id))]
        {:state (-> state
                    (assoc-in items-path updated-items))})
      {:state state})))

(defn- handle-add-track
  "Appends a new regular track to the cue chain's tracks.
   Optional :zone-group-id sets its zone; without it the track inherits
   (its folder's zone, or the chain's default zone at the top level)."
  [{:keys [col row zone-group-id state]}]
  (let [tracks-path [:chains :cue-chains [col row] :tracks]
        tracks (get-in state tracks-path [])
        new-track (model/create-track {:zone-group-id zone-group-id
                                    :name (str "Track " (inc (count tracks)))
                                    :color (model/palette-color (count (model/leaf-track-ids tracks)))})
        new-track-id (:id new-track)
        updated-tracks (conj tracks new-track)]
    {:state (-> state
                (assoc-in tracks-path updated-tracks)
                (assoc-in [:list-ui :timeline-tracks :selected-ids] #{new-track-id})
                (assoc-in [:list-ui :timeline-tracks :last-selected-id] new-track-id))}))

(defn- handle-add-folder
  "Appends a new track folder to the cue chain's tracks.
   Optional :zone-group-id sets its zone; without it the folder inherits."
  [{:keys [col row zone-group-id state]}]
  (let [tracks-path [:chains :cue-chains [col row] :tracks]
        tracks (get-in state tracks-path [])
        new-folder (assoc (model/create-track {:zone-group-id zone-group-id
                                            :name (str "Folder " (inc (count tracks)))})
                          :type :group
                          :collapsed? false
                          :items [])
        updated-tracks (conj tracks new-folder)]
    {:state (-> state
                (assoc-in tracks-path updated-tracks))}))

(defn- handle-select-track
  "Set the currently selected track ID."
  [{:keys [track-id state]}]
  {:state (-> state
              (assoc-in [:list-ui :timeline-tracks :selected-ids] (if track-id #{track-id} #{}))
              (assoc-in [:list-ui :timeline-tracks :last-selected-id] track-id))})

(defn- handle-set-cursor-beat
  "Set the timeline cursor beat position, snapping if enabled."
  [{:keys [beat state]}]
  (let [snapped (max 0.0 (model/snap (or beat 0.0) (snap-opts state)))]
    {:state (assoc-in state [:ui :timeline :cursor-beat] snapped)}))


;; Add Panel (preset/effect addition from timeline)


(defn- handle-toggle-add-panel
  "Toggle the bottom add-content panel open/closed."
  [{:keys [state]}]
  (let [current (get-in state [:ui :timeline :add-panel-open?] false)]
    {:state (assoc-in state [:ui :timeline :add-panel-open?] (not current))}))

(defn- handle-set-add-panel-tab
  "Set the active tab in the add panel's preset or effect bank.
   :panel is :preset or :effect, :tab-id is the category keyword."
  [{:keys [panel tab-id state]}]
  (let [k (if (= panel :effect)
            :add-panel-effect-tab
            :add-panel-preset-tab)]
    {:state (assoc-in state [:ui :timeline k] tab-id)}))

(defn- handle-add-preset-to-track
  "Add a preset item to a specific track in the timeline.
   Creates the preset, assigns it to the given track, and sets
   default timeline position and duration.
   
   If no :track-id is provided or points to a folder, uses a valid leaf track."
  [{:keys [col row item-id track-id start state]}]
  (let [items-path [:chains :cue-chains [col row] :items]
        tracks-path [:chains :cue-chains [col row] :tracks]
        tracks (get-in state tracks-path [])
        [tracks state-with-tracks] (if (empty? tracks)
                                     (let [default-tr (model/create-track {:name "Track 1"})]
                                       [[default-tr] (assoc-in state tracks-path [default-tr])])
                                     [tracks state])
        resolved-track-id (model/insert-track tracks track-id)
        cursor-beat (get-in state-with-tracks [:ui :timeline :cursor-beat] 0.0)
        raw-start (or start cursor-beat 0.0)
        start-beat (max 0.0 (model/snap raw-start (snap-opts state-with-tracks)))
        duration-beats model/default-clip-length
        new-preset (-> (cue-chains/create-preset-instance item-id {})
                       (h/ensure-item-fields)
                       (assoc :track-id resolved-track-id
                              :timeline/start start-beat
                              :timeline/duration duration-beats))
        new-id (:id new-preset)
        next-cursor (+ start-beat duration-beats)]
    {:state (-> state-with-tracks
                (update-in items-path (fnil conj []) new-preset)
                (assoc-in [:ui :timeline :cursor-beat] next-cursor)
                (assoc-in [:list-ui :timeline-tracks :selected-ids] #{resolved-track-id})
                (assoc-in [:list-ui :timeline-tracks :last-selected-id] resolved-track-id))
     ;; Select the newly added item on the timeline
     :dispatch {:event/type :timeline/select-items
                :ids [new-id]
                :mode :replace}}))

(defn- handle-add-effect-to-timeline-item
  "Add an effect to a timeline item identified by :target-item-id.
   Builds default params from the effect definition."
  [{:keys [col row item item-id target-item-id state]}]
  (let [items-path [:chains :cue-chains [col row] :items]
        items (get-in state items-path [])
        effect-id (or item-id (:id item))
        params-map (reduce (fn [acc {:keys [key default]}]
                             (assoc acc key default))
                           {}
                           (:parameters item []))
        new-effect (h/ensure-item-fields {:effect-id effect-id
                                          :params params-map})
        updated-items (if-let [path (chains/find-path-by-id items target-item-id)]
                        (update-in (vec items) (conj path :effects)
                                   (fnil conj []) new-effect)
                        items)]
    {:state (-> state
                (assoc-in items-path updated-items))}))


;; Open Editor


(defn- reset-view-for-cell
  "Selection, cursor, scroll and track selection belong to one cue.
   Clear them when the editor opens on a different cell."
  [state col row]
  (let [{open-col :col open-row :row} (get-in state [:ui :dialogs :timeline-cue-editor])]
    (if (= [open-col open-row] [col row])
      state
      (-> state
          (update-in [:ui :timeline] assoc :selection #{} :cursor-beat 0.0 :scroll-hvalue 0.0)
          (assoc-in [:list-ui :timeline-tracks] {})))))

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
        effects {:state (cond-> (reset-view-for-cell state col row)
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
    :timeline/update-item-timing (handle-update-item-timing event)
    :timeline/nudge-selection (handle-nudge-selection event)

    ;; Loop timing
    :timeline/update-loop-timing (handle-update-loop-timing event)
    :timeline/toggle-loop (handle-toggle-loop event)

    ;; Track CRUD
    :timeline/update-tracks (handle-update-tracks event)
    :timeline/set-track-muted (handle-set-track-muted event)
    :timeline/move-item-to-track (handle-move-item-to-track event)
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
