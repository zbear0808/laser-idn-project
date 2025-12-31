(ns laser-show.services.effects-service
  "Effects service - orchestrates effect cell operations with underlying logic.
   
   This service provides high-level operations for the effects grid,
   coordinating between state management and underlying logic.
   
   All effect cell mutations should go through this service to ensure:
   - Proper validation
   - Project dirty tracking
   - Consistent coordination with other state
   
   The service layer contains underlying logic; state/core.clj remains thin accessors."
  (:require [laser-show.state.core :as state]
            [laser-show.state.queries :as queries]
            [laser-show.state.domains :as domains]
            [laser-show.animation.effects :as effects]))

;; ============================================================================
;; Validation Helpers
;; ============================================================================

(defn valid-position?
  "Check if a position is valid within the effects grid.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: true if valid, false otherwise"
  [col row]
  (let [cols domains/default-grid-cols
        rows domains/default-grid-rows]
    (and (integer? col) (integer? row)
         (>= col 0) (< col cols)
         (>= row 0) (< row rows))))

(defn valid-effect-id?
  "Check if an effect ID is valid (exists in registry).
   
   Parameters:
   - effect-id: The effect keyword to validate
   
   Returns: true if valid effect, false otherwise"
  [effect-id]
  (and (keyword? effect-id)
       (some? (effects/get-effect effect-id))))

(defn valid-effect-cell?
  "Check if effect cell data is valid.
   
   Parameters:
   - cell-data: Map with :effects vector and :active boolean
   
   Returns: true if valid, false otherwise"
  [cell-data]
  (and (map? cell-data)
       (vector? (:effects cell-data))
       (every? #(and (map? %)
                     (keyword? (:effect-id %)))
               (:effects cell-data))))

;; ============================================================================
;; Cell Read Operations
;; ============================================================================

(defn get-effect-cell
  "Get effect cell data.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: Effect cell data map or nil if empty"
  [col row]
  (queries/effect-cell col row))

(defn cell-has-effect?
  "Check if a cell has any effects assigned.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: true if has effects, false otherwise"
  [col row]
  (let [cell (get-effect-cell col row)]
    (and cell (seq (:effects cell)))))

(defn cell-active?
  "Check if a cell's effects are active.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: true if active, false otherwise"
  [col row]
  (:active (get-effect-cell col row)))

;; ============================================================================
;; Cell Write Operations (with underlying logic)
;; ============================================================================

(defn set-effect-cell!
  "Set effect cell data.
   Validates position and cell data.
   Marks project as dirty.
   
   Parameters:
   - col: Column index
   - row: Row index
   - cell-data: Map with :effects and :active
   
   Returns: true if successful, nil if validation failed"
  [col row cell-data]
  (when (and (valid-position? col row)
             (valid-effect-cell? cell-data))
    (state/assoc-in-state! [:effects :cells [col row]] cell-data)
    (state/assoc-in-state! [:project :dirty?] true)
    true))

(defn clear-effect-cell!
  "Clear effect cell.
   Validates position.
   Marks project as dirty.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: true if successful, nil if validation failed"
  [col row]
  (when (valid-position? col row)
    (state/update-in-state! [:effects :cells] dissoc [col row])
    (state/assoc-in-state! [:project :dirty?] true)
    true))

(defn toggle-effect-active!
  "Toggle effect cell active state.
   Validates position and that cell has effects.
   Marks project as dirty.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: New active state (true/false), nil if validation failed"
  [col row]
  (when (and (valid-position? col row)
             (cell-has-effect? col row))
    (let [current (get-effect-cell col row)
          new-active (not (:active current))]
      (state/assoc-in-state! [:effects :cells [col row] :active] new-active)
      (state/assoc-in-state! [:project :dirty?] true)
      new-active)))

(defn set-effect-active!
  "Set effect cell active state explicitly.
   Validates position.
   Marks project as dirty.
   
   Parameters:
   - col: Column index
   - row: Row index
   - active?: Boolean active state
   
   Returns: true if successful, nil if validation failed"
  [col row active?]
  (when (and (valid-position? col row)
             (boolean? active?)
             (cell-has-effect? col row))
    (let [cell (get-effect-cell col row)]
      (state/assoc-in-state! [:effects :cells [col row]] (assoc cell :active active?))
      (state/assoc-in-state! [:project :dirty?] true)
      true)))

;; ============================================================================
;; Effect Chain Operations
;; ============================================================================

(defn add-effect-to-cell!
  "Add an effect to a cell's effect chain.
   Creates cell if it doesn't exist.
   Validates position and effect ID.
   Marks project as dirty.
   
   Parameters:
   - col: Column index
   - row: Row index
   - effect-data: Map with :effect-id and :params
   
   Returns: true if successful, nil if validation failed"
  [col row effect-data]
  (when (and (valid-position? col row)
             (map? effect-data)
             (valid-effect-id? (:effect-id effect-data)))
    ;; Ensure cell exists first
    (when-not (get-effect-cell col row)
      (state/assoc-in-state! [:effects :cells [col row]] {:effects [] :active true}))
    (state/update-in-state! [:effects :cells [col row] :effects] conj effect-data)
    (state/assoc-in-state! [:project :dirty?] true)
    true))

