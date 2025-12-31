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
    (let [{bg-light    ::theme/bg-light
           bg-hover    ::theme/bg-hover
           bg-active   ::theme/bg-active
           text-primary ::theme/text-primary
           accent-primary ::theme/accent-primary
           accent-hover ::theme/accent-hover
           accent-red  ::theme/accent-red} theme/theme]
      
      {
       ;; Base Button Styles
       
       
       ;; Standard button - dark theme
       ".btn"
       {:-fx-background-color bg-light
        :-fx-text-fill text-primary
        :-fx-cursor "hand"
        :-fx-background-radius 4
        :-fx-padding ["4px" "12px"]
        
        ":hover"
        {:-fx-background-color bg-hover}
        
        ":pressed"
        {:-fx-background-color bg-active}}
       
       ;; Small button variant
       ".btn-sm"
       {:-fx-background-color bg-light
        :-fx-text-fill text-primary
        :-fx-cursor "hand"
        :-fx-background-radius 4
        :-fx-font-size 11
        :-fx-padding ["2px" "8px"]
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       ;; Extra small button
       ".btn-xs"
       {:-fx-background-color bg-light
        :-fx-text-fill text-primary
        :-fx-cursor "hand"
        :-fx-background-radius 3
        :-fx-font-size 9
        :-fx-padding ["2px" "6px"]
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       
       ;; Action Button Variants
       
       
       ;; Primary action button (green)
       ".btn-primary"
       {:-fx-background-color accent-primary
        :-fx-text-fill text-primary
        :-fx-cursor "hand"
        :-fx-background-radius 4
        :-fx-padding ["6px" "20px"]
        
        ":hover"
        {:-fx-background-color accent-hover}}
       
       ;; Danger button (red) - for delete actions
       ".btn-danger"
       {:-fx-background-color accent-red
        :-fx-text-fill text-primary
        :-fx-cursor "hand"
        :-fx-background-radius 4
        :-fx-padding ["4px" "12px"]
        
        ":hover"
        {:-fx-background-color "#B71C1C"}}
       
       ;; Small danger button
       ".btn-danger-sm"
       {:-fx-background-color accent-red
        :-fx-text-fill text-primary
        :-fx-cursor "hand"
        :-fx-font-size 9
        :-fx-padding ["2px" "6px"]
        
        ":hover"
        {:-fx-background-color "#B71C1C"}}
       
       
       ;; Transport Buttons (for toolbar)
       
       
       ".transport-btn"
       {:-fx-background-color bg-light
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
       {:-fx-background-color accent-primary
        :-fx-text-fill text-primary
        :-fx-font-size 14
        :-fx-min-width 40
        :-fx-min-height 32
        :-fx-background-radius 4
        :-fx-alignment "center"
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color accent-hover}}
       
       
       ;; Tab Buttons
       
       
       ".tab-btn"
       {:-fx-background-color bg-light
        :-fx-text-fill text-primary
        :-fx-background-radius ["4px" "4px" "0px" "0px"]
        :-fx-padding ["8px" "16px"]
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color accent-hover}}
       
       ".tab-btn-active"
       {:-fx-background-color accent-primary
        :-fx-text-fill text-primary
        :-fx-background-radius ["4px" "4px" "0px" "0px"]
        :-fx-padding ["8px" "16px"]
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color accent-primary}}
       
       
       ;; Effect Bank Buttons
       
       
       ".effect-btn"
       {:-fx-background-color bg-active
        :-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-padding ["4px" "8px"]
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color bg-hover}}})))
