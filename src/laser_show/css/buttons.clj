(ns laser-show.css.buttons
  "Button styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Transport buttons (play, stop, etc.)
   - Action buttons (primary, secondary, danger)
   - Small utility buttons
   
   Usage:
   Include (::css/url buttons) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def buttons
  "Button styles for the application."
  (css/register ::buttons
    (let [;; Use semantic colors from theme
          {:keys [bg-interactive bg-hover bg-active
                  text-primary
                  accent-success]} theme/semantic-colors
          {:keys [accent-success-hover]} theme/computed-colors]
      
      {
       ;; Small button variant (used in toolbar)
       ".btn-sm"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-cursor "hand"
        :-fx-background-radius 4
        :-fx-font-size 11
        :-fx-padding ["2px" "8px"]
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       
       ;; Transport Buttons (for toolbar)
       
       
       ".transport-btn"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-font-size 14
        :-fx-min-width 40
        :-fx-min-height 32
        :-fx-background-radius 4
        :-fx-alignment "center"
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       ;; Active transport button (e.g., playing state)
       ".transport-btn-active"
       {:-fx-background-color accent-success
        :-fx-text-fill text-primary
        :-fx-font-size 14
        :-fx-min-width 40
        :-fx-min-height 32
        :-fx-background-radius 4
        :-fx-alignment "center"
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color accent-success-hover}}
       
       
       ;; Tab Buttons
       
       
       ".tab-btn"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-background-radius ["4px" "4px" "0px" "0px"]
        :-fx-padding ["8px" "16px"]
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color accent-success-hover}}
       
       ".tab-btn-active"
       {:-fx-background-color accent-success
        :-fx-text-fill text-primary
        :-fx-background-radius ["4px" "4px" "0px" "0px"]
        :-fx-padding ["8px" "16px"]
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color accent-success}}
       
       
       ;; Bank Item Buttons (for effect banks and preset banks)
       
       
       ".bank-item-btn"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-padding ["4px" "8px"]
        :-fx-cursor "hand"
        :-fx-background-radius 3
        
        ":hover"
        {:-fx-background-color bg-hover}
        
        ":pressed"
        {:-fx-background-color bg-active}}
       
       ;; Legacy effect bank button (alias for bank-item-btn)
       ".effect-btn"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-padding ["4px" "8px"]
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       
       ;; Retrigger Button (for modulator controls)
       
       
       ".retrigger-button"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-padding ["2px" "8px"]
        :-fx-cursor "hand"
        :-fx-background-radius 3
        
        ":hover"
        {:-fx-background-color accent-success
         :-fx-text-fill text-primary}
        
        ":pressed"
        {:-fx-background-color accent-success-hover}}
       
       
       ;; Modulator Toggle Button
       
       
       ".modulator-toggle"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-padding ["2px" "4px"]
        :-fx-cursor "hand"
        :-fx-background-radius 3
        :-fx-min-width 28
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       ;; Modulator toggle when active (modulated)
       ".modulator-toggle-active"
       {:-fx-background-color accent-success
        :-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-padding ["2px" "4px"]
        :-fx-cursor "hand"
        :-fx-background-radius 3
        :-fx-min-width 28
        
        ":hover"
        {:-fx-background-color accent-success-hover}}})))
