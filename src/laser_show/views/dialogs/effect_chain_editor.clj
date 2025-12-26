(ns laser-show.views.dialogs.effect-chain-editor
  "Effects chain editor dialog.
   
   Two-column layout:
   - Left sidebar: Current effect chain with selection and reorder controls
   - Right section: Effect bank (tabbed by category) + parameter editor
   
   Effects are dynamically loaded from the effect registry."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.animation.effects :as effects]))

;; ============================================================================
;; Effect Registry Access
;; ============================================================================

(defn- effect-by-id
  "Get an effect definition by its ID from the registry."
  [effect-id]
  (effects/get-effect effect-id))

(defn- effects-by-category
  "Get effects filtered by category (excludes :calibration)."
  [category]
  (effects/list-effects-by-category category))

(defn- params-vector->map
  "Convert effect parameters from vector format (registry) to map format (UI).
   [{:key :x-scale :default 1.0 ...}] -> {:x-scale {:default 1.0 ...}}"
  [params-vector]
  (into {}
        (mapv (fn [p] [(:key p) (dissoc p :key)])
              params-vector)))

;; ============================================================================
;; Left Sidebar: Effect Chain List
;; ============================================================================

(defn- chain-item-card
  "A single effect in the chain sidebar.
   Shows effect name and control buttons."
  [{:keys [col row effect-idx effect effect-def selected?]}]
  {:fx/type :h-box
   :spacing 6
   :alignment :center-left
   :style (str "-fx-background-color: " (if selected? "#4A6FA5" "#3D3D3D") ";"
               "-fx-padding: 6 8;"
               "-fx-background-radius: 4;"
               "-fx-cursor: hand;")
   :on-mouse-clicked {:event/type :ui/update-dialog-data
                      :dialog-id :effect-chain-editor
                      :updates {:selected-effect-idx effect-idx}}
   :children [{:fx/type :label
               :text (str (inc effect-idx) ".")
               :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
              {:fx/type :label
               :text (or (:name effect-def) "Unknown")
               :style (str "-fx-text-fill: " (if selected? "white" "#E0E0E0") ";"
                           "-fx-font-size: 12;")}
              {:fx/type :region :h-box/hgrow :always}
              {:fx/type :button
               :text "↑"
               :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-padding: 1 5; -fx-font-size: 10;"
               :disable (= effect-idx 0)
               :on-action {:event/type :effects/reorder
                           :col col :row row
                           :from-idx effect-idx
                           :to-idx (max 0 (dec effect-idx))}}
              {:fx/type :button
               :text "↓"
               :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-padding: 1 5; -fx-font-size: 10;"
               :on-action {:event/type :effects/reorder
                           :col col :row row
                           :from-idx effect-idx
                           :to-idx (inc effect-idx)}}
              {:fx/type :button
               :text "✕"
               :style "-fx-background-color: #D32F2F; -fx-text-fill: white; -fx-padding: 1 5; -fx-font-size: 10;"
               :on-action {:event/type :effects/remove-from-chain-and-clear-selection
                           :col col :row row
                           :effect-idx effect-idx}}]})

(defn- chain-list-sidebar
  "Left sidebar showing the effect chain."
  [{:keys [col row effect-chain selected-effect-idx]}]
  {:fx/type :v-box
   :spacing 8
   :pref-width 180
   :min-width 180
   :style "-fx-background-color: #252525; -fx-padding: 8;"
   :children [{:fx/type :label
               :text "CHAIN"
               :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
              (if (empty? effect-chain)
                {:fx/type :label
                 :text "No effects\nAdd from bank →"
                 :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: transparent; -fx-background: #252525;"
                 :content {:fx/type :v-box
                           :spacing 4
                           :children (vec
                                       (map-indexed
                                         (fn [idx effect]
                                           {:fx/type chain-item-card
                                            :col col :row row
                                            :effect-idx idx
                                            :effect effect
                                            :effect-def (effect-by-id (:effect-id effect))
                                            :selected? (= idx selected-effect-idx)})
                                         effect-chain))}})]})

;; ============================================================================
;; Right Top: Tabbed Effect Bank
;; ============================================================================

(defn- add-effect-button
  "Button to add a specific effect to the chain."
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

(defn- effect-bank-tab-content
  "Content for a single category tab in the effect bank."
  [{:keys [col row category]}]
  (let [category-effects (effects-by-category category)]
    {:fx/type :flow-pane
     :hgap 4
     :vgap 4
     :padding 8
     :children (if (seq category-effects)
                 (vec (for [effect category-effects]
                        {:fx/type add-effect-button
                         :col col :row row
                         :effect-def effect}))
                 [{:fx/type :label
                   :text "No effects"
                   :style "-fx-text-fill: #606060;"}])}))

(defn- effect-bank-tabs
  "Tabbed effect bank - Color, Shape, Intensity."
  [{:keys [col row]}]
  {:fx/type :tab-pane
   :tab-closing-policy :unavailable
   :style "-fx-background-color: #2D2D2D;"
   :pref-height 150
   :tabs [{:fx/type :tab
           :text "Color"
           :content {:fx/type effect-bank-tab-content
                     :col col :row row
                     :category :color}}
          {:fx/type :tab
           :text "Shape"
           :content {:fx/type effect-bank-tab-content
                     :col col :row row
                     :category :shape}}
          {:fx/type :tab
           :text "Intensity"
           :content {:fx/type effect-bank-tab-content
                     :col col :row row
                     :category :intensity}}]})

;; ============================================================================
;; Right Bottom: Parameter Editor
;; ============================================================================

(defn- param-slider
  "Slider control for numeric parameters."
  [{:keys [col row effect-idx param-key param-spec current-value]}]
  (let [{:keys [min max]} param-spec
        value (or current-value (:default param-spec) 0)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (name param-key)
                 :pref-width 80
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :slider
                 :min min
                 :max max
                 :value (double value)
                 :pref-width 150
                 :on-value-changed {:event/type :effects/update-param
                                    :col col :row row
                                    :effect-idx effect-idx
                                    :param-key param-key}}
                {:fx/type :label
                 :text (format "%.2f" (double value))
                 :pref-width 45
                 :style "-fx-text-fill: white; -fx-font-size: 11;"}]}))

(defn- param-choice
  "Combo-box for choice parameters."
  [{:keys [col row effect-idx param-key param-spec current-value]}]
  (let [choices (:choices param-spec [])
        value (or current-value (:default param-spec))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text (name param-key)
                 :pref-width 80
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :combo-box
                 :items choices
                 :value value
                 :pref-width 150
                 :button-cell (fn [item] {:text (if item (name item) "")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [item] {:text (if item (name item) "")})}
                 :on-value-changed {:event/type :effects/update-param
                                    :col col :row row
                                    :effect-idx effect-idx
                                    :param-key param-key}}]}))

