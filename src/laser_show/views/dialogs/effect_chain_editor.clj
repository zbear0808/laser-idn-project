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
            [laser-show.events.core :as events]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.css.core :as css]
            [laser-show.views.components.tabs :as tabs]
            [laser-show.views.components.custom-param-renderers :as custom-renderers]
            [laser-show.views.components.hierarchical-list :as hierarchical-list])
  (:import [javafx.scene.input KeyCode KeyEvent]))


;; Effect Registry Access


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


;; Right Top: Tabbed Effect Bank


(def effect-bank-tab-definitions
  "Tab definitions for the effect bank categories."
  [{:id :shape :label "Shape"}
   {:id :color :label "Color"}
   {:id :intensity :label "Intensity"}
   {:id :zone :label "Zone"}])

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
     :style "-fx-background-color: #1E1E1E;"
     :children (if (seq category-effects)
                 (vec (for [effect category-effects]
                        {:fx/type add-effect-button
                         :col col :row row
                         :effect-def effect}))
                 [{:fx/type :label
                   :text "No effects"
                   :style "-fx-text-fill: #606060;"}])}))

(defn- effect-bank-content-router
  "Routes to the correct effect bank content based on active tab."
  [{:keys [col row active-bank-tab]}]
  (case active-bank-tab
    :color {:fx/type effect-bank-tab-content :col col :row row :category :color}
    :shape {:fx/type effect-bank-tab-content :col col :row row :category :shape}
    :intensity {:fx/type effect-bank-tab-content :col col :row row :category :intensity}
    {:fx/type effect-bank-tab-content :col col :row row :category :shape}))

(defn- effect-bank-tabs
  "Tabbed effect bank using shared styled-tab-bar - Color, Shape, Intensity."
  [{:keys [col row active-bank-tab]}]
  (let [active-tab (or active-bank-tab :shape)]
    {:fx/type :v-box
     :pref-height 150
     :children [{:fx/type tabs/styled-tab-bar
                 :tabs effect-bank-tab-definitions
                 :active-tab active-tab
                 :on-tab-change {:event/type :ui/update-dialog-data
                                 :dialog-id :effect-chain-editor}}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: #1E1E1E; -fx-background: #1E1E1E;"
                 :content {:fx/type effect-bank-content-router
                           :col col :row row
                           :active-bank-tab active-tab}}]}))


;; Right Bottom: Parameter Editor


(defn- param-slider
  "Slider control for numeric parameters with editable text field.
   The text field allows typing a value and pressing Enter to update.
   Supports both :effect-idx (legacy, top-level) and :effect-path (nested)."
  [{:keys [col row effect-idx effect-path param-key param-spec current-value]}]
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
                                    :effect-path effect-path
                                    :param-key param-key}}
                {:fx/type :text-field
                 :text (format "%.2f" (double value))
                 :pref-width 55
                 :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 11; -fx-padding: 2 4;"
                 :on-action {:event/type :effects/update-param-from-text
                             :col col :row row
                             :effect-idx effect-idx
                             :effect-path effect-path
                             :param-key param-key
                             :min min :max max}}]}))

(defn- param-choice
  "Combo-box for choice parameters.
   Supports both :effect-idx (legacy, top-level) and :effect-path (nested)."
  [{:keys [col row effect-idx effect-path param-key param-spec current-value]}]
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
                                    :effect-path effect-path
                                    :param-key param-key}}]}))

(defn- param-checkbox
  "Checkbox for boolean parameters.
   Supports both :effect-idx (legacy, top-level) and :effect-path (nested)."
  [{:keys [col row effect-idx effect-path param-key param-spec current-value]}]
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
                                       :effect-path effect-path
                                       :param-key param-key}}]}))

