(ns laser-show.ui-fx.dialogs.effect-chain-editor
  "Effect chain editor dialog for cljfx.
   Allows editing effect chains for effect grid cells."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles]
            [laser-show.ui-fx.events :as events]
            [laser-show.ui-fx.components.slider :as slider]
            [laser-show.animation.effects :as fx-effects]
            [laser-show.state.atoms :as state]
            [laser-show.state.clipboard :as clipboard])
  (:import [javafx.stage Stage Modality]
           [javafx.scene Scene]
           [javafx.scene.input ClipboardContent TransferMode DragEvent MouseEvent MouseButton]
           [javafx.application Platform]))

;; ============================================================================
;; Effect Categories
;; ============================================================================

(def effect-categories
  [{:id :shape :name "Shape"}
   {:id :color :name "Color"}
   {:id :intensity :name "Intensity"}])

;; ============================================================================
;; Dialog State (UI-local, not app state)
;; ============================================================================

(defonce ^:private !dialog-state
  (atom {:col nil
         :row nil
         :selected-indices #{}
         :active-category :shape
         :stage nil}))

;; ============================================================================
;; Effect List Item
;; ============================================================================

(defn effect-list-item
  "A single effect item in the chain list.
   
   Props:
   - :idx - Index in chain
   - :effect - Effect map {:effect-id :params}
   - :selected? - Whether selected
   - :on-click - Click handler
   - :on-delete - Delete handler"
  [{:keys [idx effect selected? on-click on-delete]}]
  (let [effect-def (fx-effects/get-effect (:effect-id effect))
        effect-name (or (:name effect-def) (name (:effect-id effect)))
        category (or (:category effect-def) :shape)
        bg-color (if selected?
                   (styles/category-color category)
                   (:surface-light styles/colors))]
    {:fx/type :h-box
     :style (str "-fx-background-color: " bg-color ";"
                "-fx-padding: 8;"
                "-fx-cursor: hand;"
                "-fx-border-color: " (:border styles/colors) ";"
                "-fx-border-width: 0 0 1 0;")
     :alignment :center-left
     :spacing 8
     :on-mouse-clicked (fn [^MouseEvent e]
                         (when (= (.getButton e) MouseButton/PRIMARY)
                           (on-click idx (.isControlDown e) (.isShiftDown e))))
     :children [{:fx/type :label
                 :text "≡"
                 :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                            "-fx-font-size: 14px;")}
                {:fx/type :label
                 :text effect-name
                 :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                            "-fx-font-weight: " (if selected? "bold" "normal") ";")
                 :h-box/hgrow :always}
                {:fx/type :button
                 :text "×"
                 :style (str "-fx-background-color: " (:error styles/colors) ";"
                            "-fx-text-fill: white;"
                            "-fx-font-weight: bold;"
                            "-fx-padding: 2 8;")
                 :on-action (fn [_] (on-delete idx))}]}))

;; ============================================================================
;; Effect Chain List
;; ============================================================================

(defn effect-chain-list
  "The list of effects in the chain.
   
   Props:
   - :col, :row - Cell coordinates
   - :selected-indices - Set of selected indices
   - :on-select - Selection handler
   - :on-delete - Delete handler"
  [{:keys [col row selected-indices on-select on-delete]}]
  (let [cell (state/get-effect-cell col row)
        effects (:effects cell)]
    {:fx/type :v-box
     :style (str "-fx-background-color: " (:background styles/colors) ";")
     :children (if (seq effects)
                 (mapv (fn [idx]
                         {:fx/type effect-list-item
                          :idx idx
                          :effect (nth effects idx)
                          :selected? (contains? selected-indices idx)
                          :on-click on-select
                          :on-delete on-delete})
                       (range (count effects)))
                 [{:fx/type :label
                   :text "No effects yet"
                   :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                              "-fx-padding: 20;"
                              "-fx-alignment: center;")
                   :alignment :center}])}))

;; ============================================================================
;; Category Tab Bar
;; ============================================================================

(defn category-tab-bar
  "Tab bar for effect categories.
   
   Props:
   - :active-category - Currently selected category
   - :on-select - Selection handler"
  [{:keys [active-category on-select]}]
  {:fx/type :h-box
   :spacing 0
   :children (mapv (fn [{:keys [id name]}]
                     (let [active? (= id active-category)
                           color (styles/category-color id)]
                       {:fx/type :button
                        :text name
                        :style (str "-fx-background-color: " 
                                   (if active? color (:surface-light styles/colors))
                                   ";"
                                   "-fx-text-fill: " (:text-primary styles/colors) ";"
                                   "-fx-font-weight: " (if active? "bold" "normal") ";"
                                   "-fx-padding: 8 16;"
                                   "-fx-background-radius: 0;")
                        :on-action (fn [_] (on-select id))}))
                   effect-categories)})

;; ============================================================================
;; Effect Picker List
;; ============================================================================

