(ns laser-show.css.vibes
  "Vibe mode's styles: the Perform tab (views/tabs/perform.clj), its header,
   the vibe strip's chips, the five pads and the BLACKOUT banner, sized to
   read from two metres; and the Vibes tab (views/tabs/vibes.clj), its
   list, editor, picker (views/components/vibe_picker.clj), Controls panel
   (vibe_controls.clj), checklist and lint. A vibe's own colour is data and
   is applied inline, as the zone group colours are
   (views/components/zone_chips.clj).
   
   cljfx.css writes a map of more than eight rules in hash order, so a state
   rule has one class more than the rule it overrides, and no two state rules
   that can apply together set the same property.
   
   Usage:
   Include (::css/url vibes) in your scene's :stylesheets vector."
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def vibes
  "Perform and Vibes tab styles."
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
       {:-fx-text-fill text-muted}

       ;; The Vibes tab

       ".vibe-section-title"
       {:-fx-text-fill text-secondary
        :-fx-font-size 12
        :-fx-font-weight "bold"}

       ".vibe-check"
       {:-fx-text-fill text-muted
        :-fx-font-size 13}

       ".vibe-check.vibe-check-done"
       {:-fx-text-fill text-primary}

       ".vibe-card"
       {:-fx-background-color bg-interactive
        :-fx-background-radius 6
        :-fx-border-color border-default
        :-fx-border-radius 6
        :-fx-border-width 1
        :-fx-padding "6 10"
        :-fx-text-fill text-primary
        :-fx-font-size 13
        :-fx-graphic-text-gap 8
        :-fx-cursor "hand"}

       ".vibe-card.vibe-card-selected"
       {:-fx-border-color blue
        :-fx-border-width 2}

       ".vibe-swatch"
       {:-fx-background-radius 3
        :-fx-border-color border-default
        :-fx-border-radius 3
        :-fx-border-width 1
        :-fx-padding 0
        :-fx-cursor "hand"}

       ".vibe-swatch.vibe-swatch-selected"
       {:-fx-border-color text-primary
        :-fx-border-width 2}

       ".vibe-energy"
       {:-fx-background-color "transparent"
        :-fx-text-fill text-primary
        :-fx-font-size 16
        :-fx-padding "0 1"
        :-fx-cursor "hand"}

       ".vibe-editor-now"
       {:-fx-text-fill blue
        :-fx-font-size 12
        :-fx-font-weight "bold"}

       ".vibe-check-box"
       {:-fx-text-fill text-primary
        :-fx-font-size 12}

       ".vibe-slot"
       {:-fx-background-color bg-interactive
        :-fx-background-radius "6 6 0 0"
        :-fx-text-fill text-secondary
        :-fx-padding "6 12"
        :-fx-font-size 12
        :-fx-cursor "hand"}

       ".vibe-slot.vibe-slot-active"
       {:-fx-background-color blue
        :-fx-text-fill text-primary
        :-fx-font-weight "bold"}

       ".vibe-option"
       {:-fx-background-color bg-interactive
        :-fx-background-radius 4
        :-fx-text-fill text-secondary
        :-fx-padding "3 8"
        :-fx-font-size 11
        :-fx-cursor "hand"}

       ".vibe-option.vibe-option-active"
       {:-fx-background-color blue
        :-fx-text-fill text-primary}

       ;; A picker cell's marks, over the grid cell (blue when in the slot)
       ".vibe-pick-pos"
       {:-fx-text-fill text-primary
        :-fx-font-size 10
        :-fx-font-weight "bold"}

       ".vibe-pick-roles"
       {:-fx-text-fill text-secondary
        :-fx-font-size 8}

       ".vibe-pick-blocked"
       {:-fx-text-fill orange
        :-fx-font-size 8
        :-fx-font-weight "bold"}

       ".vibe-dot"
       {:-fx-background-radius 4}

       ".vibe-lint-row"
       {:-fx-text-fill text-secondary
        :-fx-font-size 12
        :-fx-graphic-text-gap 6}

       ".vibe-lint-mark"
       {:-fx-text-fill text-muted
        :-fx-font-size 12
        :-fx-font-weight "bold"}

       ".vibe-lint-mark.vibe-lint-error"
       {:-fx-text-fill red}

       ".vibe-lint-mark.vibe-lint-warn"
       {:-fx-text-fill orange}

       ".vibe-rules"
       {" > .title" {:-fx-background-color bg-interactive}
        " > .title > .text" {:-fx-fill text-secondary}
        " > .content" {:-fx-background-color "transparent"
                       :-fx-border-color border-default}}

       ".vibe-rule"
       {:-fx-text-fill text-secondary
        :-fx-font-size 12}

       ".vibe-control-role"
       {:-fx-text-fill text-primary
        :-fx-font-size 12
        :-fx-font-weight "bold"}

       ".vibe-control-keys"
       {:-fx-text-fill text-secondary
        :-fx-font-size 11}

       ".vibe-control-keys.vibe-control-waiting"
       {:-fx-text-fill orange
        :-fx-font-weight "bold"}

       ".vibe-wizard-status"
       {:-fx-text-fill orange
        :-fx-font-size 12
        :-fx-font-weight "bold"}})))
