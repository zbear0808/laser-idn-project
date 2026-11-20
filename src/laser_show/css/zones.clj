(ns laser-show.css.zones
  "Zones' styles (zones plan Z4): the Target chip and its menu
   (views/components/target_control.clj; compact in the timeline's track
   headers), the grid cells' badges and the
   column numbers that set a column's target, the zone map pads
   (views/components/zone_map_pads.clj) and the map editor's warnings
   (views/components/zone_maps_panel.clj).

   cljfx.css writes a map of more than eight rules in hash order, so a state
   rule has one class more than the rule it overrides.

   Usage:
   Include (::css/url zones) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.colors :as colors]
            [laser-show.css.theme :as theme]))

(def zones
  "Zone, target and zone map styles."
  (css/register ::zones
    (let [{:keys [bg-interactive text-primary text-secondary text-muted]} theme/semantic-colors
          {:keys [blue orange red border]} theme/base-colors]
      {;; The Target chip: the timeline's zone chip's shape
       ".target-chip"
       {:-fx-background-radius 9
        :-fx-border-radius 9
        :-fx-padding [1 7 1 7]
        :-fx-font-size 11
        :-fx-background-color (str blue "40")
        :-fx-text-fill blue
        :-fx-cursor "hand"
        :-fx-mark-color "transparent"}
       ".target-chip .label"
       {:-fx-text-fill "inherit"}
       ".target-chip .arrow-button"
       {:-fx-padding 0}
       ".target-chip .arrow"
       {:-fx-padding 0}
       ".target-chip.target-chip-compact"
       {:-fx-font-size 10}
       ".target-chip.target-chip-inherited"
       {:-fx-background-color "transparent"
        :-fx-border-color border
        :-fx-text-fill text-muted
        :-fx-font-style "italic"}
       ".target-chip.target-chip-missing"
       {:-fx-border-color orange
        :-fx-text-fill orange}
       ".target-from"
       {:-fx-text-fill text-muted
        :-fx-font-size 11}
       ".target-field-label"
       {:-fx-text-fill text-secondary}

       ;; Its menu
       ".target-menu-header"
       {:-fx-opacity 1.0}
       ".target-menu-header .label"
       {:-fx-text-fill text-muted
        :-fx-font-size 10
        :-fx-font-weight "bold"}
       ".target-menu-audience .label"
       {:-fx-text-fill orange}

       ;; A grid cell's badge, top right
       ".grid-cell-target-badge"
       {:-fx-background-color (colors/with-alpha "#000000" 0.45)
        :-fx-background-radius 6
        :-fx-padding [0 4 0 4]}
       ".grid-cell-target-badge .grid-cell-target-badge-text"
       {:-fx-font-size 10
        :-fx-font-weight "bold"
        :-fx-text-fill text-primary}
       ".grid-cell-target-badge.grid-cell-target-badge-inherited"
       {:-fx-opacity 0.55}
       ".grid-cell-target-badge-inherited .grid-cell-target-badge-text"
       {:-fx-font-weight "normal"}

       ;; A column's number, which sets the column's target: a label, until
       ;; it is clicked
       ".grid-column-header"
       {:-fx-background-color "transparent"
        :-fx-background-insets 0
        :-fx-background-radius 4
        :-fx-border-width 0
        :-fx-padding 0
        :-fx-text-fill text-secondary
        :-fx-cursor "hand"}
       ".grid-column-header:hover"
       {:-fx-background-color bg-interactive}
       ".grid-column-header .grid-column-header-text"
       {:-fx-text-fill text-secondary}
       ".grid-column-header.grid-column-header-set .grid-column-header-text"
       {:-fx-text-fill blue}
       ".grid-column-header .arrow-button"
       {:-fx-padding 0
        :-fx-background-color "transparent"}
       ".grid-column-header .arrow"
       {:-fx-padding 0
        :-fx-background-color "transparent"}

       ;; The zone map pads
       ".zone-map-pad"
       {:-fx-background-color bg-interactive
        :-fx-background-radius 8
        :-fx-border-radius 8
        :-fx-border-color border
        :-fx-border-width 1
        :-fx-text-fill text-primary
        :-fx-cursor "hand"}
       ".zone-map-pad.zone-map-pad-large"
       {:-fx-padding [6 10 6 10]}
       ".zone-map-pad.zone-map-pad-small"
       {:-fx-padding [2 10 2 10]
        :-fx-background-radius 11
        :-fx-border-radius 11
        :-fx-font-size 11}
       ".zone-map-pad .zone-map-pad-name"
       {:-fx-text-fill text-primary
        :-fx-font-weight "bold"
        :-fx-font-size 13}
       ".zone-map-pad .zone-map-pad-state"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}
       ".zone-map-pad .zone-map-pad-keys"
       {:-fx-text-fill text-muted
        :-fx-font-size 10}
       ".zone-map-pad.zone-map-pad-active"
       {:-fx-background-color (colors/with-alpha blue 0.55)
        :-fx-border-color blue}
       ".zone-map-pad.zone-map-pad-pending"
       {:-fx-background-color (colors/with-alpha orange 0.45)
        :-fx-border-color orange}
       ".zone-map-pad.zone-map-pad-pending:blink"
       {:-fx-background-color bg-interactive}
       ".zone-map-pad.zone-map-pad-learning"
       {:-fx-border-color red
        :-fx-border-width 2}
       ".zone-map-strip-label"
       {:-fx-text-fill text-muted
        :-fx-font-size 11}
       ".zone-map-row-title"
       {:-fx-text-fill text-secondary
        :-fx-font-size 12
        :-fx-font-weight "bold"}

       ;; The map editor
       ".zone-map-warning"
       {:-fx-text-fill orange
        :-fx-font-size 11}
       ".zone-maps-section-title"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11
        :-fx-font-weight "bold"}})))
