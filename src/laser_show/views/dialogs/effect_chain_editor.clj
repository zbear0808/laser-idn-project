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
            [laser-show.events.core :as events]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.css.core :as css]
            [laser-show.views.components.tabs :as tabs]
            [laser-show.views.components.custom-param-renderers :as custom-renderers])
  (:import [javafx.scene.input TransferMode ClipboardContent KeyCode KeyEvent]))


;; Effect Registry Access


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


;; Left Sidebar: Effect Chain List with Drag-and-Drop


(defn- setup-drag-source!
  "Setup drag source handlers on a node for reordering effects."
  [^javafx.scene.Node node effect-idx col row dispatch!]
  (.setOnDragDetected
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [dragboard (.startDragAndDrop node (into-array TransferMode [TransferMode/MOVE]))
              content (ClipboardContent.)]
          (.putString content (str effect-idx))
          (.setContent dragboard content)
          (dispatch! {:event/type :ui/update-dialog-data
                      :dialog-id :effect-chain-editor
                      :updates {:dragging-effect-idx effect-idx}})
          (.consume event)))))
  (.setOnDragDone
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (dispatch! {:event/type :ui/update-dialog-data
                    :dialog-id :effect-chain-editor
                    :updates {:dragging-effect-idx nil
                              :drop-target-idx nil}})
        (.consume event)))))

(defn- setup-drag-target!
  "Setup drag target handlers on a node for accepting dropped effects."
  [^javafx.scene.Node node effect-idx col row chain-count dispatch!]
  (.setOnDragOver
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (when (and (.getDragboard event)
                   (.hasString (.getDragboard event)))
          (.acceptTransferModes event (into-array TransferMode [TransferMode/MOVE])))
        (.consume event))))
  (.setOnDragEntered
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (when (.hasString (.getDragboard event))
          (dispatch! {:event/type :ui/update-dialog-data
                      :dialog-id :effect-chain-editor
                      :updates {:drop-target-idx effect-idx}}))
        (.consume event))))
  (.setOnDragExited
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (.consume event))))
  (.setOnDragDropped
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [dragboard (.getDragboard event)
              from-idx (when (.hasString dragboard)
                         (parse-long (.getString dragboard)))]
          (when (and from-idx (not= from-idx effect-idx))
            (let [to-idx (if (> from-idx effect-idx)
                           effect-idx
                           (min effect-idx (dec chain-count)))]
              (dispatch! {:event/type :effects/reorder
                          :col col :row row
                          :from-idx from-idx
                          :to-idx to-idx})))
          (.setDropCompleted event true)
          (.consume event))))))

(defn- setup-click-handler!
  "Setup click handler on a node for multi-select behavior.
   - Click: Select single
   - Ctrl+Click: Toggle selection
   - Shift+Click: Range select"
  [^javafx.scene.Node node effect-idx]
  (.setOnMouseClicked
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [ctrl? (.isControlDown event)
              shift? (.isShiftDown event)]
          (cond
            ctrl? (events/dispatch! {:event/type :effects/toggle-effect-selection
                                     :effect-idx effect-idx})
            shift? (events/dispatch! {:event/type :effects/range-select
                                      :effect-idx effect-idx})
            :else (events/dispatch! {:event/type :effects/select-effect
                                     :effect-idx effect-idx})))
        (.consume event)))))

