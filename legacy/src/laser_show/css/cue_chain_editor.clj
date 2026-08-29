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
            [laser-show.css.colors :as colors]
            [laser-show.css.theme :as theme]))



(def cue-chain-editor
  "Cue chain editor styles."
  (css/register ::cue-chain-editor
    (let [;; Use semantic colors from theme
          {:keys [bg-primary bg-surface bg-elevated bg-interactive bg-hover bg-active
                  text-primary text-muted text-secondary
                  selection-bg border-subtle accent-success accent-danger]} theme/semantic-colors
          {:keys [blue]} theme/base-colors
          {:keys [drop-target-bg drop-target-border accent-danger-hover]} theme/computed-colors]
      
      {
       
       ;; Main Dialog Layout
       
       
       ".dialog-content"
       {:-fx-background-color bg-primary
        :-fx-spacing 0}
       
       ".dialog-footer"
       {:-fx-background-color bg-primary
        :-fx-padding 12
        :-fx-spacing 8
        :-fx-border-color border-subtle
        :-fx-border-width ["1px" "0px" "0px" "0px"]}
       
       ".dialog-section"
       {:-fx-background-color bg-primary
        :-fx-padding 8
        :-fx-spacing 8
        :-fx-border-color border-subtle
        :-fx-border-width 1
        :-fx-border-radius 4}
       
       ".dialog-placeholder"
       {:-fx-background-color bg-primary
        :-fx-alignment "center"}
       
       ".dialog-placeholder-text"
       {:-fx-text-fill text-muted
        :-fx-font-style "italic"
        :-fx-font-size 11
        :-fx-text-alignment "center"}
       
       ".dialog-separator"
       {:-fx-border-color border-subtle
        :-fx-border-width ["1px" "0px" "0px" "0px"]}
       
       ".cue-chain-left-column"
       {:-fx-pref-width 280
        :-fx-max-width 280
        :-fx-spacing 0}
       
       
       ;; Zone Picker Section
       
       
       ".zone-picker"
       {:-fx-background-color bg-elevated
        :-fx-padding 8
        :-fx-background-radius 4
        :-fx-spacing 6
        :-fx-border-color border-subtle
        :-fx-border-width 1
        :-fx-border-radius 4}
       
       ".zone-picker-header"
       {:-fx-text-fill text-muted
        :-fx-font-size 10
        :-fx-font-weight "bold"}
       
       ".zone-picker-label"
       {:-fx-text-fill text-secondary
        :-fx-font-size 10}
       
       ".group-properties-label"
       {:-fx-text-fill text-secondary
        :-fx-font-size 10}
       
       
       
       ;; Sidebar Enhancements (extends effect-chain-sidebar)
       
       ".cue-chain-hint"
       {:-fx-text-fill text-muted
        :-fx-font-size 8
        :-fx-font-style "italic"
        :-fx-wrap-text true}
       
       
       
       ;; Preset Item Styling (overrides chain-item for presets)
       
       
       ".preset-item"
       {:-fx-spacing 6
        :-fx-alignment "CENTER_LEFT"
        :-fx-background-color bg-interactive
        :-fx-background-radius 4
        :-fx-padding ["6px" "8px"]
        
        ".preset-item-selected"
        {:-fx-background-color selection-bg}
        
        ".preset-item-drop-target"
        {:-fx-background-color drop-target-bg
         :-fx-border-color drop-target-border
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
        {:-fx-text-fill text-primary}
        
        ".preset-item-name-disabled"
        {:-fx-text-fill text-muted}}
       
       ".preset-item-effects-badge"
       {:-fx-text-fill text-muted
        :-fx-font-size 10
        :-fx-font-style "italic"}
       
       
       
       ;; Preset Bank
       
       
       ".preset-bank"
       {:-fx-background-color bg-elevated
        :-fx-background-radius 4
        :-fx-padding 8
        :-fx-spacing 8
        :-fx-min-height 150
        :-fx-pref-height 180
        :-fx-border-color border-subtle
        :-fx-border-width 1
        :-fx-border-radius 4}
       
       ".preset-bank-tab-bar"
       {:-fx-background-color "transparent"
        :-fx-spacing 4
        :-fx-padding ["0px" "0px" "4px" "0px"]}
       
       ".preset-bank-tab"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-muted
        :-fx-font-size 10
        :-fx-padding ["4px" "10px"]
        :-fx-background-radius 4
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       ".preset-bank-tab-active"
       {:-fx-background-color blue
        :-fx-text-fill text-primary
        :-fx-font-weight "bold"
        
        ":hover"
        {:-fx-background-color (colors/lighten blue 0.10)}}
       
       ".preset-bank-grid"
       {:-fx-background-color "transparent"
        :-fx-hgap 8
        :-fx-vgap 8
        :-fx-padding 4}
       
       ".preset-bank-btn"
       {:-fx-background-color bg-interactive
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
       {:-fx-border-color (:geometric theme/category-colors)
        :-fx-border-width "2px"
        :-fx-border-radius 4}
       
       ".preset-bank-btn-wave"
       {:-fx-border-color (:wave theme/category-colors)
        :-fx-border-width "2px"
        :-fx-border-radius 4}
       
       ".preset-bank-btn-beam"
       {:-fx-border-color (:beam theme/category-colors)
        :-fx-border-width "2px"
        :-fx-border-radius 4}
       
       ".preset-bank-btn-abstract"
       {:-fx-border-color (:abstract theme/category-colors)
        :-fx-border-width "2px"
        :-fx-border-radius 4}
       
       
       
       ;; Preset Parameter Editor
       
       
       ".preset-param-editor"
       {:-fx-background-color bg-elevated
        :-fx-background-radius 4
        :-fx-padding 8
        :-fx-spacing 8
        :-fx-border-color border-subtle
        :-fx-border-width 1
        :-fx-border-radius 4}
       
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
       {:-fx-background-color bg-primary
        :-fx-text-fill text-primary
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
       
       
       
       ;; Per-Preset Effects Section
       
       
       ".preset-effects-section"
       {:-fx-background-color bg-elevated
        :-fx-background-radius 4
        :-fx-padding 8
        :-fx-spacing 4
        :-fx-border-color border-subtle
        :-fx-border-width 1
        :-fx-border-radius 4}
       
       ".preset-effects-empty"
       {:-fx-text-fill text-muted
        :-fx-font-size 10
        :-fx-font-style "italic"}
       
       ".preset-effects-list"
       {:-fx-background-color "transparent"
        :-fx-spacing 4
        :-fx-padding ["4px" "0px"]}
       
       ".preset-effect-item"
       {:-fx-background-color bg-interactive
        :-fx-background-radius 4
        :-fx-padding ["4px" "8px"]
        :-fx-spacing 6
        :-fx-alignment "CENTER_LEFT"}
       
       ".preset-effect-name"
       {:-fx-font-size 11
        :-fx-text-fill text-primary}
       
       ".preset-effect-remove-btn"
       {:-fx-background-color "transparent"
        :-fx-text-fill accent-danger
        :-fx-font-size 12
        :-fx-padding ["0px" "4px"]
        :-fx-cursor "hand"
        :-fx-background-radius 3
        
        ":hover"
        {:-fx-text-fill accent-danger-hover
         :-fx-background-color bg-interactive}}
       
       
       
       ;; Effect Bank (Mini version for per-preset effects)
       
       
       ".mini-effect-bank"
       {:-fx-background-color bg-interactive
        :-fx-background-radius 4
        :-fx-padding 4
        :-fx-spacing 4
        :-fx-orientation "HORIZONTAL"
        :-fx-alignment "CENTER_LEFT"
        :-fx-border-color border-subtle
        :-fx-border-width 1
        :-fx-border-radius 4}
       
       ".mini-effect-btn"
       {:-fx-background-color bg-hover
        :-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-padding ["4px" "8px"]
        :-fx-background-radius 4
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color bg-active}}
       
       ;; Section Separators
       
       
       ".cue-chain-separator"
       {:-fx-border-color border-subtle
        :-fx-border-width ["1px" "0px" "0px" "0px"]
        :-fx-padding ["4px" "0px" "0px" "0px"]}})))
