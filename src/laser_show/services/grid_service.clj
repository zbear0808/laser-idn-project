(ns laser-show.services.grid-service
  "Grid service - orchestrates grid operations with business logic.
   
   This service provides high-level operations for the cue grid,
   coordinating between state management and business logic.
   
   All grid mutations should go through this service to ensure:
   - Proper validation
   - Coordination between related state (e.g., active cell, selection)
   - Project dirty tracking
   
   The service layer contains business logic; state/atoms.clj remains thin accessors."
  (:require [laser-show.state.atoms :as state]))

;; ============================================================================
;; Validation Helpers
;; ============================================================================

(defn valid-position?
  "Check if a position is valid within the grid.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: true if valid, false otherwise"
  [col row]
  (let [[cols rows] (state/get-grid-size)]
    (and (integer? col) (integer? row)
         (>= col 0) (< col cols)
         (>= row 0) (< row rows))))

;; ============================================================================
;; Cell Read Operations (no business logic needed)
;; ============================================================================

(defn get-cell
  "Get a cell from the grid.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: Cell data map or nil if empty"
  [col row]
  (state/get-cell col row))

(defn cell-empty?
  "Check if a cell is empty (no preset assigned).
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: true if empty, false otherwise"
  [col row]
  (nil? (get-cell col row)))

(defn cell-has-preset?
  "Check if a cell has a preset assigned.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: true if has preset, false otherwise"
  [col row]
  (some? (:preset-id (get-cell col row))))

;; ============================================================================
;; Cell Write Operations (with business logic)
;; ============================================================================

(defn set-cell-preset!
  "Assign a preset to a cell.
   Validates position and marks project as dirty.
   
   Parameters:
   - col: Column index
   - row: Row index
   - preset-id: The preset keyword to assign
   
   Returns: true if successful, nil if validation failed"
  [col row preset-id]
  (when (and (valid-position? col row)
             (keyword? preset-id))
    (state/set-cell-preset! col row preset-id)
    (state/mark-project-dirty!)
    true))

(defn clear-cell!
  "Clear a cell, removing its preset.
   If clearing the active cell, stops playback.
   Marks project as dirty.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: true if successful, nil if validation failed"
  [col row]
  (when (valid-position? col row)
    ;; If clearing the active cell, stop playback
    (when (= [col row] (state/get-active-cell))
      (state/stop-playback!))
    (state/clear-cell! col row)
    (state/mark-project-dirty!)
    true))

;; ============================================================================
;; Selection Operations
;; ============================================================================

(defn select-cell!
  "Select a cell for editing.
   Validates position.
   
   Parameters:
   - col: Column index (or nil to deselect)
   - row: Row index (or nil to deselect)
   
   Returns: true if successful, nil if validation failed"
  [col row]
  (if (and (nil? col) (nil? row))
    (do (state/clear-selected-cell!) true)
    (when (valid-position? col row)
      (state/set-selected-cell! col row)
      true)))

(defn deselect-cell!
  "Deselect the currently selected cell."
  []
  (state/clear-selected-cell!))

(defn get-selected-cell
  "Get the currently selected cell coordinates.
   
   Returns: [col row] or nil if nothing selected"
  []
  (state/get-selected-cell))

;; ============================================================================
;; Triggering / Playback (with business logic)
;; ============================================================================

(defn trigger-cell!
  "Trigger a cell for playback.
   Validates position and that cell has content.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: true if successful, nil if validation failed or cell empty"
  [col row]
  (when (valid-position? col row)
    (when-let [cell (get-cell col row)]
      (when (:preset-id cell)
        (state/trigger-cell! col row)
        true))))

(defn stop-playback!
  "Stop playback and clear the active cell."
  []
  (state/stop-playback!))

(defn get-active-cell
  "Get the currently active (playing) cell coordinates.
   
   Returns: [col row] or nil if nothing playing"
  []
  (state/get-active-cell))

(defn playing?
  "Check if a cell is currently playing.
   
   Returns: true if playing, false otherwise"
  []
  (state/playing?))

;; ============================================================================
;; Cell Movement / Copy Operations (with business logic)
;; ============================================================================

(defn move-cell!
  "Move a cell from one position to another.
   Validates both positions and updates active cell reference if needed.
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
    ;; If moving the active cell, update the reference
    (when (= [from-col from-row] (state/get-active-cell))
      (state/set-active-cell! to-col to-row))
    ;; If moving the selected cell, update selection
    (when (= [from-col from-row] (state/get-selected-cell))
      (state/set-selected-cell! to-col to-row))
    (state/move-cell! from-col from-row to-col to-row)
    (state/mark-project-dirty!)
    true))