(defn remove-effect-from-cell!
  "Remove an effect from a cell's effect chain by index.
   Validates position and index.
   Marks project as dirty.
   
   Parameters:
   - col: Column index
   - row: Row index
   - effect-index: Index of effect to remove
   
   Returns: true if successful, nil if validation failed"
  [col row effect-index]
  (when (valid-position? col row)
    (let [cell (get-effect-cell col row)
          effects (:effects cell)]
      (when (and effects
                 (integer? effect-index)
                 (>= effect-index 0)
                 (< effect-index (count effects)))
        (let [new-effects (vec (concat (subvec effects 0 effect-index)
                                       (subvec effects (inc effect-index))))]
          (if (empty? new-effects)
            (state/update-in-state! [:effects :cells] dissoc [col row])
            (state/assoc-in-state! [:effects :cells [col row]] (assoc cell :effects new-effects)))
          (state/assoc-in-state! [:project :dirty?] true)
          true)))))

(defn update-effect-params!
  "Update parameters for a specific effect in a cell's chain.
   Validates position and index.
   Marks project as dirty.
   
   Parameters:
   - col: Column index
   - row: Row index
   - effect-index: Index of effect to update
   - params: New parameters map
   
   Returns: true if successful, nil if validation failed"
  [col row effect-index params]
  (when (and (valid-position? col row)
             (map? params))
    (let [cell (get-effect-cell col row)
          effects (:effects cell)]
      (when (and effects
                 (integer? effect-index)
                 (>= effect-index 0)
                 (< effect-index (count effects)))
        (let [updated-effects (update-in effects [effect-index :params] merge params)]
          (state/assoc-in-state! [:effects :cells [col row]] (assoc cell :effects updated-effects))
          (state/assoc-in-state! [:project :dirty?] true)
          true)))))

;; ============================================================================
;; Cell Movement / Copy Operations
;; ============================================================================

(defn move-effect-cell!
  "Move effect cell from one position to another.
   Validates both positions.
   Marks project as dirty.
   
   Parameters:
   - from-col: Source column
   - from-row: Source row
   - to-col: Destination column
   - to-row: Destination row
   
   Returns: true if successful, nil if validation failed"
  [from-col from-row to-col to-row]
  (when (and (valid-position? from-col from-row)
             (valid-position? to-col to-row))
    (let [cell-data (get-effect-cell from-col from-row)]
      (when cell-data
        (state/assoc-in-state! [:effects :cells [to-col to-row]] cell-data)
        (state/update-in-state! [:effects :cells] dissoc [from-col from-row])
        (state/assoc-in-state! [:project :dirty?] true)
        true))))

(defn copy-effect-cell!
  "Copy effect cell from one position to another.
   Preserves the source cell.
   Validates both positions.
   Marks project as dirty.
   
   Parameters:
   - from-col: Source column
   - from-row: Source row
   - to-col: Destination column
   - to-row: Destination row
   
   Returns: true if successful, nil if validation failed"
  [from-col from-row to-col to-row]
  (when (and (valid-position? from-col from-row)
             (valid-position? to-col to-row))
    (let [cell-data (get-effect-cell from-col from-row)]
      (when cell-data
        (state/assoc-in-state! [:effects :cells [to-col to-row]] cell-data)
        (state/assoc-in-state! [:project :dirty?] true)
        true))))

;; ============================================================================
;; Batch Operations
;; ============================================================================

(defn clear-all-effects!
  "Clear all effect cells.
   Marks project as dirty."
  []
  (let [cols domains/default-grid-cols
        rows domains/default-grid-rows]
    (doseq [col (range cols)
            row (range rows)]
      (state/update-in-state! [:effects :cells] dissoc [col row]))
    (state/assoc-in-state! [:project :dirty?] true)
    true))

(defn deactivate-all-effects!
  "Deactivate all effect cells (but don't clear them).
   Marks project as dirty."
  []
  (let [cols domains/default-grid-cols
        rows domains/default-grid-rows]
    (doseq [col (range cols)
            row (range rows)]
      (when-let [cell (get-effect-cell col row)]
        (when (:active cell)
          (state/assoc-in-state! [:effects :cells [col row]] (assoc cell :active false)))))
    (state/assoc-in-state! [:project :dirty?] true)
    true))

(defn activate-all-effects!
  "Activate all effect cells that have effects.
   Marks project as dirty."
  []
  (let [cols domains/default-grid-cols
        rows domains/default-grid-rows]
    (doseq [col (range cols)
            row (range rows)]
      (when-let [cell (get-effect-cell col row)]
        (when (and (seq (:effects cell))
                   (not (:active cell)))
          (state/assoc-in-state! [:effects :cells [col row]] (assoc cell :active true)))))
    (state/assoc-in-state! [:project :dirty?] true)
    true))

;; ============================================================================
;; Query Operations
;; ============================================================================

(defn get-all-active-effects
  "Get all active effect instances.
   
   Returns: Vector of effect instance maps sorted by grid position"
  []
  (queries/all-active-effect-instances))

(defn get-effects-grid-size
  "Get effects grid dimensions.
   
   Returns: [cols rows]"
  []
  [domains/default-grid-cols domains/default-grid-rows])

(defn count-effect-cells
  "Count cells with effects.
   
   Returns: Number of cells with effects"
  []
  (let [cols domains/default-grid-cols
        rows domains/default-grid-rows]
    (count
     (for [col (range cols)
           row (range rows)
           :when (cell-has-effect? col row)]
       [col row]))))

(defn count-active-cells
  "Count cells with active effects.
   
   Returns: Number of cells with active effects"
  []
  (let [cols domains/default-grid-cols
        rows domains/default-grid-rows]
    (count
     (for [col (range cols)
           row (range rows)
           :let [cell (get-effect-cell col row)]
           :when (and cell (:active cell) (seq (:effects cell)))]
       [col row]))))
