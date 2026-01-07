(ns laser-show.css.hierarchical-list
  "CSS styles for the hierarchical list component.
   
   Provides CSS classes for:
   - Sidebar container
   - Group headers with depth colors and state variants
   - List items with state variants
   - Toolbar buttons
   - Indentation classes for nested items
   
   This consolidates styles that were previously in effect-chain-sidebar.clj
   into a single reusable stylesheet for all hierarchical list components.
   
   State variants use compound selectors (e.g., .chain-item.chain-item-selected)
   for proper specificity when multiple classes are applied.
   
   Usage:
   Include (::css/url hierarchical-list) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))


;; Group depth colors (exported for use in components that need programmatic access)

(def group-colors
  "Colors for different group nesting depths."
  {:depth-0 "#4A7B9D"   ;; Blue
   :depth-1 "#6B5B8C"   ;; Purple
   :depth-2 "#5B8C6B"   ;; Green
   :depth-3 "#8C7B5B"}) ;; Brown


(def hierarchical-list
  "Hierarchical list styles."
  (css/register ::hierarchical-list
    (let [{bg-dark      ::theme/bg-dark
           bg-medium    ::theme/bg-medium
           bg-light     ::theme/bg-light
           bg-hover     ::theme/bg-hover
           bg-active    ::theme/bg-active
           text-primary ::theme/text-primary
           text-muted   ::theme/text-muted
           accent-blue-dark ::theme/accent-blue-dark} theme/theme
          
          ;; Selection/drop colors
          selected-bg "#4A6FA5"
          drop-into-bg "#5A8FCF"
          drop-border "#7AB8FF"
          
          ;; Group depth colors
          depth-0-color (:depth-0 group-colors)
          depth-1-color (:depth-1 group-colors)
          depth-2-color (:depth-2 group-colors)
          depth-3-color (:depth-3 group-colors)
          
          ;; Indentation
          base-padding 8
          depth-indent 18]
      
      {
       ;; ============================================
       ;; Sidebar Container
       ;; ============================================
       
       ".chain-sidebar"
       {:-fx-background-color bg-dark
        :-fx-padding 8
        :-fx-spacing 8
        :-fx-min-width 180
        :-fx-pref-width 220}
       
       
       ;; ============================================
       ;; Header Section
       ;; ============================================
       
       ".chain-header-label"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       
       ".chain-selection-count"
       {:-fx-text-fill accent-blue-dark
        :-fx-font-size 9}
       
       ".chain-hint-text"
       {:-fx-text-fill "#505050"
        :-fx-font-size 8
        :-fx-font-style "italic"}
       
       ".chain-empty-text"
       {:-fx-text-fill "#606060"
        :-fx-font-style "italic"
        :-fx-font-size 11}
       
       
       ;; ============================================
       ;; Toolbar Buttons
       ;; ============================================
       
       ".chain-toolbar-btn"
       {:-fx-background-color bg-hover
        :-fx-text-fill "white"
        :-fx-font-size 9
        :-fx-padding ["2px" "6px"]
        
        ":hover"
        {:-fx-background-color bg-active}}
       
       ".chain-toolbar-btn-danger"
       {:-fx-background-color "#803030"
        :-fx-text-fill "white"
        :-fx-font-size 9
        :-fx-padding ["2px" "6px"]
        
        ":hover"
        {:-fx-background-color "#903030"}}
       
       
       ;; ============================================
       ;; Group Header - Base with compound state selectors
       ;; Using compound selectors for higher specificity
       ;; ============================================
       
       ".group-header"
       {:-fx-spacing 6
        :-fx-alignment "CENTER_LEFT"
        :-fx-background-color "#333333"
        :-fx-background-radius 4
        :-fx-border-radius 4
        :-fx-border-width ["0px" "0px" "0px" "3px"]
        
        ;; State variants as compound selectors (.group-header.group-header-selected)
        ".group-header-selected"
        {:-fx-background-color selected-bg}
        
        ;; Drop-into indicator - enhanced with glow for dropping into group
        ".group-header-drop-into"
        {:-fx-background-color drop-into-bg
         :-fx-border-color drop-border
         :-fx-border-width "2px"
         :-fx-effect "dropshadow(three-pass-box, rgba(122, 184, 255, 0.6), 12, 0, 0, 0)"}
        
        ;; Drop-before indicator - enhanced with glow
        ".group-header-drop-before"
        {:-fx-border-color drop-border
         :-fx-border-width ["3px" "0px" "0px" "3px"]
         :-fx-background-color "#3A3A5A"
         :-fx-effect "dropshadow(three-pass-box, rgba(122, 184, 255, 0.5), 8, 0, 0, -3)"}
        
        ;; Dragging group - looks "lifted"
        ".group-header-dragging"
        {:-fx-opacity 0.5
         :-fx-effect "dropshadow(three-pass-box, rgba(0, 0, 0, 0.4), 6, 0, 2, 2)"}
        
        ".group-header-disabled"
        {:-fx-opacity 0.6}}
       
       
       ;; ============================================
       ;; Group Depth Border Colors (compound with .group-header)
       ;; ============================================
       
       ".group-header.group-depth-0"
       {:-fx-border-color depth-0-color}
       
       ".group-header.group-depth-1"
       {:-fx-border-color depth-1-color}
       
       ".group-header.group-depth-2"
       {:-fx-border-color depth-2-color}
       
       ".group-header.group-depth-3"
       {:-fx-border-color depth-3-color}
       
       
       ;; ============================================
       ;; Group Indentation (compound with .group-header)
       ;; ============================================
       
       ".group-header.group-indent-0"
       {:-fx-padding ["6px" "8px" "6px" (str base-padding "px")]}
       
       ".group-header.group-indent-1"
       {:-fx-padding ["6px" "8px" "6px" (str (+ base-padding depth-indent) "px")]}
       
       ".group-header.group-indent-2"
       {:-fx-padding ["6px" "8px" "6px" (str (+ base-padding (* 2 depth-indent)) "px")]}
       
       ".group-header.group-indent-3"
       {:-fx-padding ["6px" "8px" "6px" (str (+ base-padding (* 3 depth-indent)) "px")]}
       
       
       ;; ============================================
       ;; Group Header Sub-elements
       ;; ============================================
       
       ".group-collapse-btn"
       {:-fx-background-color "transparent"
        :-fx-text-fill text-muted
        :-fx-font-size 10
        :-fx-padding ["0px" "0px"]
        :-fx-min-width 8}
       
       ".group-name-label"
       {:-fx-font-size 12
        :-fx-font-weight "bold"
        :-fx-cursor "hand"
        
        ;; Compound selectors for state variants
        ".group-name-selected"
        {:-fx-text-fill "white"}
        
        ".group-name-disabled"
        {:-fx-text-fill text-muted}
        
        ;; Depth colors (when not selected/disabled)
        ".group-name-depth-0"
        {:-fx-text-fill depth-0-color}
        
        ".group-name-depth-1"
        {:-fx-text-fill depth-1-color}
        
        ".group-name-depth-2"
        {:-fx-text-fill depth-2-color}
        
        ".group-name-depth-3"
        {:-fx-text-fill depth-3-color}}
       
       ".group-name-input"
       {:-fx-background-color bg-dark
        :-fx-text-fill "white"
        :-fx-font-size 12
        :-fx-font-weight "bold"
        :-fx-padding ["0px" "4px"]}
       
       ".group-count-badge"
       {:-fx-text-fill "#606060"
        :-fx-font-size 10}
       
       ".group-ungroup-btn"
       {:-fx-background-color bg-active
        :-fx-text-fill "#A0A0A0"
        :-fx-padding ["1px" "3px"]
        :-fx-font-size 8
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       
       ;; ============================================
       ;; Chain Item Card - Base
       ;; ============================================
       
       ".chain-item"
       {:-fx-spacing 6
        :-fx-alignment "CENTER_LEFT"
        :-fx-background-color bg-light
        :-fx-background-radius 4}
       
       ;; ============================================
       ;; Chain Item State Variants (compound selectors)
       ;; ============================================
       
       ".chain-item.chain-item-selected"
       {:-fx-background-color selected-bg}
       
       ;; Drop indicator when hovering over top half - place before
       ;; Enhanced with glow effect and background highlight for better visibility
       ".chain-item.chain-item-drop-before"
       {:-fx-border-color drop-border
        :-fx-border-width ["3px" "0px" "0px" "0px"]
        :-fx-border-radius 4
        :-fx-background-color "#3A3A5A"
        :-fx-effect "dropshadow(three-pass-box, rgba(122, 184, 255, 0.5), 8, 0, 0, -3)"}
       
       ;; Drop indicator when hovering over bottom half - place after
       ;; Enhanced with glow effect and background highlight for better visibility
       ".chain-item.chain-item-drop-after"
       {:-fx-border-color drop-border
        :-fx-border-width ["0px" "0px" "3px" "0px"]
        :-fx-border-radius 4
        :-fx-background-color "#3A3A5A"
        :-fx-effect "dropshadow(three-pass-box, rgba(122, 184, 255, 0.5), 8, 0, 0, 3)"}
       
       ;; Dragging item - looks "lifted" from the list
       ".chain-item.chain-item-dragging"
       {:-fx-opacity 0.5
        :-fx-effect "dropshadow(three-pass-box, rgba(0, 0, 0, 0.4), 6, 0, 2, 2)"}
       
       ".chain-item.chain-item-disabled"
       {:-fx-opacity 0.6}
       
       
       ;; ============================================
       ;; Chain Item Indentation (compound with .chain-item)
       ;; ============================================
       
       ".chain-item.item-indent-0"
       {:-fx-padding ["6px" "8px" "6px" (str base-padding "px")]}
       
       ".chain-item.item-indent-1"
       {:-fx-padding ["6px" "8px" "6px" (str (+ base-padding depth-indent) "px")]}
       
       ".chain-item.item-indent-2"
       {:-fx-padding ["6px" "8px" "6px" (str (+ base-padding (* 2 depth-indent)) "px")]}
       
       ".chain-item.item-indent-3"
       {:-fx-padding ["6px" "8px" "6px" (str (+ base-padding (* 3 depth-indent)) "px")]}
       
       
       ;; ============================================
       ;; Chain Item Sub-elements
       ;; ============================================
       
       ".chain-item-name"
       {:-fx-font-size 12
        :-fx-text-fill text-primary
        
        ;; Compound selectors for state variants
        ".chain-item-name-selected"
        {:-fx-text-fill "white"}
        
        ".chain-item-name-disabled"
        {:-fx-text-fill text-muted}}
       
       
       ;; ============================================
       ;; Drop Indicator Line
       ;; Visual horizontal line showing where item will be inserted
       ;; ============================================
       
       ".drop-indicator-line"
       {:-fx-padding ["0px" "0px"]
        :-fx-min-height 4
        :-fx-max-height 4
        :-fx-pref-height 4
        :-fx-alignment "CENTER_LEFT"}
       
       ".drop-indicator-arrow"
       {:-fx-text-fill drop-border
        :-fx-font-size 10
        :-fx-font-weight "bold"}
       
       ".drop-indicator-bar"
       {:-fx-background-color drop-border
        :-fx-pref-height 2
        :-fx-min-height 2
        :-fx-background-radius 1}
       
       
       ;; ============================================
       ;; Scroll Pane
       ;; ============================================
       
       ".chain-scroll-pane"
       {:-fx-background-color "transparent"
        :-fx-background bg-dark
        
        " > .viewport"
        {:-fx-background-color "transparent"}}})))
