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
   [laser-show.common.util :as u]
   [laser-show.animation.effects :as effects])
  (:import [javafx.scene.input MouseButton]))


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


;; Grid Events


(defn- handle-grid-cell-clicked
  "Handle grid cell click - dispatches to trigger or select based on mouse button.
   Uses :fx/event to check which mouse button was clicked."
  [{:keys [col row has-content? state fx/event]}]
  (let [button (.getButton event)]
    (cond
      ;; Right click - select (for context menu later)
      (= button MouseButton/SECONDARY)
      {:state (assoc-in state [:grid :selected-cell] [col row])}
      
      ;; Left click on cell with content - trigger
      has-content?
      (let [now (current-time-ms {:time (System/currentTimeMillis)})]
        {:state (-> state
                    (assoc-in [:playback :active-cell] [col row])
                    (assoc-in [:playback :playing?] true)
                    (assoc-in [:playback :trigger-time] now))})
      
      ;; Left click on empty - select
      :else
      {:state (assoc-in state [:grid :selected-cell] [col row])})))

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
  "Select all effects in the current chain."
  [{:keys [col row state]}]
  (let [effects-count (count (get-in state [:effects :cells [col row] :effects] []))
        all-indices (into #{} (range effects-count))]
    {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices]
                      all-indices)}))

(defn- handle-effects-clear-selection
  "Clear all effect selection in chain editor."
  [{:keys [state]}]
  {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})})

