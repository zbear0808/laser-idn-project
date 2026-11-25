(ns laser-show.css.shows
  "Styles for the Shows tab: its header, the table and its cells
   (plans/2026-11-25-timeline-shows/06 §4.7). There is no row-level
   \"playing\" style: the ▶ cell shows what plays (06 §2, P9)."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def shows
  (css/register ::shows
    (let [{:keys [bg-base text-muted]} theme/base-colors
          {:keys [text-primary text-secondary selection-bg accent-info accent-warning accent-success]} theme/semantic-colors]
      {".shows-header"
       {:-fx-background-color bg-base
        :-fx-padding [0 0 4 0]
        :-fx-spacing 8
        :-fx-alignment "center-left"}
       ".shows-search"
       {:-fx-pref-width 260
        :-fx-border-color accent-info
        :-fx-border-radius 3
        :-fx-border-width 0}
       ".shows-search:focused"
       {:-fx-border-width 1}

       ".shows-table"
       {:-fx-background-color bg-base
        :-fx-control-inner-background bg-base
        :-fx-control-inner-background-alt "#161616"
        :-fx-table-cell-border-color "#262626"
        :-fx-border-color "#262626"}
       ".shows-table .column-header-background"
       {:-fx-background-color "#161616"}
       ".shows-table .column-header"
       {:-fx-background-color "#161616"
        :-fx-border-color ["transparent" "#262626" "#262626" "transparent"]}
       ".shows-table .filler"
       {:-fx-background-color "#161616"}
       ".shows-table .column-header .label"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       ".shows-table .column-header .arrow"
       {:-fx-background-color text-secondary}
       ".shows-table .table-row-cell"
       {:-fx-background-color bg-base
        :-fx-border-color ["transparent" "transparent" "#1E1E1E" "transparent"]}
       ".shows-table .table-row-cell:odd"
       {:-fx-background-color "#151515"}
       ".shows-table .table-row-cell:selected"
       {:-fx-background-color (str selection-bg "55")}
       ".shows-table .table-cell"
       {:-fx-text-fill text-primary
        :-fx-padding [0 6 0 6]
        :-fx-alignment "center-left"}
       ".shows-table .table-cell .label"
       {:-fx-text-fill text-primary
        :-fx-font-size 12}
       ".shows-table .placeholder .label"
       {:-fx-text-fill text-muted}

       ".shows-table .table-cell .label.shows-cell-muted"
       {:-fx-text-fill text-muted}
       ".shows-table .table-cell .label.shows-cell-approx"
       {:-fx-opacity 0.6}
       ".shows-table .table-cell .label.shows-cell-mono"
       {:-fx-font-family "monospace"}
       ".shows-status"
       {:-fx-text-fill accent-success
        :-fx-font-size 11}
       ".shows-tc-problem"
       {:-fx-text-fill accent-warning}

       ".shows-tc-chip"
       {:-fx-font-family "monospace"
        :-fx-font-size 11
        :-fx-padding [2 6 2 6]
        :-fx-background-radius 3
        :-fx-text-fill "#101010"
        :-fx-background-color "#5A5A5A"}
       ".shows-tc-chip.shows-tc-running"
       {:-fx-background-color accent-success}
       ".shows-tc-chip.shows-tc-freewheel"
       {:-fx-background-color accent-warning}
       ".shows-tc-chip.shows-tc-stopped"
       {:-fx-text-fill text-secondary
        :-fx-background-color "#2A2A2A"}

       ".shows-edit-field"
       {:-fx-padding [0 4 0 4]
        :-fx-font-size 12}})))
