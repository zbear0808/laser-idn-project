(ns laser-show.css.forms
  "Form element styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Text fields and inputs
   - Labels
   - Checkboxes
   
   Usage:
   Include (::css/url forms) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def forms
  "Form element styles for the application."
  (css/register ::forms
    (let [{:keys [bg-interactive text-primary text-secondary border-default]} theme/semantic-colors
          {:keys [accent-success]} theme/semantic-colors]
      
      {;; Standard text field styling (dark background, white text)
       ".text-field"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-background-radius 3}
       
       ;; Dark text field variant (used in config panels)
       ".text-field-dark"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-background-radius 3}
       
       ;; Small text field (used in dialogs and parameter editors)
       ".text-field-dark-sm"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-font-size 11
        :-fx-padding ["2px" "4px"]
        :-fx-background-radius 3}
       
       ;; Secondary label (used throughout UI)
       ".label-secondary"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}
       
       
       ;; Checkbox Styling
       
       
       ;; Checkbox base - the outer box
       ".check-box"
       {:-fx-text-fill text-primary
        
        ;; The box itself
        " > .box"
        {:-fx-background-color "#2A2A2A"
         :-fx-border-color border-default
         :-fx-border-width 2
         :-fx-border-radius 3
         :-fx-background-radius 3
         :-fx-padding 3
         
         ;; The checkmark inside - invisible when not selected
         " > .mark"
         {:-fx-background-color "transparent"
          :-fx-padding 4
          :-fx-shape "M 0 5 L 4 9 L 12 1"}}
        
        ;; Hover state
        ":hover > .box"
        {:-fx-border-color text-primary}
        
        ;; Selected state - checkmark becomes visible
        ":selected > .box"
        {:-fx-background-color "#2A2A2A"
         :-fx-border-color accent-success
         
         " > .mark"
         {:-fx-background-color "white"}}
        
        ;; Selected + hover
        ":selected:hover > .box"
        {:-fx-background-color "#2A2A2A"
         :-fx-border-color text-primary}}})))
