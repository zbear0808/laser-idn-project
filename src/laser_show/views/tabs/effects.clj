(ns laser-show.views.tabs.effects
  "Effects grid tab - for managing effect chains per cell.
   
   Similar to the cue grid but for effect modifiers.
   Uses the generic grid-tab component for consistent grid layout."
  (:require
   [laser-show.views.components.grid-tab :as grid-tab]
   [laser-show.views.components.grid-cell :as cell]))


;; Effects Tab


(defn effects-tab
  "Complete effects tab with grid.
   Uses generic-grid-tab with effects cells."
  [{:keys [fx/context]}]
  {:fx/type grid-tab/generic-grid-tab
   :cell-component cell/effects-cell
   :header-text "Effects Grid"
   :hint-text "Click to toggle • Right-click to edit chain"})
