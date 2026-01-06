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
   [laser-show.animation.chains :as chains]
   [laser-show.events.chain-handlers :as chain-handlers]))


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
    (chains/group? item)
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
        (if (chains/group? item)
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
          (if (chains/group? item)
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

;; REMOVED: handle-grid-set-cell-preset
;; Use :cue-chain/add-preset event instead for adding presets to cells
;; This consolidates all cue content to [:chains :cue-chains] structure

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
                mark-dirty)}))

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
                  mark-dirty)}
      {:state state})))

(defn- handle-grid-copy-cell
  "Copy a cell's cue chain to clipboard."
  [{:keys [col row state]}]
  (let [cue-chain-data (get-in state [:chains :cue-chains [col row]])]
    {:state (assoc-in state [:ui :clipboard]
                      {:type :cue-chain
                       :data cue-chain-data})}))

(defn- handle-grid-paste-cell
  "Paste clipboard cue chain to a cell."
  [{:keys [col row state]}]
  (let [clipboard (get-in state [:ui :clipboard])]
    (if (and clipboard (= :cue-chain (:type clipboard)))
      {:state (-> state
                  (assoc-in [:chains :cue-chains [col row]] (:data clipboard))
                  mark-dirty)}
      {:state state})))


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
                mark-dirty)}))

(defn- handle-effects-set-effect-enabled
  "Set an individual effect's enabled state within the chain.
   The enabled value comes from the checkbox's :fx/event."
  [{:keys [col row effect-idx state] :as event}]
  (let [enabled? (:fx/event event)]
    {:state (-> state
                (assoc-in [:chains :effect-chains [col row] :items effect-idx :enabled?] enabled?)
                mark-dirty)}))

(defn- handle-effects-remove-effect
  "Remove an effect from a cell's chain by index."
  [{:keys [col row effect-idx state]}]
  (let [effects-vec (get-in state [:chains :effect-chains [col row] :items] [])
        new-effects (vec (concat (subvec effects-vec 0 effect-idx)
                                 (subvec effects-vec (inc effect-idx))))]
    {:state (-> state
                (assoc-in [:chains :effect-chains [col row] :items] new-effects)
                mark-dirty)}))

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
              mark-dirty)})

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
                mark-dirty)}))

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
                  mark-dirty)}
      {:state state})))

(defn- handle-effects-move-cell
  "Move an effects cell from one position to another."
  [{:keys [from-col from-row to-col to-row state]}]
  (let [cell-data (get-in state [:chains :effect-chains [from-col from-row]])]
    (if cell-data
      {:state (-> state
                  (update-in [:chains :effect-chains] dissoc [from-col from-row])
                  (assoc-in [:chains :effect-chains [to-col to-row]] cell-data)
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
                mark-dirty)}))




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
  (let [config (chain-handlers/chain-config :effect-chains [col row])
        ensure-cell (fn [s]
                      (if (get-in s [:chains :effect-chains [col row]])
                        s
                        (assoc-in s [:chains :effect-chains [col row]] {:items [] :active true})))]
    {:state (-> state
                ensure-cell
                (chain-handlers/handle-create-empty-group config name))}))



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
    (when tab-id
      (log/debug "Tab clicked:" {:dialog-id dialog-id :tab-id tab-id}))
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
        default-effects [{:effect-id :rgb-curves
                          :id (random-uuid)
                          :enabled? true
                          :params {:r-curve-points [[0 0] [255 255]]
                                   :g-curve-points [[0 0] [255 255]]
                                   :b-curve-points [[0 0] [255 255]]}}
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
                          :output-config {:color-bit-depth 8
                                          :xy-bit-depth 16}
                          :status {:connected? false}}]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] default-effects)
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
        default-effects [{:effect-id :rgb-curves
                          :id (random-uuid)
                          :enabled? true
                          :params {:r-curve-points [[0 0] [255 255]]
                                   :g-curve-points [[0 0] [255 255]]
                                   :b-curve-points [[0 0] [255 255]]}}
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
                          :output-config {:color-bit-depth 8
                                          :xy-bit-depth 16}
                          :status {:connected? false}}]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] default-effects)
                (assoc-in [:projectors :active-projector] projector-id)
                mark-dirty)}))

