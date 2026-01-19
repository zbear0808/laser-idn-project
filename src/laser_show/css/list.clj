(ns laser-show.css.list
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
   Include (::css/url list) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))


#_{:clj-kondo/ignore [:redefined-var]}
(def list
  "Hierarchical list styles."
  (css/register ::list
    (let [;; Use semantic colors from theme
          {:keys [bg-primary bg-surface bg-elevated bg-interactive bg-hover bg-active
                  text-primary text-muted text-secondary
                  selection-bg border-subtle border-default]} theme/semantic-colors
          {:keys [selection-hover drop-target-bg drop-target-border
                  drop-target-glow]} theme/computed-colors
          {:keys [blue-600 gray-500]} theme/base-colors
          
          ;; Category colors for depth
          {:keys [depth-0 depth-1 depth-2 depth-3]} theme/category-colors
          
          ;; Indentation
          base-padding 8
          depth-indent 18]
      
      {
       ;; ============================================
       ;; Sidebar Container
       ;; ============================================
       
       ".chain-sidebar"
       {:-fx-background-color bg-primary
        :-fx-padding 8
        :-fx-spacing 8
        :-fx-min-width 180
        :-fx-pref-width 220
        :-fx-border-color border-default
        :-fx-border-width ["0px" "1px" "0px" "0px"]}
       
       
       ;; ============================================
       ;; Header Section
       ;; ============================================
       
       ".chain-header-label"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       
       ".chain-selection-count"
       {:-fx-text-fill blue-600
        :-fx-font-size 9}
       
       ".chain-hint-text"
       {:-fx-text-fill gray-500
        :-fx-font-size 8
        :-fx-font-style "italic"}
       
       ".chain-empty-text"
       {:-fx-text-fill text-muted
        :-fx-font-style "italic"
        :-fx-font-size 11}
       
       
       ;; ============================================
       ;; Toolbar Buttons
       ;; ============================================
       
       ".chain-toolbar-btn"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-font-size 9
        :-fx-padding ["2px" "6px"]
        
        ":hover"
        {:-fx-background-color bg-hover}
        
        ":disabled"
        {:-fx-opacity 0.4
         :-fx-text-fill text-muted
         :-fx-background-color bg-primary}}
       
       ".chain-toolbar-btn-danger"
       {:-fx-background-color "#803030"  ; Dark red - keep as accent
        :-fx-text-fill text-primary
        :-fx-font-size 9
        :-fx-padding ["2px" "6px"]
        
        ":hover"
        {:-fx-background-color "#A54040"}  ; More visible red brightening on hover
        
        ":disabled"
        {:-fx-opacity 0.4
         :-fx-text-fill text-muted
         :-fx-background-color "#503030"}}  ; Much darker red when disabled
       
       
       ;; ============================================
       ;; Group Header - Base with compound state selectors
       ;; Using compound selectors for higher specificity
       ;; ============================================
       
       ".group-header"
       {:-fx-spacing 6
        :-fx-alignment "CENTER_LEFT"
        :-fx-background-color bg-elevated
        :-fx-background-radius 4
        :-fx-border-radius 4
        :-fx-border-width ["0px" "0px" "0px" "3px"]
        
        ;; State variants as compound selectors (.group-header.group-header-selected)
        ".group-header-selected"
        {:-fx-background-color selection-bg}
        
        ;; Drop-into indicator - enhanced with glow for dropping into group
        ".group-header-drop-into"
        {:-fx-background-color drop-target-bg
         :-fx-border-color drop-target-border
         :-fx-border-width "2px"
         :-fx-effect (str "dropshadow(three-pass-box, " drop-target-glow ", 12, 0, 0, 0)")}
        
        ;; Drop-before indicator - enhanced with glow
        ".group-header-drop-before"
        {:-fx-border-color drop-target-border
         :-fx-border-width ["3px" "0px" "0px" "3px"]
         :-fx-background-color drop-target-bg
         :-fx-effect (str "dropshadow(three-pass-box, " drop-target-glow ", 8, 0, 0, -3)")}
        
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
       {:-fx-border-color depth-0}
       
       ".group-header.group-depth-1"
       {:-fx-border-color depth-1}
       
       ".group-header.group-depth-2"
       {:-fx-border-color depth-2}
       
       ".group-header.group-depth-3"
       {:-fx-border-color depth-3}
       
       
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
        :-fx-min-width 8
        
        ":hover"
        {:-fx-text-fill text-primary}}
       
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
        {:-fx-text-fill depth-0}
        
        ".group-name-depth-1"
        {:-fx-text-fill depth-1}
        
        ".group-name-depth-2"
        {:-fx-text-fill depth-2}
        
        ".group-name-depth-3"
        {:-fx-text-fill depth-3}}
       
       ".group-name-input"
       {:-fx-background-color bg-primary
        :-fx-text-fill text-primary
        :-fx-font-size 12
        :-fx-font-weight "bold"
        :-fx-padding ["0px" "4px"]}
       
       ".group-count-badge"
       {:-fx-text-fill text-muted
        :-fx-font-size 10}
       
       ".group-ungroup-btn"
       {:-fx-background-color "transparent"
        :-fx-text-fill text-secondary
        :-fx-padding ["1px" "3px"]
        :-fx-font-size 8
        
        ":hover"
        {:-fx-background-color bg-hover
         :-fx-text-fill text-primary}}
       
       
       ;; ============================================
       ;; Chain Item Card - Base
       ;; ============================================
       
       ".chain-item"
       {:-fx-spacing 6
        :-fx-alignment "CENTER_LEFT"
        :-fx-background-color bg-interactive
        :-fx-background-radius 4}
       
       ;; ============================================
       ;; Chain Item State Variants (compound selectors)
       ;; ============================================
       
       ".chain-item.chain-item-selected"
       {:-fx-background-color selection-bg}
       
       ;; Drop indicator when hovering over top half - place before
       ;; Enhanced with glow effect and background highlight for better visibility
       ".chain-item.chain-item-drop-before"
       {:-fx-border-color drop-target-border
        :-fx-border-width ["3px" "0px" "0px" "0px"]
        :-fx-border-radius 4
        :-fx-background-color drop-target-bg
        :-fx-effect (str "dropshadow(three-pass-box, " drop-target-glow ", 8, 0, 0, -3)")}
       
       ;; Drop indicator when hovering over bottom half - place after
       ;; Enhanced with glow effect and background highlight for better visibility
       ".chain-item.chain-item-drop-after"
       {:-fx-border-color drop-target-border
        :-fx-border-width ["0px" "0px" "3px" "0px"]
        :-fx-border-radius 4
        :-fx-background-color drop-target-bg
        :-fx-effect (str "dropshadow(three-pass-box, " drop-target-glow ", 8, 0, 0, 3)")}
       
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
       ;; Scroll Pane
       ;; ============================================
       
       ".chain-scroll-pane"
       {:-fx-background-color "transparent"
        :-fx-background bg-primary
        
        " > .viewport"
        {:-fx-background-color "transparent"}}})))
