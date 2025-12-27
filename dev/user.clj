(ns user
  "Development utilities for hot reloading.
   
   This namespace provides REPL-driven development tools for the laser show application.
   Load this by starting a REPL with: clj -M:dev
   
   Usage:
     (start)    - Start the cljfx application
     (stop)     - Close the window and clean up resources
     (reload)   - Reload changed namespaces without restarting
     (restart)  - Stop, reload code, and restart the application
     (reset)    - Clear state, reload ALL code, and restart from scratch"
  (:require [clojure.tools.namespace.repl :as repl]
            [laser-show.app :as app]
            [laser-show.state.core :as state]
            [laser-show.state.atoms :as atoms]))

(repl/set-refresh-dirs "src")

;; ============================================================================
;; cljfx Application Hot Reloading
;; ============================================================================

(defn start
  "Start the cljfx application.
   This initializes the JavaFX application and shows the main window."
  []
  (app/start!)
  (println "✅ Laser Show application started."))

(defn stop
  "Stop the application and clean up resources.
   This closes the window but keeps the REPL running.
   You can call start again to reopen it."
  []
  (app/stop!)
  (println "✅ Application stopped."))

(defn reload
  "Hot reload without restarting.
   This reloads changed namespaces.
   Use this after making changes to views, components, or event handlers."
  []
  (println "Reloading changed namespaces...")
  (let [result (repl/refresh)]
    (if (instance? Throwable result)
      (do
        (println "❌ Reload failed:")
        (println result))
      (println "✅ Code reloaded successfully"))))

(defn restart
  "Full restart of the application.
   Stops the UI, reloads all changed code, then starts the UI again.
   Use this when hot reload doesn't work or for structural changes."
  []
  (println "Restarting application...")
  (stop)
  (Thread/sleep 500)
  (repl/refresh :after 'user/start))

(defn reset
  "Complete reset: stop UI, clear all state, reload ALL code, and restart.
   Warning: This will lose all your current application state!"
  []
  (println "Resetting everything...")
  (stop)
  (atoms/reset-all!)
  (Thread/sleep 500)
  (repl/refresh-all :after 'user/start))

(defn current-state
  "Show the current application state (useful for debugging).
   Returns the current state map."
  []
  (state/get-state))

(comment
  ;; === Laser Show Development REPL ===
  ;;
  ;; Quick Start:
  ;;   (start)          - Launch the application
  ;;   (reload)         - Hot reload your changes (keeps window open!)
  ;;   (stop)           - Close the window
  ;;
  ;; Full Lifecycle:
  ;;   (start)          - Start the application
  ;;   (stop)           - Stop and clean up
  ;;   (reload)         - Hot reload changed code (fast, preserves state)
  ;;   (restart)        - Full restart (stop, reload, start)
  ;;   (reset)          - Reset state and restart from scratch
  ;;
  ;; Hot Reload Workflow:
  ;;   1. Make changes to any file (views, components, events, etc.)
  ;;   2. Save the file
  ;;   3. Run (reload) in the REPL
  ;;   4. See your changes instantly in the running UI!
  ;;
  ;; Tips:
  ;;   - Use (reload) for most changes (instant feedback)
  ;;   - Changes to views, components, and styles hot reload perfectly
  ;;   - State is preserved across reloads
  ;;   - If something breaks, use (restart) or (reset)
  ;;   - You can also evaluate individual functions with Ctrl+Enter
  ;;
  ;; Development Examples:
  (start)
  (reload)
  (stop)
  (restart)
  (reset)
  
  ;; Debug helpers:
  (current-state))
