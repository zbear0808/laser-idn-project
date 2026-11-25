(ns laser-show.css.shows
  "The Shows tab's styles (plans/2026-11-25-timeline-shows 01 §7,
   views/tabs/shows.clj): the banner for what Open moved from the grid.

   Usage:
   Include (::css/url shows) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def shows
  "Shows tab styles."
  (css/register ::shows
    (let [{:keys [text-primary]} theme/semantic-colors
          {:keys [blue]} theme/base-colors]
      {".shows-banner"
       {:-fx-background-color (str blue "33")
        :-fx-border-color blue
        :-fx-border-width 1
        :-fx-border-radius 4
        :-fx-background-radius 4
        :-fx-padding [6 8 6 12]}
       ".shows-banner-text"
       {:-fx-text-fill text-primary
        :-fx-font-size 12}})))
