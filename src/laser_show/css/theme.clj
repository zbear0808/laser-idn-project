(ns laser-show.css.theme
  "Core theme definitions for the laser show UI using cljfx/css.
   
   This namespace provides:
   - Base color palette (raw hex values)
   - Semantic colors (meaningful names mapped to base colors)
   - Computed color variants (hover, active, disabled states)
   - Base CSS classes for typography, containers, and common elements
   - Theme URL for stylesheet loading
   
   Color Architecture:
   1. base-colors     - Foundation palette, only raw hex values here
   2. semantic-colors - Meaningful names (bg-primary, accent-success, etc.)
   3. computed-colors - Auto-derived state variants using color utilities
   
   Usage:
   Include (::css/url theme) in your scene's :stylesheets vector.
   Access color constants directly: (::bg-primary theme)"
  (:require [cljfx.css :as css]
            [laser-show.css.colors :as colors]))



;; Base Colors - Raw hex values (single source of truth)

;;
;; SIMPLIFIED Color Palette:
;; - 4 grays: background, interactive, border, text-muted
;; - 4 accents: green (success), blue (selection), orange (warning), red (danger)
;; - 4 category colors for visual grouping

(def base-colors
  "Foundation color palette - raw hex values only.
   Use semantic-colors for actual UI usage."
  {;; Grayscale - 4 functional levels
   :bg-base      "#121212"   ; Main background (darkest)
   :interactive  "#3D3D3D"   ; Buttons, inputs, cell content
   :border       "#505050"   ; Borders, separators
   :hover        "#606060"   ; Hover states (more visible than border)
   :text-muted   "#808080"   ; Muted/secondary text
   
   ;; Accent colors
   :green   "#3D8B40"   ; Success, primary action buttons (darker for better hover contrast)
   :blue    "#4992db"   ; Selection, active state, info
   :orange  "#FF9800"   ; Warning
   :red     "#fd3636"   ; Danger, error
   
   ;; Category accent colors (for visual grouping in hierarchies)
   :cat-blue   "#4A7B9D"
   :cat-purple "#6B5B8C"
   :cat-green  "#5B8C6B"
   :cat-brown  "#8C7B5B"})



;; Semantic Colors - Meaningful names mapped to base colors


(def semantic-colors
  "Colors with semantic meaning - use these in your code.
   Maps meaningful UI concepts to base color values."
  (let [{:keys [bg-base interactive border hover text-muted green blue orange red]} base-colors]
    {;; Background hierarchy
     :bg-primary     bg-base      ; Root/window background (darkest)
     :bg-surface     bg-base      ; Panel backgrounds (same as primary for flat look)
     :bg-elevated    bg-base      ; Cards, dialogs, menus (same for flat look)
     :bg-interactive interactive  ; Buttons, inputs (default state)
     :bg-hover       hover        ; Hover state (more visible)
     :bg-active      border       ; Active/pressed state
     
     :text-primary   "#ffffff"    ; Main text, headings (white)
     :text-secondary "#B0B0B0"    ; Secondary text
     :text-muted     text-muted   ; Hints, disabled text
     
     ;; Borders
     :border-subtle  interactive  ; Subtle borders
     :border-default border       ; Standard borders
     
     ;; Accent colors (semantic meanings)
     :accent-success green        ; Positive actions, service buttons
     :accent-info    blue         ; Information, help, connecting
     :accent-warning orange       ; Warnings, attention needed
     :accent-danger  red          ; Destructive actions, errors
     
     ;; Selection/Active state - unified blue
     :selection-bg   blue}))



;; Computed Colors - Auto-derived state variants


