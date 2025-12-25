(ns laser-show.ui-fx.dialogs.zone-config
  "Zone configuration dialog for cljfx.
   Allows managing zones with transformations and blocked regions."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.ui-fx.styles :as styles]
            [laser-show.ui-fx.components.slider :as slider]
            [laser-show.backend.zones :as zones]
            [laser-show.backend.projectors :as projectors])
  (:import [javafx.stage Stage Modality]
           [javafx.scene Scene]
           [javafx.application Platform]))

;; ============================================================================
;; Zone Tags
;; ============================================================================

(def zone-tags
  [:safe :crowd-scanning :graphics :effects :restricted])

;; ============================================================================
;; Dialog State
;; ============================================================================

(defonce ^:private !config-state
  (atom {:zones []
         :selected-zone-id nil
         :edit-zone nil
         :stage nil}))

;; ============================================================================
;; Zone List Item
;; ============================================================================

(defn zone-list-item
  "A single zone item in the list."
  [{:keys [zone selected? on-click]}]
  (let [{:keys [id name enabled projector-id tags]} zone
        proj (projectors/get-projector projector-id)
        proj-name (or (:name proj) (when projector-id (clojure.core/name projector-id)) "None")]
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
                            (if enabled (:success styles/colors) (:text-secondary styles/colors))
                            ";"
                            "-fx-background-radius: 4;")
                 :pref-width 8
                 :pref-height 8}
                {:fx/type :v-box
                 :h-box/hgrow :always
                 :children [{:fx/type :label
                             :text (or name (clojure.core/name id))
                             :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                        "-fx-font-weight: bold;")}
                            {:fx/type :label
                             :text (str "→ " proj-name " [" (str/join ", " (map clojure.core/name tags)) "]")
                             :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                                        "-fx-font-size: 10px;")}]}]}))

;; ============================================================================
;; Zone List
;; ============================================================================

(defn zone-list
  "List of configured zones."
  [{:keys [zones selected-id on-select]}]
  {:fx/type :v-box
   :style (str "-fx-background-color: " (:background styles/colors) ";")
   :children (if (seq zones)
               (mapv (fn [zone]
                       {:fx/type zone-list-item
                        :zone zone
                        :selected? (= (:id zone) selected-id)
                        :on-click on-select})
                     zones)
               [{:fx/type :label
                 :text "No zones configured"
                 :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                            "-fx-padding: 20;"
                            "-fx-alignment: center;")
                 :alignment :center}])})

;; ============================================================================
;; Tag Checkboxes
;; ============================================================================

(defn tag-checkboxes
  "Tag selection checkboxes."
  [{:keys [selected-tags on-change]}]
  {:fx/type :flow-pane
   :hgap 8
   :vgap 4
   :children (mapv (fn [tag]
                     {:fx/type :check-box
                      :text (clojure.core/name tag)
                      :selected (contains? selected-tags tag)
                      :on-selected-changed (fn [selected]
                                             (on-change (if selected
                                                          (conj selected-tags tag)
                                                          (disj selected-tags tag))))})
                   zone-tags)})

;; ============================================================================
;; Viewport Settings
;; ============================================================================

(defn viewport-settings
  "Viewport configuration panel."
  [{:keys [viewport on-change]}]
  (let [{:keys [x-min x-max y-min y-max]
         :or {x-min -1.0 x-max 1.0 y-min -1.0 y-max 1.0}} viewport]
    {:fx/type :v-box
     :spacing 4
     :children [{:fx/type :label
                 :text "Viewport"
                 :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                            "-fx-font-weight: bold;")}
                {:fx/type slider/value-slider
                 :label "X Min"
                 :value x-min
                 :min -1.0
                 :max 1.0
                 :on-change (fn [v] (on-change (assoc viewport :x-min v)))}
                {:fx/type slider/value-slider
                 :label "X Max"
                 :value x-max
                 :min -1.0
                 :max 1.0
                 :on-change (fn [v] (on-change (assoc viewport :x-max v)))}
                {:fx/type slider/value-slider
                 :label "Y Min"
                 :value y-min
                 :min -1.0
                 :max 1.0
                 :on-change (fn [v] (on-change (assoc viewport :y-min v)))}
                {:fx/type slider/value-slider
                 :label "Y Max"
                 :value y-max
                 :min -1.0
                 :max 1.0
                 :on-change (fn [v] (on-change (assoc viewport :y-max v)))}]}))

;; ============================================================================
;; Transform Settings
;; ============================================================================

