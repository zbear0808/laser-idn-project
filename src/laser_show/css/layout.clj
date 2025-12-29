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
    (let [{bg-darkest     ::theme/bg-darkest
           bg-dark        ::theme/bg-dark
           bg-medium      ::theme/bg-medium
           text-primary   ::theme/text-primary
           text-muted     ::theme/text-muted
           border-medium  ::theme/border-medium
           border-dark    ::theme/border-dark} theme/theme]
      
      {;; ==================================================================
       ;; Toolbar
       ;; ==================================================================
       
       ".toolbar"
       {:-fx-background-color bg-medium
        :-fx-padding ["8px" "16px"]
        :-fx-spacing 24}
       
       ".toolbar-section"
       {:-fx-spacing 8
        :-fx-alignment "center"}
       
       ;; ==================================================================
       ;; Status Bar
       ;; ==================================================================
       
       ".status-bar"
       {:-fx-background-color bg-dark
        :-fx-padding ["4px" "16px"]
        :-fx-border-color border-medium
        :-fx-border-width ["1px" "0px" "0px" "0px"]
        :-fx-spacing 24}
       
       ;; ==================================================================
       ;; Title Bar / Menu Bar
       ;; ==================================================================
       
       ".title-bar"
       {:-fx-background-color bg-darkest}
       
       ".menu-bar"
       {:-fx-background-color bg-darkest
        :-fx-text-fill text-primary
        :-fx-border-color border-dark
        :-fx-border-width ["0px" "0px" "1px" "0px"]
        :-fx-padding 0}
       
       ;; Menu text styling
       ".menu"
       {" .label"
        {:-fx-text-fill text-primary}}
       
       ;; ==================================================================
       ;; Main Content Panels
       ;; ==================================================================
       
       ".main-content"
       {:-fx-background-color bg-darkest}
       
       ".side-panel"
       {:-fx-background-color bg-medium}
       
       ;; ==================================================================
       ;; Preview Panel
       ;; ==================================================================
       
       ".preview-panel"
       {:-fx-background-color bg-medium}
       
       ".preview-header"
       {:-fx-background-color bg-dark
        :-fx-padding 8}
       
       ;; ==================================================================
       ;; Tab Container
       ;; ==================================================================
       
       ".tab-container"
       {:-fx-background-color bg-darkest}
       
       ".tab-header"
       {:-fx-background-color bg-dark
        :-fx-padding ["8px" "8px" "0px" "8px"]}
       
       ;; ==================================================================
       ;; Split Pane
       ;; ==================================================================
       
       ".split-pane"
       {:-fx-background-color bg-darkest}
       
       ;; ==================================================================
       ;; Connection Status Indicator
       ;; ==================================================================
       
       ".status-indicator"
       {:-fx-background-radius 5}
       
       ".status-indicator-connected"
       {:-fx-background-color "#4CAF50"}
       
       ".status-indicator-disconnected"
       {:-fx-background-color "#F44336"}
       
       ".status-indicator-connecting"
       {:-fx-background-color "#FF9800"}})))
