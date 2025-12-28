(ns user
  "Development utilities for REPL workflow.
   
   Usage:
     (start)           - Start the app
     (stop)            - Stop the app
     (watch-styles!)   - Enable CSS hot-reload (eval style def to update UI)
     (unwatch-styles!) - Disable CSS hot-reload")

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private !app-started? (atom false))

;; ============================================================================
;; Public API
;; ============================================================================

(defn start
  "Start the laser show application."
  []
  (if @!app-started?
    (println "⚠️  App already started. Use (stop) first to restart.")
    (do
      (println "\n🚀 Starting Laser Show...")
      
      ;; Load and start the app
      (require 'laser-show.app)
      ((resolve 'laser-show.app/start!))
      
      (reset! !app-started? true)
      
      (println "\n✅ Ready!")
      (println "   Use (watch-styles!) for CSS hot-reload"))))

(defn stop
  "Stop the application."
  []
  (println "\n🛑 Stopping...")
  
  (when @!app-started?
    (try
      (require 'laser-show.app)
      ((resolve 'laser-show.app/stop!))
      (catch Exception e
        (println "⚠️  Error stopping app:" (.getMessage e))))
    (reset! !app-started? false))
  
  (println "✅ Stopped."))

;; ============================================================================
;; CSS Hot-Reload Support
;; ============================================================================

(defonce ^:private !style-watches (atom #{}))

(def ^:private style-vars
  "Style vars to watch for hot-reload.
   Each entry maps a var symbol to its state key."
  [{:var-sym 'laser-show.css.menus/menu-theme
    :state-key :menu-theme}])

(defn watch-styles!
  "Enable CSS hot-reload. Re-evaluating style defs updates UI instantly.
   
   How it works:
   1. Watches the style var (e.g., laser-show.css.menus/menu-theme)
   2. When you eval the (def menu-theme ...) form, the var changes
   3. The watch callback updates the CSS URL in state
   4. cljfx sees the state change and re-renders with new styles
   
   Usage:
   1. Call (watch-styles!) after starting the app
   2. Edit src/laser_show/css/menus.clj
   3. Eval the (def menu-theme ...) form (e.g., Ctrl+Enter in Calva)
   4. UI updates instantly with new styles!
   
   Call (unwatch-styles!) when done iterating on styles."
  []
  (doseq [{:keys [var-sym state-key]} style-vars]
    (let [v (requiring-resolve var-sym)]
      (add-watch v ::style-reload
                 (fn [_ _ _ new-val]
                   (println "🎨 Style updated:" var-sym)
                   ((resolve 'laser-show.state.core/assoc-in-state!)
                    [:styles state-key]
                    (:cljfx.css/url new-val))))
      (swap! !style-watches conj v)))
  (println "👁️  Watching" (count style-vars) "style var(s) for hot-reload")
  (println "   Edit css/menus.clj and eval the def to update styles instantly!"))

(defn unwatch-styles!
  "Stop watching style vars for hot-reload."
  []
  (doseq [v @!style-watches]
    (remove-watch v ::style-reload))
  (reset! !style-watches #{})
  (println "👁️  Stopped watching styles"))

;; ============================================================================
;; REPL Quick Reference
;; ============================================================================

(comment
  ;; App lifecycle
  (start)  ;; Start the app
  (stop)   ;; Stop the app
  
  ;; CSS Hot-Reload
  (watch-styles!)    ;; Enable style watching
  (unwatch-styles!)  ;; Disable style watching
  ;; Then edit css/menus.clj and eval (def menu-theme ...) to see instant updates!
  )
