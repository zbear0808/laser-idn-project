(ns laser-show.ui-fx.views.toolbar
  "Toolbar component for transport controls and status."
  (:require [cljfx.api :as fx]
            [laser-show.ui-fx.styles :as styles]
            [laser-show.ui-fx.events :as events]))

;; ============================================================================
;; Transport Controls
;; ============================================================================

(defn transport-controls
  "Transport control buttons (play/pause, stop)."
  [{:keys [playing?]}]
  {:fx/type :h-box
   :spacing 4
   :alignment :center-left
   :children [{:fx/type :button
               :text (if playing? "⏸" "▶")
               :style (str "-fx-font-size: 14px;"
                          "-fx-min-width: 32;"
                          "-fx-min-height: 28;")
               :on-action {:event/type :transport/play-pause}}
              {:fx/type :button
               :text "⏹"
               :style (str "-fx-font-size: 14px;"
                          "-fx-min-width: 32;"
                          "-fx-min-height: 28;")
               :on-action {:event/type :transport/stop}}
              {:fx/type :button
               :text "⟳"
               :style (str "-fx-font-size: 14px;"
                          "-fx-min-width: 32;"
                          "-fx-min-height: 28;")
               :tooltip {:fx/type :tooltip :text "Retrigger"}
               :on-action {:event/type :transport/retrigger}}]})

;; ============================================================================
;; BPM Control
;; ============================================================================

(defn bpm-control
  "BPM display and tap tempo."
  [{:keys [bpm]}]
  {:fx/type :h-box
   :spacing 8
   :alignment :center-left
   :children [{:fx/type :label
               :text "BPM:"
               :style "-fx-text-fill: #808080;"}
              {:fx/type :text-field
               :text (str (int bpm))
               :pref-width 50
               :style "-fx-font-size: 11px;"
               :on-action (fn [e]
                            (try
                              (let [value (Double/parseDouble (.getText (.getSource e)))]
                                (events/dispatch! {:event/type :timing/set-bpm :bpm value}))
                              (catch NumberFormatException _ nil)))}
              {:fx/type :button
               :text "TAP"
               :style "-fx-font-size: 10px; -fx-padding: 4 8;"
               :on-action {:event/type :timing/tap-tempo}}]})

;; ============================================================================
;; Connection Controls
;; ============================================================================

(defn connection-control
  "Connection target input and connect button."
  [{:keys [connected? target on-connect on-disconnect on-target-change]}]
  {:fx/type :h-box
   :spacing 8
   :alignment :center-left
   :children [{:fx/type :label
               :text "Target:"
               :style "-fx-text-fill: #808080;"}
              {:fx/type :text-field
               :text (or target "")  ;; Empty string as default, user provides target
               :pref-width 120
               :style "-fx-font-size: 11px;"
               :on-text-changed (fn [new-text]
                                  (when on-target-change
                                    (on-target-change new-text)))}
              {:fx/type :button
               :text (if connected? "Disconnect" "Connect")
               :style-class (if connected? ["button"] ["button" "primary"])
               :on-action (fn [_]
                            (if connected?
                              (when on-disconnect (on-disconnect))
                              (when on-connect (on-connect target))))}]})

;; ============================================================================
;; Main Toolbar
;; ============================================================================

(defn toolbar
  "Main toolbar component.
   
   Props:
   - :playing? - Whether playback is active
   - :bpm - Current BPM
   - :connected? - Whether connected to IDN target
   - :target - Current connection target"
  [{:keys [playing? bpm connected? target]}]
  {:fx/type :h-box
   :style-class ["toolbar"]
   :style (str "-fx-background-color: " (:surface styles/colors) ";"
              "-fx-padding: 8;"
              "-fx-spacing: 16;")
   :alignment :center-left
   :children [{:fx/type transport-controls
               :playing? playing?}
              
              {:fx/type :separator
               :orientation :vertical}
              
              {:fx/type bpm-control
               :bpm (or bpm 120)}
              
              {:fx/type :separator
               :orientation :vertical}
              
              {:fx/type connection-control
               :connected? connected?
               :target target
               :on-connect (fn [t] (events/dispatch! {:event/type :idn/connect :target t}))
               :on-disconnect (fn [] (events/dispatch! {:event/type :idn/disconnect}))
               :on-target-change nil}
              
              ;; Spacer
              {:fx/type :region
               :h-box/hgrow :always}
              
              ;; Project status indicator
              {:fx/type :label
               :text "●"
               :style (str "-fx-text-fill: " 
                          (if connected? 
                            (:success styles/colors) 
                            (:text-secondary styles/colors))
                          ";")}]})

;; ============================================================================
;; Status Bar
;; ============================================================================

(defn status-bar
  "Status bar at the bottom of the window.
   
   Props:
   - :connected? - Connection status
   - :target - Connection target
   - :project-dirty? - Whether project has unsaved changes
   - :project-folder - Current project folder"
  [{:keys [connected? target project-dirty? project-folder]}]
  {:fx/type :h-box
   :style-class ["status-bar"]
   :style (str "-fx-background-color: " (:surface styles/colors) ";"
              "-fx-padding: 4 8;"
              "-fx-spacing: 16;")
   :alignment :center-left
   :children [;; Connection status
              {:fx/type :label
               :text (if connected?
                       (str "Connected: " target)
                       "Disconnected")
               :style-class ["label" (if connected? "connected" "disconnected")]
               :style (str "-fx-text-fill: "
                          (if connected?
                            (:success styles/colors)
                            (:text-secondary styles/colors))
                          ";")
               :graphic {:fx/type :region
                         :style (str "-fx-background-color: "
                                    (if connected?
                                      (:success styles/colors)
                                      (:text-secondary styles/colors))
                                    ";"
                                    "-fx-background-radius: 4;")
                         :pref-width 8
                         :pref-height 8}}
              
              ;; Spacer
              {:fx/type :region
               :h-box/hgrow :always}
              
              ;; Project status
              {:fx/type :label
               :text (cond
                       (and project-folder project-dirty?)
                       (str project-folder " •")
                       
                       project-folder
                       project-folder
                       
                       :else
                       "No project")
               :style (str "-fx-text-fill: " (:text-secondary styles/colors) ";")}]})
