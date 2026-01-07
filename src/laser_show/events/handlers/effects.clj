(ns laser-show.events.handlers.effects
  "Event handlers for effect chain operations.
   
   Handles:
   - Effect chain cell operations (toggle, add, remove, enable/disable)
   - Parameter updates (slider, text input, spatial, curves)
   - Cell management (copy, paste, clear, move, reorder)
   - Custom parameter UI modes (visual vs numeric)
   - Curve editor operations (add/update/remove control points)
   - Group creation"
  (:require [laser-show.events.helpers :as h]
            [laser-show.events.handlers.chain :as chain-handlers]
            [laser-show.animation.chains :as chains]))


;; Effects Grid Events


(defn- handle-effects-toggle-cell
  "Toggle an effects cell on/off."
  [{:keys [col row state]}]
  (let [current-active (get-in state [:chains :effect-chains [col row] :active] false)]
    {:state (assoc-in state [:chains :effect-chains [col row] :active] (not current-active))}))

(defn- handle-effects-add-effect
  "Add an effect to a cell's chain.
   Automatically selects the newly added effect so it can be edited immediately."
  [{:keys [col row effect state]}]
  (let [ensure-cell (fn [s]
                      (if (get-in s [:chains :effect-chains [col row]])
                        s
                        (assoc-in s [:chains :effect-chains [col row]] {:items [] :active true})))
        ;; Ensure the effect has both :enabled? and :id fields
        effect-with-fields (cond-> effect
                             (not (contains? effect :enabled?))
                             (assoc :enabled? true)
                             (not (contains? effect :id))
                             (assoc :id (random-uuid)))
        ;; Calculate the index where the new effect will be inserted (at the end)
        state-with-cell (ensure-cell state)
        current-effects (get-in state-with-cell [:chains :effect-chains [col row] :items] [])
        new-effect-path [(count current-effects)]]
    {:state (-> state-with-cell
                (update-in [:chains :effect-chains [col row] :items] conj effect-with-fields)
                ;; Auto-select the newly added effect using path-based selection
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{new-effect-path})
                (assoc-in [:ui :dialogs :effect-chain-editor :data :last-selected-path] new-effect-path)
                h/mark-dirty)}))

(defn- handle-effects-set-effect-enabled
  "Set an individual effect's enabled state within the chain.
   The enabled value comes from the checkbox's :fx/event."
  [{:keys [col row effect-idx state] :as event}]
  (let [enabled? (:fx/event event)]
    {:state (-> state
                (assoc-in [:chains :effect-chains [col row] :items effect-idx :enabled?] enabled?)
                h/mark-dirty)}))

(defn- handle-effects-remove-effect
  "Remove an effect from a cell's chain by index."
  [{:keys [col row effect-idx state]}]
  (let [effects-vec (get-in state [:chains :effect-chains [col row] :items] [])
        new-effects (vec (concat (subvec effects-vec 0 effect-idx)
                                 (subvec effects-vec (inc effect-idx))))]
    {:state (-> state
                (assoc-in [:chains :effect-chains [col row] :items] new-effects)
                h/mark-dirty)}))

(defn- handle-effects-update-param
  "Update a parameter in an effect.
   Value comes from :fx/event when using map event handlers."
  [{:keys [col row effect-path param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))
        effects-vec (vec (get-in state [:chains :effect-chains [col row] :items] []))
        updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) value)]
    {:state (assoc-in state [:chains :effect-chains [col row] :items] updated-effects)}))

(defn- handle-effects-update-param-from-text
  "Update a parameter from text field input.
   Parses the text, clamps to min/max bounds, and updates the param.
   Called when user presses Enter in the parameter text field."
  [{:keys [col row effect-path param-key min max state] :as event}]
  (let [action-event (:fx/event event)
        text-field (.getSource action-event)
        text (.getText text-field)
        parsed (try (Double/parseDouble text) (catch Exception _ nil))]
    (if parsed
      (let [clamped (-> parsed (clojure.core/max min) (clojure.core/min max))
            effects-vec (vec (get-in state [:chains :effect-chains [col row] :items] []))
            updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) clamped)]
        {:state (assoc-in state [:chains :effect-chains [col row] :items] updated-effects)})
      {:state state})))

