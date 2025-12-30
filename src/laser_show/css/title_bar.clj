(ns laser-show.css.title-bar
  "Dark theme styling for menus and context menus using cljfx/css.
   
   This namespace provides:
   - menu-theme: Registered CSS style map for all menu components
   - Dark mode styling for dropdowns, menu items, separators, and hover states
   
   Usage:
   Include (::css/url menu-theme) in your scene's :stylesheets vector"
  (:require [cljfx.css :as css]))

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
          border-color "#3d3d3d"
          header-bg "#1e1e1eff"]
      
      {;; Store colors as keywords for code access
       ::background bg
       ::background-hover bg-hover
       ::background-focused bg-focused
       ::text text-color
       ::border border-color
       ::separator border-color
       ::header-background header-bg

       ".header-bar"
       {:-fx-background-color header-bg}

       ".menu-container"
       {:-fx-background-color header-bg}

       ".menu-bar-transparent"
       {:-fx-background-color :transparent
        :-fx-text-fill text-color
        :-fx-border-color :transparent
        :-fx-border-width "0"
        :-fx-padding "0"}

       ".menu-label"
       {:-fx-text-fill text-color}

       ".menu"
       {:-fx-padding "2px"
        :-fx-background-radius "4px"
        
        ":hover"
        {:-fx-background-color "#3d3d3d"}
        
        ;; Hide the dropdown arrow 
        " > .arrow-button"
        {:-fx-padding "0"
         :-fx-min-width "0"
         :-fx-pref-width "0"
         :-fx-max-width "0"} 
        " > .arrow-button > .arrow"
        {:-fx-padding "0"
         :-fx-min-width "0"
         :-fx-pref-width "0"
         :-fx-max-width "0"
         :-fx-shape ""}}

       ".context-menu"
       {:-fx-background-color bg
        :-fx-border-color border-color
        :-fx-border-width "1px"
        :-fx-background-radius "4px"
        :-fx-border-radius "4px"}

       ".menu-item"
       {:-fx-background-color bg
        :-fx-text-fill text-color
        :-fx-background-radius "2px"

        ":hover"
        {:-fx-background-color bg-hover
         :-fx-cursor "hand"}

        ":focused"
        {:-fx-background-color bg-focused
         :-fx-border-width "1px"}

        " .label"
        {:-fx-text-fill text-color}}


       ".menu-item:disabled"
       {:-fx-opacity 0.4
        :-fx-cursor "default"

        " .label"
        {:-fx-text-fill "#707070"}}

       
       ".separator-menu-item"
       {:-fx-padding ["2px" "0px"]

        " .line"
        {:-fx-border-color border-color
         :-fx-border-width "1px 0 0 0"
         :-fx-padding "0px"}}})))
