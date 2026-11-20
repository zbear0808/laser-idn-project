(ns laser-show.css.grid-cells
  "Grid cell styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Cue grid cells with various states (empty, content, active, held)
   - Effect grid cells (off like a cue cell with content, on like a playing one)
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
          {:keys [cell-content cell-content-hover]} theme/computed-colors
          {:keys [blue orange]} theme/base-colors]
      
      {;; Base Grid Cell
       ;; Structural styles that apply to all cells
       ".grid-cell"
       {:-fx-background-radius 4
        :-fx-border-radius 4
        :-fx-border-color border-default
        :-fx-border-width 1
        :-fx-cursor "hand"}
       
       
       ;; State-Based Background Colors
       ;; This map is a hash map, so cljfx.css writes the rules in hash order,
       ;; not in the order they are written here, and of two rules with the
       ;; same specificity the one written later wins. So the empty colour has
       ;; its own class instead of being .grid-cell's, and a state that comes
       ;; on top of another (a playing or held cell has content; any cell can
       ;; be hovered) has a rule with more classes than the one it overrides:
       ;; empty, content 1; playing, hovered 2; content hovered, held 3;
       ;; playing hovered 4.
       
       ;; Empty cell (a cell has either this class or grid-cell-content)
       ".grid-cell-empty"
       {:-fx-background-color bg-interactive}
       
       ;; Selected cell (accent border)
       ".grid-cell-selected"
       {:-fx-border-color blue
        :-fx-border-width 2}

       ;; Actively learning cell (orange border & glow)
       ".grid-cell-learning"
       {:-fx-border-color orange
        :-fx-border-width 2
        :-fx-effect (str "dropshadow(gaussian, " orange ", 8, 0.4, 0, 0)")}
       
       ;; Cell with content (a cue, or effects), off
       ".grid-cell-content"
       {:-fx-background-color cell-content}
       
       ;; Playing cue, or an effects cell that is on (blue)
       ".grid-cell-content.grid-cell-active"
       {:-fx-background-color selection-bg
        :-fx-effect (str "dropshadow(gaussian, " blue ", 10, 0.5, 0, 0)")}

       ;; Held cue: in play, but Stop blacked the output out (a dashed blue
       ;; outline and no blue fill; dashed, so it isn't the selected cell's)
       ".grid-cell.grid-cell-content.grid-cell-held"
       {:-fx-border-color blue
        :-fx-border-width 2
        :-fx-border-style "dashed"}
       
       
       ;; Hover States
       
       
       ".grid-cell:hover"
       {:-fx-background-color bg-hover
        :-fx-border-color text-muted}
       
       ;; Playing and on cells stay blue on hover, just reduce opacity slightly
       ".grid-cell.grid-cell-content.grid-cell-active:hover"
       {:-fx-background-color selection-bg
        :-fx-opacity 0.9}
       
       ;; Content cells get lighter on hover
       ".grid-cell.grid-cell-content:hover"
       {:-fx-background-color cell-content-hover}
       
       
       ;; Grid Cell Labels & Indicators
       
       
       ;; 12 px; the grid gives the label up to 16 px as the cell grows
       ;; (views/components/grid_tab.clj label-size)
       ".grid-cell-label"
       {:-fx-text-fill text-primary
        :-fx-font-size 12
        :-fx-wrap-text true
        :-fx-text-alignment "center"}

       ".grid-cell-learning-badge"
       {:-fx-text-fill orange
        :-fx-font-size 8
        :-fx-font-weight "bold"}

       ".grid-cell-trigger-indicator"
       {:-fx-text-fill blue
        :-fx-font-size 9}

       ;; A playing cue too heavy for a laser (zones plan Z3)
       ".grid-cell-warning-indicator"
       {:-fx-text-fill orange
        :-fx-font-size 9
        :-fx-font-weight "bold"}})))
