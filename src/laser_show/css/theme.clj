(ns laser-show.css.theme
  "Core theme definitions for the laser show UI using cljfx/css.
   
   This namespace provides:
   - Base color palette (raw hex values)
   - Semantic colors (meaningful names mapped to base colors)
   - Computed color variants (hover, active, disabled states)
   - Special purpose colors (status indicators, categories, channels)
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


;; =============================================================================
;; Base Colors - Raw hex values (single source of truth)
;; =============================================================================
;;
;; Color Architecture:
;; - Backgrounds: 4 levels (base → surface → elevated → interactive)
;; - Interactive states: hover, active/pressed
;; - Text: 3 levels (primary, secondary, muted)
;; - Accents: semantic colors (success, info, warning, danger)
;; - Special: categories, channels, status indicators

(def base-colors
  "Foundation color palette - these are the only raw hex values.
   Use semantic-colors for actual UI usage.
   
   CONSOLIDATED from 11 grays to 7 for cleaner visual hierarchy."
  {;; Grayscale ramp (darkest to lightest) - 7 levels
   :gray-900 "#1E1E1E"   ; Base - main window background (darkest)
   :gray-800 "#282828"   ; Surface - panel backgrounds
   :gray-700 "#303030"   ; Elevated - cards, dialogs, menus
   :gray-600 "#3D3D3D"   ; Interactive - buttons, inputs (default state)
   :gray-500 "#505050"   ; Hover state / active borders
   :gray-300 "#808080"   ; Muted text, disabled text, subtle borders
   :gray-100 "#E0E0E0"   ; Primary text (lightest)
   
   ;; Brand/accent colors - SUCCESS (green)
   :green-500 "#4CAF50"  ; Success, primary action, active state
   :green-400 "#5CBF60"  ; Success hover
   :green-600 "#388E3C"  ; Success pressed
   
   ;; Brand/accent colors - INFO (blue)
   :blue-500  "#2196F3"  ; Info, links
   :blue-600  "#4A6FA5"  ; Selection background, active tabs
   :blue-400  "#5A8FCF"  ; Drop target background
   :blue-300  "#7AB8FF"  ; Focus ring, drop indicator border
   
   ;; Brand/accent colors - WARNING (orange)
   :orange-500 "#FF9800" ; Warning
   
   ;; Brand/accent colors - DANGER (red)
   :red-500   "#D32F2F"  ; Danger, error, delete
   :red-600   "#B71C1C"  ; Danger hover/pressed
   
   ;; Effects/special content (purple)
   :purple-500 "#7E57C2" ; Effects cells
   :purple-400 "#8E67D2" ; Effects hover
   
   ;; Category accent colors (for visual grouping in hierarchies)
   :cat-blue   "#4A7B9D"
   :cat-purple "#6B5B8C"
   :cat-green  "#5B8C6B"
   :cat-brown  "#8C7B5B"})


;; =============================================================================
;; Semantic Colors - Meaningful names mapped to base colors
;; =============================================================================

(def semantic-colors
  "Colors with semantic meaning - use these in your code.
   Maps meaningful UI concepts to base color values.
   
   SIMPLIFIED color hierarchy:
   - Backgrounds: 4 levels (primary → surface → elevated → interactive)
   - Text: 3 levels (primary → secondary/muted → disabled uses alpha)
   - Borders: 2 levels (subtle, default)"
  (let [{:keys [gray-900 gray-800 gray-700 gray-600 gray-500 gray-300 gray-100
                green-500 blue-500 blue-600 orange-500 red-500]} base-colors]
    {;; Background hierarchy (darkest to lightest)
     :bg-primary     gray-900    ; Root/window background (darkest)
     :bg-surface     gray-800    ; Panel backgrounds
     :bg-elevated    gray-700    ; Cards, dialogs, menus, group headers
     :bg-interactive gray-600    ; Buttons, inputs (default state)
     :bg-hover       gray-500    ; Hover state
     :bg-active      gray-500    ; Active/pressed state (same as hover, use opacity for difference)
     
     ;; Text hierarchy (3 levels, use alpha for disabled)
     :text-primary   gray-100    ; Main text, headings
     :text-secondary "#B0B0B0"   ; Secondary text (computed between gray-300 and gray-100)
     :text-muted     gray-300    ; Hints, disabled text, less important
     :text-disabled  gray-300    ; Disabled text (same as muted)
     
     ;; Borders (2 levels)
     :border-subtle  gray-700    ; Subtle borders (matches elevated bg)
     :border-default gray-500    ; Standard borders
     
     ;; Accent colors (semantic meanings)
     :accent-success green-500   ; Positive actions, active states, online
     :accent-info    blue-500    ; Information, help, connecting
     :accent-warning orange-500  ; Warnings, attention needed
     :accent-danger  red-500     ; Destructive actions, errors, offline
     
     ;; Selection colors
     :selection-bg     blue-600  ; Selected item background
     :selection-border blue-600})) ; Selected item border


;; =============================================================================
;; Computed Colors - Auto-derived state variants
;; =============================================================================

(def computed-colors
  "Automatically derived color variants for different UI states.
   Generated using the color utility functions.
   
   These are computed from base/semantic colors - no hardcoded hex here."
  (let [{:keys [bg-interactive bg-hover accent-success accent-danger selection-bg
                text-muted]} semantic-colors
        {:keys [blue-400 blue-300 purple-500 gray-600 gray-500]} base-colors]
    {;; Interactive element states (derived from semantic)
     :bg-interactive-hover  (colors/lighten bg-interactive 0.10)
     :bg-interactive-active (colors/darken bg-interactive 0.10)
     
     ;; Success/primary action states
     :accent-success-hover  (colors/lighten accent-success 0.10)
     :accent-success-active (colors/darken accent-success 0.15)
     
     ;; Danger states
     :accent-danger-hover   (colors/darken accent-danger 0.15)
     :accent-danger-active  (colors/darken accent-danger 0.25)
     
     ;; Selection states
     :selection-hover       (colors/lighten selection-bg 0.10)
     :selection-focus       (colors/lighten selection-bg 0.15)
     
     ;; Drop target colors (use base colors)
     :drop-target-bg        blue-400
     :drop-target-border    blue-300
     :drop-target-glow      (colors/with-alpha blue-300 0.6)
     
     ;; Disabled variants
     :text-disabled-muted   (colors/with-alpha text-muted 0.6)
     :bg-disabled           (colors/desaturate gray-600 0.5)
     
     ;; Effects cells (purple variants)
     :effects-content       purple-500
     :effects-content-hover (colors/lighten purple-500 0.10)
     
     ;; Cell content (derived from gray-500 hover)
     :cell-content          gray-500
     :cell-content-hover    (colors/lighten gray-500 0.12)}))


;; =============================================================================
;; Special Purpose Colors - Domain-specific
;; =============================================================================

(def status-colors
  "Colors for connection/device status indicators."
  (let [{:keys [accent-success accent-info accent-warning accent-danger
                text-muted]} semantic-colors]
    {:online     accent-success    ; Connected, healthy
     :connected  accent-success    ; Same as online
     :offline    accent-danger     ; Disconnected, error
     :connecting accent-info       ; In progress
     :occupied   accent-warning    ; In use by another
     :unknown    text-muted}))     ; Unknown state

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

(def channel-colors
  "Colors for RGB channel visualization."
  {:r "#FF5555"
   :g "#55FF55"
   :b "#5555FF"})


;; =============================================================================
;; All Colors Combined (for easy access)
;; =============================================================================

(def all-colors
  "All color definitions merged into a single map for easy access."
  (merge base-colors
         semantic-colors
         computed-colors
         status-colors
         category-colors
         channel-colors))


;; =============================================================================
;; Theme Registration (cljfx/css)
;; =============================================================================

(def theme
  "Main application theme with colors and base styles.
   
   Access colors via keyword keys:
   (::bg-primary theme) => \"#1E1E1E\"
   
   Load CSS via URL:
   (::css/url theme)"
  (css/register ::theme
    (let [;; Pull all colors we need
          {:keys [bg-primary bg-surface bg-elevated bg-interactive bg-hover bg-active
                  text-primary text-secondary text-muted text-disabled
                  border-subtle border-default
                  accent-success accent-info accent-warning accent-danger
                  selection-bg]} semantic-colors
          {:keys [accent-success-hover accent-danger-hover]} computed-colors
          {:keys [green-500 blue-500 blue-600 orange-500 red-500]} base-colors]
      
      {
       ;; =========================================================================
       ;; Color Constants (accessible from code)
       ;; For backward compatibility, keeping old key names but using new values
       ;; =========================================================================
       
       ;; Background colors (semantic names - preferred)
       ::bg-primary bg-primary
       ::bg-surface bg-surface
       ::bg-elevated bg-elevated
       ::bg-interactive bg-interactive
       ::bg-hover bg-hover
       ::bg-active bg-active
       
       ;; Background colors (legacy names - for compatibility)
       ::bg-darkest bg-primary
       ::bg-dark bg-surface
       ::bg-medium bg-elevated
       ::bg-light bg-interactive
       
       ;; Text colors
       ::text-primary text-primary
       ::text-secondary text-secondary
       ::text-muted text-muted
       ::text-disabled text-disabled
       
       ;; Border colors
       ::border-dark border-subtle
       ::border-medium border-default
       ::border-subtle border-subtle
       ::border-default border-default
       
       ;; Accent colors (semantic names - preferred)
       ::accent-success accent-success
       ::accent-info accent-info
       ::accent-warning accent-warning
       ::accent-danger accent-danger
       ::accent-success-hover accent-success-hover
       ::accent-danger-hover accent-danger-hover
       
       ;; Accent colors (legacy names - for compatibility)
       ::accent-primary accent-success
       ::accent-hover accent-success-hover
       ::accent-blue accent-info
       ::accent-blue-dark blue-600
       ::accent-orange accent-warning
       ::accent-red accent-danger
       
       ;; Selection colors
       ::selection-bg selection-bg
       
       
       ;; =========================================================================
       ;; Root Styles
       ;; =========================================================================
       
       ".root"
       {:-fx-base bg-primary
        :-fx-background bg-primary}
       
       
       ;; =========================================================================
       ;; Typography Classes
       ;; =========================================================================
       
       ;; Primary text styles
       ".text-primary"
       {:-fx-text-fill text-primary}
       
       ".text-secondary"
       {:-fx-text-fill text-secondary}
       
       ".text-muted"
       {:-fx-text-fill text-muted}
       
       ".text-disabled"
       {:-fx-text-fill text-disabled}
       
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
       
       
       ;; =========================================================================
       ;; Container/Panel Classes
       ;; =========================================================================
       
       ".panel-primary"
       {:-fx-background-color bg-primary}
       
       ".panel-surface"
       {:-fx-background-color bg-surface}
       
       ".panel-elevated"
       {:-fx-background-color bg-elevated}
       
       ;; Legacy names (for compatibility)
       ".panel-dark"
       {:-fx-background-color bg-primary}
       
       ".panel-medium"
       {:-fx-background-color bg-elevated}
       
       ".panel-light"
       {:-fx-background-color bg-surface}
       
       ;; Panel with standard padding
       ".panel-padded"
       {:-fx-padding 16}
       
       ".panel-padded-sm"
       {:-fx-padding 8}
       
       
       ;; =========================================================================
       ;; Scroll Pane Styles
       ;; =========================================================================
       
       ".scroll-pane-transparent"
       {:-fx-background-color "transparent"
        :-fx-background "transparent"
        
        " > .viewport"
        {:-fx-background-color "transparent"}}})))
