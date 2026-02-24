(ns laser-show.events.handlers.timeline
  "Event handlers for timeline editor operations.
   
   Handles:
   - View controls: zoom, scroll, snap
   - Item selection within the timeline
   - Item timing updates (start/duration)
   - Track CRUD: add, delete, update, move, init
   - Item-to-track assignment
   - Track expand/collapse for sub-effects
   - Nudge and resize of selected items
   
   State paths:
   - Timeline UI: [:ui :timeline]
   - Tracks: [:chains :cue-chains [col row] :tracks]
   - Item timing: [:chains :cue-chains [col row] :items ... :timeline/start :timeline/duration]"
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.animation.chains :as chains]
            [laser-show.views.components.visual-editors.timeline.track-logic :as tl]))


;; Constants


(def ^:private min-zoom 10.0)
(def ^:private max-zoom 500.0)
(def ^:private min-duration 0.0625) ;; 1/16 beat


;; Helpers


(defn- snap-value
  "Snap a beat value to the nearest grid subdivision.
   Returns the original value if snap is disabled."
  [beats snap-size snap-enabled?]
  (if snap-enabled?
    (* (Math/round (/ (double beats) (double snap-size)))
       (double snap-size))
    beats))

(defn- update-item-at-id
  "Update an item by ID within a nested chain structure.
   Returns updated items vector, or original if ID not found."
  [items id update-fn]
  (if-let [path (chains/find-path-by-id items id)]
    (update-in (vec items) path update-fn)
    items))


;; View Controls


(defn- handle-set-zoom
  "Set the horizontal zoom level (pixels per beat). Clamped to bounds."
  [{:keys [zoom state]}]
  (let [clamped (-> zoom (max min-zoom) (min max-zoom))]
    {:state (assoc-in state [:ui :timeline :zoom-x] clamped)}))

(defn- handle-scroll
  "Update horizontal scroll position."
  [{:keys [delta-x state]}]
  (let [current (get-in state [:ui :timeline :scroll-x] 0.0)]
    {:state (assoc-in state [:ui :timeline :scroll-x]
                      (max 0.0 (+ current delta-x)))}))

(defonce ^:private scroll-panes (atom {}))

(defn- handle-register-scroll-pane
  "Store the ScrollPane instances for synchronized scrolling."
  [{:keys [pane instance]}]
  (if instance
    (swap! scroll-panes assoc pane instance)
    (swap! scroll-panes dissoc pane))
  {})

(defn- handle-sync-scroll
  "Link the vertical scroll between the left list sidebar and the right timeline canvas."
  [{:keys [pane state]}]
  (when-let [source-sp (get @scroll-panes pane)]
    (let [vval (.getVvalue source-sp)
          target-pane (if (= pane :left) :right :left)]
      (when-let [target-sp (get @scroll-panes target-pane)]
        (when (not= (.getVvalue target-sp) vval)
          (.setVvalue target-sp vval)))
      {:state (assoc-in state [:ui :timeline :sync-scroll-y] vval)})))

