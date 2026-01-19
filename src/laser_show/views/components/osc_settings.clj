(ns laser-show.views.components.osc-settings
  "OSC configuration UI component for the settings tab.
   
   Provides:
   - Enable/disable OSC input
   - Server start/stop controls
   - Port configuration
   - Address mappings display
   - OSC learn mode"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]))


;; Status Indicator


(defn- status-indicator
  "Colored circle indicating status."
  [{:keys [status size]
    :or {size 8}}]
  (let [color (case status
                :running "#4CAF50"
                :stopped "#808080"
                :learning "#2196F3"
                "#808080")]
    {:fx/type :circle
     :radius (/ size 2)
     :fill color}))


;; Address Mapping Row


(defn- mapping-type-label
  "Format mapping type for display."
  [mapping]
  (case (:type mapping)
    :control-change (str "CC " (:control mapping)
                         (when (:second-control mapping)
                           (str "/" (:second-control mapping))))
    :note (str "Note " (:note mapping))
    :trigger (str "Trigger " (name (:id mapping)))
    "Unknown"))

(defn- address-mapping-row
  "Single address mapping row."
  [{:keys [address mapping]}]
  {:fx/type :h-box
   :spacing 8
   :alignment :center-left
   :padding {:left 8 :right 8 :top 4 :bottom 4}
   :style (str "-fx-background-color: " (css/bg-elevated) "; "
               "-fx-background-radius: 4;")
   :children [{:fx/type :label
               :text address
               :style (str "-fx-text-fill: " (css/text-primary) "; "
                           "-fx-font-family: monospace;")
               :pref-width 150}
              {:fx/type :label
               :text "→"
               :style (str "-fx-text-fill: " (css/text-muted) ";")}
              {:fx/type :label
               :text (mapping-type-label mapping)
               :style (str "-fx-text-fill: " (css/text-secondary) ";")
               :h-box/hgrow :always}
              {:fx/type :button
               :text "✕"
               :style-class ["button-close"]
               :style "-fx-font-size: 10; -fx-padding: 2 6;"
               :on-action {:event/type :input/osc-remove-address-mapping
                           :address address}}]})


;; OSC Settings Section


(defn osc-settings-section
  "OSC configuration section for settings tab."
  [{:keys [fx/context]}]
  (let [osc-config (fx/sub-ctx context subs/osc-config)
        enabled? (:enabled osc-config)
        server-running? (:server-running? osc-config false)
        port (:port osc-config 9000)
        address-mappings (:address-mappings osc-config {})
        learning? (fx/sub-ctx context subs/osc-learning?)]
    {:fx/type :v-box
     :spacing 12
     :style (str "-fx-background-color: " (css/bg-primary) "; "
                 "-fx-padding: 16; "
                 "-fx-background-radius: 8; "
                 "-fx-border-color: " (css/border) "; "
                 "-fx-border-radius: 8; "
                 "-fx-border-width: 1;")
     :children
     [;; Section Header
      {:fx/type :h-box
       :spacing 8
       :alignment :center-left
       :children [{:fx/type :label
                   :text "OSC Configuration"
                   :style (str "-fx-font-size: 16; -fx-font-weight: bold; "
                               "-fx-text-fill: " (css/text-primary) ";")}
                  {:fx/type :region :h-box/hgrow :always}
                  {:fx/type :check-box
                   :text "Enabled"
                   :selected enabled?
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")
                   :on-selected-changed {:event/type (if enabled?
                                                       :input/osc-disable
                                                       :input/osc-enable)}}]}
      
      ;; Server Status and Controls
      {:fx/type :v-box
       :spacing 8
       :children [{:fx/type :label
                   :text "Server"
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  {:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children [{:fx/type :label
                               :text "Port:"
                               :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                              {:fx/type :text-field
                               :text (str port)
                               :pref-width 80
                               :style-class ["text-field-dark"]
                               :disable server-running?
                               :on-action (fn [evt]
                                            (let [text (.getText (.getSource evt))]
                                              (try
                                                {:event/type :input/osc-set-port
                                                 :port (Integer/parseInt text)}
                                                (catch Exception _
                                                  nil))))}
                              {:fx/type :button
                               :text (if server-running? "Stop Server" "Start Server")
                               :style-class [(if server-running? "button-warning" "button-primary")]
                               :on-action {:event/type (if server-running?
                                                         :input/osc-stop-server
                                                         :input/osc-start-server)
                                           :port port}}
                              {:fx/type :h-box
                               :spacing 4
                               :alignment :center-left
                               :children [{:fx/type status-indicator
                                           :status (if server-running? :running :stopped)}
                                          {:fx/type :label
                                           :text (if server-running?
                                                   (str "Listening on port " port)
                                                   "Server stopped")
                                           :style (str "-fx-text-fill: "
                                                       (if server-running?
                                                         "#4CAF50"
                                                         (css/text-muted)) ";")}]}]}]}
      
      ;; OSC Learn Mode
      {:fx/type :v-box
       :spacing 4
       :children [{:fx/type :label
                   :text "OSC Learn"
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  {:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children (filterv some?
                               [{:fx/type :button
                                 :text (if learning? "Cancel Learn" "Start Learn Mode")
                                 :style-class [(if learning? "button-warning" "button-primary")]
                                 :disable (not server-running?)
                                 :on-action {:event/type (if learning?
                                                          :input/osc-cancel-learn
                                                          :input/osc-start-learn)}}
                                (when learning?
                                  {:fx/type :h-box
                                   :spacing 4
                                   :alignment :center-left
                                   :children [{:fx/type status-indicator
                                               :status :learning}
                                              {:fx/type :label
                                               :text "Waiting for OSC input..."
                                               :style (str "-fx-text-fill: " (css/selection-bg) ";")}]})])}]}
      
      ;; Address Mappings
      {:fx/type :v-box
       :spacing 8
       :children [{:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children [{:fx/type :label
                               :text "Address Mappings"
                               :style (str "-fx-text-fill: " (css/text-secondary) ";")
                               :h-box/hgrow :always}
                              {:fx/type :label
                               :text (str (count address-mappings) " configured")
                               :style (str "-fx-text-fill: " (css/text-muted) "; "
                                           "-fx-font-size: 10;")}
                              {:fx/type :button
                               :text "Load Defaults"
                               :style-class ["button-secondary"]
                               :style "-fx-font-size: 10;"
                               :on-action {:event/type :input/osc-load-default-mappings}}]}
                  
                  ;; Scrollable mapping list
                  {:fx/type :scroll-pane
                   :fit-to-width true
                   :pref-height 150
                   :max-height 200
                   :style-class ["scroll-pane-dark"]
                   :content {:fx/type :v-box
                             :spacing 4
                             :padding 4
                             :children (if (seq address-mappings)
                                         (mapv (fn [[address mapping]]
                                                 {:fx/type address-mapping-row
                                                  :fx/key address
                                                  :address address
                                                  :mapping mapping})
                                               (sort-by first address-mappings))
                                         [{:fx/type :label
                                           :text "No mappings configured.\nClick 'Load Defaults' for TouchOSC-style mappings."
                                           :style (str "-fx-text-fill: " (css/text-muted) "; "
                                                       "-fx-font-style: italic; "
                                                       "-fx-padding: 8;")}])}}]}]}))
