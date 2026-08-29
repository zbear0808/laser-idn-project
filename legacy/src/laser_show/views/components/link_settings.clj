(ns laser-show.views.components.link-settings
  "Ableton Link configuration UI component for the settings tab.
   
   Architecture:
   - Carabiner daemon auto-connects on app startup
   - Button toggles Link sync mode (:passive/:off)
   - Shows both Carabiner and Link connection status
   
   Provides:
   - Dual connection status display (Carabiner + Link)
   - Enable/disable Link sync via button
   - Enable/disable BPM sync (Link BPM -> app BPM)
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
                :connected "#4CAF50"    ;; Green
                :disconnected "#808080" ;; Gray
                :partial "#FFA500"      ;; Orange (Carabiner connected but Link not enabled)
                :syncing "#2196F3"      ;; Blue
                "#808080")]
    {:fx/type :circle
     :radius (/ size 2)
     :fill color}))


;; Connection Status Row


(defn- dual-status-row
  "Displays Carabiner and Link connection status separately."
  [{:keys [carabiner-connected? link-enabled? link-bpm link-peers]}]
  {:fx/type :v-box
   :spacing 8
   :padding {:left 8 :right 8 :top 8 :bottom 8}
   :style (str "-fx-background-color: " (css/bg-elevated) "; "
               "-fx-background-radius: 4;")
   :children
   [;; Carabiner status row
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type status-indicator
                 :status (if carabiner-connected? :connected :disconnected)
                 :size 10}
                {:fx/type :label
                 :text "Carabiner:"
                 :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                {:fx/type :label
                 :text (if carabiner-connected? "Connected" "Disconnected")
                 :style (str "-fx-text-fill: " (css/text-primary) ";")}]}
    
    ;; Link status row
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children (filterv some?
                 [{:fx/type status-indicator
                   :status (cond
                             (and carabiner-connected? link-enabled?) :connected
                             carabiner-connected? :partial
                             :else :disconnected)
                   :size 10}
                  {:fx/type :label
                   :text "Link:"
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  {:fx/type :label
                   :text (cond
                           (not carabiner-connected?) "Unavailable"
                           link-enabled? (format "Enabled (%d peer%s)" 
                                                 link-peers 
                                                 (if (= link-peers 1) "" "s"))
                           :else "Disabled")
                   :style (str "-fx-text-fill: " (css/text-primary) ";")}
                  (when (and link-enabled? link-bpm)
                    {:fx/type :region :h-box/hgrow :always})
                  (when (and link-enabled? link-bpm)
                    {:fx/type :label
                     :text (format "%.1f BPM" (double link-bpm))
                     :style (str "-fx-text-fill: " (css/selection-bg) "; "
                                 "-fx-font-weight: bold;")})])}]})


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
        {:keys [carabiner-connected? link-enabled? sync-enabled? beat-sync? 
                auto-connect? latency-ms link-bpm link-peers]} link-state]
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
                   :text (if link-enabled? "Disable Link" "Enable Link")
                   :disable (not carabiner-connected?)
                   :style-class [(if link-enabled? "button-warning" "button-primary")]
                   :on-action {:event/type :timing/link-toggle}}]}
      
      ;; Connection Status (shows both Carabiner and Link)
      {:fx/type dual-status-row
       :carabiner-connected? carabiner-connected?
       :link-enabled? link-enabled?
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
                   :disabled? (not link-enabled?)
                   :on-change {:event/type :timing/link-set-sync-enabled}}
                  
                  {:fx/type setting-row
                   :label "Sync downbeat alignment"
                   :description "Align beat phase with Link session (requires BPM sync)"
                   :checked? beat-sync?
                   :disabled? (or (not link-enabled?) (not sync-enabled?))
                   :on-change {:event/type :timing/link-set-beat-sync}}]}
      
      ;; Startup Settings
      {:fx/type :v-box
       :spacing 8
       :children [{:fx/type :label
                   :text "Startup"
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  
                  {:fx/type setting-row
                   :label "Auto-connect Carabiner on startup"
                   :description "Automatically connect to Carabiner daemon when the app starts"
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
                   :disabled? (not link-enabled?)}
                  
                  {:fx/type :label
                   :text "Positive values = packets arrive late, negative = early"
                   :style (str "-fx-text-fill: " (css/text-muted) "; "
                               "-fx-font-size: 10; "
                               "-fx-padding: 0 0 0 8;")}]}]}))
