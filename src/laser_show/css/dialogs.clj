(ns laser-show.css.dialogs
  "Dialog-specific styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Dialog containers and sections
   - Effect chain editor dialog
   - Tab pane styling for dialogs
   
   Usage:
   Include (::css/url dialogs) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def dialogs
  "Dialog-specific styles for the application."
  (css/register ::dialogs
    (let [{bg-dark        ::theme/bg-dark
           bg-medium      ::theme/bg-medium
           bg-light       ::theme/bg-light
           text-primary   ::theme/text-primary
           text-muted     ::theme/text-muted
           accent-blue-dark ::theme/accent-blue-dark} theme/theme]
      
      {
       ;; Dialog Root
       
       
       ;; Dark dialog root override
       ".dialog-root"
       {:-fx-base bg-medium
        :-fx-background bg-medium}
       
       
       ;; Dialog Sections
       
       
       ".dialog-header"
       {:-fx-background-color bg-dark
        :-fx-padding 12}
       
       ".dialog-content"
       {:-fx-background-color bg-medium}
       
       ".dialog-footer"
       {:-fx-background-color bg-dark
        :-fx-padding 12}
       
       
       ;; Tab Pane in Dialogs
       
       
       ;; Tab header background
       ".tab-pane"
       {" > .tab-header-area"
        {" > .tab-header-background"
         {:-fx-background-color bg-dark}}}
       
       ;; Individual tab styling
       ".tab"
       {:-fx-background-color bg-light
        
        ":selected"
        {:-fx-background-color accent-blue-dark}
        
        " .tab-label"
        {:-fx-text-fill text-primary}}
       
       
       ;; Effect Chain Editor Specific
       
       
       ;; Chain panel (left side)
       ".chain-panel"
       {:-fx-background-color bg-dark
        :-fx-padding 8}
       
       ;; Effect bank panel (right side)
       ".effect-bank-panel"
       {:-fx-background-color bg-medium}
       
       ;; Parameter panel (bottom right)
       ".param-panel"
       {:-fx-background-color "#2A2A2A"
        :-fx-padding 8}
       
       
       ;; Empty State Messages
       
       
       ".empty-state-text"
       {:-fx-text-fill "#606060"
        :-fx-font-style "italic"
        :-fx-font-size 11}
       
       
       ;; Selection Info
       
       
       ".selection-count"
       {:-fx-text-fill accent-blue-dark
        :-fx-font-size 9}
       
       
       ;; Scroll Pane Transparency (for dialogs)
       
       
       ".scroll-pane"
       {:-fx-background-color "transparent"
        
        " > .viewport"
        {:-fx-background-color "transparent"}}})))