(def computed-colors
  "Automatically derived color variants for different UI states.
   Generated using the color utility functions."
  (let [{:keys [bg-interactive accent-success accent-danger selection-bg text-muted border]} semantic-colors
        {:keys [blue interactive]} base-colors]
    {;; Interactive element states
     :bg-interactive-hover  (colors/lighten bg-interactive 0.10)
     :bg-interactive-active (colors/darken bg-interactive 0.10)
     
     ;; Success/primary action states
     :accent-success-hover  (colors/lighten accent-success 0.20)
     :accent-success-active (colors/darken accent-success 0.15)
     
     ;; Danger states
     :accent-danger-hover   (colors/darken accent-danger 0.15)
     :accent-danger-active  (colors/darken accent-danger 0.25)
     
     ;; Selection states (used for active cells, effects cells)
     :selection-hover       (colors/lighten selection-bg 0.10)
     :selection-focus       (colors/lighten selection-bg 0.15)
     
     ;; Drop target colors
     :drop-target-bg        blue
     :drop-target-border    blue
     :drop-target-glow      (colors/with-alpha blue 0.6)
     
     ;; Disabled variants
     :text-disabled-muted   (colors/with-alpha text-muted 0.6)
     :bg-disabled           (colors/desaturate interactive 0.5)
     
     ;; Cell content (used for cells with content but not active)
     :cell-content          border
     :cell-content-hover    (colors/lighten border 0.12)}))



;; Special Purpose Colors - Domain-specific


(def category-colors
  "Colors for visual differentiation of categories/types.
   Used for group headers, preset categories, etc."
  (let [{:keys [cat-blue cat-purple cat-green cat-brown]} base-colors]
    {:depth-0   cat-blue    ; Blue - first level
     :depth-1   cat-purple  ; Purple - second level
     :depth-2   cat-green   ; Green - third level  
     :depth-3   cat-brown   ; Brown - fourth level
     ;; Aliases for preset categories
     :geometric cat-blue
     :wave      cat-purple
     :beam      cat-green
     :abstract  cat-brown}))



;; Theme CSS Registration


(def theme
  "Main application theme with colors and base styles.
   
   Access colors via keyword keys:
   (::bg-primary theme) => \"#121212\"
   
   Load CSS via URL:
   (::css/url theme)"
  (css/register ::theme
    (let [{:keys [bg-primary bg-surface bg-elevated bg-interactive bg-hover bg-active
                  text-primary text-secondary text-muted
                  border-subtle border-default
                  accent-success accent-info accent-warning accent-danger
                  selection-bg]} semantic-colors
          {:keys [accent-success-hover accent-danger-hover]} computed-colors]
      
      {
       ;; Color Constants (accessible from code)
       
       
       ;; Background colors
       ::bg-primary bg-primary
       ::bg-surface bg-surface
       ::bg-elevated bg-elevated
       ::bg-interactive bg-interactive
       ::bg-hover bg-hover
       ::bg-active bg-active
       
       ;; Text colors
       ::text-primary text-primary
       ::text-secondary text-secondary
       ::text-muted text-muted
       
       ;; Border colors
       ::border-subtle border-subtle
       ::border-default border-default
       
       ;; Accent colors
       ::accent-success accent-success
       ::accent-info accent-info
       ::accent-warning accent-warning
       ::accent-danger accent-danger
       ::accent-success-hover accent-success-hover
       ::accent-danger-hover accent-danger-hover
       
       ;; Selection color
       ::selection-bg selection-bg
       
       
       
       ;; Root Styles
       
       
       ".root"
       {:-fx-base bg-primary
        :-fx-background bg-primary
        ;; Set default text color for dark theme
        :-fx-text-background-color text-primary
        :-fx-text-fill text-primary}
       
       
       
       ;; Container/Panel Classes
       
       
       ".panel-primary"
       {:-fx-background-color bg-primary}
       
       ".text-muted"
       {:-fx-text-fill text-muted}
       
       ".label-primary"
       {:-fx-text-fill text-primary}
       
       
       
       ;; Base Classes (for composition)
       
       
       ;; Panel with border - common bordered container pattern
       ".panel-bordered"
       {:-fx-border-color border-default
        :-fx-border-width 1
        :-fx-border-radius 4
        :-fx-background-radius 4}
       
       ;; Interactive item base - common clickable item pattern
       ".interactive-item"
       {:-fx-background-radius 4
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       
       ;; Scroll Pane Styles
       
       
       ;; Base scroll pane - transparent background (can be extended)
       ".scroll-pane-base"
       {:-fx-background-color "transparent"
        :-fx-background "transparent"
        
        " > .viewport"
        {:-fx-background-color "transparent"}}
       
       })))
