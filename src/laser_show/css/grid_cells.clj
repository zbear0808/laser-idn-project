(ns laser-show.css.grid-cells
  "Grid cell styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Cue grid cells with various states
   - Effect grid cells
   - Selection and active states
   
   Note: Some grid cell styles need to remain inline because
   their background colors are computed from cue data (e.g., preset colors).
   This file provides the structural styles and state indicators.
   
   Usage:
   Include (::css/url grid-cells) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def grid-cells
  "Grid cell styles for the application."
  (css/register ::grid-cells
    (let [{bg-light       ::theme/bg-light
           bg-medium      ::theme/bg-medium
           text-primary   ::theme/text-primary
           accent-blue    ::theme/accent-blue
           accent-primary ::theme/accent-primary} theme/theme]
      
      {;; ==================================================================
       ;; Base Grid Cell
       ;; ==================================================================
       
       ".grid-cell"
       {:-fx-background-radius 4
        :-fx-cursor "hand"}
       
       ;; Default empty cell
       ".grid-cell-empty"
       {:-fx-background-color bg-light
        :-fx-background-radius 4
        :-fx-cursor "hand"}
       
       ;; ==================================================================
       ;; Grid Cell States
       ;; ==================================================================
       
       ;; Selected cell (blue border)
       ".grid-cell-selected"
       {:-fx-border-color accent-blue
        :-fx-border-width 2
        :-fx-border-radius 4}
       
       ;; Active cell (green glow effect)
       ".grid-cell-active"
       {:-fx-effect "dropshadow(gaussian, #4CAF50, 10, 0.5, 0, 0)"}
       
       ;; Active indicator bar (white bar at bottom)
       ".grid-cell-active-indicator"
       {:-fx-background-color text-primary
        :-fx-background-radius 4}
       
       ;; ==================================================================
       ;; Grid Cell Labels
       ;; ==================================================================
       
       ".grid-cell-label"
       {:-fx-text-fill text-primary
        :-fx-font-size 10}
       
       ;; ==================================================================
       ;; Effect Chain Item (in effect chain editor)
       ;; ==================================================================
       
       ".chain-item"
       {:-fx-background-color bg-light
        :-fx-padding ["6px" "8px"]
        :-fx-background-radius 4
        :-fx-cursor "hand"}
       
       ".chain-item-selected"
       {:-fx-background-color "#5A8FCF"
        :-fx-padding ["6px" "8px"]
        :-fx-background-radius 4
        :-fx-cursor "hand"}
       
       ".chain-item-drop-target"
       {:-fx-background-color "#5A8FCF"
        :-fx-border-color "#7AB8FF"
        :-fx-border-width ["2px" "0px" "0px" "0px"]}
       
       ".chain-item-dragging"
       {:-fx-opacity 0.5}
       
       ;; Drag handle for chain items
       ".chain-item-handle"
       {:-fx-text-fill "#606060"
        :-fx-font-size 10
        :-fx-cursor "move"}
       
       ;; ==================================================================
       ;; Grid Header (column letters, row numbers)
       ;; ==================================================================
       
       ".grid-header-label"
       {:-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-alignment "center"}})))
