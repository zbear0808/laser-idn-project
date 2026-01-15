(ns laser-show.css.title-bar
  "Dark theme styling for menus and context menus using cljfx/css.
   
   This namespace provides:
   - menu-theme: Registered CSS style map for all menu components
   - Dark mode styling for dropdowns, menu items, separators, and hover states
   
   Usage:
   Include (::css/url menu-theme) in your scene's :stylesheets vector"
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def menu-theme
  "Dark theme styling for menu components.
   
   This creates a registered cljfx/css style map that can be loaded as a URL.
   The URL is available at (::css/url menu-theme)."
  (css/register ::menu-theme
    (let [;; Use semantic colors from theme
          {:keys [bg-elevated bg-hover bg-interactive
                  text-primary text-disabled
                  border-default bg-primary]} theme/semantic-colors]
      
      {;; Store colors as keywords for code access (legacy support)
       ::background bg-elevated
       ::background-hover bg-hover
       ::background-focused bg-interactive
       ::text text-primary
       ::border border-default
       ::separator border-default
       ::header-background bg-primary

       ".header-bar"
       {:-fx-background-color bg-primary}

       ".window-title"
       {:-fx-text-fill text-primary
        :-fx-font-size "13px"
        :-fx-font-weight "normal"}

       ".menu-container"
       {:-fx-background-color bg-primary}

       ".menu-bar-transparent"
       {:-fx-background-color :transparent
        :-fx-text-fill text-primary
        :-fx-border-color :transparent
        :-fx-border-width "0"
        :-fx-padding "0"}

       ".menu-label"
       {:-fx-text-fill text-primary}

       ".menu"
       {:-fx-padding "2px"
        :-fx-background-radius "4px"
        
        ":hover"
        {:-fx-background-color bg-interactive}
        
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
       {:-fx-background-color bg-elevated
        :-fx-border-color border-default
        :-fx-border-width "1px"
        :-fx-background-radius "4px"
        :-fx-border-radius "4px"}

       ".menu-item"
       {:-fx-background-color bg-elevated
        :-fx-text-fill text-primary
        :-fx-background-radius "2px"

        ":hover"
        {:-fx-background-color bg-hover
         :-fx-cursor "hand"}

        ":focused"
        {:-fx-background-color bg-interactive
         :-fx-border-width "1px"}

        " .label"
        {:-fx-text-fill text-primary}}


       ".menu-item:disabled"
       {:-fx-opacity 0.4
        :-fx-cursor "default"

        " .label"
        {:-fx-text-fill text-disabled}}

       
       ".separator-menu-item"
       {:-fx-padding ["2px" "0px"]

        " .line"
        {:-fx-border-color border-default
         :-fx-border-width "1px 0 0 0"
         :-fx-padding "0px"}}})))
