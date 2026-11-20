(ns laser-show.views.tabs.effects
  "Effects grid tab - for managing effect chains per cell.
   
   Similar to the cue grid but for effect modifiers.
   Uses the generic grid-tab component for consistent grid layout."
  (:require
   [cljfx.api :as fx]
   [laser-show.subs :as subs]
   [laser-show.views.components.grid-tab :as grid-tab]
   [laser-show.views.components.grid-cell :as cell]))


;; Effects Tab


(defn effects-tab
  "Complete effects tab with grid.
   Uses generic-grid-tab with effects cells. While no cell has an effect
   (but a new project's Master: subs/effects-grid-empty?), a line says what
   they are for and how to start one."
  [{:keys [fx/context]}]
  {:fx/type grid-tab/generic-grid-tab
   :cell-component cell/effects-cell
   :header-text "Effects Grid"
   :hint-text "Click: on or off · Double-click or right-click: edit · Drag: move"
   :empty-hint (when (fx/sub-ctx context subs/effects-grid-empty?)
                 "Effect cells change every playing cue. Double-click one to start")})