(defn effect-picker-list
  "List of available effects in a category.
   
   Props:
   - :category - Effect category
   - :on-add - Add effect handler"
  [{:keys [category on-add]}]
  (let [effects (fx-effects/list-effects-by-category category)]
    {:fx/type :v-box
     :spacing 2
     :children (mapv (fn [effect-def]
                       {:fx/type :h-box
                        :style (str "-fx-background-color: " (:surface styles/colors) ";"
                                   "-fx-padding: 6 8;"
                                   "-fx-cursor: hand;")
                        :alignment :center-left
                        :on-mouse-clicked (fn [^MouseEvent e]
                                            (when (= (.getClickCount e) 2)
                                              (on-add (:id effect-def))))
                        :children [{:fx/type :label
                                    :text (:name effect-def)
                                    :style (str "-fx-text-fill: " (:text-primary styles/colors) ";")
                                    :h-box/hgrow :always}]})
                     effects)}))

;; ============================================================================
;; Effect Picker Panel
;; ============================================================================

(defn effect-picker-panel
  "Panel for picking effects to add.
   
   Props:
   - :active-category - Currently selected category
   - :on-category-select - Category selection handler
   - :on-add-effect - Add effect handler"
  [{:keys [active-category on-category-select on-add-effect]}]
  {:fx/type :v-box
   :spacing 0
   :children [{:fx/type category-tab-bar
               :active-category active-category
               :on-select on-category-select}
              {:fx/type :scroll-pane
               :fit-to-width true
               :style (str "-fx-background-color: " (:surface styles/colors) ";")
               :pref-height 150
               :content {:fx/type effect-picker-list
                         :category active-category
                         :on-add on-add-effect}}
              {:fx/type :h-box
               :style (str "-fx-background-color: " (:surface styles/colors) ";"
                          "-fx-padding: 8;")
               :alignment :center-right
               :children [{:fx/type :button
                           :text "Add to Chain"
                           :style-class ["button" "primary"]
                           :on-action (fn [_]
                                        ;; Add first effect of current category
                                        (when-let [effects (seq (fx-effects/list-effects-by-category active-category))]
                                          (on-add-effect (:id (first effects)))))}]}]})

;; ============================================================================
;; Parameter Editor Panel
;; ============================================================================

(defn param-editor-panel
  "Panel for editing effect parameters.
   
   Props:
   - :col, :row - Cell coordinates
   - :effect-idx - Index of effect being edited
   - :effect - Effect data"
  [{:keys [col row effect-idx effect]}]
  (let [effect-def (when effect (fx-effects/get-effect (:effect-id effect)))
        param-defs (:parameters effect-def)
        current-params (:params effect)]
    {:fx/type :v-box
     :style (str "-fx-background-color: " (:surface-light styles/colors) ";"
                "-fx-padding: 8;")
     :spacing 8
     :children (if (and effect param-defs)
                 (concat
                  [{:fx/type :label
                    :text (str "Parameters: " (:name effect-def))
                    :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                               "-fx-font-weight: bold;")}]
                  (mapv (fn [param-def]
                          {:fx/type slider/param-control
                           :param-def param-def
                           :value (get current-params (:key param-def))
                           :on-change (fn [new-val]
                                        (state/update-effect-param! col row effect-idx (:key param-def) new-val))})
                        param-defs))
                 [{:fx/type :label
                   :text "Select an effect to edit parameters"
                   :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                              "-fx-padding: 20;")
                   :alignment :center}])}))

;; ============================================================================
;; Main Dialog Content
;; ============================================================================

