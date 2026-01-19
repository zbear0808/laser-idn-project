(ns laser-show.css.visual-editors
  "CSS styles for visual parameter editors (spatial canvas, curve editor, etc.).
   
   Provides CSS classes for:
   - Visual editor containers (corner pin, translate, RGB curves)
   - Hint text labels
   - Coordinate display labels
   - Mode selector buttons
   - Info panels
   
   Usage:
   Include (::css/url visual-editors) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def visual-editors
  "Visual editor styles for the application."
  (css/register ::visual-editors
    (let [{:keys [bg-primary bg-surface bg-elevated bg-interactive bg-hover
                  text-primary text-secondary text-muted
                  selection-bg]} theme/semantic-colors]
      
      {
       ;; Visual Editor Container
       
       
       ;; Base container for all visual editors (corner pin, translate, curves)
       ".visual-editor"
       {:-fx-background-color bg-primary
        :-fx-background-radius 4}
       
       ;; Padding variant for editors that need internal spacing
       ".visual-editor-padded"
       {:-fx-background-color bg-primary
        :-fx-background-radius 4
        :-fx-padding 8}
       
       
       ;; Hint Labels
       
       
       ;; Hint text shown above visual editors
       ".visual-editor-hint"
       {:-fx-text-fill text-muted
        :-fx-font-size 10
        :-fx-font-style "italic"}
       
       
       ;; Coordinate Display
       
       
       ;; Monospace coordinate labels (e.g., "TL: (0.50, 0.75)")
       ".visual-editor-coord"
       {:-fx-font-size 10
        :-fx-font-family "'Consolas', monospace"}
       
       ;; Colored coordinate variants for corner pin
       ".visual-editor-coord-tl"
       {:-fx-text-fill "#FF5722"
        :-fx-font-size 10
        :-fx-font-family "'Consolas', monospace"}
       
       ".visual-editor-coord-tr"
       {:-fx-text-fill "#4CAF50"
        :-fx-font-size 10
        :-fx-font-family "'Consolas', monospace"}
       
       ".visual-editor-coord-bl"
       {:-fx-text-fill "#2196F3"
        :-fx-font-size 10
        :-fx-font-family "'Consolas', monospace"}
       
       ".visual-editor-coord-br"
       {:-fx-text-fill "#FFC107"
        :-fx-font-size 10
        :-fx-font-family "'Consolas', monospace"}
       
       
       ;; Mode Selector Buttons
       
       
       ;; Base mode button (inactive)
       ".visual-editor-mode-btn"
       {:-fx-font-size 10
        :-fx-padding ["4px" "10px"]
        :-fx-background-color "#404040"
        :-fx-text-fill "#B0B0B0"}
       
       ;; Active mode button
       ".visual-editor-mode-btn-active"
       {:-fx-font-size 10
        :-fx-padding ["4px" "10px"]
        :-fx-background-color selection-bg
        :-fx-text-fill "white"}
       
       
       ;; Reset Button
       
       
       ".visual-editor-reset-btn"
       {:-fx-background-color bg-hover
        :-fx-text-fill text-secondary
        :-fx-font-size 10
        :-fx-padding ["4px" "12px"]}
       
       
       ;; Section Labels (e.g., "ROUTING MODE", "TARGET ZONE GROUPS")
       
       
       ".visual-editor-section-label"
       {:-fx-text-fill text-muted
        :-fx-font-size 10
        :-fx-font-weight "bold"}
       
       
       ;; Description Text
       
       
       ".visual-editor-description"
       {:-fx-text-fill "#606060"
        :-fx-font-size 9
        :-fx-font-style "italic"}
       
       ".visual-editor-selected-text"
       {:-fx-text-fill "#606060"
        :-fx-font-size 9}
       
       
       ;; Info Panel
       
       
       ".visual-editor-info-panel"
       {:-fx-background-color bg-elevated
        :-fx-padding 8
        :-fx-background-radius 4}
       
       ".visual-editor-info-title"
       {:-fx-text-fill "#7A9EC2"
        :-fx-font-size 10}
       
       ".visual-editor-info-text"
       {:-fx-text-fill "#909090"
        :-fx-font-size 9}})))
