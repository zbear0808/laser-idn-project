(ns laser-show.ui-fx.views.preset-palette
  "Preset palette component - shows available presets for selection."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles]
            [laser-show.ui-fx.subs :as subs]
            [laser-show.ui-fx.events :as events]
            [laser-show.animation.presets :as presets]))

;; ============================================================================
;; Preset Button
;; ============================================================================

(defn preset-button
  "Individual preset button.
   
   Props:
   - :preset - Preset map with :id, :name, :category"
  [{:keys [preset]}]
  (let [{:keys [id name category]} preset
        color (styles/category-color category)]
    {:fx/type :button
     :text name
     :style (str "-fx-background-color: " color ";"
                "-fx-text-fill: white;"
                "-fx-font-size: 10px;"
                "-fx-padding: 6 10;"
                "-fx-background-radius: 4;"
                "-fx-cursor: hand;"
                "-fx-min-width: 90;")
     :on-action (fn [_]
                  (events/dispatch! {:event/type :grid/set-selected-preset
                                     :preset-id id}))}))

;; ============================================================================
;; Category Section
;; ============================================================================

(defn category-section
  "A section showing presets for one category.
   
   Props:
   - :category - Category keyword
   - :presets - Vector of presets in this category"
  [{:keys [category presets]}]
  (let [cat-info (get presets/categories category)
        cat-name (or (:name cat-info) (name category))]
    {:fx/type :v-box
     :spacing 4
     :children [{:fx/type :label
                 :text cat-name
                 :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                            "-fx-font-size: 11px;"
                            "-fx-font-weight: bold;"
                            "-fx-padding: 4 0;")}
                {:fx/type :flow-pane
                 :hgap 4
                 :vgap 4
                 :children (mapv (fn [preset]
                                   {:fx/type preset-button
                                    :preset preset})
                                 presets)}]}))

;; ============================================================================
;; Main Preset Palette
;; ============================================================================

(defn preset-palette
  "Main preset palette showing all available presets grouped by category.
   
   Props: None - reads from subscriptions"
  [_]
  (let [presets-by-cat (subs/presets-by-category)
        categories [:geometric :organic :beam :text :effect]]
    {:fx/type :scroll-pane
     :fit-to-width true
     :style (str "-fx-background-color: " (:surface styles/colors) ";"
                "-fx-background: " (:surface styles/colors) ";")
     :content {:fx/type :v-box
               :style (str "-fx-background-color: " (:surface styles/colors) ";"
                          "-fx-padding: 8;")
               :spacing 12
               :children (into []
                               (comp
                                (map (fn [cat]
                                       (let [cat-presets (get presets-by-cat cat)]
                                         (when (seq cat-presets)
                                           {:fx/type category-section
                                            :category cat
                                            :presets cat-presets}))))
                                (filter some?))
                               categories)}}))

;; ============================================================================
;; Compact Preset Grid (for smaller spaces)
;; ============================================================================

(defn preset-grid-compact
  "Compact grid of preset buttons without category headers.
   
   Props:
   - :columns - Number of columns (default 2)"
  [{:keys [columns]
    :or {columns 2}}]
  (let [all-presets (subs/all-presets)]
    {:fx/type :scroll-pane
     :fit-to-width true
     :style (str "-fx-background-color: " (:surface styles/colors) ";"
                "-fx-background: " (:surface styles/colors) ";")
     :content {:fx/type :flow-pane
               :style (str "-fx-background-color: " (:surface styles/colors) ";"
                          "-fx-padding: 8;")
               :hgap 4
               :vgap 4
               :pref-wrap-length (* columns 100)
               :children (mapv (fn [preset]
                                 {:fx/type preset-button
                                  :preset preset})
                               all-presets)}}))

;; ============================================================================
;; Preset Palette with Header
;; ============================================================================

(defn preset-palette-panel
  "Preset palette with a header.
   
   Props:
   - :title - Optional title (default 'Presets')"
  [{:keys [title]
    :or {title "Presets"}}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (:surface styles/colors) ";")
   :children [{:fx/type :label
               :text title
               :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                          "-fx-font-size: 12px;"
                          "-fx-font-weight: bold;"
                          "-fx-padding: 8;"
                          "-fx-background-color: " (:surface-light styles/colors) ";")}
              {:fx/type preset-palette
               :v-box/vgrow :always}]})
