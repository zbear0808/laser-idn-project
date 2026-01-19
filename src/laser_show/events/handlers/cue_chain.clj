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
   Stored separately per item to track selection, UI modes, etc.
   FLATTENED: No :data nesting - fields directly in dialog map."
  [col row item-path]
  [:ui :dialogs :cue-chain-editor :item-effects-ui (vec item-path)])


;; Editor Lifecycle


(defn- handle-cue-chain-open-editor
  "Open the cue chain editor for a specific cell.
   FLATTENED: No :data nesting - fields directly in dialog map."
  [{:keys [col row state]}]
  (log/debug "Opening cue chain editor" {:col col :row row})
  {:state (-> state
              (update-in [:ui :dialogs :cue-chain-editor] merge
                         {:open? true
                          :col col
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
  "Add a preset to the cue chain.
   
   Supports both legacy format (preset-id) and new data-driven format (item-id from bank)."
  [{:keys [col row preset-id item-id state]}]
  (let [;; Support both old :preset-id and new :item-id (from data-driven banks)
        pid (or preset-id item-id)
        ;; Ensure cell exists with default destination-zone routing to 'All'
        ensure-cell (fn [s]
                      (if (get-in s [:chains :cue-chains [col row]])
                        s
                        (assoc-in s [:chains :cue-chains [col row]]
                                  {:items []
                                   :destination-zone {:zone-group-id :all}})))
        new-preset (h/ensure-item-fields (cue-chains/create-preset-instance pid {}))
        state-with-cell (ensure-cell state)
        current-items (get-in state-with-cell (cue-chain-path col row) [])
        new-idx (count current-items)
        new-path [new-idx]]
    {:state (-> state-with-cell
                (update-in (cue-chain-path col row) conj new-preset)
                (assoc-in [:ui :dialogs :cue-chain-editor :selected-paths] #{new-path})
                (assoc-in [:ui :dialogs :cue-chain-editor :last-selected-path] new-path)
                h/mark-dirty)}))

(defn- handle-cue-chain-add-effect-from-bank
  "Add an effect to a cue chain item from the effect bank.
   
   This handles the data-driven event format from effect-bank component,
   where :item contains the full effect definition and :item-id is the effect-id."
  [{:keys [col row parent-path item item-id state]}]
  (let [effect-id (or item-id (:id item))
        params-map (reduce (fn [acc {:keys [key default]}]
                             (assoc acc key default))
                           {}
                           (:parameters item []))
        new-effect (h/ensure-item-fields {:effect-id effect-id
                                          :params params-map})
        ;; Ensure cell exists with default destination-zone routing to 'All'
        ensure-cell (fn [s]
                      (if (get-in s [:chains :cue-chains [col row]])
                        s
                        (assoc-in s [:chains :cue-chains [col row]]
                                  {:items []
                                   :destination-zone {:zone-group-id :all}})))
        state-with-cell (ensure-cell state)
        ;; parent-path should be something like [0 :effects]
        target-path (vec (concat [:chains :cue-chains [col row] :items] parent-path))
        current-effects (get-in state-with-cell target-path [])]
    {:state (-> state-with-cell
                (assoc-in target-path (conj current-effects new-effect))
                h/mark-dirty)}))

;; NOTE: Preset parameter handlers have been removed - now using generic :chain/* events
;; from chain.clj with {:domain :cue-chains :entity-key [col row] :effect-path preset-path}
;; This includes :chain/update-param, :chain/update-param-from-text, :chain/update-color-param


;; Tab Switching


(defn- handle-cue-chain-set-preset-tab
  "Set the active preset bank tab.
   FLATTENED: No :data nesting."
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :active-preset-tab] tab-id)})

(defn- handle-cue-chain-set-effect-tab
  "Set the active effect bank tab.
   FLATTENED: No :data nesting."
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :active-effect-tab] (or tab-id :shape))})


;; Item Effect Selection


(defn- handle-cue-chain-select-item-effect
  "Select an effect within an item for editing.
   FLATTENED: No :data nesting."
  [{:keys [effect-id state]}]
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :selected-effect-id] effect-id)})


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
    (when (seq effects)
      (clipboard/copy-item-effects! effects))
    {:state (assoc-in state (conj ui-path :clipboard) effects)}))

(defn- handle-cue-chain-set-clipboard
  "Set the clipboard for cue chain items (presets and groups).
   Also copies to system clipboard as serialized EDN.
   FLATTENED: No :data nesting."
  [{:keys [items state]}]
  (when (seq items)
    (clipboard/copy-cue-chain-items! items))
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :clipboard :items] items)})


;; Destination Zone Operations


(defn- handle-cue-chain-set-destination-zone-group
  "Set the destination zone group for the entire cue chain.
   
   Updates the cue chain at [col row] with the specified zone group ID.
   Accepts either :group-id directly or extracts :id from the fx/event
   (when triggered from a combo-box selection).
   
   This applies to all presets in the cue chain."
  [{:keys [col row group-id fx/event state]}]
  (let [;; Support both direct group-id and combo-box event (which passes the group map)
        actual-group-id (or group-id (:id event))
        dest-zone-path [:chains :cue-chains [col row] :destination-zone :zone-group-id]]
    {:state (-> state
                (assoc-in dest-zone-path actual-group-id)
                h/mark-dirty)}))


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
    ;; NOTE: :cue-chain/update-preset-param, :cue-chain/update-preset-color, and
    ;; :cue-chain/update-preset-param-from-text have been removed.
    ;; Use :chain/update-param, :chain/update-color-param, :chain/update-param-from-text
    ;; with {:domain :cue-chains :entity-key [col row] :effect-path preset-path} instead.
    
    ;; Effect bank (data-driven) - add effect to cue chain item
    :cue-chain/add-effect-from-bank (handle-cue-chain-add-effect-from-bank event)
    
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
    
    ;; Destination zone routing
    :cue-chain/set-destination-zone-group (handle-cue-chain-set-destination-zone-group event)
    
    ;; Unknown event in this domain
    {}))
