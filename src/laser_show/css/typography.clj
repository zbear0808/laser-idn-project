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
      
      {;; =========================================================================
       ;; Headers
       ;; =========================================================================
       
       ;; Primary header - main page/section titles (16px, bold, white)
       ".header-primary"
       {:-fx-text-fill text-primary
        :-fx-font-size 16
        :-fx-font-weight "bold"}
       
       ;; Section header - subsection titles (11px, bold, gray)
       ".header-section"
       {:-fx-text-fill text-muted
        :-fx-font-size 11
        :-fx-font-weight "bold"}
       
       ;; =========================================================================
       ;; Labels
       ;; =========================================================================
       
       ;; Secondary label - less emphasized content (11px, gray-200)
       ".label-secondary"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}
       
       ;; Hint text - italicized hints, empty state messages (gray-400)
       ".label-hint"
       {:-fx-text-fill "#808080"
        :-fx-font-size 10
        :-fx-font-style "italic"}
       
       ;; Bold white label for names/titles in lists
       ".label-bold"
       {:-fx-text-fill text-primary
        :-fx-font-weight "bold"}
       
       ;; =========================================================================
       ;; Text Variants
       ;; =========================================================================
       
       ;; Monospace text for code/values display
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