(defn- handle-projectors-add-all-services
  "Add all services from a discovered device as configured projectors."
  [{:keys [device state]}]
  (let [host (:address device)
        services (:services device [])
        host-name (:host-name device)
        now (System/currentTimeMillis)
        ;; Build projector configs and effect chains separately
        projector-data (reduce
                        (fn [acc [idx service]]
                          (let [service-id (:service-id service)
                                service-name (:name service)
                                ;; Prefer service name, then host name, then IP address
                                name (or service-name host-name host)
                                projector-id (keyword (str "projector-" now "-" service-id "-" idx))
                                default-effects [{:effect-id :rgb-curves
                                                  :id (random-uuid)
                                                  :enabled? true
                                                  :params {:r-curve-points [[0 0] [255 255]]
                                                           :g-curve-points [[0 0] [255 255]]
                                                           :b-curve-points [[0 0] [255 255]]}}
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
                                                  :output-config {:color-bit-depth 8
                                                                  :xy-bit-depth 16}
                                                  :status {:connected? false}}]
                            (-> acc
                                (assoc-in [:projectors projector-id] projector-config)
                                ;; FIX: Wrap effects in {:items ...} to match expected structure
                                (assoc-in [:effects projector-id] {:items default-effects}))))
                        {:projectors {} :effects {}}
                        (map-indexed vector services))
        first-projector-id (first (keys (:projectors projector-data)))]
    {:state (-> state
                (update-in [:projectors :items] merge (:projectors projector-data))
                (update-in [:chains :projector-effects] merge (:effects projector-data))
                (assoc-in [:projectors :active-projector] first-projector-id)
                mark-dirty)}))

(defn- handle-projectors-add-manual
  "Add a projector manually (not from discovery)."
  [{:keys [name host port state]}]
  (let [projector-id (keyword (str "projector-" (System/currentTimeMillis)))
        default-effects [{:effect-id :rgb-curves
                          :id (random-uuid)
                          :enabled? true
                          :params {:r-curve-points [[0 0] [255 255]]
                                   :g-curve-points [[0 0] [255 255]]
                                   :b-curve-points [[0 0] [255 255]]}}
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
                          :output-config {:color-bit-depth 8
                                          :xy-bit-depth 16}
                          :status {:connected? false}}]
    {:state (-> state
                (assoc-in [:projectors :items projector-id] projector-config)
                (assoc-in [:chains :projector-effects projector-id :items] default-effects)
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
                (update-in [:chains :projector-effects projector-id :items] conj effect-with-fields)
                mark-dirty)}))

(defn- handle-projectors-remove-effect
  "Remove an effect from a projector's chain."
  [{:keys [projector-id effect-idx state]}]
  (let [effects-vec (get-in state [:chains :projector-effects projector-id :items] [])
        new-effects (vec (concat (subvec effects-vec 0 effect-idx)
                                 (subvec effects-vec (inc effect-idx))))]
    {:state (-> state
                (assoc-in [:chains :projector-effects projector-id :items] new-effects)
                mark-dirty)}))

(defn- handle-projectors-update-effect-param
  "Update a parameter in a projector's effect."
  [{:keys [projector-id effect-idx param-key state] :as event}]
  (let [value (or (:fx/event event) (:value event))]
    {:state (assoc-in state
                      [:chains :projector-effects projector-id :items effect-idx :params param-key]
                      value)}))

