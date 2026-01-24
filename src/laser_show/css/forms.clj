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
       
       
       ;; ===========================================
       ;; Checkbox Styling
       ;; ===========================================
       ;; Structure: .check-box > .box > .mark
       ;;   - .check-box: the root container (includes label text)
       ;;   - .box: the square checkbox container
       ;;   - .mark: the checkmark shape inside the box
       ;;
       ;; What you CAN modify:
       ;;   - Box background color (:-fx-background-color on .box)
       ;;   - Box corner rounding (:-fx-background-radius on .box)
       ;;   - Hover background color (:-fx-background-color on :hover > .box)
       ;;   - Checkmark color (:-fx-background-color on :selected > .box > .mark)
       ;;
       ;; What you CANNOT modify (these don't work in CSS):
       ;;   - Checkmark shape (:-fx-shape on .mark breaks rendering)
       ;;   - Checkmark size (sizing properties have no effect)
       ;;   - Individual borders (border styling not reliable)
       ;; ===========================================
       
       ".check-box"
       {;; === .box: The square checkbox container ===
        ;; This is the clickable square area
        " > .box"
        {:-fx-background-color "#2A2A2A"  ; Dark gray background
         :-fx-background-radius 1}        ; Slight rounding of corners
        
        ;; === Hover state: when mouse is over the checkbox ===
        ":hover > .box"
        {:-fx-background-color "#4A4A4A"} ; Much lighter gray on hover
        
        ;; === Selected state: the checkmark when checkbox is checked ===
        ;; .mark is the checkmark shape inside .box
        ":selected > .box > .mark"
        {:-fx-background-color "white"}}}))) ; White checkmark
