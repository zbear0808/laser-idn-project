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
    (let [;; Use semantic colors from theme
          {:keys [bg-surface bg-elevated bg-interactive
                  text-primary text-muted]} theme/semantic-colors
          {:keys [blue-600]} theme/base-colors]
      
      {
       ;; Dialog Root
       
       
       ;; Dark dialog root override
       ".dialog-root"
       {:-fx-base bg-elevated
        :-fx-background bg-elevated}
       
       
       ;; Dialog Sections
       
       
       ".dialog-header"
       {:-fx-background-color bg-surface
        :-fx-padding 12}
       
       ".dialog-content"
       {:-fx-background-color bg-elevated}
       
       ".dialog-footer"
       {:-fx-background-color bg-surface
        :-fx-padding 12}
       
       
       ;; Tab Pane in Dialogs
       
       
       ;; Tab header background
       ".tab-pane"
       {" > .tab-header-area"
        {" > .tab-header-background"
         {:-fx-background-color bg-surface}}}
       
       ;; Individual tab styling
       ".tab"
       {:-fx-background-color bg-interactive
        
        ":selected"
        {:-fx-background-color blue-600}
        
        " .tab-label"
        {:-fx-text-fill text-primary}}
       
       
       ;; Effect Chain Editor Specific
       
       
       ;; Chain panel (left side)
       ".chain-panel"
       {:-fx-background-color bg-surface
        :-fx-padding 8}
       
       ;; Effect bank panel (right side)
       ".effect-bank-panel"
       {:-fx-background-color bg-elevated}
       
       ;; Parameter panel (bottom right)
       ".param-panel"
       {:-fx-background-color bg-surface
        :-fx-padding 8}
       
       
       ;; Empty State Messages
       
       
       ".empty-state-text"
       {:-fx-text-fill text-muted
        :-fx-font-style "italic"
        :-fx-font-size 11}
       
       
       ;; Selection Info
       
       
       ".selection-count"
       {:-fx-text-fill blue-600
        :-fx-font-size 9}
       
       
       ;; Scroll Pane Transparency (for dialogs)
       
       
       ".scroll-pane"
       {:-fx-background-color "transparent"
        
        " > .viewport"
        {:-fx-background-color "transparent"}}})))
