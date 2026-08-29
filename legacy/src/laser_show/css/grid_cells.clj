(ns laser-show.css.grid-cells
  "Grid cell styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Cue grid cells with various states (empty, content, active)
   - Effect grid cells
   - Active state (blue)
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
    (let [{:keys [bg-interactive bg-hover text-primary text-muted
                  selection-bg border-default]} theme/semantic-colors
          {:keys [cell-content cell-content-hover
                  selection-hover]} theme/computed-colors
          {:keys [blue]} theme/base-colors]
      
      {;; Base Grid Cell
       ;; Structural styles that apply to all cells
       ".grid-cell"
       {:-fx-background-radius 4
        :-fx-border-radius 4
        :-fx-border-color border-default
        :-fx-border-width 1
        :-fx-cursor "hand"
        :-fx-background-color bg-interactive}
       
       
       ;; State-Based Background Colors
       
       
       ;; Cell with content (cues/effects assigned)
       ".grid-cell-content"
       {:-fx-background-color cell-content}
       
       ;; Active/playing cell (blue)
       ".grid-cell-active"
       {:-fx-background-color selection-bg
        :-fx-effect (str "dropshadow(gaussian, " blue ", 10, 0.5, 0, 0)")}
       
       
       ;; Cell Type Variants
       
       
       ;; Effects cells with content also use blue (same as active state, but dimmer)
       ".grid-cell-effects.grid-cell-content"
       {:-fx-background-color selection-bg
        :-fx-opacity 0.7}
       
       
       ;; Hover States
       
       
       ".grid-cell:hover"
       {:-fx-background-color bg-hover
        :-fx-border-color text-muted}
       
       ;; Active cells stay blue on hover, just reduce opacity slightly
       ".grid-cell-active:hover"
       {:-fx-background-color selection-bg
        :-fx-opacity 0.9}
       
       ;; Content cells get lighter on hover
       ".grid-cell-content:hover"
       {:-fx-background-color cell-content-hover}
       
       ;; Effects content cells get lighter blue on hover
       ".grid-cell-effects.grid-cell-content:hover"
       {:-fx-background-color selection-hover
        :-fx-opacity 0.85}
       
       
       ;; Grid Cell Labels
       
       
       ".grid-cell-label"
       {:-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-wrap-text true
        :-fx-text-alignment "center"}})))
