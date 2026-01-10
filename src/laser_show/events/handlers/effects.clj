(ns laser-show.events.handlers.effects
  "Event handlers for effect chain cell-level operations.
   
   This file handles cell-level operations only:
   - toggle-cell: Turn a cell on/off
   - clear-cell: Clear all effects from a cell
   - copy-cell/paste-cell: Cell clipboard operations
   - move-cell: Move a cell between positions
   - select-cell: Select a cell for editing
   
   Effect-level operations have moved to chain.clj:
   - Effect CRUD (add, remove, reorder, set-enabled)
   - Parameter updates (update-param, update-param-from-text)
   - Spatial and curve editing
   - UI mode switching
   
   UI components should use :chain/* events with {:domain :effect-chains}
   for all effect-level operations."
  (:require [laser-show.events.helpers :as h]
            [laser-show.state.clipboard :as clipboard]))


;; Cell-Level Operations


(defn- handle-effects-toggle-cell
  "Toggle an effects cell on/off."
  [{:keys [col row state]}]
  (let [current-active (get-in state [:chains :effect-chains [col row] :active] false)]
    {:state (assoc-in state [:chains :effect-chains [col row] :active] (not current-active))}))

(defn- handle-effects-clear-cell
  "Clear all effects from a cell."
  [{:keys [col row state]}]
  {:state (-> state
              (update-in [:chains :effect-chains] dissoc [col row])
              h/mark-dirty)})

(defn- handle-effects-copy-cell
  "Copy an effects cell to clipboard.
   Also copies to system clipboard as serialized EDN."
  [{:keys [col row state]}]
  (let [cell-data (get-in state [:chains :effect-chains [col row]])
        clip-data {:type :effects-cell
                   :data cell-data}]
    ;; Copy to system clipboard (side effect)
    (clipboard/copy-effects-cell! cell-data)
    ;; Return state update for internal clipboard
    {:state (assoc-in state [:ui :clipboard] clip-data)}))

(defn- handle-effects-paste-cell
  "Paste clipboard to an effects cell."
  [{:keys [col row state]}]
  (let [clipboard (get-in state [:ui :clipboard])]
    (if (and clipboard (= :effects-cell (:type clipboard)))
      {:state (-> state
                  (assoc-in [:chains :effect-chains [col row]] (:data clipboard))
                  h/mark-dirty)}
      {:state state})))

(defn- handle-effects-move-cell
  "Move an effects cell from one position to another."
  [{:keys [from-col from-row to-col to-row state]}]
  (let [cell-data (get-in state [:chains :effect-chains [from-col from-row]])]
    (if cell-data
      {:state (-> state
                  (update-in [:chains :effect-chains] dissoc [from-col from-row])
                  (assoc-in [:chains :effect-chains [to-col to-row]] cell-data)
                  h/mark-dirty)}
      {:state state})))

(defn- handle-effects-select-cell
  "Select an effects cell for editing."
  [{:keys [col row state]}]
  {:state (assoc-in state [:effects :selected-cell] [col row])})


;; Public API


(defn handle
  "Dispatch effects events to their handlers.
   
   Accepts events with :event/type in the :effects/* namespace.
   
   Note: Most effect-level operations have moved to chain.clj.
   UI components should use :chain/* events for:
   - Adding/removing/reordering effects
   - Parameter updates
   - Curve and spatial editing
   - UI mode switching"
  [{:keys [event/type] :as event}]
  (case type
    ;; Cell-level operations (retained in this file)
    :effects/toggle-cell (handle-effects-toggle-cell event)
    :effects/clear-cell (handle-effects-clear-cell event)
    :effects/copy-cell (handle-effects-copy-cell event)
    :effects/paste-cell (handle-effects-paste-cell event)
    :effects/move-cell (handle-effects-move-cell event)
    :effects/select-cell (handle-effects-select-cell event)
    
    ;; Unknown event in this domain
    {}))