(defn- handle-effects-copy-selected
  "Copy selected effects to clipboard.
   Supports both path-based selection (for groups) and legacy index-based selection."
  [{:keys [col row state]}]
  (let [selected-paths (get-in state [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
        selected-indices (get-in state [:ui :dialogs :effect-chain-editor :data :selected-effect-indices] #{})
        effects-vec (get-in state [:effects :cells [col row] :effects] [])
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
                        
                        :else [])]
    
    (cond-> {:state state}
      (seq valid-effects)
      (assoc :clipboard/copy-effects valid-effects))))

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
            ;; Extract items at those paths in order
            selected-items (mapv #(get-in effects-vec (vec %)) sorted-paths)
            ;; Create the new group
            new-group (make-group group-name selected-items)
            ;; Find insertion point - where the first selected item was (at top level)
            ;; For nested items, we insert at the top level where their root parent is
            first-path (first sorted-paths)
            insert-at (first first-path)  ;; Use top-level index
            ;; Remove selected items (deepest first to avoid index shifting)
            after-remove (reduce remove-at-path effects-vec
                                 (sort-by (comp - count) sorted-paths))
            ;; Insert the new group at the position where first item was
            ;; Need to adjust insert position if we removed items before it
            removed-before (count (filter #(< (first %) insert-at) sorted-paths))
            adjusted-insert (- insert-at removed-before)
            new-effects (vec (concat (subvec after-remove 0 adjusted-insert)
                                     [new-group]
                                     (subvec after-remove adjusted-insert)))
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
  (let [current-paths (get-in state [:ui :dialogs :effect-chain-editor :data :selected-paths] #{})
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
        update-anchor? (not shift?)]
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
  "Move multiple items to a new position.
   Items are moved in their visual order to maintain relative ordering.
   
   Event keys:
   - :col, :row - Grid cell coordinates
   - :target-id - ID of the target item (for finding drop location)
   - :drop-position - :before | :into (only :into for groups)"
  [{:keys [col row target-id drop-position state]}]
  (let [effects-vec (get-in state [:effects :cells [col row] :effects] [])
        dragging-paths (get-in state [:ui :dialogs :effect-chain-editor :data :dragging-paths] #{})
        
        ;; 1. Filter to root-level paths only (groups include their children)
        root-paths (filter-to-root-paths dragging-paths)
        
        ;; 2. Sort by visual order to maintain relative ordering
        all-paths (vec (effects/paths-in-chain effects-vec))
        sorted-paths (sort-by #(.indexOf all-paths %) root-paths)
        
        ;; 3. Check if target is in dragging paths or descendant (prevent self-drop)
        target-path (effects/find-path-by-id effects-vec target-id)
        target-in-drag? (or (contains? root-paths target-path)
                           (some #(path-is-ancestor? % target-path) root-paths))
        
        ;; 4. Extract items in order
        items-to-move (mapv #(get-in effects-vec %) sorted-paths)]
    
    (if (or (empty? items-to-move) target-in-drag?)
      ;; Invalid move - clear dragging state and return
      {:state (assoc-in state [:ui :dialogs :effect-chain-editor :data :dragging-paths] nil)}
      
      (let [;; 5. Remove from deepest first to avoid index shifting
            after-remove (reduce remove-at-path effects-vec
                                 (sort-by (comp - count) sorted-paths))
            
            ;; 6. Find target in modified chain and insert
            new-target-path (effects/find-path-by-id after-remove target-id)
            
            ;; Only use into-group? if target was actually found
            ;; If target wasn't found, we append to end and DON'T try to insert into a non-existent group
            target-found? (some? new-target-path)
            final-to-path (or new-target-path [(count after-remove)])
            final-into-group? (and target-found? (= drop-position :into))
            
            ;; 7. Insert items at target
            after-insert (insert-items-at after-remove final-to-path items-to-move final-into-group?)]
        
        {:state (-> state
                    (assoc-in [:effects :cells [col row] :effects] after-insert)
                    ;; Clear dragging state
                    (assoc-in [:ui :dialogs :effect-chain-editor :data :dragging-paths] nil)
                    ;; Update selection to new paths (items moved together stay selected)
                    ;; For now, clear selection - could compute new paths if needed
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


;; File Menu Events


(defn- handle-file-new-project
  "Create a new project (TODO: Implement confirmation dialog if dirty)."
  [{:keys [state]}]
  (println "File > New Project")
  ;; TODO: Show confirmation dialog if project is dirty
  ;; For now, just log
  {:state state})

(defn- handle-file-open
  "Open a project from disk (TODO: Implement file dialog)."
  [{:keys [state]}]
  (println "File > Open")
  ;; TODO: Show file chooser dialog
  ;; :file/open-dialog effect to be implemented
  {:state state})

(defn- handle-file-save
  "Save the current project (TODO: Implement save logic)."
  [{:keys [state]}]
  (println "File > Save")
  ;; TODO: Implement actual save to disk
  ;; For now, just mark as clean
  {:state (-> state
              (assoc-in [:project :dirty?] false)
              (assoc-in [:project :last-saved] (System/currentTimeMillis)))})

(defn- handle-file-save-as
  "Save the project to a new location (TODO: Implement file dialog)."
  [{:keys [state]}]
  (println "File > Save As")
  ;; TODO: Show file chooser dialog and save
  {:state state})

(defn- handle-file-export
  "Export project data (TODO: Implement export dialog)."
  [{:keys [state]}]
  (println "File > Export")
  ;; TODO: Show export options dialog
  {:state state})

(defn- handle-file-exit
  "Exit the application."
  [{:keys [state]}]
  (println "File > Exit")
  ;; TODO: Show confirmation dialog if project is dirty
  ;; For now, just exit
  {:system/exit true})


;; Edit Menu Events


(defn- handle-edit-undo
  "Undo the last action (TODO: Implement undo stack)."
  [{:keys [state]}]
  (println "Edit > Undo")
  ;; TODO: Implement undo/redo system
  {:state state})

(defn- handle-edit-redo
  "Redo the last undone action (TODO: Implement redo stack)."
  [{:keys [state]}]
  (println "Edit > Redo")
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
        (println "Edit > Copy: No cell selected")
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
        (println "Edit > Paste: No cell selected")
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
        (println "Edit > Clear Cell: No cell selected")
        {:state state}))))


;; View Menu Events


(defn- handle-view-toggle-preview
  "Toggle preview panel visibility (TODO: Implement preview toggle)."
  [{:keys [state]}]
  (println "View > Toggle Preview")
  ;; TODO: Implement preview panel show/hide
  {:state (update-in state [:ui :preview-visible?] not)})

(defn- handle-view-fullscreen
  "Toggle fullscreen mode (TODO: Implement fullscreen)."
  [{:keys [state]}]
  (println "View > Fullscreen")
  ;; TODO: Implement fullscreen toggle via JavaFX stage
  {:state (update-in state [:ui :fullscreen?] not)})


;; Help Menu Events


(defn- handle-help-documentation
  "Open documentation in browser."
  [{:keys [state]}]
  (println "Help > Documentation")
  ;; TODO: Open URL in system browser
  {:state state
   :system/open-url "https://github.com/your-repo/docs"})

(defn- handle-help-about
  "Show About dialog."
  [{:keys [state]}]
  (println "Help > About")
  {:state (assoc-in state [:ui :dialogs :about :open?] true)})

(defn- handle-help-check-updates
  "Check for application updates."
  [{:keys [state]}]
  (println "Help > Check for Updates")
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
    :effects/delete-at-paths (handle-effects-delete-at-paths event)
    
    ;; Multi-select drag-and-drop events
    :effects/start-multi-drag (handle-effects-start-multi-drag event)
    :effects/move-items (handle-effects-move-items event)
    
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
      (println "Unknown event type:" type)
      {})))
