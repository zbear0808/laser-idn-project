(ns laser-show.views.tabs.projectors
  "Projectors tab - for managing IDN laser projectors.
   
   Features:
   - Device discovery via network scan
   - Configure projectors with effect chains (color calibration, corner pin)
   - Test pattern mode for calibration
   - Enable/disable individual projectors"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.animation.chains :as chains]
            [laser-show.events.core :as events]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.views.components.spatial-canvas :as spatial-canvas]
            [laser-show.views.components.hierarchical-list :as hierarchical-list]))


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


(defn- service-entry-item
  "Single service/output entry within a device.
   Shows service name and add button for multi-output devices."
  [{:keys [device service configured?]}]
  (let [{:keys [service-id name]} service
        service-display-name (or name (str "Output " service-id))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :padding {:left 24 :right 8 :top 4 :bottom 4}
     :style "-fx-background-color: #353535; -fx-background-radius: 2;"
     :children [{:fx/type :label
                 :text (str "• " service-display-name)
                 :style (str "-fx-text-fill: " (if configured? "#606060" "#B0B0B0") "; -fx-font-size: 11;")}
                {:fx/type :region :h-box/hgrow :always}
                {:fx/type :button
                 :text (if configured? "Added" "Add")
                 :disable configured?
                 :style (str "-fx-background-color: " (if configured? "#404040" "#4A6FA5")
                            "; -fx-text-fill: " (if configured? "#606060" "white")
                            "; -fx-font-size: 10; -fx-padding: 2 8;")
                 :on-action {:event/type :projectors/add-service
                             :device device
                             :service service}}]}))

(defn- discovered-device-item
  "Single discovered device in the list.
   For devices with multiple services, shows expandable service list.
   For single-service devices, shows inline add button."
  [{:keys [device configured-services expanded? on-toggle-expand]}]
  (let [{:keys [address host-name status services]} device
        service-count (count services)
        has-multiple-services? (> service-count 1)
        ;; For single-service devices, check if that service is configured
        single-service-configured? (and (= service-count 1)
                                        (contains? configured-services
                                                   [address (:service-id (first services))]))
        ;; For no-service devices (service-id 0), check if configured
        no-service-configured? (and (zero? service-count)
                                    (contains? configured-services [address 0]))
        device-status (cond
                        (:offline status) :offline
                        (:occupied status) :occupied
                        :else :online)
        ;; Build address info children (filter out nil)
        address-info-children (filterv some?
                                       [{:fx/type :label
                                         :text address
                                         :style "-fx-text-fill: #808080; -fx-font-size: 10;"}
                                        (when (pos? service-count)
                                          {:fx/type :label
                                           :text (str "(" service-count " output" (when (> service-count 1) "s") ")")
                                           :style "-fx-text-fill: #606060; -fx-font-size: 10;"})])
        ;; Build header children (filter out nil)
        header-children (filterv some?
                                 (concat
                                   ;; Expand button for multi-service devices
                                   (when has-multiple-services?
                                     [{:fx/type :button
                                       :text (if expanded? "▼" "▶")
                                       :style "-fx-background-color: transparent; -fx-text-fill: #808080; -fx-font-size: 10; -fx-padding: 2 4; -fx-min-width: 20;"
                                       :on-action on-toggle-expand}])
                                   ;; Status indicator and device info
                                   [{:fx/type status-indicator
                                     :status device-status}
                                    {:fx/type :v-box
                                     :children [{:fx/type :label
                                                 :text (or host-name address)
                                                 :style "-fx-text-fill: white; -fx-font-weight: bold;"}
                                                {:fx/type :h-box
                                                 :spacing 8
                                                 :children address-info-children}]}
                                    {:fx/type :region :h-box/hgrow :always}]
                                   ;; For single-service or no-service devices, show add button directly
                                   (when (not has-multiple-services?)
                                     [{:fx/type :button
                                       :text (cond
                                               single-service-configured? "Added"
                                               no-service-configured? "Added"
                                               :else "Add →")
                                       :disable (or single-service-configured? no-service-configured?)
                                       :style "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 11; -fx-padding: 4 12;"
                                       :on-action {:event/type :projectors/add-device
                                                   :device device}}])))]
    {:fx/type :v-box
     :spacing 2
     :children
     (vec
       (concat
         ;; Device header row
         [{:fx/type :h-box
           :spacing 8
           :alignment :center-left
           :padding 8
           :style "-fx-background-color: #3D3D3D; -fx-background-radius: 4;"
           :children header-children}]
         ;; Expanded services list for multi-service devices
         (when (and has-multiple-services? expanded?)
           (for [service services]
             {:fx/type service-entry-item
              :fx/key (str address "-" (:service-id service))
              :device device
              :service service
              :configured? (contains? configured-services [address (:service-id service)])}))))}))

