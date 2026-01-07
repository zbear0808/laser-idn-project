(ns laser-show.events.handlers.cue-chain
  "Event handlers for cue chain operations (presets and groups).
   
   The cue chain editor allows building complex cues by combining multiple
   presets with per-preset effect chains. This module handles all operations
   within the cue chain editor dialog.
   
   Handles:
   - Editor lifecycle (open/close)
   - Preset management (add, remove, update parameters, color)
   - Group creation
   - Item manipulation (move, delete via chain-handlers)
   - Per-item effect chains (add, remove, update effects)
   - Effect parameters (sliders, spatial, curves)
   - Tab switching (preset banks, effect banks)
   - Selection and clipboard operations"
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.events.handlers.chain :as chain-handlers]
            [laser-show.animation.cue-chains :as cue-chains]))


;; Path Helpers


(defn- cue-chain-path
  "Get the path to a grid cell's cue chain in state.
   Uses new unified :chains domain structure."
  [col row]
  [:chains :cue-chains [col row] :items])

(defn- item-effects-path
  "Get the path to an item's effects array in state.
   Item is identified by col, row, and item-path within the cue chain."
  [col row item-path]
  (vec (concat (cue-chain-path col row) item-path [:effects])))

(defn- item-effects-ui-path
  "Get the path to item effects UI state.
   Stored separately per item to track selection, UI modes, etc."
  [col row item-path]
  [:cue-chain-editor :item-effects-ui (vec item-path)])


;; Editor Lifecycle


(defn- handle-cue-chain-open-editor
  "Open the cue chain editor for a specific cell.
   Event keys:
   - :col, :row - Grid cell coordinates"
  [{:keys [col row state]}]
  {:state (-> state
              (assoc-in [:cue-chain-editor :cell] [col row])
              (assoc-in [:cue-chain-editor :selected-paths] #{})
              (assoc-in [:cue-chain-editor :clipboard] nil)
              (assoc-in [:cue-chain-editor :active-preset-tab] :geometric)
              (assoc-in [:ui :dialogs :cue-chain-editor :open?] true)
              (assoc-in [:ui :dialogs :cue-chain-editor :data] {:col col :row row}))})

(defn- handle-cue-chain-close-editor
  "Close the cue chain editor."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:ui :dialogs :cue-chain-editor :open?] false)
              (assoc-in [:cue-chain-editor :cell] nil))})


;; Preset Management


(defn- handle-cue-chain-add-preset
  "Add a preset to the cue chain.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :preset-id - ID of the preset to add (e.g., :circle, :wave)"
  [{:keys [col row preset-id state]}]
 (let [ensure-cell (fn [s]
                     (if (get-in s [:chains :cue-chains [col row]])
                       s
                       (assoc-in s [:chains :cue-chains [col row]] {:items []})))
       new-preset (cue-chains/create-preset-instance preset-id {})
       state-with-cell (ensure-cell state)
       current-items (get-in state-with-cell (cue-chain-path col row) [])
       new-idx (count current-items)
       new-path [new-idx]]
   {:state (-> state-with-cell
               (update-in (cue-chain-path col row) conj new-preset)
               ;; Auto-select the newly added preset
               (assoc-in [:cue-chain-editor :selected-paths] #{new-path})
               (assoc-in [:cue-chain-editor :last-selected-path] new-path)
               h/mark-dirty)}))

(defn- handle-cue-chain-update-preset-param
  "Update a parameter in a preset.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :preset-path - Path to the preset
   - :param-key - Parameter key
   - :value or :fx/event - New value"
  [{:keys [col row preset-path param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))
        items-vec (vec (get-in state (cue-chain-path col row) []))
        updated-items (assoc-in items-vec (conj (vec preset-path) :params param-key) value)]
    {:state (assoc-in state (cue-chain-path col row) updated-items)}))

(defn- handle-cue-chain-update-preset-color
  "Update a color parameter in a preset.
   Extracts color from ActionEvent's source ColorPicker.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :preset-path - Path to the preset
   - :param-key - Parameter key
   - :fx/event - ActionEvent from color picker"
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
  "Set the active preset bank tab.
   Event keys:
   - :tab-id - Tab ID (e.g., :geometric, :wave, :beam, :abstract)"
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:cue-chain-editor :active-preset-tab] tab-id)})

(defn- handle-cue-chain-set-effect-tab
  "Set the active effect bank tab.
   Event keys:
   - :tab-id - Tab ID (e.g., :shape, :color, :intensity)"
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:cue-chain-editor :active-effect-tab] (or tab-id :shape))})


;; Legacy Preset Effect Handlers (for compatibility)


