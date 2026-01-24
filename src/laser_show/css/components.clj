(ns laser-show.css.components
  "Component styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Badges (colored labels)
   - Chips (clickable colored tags)
   - List items (selectable rows)
   - Cards and panels
   - Scrollbars (modern minimal styling)
   
   Usage:
   Include (::css/url components) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.colors :as colors]
            [laser-show.css.theme :as theme]))

(def components
  "Component styles for the application."
  (css/register ::components
    (let [{:keys [bg-primary bg-surface bg-elevated bg-interactive bg-hover
                  text-primary text-secondary text-muted
                  accent-success accent-info accent-warning accent-danger
                  selection-bg border-default]} theme/semantic-colors
          {:keys [interactive]} theme/base-colors]
      
      {
       ;; Badge - Small colored labels
       
       
       ;; Base badge style
       ".badge"
       {:-fx-text-fill "white"
        :-fx-padding ["2px" "6px"]
        :-fx-background-radius 3
        :-fx-font-size 9}
       
       ;; Badge color variants
       ".badge-default"
       {:-fx-background-color "#4A6FA5"}
       
       ".badge-success"
       {:-fx-background-color accent-success}
       
       ".badge-info"
       {:-fx-background-color accent-info}
       
       ".badge-warning"
       {:-fx-background-color accent-warning}
       
       ".badge-danger"
       {:-fx-background-color accent-danger}
       
       ".badge-purple"
       {:-fx-background-color "#9B59B6"}
       
       ".badge-orange"
       {:-fx-background-color "#E67E22"}
       
       
       ;; Chip - Clickable colored tags
       
       
       ;; Base chip style
       ".chip"
       {:-fx-text-fill "white"
        :-fx-padding ["2px" "6px"]
        :-fx-background-radius 8
        :-fx-font-size 10
        :-fx-cursor "hand"
        :-fx-opacity 0.6
        
        ":hover"
        {:-fx-opacity 1.0}}
       
       ;; Selected chip - full opacity
       ".chip-selected"
       {:-fx-text-fill "white"
        :-fx-padding ["2px" "6px"]
        :-fx-background-radius 8
        :-fx-font-size 10
        :-fx-cursor "hand"
        :-fx-opacity 1.0}
       
       
       ;; List Items - Selectable rows
       
       
       ;; Base list item
       ".list-item"
       {:-fx-background-color interactive
        :-fx-background-radius 4
        :-fx-padding 8
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color bg-hover}}
       
       ;; Selected list item
       ".list-item-selected"
       {:-fx-background-color selection-bg
        :-fx-background-radius 4
        :-fx-padding 8
        :-fx-cursor "hand"
        
        ":hover"
        {:-fx-background-color (colors/lighten selection-bg 0.10)}}
       
       ;; Disabled list item (via opacity)
       ".list-item-disabled"
       {:-fx-background-color interactive
        :-fx-background-radius 4
        :-fx-padding 8
        :-fx-opacity 0.5}
       
       
       ;; Cards and Panels
       
       
       ;; Card container - matches standard background
       ".card"
       {:-fx-background-color bg-primary
        :-fx-background-radius 4
        :-fx-border-color border-default
        :-fx-border-width 1
        :-fx-border-radius 4}
       
       ;; Elevated panel - for editor sections, dialogs
       ".panel-content"
       {:-fx-background-color bg-elevated
        :-fx-background-radius 4}
       
       
       ;; Scroll Pane Variants
       
       
       ;; Dark scroll pane (matches surface bg)
       ".scroll-pane-dark"
       {:-fx-background-color bg-surface
        :-fx-background bg-surface
        
        " > .viewport"
        {:-fx-background-color bg-surface}}
       
       
       ;; Status Indicators
       
       
       ;; Status dot base
       ".status-dot"
       {:-fx-background-radius 100}
       
       ".status-dot-online"
       {:-fx-background-color accent-success}
       
       ".status-dot-offline"
       {:-fx-background-color accent-danger}
       
       ".status-dot-connecting"
       {:-fx-background-color accent-info}
       
       ".status-dot-warning"
       {:-fx-background-color accent-warning}
       
       
       ;; Scrollbar - Modern minimal styling
       
       
       ;; Define scrollbar colors using theme border as base
       ;; Default: ~20% lighter than border, Hover: ~30% lighter
       
       ;; Scrollbar container
       ".scroll-bar"
       {:-fx-background-color "transparent"
        :-fx-background-radius 6}
       
       ;; Track - nearly invisible
       ".scroll-bar .track"
       {:-fx-background-color "transparent"
        :-fx-background-radius 6}
       
       ;; Track background
       ".scroll-bar .track-background"
       {:-fx-background-color "transparent"}
       
       ;; Thumb - the main visual element
       ".scroll-bar .thumb"
       {:-fx-background-color (colors/lighten border-default 0.20)
        :-fx-background-radius 6
        :-fx-background-insets 2}
       
       ;; Thumb hover state - lighter and full width
       ".scroll-bar:hover .thumb"
       {:-fx-background-color (colors/lighten border-default 0.35)
        :-fx-background-insets 0}
       
       ;; Hide increment/decrement arrow buttons
       ".scroll-bar .increment-button"
       {:-fx-pref-width 0
        :-fx-pref-height 0
        :-fx-padding 0}
       
       ".scroll-bar .decrement-button"
       {:-fx-pref-width 0
        :-fx-pref-height 0
        :-fx-padding 0}
       
       ;; Hide the arrows inside the buttons
       ".scroll-bar .increment-arrow"
       {:-fx-shape ""
        :-fx-padding 0}
       
       ".scroll-bar .decrement-arrow"
       {:-fx-shape ""
        :-fx-padding 0}
       
       ;; Vertical scrollbar width
       ".scroll-bar:vertical"
       {:-fx-pref-width 6}
       
       ".scroll-bar:vertical:hover"
       {:-fx-pref-width 8}
       
       ;; Horizontal scrollbar height
       ".scroll-bar:horizontal"
       {:-fx-pref-height 6}
       
       ".scroll-bar:horizontal:hover"
       {:-fx-pref-height 8}})))