(defn- chain-item-card
  "A single effect in the chain sidebar with drag-and-drop support.
   Shows enable checkbox, effect name with delete button.
   Supports multi-select with Click/Ctrl+Click/Shift+Click on the name label."
  [{:keys [col row effect-idx effect effect-def selected? dragging? drop-target? chain-count]}]
  (let [enabled? (:enabled? effect true)]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   ;; Use events/dispatch! directly - this handles state updates correctly
                   (let [dispatch! (fn [event]
                                     (events/dispatch! event))]
                     (setup-drag-source! node effect-idx col row dispatch!)
                     (setup-drag-target! node effect-idx col row chain-count dispatch!)))
     :desc {:fx/type :h-box
            :spacing 6
            :alignment :center-left
            :style (str "-fx-background-color: " (cond
                                                    drop-target? "#5A8FCF"
                                                    selected? "#4A6FA5"
                                                    :else "#3D3D3D") ";"
                        "-fx-padding: 6 8;"
                        "-fx-background-radius: 4;"
                        (when drop-target? "-fx-border-color: #7AB8FF; -fx-border-width: 2 0 0 0;")
                        (when dragging? "-fx-opacity: 0.5;")
                        (when-not enabled? "-fx-opacity: 0.6;"))
            :children [{:fx/type :label
                        :text "⠿"
                        :style "-fx-text-fill: #606060; -fx-font-size: 10; -fx-cursor: move;"}
                       
                       ;; Enable/disable checkbox (does NOT trigger selection)
                       {:fx/type :check-box
                        :selected enabled?
                        :on-selected-changed {:event/type :effects/set-effect-enabled
                                              :col col :row row
                                              :effect-idx effect-idx}}
                       
                       ;; Effect name - clickable for selection
                       {:fx/type fx/ext-on-instance-lifecycle
                        :on-created (fn [node]
                                      (setup-click-handler! node effect-idx))
                        :desc {:fx/type :label
                               :text (or (:name effect-def) "Unknown")
                               :style (str "-fx-text-fill: "
                                          (cond
                                            (not enabled?) "#808080"
                                            selected? "white"
                                            :else "#E0E0E0") ";"
                                          "-fx-font-size: 12;"
                                          "-fx-cursor: hand;")}}
                       {:fx/type :region :h-box/hgrow :always}
                       {:fx/type :button
                        :text "✕"
                        :style "-fx-background-color: #D32F2F; -fx-text-fill: white; -fx-padding: 1 3; -fx-font-size: 5;"
                        :on-action {:event/type :effects/remove-from-chain-and-clear-selection
                                    :col col :row row
                                    :effect-idx effect-idx}}]}}))

(defn- chain-list-sidebar
  "Left sidebar showing the effect chain with drag-and-drop reordering.
   Supports multi-select via selected-effect-indices set."
  [{:keys [col row effect-chain selected-effect-indices dragging-effect-idx drop-target-idx can-paste?]}]
  (let [chain-count (count effect-chain)
        selected-indices (or selected-effect-indices #{})
        selection-count (count selected-indices)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 180
     :min-width 180
     :style "-fx-background-color: #252525; -fx-padding: 8;"
     :children [{:fx/type :h-box
                 :alignment :center-left
                 :children (filterv some?
                             [{:fx/type :label
                               :text "CHAIN"
                               :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                              {:fx/type :region :h-box/hgrow :always}
                              (when (pos? selection-count)
                                {:fx/type :label
                                 :text (str selection-count " selected")
                                 :style "-fx-text-fill: #4A6FA5; -fx-font-size: 9;"})])}
                {:fx/type :label
                 :text "Ctrl+Click multi-select • Ctrl+C/V copy/paste"
                 :style "-fx-text-fill: #505050; -fx-font-size: 8; -fx-font-style: italic;"}
                ;; Action buttons for copy/paste
                {:fx/type :h-box
                 :spacing 4
                 :children [{:fx/type :button
                             :text "Copy"
                             :disable (zero? selection-count)
                             :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
                             :on-action {:event/type :effects/copy-selected
                                         :col col :row row}}
                            {:fx/type :button
                             :text "Paste"
                             :disable (not can-paste?)
                             :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
                             :on-action {:event/type :effects/paste-into-chain
                                         :col col :row row}}
                            {:fx/type :button
                             :text "Del"
                             :disable (zero? selection-count)
                             :style "-fx-background-color: #803030; -fx-text-fill: white; -fx-font-size: 9; -fx-padding: 2 6;"
                             :on-action {:event/type :effects/delete-selected
                                         :col col :row row}}]}
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
                                              :fx/key idx
                                              :col col :row row
                                              :effect-idx idx
                                              :effect effect
                                              :effect-def (effect-by-id (:effect-id effect))
                                              :selected? (contains? selected-indices idx)
                                              :dragging? (= idx dragging-effect-idx)
                                              :drop-target? (= idx drop-target-idx)
                                              :chain-count chain-count})
                                           effect-chain))}})]}))


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
    {:fx/type effect-bank-tab-content :col col :row row :category :color}))

(defn- effect-bank-tabs
  "Tabbed effect bank using shared styled-tab-bar - Color, Shape, Intensity."
  [{:keys [col row active-bank-tab]}]
  (let [active-tab (or active-bank-tab :color)]
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
   The text field allows typing a value and pressing Enter to update."
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
                {:fx/type :text-field
                 :text (format "%.2f" (double value))
                 :pref-width 55
                 :style "-fx-background-color: #404040; -fx-text-fill: white; -fx-font-size: 11; -fx-padding: 2 4;"
                 :on-action {:event/type :effects/update-param-from-text
                             :col col :row row
                             :effect-idx effect-idx
                             :param-key param-key
                             :min min :max max}}]}))

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

