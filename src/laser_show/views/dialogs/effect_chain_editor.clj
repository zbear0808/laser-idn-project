(ns laser-show.views.dialogs.effect-chain-editor
  "Effects chain editor dialog.
   
   Allows editing the effects chain for a grid cell:
   - View current effects in chain
   - Add/remove effects
   - Reorder effects
   - Edit effect parameters
   
   Effects are dynamically loaded from the effect registry
   (laser-show.animation.effects) - no hardcoded effect definitions."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.events.core :as events]
            [laser-show.animation.effects :as effects]))

;; ============================================================================
;; Effect Registry Access
;; ============================================================================

(defn get-available-effects
  "Get all registered effects from the effect registry.
   Returns a list of effect definitions."
  []
  (effects/list-effects))

(defn effect-by-id
  "Get an effect definition by its ID from the registry."
  [effect-id]
  (effects/get-effect effect-id))

(defn effects-by-category
  "Get effects filtered by category."
  [category]
  (effects/list-effects-by-category category))

(defn params-vector->map
  "Convert effect parameters from vector format (registry) to map format (UI).
   [{:key :x-scale :default 1.0 ...}] -> {:x-scale {:default 1.0 ...}}"
  [params-vector]
  (into {}
        (map (fn [p] [(:key p) (dissoc p :key)])
             params-vector)))

;; ============================================================================
;; Effect Item in Chain
;; ============================================================================

(defn effect-param-slider
  "Slider for editing an effect parameter."
  [{:keys [col row effect-idx param-key param-spec current-value]}]
  (let [{:keys [min max]} param-spec
        value (or current-value (:default param-spec))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (name param-key)
                 :pref-width 60
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :slider
                 :min min
                 :max max
                 :value value
                 :pref-width 120
                 :on-value-changed {:event/type :effects/update-param
                                    :col col :row row
                                    :effect-idx effect-idx
                                    :param-key param-key}}
                {:fx/type :label
                 :text (format "%.2f" (double value))
                 :pref-width 40
                 :style "-fx-text-fill: white; -fx-font-size: 11;"}]}))

(defn effect-chain-item
  "A single effect in the chain with controls.
   effect-def is from the registry with :parameters (vector of param specs)
   effect has :params (map of current values)"
  [{:keys [col row effect-idx effect effect-def]}]
  (let [current-params (:params effect {})
        params-map (params-vector->map (:parameters effect-def []))]
    {:fx/type :v-box
     :spacing 4
     :style "-fx-background-color: #3D3D3D; -fx-padding: 8; -fx-background-radius: 4;"
     :children [{:fx/type :h-box
                 :spacing 8
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text (str (inc effect-idx) ".")
                             :style "-fx-text-fill: #808080; -fx-font-size: 12;"}
                            {:fx/type :label
                             :text (:name effect-def "Unknown")
                             :style "-fx-text-fill: white; -fx-font-weight: bold;"}
                            {:fx/type :region :h-box/hgrow :always}
                            {:fx/type :button
                             :text "↑"
                             :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-padding: 2 6;"
                             :disable (= effect-idx 0)
                             :on-action {:event/type :effects/reorder
                                         :col col :row row
                                         :from-idx effect-idx
                                         :to-idx (max 0 (dec effect-idx))}}
                            {:fx/type :button
                             :text "↓"
                             :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-padding: 2 6;"
                             :on-action {:event/type :effects/reorder
                                         :col col :row row
                                         :from-idx effect-idx
                                         :to-idx (inc effect-idx)}}
                            {:fx/type :button
                             :text "✕"
                             :style "-fx-background-color: #D32F2F; -fx-text-fill: white; -fx-padding: 2 6;"
                             :on-action {:event/type :effects/remove-effect
                                         :col col :row row
                                         :effect-idx effect-idx}}]}
                ;; Parameter sliders
                {:fx/type :v-box
                 :spacing 4
                 :padding {:left 20}
                 :children (vec
                             (for [[param-key param-spec] params-map]
                               {:fx/type effect-param-slider
                                :col col :row row
                                :effect-idx effect-idx
                                :param-key param-key
                                :param-spec param-spec
                                :current-value (get current-params param-key)}))}]}))

;; ============================================================================
;; Add Effect Selector
;; ============================================================================

(defn add-effect-button
  "Button to add a specific effect.
   effect-def is a registered effect from the effect registry with:
   - :id (keyword)
   - :name (string)
   - :parameters (vector of param specs)"
  [{:keys [col row effect-def]}]
  (let [params-map (params-vector->map (:parameters effect-def))]
    {:fx/type :button
     :text (:name effect-def)
     :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10; -fx-padding: 4 8;"
     :on-action {:event/type :effects/add-effect
                 :col col :row row
                 :effect {:effect-id (:id effect-def)
                          :params (into {}
                                        (for [[k v] params-map]
                                          [k (:default v)]))}}}))