(defn- handle-projectors-reorder-effects
  "Reorder effects in a projector's chain."
  [{:keys [projector-id from-idx to-idx state]}]
  (let [effects-vec (get-in state [:chains :projector-effects projector-id :items] [])
        effect (nth effects-vec from-idx)
        without (vec (concat (subvec effects-vec 0 from-idx)
                             (subvec effects-vec (inc from-idx))))
        reordered (vec (concat (subvec without 0 to-idx)
                               [effect]
                               (subvec without to-idx)))]
    {:state (-> state
                (assoc-in [:chains :projector-effects projector-id :items] reordered)
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
                    (assoc-in [:chains :projector-effects projector-id :items effect-idx :params x-key] x)
                    (assoc-in [:chains :projector-effects projector-id :items effect-idx :params y-key] y))})
      {:state state})))

(defn- handle-projectors-reset-corner-pin
  "Reset corner pin effect to default values."
  [{:keys [projector-id effect-idx state]}]
  {:state (assoc-in state [:chains :projector-effects projector-id :items effect-idx :params]
                    {:tl-x -1.0 :tl-y 1.0
                     :tr-x 1.0 :tr-y 1.0
                     :bl-x -1.0 :bl-y -1.0
                     :br-x 1.0 :br-y -1.0})})


;; Projector Effect Chain Events (Path-Based Selection)
;; These handlers support the shared effect-chain-sidebar component
;; and use path-based selection similar to the grid cell effects handlers.


(defn- projector-effects-path
  "Get the path to a projector's effects in state.
   Uses new unified :chains domain structure."
  [projector-id]
  [:chains :projector-effects projector-id :items])

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
                      (let [all-paths (vec (chains/paths-in-chain effects-vec))
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
        all-paths (into #{} (chains/paths-in-chain effects-vec))
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
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-group-selected state config name)}))

(defn- handle-projectors-ungroup-effects
  "Ungroup a group in projector chain."
  [{:keys [projector-id path state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-ungroup state config path)}))

(defn- handle-projectors-create-effect-group
  "Create empty group in projector chain."
  [{:keys [projector-id state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-create-empty-group state config)}))

(defn- handle-projectors-toggle-effect-group-collapse
  "Toggle collapse state of a group in projector chain."
  [{:keys [projector-id path state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-toggle-collapse state config path)}))

(defn- handle-projectors-start-rename-effect-group
  "Start renaming a group in projector chain."
  [{:keys [projector-id path state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-start-rename state config path)}))

(defn- handle-projectors-rename-effect-group
  "Rename a group in projector chain."
  [{:keys [projector-id path name state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-rename-item state config path name)}))

(defn- handle-projectors-cancel-rename-effect-group
  "Cancel renaming a group in projector chain."
  [{:keys [projector-id state]}]
  (let [config (chain-handlers/chain-config :projector-effects projector-id)]
    {:state (chain-handlers/handle-cancel-rename state config)}))

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


;; Projector Curve Editor Events


(defn- handle-projectors-add-curve-point
  "Add a new control point to a projector's curve.
   Event keys:
   - :projector-id - ID of the projector
   - :effect-path - Path to effect (supports nested)
   - :channel - Channel keyword (:r, :g, or :b)
   - :x, :y - Point coordinates"
  [{:keys [projector-id effect-path channel x y state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-vec (vec (get-in state (projector-effects-path projector-id) []))
        current-points (get-in effects-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        new-point [(int x) (int y)]
        new-points (->> (conj current-points new-point)
                        (sort-by first)
                        vec)
        updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) new-points)]
    {:state (assoc-in state (projector-effects-path projector-id) updated-effects)}))

