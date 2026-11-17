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
            [taoensso.timbre :as timbre]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.templates :as templates]
            [laser-show.events.core :as events]
            [laser-show.views.root :as root]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.link :as link]
            [laser-show.input.gamepad :as gamepad]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.css.title-bar :as menus]
            [laser-show.dev-config :as dev-config]
            [clojure.pprint :as pprint])
  (:import [javafx.application Platform])
  (:gen-class))

(defonce ^{:private true :doc "The cljfx app instance."} *app (atom nil))

(defn- default-error-handler
  "Error handler for cljfx renderer.
   
   Logs errors and prints stack trace. In dev mode, cljfx.dev provides
   enhanced error messages with component stacks, which are stored in ex-data."
  [^Throwable ex]
  (log/error ex "Error in cljfx renderer:")
  (binding [*out* *err*]
    (println "Error in cljfx renderer:" (.getMessage ex))
    (when-let [data (ex-data ex)]
      (println "Exception Data:")
      (pprint/pprint data))
    (println "Stack trace:")
    (.printStackTrace ex *out*)))

(defn- get-type->lifecycle
  "Returns the type->lifecycle function based on dev mode.
   
   In dev mode: Uses cljfx.dev/type->lifecycle for validation and better errors
                on keyword types only. Function components use standard context-aware
                lifecycle to ensure proper context propagation.
   In prod mode: Uses standard cljfx lifecycle lookup"
  []
  (let [std-lifecycle #(or (fx/keyword->lifecycle %)
                           (fx/fn->lifecycle-with-context %))]
    (if (dev-config/dev-mode?)
      (if-let [dev-lifecycle (try
                               @(requiring-resolve 'cljfx.dev/type->lifecycle)
                               (catch Throwable t
                                 (log/warn t "Could not load cljfx.dev tools; falling back to standard lifecycle")
                                 nil))]
        (do
          (log/info "🔧 Dev mode enabled - cljfx dev tools active (Press F12 for inspector)")
          (fn [type]
            (if (keyword? type)
              (dev-lifecycle type)
              (fx/fn->lifecycle-with-context type))))
        (do
          (log/info "🔧 Dev mode enabled - using standard cljfx lifecycle")
          std-lifecycle))
      std-lifecycle)))

(defn- agent-error-handler
  "Error handler for the event processing agent.
   
   Logs errors and continues processing. The agent will remain usable."
  [agent-ref exception]
  (log/error exception "Error in event processing agent:")
  (.printStackTrace exception *err*))

(defn- create-app
  "Create the cljfx app with async event processing. cljfx wraps the bare
   pipeline with our co-effects and effects and runs it on its agent, so every
   effect's dispatch queues on that agent. The :fx/context co-effect cljfx
   would add is switched off: events carry :state instead."
  []
  (fx/create-app
   (state/get-context-atom)
   :event-handler events/handle-event-pipeline
   :co-effects (assoc events/co-effects :fx/context (constantly nil))
   :effects events/effects
   :desc-fn (fn [_] {:fx/type root/root-view})
   :opts {:fx.opt/type->lifecycle (get-type->lifecycle)}
   :error-handler default-error-handler
   :async-agent-options {:error-handler agent-error-handler}))


(defn init-styles!
  "Initialize CSS style URLs in state.
   
   This sets up the cljfx-css registered styles in the application state,
   enabling hot-reload support. The style URLs are stored in state so that
   when a var watch detects a change, updating state triggers a re-render."
  []
  (state/assoc-in-state! [:styles :menu-theme] (::css/url menus/menu-theme))
  (log/debug "CSS styles initialized"))


;; Application Lifecycle


(declare stop! exit!)