(defn- device-discovery-panel
  "Panel for scanning network and listing discovered devices.
   Shows expandable entries for multi-output devices with service list."
  [{:keys [fx/context]}]
  (let [discovered (fx/sub-ctx context subs/discovered-devices)
        scanning? (fx/sub-ctx context subs/projector-scanning?)
        configured-services (fx/sub-ctx context subs/configured-projector-services)
        expanded-devices (fx/sub-ctx context subs/expanded-discovered-devices)]
    {:fx/type :v-box
     :spacing 8
     :pref-width 300
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
                                              (let [address (:address device)]
                                                {:fx/type discovered-device-item
                                                 :fx/key address
                                                 :device device
                                                 :configured-services configured-services
                                                 :expanded? (contains? expanded-devices address)
                                                 :on-toggle-expand {:event/type :projectors/toggle-device-expand
                                                                    :address address}})))
                                       [{:fx/type :label
                                         :text (if scanning?
                                                 "Searching for devices..."
                                                 "No devices found.\nClick 'Scan Network' to search.")
                                         :style "-fx-text-fill: #606060; -fx-font-style: italic; -fx-padding: 16;"}])}}]}))


;; Configured Projectors List


(defn- projector-list-item
  "Single configured projector in the list.
   Shows service ID when configured for a specific output."
  [{:keys [projector selected?]}]
  (let [{:keys [id name host service-id service-name enabled? status]} projector
        connected? (:connected? status)
        ;; Display host:service or just host
        host-display (if (and service-id (pos? service-id))
                       (str host " #" service-id)
                       host)]
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
                             :text host-display
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
  "Basic projector settings (name, host, port, service info)."
  [{:keys [projector]}]
  (let [{:keys [id name host port service-id service-name]} projector]
    {:fx/type :v-box
     :spacing 8
     :children (vec
                 (concat
                   ;; Name field
                   [{:fx/type :h-box
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
                    ;; Host and port
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
                                 :style "-fx-background-color: #404040; -fx-text-fill: white;"}]}]
                   ;; Service info (if multi-output device)
                   (when (and service-id (pos? service-id))
                     [{:fx/type :h-box
                       :spacing 8
                       :alignment :center-left
                       :children [{:fx/type :label
                                   :text "Output:"
                                   :pref-width 50
                                   :style "-fx-text-fill: #B0B0B0;"}
                                  {:fx/type :label
                                   :text (str "Service #" service-id
                                             (when service-name (str " - " service-name)))
                                   :style "-fx-text-fill: #4A6FA5; -fx-font-size: 11;"}]}])))}))


;; Calibration effect label map for hierarchical list


(def ^:private calibration-effect-labels
  "Map of calibration effect IDs to display labels."
  {:corner-pin "Corner Pin"
   :rgb-calibration "RGB Calibration"
   :gamma-correction "Gamma Correction"
   :axis-flip "Axis Flip"
   :rotation-offset "Rotation Offset"})


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

(defn- projector-effects-sidebar
  "Effect chain sidebar using hierarchical-list-editor with built-in keyboard handling.
   Props:
   - :projector - The projector config map
   - :selected-ids - Set of selected item IDs
   - :clipboard-items - Items available for pasting"
  [{:keys [projector selected-ids clipboard-items]}]
  (let [{:keys [id effects]} projector]
    {:fx/type hierarchical-list/hierarchical-list-editor
     :items (or effects [])
     :component-id [:projector-effects id]
     :item-id-key :effect-id
     :label-map calibration-effect-labels
     :fallback-label "Unknown Effect"
     :on-change-event :projectors/set-effects
     :on-change-params {:projector-id id}
     :items-key :effects
     :on-selection-event :projectors/update-effect-selection
     :on-selection-params {:projector-id id}
     :selection-key :selected-ids
     :on-copy-fn (fn [items]
                   (clipboard/copy-effect-chain! {:effects items}))
     :clipboard-items clipboard-items
     :header-label "EFFECTS"
     :empty-text "No calibration effects\nAdd from menu above"}))

