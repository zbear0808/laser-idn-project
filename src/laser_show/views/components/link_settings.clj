(ns laser-show.views.components.link-settings
  "Ableton Link configuration UI component for the settings tab.
   
   Provides:
   - Connection status display
   - Enable/disable BPM sync
   - Enable/disable beat (downbeat) sync
   - Auto-connect on startup toggle
   - Latency compensation setting"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]))


;; Status Indicator


(defn- status-indicator
  "Colored circle indicating status."
  [{:keys [status size]
    :or {size 8}}]
  (let [color (case status
                :connected "#4CAF50"
                :disconnected "#808080"
                :syncing "#2196F3"
                "#808080")]
    {:fx/type :circle
     :radius (/ size 2)
     :fill color}))


;; Connection Status Row


(defn- connection-status-row
  "Displays current Link connection status."
  [{:keys [connected? link-bpm link-peers]}]
  {:fx/type :h-box
   :spacing 10
   :alignment :center-left
   :padding {:left 8 :right 8 :top 8 :bottom 8}
   :style (str "-fx-background-color: " (css/bg-elevated) "; "
               "-fx-background-radius: 4;")
   :children (filterv some?
               [{:fx/type status-indicator
                 :status (if connected? :connected :disconnected)
                 :size 10}
                {:fx/type :label
                 :text (if connected?
                         (format "Connected to %d peer(s)" link-peers)
                         "Disconnected")
                 :style (str "-fx-text-fill: " (css/text-primary) ";")
                 :h-box/hgrow :always}
                (when (and connected? link-bpm)
                  {:fx/type :label
                   :text (format "%.1f BPM" (double link-bpm))
                   :style (str "-fx-text-fill: " (css/selection-bg) "; "
                               "-fx-font-weight: bold;")})])})


;; Settings Row


(defn- setting-row
  "A row with a checkbox setting and optional description."
  [{:keys [label description checked? disabled? on-change]}]
  {:fx/type :v-box
   :spacing 2
   :children (filterv some?
               [{:fx/type :check-box
                 :text label
                 :selected (boolean checked?)
                 :disable (boolean disabled?)
                 :style (str "-fx-text-fill: " (css/text-primary) ";")
                 :on-selected-changed on-change}
                (when description
                  {:fx/type :label
                   :text description
                   :style (str "-fx-text-fill: " (css/text-muted) "; "
                               "-fx-font-size: 10; "
                               "-fx-padding: 0 0 0 24;")})])})


;; Latency Input


(defn- latency-input
  "Input for latency compensation in milliseconds."
  [{:keys [latency-ms disabled?]}]
  {:fx/type :h-box
   :spacing 8
   :alignment :center-left
   :children [{:fx/type :label
               :text "Latency compensation:"
               :style (str "-fx-text-fill: " (css/text-secondary) ";")}
              {:fx/type :spinner
               :disable (boolean disabled?)
               :pref-width 100
               :style-class ["spinner"]
               :value-factory {:fx/type :integer-spinner-value-factory
                               :min -500
                               :max 500
                               :value (or latency-ms 0)
                               :amount-to-step-by 5}
               :on-value-changed {:event/type :timing/link-set-latency}}
              {:fx/type :label
               :text "ms"
               :style (str "-fx-text-fill: " (css/text-muted) ";")}]})


;; Link Settings Section


(defn link-settings-section
  "Ableton Link configuration section for settings tab."
  [{:keys [fx/context]}]
  (let [link-state (fx/sub-ctx context subs/link-status)
        {:keys [connected? sync-enabled? beat-sync? auto-connect? latency-ms link-bpm link-peers]} link-state]
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
                   :text "Ableton Link"
                   :style (str "-fx-font-size: 16; -fx-font-weight: bold; "
                               "-fx-text-fill: " (css/text-primary) ";")}
                  {:fx/type :region :h-box/hgrow :always}
                  {:fx/type :button
                   :text (if connected? "Disconnect" "Connect")
                   :style-class [(if connected? "button-warning" "button-primary")]
                   :on-action {:event/type :timing/link-toggle}}]}
      
      ;; Connection Status
      {:fx/type connection-status-row
       :connected? connected?
       :link-bpm link-bpm
       :link-peers (or link-peers 0)}
      
      ;; Sync Settings
      {:fx/type :v-box
       :spacing 8
       :children [{:fx/type :label
                   :text "Sync Settings"
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  
                  {:fx/type setting-row
                   :label "Sync BPM from Link"
                   :description "Use Link session tempo as the app BPM"
                   :checked? sync-enabled?
                   :disabled? (not connected?)
                   :on-change {:event/type :timing/link-set-sync-enabled}}
                  
                  {:fx/type setting-row
                   :label "Sync downbeat alignment"
                   :description "Align beat phase with Link session (requires BPM sync)"
                   :checked? beat-sync?
                   :disabled? (or (not connected?) (not sync-enabled?))
                   :on-change {:event/type :timing/link-set-beat-sync}}]}
      
      ;; Startup Settings
      {:fx/type :v-box
       :spacing 8
       :children [{:fx/type :label
                   :text "Startup"
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  
                  {:fx/type setting-row
                   :label "Auto-connect on startup"
                   :description "Automatically connect to Link when the app starts"
                   :checked? auto-connect?
                   :disabled? false
                   :on-change {:event/type :timing/link-set-auto-connect}}]}
      
      ;; Advanced Settings
      {:fx/type :v-box
       :spacing 8
       :children [{:fx/type :label
                   :text "Advanced"
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  
                  {:fx/type latency-input
                   :latency-ms latency-ms
                   :disabled? (not connected?)}
                  
                  {:fx/type :label
                   :text "Positive values = packets arrive late, negative = early"
                   :style (str "-fx-text-fill: " (css/text-muted) "; "
                               "-fx-font-size: 10; "
                               "-fx-padding: 0 0 0 8;")}]}]}))