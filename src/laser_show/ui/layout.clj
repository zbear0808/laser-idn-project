(ns laser-show.ui.layout
  "UI layout configuration for the laser show application.
   Defines dimensions, grid sizes, and layout constraints.
   
   IDN-Stream Coordinate System (Section 3.4.7)
   ============================================
   
   The coordinate mapping functions in this namespace convert between:
   - IDN-Stream laser coordinates: 16-bit signed (-32768 to 32767)
   - Screen coordinates: 0 to screen-size pixels
   - Normalized coordinates: -1.0 to 1.0 (application convenience)
   
   IDN-Stream Coordinate Orientation:
   - X: Positive = right, Negative = left (front projection view)
   - Y: Positive = up, Negative = down
   - Origin (0,0) = center of projection area
   
   Screen Coordinate Orientation:
   - X: 0 = left, max = right (standard screen coords)
   - Y: 0 = top, max = bottom (standard screen coords, inverted from laser)
   
   Note: When rendering, Y coordinates must be inverted:
   screen_y = screen_height - laser_to_screen(laser_y)")

;; ============================================================================
;; Grid Configuration
;; ============================================================================

(def default-grid-cols 5)
(def default-grid-rows 4)

(def grid-config
  "Default grid configuration."
  {:cols default-grid-cols
   :rows default-grid-rows})

;; ============================================================================
;; Effects Grid Configuration
;; ============================================================================

(def default-effects-grid-cols 5)
(def default-effects-grid-rows 2)

(def effects-grid-config
  "Default effects grid configuration."
  {:cols default-effects-grid-cols
   :rows default-effects-grid-rows})

(defn grid-cell-count
  "Calculate total number of cells in a grid."
  [{:keys [cols rows] :or {cols default-grid-cols rows default-grid-rows}}]
  (* cols rows))

;; ============================================================================
;; Cell Dimensions
;; ============================================================================

(def cell-width 80)
(def cell-height 60)
(def cell-min-width 60)
(def cell-min-height 50)
(def cell-gap 2)
(def cell-border-width 1)
(def cell-border-hover-width 2)

(def cell-dimensions
  "Default cell dimension configuration."
  {:width cell-width
   :height cell-height
   :min-width cell-min-width
   :min-height cell-min-height
   :gap cell-gap
   :border-width cell-border-width
   :border-hover-width cell-border-hover-width})

;; ============================================================================
;; Preview Panel Dimensions
;; ============================================================================

(def preview-default-width 400)
(def preview-default-height 400)
(def mini-preview-size 60)

(def preview-dimensions
  "Default preview panel dimensions."
  {:width preview-default-width
   :height preview-default-height
   :mini-size mini-preview-size})

;; ============================================================================
;; Window Dimensions
;; ============================================================================

(def main-window-min-width 800)
(def main-window-min-height 600)
(def main-window-default-width 1200)
(def main-window-default-height 800)

(def window-dimensions
  "Default window dimensions."
  {:min-width main-window-min-width
   :min-height main-window-min-height
   :default-width main-window-default-width
   :default-height main-window-default-height})

;; ============================================================================
;; Panel Insets and Padding
;; ============================================================================

(def panel-insets 5)
(def panel-gap 5)
(def scrollbar-width 20)

(def panel-spacing
  "Default panel spacing configuration."
  {:insets panel-insets
   :gap panel-gap
   :scrollbar-width scrollbar-width})

;; ============================================================================
;; Font Configuration
;; ============================================================================

(def font-family "SansSerif")
(def font-size-small 10)
(def font-size-normal 11)
(def font-size-large 14)
(def font-size-title 18)

(def font-config
  "Default font configuration."
  {:family font-family
   :size-small font-size-small
   :size-normal font-size-normal
   :size-large font-size-large
   :size-title font-size-title})

;; ============================================================================
;; MIG Layout Helpers
;; ============================================================================

(defn make-grid-col-constraints
  "Generate MIG layout column constraints for a grid."
  [cols cell-width]
  (apply str (repeat cols (str "[" cell-width "!, grow, fill]"))))

(defn make-grid-row-constraints
  "Generate MIG layout row constraints for a grid."
  [rows cell-height]
  (apply str (repeat rows (str "[" cell-height "!, grow, fill]"))))

(defn make-grid-constraints
  "Generate MIG layout constraints string for a grid panel."
  [{:keys [cols rows cell-width cell-height gap insets]
    :or {cols default-grid-cols
         rows default-grid-rows
         cell-width 80
         cell-height 60
         gap cell-gap
         insets panel-insets}}]
  {:layout (str "gap " gap ", insets " insets ", wrap " cols)
   :cols (make-grid-col-constraints cols cell-width)
   :rows (make-grid-row-constraints rows cell-height)})

;; ============================================================================
;; Coordinate Mapping (IDN-Stream Section 3.4.7)
;; ============================================================================

(def laser-coord-min
  "Minimum laser coordinate value per IDN-Stream spec (16-bit signed)."
  -32768)

(def laser-coord-max
  "Maximum laser coordinate value per IDN-Stream spec (16-bit signed)."
  32767)

(def laser-coord-range
  "Total range of laser coordinates."
  65535)

(defn laser-to-screen
  "Convert IDN-Stream laser coordinates to screen coordinates.
   
   IDN-Stream coordinates (Section 3.4.7):
   - Range: -32768 to 32767 (16-bit signed)
   - -32768 maps to screen position 0
   - 32767 maps to screen position screen-size
   - 0 maps to screen center
   
   Note: For Y axis, caller should invert: (- screen-height result)"
  [laser-coord screen-size]
  (let [normalized (/ laser-coord 32767.0)]
    (int (* (+ normalized 1) (/ screen-size 2)))))

(defn screen-to-laser
  "Convert screen coordinates to IDN-Stream laser coordinates.
   
   Returns 16-bit signed value in range -32768 to 32767.
   Note: For Y axis, caller should invert screen-coord first."
  [screen-coord screen-size]
  (let [normalized (- (* 2 (/ screen-coord screen-size)) 1)]
    (int (* normalized 32767))))

(defn normalized-to-screen
  "Convert normalized coordinates (-1 to 1) to screen coordinates.
   
   Normalized coordinates are an application convenience:
   - Range: -1.0 to 1.0
   - Maps directly to IDN-Stream range when scaled by 32767"
  [normalized screen-size]
  (int (* (+ normalized 1) (/ screen-size 2))))

(defn screen-to-normalized
  "Convert screen coordinates to normalized coordinates (-1 to 1).
   
   Normalized coordinates are an application convenience:
   - Range: -1.0 to 1.0
   - Maps directly to IDN-Stream range when scaled by 32767"
  [screen-coord screen-size]
  (- (* 2 (/ screen-coord screen-size)) 1))

(defn normalized-to-laser
  "Convert normalized coordinates (-1 to 1) to IDN-Stream laser coordinates.
   
   This is the scaling used by make-point in animation.types."
  [normalized]
  (short (* normalized 32767)))

(defn laser-to-normalized
  "Convert IDN-Stream laser coordinates to normalized coordinates (-1 to 1)."
  [laser-coord]
  (/ laser-coord 32767.0))
