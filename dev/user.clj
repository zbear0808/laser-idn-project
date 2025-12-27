(ns user
  "Development utilities for hot reloading.
   
   Usage:
     (start)    - Start the app with automatic hot reload watching
     (stop)     - Stop the app and file watcher
     (restart)  - Full restart (stop, reload all code, start fresh)"
  (:require [clojure.tools.namespace.repl :as repl]
            [hawk.core :as hawk]))

(repl/set-refresh-dirs "src")

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private !watcher (atom nil))
(defonce ^:private !app-started? (atom false))

;; ============================================================================
;; Safe Reload
;; ============================================================================

(defn- safe-reload
  "Reload changed namespaces with error handling.
   Returns true if successful, false if there was an error."
  []
  (println "\n🔄 Reloading...")
  (try
    (let [result (repl/refresh)]
      (if (instance? Throwable result)
        (do
          (println "❌ Reload failed:")
          (println (.getMessage ^Throwable result))
          (.printStackTrace ^Throwable result)
          false)
        (do
          (println "✅ Reloaded successfully")
          true)))
    (catch Exception e
      (println "❌ Compilation error:")
      (println (.getMessage e))
      (.printStackTrace e)
      false)))

;; ============================================================================
;; File Watcher
;; ============================================================================

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
                                        (safe-reload))
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
;; App Lifecycle
;; ============================================================================

(defn start
  "Start the application with automatic hot reload.
   
   This will:
   1. Start the cljfx application
   2. Begin watching src/ for file changes
   3. Automatically reload when .clj files are saved"
  []
  (if @!app-started?
    (println "⚠️  App already started. Use (restart) to restart.")
    (do
      (println "\n🚀 Starting Laser Show...")
      
      ;; Start the app using require + resolve to avoid alias issues
      (require 'laser-show.app)
      ((resolve 'laser-show.app/start!))
      
      (reset! !app-started? true)
      (start-watcher!)
      
      (println "\n✅ Ready! Save any .clj file to hot reload.")
      (println "   Use (stop) to stop, (restart) for full restart."))))

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
  "Full restart: stop everything, reload ALL code, start fresh.
   
   Use this when hot reload isn't enough (structural changes, 
   state corruption, etc.)"
  []
  (println "\n🔄 Full restart...")
  
  (stop)
  (Thread/sleep 500)
  
  ;; Clear all loaded state by refreshing everything
  (println "📦 Reloading all namespaces...")
  (try
    (repl/refresh-all)
    (catch Exception e
      (println "⚠️  Refresh error:" (.getMessage e))))
  
  (Thread/sleep 200)
  (start))

;; ============================================================================
;; REPL Quick Reference
;; ============================================================================

(comment
  ;; Quick Start
  (start)    ;; Start app + hot reload watcher
  (stop)     ;; Stop everything
  (restart)  ;; Full restart from scratch
  
  ;; Manual reload (if watcher isn't running)
  (safe-reload)
  )
