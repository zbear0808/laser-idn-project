(ns user
  "Development utilities for hot reloading.
   
   This namespace provides REPL-driven development tools for the laser show application.
   Load this by starting a REPL with: clj -M:dev
   
   Usage:
     (start)    - Start the application (or bring existing window to front)
     (stop)     - Close the window and clean up resources
     (reload)   - Reload changed namespaces without restarting
     (restart)  - Stop, reload code, and restart the application
     (reset)    - Clear state, reload ALL code, and restart from scratch"
  (:require [clojure.tools.namespace.repl :as repl]
            [laser-show.core :as core]
            [laser-show.state.atoms :as state]
            [laser-show.ui.window :as window]))

(repl/set-refresh-dirs "src")

(defn start
  "Start the laser show application.
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
  ;; Lifecycle Commands:
  ;;   (start)          - Start the application (brings window to front if open)
  ;;   (stop)           - Close window and clean up (keeps REPL running)
  ;;   (reload)         - Reload changed code (keeps GUI open)
  ;;   (restart)        - Full restart (stop, reload, start)
  ;;   (reset)          - Reset state and restart from scratch
  ;;
  ;; Development Helpers:
  ;;   (current-state)  - Show current application state
  ;;
  ;; Window Management:
  ;;   Closing the window with X button keeps REPL running
  ;;   Run (start) again to re-open the window
  ;;
  ;; Hot Reload Workflow:
  ;;   1. Make changes to your code
  ;;   2. Save the file
  ;;   3. Run (reload) in the REPL
  ;;   4. Test your changes in the running application
  ;;
  ;; Tips:
  ;;   - Use (reload) for most changes (fast, preserves state)
  ;;   - Use (restart) if reload fails or for structural changes
  ;;   - Evaluate individual functions with Ctrl+Enter (even faster!)
  ;;   - Window persists across reloads - no more duplicate windows!
  
  (start)
  (reload)
  (stop)
  (restart)
  (reset)
  (current-state)
  (window/window-open?))
