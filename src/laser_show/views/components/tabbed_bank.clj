(ns laser-show.views.components.tabbed-bank
  "Generic tabbed bank component for items organized by category.
   
   Used for:
   - Effect banks (shape, color, intensity categories)
   - Preset banks (geometric, wave, beam, abstract categories)
   
   The component provides a tab bar at the top and a scrollable
   flow pane showing items for the selected category.
   
   IMPORTANT: This component uses DATA-DRIVEN props (maps, keywords, vectors)
   rather than function props to ensure stable prop identity for cljfx
   memoization. Passing functions as props causes re-renders on every
   context change because function identity changes.
   
   Usage:
   ```clojure
   {:fx/type tabbed-bank
    :tab-definitions [{:id :shape :label \"Shape\"} ...]
    :active-tab :shape
    :on-tab-change {:event/type :my/tab-change}
    :items [{:id :item-1 :name \"Item 1\"} ...]
    :item-event-template {:event/type :my/item-click}
    :item-id-key :id
    :item-name-key :name
    :empty-text \"No items\"}
   ```"
  (:require [laser-show.views.components.tabs :as tabs]))


;; Item Button (data-driven, no function props)


(defn- item-button
  "Render a single item button.
   
   Props:
   - :item - The item data map
   - :item-name-key - Key to get display name (default :name)
   - :item-id-key - Key to get item ID (default :id)
   - :event-template - Base event map to merge item-id into
   - :button-style - Optional inline style override"
  [{:keys [item item-name-key item-id-key event-template button-style]}]
  (let [name-key (or item-name-key :name)
        id-key (or item-id-key :id)
        item-id (get item id-key)
        item-name (get item name-key "Unknown")]
    {:fx/type :button
     :text item-name
     :style (or button-style
                "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10; -fx-padding: 4 8;")
     :on-action (merge event-template {:item-id item-id :item item})}))


;; Tabbed Bank Component


(defn tabbed-bank
  "Generic tabbed item bank with DATA-DRIVEN configuration.
   
   This component avoids function props to ensure stable prop identity
   for cljfx memoization. Use :items directly or provide category filtering
   via :items-by-category map.
   
   Props:
   - :tab-definitions - Vector of {:id :label} maps for tabs
   - :active-tab - Currently active tab ID (defaults to first tab)
   - :on-tab-change - Event map for tab changes
   
   Item Display (choose one approach):
   - :items - Direct vector of items to display (ignores tabs/categories)
   - :items-by-category - Map of category-id -> items vector
   
   Item Configuration:
   - :item-event-template - Event map base, will have :item-id and :item merged
   - :item-name-key - Key to get display name from item (default: :name)
   - :item-id-key - Key to get item ID (default: :id)
   - :button-style - Optional custom button style
   
   Layout:
   - :empty-text - Text shown when category is empty (default: \"No items\")
   - :pref-height - Height of bank (default: 150)
   - :hgap - Horizontal gap between buttons (default: 4)
   - :vgap - Vertical gap between buttons (default: 4)
   - :padding - Padding around flow pane (default: 8)"
  [{:keys [tab-definitions active-tab on-tab-change
           items items-by-category
           item-event-template item-name-key item-id-key button-style
           empty-text pref-height hgap vgap padding]}]
  (let [active (or active-tab (:id (first tab-definitions)))
        ;; Get items either directly or from category map
        display-items (or items
                         (get items-by-category active)
                         [])
        h (or pref-height 150)
        hg (or hgap 4)
        vg (or vgap 4)
        pad (or padding 8)
        empty-msg (or empty-text "No items")
        name-key (or item-name-key :name)
        id-key (or item-id-key :id)]
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
                           :children (if (seq display-items)
                                       (vec (for [item display-items]
                                              {:fx/type item-button
                                               :fx/key (get item id-key)
                                               :item item
                                               :item-name-key name-key
                                               :item-id-key id-key
                                               :event-template item-event-template
                                               :button-style button-style}))
                                       [{:fx/type :label
                                         :text empty-msg
                                         :style "-fx-text-fill: #606060;"}])}}]}))
