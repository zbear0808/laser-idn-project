(ns laser-show.views.components.effect-bank
  "Effect bank component for chain editors.
   
   Displays available effects organized by category tabs (Shape, Color, Intensity).
   Each effect can be added to an effect chain with a single click.
   
   Uses the data-driven tabbed-bank component for the UI structure.
   
   What it offers comes from the registered effects as published into app
   state (subs/effect-bank-categories, reactivity plan 17), so a
   re-registered effect shows as it is now once published. A click sends
   the effect's id; the handler reads its definition when it runs."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.views.components.tabbed-bank :as tabbed-bank]))


;; Effect Categories (excludes :calibration which is projector-only)


(def effect-bank-tab-definitions
  "Tab definitions for the effect bank categories."
  [{:id :shape :label "Shape"}
   {:id :color :label "Color"}
   {:id :intensity :label "Intensity"}])

(def effect-bank-tab-definitions-with-zone
  "Tab definitions including zone effects."
  [{:id :shape :label "Shape"}
   {:id :color :label "Color"}
   {:id :intensity :label "Intensity"}
   {:id :zone :label "Zone"}])

(def ^:private zone-hint
  "The effects grid's Zone tab: the order zone patterns run in (07 §7.2)."
  "Zone patterns run after each cue's own pattern, cell by cell, row by row, left to right.")


;; Effect Bank Component


(defn effect-bank
  "Tabbed effect bank showing available effects by category.
   
   Uses the data-driven tabbed-bank component with stable category data.
   
   Props:
   - :active-tab - Currently active category tab (default: :shape)
   - :on-tab-change - Event map for tab changes
   - :item-event-template - Event map template for adding effects
                          Will receive :item-id (the effect's id)
   - :scope - :item (the default: a cue's items, the timeline's clips and
              tracks) or :global (the effects grid): the effects it offers
              (subs/effect-bank-categories); the Zone tab shows while it
              offers one, with its hint (zones plan Z5)
   - :fill? - Grow with the parent (default: fixed 80 px)
   - :drag-kind - :effect to make the buttons drag sources (timeline browser)"
  [{:keys [fx/context active-tab on-tab-change item-event-template scope fill? drag-kind]}]
  (let [effects-map (fx/sub-ctx context subs/effect-bank-categories (or scope :item))
        zone? (seq (:zone effects-map))
        tabs (if zone?
               effect-bank-tab-definitions-with-zone
               effect-bank-tab-definitions)
        active-tab (or active-tab :shape)]
    {:fx/type tabbed-bank/tabbed-bank
     :tab-definitions tabs
     :hint (when (and zone? (= :zone active-tab)) zone-hint)
     :hint-id "effect-bank-hint"
     :active-tab active-tab
     :on-tab-change on-tab-change
     ;; Data-driven: pass pre-computed items map
     :items-by-category effects-map
     ;; Data-driven event template
     :item-event-template item-event-template
     :item-name-key :name
     :item-id-key :id
     :button-style-class "bank-item-btn"
     :empty-text "No effects"
     :fill? fill?
     :drag-kind drag-kind}))
