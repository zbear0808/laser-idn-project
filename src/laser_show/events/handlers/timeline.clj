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
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.views.components.visual-editors.timeline.track-logic :as tl])
  (:import [javafx.scene.control ScrollPane]
           [javafx.scene Node]
           [javafx.application Platform]))


;; Constants


(def ^:private min-zoom 10.0)
(def ^:private max-zoom 200.0)
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
  "Set normalized horizontal scroll position (0.0 to 1.0)."
  [{:keys [hvalue fx/event state]}]
  (let [val (or hvalue event 0.0)
        clamped (-> (double val) (max 0.0) (min 1.0))]
    (when-let [^ScrollPane sp (get @scroll-panes :right)]
      (when (not= (.getHvalue sp) clamped)
        (run-on-fx! #(.setHvalue sp clamped))))
    {:state (assoc-in state [:ui :timeline :scroll-hvalue] clamped)}))

(defn- handle-scroll-horizontal
  "Update horizontal scroll position from mouse wheel scroll delta.
   Negative delta-y scrolls right (forward in time); positive scrolls left (backward in time)."
  [{:keys [delta-x delta-y state]}]
  (let [raw-delta (if (and (number? delta-x) (not (zero? delta-x)))
                    (double delta-x)
                    (double (or delta-y 0.0)))
        delta-px (- raw-delta)]
    (if-let [^ScrollPane sp (get @scroll-panes :right)]
      (let [content-node ^Node (.getContent sp)
            content-width (if content-node (.getWidth (.getLayoutBounds content-node)) 0.0)
            viewport-bounds (.getViewportBounds sp)
            viewport-width (if viewport-bounds (.getWidth viewport-bounds) 0.0)
            cur-h (.getHvalue sp)
            new-h (tl/calculate-horizontal-scroll
                   {:current-hvalue cur-h
                    :delta-px delta-px
                    :content-width content-width
                    :viewport-width viewport-width})]
        (run-on-fx! #(.setHvalue sp new-h))
        {:state (assoc-in state [:ui :timeline :scroll-hvalue] new-h)})
      (let [cur-h (get-in state [:ui :timeline :scroll-hvalue] 0.0)]
        {:state (assoc-in state [:ui :timeline :scroll-hvalue] cur-h)}))))

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
                    (assoc-in tracks-path updated-tracks)
                    h/mark-dirty)})
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
                    (assoc-in items-path updated-items)
                    h/mark-dirty)})
      {:state state})))

(defn- handle-add-track
  "Appends a new regular track to the cue chain's tracks.
   Optional :zone-group-id sets initial zone, falling back to chain default."
  [{:keys [col row zone-group-id state]}]
  (let [tracks-path [:chains :cue-chains [col row] :tracks]
        tracks (get-in state tracks-path [])
        dest-zone (or zone-group-id
                      (get-in state [:chains :cue-chains [col row] :destination-zone :zone-group-id] :all))
        new-track (tl/create-track {:zone-group-id dest-zone
                                    :name (str "Track " (inc (count tracks)))})
        new-track-id (:id new-track)
        updated-tracks (conj tracks new-track)]
    {:state (-> state
                (assoc-in tracks-path updated-tracks)
                (assoc-in [:list-ui :timeline-tracks :selected-ids] #{new-track-id})
                (assoc-in [:list-ui :timeline-tracks :last-selected-id] new-track-id)
                h/mark-dirty)}))

(defn- handle-add-folder
  "Appends a new track folder to the cue chain's tracks.
   Optional :zone-group-id sets initial zone, falling back to chain default."
  [{:keys [col row zone-group-id state]}]
  (let [tracks-path [:chains :cue-chains [col row] :tracks]
        tracks (get-in state tracks-path [])
        dest-zone (or zone-group-id
                      (get-in state [:chains :cue-chains [col row] :destination-zone :zone-group-id] :all))
        new-folder (assoc (tl/create-track {:zone-group-id dest-zone
                                            :name (str "Folder " (inc (count tracks)))})
                          :type :group
                          :collapsed? false
                          :items [])
        updated-tracks (conj tracks new-folder)]
    {:state (-> state
                (assoc-in tracks-path updated-tracks)
                h/mark-dirty)}))

(defn- handle-select-track
  "Set the currently selected track ID."
  [{:keys [track-id state]}]
  {:state (-> state
              (assoc-in [:list-ui :timeline-tracks :selected-ids] (if track-id #{track-id} #{}))
              (assoc-in [:list-ui :timeline-tracks :last-selected-id] track-id))})

(defn- handle-set-cursor-beat
  "Set the timeline cursor beat position, snapping if enabled."
  [{:keys [beat state]}]
  (let [snap-enabled? (get-in state [:ui :timeline :snap-enabled?] true)
        snap-size (get-in state [:ui :timeline :snap-value] 0.25)
        snapped (max 0.0 (snap-value (or beat 0.0) snap-size snap-enabled?))]
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
        dest-zone (get-in state [:chains :cue-chains [col row] :destination-zone :zone-group-id] :all)
        [tracks state-with-tracks] (if (empty? tracks)
                                     (let [default-tr (tl/create-track {:zone-group-id dest-zone :name "Track 1"})]
                                       [[default-tr] (assoc-in state tracks-path [default-tr])])
                                     [tracks state])
        resolved-track-id (tl/ensure-leaf-track tracks track-id)
        snap-enabled? (get-in state-with-tracks [:ui :timeline :snap-enabled?] true)
        snap-size (get-in state-with-tracks [:ui :timeline :snap-value] 0.25)
        cursor-beat (get-in state-with-tracks [:ui :timeline :cursor-beat] 0.0)
        raw-start (or start cursor-beat 0.0)
        start-beat (max 0.0 (snap-value raw-start snap-size snap-enabled?))
        duration-beats 4.0
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
                (assoc-in [:list-ui :timeline-tracks :last-selected-id] resolved-track-id)
                h/mark-dirty)
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
                (assoc-in items-path updated-items)
                h/mark-dirty)}))


