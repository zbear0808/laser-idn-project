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


;; Application Lifecycle


(defn start!
  "Start the laser show application.
   
   Initializes state, creates renderer, and mounts the UI.
   Returns the app instance."
  []
  (println "Starting Laser Show application...")
  
  (let [initial-state (domains/build-initial-state)]
    (state/init-state! initial-state))
  
  (init-styles!)
  
  (frame-service/start-preview-updates! 30)
  
  (let [renderer (create-renderer)]
    (reset! *renderer renderer)
    (fx/mount-renderer (state/get-context-atom) renderer))
  
  (println "Laser Show application started.")
  @*renderer)




;; Main Entry Point

(defn -main
  "Main entry point for the application.
   
   Starts the GUI application."
  [& _args]
  (println "╔══════════════════════════════════════╗")
  (println "║       Laser Show Application         ║")
  (println "╚══════════════════════════════════════╝")

  (start!))


