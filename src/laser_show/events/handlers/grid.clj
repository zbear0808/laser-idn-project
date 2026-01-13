(ns laser-show.events.handlers.grid
  "Event handlers for grid cell operations.
   
   Handles:
   - Cell triggering and selection
   - Cell content management (clear, move)
   - Clipboard operations (copy, paste)"
  (:require [laser-show.events.helpers :as h]
            [laser-show.state.clipboard :as clipboard]))


(defn- handle-grid-cell-clicked
  "Handle grid cell click - dispatches to trigger or select.
   Note: Button detection is handled in grid_cell.clj before dispatching.
   This handler receives only single left-clicks."
  [{:keys [col row has-content? state]}]
  (if has-content?
    ;; Left click on cell with content - trigger
    (let [now (h/current-time-ms {:time (System/currentTimeMillis)})]
      {:state (-> state
                  (assoc-in [:playback :active-cell] [col row])
                  (assoc-in [:playback :playing?] true)
                  (assoc-in [:playback :trigger-time] now))})
    ;; Left click on empty - select
    {:state (assoc-in state [:grid :selected-cell] [col row])}))

(defn- handle-grid-trigger-cell
  "Trigger a cell to start playing its preset."
  [{:keys [col row state] :as event}]
  (let [now (h/current-time-ms event)]
    {:state (-> state
                (assoc-in [:playback :active-cell] [col row])
                (assoc-in [:playback :playing?] true)
                (assoc-in [:playback :trigger-time] now))}))

(defn- handle-grid-select-cell
  "Select a cell for editing."
  [{:keys [col row state]}]
  {:state (assoc-in state [:grid :selected-cell] [col row])})

(defn- handle-grid-deselect-cell
  "Clear cell selection."
  [{:keys [state]}]
  {:state (assoc-in state [:grid :selected-cell] nil)})

(defn- handle-grid-clear-cell
  "Clear a grid cell's cue chain."
  [{:keys [col row state]}]
  (let [active-cell (get-in state [:playback :active-cell])
        clearing-active? (= [col row] active-cell)]
    {:state (-> state
                (update-in [:chains :cue-chains] dissoc [col row])
                (cond-> clearing-active?
                  (-> (assoc-in [:playback :playing?] false)
                      (assoc-in [:playback :active-cell] nil)))
                h/mark-dirty)}))

(defn- handle-grid-move-cell
  "Move a cell's cue chain from one position to another."
  [{:keys [from-col from-row to-col to-row state]}]
  (let [cue-chain-data (get-in state [:chains :cue-chains [from-col from-row]])]
    (if cue-chain-data
      {:state (-> state
                  (update-in [:chains :cue-chains] dissoc [from-col from-row])
                  (assoc-in [:chains :cue-chains [to-col to-row]] cue-chain-data)
                  ;; Update playback if moving active cell
                  (cond-> (= (get-in state [:playback :active-cell]) [from-col from-row])
                    (assoc-in [:playback :active-cell] [to-col to-row]))
                  h/mark-dirty)}
      {:state state})))

(defn- handle-grid-copy-cell
  "Copy a cell's cue chain to clipboard.
   Also copies to system clipboard as serialized EDN."
  [{:keys [col row state]}]
  (h/handle-copy-to-clipboard state
                              [:chains :cue-chains [col row]]
                              :cue-chain
                              clipboard/copy-cue-chain!))

(defn- handle-grid-paste-cell
  "Paste clipboard cue chain to a cell."
  [{:keys [col row state]}]
  (h/handle-paste-from-clipboard state
                                 [:chains :cue-chains [col row]]
                                 :cue-chain))


;; Public API


(defn handle
  "Dispatch grid events to their handlers.
   
   Accepts events with :event/type in the :grid/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :grid/cell-clicked (handle-grid-cell-clicked event)
    :grid/trigger-cell (handle-grid-trigger-cell event)
    :grid/select-cell (handle-grid-select-cell event)
    :grid/deselect-cell (handle-grid-deselect-cell event)
    :grid/clear-cell (handle-grid-clear-cell event)
    :grid/move-cell (handle-grid-move-cell event)
    :grid/copy-cell (handle-grid-copy-cell event)
    :grid/paste-cell (handle-grid-paste-cell event)
    
    ;; Unknown event in this domain
    {}))