(defn- param-checkbox
  "Checkbox for boolean parameters."
  [{:keys [col row effect-idx param-key param-spec current-value]}]
  (let [value (if (some? current-value) current-value (:default param-spec false))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :check-box
                 :text (name param-key)
                 :selected value
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"
                 :on-selected-changed {:event/type :effects/update-param
                                       :col col :row row
                                       :effect-idx effect-idx
                                       :param-key param-key}}]}))

(defn- param-control
  "Render appropriate control based on parameter type."
  [{:keys [param-spec] :as props}]
  (case (:type param-spec :float)
    :choice {:fx/type param-choice
             :col (:col props) :row (:row props)
             :effect-idx (:effect-idx props)
             :param-key (:param-key props)
             :param-spec param-spec
             :current-value (:current-value props)}
    :bool {:fx/type param-checkbox
           :col (:col props) :row (:row props)
           :effect-idx (:effect-idx props)
           :param-key (:param-key props)
           :param-spec param-spec
           :current-value (:current-value props)}
    ;; Default: numeric slider
    {:fx/type param-slider
     :col (:col props) :row (:row props)
     :effect-idx (:effect-idx props)
     :param-key (:param-key props)
     :param-spec param-spec
     :current-value (:current-value props)}))

(defn- parameter-editor
  "Parameter editor for the selected effect."
  [{:keys [col row selected-effect-idx effect-chain]}]
  (let [selected-effect (when selected-effect-idx
                          (nth effect-chain selected-effect-idx nil))
        effect-def (when selected-effect
                     (effect-by-id (:effect-id selected-effect)))
        current-params (:params selected-effect {})
        params-map (params-vector->map (:parameters effect-def []))]
    {:fx/type :v-box
     :spacing 8
     :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
     :children [{:fx/type :label
                 :text (if effect-def
                         (str "PARAMETERS: " (:name effect-def))
                         "PARAMETERS")
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                (if effect-def
                  {:fx/type :scroll-pane
                   :fit-to-width true
                   :style "-fx-background-color: transparent; -fx-background: #2A2A2A;"
                   :content {:fx/type :v-box
                             :spacing 6
                             :padding {:top 4}
                             :children (vec
                                         (for [[param-key param-spec] params-map]
                                           {:fx/type param-control
                                            :col col :row row
                                            :effect-idx selected-effect-idx
                                            :param-key param-key
                                            :param-spec param-spec
                                            :current-value (get current-params param-key)}))}}
                  {:fx/type :label
                   :text "Select an effect from the chain"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"})]}))

;; ============================================================================
;; Main Dialog Content
;; ============================================================================

(defn- effect-chain-editor-content
  "Main content of the effect chain editor dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :effect-chain-editor)
        {:keys [col row selected-effect-idx]} dialog-data
        effects-state (fx/sub-val context :effects)
        cell-data (get-in effects-state [:cells [col row]])
        effect-chain (:effects cell-data [])
        active? (:active cell-data false)]
    {:fx/type :v-box
     :spacing 0
     :style "-fx-background-color: #2D2D2D;"
     :pref-width 600
     :pref-height 450
     :children [;; Header
                {:fx/type :h-box
                 :alignment :center-left
                 :spacing 8
                 :padding 12
                 :style "-fx-background-color: #252525;"
                 :children [{:fx/type :label
                             :text (str "Effects Chain - Cell "
                                        (char (+ 65 row))
                                        (inc col))
                             :style "-fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold;"}
                            {:fx/type :region :h-box/hgrow :always}
                            {:fx/type :check-box
                             :text "Active"
                             :selected active?
                             :style "-fx-text-fill: white;"
                             :on-selected-changed {:event/type :effects/toggle-cell
                                                   :col col :row row}}]}
                
                ;; Main content area
                {:fx/type :h-box
                 :spacing 0
                 :v-box/vgrow :always
                 :children [;; Left sidebar - chain list
                            {:fx/type chain-list-sidebar
                             :col col :row row
                             :effect-chain effect-chain
                             :selected-effect-idx selected-effect-idx}
                            
                            ;; Right section
                            {:fx/type :v-box
                             :spacing 0
                             :h-box/hgrow :always
                             :children [;; Effect bank tabs (top)
                                        {:fx/type effect-bank-tabs
                                         :col col :row row}
                                        
                                        ;; Parameter editor (bottom)
                                        {:fx/type parameter-editor
                                         :col col :row row
                                         :selected-effect-idx selected-effect-idx
                                         :effect-chain effect-chain}]}]}
                
                ;; Footer with close button
                {:fx/type :h-box
                 :alignment :center-right
                 :padding 12
                 :style "-fx-background-color: #252525;"
                 :children [{:fx/type :button
                             :text "Close"
                             :style "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-padding: 6 20;"
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
                                  ".root { -fx-base: #2D2D2D; -fx-background: #2D2D2D; }
                                   .tab-pane > .tab-header-area > .tab-header-background { -fx-background-color: #252525; }
                                   .tab { -fx-background-color: #3D3D3D; }
                                   .tab:selected { -fx-background-color: #4A6FA5; }
                                   .tab .tab-label { -fx-text-fill: white; }
                                   .scroll-pane { -fx-background-color: transparent; }
                                   .scroll-pane > .viewport { -fx-background-color: transparent; }"
                                  "UTF-8"))]
             :root {:fx/type effect-chain-editor-content}}}))
