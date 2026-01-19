(ns user
  "Development utilities for REPL workflow.
   
   Usage:
     (start)           - Start the app
     (stop)            - Stop the app
     (watch-styles!)   - Enable CSS hot-reload (eval style def to update UI)
     (unwatch-styles!) - Disable CSS hot-reload
     
   Dev Tools (requires :dev alias):
     (help)            - List all cljfx types
     (help :label)     - Show props for a specific type
     (help :label :text) - Show details for a specific prop
     (help-ui)         - Open interactive reference browser
     (explain-desc desc) - Validate a cljfx description
     
   Profiling (clj-async-profiler - CPU/allocation flamegraphs):
      (profile-cpu 10)  - Profile CPU for 10 seconds
      (profile-alloc 10) - Profile allocations for 10 seconds
      (profile-section! #(...)) - Profile a code section
      (view-flamegraph) - Open latest flamegraph
      (profiler-ui 8080) - Start profiler web UI
      
   JFR Profiling (low-overhead continuous profiling):
      (jfr-start)       - Start continuous JFR recording
      (jfr-stop)        - Stop JFR recording
      (jfr-dump)        - Dump recording to file
      (jfr-spikes 5000) - Alert on frames >5ms
      (jfr-auto-dump 10000) - Auto-dump on frames >10ms
      (jfr-status)      - Show JFR status")


;; State


(defonce ^:private !app-started? (atom false))


;; Public API


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


;; CSS Hot-Reload Support


(defonce ^:private !style-watches (atom #{}))

(def ^:private style-vars
  "Style vars to watch for hot-reload.
   Each entry is a var symbol for a CSS module."
  '[laser-show.css.theme/theme
    laser-show.css.buttons/buttons
    laser-show.css.forms/forms
    laser-show.css.grid-cells/grid-cells
    laser-show.css.layout/layout
    laser-show.css.title-bar/menu-theme
    laser-show.css.cue-chain-editor/cue-chain-editor
    laser-show.css.list/list])

(defn watch-styles!
  "Enable CSS hot-reload. Re-evaluating style defs updates UI instantly.
   
   How it works:
   1. Watches CSS module vars (e.g., laser-show.css.theme/theme)
   2. When you eval a (def theme ...) form, the var changes
   3. The watch callback triggers a dummy state update
   4. cljfx sees the state change and re-renders, picking up new CSS URLs
   
   Usage:
   1. Call (watch-styles!) after starting the app with (start)
   2. Edit any CSS file (e.g., src/laser_show/css/theme.clj)
   3. Eval the (def theme ...) form (e.g., Ctrl+Enter in Calva)
   4. UI updates instantly with new styles!
   
   Call (unwatch-styles!) when done iterating on styles."
  []
  (when-not @!app-started?
    (println "⚠️  App not started. Call (start) first.")
    (throw (ex-info "App must be started before watching styles" {})))
  
  (doseq [var-sym style-vars]
    (if-let [v (try (requiring-resolve var-sym) (catch Exception _ nil))]
      (do
        (add-watch v ::style-reload
                   (fn [_ _ old-val new-val]
                     (when (not= old-val new-val)
                       (let [url (:cljfx.css/url new-val)]
                         (if url
                           (do
                             (println "🎨 Style updated:" var-sym)
                             ;; Trigger a dummy state update to force re-render
                             ;; The actual CSS URLs are computed in subs/stylesheet-urls
                             ((resolve 'laser-show.state.core/update-in-state!)
                              [:styles :reload-trigger]
                              (fnil inc 0)))
                           (println "⚠️  Warning: No :cljfx.css/url in" var-sym))))))
        (swap! !style-watches conj v))
      (println "⚠️  Warning: Could not resolve" var-sym)))
  
  (println "👁️  Watching" (count @!style-watches) "style var(s) for hot-reload")
  (println "   Edit any CSS file and eval the (def ...) form to update styles instantly!"))

(defn unwatch-styles!
  "Stop watching style vars for hot-reload."
  []
  (doseq [v @!style-watches]
    (remove-watch v ::style-reload))
  (reset! !style-watches #{})
  (println "👁️  Stopped watching styles"))


;; cljfx Dev Tools
;; These functions provide convenient access to cljfx.dev utilities
;; They require the :dev alias to be active


(defn help
  "Look up cljfx types and props documentation.
   
   Usage:
     (help)            - List all available types
     (help :label)     - Show props for a specific type
     (help :label :text) - Show details for a specific prop
   
   Note: Requires the :dev alias (cljfx.dev dependency)."
  ([]
   (if-let [help-fn (try (requiring-resolve 'cljfx.dev/help) (catch Exception _ nil))]
     (help-fn)
     (println "⚠️  cljfx.dev not available. Start REPL with: clj -M:dev")))
  ([type-kw]
   (if-let [help-fn (try (requiring-resolve 'cljfx.dev/help) (catch Exception _ nil))]
     (help-fn type-kw)
     (println "⚠️  cljfx.dev not available. Start REPL with: clj -M:dev")))
  ([type-kw prop-kw]
   (if-let [help-fn (try (requiring-resolve 'cljfx.dev/help) (catch Exception _ nil))]
     (help-fn type-kw prop-kw)
     (println "⚠️  cljfx.dev not available. Start REPL with: clj -M:dev"))))

(defn help-ui
  "Open an interactive reference browser window.
   
   Shows a searchable UI with all cljfx types and their props.
   Great for discovering available components and options.
   
   Note: Requires the :dev alias (cljfx.dev dependency)."
  []
  (if-let [help-ui-fn (try (requiring-resolve 'cljfx.dev/help-ui) (catch Exception _ nil))]
    (help-ui-fn)
    (println "⚠️  cljfx.dev not available. Start REPL with: clj -M:dev")))

(defn explain-desc
  "Validate a cljfx description and explain any errors.
   
   Usage:
     (explain-desc {:fx/type :label :text 123})
     ;; => Shows error: 123 is not a string
     
     (explain-desc {:fx/type :label :text \"Hello\"})
     ;; => Success!
   
   Note: Requires the :dev alias (cljfx.dev dependency)."
  [desc]
  (if-let [explain-fn (try (requiring-resolve 'cljfx.dev/explain-desc) (catch Exception _ nil))]
    (explain-fn desc)
    (println "⚠️  cljfx.dev not available. Start REPL with: clj -M:dev")))


;; Profiling Support


(defn profile-cpu
  "Profile CPU usage for the specified duration.
   
   Parameters:
   - duration-sec: How long to profile (in seconds)
   
   Example:
     (profile-cpu 30)  ; Profile for 30 seconds"
  [duration-sec]
  (require 'laser-show.profiling.async-profiler)
  ((resolve 'laser-show.profiling.async-profiler/profile-cpu!) duration-sec))

(defn profile-alloc
  "Profile memory allocations for the specified duration.
   
   Parameters:
   - duration-sec: How long to profile (in seconds)
   
   Example:
     (profile-alloc 30)  ; Profile allocations for 30 seconds"
  [duration-sec]
  (require 'laser-show.profiling.async-profiler)
  ((resolve 'laser-show.profiling.async-profiler/profile-alloc!) duration-sec))

(defn profile-section!
  "Profile a specific section of code (CPU profiling).
   
   Parameters:
   - f: A zero-argument function to profile
   
   Example:
     (profile-section! #(dotimes [i 1000] (expensive-fn i)))"
  [f]
  (require 'laser-show.profiling.async-profiler)
  ((resolve 'laser-show.profiling.async-profiler/profile-section!) f))

(defn view-flamegraph
  "Open the most recent flamegraph in the browser."
  []
  (require 'laser-show.profiling.async-profiler)
  ((resolve 'laser-show.profiling.async-profiler/view-latest!)))

(defn profiler-ui
  "Start the profiler web UI for browsing all profiles.
   
   Parameters:
   - port: Port number to serve on (default: 8080)
   
   Example:
     (profiler-ui 8080)"
  ([]
   (profiler-ui 8080))
  ([port]
   (require 'laser-show.profiling.async-profiler)
   ((resolve 'laser-show.profiling.async-profiler/serve-ui!) port)))

(defn profiler-status
  "Print the current profiler status."
  []
  (require 'laser-show.profiling.async-profiler)
  ((resolve 'laser-show.profiling.async-profiler/print-status)))


;; JFR (Java Flight Recorder) Profiling
;; Low-overhead continuous profiling for identifying frame spikes


(defn jfr-start
  "Start continuous JFR recording for frame profiling.
   
   JFR runs with <1% overhead, suitable for continuous use.
   Records custom frame events and correlates with GC/JIT activity.
   
   Options:
   - :max-age - How long to keep events (default \"5m\")
   - :max-size - Maximum recording size (default \"100m\")
   - :settings - :default, :profile, or path to .jfc file
   
   Example:
     (jfr-start)
     (jfr-start {:max-age \"10m\" :settings :profile})"
  ([]
   (require 'laser-show.profiling.jfr-profiler)
   ((resolve 'laser-show.profiling.jfr-profiler/start-recording!)))
  ([opts]
   (require 'laser-show.profiling.jfr-profiler)
   ((resolve 'laser-show.profiling.jfr-profiler/start-recording!) opts)))

(defn jfr-stop
  "Stop JFR recording."
  []
  (require 'laser-show.profiling.jfr-profiler)
  ((resolve 'laser-show.profiling.jfr-profiler/stop-recording!)))

(defn jfr-dump
  "Dump current JFR recording to file.
   Recording continues after dump.
   
   Example:
     (jfr-dump)
     (jfr-dump \"spike-investigation.jfr\")"
  ([]
   (require 'laser-show.profiling.jfr-profiler)
   ((resolve 'laser-show.profiling.jfr-profiler/dump-recording!)))
  ([filename]
   (require 'laser-show.profiling.jfr-profiler)
   ((resolve 'laser-show.profiling.jfr-profiler/dump-recording!) filename)))

(defn jfr-spikes
  "Start real-time spike detection (threshold in microseconds).
   Alerts when frame generation exceeds threshold.
   
   Example:
     (jfr-spikes 5000)   ; Alert on frames >5ms
     (jfr-spikes 16000)  ; Alert on frames >16ms (60 FPS budget)"
  [threshold-us]
  (require 'laser-show.profiling.jfr-profiler)
  ((resolve 'laser-show.profiling.jfr-profiler/start-spike-detection!) threshold-us))


(defn jfr-auto-dump
  "Auto-dump recording on spikes.
   Automatically saves JFR when frame exceeds threshold.
   
   Parameters:
   - threshold-us: Microseconds threshold for auto-dump
   - cooldown-sec: (optional) Minimum seconds between dumps (default 30)
   
   Example:
     (jfr-start)            ; Must start recording first
     (jfr-auto-dump 10000)  ; Dump on frames >10ms"
  ([threshold-us]
   (require 'laser-show.profiling.jfr-profiler)
   ((resolve 'laser-show.profiling.jfr-profiler/spike-auto-dump!) threshold-us))
  ([threshold-us cooldown-sec]
   (require 'laser-show.profiling.jfr-profiler)
   ((resolve 'laser-show.profiling.jfr-profiler/spike-auto-dump!) threshold-us cooldown-sec)))

(defn jfr-status
  "Print JFR profiler status."
  []
  (require 'laser-show.profiling.jfr-profiler)
  ((resolve 'laser-show.profiling.jfr-profiler/print-status)))

(defn jfr-recordings
  "List all JFR recordings in the output directory."
  []
  (require 'laser-show.profiling.jfr-profiler)
  ((resolve 'laser-show.profiling.jfr-profiler/print-recordings)))


;; REPL Quick Reference


(comment
  ;; App lifecycle
  (start)  ;; Start the app
  (stop)   ;; Stop the app
  
  ;; CSS Hot-Reload
  (watch-styles!)    ;; Enable style watching
  (unwatch-styles!)  ;; Disable style watching
  ;; Then edit css/menus.clj and eval (def menu-theme ...) to see instant updates!
  
  ;; cljfx Dev Tools (requires :dev alias)
  (help)                            ;; List all cljfx types
  (help :label)                     ;; Show props for :label
  (help :label :text)               ;; Show details for :text prop
  (help-ui)                         ;; Open interactive browser
  (explain-desc {:fx/type :label :text "Hello"})  ;; Validate description
  
  ;; Profiling (async-profiler - flamegraphs)
  (profile-cpu 30)                  ;; Profile CPU for 30 seconds
  (profile-alloc 30)                ;; Profile allocations for 30 seconds
  (profile-section! prn #_(your-code))   ;; Profile specific code
  (view-flamegraph)                 ;; Open latest flamegraph
  (profiler-ui 8080)                ;; Start web UI
  (profiler-status)                 ;; Check profiler status
  
  ;; JFR Profiling (low-overhead continuous profiling)
  (jfr-start)                       ;; Start continuous recording
  (jfr-start {:max-age "10m"})      ;; With custom options
  (jfr-spikes 5000)                 ;; Alert on frames >5ms
  (jfr-auto-dump 10000)             ;; Auto-save on frames >10ms
  (jfr-dump)                        ;; Save recording to file
  (jfr-dump "my-recording.jfr")     ;; Save with custom name
  (jfr-stop)                        ;; Stop recording
  (jfr-status)                      ;; Check JFR status
  (jfr-recordings)                  ;; List saved recordings
  )
