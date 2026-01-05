(ns laser-show.app
  "Main application entry point for the laser show GUI.
   
   This module:
   - Initializes the state system
   - Creates the cljfx application using fx/create-app
   - Provides start/stop functions for the app lifecycle
   - Integrates cljfx dev tools in development mode
   
   Usage:
   (start!)  - Start the application
   (stop!)   - Stop the application
   (-main)   - Main entry point"
  (:require [cljfx.api :as fx]
            [cljfx.css :as css]
            [clojure.tools.logging :as log]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.events.core :as events]
            [laser-show.views.root :as root]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.css.title-bar :as menus]
            [laser-show.dev-config :as dev-config])
  (:gen-class))

(defonce ^{:private true :doc "The cljfx renderer instance."} *renderer (atom nil))

(defn- error-handler
  "Error handler for cljfx renderer.
   
   Logs errors and prints stack trace. In dev mode, cljfx.dev provides
   enhanced error messages with component stacks."
  [^Throwable ex]
  (log/error ex "Error in cljfx renderer:")
  (.printStackTrace ex *err*))

(defn- get-type->lifecycle
  "Returns the type->lifecycle function based on dev mode.
   
   In dev mode: Uses cljfx.dev/type->lifecycle for validation and better errors
                on keyword types only. Function components use standard context-aware
                lifecycle to ensure proper context propagation.
   In prod mode: Uses standard cljfx lifecycle lookup"
  []
  (if (dev-config/dev-mode?)
    ;; Dev mode: Use dev validation for keywords, standard lifecycle for functions
    (let [dev-lifecycle @(requiring-resolve 'cljfx.dev/type->lifecycle)]
      (log/info "🔧 Dev mode enabled - cljfx dev tools active (Press F12 for inspector)")
      (fn [type]
        (if (keyword? type)
          ;; Use dev validation for keyword types (JavaFX components)
          (dev-lifecycle type)
          ;; Use standard context-aware lifecycle for function components
          ;; This ensures fx/context is properly passed through
          (fx/fn->lifecycle-with-context type))))
    ;; Prod mode: Use standard lifecycle
    #(or (fx/keyword->lifecycle %)
         (fx/fn->lifecycle-with-context %))))

(defn- create-renderer
  "Create the cljfx renderer with proper middleware."
  []
  (fx/create-renderer
    :middleware (comp
                  ;; Pass context to every component
                  fx/wrap-context-desc
                  ;; Map from context to root view
                  (fx/wrap-map-desc (fn [_] {:fx/type root/root-view})))
    :opts {:fx.opt/type->lifecycle (get-type->lifecycle)
           :fx.opt/map-event-handler events/event-handler}
    :error-handler error-handler))


(defn init-styles!
  "Initialize CSS style URLs in state.
   
   This sets up the cljfx-css registered styles in the application state,
   enabling hot-reload support. The style URLs are stored in state so that
   when a var watch detects a change, updating state triggers a re-render."
  []
  (state/assoc-in-state! [:styles :menu-theme] (::css/url menus/menu-theme))
  (log/debug "CSS styles initialized"))


;; Application Lifecycle


(defn start!
  "Start the laser show application.
   
   Initializes state, creates renderer, and mounts the UI.
   Auto-scans for IDN devices on startup.
   Returns the app instance."
  []
  (log/info "Starting Laser Show application...")
  
  (let [initial-state (domains/build-initial-state)]
    (state/init-state! initial-state))
  
  (init-styles!)
  
  (frame-service/start-preview-updates! 30)
  
  (let [renderer (create-renderer)]
    (reset! *renderer renderer)
    (fx/mount-renderer (state/get-context-atom) renderer))
  
  ;; Auto-scan for IDN devices on startup
  (log/info "Starting automatic device discovery...")
  (events/dispatch! {:event/type :projectors/scan-network})
  
  (log/info "Laser Show application started.")
  @*renderer)




;; Main Entry Point

(defn -main
  "Main entry point for the application.
   
   Starts the GUI application."
  [& _args]
  (log/info "╔══════════════════════════════════════╗")
  (log/info "║       Laser Show Application         ║")
  (log/info "╚══════════════════════════════════════╝")

  (start!))
