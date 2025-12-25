(ns laser-show.ui-fx.dialogs.projector-config
  "Projector configuration dialog for cljfx.
   Allows configuring projector connections, zones, and transforms."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles]
            [laser-show.ui-fx.components.slider :as slider]
            [laser-show.state.atoms :as state]
            [laser-show.backend.projectors :as projectors])
  (:import [javafx.stage Stage Modality]
           [javafx.scene Scene]
           [javafx.application Platform]))

;; ============================================================================
;; Dialog State
;; ============================================================================

(defonce ^:private !config-state
  (atom {:selected-projector nil
         :projectors []
         :stage nil}))

;; ============================================================================
;; Projector List Item
;; ============================================================================

(defn projector-list-item
  "A single projector item in the list.
   
   Props:
   - :projector - Projector map
   - :selected? - Whether selected
   - :on-click - Click handler"
  [{:keys [projector selected? on-click]}]
  (let [{:keys [id name target connected?]} projector]
    {:fx/type :h-box
     :style (str "-fx-background-color: " 
                (if selected? (:accent styles/colors) (:surface styles/colors))
                ";"
                "-fx-padding: 8;"
                "-fx-cursor: hand;"
                "-fx-border-color: " (:border styles/colors) ";"
                "-fx-border-width: 0 0 1 0;")
     :alignment :center-left
     :spacing 8
     :on-mouse-clicked (fn [_] (on-click id))
     :children [{:fx/type :region
                 :style (str "-fx-background-color: "
                            (if connected? (:success styles/colors) (:text-secondary styles/colors))
                            ";"
                            "-fx-background-radius: 4;")
                 :pref-width 8
                 :pref-height 8}
                {:fx/type :v-box
                 :h-box/hgrow :always
                 :children [{:fx/type :label
                             :text (or name (str "Projector " id))
                             :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                        "-fx-font-weight: bold;")}
                            {:fx/type :label
                             :text (or target "Not configured")
                             :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                                        "-fx-font-size: 10px;")}]}]}))

;; ============================================================================
;; Projector List
;; ============================================================================

(defn projector-list
  "List of configured projectors.
   
   Props:
   - :projectors - Vector of projector maps
   - :selected-id - Currently selected projector ID
   - :on-select - Selection handler"
  [{:keys [projectors selected-id on-select]}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (:background styles/colors) ";")
   :children (if (seq projectors)
               (mapv (fn [proj]
                       {:fx/type projector-list-item
                        :projector proj
                        :selected? (= (:id proj) selected-id)
                        :on-click on-select})
                     projectors)
               [{:fx/type :label
                 :text "No projectors configured"
                 :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                            "-fx-padding: 20;"
                            "-fx-alignment: center;")
                 :alignment :center}])})

;; ============================================================================
;; Connection Settings
;; ============================================================================

(defn connection-settings
  "Connection settings panel.
   
   Props:
   - :projector - Projector map
   - :on-change - Change handler"
  [{:keys [projector on-change]}]
  (let [{:keys [target port]} projector]
    {:fx/type :v-box
     :spacing 8
     :children [{:fx/type :label
                 :text "Connection"
                 :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                            "-fx-font-weight: bold;")}
                {:fx/type :h-box
                 :spacing 8
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text "Target:"
                             :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                        "-fx-min-width: 80;")}
                            {:fx/type :text-field
                             :text (or target "localhost")
                             :pref-width 200
                             :on-text-changed (fn [new-val]
                                                (on-change :target new-val))}]}
                {:fx/type :h-box
                 :spacing 8
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text "Port:"
                             :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                        "-fx-min-width: 80;")}
                            {:fx/type :text-field
                             :text (str (or port 7765))
                             :pref-width 100
                             :on-text-changed (fn [new-val]
                                                (try
                                                  (on-change :port (Integer/parseInt new-val))
                                                  (catch Exception _)))}]}]}))

;; ============================================================================
;; Transform Settings
;; ============================================================================

