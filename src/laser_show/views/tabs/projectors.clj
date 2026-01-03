(ns laser-show.views.tabs.projectors
  "Projectors tab - for managing IDN laser projectors.
   
   Features:
   - Device discovery via network scan
   - Configure projectors with effect chains (color calibration, corner pin)
   - Test pattern mode for calibration
   - Enable/disable individual projectors"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.animation.effects :as effects]
            [laser-show.views.components.spatial-canvas :as spatial-canvas]))


;; Status Indicators


(defn- status-indicator
  "Colored circle indicating device/connection status."
  [{:keys [status size]
    :or {size 8}}]
  (let [color (case status
                :online "#4CAF50"       ;; Green
                :occupied "#FFC107"     ;; Yellow 
                :offline "#F44336"      ;; Red
                :connected "#4CAF50"    ;; Green
                :connecting "#2196F3"   ;; Blue
                "#808080")]             ;; Gray - unknown
    {:fx/type :circle
     :radius (/ size 2)
     :fill color}))


;; Device Discovery Panel


(defn- discovered-device-item
  "Single discovered device in the list."
  [{:keys [device configured?]}]
  (let [{:keys [address host-name status]} device
        device-status (cond
                        (:offline status) :offline
                        (:occupied status) :occupied
                        :else :online)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :padding 8
     :style (str "-fx-background-color: #3D3D3D; -fx-background-radius: 4;"
                 (when configured? " -fx-opacity: 0.6;"))
     :children [{:fx/type status-indicator
                 :status device-status}
                {:fx/type :v-box
                 :children [{:fx/type :label
                             :text (or host-name address)
                             :style "-fx-text-fill: white; -fx-font-weight: bold;"}
                            {:fx/type :label
                             :text address
                             :style "-fx-text-fill: #808080; -fx-font-size: 10;"}]}
                {:fx/type :region :h-box/hgrow :always}
                {:fx/type :button
                 :text (if configured? "Added" "Add →")
                 :disable configured?
                 :style "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 11; -fx-padding: 4 12;"
                 :on-action {:event/type :projectors/add-device
                             :device device}}]}))