(defn- handle-cue-chain-add-preset-effect
  "Add an effect to a preset's effect chain.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :preset-path - Path to the preset
   - :effect - Effect to add (e.g., {:effect-id :scale :params {...}})"
  [{:keys [col row preset-path effect state]}]
  (let [effect-with-fields (cond-> effect
                             (not (contains? effect :enabled?))
                             (assoc :enabled? true)
                             (not (contains? effect :id))
                             (assoc :id (random-uuid)))
        items-vec (vec (get-in state (cue-chain-path col row) []))
        preset (get-in items-vec preset-path)]
    (if preset
      (let [updated-items (update-in items-vec (conj (vec preset-path) :effects)
                                     (fn [effs] (conj (or effs []) effect-with-fields)))]
        {:state (-> state
                    (assoc-in (cue-chain-path col row) updated-items)
                    h/mark-dirty)})
      {:state state})))

(defn- handle-cue-chain-remove-preset-effect
  "Remove an effect from a preset's effect chain.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :preset-path - Path to the preset
   - :effect-idx - Index of the effect to remove"
  [{:keys [col row preset-path effect-idx state]}]
  (let [items-vec (vec (get-in state (cue-chain-path col row) []))
        preset (get-in items-vec preset-path)]
    (if preset
      (let [effects-vec (get preset :effects [])
            new-effects (vec (concat (subvec effects-vec 0 effect-idx)
                                     (subvec effects-vec (inc effect-idx))))
            updated-items (assoc-in items-vec (conj (vec preset-path) :effects) new-effects)]
        {:state (-> state
                    (assoc-in (cue-chain-path col row) updated-items)
                    h/mark-dirty)})
      {:state state})))


;; Item Effect Chain Handlers


(defn- handle-cue-chain-add-effect-to-item
  "Add an effect to a preset or group's effect chain.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the preset/group
   - :effect - Effect to add"
  [{:keys [col row item-path effect state]}]
  (let [effect-with-fields (-> effect
                               (assoc :enabled? true)
                               (assoc :id (random-uuid)))
        items-path (cue-chain-path col row)
        effect-chain-path (vec (concat items-path item-path [:effects]))]
    {:state (-> state
                (update-in effect-chain-path conj effect-with-fields)
                ;; Auto-select the newly added effect
                (assoc-in [:cue-chain-editor :selected-effect-id] (:id effect-with-fields))
                h/mark-dirty)}))

