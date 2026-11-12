(ns laser-show.css.timeline
  "Styles for the timeline editor's node chrome: toolbar, header column,
   inspector and content drawer. The canvas itself draws with laser-show.css.canvas.
   See plans/2026-11-08-timeline-rework/05-visual-design.md §3.5-§3.10."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def timeline
  (css/register ::timeline
    (let [{:keys [bg-base interactive border text-muted blue orange]} theme/base-colors
          {:keys [text-primary text-secondary]} theme/semantic-colors]
      {".tl-root"
       {:-fx-background-color bg-base}

       ;; Toolbar (one 40 px row)
       ".tl-toolbar"
       {:-fx-background-color "#161616"
        :-fx-pref-height 40
        :-fx-min-height 40
        :-fx-padding [0 8 0 8]
        :-fx-spacing 6
        :-fx-alignment "center-left"
        :-fx-border-color ["transparent" "transparent" "#262626" "transparent"]}
       ".tl-toolbar-sep"
       {:-fx-background-color "#2E2E2E"
        :-fx-min-width 1 :-fx-pref-width 1 :-fx-max-width 1
        :-fx-pref-height 22 :-fx-max-height 22}
       ".tl-readout"
       {:-fx-font-family "monospace"
        :-fx-font-size 13
        :-fx-text-fill text-primary
        :-fx-min-width 70
        :-fx-alignment "center"}
       ".tl-readout-tc"
       {:-fx-text-fill text-secondary
        :-fx-min-width 100}
       ".tl-readout-approx"
       {:-fx-opacity 0.6}
       ".tl-tempo"
       {:-fx-min-width 76
        :-fx-font-size 12}
       ".tl-segment"
       {:-fx-padding [3 8 3 8]
        :-fx-font-size 11}
       ".tl-icon-button"
       {:-fx-background-color "transparent"
        :-fx-background-radius 4
        :-fx-padding [4 7 4 7]
        :-fx-text-fill text-secondary
        :-fx-cursor "hand"
        ":hover" {:-fx-background-color "#262626"}
        ":disabled" {:-fx-opacity 0.35}}
       ".tl-toggle"
       {:-fx-background-color "transparent"
        :-fx-background-radius 4
        :-fx-padding [4 7 4 7]
        :-fx-text-fill text-muted
        :-fx-cursor "hand"
        ":hover" {:-fx-background-color "#262626"}
        ":selected" {:-fx-background-color (str blue "33")
                     :-fx-text-fill blue}}
       ".tl-toggle .label"
       {:-fx-text-fill "inherit"}
       ".tl-snap-combo"
       {:-fx-pref-width 78
        :-fx-font-size 11}
       ".tl-zoom-slider"
       {:-fx-pref-width 120}

       ;; Header column (220 px)
       ".tl-header-column"
       {:-fx-background-color "#141414"
        :-fx-min-width 220 :-fx-pref-width 220 :-fx-max-width 220
        :-fx-border-color ["transparent" "#262626" "transparent" "transparent"]}
       ".tl-header-corner"
       {:-fx-min-height 36 :-fx-pref-height 36 :-fx-max-height 36
        :-fx-padding [0 4 0 10]
        :-fx-alignment "center-left"
        :-fx-background-color "#161616"}
       ".tl-section-label"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       ".tl-header-row"
       {:-fx-background-color "#181818"
        :-fx-alignment "center-left"
        :-fx-spacing 4
        :-fx-padding [0 4 0 0]
        :-fx-cursor "hand"
        ":hover" {:-fx-background-color "#222222"}}
       ".tl-header-row-folder"
       {:-fx-background-color "#222222"}
       ".tl-header-row-selected"
       {:-fx-background-color (str blue "2E")
        :-fx-border-color ["transparent" "transparent" "transparent" blue]
        :-fx-border-width [0 0 0 2]}
       ".tl-header-name"
       {:-fx-text-fill "#E0E0E0"
        :-fx-font-size 12}
       ".tl-header-name-folder"
       {:-fx-font-size 11
        :-fx-font-weight "bold"}
       ".tl-header-name-muted"
       {:-fx-text-fill text-muted}
       ".tl-header-name-input"
       {:-fx-font-size 12
        :-fx-pref-height 22
        :-fx-padding [1 4 1 4]}
       ".tl-chevron"
       {:-fx-background-color "transparent"
        :-fx-padding [0 2 0 2]
        :-fx-text-fill text-secondary
        :-fx-cursor "hand"}
       ".tl-zone-chip"
       {:-fx-background-radius 9
        :-fx-border-radius 9
        :-fx-padding [1 7 1 7]
        :-fx-font-size 10
        :-fx-background-color (str blue "40")
        :-fx-text-fill blue
        :-fx-cursor "hand"
        :-fx-mark-color "transparent"}
       ".tl-zone-chip .label"
       {:-fx-text-fill "inherit"
        :-fx-font-size 10}
       ".tl-zone-chip .arrow-button"
       {:-fx-padding 0}
       ".tl-zone-chip .arrow"
       {:-fx-padding 0}
       ".tl-zone-chip-inherited"
       {:-fx-background-color "transparent"
        :-fx-border-color border
        :-fx-text-fill text-muted
        :-fx-font-style "italic"}
       ".tl-mute"
       {:-fx-min-width 18 :-fx-pref-width 18 :-fx-max-width 18
        :-fx-min-height 18 :-fx-pref-height 18 :-fx-max-height 18
        :-fx-padding 0
        :-fx-font-size 10
        :-fx-font-weight "bold"
        :-fx-background-color "transparent"
        :-fx-border-color interactive
        :-fx-border-radius 3
        :-fx-background-radius 3
        :-fx-text-fill text-muted
        :-fx-cursor "hand"
        ":selected" {:-fx-background-color orange
                     :-fx-border-color orange
                     :-fx-text-fill "#121212"}}

       ;; Inspector (right, 280 px)
       ".tl-inspector"
       {:-fx-background-color "#161616"
        :-fx-min-width 280 :-fx-pref-width 280 :-fx-max-width 280
        :-fx-padding 10
        :-fx-spacing 8
        :-fx-border-color ["transparent" "transparent" "transparent" "#262626"]}
       ".tl-inspector-title"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       ".tl-inspector-name"
       {:-fx-text-fill text-primary
        :-fx-font-size 13
        :-fx-font-weight "bold"}
       ".tl-field-label"
       {:-fx-text-fill text-muted
        :-fx-font-size 11}
       ".tl-field-value"
       {:-fx-text-fill "#E0E0E0"
        :-fx-font-size 11}
       ".tl-swatch"
       {:-fx-min-width 18 :-fx-pref-width 18 :-fx-max-width 18
        :-fx-min-height 18 :-fx-pref-height 18 :-fx-max-height 18
        :-fx-background-radius 9
        :-fx-border-radius 9
        :-fx-cursor "hand"}
       ".tl-swatch-selected"
       {:-fx-border-color text-primary
        :-fx-border-width 2}

       ;; Content drawer (bottom, 132 px)
       ".tl-drawer"
       {:-fx-background-color "#161616"
        :-fx-min-height 132 :-fx-pref-height 132 :-fx-max-height 132
        :-fx-border-color ["#262626" "transparent" "transparent" "transparent"]}
       ".tl-hint"
       {:-fx-text-fill text-muted
        :-fx-font-size 11}

       ;; Scroll bars right under the lanes
       ".tl-scroll-bar"
       {:-fx-background-color bg-base}})))
