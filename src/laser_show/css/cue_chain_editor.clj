(ns laser-show.css.cue-chain-editor
  "CSS styles for the cue chain editor dialog.
   
   Provides CSS classes for:
   - Main dialog layout
   - Preset bank (tabbed browser)
   - Preset parameter editor
   - Cue chain sidebar (extends effect-chain-sidebar styles)
   
   Usage:
   Include (::css/url cue-chain-editor) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))


;; Preset category colors

(def preset-category-colors
  "Colors for preset categories in the bank."
  {:geometric "#4A7B9D"   ;; Blue
   :wave      "#6B5B8C"   ;; Purple
   :beam      "#5B8C6B"   ;; Green
   :abstract  "#8C7B5B"}) ;; Brown


(def cue-chain-editor
  "Cue chain editor styles."
  (css/register ::cue-chain-editor
    (let [{bg-dark      ::theme/bg-dark
           bg-medium    ::theme/bg-medium
           bg-light     ::theme/bg-light
           bg-hover     ::theme/bg-hover
           bg-active    ::theme/bg-active
           text-primary ::theme/text-primary
           text-muted   ::theme/text-muted
           accent-blue-dark ::theme/accent-blue-dark
           accent-blue  ::theme/accent-blue} theme/theme]
      
      {
       ;; ============================================
       ;; Main Dialog Layout
       ;; ============================================
       
       ".cue-chain-editor"
       {:-fx-background-color bg-dark
        :-fx-padding 0}
       
       ".cue-chain-editor-content"
       {:-fx-background-color bg-dark
        :-fx-padding 0}
       
       ".cue-chain-left-sidebar"
       {:-fx-background-color bg-dark
        :-fx-min-width 280
        :-fx-pref-width 300
        :-fx-max-width 350
        :-fx-padding 8
        :-fx-spacing 8}
       
       ".cue-chain-right-section"
       {:-fx-background-color bg-medium
        :-fx-padding 8
        :-fx-spacing 8}
       
       
       ;; ============================================
       ;; Sidebar Enhancements (extends effect-chain-sidebar)
       ;; ============================================
       
       ".cue-chain-sidebar-header"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       
       ".cue-chain-hint"
       {:-fx-text-fill "#505050"
        :-fx-font-size 8
        :-fx-font-style "italic"
        :-fx-wrap-text true}
       
       
       ;; ============================================
       ;; Preset Item Styling (overrides chain-item for presets)
       ;; ============================================
       
       ".preset-item"
       {:-fx-spacing 6
        :-fx-alignment "CENTER_LEFT"
        :-fx-background-color bg-light
        :-fx-background-radius 4
        :-fx-padding ["6px" "8px"]
        
        ".preset-item-selected"
        {:-fx-background-color "#4A6FA5"}
        
        ".preset-item-drop-target"
        {:-fx-background-color "#5A8FCF"
         :-fx-border-color "#7AB8FF"
         :-fx-border-width ["2px" "0px" "0px" "0px"]}
        
        ".preset-item-dragging"
        {:-fx-opacity 0.5}
        
        ".preset-item-disabled"
        {:-fx-opacity 0.6}}
       
       ".preset-item-icon"
       {:-fx-font-size 16
        :-fx-min-width 24
        :-fx-alignment "CENTER"}
       
       ".preset-item-name"
       {:-fx-font-size 12
        :-fx-text-fill text-primary
        
        ".preset-item-name-selected"
        {:-fx-text-fill "white"}
        
        ".preset-item-name-disabled"
        {:-fx-text-fill text-muted}}
       
       ".preset-item-effects-badge"
       {:-fx-text-fill "#707070"
        :-fx-font-size 10
        :-fx-font-style "italic"}
       
       
       ;; ============================================
       ;; Preset Bank
       ;; ============================================
       
       ".preset-bank"
       {:-fx-background-color bg-medium
        :-fx-background-radius 4
        :-fx-padding 8
        :-fx-spacing 8
        :-fx-min-height 150
        :-fx-pref-height 180}
       
       ".preset-bank-header"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       
       ".preset-bank-tab-bar"
       {:-fx-background-color "transparent"
        :-fx-spacing 4
        :-fx-padding ["0px" "0px" "4px" "0px"]}
       
       ".preset-bank-tab"
       {:-fx-background-color bg-light
        :-fx-text-fill text-muted
        :-fx-font-size 10
        :-fx-padding ["4px" "10px"]
        :-fx-background-radius 4
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       ".preset-bank-tab-active"
       {:-fx-background-color accent-blue-dark
        :-fx-text-fill "white"
        :-fx-font-weight "bold"}
       
       ".preset-bank-grid"
       {:-fx-background-color "transparent"
        :-fx-hgap 8
        :-fx-vgap 8
        :-fx-padding 4}
       
       ".preset-bank-btn"
       {:-fx-background-color bg-light
        :-fx-text-fill text-primary
        :-fx-font-size 11
        :-fx-padding ["8px" "12px"]
        :-fx-background-radius 4
        :-fx-cursor "hand"
        :-fx-min-width 80
        :-fx-alignment "CENTER"
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       ;; Category-specific button colors
       ".preset-bank-btn-geometric"
       {:-fx-border-color (:geometric preset-category-colors)
        :-fx-border-width "2px"
        :-fx-border-radius 4}
       
       ".preset-bank-btn-wave"
       {:-fx-border-color (:wave preset-category-colors)
        :-fx-border-width "2px"
        :-fx-border-radius 4}
       
       ".preset-bank-btn-beam"
       {:-fx-border-color (:beam preset-category-colors)
        :-fx-border-width "2px"
        :-fx-border-radius 4}
       
       ".preset-bank-btn-abstract"
       {:-fx-border-color (:abstract preset-category-colors)
        :-fx-border-width "2px"
        :-fx-border-radius 4}
       
       
       ;; ============================================
       ;; Preset Parameter Editor
       ;; ============================================
       
       ".preset-param-editor"
       {:-fx-background-color bg-medium
        :-fx-background-radius 4
        :-fx-padding 8
        :-fx-spacing 8}
       
       ".preset-param-header"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       
       ".preset-param-name"
       {:-fx-text-fill text-primary
        :-fx-font-size 12
        :-fx-font-weight "bold"}
       
       ".preset-param-section"
       {:-fx-spacing 4
        :-fx-padding ["4px" "0px"]}
       
       ".preset-param-row"
       {:-fx-spacing 8
        :-fx-alignment "CENTER_LEFT"}
       
       ".preset-param-label"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-min-width 80
        :-fx-pref-width 80}
       
       ".preset-param-slider"
       {:-fx-min-width 120
        :-fx-pref-width 160}
       
       ".preset-param-text"
       {:-fx-background-color bg-dark
        :-fx-text-fill "white"
        :-fx-font-size 10
        :-fx-padding ["2px" "4px"]
        :-fx-pref-width 60
        :-fx-min-width 50}
       
       ".preset-param-color-picker"
       {:-fx-pref-width 80
        :-fx-pref-height 24}
       
       ".preset-param-empty"
       {:-fx-text-fill text-muted
        :-fx-font-style "italic"
        :-fx-font-size 11}
       
       
       ;; ============================================
       ;; Per-Preset Effects Section
       ;; ============================================
       
       ".preset-effects-section"
       {:-fx-background-color bg-medium
        :-fx-background-radius 4
        :-fx-padding 8
        :-fx-spacing 4}
       
       ".preset-effects-header"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       
       ".preset-effects-empty"
       {:-fx-text-fill "#505050"
        :-fx-font-size 10
        :-fx-font-style "italic"}
       
       ".preset-effects-list"
       {:-fx-background-color "transparent"
        :-fx-spacing 4
        :-fx-padding ["4px" "0px"]}
       
       ".preset-effect-item"
       {:-fx-background-color bg-light
        :-fx-background-radius 4
        :-fx-padding ["4px" "8px"]
        :-fx-spacing 6
        :-fx-alignment "CENTER_LEFT"}
       
       ".preset-effect-name"
       {:-fx-font-size 11
        :-fx-text-fill text-primary}
       
       ".preset-effect-remove-btn"
       {:-fx-background-color "transparent"
        :-fx-text-fill "#803030"
        :-fx-font-size 12
        :-fx-padding ["0px" "4px"]
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-text-fill "#C05050"}}
       
       
       ;; ============================================
       ;; Effect Bank (Mini version for per-preset effects)
       ;; ============================================
       
       ".mini-effect-bank"
       {:-fx-background-color bg-light
        :-fx-background-radius 4
        :-fx-padding 4
        :-fx-spacing 4
        :-fx-orientation "HORIZONTAL"
        :-fx-alignment "CENTER_LEFT"}
       
       ".mini-effect-btn"
       {:-fx-background-color bg-hover
        :-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-padding ["4px" "8px"]
        :-fx-background-radius 4
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color bg-active}}
       
       
       ;; ============================================
       ;; Dialog Scroll Pane
       ;; ============================================
       
       ".cue-chain-scroll-pane"
       {:-fx-background-color "transparent"
        :-fx-background bg-dark
        
        " > .viewport"
        {:-fx-background-color "transparent"}}
       
       
       ;; ============================================
       ;; Section Separators
       ;; ============================================
       
       ".cue-chain-separator"
       {:-fx-border-color "#303030"
        :-fx-border-width ["1px" "0px" "0px" "0px"]
        :-fx-padding ["4px" "0px" "0px" "0px"]}})))
