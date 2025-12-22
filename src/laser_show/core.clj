(ns laser-show.core
  "Main entry point for the Laser Show application.
   Creates the main window with grid, preview, and controls."
  (:require [seesaw.core :as ss]
            [seesaw.mig :as mig]
            [seesaw.border :as border]
            [seesaw.color :as sc]
            [laser-show.animation.types :as t]
            [laser-show.animation.presets :as presets]
            [laser-show.ui.grid :as grid]
            [laser-show.ui.preview :as preview]
            [laser-show.ui.projector-config :as projector-config]
            [laser-show.ui.zone-config :as zone-config]
            [laser-show.ui.zone-group-config :as zone-group-config]
            [laser-show.backend.packet-logger :as plog]
            [laser-show.backend.streaming-engine :as streaming]
            [laser-show.backend.projectors :as projectors]
            [laser-show.backend.zones :as zones]
            [laser-show.backend.zone-groups :as zone-groups]
            [laser-show.backend.multi-projector-stream :as multi-stream]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.input.events :as events]
            [laser-show.input.router :as router]
            [laser-show.input.keyboard :as keyboard]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc])
  (:import [java.awt Color Dimension Font]
           [javax.swing UIManager JFrame]
           [com.formdev.flatlaf FlatDarkLaf])
  (:gen-class))

;; ============================================================================
;; Application State
;; ============================================================================

(defonce app-state
  (atom {:main-frame nil
         :grid nil
         :preview nil
         :idn-connected false
         :idn-target nil
         :playing false
         :current-animation nil
         :animation-start-time 0
         :streaming-engine nil
         :packet-logging-enabled false
         :packet-log-file nil
         :packet-log-path "idn-packets.log"}))

;; ============================================================================
;; Frame Provider for Streaming
;; ============================================================================

(defn create-frame-provider
  "Create a function that provides the current animation frame for streaming.
   The frame provider reads from app-state and generates frames based on
   the current animation and elapsed time."
  []
  (fn []
    (when-let [anim (:current-animation @app-state)]
      (let [start-time (:animation-start-time @app-state)
            elapsed (- (System/currentTimeMillis) start-time)]
        (t/get-frame anim elapsed)))))

;; ============================================================================
;; IDN Packet Logging Helper
;; ============================================================================

(defn get-packet-log-callback
  "Returns a logging callback function if packet logging is enabled, nil otherwise.
   This function should be called when sending IDN packets and passed to send-packet."
  []
  (when (:packet-logging-enabled @app-state)
    (when-let [writer (:packet-log-file @app-state)]
      (plog/create-log-callback writer))))