(defn- handle-projectors-update-curve-point
  "Update a control point in a projector's curve.
   Event keys:
   - :projector-id - ID of the projector
   - :effect-path - Path to effect (supports nested)
   - :channel - Channel keyword (:r, :g, or :b)
   - :point-idx - Index of the point to update
   - :x, :y - New coordinates"
  [{:keys [projector-id effect-path channel point-idx x y state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-vec (vec (get-in state (projector-effects-path projector-id) []))
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
    {:state (assoc-in state (projector-effects-path projector-id) updated-effects)}))

(defn- handle-projectors-remove-curve-point
  "Remove a control point from a projector's curve.
   Event keys:
   - :projector-id - ID of the projector
   - :effect-path - Path to effect (supports nested)
   - :channel - Channel keyword (:r, :g, or :b)
   - :point-idx - Index of the point to remove"
  [{:keys [projector-id effect-path channel point-idx state]}]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        effects-vec (vec (get-in state (projector-effects-path projector-id) []))
        current-points (get-in effects-vec (conj (vec effect-path) :params param-key) [[0 0] [255 255]])
        num-points (count current-points)
        ;; Cannot remove corner points (first and last)
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))]
    (if is-corner?
      {:state state}  ;; No change for corner points
      (let [updated-points (vec (concat (subvec current-points 0 point-idx)
                                        (subvec current-points (inc point-idx))))
            updated-effects (assoc-in effects-vec (conj (vec effect-path) :params param-key) updated-points)]
        {:state (assoc-in state (projector-effects-path projector-id) updated-effects)}))))

(defn- handle-projectors-set-active-curve-channel
  "Set the active curve channel (R/G/B) for the projector curve editor.
   Event keys:
   - :projector-id - ID of the projector
   - :effect-path - Path to effect
   - :tab-id - Channel keyword (:r, :g, or :b)"
  [{:keys [projector-id effect-path tab-id state]}]
  (log/debug "[CURVE TAB DEBUG] handle-projectors-set-active-curve-channel called:"
             "projector-id=" projector-id
             "effect-path=" effect-path
             "tab-id=" tab-id)
  (let [ui-path (projector-ui-state-path projector-id)
        full-path (conj ui-path :ui-modes effect-path :active-curve-channel)]
    (log/debug "[CURVE TAB DEBUG] Storing at path:" full-path)
    {:state (assoc-in state full-path tab-id)}))


;; Cue Chain Events
;; These handlers support the cue chain editor for building complex cues
;; by combining multiple presets with per-preset effect chains.


(defn- effects-chain-items-path
  "Get the path to effect chain items in state (new :chains domain)."
  [col row]
  [:chains :effect-chains [col row] :items])

(defn- effects-chain-metadata-path
  "Get the path to effect chain metadata in state (new :chains domain)."
  [col row]
  [:chains :effect-chains [col row]])

(defn- cue-chain-path
  "Get the path to a grid cell's cue chain in state.
   Uses new unified :chains domain structure."
  [col row]
  [:chains :cue-chains [col row] :items])

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
               mark-dirty)}))

(defn- handle-cue-chain-remove-items
  "Remove selected items from the cue chain.
   Event keys:
   - :col, :row - Grid cell coordinates"
  [{:keys [col row state]}]
  (let [config (chain-handlers/chain-config :cue-chains [col row])]
    {:state (chain-handlers/handle-delete-selected state config)}))

(defn- handle-cue-chain-move-items
  "Move items to a new position via drag-and-drop.
   Uses the centralized chains/move-items-to-target for correct ordering.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :target-id - ID of the target item
   - :drop-position - :before | :into"
  [{:keys [col row target-id drop-position state]}]
  (let [config (chain-handlers/chain-config :cue-chains [col row])]
    {:state (chain-handlers/handle-move-items state config target-id drop-position)}))



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

(defn- handle-cue-chain-set-effect-tab
  "Set the active effect bank tab.
   Event keys:
   - :tab-id - Tab ID (e.g., :shape, :color, :intensity)"
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:cue-chain-editor :active-effect-tab] (or tab-id :shape))})

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
                mark-dirty)}))

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
                mark-dirty)}))

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
                  mark-dirty)}
      {:state state})))



;; Cue Chain ID-Based Selection and Manipulation Events
;; These handlers resolve stable item IDs to ephemeral paths at handling time,
;; fixing the stale closure problem where drag-and-drop handlers captured
;; outdated paths from node creation time.