(defn effect-category-row
  "A row in the palette for a specific effect category."
  [{:keys [col row label category]}]
  (let [category-effects (effects-by-category category)]
    (when (seq category-effects)
      {:fx/type :h-box
       :spacing 4
       :children [{:fx/type :label
                   :text label
                   :pref-width 70
                   :style "-fx-text-fill: #808080;"}
                  {:fx/type :flow-pane
                   :hgap 4
                   :vgap 4
                   :children (vec
                               (for [effect category-effects]
                                 {:fx/type add-effect-button
                                  :col col :row row
                                  :effect-def effect}))}]})))

(defn add-effect-palette
  "Palette of effects that can be added.
   Categories are loaded dynamically from the effect registry."
  [{:keys [col row]}]
  {:fx/type :v-box
   :spacing 8
   :children (filterv some?
               [{:fx/type :label
                 :text "Add Effect"
                 :style "-fx-text-fill: white; -fx-font-weight: bold;"}
                {:fx/type effect-category-row
                 :col col :row row
                 :label "Shape:"
                 :category :shape}
                {:fx/type effect-category-row
                 :col col :row row
                 :label "Color:"
                 :category :color}
                {:fx/type effect-category-row
                 :col col :row row
                 :label "Intensity:"
                 :category :intensity}
                {:fx/type effect-category-row
                 :col col :row row
                 :label "Calibration:"
                 :category :calibration}])})

;; ============================================================================
;; Effect Chain Editor Dialog Content
;; ============================================================================

(defn effect-chain-editor-content
  "Content of the effect chain editor dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :effect-chain-editor)
        {:keys [col row]} dialog-data
        effects-state (fx/sub-val context :effects)
        cell-data (get-in effects-state [:cells [col row]])
        effect-chain (:effects cell-data [])
        active? (:active cell-data false)]
    {:fx/type :v-box
     :spacing 16
     :padding 16
     :style "-fx-background-color: #2D2D2D;"
     :pref-width 450
     :children [{:fx/type :h-box
                 :alignment :center-left
                 :spacing 8
                 :children [{:fx/type :label
                             :text (str "Effects Chain - Cell "
                                        (char (+ 65 row))
                                        (inc col))
                             :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
                            {:fx/type :region :h-box/hgrow :always}
                            {:fx/type :check-box
                             :text "Active"
                             :selected active?
                             :style "-fx-text-fill: white;"
                             :on-selected-changed {:event/type :effects/toggle-cell
                                                   :col col :row row}}]}
                
                ;; Separator
                {:fx/type :separator}
                
                ;; Current chain
                {:fx/type :v-box
                 :spacing 8
                 :children [{:fx/type :label
                             :text (str "Chain (" (count effect-chain) " effects)")
                             :style "-fx-text-fill: #B0B0B0;"}
                            (if (empty? effect-chain)
                              {:fx/type :label
                               :text "No effects - add effects below"
                               :style "-fx-text-fill: #606060; -fx-font-style: italic;"}
                              {:fx/type :scroll-pane
                               :fit-to-width true
                               :pref-height 200
                               :style "-fx-background-color: transparent; -fx-background: #2D2D2D;"
                               :content {:fx/type :v-box
                                         :spacing 8
                                         :padding 4
                                         :children (vec
                                                     (map-indexed
                                                       (fn [idx effect]
                                                         {:fx/type effect-chain-item
                                                          :col col :row row
                                                          :effect-idx idx
                                                          :effect effect
                                                          :effect-def (effect-by-id (:effect-id effect))})
                                                       effect-chain))}})]}
                
                ;; Separator
                {:fx/type :separator}
                
                ;; Add effect palette
                {:fx/type add-effect-palette
                 :col col :row row}
                
                ;; Close button
                {:fx/type :h-box
                 :alignment :center-right
                 :children [{:fx/type :button
                             :text "Close"
                             :style "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-padding: 8 24;"
                             :on-action {:event/type :ui/close-dialog
                                         :dialog-id :effect-chain-editor}}]}]}))

;; ============================================================================
;; Dialog Window
;; ============================================================================

(defn effect-chain-editor-dialog
  "The effect chain editor dialog window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :effect-chain-editor)]
    {:fx/type :stage
     :showing open?
     :title "Edit Effects Chain"
     :modality :application-modal
     :on-close-request {:event/type :ui/close-dialog :dialog-id :effect-chain-editor}
     :scene {:fx/type :scene
             :stylesheets [(str "data:text/css,"
                                (java.net.URLEncoder/encode
                                  ".root { -fx-base: #2D2D2D; -fx-background: #2D2D2D; }"
                                  "UTF-8"))]
             :root {:fx/type effect-chain-editor-content}}}))