(defn- handle-set-snap
  "Configure snap-to-grid settings."
  [{:keys [enabled? value state]}]
  (let [current-enabled? (get-in state [:ui :timeline :snap-enabled?] true)
        current-value (get-in state [:ui :timeline :snap-value] 0.25)]
    {:state (-> state
                (assoc-in [:ui :timeline :snap-enabled?]
                          (if (some? enabled?) enabled? current-enabled?))
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


;; Loop Timing

(defn- handle-update-loop-timing
  "Update the loop brace timing.
   Accepts :col :row and :start / :duration."
  [{:keys [col row start duration state]}]
  (let [loop-path [:chains :cue-chains [col row] :loop]
        snap-enabled? (get-in state [:ui :timeline :snap-enabled?] true)
        snap-size (get-in state [:ui :timeline :snap-value] 0.25)
        current-loop (get-in state loop-path {:enabled? true :start 0.0 :duration 4.0})
        new-start (if (some? start)
                    (max 0.0 (snap-value start snap-size snap-enabled?))
                    (:start current-loop 0.0))
        new-duration (if (some? duration)
                       (max min-duration (snap-value duration snap-size snap-enabled?))
                       (:duration current-loop 4.0))
        updated-loop (assoc current-loop :start new-start :duration new-duration)]
    {:state (-> state
                (assoc-in loop-path updated-loop)
                h/mark-dirty)}))

(defn- handle-toggle-loop
  "Toggle the loop enabled state for a cue-chain."
  [{:keys [col row state]}]
  (let [loop-path [:chains :cue-chains [col row] :loop]
        current-loop (get-in state loop-path {:enabled? false :start 0.0 :duration 4.0})
        updated-loop (update current-loop :enabled? not)]
    {:state (-> state
                (assoc-in loop-path updated-loop)
                h/mark-dirty)}))


;; Item Timing


(defn- handle-update-item-timing
  "Update :timeline/start and :timeline/duration for a single item by ID.
   Applies snap if enabled. Enforces start >= 0 and duration >= min-duration.
   
   Accepts :col :row to identify the cue chain, :id for the item,
   and :start / :duration for the new values (either or both)."
  [{:keys [col row id start duration state]}]
  (let [items-path [:chains :cue-chains [col row] :items]
        items (get-in state items-path [])
        snap-enabled? (get-in state [:ui :timeline :snap-enabled?] true)
        snap-size (get-in state [:ui :timeline :snap-value] 0.25)
        updated-items (update-item-at-id
                       items id
                       (fn [item]
                         (cond-> item
                           (some? start)
                           (assoc :timeline/start
                                  (max 0.0 (snap-value start snap-size snap-enabled?)))
                           (some? duration)
                           (assoc :timeline/duration
                                  (max min-duration
                                       (snap-value duration snap-size snap-enabled?))))))]
    {:state (-> state
                (assoc-in items-path updated-items)
                h/mark-dirty)}))

(defn- handle-nudge-selection
  "Move all selected items by delta-beats."
  [{:keys [col row delta-beats state]}]
  (let [items-path [:chains :cue-chains [col row] :items]
        items (get-in state items-path [])
        selection (get-in state [:ui :timeline :selection] #{})
        snap-enabled? (get-in state [:ui :timeline :snap-enabled?] true)
        snap-size (get-in state [:ui :timeline :snap-value] 0.25)
        updated-items (reduce
                       (fn [acc id]
                         (update-item-at-id
                          acc id
                          (fn [item]
                            (let [current-start (:timeline/start item 0.0)
                                  new-start (max 0.0 (+ current-start delta-beats))]
                              (assoc item :timeline/start
                                     (snap-value new-start snap-size snap-enabled?))))))
                       items
                       selection)]
    {:state (-> state
                (assoc-in items-path updated-items)
                h/mark-dirty)}))

(defn- handle-resize-selection
  "Adjust duration (and optionally start) of selected items.
   :side :right -> only change duration
   :side :left  -> change both start and duration (trim from left)"
  [{:keys [col row delta-duration side state]}]
  (let [items-path [:chains :cue-chains [col row] :items]
        items (get-in state items-path [])
        selection (get-in state [:ui :timeline :selection] #{})
        snap-enabled? (get-in state [:ui :timeline :snap-enabled?] true)
        snap-size (get-in state [:ui :timeline :snap-value] 0.25)
        updated-items (reduce
                       (fn [acc id]
                         (update-item-at-id
                          acc id
                          (fn [item]
                            (let [current-start (:timeline/start item 0.0)
                                  current-dur (:timeline/duration item 4.0)]
                              (if (= side :left)
                                ;; Left edge: move start, adjust duration inversely
                                (let [new-start (max 0.0 (+ current-start delta-duration))
                                      start-delta (- new-start current-start)
                                      new-dur (max min-duration (- current-dur start-delta))]
                                  (assoc item
                                         :timeline/start (snap-value new-start snap-size snap-enabled?)
                                         :timeline/duration (snap-value new-dur snap-size snap-enabled?)))
                                ;; Right edge: just adjust duration
                                (let [new-dur (max min-duration (+ current-dur delta-duration))]
                                  (assoc item :timeline/duration
                                         (snap-value new-dur snap-size snap-enabled?))))))))
                       items
                       selection)]
    {:state (-> state
                (assoc-in items-path updated-items)
                h/mark-dirty)}))


;; Track Expand/Collapse


(defn- handle-toggle-track-expand
  "Toggle an item's expanded state (shows/hides effect sub-tracks)."
  [{:keys [id state]}]
  (let [expanded (get-in state [:ui :timeline :expanded-tracks] #{})]
    {:state (assoc-in state [:ui :timeline :expanded-tracks]
                      (if (contains? expanded id)
                        (disj expanded id)
                        (conj expanded id)))}))


;; Track CRUD


(defn- handle-update-tracks
  "Replaces the entire tracks vector for a cue chain.
   This is the callback fired by the `list-editor` component when tracks
   are grouped, reordered, deleted, or pasted."
  [{:keys [col row items state]}]
  (let [tracks-path [:chains :cue-chains [col row] :tracks]]
    {:state (-> state
                (assoc-in tracks-path (vec items))
                h/mark-dirty)}))

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
                (assoc-in items-path updated-items)
                h/mark-dirty)}))

(defn- handle-init-tracks
  "Auto-initialize tracks for a cue chain that has none.
   Uses auto-initialize-tracks from track-logic."
  [{:keys [col row zone-groups state]}]
  (let [chain-path [:chains :cue-chains [col row]]
        cue-chain (get-in state chain-path)]
    (if (seq (:tracks cue-chain))
      {:state state} ;; Already has tracks, no-op
      (let [updated-chain (tl/auto-initialize-tracks cue-chain (or zone-groups {}))]
        {:state (-> state
                    (assoc-in chain-path updated-chain)
                    h/mark-dirty)}))))

(defn- handle-add-track
  "Appends a new regular track to the cue chain's tracks."
  [{:keys [col row state]}]
  (let [tracks-path [:chains :cue-chains [col row] :tracks]
        tracks (get-in state tracks-path [])
        new-track {:id (str (random-uuid))
                   :type :track
                   :name "New Track"}
        updated-tracks (conj tracks new-track)]
    {:state (-> state
                (assoc-in tracks-path updated-tracks)
                h/mark-dirty)}))

(defn- handle-add-folder
  "Appends a new track folder to the cue chain's tracks."
  [{:keys [col row state]}]
  (let [tracks-path [:chains :cue-chains [col row] :tracks]
        tracks (get-in state tracks-path [])
        new-folder {:id (str (random-uuid))
                    :type :group
                    :name "New Folder"
                    :collapsed? false
                    :items []}
        updated-tracks (conj tracks new-folder)]
    {:state (-> state
                (assoc-in tracks-path updated-tracks)
                h/mark-dirty)}))



;; Public API


(defn handle
  "Dispatch timeline events to their handlers.
   
   Accepts events with :event/type in the :timeline/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    ;; View controls
    :timeline/set-zoom (handle-set-zoom event)
    :timeline/scroll (handle-scroll event)
    :timeline/set-snap (handle-set-snap event)
    :timeline/register-scroll-pane (handle-register-scroll-pane event)
    :timeline/sync-scroll (handle-sync-scroll event)

    ;; Selection
    :timeline/select-items (handle-select-items event)
    :timeline/clear-selection (handle-clear-selection event)

    ;; Item timing
    :timeline/update-item-timing (handle-update-item-timing event)
    :timeline/nudge-selection (handle-nudge-selection event)
    :timeline/resize-selection (handle-resize-selection event)

    ;; Loop timing
    :timeline/update-loop-timing (handle-update-loop-timing event)
    :timeline/toggle-loop (handle-toggle-loop event)

    ;; Track expand/collapse
    :timeline/toggle-track-expand (handle-toggle-track-expand event)

    ;; Track CRUD
    :timeline/update-tracks (handle-update-tracks event)
    :timeline/move-item-to-track (handle-move-item-to-track event)
    :timeline/init-tracks (handle-init-tracks event)
    :timeline/add-track (handle-add-track event)
    :timeline/add-folder (handle-add-folder event)

    ;; Unknown event in this domain
    (do
      (log/warn "Unknown timeline event:" type)
      {})))