(defn- projector-add-effect-menu
  "Menu button for adding calibration effects to a projector."
  [{:keys [projector-id]}]
  {:fx/type :menu-button
   :text "+ Add Effect"
   :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10;"
   :items [{:fx/type :menu-item
            :text "RGB Calibration"
            :on-action {:event/type :projectors/add-calibration-effect
                        :projector-id projector-id
                        :effect {:effect-id :rgb-calibration
                                 :params {:r-gain 1.0 :g-gain 1.0 :b-gain 1.0}}}}
           {:fx/type :menu-item
            :text "Gamma Correction"
            :on-action {:event/type :projectors/add-calibration-effect
                        :projector-id projector-id
                        :effect {:effect-id :gamma-correction
                                 :params {:r-gamma 2.2 :g-gamma 2.2 :b-gamma 2.2}}}}
           {:fx/type :menu-item
            :text "Corner Pin"
            :on-action {:event/type :projectors/add-calibration-effect
                        :projector-id projector-id
                        :effect {:effect-id :corner-pin
                                 :params {:tl-x -1.0 :tl-y 1.0
                                          :tr-x 1.0 :tr-y 1.0
                                          :bl-x -1.0 :bl-y -1.0
                                          :br-x 1.0 :br-y -1.0}}}}
           {:fx/type :menu-item
            :text "Axis Flip"
            :on-action {:event/type :projectors/add-calibration-effect
                        :projector-id projector-id
                        :effect {:effect-id :axis-flip
                                 :params {:flip-x false :flip-y false}}}}
           {:fx/type :menu-item
            :text "Rotation Offset"
            :on-action {:event/type :projectors/add-calibration-effect
                        :projector-id projector-id
                        :effect {:effect-id :rotation-offset
                                 :params {:angle 0.0}}}}]})

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
  "Configuration panel for the selected projector.
   Uses the self-contained effect chain sidebar with callbacks."
  [{:keys [fx/context]}]
  (let [projector (fx/sub-ctx context subs/active-projector)
        projector-id (:id projector)
        ;; Get UI state for the effect chain (selection)
        ui-state (when projector-id
                   (fx/sub-ctx context subs/projector-effect-ui-state projector-id))
        selected-ids (:selected-ids ui-state #{})
        effects (or (:effects projector) [])
        ;; Get selected effect for parameter editor (first selected if single select)
        first-selected-id (when (= 1 (count selected-ids))
                            (first selected-ids))
        selected-effect-path (when first-selected-id
                               (chains/find-path-by-id effects first-selected-id))
        selected-effect-idx (when selected-effect-path
                              (first selected-effect-path))
        selected-effect (when selected-effect-path
                          (chains/get-item-at-path effects selected-effect-path))
        clipboard-items (clipboard/get-effects-to-paste)]
    (if projector
      {:fx/type :v-box
       :children [{:fx/type :scroll-pane
                   :v-box/vgrow :always
                   :fit-to-width true
                   :fit-to-height true
                   :style "-fx-background-color: #2D2D2D; -fx-background: #2D2D2D;"
                   :content {:fx/type :h-box
                             :spacing 16
                             :padding 16
                             :style "-fx-background-color: #2D2D2D;"
                             :children [;; Left: Effect chain sidebar
                                        {:fx/type :v-box
                                         :spacing 8
                                         :pref-width 250
                                         :children [{:fx/type :label
                                                     :text (str "CONFIGURE: " (:name projector))
                                                     :style "-fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold;"}
                                                    {:fx/type projector-basic-settings
                                                     :projector projector}
                                                    {:fx/type :separator}
                                                    {:fx/type :h-box
                                                     :alignment :center-left
                                                     :children [{:fx/type :label
                                                                 :text "CALIBRATION EFFECTS"
                                                                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                                                                {:fx/type :region :h-box/hgrow :always}
                                                                {:fx/type projector-add-effect-menu
                                                                 :projector-id projector-id}]}
                                                    {:fx/type projector-effects-sidebar
                                                     :projector projector
                                                     :selected-ids selected-ids
                                                     :clipboard-items clipboard-items}]}
                                        ;; Right: Parameter editor + test patterns
                                        {:fx/type :v-box
                                         :spacing 16
                                         :h-box/hgrow :always
                                         :children (vec
                                                     (concat
                                                       (when selected-effect
                                                         [{:fx/type effect-parameter-editor
                                                           :projector-id projector-id
                                                           :effect-idx selected-effect-idx
                                                           :effect selected-effect}])
                                                       [{:fx/type :region :v-box/vgrow :always}
                                                        {:fx/type test-pattern-controls}]))}]}}]}
      ;; No projector selected
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
                             :children [{:fx/type :label
                                         :text "Select a projector to configure"
                                         :style "-fx-text-fill: #606060; -fx-font-style: italic;"}]}}]})))


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
