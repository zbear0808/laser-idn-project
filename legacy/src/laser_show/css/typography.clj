(ns laser-show.css.typography
  "Typography styles for the laser show UI using cljfx/css.
   
   Provides CSS classes for:
   - Headers (primary, section)
   - Labels (secondary, hint)
   - Text variants (monospace, small, tiny)
   
   Usage:
   Include (::css/url typography) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def typography
  "Typography styles for the application."
  (css/register ::typography
    (let [{:keys [text-primary text-secondary text-muted]} theme/semantic-colors]
      
      {".header-primary"
       {:-fx-text-fill text-primary
        :-fx-font-size 16
        :-fx-font-weight "bold"}
       
       ".header-section"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       
 
       ".label-secondary"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}
       
       ".label-hint"
       {:-fx-text-fill "#808080"
        :-fx-font-size 10
        :-fx-font-style "italic"}
       
       ".label-bold"
       {:-fx-text-fill text-primary
        :-fx-font-weight "bold"}
       
 
       ".text-monospace"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11
        :-fx-font-family "'Consolas', 'Monaco', monospace"}
       
       ;; Small text (10px)
       ".text-small"
       {:-fx-text-fill text-secondary
        :-fx-font-size 10}
       
       ;; Tiny text (9px)
       ".text-tiny"
       {:-fx-text-fill text-secondary
        :-fx-font-size 9}
       
       ;; Description text (gray, small)
       ".text-description"
       {:-fx-text-fill text-muted
        :-fx-font-size 10}
       
       ;; Count/stat text (subtle gray)
       ".text-count"
       {:-fx-text-fill "#606060"
        :-fx-font-size 11}})))
