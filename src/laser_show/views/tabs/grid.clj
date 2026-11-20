(ns laser-show.views.tabs.grid
  "Grid tab component - the main cue trigger interface.
   
   The grid displays an 8x4 matrix of cells, each representing a cue
   that can be triggered to play a preset animation.
   
   Uses the generic grid-tab component for consistent grid layout."
  (:require
   [laser-show.views.components.grid-tab :as grid-tab]
   [laser-show.views.components.grid-cell :as grid-cell]
   [laser-show.views.components.target-control :as target-control]))


;; Grid Tab


(defn grid-tab
  "Complete grid tab with header and grid.
   Uses generic-grid-tab with cue grid cells; a column's number sets its
   default target (zones plan Z4)."
  [{:keys [fx/context]}]
  {:fx/type grid-tab/generic-grid-tab
   :cell-component grid-cell/grid-cell
   :column-component target-control/column-target-header
   :header-text "Cue Grid"
   :hint-text "Click: play or stop · Double-click or right-click: edit · Drag: move"})
