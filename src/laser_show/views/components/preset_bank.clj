(ns laser-show.views.components.preset-bank
  "Preset bank component for the cue chain editor.
   
   Displays available presets organized by category tabs (Geometric, Wave, Beam, Abstract).
   Each preset can be added to the cue chain with a single click.
   
   Uses the data-driven tabbed-bank component for the UI structure."
  (:require [laser-show.animation.presets :as presets]
            [laser-show.views.components.tabbed-bank :as tabbed-bank]))


;; Preset Categories


(def preset-bank-tab-definitions
  "Tab definitions for the preset bank categories."
  [{:id :geometric :label "Geometric"}
   {:id :wave :label "Wave"}
   {:id :beam :label "Beam"}
   {:id :abstract :label "Abstract"}])


;; Pre-computed items by category (computed once at load time)


(def ^:private presets-by-category
  "Map of category -> presets vector, pre-computed for stable identity."
  (reduce (fn [acc preset-def]
            (update acc (:category preset-def) (fnil conj []) preset-def))
          {}
          presets/all-presets))


;; Main Preset Bank Component


(defn preset-bank
  "Tabbed preset bank showing available presets by category.
   
   Uses the data-driven tabbed-bank component with pre-computed category data.
   
   Props:
   - :cell - [col row] of the cell being edited
   - :active-tab - Currently active category tab (default: :geometric)
   - :on-tab-change - Event map or function for tab changes"
  [{:keys [cell active-tab on-tab-change]}]
  (let [[col row] cell]
    {:fx/type tabbed-bank/tabbed-bank
     :tab-definitions preset-bank-tab-definitions
     :active-tab (or active-tab :geometric)
     :on-tab-change (or on-tab-change {:event/type :cue-chain/set-preset-tab})
     ;; Data-driven: pass items map instead of function
     :items-by-category presets-by-category
     ;; Data-driven event template - handler will receive :item-id and :item
     :item-event-template {:event/type :cue-chain/add-preset
                           :col col
                           :row row}
     :item-name-key :name
     :item-id-key :id
     :button-style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10; -fx-padding: 6 12;"
     :empty-text "No presets in this category"
     :pref-height 150
     :hgap 6
     :vgap 6
     :padding 10}))
