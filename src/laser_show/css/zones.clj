(ns laser-show.css.zones
  "Zones' styles (zones plan Z4): the Target chip and its menu
   (views/components/target_control.clj; compact in the timeline's track
   headers), the grid cells' badges and the
   column numbers that set a column's target, the zone map pads
   (views/components/zone_map_pads.clj) and the zones and maps under the
   Zone Groups tab (views/components/zone_maps_panel.clj); zone patterns'
   chip, places and window, the pattern pads and CYCLE (zones plan Z5,
   views/components/zone_pattern_editor.clj, pattern_pads.clj); the step
   grid's cells, lines, rows and numbers (zones plan Z6,
   views/components/step_grid.clj).

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
          {:keys [blue orange red border cat-purple]} theme/base-colors]
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

       ;; The zones and maps, under the Zone Groups tab's columns
       ".zone-maps-panel"
       {:-fx-padding [10 0 0 0]
        :-fx-border-color [border "transparent" "transparent" "transparent"]
        :-fx-border-width [1 0 0 0]}
       ".zone-map-item"
       {:-fx-background-radius 4
        :-fx-cursor "hand"}
       ".zone-map-item .zone-map-item-name"
       {:-fx-text-fill text-primary}
       ".zone-map-item .zone-map-item-manual"
       {:-fx-background-color (colors/with-alpha "#000000" 0.35)
        :-fx-background-radius 6
        :-fx-padding [0 5 0 5]
        :-fx-font-size 10
        :-fx-font-weight "bold"
        :-fx-text-fill text-primary}

       ;; The map editor
       ".zone-map-warning"
       {:-fx-text-fill orange
        :-fx-font-size 11}
       ".zone-maps-section-title"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11
        :-fx-font-weight "bold"}

       ;; Zone patterns (zones plan Z5): the Pattern chip, dim with none
       ".target-chip.pattern-chip.pattern-chip-none"
       {:-fx-background-color "transparent"
        :-fx-border-color border
        :-fx-text-fill text-muted}
       ;; A lane's places, and a Map cycle's maps
       ".zone-pattern-place"
       {:-fx-background-color (str blue "30")
        :-fx-background-radius 9
        :-fx-padding [1 2 1 8]}
       ".zone-pattern-place .zone-pattern-place-name"
       {:-fx-text-fill blue
        :-fx-font-size 11}
       ".zone-pattern-place .zone-pattern-place-remove"
       {:-fx-background-color "transparent"
        :-fx-text-fill text-secondary
        :-fx-padding [0 4 0 4]
        :-fx-font-size 11
        :-fx-cursor "hand"}
       ".zone-pattern-arrow"
       {:-fx-text-fill text-secondary}
       ;; The window's hints, Now, the fold line and warnings
       ".zone-pattern-hint"
       {:-fx-text-fill text-muted
        :-fx-font-size 11}
       ".zone-pattern-now"
       {:-fx-text-fill text-primary
        :-fx-font-weight "bold"}
       ".zone-pattern-fold"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11
        :-fx-graphic-text-gap 6}
       ".zone-pattern-fold .zone-pattern-fold-icon"
       {:-fx-text-fill blue}
       ".zone-pattern-warning"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}
       ".zone-pattern-warning.zone-pattern-warning-warn"
       {:-fx-text-fill orange}
       ".zone-pattern-warning.zone-pattern-warning-error"
       {:-fx-text-fill red}
       ;; The ⇄ on a cue's badge
       ".grid-cell-target-badge .grid-cell-pattern-glyph"
       {:-fx-text-fill blue}

       ;; CYCLE, and the map a Map cycle shows now
       ".zone-map-pad.zone-map-pad-dim"
       {:-fx-opacity 0.45}
       ".zone-map-pad.zone-map-pad-small:cycling"
       {:-fx-background-color (colors/with-alpha cat-purple 0.55)
        :-fx-border-color cat-purple}
       ".zone-map-pad.zone-map-pad-large:cycling"
       {:-fx-background-color (colors/with-alpha cat-purple 0.55)
        :-fx-border-color cat-purple}

       ;; The pattern pads: the map pads' shell
       ".pattern-pad"
       {:-fx-background-color bg-interactive
        :-fx-background-radius 8
        :-fx-border-radius 8
        :-fx-border-color border
        :-fx-border-width 1
        :-fx-text-fill text-primary
        :-fx-cursor "hand"}
       ".pattern-pad.pattern-pad-large"
       {:-fx-padding [6 8 6 8]}
       ".pattern-pad.pattern-pad-small"
       {:-fx-padding [2 10 2 10]
        :-fx-background-radius 11
        :-fx-border-radius 11
        :-fx-font-size 11}
       ".pattern-pad .pattern-pad-name"
       {:-fx-text-fill text-primary
        :-fx-font-weight "bold"
        :-fx-font-size 13}
       ".pattern-pad .pattern-pad-state"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}
       ".pattern-pad .pattern-pad-keys"
       {:-fx-text-fill text-muted
        :-fx-font-size 10}
       ".pattern-pad .pattern-step-count"
       {:-fx-text-fill text-primary
        :-fx-font-size 13}
       ".pattern-pad .pattern-step-pulse"
       {:-fx-background-color orange
        :-fx-background-radius 3}
       ".pattern-pad.pattern-pad-active"
       {:-fx-background-color (colors/with-alpha blue 0.55)
        :-fx-border-color blue}
       ".pattern-pad.pattern-pad-pending"
       {:-fx-background-color (colors/with-alpha orange 0.45)
        :-fx-border-color orange}
       ".pattern-pad.pattern-pad-pending:blink"
       {:-fx-background-color bg-interactive}
       ".pattern-pad.pattern-pad-learning"
       {:-fx-border-color red
        :-fx-border-width 2}

       ;; The step grid (zones plan Z6): its cells, the cursor, the beat and
       ;; bar lines under them, the row labels and the step numbers
       ".step-grid-body"
       {:-fx-background-color (colors/with-alpha "#000000" 0.25)}
       ".step-grid-body:focused"
       {:-fx-background-color (colors/with-alpha blue 0.08)}
       ".step-cell"
       {:-fx-background-color bg-interactive
        :-fx-background-insets 2
        :-fx-background-radius 3}
       ".step-cell.step-cell-on"
       {:-fx-background-color blue}
       ".step-cell.step-cell-mini"
       {:-fx-background-insets 1
        :-fx-background-radius 1}
       ".step-cell.step-cell-mini.step-cell-on"
       {:-fx-background-color blue}
       ".step-cell.step-cell-cursor"
       {:-fx-border-color text-primary
        :-fx-border-width 2
        :-fx-border-radius 4}
       ".step-grid-beat-line"
       {:-fx-background-color (colors/with-alpha text-muted 0.55)}
       ".step-grid-bar-line"
       {:-fx-background-color text-secondary}
       ".step-row-label"
       {:-fx-text-fill text-secondary
        :-fx-font-size 12
        :-fx-padding [0 6 0 0]
        :-fx-text-overrun "ellipsis"}
       ".step-number"
       {:-fx-text-fill text-muted
        :-fx-font-size 10}
       ".step-number.step-number-bar"
       {:-fx-text-fill text-secondary
        :-fx-font-weight "bold"}
       ".step-playhead"
       {:-fx-background-color "transparent"}
       ;; Its toolbar's buttons: length, Start from, Copy, Paste, Clear
       ".button.step-grid-button"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        :-fx-padding [3 9 3 9]
        :-fx-font-size 11
        :-fx-background-radius 4
        :-fx-cursor "hand"}
       ".button.step-grid-button:hover"
       {:-fx-background-color (colors/with-alpha blue 0.35)}
       ".button.step-grid-button.step-grid-button-selected"
       {:-fx-background-color (colors/with-alpha blue 0.6)}
       ".button.step-grid-button:disabled"
       {:-fx-opacity 0.45}})))
