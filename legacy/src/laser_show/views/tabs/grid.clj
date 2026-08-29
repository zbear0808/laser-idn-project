(ns laser-show.views.tabs.grid
  "Grid tab component - the main cue trigger interface.
   
   The grid displays an 8x4 matrix of cells, each representing a cue
   that can be triggered to play a preset animation.
   
   Uses the generic grid-tab component for consistent grid layout."
  (:require
   [laser-show.views.components.grid-tab :as grid-tab]
   [laser-show.views.components.grid-cell :as grid-cell]))


;; Grid Tab


(defn grid-tab
  "Complete grid tab with header and grid.
   Uses generic-grid-tab with cue grid cells."
  [{:keys [fx/context]}]
  {:fx/type grid-tab/generic-grid-tab
   :cell-component grid-cell/grid-cell
   :header-text "Cue Grid"
   :hint-text "Click to trigger • Right-click to select • Drag to move"})