(defn transform-settings
  "Transform configuration panel."
  [{:keys [transforms on-change]}]
  (let [scale-x (get-in transforms [:scale :x] 1.0)
        scale-y (get-in transforms [:scale :y] 1.0)
        offset-x (get-in transforms [:offset :x] 0.0)
        offset-y (get-in transforms [:offset :y] 0.0)
        rotation (get transforms :rotation 0.0)]
    {:fx/type :v-box
     :spacing 4
     :children [{:fx/type :label
                 :text "Transformations"
                 :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                            "-fx-font-weight: bold;")}
                {:fx/type slider/value-slider
                 :label "Scale X"
                 :value scale-x
                 :min 0.1
                 :max 2.0
                 :on-change (fn [v] (on-change (assoc-in transforms [:scale :x] v)))}
                {:fx/type slider/value-slider
                 :label "Scale Y"
                 :value scale-y
                 :min 0.1
                 :max 2.0
                 :on-change (fn [v] (on-change (assoc-in transforms [:scale :y] v)))}
                {:fx/type slider/value-slider
                 :label "Offset X"
                 :value offset-x
                 :min -1.0
                 :max 1.0
                 :on-change (fn [v] (on-change (assoc-in transforms [:offset :x] v)))}
                {:fx/type slider/value-slider
                 :label "Offset Y"
                 :value offset-y
                 :min -1.0
                 :max 1.0
                 :on-change (fn [v] (on-change (assoc-in transforms [:offset :y] v)))}
                {:fx/type slider/value-slider
                 :label "Rotation (rad)"
                 :value rotation
                 :min (- Math/PI)
                 :max Math/PI
                 :on-change (fn [v] (on-change (assoc transforms :rotation v)))}]}))

;; ============================================================================
;; Zone Editor Panel
;; ============================================================================

(defn zone-editor-panel
  "Editor panel for a zone."
  [{:keys [zone projectors on-change]}]
  (if zone
    {:fx/type :scroll-pane
     :fit-to-width true
     :style (str "-fx-background-color: " (:surface-light styles/colors) ";")
     :content {:fx/type :v-box
               :style (str "-fx-background-color: " (:surface-light styles/colors) ";"
                          "-fx-padding: 12;")
               :spacing 16
               :children [;; Name
                          {:fx/type :h-box
                           :spacing 8
                           :alignment :center-left
                           :children [{:fx/type :label
                                       :text "Name:"
                                       :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                                  "-fx-min-width: 80;")}
                                      {:fx/type :text-field
                                       :text (or (:name zone) "")
                                       :pref-width 200
                                       :on-text-changed (fn [v]
                                                          (on-change (assoc zone :name v)))}]}
                          
                          ;; Projector
                          {:fx/type :h-box
                           :spacing 8
                           :alignment :center-left
                           :children [{:fx/type :label
                                       :text "Projector:"
                                       :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                                  "-fx-min-width: 80;")}
                                      {:fx/type :combo-box
                                       :items (mapv :id projectors)
                                       :value (:projector-id zone)
                                       :on-value-changed (fn [v]
                                                           (on-change (assoc zone :projector-id v)))}]}
                          
                          ;; Priority
                          {:fx/type :h-box
                           :spacing 8
                           :alignment :center-left
                           :children [{:fx/type :label
                                       :text "Priority:"
                                       :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                                  "-fx-min-width: 80;")}
                                      {:fx/type :spinner
                                       :value-factory {:fx/type :integer-spinner-value-factory
                                                       :min 1
                                                       :max 100
                                                       :value (or (:priority zone) 1)}
                                       :on-value-changed (fn [v]
                                                           (on-change (assoc zone :priority v)))}]}
                          
                          ;; Enabled
                          {:fx/type :check-box
                           :text "Enabled"
                           :selected (boolean (:enabled zone))
                           :on-selected-changed (fn [v]
                                                  (on-change (assoc zone :enabled v)))}
                          
                          ;; Tags
                          {:fx/type :v-box
                           :spacing 4
                           :children [{:fx/type :label
                                       :text "Tags"
                                       :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                                                  "-fx-font-weight: bold;")}
                                      {:fx/type tag-checkboxes
                                       :selected-tags (or (:tags zone) #{})
                                       :on-change (fn [tags]
                                                    (on-change (assoc zone :tags tags)))}]}
                          
                          ;; Viewport
                          {:fx/type viewport-settings
                           :viewport (get-in zone [:transformations :viewport])
                           :on-change (fn [vp]
                                        (on-change (assoc-in zone [:transformations :viewport] vp)))}
                          
                          ;; Transforms
                          {:fx/type transform-settings
                           :transforms (dissoc (:transformations zone) :viewport)
                           :on-change (fn [t]
                                        (on-change (update zone :transformations merge t)))}]}}
    {:fx/type :label
     :text "Select a zone to edit"
     :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";"
                "-fx-padding: 20;")
     :alignment :center}))

;; ============================================================================
;; Main Dialog Content
;; ============================================================================