(defn- custom-param-renderer
  "Renders parameters with custom UI and mode toggle.
   
   Props:
   - :col, :row - Grid cell coordinates
   - :effect-idx - Index in effect chain
   - :effect-def - Effect definition with :ui-hints
   - :current-params - Current parameter values
   - :ui-mode - Current UI mode (:visual or :numeric)
   - :params-map - Parameter specifications map"
  [{:keys [col row effect-idx effect-def current-params ui-mode params-map]}]
  (let [ui-hints (:ui-hints effect-def)
        actual-mode (or ui-mode (:default-mode ui-hints :visual))]
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
                                       :mode :visual}}
                           {:fx/type :button
                            :text "🔢 Numeric"
                            :style (str "-fx-font-size: 9; -fx-padding: 3 10; "
                                       (if (= actual-mode :numeric)
                                         "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                                         "-fx-background-color: #404040; -fx-text-fill: #B0B0B0;"))
                            :on-action {:event/type :effects/set-param-ui-mode
                                       :effect-idx effect-idx
                                       :mode :numeric}}]}
                
                ;; Render based on mode
                (if (= actual-mode :visual)
                  ;; Custom visual renderer
                  (case (:renderer ui-hints)
                    :spatial-2d {:fx/type custom-renderers/translate-visual-editor
                                :col col :row row
                                :effect-idx effect-idx
                                :current-params current-params
                                :param-specs (:parameters effect-def)}
                    
                    :corner-pin-2d {:fx/type custom-renderers/corner-pin-visual-editor
                                   :col col :row row
                                   :effect-idx effect-idx
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
                                 :param-key param-key
                                 :param-spec param-spec
                                 :current-value (get current-params param-key)}))})]}))

(defn- parameter-editor
  "Parameter editor for the selected effect."
  [{:keys [col row selected-effect-idx effect-chain dialog-data]}]
  (let [selected-effect (when selected-effect-idx
                          (nth effect-chain selected-effect-idx nil))
        effect-def (when selected-effect
                     (effect-by-id (:effect-id selected-effect)))
        current-params (:params selected-effect {})
        params-map (params-vector->map (:parameters effect-def []))
        ui-mode (get-in dialog-data [:ui-modes selected-effect-idx])]
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
                              :effect-def effect-def
                              :current-params current-params
                              :ui-mode ui-mode
                              :params-map params-map}}
                    
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
                                            :param-key param-key
                                            :param-spec param-spec
                                            :current-value (get current-params param-key)}))}})
                  
                  {:fx/type :label
                   :text "Select an effect from the chain"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-font-size: 11;"})]}))


;; Keyboard Handler Setup


(defn- setup-keyboard-handler!
  "Setup keyboard handler on root node for shortcuts.
   Uses addEventFilter for Ctrl+C/V to intercept before system clipboard handling.
   Uses setOnKeyPressed for other shortcuts (Ctrl+A, Delete, Escape).
   
   - Ctrl+C: Copy selected effects
   - Ctrl+V: Paste effects
   - Ctrl+A: Select all effects
   - Delete/Backspace: Delete selected effects"
  [^javafx.scene.Node node col row]
  (println "[DEBUG] setup-keyboard-handler! called for node:" (.getClass node) "col:" col "row:" row)
  
  ;; Use event filter for Ctrl+C and Ctrl+V - these are intercepted by the system
  ;; at Scene level, so we need to catch them during capture phase
  (.addEventFilter
    node
    KeyEvent/KEY_PRESSED
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [code (.getCode event)
              ctrl? (.isControlDown event)
              shift? (.isShiftDown event)
              alt? (.isAltDown event)]
          ;; Log ALL key presses in the filter
          (println "[DEBUG FILTER] Key pressed:" code "ctrl?" ctrl? "shift?" shift? "alt?" alt?)
          (cond
            ;; Ctrl+C - Copy (must use filter to intercept system clipboard)
            (and ctrl? (= code KeyCode/C))
            (do (println "[DEBUG] Ctrl+C detected! Dispatching :effects/copy-selected")
                (events/dispatch! {:event/type :effects/copy-selected
                                   :col col :row row})
                (println "[DEBUG] Ctrl+C dispatch complete, consuming event")
                (.consume event))
            
            ;; Ctrl+V - Paste (must use filter to intercept system clipboard)
            (and ctrl? (= code KeyCode/V))
            (do (println "[DEBUG] Ctrl+V detected! Dispatching :effects/paste-into-chain")
                (events/dispatch! {:event/type :effects/paste-into-chain
                                   :col col :row row})
                (println "[DEBUG] Ctrl+V dispatch complete, consuming event")
                (.consume event)))))))
  
  ;; Use regular key handler for other shortcuts that don't conflict
  (.setOnKeyPressed
    node
    (reify javafx.event.EventHandler
      (handle [_ event]
        (let [code (.getCode event)
              ctrl? (.isControlDown event)]
          ;; Log key presses in regular handler
          (println "[DEBUG HANDLER] Key pressed:" code "ctrl?" ctrl?)
          (cond
            ;; Ctrl+A - Select all
            (and ctrl? (= code KeyCode/A))
            (do (println "[DEBUG] Ctrl+A detected! Dispatching :effects/select-all")
                (events/dispatch! {:event/type :effects/select-all
                                   :col col :row row})
                (.consume event))
            
            ;; Delete or Backspace - Delete selected
            (or (= code KeyCode/DELETE) (= code KeyCode/BACK_SPACE))
            (do (println "[DEBUG] Delete detected! Dispatching :effects/delete-selected")
                (events/dispatch! {:event/type :effects/delete-selected
                                   :col col :row row})
                (.consume event))
            
            ;; Escape - Clear selection
            (= code KeyCode/ESCAPE)
            (do (println "[DEBUG] Escape detected! Dispatching :effects/clear-selection")
                (events/dispatch! {:event/type :effects/clear-selection})
                (.consume event))))))))