(defn- handle-effects-clear-cell
  "Clear all effects from a cell."
  [{:keys [col row state]}]
  {:state (-> state
              (update-in [:chains :effect-chains] dissoc [col row])
              h/mark-dirty)})

(defn- handle-effects-reorder
  "Reorder effects in a cell's chain."
  [{:keys [col row from-idx to-idx state]}]
  (let [effects-vec (get-in state [:chains :effect-chains [col row] :items] [])
        effect (nth effects-vec from-idx)
        without (vec (concat (subvec effects-vec 0 from-idx)
                             (subvec effects-vec (inc from-idx))))
        reordered (vec (concat (subvec without 0 to-idx)
                               [effect]
                               (subvec without to-idx)))]
    {:state (-> state
                (assoc-in [:chains :effect-chains [col row] :items] reordered)
                h/mark-dirty)}))

(defn- handle-effects-copy-cell
  "Copy an effects cell to clipboard."
  [{:keys [col row state]}]
  (let [cell-data (get-in state [:chains :effect-chains [col row]])]
    {:state (assoc-in state [:ui :clipboard]
                      {:type :effects-cell
                       :data cell-data})}))

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

(defn- handle-effects-remove-from-chain-and-clear-selection
  "Remove an effect from chain and clear the dialog selection if needed.
   Used by the effect chain editor dialog."
  [{:keys [col row effect-idx state]}]
  (let [effects-vec (get-in state [:chains :effect-chains [col row] :items] [])
        new-effects (vec (concat (subvec effects-vec 0 effect-idx)
                                 (subvec effects-vec (inc effect-idx))))
        selected-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        new-indices (-> selected-indices
                        (disj effect-idx)
                        (->> (mapv (fn [idx]
                                     (if (> idx effect-idx)
                                       (dec idx)
                                       idx)))
                             (into #{})))]
    {:state (-> state
                (assoc-in [:chains :effect-chains [col row] :items] new-effects)
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] new-indices)
                h/mark-dirty)}))


;; Custom Parameter UI Events


(defn- handle-effects-set-param-ui-mode
  "Toggle between visual and numeric parameter editing mode for an effect."
  [{:keys [effect-path mode state]}]
  {:state (assoc-in state
                    [:ui :dialogs :effect-chain-editor :data :ui-modes effect-path]
                    mode)})


;; Curve Editor Events


(defn- handle-effects-add-curve-point
  "Add a new control point to a curve.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :effect-path - Path to effect (supports nested)
   - :channel - Channel keyword (:r, :g, or :b)
   - :x, :y - Point coordinates"
  [{:keys [col row effect-path channel x y state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-vec (vec (get-in state [:chains :effect-chains [col row] :items] []))
        current-points (get-in effects-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        new-point [(int x) (int y)]
        new-points (->> (conj current-points new-point)
                        (sort-by first)
                        vec)
        updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) new-points)]
    {:state (assoc-in state [:chains :effect-chains [col row] :items] updated-effects)}))

(defn- handle-effects-update-curve-point
  "Update a control point in a curve.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :effect-path - Path to effect (supports nested)
   - :channel - Channel keyword (:r, :g, or :b)
   - :point-idx - Index of the point to update
   - :x, :y - New coordinates"
  [{:keys [col row effect-path channel point-idx x y state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-vec (vec (get-in state [:chains :effect-chains [col row] :items] []))
        current-points (get-in effects-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        num-points (count current-points)
        ;; Corner points (first and last) can only move in Y
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))
        current-point (nth current-points point-idx [0 0])
        updated-point (if is-corner?
                        [(first current-point) (int y)]  ;; Keep original X for corners
                        [(int x) (int y)])
        updated-points (assoc current-points point-idx updated-point)
        sorted-points (->> updated-points
                          (sort-by first)
                          vec)
        updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) sorted-points)]
    {:state (assoc-in state [:chains :effect-chains [col row] :items] updated-effects)}))

