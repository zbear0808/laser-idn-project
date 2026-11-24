(ns laser-show.views.components.osc-settings
  "OSC configuration UI component for the settings tab.
   
   Provides:
   - Server start/stop: one switch, as OSC input needs the server and its
     messages let in (:input/osc-start-server does both, on the port set
     when it is handled)
   - Port configuration: the field applies a port on Enter or when it loses
     focus (commit-field)
   - Address mappings display
   - Identify OSC input: what a control sends (views/components/identify_input.clj)"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]
            [laser-show.views.components.commit-field :as commit-field]
            [laser-show.views.components.identify-input :as identify-input]))


;; Status Indicator


(defn- status-indicator
  "Colored circle indicating status."
  [{:keys [status size]
    :or {size 8}}]
  (let [color (case status
                :running "#4CAF50"
                :stopped "#808080"
                "#808080")]
    {:fx/type :circle
     :radius (/ size 2)
     :fill color}))


;; The Port field


(defn- parse-port
  "The port typed into the Port field, or nil unless it is 1-65535."
  [text]
  (let [port (parse-long (str/trim text))]
    (when (and port (<= 1 port 65535))
      port)))


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
               :tooltip {:fx/type :tooltip :text "Remove this mapping"}
               :on-action {:event/type :input/osc-remove-address-mapping
                           :address address}}]})


;; OSC Settings Section


(defn osc-settings-section
  "OSC configuration section for settings tab."
  [{:keys [fx/context]}]
  (let [osc-config (fx/sub-ctx context subs/osc-config)
        server-running? (:server-running? osc-config false)
        port (:port osc-config 9000)
        address-mappings (:address-mappings osc-config {})]
    {:fx/type :v-box
     :id "osc-settings"
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
                               "-fx-text-fill: " (css/text-primary) ";")}]}
      
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
                              {:fx/type commit-field/commit-field
                               :id "osc-port-field"
                               :text (str port)
                               :pref-width 80
                               ;; Listening, the port is the server's
                               :disable server-running?
                               :on-commit (fn [typed]
                                            (when-let [new-port (parse-port typed)]
                                              {:event/type :input/osc-set-port
                                               :port new-port}))}
                              {:fx/type :button
                               :id "osc-server-button"
                               :text (if server-running? "Stop Server" "Start Server")
                               :style-class [(if server-running? "button-warning" "button-primary")]
                               :tooltip {:fx/type :tooltip
                                         :text (if server-running?
                                                 "Stop listening for OSC messages"
                                                 "Listen for OSC messages on this port")}
                               ;; No port: Start listens on the one set when
                               ;; it is handled, which a click applies first
                               ;; (the field loses focus)
                               :on-action {:event/type (if server-running?
                                                         :input/osc-stop-server
                                                         :input/osc-start-server)}}
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
      
      ;; Identify an input: what a control sends, binding nothing
      {:fx/type :v-box
       :spacing 4
       :children [{:fx/type :label
                   :text "Identify"
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  {:fx/type identify-input/identify-input
                   :source :osc}]}
      
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
