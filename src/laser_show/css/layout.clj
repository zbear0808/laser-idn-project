(ns laser-show.css.layout
  "Layout and container styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Toolbar with transport controls (and the OUTPUT OFF and LOW FPS chips, a link)
   - Status indicators (connection states)
   - Panel containers
   - A rig card's load meter and its low state
   
   Usage:
   Include (::css/url layout) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def layout
  "Layout and container styles for the application."
  (css/register ::layout
    (let [{:keys [bg-primary bg-surface bg-elevated bg-subtle
                  text-primary text-muted border-subtle border-default
                  accent-info accent-success accent-danger accent-warning]} theme/semantic-colors]
      
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
       
       ;; Stop holds the cues: the output is blacked out (views/toolbar.clj)
       ".output-held-chip"
       {:-fx-background-color accent-warning
        :-fx-background-radius 8
        :-fx-padding ["2px" "8px"]
        :-fx-text-fill bg-primary
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       
       ;; A laser still below its minimum fps after the fit guard; a click
       ;; opens its card (views/toolbar.clj, zones plan Z3)
       ".low-fps-chip"
       {:-fx-background-color accent-warning
        :-fx-background-radius 8
        :-fx-padding ["2px" "8px"]
        :-fx-text-fill bg-primary
        :-fx-font-size 11
        :-fx-font-weight "bold"
        :-fx-cursor "hand"}
       
       ;; A rig card whose laser is below its minimum fps
       ;; (views/components/rig_strip.clj)
       ".rig-card-low"
       {:-fx-border-color accent-warning
        :-fx-border-width 2}
       
       ;; A rig card's load meter: green, amber from 80%, red past 100%
       ".load-meter-track"
       {:-fx-background-color bg-subtle
        :-fx-background-radius 3}
       
       ".load-meter"
       {:-fx-background-color accent-success
        :-fx-background-radius 3}
       
       ".load-meter.load-meter-near"
       {:-fx-background-color accent-warning}
       
       ".load-meter.load-meter-over"
       {:-fx-background-color accent-danger}
       
       ;; A link in the toolbar (its style class replaces .hyperlink's)
       ".toolbar-link"
       {:-fx-text-fill accent-info
        :-fx-font-size 11
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-underline true}}
       
       ;; Beat Indicator
       ".beat-indicator"
       {:-fx-padding ["0" "4px"]}
       
       ".beat-square"
       {:-fx-background-color bg-subtle
        :-fx-background-radius "2px"
        :-fx-border-color border-default
        :-fx-border-radius "2px"
        :-fx-border-width "1px"}
       
       ".beat-square.beat-active"
       {:-fx-background-color text-muted}
       
       ".beat-square.beat-active.beat-downbeat"
       {:-fx-background-color accent-info}
       
       ".container-primary"
       {:-fx-background-color bg-primary}
       
       ".container-surface"
       {:-fx-background-color bg-primary}
       
       ".container-elevated"
       {:-fx-background-color bg-elevated}})))