(defn- handle-effects-remove-curve-point
  "Remove a control point from a curve.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :effect-path - Path to effect (supports nested)
   - :channel - Channel keyword (:r, :g, or :b)
   - :point-idx - Index of the point to remove"
  [{:keys [col row effect-path channel point-idx state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-vec (vec (get-in state [:chains :effect-chains [col row] :items] []))
        current-points (get-in effects-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        num-points (count current-points)
        ;; Cannot remove corner points (first and last)
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))]
    (if is-corner?
      {:state state}  ;; No change for corner points
      (let [updated-points (vec (concat (subvec current-points 0 point-idx)
                                        (subvec current-points (inc point-idx))))
            updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) updated-points)]
        {:state (assoc-in state [:chains :effect-chains [col row] :items] updated-effects)}))))

(defn- handle-effects-set-active-curve-channel
  "Set the active curve channel (R/G/B) for the curve editor.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :effect-path - Path to effect
   - :tab-id - Channel keyword (:r, :g, or :b)"
  [{:keys [col row effect-path tab-id state]}]
  (let [dialog-data-path [:ui :dialogs :effect-chain-editor :data :ui-modes effect-path]]
    {:state (assoc-in state (conj dialog-data-path :active-curve-channel) tab-id)}))

(defn- handle-effects-update-spatial-params
  "Update multiple related parameters from spatial drag (e.g., x and y together).
   
   Event keys:
   - :col, :row - Grid cell coordinates
   - :effect-path - Path to effect
   - :point-id - ID of the dragged point (e.g., :center, :tl, :tr)
   - :x, :y - New world coordinates
   - :param-map - Map of point IDs to parameter key mappings
                  e.g., {:center {:x :x :y :y}
                         :tl {:x :tl-x :y :tl-y}}"
  [{:keys [col row effect-path point-id x y param-map state]}]
 (let [point-params (get param-map point-id)
       effects-vec (vec (get-in state [:chains :effect-chains [col row] :items] []))]
   (if point-params
     (let [x-key (:x point-params)
           y-key (:y point-params)
           updated-effects (-> effects-vec
                              (assoc-in (conj (vec effect-path) :params x-key) x)
                              (assoc-in (conj (vec effect-path) :params y-key) y))]
       {:state (assoc-in state [:chains :effect-chains [col row] :items] updated-effects)})
     {:state state})))


;; Public API


(defn handle
  "Dispatch effects events to their handlers.
   
   Accepts events with :event/type in the :effects/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    :effects/toggle-cell (handle-effects-toggle-cell event)
    :effects/add-effect (handle-effects-add-effect event)
    :effects/remove-effect (handle-effects-remove-effect event)
    :effects/update-param (handle-effects-update-param event)
    :effects/update-param-from-text (handle-effects-update-param-from-text event)
    :effects/clear-cell (handle-effects-clear-cell event)
    :effects/reorder (handle-effects-reorder event)
    :effects/copy-cell (handle-effects-copy-cell event)
    :effects/paste-cell (handle-effects-paste-cell event)
    :effects/move-cell (handle-effects-move-cell event)
    :effects/select-cell (handle-effects-select-cell event)
    :effects/remove-from-chain-and-clear-selection (handle-effects-remove-from-chain-and-clear-selection event)
    :effects/set-effect-enabled (handle-effects-set-effect-enabled event)
    
    ;; Custom parameter UI
    :effects/set-param-ui-mode (handle-effects-set-param-ui-mode event)
    :effects/update-spatial-params (handle-effects-update-spatial-params event)
    
    ;; Curve editor
    :effects/add-curve-point (handle-effects-add-curve-point event)
    :effects/update-curve-point (handle-effects-update-curve-point event)
    :effects/remove-curve-point (handle-effects-remove-curve-point event)
    :effects/set-active-curve-channel (handle-effects-set-active-curve-channel event)
    
    ;; Unknown event in this domain
    {}))
