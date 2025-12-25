(ns laser-show.services.effects-service
  "Effects service - orchestrates effect cell operations with underlying logic.
   
   This service provides high-level operations for the effects grid,
   coordinating between state management and underlying logic.
   
   All effect cell mutations should go through this service to ensure:
   - Proper validation
   - Project dirty tracking
   - Consistent coordination with other state
   
   The service layer contains underlying logic; state/atoms.clj remains thin accessors."
  (:require [laser-show.state.atoms :as state]
            [laser-show.animation.effects :as fx]
            [laser-show.ui.layout :as layout]))

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
  (let [cols layout/default-effects-grid-cols
        rows layout/default-effects-grid-rows]
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
       (some? (fx/get-effect effect-id))))

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
  (state/get-effect-cell col row))

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
    (state/set-effect-cell! col row cell-data)
    (state/mark-project-dirty!)
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
    (state/clear-effect-cell! col row)
    (state/mark-project-dirty!)
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
    (state/toggle-effect-cell-active! col row)
    (state/mark-project-dirty!)
    (:active (get-effect-cell col row))))

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
      (state/set-effect-cell! col row (assoc cell :active active?))
      (state/mark-project-dirty!)
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
    (state/add-effect-to-cell! col row effect-data)
    (state/mark-project-dirty!)
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
            (state/clear-effect-cell! col row)
            (state/set-effect-cell! col row (assoc cell :effects new-effects)))
          (state/mark-project-dirty!)
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
          (state/set-effect-cell! col row (assoc cell :effects updated-effects))
          (state/mark-project-dirty!)
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
        (state/set-effect-cell! to-col to-row cell-data)
        (state/clear-effect-cell! from-col from-row)
        (state/mark-project-dirty!)
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
        (state/set-effect-cell! to-col to-row cell-data)
        (state/mark-project-dirty!)
        true))))

;; ============================================================================
;; Batch Operations
;; ============================================================================

(defn clear-all-effects!
  "Clear all effect cells.
   Marks project as dirty."
  []
  (let [cols layout/default-effects-grid-cols
        rows layout/default-effects-grid-rows]
    (doseq [col (range cols)
            row (range rows)]
      (state/clear-effect-cell! col row))
    (state/mark-project-dirty!)
    true))

(defn deactivate-all-effects!
  "Deactivate all effect cells (but don't clear them).
   Marks project as dirty."
  []
  (let [cols layout/default-effects-grid-cols
        rows layout/default-effects-grid-rows]
    (doseq [col (range cols)
            row (range rows)]
      (when-let [cell (get-effect-cell col row)]
        (when (:active cell)
          (state/set-effect-cell! col row (assoc cell :active false)))))
    (state/mark-project-dirty!)
    true))

(defn activate-all-effects!
  "Activate all effect cells that have effects.
   Marks project as dirty."
  []
  (let [cols layout/default-effects-grid-cols
        rows layout/default-effects-grid-rows]
    (doseq [col (range cols)
            row (range rows)]
      (when-let [cell (get-effect-cell col row)]
        (when (and (seq (:effects cell))
                   (not (:active cell)))
          (state/set-effect-cell! col row (assoc cell :active true)))))
    (state/mark-project-dirty!)
    true))

;; ============================================================================
;; Query Operations
;; ============================================================================

(defn get-all-active-effects
  "Get all active effect instances.
   
   Returns: Vector of effect instance maps sorted by grid position"
  []
  (state/get-all-active-effect-instances))

(defn get-effects-grid-size
  "Get effects grid dimensions.
   
   Returns: [cols rows]"
  []
  [layout/default-effects-grid-cols layout/default-effects-grid-rows])

(defn count-effect-cells
  "Count cells with effects.
   
   Returns: Number of cells with effects"
  []
  (let [cols layout/default-effects-grid-cols
        rows layout/default-effects-grid-rows]
    (count
     (for [col (range cols)
           row (range rows)
           :when (cell-has-effect? col row)]
       [col row]))))

(defn count-active-cells
  "Count cells with active effects.
   
   Returns: Number of cells with active effects"
  []
  (let [cols layout/default-effects-grid-cols
        rows layout/default-effects-grid-rows]
    (count
     (for [col (range cols)
           row (range rows)
           :let [cell (get-effect-cell col row)]
           :when (and cell (:active cell) (seq (:effects cell)))]
       [col row]))))
