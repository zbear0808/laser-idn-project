(ns laser-show.views.toolbar
  "Toolbar component with transport controls, BPM, and connection status."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.css.core :as css]))

;; ============================================================================
;; Transport Controls
;; ============================================================================

(defn transport-button
  "A transport control button (play/stop/etc).
   Uses style-class for base styling, inline :style only for dynamic active state."
  [{:keys [icon-text tooltip on-action active?]}]
  {:fx/type :button
   :text icon-text
   :style-class (if active? "transport-btn-active" "transport-btn")
   :tooltip {:fx/type :tooltip :text tooltip}
   :on-action on-action})

(defn transport-controls
  "Play/Stop/Retrigger controls."
  [{:keys [fx/context]}]
  (let [playing? (fx/sub-ctx context subs/playing?)]
    {:fx/type :h-box
     :spacing 4
     :alignment :center-left
     :children [{:fx/type transport-button
                 :icon-text (if playing? "■" "▶")
                 :tooltip (if playing? "Stop" "Play")
                 :on-action {:event/type (if playing? :transport/stop :transport/play)}
                 :active? playing?}
                {:fx/type transport-button
                 :icon-text "↻"
                 :tooltip "Retrigger"
                 :on-action {:event/type :transport/retrigger}
                 :active? false}]}))

;; ============================================================================
;; BPM Controls
;; ============================================================================

(defn bpm-display
  "BPM display with editable value."
  [{:keys [fx/context]}]
  (let [bpm (fx/sub-ctx context subs/bpm)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "BPM:"
                 :style-class "label-secondary"}
                {:fx/type :text-field
                 :text (format "%.1f" (double bpm))
                 :pref-width 60
                 :style-class "text-field-dark-sm"
                 :on-action (fn [e]
                              (let [text (.getText (.getSource e))]
                                (try
                                  (let [new-bpm (Double/parseDouble text)]
                                    {:event/type :timing/set-bpm :bpm new-bpm})
                                  (catch NumberFormatException _
                                    nil))))}]}))

(defn tap-tempo-button
  "Tap tempo button."
  [{:keys [fx/context]}]
  {:fx/type :button
   :text "TAP"
   :style-class "btn-sm"
   :tooltip {:fx/type :tooltip :text "Tap to set BPM"}
   :on-action {:event/type :timing/tap-tempo}})

(defn bpm-controls
  "BPM display and tap tempo."
  [{:keys [fx/context]}]
  {:fx/type :h-box
   :spacing 8
   :alignment :center-left
   :children [{:fx/type bpm-display}
              {:fx/type tap-tempo-button}]})

;; ============================================================================
;; Connection Status
;; ============================================================================

(defn connection-indicator
  "Visual connection status indicator.
   Uses dynamic inline style for color based on connection state."
  [{:keys [connected? connecting?]}]
  {:fx/type :region
   :pref-width 10
   :pref-height 10
   :style-class ["status-indicator"
                 (cond
                   connected? "status-indicator-connected"
                   connecting? "status-indicator-connecting"
                   :else "status-indicator-disconnected")]})

(defn connection-status
  "Connection status display."
  [{:keys [fx/context]}]
  (let [{:keys [connected? connecting? status-text]} (fx/sub-ctx context subs/connection-status)
        idn-config (get (fx/sub-val context :config) :idn {:host "127.0.0.1" :port 7255})
        host (:host idn-config)
        port (:port idn-config)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type connection-indicator
                 :connected? connected?
                 :connecting? connecting?}
                {:fx/type :label
                 :text status-text
                 :style-class "label-secondary"}
                {:fx/type :button
                 :text (if connected? "Disconnect" "Connect")
                 :style-class "btn-sm"
                 :on-action {:event/type (if connected? :idn/disconnect :idn/connect)
                             :host host
                             :port port}}]}))

;; ============================================================================
;; Main Toolbar
;; ============================================================================

(defn toolbar
  "Main toolbar component."
  [{:keys [fx/context]}]
  {:fx/type :h-box
   :style-class "toolbar"
   :alignment :center-left
   :children [{:fx/type transport-controls}
              {:fx/type :separator :orientation :vertical}
              {:fx/type bpm-controls}
              {:fx/type :region :h-box/hgrow :always} ;; Spacer
              {:fx/type connection-status}]})
