(ns laser-show.views.components.preset-bank
  "Preset bank component for the cue chain editor.
   
   Displays available presets organized by category tabs (Geometric, Wave, Beam, Abstract).
   Each preset can be added to the cue chain with a single click.
   
   Uses the generic tabbed-bank component for the UI structure."
  (:require [laser-show.animation.presets :as presets]
            [laser-show.views.components.tabbed-bank :as tabbed-bank]))


;; Preset Categories


(def preset-bank-tab-definitions
  "Tab definitions for the preset bank categories."
  [{:id :geometric :label "Geometric"}
   {:id :wave :label "Wave"}
   {:id :beam :label "Beam"}
   {:id :abstract :label "Abstract"}])


;; Preset Button Factory


(defn- make-preset-button-fn
  "Create a button factory function for preset items.
   
   Returns a function (preset-def) -> cljfx button description."
  [{:keys [cell on-add-preset]}]
  (let [[col row] cell]
    (fn [preset-def]
      {:fx/type :button
       :text (:name preset-def)
       :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10; -fx-padding: 6 12;"
       :on-action (if on-add-preset
                    (fn [_] (on-add-preset (:id preset-def)))
                    {:event/type :cue-chain/add-preset
                     :col col :row row
                     :preset-id (:id preset-def)})})))


;; Items Function


(defn- presets-by-category
  "Get presets filtered by category."
  [category]
  (filterv #(= category (:category %)) presets/all-presets))


;; Main Preset Bank Component


(defn preset-bank
  "Tabbed preset bank showing available presets by category.
   
   Uses the generic tabbed-bank component with preset-specific configuration.
   
   Props:
   - :cell - [col row] of the cell being edited
   - :active-tab - Currently active category tab (default: :geometric)
   - :on-tab-change - Event map or function for tab changes
   - :on-add-preset - (optional) Function to call when adding preset, receives preset-id"
  [{:keys [cell active-tab on-tab-change on-add-preset]}]
  {:fx/type tabbed-bank/tabbed-bank
   :tab-definitions preset-bank-tab-definitions
   :active-tab (or active-tab :geometric)
   :on-tab-change (or on-tab-change {:event/type :cue-chain/set-preset-tab})
   :items-fn presets-by-category
   :item-button-fn (make-preset-button-fn {:cell cell :on-add-preset on-add-preset})
   :empty-text "No presets in this category"
   :pref-height 150
   :hgap 6
   :vgap 6
   :padding 10})
