(ns laser-show.app
  "Main application entry point for the laser show GUI.
   
   This module:
   - Initializes the state system
   - Creates the cljfx application using fx/create-app
   - Provides start/stop functions for the app lifecycle
   
   Usage:
   (start!)  - Start the application
   (stop!)   - Stop the application
   (-main)   - Main entry point"
  (:require [cljfx.api :as fx]
            [cljfx.css :as css]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.events.core :as events]
            [laser-show.views.root :as root]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.css.title-bar :as menus])
  (:gen-class))

(defonce ^{:private true :doc "The cljfx application instance."} *app (atom nil))

(defonce ^{:private true :doc "The cljfx renderer instance."} *renderer (atom nil))

(defn- create-renderer
  "Create the cljfx renderer with proper middleware."
  []
  (fx/create-renderer
    :middleware (comp
                  ;; Pass context to every component
                  fx/wrap-context-desc
                  ;; Map from context to root view
                  (fx/wrap-map-desc (fn [_] {:fx/type root/root-view})))
    :opts {:fx.opt/type->lifecycle
           #(or (fx/keyword->lifecycle %)
                (fx/fn->lifecycle-with-context %))
           :fx.opt/map-event-handler
           events/event-handler}))


(defn init-styles!
  "Initialize CSS style URLs in state.
   
   This sets up the cljfx-css registered styles in the application state,
   enabling hot-reload support. The style URLs are stored in state so that
   when a var watch detects a change, updating state triggers a re-render."
  []
  (state/assoc-in-state! [:styles :menu-theme] (::css/url menus/menu-theme))
  (println "✨ CSS styles initialized"))

;; ============================================================================
;; Application Lifecycle
;; ============================================================================

(defn start!
  "Start the laser show application.
   
   Initializes state, creates renderer, and mounts the UI.
   Returns the app instance."
  []
  (println "Starting Laser Show application...")
  
  ;; Initialize state with domains
  (let [initial-state (domains/build-initial-state)]
    (state/init-state! initial-state))
  
  ;; Initialize CSS styles in state for hot-reload support
  (init-styles!)
  
  ;; Start preview frame updates (30 FPS)
  (frame-service/start-preview-updates! 30)
  
  ;; Create and mount renderer
  (let [renderer (create-renderer)]
    (reset! *renderer renderer)
    (fx/mount-renderer (state/get-context-atom) renderer))
  
  (println "Laser Show application started.")
  @*renderer)

(defn stop!
  "Stop the laser show application.
   
   Unmounts the renderer and shuts down state."
  []
  (println "Stopping Laser Show application...")
  
  ;; Stop preview updates
  (frame-service/stop-preview-updates!)
  
  (when-let [renderer @*renderer]
    (fx/unmount-renderer (state/get-context-atom) renderer)
    (reset! *renderer nil))
  
  (state/shutdown!)
  
  (println "Laser Show application stopped."))

(defn restart!
  "Restart the application. Useful for development."
  []
  (stop!)
  (Thread/sleep 500)
  (start!))

;; ============================================================================
;; Alternative: Using fx/create-app
;; ============================================================================

(defn create-app!
  "Create the application using fx/create-app helper.
   
   This is an alternative to the renderer approach that handles
   more wiring automatically."
  []
  (println "Creating Laser Show app with fx/create-app...")
  
  ;; Initialize state
  (let [initial-state (domains/build-initial-state)]
    (state/init-state! initial-state))
  
  ;; Create app - this handles renderer setup automatically
  (let [app (fx/create-app (state/get-context-atom)
                           :event-handler events/event-handler
                           :desc-fn (fn [_] {:fx/type root/root-view}))]
    (reset! *app app)
    (println "Laser Show app created.")
    app))

(defn stop-app!
  "Stop the fx/create-app based application."
  []
  (when-let [app @*app]
    ;; fx/create-app returns a function that can be called to unmount
    ;; but we need to handle this properly
    (reset! *app nil)
    (state/shutdown!)))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn -main
  "Main entry point for the application.
   
   Starts the GUI application."
  [& _args]
  (println "")
  (println "╔══════════════════════════════════════╗")
  (println "║       Laser Show Application         ║")
  (println "╚══════════════════════════════════════╝")
  (println "")
  
  ;; Use the renderer approach (more control)
  (start!)
  
  ;; Keep main thread alive
  ;; JavaFX runs on its own thread
  )

;; ============================================================================
;; REPL Development Helpers
;; ============================================================================

(comment
  ;; Start the app
  (start!)
  
  ;; Stop the app
  (stop!)
  
  ;; Restart the app
  (restart!)
  
  ;; Check state
  (state/debug-state)
  
  ;; Dispatch test events
  (events/dispatch! {:event/type :grid/trigger-cell :col 0 :row 0})
  (events/dispatch! {:event/type :timing/set-bpm :bpm 140.0})
  (events/dispatch! {:event/type :transport/stop})
  
  ;; Check specific state
  (state/get-in-state [:timing :bpm])
  (state/get-in-state [:playback :active-cell])
  )
