(ns laser-show.events.handlers.cue-chain
  "Event handlers for cue chain operations (presets and groups).
   
   The cue chain editor allows building complex cues by combining multiple
   presets with per-preset effect chains.
   
   This file handles cue-chain-specific operations:
   - Editor lifecycle (open/close)
   - Preset management (add, update parameters, color)
   - Tab switching (preset banks, effect banks)
   - Clipboard operations
   
   Effect-level operations have moved to chain.clj:
   - Effect CRUD (add, remove, set-enabled)
   - Parameter updates (update-param, update-param-from-text)
   - Spatial and curve editing
   - UI mode switching
   
   UI components should use :chain/* events with {:domain :cue-chains}
   for cue-chain item operations and effect parameter editing."
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.state.clipboard :as clipboard]))


;; Path Helpers


(defn- cue-chain-path
  "Get the path to a grid cell's cue chain in state.
   Uses new unified :chains domain structure."
  [col row]
  [:chains :cue-chains [col row] :items])

(defn- item-effects-ui-path
  "Get the path to item effects UI state.
   Stored separately per item to track selection, UI modes, etc."
  [col row item-path]
  [:ui :dialogs :cue-chain-editor :data :item-effects-ui (vec item-path)])


;; Editor Lifecycle


(defn- handle-cue-chain-open-editor
  "Open the cue chain editor for a specific cell."
  [{:keys [col row state]}]
  (log/debug "Opening cue chain editor" {:col col :row row})
  {:state (-> state
              (assoc-in [:ui :dialogs :cue-chain-editor :open?] true)
              (assoc-in [:ui :dialogs :cue-chain-editor :data]
                        {:col col
                         :row row
                         :selected-paths #{}
                         :last-selected-path nil
                         :clipboard nil
                         :active-preset-tab :geometric
                         :selected-effect-id nil
                         :item-effects-ui {}}))})

(defn- handle-cue-chain-close-editor
  "Close the cue chain editor."
  [{:keys [state]}]
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :open?] false)})


;; Preset Management


(defn- handle-cue-chain-add-preset
  "Add a preset to the cue chain."
  [{:keys [col row preset-id state]}]
  (let [ensure-cell (fn [s]
                      (if (get-in s [:chains :cue-chains [col row]])
                        s
                        (assoc-in s [:chains :cue-chains [col row]] {:items []})))
        new-preset (h/ensure-item-fields (cue-chains/create-preset-instance preset-id {}))
        state-with-cell (ensure-cell state)
        current-items (get-in state-with-cell (cue-chain-path col row) [])
        new-idx (count current-items)
        new-path [new-idx]]
    {:state (-> state-with-cell
                (update-in (cue-chain-path col row) conj new-preset)
                (assoc-in [:ui :dialogs :cue-chain-editor :data :selected-paths] #{new-path})
                (assoc-in [:ui :dialogs :cue-chain-editor :data :last-selected-path] new-path)
                h/mark-dirty)}))

(defn- handle-cue-chain-update-preset-param
  "Update a parameter in a preset."
  [{:keys [col row preset-path param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))
        items-vec (vec (get-in state (cue-chain-path col row) []))
        updated-items (assoc-in items-vec (conj (vec preset-path) :params param-key) value)]
    {:state (assoc-in state (cue-chain-path col row) updated-items)}))

(defn- handle-cue-chain-update-preset-color
  "Update a color parameter in a preset.
   Extracts color from ActionEvent's source ColorPicker."
  [{:keys [col row preset-path param-key state] :as event}]
  (let [action-event (:fx/event event)
        color-picker (.getSource action-event)
        color (.getValue color-picker)
        rgb-value [(int (* 255 (.getRed color)))
                   (int (* 255 (.getGreen color)))
                   (int (* 255 (.getBlue color)))]
        items-vec (vec (get-in state (cue-chain-path col row) []))
        updated-items (assoc-in items-vec (conj (vec preset-path) :params param-key) rgb-value)]
    {:state (assoc-in state (cue-chain-path col row) updated-items)}))


;; Tab Switching


(defn- handle-cue-chain-set-preset-tab
  "Set the active preset bank tab."
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :data :active-preset-tab] tab-id)})

(defn- handle-cue-chain-set-effect-tab
  "Set the active effect bank tab."
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :data :active-effect-tab] (or tab-id :shape))})


;; Item Effect Selection


(defn- handle-cue-chain-select-item-effect
  "Select an effect within an item for editing."
  [{:keys [effect-id state]}]
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :data :selected-effect-id] effect-id)})


;; Hierarchical List Integration (Item Effects)


(defn- handle-cue-chain-set-item-effects
  "Set the entire effects array for a cue chain item (simple persistence callback)."
  [{:keys [col row item-path effects state]}]
  (let [ensure-cell (fn [s]
                      (if (get-in s [:chains :cue-chains [col row]])
                        s
                        (assoc-in s [:chains :cue-chains [col row]] {:items []})))
        items-path (cue-chain-path col row)
        effects-path (vec (concat items-path item-path [:effects]))]
    {:state (-> state
                ensure-cell
                (assoc-in effects-path effects)
                h/mark-dirty)}))

(defn- handle-cue-chain-update-item-effect-selection
  "Update the selection state for item effects editor."
  [{:keys [col row item-path selected-ids state]}]
  (let [ui-path (item-effects-ui-path col row item-path)]
    {:state (assoc-in state (conj ui-path :selected-ids) selected-ids)}))


;; Clipboard Operations


(defn- handle-cue-chain-set-item-effects-clipboard
  "Set the clipboard for item effects (separate from cue chain clipboard).
   Also copies to system clipboard as serialized EDN."
  [{:keys [col row item-path effects state]}]
  (let [ui-path (item-effects-ui-path col row item-path)]
    ;; Copy to system clipboard (side effect)
    (clipboard/copy-item-effects! effects)
    ;; Return state update for internal clipboard
    {:state (assoc-in state (conj ui-path :clipboard) effects)}))

(defn- handle-cue-chain-set-clipboard
  "Set the clipboard for cue chain items (presets and groups).
   Also copies to system clipboard as serialized EDN."
  [{:keys [items state]}]
  ;; Copy to system clipboard (side effect)
  (clipboard/copy-cue-chain-items! items)
  ;; Return state update for internal clipboard
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :data :clipboard :items] items)})


;; Public API


(defn handle
  "Dispatch cue chain events to their handlers.
   
   Accepts events with :event/type in the :cue-chain/* namespace.
   
   Note: Effect CRUD, parameter, curve, and UI mode operations should use :chain/* events:
   - :chain/add-item, :chain/remove-item-at-path
   - :chain/update-param, :chain/update-param-from-text
   - :chain/add-curve-point, :chain/update-curve-point, :chain/remove-curve-point
   - :chain/set-active-curve-channel
   - :chain/update-spatial-params
   - :chain/set-ui-mode
   
   Pass {:domain :cue-chains :entity-key [col row]} to these events."
  [{:keys [event/type] :as event}]
  (case type
    ;; Editor lifecycle
    :cue-chain/open-editor (handle-cue-chain-open-editor event)
    :cue-chain/close-editor (handle-cue-chain-close-editor event)
    
    ;; Preset management
    :cue-chain/add-preset (handle-cue-chain-add-preset event)
    :cue-chain/update-preset-param (handle-cue-chain-update-preset-param event)
    :cue-chain/update-preset-color (handle-cue-chain-update-preset-color event)
    :cue-chain/update-preset-param-from-text (handle-cue-chain-update-preset-param event)
    
    ;; Tab switching
    :cue-chain/set-preset-tab (handle-cue-chain-set-preset-tab event)
    :cue-chain/set-effect-tab (handle-cue-chain-set-effect-tab event)
    
    ;; Item effect selection
    :cue-chain/select-item-effect (handle-cue-chain-select-item-effect event)
    
    ;; Hierarchical list integration
    :cue-chain/set-item-effects (handle-cue-chain-set-item-effects event)
    :cue-chain/update-item-effect-selection (handle-cue-chain-update-item-effect-selection event)
    :cue-chain/set-clipboard (handle-cue-chain-set-clipboard event)
    :cue-chain/set-item-effects-clipboard (handle-cue-chain-set-item-effects-clipboard event)
    
    ;; Unknown event in this domain
    {}))
