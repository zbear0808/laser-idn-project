(ns laser-show.core
  "Main entry point for the Laser Show application.
   Refactored to use Uni-directional Data Flow."
  (:require [seesaw.core :as ss]
            [laser-show.state.dynamic :as dyn]
            [laser-show.app-events :as events]
            [laser-show.ui.window :as window]
            [laser-show.ui.layout :as layout]
            [laser-show.backend.projectors :as projectors]
            [laser-show.backend.zones :as zones]
            [laser-show.backend.zone-groups :as zone-groups]
            [laser-show.input.router :as router]
            [laser-show.input.keyboard :as keyboard]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc])
  (:gen-class))

;; ============================================================================
;; Input System Integration
;; ============================================================================

(defn- setup-input-handlers!
  "Sets up input event handlers to dispatch application events."
  []
  (let [cols layout/default-grid-cols
        rows layout/default-grid-rows]
    
    ;; Handle note-on events (trigger grid cells)
    (router/on-note! ::grid-trigger nil nil
      (fn [event]
        (let [note (:note event)
              col (mod note cols)
              row (quot note cols)]
          (when (and (< row rows) (< col cols))
            (ss/invoke-later
              (events/dispatch! [:grid/trigger-cell col row]))))))
    
    ;; Handle transport triggers
    (router/on-trigger! ::play-pause :play-pause
      (fn [_event]
        (ss/invoke-later
          (events/dispatch! [:transport/play-pause]))))
    
    (router/on-trigger! ::stop :stop
      (fn [_event]
        (ss/invoke-later
          (events/dispatch! [:transport/stop]))))
    
    (println "Input handlers registered (using event dispatch)")))

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
  (setup-input-handlers!))

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
  
  ;; Register input system shutdown as a window close callback
  (window/add-on-close-callback! shutdown-input-system!)
  
  ;; Show or create the window
  (window/show-window!)
  
  ;; Initialize input system after window is shown
  (future
    (Thread/sleep 200)
    (when-let [frame (window/get-frame)]
      (init-input-system! frame))))

(defn stop!
  "Stop the Laser Show application."
  []
  (shutdown-input-system!)
  (window/close-window!))

(defn -main
  "Main entry point."
  [& _args]
  (start!))
