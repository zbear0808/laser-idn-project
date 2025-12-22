(ns laser-show.core
  "Main entry point for the Laser Show application.
   
   This namespace provides the application entry point and input system setup.
   The main window and UI components are managed by laser-show.ui.window."
  (:require [seesaw.core :as ss]
            [laser-show.ui.window :as window]
            [laser-show.ui.layout :as layout]
            [laser-show.backend.projectors :as projectors]
            [laser-show.backend.zones :as zones]
            [laser-show.backend.zone-groups :as zone-groups]
            [laser-show.input.events :as events]
            [laser-show.input.router :as router]
            [laser-show.input.keyboard :as keyboard]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc])
  (:gen-class))

;; ============================================================================
;; Application State (delegated to window namespace)
;; ============================================================================

(def app-state 
  "Application state atom. This is the same atom as window/app-state,
   provided here for backward compatibility."
  window/app-state)

;; ============================================================================
;; Input System Integration
;; ============================================================================

(defn- setup-input-handlers!
  "Sets up input event handlers to control the grid and application."
  []
  (let [{:keys [grid preview]} @window/app-state
        cols layout/default-grid-cols
        rows layout/default-grid-rows]
    
    ;; Handle note-on events (trigger grid cells)
    (router/on-note! ::grid-trigger nil nil
      (fn [event]
        (when grid
          (let [note (:note event)
                col (mod note cols)
                row (quot note cols)]
            (when (and (< row rows) (< col cols))
              (ss/invoke-later
                (when-let [cell-state ((:get-cell-state grid) col row)]
                  (when-let [anim (:animation cell-state)]
                    ((:set-animation! preview) anim)
                    ((:set-active-cell! grid) col row)
                    (swap! window/app-state assoc :playing true :current-animation anim)))))))))
    
    ;; Handle transport triggers
    (router/on-trigger! ::play-pause :play-pause
      (fn [_event]
        (ss/invoke-later
          (if (:playing @window/app-state)
            (do
              ((:stop! preview))
              (when grid ((:set-active-cell! grid) nil nil))
              (swap! window/app-state assoc :playing false :current-animation nil))
            (when-let [anim (:current-animation @window/app-state)]
              ((:set-animation! preview) anim)
              (swap! window/app-state assoc :playing true))))))
    
    (router/on-trigger! ::stop :stop
      (fn [_event]
        (ss/invoke-later
          ((:stop! preview))
          (when grid ((:set-active-cell! grid) nil nil))
          (swap! window/app-state assoc :playing false :current-animation nil))))
    
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
  "Start the Laser Show application.
   
   If a window is already open, brings it to front.
   Otherwise creates a new window."
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
  ;; We need to wait briefly for the window to be created
  (future
    (Thread/sleep 200)
    (when-let [frame (window/get-frame)]
      (init-input-system! frame))))

(defn stop!
  "Stop the Laser Show application.
   Closes the window and cleans up resources."
  []
  (shutdown-input-system!)
  (window/close-window!))

(defn -main
  "Main entry point."
  [& _args]
  (start!))

;; ============================================================================
;; REPL Development Helpers
;; ============================================================================

(comment
  ;; Start the application
  (start!)
  
  ;; Stop the application (close window)
  (stop!)
  
  ;; Check if window is open
  (window/window-open?)
  
  ;; Bring window to front
  (window/bring-to-front!)
  
  ;; Get current state
  @window/app-state
  )
