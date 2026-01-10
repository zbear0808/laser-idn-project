(ns laser-show.views.components.tabbed-bank
  "Generic tabbed bank component for items organized by category.
   
   Used for:
   - Effect banks (shape, color, intensity categories)
   - Preset banks (geometric, wave, beam, abstract categories)
   
   The component provides a tab bar at the top and a scrollable
   flow pane showing items for the selected category.
   
   Usage:
   ```clojure
   {:fx/type tabbed-bank
    :tab-definitions [{:id :shape :label \"Shape\"} ...]
    :active-tab :shape
    :on-tab-change {:event/type :my/tab-change}
    :items-fn (fn [category] (get-items-by-category category))
    :item-button-fn (fn [item] {:fx/type :button :text (:name item) ...})
    :empty-text \"No items\"}
   ```"
  (:require [laser-show.views.components.tabs :as tabs]))


(defn tabbed-bank
  "Generic tabbed item bank.
   
   Props:
   - :tab-definitions - Vector of {:id :label} maps for tabs
   - :active-tab - Currently active tab ID (defaults to first tab)
   - :on-tab-change - Event map for tab changes
   - :items-fn - Function (category) -> vector of items for that category
   - :item-button-fn - Function (item) -> cljfx component description for item button
   - :empty-text - Text shown when category is empty (default: \"No items\")
   - :pref-height - Height of bank (default: 150)
   - :hgap - Horizontal gap between buttons (default: 4)
   - :vgap - Vertical gap between buttons (default: 4)
   - :padding - Padding around flow pane (default: 8)"
  [{:keys [tab-definitions active-tab on-tab-change
           items-fn item-button-fn
           empty-text pref-height hgap vgap padding]}]
  (let [active (or active-tab (:id (first tab-definitions)))
        items (items-fn active)
        h (or pref-height 150)
        hg (or hgap 4)
        vg (or vgap 4)
        pad (or padding 8)
        empty-msg (or empty-text "No items")]
    {:fx/type :v-box
     :pref-height h
     :children [{:fx/type tabs/styled-tab-bar
                 :tabs tab-definitions
                 :active-tab active
                 :on-tab-change on-tab-change}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: #1E1E1E; -fx-background: #1E1E1E;"
                 :content {:fx/type :flow-pane
                           :hgap hg
                           :vgap vg
                           :padding pad
                           :style "-fx-background-color: #1E1E1E;"
                           :children (if (seq items)
                                       (vec (for [item items]
                                              (item-button-fn item)))
                                       [{:fx/type :label
                                         :text empty-msg
                                         :style "-fx-text-fill: #606060;"}])}}]}))


;; Convenience Functions for Common Item Button Patterns


(defn make-item-button
  "Create a standard item button with consistent styling.
   
   Props:
   - :text - Button text (e.g., item name)
   - :on-action - Event map when button clicked
   - :style - Optional additional style (merged with base style)"
  [{:keys [text on-action style]}]
  {:fx/type :button
   :text text
   :style (str "-fx-background-color: #505050; -fx-text-fill: white; "
               "-fx-font-size: 10; -fx-padding: 4 8;"
               (when style (str " " style)))
   :on-action on-action})
