(ns laser-show.css.menus
  "Dark theme styling for menus and context menus using cljfx/css.
   
   This namespace provides:
   - menu-theme: Registered CSS style map for all menu components
   - Dark mode styling for dropdowns, menu items, separators, and hover states
   
   Usage:
   Include (::css/url menu-theme) in your scene's :stylesheets vector"
  (:require [cljfx.css :as css]))

;; ============================================================================
;; Menu Theme Definition
;; ============================================================================

(def menu-theme
  "Dark theme styling for menu components.
   
   This creates a registered cljfx/css style map that can be loaded as a URL.
   The URL is available at (::css/url menu-theme).
   
   Colors and style constants are available as keyword keys:
   - ::background - Main menu background
   - ::background-hover - Hover state background
   - ::background-focused - Focused state background
   - ::text - Text color
   - ::border - Border color
   - ::separator - Separator line color"
  (css/register ::menu-theme
    (let [;; Color palette for menus
          bg "#2d2d2d"
          bg-hover "#404040"
          bg-focused "#3d3d3d"
          text-color "#e0e0e0"
          border-color "#3d3d3d"]
      
      {;; Store colors as keywords for code access
       ::background bg
       ::background-hover bg-hover
       ::background-focused bg-focused
       ::text text-color
       ::border border-color
       ::separator border-color
       
       ;; ====================================================================
       ;; Context Menu Popup Styling
       ;; ====================================================================
       ;; The popup container that appears when clicking a menu
       ".context-menu" 
       {:-fx-background-color bg
        :-fx-border-color border-color
        :-fx-border-width "1px"
        :-fx-background-radius "4px"
        :-fx-border-radius "4px"
        #_#_:-fx-padding "4px"
        #_#_:-fx-effect "dropshadow(three-pass-box, rgba(0,0,0,0.5), 10, 0, 0, 2)"}
       
       ;; ====================================================================
       ;; Menu Item Styling
       ;; ====================================================================
       ;; Individual items within the menu
       ".menu-item"
       {:-fx-background-color bg
        :-fx-text-fill text-color
        #_#_:-fx-padding ["5px" "20px" "5px" "20px"]
        :-fx-background-radius "2px"
        
        ;; Hover state - when mouse is over the item
        ":hover"
        {:-fx-background-color bg-hover
         :-fx-cursor "hand"}
        
        ;; Focused state - when navigating with keyboard
        ":focused"
        {:-fx-background-color bg-focused
         #_#_:-fx-border-color "#4CAF50"
         :-fx-border-width "1px"}
        
        ;; Label text within menu item
        " .label"
        {:-fx-text-fill text-color}}
       
       ;; ====================================================================
       ;; Disabled Menu Items
       ;; ====================================================================
       ".menu-item:disabled"
       {:-fx-opacity 0.4
        :-fx-cursor "default"
        
        " .label"
        {:-fx-text-fill "#707070"}}
       
       ;; ====================================================================
       ;; Separator Styling
       ;; ====================================================================
       ".separator-menu-item"
       {:-fx-padding ["2px" "0px"]
        
        " .line"
        {:-fx-border-color border-color
         :-fx-border-width "1px 0 0 0"
         :-fx-padding "0px"}}})))