(defn- device-discovery-panel
  "Panel for scanning network and listing discovered devices."
  [{:keys [fx/context]}]
  (let [discovered (fx/sub-ctx context subs/discovered-devices)
        scanning? (fx/sub-ctx context subs/projector-scanning?)
        configured-hosts (fx/sub-ctx context subs/configured-projector-hosts)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 280
     :children [{:fx/type :label
                 :text "DISCOVER DEVICES"
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                {:fx/type :h-box
                 :spacing 8
                 :children [{:fx/type :button
                             :text (if scanning? "Scanning..." "Scan Network")
                             :disable scanning?
                             :style "-fx-background-color: #2196F3; -fx-text-fill: white; -fx-padding: 6 16;"
                             :on-action {:event/type :projectors/scan-network}}
                            {:fx/type :button
                             :text "Add Manual..."
                             :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-padding: 6 16;"
                             :on-action {:event/type :ui/open-dialog
                                         :dialog-id :add-projector-manual}}]}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: #2D2D2D; -fx-background: #2D2D2D;"
                 :content {:fx/type :v-box
                           :spacing 4
                           :padding 4
                           :children (if (seq discovered)
                                       (vec (for [device discovered]
                                              {:fx/type discovered-device-item
                                               :fx/key (:address device)
                                               :device device
                                               :configured? (contains? configured-hosts (:address device))}))
                                       [{:fx/type :label
                                         :text (if scanning?
                                                 "Searching for devices..."
                                                 "No devices found.\nClick 'Scan Network' to search.")
                                         :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-padding: 16;"}])}}]}))


;; Configured Projectors List


(defn- projector-list-item
  "Single configured projector in the list."
  [{:keys [projector selected?]}]
  (let [{:keys [id name host enabled? status]} projector
        connected? (:connected? status)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :padding 8
     :style (str "-fx-background-color: " (if selected? "#4A6FA5" "#3D3D3D") "; -fx-background-radius: 4;")
     :on-mouse-clicked {:event/type :projectors/select-projector
                        :projector-id id}
     :children [{:fx/type :check-box
                 :selected enabled?
                 :on-selected-changed {:event/type :projectors/toggle-enabled
                                       :projector-id id}}
                {:fx/type status-indicator
                 :status (if connected? :connected :offline)}
                {:fx/type :v-box
                 :children [{:fx/type :label
                             :text name
                             :style "-fx-text-fill: white; -fx-font-weight: bold;"}
                            {:fx/type :label
                             :text host
                             :style "-fx-text-fill: #808080; -fx-font-size: 10;"}]}
                {:fx/type :region :h-box/hgrow :always}
                {:fx/type :button
                 :text "✕"
                 :style "-fx-background-color: transparent; -fx-text-fill: #808080; -fx-font-size: 14; -fx-padding: 2 6;"
                 :on-action {:event/type :projectors/remove-projector
                             :projector-id id}}]}))

(defn- configured-projectors-list
  "List of configured projectors."
  [{:keys [fx/context]}]
  (let [projectors (fx/sub-ctx context subs/projectors-list)
        active-id (fx/sub-ctx context subs/active-projector-id)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 280
     :children [{:fx/type :label
                 :text "CONFIGURED PROJECTORS"
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :v-box/vgrow :always
                 :style "-fx-background-color: #2D2D2D; -fx-background: #2D2D2D;"
                 :content {:fx/type :v-box
                           :spacing 4
                           :padding 4
                           :children (if (seq projectors)
                                       (vec (for [projector projectors]
                                              {:fx/type projector-list-item
                                               :fx/key (:id projector)
                                               :projector projector
                                               :selected? (= (:id projector) active-id)}))
                                       [{:fx/type :label
                                         :text "No projectors configured.\nAdd from discovered devices or manually."
                                         :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-padding: 16;"}])}}]}))


;; Projector Configuration Panel


(defn- projector-basic-settings
  "Basic projector settings (name, host, port)."
  [{:keys [projector]}]
  (let [{:keys [id name host port]} projector]
    {:fx/type :v-box
     :spacing 8
     :children [{:fx/type :h-box
                 :spacing 8
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text "Name:"
                             :pref-width 50
                             :style "-fx-text-fill: #B0B0B0;"}
                            {:fx/type :text-field
                             :text (or name "")
                             :pref-width 200
                             :style "-fx-background-color: #404040; -fx-text-fill: white;"
                             :on-text-changed {:event/type :projectors/update-settings
                                               :projector-id id
                                               :updates {:name :fx/event}}}]}
                {:fx/type :h-box
                 :spacing 8
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text "Host:"
                             :pref-width 50
                             :style "-fx-text-fill: #B0B0B0;"}
                            {:fx/type :text-field
                             :text (or host "")
                             :pref-width 150
                             :style "-fx-background-color: #404040; -fx-text-fill: white;"
                             :on-text-changed {:event/type :projectors/update-settings
                                               :projector-id id
                                               :updates {:host :fx/event}}}
                            {:fx/type :label
                             :text "Port:"
                             :style "-fx-text-fill: #B0B0B0;"}
                            {:fx/type :text-field
                             :text (str (or port 7255))
                             :pref-width 60
                             :style "-fx-background-color: #404040; -fx-text-fill: white;"}]}]}))

(defn- effect-item-row
  "Single effect in the projector's chain."
  [{:keys [projector-id effect-idx effect selected? on-select]}]
  (let [effect-def (effects/get-effect (:effect-id effect))
        effect-name (or (:name effect-def) (name (:effect-id effect)))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :padding {:left 8 :right 8 :top 4 :bottom 4}
     :style (str "-fx-background-color: " (if selected? "#4A6FA5" "#3D3D3D") "; -fx-background-radius: 4; -fx-cursor: hand;")
     :on-mouse-clicked (merge {:event/type :projectors/select-effect
                               :projector-id projector-id
                               :effect-idx effect-idx}
                              on-select)
     :children [{:fx/type :check-box
                 :selected (:enabled? effect true)
                 :on-selected-changed {:event/type :projectors/update-effect-param
                                       :projector-id projector-id
                                       :effect-idx effect-idx
                                       :param-key :enabled?}}
                {:fx/type :label
                 :text effect-name
                 :style "-fx-text-fill: white;"}
                {:fx/type :region :h-box/hgrow :always}
                {:fx/type :button
                 :text "✕"
                 :style "-fx-background-color: transparent; -fx-text-fill: #808080; -fx-font-size: 12;"
                 :on-action {:event/type :projectors/remove-effect
                             :projector-id projector-id
                             :effect-idx effect-idx}}]}))


;; Effect Parameter Editors


(defn- slider-param-editor
  "Slider control for a numeric parameter."
  [{:keys [projector-id effect-idx param-key value min-val max-val label step]
    :or {min-val 0.0 max-val 1.0 step 0.01}}]
  {:fx/type :h-box
   :spacing 8
   :alignment :center-left
   :children [{:fx/type :label
               :text (str label ":")
               :pref-width 80
               :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
              {:fx/type :slider
               :min min-val
               :max max-val
               :value (or value (/ (+ min-val max-val) 2))
               :block-increment step
               :pref-width 150
               :on-value-changed {:event/type :projectors/update-effect-param
                                  :projector-id projector-id
                                  :effect-idx effect-idx
                                  :param-key param-key}}
              {:fx/type :label
               :text (format "%.2f" (double (or value 0)))
               :pref-width 40
               :style "-fx-text-fill: #808080; -fx-font-size: 10; -fx-font-family: 'Consolas', monospace;"}]})

(defn- checkbox-param-editor
  "Checkbox control for a boolean parameter."
  [{:keys [projector-id effect-idx param-key value label]}]
  {:fx/type :h-box
   :spacing 8
   :alignment :center-left
   :children [{:fx/type :check-box
               :selected (boolean value)
               :text label
               :style "-fx-text-fill: #B0B0B0;"
               :on-selected-changed {:event/type :projectors/update-effect-param
                                     :projector-id projector-id
                                     :effect-idx effect-idx
                                     :param-key param-key}}]})

(defn- corner-pin-visual-editor
  "Visual editor for corner pin effect with draggable corners."
  [{:keys [projector-id effect-idx params]}]
  (let [tl-x (get params :tl-x -1.0)
        tl-y (get params :tl-y 1.0)
        tr-x (get params :tr-x 1.0)
        tr-y (get params :tr-y 1.0)
        bl-x (get params :bl-x -1.0)
        bl-y (get params :bl-y -1.0)
        br-x (get params :br-x 1.0)
        br-y (get params :br-y -1.0)]
    {:fx/type :v-box
     :spacing 8
     :children [{:fx/type :label
                 :text "Drag corners to set safety zone"
                 :style "-fx-text-fill: #808080; -fx-font-size: 10; -fx-font-style: italic;"}
                
                {:fx/type spatial-canvas/spatial-canvas
                 :fx/key [projector-id effect-idx :corner-pin]
                 :width 240
                 :height 240
                 :bounds {:x-min -1.5 :x-max 1.5
                         :y-min -1.5 :y-max 1.5}
                 :points [{:id :tl :x tl-x :y tl-y :color "#FF5722" :label "TL"}
                          {:id :tr :x tr-x :y tr-y :color "#4CAF50" :label "TR"}
                          {:id :bl :x bl-x :y bl-y :color "#2196F3" :label "BL"}
                          {:id :br :x br-x :y br-y :color "#FFC107" :label "BR"}]
                 :lines [{:from :tl :to :tr :color "#7AB8FF" :line-width 2}
                         {:from :tr :to :br :color "#7AB8FF" :line-width 2}
                         {:from :br :to :bl :color "#7AB8FF" :line-width 2}
                         {:from :bl :to :tl :color "#7AB8FF" :line-width 2}]
                 :polygon {:points [:tl :tr :br :bl] :color "#4A6FA520"}
                 :on-point-drag {:event/type :projectors/update-corner-pin
                                :projector-id projector-id
                                :effect-idx effect-idx
                                :param-map {:tl {:x :tl-x :y :tl-y}
                                           :tr {:x :tr-x :y :tr-y}
                                           :bl {:x :bl-x :y :bl-y}
                                           :br {:x :br-x :y :br-y}}}
                 :show-grid true
                 :show-axes true
                 :show-labels true}
                
                {:fx/type :v-box
                 :spacing 2
                 :children [{:fx/type :h-box
                            :spacing 12
                            :alignment :center
                            :children [{:fx/type :label
                                       :text (format "TL: (%.2f, %.2f)" tl-x tl-y)
                                       :style "-fx-text-fill: #FF5722; -fx-font-size: 9; -fx-font-family: 'Consolas', monospace;"}
                                      {:fx/type :label
                                       :text (format "TR: (%.2f, %.2f)" tr-x tr-y)
                                       :style "-fx-text-fill: #4CAF50; -fx-font-size: 9; -fx-font-family: 'Consolas', monospace;"}]}
                           {:fx/type :h-box
                            :spacing 12
                            :alignment :center
                            :children [{:fx/type :label
                                       :text (format "BL: (%.2f, %.2f)" bl-x bl-y)
                                       :style "-fx-text-fill: #2196F3; -fx-font-size: 9; -fx-font-family: 'Consolas', monospace;"}
                                      {:fx/type :label
                                       :text (format "BR: (%.2f, %.2f)" br-x br-y)
                                       :style "-fx-text-fill: #FFC107; -fx-font-size: 9; -fx-font-family: 'Consolas', monospace;"}]}]}
                
                {:fx/type :button
                 :text "Reset to Defaults"
                 :style "-fx-background-color: #505050; -fx-text-fill: #B0B0B0; -fx-font-size: 10; -fx-padding: 4 12;"
                 :on-action {:event/type :projectors/reset-corner-pin
                             :projector-id projector-id
                             :effect-idx effect-idx}}]}))

(defn- rgb-calibration-editor
  "Editor for RGB calibration effect with gain sliders."
  [{:keys [projector-id effect-idx params]}]
  {:fx/type :v-box
   :spacing 6
   :children [{:fx/type slider-param-editor
               :projector-id projector-id
               :effect-idx effect-idx
               :param-key :r-gain
               :value (get params :r-gain 1.0)
               :min-val 0.0
               :max-val 2.0
               :step 0.01
               :label "Red Gain"}
              {:fx/type slider-param-editor
               :projector-id projector-id
               :effect-idx effect-idx
               :param-key :g-gain
               :value (get params :g-gain 1.0)
               :min-val 0.0
               :max-val 2.0
               :step 0.01
               :label "Green Gain"}
              {:fx/type slider-param-editor
               :projector-id projector-id
               :effect-idx effect-idx
               :param-key :b-gain
               :value (get params :b-gain 1.0)
               :min-val 0.0
               :max-val 2.0
               :step 0.01
               :label "Blue Gain"}]})

(defn- gamma-correction-editor
  "Editor for gamma correction effect."
  [{:keys [projector-id effect-idx params]}]
  {:fx/type :v-box
   :spacing 6
   :children [{:fx/type slider-param-editor
               :projector-id projector-id
               :effect-idx effect-idx
               :param-key :r-gamma
               :value (get params :r-gamma 2.2)
               :min-val 0.5
               :max-val 4.0
               :step 0.1
               :label "Red Gamma"}
              {:fx/type slider-param-editor
               :projector-id projector-id
               :effect-idx effect-idx
               :param-key :g-gamma
               :value (get params :g-gamma 2.2)
               :min-val 0.5
               :max-val 4.0
               :step 0.1
               :label "Green Gamma"}
              {:fx/type slider-param-editor
               :projector-id projector-id
               :effect-idx effect-idx
               :param-key :b-gamma
               :value (get params :b-gamma 2.2)
               :min-val 0.5
               :max-val 4.0
               :step 0.1
               :label "Blue Gamma"}]})

(defn- axis-flip-editor
  "Editor for axis flip effect."
  [{:keys [projector-id effect-idx params]}]
  {:fx/type :h-box
   :spacing 16
   :children [{:fx/type checkbox-param-editor
               :projector-id projector-id
               :effect-idx effect-idx
               :param-key :flip-x
               :value (get params :flip-x false)
               :label "Flip X"}
              {:fx/type checkbox-param-editor
               :projector-id projector-id
               :effect-idx effect-idx
               :param-key :flip-y
               :value (get params :flip-y false)
               :label "Flip Y"}]})

(defn- rotation-offset-editor
  "Editor for rotation offset effect."
  [{:keys [projector-id effect-idx params]}]
  {:fx/type slider-param-editor
   :projector-id projector-id
   :effect-idx effect-idx
   :param-key :angle
   :value (get params :angle 0.0)
   :min-val -180.0
   :max-val 180.0
   :step 1.0
   :label "Angle (°)"})

(defn- effect-parameter-editor
  "Renders the appropriate editor for a selected effect."
  [{:keys [projector-id effect-idx effect]}]
  (let [{:keys [effect-id params]} effect]
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style "-fx-background-color: #353535; -fx-background-radius: 4;"
     :children [{:fx/type :label
                 :text (str "Edit: " (name effect-id))
                 :style "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11;"}
                (case effect-id
                  :corner-pin {:fx/type corner-pin-visual-editor
                               :projector-id projector-id
                               :effect-idx effect-idx
                               :params params}
                  :rgb-calibration {:fx/type rgb-calibration-editor
                                    :projector-id projector-id
                                    :effect-idx effect-idx
                                    :params params}
                  :gamma-correction {:fx/type gamma-correction-editor
                                     :projector-id projector-id
                                     :effect-idx effect-idx
                                     :params params}
                  :axis-flip {:fx/type axis-flip-editor
                              :projector-id projector-id
                              :effect-idx effect-idx
                              :params params}
                  :rotation-offset {:fx/type rotation-offset-editor
                                    :projector-id projector-id
                                    :effect-idx effect-idx
                                    :params params}
                  ;; Default: show message for unsupported effects
                  {:fx/type :label
                   :text "No visual editor available for this effect"
                   :style "-fx-text-fill: #808080; -fx-font-style: italic;"})]}))

(defn- projector-effects-list
  "List of effects in the projector's chain with optional effect selection."
  [{:keys [projector selected-effect-idx on-effect-select]}]
  (let [{:keys [id effects]} projector]
    {:fx/type :v-box
     :spacing 4
     :children [{:fx/type :h-box
                 :alignment :center-left
                 :children [{:fx/type :label
                             :text "EFFECTS CHAIN"
                             :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                            {:fx/type :region :h-box/hgrow :always}
                            {:fx/type :menu-button
                             :text "+ Add"
                             :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10;"
                             :items [{:fx/type :menu-item
                                      :text "RGB Calibration"
                                      :on-action {:event/type :projectors/add-effect
                                                  :projector-id id
                                                  :effect {:effect-id :rgb-calibration
                                                           :params {:r-gain 1.0 :g-gain 1.0 :b-gain 1.0}}}}
                                     {:fx/type :menu-item
                                      :text "Gamma Correction"
                                      :on-action {:event/type :projectors/add-effect
                                                  :projector-id id
                                                  :effect {:effect-id :gamma-correction
                                                           :params {:r-gamma 2.2 :g-gamma 2.2 :b-gamma 2.2}}}}
                                     {:fx/type :menu-item
                                      :text "Corner Pin"
                                      :on-action {:event/type :projectors/add-effect
                                                  :projector-id id
                                                  :effect {:effect-id :corner-pin
                                                           :params {:tl-x -1.0 :tl-y 1.0
                                                                    :tr-x 1.0 :tr-y 1.0
                                                                    :bl-x -1.0 :bl-y -1.0
                                                                    :br-x 1.0 :br-y -1.0}}}}
                                     {:fx/type :menu-item
                                      :text "Axis Flip"
                                      :on-action {:event/type :projectors/add-effect
                                                  :projector-id id
                                                  :effect {:effect-id :axis-flip
                                                           :params {:flip-x false :flip-y false}}}}
                                     {:fx/type :menu-item
                                      :text "Rotation Offset"
                                      :on-action {:event/type :projectors/add-effect
                                                  :projector-id id
                                                  :effect {:effect-id :rotation-offset
                                                           :params {:angle 0.0}}}}]}]}
                {:fx/type :v-box
                 :spacing 2
                 :children (if (seq effects)
                             (vec (for [[idx effect] (map-indexed vector effects)]
                                    {:fx/type effect-item-row
                                     :fx/key idx
                                     :projector-id id
                                     :effect-idx idx
                                     :effect effect
                                     :selected? (= idx selected-effect-idx)
                                     :on-select on-effect-select}))
                             [{:fx/type :label
                               :text "No effects. Add calibration effects above."
                               :style "-fx-text-fill: #606060; -fx-font-size: 10; -fx-font-style: italic; -fx-padding: 8;"}])}]}))

(defn- test-pattern-controls
  "Buttons to activate test patterns for calibration."
  [{:keys [fx/context]}]
  (let [mode (fx/sub-ctx context subs/test-pattern-mode)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "TEST PATTERN:"
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                {:fx/type :button
                 :text "Grid"
                 :style (str "-fx-padding: 4 12; "
                            (if (= mode :grid)
                              "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                              "-fx-background-color: #505050; -fx-text-fill: #B0B0B0;"))
                 :on-action {:event/type :projectors/set-test-pattern
                             :pattern (if (= mode :grid) nil :grid)}}
                {:fx/type :button
                 :text "Corners"
                 :style (str "-fx-padding: 4 12; "
                            (if (= mode :corners)
                              "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                              "-fx-background-color: #505050; -fx-text-fill: #B0B0B0;"))
                 :on-action {:event/type :projectors/set-test-pattern
                             :pattern (if (= mode :corners) nil :corners)}}
                {:fx/type :button
                 :text "Off"
                 :style (str "-fx-padding: 4 12; "
                            (if (nil? mode)
                              "-fx-background-color: #4A6FA5; -fx-text-fill: white;"
                              "-fx-background-color: #505050; -fx-text-fill: #B0B0B0;"))
                 :on-action {:event/type :projectors/set-test-pattern
                             :pattern nil}}]}))

(defn- projector-config-panel
  "Configuration panel for the selected projector."
  [{:keys [fx/context]}]
  (let [projector (fx/sub-ctx context subs/active-projector)
        selected-effect-idx (fx/sub-ctx context subs/selected-projector-effect-idx)
        selected-effect (when (and projector selected-effect-idx)
                          (get-in projector [:effects selected-effect-idx]))]
    {:fx/type :v-box
     :children [{:fx/type :scroll-pane
                 :v-box/vgrow :always
                 :fit-to-width true
                 :fit-to-height true
                 :style "-fx-background-color: #2D2D2D; -fx-background: #2D2D2D;"
                 :content {:fx/type :v-box
                           :spacing 16
                           :padding 16
                           :style "-fx-background-color: #2D2D2D; -fx-background-radius: 4;"
                           :children (if projector
                                       (vec
                                         (concat
                                           [{:fx/type :label
                                             :text (str "CONFIGURE: " (:name projector))
                                             :style "-fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold;"}
                                            {:fx/type projector-basic-settings
                                             :projector projector}
                                            {:fx/type :separator}
                                            {:fx/type projector-effects-list
                                             :projector projector
                                             :selected-effect-idx selected-effect-idx}]
                                           (when selected-effect
                                             [{:fx/type :separator}
                                              {:fx/type effect-parameter-editor
                                               :projector-id (:id projector)
                                               :effect-idx selected-effect-idx
                                               :effect selected-effect}])
                                           [{:fx/type :separator}
                                            {:fx/type test-pattern-controls}]))
                                       [{:fx/type :label
                                         :text "Select a projector to configure"
                                         :style "-fx-text-fill: #606060; -fx-font-style: italic;"}])}}]}))


;; Main Tab


(defn projectors-tab
  "Complete projectors tab with discovery and configuration."
  [{:keys [fx/context]}]
  {:fx/type :v-box
   :style "-fx-background-color: #1E1E1E;"
   :padding 16
   :spacing 8
   :children [{:fx/type :label
               :text "Projector Configuration"
               :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
              {:fx/type :label
               :text "Scan for IDN devices • Configure color calibration and safety zones"
               :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
              {:fx/type :h-box
               :spacing 16
               :v-box/vgrow :always
               :children [{:fx/type device-discovery-panel}
                          {:fx/type configured-projectors-list}
                          {:fx/type projector-config-panel}]}]})
