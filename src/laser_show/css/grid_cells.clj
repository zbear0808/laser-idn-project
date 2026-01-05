(ns laser-show.css.grid-cells
  "Grid cell styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Cue grid cells with various states (empty, content, active)
   - Effect grid cells
   - Selection and active states
   - State-based background colors (no inline styles needed!)
   
   CSS Best Practices:
   - All colors defined via CSS classes, not inline styles
   - State classes can be combined: 'grid-cell grid-cell-active'
   - Cell type variants: 'grid-cell grid-cell-effects grid-cell-content'
   
   Usage:
   Include (::css/url grid-cells) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def grid-cells
  "Grid cell styles for the application."
  (css/register ::grid-cells
    (let [{bg-light       ::theme/bg-light
           bg-medium      ::theme/bg-medium
           bg-hover       ::theme/bg-hover
           text-primary   ::theme/text-primary
           accent-blue    ::theme/accent-blue
           accent-primary ::theme/accent-primary} theme/theme]
      
      {
       ;; Base Grid Cell
       ;; Structural styles that apply to all cells
       
       ".grid-cell"
       {:-fx-background-radius 4
        :-fx-border-radius 4
        :-fx-border-color "#404040"
        :-fx-border-width 1
        :-fx-cursor "hand"
        ;; Default background for empty cells
        :-fx-background-color bg-light}
       
       
       ;; State-Based Background Colors
       ;; These classes control background color - no more inline styles!
       
       
       ;; Empty cell (default state)
       ".grid-cell-empty"
       {:-fx-background-color bg-light}
       
       ;; Cell with content (cues/effects assigned)
       ".grid-cell-content"
       {:-fx-background-color "#616161"}
       
       ;; Active/playing cell (green)
       ".grid-cell-active"
       {:-fx-background-color accent-primary
        :-fx-effect "dropshadow(gaussian, #4CAF50, 10, 0.5, 0, 0)"}
       
       ;; Selected cell (blue border)
       ".grid-cell-selected"
       {:-fx-border-color accent-blue
        :-fx-border-width 2
        :-fx-border-radius 4}
       
       
       ;; Cell Type Variants
       ;; Different styling for grid vs effects cells
       
       
       ;; Effects cells with content use purple
       ".grid-cell-effects.grid-cell-content"
       {:-fx-background-color "#7E57C2"}
       
       
       ;; Hover States
       
       
       ".grid-cell:hover"
       {:-fx-background-color bg-hover
        :-fx-border-color "#606060"}
       
       ;; Active cells stay green on hover, just reduce opacity slightly
       ".grid-cell-active:hover"
       {:-fx-background-color accent-primary
        :-fx-opacity 0.9}
       
       ;; Content cells get lighter on hover
       ".grid-cell-content:hover"
       {:-fx-background-color "#717171"}
       
       ;; Effects content cells get lighter purple
       ".grid-cell-effects.grid-cell-content:hover"
       {:-fx-background-color "#8E67D2"}
       
       
       ;; Active Indicator (small dot/bar showing active state)
       
       
       ".grid-cell-active-indicator"
       {:-fx-background-color text-primary
        :-fx-background-radius 4}
       
       
       ;; Grid Cell Labels
       
       
       ".grid-cell-label"
       {:-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-wrap-text true
        :-fx-text-alignment "center"}
       
       
       ;; Effect Chain Item (in effect chain editor)
       
       
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
       
       
       ;; Grid Header (column letters, row numbers)
       
       
       ".grid-header-label"
       {:-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-alignment "center"}})))
