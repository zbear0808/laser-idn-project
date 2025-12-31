(ns laser-show.css.forms
  "Form element styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Text fields and inputs
   - Labels
   - Sliders
   - Combo boxes
   - Checkboxes
   
   Usage:
   Include (::css/url forms) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def forms
  "Form element styles for the application."
  (css/register ::forms
    (let [{bg-light     ::theme/bg-light
           bg-hover     ::theme/bg-hover
           text-primary ::theme/text-primary
           text-secondary ::theme/text-secondary
           text-muted   ::theme/text-muted} theme/theme]
      
      {
       ;; Text Fields
       
       
       ".text-field-dark"
       {:-fx-background-color bg-light
        :-fx-text-fill text-primary
        :-fx-font-size 12
        :-fx-padding ["4px" "8px"]
        :-fx-background-radius 4
        
        ":focused"
        {:-fx-background-color bg-hover}}
       
       ".text-field-dark-sm"
       {:-fx-background-color bg-light
        :-fx-text-fill text-primary
        :-fx-font-size 11
        :-fx-padding ["2px" "4px"]
        :-fx-background-radius 3}
       
       
       ;; Labels
       
       
       ".label-primary"
       {:-fx-text-fill text-primary}
       
       ".label-secondary"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}
       
       ".label-muted"
       {:-fx-text-fill text-muted
        :-fx-font-size 11}
       
       ;; Label with colon (for form fields)
       ".label-field"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}
       
       ;; Section header labels
       ".label-header"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       
       
       ;; Combo Boxes
       
       
       ".combo-box-dark"
       {:-fx-background-color bg-light
        :-fx-text-fill text-secondary
        :-fx-font-size 11}
       
       
       ;; Checkboxes
       
       
       ".check-box-dark"
       {:-fx-text-fill text-primary}
       
       
       ;; Sliders
       
       
       ;; Note: Slider styling in JavaFX requires targeting internal elements
       ;; This provides basic track/thumb styling
       ".slider-dark"
       {" .track"
        {:-fx-background-color bg-light
         :-fx-background-radius 4}
        
        " .thumb"
        {:-fx-background-color text-secondary}}})))