(defn copy-cell!
  "Copy a cell from one position to another.
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
    (when-let [cell-data (get-cell from-col from-row)]
      (state/set-cell! to-col to-row cell-data)
      (state/mark-project-dirty!)
      true)))

(defn swap-cells!
  "Swap two cells.
   Validates both positions.
   Updates active cell and selection if needed.
   Marks project as dirty.
   Works even if one or both cells are empty.
   
   Parameters:
   - col1, row1: First cell position
   - col2, row2: Second cell position
   
   Returns: true if successful, nil if validation failed"
  [col1 row1 col2 row2]
  (when (and (valid-position? col1 row1)
             (valid-position? col2 row2))
    (let [cell1 (get-cell col1 row1)
          cell2 (get-cell col2 row2)
          active-cell (state/get-active-cell)
          selected-cell (state/get-selected-cell)]
      ;; Set the cells - handle empty cells properly
      (if cell1
        (state/set-cell! col2 row2 cell1)
        (state/clear-cell! col2 row2))
      (if cell2
        (state/set-cell! col1 row1 cell2)
        (state/clear-cell! col1 row1))
      ;; Update active cell if it was one of the swapped cells
      (cond
        (= active-cell [col1 row1]) (state/set-active-cell! col2 row2)
        (= active-cell [col2 row2]) (state/set-active-cell! col1 row1))
      ;; Update selection if it was one of the swapped cells
      (cond
        (= selected-cell [col1 row1]) (state/set-selected-cell! col2 row2)
        (= selected-cell [col2 row2]) (state/set-selected-cell! col1 row1))
      (state/mark-project-dirty!)
      true)))

;; ============================================================================
;; Grid Information
;; ============================================================================

(defn get-grid-size
  "Get the grid dimensions.
   
   Returns: [cols rows]"
  []
  (state/get-grid-size))

(defn get-all-cells
  "Get all non-empty cells in the grid.
   
   Returns: Map of [col row] -> cell-data"
  []
  (state/get-grid-cells))

(defn count-cells
  "Count the number of non-empty cells.
   
   Returns: Number of cells with content"
  []
  (count (get-all-cells)))

;; ============================================================================
;; Batch Operations (with business logic)
;; ============================================================================

(defn clear-all-cells!
  "Clear all cells in the grid.
   Stops playback if any cell is active.
   Clears selection.
   Marks project as dirty."
  []
  (state/stop-playback!)
  (state/clear-selected-cell!)
  (doseq [[[col row] _] (get-all-cells)]
    (state/clear-cell! col row))
  (state/mark-project-dirty!)
  true)

(defn clear-row!
  "Clear all cells in a row.
   Handles active cell and selection appropriately.
   Marks project as dirty.
   
   Parameters:
   - row: Row index to clear
   
   Returns: true if successful, nil if row invalid"
  [row]
  (let [[cols rows] (get-grid-size)]
    (when (and (integer? row) (>= row 0) (< row rows))
      ;; Check if active cell is in this row
      (when-let [[_ac ar] (state/get-active-cell)]
        (when (= ar row)
          (state/stop-playback!)))
      ;; Check if selected cell is in this row
      (when-let [[_sc sr] (state/get-selected-cell)]
        (when (= sr row)
          (state/clear-selected-cell!)))
      (doseq [col (range cols)]
        (state/clear-cell! col row))
      (state/mark-project-dirty!)
      true)))

(defn clear-column!
  "Clear all cells in a column.
   Handles active cell and selection appropriately.
   Marks project as dirty.
   
   Parameters:
   - col: Column index to clear
   
   Returns: true if successful, nil if column invalid"
  [col]
  (let [[cols rows] (get-grid-size)]
    (when (and (integer? col) (>= col 0) (< col cols))
      ;; Check if active cell is in this column
      (when-let [[ac _ar] (state/get-active-cell)]
        (when (= ac col)
          (state/stop-playback!)))
      ;; Check if selected cell is in this column
      (when-let [[sc _sr] (state/get-selected-cell)]
        (when (= sc col)
          (state/clear-selected-cell!)))
      (doseq [row (range rows)]
        (state/clear-cell! col row))
      (state/mark-project-dirty!)
      true)))
