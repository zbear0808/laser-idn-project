(ns laser-show.css.theme
  "Core theme definitions for the laser show UI using cljfx/css.
   
   This namespace provides:
   - Color palette constants accessible from code (keyword keys)
   - Base CSS classes for typography, containers, and common elements
   - Theme URL for stylesheet loading
   
   Usage:
   Include (::css/url theme) in your scene's :stylesheets vector.
   Access color constants directly: (::bg-dark theme)"
  (:require [cljfx.css :as css]))


;; Color Palette Constants (for reference/documentation)

;; These colors define our dark theme. They are also available as keyword keys
;; in the registered theme map for programmatic access.

(def ^:private palette
  {:bg-darkest   "#1E1E1E"   ; Main background, root
   :bg-dark      "#252525"   ; Panel backgrounds
   :bg-medium    "#2D2D2D"   ; Secondary panels, cards
   :bg-light     "#3D3D3D"   ; Interactive elements default
   :bg-hover     "#404040"   ; Hover state
   :bg-active    "#505050"   ; Active/pressed state
   
   :text-primary   "#E0E0E0"   ; Main text
   :text-secondary "#B0B0B0"   ; Secondary text
   :text-muted     "#808080"   ; Disabled/hint text
   :text-disabled  "#606060"   ; Disabled text
   
   :border-dark    "#1E1E1E"   ; Dark borders
   :border-medium  "#3D3D3D"   ; Standard borders
   
   :accent-primary "#4CAF50"   ; Green - primary action, active state
   :accent-hover   "#5CAF60"   ; Green hover
   :accent-blue    "#2196F3"   ; Blue - selection
   :accent-blue-dark "#4A6FA5" ; Darker blue for tabs
   :accent-orange  "#FF9800"   ; Orange - warnings
   :accent-red     "#D32F2F"}) ; Red - danger/delete

(def theme
  "Main application theme with colors and base styles.
   
   Access colors via keyword keys:
   (::bg-dark theme) => \"#252525\"
   
   Load CSS via URL:
   (::css/url theme)"
  (css/register ::theme
    (let [{:keys [bg-darkest bg-dark bg-medium bg-light bg-hover bg-active
                  text-primary text-secondary text-muted text-disabled
                  border-dark border-medium
                  accent-primary accent-hover accent-blue accent-blue-dark
                  accent-orange accent-red]} palette]
      
      {
       ;; Color Constants (accessible from code)
       
       ::bg-darkest bg-darkest
       ::bg-dark bg-dark
       ::bg-medium bg-medium
       ::bg-light bg-light
       ::bg-hover bg-hover
       ::bg-active bg-active
       
       ::text-primary text-primary
       ::text-secondary text-secondary
       ::text-muted text-muted
       ::text-disabled text-disabled
       
       ::border-dark border-dark
       ::border-medium border-medium
       
       ::accent-primary accent-primary
       ::accent-hover accent-hover
       ::accent-blue accent-blue
       ::accent-blue-dark accent-blue-dark
       ::accent-orange accent-orange
       ::accent-red accent-red
       
       
       ;; Root Styles
       
       ".root"
       {:-fx-base bg-darkest
        :-fx-background bg-darkest}
       
       
       ;; Typography Classes
       
       
       ;; Primary text styles
       ".text-primary"
       {:-fx-text-fill text-primary}
       
       ".text-secondary"
       {:-fx-text-fill text-secondary}
       
       ".text-muted"
       {:-fx-text-fill text-muted}
       
       ;; Headings
       ".heading"
       {:-fx-text-fill text-primary
        :-fx-font-size 16
        :-fx-font-weight "bold"}
       
       ".heading-sm"
       {:-fx-text-fill text-primary
        :-fx-font-size 14
        :-fx-font-weight "bold"}
       
       ;; Body text
       ".body-text"
       {:-fx-text-fill text-primary
        :-fx-font-size 12}
       
       ".body-text-sm"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}
       
       ".hint-text"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-style "italic"}
       
       
       ;; Container/Panel Classes
       
       
       ".panel-dark"
       {:-fx-background-color bg-darkest}
       
       ".panel-medium"
       {:-fx-background-color bg-medium}
       
       ".panel-light"
       {:-fx-background-color bg-dark}
       
       ;; Panel with standard padding
       ".panel-padded"
       {:-fx-padding 16}
       
       ".panel-padded-sm"
       {:-fx-padding 8}
       
       
       ;; Scroll Pane Styles
       
       
       ".scroll-pane-transparent"
       {:-fx-background-color "transparent"
        :-fx-background "transparent"
        
        " > .viewport"
        {:-fx-background-color "transparent"}}})))
