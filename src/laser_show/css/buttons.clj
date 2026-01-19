(ns laser-show.css.buttons
  "Button styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Transport buttons (play, stop, etc.)
   - Action buttons (primary, secondary, danger)
   - Icon buttons
   - Small utility buttons
   
   Usage:
   Include (::css/url buttons) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def buttons
  "Button styles for the application."
  (css/register ::buttons
    (let [;; Use semantic colors from theme
          {:keys [bg-primary bg-interactive bg-hover bg-active
                  text-primary text-muted border-default
                  accent-success accent-info accent-danger]} theme/semantic-colors
          {:keys [accent-success-hover accent-danger-hover]} theme/computed-colors
          {:keys [border]} theme/base-colors]
      
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
       
       
       ;; Tab Buttons (VS Code style - flush tabs with right/bottom border)
       
       
       ".tab-btn"
       {:-fx-background-color bg-primary
        :-fx-text-fill text-muted
        :-fx-background-radius 0
        :-fx-padding ["8px" "16px"]
        :-fx-cursor "hand"
        ;; Grey border on right and bottom
        :-fx-border-color ["transparent" border border "transparent"]
        :-fx-border-width [0 1 1 0]
        
        ":hover"
        {:-fx-background-color bg-hover
         :-fx-text-fill text-primary}}
       
       ".tab-btn-active"
       {:-fx-background-color bg-primary
        :-fx-text-fill text-primary
        :-fx-background-radius 0
        :-fx-padding ["8px" "16px"]
        :-fx-cursor "hand"
        ;; Active tab has blue top border, grey right border, no bottom border
        :-fx-border-color [accent-info border "transparent" "transparent"]
        :-fx-border-width [2 1 0 0]
        
        ":hover"
        {:-fx-background-color bg-primary}}
       
       
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
        {:-fx-background-color accent-success-hover}}
       
       
       
       ;; Action Buttons (Primary, Secondary, Danger)
       
       
       ;; Primary action button (green, for main actions like Save, Add)
       ".button-primary"
       {:-fx-background-color accent-success
        :-fx-text-fill text-primary
        :-fx-padding ["8px" "20px"]
        :-fx-cursor "hand"
        :-fx-background-radius 4
        
        ":hover"
        {:-fx-background-color accent-success-hover}
        
        ":disabled"
        {:-fx-background-color "#404040"
         :-fx-text-fill "#606060"
         :-fx-cursor "default"}}
       
       ;; Secondary action button (gray, for secondary actions)
       ".button-secondary"
       {:-fx-background-color border
        :-fx-text-fill text-primary
        :-fx-padding ["6px" "12px"]
        :-fx-cursor "hand"
        :-fx-background-radius 4
        
        ":hover"
        {:-fx-background-color bg-hover}
        
        ":disabled"
        {:-fx-background-color "#404040"
         :-fx-text-fill "#606060"
         :-fx-cursor "default"}}
       
       ;; Danger button (red, for destructive actions like Delete)
       ".button-danger"
       {:-fx-background-color "#663333"
        :-fx-text-fill text-primary
        :-fx-padding ["6px" "12px"]
        :-fx-cursor "hand"
        :-fx-background-radius 4
        
        ":hover"
        {:-fx-background-color accent-danger-hover}
        
        ":disabled"
        {:-fx-background-color "#404040"
         :-fx-text-fill "#606060"
         :-fx-cursor "default"}}
       
       ;; Info/Scan button (blue, for informational actions)
       ".button-info"
       {:-fx-background-color accent-info
        :-fx-text-fill text-primary
        :-fx-padding ["6px" "16px"]
        :-fx-cursor "hand"
        :-fx-background-radius 4
        
        ":hover"
        {:-fx-background-color "#1976D2"}
        
        ":disabled"
        {:-fx-background-color "#404040"
         :-fx-text-fill "#606060"
         :-fx-cursor "default"}}
       
       
       
       ;; Icon Buttons
       
       
       ;; Icon-only button (transparent background, for inline actions)
       ".button-icon"
       {:-fx-background-color "transparent"
        :-fx-text-fill text-muted
        :-fx-font-size 14
        :-fx-padding ["2px" "6px"]
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-text-fill text-primary
         :-fx-background-color bg-interactive}}
       
       ;; Add/Plus button (compact gray button)
       ".button-add"
       {:-fx-background-color border
        :-fx-text-fill text-primary
        :-fx-font-size 12
        :-fx-padding ["2px" "8px"]
        :-fx-cursor "hand"
        :-fx-background-radius 3
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       ;; Close/Remove button (small X button)
       ".button-close"
       {:-fx-background-color "transparent"
        :-fx-text-fill text-muted
        :-fx-font-size 14
        :-fx-padding ["2px" "6px"]
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-text-fill accent-danger}}})))
