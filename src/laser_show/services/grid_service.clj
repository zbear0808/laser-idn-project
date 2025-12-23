(ns laser-show.services.grid-service
  "Grid service - orchestrates grid operations.
   
   This service provides high-level operations for the cue grid,
   coordinating between state management and business logic.
   
   All grid mutations should go through this service to ensure
   proper state updates and event dispatch."
  (:require [laser-show.state.dynamic :as dyn]))

;; ============================================================================
;; Cell Operations
;; ============================================================================

(defn get-cell
  "Get a cell from the grid.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: Cell data map or nil if empty"
  [col row]
  (dyn/get-cell col row))

(defn set-cell-preset!
  "Assign a preset to a cell.
   
   Parameters:
   - col: Column index
   - row: Row index
   - preset-id: The preset keyword to assign"
  [col row preset-id]
  (dyn/set-cell-preset! col row preset-id))

(defn clear-cell!
  "Clear a cell, removing its preset.
   
   Parameters:
   - col: Column index
   - row: Row index"
  [col row]
  (dyn/clear-cell! col row))

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
;; Selection Operations
;; ============================================================================

(defn select-cell!
  "Select a cell for editing.
   
   Parameters:
   - col: Column index (or nil to deselect)
   - row: Row index (or nil to deselect)"
  [col row]
  (dyn/set-selected-cell! col row))

(defn deselect-cell!
  "Deselect the currently selected cell."
  []
  (dyn/clear-selected-cell!))

(defn get-selected-cell
  "Get the currently selected cell coordinates.
   
   Returns: [col row] or nil if nothing selected"
  []
  (dyn/get-selected-cell))

;; ============================================================================
;; Triggering / Playback
;; ============================================================================

(defn trigger-cell!
  "Trigger a cell for playback.
   This sets the cell as active and starts animation.
   
   Parameters:
   - col: Column index
   - row: Row index"
  [col row]
  (dyn/trigger-cell! col row))

(defn stop-playback!
  "Stop playback and clear the active cell."
  []
  (dyn/stop-playback!))

(defn get-active-cell
  "Get the currently active (playing) cell coordinates.
   
   Returns: [col row] or nil if nothing playing"
  []
  (dyn/get-active-cell))

(defn playing?
  "Check if a cell is currently playing.
   
   Returns: true if playing, false otherwise"
  []
  (dyn/playing?))

;; ============================================================================
;; Cell Movement / Copy Operations
;; ============================================================================

(defn move-cell!
  "Move a cell from one position to another.
   
   Parameters:
   - from-col: Source column
   - from-row: Source row
   - to-col: Destination column
   - to-row: Destination row"
  [from-col from-row to-col to-row]
  (dyn/move-cell! from-col from-row to-col to-row))

(defn copy-cell!
  "Copy a cell from one position to another.
   Preserves the source cell.
   
   Parameters:
   - from-col: Source column
   - from-row: Source row
   - to-col: Destination column
   - to-row: Destination row"
  [from-col from-row to-col to-row]
  (when-let [cell-data (get-cell from-col from-row)]
    (dyn/set-cell! to-col to-row cell-data)))

(defn swap-cells!
  "Swap two cells.
   
   Parameters:
   - col1, row1: First cell position
   - col2, row2: Second cell position"
  [col1 row1 col2 row2]
  (let [cell1 (get-cell col1 row1)
        cell2 (get-cell col2 row2)]
    (if cell1
      (dyn/set-cell! col2 row2 cell1)
      (clear-cell! col2 row2))
    (if cell2
      (dyn/set-cell! col1 row1 cell2)
      (clear-cell! col1 row1))))

;; ============================================================================
;; Grid Information
;; ============================================================================

(defn get-grid-size
  "Get the grid dimensions.
   
   Returns: [cols rows]"
  []
  (dyn/get-grid-size))

(defn get-all-cells
  "Get all non-empty cells in the grid.
   
   Returns: Map of [col row] -> cell-data"
  []
  (dyn/get-grid-cells))

(defn count-cells
  "Count the number of non-empty cells.
   
   Returns: Number of cells with content"
  []
  (count (get-all-cells)))

(defn valid-position?
  "Check if a position is valid within the grid.
   
   Parameters:
   - col: Column index
   - row: Row index
   
   Returns: true if valid, false otherwise"
  [col row]
  (let [[cols rows] (get-grid-size)]
    (and (>= col 0) (< col cols)
         (>= row 0) (< row rows))))

;; ============================================================================
;; Batch Operations
;; ============================================================================

(defn clear-all-cells!
  "Clear all cells in the grid."
  []
  (doseq [[[col row] _] (get-all-cells)]
    (clear-cell! col row)))

(defn clear-row!
  "Clear all cells in a row.
   
   Parameters:
   - row: Row index to clear"
  [row]
  (let [[cols _] (get-grid-size)]
    (doseq [col (range cols)]
      (clear-cell! col row))))

(defn clear-column!
  "Clear all cells in a column.
   
   Parameters:
   - col: Column index to clear"
  [col]
  (let [[_ rows] (get-grid-size)]
    (doseq [row (range rows)]
      (clear-cell! col row))))
