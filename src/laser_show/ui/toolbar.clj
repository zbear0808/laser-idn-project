(ns laser-show.ui.toolbar
  "Toolbar, status bar, and menu bar components for the main window."
  (:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [laser-show.ui.projector-config :as projector-config]
            [laser-show.ui.zone-config :as zone-config]
            [laser-show.ui.zone-group-config :as zone-group-config]
            [laser-show.state.atoms :as state])
  (:import [java.awt Color Dimension Font]
           [javax.swing JSpinner SpinnerNumberModel]
           [javax.swing.event ChangeListener]))

;; ============================================================================
;; Status Bar
;; ============================================================================

(defn create-status-bar
  "Create the status bar showing connection controls and status.
   
   Parameters:
   - on-connect - fn [target-string] called when connect button clicked
   - on-log-toggle - fn [enabled?] called when log checkbox toggled
   
   Returns a map with:
   - :panel - the status bar panel component
   - :set-connection! - fn [connected target] to update connection status
   - :set-fps! - fn [fps] to update FPS display
   - :set-points! - fn [points] to update points display
   - :get-target - fn [] to get target IP address
   - :log-checkbox - the log checkbox component"
  [on-connect on-log-toggle]
  (let [target-field (ss/text :text "192.168.1.100"
                              :columns 12
                              :font (Font. "Monospaced" Font/PLAIN 11))
        log-checkbox (ss/checkbox :text "Log Packets"
                                 :selected? false
                                 :font (Font. "SansSerif" Font/PLAIN 11))
        connect-btn (ss/button :text "🔌 Connect IDN"
                               :font (Font. "SansSerif" Font/PLAIN 11))
        connection-label (ss/label :text "IDN: Disconnected"
                                   :foreground (Color. 255 100 100)
                                   :font (Font. "SansSerif" Font/PLAIN 11))
        fps-label (ss/label :text "FPS: --"
                            :foreground Color/WHITE
                            :font (Font. "SansSerif" Font/PLAIN 11))
        points-label (ss/label :text "Points: --"
                               :foreground Color/WHITE
                               :font (Font. "SansSerif" Font/PLAIN 11))]
    
    (ss/listen connect-btn :action (fn [_] (on-connect (ss/text target-field))))
    (ss/listen log-checkbox :action (fn [_] (on-log-toggle (ss/value log-checkbox))))
    
    {:panel (mig/mig-panel
             :constraints ["insets 5 10 5 10", "[][][][grow][][][]", ""]
             :items [[(ss/label :text "Target:") ""]
                     [target-field ""]
                     [log-checkbox ""]
                     [connect-btn ""]
                     [(ss/label :text "") "growx"]
                     [connection-label ""]
                     [fps-label ""]
                     [points-label ""]]
             :background (Color. 35 35 35))
     :set-connection! (fn [connected target]
                        (if connected
                          (do
                            (ss/config! connection-label :text (str "IDN: " target))
                            (ss/config! connection-label :foreground (Color. 100 255 100)))
                          (do
                            (ss/config! connection-label :text "IDN: Disconnected")
                            (ss/config! connection-label :foreground (Color. 255 100 100)))))
     :set-fps! (fn [fps]
                 (ss/config! fps-label :text (format "FPS: %.1f" (float fps))))
     :set-points! (fn [points]
                    (ss/config! points-label :text (str "Points: " points)))
     :get-target (fn [] (ss/text target-field))
     :log-checkbox log-checkbox}))

;; ============================================================================
;; Toolbar
;; ============================================================================

(defn create-toolbar
  "Create the main toolbar with playback and BPM controls.
   
   Parameters:
   - on-play - fn [] called when play button clicked
   - on-stop - fn [] called when stop button clicked
   
   Returns a map with:
   - :panel - the toolbar panel component
   - :set-playing! - fn [playing?] to update button states
   - :set-bpm! - fn [bpm] to update BPM display
   - :get-bpm - fn [] to get current BPM value"
  [on-play on-stop]
  (let [play-btn (ss/button :text "▶ Play"
                            :font (Font. "SansSerif" Font/BOLD 12))
        stop-btn (ss/button :text "■ Stop"
                            :font (Font. "SansSerif" Font/BOLD 12))
        bpm-label (ss/label :text "BPM:"
                            :foreground Color/WHITE
                            :font (Font. "SansSerif" Font/PLAIN 11))
        bpm-model (SpinnerNumberModel. 120.0 20.0 300.0 0.5)
        bpm-spinner (JSpinner. bpm-model)
        _ (do
            (.setFont bpm-spinner (Font. "SansSerif" Font/BOLD 11))
            (.setPreferredSize bpm-spinner (Dimension. 65 22))
            (.addChangeListener bpm-spinner
                                (reify ChangeListener
                                  (stateChanged [_ _evt]
                                    (let [new-bpm (.getValue bpm-model)]
                                      (state/set-bpm! new-bpm))))))]
    
    (ss/listen play-btn :action (fn [_] (on-play)))
    (ss/listen stop-btn :action (fn [_] (on-stop)))
    
    {:panel (mig/mig-panel
             :constraints ["insets 5 10 5 10", "[][][][grow][][]", ""]
             :items [[play-btn ""]
                     [stop-btn ""]
                     [(ss/label :text "   ") ""]
                     [(ss/label :text "") "growx"]
                     [bpm-label ""]
                     [bpm-spinner ""]]
             :background (Color. 45 45 45))
     :set-playing! (fn [playing]
                     (ss/config! play-btn :enabled? (not playing))
                     (ss/config! stop-btn :enabled? playing))
     :set-bpm! (fn [bpm]
                 (.setValue bpm-model (double bpm)))
     :get-bpm (fn []
                (.getValue bpm-model))}))

;; ============================================================================
;; Menu Bar
;; ============================================================================

(defn create-menu-bar
  "Create the application menu bar.
   
   Parameters:
   - frame - the parent frame for dialogs
   - on-new - fn [] called for File > New Project
   - on-open - fn [] called for File > Open Project
   - on-save - fn [] called for File > Save Project
   - on-save-as - fn [] called for File > Save Project As
   - on-about - fn [] called for Help > About
   
   Returns the menu bar component."
  [frame on-new on-open on-save on-save-as on-about]
  (ss/menubar
   :items [(ss/menu :text "File"
                    :items [(ss/action :name "New Project"
                                       :key "menu N"
                                       :handler (fn [_] (on-new)))
                            (ss/action :name "Open Project..."
                                       :key "menu O"
                                       :handler (fn [_] (on-open)))
                            (ss/action :name "Save Project"
                                       :key "menu S"
                                       :handler (fn [_] (on-save)))
                            (ss/action :name "Save Project As..."
                                       :key "menu shift S"
                                       :handler (fn [_] (on-save-as)))
                            :separator
                            (ss/action :name "Exit" :handler (fn [_]
                                                               ;; Just dispose the frame, don't exit JVM
                                                               (.dispose frame)))])
           (ss/menu :text "Configure"
                    :items [(ss/action :name "Projectors..."
                                       :handler (fn [_] (projector-config/show-projector-config-dialog frame)))
                            (ss/action :name "Zones..."
                                       :handler (fn [_] (zone-config/show-zone-config-dialog frame)))
                            (ss/action :name "Zone Groups..."
                                       :handler (fn [_] (zone-group-config/show-zone-group-config-dialog frame)))])
           (ss/menu :text "View"
                    :items [(ss/action :name "Reset Layout" :handler (fn [_] nil))])
           (ss/menu :text "Help"
                    :items [(ss/action :name "About Laser Show" :handler (fn [_] (on-about)))])]))
