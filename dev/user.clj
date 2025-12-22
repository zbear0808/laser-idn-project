(ns user
  "Development utilities for hot reloading.
   
   This namespace provides REPL-driven development tools for the laser show application.
   Load this by starting a REPL with: clj -M:dev
   
   Usage:
     (start)    - Start the application
     (stop)     - Stop the application
     (reload)   - Reload changed namespaces without restarting
     (restart)  - Stop, reload code, and restart the application
     (reset)    - Clear state and restart from scratch"
  (:require [clojure.tools.namespace.repl :as repl]
            [laser-show.core :as core]))

;; Configure tools.namespace to only track src directory
(repl/set-refresh-dirs "src")

;; ============================================================================
;; Lifecycle Functions
;; ============================================================================

(defn start
  "Start the laser show application.
   The application state persists across reloads thanks to defonce."
  []
  (println "Starting laser show application...")
  (core/start!)
  (println "✓ Application started"))

(defn stop
  "Stop the application and clean up resources.
   This closes the main window if it's open."
  []
  (println "Stopping application...")
  (when-let [app-state (resolve 'laser-show.core/app-state)]
    (when-let [frame (:main-frame @@app-state)]
      (try
        (.dispose frame)
        (println "✓ Main window closed")
        (catch Exception e
          (println "Note: Window may already be closed")))))
  (println "✓ Application stopped"))

(defn reload
  "Reload changed namespaces without restarting the application.
   This preserves application state (the GUI window stays open).
   Use this after making code changes to functions or definitions."
  []
  (println "Reloading changed namespaces...")
  (try
    (repl/refresh)
    (println "✓ Code reloaded successfully")
    :ok
    (catch Exception e
      (println "✗ Reload failed:")
      (println (.getMessage e))
      :error)))

(defn restart
  "Full restart: stop the application, reload all code, and start again.
   Use this when reload alone doesn't work (e.g., after changing requires)."
  []
  (println "Restarting application...")
  (stop)
  (Thread/sleep 500) ; Give the GUI time to shut down
  (repl/refresh :after 'user/start))

(defn reset
  "Reset everything: clear all state and restart from scratch.
   Warning: This will lose all your current application state!"
  []
  (println "Resetting application (clearing state)...")
  (stop)
  ;; Clear the app-state atom
  (when-let [app-state (resolve 'laser-show.core/app-state)]
    (reset! @app-state {}))
  (Thread/sleep 500)
  (repl/refresh :after 'user/start))

;; ============================================================================
;; Development Helpers
;; ============================================================================

(defn current-state
  "Show the current application state (useful for debugging)."
  []
  (when-let [app-state (resolve 'laser-show.core/app-state)]
    (let [state @@app-state]
      (println "Application State:")
      (println "  Playing:" (:playing state))
      (println "  IDN Connected:" (:idn-connected state))
      (println "  IDN Target:" (:idn-target state))
      (println "  Current Animation:" (if (:current-animation state) "Yes" "No"))
      (println "  Main Frame:" (if (:main-frame state) "Open" "Closed"))
      state)))

(defn help
  "Display help information for development commands."
  []
  (println "\n=== Laser Show Development REPL ===")
  (println "\nLifecycle Commands:")
  (println "  (start)          - Start the application")
  (println "  (stop)           - Stop the application")
  (println "  (reload)         - Reload changed code (keeps GUI open)")
  (println "  (restart)        - Full restart (stop, reload, start)")
  (println "  (reset)          - Reset state and restart from scratch")
  (println "\nDevelopment Helpers:")
  (println "  (current-state)  - Show current application state")
  (println "  (help)           - Show this help message")
  (println "\nHot Reload Workflow:")
  (println "  1. Make changes to your code")
  (println "  2. Save the file")
  (println "  3. Run (reload) in the REPL")
  (println "  4. Test your changes in the running application")
  (println "\nTips:")
  (println "  - Use (reload) for most changes (fast, preserves state)")
  (println "  - Use (restart) if reload fails or for structural changes")
  (println "  - Evaluate individual functions with Ctrl+Enter (even faster!)")
  (println "  - The GUI window persists across reloads thanks to defonce")
  (println))

;; ============================================================================
;; Auto-print help on load
;; ============================================================================

(println "\n╔═══════════════════════════════════════════════════════════╗")
(println "║   Laser Show Development REPL Ready                      ║")
(println "╚═══════════════════════════════════════════════════════════╝")
(println)
(println "Quick Start:")
(println "  (start)   - Start the laser show application")
(println "  (help)    - Show all available commands")
(println)



(comment
  ;; Example usage of development commands:
  
  ;; Start the application
  (start)
  
  ;; Make code changes, then reload
  (reload)
  
  ;; Restart the application
  (restart)
  
  ;; Reset application state
  (reset)
  
  ;; Check current state
  (current-state)
  
  ;; Show help
  (help)
  )