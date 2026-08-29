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
   {:id :triggers :label "Triggers"}])


;; Pre-computed items by category (computed once at load time)


(def ^:private presets-by-category
  "Map of category -> presets vector, pre-computed for stable identity."
  (reduce (fn [acc preset-def]
            (update acc (:category preset-def) (fnil conj []) preset-def))
          {}
          presets/all-presets))




(defn preset-bank
  "Tabbed preset bank showing available presets by category.
   
   Uses the data-driven tabbed-bank component with pre-computed category data.
   
   Props:
   - :cell - [col row] of the cell being edited
   - :active-tab - Currently active category tab (default: :geometric)
   - :on-tab-change - Event map or function for tab changes
   - :item-event-template - Optional override for the event dispatched on item click.
                            Defaults to {:event/type :cue-chain/add-preset :col col :row row}"
  [{:keys [cell active-tab on-tab-change item-event-template]}]
  (let [[col row] cell
        event-template (or item-event-template
                           {:event/type :cue-chain/add-preset
                            :col col
                            :row row})]
    {:fx/type tabbed-bank/tabbed-bank
     :tab-definitions preset-bank-tab-definitions
     :active-tab (or active-tab :geometric)
     :on-tab-change (or on-tab-change {:event/type :cue-chain/set-preset-tab})
     ;; Data-driven: pass items map instead of function
     :items-by-category presets-by-category
     ;; Data-driven event template - handler will receive :item-id and :item
     :item-event-template event-template
     :item-name-key :name
     :item-id-key :id
     :button-style-class "bank-item-btn"
     :empty-text "No presets in this category"
     :hgap 6
     :vgap 6
     :padding 10}))