(defn- handle-cue-chain-remove-effect-from-item
  "Remove an effect from a preset or group's effect chain.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the preset/group
   - :effect-id - ID of the effect to remove"
  [{:keys [col row item-path effect-id state]}]
  (let [items-path (cue-chain-path col row)
        effect-chain-path (vec (concat items-path item-path [:effects]))
        effects (vec (get-in state effect-chain-path []))
        new-effects (vec (remove #(= (:id %) effect-id) effects))
        selected-effect-id (get-in state [:cue-chain-editor :selected-effect-id])]
    {:state (-> state
                (assoc-in effect-chain-path new-effects)
                ;; Clear selection if we deleted the selected effect
                (cond-> (= effect-id selected-effect-id)
                  (assoc-in [:cue-chain-editor :selected-effect-id] nil))
                h/mark-dirty)}))

(defn- handle-cue-chain-update-effect-param
  "Update a parameter in a preset/group's effect.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the preset/group
   - :effect-path - Path to effect within the item's effect chain
   - :param-key - Parameter key
   - :value or :fx/event - New value"
  [{:keys [col row item-path effect-path param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))
        items-path (cue-chain-path col row)
        full-path (vec (concat items-path item-path [:effects] effect-path [:params param-key]))]
    {:state (assoc-in state full-path value)}))

(defn- handle-cue-chain-update-effect-param-from-text
  "Update a parameter from text field input.
   Parses the text, clamps to min/max bounds, and updates the param.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the preset/group
   - :effect-path - Path to effect within the item's effect chain
   - :param-key - Parameter key
   - :min, :max - Bounds for clamping"
  [{:keys [col row item-path effect-path param-key min max state] :as event}]
  (let [action-event (:fx/event event)
        text-field (.getSource action-event)
        text (.getText text-field)
        parsed (try (Double/parseDouble text) (catch Exception _ nil))]
    (if parsed
      (let [clamped (-> parsed (clojure.core/max min) (clojure.core/min max))
            items-path (cue-chain-path col row)
            full-path (vec (concat items-path item-path [:effects] effect-path [:params param-key]))]
        {:state (assoc-in state full-path clamped)})
      {:state state})))

(defn- handle-cue-chain-select-item-effect
  "Select an effect within an item for editing.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the preset/group
   - :effect-id - ID of the effect to select"
  [{:keys [col row item-path effect-id state]}]
  {:state (assoc-in state [:cue-chain-editor :selected-effect-id] effect-id)})

(defn- handle-cue-chain-set-item-effect-enabled
  "Set the enabled state of an effect within an item.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the preset/group
   - :effect-id - ID of the effect
   - :enabled? or :fx/event - New enabled state"
  [{:keys [col row item-path effect-id state] :as event}]
  (let [enabled? (if (contains? event :fx/event)
                   (:fx/event event)
                   (:enabled? event))
        items-path (cue-chain-path col row)
        effect-chain-path (vec (concat items-path item-path [:effects]))
        effects (vec (get-in state effect-chain-path []))
        effect-idx (.indexOf (mapv :id effects) effect-id)]
    (if (>= effect-idx 0)
      {:state (-> state
                  (assoc-in (vec (concat effect-chain-path [effect-idx :enabled?])) enabled?)
                  h/mark-dirty)}
      {:state state})))


;; Hierarchical List Integration (Item Effects)


(defn- handle-cue-chain-set-item-effects
  "Set the entire effects array for a cue chain item (simple persistence callback).
   Called by hierarchical-list component's :on-items-changed callback.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item (preset/group) within cue chain
   - :effects - New effects vector"
  [{:keys [col row item-path effects state]}]
 (let [ensure-cell (fn [s]
                     (if (get-in s [:chains :cue-chains [col row]])
                       s
                       (assoc-in s [:chains :cue-chains [col row]] {:items []})))
       effects-path (item-effects-path col row item-path)]
   {:state (-> state
               ensure-cell
               (assoc-in effects-path effects)
               h/mark-dirty)}))

(defn- handle-cue-chain-update-item-effect-selection
  "Update the selection state for item effects editor.
   Called by hierarchical-list component's :on-selection-changed callback.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :selected-ids - Set of selected effect IDs"
  [{:keys [col row item-path selected-ids state]}]
  (let [ui-path (item-effects-ui-path col row item-path)]
    {:state (assoc-in state (conj ui-path :selected-ids) selected-ids)}))

(defn- handle-cue-chain-set-item-effects-clipboard
  "Set the clipboard for item effects (separate from cue chain clipboard).
   Called by hierarchical-list component's :on-copy callback.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :effects - Effects to copy to clipboard"
  [{:keys [col row item-path effects state]}]
  (let [ui-path (item-effects-ui-path col row item-path)]
    {:state (assoc-in state (conj ui-path :clipboard) effects)}))

(defn- handle-cue-chain-set-clipboard
  "Set the clipboard for cue chain items (presets and groups).
   Called by hierarchical-list component's :on-copy callback.
   Event keys:
   - :items - Items to copy to clipboard"
  [{:keys [items state]}]
  {:state (assoc-in state [:cue-chain-editor :clipboard :items] items)})

(defn- handle-cue-chain-update-item-effect-param
  "Update a parameter in an item's effect using path-based addressing.
   Supports custom param renderers with visual/numeric modes.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :effect-path - Path to effect within item's effects array
   - :param-key - Parameter key
   - :value or :fx/event - New value"
  [{:keys [col row item-path effect-path param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))
        effects-path (item-effects-path col row item-path)
        param-path (vec (concat effects-path effect-path [:params param-key]))]
    {:state (assoc-in state param-path value)}))

(defn- handle-cue-chain-set-item-effect-param-ui-mode
  "Set UI mode (visual/numeric) for an item's effect parameters.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :effect-path - Path to effect
   - :mode - :visual or :numeric"
  [{:keys [col row item-path effect-path mode state]}]
  (let [ui-path (item-effects-ui-path col row item-path)]
    {:state (assoc-in state (conj ui-path :ui-modes effect-path) mode)}))

(defn- handle-cue-chain-update-item-effect-spatial-params
  "Update spatial parameters (x,y) from visual editor drag.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :effect-path - Path to effect
   - :point-id - ID of dragged point (e.g., :center, :tl, :tr)
   - :x, :y - New coordinates
   - :param-map - Mapping of point IDs to param keys"
  [{:keys [col row item-path effect-path point-id x y param-map state]}]
  (let [point-params (get param-map point-id)]
    (if point-params
      (let [x-key (:x point-params)
            y-key (:y point-params)
            effects-path (item-effects-path col row item-path)
            ;; Ensure effect-path is a vector to avoid ClassCastException
            base-path (vec (concat effects-path (vec effect-path) [:params]))]
        {:state (-> state
                    (assoc-in (conj base-path x-key) x)
                    (assoc-in (conj base-path y-key) y))})
      {:state state})))


;; Curve Editor for Item Effects


