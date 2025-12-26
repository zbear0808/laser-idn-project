(ns laser-show.views.toolbar
  "Toolbar component with transport controls, BPM, and connection status."
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]))

;; ============================================================================
;; Transport Controls
;; ============================================================================

(defn transport-button
  "A transport control button (play/stop/etc)."
  [{:keys [icon-text tooltip on-action style]}]
  {:fx/type :button
   :text icon-text
   :style (str "-fx-background-color: #3D3D3D; "
               "-fx-text-fill: white; "
               "-fx-font-size: 14; "
               "-fx-min-width: 40; "
               "-fx-min-height: 32; "
               "-fx-cursor: hand; "
               style)
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
                 :style (when playing? "-fx-background-color: #4CAF50;")}
                {:fx/type transport-button
                 :icon-text "↻"
                 :tooltip "Retrigger"
                 :on-action {:event/type :transport/retrigger}}]}))

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
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 12;"}
                {:fx/type :text-field
                 :text (format "%.1f" (double bpm))
                 :pref-width 60
                 :style "-fx-background-color: #3D3D3D; -fx-text-fill: white; -fx-font-size: 12;"
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
   :style "-fx-background-color: #3D3D3D; -fx-text-fill: white; -fx-font-size: 11; -fx-padding: 4 12; -fx-cursor: hand;"
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
  "Visual connection status indicator."
  [{:keys [connected? connecting?]}]
  {:fx/type :region
   :pref-width 10
   :pref-height 10
   :style (str "-fx-background-radius: 5; "
               "-fx-background-color: "
               (cond
                 connected? "#4CAF50"
                 connecting? "#FF9800"
                 :else "#F44336")
               ";")})

(defn connection-status
  "Connection status display."
  [{:keys [fx/context]}]
  (let [{:keys [connected? connecting? status-text]} (fx/sub-ctx context subs/connection-status)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type connection-indicator
                 :connected? connected?
                 :connecting? connecting?}
                {:fx/type :label
                 :text status-text
                 :style "-fx-text-fill: #B0B0B0; -fx-font-size: 11;"}
                {:fx/type :button
                 :text (if connected? "Disconnect" "Connect")
                 :style "-fx-background-color: #3D3D3D; -fx-text-fill: white; -fx-font-size: 11; -fx-padding: 4 8; -fx-cursor: hand;"
                 :on-action {:event/type (if connected? :idn/disconnect :idn/connect)
                             :host "localhost"
                             :port 7255}}]}))

;; ============================================================================
;; Main Toolbar
;; ============================================================================

(defn toolbar
  "Main toolbar component."
  [{:keys [fx/context]}]
  {:fx/type :h-box
   :style "-fx-background-color: #2D2D2D; -fx-padding: 8 16;"
   :spacing 24
   :alignment :center-left
   :children [{:fx/type transport-controls}
              {:fx/type :separator :orientation :vertical}
              {:fx/type bpm-controls}
              {:fx/type :region :h-box/hgrow :always} ;; Spacer
              {:fx/type connection-status}]})