(defn dialog-content
  "Main content for the effect chain editor dialog.
   
   Props:
   - :col, :row - Cell coordinates
   - :selected-indices - Set of selected effect indices
   - :active-category - Active category in picker
   - :on-close - Close dialog handler"
  [{:keys [col row selected-indices active-category on-close]}]
  (let [cell (state/get-effect-cell col row)
        effects (:effects cell)
        selected-idx (when (= 1 (count selected-indices))
                       (first selected-indices))
        selected-effect (when selected-idx
                          (get effects selected-idx))]
    {:fx/type :border-pane
     :style (str "-fx-background-color: " (:background styles/colors) ";")
     
     ;; Header
     :top {:fx/type :h-box
           :style (str "-fx-background-color: " (:surface styles/colors) ";"
                      "-fx-padding: 12 16;")
           :alignment :center-left
           :children [{:fx/type :label
                       :text (str "Effect Chain Editor - Cell [" col ", " row "]")
                       :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                  "-fx-font-size: 14px;"
                                  "-fx-font-weight: bold;")}]}
     
     ;; Main content - split pane
     :center {:fx/type :split-pane
              :style (str "-fx-background-color: " (:background styles/colors) ";")
              :divider-positions [0.4]
              :items [;; Left - Effect chain list
                      {:fx/type :v-box
                       :children [{:fx/type :label
                                   :text "Effect Chain"
                                   :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                              "-fx-font-weight: bold;"
                                              "-fx-padding: 8;")
                                   :alignment :center-left}
                                  {:fx/type :scroll-pane
                                   :fit-to-width true
                                   :style (str "-fx-background-color: " (:background styles/colors) ";")
                                   :v-box/vgrow :always
                                   :content {:fx/type effect-chain-list
                                             :col col
                                             :row row
                                             :selected-indices selected-indices
                                             :on-select (fn [idx ctrl? shift?]
                                                          (swap! !dialog-state
                                                                 update :selected-indices
                                                                 (fn [sel]
                                                                   (cond
                                                                     ctrl? (if (contains? sel idx)
                                                                             (disj sel idx)
                                                                             (conj sel idx))
                                                                     :else #{idx}))))
                                             :on-delete (fn [idx]
                                                          (state/remove-effect-from-cell! col row idx)
                                                          (swap! !dialog-state update :selected-indices disj idx))}}
                                  ;; Copy/Paste buttons
                                  {:fx/type :h-box
                                   :style (str "-fx-background-color: " (:surface styles/colors) ";"
                                              "-fx-padding: 8;")
                                   :spacing 4
                                   :children [{:fx/type :button
                                               :text "Copy"
                                               :style "-fx-font-size: 10px;"
                                               :on-action (fn [_]
                                                            (when (seq selected-indices)
                                                              (let [effects-to-copy (mapv #(get effects %) (sort selected-indices))]
                                                                (clipboard/copy-effect-chain! {:effects effects-to-copy :active true}))))}
                                              {:fx/type :button
                                               :text "Paste"
                                               :style "-fx-font-size: 10px;"
                                               :on-action (fn [_]
                                                            (when-let [to-paste (clipboard/get-effects-to-paste)]
                                                              (doseq [effect to-paste]
                                                                (state/add-effect-to-cell! col row effect))))}]}]}
                      
                      ;; Right - Picker + Params
                      {:fx/type :v-box
                       :children [{:fx/type effect-picker-panel
                                   :active-category active-category
                                   :on-category-select (fn [cat]
                                                         (swap! !dialog-state assoc :active-category cat))
                                   :on-add-effect (fn [effect-id]
                                                    (let [default-params (fx-effects/get-default-params effect-id)]
                                                      (state/add-effect-to-cell! col row
                                                                                 {:effect-id effect-id
                                                                                  :params default-params})))}
                                  {:fx/type :label
                                   :text "─── Parameters ───"
                                   :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                                              "-fx-padding: 8;"
                                              "-fx-font-weight: bold;")
                                   :alignment :center}
                                  {:fx/type :scroll-pane
                                   :fit-to-width true
                                   :v-box/vgrow :always
                                   :content {:fx/type param-editor-panel
                                             :col col
                                             :row row
                                             :effect-idx selected-idx
                                             :effect selected-effect}}]}]}
     
     ;; Footer
     :bottom {:fx/type :h-box
              :style (str "-fx-background-color: " (:surface styles/colors) ";"
                         "-fx-padding: 8 16;")
              :alignment :center-right
              :children [{:fx/type :button
                          :text "Done"
                          :style-class ["button" "primary"]
                          :on-action (fn [_] (on-close))}]}}))

;; ============================================================================
;; Public API
;; ============================================================================

(defn show-effect-chain-editor!
  "Show the effect chain editor dialog.
   
   Parameters:
   - col, row - Cell coordinates in effects grid"
  [col row]
  ;; Ensure cell exists
  (state/ensure-effect-cell! col row)
  
  ;; Initialize dialog state
  (reset! !dialog-state {:col col
                         :row row
                         :selected-indices #{}
                         :active-category :shape
                         :stage nil})
  
  (Platform/runLater
   (fn []
     (let [stage (Stage.)
           
           close-fn (fn []
                      (.close stage)
                      (swap! !dialog-state assoc :stage nil))
           
           render-fn (fn []
                       (let [state @!dialog-state]
                         {:fx/type dialog-content
                          :col (:col state)
                          :row (:row state)
                          :selected-indices (:selected-indices state)
                          :active-category (:active-category state)
                          :on-close close-fn}))
           
           component (fx/create-component (render-fn))
           scene (Scene. (fx/instance component) 650 500)]
       
       (.setTitle stage (str "Effect Chain Editor - Cell [" col ", " row "]"))
       (.setScene stage scene)
       (.initModality stage Modality/APPLICATION_MODAL)
       
       ;; Update scene when dialog state changes
       (add-watch !dialog-state :dialog-render
                  (fn [_ _ _ new-state]
                    (when (:stage new-state)
                      (Platform/runLater
                       (fn []
                         (let [new-comp (fx/create-component (render-fn))]
                           (.setRoot scene (fx/instance new-comp))))))))
       
       ;; Also watch effects atom for changes
       (add-watch state/!effects :dialog-effects-render
                  (fn [_ _ _ _]
                    (when (:stage @!dialog-state)
                      (Platform/runLater
                       (fn []
                         (let [new-comp (fx/create-component (render-fn))]
                           (.setRoot scene (fx/instance new-comp))))))))
       
       (swap! !dialog-state assoc :stage stage)
       (.showAndWait stage)
       
       ;; Cleanup watches
       (remove-watch !dialog-state :dialog-render)
       (remove-watch state/!effects :dialog-effects-render)))))
