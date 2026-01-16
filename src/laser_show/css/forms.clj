(ns laser-show.css.forms
  "Form element styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Text fields and inputs
   - Labels
   
   Usage:
   Include (::css/url forms) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def forms
  "Form element styles for the application."
  (css/register ::forms
    (let [{:keys [bg-interactive text-primary text-secondary]} theme/semantic-colors]
      
      {;; Small text field (used in dialogs and parameter editors)
       ".text-field-dark-sm"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-font-size 11
        :-fx-padding ["2px" "4px"]
        :-fx-background-radius 3}
       
       ;; Secondary label (used throughout UI)
       ".label-secondary"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}})))