;; Main Dialog Content


(defn- effect-chain-editor-content
  "Main content of the effect chain editor dialog."
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :effect-chain-editor)
        {:keys [col row selected-effect-indices dragging-effect-idx drop-target-idx active-bank-tab]} dialog-data
        ;; Convert old format to new if needed (backwards compat)
        selected-indices (or selected-effect-indices #{})
        effects-state (fx/sub-val context :effects)
        cell-data (get-in effects-state [:cells [col row]])
        effect-chain (:effects cell-data [])
        active? (:active cell-data false)
        can-paste? (clipboard/can-paste-effects?)
        ;; For parameter editor, use first selected if single select or nothing
        first-selected-idx (when (= 1 (count selected-indices))
                             (first selected-indices))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (setup-keyboard-handler! node col row)
                   ;; Make node focusable to receive key events
                   (.setFocusTraversable node true)
                   (.requestFocus node))
     :desc {:fx/type :v-box
             :spacing 0
             :style "-fx-background-color: #2D2D2D;"
             :pref-width 600
             :pref-height 550
             :children [;; Main content area
                       {:fx/type :h-box
                        :spacing 0
                        :v-box/vgrow :always
                        :children [;; Left sidebar - chain list with drag-and-drop
                                   {:fx/type chain-list-sidebar
                                    :col col :row row
                                    :effect-chain effect-chain
                                    :selected-effect-indices selected-indices
                                    :dragging-effect-idx dragging-effect-idx
                                    :drop-target-idx drop-target-idx
                                    :can-paste? can-paste?}
                                   
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
                                                :selected-effect-idx first-selected-idx
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
                                                :dialog-id :effect-chain-editor}}]}]}}))


;; Scene-level Event Filter Setup


(defn- setup-scene-key-filter!
  "Setup Scene-level event filter for Ctrl+C and Ctrl+V.
   Scene-level filters catch events before any node handlers,
   allowing us to intercept system clipboard shortcuts."
  [^javafx.scene.Scene scene col row]
  (println "[DEBUG] setup-scene-key-filter! called for scene, col:" col "row:" row)
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
            (do (println "[DEBUG SCENE FILTER] Ctrl+C triggered! col:" col "row:" row)
                (events/dispatch! {:event/type :effects/copy-selected
                                   :col col :row row})
                (.consume event))
            
            ;; Ctrl+V - Paste
            (and ctrl? (= code KeyCode/V))
            (do (println "[DEBUG SCENE FILTER] Ctrl+V triggered! col:" col "row:" row)
                (events/dispatch! {:event/type :effects/paste-into-chain
                                   :col col :row row})
                (.consume event)))))))
  (println "[DEBUG] Scene key filter registered"))


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
     :modality :application-modal
     :on-close-request {:event/type :ui/close-dialog :dialog-id :effect-chain-editor}
     :scene {:fx/type effect-chain-editor-scene
             :col col
             :row row
             :stylesheets stylesheets}}))