(defn transform-settings
  "Transform settings (scale, offset, rotation).
   
   Props:
   - :projector - Projector map
   - :on-change - Change handler"
  [{:keys [projector on-change]}]
  (let [transform (or (:transform projector) {})]
    {:fx/type :v-box
     :spacing 8
     :children [{:fx/type :label
                 :text "Transform"
                 :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                            "-fx-font-weight: bold;")}
                {:fx/type slider/value-slider
                 :label "Scale X"
                 :value (get transform :scale-x 1.0)
                 :min 0.1
                 :max 2.0
                 :on-change (fn [v] (on-change :transform (assoc transform :scale-x v)))}
                {:fx/type slider/value-slider
                 :label "Scale Y"
                 :value (get transform :scale-y 1.0)
                 :min 0.1
                 :max 2.0
                 :on-change (fn [v] (on-change :transform (assoc transform :scale-y v)))}
                {:fx/type slider/value-slider
                 :label "Offset X"
                 :value (get transform :offset-x 0.0)
                 :min -1.0
                 :max 1.0
                 :on-change (fn [v] (on-change :transform (assoc transform :offset-x v)))}
                {:fx/type slider/value-slider
                 :label "Offset Y"
                 :value (get transform :offset-y 0.0)
                 :min -1.0
                 :max 1.0
                 :on-change (fn [v] (on-change :transform (assoc transform :offset-y v)))}
                {:fx/type slider/value-slider
                 :label "Rotation"
                 :value (get transform :rotation 0.0)
                 :min -180.0
                 :max 180.0
                 :on-change (fn [v] (on-change :transform (assoc transform :rotation v)))}]}))

;; ============================================================================
;; Projector Settings Panel
;; ============================================================================

(defn projector-settings
  "Settings panel for a projector.
   
   Props:
   - :projector - Projector map
   - :on-change - Change handler (fn [key value])"
  [{:keys [projector on-change]}]
  (if projector
    {:fx/type :scroll-pane
     :fit-to-width true
     :style (str "-fx-background-color: " (:surface-light styles/colors) ";")
     :content {:fx/type :v-box
               :style (str "-fx-background-color: " (:surface-light styles/colors) ";"
                          "-fx-padding: 12;")
               :spacing 16
               :children [{:fx/type :h-box
                           :spacing 8
                           :alignment :center-left
                           :children [{:fx/type :label
                                       :text "Name:"
                                       :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                                  "-fx-min-width: 80;")}
                                      {:fx/type :text-field
                                       :text (or (:name projector) "")
                                       :pref-width 200
                                       :on-text-changed (fn [new-val]
                                                          (on-change :name new-val))}]}
                          {:fx/type connection-settings
                           :projector projector
                           :on-change on-change}
                          {:fx/type transform-settings
                           :projector projector
                           :on-change on-change}]}}
    {:fx/type :label
     :text "Select a projector to configure"
     :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                "-fx-padding: 20;")
     :alignment :center}))

;; ============================================================================
;; Main Dialog Content
;; ============================================================================