;; Open Editor (initialize timeline data on first open)


(defn- timeline-ready?
  "Check if any item in the cue chain already has timeline-namespaced keys.
   A quick check to avoid re-initializing items that already have timing data."
  [items]
  (some #(contains? % :timeline/start) items))

(defn- ensure-item-timeline-fields
  "Add :timeline/start and :timeline/duration to an item if missing."
  [item]
  (cond-> item
    (not (contains? item :timeline/start))    (assoc :timeline/start 0.0)
    (not (contains? item :timeline/duration)) (assoc :timeline/duration 4.0)))

(defn- handle-open-editor
  "Open the timeline cue editor for a cell.
   Initializes timeline configuration:
   - Sets :editor-type to :timeline
   - Ensures :timeline/start and :timeline/duration on every item
   - Tracks from :destination-zone via auto-initialize-tracks if missing
   Then chains into :ui/open-dialog to actually show the dialog."
  [{:keys [col row state]}]
  (let [chain-path [:chains :cue-chains [col row]]
        cue-chain  (get-in state chain-path)
        items      (:items cue-chain [])
        zone-groups (get state :zone-groups {})
        ready?     (timeline-ready? items)
        has-tracks? (seq (:tracks cue-chain))
        timeline-type? (= (:editor-type cue-chain) :timeline)

        updated-state
        (if (and ready? has-tracks? timeline-type?)
          state
          (let [updated-items (if ready? items (mapv ensure-item-timeline-fields items))
                chain-with-items (assoc (or cue-chain {}) :items updated-items)
                final-chain (cond-> (if has-tracks?
                                      chain-with-items
                                      (tl/auto-initialize-tracks chain-with-items zone-groups))
                              true (assoc :editor-type :timeline))]
            (-> state
                (assoc-in chain-path final-chain)
                h/mark-dirty)))]
    {:state updated-state
     :dispatch {:event/type :ui/open-dialog
                :dialog-id :timeline-cue-editor
                :data {:col col :row row}}}))


;; Public API


(defn handle
  "Dispatch timeline events to their handlers.
   
   Accepts events with :event/type in the :timeline/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    ;; View controls
    :timeline/set-zoom (handle-set-zoom event)
    :timeline/scroll (handle-scroll event)
    :timeline/scroll-horizontal (handle-scroll-horizontal event)
    :timeline/zoom-at-cursor (handle-zoom-at-cursor event)
    :timeline/set-hvalue (handle-set-hvalue event)
    :timeline/set-snap (handle-set-snap event)
    :timeline/register-scroll-pane (handle-register-scroll-pane event)
    :timeline/sync-scroll (handle-sync-scroll event)

    ;; Selection
    :timeline/select-items (handle-select-items event)
    :timeline/clear-selection (handle-clear-selection event)
    :timeline/select-track (handle-select-track event)
    :timeline/set-cursor-beat (handle-set-cursor-beat event)

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
