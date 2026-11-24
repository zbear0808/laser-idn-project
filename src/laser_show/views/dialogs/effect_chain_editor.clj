(ns laser-show.views.dialogs.effect-chain-editor
  "Effects chain editor dialog.
   
   Two-column layout:
   - Left sidebar: Current effect chain with drag-and-drop reordering
   - Right section: Effect bank (tabbed by category) + parameter editor
   
   Effects are dynamically loaded from the effect registry.
   
   Features:
   - Multi-select with Click, Ctrl+Click, Shift+Click
   - Keyboard shortcuts: Ctrl+C (copy), Ctrl+V (paste), Ctrl+A (select all), Delete
   - Drag-and-drop reordering
   - Copy/paste across cells"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.chains :as chains]
            [laser-show.views.components.effect-bank :as effect-bank]
            [laser-show.views.components.effect-parameter-editor :as param-editor]
            [laser-show.views.components.inline-edit :as inline-edit]
            [laser-show.views.components.list :as list]
            [laser-show.views.dialogs.common :as common]))


;; Right Top: Tabbed Effect Bank (using data-driven component)


(defn- effect-bank-tabs
  "Tabbed effect bank using data-driven effect-bank component."
  [{:keys [col row active-bank-tab]}]
  {:fx/type effect-bank/effect-bank
   :active-tab (or active-bank-tab :shape)
   :on-tab-change {:event/type :ui/update-dialog-data
                   :dialog-id :effect-chain-editor}
   ;; Data-driven event template - the handler receives :item-id, the effect's id
   :item-event-template {:event/type :effect-chain/add-effect-from-bank
                         :col col
                         :row row}
   ;; The effects grid's bank: Zone pattern and Map cycle too (zones plan Z5)
   :scope :global})


;; Right Bottom: Parameter Editor (using shared component)


(defn- parameter-editor
  "Parameter editor for the selected effect using the shared component.
   Supports keyframe modulation and custom renderers."
  [{:keys [fx/context col row selected-effect-path effect-chain dialog-data]}]
  (let [selected-effect (when selected-effect-path
                          (get-in effect-chain (vec selected-effect-path)))
        effect-def (when selected-effect
                     (effects/get-effect (:effect-id selected-effect)))]
    (if selected-effect
      ;; Use shared effect parameter editor component
      {:fx/type param-editor/effect-parameter-editor
       :fx/context context
       :domain :effect-chains
       :entity-key [col row]
       :effect-path selected-effect-path
       :effect selected-effect
       :effect-def effect-def
       :dialog-data dialog-data}
      ;; No effect selected - show placeholder
      {:fx/type :v-box
       :spacing 8
       :style-class "dialog-section"
       :children [{:fx/type :label
                   :text "PARAMETERS"
                   :style-class "header-section"}
                  {:fx/type :label
                   :text "Select an effect from the chain"
                   :style-class "dialog-placeholder-text"}]})))


;; Main Dialog Content


(defn- effect-chain-editor-content
  "Main content of the effect chain editor dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :effect-chain-editor)
        {:keys [col row active-bank-tab]} dialog-data
        ;; Read selection from canonical list-ui state instead of dialog-data
        list-state (fx/sub-ctx context subs/list-ui-state [:effect-chain col row])
        selected-ids (:selected-ids list-state #{})
        chains-state (fx/sub-val context :chains)
        cell-data (get-in chains-state [:effect-chains [col row]])
        effect-chain (:items cell-data [])
        active? (:active? cell-data false)
        ;; For parameter editor - use first selected ID if single select
        first-selected-id (when (= 1 (count selected-ids))
                            (first selected-ids))
        first-selected-path (when first-selected-id
                              (chains/find-path-by-id effect-chain first-selected-id))
        ;; Get chain name for header
        chain-name (:name cell-data)
        default-name (common/default-name col row)
        editing-name? (:editing-name? dialog-data false)]
    {:fx/type :v-box
     :spacing 0
     :style-class "dialog-content"
     :pref-width 800
     :pref-height 550
     :children [;; Header with editable name (double-click to edit)
               {:fx/type :h-box
                :alignment :center-left
                :style-class "dialog-header"
                :children [{:fx/type inline-edit/inline-edit-text
                            :value chain-name
                            :placeholder default-name
                            :editing? editing-name?
                            :on-start-edit {:event/type :ui/update-dialog-data
                                            :dialog-id :effect-chain-editor
                                            :editing-name? true}
                            :on-commit {:event/type :effect-chain/set-name
                                        :col col
                                        :row row}
                            :on-cancel {:event/type :ui/update-dialog-data
                                        :dialog-id :effect-chain-editor
                                        :editing-name? false}}]}
               ;; Main content area
               {:fx/type :h-box
                :spacing 0
                :v-box/vgrow :always
                :children [;; Left sidebar - chain list with drag-and-drop
                           ;; Using list-editor with built-in keyboard handling
                           {:fx/type list/list-editor
                            :fx/context context
                            :h-box/hgrow :always
                            :items effect-chain
                            :component-id [:effect-chain col row]
                            :items-path [:chains :effect-chains [col row] :items]
                            :item-id-key :effect-id
                            :item-registry-fn effects/get-effect
                            :fallback-label "Unknown Effect"
                            :on-change-event :chain/set-items
                            :on-change-params {:domain :effect-chains :entity-key [col row]}
                            :header-label "CHAIN"
                            :empty-text "No effects\nAdd from bank →"}
                                   
                           ;; Right section
                           {:fx/type :v-box
                            :spacing 0
                            :h-box/hgrow :always
                            :children (filterv
                                       some?
                                       [;; Effect bank tabs (top)
                                        {:fx/type effect-bank-tabs
                                         :col col :row row
                                         :active-bank-tab active-bank-tab}

                                        ;; Parameter editor (bottom)
                                        {:fx/type parameter-editor
                                         :fx/context context
                                         :col col :row row
                                         :selected-effect-path first-selected-path
                                         :effect-chain effect-chain
                                         :dialog-data dialog-data}])}]}
               
               ;; Footer with Active checkbox, triggers button, and close button
               {:fx/type :h-box
                :alignment :center-left
                :spacing 12
                :style-class "dialog-footer"
                :children [{:fx/type :check-box
                            :text "Active"
                            :selected active?
                            :on-selected-changed {:event/type :effects/toggle-cell
                                                  :col col :row row}}
                           {:fx/type :button
                            :text "⚡ Triggers..."
                            :style-class "button-secondary"
                            :on-action {:event/type :ui/open-dialog
                                        :dialog-id :trigger-editor
                                        :data {:col col
                                               :row row
                                               :cell-mode :effects}}}
                           {:fx/type :region :h-box/hgrow :always}
                           {:fx/type :button
                            :text "Close"
                            :style-class "button-primary"
                            :on-action {:event/type :ui/close-dialog
                                        :dialog-id :effect-chain-editor}}]}]}))


;; Dialog Window


(defn effect-chain-editor-dialog
  "The effect chain editor dialog window."
  [{:keys [fx/context]}]
  (let [{:keys [col row]} (fx/sub-ctx context subs/dialog-data :effect-chain-editor)
        chain-name (:name (fx/sub-ctx context subs/effect-cell-chain col row))]
    {:fx/type common/dialog-stage
     :dialog-id :effect-chain-editor
     :title (common/window-title "Effects Chain" chain-name col row)
     :content {:fx/type effect-chain-editor-content}}))
