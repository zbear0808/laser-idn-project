(ns user
  "Development utilities for auto-restart on file save.
   
   Usage:
     (start)    - Start the app with auto-restart watching
     (stop)     - Stop the app and file watcher
     (restart)  - Manual restart (stop, reload code, start)"
  (:require [hawk.core :as hawk]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private !watcher (atom nil))
(defonce ^:private !app-started? (atom false))
(defonce ^:private !restart-pending? (atom false))

;; ============================================================================
;; Code Reloading
;; ============================================================================

(defn- reload-app-namespace!
  "Reload the main app namespace and its dependencies.
   Uses :reload to pick up changes without JVM restart."
  []
  (try
    ;; Reload the root view namespace (pulls in most UI changes)
    (require 'laser-show.views.root :reload)
    ;; Reload the main app namespace
    (require 'laser-show.app :reload)
    true
    (catch Exception e
      (println "❌ Reload failed:")
      (println (.getMessage e))
      false)))

;; ============================================================================
;; UI Restart (not JVM restart)
;; ============================================================================

(defn- do-restart!
  "Stop UI, reload code, restart UI. Preserves state, skips JVM startup."
  []
  (println "\n🔄 Restarting UI...")
  (try
    ;; Stop the UI
    (when @!app-started?
      (when-let [stop-fn (resolve 'laser-show.app/stop!)]
        (stop-fn))
      (reset! !app-started? false))
    
    (Thread/sleep 200)
    
    ;; Reload code
    (println "📦 Reloading code...")
    (when (reload-app-namespace!)
      ;; Start the UI again
      (when-let [start-fn (resolve 'laser-show.app/start!)]
        (start-fn)
        (reset! !app-started? true)
        (println "✅ UI restarted!")))
    
    (catch Exception e
      (println "❌ Restart failed:" (.getMessage e)))))

;; ============================================================================
;; File Watcher
;; ============================================================================

(defn- schedule-restart!
  "Schedule a restart to happen on the main thread.
   We use an agent to avoid the *ns* binding issue in hawk's thread."
  []
  (when (compare-and-set! !restart-pending? false true)
    ;; Use a future to run on a different thread that can do the restart
    (future
      (Thread/sleep 100) ; debounce rapid saves
      (reset! !restart-pending? false)
      (do-restart!))))

(defn- start-watcher!
  "Start watching src/ for .clj file changes."
  []
  (when-not @!watcher
    (reset! !watcher
            (hawk/watch! [{:paths ["src"]
                           :filter hawk/file?
                           :handler (fn [ctx {:keys [kind file]}]
                                      (when (and (#{:modify :create} kind)
                                                 (.endsWith (.getName ^java.io.File file) ".clj"))
                                        (println "\n📝 Changed:" (.getName ^java.io.File file))
                                        (schedule-restart!))
                                      ctx)}]))
    (println "👁️  Watching src/ for changes...")))

(defn- stop-watcher!
  "Stop the file watcher."
  []
  (when-let [w @!watcher]
    (hawk/stop! w)
    (reset! !watcher nil)
    (println "👁️  Stopped watching.")))

;; ============================================================================
;; Public API
;; ============================================================================

(defn start
  "Start the application with auto-restart on file changes.
   
   When you save a .clj file:
   1. UI window closes briefly
   2. Code is reloaded  
   3. UI reopens with new code
   
   State is preserved (atoms are not reset)."
  []
  (if @!app-started?
    (println "⚠️  App already started. Use (restart) to restart.")
    (do
      (println "\n🚀 Starting Laser Show...")
      
      ;; Load and start the app
      (require 'laser-show.app)
      ((resolve 'laser-show.app/start!))
      
      (reset! !app-started? true)
      (start-watcher!)
      
      (println "\n✅ Ready! Save any .clj file to auto-restart UI.")
      (println "   Use (stop) to stop, (restart) for manual restart."))))

(defn stop
  "Stop the application and file watcher."
  []
  (println "\n🛑 Stopping...")
  
  (stop-watcher!)
  
  (when @!app-started?
    (try
      (require 'laser-show.app)
      ((resolve 'laser-show.app/stop!))
      (catch Exception e
        (println "⚠️  Error stopping app:" (.getMessage e))))
    (reset! !app-started? false))
  
  (println "✅ Stopped."))

(defn restart
  "Manual restart: stop UI, reload code, start UI.
   
   Use this from the REPL. The file watcher does this automatically."
  []
  (do-restart!))

;; ============================================================================
;; REPL Quick Reference
;; ============================================================================

(comment
  ;; Quick Start
  (start)    ;; Start app + auto-restart watcher
  (stop)     ;; Stop everything
  (restart)  ;; Manual restart
  )
