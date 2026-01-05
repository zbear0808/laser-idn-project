(ns laser-show.events.handlers
  "Pure event handlers for the laser show application.
   
   This module contains PURE FUNCTIONS that transform state.
   
   Event handlers:
   - Receive event map with :event/type and co-effects (:state, :time)
   - Return effects map (:state, :dispatch, custom effects)
   
   Benefits of pure handlers:
   - Easy to test: (handle-event event) => effects-map
   - No mocks needed
   - Clear data flow
   - Composable
   
   Usage:
   (handle-event {:event/type :grid/trigger-cell :col 0 :row 0 :state current-state})
   => {:state new-state}"
  (:require
   [clojure.tools.logging :as log]
   [laser-show.common.util :as u]
   [laser-show.animation.effects :as effects]
   [laser-show.animation.cue-chains :as cue-chains]
   [laser-show.animation.chains :as chains]))


;; Helper Functions


(defn- mark-dirty
  "Mark project as having unsaved changes."
  [state]
  (assoc-in state [:project :dirty?] true))

(defn- current-time-ms
  "Get current time from event or system."
  [event]
  (or (:time event) (System/currentTimeMillis)))

(defn- remove-at-path
  "Remove an item at the given path from a nested vector structure.
   Used for group operations."
  [items path]
  (let [items-vec (vec items)   ;; Ensure items is a vector for subvec
        path-vec (vec path)]    ;; Ensure path is a vector
    (if (= 1 (count path-vec))
      (let [idx (first path-vec)]
        (if (and (>= idx 0) (< idx (count items-vec)))
          (vec (concat (subvec items-vec 0 idx)
                       (subvec items-vec (inc idx))))
          items-vec))
      (let [[parent-idx & rest-path] path-vec]
        (if (and (>= parent-idx 0) (< parent-idx (count items-vec))
                 (= :items (first rest-path)))
          (update items-vec parent-idx
                  #(update % :items
                           (fn [sub-items]
                             (remove-at-path (vec (or sub-items [])) (vec (rest rest-path))))))
          items-vec)))))

(defn- insert-at-path
  "Insert an item at the given path in a nested vector structure.
   If into-group? is true, insert into the group's items at the path."
  [items path item into-group?]
  (let [items-vec (vec items)   ;; Ensure items is a vector for subvec
        path-vec (vec path)]    ;; Ensure path is a vector
    (if into-group?
      ;; Insert into group's items
      (update-in items-vec (conj path-vec :items) #(conj (vec (or % [])) item))
      ;; Insert before position
      (if (= 1 (count path-vec))
        (let [idx (first path-vec)
              safe-idx (min idx (count items-vec))]
          (vec (concat (subvec items-vec 0 safe-idx)
                       [item]
                       (subvec items-vec safe-idx))))
        (let [[parent-idx & rest-path] path-vec]
          (if (and (>= parent-idx 0) (< parent-idx (count items-vec))
                   (= :items (first rest-path)))
            (update items-vec parent-idx
                    #(update % :items
                             (fn [sub-items]
                               (insert-at-path (vec (or sub-items [])) (vec (rest rest-path)) item false))))
            items-vec))))))

(defn- regenerate-ids
  "Recursively regenerate :id fields for effects and groups.
   Ensures pasted items have unique IDs to prevent drag/drop issues
   when the same item is copied multiple times."
  [item]
  (cond
    ;; Group - regenerate ID and recurse into items
    (effects/group? item)
    (-> item
        (assoc :id (random-uuid))
        (update :items #(mapv regenerate-ids %)))
    
    ;; Effect or other item - just regenerate ID
    :else
    (assoc item :id (random-uuid))))


;; Forward declarations for helper functions used in delete-selected
(declare path-is-ancestor? filter-to-root-paths)

;; Forward declaration for projector path-based selection
(declare handle-projectors-select-effect-at-path)


;; Two-Phase Operation Helpers
;; These avoid index-shifting bugs by collecting items first, then operating


(defn- collect-items-by-ids
  "Recursively collect all items matching the given ID set.
   Returns a vector of items (preserving order as encountered)."
  [items id-set]
  (reduce
    (fn [acc item]
      (let [acc (if (contains? id-set (:id item))
                  (conj acc item)
                  acc)]
        ;; Recurse into groups
        (if (effects/group? item)
          (into acc (collect-items-by-ids (:items item []) id-set))
          acc)))
    []
    items))

(defn- remove-items-by-ids
  "Recursively remove all items matching the given ID set.
   Returns the chain with matching items filtered out."
  [items id-set]
  (vec
    (keep
      (fn [item]
        (when-not (contains? id-set (:id item))
          (if (effects/group? item)
            (update item :items #(remove-items-by-ids % id-set))
            item)))
      items)))

(defn- insert-items-at-index
  "Insert items at a specific index in the vector."
  [vec idx items]
  (let [safe-idx (max 0 (min idx (count vec)))]
    (into (subvec vec 0 safe-idx)
          (concat items (subvec vec safe-idx)))))


;; Grid Events


(defn- handle-grid-cell-clicked
  "Handle grid cell click - dispatches to trigger or select.
   Note: Button detection is handled in grid_cell.clj before dispatching.
   This handler receives only single left-clicks."
  [{:keys [col row has-content? state]}]
  (if has-content?
    ;; Left click on cell with content - trigger
    (let [now (current-time-ms {:time (System/currentTimeMillis)})]
      {:state (-> state
                  (assoc-in [:playback :active-cell] [col row])
                  (assoc-in [:playback :playing?] true)
                  (assoc-in [:playback :trigger-time] now))})
    ;; Left click on empty - select
    {:state (assoc-in state [:grid :selected-cell] [col row])}))

(defn- handle-grid-trigger-cell
  "Trigger a cell to start playing its preset."
  [{:keys [col row state] :as event}]
  (let [now (current-time-ms event)]
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

(defn- handle-grid-set-cell-preset
  "Set a preset for a grid cell."
  [{:keys [col row preset-id state]}]
  {:state (-> state
              (assoc-in [:grid :cells [col row]] {:preset-id preset-id})
              mark-dirty)})

(defn- handle-grid-clear-cell
  "Clear a grid cell."
  [{:keys [col row state]}]
  (let [active-cell (get-in state [:playback :active-cell])
        clearing-active? (= [col row] active-cell)]
    {:state (-> state
                (update-in [:grid :cells] dissoc [col row])
                (cond-> clearing-active?
                  (-> (assoc-in [:playback :playing?] false)
                      (assoc-in [:playback :active-cell] nil)))
                mark-dirty)}))

(defn- handle-grid-move-cell
  "Move a cell from one position to another."
  [{:keys [from-col from-row to-col to-row state]}]
  (let [cell-data (get-in state [:grid :cells [from-col from-row]])]
    (if cell-data
      {:state (-> state
                  (update-in [:grid :cells] dissoc [from-col from-row])
                  (assoc-in [:grid :cells [to-col to-row]] cell-data)
                  mark-dirty)}
      {:state state})))

(defn- handle-grid-copy-cell
  "Copy a cell to clipboard."
  [{:keys [col row state]}]
  (let [cell-data (get-in state [:grid :cells [col row]])]
    {:state (assoc-in state [:ui :clipboard]
                      {:type :grid-cell
                       :data cell-data})}))

(defn- handle-grid-paste-cell
  "Paste clipboard to a cell."
  [{:keys [col row state]}]
  (let [clipboard (get-in state [:ui :clipboard])]
    (if (and clipboard (= :grid-cell (:type clipboard)))
      {:state (-> state
                  (assoc-in [:grid :cells [col row]] (:data clipboard))
                  mark-dirty)}
      {:state state})))


;; Effects Grid Events


(defn- handle-effects-toggle-cell
  "Toggle an effects cell on/off."
  [{:keys [col row state]}]
  (let [current-active (get-in state [:effects :cells [col row] :active] false)]
    {:state (assoc-in state [:effects :cells [col row] :active] (not current-active))}))

(defn- handle-effects-add-effect
  "Add an effect to a cell's chain.
   Automatically selects the newly added effect so it can be edited immediately."
  [{:keys [col row effect state]}]
  (let [ensure-cell (fn [s]
                      (if (get-in s [:effects :cells [col row]])
                        s
                        (assoc-in s [:effects :cells [col row]] {:effects [] :active true})))
        ;; Ensure the effect has both :enabled? and :id fields
        effect-with-fields (cond-> effect
                             (not (contains? effect :enabled?))
                             (assoc :enabled? true)
                             (not (contains? effect :id))
                             (assoc :id (random-uuid)))
        ;; Calculate the index where the new effect will be inserted (at the end)
        state-with-cell (ensure-cell state)
        current-effects (get-in state-with-cell [:effects :cells [col row] :effects] [])
        new-effect-idx (count current-effects)
        new-effect-path [new-effect-idx]]
    {:state (-> state-with-cell
                (update-in [:effects :cells [col row] :effects] conj effect-with-fields)
                ;; Auto-select the newly added effect using path-based selection
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{new-effect-path})
                (assoc-in [:ui :dialogs :effect-chain-editor :data :last-selected-path] new-effect-path)
                ;; Also update legacy index-based selection for compatibility
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{new-effect-idx})
                (assoc-in [:ui :dialogs :effect-chain-editor :data :last-selected-idx] new-effect-idx)
                mark-dirty)}))

(defn- handle-effects-set-effect-enabled
  "Set an individual effect's enabled state within the chain.
   The enabled value comes from the checkbox's :fx/event."
  [{:keys [col row effect-idx state] :as event}]
  (let [enabled? (:fx/event event)]
    {:state (-> state
                (assoc-in [:effects :cells [col row] :effects effect-idx :enabled?] enabled?)
                mark-dirty)}))

(defn- handle-effects-remove-effect
  "Remove an effect from a cell's chain by index."
  [{:keys [col row effect-idx state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        new-effects (vec (concat (subvec effects-vec 0 effect-idx)
                                 (subvec effects-vec (inc effect-idx))))]
    {:state (-> state
                (assoc-in [:effects :cells [col row] :effects] new-effects)
                mark-dirty)}))

(defn- handle-effects-update-param
  "Update a parameter in an effect.
   Value comes from :fx/event when using map event handlers.
   Supports both :effect-idx (legacy, top-level) and :effect-path (nested)."
  [{:keys [col row effect-idx effect-path param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))
        effects-vec (vec (get-in state [:effects :cells [col row] :effects] []))
        ;; Use path-based update if effect-path is provided, otherwise fall back to index
        updated-effects (if effect-path
                          (assoc-in effects-vec (conj (vec effect-path) :params param-key) value)
                          (assoc-in effects-vec [effect-idx :params param-key] value))]
    {:state (assoc-in state [:effects :cells [col row] :effects] updated-effects)}))

(defn- handle-effects-update-param-from-text
  "Update a parameter from text field input.
   Parses the text, clamps to min/max bounds, and updates the param.
   Called when user presses Enter in the parameter text field.
   Supports both :effect-idx (legacy, top-level) and :effect-path (nested)."
  [{:keys [col row effect-idx effect-path param-key min max state] :as event}]
  (let [action-event (:fx/event event)
        text-field (.getSource action-event)
        text (.getText text-field)
        parsed (try (Double/parseDouble text) (catch Exception _ nil))]
    (if parsed
      (let [clamped (-> parsed (clojure.core/max min) (clojure.core/min max))
            effects-vec (vec (get-in state [:effects :cells [col row] :effects] []))
            ;; Use path-based update if effect-path is provided, otherwise fall back to index
            updated-effects (if effect-path
                              (assoc-in effects-vec (conj (vec effect-path) :params param-key) clamped)
                              (assoc-in effects-vec [effect-idx :params param-key] clamped))]
        {:state (assoc-in state [:effects :cells [col row] :effects] updated-effects)})
      {:state state})))

(defn- handle-effects-clear-cell
  "Clear all effects from a cell."
  [{:keys [col row state]}]
  {:state (-> state
              (update-in [:effects :cells] dissoc [col row])
              mark-dirty)})

(defn- handle-effects-reorder
  "Reorder effects in a cell's chain."
  [{:keys [col row from-idx to-idx state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        effect (nth effects-vec from-idx)
        without (vec (concat (subvec effects-vec 0 from-idx)
                             (subvec effects-vec (inc from-idx))))
        reordered (vec (concat (subvec without 0 to-idx)
                               [effect]
                               (subvec without to-idx)))]
    {:state (-> state
                (assoc-in [:effects :cells [col row] :effects] reordered)
                mark-dirty)}))

(defn- handle-effects-copy-cell
  "Copy an effects cell to clipboard."
  [{:keys [col row state]}]
  (let [cell-data (get-in state [:effects :cells [col row]])]
    {:state (assoc-in state [:ui :clipboard]
                      {:type :effects-cell
                       :data cell-data})}))

(defn- handle-effects-paste-cell
  "Paste clipboard to an effects cell."
  [{:keys [col row state]}]
  (let [clipboard (get-in state [:ui :clipboard])]
    (if (and clipboard (= :effects-cell (:type clipboard)))
      {:state (-> state
                  (assoc-in [:effects :cells [col row]] (:data clipboard))
                  mark-dirty)}
      {:state state})))

(defn- handle-effects-move-cell
  "Move an effects cell from one position to another."
  [{:keys [from-col from-row to-col to-row state]}]
  (let [cell-data (get-in state [:effects :cells [from-col from-row]])]
    (if cell-data
      {:state (-> state
                  (update-in [:effects :cells] dissoc [from-col from-row])
                  (assoc-in [:effects :cells [to-col to-row]] cell-data)
                  mark-dirty)}
      {:state state})))

(defn- handle-effects-select-cell
  "Select an effects cell for editing."
  [{:keys [col row state]}]
  {:state (assoc-in state [:effects :selected-cell] [col row])})

(defn- handle-effects-remove-from-chain-and-clear-selection
  "Remove an effect from chain and clear the dialog selection if needed.
   Used by the effect chain editor dialog."
  [{:keys [col row effect-idx state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
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
                (assoc-in [:effects :cells [col row] :effects] new-effects)
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] new-indices)
                mark-dirty)}))


;; Effect Chain Editor Multi-Select Events


(defn- handle-effects-select-effect
  "Select a single effect in the chain editor (replaces existing selection).
   Also sets last-selected-idx as anchor for shift+click range selection."
  [{:keys [effect-idx state]}]
  {:state (-> state
              (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{effect-idx})
              (assoc-in [:ui :dialogs :effect-chain-editor :data :last-selected-idx] effect-idx))})

(defn- handle-effects-toggle-effect-selection
  "Toggle an effect's selection state (Ctrl+click behavior)."
  [{:keys [effect-idx state]}]
  (let [current-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        new-indices (if (contains? current-indices effect-idx)
                      (disj current-indices effect-idx)
                      (conj current-indices effect-idx))]
    {:state (-> state
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] new-indices)
                (assoc-in [:ui :dialogs :effect-chain-editor :data :last-selected-idx] effect-idx))}))