(defn start!
  "Start the laser show application.
   
   Initializes state, creates renderer, and mounts the UI.
   Auto-scans for IDN devices on startup.
   Returns the app instance."
  []
  ;; Configure Timbre logging levels (used by beat-carabiner transitive dependency)
  ;; Suppress DEBUG logs from beat-carabiner (read timeout messages are normal)
  (timbre/merge-config!
   {:min-level [["beat-carabiner.*" :info]  ;; INFO and above for beat-carabiner
                ["*" :debug]]})              ;; DEBUG for everything else

  ;; In dev mode: Allow closing windows without exiting JVM (for REPL development)
  ;; In prod mode: Closing the window exits the application normally
  (when (dev-config/dev-mode?)
    (Platform/setImplicitExit false)
    (log/info "Dev mode: Window close will not exit JVM"))

  ;; Initialize with base state, then apply starter templates for fresh projects
  (state/init-state! (-> (domains/build-initial-state)
                         (templates/apply-starter-cue-chains)
                         (history/mark-clean)))

  (init-styles!)

  (output-runtime/open!)
  (output-runtime/install-shutdown-hook!)

  (frame-service/start-preview-updates! 30)

  (let [app (create-app)]
    (reset! *app app)
    ;; Wire dispatch! to use app's handler (wrapped with async agent)
    (events/set-dispatch-fn! (:handler app))
    (events/set-exit-fn! #(exit!))
    (input-mapper/set-dispatch-fn! events/dispatch!))

  ;; Start Carabiner service (auto-connect for Link support)
  (let [link-state (get-in (state/get-raw-state) [:backend :link])
        auto-connect? (:auto-connect? link-state true)
        get-link-state-fn #(get-in (state/get-raw-state) [:backend :link])]
    (when auto-connect?
      (let [new-link-state (link/connect-carabiner! link-state events/dispatch! get-link-state-fn)]
        (state/assoc-in-state! [:backend :link] new-link-state)
        (log/info "Carabiner daemon connected (Link available via UI button)"))))

  ;; Auto-scan for IDN devices on startup
  (log/info "Starting automatic device discovery...")
  (events/dispatch! {:event/type :projectors/scan-network})

  ;; Start fast-path global input mapper for Midi/OSC/Keyboard events
  (input-mapper/start-mapper!)

  ;; Start Gamepad input parsing thread. Events go to the input router (like
  ;; MIDI/OSC), not the cljfx event pipeline.
  (gamepad/start! router/dispatch!
                  #(get-in (state/get-raw-state) [:backend :input :gamepad :enabled?] true))

  (log/info "Laser Show application started.")
  @*app)

(defn- stop-input-devices!
  "Stop the gamepad poller and release MIDI ports and the OSC socket."
  []
  (gamepad/stop!)
  (try
    (when (get-in (state/get-raw-state) [:backend :input])
      (state/swap-state! (fn [s]
                           (-> s
                               (update-in [:backend :input :midi] midi/disconnect-all)
                               (update-in [:backend :input :osc] osc/stop-server)))))
    (catch Exception e
      (log/warn "Error releasing input devices:" (.getMessage e)))))

(defn- stop-streaming!
  "Drain the output runtime: every engine is stopped, so each DAC gets its IDN
   close packets instead of waiting for its link timeout, and nothing starts
   an engine again until the app is started again."
  []
  (try
    (output-runtime/drain! 2000)
    (catch Exception e
      (log/warn "Error stopping streaming engines:" (.getMessage e)))))

(defn- disconnect-link!
  "Disconnect from the Carabiner daemon if we connected to it."
  []
  (when (get-in (state/get-raw-state) [:backend :link :carabiner-connected?])
    (try
      (state/swap-state! update-in [:backend :link] link/disconnect-carabiner!)
      (catch Exception e
        (log/warn "Error disconnecting from Carabiner:" (.getMessage e))))))

(defn stop!
  "Stop the mounted cljfx application and release application-owned services:
   the UI, the preview timer, the input mapper, the streaming engines (the
   output runtime is drained), input devices and the Carabiner link. dispatch!
   falls back to the synchronous handler afterwards.

   This is intentionally idempotent so UI fixtures can safely call it from
   `finally` blocks after a failed startup or test."
  []
  (when-let [{:keys [renderer]} @*app]
    (fx/unmount-renderer (state/get-context-atom) renderer))
  (frame-service/stop-preview-updates!)
  (input-mapper/stop-mapper!)
  (stop-streaming!)
  (stop-input-devices!)
  (disconnect-link!)
  (events/set-exit-fn! nil)
  (events/set-dispatch-fn! nil)
  (reset! *app nil)
  (when (dev-config/dev-mode?)
    (Platform/setImplicitExit false))
  nil)

(defn- exit!
  "End the application (the :system/exit effect calls this). In dev mode the JVM
   and its REPL stay alive; otherwise the process exits."
  []
  (stop!)
  (when-not (dev-config/dev-mode?)
    (Platform/exit)
    (System/exit 0)))

(defn start-test!
  "Mount the application UI without starting hardware or background services.

   This is intended for TestFX and other UI integration tests. The supplied
   state is isolated at the cljfx context level, while the normal event
   handler and production root view remain in use."
  ([]
   (start-test! (-> (domains/build-initial-state)
                    (templates/apply-starter-cue-chains)
                    (history/mark-clean))))
  ([initial-state]
   (stop!)
   (state/init-state! initial-state)
   (init-styles!)
   (output-runtime/open!)
   (let [app (create-app)]
     (reset! *app app)
     (events/set-dispatch-fn! (:handler app))
     app)))




;; Main Entry Point

(defn -main
  "Main entry point for the application.
   
   Starts the GUI application."
  [& _args]
  (start!))