(defn send-idn-packet
  "Convenience wrapper for sending IDN packets with automatic logging.
   Requires idn-hello.core to be required where this is used.
   Usage: (send-idn-packet socket data host port)"
  [socket data host port]
  (let [log-fn (get-packet-log-callback)]
    ;; Note: This requires (require '[idn-hello.core :as idn]) in the calling namespace
    ;; and calling (idn/send-packet socket data host port log-fn)
    ;; This is just a helper to get the log callback
    log-fn))

;; ============================================================================
;; Status Bar
;; ============================================================================

(defn- create-status-bar
  "Create the status bar showing connection status and FPS."
  []
  (let [connection-label (ss/label :text "IDN: Disconnected"
                                   :foreground (Color. 255 100 100)
                                   :font (Font. "SansSerif" Font/PLAIN 11))
        fps-label (ss/label :text "FPS: --"
                            :foreground Color/WHITE
                            :font (Font. "SansSerif" Font/PLAIN 11))
        points-label (ss/label :text "Points: --"
                               :foreground Color/WHITE
                               :font (Font. "SansSerif" Font/PLAIN 11))]
    {:panel (mig/mig-panel
             :constraints ["insets 5 10 5 10", "[grow][][]", ""]
             :items [[connection-label "growx"]
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
                    (ss/config! points-label :text (str "Points: " points)))}))

;; ============================================================================
;; Toolbar
;; ============================================================================

(defn- create-toolbar
  "Create the main toolbar with playback and connection controls."
  [on-play on-stop on-connect on-log-toggle]
  (let [play-btn (ss/button :text "▶ Play"
                            :font (Font. "SansSerif" Font/BOLD 12))
        stop-btn (ss/button :text "■ Stop"
                            :font (Font. "SansSerif" Font/BOLD 12))
        connect-btn (ss/button :text "🔌 Connect IDN"
                               :font (Font. "SansSerif" Font/PLAIN 11))
        target-field (ss/text :text "192.168.1.100"
                              :columns 12
                              :font (Font. "Monospaced" Font/PLAIN 11))
        log-checkbox (ss/checkbox :text "Log Packets"
                                 :selected? false
                                 :font (Font. "SansSerif" Font/PLAIN 11))]
    
    (ss/listen play-btn :action (fn [_] (on-play)))
    (ss/listen stop-btn :action (fn [_] (on-stop)))
    (ss/listen connect-btn :action (fn [_] (on-connect (ss/text target-field))))
    (ss/listen log-checkbox :action (fn [_] (on-log-toggle (ss/value log-checkbox))))
    
    {:panel (mig/mig-panel
             :constraints ["insets 5 10 5 10", "[][][][grow][][]", ""]
             :items [[play-btn ""]
                     [stop-btn ""]
                     [(ss/label :text "   ") ""]
                     [(ss/label :text "") "growx"]
                     [(ss/label :text "Target:") ""]
                     [target-field ""]
                     [log-checkbox ""]
                     [connect-btn ""]]
             :background (Color. 45 45 45))
     :set-playing! (fn [playing]
                     (ss/config! play-btn :enabled? (not playing))
                     (ss/config! stop-btn :enabled? playing))
     :get-target (fn [] (ss/text target-field))
     :log-checkbox log-checkbox}))

;; ============================================================================
;; Menu Bar
;; ============================================================================

(defn- create-menu-bar
  "Create the application menu bar."
  [frame on-new on-open on-save on-about]
  (ss/menubar
   :items [(ss/menu :text "File"
                    :items [(ss/action :name "New Grid" :handler (fn [_] (on-new)))
                            (ss/action :name "Open..." :handler (fn [_] (on-open)))
                            (ss/action :name "Save..." :handler (fn [_] (on-save)))
                            :separator
                            (ss/action :name "Exit" :handler (fn [_] (System/exit 0)))])
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

;; ============================================================================
;; Main Window
;; ============================================================================

(defn create-main-window
  "Create and configure the main application window."
  []
  (let [;; Create preview panel
        preview-component (preview/create-preview-panel :width 350 :height 350)
        
        ;; Track selected preset for assignment
        selected-preset-atom (atom nil)
        
        ;; Mutable reference for grid component (needed for forward reference)
        grid-ref (atom nil)
        
        ;; Create grid panel with handlers
        grid-component (grid/create-grid-panel
                        :cols 8
                        :rows 4
                        :on-cell-click (fn [[col row] cell-state]
                                         (println "Cell clicked:" [col row])
                                         ;; If a preset is selected from palette, assign it
                                         (when-let [preset-id @selected-preset-atom]
                                           (when-let [gc @grid-ref]
                                             ((:set-cell-preset! gc) col row preset-id))
                                           (reset! selected-preset-atom nil))
                                         ;; If cell has animation, play it
                                         (when-let [anim (:animation cell-state)]
                                           ((:set-animation! preview-component) anim)
                                           (when-let [gc @grid-ref]
                                             ((:set-active-cell! gc) col row))
                                           (swap! app-state assoc 
                                                  :playing true 
                                                  :current-animation anim
                                                  :animation-start-time (System/currentTimeMillis))))
                        :on-cell-right-click (fn [[col row] cell-state]
                                               (println "Cell right-clicked:" [col row]))
                        :on-copy (fn [[col row] cell-state]
                                   (when-let [preset-id (:preset-id cell-state)]
                                     (clipboard/copy-cell-assignment! preset-id)
                                     (println "Copied preset:" preset-id)))
                        :on-paste (fn [[col row]]
                                    (when-let [preset-id (clipboard/paste-cell-assignment)]
                                      (when-let [gc @grid-ref]
                                        ((:set-cell-preset! gc) col row preset-id)
                                        (println "Pasted preset:" preset-id "to" [col row]))))
                        :on-clear (fn [[col row]]
                                    (when-let [gc @grid-ref]
                                      ((:set-cell-preset! gc) col row nil)
                                      (println "Cleared cell:" [col row]))))
        
        ;; Store grid component in ref for use in callbacks
        _ (reset! grid-ref grid-component)
        
        ;; Create preset palette
        preset-palette (grid/create-preset-palette
                        (fn [preset-id]
                          (println "Preset selected:" preset-id)
                          (reset! selected-preset-atom preset-id)
                          ;; If a cell is selected, assign directly
                          (when-let [gc @grid-ref]
                            (when-let [[col row] ((:get-selected-cell gc))]
                              ((:set-cell-preset! gc) col row preset-id)
                              (reset! selected-preset-atom nil)))))
        
        ;; Create status bar
        status-bar (create-status-bar)
        
        ;; Toolbar reference for callbacks
        toolbar-ref (atom nil)
        
        ;; Create toolbar
        toolbar (create-toolbar
                 ;; On Play
                 (fn []
                   (when-let [anim (:current-animation @app-state)]
                     ((:set-animation! preview-component) anim)
                     (swap! app-state assoc 
                            :playing true
                            :animation-start-time (System/currentTimeMillis))))
                 ;; On Stop
                 (fn []
                   ((:stop! preview-component))
                   ((:set-active-cell! grid-component) nil nil)
                   (swap! app-state assoc :playing false :current-animation nil))
                 ;; On Connect
                 (fn [target]
                   (if (:idn-connected @app-state)
                     ;; Disconnect
                     (do
                       (println "Disconnecting from IDN target")
                       (when-let [engine (:streaming-engine @app-state)]
                         (streaming/stop! engine))
                       (swap! app-state assoc 
                              :idn-connected false 
                              :idn-target nil
                              :streaming-engine nil)
                       ((:set-connection! status-bar) false nil))
                     ;; Connect
                     (do
                       (println "Connecting to IDN target:" target)
                       (try
                         (let [frame-provider (create-frame-provider)
                               log-callback (get-packet-log-callback)
                               engine (streaming/create-engine target frame-provider
                                                               :log-callback log-callback)]
                           (streaming/start! engine)
                           (swap! app-state assoc 
                                  :idn-target target 
                                  :idn-connected true
                                  :streaming-engine engine)
                           ((:set-connection! status-bar) true target))
                         (catch Exception e
                           (println "Connection failed:" (.getMessage e))
                           (ss/alert "Connection failed: " (.getMessage e)))))))
                 ;; On Log Toggle
                 (fn [enabled]
                   (if enabled
                     ;; Start logging
                     (let [log-path (:packet-log-path @app-state)
                           writer (plog/start-logging! log-path)]
                       (if writer
                         (do
                           (swap! app-state assoc
                                  :packet-logging-enabled true
                                  :packet-log-file writer)
                           ;; Update streaming engine callback if connected
                           (when-let [engine (:streaming-engine @app-state)]
                             (streaming/set-log-callback! engine (plog/create-log-callback writer))))
                         ;; Failed to start logging, uncheck the box
                         (when-let [tb @toolbar-ref]
                           (ss/value! (:log-checkbox tb) false))))
                     ;; Stop logging
                     (do
                       (when-let [writer (:packet-log-file @app-state)]
                         (plog/stop-logging! writer))
                       ;; Clear streaming engine callback if connected
                       (when-let [engine (:streaming-engine @app-state)]
                         (streaming/set-log-callback! engine nil))
                       (swap! app-state assoc
                              :packet-logging-enabled false
                              :packet-log-file nil)))))
        
        ;; Store toolbar reference
        _ (reset! toolbar-ref toolbar)
        
        ;; Right panel with preview and palette
        right-panel (ss/border-panel
                     :north (ss/border-panel
                             :center (:panel preview-component)
                             :border (border/line-border :color (Color. 60 60 60) :thickness 1))
                     :center preset-palette
                     :vgap 10
                     :background (Color. 35 35 35))
        
        ;; Wrap grid in scrollable for when window is small
        grid-scrollable (ss/scrollable (:panel grid-component)
                                       :border nil
                                       :background (Color. 30 30 30))
        
        ;; Main content panel
        content-panel (ss/border-panel
                       :center grid-scrollable
                       :east right-panel
                       :south (:panel status-bar)
                       :north (:panel toolbar)
                       :background (Color. 30 30 30))
        
        ;; Create main frame
        frame (ss/frame
               :title "Laser Show - IDN Controller"
               :content content-panel
               :minimum-size [900 :by 600]
               :size [1100 :by 700]
               :on-close :exit)]
    
    ;; Set up menu bar
    (ss/config! frame :menubar 
                (create-menu-bar
                 frame
                 ;; New
                 (fn [] ((:clear-all! grid-component)))
                 ;; Open
                 (fn [] (println "Open not implemented yet"))
                 ;; Save
                 (fn [] (println "Save not implemented yet"))
                 ;; About
                 (fn [] 
                   (ss/alert frame 
                             "Laser Show - IDN Controller\n\nA launchpad-style interface for laser animations.\n\nVersion 0.1.0"))))
    
    ;; Store references
    (swap! app-state assoc
           :main-frame frame
           :grid grid-component
           :preview preview-component)
    
    ;; Set up some demo presets
    ((:set-cell-preset! grid-component) 0 0 :circle)
    ((:set-cell-preset! grid-component) 1 0 :spinning-square)
    ((:set-cell-preset! grid-component) 2 0 :triangle)
    ((:set-cell-preset! grid-component) 3 0 :star)
    ((:set-cell-preset! grid-component) 4 0 :spiral)
    ((:set-cell-preset! grid-component) 5 0 :wave)
    ((:set-cell-preset! grid-component) 6 0 :beam-fan)
    ((:set-cell-preset! grid-component) 7 0 :rainbow-circle)
    
    frame))

;; ============================================================================
;; Input System Integration
;; ============================================================================

(defn- setup-input-handlers!
  "Sets up input event handlers to control the grid and application."
  []
  (let [{:keys [grid preview]} @app-state
        cols 8]
    
    ;; Handle note-on events (trigger grid cells)
    (router/on-note! ::grid-trigger nil nil
      (fn [event]
        (when grid
          (let [note (:note event)
                col (mod note cols)
                row (quot note cols)]
            (when (and (< row 4) (< col 8))
              ;; Trigger the cell via the grid's click handler
              (ss/invoke-later
                (when-let [cell-state ((:get-cell-state grid) col row)]
                  (when-let [anim (:animation cell-state)]
                    ((:set-animation! preview) anim)
                    ((:set-active-cell! grid) col row)
                    (swap! app-state assoc :playing true :current-animation anim)))))))))
    
    ;; Handle transport triggers
    (router/on-trigger! ::play-pause :play-pause
      (fn [_event]
        (ss/invoke-later
          (if (:playing @app-state)
            (do
              ((:stop! preview))
              (when grid ((:set-active-cell! grid) nil nil))
              (swap! app-state assoc :playing false :current-animation nil))
            (when-let [anim (:current-animation @app-state)]
              ((:set-animation! preview) anim)
              (swap! app-state assoc :playing true))))))
    
    (router/on-trigger! ::stop :stop
      (fn [_event]
        (ss/invoke-later
          ((:stop! preview))
          (when grid ((:set-active-cell! grid) nil nil))
          (swap! app-state assoc :playing false :current-animation nil))))
    
    (println "Input handlers registered")))

(defn- init-input-system!
  "Initializes the input system (keyboard, MIDI, OSC)."
  [frame]
  ;; Initialize keyboard input
  (keyboard/init!)
  (keyboard/attach-to-component! frame)
  (println "Keyboard input initialized")
  
  ;; Initialize MIDI (don't auto-connect, let user do it)
  (midi/init! false)
  (println "MIDI input initialized (no auto-connect)")
  
  ;; Initialize OSC (don't start server by default)
  (osc/init! false)
  (println "OSC input initialized (server not started)")
  
  ;; Set up the handlers
  (setup-input-handlers!)
  
  ;; Enable router logging for debugging (optional)
  ;; (router/enable-logging!)
  )

(defn- shutdown-input-system!
  "Shuts down the input system cleanly."
  []
  (router/clear-handlers!)
  (keyboard/detach-all!)
  (midi/shutdown!)
  (osc/shutdown!)
  (println "Input system shutdown complete"))

;; ============================================================================
;; Application Entry Point
;; ============================================================================

(defn start!
  "Start the Laser Show application."
  []
  ;; Initialize projector/zone systems (before UI)
  (println "Initializing projector and zone systems...")
  (projectors/init!)
  (zones/init!)
  (zone-groups/init!)
  (println "Projector/zone systems initialized")
  
  (ss/invoke-later
   (try
     ;; Set up FlatLaf dark theme
     (FlatDarkLaf/setup)
     (catch Exception e
       (println "Could not set FlatLaf theme:" (.getMessage e))))
   
   (let [frame (create-main-window)]
     ;; Initialize input system
     (init-input-system! frame)
     
     ;; Show the frame
     (ss/show! frame))))

(defn -main
  "Main entry point."
  [& args]
  (start!))

;; For REPL development
(comment
  (start!)
  
  ;; Test animation
  (let [anim (presets/create-animation-from-preset :spinning-square)]
    ((:set-animation! (:preview @app-state)) anim))
  
  ;; Stop animation
  ((:stop! (:preview @app-state)))
  )