(defn- handle-effects-range-select
  "Range select effects from last selected to clicked (Shift+click behavior)."
  [{:keys [effect-idx state]}]
  (let [last-idx (get-in state [:ui :dialogs :effect-chain-editor :data :last-selected-idx])
        start-idx (or last-idx 0)
        [from to] (if (<= start-idx effect-idx)
                    [start-idx effect-idx]
                    [effect-idx start-idx])
        range-indices (into #{} (range from (inc to)))]
    {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices]
                      range-indices)}))

(defn- handle-effects-select-all
  "Select all effects and groups in the current chain.
   Uses path-based selection to include nested items within groups."
  [{:keys [col row state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        ;; Get all paths including nested items using the effects module helper
        all-paths (into #{} (effects/paths-in-chain effects-vec))
        ;; Also support legacy index-based selection for compatibility
        effects-count (count effects-vec)
        all-indices (into #{} (range effects-count))]
    {:state (-> state
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] all-paths)
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] all-indices))}))

(defn- handle-effects-clear-selection
  "Clear all effect selection in chain editor."
  [{:keys [state]}]
  {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})})

(defn- handle-effects-copy-selected
  "Copy selected effects to clipboard.
   Supports both path-based selection (for groups) and legacy index-based selection."
  [{:keys [col row state]}]
  (log/debug "handle-effects-copy-selected called: col=" col "row=" row)
  (let [selected-paths (get-in state [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
        selected-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        effects-vec (get-in state [:effects :cells [col row] :effects] [])
        _ (log/debug "  selected-paths=" selected-paths)
        _ (log/debug "  selected-indices=" selected-indices)
        _ (log/debug "  effects-vec count=" (count effects-vec))
        ;; Path-based selection takes precedence
        valid-effects (cond
                        ;; Path-based selection - get items at each path
                        (seq selected-paths)
                        (vec (keep #(get-in effects-vec %) selected-paths))
                        
                        ;; Legacy index-based selection
                        (seq selected-indices)
                        (u/filterv-indexed
                          (fn [idx _effect]
                            (selected-indices idx))
                          effects-vec)
                        
                        :else [])
        _ (log/debug "  valid-effects count=" (count valid-effects))
        result (cond-> {:state state}
                 (seq valid-effects)
                 (assoc :clipboard/copy-effects valid-effects))
        _ (log/debug "  returning clipboard/copy-effects?" (contains? result :clipboard/copy-effects))]
    result))

(defn- handle-effects-paste-into-chain
  "Paste effects from clipboard into current chain.
   Supports both path-based selection (for groups) and legacy index-based selection."
  [{:keys [col row state]}]
  (let [;; Get paste position (after last selected, or at end)
        selected-paths (get-in state [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
        selected-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        effects-vec (get-in state [:effects :cells [col row] :effects] [])
        ;; Calculate insert position - only support top-level insert for now
        ;; For path-based selection, use the first element of the last path + 1
        insert-pos (cond
                     ;; Path-based selection - use first element of max path + 1
                     (seq selected-paths)
                     (let [top-level-indices (map first selected-paths)]
                       (inc (apply max top-level-indices)))
                     
                     ;; Legacy index-based selection
                     (seq selected-indices)
                     (inc (apply max selected-indices))
                     
                     ;; No selection - append at end
                     :else
                     (count effects-vec))]
    {:state state
     :clipboard/paste-effects {:col col
                               :row row
                               :insert-pos insert-pos}}))

(defn- handle-effects-insert-pasted
  "Actually insert pasted effects into the chain (called by effect handler).
   Regenerates UUIDs on all pasted items to prevent duplicate IDs."
  [{:keys [col row insert-pos effects state]}]
  (let [ensure-cell (fn [s]
                      (if (get-in s [:effects :cells [col row]])
                        s
                        (assoc-in s [:effects :cells [col row]] {:effects [] :active true})))
        ;; Regenerate IDs on all pasted effects to ensure uniqueness
        effects-with-new-ids (mapv regenerate-ids effects)
        current-effects (vec (get-in state [:effects :cells [col row] :effects] []))
        safe-pos (min insert-pos (count current-effects))
        new-effects (vec (concat (subvec current-effects 0 safe-pos)
                                 effects-with-new-ids
                                 (subvec current-effects safe-pos)))
        ;; Select the newly pasted effects
        new-indices (into #{} (range safe-pos (+ safe-pos (count effects-with-new-ids))))]
    {:state (-> state
                ensure-cell
                (assoc-in [:effects :cells [col row] :effects] new-effects)
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] new-indices)
                mark-dirty)}))

(defn- handle-effects-delete-selected
  "Delete all selected effects from the chain.
   Supports both path-based selection (for groups) and legacy index-based selection.
   When deleting a group, all children are deleted with it (not promoted to parent)."
  [{:keys [col row state]}]
  (let [selected-paths (get-in state [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
        selected-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        effects-vec (get-in state [:effects :cells [col row] :effects] [])]
    (cond
      ;; Path-based selection (supports groups)
      (seq selected-paths)
      (let [;; Filter to root paths only - if a group is selected, don't separately delete
            ;; its children (they'll be deleted with the group). This prevents issues where
            ;; children get deleted first, shifting indices and causing unexpected behavior.
            root-paths (filter-to-root-paths selected-paths)
            ;; Sort paths by depth (deepest first) to avoid index issues
            sorted-paths (sort-by (comp - count) root-paths)
            ;; Remove each path
            new-effects (reduce remove-at-path effects-vec sorted-paths)]
        {:state (-> state
                    (assoc-in [:effects :cells [col row] :effects] new-effects)
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
                    mark-dirty)})
      
      ;; Legacy index-based selection
      (seq selected-indices)
      (let [new-effects (u/removev-indexed
                          (fn [idx _] (contains? selected-indices idx))
                          effects-vec)]
        {:state (-> state
                    (assoc-in [:effects :cells [col row] :effects] new-effects)
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
                    mark-dirty)})
      
      :else
      {:state state})))


;; Custom Parameter UI Events


(defn- handle-effects-set-param-ui-mode
  "Toggle between visual and numeric parameter editing mode for an effect.
   Supports both :effect-idx (legacy, top-level) and :effect-path (nested)."
  [{:keys [effect-idx effect-path mode state]}]
  (let [;; Use path as key if available, otherwise use index
        ui-mode-key (or effect-path effect-idx)]
    {:state (assoc-in state
                      [:ui :dialogs :effect-chain-editor :data :ui-modes ui-mode-key]
                      mode)}))

(defn- handle-effects-update-spatial-params
  "Update multiple related parameters from spatial drag (e.g., x and y together).
   
   Event keys:
   - :col, :row - Grid cell coordinates
   - :effect-idx - Index in effect chain (legacy, top-level)
   - :effect-path - Path to effect (supports nested)
   - :point-id - ID of the dragged point (e.g., :center, :tl, :tr)
   - :x, :y - New world coordinates
   - :param-map - Map of point IDs to parameter key mappings
                  e.g., {:center {:x :x :y :y}
                         :tl {:x :tl-x :y :tl-y}}"
  [{:keys [col row effect-idx effect-path point-id x y param-map state]}]
  (let [point-params (get param-map point-id)
        effects-vec (vec (get-in state [:effects :cells [col row] :effects] []))]
    (if point-params
      (let [x-key (:x point-params)
            y-key (:y point-params)
            ;; Use path-based update if effect-path is provided, otherwise fall back to index
            base-path (if effect-path (vec effect-path) [effect-idx])
            updated-effects (-> effects-vec
                               (assoc-in (conj base-path :params x-key) x)
                               (assoc-in (conj base-path :params y-key) y))]
        {:state (assoc-in state [:effects :cells [col row] :effects] updated-effects)})
      {:state state})))


;; Effect Chain Group Events


(defn- make-group
  "Create a new group with given name and items."
  [name items]
  {:type :group
   :id (random-uuid)
   :name name
   :collapsed? false
   :enabled? true
   :items (vec items)})

(defn- handle-effects-create-empty-group
  "Create a new empty group at the end of the chain.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :name (optional) - Group name, defaults to 'New Group'"
  [{:keys [col row name state]}]
  (let [group-name (or name "New Group")
        ensure-cell (fn [s]
                      (if (get-in s [:effects :cells [col row]])
                        s
                        (assoc-in s [:effects :cells [col row]] {:effects [] :active true})))
        new-group (make-group group-name [])]
    {:state (-> state
                ensure-cell
                (update-in [:effects :cells [col row] :effects] conj new-group)
                mark-dirty)}))

(defn- handle-effects-group-selected
  "Group currently selected effects into a new group.
   Selected effects are removed and replaced with a group containing them.
   Works with path-based selection to support nested items.
   
   Uses two-phase operation to avoid index-shifting bugs:
   1. Collect selected items by ID
   2. Remove items by ID set (no index dependencies)
   3. Insert new group at computed position
   
   Event keys:
   - :col, :row - Grid cell coordinates
   - :name (optional) - Group name"
  [{:keys [col row name state]}]
  (let [group-name (or name "New Group")
        selected-paths (get-in state [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
        effects-vec (get-in state [:effects :cells [col row] :effects] [])]
    (if (seq selected-paths)
      (let [;; Filter to root paths only - if a group is selected, don't separately include its children
            root-paths (filter-to-root-paths selected-paths)
            ;; Sort by visual order to maintain relative ordering in the new group
            all-paths (vec (effects/paths-in-chain effects-vec))
            sorted-paths (sort-by #(.indexOf all-paths %) root-paths)
            
            ;; PHASE 1: Extract items in visual order (using paths, which are still valid)
            selected-items (mapv #(get-in effects-vec (vec %)) sorted-paths)
            
            ;; Collect IDs of items to remove (for two-phase operation)
            ids-to-remove (into #{} (map :id selected-items))
            
            ;; Find insertion point - the top-level index of the first selected item
            first-path (first sorted-paths)
            insert-at-top-level (first first-path)
            
            ;; PHASE 2: Remove all selected items by ID (avoids index shifting)
            after-remove (remove-items-by-ids effects-vec ids-to-remove)
            
            ;; Calculate insert position:
            ;; We need to count how many top-level items before insert-at-top-level were removed
            items-removed-before-insert (count
                                          (filter
                                            (fn [path]
                                              (and (= 1 (count path))  ;; Only count top-level items
                                                   (< (first path) insert-at-top-level)))
                                            sorted-paths))
            adjusted-insert (- insert-at-top-level items-removed-before-insert)
            
            ;; Create the new group with extracted items
            new-group (make-group group-name selected-items)
            
            ;; PHASE 3: Insert new group at adjusted position
            new-effects (insert-items-at-index after-remove adjusted-insert [new-group])
            
            ;; Select the newly created group
            new-group-path [adjusted-insert]]
        {:state (-> state
                    (assoc-in [:effects :cells [col row] :effects] new-effects)
                    ;; Select the new group using path-based selection
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{new-group-path})
                    mark-dirty)})
      {:state state})))

(defn- handle-effects-toggle-group-collapse
  "Toggle a group's collapsed state.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :path - Path to the group (e.g., [1] or [2 :items 0])"
  [{:keys [col row path state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        item (get-in effects-vec path)]
    (if (effects/group? item)
      {:state (-> state
                  (update-in [:effects :cells [col row] :effects]
                             (fn [effects]
                               (update-in effects (conj path :collapsed?) not))))}
      {:state state})))

(defn- handle-effects-rename-group
  "Rename a group.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :path - Path to the group
   - :name - New name for the group"
  [{:keys [col row path name state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        item (get-in effects-vec path)]
    (if (effects/group? item)
      {:state (-> state
                  (assoc-in (into [:effects :cells [col row] :effects] (conj path :name)) name)
                  (assoc-in [:ui :dialogs :effect-chain-editor :data :renaming-path] nil)
                  mark-dirty)}
      {:state state})))

(defn- handle-effects-start-rename-group
  "Start renaming a group (shows inline text field).
   Event keys:
   - :path - Path to the group"
  [{:keys [path state]}]
  {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :renaming-path] path)})

(defn- handle-effects-cancel-rename-group
  "Cancel renaming a group (hides inline text field).
   Event keys: none"
  [{:keys [state]}]
  {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :renaming-path] nil)})

(defn- handle-effects-ungroup
  "Ungroup a group, moving its contents to the parent level.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :path - Path to the group (e.g., [1] for top-level, [2 :items 0] for nested)"
  [{:keys [col row path state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        item (get-in effects-vec path)]
    (if (effects/group? item)
      (let [group-items (:items item [])
            ;; Handle top-level vs nested groups differently
            is-top-level? (= 1 (count path))
            group-idx (if is-top-level?
                        (first path)
                        (last path))
            parent-path (if is-top-level?
                          []
                          (vec (butlast (butlast path)))) ;; Remove last index and :items
            parent-vec (if is-top-level?
                         effects-vec
                         (get-in effects-vec (conj parent-path :items) []))
            ;; Remove group and insert its items at same position
            new-parent (vec (concat (subvec parent-vec 0 group-idx)
                                    group-items
                                    (subvec parent-vec (inc group-idx))))
            ;; Update the effects
            new-effects (if is-top-level?
                          new-parent
                          (assoc-in effects-vec (conj parent-path :items) new-parent))]
        {:state (-> state
                    (assoc-in [:effects :cells [col row] :effects] new-effects)
                    ;; Clear selection
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
                    mark-dirty)})
      {:state state})))

(defn- handle-effects-set-item-enabled-at-path
  "Set the enabled state of an item at a specific path.
   Works for both effects and groups.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :path - Path to the item
   - :enabled? - New enabled state (from :fx/event for checkbox)"
  [{:keys [col row path state] :as event}]
  (let [enabled? (if (contains? event :fx/event)
                   (:fx/event event)
                   (:enabled? event))
        path-vec (vec path)]
    {:state (-> state
                (assoc-in (into [:effects :cells [col row] :effects] (conj path-vec :enabled?)) enabled?)
                mark-dirty)}))

(defn- handle-effects-move-item
  "Move an item from one position to another using ID-based targeting.
   Used for drag-and-drop reordering within and between groups.
   
   Supports two modes for backward compatibility:
   1. ID-based (preferred): Uses :target-id and :drop-position
   2. Path-based (legacy): Uses :to-path and :into-group?
   
   Event keys:
   - :col, :row - Grid cell coordinates
   - :from-path - Source path (required to extract the item)
   - :target-id - ID of the target item (preferred over to-path)
   - :drop-position - :before | :into (only :into for groups)
   - :to-path - Legacy: Destination path
   - :into-group? - Legacy: If true, insert into group"
  [{:keys [col row from-path to-path target-id drop-position into-group? state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        item (get-in effects-vec from-path)]
    (if item
      (let [;; Step 1: Remove the item from its current position
            after-remove (remove-at-path effects-vec from-path)
            
            ;; Step 2: Determine the insertion point
            ;; If target-id is provided, use ID-based lookup (robust)
            ;; Otherwise fall back to path-based adjustment (legacy)
            [final-to-path final-into-group?]
            (if target-id
              ;; ID-based: find target in modified chain
              (let [found-path (effects/find-path-by-id after-remove target-id)]
                (if found-path
                  [found-path (= drop-position :into)]
                  ;; Target not found - append to end
                  [[(count after-remove)] false]))
              ;; Path-based (legacy): adjust path manually
              (let [adjusted (if (and (= (butlast from-path) (butlast to-path))
                                      (< (last from-path) (last to-path)))
                               (update to-path (dec (count to-path)) dec)
                               to-path)]
                [adjusted into-group?]))
            
            ;; Step 3: Insert at the determined position
            after-insert (insert-at-path after-remove final-to-path item final-into-group?)]
        {:state (-> state
                    (assoc-in [:effects :cells [col row] :effects] after-insert)
                    mark-dirty)})
      {:state state})))

(defn- handle-effects-select-item-at-path
  "Select an item at a path (path-based selection for nested structures).
   Event keys:
   - :path - Path to select
   - :col, :row - Grid cell coordinates (needed for shift+click range select)
   - :shift? - If true, range select based on visual order (anchor stays fixed)
   - :ctrl? - If true, toggle selection"
  [{:keys [path col row shift? ctrl? state]}]
  (log/debug "handle-effects-select-item-at-path called:"
             "path=" path "col=" col "row=" row "ctrl?=" ctrl? "shift?=" shift?)
  (let [current-paths (get-in state [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
        _ (log/debug "  current-paths=" current-paths)
        last-selected (get-in state [:ui :dialogs :effect-chain-editor :data :last-selected-path])
        new-paths (cond
                    ctrl?
                    (if (contains? current-paths path)
                      (disj current-paths path)
                      (conj current-paths path))
                    
                    shift?
                    (if (and last-selected col row)
                      ;; Range select: get all paths between anchor and clicked in visual order
                      (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
                            all-paths (vec (effects/paths-in-chain effects-vec))
                            anchor-idx (.indexOf all-paths last-selected)
                            target-idx (.indexOf all-paths path)]
                        (if (and (>= anchor-idx 0) (>= target-idx 0))
                          (let [start-idx (min anchor-idx target-idx)
                                end-idx (max anchor-idx target-idx)]
                            (into #{} (subvec all-paths start-idx (inc end-idx))))
                          ;; Fallback if paths not found - just select clicked
                          #{path}))
                      ;; No anchor or col/row - just select clicked
                      #{path})
                    
                    :else
                    #{path})
        ;; Only update anchor on regular click or ctrl+click, NOT on shift+click
        ;; This allows consecutive shift+clicks to extend from the original anchor
        update-anchor? (not shift?)
        _ (log/debug "  new-paths=" new-paths)]
    {:state (-> state
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] new-paths)
                (cond-> update-anchor?
                  (assoc-in [:ui :dialogs :effect-chain-editor :data :last-selected-path] path)))}))

(defn- handle-effects-delete-at-paths
  "Delete all items at the selected paths.
   Event keys:
   - :col, :row - Grid cell coordinates"
  [{:keys [col row state]}]
  (let [selected-paths (get-in state [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
        effects-vec (get-in state [:effects :cells [col row] :effects] [])]
    (if (seq selected-paths)
      (let [;; Sort paths by depth (deepest first) to avoid index issues
            sorted-paths (sort-by (comp - count) selected-paths)
            ;; Remove each path using the top-level helper
            new-effects (reduce remove-at-path effects-vec sorted-paths)]
        {:state (-> state
                    (assoc-in [:effects :cells [col row] :effects] new-effects)
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
                    mark-dirty)})
      {:state state})))


;; ID-Based Selection and Manipulation Events
;; These handlers resolve stable item IDs to ephemeral paths at handling time,
;; fixing the stale closure problem where drag-and-drop handlers captured
;; outdated paths from node creation time.


(defn- handle-effects-select-item-by-id
  "Select an item by its ID (ID-based selection for stable references).
   Stores selected IDs in UI state for stable selection across reorders.
   Event keys:
   - :item-id - UUID of the item to select
   - :col, :row - Grid cell coordinates
   - :ctrl? - Toggle selection
   - :shift? - Range select"
  [{:keys [item-id col row ctrl? shift? state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        current-ids (get-in state [:ui :dialogs :effect-chain-editor :data :selected-ids] #{})
        last-selected-id (get-in state [:ui :dialogs :effect-chain-editor :data :last-selected-id])
        new-ids (cond
                  ctrl?
                  ;; Toggle selection
                  (if (contains? current-ids item-id)
                    (disj current-ids item-id)
                    (conj current-ids item-id))
                  
                  shift?
                  ;; Range select - need to find items between last-selected and clicked
                  (if last-selected-id
                    (let [all-ids (chains/collect-all-ids effects-vec)
                          anchor-idx (.indexOf all-ids last-selected-id)
                          target-idx (.indexOf all-ids item-id)]
                      (if (and (>= anchor-idx 0) (>= target-idx 0))
                        (let [start-idx (min anchor-idx target-idx)
                              end-idx (max anchor-idx target-idx)]
                          (into #{} (subvec all-ids start-idx (inc end-idx))))
                        ;; Fallback if IDs not found
                        #{item-id}))
                    #{item-id})
                  
                  :else
                  ;; Simple selection - replace
                  #{item-id})
        ;; Update anchor only on regular click or ctrl+click, NOT on shift+click
        update-anchor? (not shift?)]
    {:state (-> state
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-ids] new-ids)
                (cond-> update-anchor?
                  (assoc-in [:ui :dialogs :effect-chain-editor :data :last-selected-id] item-id)))}))

(defn- handle-effects-start-drag-by-id
  "Start a drag operation by item ID.
   Resolves to selected IDs for multi-drag.
   Event keys:
   - :initiating-id - UUID of the item being dragged
   - :col, :row - Grid cell coordinates"
  [{:keys [initiating-id col row state]}]
  (let [selected-ids (get-in state [:ui :dialogs :effect-chain-editor :data :selected-ids] #{})
        ;; If dragging a selected item, drag all selected items
        ;; Otherwise, select just this item and drag it
        dragging-ids (if (contains? selected-ids initiating-id)
                       selected-ids
                       #{initiating-id})
        ;; If dragging unselected item, update selection to just that item
        new-selected-ids (if (contains? selected-ids initiating-id)
                           selected-ids
                           #{initiating-id})]
    {:state (-> state
                (assoc-in [:ui :dialogs :effect-chain-editor :data :dragging-ids] dragging-ids)
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-ids] new-selected-ids))}))

(defn- handle-effects-update-drag-ui-state
  "Update drag/drop UI state (for visual feedback).
   Event keys:
   - :col, :row - Grid cell coordinates
   - :updates - Map of state updates (e.g., {:drop-target-id uuid, :drop-position :before})"
  [{:keys [col row updates state]}]
  {:state (update-in state [:ui :dialogs :effect-chain-editor :data] merge updates)})

(defn- handle-effects-toggle-collapse-by-id
  "Toggle a group's collapsed state by ID.
   Event keys:
   - :item-id - UUID of the group
   - :col, :row - Grid cell coordinates"
  [{:keys [item-id col row state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        path (chains/find-path-by-id effects-vec item-id)]
    (if path
      (handle-effects-toggle-group-collapse {:col col :row row :path path :state state})
      {:state state})))

(defn- handle-effects-ungroup-by-id
  "Ungroup a group by ID.
   Event keys:
   - :item-id - UUID of the group
   - :col, :row - Grid cell coordinates"
  [{:keys [item-id col row state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        path (chains/find-path-by-id effects-vec item-id)]
    (if path
      (handle-effects-ungroup {:col col :row row :path path :state state})
      {:state state})))

(defn- handle-effects-start-rename-by-id
  "Start renaming an item by ID.
   Event keys:
   - :item-id - UUID of the item
   - :col, :row - Grid cell coordinates"
  [{:keys [item-id col row state]}]
  {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :renaming-id] item-id)})

(defn- handle-effects-rename-item-by-id
  "Rename an item by ID.
   Event keys:
   - :item-id - UUID of the item
   - :col, :row - Grid cell coordinates
   - :new-name - New name for the item"
  [{:keys [item-id col row new-name state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        path (chains/find-path-by-id effects-vec item-id)
        item (when path (get-in effects-vec path))]
    (if (and path (effects/group? item))
      {:state (-> state
                  (assoc-in (into [:effects :cells [col row] :effects] (conj path :name)) new-name)
                  (assoc-in [:ui :dialogs :effect-chain-editor :data :renaming-id] nil)
                  mark-dirty)}
      {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :renaming-id] nil)})))

(defn- handle-effects-set-enabled-by-id
  "Set enabled state of an item by ID.
   Event keys:
   - :item-id - UUID of the item
   - :col, :row - Grid cell coordinates
   - :enabled? - New enabled state"
  [{:keys [item-id col row enabled? state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        path (chains/find-path-by-id effects-vec item-id)]
    (if path
      {:state (-> state
                  (assoc-in (into [:effects :cells [col row] :effects] (conj path :enabled?)) enabled?)
                  mark-dirty)}
      {:state state})))


;; Multi-Select Drag-and-Drop Events


(defn- path-is-ancestor?
  "Check if ancestor-path is an ancestor of descendant-path.
   [1] is ancestor of [1 :items 0]."
  [ancestor-path descendant-path]
  (and (< (count ancestor-path) (count descendant-path))
       (= (vec ancestor-path) (vec (take (count ancestor-path) descendant-path)))))

(defn- filter-to-root-paths
  "Remove paths that are descendants of other paths in the set.
   If [1] is selected, [1 :items 0] should be excluded since it moves with its parent."
  [paths]
  (set (filter
         (fn [path]
           (not-any? (fn [other]
                       (and (not= other path)
                            (path-is-ancestor? other path)))
                     paths))
         paths)))

(defn- insert-items-at
  "Insert multiple items at a path. If into-group?, add to group's items at end."
  [items path new-items into-group?]
  (let [items-vec (vec items)   ;; Ensure items is a vector for subvec
        path-vec (vec path)]    ;; Ensure path is a vector
    (if into-group?
      ;; Insert into group's items
      (update-in items-vec (conj path-vec :items)
                 #(vec (concat (or % []) new-items)))
      ;; Insert before position
      (if (= 1 (count path-vec))
        (let [idx (first path-vec)
              safe-idx (min idx (count items-vec))]
          (vec (concat (subvec items-vec 0 safe-idx)
                       new-items
                       (subvec items-vec safe-idx))))
        ;; Nested insertion
        (let [[parent-idx & rest-path] path-vec]
          (if (and (>= parent-idx 0) (< parent-idx (count items-vec))
                   (= :items (first rest-path)))
            (update items-vec parent-idx
                    #(update % :items
                             (fn [sub-items]
                               (insert-items-at (vec (or sub-items []))
                                               (vec (rest rest-path))
                                               new-items
                                               false))))
            items-vec))))))

(defn- handle-effects-start-multi-drag
  "Start a multi-drag operation.
   If the initiating item is part of the selection, drag all selected items.
   If it's not selected, auto-select just that item and drag it alone.
   
   Event keys:
   - :col, :row - Grid cell coordinates
   - :initiating-path - Path of the item that was dragged"
  [{:keys [col row initiating-path state]}]
  (let [selected-paths (get-in state [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
        ;; If dragging a selected item, drag all selected items
        ;; Otherwise, select just this item and drag it
        dragging-paths (if (contains? selected-paths initiating-path)
                         selected-paths
                         #{initiating-path})
        ;; If dragging unselected item, update selection to just that item
        new-selected (if (contains? selected-paths initiating-path)
                       selected-paths
                       #{initiating-path})]
    {:state (-> state
                (assoc-in [:ui :dialogs :effect-chain-editor :data :dragging-paths] dragging-paths)
                (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] new-selected))}))

(defn- handle-effects-move-items
  "Move multiple items to a new position using ID-based approach.
   Items are moved in their visual order to maintain relative ordering.
   Uses the centralized chains/move-items-to-target for correct ordering.
   
   Event keys:
   - :col, :row - Grid cell coordinates
   - :target-id - ID of the target item (for finding drop location)
   - :drop-position - :before | :into (only :into for groups)"
  [{:keys [col row target-id drop-position state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        ;; Support both ID-based and legacy path-based dragging
        dragging-ids (get-in state [:ui :dialogs :effect-chain-editor :data :dragging-ids] #{})
        dragging-paths-legacy (get-in state [:ui :dialogs :effect-chain-editor :data :dragging-paths] #{})
        
        ;; Convert IDs to paths if using ID-based approach
        ;; find-paths-by-ids returns a map of {id -> path}, so extract just the values (paths)
        dragging-paths (if (seq dragging-ids)
                         (set (vals (chains/find-paths-by-ids effects-vec dragging-ids)))
                         dragging-paths-legacy)
        
        ;; Filter to root-level paths only (groups include their children)
        root-paths (filter-to-root-paths dragging-paths)
        
        ;; Check if target is in dragging paths or descendant (prevent self-drop)
        target-path (chains/find-path-by-id effects-vec target-id)
        target-in-drag? (or (contains? root-paths target-path)
                           (some #(path-is-ancestor? % target-path) root-paths))]
    
    (if (or (empty? root-paths) target-in-drag?)
      ;; Invalid move - clear dragging state and return
      {:state (-> state
                  (assoc-in [:ui :dialogs :effect-chain-editor :data :dragging-ids] nil)
                  (assoc-in [:ui :dialogs :effect-chain-editor :data :dragging-paths] nil))}
      
      ;; Use centralized move-items-to-target for correct ordering
      (let [new-effects (chains/move-items-to-target effects-vec root-paths target-id drop-position)]
        {:state (-> state
                    (assoc-in [:effects :cells [col row] :effects] new-effects)
                    ;; Clear dragging state
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :dragging-ids] nil)
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :dragging-paths] nil)
                    ;; Clear selection - could compute new paths if needed
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-ids] #{})
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
                    mark-dirty)}))))


;; Timing Events


(defn- handle-timing-set-bpm
  "Set the BPM."
  [{:keys [bpm state]}]
  {:state (assoc-in state [:timing :bpm] (double bpm))})

(defn- handle-timing-tap-tempo
  "Record a tap for tap-tempo calculation."
  [{:keys [state] :as event}]
  (let [now (current-time-ms event)]
    {:state (update-in state [:timing :tap-times] conj now)
     :timing/calculate-bpm true}))

(defn- handle-timing-clear-taps
  "Clear tap tempo timestamps."
  [{:keys [state]}]
  {:state (assoc-in state [:timing :tap-times] [])})

(defn- handle-timing-set-quantization
  "Set quantization mode."
  [{:keys [mode state]}]
  {:state (assoc-in state [:timing :quantization] mode)})


;; Transport Events


(defn- handle-transport-play
  "Start playback."
  [{:keys [state]}]
  {:state (assoc-in state [:playback :playing?] true)})

(defn- handle-transport-stop
  "Stop playback."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:playback :playing?] false)
              (assoc-in [:playback :active-cell] nil))})

(defn- handle-transport-retrigger
  "Retrigger the current animation."
  [{:keys [state] :as event}]
  (let [now (current-time-ms event)]
    {:state (assoc-in state [:playback :trigger-time] now)}))


;; UI Events


(defn- handle-ui-set-active-tab
  "Change the active tab."
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:ui :active-tab] tab-id)})

(defn- handle-ui-select-preset
  "Select a preset in the browser."
  [{:keys [preset-id state]}]
  {:state (assoc-in state [:ui :selected-preset] preset-id)})

(defn- handle-ui-open-dialog
  "Open a dialog."
  [{:keys [dialog-id data state]}]
  {:state (-> state
              (assoc-in [:ui :dialogs dialog-id :open?] true)
              (assoc-in [:ui :dialogs dialog-id :data] data))})

(defn- handle-ui-close-dialog
  "Close a dialog."
  [{:keys [dialog-id state]}]
  {:state (assoc-in state [:ui :dialogs dialog-id :open?] false)})

(defn- handle-ui-update-dialog-data
  "Update data associated with a dialog (e.g., selected item within dialog).
   Supports:
   - :updates - A map of keys to merge into dialog data
   - :tab-id - If present without :updates, sets :active-bank-tab to this value
               (used by styled-tab-bar in dialogs)"
  [{:keys [dialog-id updates tab-id state]}]
  (let [actual-updates (cond
                         updates updates
                         tab-id {:active-bank-tab tab-id}
                         :else {})]
    {:state (update-in state [:ui :dialogs dialog-id :data] merge actual-updates)}))

(defn- handle-ui-start-drag
  "Start a drag operation."
  [{:keys [source-type source-key data state]}]
  {:state (assoc-in state [:ui :drag]
                    {:active? true
                     :source-type source-type
                     :source-key source-key
                     :data data})})

(defn- handle-ui-end-drag
  "End a drag operation."
  [{:keys [state]}]
  {:state (assoc-in state [:ui :drag]
                    {:active? false
                     :source-type nil
                     :source-key nil
                     :data nil})})


;; Project Events


(defn- handle-project-mark-dirty
  "Mark project as having unsaved changes."
  [{:keys [state]}]
  {:state (assoc-in state [:project :dirty?] true)})

(defn- handle-project-mark-clean
  "Mark project as saved."
  [{:keys [state] :as event}]
  (let [now (current-time-ms event)]
    {:state (-> state
                (assoc-in [:project :dirty?] false)
                (assoc-in [:project :last-saved] now))}))

(defn- handle-project-set-folder
  "Set the current project folder."
  [{:keys [folder state]}]
  {:state (assoc-in state [:project :current-folder] folder)})


;; IDN Connection Events


(defn- handle-idn-connect
  "Start IDN connection."
  [{:keys [host port state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connecting?] true)
              (assoc-in [:backend :idn :error] nil))
   :idn/start-streaming {:host host :port (or port 7255)}})

(defn- handle-idn-connected
  "IDN connection established."
  [{:keys [engine target state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connected?] true)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :target] target)
              (assoc-in [:backend :idn :streaming-engine] engine))})

(defn- handle-idn-connection-failed
  "IDN connection failed."
  [{:keys [error state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connected?] false)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :error] error))})

(defn- handle-idn-disconnect
  "Disconnect from IDN target."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:backend :idn :connected?] false)
              (assoc-in [:backend :idn :connecting?] false)
              (assoc-in [:backend :idn :target] nil)
              (assoc-in [:backend :idn :streaming-engine] nil))
   :idn/stop-streaming true})


;; Config Events


(defn- handle-config-update
  "Update a config value."
  [{:keys [path value state]}]
  {:state (assoc-in state (into [:config] path) value)})


;; Projector Events


(defn- handle-projectors-scan-network
  "Start scanning the network for IDN devices."
  [{:keys [state]}]
  (let [broadcast-addr (get-in state [:projectors :broadcast-address] "255.255.255.255")]
    {:state (assoc-in state [:projectors :scanning?] true)
     :projectors/scan {:broadcast-address broadcast-addr}}))

(defn- handle-projectors-scan-complete
  "Handle scan completion - update discovered devices list."
  [{:keys [devices state]}]
  {:state (-> state
              (assoc-in [:projectors :scanning?] false)
              (assoc-in [:projectors :discovered-devices] (vec devices)))})

(defn- handle-projectors-scan-failed
  "Handle scan failure."
  [{:keys [error state]}]
  (log/warn "Network scan failed:" error)
  {:state (assoc-in state [:projectors :scanning?] false)})

(defn- handle-projectors-add-device
  "Add a discovered device as a configured projector.
   If device has services, adds the default service (or first one).
   For multi-output devices, use :projectors/add-service instead."
  [{:keys [device state]}]
  (let [host (:address device)
        services (:services device [])
        ;; Use default service if available, otherwise first, otherwise nil
        default-service (or (first (filter #(get-in % [:flags :default-service]) services))
                            (first services))
        service-id (if default-service (:service-id default-service) 0)
        service-name (when default-service (:name default-service))
        host-name (:host-name device)
        ;; Prefer service name, then host name, then IP address
        name (or service-name host-name host)
        projector-id (keyword (str "projector-" (System/currentTimeMillis)))
        ;; Default effect chain with color calibration and corner pin
        default-effects [{:effect-id :rgb-calibration
                          :id (random-uuid)
                          :enabled? true
                          :params {:r-gain 1.0 :g-gain 1.0 :b-gain 1.0}}
                         {:effect-id :corner-pin
                          :id (random-uuid)
                          :enabled? true
                          :params {:tl-x -1.0 :tl-y 1.0
                                   :tr-x 1.0 :tr-y 1.0
                                   :bl-x -1.0 :bl-y -1.0
                                   :br-x 1.0 :br-y -1.0}}]
        projector-config {:name name
                          :host host
                          :port (:port device 7255)
                          :service-id service-id
                          :service-name service-name
                          :unit-id (:unit-id device)
                          :enabled? true
                          :effects default-effects
                          :output-config {:color-bit-depth 8
                                          :xy-bit-depth 16}
                          :status {:connected? false}}]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:projectors :active-projector] projector-id)
                mark-dirty)}))

(defn- handle-projectors-add-service
  "Add a specific service/output from a discovered device as a projector.
   Used when a device has multiple outputs and user wants to add a specific one."
  [{:keys [device service state]}]
  (let [host (:address device)
        service-id (:service-id service)
        service-name (:name service)
        host-name (:host-name device)
        ;; Prefer service name, then host name, then IP address
        name (or service-name host-name host)
        projector-id (keyword (str "projector-" (System/currentTimeMillis) "-" service-id))
        ;; Default effect chain with color calibration and corner pin
        default-effects [{:effect-id :rgb-calibration
                          :id (random-uuid)
                          :enabled? true
                          :params {:r-gain 1.0 :g-gain 1.0 :b-gain 1.0}}
                         {:effect-id :corner-pin
                          :id (random-uuid)
                          :enabled? true
                          :params {:tl-x -1.0 :tl-y 1.0
                                   :tr-x 1.0 :tr-y 1.0
                                   :bl-x -1.0 :bl-y -1.0
                                   :br-x 1.0 :br-y -1.0}}]
        projector-config {:name name
                          :host host
                          :port (:port device 7255)
                          :service-id service-id
                          :service-name service-name
                          :unit-id (:unit-id device)
                          :enabled? true
                          :effects default-effects
                          :output-config {:color-bit-depth 8
                                          :xy-bit-depth 16}
                          :status {:connected? false}}]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:projectors :active-projector] projector-id)
                mark-dirty)}))

(defn- handle-projectors-add-all-services
  "Add all services from a discovered device as configured projectors."
  [{:keys [device state]}]
  (let [host (:address device)
        services (:services device [])
        host-name (:host-name device)
        now (System/currentTimeMillis)
        ;; Create projector configs for each service
        new-projectors (reduce
                        (fn [acc [idx service]]
                          (let [service-id (:service-id service)
                                service-name (:name service)
                                ;; Prefer service name, then host name, then IP address
                                name (or service-name host-name host)
                                projector-id (keyword (str "projector-" now "-" service-id "-" idx))
                                default-effects [{:effect-id :rgb-calibration
                                                  :id (random-uuid)
                                                  :enabled? true
                                                  :params {:r-gain 1.0 :g-gain 1.0 :b-gain 1.0}}
                                                 {:effect-id :corner-pin
                                                  :id (random-uuid)
                                                  :enabled? true
                                                  :params {:tl-x -1.0 :tl-y 1.0
                                                           :tr-x 1.0 :tr-y 1.0
                                                           :bl-x -1.0 :bl-y -1.0
                                                           :br-x 1.0 :br-y -1.0}}]
                                projector-config {:name name
                                                  :host host
                                                  :port (:port device 7255)
                                                  :service-id service-id
                                                  :service-name service-name
                                                  :unit-id (:unit-id device)
                                                  :enabled? true
                                                  :effects default-effects
                                                  :output-config {:color-bit-depth 8
                                                                  :xy-bit-depth 16}
                                                  :status {:connected? false}}]
                            (assoc acc projector-id projector-config)))
                        {}
                        (map-indexed vector services))
        first-projector-id (first (keys new-projectors))]
    {:state (-> state
                (update-in [:projectors :items] merge new-projectors)
                (assoc-in [:projectors :active-projector] first-projector-id)
                mark-dirty)}))

(defn- handle-projectors-add-manual
  "Add a projector manually (not from discovery)."
  [{:keys [name host port state]}]
  (let [projector-id (keyword (str "projector-" (System/currentTimeMillis)))
        default-effects [{:effect-id :rgb-calibration
                          :id (random-uuid)
                          :enabled? true
                          :params {:r-gain 1.0 :g-gain 1.0 :b-gain 1.0}}
                         {:effect-id :corner-pin
                          :id (random-uuid)
                          :enabled? true
                          :params {:tl-x -1.0 :tl-y 1.0
                                   :tr-x 1.0 :tr-y 1.0
                                   :bl-x -1.0 :bl-y -1.0
                                   :br-x 1.0 :br-y -1.0}}]
        projector-config {:name (or name host)
                          :host host
                          :port (or port 7255)
                          :unit-id nil
                          :enabled? true
                          :effects default-effects
                          :output-config {:color-bit-depth 8
                                          :xy-bit-depth 16}
                          :status {:connected? false}}]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:projectors :active-projector] projector-id)
                mark-dirty)}))

(defn- handle-projectors-select-projector
  "Select a projector for editing."
  [{:keys [projector-id state]}]
  {:state (assoc-in state [:projectors :active-projector] projector-id)})

(defn- handle-projectors-remove-projector
  "Remove a projector configuration."
  [{:keys [projector-id state]}]
  (let [active (get-in state [:projectors :active-projector])
        items (get-in state [:projectors :items])
        new-items (dissoc items projector-id)
        ;; If removing active projector, select first remaining
        new-active (if (= active projector-id)
                     (first (keys new-items))
                     active)]
    {:state (-> state
                (assoc-in [:projectors :items] new-items)
                (assoc-in [:projectors :active-projector] new-active)
                mark-dirty)}))

(defn- handle-projectors-update-settings
  "Update projector settings (name, host, port, enabled, output-config)."
  [{:keys [projector-id updates state]}]
  {:state (-> state
              (update-in [:projectors :items projector-id] merge updates)
              mark-dirty)})

(defn- handle-projectors-toggle-enabled
  "Toggle a projector's enabled state."
  [{:keys [projector-id state]}]
  (let [current (get-in state [:projectors :items projector-id :enabled?] true)]
    {:state (-> state
                (assoc-in [:projectors :items projector-id :enabled?] (not current))
                mark-dirty)}))

(defn- handle-projectors-add-effect
  "Add an effect to a projector's chain."
  [{:keys [projector-id effect state]}]
  (let [effect-with-fields (cond-> effect
                             (not (contains? effect :enabled?))
                             (assoc :enabled? true)
                             (not (contains? effect :id))
                             (assoc :id (random-uuid)))]
    {:state (-> state
                (update-in [:projectors :items projector-id :effects] conj effect-with-fields)
                mark-dirty)}))

(defn- handle-projectors-remove-effect
  "Remove an effect from a projector's chain."
  [{:keys [projector-id effect-idx state]}]
  (let [effects-vec (get-in state [:projectors :items projector-id :effects] [])
        new-effects (vec (concat (subvec effects-vec 0 effect-idx)
                                 (subvec effects-vec (inc effect-idx))))]
    {:state (-> state
                (assoc-in [:projectors :items projector-id :effects] new-effects)
                mark-dirty)}))

(defn- handle-projectors-update-effect-param
  "Update a parameter in a projector's effect."
  [{:keys [projector-id effect-idx param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))]
    {:state (assoc-in state
                      [:projectors :items projector-id :effects effect-idx :params param-key]
                      value)}))

(defn- handle-projectors-reorder-effects
  "Reorder effects in a projector's chain."
  [{:keys [projector-id from-idx to-idx state]}]
  (let [effects-vec (get-in state [:projectors :items projector-id :effects] [])
        effect (nth effects-vec from-idx)
        without (vec (concat (subvec effects-vec 0 from-idx)
                             (subvec effects-vec (inc from-idx))))
        reordered (vec (concat (subvec without 0 to-idx)
                               [effect]
                               (subvec without to-idx)))]
    {:state (-> state
                (assoc-in [:projectors :items projector-id :effects] reordered)
                mark-dirty)}))

(defn- handle-projectors-set-test-pattern
  "Set or clear the test pattern mode."
  [{:keys [pattern state]}]
  {:state (assoc-in state [:projectors :test-pattern-mode] pattern)})

(defn- handle-projectors-set-broadcast-address
  "Set the broadcast address for network scanning."
  [{:keys [address state]}]
  {:state (assoc-in state [:projectors :broadcast-address] address)})

(defn- handle-projectors-toggle-device-expand
  "Toggle the expanded state of a device in the discovery panel.
   Used to show/hide the service list for multi-output devices."
  [{:keys [address state]}]
  (let [current-expanded (get-in state [:projectors :expanded-devices] #{})]
    {:state (assoc-in state [:projectors :expanded-devices]
                      (if (contains? current-expanded address)
                        (disj current-expanded address)
                        (conj current-expanded address)))}))

(defn- handle-projectors-update-connection-status
  "Update a projector's connection status (called by streaming service)."
  [{:keys [projector-id status state]}]
  {:state (update-in state [:projectors :items projector-id :status] merge status)})

(defn- handle-projectors-select-effect
  "Select an effect in the projector's chain for editing.
   Supports both legacy index-based selection (:effect-idx) and
   new path-based selection (:path with :ctrl? and :shift? modifiers)."
  [{:keys [projector-id effect-idx path ctrl? shift? state] :as event}]
  (if path
    ;; New path-based selection (from shared sidebar)
    (handle-projectors-select-effect-at-path event)
    ;; Legacy index-based selection
    {:state (assoc-in state [:projectors :selected-effect-idx] effect-idx)}))

(defn- handle-projectors-update-corner-pin
  "Update corner pin parameters from spatial drag.
   Event keys:
   - :projector-id - ID of the projector
   - :effect-idx - Index of the corner pin effect
   - :point-id - Corner being dragged (:tl, :tr, :bl, :br)
   - :x, :y - New coordinates
   - :param-map - Mapping of point IDs to param keys"
  [{:keys [projector-id effect-idx point-id x y param-map state]}]
  (let [point-params (get param-map point-id)]
    (if point-params
      (let [x-key (:x point-params)
            y-key (:y point-params)]
        {:state (-> state
                    (assoc-in [:projectors :items projector-id :effects effect-idx :params x-key] x)
                    (assoc-in [:projectors :items projector-id :effects effect-idx :params y-key] y))})
      {:state state})))

(defn- handle-projectors-reset-corner-pin
  "Reset corner pin effect to default values."
  [{:keys [projector-id effect-idx state]}]
  {:state (assoc-in state [:projectors :items projector-id :effects effect-idx :params]
                    {:tl-x -1.0 :tl-y 1.0
                     :tr-x 1.0 :tr-y 1.0
                     :bl-x -1.0 :bl-y -1.0
                     :br-x 1.0 :br-y -1.0})})


;; Projector Effect Chain Events (Path-Based Selection)
;; These handlers support the shared effect-chain-sidebar component
;; and use path-based selection similar to the grid cell effects handlers.


(defn- projector-effects-path
  "Get the path to a projector's effects in state."
  [projector-id]
  [:projectors :items projector-id :effects])

(defn- projector-ui-state-path
  "Get the path to projector effect chain UI state.
   
   UI state is stored in :ui domain (not :projectors) to avoid
   subscription cascade - changes to drag state won't invalidate
   all projector-related subscriptions."
  [projector-id]
  [:ui :projector-effect-ui-state projector-id])

(defn- handle-projectors-select-effect-at-path
  "Select an effect at a path using path-based selection (supports nested structures).
   Handles Ctrl+click (toggle) and Shift+click (range select)."
  [{:keys [projector-id path ctrl? shift? state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        current-paths (get-in state (conj ui-path :selected-paths) #{})
        last-selected (get-in state (conj ui-path :last-selected-path))
        effects-vec (get-in state (projector-effects-path projector-id) [])
        new-paths (cond
                    ctrl?
                    (if (contains? current-paths path)
                      (disj current-paths path)
                      (conj current-paths path))
                    
                    shift?
                    (if last-selected
                      (let [all-paths (vec (effects/paths-in-chain effects-vec))
                            anchor-idx (.indexOf all-paths last-selected)
                            target-idx (.indexOf all-paths path)]
                        (if (and (>= anchor-idx 0) (>= target-idx 0))
                          (let [start-idx (min anchor-idx target-idx)
                                end-idx (max anchor-idx target-idx)]
                            (into #{} (subvec all-paths start-idx (inc end-idx))))
                          #{path}))
                      #{path})
                    
                    :else
                    #{path})
        update-anchor? (not shift?)]
    {:state (-> state
                (assoc-in (conj ui-path :selected-paths) new-paths)
                (cond-> update-anchor?
                  (assoc-in (conj ui-path :last-selected-path) path)))}))

(defn- handle-projectors-select-all-effects
  "Select all effects in a projector's chain."
  [{:keys [projector-id state]}]
  (let [effects-vec (get-in state (projector-effects-path projector-id) [])
        all-paths (into #{} (effects/paths-in-chain effects-vec))
        ui-path (projector-ui-state-path projector-id)]
    {:state (assoc-in state (conj ui-path :selected-paths) all-paths)}))

(defn- handle-projectors-clear-effect-selection
  "Clear effect selection for a projector."
  [{:keys [projector-id state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (-> state
                (assoc-in (conj ui-path :selected-paths) #{})
                (assoc-in (conj ui-path :last-selected-path) nil))}))

(defn- handle-projectors-copy-effects
  "Copy selected effects to clipboard."
  [{:keys [projector-id state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        selected-paths (get-in state (conj ui-path :selected-paths) #{})
        effects-vec (get-in state (projector-effects-path projector-id) [])
        valid-effects (when (seq selected-paths)
                        (vec (keep #(get-in effects-vec %) selected-paths)))]
    (cond-> {:state state}
      (seq valid-effects)
      (assoc :clipboard/copy-effects valid-effects))))

(defn- handle-projectors-paste-effects
  "Paste effects from clipboard into projector chain."
  [{:keys [projector-id state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        selected-paths (get-in state (conj ui-path :selected-paths) #{})
        effects-vec (get-in state (projector-effects-path projector-id) [])
        insert-pos (if (seq selected-paths)
                     (let [top-level-indices (map first selected-paths)]
                       (inc (apply max top-level-indices)))
                     (count effects-vec))]
    {:state state
     :clipboard/paste-projector-effects {:projector-id projector-id
                                         :insert-pos insert-pos}}))

(defn- handle-projectors-insert-pasted-effects
  "Actually insert pasted effects into projector chain (called by effect handler)."
  [{:keys [projector-id insert-pos effects state]}]
  (let [effects-with-new-ids (mapv regenerate-ids effects)
        current-effects (vec (get-in state (projector-effects-path projector-id) []))
        safe-pos (min insert-pos (count current-effects))
        new-effects (vec (concat (subvec current-effects 0 safe-pos)
                                 effects-with-new-ids
                                 (subvec current-effects safe-pos)))
        ui-path (projector-ui-state-path projector-id)
        new-paths (into #{} (map (fn [i] [(+ safe-pos i)]) (range (count effects-with-new-ids))))]
    {:state (-> state
                (assoc-in (projector-effects-path projector-id) new-effects)
                (assoc-in (conj ui-path :selected-paths) new-paths)
                mark-dirty)}))

(defn- handle-projectors-delete-effects
  "Delete selected effects from projector chain."
  [{:keys [projector-id state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        selected-paths (get-in state (conj ui-path :selected-paths) #{})
        effects-vec (get-in state (projector-effects-path projector-id) [])]
    (if (seq selected-paths)
      (let [root-paths (filter-to-root-paths selected-paths)
            sorted-paths (sort-by (comp - count) root-paths)
            new-effects (reduce remove-at-path effects-vec sorted-paths)]
        {:state (-> state
                    (assoc-in (projector-effects-path projector-id) new-effects)
                    (assoc-in (conj ui-path :selected-paths) #{})
                    mark-dirty)})
      {:state state})))

(defn- handle-projectors-start-effect-drag
  "Start a multi-drag operation for projector effects."
  [{:keys [projector-id initiating-path state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        selected-paths (get-in state (conj ui-path :selected-paths) #{})
        dragging-paths (if (contains? selected-paths initiating-path)
                         selected-paths
                         #{initiating-path})
        new-selected (if (contains? selected-paths initiating-path)
                       selected-paths
                       #{initiating-path})]
    {:state (-> state
                (assoc-in (conj ui-path :dragging-paths) dragging-paths)
                (assoc-in (conj ui-path :selected-paths) new-selected))}))

(defn- handle-projectors-move-effects
  "Move multiple effects to a new position in projector chain.
   Uses the centralized chains/move-items-to-target for correct ordering."
  [{:keys [projector-id target-id drop-position state]}]
  (let [ui-path (projector-ui-state-path projector-id)
        effects-vec (get-in state (projector-effects-path projector-id) [])
        dragging-paths (get-in state (conj ui-path :dragging-paths) #{})
        root-paths (filter-to-root-paths dragging-paths)
        target-path (chains/find-path-by-id effects-vec target-id)
        target-in-drag? (or (contains? root-paths target-path)
                           (some #(path-is-ancestor? % target-path) root-paths))]
    (if (or (empty? root-paths) target-in-drag?)
      {:state (assoc-in state (conj ui-path :dragging-paths) nil)}
      ;; Use centralized move-items-to-target for correct ordering
      (let [new-effects (chains/move-items-to-target effects-vec root-paths target-id drop-position)]
        {:state (-> state
                    (assoc-in (projector-effects-path projector-id) new-effects)
                    (assoc-in (conj ui-path :dragging-paths) nil)
                    (assoc-in (conj ui-path :selected-paths) #{})
                    mark-dirty)}))))

(defn- handle-projectors-update-effect-ui-state
  "Update projector effect UI state (for drag-and-drop feedback)."
  [{:keys [projector-id updates state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (update-in state ui-path merge updates)}))

(defn- handle-projectors-group-effects
  "Group selected effects in projector chain."
  [{:keys [projector-id name state]}]
  (let [group-name (or name "New Group")
        ui-path (projector-ui-state-path projector-id)
        selected-paths (get-in state (conj ui-path :selected-paths) #{})
        effects-vec (get-in state (projector-effects-path projector-id) [])]
    (if (seq selected-paths)
      (let [root-paths (filter-to-root-paths selected-paths)
            all-paths (vec (effects/paths-in-chain effects-vec))
            sorted-paths (sort-by #(.indexOf all-paths %) root-paths)
            selected-items (mapv #(get-in effects-vec (vec %)) sorted-paths)
            ids-to-remove (into #{} (map :id selected-items))
            first-path (first sorted-paths)
            insert-at-top-level (first first-path)
            after-remove (remove-items-by-ids effects-vec ids-to-remove)
            items-removed-before-insert (count
                                          (filter
                                            (fn [path]
                                              (and (= 1 (count path))
                                                   (< (first path) insert-at-top-level)))
                                            sorted-paths))
            adjusted-insert (- insert-at-top-level items-removed-before-insert)
            new-group (make-group group-name selected-items)
            new-effects (insert-items-at-index after-remove adjusted-insert [new-group])
            new-group-path [adjusted-insert]]
        {:state (-> state
                    (assoc-in (projector-effects-path projector-id) new-effects)
                    (assoc-in (conj ui-path :selected-paths) #{new-group-path})
                    mark-dirty)})
      {:state state})))

(defn- handle-projectors-ungroup-effects
  "Ungroup a group in projector chain."
  [{:keys [projector-id path state]}]
  (let [effects-vec (get-in state (projector-effects-path projector-id) [])
        item (get-in effects-vec path)
        ui-path (projector-ui-state-path projector-id)]
    (if (effects/group? item)
      (let [group-items (:items item [])
            is-top-level? (= 1 (count path))
            group-idx (if is-top-level? (first path) (last path))
            parent-path (if is-top-level? [] (vec (butlast (butlast path))))
            parent-vec (if is-top-level? effects-vec (get-in effects-vec (conj parent-path :items) []))
            new-parent (vec (concat (subvec parent-vec 0 group-idx)
                                    group-items
                                    (subvec parent-vec (inc group-idx))))
            new-effects (if is-top-level?
                          new-parent
                          (assoc-in effects-vec (conj parent-path :items) new-parent))]
        {:state (-> state
                    (assoc-in (projector-effects-path projector-id) new-effects)
                    (assoc-in (conj ui-path :selected-paths) #{})
                    mark-dirty)})
      {:state state})))

(defn- handle-projectors-create-effect-group
  "Create empty group in projector chain."
  [{:keys [projector-id state]}]
  (let [new-group (make-group "New Group" [])]
    {:state (-> state
                (update-in (projector-effects-path projector-id) conj new-group)
                mark-dirty)}))

(defn- handle-projectors-toggle-effect-group-collapse
  "Toggle collapse state of a group in projector chain."
  [{:keys [projector-id path state]}]
  (let [effects-vec (get-in state (projector-effects-path projector-id) [])
        item (get-in effects-vec path)]
    (if (effects/group? item)
      {:state (update-in state (projector-effects-path projector-id)
                         (fn [effects]
                           (update-in effects (conj path :collapsed?) not)))}
      {:state state})))

(defn- handle-projectors-start-rename-effect-group
  "Start renaming a group in projector chain."
  [{:keys [projector-id path state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (assoc-in state (conj ui-path :renaming-path) path)}))

(defn- handle-projectors-rename-effect-group
  "Rename a group in projector chain."
  [{:keys [projector-id path name state]}]
  (let [effects-vec (get-in state (projector-effects-path projector-id) [])
        item (get-in effects-vec path)
        ui-path (projector-ui-state-path projector-id)]
    (if (effects/group? item)
      {:state (-> state
                  (assoc-in (into (projector-effects-path projector-id) (conj path :name)) name)
                  (assoc-in (conj ui-path :renaming-path) nil)
                  mark-dirty)}
      {:state state})))

(defn- handle-projectors-cancel-rename-effect-group
  "Cancel renaming a group in projector chain."
  [{:keys [projector-id state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (assoc-in state (conj ui-path :renaming-path) nil)}))

(defn- handle-projectors-set-effect-enabled-at-path
  "Set enabled state of an effect at a path in projector chain."
  [{:keys [projector-id path enabled? state] :as event}]
  (let [enabled-val (if (contains? event :fx/event) (:fx/event event) enabled?)
        path-vec (vec path)]
    {:state (-> state
                (assoc-in (into (projector-effects-path projector-id) (conj path-vec :enabled?)) enabled-val)
                mark-dirty)}))

(defn- handle-projectors-add-calibration-effect
  "Add a calibration effect to projector chain."
  [{:keys [projector-id effect state]}]
  (let [effect-with-fields (cond-> effect
                             (not (contains? effect :enabled?))
                             (assoc :enabled? true)
                             (not (contains? effect :id))
                             (assoc :id (random-uuid)))
        current-effects (get-in state (projector-effects-path projector-id) [])
        new-effect-idx (count current-effects)
        new-effect-path [new-effect-idx]
        ui-path (projector-ui-state-path projector-id)]
    {:state (-> state
                (update-in (projector-effects-path projector-id) conj effect-with-fields)
                (assoc-in (conj ui-path :selected-paths) #{new-effect-path})
                (assoc-in (conj ui-path :last-selected-path) new-effect-path)
                mark-dirty)}))

(defn- handle-projectors-update-effect-param-at-path
  "Update a parameter in a projector effect using path-based addressing."
  [{:keys [projector-id effect-path param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))
        effects-vec (vec (get-in state (projector-effects-path projector-id) []))
        updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) value)]
    {:state (assoc-in state (projector-effects-path projector-id) updated-effects)}))

(defn- handle-projectors-update-effect-param-from-text
  "Update a projector effect parameter from text input."
  [{:keys [projector-id effect-path param-key min max state] :as event}]
  (let [action-event (:fx/event event)
        text-field (.getSource action-event)
        text (.getText text-field)
        parsed (try (Double/parseDouble text) (catch Exception _ nil))]
    (if parsed
      (let [clamped (-> parsed (clojure.core/max min) (clojure.core/min max))
            effects-vec (vec (get-in state (projector-effects-path projector-id) []))
            updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) clamped)]
        {:state (assoc-in state (projector-effects-path projector-id) updated-effects)})
      {:state state})))

(defn- handle-projectors-set-effect-ui-mode
  "Set UI mode for a projector effect's parameters."
  [{:keys [projector-id effect-path mode state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (assoc-in state (conj ui-path :ui-modes effect-path) mode)}))


;; Cue Chain Events
;; These handlers support the cue chain editor for building complex cues
;; by combining multiple presets with per-preset effect chains.


(defn- cue-chain-path
  "Get the path to a grid cell's cue chain in state."
  [col row]
  [:grid :cells [col row] :cue-chain :items])

(defn- cue-chain-ui-path
  "Get the path to cue chain editor UI state."
  []
  [:cue-chain-editor])

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

(defn- handle-cue-chain-add-preset
  "Add a preset to the cue chain.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :preset-id - ID of the preset to add (e.g., :circle, :wave)"
  [{:keys [col row preset-id state]}]
  (let [ensure-cell (fn [s]
                      (if (get-in s [:grid :cells [col row] :cue-chain])
                        s
                        (assoc-in s [:grid :cells [col row]] {:cue-chain {:items []}})))
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
                mark-dirty)}))

(defn- handle-cue-chain-remove-items
  "Remove selected items from the cue chain.
   Event keys:
   - :col, :row - Grid cell coordinates"
  [{:keys [col row state]}]
  (let [selected-paths (get-in state [:cue-chain-editor :selected-paths] #{})
        items-vec (get-in state (cue-chain-path col row) [])]
    (if (seq selected-paths)
      (let [root-paths (filter-to-root-paths selected-paths)
            sorted-paths (sort-by (comp - count) root-paths)
            new-items (reduce remove-at-path items-vec sorted-paths)]
        {:state (-> state
                    (assoc-in (cue-chain-path col row) new-items)
                    (assoc-in [:cue-chain-editor :selected-paths] #{})
                    mark-dirty)})
      {:state state})))

(defn- handle-cue-chain-move-items
  "Move items to a new position via drag-and-drop.
   Uses the centralized chains/move-items-to-target for correct ordering.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :target-id - ID of the target item
   - :drop-position - :before | :into"
  [{:keys [col row target-id drop-position state]}]
  (let [items-vec (get-in state (cue-chain-path col row) [])
        dragging-paths (get-in state [:cue-chain-editor :dragging-paths] #{})
        root-paths (filter-to-root-paths dragging-paths)
        target-path (chains/find-path-by-id items-vec target-id)
        target-in-drag? (or (contains? root-paths target-path)
                           (some #(path-is-ancestor? % target-path) root-paths))]
    (if (or (empty? root-paths) target-in-drag?)
      {:state (assoc-in state [:cue-chain-editor :dragging-paths] nil)}
      ;; Use centralized move-items-to-target for correct ordering
      (let [new-items (chains/move-items-to-target items-vec root-paths target-id drop-position)]
        {:state (-> state
                    (assoc-in (cue-chain-path col row) new-items)
                    (assoc-in [:cue-chain-editor :dragging-paths] nil)
                    (assoc-in [:cue-chain-editor :selected-paths] #{})
                    mark-dirty)}))))

(defn- handle-cue-chain-select-item
  "Select an item in the cue chain.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :path - Path to the item
   - :ctrl? - Toggle selection
   - :shift? - Range select"
  [{:keys [col row path ctrl? shift? state]}]
  (let [current-paths (get-in state [:cue-chain-editor :selected-paths] #{})
        last-selected (get-in state [:cue-chain-editor :last-selected-path])
        items-vec (get-in state (cue-chain-path col row) [])
        new-paths (cond
                    ctrl?
                    (if (contains? current-paths path)
                      (disj current-paths path)
                      (conj current-paths path))
                    
                    shift?
                    (if last-selected
                      (let [all-paths (vec (chains/paths-in-chain items-vec))
                            anchor-idx (.indexOf all-paths last-selected)
                            target-idx (.indexOf all-paths path)]
                        (if (and (>= anchor-idx 0) (>= target-idx 0))
                          (let [start-idx (min anchor-idx target-idx)
                                end-idx (max anchor-idx target-idx)]
                            (into #{} (subvec all-paths start-idx (inc end-idx))))
                          #{path}))
                      #{path})
                    
                    :else
                    #{path})
        update-anchor? (not shift?)]
    {:state (-> state
                (assoc-in [:cue-chain-editor :selected-paths] new-paths)
                (cond-> update-anchor?
                  (assoc-in [:cue-chain-editor :last-selected-path] path)))}))

(defn- handle-cue-chain-select-all
  "Select all items in the cue chain.
   Event keys:
   - :col, :row - Grid cell coordinates"
  [{:keys [col row state]}]
  (let [items-vec (get-in state (cue-chain-path col row) [])
        all-paths (into #{} (chains/paths-in-chain items-vec))]
    {:state (assoc-in state [:cue-chain-editor :selected-paths] all-paths)}))

(defn- handle-cue-chain-clear-selection
  "Clear item selection in the cue chain editor."
  [{:keys [state]}]
  {:state (-> state
              (assoc-in [:cue-chain-editor :selected-paths] #{})
              (assoc-in [:cue-chain-editor :last-selected-path] nil))})

(defn- handle-cue-chain-start-drag
  "Start a multi-drag operation.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :initiating-path - Path of the item being dragged"
  [{:keys [col row initiating-path state]}]
  (let [selected-paths (get-in state [:cue-chain-editor :selected-paths] #{})
        dragging-paths (if (contains? selected-paths initiating-path)
                         selected-paths
                         #{initiating-path})
        new-selected (if (contains? selected-paths initiating-path)
                       selected-paths
                       #{initiating-path})]
    {:state (-> state
                (assoc-in [:cue-chain-editor :dragging-paths] dragging-paths)
                (assoc-in [:cue-chain-editor :selected-paths] new-selected))}))

(defn- handle-cue-chain-copy-selected
  "Copy selected items to clipboard.
   Event keys:
   - :col, :row - Grid cell coordinates"
  [{:keys [col row state]}]
  (let [selected-paths (get-in state [:cue-chain-editor :selected-paths] #{})
        items-vec (get-in state (cue-chain-path col row) [])
        valid-items (when (seq selected-paths)
                      (vec (keep #(get-in items-vec %) selected-paths)))]
    (if (seq valid-items)
      {:state (assoc-in state [:cue-chain-editor :clipboard] valid-items)}
      {:state state})))

(defn- handle-cue-chain-paste
  "Paste items from clipboard.
   Event keys:
   - :col, :row - Grid cell coordinates"
  [{:keys [col row state]}]
  (let [clipboard (get-in state [:cue-chain-editor :clipboard])
        selected-paths (get-in state [:cue-chain-editor :selected-paths] #{})
        items-vec (get-in state (cue-chain-path col row) [])]
    (if (seq clipboard)
      (let [;; Regenerate IDs on pasted items
            items-with-new-ids (mapv regenerate-ids clipboard)
            ;; Calculate insert position
            insert-pos (if (seq selected-paths)
                         (let [top-level-indices (map first selected-paths)]
                           (inc (apply max top-level-indices)))
                         (count items-vec))
            safe-pos (min insert-pos (count items-vec))
            new-items (vec (concat (subvec items-vec 0 safe-pos)
                                   items-with-new-ids
                                   (subvec items-vec safe-pos)))
            ;; Select pasted items
            new-paths (into #{} (map (fn [i] [(+ safe-pos i)]) (range (count items-with-new-ids))))]
        {:state (-> state
                    (assoc-in (cue-chain-path col row) new-items)
                    (assoc-in [:cue-chain-editor :selected-paths] new-paths)
                    mark-dirty)})
      {:state state})))

(defn- handle-cue-chain-group-selected
  "Group selected items into a new group.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :name (optional) - Group name"
  [{:keys [col row name state]}]
  (let [group-name (or name "New Group")
        selected-paths (get-in state [:cue-chain-editor :selected-paths] #{})
        items-vec (get-in state (cue-chain-path col row) [])]
    (if (seq selected-paths)
      (let [root-paths (filter-to-root-paths selected-paths)
            all-paths (vec (chains/paths-in-chain items-vec))
            sorted-paths (sort-by #(.indexOf all-paths %) root-paths)
            selected-items (mapv #(get-in items-vec (vec %)) sorted-paths)
            ids-to-remove (into #{} (map :id selected-items))
            first-path (first sorted-paths)
            insert-at-top-level (first first-path)
            after-remove (remove-items-by-ids items-vec ids-to-remove)
            items-removed-before-insert (count
                                          (filter
                                            (fn [path]
                                              (and (= 1 (count path))
                                                   (< (first path) insert-at-top-level)))
                                            sorted-paths))
            adjusted-insert (- insert-at-top-level items-removed-before-insert)
            new-group (make-group group-name selected-items)
            new-items (insert-items-at-index after-remove adjusted-insert [new-group])
            new-group-path [adjusted-insert]]
        {:state (-> state
                    (assoc-in (cue-chain-path col row) new-items)
                    (assoc-in [:cue-chain-editor :selected-paths] #{new-group-path})
                    mark-dirty)})
      {:state state})))

(defn- handle-cue-chain-ungroup
  "Ungroup a group, moving contents to parent level.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :path - Path to the group"
  [{:keys [col row path state]}]
  (let [items-vec (get-in state (cue-chain-path col row) [])
        item (get-in items-vec path)]
    (if (chains/group? item)
      (let [group-items (:items item [])
            is-top-level? (= 1 (count path))
            group-idx (if is-top-level? (first path) (last path))
            parent-path (if is-top-level? [] (vec (butlast (butlast path))))
            parent-vec (if is-top-level? items-vec (get-in items-vec (conj parent-path :items) []))
            new-parent (vec (concat (subvec parent-vec 0 group-idx)
                                    group-items
                                    (subvec parent-vec (inc group-idx))))
            new-items (if is-top-level?
                        new-parent
                        (assoc-in items-vec (conj parent-path :items) new-parent))]
        {:state (-> state
                    (assoc-in (cue-chain-path col row) new-items)
                    (assoc-in [:cue-chain-editor :selected-paths] #{})
                    mark-dirty)})
      {:state state})))

(defn- handle-cue-chain-toggle-collapse
  "Toggle a group's collapsed state.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :path - Path to the group"
  [{:keys [col row path state]}]
  (let [items-vec (get-in state (cue-chain-path col row) [])
        item (get-in items-vec path)]
    (if (chains/group? item)
      {:state (update-in state (cue-chain-path col row)
                         (fn [items]
                           (update-in items (conj path :collapsed?) not)))}
      {:state state})))

(defn- handle-cue-chain-set-item-enabled
  "Set enabled state of an item.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :path - Path to the item
   - :enabled? - New enabled state (from :fx/event for checkbox)"
  [{:keys [col row path state] :as event}]
  (let [enabled? (if (contains? event :fx/event)
                   (:fx/event event)
                   (:enabled? event))
        path-vec (vec path)]
    {:state (-> state
                (assoc-in (into (cue-chain-path col row) (conj path-vec :enabled?)) enabled?)
                mark-dirty)}))

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
                    mark-dirty)})
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
                    mark-dirty)})
      {:state state})))

(defn- handle-cue-chain-update-effect-param
  "Update a parameter in a preset's effect.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :preset-path - Path to the preset
   - :effect-idx - Index of the effect
   - :param-key - Parameter key
   - :value or :fx/event - New value"
  [{:keys [col row preset-path effect-idx param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))
        items-vec (vec (get-in state (cue-chain-path col row) []))
        effect-path (conj (vec preset-path) :effects effect-idx :params param-key)
        updated-items (assoc-in items-vec effect-path value)]
    {:state (assoc-in state (cue-chain-path col row) updated-items)}))

(defn- handle-cue-chain-set-preset-tab
  "Set the active preset bank tab.
   Event keys:
   - :tab-id - Tab ID (e.g., :geometric, :wave, :beam, :abstract)"
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:cue-chain-editor :active-preset-tab] tab-id)})

(defn- handle-cue-chain-start-rename
  "Start renaming a group.
   Event keys:
   - :path - Path to the group"
  [{:keys [path state]}]
  {:state (assoc-in state [:cue-chain-editor :renaming-path] path)})

(defn- handle-cue-chain-rename-group
  "Rename a group.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :path - Path to the group
   - :name - New name"
  [{:keys [col row path name state]}]
  (let [items-vec (get-in state (cue-chain-path col row) [])
        item (get-in items-vec path)]
    (if (chains/group? item)
      {:state (-> state
                  (assoc-in (into (cue-chain-path col row) (conj path :name)) name)
                  (assoc-in [:cue-chain-editor :renaming-path] nil)
                  mark-dirty)}
      {:state state})))

(defn- handle-cue-chain-cancel-rename
  "Cancel renaming a group."
  [{:keys [state]}]
  {:state (assoc-in state [:cue-chain-editor :renaming-path] nil)})


;; Cue Chain ID-Based Selection and Manipulation Events
;; These handlers resolve stable item IDs to ephemeral paths at handling time,
;; fixing the stale closure problem where drag-and-drop handlers captured
;; outdated paths from node creation time.


(defn- handle-cue-chain-select-item-by-id
  "Select an item by its ID (ID-based selection for stable references).
   Resolves the ID to a path at handling time.
   Event keys:
   - :item-id - UUID of the item to select
   - :col, :row - Grid cell coordinates
   - :ctrl? - Toggle selection
   - :shift? - Range select"
  [{:keys [item-id col row ctrl? shift? state]}]
  (let [items-vec (get-in state (cue-chain-path col row) [])
        ;; Resolve ID to path at handling time (fresh lookup)
        path (chains/find-path-by-id items-vec item-id)]
    (if path
      ;; Delegate to existing path-based handler
      (handle-cue-chain-select-item {:path path :col col :row row :ctrl? ctrl? :shift? shift? :state state})
      ;; Item not found - no-op
      {:state state})))

(defn- handle-cue-chain-start-drag-by-id
  "Start a drag operation by item ID.
   Resolves to selected IDs for multi-drag.
   Event keys:
   - :initiating-id - UUID of the item being dragged
   - :col, :row - Grid cell coordinates"
  [{:keys [initiating-id col row state]}]
  (let [selected-ids (get-in state [:cue-chain-editor :selected-ids] #{})
        ;; If dragging a selected item, drag all selected items
        ;; Otherwise, select just this item and drag it
        dragging-ids (if (contains? selected-ids initiating-id)
                       selected-ids
                       #{initiating-id})
        ;; If dragging unselected item, update selection to just that item
        new-selected-ids (if (contains? selected-ids initiating-id)
                           selected-ids
                           #{initiating-id})]
    {:state (-> state
                (assoc-in [:cue-chain-editor :dragging-ids] dragging-ids)
                (assoc-in [:cue-chain-editor :selected-ids] new-selected-ids))}))

(defn- handle-cue-chain-update-drag-ui-state
  "Update drag/drop UI state (for visual feedback).
   Event keys:
   - :col, :row - Grid cell coordinates
   - :updates - Map of state updates (e.g., {:drop-target-id uuid, :drop-position :before})"
  [{:keys [col row updates state]}]
  {:state (update-in state [:cue-chain-editor] merge updates)})

(defn- handle-cue-chain-toggle-collapse-by-id
  "Toggle a group's collapsed state by ID.
   Event keys:
   - :item-id - UUID of the group
   - :col, :row - Grid cell coordinates"
  [{:keys [item-id col row state]}]
  (let [items-vec (get-in state (cue-chain-path col row) [])
        path (chains/find-path-by-id items-vec item-id)]
    (if path
      (handle-cue-chain-toggle-collapse {:col col :row row :path path :state state})
      {:state state})))

(defn- handle-cue-chain-ungroup-by-id
  "Ungroup a group by ID.
   Event keys:
   - :item-id - UUID of the group
   - :col, :row - Grid cell coordinates"
  [{:keys [item-id col row state]}]
  (let [items-vec (get-in state (cue-chain-path col row) [])
        path (chains/find-path-by-id items-vec item-id)]
    (if path
      (handle-cue-chain-ungroup {:col col :row row :path path :state state})
      {:state state})))

(defn- handle-cue-chain-start-rename-by-id
  "Start renaming an item by ID.
   Event keys:
   - :item-id - UUID of the item
   - :col, :row - Grid cell coordinates"
  [{:keys [item-id col row state]}]
  {:state (assoc-in state [:cue-chain-editor :renaming-id] item-id)})

(defn- handle-cue-chain-rename-item-by-id
  "Rename an item by ID.
   Event keys:
   - :item-id - UUID of the item
   - :col, :row - Grid cell coordinates
   - :new-name - New name for the item"
  [{:keys [item-id col row new-name state]}]
  (let [items-vec (get-in state (cue-chain-path col row) [])
        path (chains/find-path-by-id items-vec item-id)
        item (when path (get-in items-vec path))]
    (if (and path (chains/group? item))
      {:state (-> state
                  (assoc-in (into (cue-chain-path col row) (conj path :name)) new-name)
                  (assoc-in [:cue-chain-editor :renaming-id] nil)
                  mark-dirty)}
      {:state (assoc-in state [:cue-chain-editor :renaming-id] nil)})))

(defn- handle-cue-chain-set-enabled-by-id
  "Set enabled state of an item by ID.
   Event keys:
   - :item-id - UUID of the item
   - :col, :row - Grid cell coordinates
   - :enabled? - New enabled state"
  [{:keys [item-id col row enabled? state]}]
  (let [items-vec (get-in state (cue-chain-path col row) [])
        path (chains/find-path-by-id items-vec item-id)]
    (if path
      {:state (-> state
                  (assoc-in (into (cue-chain-path col row) (conj path :enabled?)) enabled?)
                  mark-dirty)}
      {:state state})))


(defn- handle-cue-chain-create-group
  "Create a new empty group.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :name (optional) - Group name"
  [{:keys [col row name state]}]
  (let [group-name (or name "New Group")
        ensure-cell (fn [s]
                      (if (get-in s [:grid :cells [col row] :cue-chain])
                        s
                        (assoc-in s [:grid :cells [col row]] {:cue-chain {:items []}})))
        new-group (make-group group-name [])]
    {:state (-> state
                ensure-cell
                (update-in (cue-chain-path col row) conj new-group)
                mark-dirty)}))


;; NEW Simplified Handlers for Hierarchical List Refactor
;; These handlers support the callback-based hierarchical-list component
;; which manages its own internal state and just calls these to persist changes.


(defn- handle-effects-set-chain
  "Set the entire effect chain for a cell (simple persistence callback).
   Called by hierarchical-list component's :on-items-changed callback.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :items - New items vector"
  [{:keys [col row items state]}]
  (let [ensure-cell (fn [s]
                      (if (get-in s [:effects :cells [col row]])
                        s
                        (assoc-in s [:effects :cells [col row]] {:effects [] :active true})))]
    {:state (-> state
                ensure-cell
                (assoc-in [:effects :cells [col row] :effects] items)
                mark-dirty)}))

(defn- handle-effects-update-selection
  "Update the selection state for the effect chain editor.
   Called by hierarchical-list component's :on-selection-changed callback.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :selected-ids - Set of selected item IDs"
  [{:keys [col row selected-ids state]}]
  {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :selected-ids] selected-ids)})

(defn- handle-cue-chain-set-items
  "Set the entire cue chain items for a cell (simple persistence callback).
   Called by hierarchical-list component's :on-items-changed callback.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :items - New items vector"
  [{:keys [col row items state]}]
  (let [ensure-cell (fn [s]
                      (if (get-in s [:grid :cells [col row] :cue-chain])
                        s
                        (assoc-in s [:grid :cells [col row]] {:cue-chain {:items []}})))]
    {:state (-> state
                ensure-cell
                (assoc-in (cue-chain-path col row) items)
                mark-dirty)}))

(defn- handle-cue-chain-set-clipboard
  "Set the cue chain clipboard.
   Called by hierarchical-list component's :on-copy callback.
   Event keys:
   - :items - Items to copy to clipboard"
  [{:keys [items state]}]
  {:state (assoc-in state [:cue-chain-editor :clipboard] items)})

(defn- handle-cue-chain-update-selection
  "Update the selection state for the cue chain editor.
   Called by hierarchical-list component's :on-selection-changed callback.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :selected-ids - Set of selected item IDs"
  [{:keys [col row selected-ids state]}]
  {:state (assoc-in state [:cue-chain-editor :selected-ids] selected-ids)})

(defn- handle-projectors-set-effects
  "Set the entire effects chain for a projector (simple persistence callback).
   Called by hierarchical-list component's :on-items-changed callback.
   Event keys:
   - :projector-id - ID of the projector
   - :effects - New effects vector"
  [{:keys [projector-id effects state]}]
  {:state (-> state
              (assoc-in (projector-effects-path projector-id) effects)
              mark-dirty)})

(defn- handle-projectors-update-effect-selection
  "Update the selection state for projector effects.
   Called by hierarchical-list component's :on-selection-changed callback.
   Event keys:
   - :projector-id - ID of the projector
   - :selected-ids - Set of selected item IDs"
  [{:keys [projector-id selected-ids state]}]
  (let [ui-path (projector-ui-state-path projector-id)]
    {:state (assoc-in state (conj ui-path :selected-ids) selected-ids)}))


;; File Menu Events


(defn- handle-file-new-project
  "Create a new project (TODO: Implement confirmation dialog if dirty)."
  [{:keys [state]}]
  (log/debug "File > New Project")
  ;; TODO: Show confirmation dialog if project is dirty
  ;; For now, just log
  {:state state})

(defn- handle-file-open
  "Open a project from disk (TODO: Implement file dialog)."
  [{:keys [state]}]
  (log/debug "File > Open")
  ;; TODO: Show file chooser dialog
  ;; :file/open-dialog effect to be implemented
  {:state state})

(defn- handle-file-save
  "Save the current project (TODO: Implement save logic)."
  [{:keys [state]}]
  (log/debug "File > Save")
  ;; TODO: Implement actual save to disk
  ;; For now, just mark as clean
  {:state (-> state
              (assoc-in [:project :dirty?] false)
              (assoc-in [:project :last-saved] (System/currentTimeMillis)))})

(defn- handle-file-save-as
  "Save the project to a new location (TODO: Implement file dialog)."
  [{:keys [state]}]
  (log/debug "File > Save As")
  ;; TODO: Show file chooser dialog and save
  {:state state})

(defn- handle-file-export
  "Export project data (TODO: Implement export dialog)."
  [{:keys [state]}]
  (log/debug "File > Export")
  ;; TODO: Show export options dialog
  {:state state})

(defn- handle-file-exit
  "Exit the application."
  [{:keys [state]}]
  (log/debug "File > Exit")
  ;; TODO: Show confirmation dialog if project is dirty
  ;; For now, just exit
  {:system/exit true})


;; Edit Menu Events


(defn- handle-edit-undo
  "Undo the last action (TODO: Implement undo stack)."
  [{:keys [state]}]
  (log/debug "Edit > Undo")
  ;; TODO: Implement undo/redo system
  {:state state})

(defn- handle-edit-redo
  "Redo the last undone action (TODO: Implement redo stack)."
  [{:keys [state]}]
  (log/debug "Edit > Redo")
  ;; TODO: Implement undo/redo system
  {:state state})

(defn- handle-edit-copy
  "Copy selected cell to clipboard."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (case active-tab
          :grid (handle-grid-copy-cell {:col col :row row :state state})
          :effects (handle-effects-copy-cell {:col col :row row :state state})
          {:state state}))
      (do
        (log/debug "Edit > Copy: No cell selected")
        {:state state}))))

(defn- handle-edit-paste
  "Paste clipboard to selected cell."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (case active-tab
          :grid (handle-grid-paste-cell {:col col :row row :state state})
          :effects (handle-effects-paste-cell {:col col :row row :state state})
          {:state state}))
      (do
        (log/debug "Edit > Paste: No cell selected")
        {:state state}))))

(defn- handle-edit-clear-cell
  "Clear the selected cell."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (case active-tab
          :grid (handle-grid-clear-cell {:col col :row row :state state})
          :effects (handle-effects-clear-cell {:col col :row row :state state})
          {:state state}))
      (do
        (log/debug "Edit > Clear Cell: No cell selected")
        {:state state}))))


;; View Menu Events


(defn- handle-view-toggle-preview
  "Toggle preview panel visibility (TODO: Implement preview toggle)."
  [{:keys [state]}]
  (log/debug "View > Toggle Preview")
  ;; TODO: Implement preview panel show/hide
  {:state (update-in state [:ui :preview-visible?] not)})

(defn- handle-view-fullscreen
  "Toggle fullscreen mode (TODO: Implement fullscreen)."
  [{:keys [state]}]
  (log/debug "View > Fullscreen")
  ;; TODO: Implement fullscreen toggle via JavaFX stage
  {:state (update-in state [:ui :fullscreen?] not)})


;; Help Menu Events


(defn- handle-help-documentation
  "Open documentation in browser."
  [{:keys [state]}]
  (log/debug "Help > Documentation")
  ;; TODO: Open URL in system browser
  {:state state
   :system/open-url "https://github.com/your-repo/docs"})

(defn- handle-help-about
  "Show About dialog."
  [{:keys [state]}]
  (log/debug "Help > About")
  {:state (assoc-in state [:ui :dialogs :about :open?] true)})

(defn- handle-help-check-updates
  "Check for application updates."
  [{:keys [state]}]
  (log/debug "Help > Check for Updates")
  ;; TODO: Implement update check
  {:state state})


;; Main Event Handler


(defn handle-event
  "Main event handler - PURE FUNCTION.
   
   Input: Event map with :event/type and co-effects (:state, :time)
   Output: Effects map (:state, :dispatch, custom effects)
   
   This is the central event dispatcher. All events flow through here."
  [{:keys [event/type] :as event}]
  (case type
    ;; Grid events
    :grid/cell-clicked (handle-grid-cell-clicked event)
    :grid/trigger-cell (handle-grid-trigger-cell event)
    :grid/select-cell (handle-grid-select-cell event)
    :grid/deselect-cell (handle-grid-deselect-cell event)
    :grid/set-cell-preset (handle-grid-set-cell-preset event)
    :grid/clear-cell (handle-grid-clear-cell event)
    :grid/move-cell (handle-grid-move-cell event)
    :grid/copy-cell (handle-grid-copy-cell event)
    :grid/paste-cell (handle-grid-paste-cell event)
    
    ;; Effects events
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
    
    ;; Effect chain editor multi-select events
    :effects/select-effect (handle-effects-select-effect event)
    :effects/toggle-effect-selection (handle-effects-toggle-effect-selection event)
    :effects/range-select (handle-effects-range-select event)
    :effects/select-all (handle-effects-select-all event)
    :effects/clear-selection (handle-effects-clear-selection event)
    :effects/copy-selected (handle-effects-copy-selected event)
    :effects/paste-into-chain (handle-effects-paste-into-chain event)
    :effects/insert-pasted (handle-effects-insert-pasted event)
    :effects/delete-selected (handle-effects-delete-selected event)
    
    ;; Custom parameter UI events
    :effects/set-param-ui-mode (handle-effects-set-param-ui-mode event)
    :effects/update-spatial-params (handle-effects-update-spatial-params event)
    
    ;; Effect chain group events
    :effects/create-empty-group (handle-effects-create-empty-group event)
    :effects/group-selected (handle-effects-group-selected event)
    :effects/toggle-group-collapse (handle-effects-toggle-group-collapse event)
    :effects/rename-group (handle-effects-rename-group event)
    :effects/start-rename-group (handle-effects-start-rename-group event)
    :effects/cancel-rename-group (handle-effects-cancel-rename-group event)
    :effects/ungroup (handle-effects-ungroup event)
    :effects/set-item-enabled-at-path (handle-effects-set-item-enabled-at-path event)
    :effects/move-item (handle-effects-move-item event)
    :effects/select-item-at-path (handle-effects-select-item-at-path event)
    :effects/select-item-by-id (handle-effects-select-item-by-id event)
    :effects/start-drag-by-id (handle-effects-start-drag-by-id event)
    :effects/update-drag-ui-state (handle-effects-update-drag-ui-state event)
    :effects/toggle-collapse-by-id (handle-effects-toggle-collapse-by-id event)
    :effects/ungroup-by-id (handle-effects-ungroup-by-id event)
    :effects/start-rename-by-id (handle-effects-start-rename-by-id event)
    :effects/rename-item-by-id (handle-effects-rename-item-by-id event)
    :effects/set-enabled-by-id (handle-effects-set-enabled-by-id event)
    :effects/delete-at-paths (handle-effects-delete-at-paths event)
    
    ;; Multi-select drag-and-drop events
    :effects/start-multi-drag (handle-effects-start-multi-drag event)
    :effects/move-items (handle-effects-move-items event)
    
    ;; NEW: Simplified effect chain handlers (hierarchical-list refactor)
    :effects/set-chain (handle-effects-set-chain event)
    :effects/update-selection (handle-effects-update-selection event)
    
    ;; Timing events
    :timing/set-bpm (handle-timing-set-bpm event)
    :timing/tap-tempo (handle-timing-tap-tempo event)
    :timing/clear-taps (handle-timing-clear-taps event)
    :timing/set-quantization (handle-timing-set-quantization event)
    
    ;; Transport events
    :transport/play (handle-transport-play event)
    :transport/stop (handle-transport-stop event)
    :transport/retrigger (handle-transport-retrigger event)
    
    ;; UI events
    :ui/set-active-tab (handle-ui-set-active-tab event)
    :ui/select-preset (handle-ui-select-preset event)
    :ui/open-dialog (handle-ui-open-dialog event)
    :ui/close-dialog (handle-ui-close-dialog event)
    :ui/update-dialog-data (handle-ui-update-dialog-data event)
    :ui/start-drag (handle-ui-start-drag event)
    :ui/end-drag (handle-ui-end-drag event)
    
    ;; Project events
    :project/mark-dirty (handle-project-mark-dirty event)
    :project/mark-clean (handle-project-mark-clean event)
    :project/set-folder (handle-project-set-folder event)
    
    ;; IDN events
    :idn/connect (handle-idn-connect event)
    :idn/connected (handle-idn-connected event)
    :idn/connection-failed (handle-idn-connection-failed event)
    :idn/disconnect (handle-idn-disconnect event)
    
    ;; Config events
    :config/update (handle-config-update event)
    
    ;; Projector events
    :projectors/scan-network (handle-projectors-scan-network event)
    :projectors/scan-complete (handle-projectors-scan-complete event)
    :projectors/scan-failed (handle-projectors-scan-failed event)
    :projectors/add-device (handle-projectors-add-device event)
    :projectors/add-service (handle-projectors-add-service event)
    :projectors/add-all-services (handle-projectors-add-all-services event)
    :projectors/add-manual (handle-projectors-add-manual event)
    :projectors/select-projector (handle-projectors-select-projector event)
    :projectors/remove-projector (handle-projectors-remove-projector event)
    :projectors/update-settings (handle-projectors-update-settings event)
    :projectors/toggle-enabled (handle-projectors-toggle-enabled event)
    :projectors/add-effect (handle-projectors-add-effect event)
    :projectors/remove-effect (handle-projectors-remove-effect event)
    :projectors/update-effect-param (handle-projectors-update-effect-param event)
    :projectors/reorder-effects (handle-projectors-reorder-effects event)
    :projectors/set-test-pattern (handle-projectors-set-test-pattern event)
    :projectors/set-broadcast-address (handle-projectors-set-broadcast-address event)
    :projectors/toggle-device-expand (handle-projectors-toggle-device-expand event)
    :projectors/update-connection-status (handle-projectors-update-connection-status event)
    :projectors/select-effect (handle-projectors-select-effect event)
    :projectors/update-corner-pin (handle-projectors-update-corner-pin event)
    :projectors/reset-corner-pin (handle-projectors-reset-corner-pin event)
    
    ;; Projector effect chain events (path-based selection for shared sidebar)
    :projectors/select-all-effects (handle-projectors-select-all-effects event)
    :projectors/clear-effect-selection (handle-projectors-clear-effect-selection event)
    :projectors/copy-effects (handle-projectors-copy-effects event)
    :projectors/paste-effects (handle-projectors-paste-effects event)
    :projectors/insert-pasted-effects (handle-projectors-insert-pasted-effects event)
    :projectors/delete-effects (handle-projectors-delete-effects event)
    :projectors/start-effect-drag (handle-projectors-start-effect-drag event)
    :projectors/move-effects (handle-projectors-move-effects event)
    :projectors/update-effect-ui-state (handle-projectors-update-effect-ui-state event)
    :projectors/group-effects (handle-projectors-group-effects event)
    :projectors/ungroup-effects (handle-projectors-ungroup-effects event)
    :projectors/create-effect-group (handle-projectors-create-effect-group event)
    :projectors/toggle-effect-group-collapse (handle-projectors-toggle-effect-group-collapse event)
    :projectors/start-rename-effect-group (handle-projectors-start-rename-effect-group event)
    :projectors/rename-effect-group (handle-projectors-rename-effect-group event)
    :projectors/cancel-rename-effect-group (handle-projectors-cancel-rename-effect-group event)
    :projectors/set-effect-enabled (handle-projectors-set-effect-enabled-at-path event)
    :projectors/add-calibration-effect (handle-projectors-add-calibration-effect event)
    
    ;; NEW: Simplified projector handlers (hierarchical-list refactor)
    :projectors/set-effects (handle-projectors-set-effects event)
    :projectors/update-effect-selection (handle-projectors-update-effect-selection event)
    
    ;; Cue chain events
    :cue-chain/open-editor (handle-cue-chain-open-editor event)
    :cue-chain/close-editor (handle-cue-chain-close-editor event)
    :cue-chain/add-preset (handle-cue-chain-add-preset event)
    :cue-chain/remove-items (handle-cue-chain-remove-items event)
    :cue-chain/move-items (handle-cue-chain-move-items event)
    :cue-chain/select-item (handle-cue-chain-select-item event)
    :cue-chain/select-item-at-path (handle-cue-chain-select-item event)
    :cue-chain/select-item-by-id (handle-cue-chain-select-item-by-id event)
    :cue-chain/start-drag-by-id (handle-cue-chain-start-drag-by-id event)
    :cue-chain/update-drag-ui-state (handle-cue-chain-update-drag-ui-state event)
    :cue-chain/toggle-collapse-by-id (handle-cue-chain-toggle-collapse-by-id event)
    :cue-chain/ungroup-by-id (handle-cue-chain-ungroup-by-id event)
    :cue-chain/start-rename-by-id (handle-cue-chain-start-rename-by-id event)
    :cue-chain/rename-item-by-id (handle-cue-chain-rename-item-by-id event)
    :cue-chain/set-enabled-by-id (handle-cue-chain-set-enabled-by-id event)
    :cue-chain/select-all (handle-cue-chain-select-all event)
    :cue-chain/clear-selection (handle-cue-chain-clear-selection event)
    :cue-chain/start-drag (handle-cue-chain-start-drag event)
    :cue-chain/copy-selected (handle-cue-chain-copy-selected event)
    :cue-chain/paste (handle-cue-chain-paste event)
    :cue-chain/group-selected (handle-cue-chain-group-selected event)
    :cue-chain/ungroup (handle-cue-chain-ungroup event)
    :cue-chain/toggle-collapse (handle-cue-chain-toggle-collapse event)
    :cue-chain/set-item-enabled (handle-cue-chain-set-item-enabled event)
    :cue-chain/update-preset-param (handle-cue-chain-update-preset-param event)
    :cue-chain/add-preset-effect (handle-cue-chain-add-preset-effect event)
    :cue-chain/remove-preset-effect (handle-cue-chain-remove-preset-effect event)
    :cue-chain/update-effect-param (handle-cue-chain-update-effect-param event)
    :cue-chain/set-preset-tab (handle-cue-chain-set-preset-tab event)
    :cue-chain/start-rename (handle-cue-chain-start-rename event)
    :cue-chain/start-rename-group (handle-cue-chain-start-rename event)
    :cue-chain/rename-group (handle-cue-chain-rename-group event)
    :cue-chain/cancel-rename (handle-cue-chain-cancel-rename event)
    :cue-chain/cancel-rename-group (handle-cue-chain-cancel-rename event)
    :cue-chain/create-group (handle-cue-chain-create-group event)
    :cue-chain/create-empty-group (handle-cue-chain-create-group event)
    :cue-chain/toggle-group-collapse (handle-cue-chain-toggle-collapse event)
    :cue-chain/delete-selected (handle-cue-chain-remove-items event)
    :cue-chain/paste-items (handle-cue-chain-paste event)
    :cue-chain/start-multi-drag (handle-cue-chain-start-drag event)
    :cue-chain/add-effect-to-preset (handle-cue-chain-add-preset-effect event)
    :cue-chain/remove-effect (handle-cue-chain-remove-preset-effect event)
    :cue-chain/clear-drag-state {:state (assoc-in (:state event) [:cue-chain-editor :dragging-paths] nil)}
    :cue-chain/update-drop-position {:state (:state event)}  ;; UI feedback only
    :cue-chain/set-drop-target {:state (:state event)}  ;; UI feedback only
    :cue-chain/select-effect {:state (:state event)}  ;; TODO: Implement effect selection
    :cue-chain/set-effect-enabled (handle-cue-chain-set-item-enabled event)
    :cue-chain/update-preset-color (handle-cue-chain-update-preset-color event)
    :cue-chain/update-preset-param-from-text (handle-cue-chain-update-preset-param event)
    
    ;; NEW: Simplified cue chain handlers (hierarchical-list refactor)
    :cue-chain/set-items (handle-cue-chain-set-items event)
    :cue-chain/set-clipboard (handle-cue-chain-set-clipboard event)
    :cue-chain/update-selection (handle-cue-chain-update-selection event)
    
    ;; File menu events
    :file/new-project (handle-file-new-project event)
    :file/open (handle-file-open event)
    :file/save (handle-file-save event)
    :file/save-as (handle-file-save-as event)
    :file/export (handle-file-export event)
    :file/exit (handle-file-exit event)
    
    ;; Edit menu events
    :edit/undo (handle-edit-undo event)
    :edit/redo (handle-edit-redo event)
    :edit/copy (handle-edit-copy event)
    :edit/paste (handle-edit-paste event)
    :edit/clear-cell (handle-edit-clear-cell event)
    
    ;; View menu events
    :view/toggle-preview (handle-view-toggle-preview event)
    :view/fullscreen (handle-view-fullscreen event)
    
    ;; Help menu events
    :help/documentation (handle-help-documentation event)
    :help/about (handle-help-about event)
    :help/check-updates (handle-help-check-updates event)
    
    ;; Unknown event
    (do
      (log/warn "Unknown event type:" type)
      (log/debug "  Full event:" (dissoc event :state))
      {})))
