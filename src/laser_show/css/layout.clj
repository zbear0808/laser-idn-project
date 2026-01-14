(ns laser-show.css.layout
  "Layout and container styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Toolbar and status bar
   - Panel layouts
   - Title bar and menu bar
   - Preview panel
   
   Usage:
   Include (::css/url layout) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def layout
  "Layout and container styles for the application."
  (css/register ::layout
    (let [;; Use semantic colors from theme
          {:keys [bg-primary bg-surface bg-elevated
                  text-primary text-muted
                  border-default border-subtle
                  accent-success accent-danger accent-warning]} theme/semantic-colors]
      
      {
       ;; Toolbar
       
       
       ".toolbar"
       {:-fx-background-color bg-elevated
        :-fx-padding ["8px" "16px"]
        :-fx-spacing 24}
       
       ".toolbar-section"
       {:-fx-spacing 8
        :-fx-alignment "center"}
       
       
       ;; Status Bar
       
       
       ".status-bar"
       {:-fx-background-color bg-surface
        :-fx-padding ["4px" "16px"]
        :-fx-border-color border-default
        :-fx-border-width ["1px" "0px" "0px" "0px"]
        :-fx-spacing 24}
       
       
       ;; Title Bar / Menu Bar
       
       
       ".title-bar"
       {:-fx-background-color bg-primary}
       
       ".menu-bar"
       {:-fx-background-color bg-primary
        :-fx-text-fill text-primary
        :-fx-border-color border-subtle
        :-fx-border-width ["0px" "0px" "1px" "0px"]
        :-fx-padding 0}
       
       ;; Menu text styling
       ".menu"
       {" .label"
        {:-fx-text-fill text-primary}}
       
       
       ;; Main Content Panels
       
       
       ".main-content"
       {:-fx-background-color bg-primary}
       
       ".side-panel"
       {:-fx-background-color bg-elevated}
       
       
       ;; Preview Panel
       
       
       ".preview-panel"
       {:-fx-background-color bg-elevated}
       
       ".preview-header"
       {:-fx-background-color bg-surface
        :-fx-padding 8}
       
       
       ;; Tab Container
       
       
       ".tab-container"
       {:-fx-background-color bg-primary}
       
       ".tab-header"
       {:-fx-background-color bg-surface
        :-fx-padding ["8px" "8px" "0px" "8px"]}
       
       
       ;; Split Pane
       
       
       ".split-pane"
       {:-fx-background-color bg-primary}
       
       
       ;; Connection Status Indicator
       
       
       ".status-indicator"
       {:-fx-background-radius 5}
       
       ".status-indicator-connected"
       {:-fx-background-color accent-success}
       
       ".status-indicator-disconnected"
       {:-fx-background-color accent-danger}
       
       ".status-indicator-connecting"
       {:-fx-background-color accent-warning}})))
