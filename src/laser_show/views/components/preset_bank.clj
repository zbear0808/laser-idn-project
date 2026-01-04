(ns laser-show.views.components.preset-bank
  "Preset bank component for the cue chain editor.
   
   Displays available presets organized by category tabs (Geometric, Wave, Beam, Abstract).
   Each preset can be added to the cue chain with a single click.
   
   Similar to the effect bank in the effect-chain-editor but for animation presets."
  (:require [cljfx.api :as fx]
            [laser-show.animation.presets :as presets]
            [laser-show.views.components.tabs :as tabs]))


;; Preset Categories


(def preset-bank-tab-definitions
  "Tab definitions for the preset bank categories."
  [{:id :geometric :label "Geometric"}
   {:id :wave :label "Wave"}
   {:id :beam :label "Beam"}
   {:id :abstract :label "Abstract"}])


;; Preset Button


(defn- add-preset-button
  "Button to add a specific preset to the cue chain."
  [{:keys [cell preset-def on-add-preset]}]
  (let [[col row] cell]
    {:fx/type :button
     :text (:name preset-def)
     :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10; -fx-padding: 6 12;"
     :on-action (if on-add-preset
                  (fn [_] (on-add-preset (:id preset-def)))
                  {:event/type :cue-chain/add-preset
                   :col col :row row
                   :preset-id (:id preset-def)})}))


;; Tab Content


(defn- preset-bank-tab-content
  "Content for a single category tab in the preset bank."
  [{:keys [cell category on-add-preset]}]
  (let [category-presets (filter #(= category (:category %)) presets/all-presets)]
    {:fx/type :flow-pane
     :hgap 6
     :vgap 6
     :padding 10
     :style "-fx-background-color: #1E1E1E;"
     :children (if (seq category-presets)
                 (vec (for [preset category-presets]
                        {:fx/type add-preset-button
                         :cell cell
                         :preset-def preset
                         :on-add-preset on-add-preset}))
                 [{:fx/type :label
                   :text "No presets in this category"
                   :style "-fx-text-fill: #606060;"}])}))


;; Content Router


(defn- preset-bank-content-router
  "Routes to the correct preset bank content based on active tab."
  [{:keys [cell active-tab on-add-preset]}]
  {:fx/type preset-bank-tab-content
   :cell cell
   :category (or active-tab :geometric)
   :on-add-preset on-add-preset})


;; Main Preset Bank Component


(defn preset-bank
  "Tabbed preset bank showing available presets by category.
   
   Props:
   - :cell - [col row] of the cell being edited
   - :active-tab - Currently active category tab (default: :geometric)
   - :on-tab-change - Event map or function for tab changes
   - :on-add-preset - (optional) Function to call when adding preset, receives preset-id"
  [{:keys [cell active-tab on-tab-change on-add-preset]}]
  (let [active (or active-tab :geometric)]
    {:fx/type :v-box
     :pref-height 150
     :children [{:fx/type tabs/styled-tab-bar
                 :tabs preset-bank-tab-definitions
                 :active-tab active
                 :on-tab-change (or on-tab-change
                                    {:event/type :cue-chain/set-preset-tab})}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: #1E1E1E; -fx-background: #1E1E1E;"
                 :content {:fx/type preset-bank-content-router
                           :cell cell
                           :active-tab active
                           :on-add-preset on-add-preset}}]}))
