(ns laser-show.css.layout
  "Layout and container styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Toolbar with transport controls
   - Status indicators (connection states)
   - Panel containers
   
   Usage:
   Include (::css/url layout) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def layout
  "Layout and container styles for the application."
  (css/register ::layout
    (let [{:keys [bg-primary bg-surface bg-elevated
                  text-primary border-subtle
                  accent-success accent-danger accent-warning]} theme/semantic-colors]
      
      {;; Toolbar
       ".toolbar"
       {:-fx-background-color bg-primary
        :-fx-padding ["8px" "16px"]
        :-fx-spacing 24
        :-fx-border-color border-subtle
        :-fx-border-width ["1px" "0px" "1px" "0px"]}
       
       ;; Panel containers
       ".panel-primary"
       {:-fx-background-color bg-primary
        :-fx-border-color border-subtle
        :-fx-border-width 1
        :-fx-border-radius 4
        :-fx-background-radius 4}
       
       ".panel-surface"
       {:-fx-background-color bg-primary
        :-fx-border-color border-subtle
        :-fx-border-width 1
        :-fx-border-radius 4
        :-fx-background-radius 4}
       
       ".panel-elevated"
       {:-fx-background-color bg-elevated
        :-fx-border-color border-subtle
        :-fx-border-width 1
        :-fx-border-radius 4
        :-fx-background-radius 4}
       
       ;; Connection Status Indicator
       ".status-indicator"
       {:-fx-background-radius 5}
       
       ".status-indicator-connected"
       {:-fx-background-color accent-success}
       
       ".status-indicator-disconnected"
       {:-fx-background-color accent-danger}
       
       ".status-indicator-connecting"
       {:-fx-background-color accent-warning}
       
       ".container-primary"
       {:-fx-background-color bg-primary}
       
       ".container-surface"
       {:-fx-background-color bg-primary}
       
       ".container-elevated"
       {:-fx-background-color bg-elevated}})))