(defn dialog-content
  "Main content for the projector config dialog.
   
   Props:
   - :projectors - Vector of projector maps
   - :selected-id - Currently selected projector ID
   - :on-select - Projector selection handler
   - :on-add - Add projector handler
   - :on-remove - Remove projector handler
   - :on-change - Change handler
   - :on-close - Close handler"
  [{:keys [projectors selected-id on-select on-add on-remove on-change on-close]}]
  (let [selected-proj (first (filter #(= (:id %) selected-id) projectors))]
    {:fx/type :border-pane
     :style (str "-fx-background-color: " (:background styles/colors) ";")
     
     ;; Header
     :top {:fx/type :h-box
           :style (str "-fx-background-color: " (:surface styles/colors) ";"
                      "-fx-padding: 12 16;")
           :alignment :center-left
           :children [{:fx/type :label
                       :text "Projector Configuration"
                       :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                  "-fx-font-size: 14px;"
                                  "-fx-font-weight: bold;")}]}
     
     ;; Main content
     :center {:fx/type :split-pane
              :style (str "-fx-background-color: " (:background styles/colors) ";")
              :divider-positions [0.35]
              :items [;; Left - Projector list
                      {:fx/type :v-box
                       :children [{:fx/type :h-box
                                   :style (str "-fx-background-color: " (:surface styles/colors) ";"
                                              "-fx-padding: 8;")
                                   :spacing 4
                                   :children [{:fx/type :button
                                               :text "+"
                                               :style "-fx-font-weight: bold;"
                                               :on-action (fn [_] (on-add))}
                                              {:fx/type :button
                                               :text "-"
                                               :style "-fx-font-weight: bold;"
                                               :disable (nil? selected-id)
                                               :on-action (fn [_] (on-remove selected-id))}]}
                                  {:fx/type :scroll-pane
                                   :fit-to-width true
                                   :v-box/vgrow :always
                                   :content {:fx/type projector-list
                                             :projectors projectors
                                             :selected-id selected-id
                                             :on-select on-select}}]}
                      
                      ;; Right - Settings
                      {:fx/type projector-settings
                       :projector selected-proj
                       :on-change (fn [k v]
                                    (on-change selected-id k v))}]}
     
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

(defn show-projector-config!
  "Show the projector configuration dialog."
  []
  ;; Load current projectors from state
  (let [projectors (or @state/!projectors [])]
    (reset! !config-state {:selected-projector (when (seq projectors) (:id (first projectors)))
                           :projectors projectors
                           :stage nil}))
  
  (Platform/runLater
   (fn []
     (let [stage (Stage.)
           
           close-fn (fn []
                      ;; Save projectors back to state
                      (reset! state/!projectors (:projectors @!config-state))
                      (.close stage)
                      (swap! !config-state assoc :stage nil))
           
           render-fn (fn []
                       (let [state @!config-state]
                         {:fx/type dialog-content
                          :projectors (:projectors state)
                          :selected-id (:selected-projector state)
                          :on-select (fn [id]
                                       (swap! !config-state assoc :selected-projector id))
                          :on-add (fn []
                                    (let [new-id (keyword (str "proj-" (System/currentTimeMillis)))
                                          new-proj {:id new-id
                                                    :name "New Projector"
                                                    :target "localhost"
                                                    :port 7765
                                                    :connected? false
                                                    :transform {:scale-x 1.0 :scale-y 1.0
                                                               :offset-x 0.0 :offset-y 0.0
                                                               :rotation 0.0}}]
                                      (swap! !config-state
                                             (fn [s]
                                               (-> s
                                                   (update :projectors conj new-proj)
                                                   (assoc :selected-projector new-id))))))
                          :on-remove (fn [id]
                                       (swap! !config-state
                                              (fn [s]
                                                (let [new-projs (vec (remove #(= (:id %) id) (:projectors s)))]
                                                  (-> s
                                                      (assoc :projectors new-projs)
                                                      (assoc :selected-projector (when (seq new-projs)
                                                                                   (:id (first new-projs)))))))))
                          :on-change (fn [proj-id key value]
                                       (swap! !config-state
                                              update :projectors
                                              (fn [projs]
                                                (mapv (fn [p]
                                                        (if (= (:id p) proj-id)
                                                          (assoc p key value)
                                                          p))
                                                      projs))))
                          :on-close close-fn}))
           
           component (fx/create-component (render-fn))
           scene (Scene. (fx/instance component) 700 500)]
       
       (.setTitle stage "Projector Configuration")
       (.setScene stage scene)
       (.initModality stage Modality/APPLICATION_MODAL)
       
       ;; Update on state changes
       (add-watch !config-state :proj-config-render
                  (fn [_ _ _ new-state]
                    (when (:stage new-state)
                      (Platform/runLater
                       (fn []
                         (let [new-comp (fx/create-component (render-fn))]
                           (.setRoot scene (fx/instance new-comp))))))))
       
       (swap! !config-state assoc :stage stage)
       (.showAndWait stage)
       
       ;; Cleanup
       (remove-watch !config-state :proj-config-render)))))
