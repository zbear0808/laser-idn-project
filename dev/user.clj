(ns user
  "Development utilities for hot reloading.
   
   This namespace provides REPL-driven development tools for the laser show application.
   Load this by starting a REPL with: clj -M:dev
   
   Usage:
     (start)    - Start the application (or bring existing window to front)
     (stop)     - Close the window and clean up resources
     (reload)   - Reload changed namespaces without restarting
     (restart)  - Stop, reload code, and restart the application
     (reset)    - Clear state, reload ALL code, and restart from scratch
     
   cljfx UI:
     (fx-start)   - Start the cljfx UI
     (fx-stop)    - Stop the cljfx UI
     (fx-reload)  - Hot reload cljfx UI without restart
     (fx-restart) - Stop, reload all code, and restart cljfx UI"
  (:require [clojure.tools.namespace.repl :as repl]
            [laser-show.core :as core]
            [laser-show.state.atoms :as state]
            [laser-show.ui.window :as window]
            [laser-show.ui-fx.app :as fx-app]))

(repl/set-refresh-dirs "src")

;; ============================================================================
;; cljfx UI Hot Reloading
;; ============================================================================

(defn fx-start
  "Start the cljfx UI.
   This initializes the JavaFX application and shows the main window.
   Safe to call multiple times - will reuse existing window."
  []
  (fx-app/dev-start!)
  (println "cljfx UI started. Window should be visible."))

(defn fx-stop
  "Stop the cljfx UI and clean up resources.
   This closes the window but keeps the REPL running.
   You can call fx-start again to reopen it."
  []
  (fx-app/dev-stop!)
  (println "cljfx UI stopped."))

(defn fx-reload
  "Hot reload the cljfx UI without restarting.
   This reloads changed namespaces and refreshes the UI.
   The window stays open and state is preserved.
   Use this after making changes to views, components, or event handlers."
  []
  (println "Reloading changed namespaces...")
  (let [result (repl/refresh)]
    (if (instance? Throwable result)
      (do
        (println "❌ Reload failed:")
        (println result))
      (do
        (println "✅ Code reloaded successfully")
        (fx-app/refresh!)
        (println "✅ UI refreshed")))))

(defn fx-restart
  "Full restart of the cljfx UI.
   Stops the UI, reloads all changed code, then starts the UI again.
   Use this when hot reload doesn't work or for structural changes."
  []
  (println "Restarting cljfx UI...")
  (fx-stop)
  (Thread/sleep 500)
  (repl/refresh :after 'user/fx-start))

(defn fx-reset
  "Complete reset: stop UI, clear all state, reload ALL code, and restart.
   Warning: This will lose all your current application state!"
  []
  (println "Resetting everything...")
  (fx-stop)
  (state/reset-all!)
  (Thread/sleep 500)
  (repl/refresh-all :after 'user/fx-start))

;; ============================================================================
;; Swing UI (Legacy)
;; ============================================================================

(defn start
  "Start the laser show application (Swing UI).
   If a window is already open, brings it to front instead of creating a new one."
  []
  (core/start!))

(defn stop
  "Stop the application and clean up resources.
   This closes the main window but keeps the REPL running."
  []
  (core/stop!))

(defn reload
  "Reload changed namespaces without restarting the application.
   This preserves application state (the GUI window stays open).
   Use this after making code changes to functions or definitions."
  []
  (repl/refresh))

(defn restart
  "Full restart: stop the application, reload all code, and start again.
   Use this when reload alone doesn't work (e.g., after changing requires)."
  []
  (stop)
  (Thread/sleep 500)
  (repl/refresh :after 'user/start))

(defn reset
  "Reset everything: clear all state, reload ALL code, and restart from scratch.
   Warning: This will lose all your current application state!"
  []
  (stop)
  (state/reset-all!)
  (window/clean-up!)
  (Thread/sleep 500)
  (repl/refresh-all :after 'user/start))

(defn current-state
  "Show the current application state (useful for debugging).
   Returns a map of all state atoms."
  []
  {:timing @state/!timing
   :playback @state/!playback
   :grid @state/!grid
   :idn @state/!idn
   :effects @state/!effects
   :ui @state/!ui
   :project @state/!project})

(comment
  ;; === Laser Show Development REPL ===
  ;;
  ;; === cljfx UI (Recommended) ===
  ;;
  ;; Quick Start:
  ;;   (fx-start)       - Launch the cljfx UI
  ;;   (fx-reload)      - Hot reload your changes (keeps window open!)
  ;;   (fx-stop)        - Close the window
  ;;
  ;; Full Lifecycle:
  ;;   (fx-start)       - Start the cljfx UI
  ;;   (fx-stop)        - Stop and clean up
  ;;   (fx-reload)      - Hot reload changed code (fast, preserves state)
  ;;   (fx-restart)     - Full restart (stop, reload, start)
  ;;   (fx-reset)       - Reset state and restart from scratch
  ;;
  ;; Hot Reload Workflow:
  ;;   1. Make changes to any file (views, components, events, etc.)
  ;;   2. Save the file
  ;;   3. Run (fx-reload) in the REPL
  ;;   4. See your changes instantly in the running UI!
  ;;
  ;; Tips:
  ;;   - Use (fx-reload) for most changes (instant feedback)
  ;;   - Changes to views, components, and styles hot reload perfectly
  ;;   - State is preserved across reloads
  ;;   - If something breaks, use (fx-restart) or (fx-reset)
  ;;   - You can also evaluate individual functions with Ctrl+Enter
  ;;
  ;; === Swing UI (Legacy) ===
  ;;
  ;; Lifecycle Commands:
  ;;   (start)          - Start the Swing UI
  ;;   (stop)           - Close window and clean up
  ;;   (reload)         - Reload changed code
  ;;   (restart)        - Full restart (stop, reload, start)
  ;;   (reset)          - Reset state and restart from scratch
  ;;
  ;; Development Helpers:
  ;;   (current-state)  - Show current application state
  
  ;; Try the cljfx UI:
  (fx-start)
  (fx-reload)
  (fx-stop)
  (fx-restart)
  (fx-reset)
  
  ;; Legacy Swing UI:
  (start)
  (reload)
  (stop)
  (restart)
  (reset)
  
  ;; Debug helpers:
  (current-state)
  (window/window-open?))
