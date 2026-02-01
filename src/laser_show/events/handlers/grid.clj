(ns laser-show.events.handlers.grid
  "Event handlers for grid cell operations.
   
   Handles:
   - Cell triggering and selection (with toggle/retrigger modes)
   - Cell content management (clear, move)
   - Clipboard operations (copy, paste)"
  (:require [laser-show.events.helpers :as h]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.state.extractors :as ex]
            [laser-show.animation.cue-timing :as cue-timing]
            [clojure.tools.logging :as log]))


(defn- resolve-trigger-mode
  "Resolve the effective trigger mode for a cue.
   Global setting overrides per-cue if not :default.
   
   Returns :toggle or :retrigger"
  [state col row]
  (let [global-mode (get-in state [:config :cue :trigger-mode] :default)
        cue-mode (get-in state [:chains :cue-chains [col row] :trigger-mode] :toggle)
        resolved-mode (if (= global-mode :default) cue-mode global-mode)]
    (log/info "Trigger mode resolution:"
              {:col col :row row
               :global-mode global-mode
               :cue-mode cue-mode
               :resolved-mode resolved-mode})
    resolved-mode))

(defn- trigger-cue
  "Trigger a cue by adding it to active-cues with fresh timing state."
  [state col row]
  (let [now (h/current-time-ms {:time (System/currentTimeMillis)})
        global-clock-beats (ex/global-accumulated-beats state)
        cue-timing-state (cue-timing/create-cue-timing-state now global-clock-beats)]
    (-> state
        (assoc-in [:playback :active-cues [col row]] cue-timing-state)
        (assoc-in [:playback :playing?] true)
        ;; Keep deprecated state for backward compatibility during transition
        (assoc-in [:playback :active-cell] [col row])
        (assoc-in [:playback :trigger-time] now))))

(defn- untrigger-cue
  "Remove a cue from active-cues. Sets playing? to false if no cues remain."
  [state col row]
  (let [remaining-cues (dissoc (get-in state [:playback :active-cues]) [col row])
        any-remaining? (seq remaining-cues)]
    (-> state
        (assoc-in [:playback :active-cues] remaining-cues)
        (assoc-in [:playback :playing?] any-remaining?)
        ;; Clear deprecated active-cell if this was it
        (cond-> (= (get-in state [:playback :active-cell]) [col row])
          (assoc-in [:playback :active-cell] (first (keys remaining-cues)))))))

(defn- handle-grid-cell-clicked
  "Handle grid cell click - dispatches to trigger or select.
   
   Trigger modes:
   - :toggle - Click ON to start, click OFF to stop
   - :retrigger - Always start/restart the cue
   
   Note: Button detection is handled in grid_cell.clj before dispatching.
   This handler receives only single left-clicks."
  [{:keys [col row has-content? state]}]
  (if has-content?
    (let [trigger-mode (resolve-trigger-mode state col row)
          cue-active? (contains? (get-in state [:playback :active-cues]) [col row])]
      (log/info "Grid cell clicked:"
                {:col col :row row
                 :trigger-mode trigger-mode
                 :cue-active? cue-active?})
      (case trigger-mode
        :toggle (if cue-active?
                  (do
                    (log/info "Toggle mode: Turning OFF cue" {:col col :row row})
                    {:state (untrigger-cue state col row)})
                  (do
                    (log/info "Toggle mode: Turning ON cue" {:col col :row row})
                    {:state (trigger-cue state col row)}))
        ;; :retrigger or any other value - always trigger/restart
        (do
          (log/info "Retrigger mode: Always triggering cue" {:col col :row row})
          {:state (trigger-cue state col row)})))
    ;; Left click on empty - select
    {:state (assoc-in state [:grid :selected-cell] [col row])}))

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
    :grid/move-cell (handle-grid-move-cell event)
    :grid/copy-cell (handle-grid-copy-cell event)
    :grid/paste-cell (handle-grid-paste-cell event)
    
    ;; Unknown event in this domain
    {}))