(defn- handle-cue-chain-add-item-effect-curve-point
  "Add a curve point to an RGB curves effect in an item.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :effect-path - Path to effect
   - :channel - :r, :g, or :b
   - :x, :y - Point coordinates"
  [{:keys [col row item-path effect-path channel x y state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-path (item-effects-path col row item-path)
        param-path (vec (concat effects-path effect-path [:params param-key]))
        current-points (get-in state param-path [[0 0] [255 255]])
        new-point [(int x) (int y)]
        new-points (->> (conj current-points new-point)
                        (sort-by first)
                        vec)]
    {:state (assoc-in state param-path new-points)}))

(defn- handle-cue-chain-update-item-effect-curve-point
  "Update a curve point in an RGB curves effect in an item.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :effect-path - Path to effect
   - :channel - :r, :g, or :b
   - :point-idx - Index of point to update
   - :x, :y - New coordinates"
  [{:keys [col row item-path effect-path channel point-idx x y state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-path (item-effects-path col row item-path)
        param-path (vec (concat effects-path effect-path [:params param-key]))
        current-points (get-in state param-path [[0 0] [255 255]])
        num-points (count current-points)
        ;; Corner points can only move in Y
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))
        current-point (nth current-points point-idx [0 0])
        updated-point (if is-corner?
                        [(first current-point) (int y)]
                        [(int x) (int y)])
        updated-points (assoc current-points point-idx updated-point)
        sorted-points (->> updated-points (sort-by first) vec)]
    {:state (assoc-in state param-path sorted-points)}))

(defn- handle-cue-chain-remove-item-effect-curve-point
  "Remove a curve point from an RGB curves effect in an item.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :effect-path - Path to effect
   - :channel - :r, :g, or :b
   - :point-idx - Index of point to remove"
  [{:keys [col row item-path effect-path channel point-idx state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-path (item-effects-path col row item-path)
        param-path (vec (concat effects-path effect-path [:params param-key]))
        current-points (get-in state param-path [[0 0] [255 255]])
        num-points (count current-points)
        ;; Cannot remove corner points
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))]
    (if is-corner?
      {:state state}
      (let [updated-points (vec (concat (subvec current-points 0 point-idx)
                                        (subvec current-points (inc point-idx))))]
        {:state (assoc-in state param-path updated-points)}))))

(defn- handle-cue-chain-set-item-effect-active-curve-channel
  "Set active curve channel (R/G/B) for curve editor in an item effect.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :item-path - Path to the item within cue chain
   - :effect-path - Path to effect
   - :tab-id - :r, :g, or :b"
  [{:keys [col row item-path effect-path tab-id state]}]
  (let [ui-path (item-effects-ui-path col row item-path)]
    {:state (assoc-in state (conj ui-path :ui-modes effect-path :active-curve-channel) tab-id)}))


;; Public API


(defn handle
  "Dispatch cue chain events to their handlers.
   
   Accepts events with :event/type in the :cue-chain/* namespace."
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
    
    ;; Legacy preset effect handlers
    :cue-chain/add-preset-effect (handle-cue-chain-add-preset-effect event)
    :cue-chain/remove-preset-effect (handle-cue-chain-remove-preset-effect event)
    
    ;; Item effect chain
    :cue-chain/add-effect-to-item (handle-cue-chain-add-effect-to-item event)
    :cue-chain/remove-effect-from-item (handle-cue-chain-remove-effect-from-item event)
    :cue-chain/update-effect-param (handle-cue-chain-update-effect-param event)
    :cue-chain/update-effect-param-from-text (handle-cue-chain-update-effect-param-from-text event)
    :cue-chain/select-item-effect (handle-cue-chain-select-item-effect event)
    :cue-chain/set-item-effect-enabled (handle-cue-chain-set-item-effect-enabled event)
    
    ;; Hierarchical list integration
    :cue-chain/set-item-effects (handle-cue-chain-set-item-effects event)
    :cue-chain/update-item-effect-selection (handle-cue-chain-update-item-effect-selection event)
    :cue-chain/set-clipboard (handle-cue-chain-set-clipboard event)
    :cue-chain/set-item-effects-clipboard (handle-cue-chain-set-item-effects-clipboard event)
    :cue-chain/update-item-effect-param (handle-cue-chain-update-item-effect-param event)
    :cue-chain/set-item-effect-param-ui-mode (handle-cue-chain-set-item-effect-param-ui-mode event)
    :cue-chain/update-item-effect-spatial-params (handle-cue-chain-update-item-effect-spatial-params event)
    
    ;; Curve editor
    :cue-chain/add-item-effect-curve-point (handle-cue-chain-add-item-effect-curve-point event)
    :cue-chain/update-item-effect-curve-point (handle-cue-chain-update-item-effect-curve-point event)
    :cue-chain/remove-item-effect-curve-point (handle-cue-chain-remove-item-effect-curve-point event)
    :cue-chain/set-item-effect-active-curve-channel (handle-cue-chain-set-item-effect-active-curve-channel event)
    
    ;; Unknown event in this domain
    {}))