(defn- handle-cue-chain-create-group
  "Create a new empty group.
   Event keys:
   - :col, :row - Grid cell coordinates
   - :name (optional) - Group name"
  [{:keys [col row name state]}]
 (let [config (chain-handlers/chain-config :cue-chains [col row])
       ensure-cell (fn [s]
                     (if (get-in s [:chains :cue-chains [col row]])
                       s
                       (assoc-in s [:chains :cue-chains [col row]] {:items []})))]
   {:state (-> state
               ensure-cell
               (chain-handlers/handle-create-empty-group config name))}))


;; Cue Chain Item Effects Events
;; These handlers support using hierarchical-list for effects within cue chain items.


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
               mark-dirty)}))

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
            base-path (vec (concat effects-path effect-path [:params]))]
        {:state (-> state
                    (assoc-in (conj base-path x-key) x)
                    (assoc-in (conj base-path y-key) y))})
      {:state state})))

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


;; ============================================================================
;; Generic Chain Events
;; ============================================================================
;; These handlers use the unified :chains domain and config-driven approach.
;; They replace the domain-specific handlers above with generic implementations.


(defn- handle-chain-set-items
  "Generic set items handler for any chain type.
   
   Event keys:
   - :domain - Chain domain (:effect-chains, :cue-chains, :projector-effects)
   - :entity-key - Entity key ([col row] or projector-id)
   - :items - New items vector"
  [{:keys [domain entity-key items state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (-> state
                (assoc-in (:items-path config) items)
                mark-dirty)}))

(defn- handle-chain-update-selection
  "Generic update selection handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :selected-ids - Set of selected item IDs"
  [{:keys [domain entity-key selected-ids state]}]
  (let [config (chain-handlers/chain-config domain entity-key)
        ui-path (:ui-path config)]
    {:state (assoc-in state (conj ui-path :selected-ids) selected-ids)}))

(defn- handle-chain-add-curve-point
  "Generic add curve point handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :effect-path - Path to effect within chain
   - :channel - Curve channel (:r, :g, :b)
   - :x, :y - Point coordinates"
  [{:keys [domain entity-key effect-path channel x y state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-add-curve-point
              state config {:effect-path effect-path :channel channel :x x :y y})}))

(defn- handle-chain-update-curve-point
  "Generic update curve point handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :effect-path - Path to effect within chain
   - :channel - Curve channel (:r, :g, :b)
   - :point-idx - Index of point to update
   - :x, :y - New coordinates"
  [{:keys [domain entity-key effect-path channel point-idx x y state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-update-curve-point
              state config {:effect-path effect-path :channel channel :point-idx point-idx :x x :y y})}))

(defn- handle-chain-remove-curve-point
  "Generic remove curve point handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :effect-path - Path to effect within chain
   - :channel - Curve channel (:r, :g, :b)
   - :point-idx - Index of point to remove"
  [{:keys [domain entity-key effect-path channel point-idx state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-remove-curve-point
              state config {:effect-path effect-path :channel channel :point-idx point-idx})}))

(defn- handle-chain-set-active-curve-channel
  "Generic set active curve channel handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :effect-path - Path to effect within chain
   - :tab-id - Channel to activate (:r, :g, :b)"
  [{:keys [domain entity-key effect-path tab-id state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-set-active-curve-channel
              state config {:effect-path effect-path :tab-id tab-id})}))

(defn- handle-chain-update-spatial-params
  "Generic spatial parameter update handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :effect-path - Path to effect within chain
   - :point-id - ID of the dragged point
   - :x, :y - New coordinates
   - :param-map - Mapping of point IDs to parameter keys"
  [{:keys [domain entity-key effect-path point-id x y param-map state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-update-spatial-params
              state config {:effect-path effect-path :point-id point-id :x x :y y :param-map param-map})}))

(defn- handle-chain-select-item
  "Generic item selection handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :path - Path to select
   - :ctrl? - Toggle selection
   - :shift? - Range select"
  [{:keys [domain entity-key path ctrl? shift? state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-select-item state config path ctrl? shift?)}))

(defn- handle-chain-select-all
  "Generic select all handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key"
  [{:keys [domain entity-key state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-select-all state config)}))

(defn- handle-chain-clear-selection
  "Generic clear selection handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key"
  [{:keys [domain entity-key state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-clear-selection state config)}))

(defn- handle-chain-delete-selected
  "Generic delete selected handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key"
  [{:keys [domain entity-key state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-delete-selected state config)}))

(defn- handle-chain-group-selected
  "Generic group selected handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :name - Optional group name"
  [{:keys [domain entity-key name state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-group-selected state config name)}))

(defn- handle-chain-ungroup
  "Generic ungroup handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :path - Path to group"
  [{:keys [domain entity-key path state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-ungroup state config path)}))

(defn- handle-chain-toggle-collapse
  "Generic toggle collapse handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :path - Path to group"
  [{:keys [domain entity-key path state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-toggle-collapse state config path)}))

(defn- handle-chain-start-rename
  "Generic start rename handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :path - Path to item"
  [{:keys [domain entity-key path state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-start-rename state config path)}))

(defn- handle-chain-rename-item
  "Generic rename item handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :path - Path to item
   - :new-name - New name"
  [{:keys [domain entity-key path new-name state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-rename-item state config path new-name)}))

(defn- handle-chain-cancel-rename
  "Generic cancel rename handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key"
  [{:keys [domain entity-key state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-cancel-rename state config)}))

(defn- handle-chain-set-item-enabled
  "Generic set item enabled handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :path - Path to item
   - :enabled? - New enabled state"
  [{:keys [domain entity-key path enabled? state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-set-item-enabled state config path enabled?)}))

(defn- handle-chain-start-drag
  "Generic start drag handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :initiating-path - Path of item being dragged"
  [{:keys [domain entity-key initiating-path state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-start-drag state config initiating-path)}))

(defn- handle-chain-move-items
  "Generic move items handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key
   - :target-id - ID of target item
   - :drop-position - :before or :into"
  [{:keys [domain entity-key target-id drop-position state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-move-items state config target-id drop-position)}))

(defn- handle-chain-clear-drag-state
  "Generic clear drag state handler for any chain type.
   
   Event keys:
   - :domain - Chain domain
   - :entity-key - Entity key"
  [{:keys [domain entity-key state]}]
  (let [config (chain-handlers/chain-config domain entity-key)]
    {:state (chain-handlers/handle-clear-drag-state state config)}))


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
    ;; :grid/set-cell-preset REMOVED - use :cue-chain/add-preset instead
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
    
    
    ;; Custom parameter UI events
    :effects/set-param-ui-mode (handle-effects-set-param-ui-mode event)
    :effects/update-spatial-params (handle-effects-update-spatial-params event)
    
    ;; Curve editor events
    :effects/add-curve-point (handle-effects-add-curve-point event)
    :effects/update-curve-point (handle-effects-update-curve-point event)
    :effects/remove-curve-point (handle-effects-remove-curve-point event)
    :effects/set-active-curve-channel (handle-effects-set-active-curve-channel event)
    
    ;; Effect chain group event (create empty group from toolbar)
    :effects/create-empty-group (handle-effects-create-empty-group event)
    
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
    
    :projectors/add-calibration-effect (handle-projectors-add-calibration-effect event)
    :projectors/update-effect-param-at-path (handle-projectors-update-effect-param-at-path event)
    :projectors/update-effect-param-from-text (handle-projectors-update-effect-param-from-text event)
    :projectors/set-effect-ui-mode (handle-projectors-set-effect-ui-mode event)
    
    ;; Projector curve editor events
    :projectors/add-curve-point (handle-projectors-add-curve-point event)
    :projectors/update-curve-point (handle-projectors-update-curve-point event)
    :projectors/remove-curve-point (handle-projectors-remove-curve-point event)
    :projectors/set-active-curve-channel (handle-projectors-set-active-curve-channel event)
    
    ;; Cue chain events
    :cue-chain/open-editor (handle-cue-chain-open-editor event)
    :cue-chain/close-editor (handle-cue-chain-close-editor event)
    :cue-chain/add-preset (handle-cue-chain-add-preset event)
    :cue-chain/remove-items (handle-cue-chain-remove-items event)
    :cue-chain/move-items (handle-cue-chain-move-items event)
    :cue-chain/update-preset-param (handle-cue-chain-update-preset-param event)
    :cue-chain/add-preset-effect (handle-cue-chain-add-preset-effect event)
    :cue-chain/remove-preset-effect (handle-cue-chain-remove-preset-effect event)
    :cue-chain/update-effect-param (handle-cue-chain-update-effect-param event)
    :cue-chain/set-preset-tab (handle-cue-chain-set-preset-tab event)
    :cue-chain/create-group (handle-cue-chain-create-group event)
    :cue-chain/create-empty-group (handle-cue-chain-create-group event)
    :cue-chain/update-preset-color (handle-cue-chain-update-preset-color event)
    :cue-chain/update-preset-param-from-text (handle-cue-chain-update-preset-param event)
    
    ;; NEW: Full effect editor for items (presets/groups)
    :cue-chain/set-effect-tab (handle-cue-chain-set-effect-tab event)
    :cue-chain/add-effect-to-item (handle-cue-chain-add-effect-to-item event)
    :cue-chain/remove-effect-from-item (handle-cue-chain-remove-effect-from-item event)
    :cue-chain/update-effect-param-from-text (handle-cue-chain-update-effect-param-from-text event)
    :cue-chain/select-item-effect (handle-cue-chain-select-item-effect event)
    :cue-chain/set-item-effect-enabled (handle-cue-chain-set-item-effect-enabled event)
    
    ;; NEW: Cue chain item effects handlers (hierarchical-list for effects within items)
    :cue-chain/set-item-effects (handle-cue-chain-set-item-effects event)
    :cue-chain/update-item-effect-selection (handle-cue-chain-update-item-effect-selection event)
    :cue-chain/set-item-effects-clipboard (handle-cue-chain-set-item-effects-clipboard event)
    :cue-chain/update-item-effect-param (handle-cue-chain-update-item-effect-param event)
    :cue-chain/set-item-effect-param-ui-mode (handle-cue-chain-set-item-effect-param-ui-mode event)
    :cue-chain/update-item-effect-spatial-params (handle-cue-chain-update-item-effect-spatial-params event)
    
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
    
    ;; Generic chain events (unified handlers for all chain types)
    :chain/set-items (handle-chain-set-items event)
    :chain/update-selection (handle-chain-update-selection event)
    :chain/add-curve-point (handle-chain-add-curve-point event)
    :chain/update-curve-point (handle-chain-update-curve-point event)
    :chain/remove-curve-point (handle-chain-remove-curve-point event)
    :chain/set-active-curve-channel (handle-chain-set-active-curve-channel event)
    :chain/update-spatial-params (handle-chain-update-spatial-params event)
    :chain/select-item (handle-chain-select-item event)
    :chain/select-all (handle-chain-select-all event)
    :chain/clear-selection (handle-chain-clear-selection event)
    :chain/delete-selected (handle-chain-delete-selected event)
    :chain/group-selected (handle-chain-group-selected event)
    :chain/ungroup (handle-chain-ungroup event)
    :chain/toggle-collapse (handle-chain-toggle-collapse event)
    :chain/start-rename (handle-chain-start-rename event)
    :chain/rename-item (handle-chain-rename-item event)
    :chain/cancel-rename (handle-chain-cancel-rename event)
    :chain/set-item-enabled (handle-chain-set-item-enabled event)
    :chain/start-drag (handle-chain-start-drag event)
    :chain/move-items (handle-chain-move-items event)
    :chain/clear-drag-state (handle-chain-clear-drag-state event)
    
    ;; Unknown event
    (do
      (log/warn "Unknown event type:" type)
      (log/debug "  Full event:" (dissoc event :state))
      {})))
