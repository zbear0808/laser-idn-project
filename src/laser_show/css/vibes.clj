(ns laser-show.css.vibes
  "Perform tab styles (views/tabs/perform.clj): the header, the vibe strip's
   chips, the five pads and the BLACKOUT banner, sized to read from two
   metres. A vibe's own colour is data and is applied inline, as the zone
   group colours are (views/components/zone_chips.clj).
   
   cljfx.css writes a map of more than eight rules in hash order, so a state
   rule has one class more than the rule it overrides, and no two state rules
   that can apply together set the same property.
   
   Usage:
   Include (::css/url vibes) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def vibes
  "Perform tab styles."
  (css/register ::vibes
    (let [{:keys [bg-interactive text-primary text-secondary text-muted border-default]} theme/semantic-colors
          {:keys [blue orange red]} theme/base-colors]
      {".vibe-header"
       {:-fx-background-color bg-interactive
        :-fx-background-radius 10
        :-fx-padding 12}

       ".vibe-header-name"
       {:-fx-text-fill text-primary
        :-fx-font-size 40
        :-fx-font-weight "bold"}

       ".vibe-header-text"
       {:-fx-text-fill text-primary
        :-fx-font-size 18}

       ".vibe-header-locked"
       {:-fx-text-fill text-primary
        :-fx-font-size 16
        :-fx-font-weight "bold"
        :-fx-border-color text-primary
        :-fx-border-radius 6
        :-fx-border-width 2
        :-fx-padding "2 8"}

       ".vibe-learn-prompt"
       {:-fx-text-fill orange
        :-fx-font-size 18
        :-fx-font-weight "bold"}

       ".vibe-banner"
       {:-fx-background-color red
        :-fx-background-radius 6
        :-fx-text-fill text-primary
        :-fx-font-size 28
        :-fx-font-weight "bold"
        :-fx-padding 8
        :-fx-alignment "center"}

       ".vibe-chip"
       {:-fx-background-color bg-interactive
        :-fx-background-radius 18
        :-fx-border-color border-default
        :-fx-border-radius 18
        :-fx-border-width 2
        :-fx-padding "8 16"
        :-fx-text-fill text-primary
        :-fx-font-size 16
        :-fx-cursor "hand"}

       ".vibe-chip.vibe-chip-current"
       {:-fx-border-width 4
        :-fx-font-weight "bold"}

       ".vibe-pad"
       {:-fx-background-color bg-interactive
        :-fx-background-radius 12
        :-fx-border-color border-default
        :-fx-border-radius 12
        :-fx-border-width 2
        :-fx-padding 6
        :-fx-cursor "hand"}

       ;; Held (lit) and BLACKOUT on are never both true of one pad
       ".vibe-pad.vibe-pad-held"
       {:-fx-background-color blue}

       ".vibe-pad.vibe-pad-on"
       {:-fx-background-color red}

       ".vibe-pad.vibe-pad-learning"
       {:-fx-border-color orange
        :-fx-border-width 4}

       ".vibe-pad.vibe-pad-dim"
       {:-fx-opacity 0.35}

       ".vibe-pad-role"
       {:-fx-text-fill text-primary
        :-fx-font-size 22
        :-fx-font-weight "bold"}

       ".vibe-pad-now"
       {:-fx-text-fill text-primary
        :-fx-font-size 18}

       ".vibe-pad-keys"
       {:-fx-text-fill text-secondary
        :-fx-font-size 12
        :-fx-text-alignment "center"}

       ".vibe-pad-badge"
       {:-fx-text-fill orange
        :-fx-font-size 12
        :-fx-font-weight "bold"}

       ".vibe-status"
       {:-fx-text-fill text-secondary
        :-fx-font-size 14}

       ".vibe-status.vibe-status-none"
       {:-fx-text-fill text-muted}})))