(defn- param-control
  "Render appropriate control based on parameter type.
   Supports both :effect-idx (legacy, top-level) and :effect-path (nested)."
  [{:keys [param-spec] :as props}]
  (case (:type param-spec :float)
    :choice {:fx/type param-choice
             :col (:col props) :row (:row props)
             :effect-idx (:effect-idx props)
             :effect-path (:effect-path props)
             :param-key (:param-key props)
             :param-spec param-spec
             :current-value (:current-value props)}
    :bool {:fx/type param-checkbox
           :col (:col props) :row (:row props)
           :effect-idx (:effect-idx props)
           :effect-path (:effect-path props)
           :param-key (:param-key props)
           :param-spec param-spec
           :current-value (:current-value props)}
    ;; Default: numeric slider
    {:fx/type param-slider
     :col (:col props) :row (:row props)
     :effect-idx (:effect-idx props)
     :effect-path (:effect-path props)
     :param-key (:param-key props)
     :param-spec param-spec
     :current-value (:current-value props)}))

(defn- custom-param-renderer
  "Renders parameters with custom UI and mode toggle.
   
   Props:
   - :col, :row - Grid cell coordinates
   - :effect-idx - Index in effect chain (legacy, for top-level only)
   - :effect-path - Path to effect (supports nested effects)
   - :effect-def - Effect definition with :ui-hints
   - :current-params - Current parameter values
   - :ui-mode - Current UI mode (:visual or :numeric)
   - :params-map - Parameter specifications map
   - :dialog-data - Dialog state data (for curve editor channel tab tracking)"
  [{:keys [col row effect-idx effect-path effect-def current-params ui-mode params-map dialog-data]}]
  (let [ui-hints (:ui-hints effect-def)
        renderer-type (:renderer ui-hints)
        actual-mode (or ui-mode (:default-mode ui-hints :visual))]
    ;; RGB Curves is visual-only, no mode toggle
    (if (= renderer-type :rgb-curves)
      {:fx/type custom-renderers/rgb-curves-visual-editor
       :col col :row row
       :effect-path effect-path
       :current-params current-params
       :dialog-data dialog-data}
      
      ;; Other custom renderers have mode toggle
      {:fx/type :v-box
       :spacing 8
       :children [;; Mode toggle buttons
                  {:fx/type :h-box
                   :spacing 6
                   :alignment :center-left
                   :padding {:bottom 8}
                   :children [{:fx/type :label
                              :text "Edit Mode:"
                              :style "-fx-text-fill: #808080; -fx-font-size: 10;"}
                             {:fx/type :button
                              :text "👁 Visual"
                              :style (str "-fx-font-size: 9; -fx-padding: 3 10; "
                                         (if (= actual-mode :visual)
                                           "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                                           "-fx-background-color: #404040; -fx-text-fill: #B0B0B0;"))
                              :on-action {:event/type :effects/set-param-ui-mode
                                         :effect-idx effect-idx
                                         :effect-path effect-path
                                         :mode :visual}}
                             {:fx/type :button
                              :text "🔢 Numeric"
                              :style (str "-fx-font-size: 9; -fx-padding: 3 10; "
                                         (if (= actual-mode :numeric)
                                           "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                                           "-fx-background-color: #404040; -fx-text-fill: #B0B0B0;"))
                              :on-action {:event/type :effects/set-param-ui-mode
                                         :effect-idx effect-idx
                                         :effect-path effect-path
                                         :mode :numeric}}]}
                  
                  ;; Render based on mode
                  (if (= actual-mode :visual)
                    ;; Custom visual renderer
                    (case renderer-type
                      :spatial-2d {:fx/type custom-renderers/translate-visual-editor
                                  :col col :row row
                                  :effect-idx effect-idx
                                  :effect-path effect-path
                                  :current-params current-params
                                  :param-specs (:parameters effect-def)}
                      
                      :corner-pin-2d {:fx/type custom-renderers/corner-pin-visual-editor
                                     :col col :row row
                                     :effect-idx effect-idx
                                     :effect-path effect-path
                                     :current-params current-params
                                     :param-specs (:parameters effect-def)}
                      
                      ;; Fallback to standard params
                      {:fx/type :v-box
                       :spacing 6
                       :children (vec
                                  (for [[param-key param-spec] params-map]
                                    {:fx/type param-control
                                     :col col :row row
                                     :effect-idx effect-idx
                                     :effect-path effect-path
                                     :param-key param-key
                                     :param-spec param-spec
                                     :current-value (get current-params param-key)}))})
                    
                    ;; Numeric mode - standard sliders
                    {:fx/type :v-box
                     :spacing 6
                     :children (vec
                                (for [[param-key param-spec] params-map]
                                  {:fx/type param-control
                                   :col col :row row
                                   :effect-idx effect-idx
                                   :effect-path effect-path
                                   :param-key param-key
                                   :param-spec param-spec
                                   :current-value (get current-params param-key)}))})]})))

(defn- parameter-editor
  "Parameter editor for the selected effect.
   Supports both :selected-effect-idx (legacy, top-level only) and
   :selected-effect-path (nested effects via get-in)."
  [{:keys [col row selected-effect-idx selected-effect-path effect-chain dialog-data]}]
  (let [;; Use path-based lookup if available, otherwise fall back to index
        selected-effect (cond
                          selected-effect-path
                          (get-in effect-chain (vec selected-effect-path))
                          
                          selected-effect-idx
                          (nth effect-chain selected-effect-idx nil)
                          
                          :else nil)
        effect-def (when selected-effect
                     (effects/get-effect (:effect-id selected-effect)))
        current-params (:params selected-effect {})
        params-map (params-vector->map (:parameters effect-def []))
        ;; For UI mode lookup, use path if available (stringified for map key)
        ui-mode-key (or selected-effect-path selected-effect-idx)
        ui-mode (get-in dialog-data [:ui-modes ui-mode-key])]
    {:fx/type :v-box
     :spacing 8
     :style "-fx-background-color: #2A2A2A; -fx-padding: 8;"
     :children [{:fx/type :label
                 :text (if effect-def
                         (str "PARAMETERS: " (:name effect-def))
                         "PARAMETERS")
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                (if effect-def
                  (if (:ui-hints effect-def)
                    ;; Has custom UI - use custom renderer with mode toggle
                    {:fx/type :scroll-pane
                     :fit-to-width true
                     :style "-fx-background-color: transparent; -fx-background: #2A2A2A;"
                     :content {:fx/type custom-param-renderer
                              :col col :row row
                              :effect-idx selected-effect-idx
                              :effect-path selected-effect-path
                              :effect-def effect-def
                              :current-params current-params
                              :ui-mode ui-mode
                              :params-map params-map
                              :dialog-data dialog-data}}
                    
                    ;; Standard parameters - use existing controls
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
                                            :effect-path selected-effect-path
                                            :param-key param-key
                                            :param-spec param-spec
                                            :current-value (get current-params param-key)}))}})
                  
                  {:fx/type :label
                   :text "Select an effect from the chain"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"})]}))


;; Main Dialog Content


(defn- effect-chain-editor-content
  "Main content of the effect chain editor dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :effect-chain-editor)
        {:keys [col row selected-ids active-bank-tab]} dialog-data
        effects-state (fx/sub-val context :effects)
        cell-data (get-in effects-state [:cells [col row]])
        effect-chain (:effects cell-data [])
        active? (:active cell-data false)
        clipboard-items (clipboard/get-effects-to-paste)
        ;; For parameter editor - use first selected ID if single select
        first-selected-id (when (= 1 (count selected-ids))
                            (first selected-ids))
        first-selected-path (when first-selected-id
                              (chains/find-path-by-id effect-chain first-selected-id))]
    {:fx/type :v-box
     :spacing 0
     :style "-fx-background-color: #2D2D2D;"
     :pref-width 600
     :pref-height 550
     :children [;; Main content area
               {:fx/type :h-box
                :spacing 0
                :v-box/vgrow :always
                :children [;; Left sidebar - chain list with drag-and-drop
                           ;; Using hierarchical-list-editor with built-in keyboard handling
                           {:fx/type hierarchical-list/hierarchical-list-editor
                            :h-box/hgrow :always
                            :items effect-chain
                            :component-id [:effect-chain col row]
                            :item-id-key :effect-id
                            :item-registry-fn effects/get-effect
                            :item-name-key :name
                            :fallback-label "Unknown Effect"
                            :on-change-event :effects/set-chain
                            :on-change-params {:col col :row row}
                            :on-selection-event :effects/update-selection
                            :on-selection-params {:col col :row row}
                            :selection-key :selected-ids
                            :on-copy-fn (fn [items]
                                          (clipboard/copy-effect-chain! {:effects items}))
                            :clipboard-items clipboard-items
                            :header-label "CHAIN"
                            :empty-text "No effects\nAdd from bank →"}
                                   
                           ;; Right section
                           {:fx/type :v-box
                            :spacing 0
                            :h-box/hgrow :always
                            :children [;; Effect bank tabs (top)
                                       {:fx/type effect-bank-tabs
                                        :col col :row row
                                        :active-bank-tab active-bank-tab}
                                       
                                       ;; Parameter editor (bottom) - shows first selected
                                       {:fx/type parameter-editor
                                        :col col :row row
                                        :selected-effect-idx nil
                                        :selected-effect-path first-selected-path
                                        :effect-chain effect-chain
                                        :dialog-data dialog-data}]}]}
               
               ;; Footer with Active checkbox and close button
               {:fx/type :h-box
                :alignment :center-left
                :spacing 8
                :padding 12
                :style "-fx-background-color: #252525;"
                :children [{:fx/type :check-box
                            :text "Active"
                            :selected active?
                            :style "-fx-text-fill: white;"
                            :on-selected-changed {:event/type :effects/toggle-cell
                                                  :col col :row row}}
                           {:fx/type :region :h-box/hgrow :always}
                           {:fx/type :button
                            :text "Close"
                            :style "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-padding: 6 20;"
                            :on-action {:event/type :ui/close-dialog
                                        :dialog-id :effect-chain-editor}}]}]}))


;; Scene-level Event Filter Setup


(defn- setup-scene-key-filter!
  "Setup Scene-level event filter for Ctrl+C and Ctrl+V.
   Scene-level filters catch events before any node handlers,
   allowing us to intercept system clipboard shortcuts."
  [^javafx.scene.Scene scene col row]
  (.addEventFilter
    scene
    KeyEvent/KEY_PRESSED
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [code (.getCode event)
              ctrl? (.isControlDown event)]
          (cond
            ;; Ctrl+C - Copy
            (and ctrl? (= code KeyCode/C))
            (do (events/dispatch! {:event/type :effects/copy-selected
                                   :col col :row row})
                (.consume event))
            
            ;; Ctrl+V - Paste
            (and ctrl? (= code KeyCode/V))
            (do (events/dispatch! {:event/type :effects/paste-into-chain
                                   :col col :row row})
                (.consume event))))))))


;; Dialog Window


;; NOTE: Dialog CSS is now defined in laser-show.css.dialogs
;; The CSS string literal has been moved to the centralized CSS system

(defn- effect-chain-editor-scene
  "Scene component with event filter for Ctrl+C/V.
   Stylesheets use the centralized CSS system."
  [{:keys [col row stylesheets]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [^javafx.scene.Scene scene]
                 (setup-scene-key-filter! scene col row))
   :desc {:fx/type :scene
          :stylesheets stylesheets
          :root {:fx/type effect-chain-editor-content}}})

(defn effect-chain-editor-dialog
  "The effect chain editor dialog window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :effect-chain-editor)
        dialog-data (fx/sub-ctx context subs/dialog-data :effect-chain-editor)
        {:keys [col row]} dialog-data
        ;; Use centralized CSS system - dialogs includes all needed styles
        stylesheets (css/dialog-stylesheet-urls)
        ;; Generate window title with cell identifier
        window-title (str "Effects Chain - Cell "
                         (char (+ 65 row))
                         (inc col))]
    {:fx/type :stage
     :showing open?
     :title window-title
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :effect-chain-editor}
     :scene {:fx/type effect-chain-editor-scene
             :col col
             :row row
             :stylesheets stylesheets}}))