(defn dialog-content
  "Main content for the zone config dialog."
  [{:keys [zones selected-id edit-zone projectors on-select on-add on-remove on-change on-toggle on-close]}]
  {:fx/type :border-pane
   :style (str "-fx-background-color: " (:background styles/colors) ";")
   
   ;; Header
   :top {:fx/type :h-box
         :style (str "-fx-background-color: " (:surface styles/colors) ";"
                    "-fx-padding: 12 16;")
         :alignment :center-left
         :children [{:fx/type :label
                     :text "Zone Configuration"
                     :style (str "-fx-text-fill: " (:text-primary styles/colors) ";"
                                "-fx-font-size: 14px;"
                                "-fx-font-weight: bold;")}]}
   
   ;; Main content
   :center {:fx/type :split-pane
            :style (str "-fx-background-color: " (:background styles/colors) ";")
            :divider-positions [0.35]
            :items [;; Left - Zone list
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
                                             :on-action (fn [_] (on-remove selected-id))}
                                            {:fx/type :button
                                             :text "Toggle"
                                             :disable (nil? selected-id)
                                             :on-action (fn [_] (on-toggle selected-id))}]}
                                {:fx/type :scroll-pane
                                 :fit-to-width true
                                 :v-box/vgrow :always
                                 :content {:fx/type zone-list
                                           :zones zones
                                           :selected-id selected-id
                                           :on-select on-select}}]}
                    
                    ;; Right - Editor
                    {:fx/type zone-editor-panel
                     :zone edit-zone
                     :projectors projectors
                     :on-change on-change}]}
   
   ;; Footer
   :bottom {:fx/type :h-box
            :style (str "-fx-background-color: " (:surface styles/colors) ";"
                       "-fx-padding: 8 16;")
            :alignment :center-right
            :children [{:fx/type :button
                        :text "Done"
                        :style-class ["button" "primary"]
                        :on-action (fn [_] (on-close))}]}})

;; ============================================================================
;; Public API
;; ============================================================================

(defn show-zone-config!
  "Show the zone configuration dialog."
  []
  (let [zones (vec (zones/list-zones))
        projs (vec (projectors/list-projectors))]
    (reset! !config-state {:zones zones
                           :selected-zone-id (when (seq zones) (:id (first zones)))
                           :edit-zone (first zones)
                           :projectors projs
                           :stage nil}))
  
  (Platform/runLater
   (fn []
     (let [stage (Stage.)
           
           save-zones! (fn []
                         ;; Save all zones back
                         (doseq [zone (:zones @!config-state)]
                           (if (zones/get-zone (:id zone))
                             (zones/update-zone! (:id zone) (dissoc zone :id :created-at))
                             (zones/create-zone! zone))))
           
           close-fn (fn []
                      (save-zones!)
                      (.close stage)
                      (swap! !config-state assoc :stage nil))
           
           render-fn (fn []
                       (let [state @!config-state
                             selected-id (:selected-zone-id state)
                             edit-zone (first (filter #(= (:id %) selected-id) (:zones state)))]
                         {:fx/type dialog-content
                          :zones (:zones state)
                          :selected-id selected-id
                          :edit-zone edit-zone
                          :projectors (:projectors state)
                          :on-select (fn [id]
                                       (swap! !config-state assoc :selected-zone-id id))
                          :on-add (fn []
                                    (let [new-id (keyword (str "zone-" (System/currentTimeMillis)))
                                          new-zone (zones/make-zone
                                                    new-id
                                                    "New Zone"
                                                    (or (:id (first (:projectors @!config-state))) :default))]
                                      (swap! !config-state
                                             (fn [s]
                                               (-> s
                                                   (update :zones conj new-zone)
                                                   (assoc :selected-zone-id new-id))))))
                          :on-remove (fn [id]
                                       (zones/remove-zone! id)
                                       (swap! !config-state
                                              (fn [s]
                                                (let [new-zones (vec (remove #(= (:id %) id) (:zones s)))]
                                                  (-> s
                                                      (assoc :zones new-zones)
                                                      (assoc :selected-zone-id (when (seq new-zones)
                                                                                 (:id (first new-zones)))))))))
                          :on-toggle (fn [id]
                                       (swap! !config-state
                                              update :zones
                                              (fn [zones]
                                                (mapv (fn [z]
                                                        (if (= (:id z) id)
                                                          (update z :enabled not)
                                                          z))
                                                      zones))))
                          :on-change (fn [updated-zone]
                                       (swap! !config-state
                                              update :zones
                                              (fn [zones]
                                                (mapv (fn [z]
                                                        (if (= (:id z) (:id updated-zone))
                                                          updated-zone
                                                          z))
                                                      zones))))
                          :on-close close-fn}))
           
           component (fx/create-component (render-fn))
           scene (Scene. (fx/instance component) 750 550)]
       
       (.setTitle stage "Zone Configuration")
       (.setScene stage scene)
       (.initModality stage Modality/APPLICATION_MODAL)
       
       (add-watch !config-state :zone-config-render
                  (fn [_ _ _ new-state]
                    (when (:stage new-state)
                      (Platform/runLater
                       (fn []
                         (let [new-comp (fx/create-component (render-fn))]
                           (.setRoot scene (fx/instance new-comp))))))))
       
       (swap! !config-state assoc :stage stage)
       (.showAndWait stage)
       
       (remove-watch !config-state :zone-config-render)))))
