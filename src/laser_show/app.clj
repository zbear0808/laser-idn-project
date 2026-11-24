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
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [taoensso.timbre :as timbre]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.rig-file :as rig-file]
            [laser-show.state.rig-store :as rig-store]
            [laser-show.state.templates :as templates]
            [laser-show.events.core :as events]
            [laser-show.views.root :as root]
            [laser-show.animation.show-time :as show-time]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.output.addons :as addons]
            [laser-show.output.driver :as driver]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.link :as link]
            [laser-show.input.gamepad :as gamepad]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
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
  "Create the cljfx app with async event processing. cljfx wraps the
   pipeline (with :state first, events/ordered-pipeline) with our co-effects
   and effects and runs it on its agent, on the one event thread
   (events/event-executor, shared by every app instance), so every
   effect's dispatch queues on that agent. The :fx/context co-effect cljfx
   would add is switched off: events carry :state instead."
  []
  (fx/create-app
   (state/get-context-atom)
   :event-handler events/ordered-pipeline
   :co-effects (assoc events/co-effects :fx/context (constantly nil))
   :effects events/effects
   :desc-fn (fn [_] {:fx/type root/root-view})
   :opts {:fx.opt/type->lifecycle (get-type->lifecycle)}
   :error-handler default-error-handler
   :async-agent-options {:error-handler agent-error-handler
                         :fx/executor events/event-executor}))


;; Application Lifecycle


(declare stop! exit!)

(defn- load-addons
  "Load the add-on jars (output.addons/load-all!, plans/2026-11-19-output-drivers/01
   §4.2) from opts' :dir, else the add-ons folder (~/LaserShowProjects/addons),
   with the rest of opts for load-all!, and log what loaded and how long each
   took. {:addons [status …] :dir the folder's path}."
  [opts]
  (let [dir (io/file (or (:dir opts) (addons/addons-dir)))
        statuses (addons/load-all! (assoc opts :dir dir))
        {:keys [loaded failed skipped]} (group-by :state statuses)]
    (log/info "Add-ons from" (.getAbsolutePath dir) ":"
              (count loaded) "loaded" (mapv (fn [{:keys [id load-ms]}] (str id " in " load-ms " ms")) loaded)
              "·" (count failed) "failed ·" (count skipped) "skipped")
    {:addons statuses :dir (.getAbsolutePath dir)}))

(defn- dispatch-drivers-loaded!
  "Put the registered output drivers' descriptors, the add-ons' statuses and
   the add-ons folder (load-addons) into app state, through :addons/loaded."
  [{:keys [addons dir]}]
  (events/dispatch! {:event/type :addons/loaded
                     :addons (vec addons)
                     :drivers (driver/descriptors)
                     :dir dir}))

(defn- starter-state
  "A fresh starter document: the starter cues, vibes and the Master, with
   an identity of its own (persistent/begin-document)."
  []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (templates/apply-starter-effects)
      (history/mark-clean)
      (persistent/begin-document)))

(defn- load-rig
  "state on this machine's rig, the file at path (plans/2026-11-19-zones/09):
   locked for this window if no other has it (else read only, Q-ZR-4), read
   (rig-store/read!: whatever it holds, the app starts) and applied, the
   show's groups joined by the rig's (its group copy), and [:rig-file] set.
   Returns {:state :notice (the read's, for after mounting) :recovered? (the
   backup is in use: write it to rig.edn at once)}. A read-only window never
   moves a file aside."
  [state path]
  (let [locked? (rig-store/lock! path)
        {:keys [status doc notice]} (rig-store/read! path {:read-only? (not locked?)})
        read-only? (or (not locked?) (= :newer status))
        modified (let [f (java.io.File. ^String path)] (when (.exists f) (.lastModified f)))
        rigged (-> (cond-> state doc (rig-file/apply-rig-document doc))
                   (update :rig-file merge {:path path
                                            :status (if locked? status :locked)
                                            :read-only? read-only?
                                            :written-at (when doc modified)}))
        ;; The show's groups joined by the rig's, in the saved baseline too,
        ;; so the show isn't unsaved for it
        join-groups #(update % :zone-groups merge (get-in rigged [:rig-file :group-copy]))]
    (log/info "Rig file" path (name (get-in rigged [:rig-file :status])) (when read-only? "(read only)"))
    {:state (-> rigged join-groups (history/backfill-baseline join-groups))
     :notice notice
     :recovered? (and (= :recovered status) (not read-only?))}))

(defn- after-rig-loaded!
  "Once the window is up: the rig read's notice, and a recovered rig written
   back to rig.edn (else the next start would find no file and start empty)."
  [{:keys [notice recovered?]}]
  (when notice
    (events/dispatch! {:event/type :rig/startup-notice :text notice :level :warning}))
  (when recovered?
    (events/dispatch! {:event/type :rig/retry-write})))

(defn- connect-link!
  "Connect to the Carabiner daemon unless [:backend :link :auto-connect?] is
   false: on this thread (it blocks as link/connect-carabiner! does), then
   the result goes to app state through :timing/carabiner-connected, queued
   like every other event, so an event in flight can't undo it."
  []
  (let [link-state (get-in (state/get-raw-state) [:backend :link])]
    (when (:auto-connect? link-state true)
      (let [{:keys [carabiner-connected? listener]}
            (link/connect-carabiner! link-state events/dispatch! state/get-raw-state)]
        (events/dispatch! (cond-> {:event/type :timing/carabiner-connected
                                   :connected? (boolean carabiner-connected?)}
                            listener (assoc :listener listener)))
        (when carabiner-connected?
          (log/info "Carabiner daemon connected (Link available via UI button)"))))))

(defn start!
  "Start the laser show application.
   
   Initializes state on this machine's rig (its file, ~/LaserShowProjects/rig.edn),
   loads the add-on jars (~/LaserShowProjects/addons) before the output
   runtime opens, creates renderer, and mounts the UI.
   Auto-scans for IDN devices on startup, matched against the loaded rig.
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

  ;; A fresh starter document on this machine's rig
  (let [rig (load-rig (starter-state) (persistent/default-rig-file))
        ;; The add-on jars' drivers, registered before the output runtime opens
        loaded-addons (load-addons {})]
    (state/init-state! (:state rig))

    (output-runtime/open!)
    (output-runtime/install-shutdown-hook!)
    (rig-store/install-shutdown-hook!)

    (let [app (create-app)]
      (reset! *app app)
      ;; Wire dispatch! to use app's handler (wrapped with async agent)
      (events/set-dispatch-fn! (:handler app))
      (state/guard-writes! {:allowed? events/on-event-thread? :strict? (dev-config/dev-mode?)})
      (events/set-exit-fn! #(exit!))
      (input-mapper/set-dispatch-fn! events/dispatch!))

    (after-rig-loaded! rig)

    ;; The output drivers and add-ons, as data for the views; before the
    ;; startup scan, so it sees every driver
    (dispatch-drivers-loaded! loaded-addons))

  ;; After dispatch! reaches the agent, so the clock's reports queue there
  (show-clock/start!)

  (connect-link!)

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

(defn- stop-streaming!
  "Drain the output runtime: every engine is stopped, so each DAC gets its IDN
   close packets instead of waiting for its link timeout, and nothing starts
   an engine again until the app is started again."
  []
  (try
    (output-runtime/drain! 2000)
    (catch Exception e
      (log/warn "Error stopping streaming engines:" (.getMessage e)))))

(defn- release-devices!
  "Close what state lists: the MIDI ports and the OSC server, and the
   Carabiner connection when it is connected. Each is closed once, in its
   own try, and nothing is written back: the next start replaces app state."
  [state]
  (when-let [{:keys [midi osc]} (get-in state [:backend :input])]
    (try
      (midi/disconnect-all midi)
      (catch Exception e
        (log/warn "Error releasing MIDI ports:" (.getMessage e))))
    (try
      (osc/stop-server osc)
      (catch Exception e
        (log/warn "Error stopping the OSC server:" (.getMessage e)))))
  (when (get-in state [:backend :link :carabiner-connected?])
    (try
      (link/disconnect-carabiner! (get-in state [:backend :link]))
      (catch Exception e
        (log/warn "Error disconnecting from Carabiner:" (.getMessage e))))))

(defn- stop-rig-file!
  "Write the rig change still pending (within 2 s), then let go of the rig
   file's lock, so the next window can write it."
  []
  (try
    (rig-store/flush! 2000)
    (catch Exception e
      (log/warn "Error writing the rig file:" (.getMessage e))))
  (rig-store/unlock!))

(defn stop!
  "Stop the mounted cljfx application and release application-owned services:
   the UI, the show clock, the input mapper, the gamepad poller, the
   streaming engines (the output runtime is drained), the add-ons' drivers
   (unregistered, their jars closed), input devices, the Carabiner link and
   the rig file (its pending change written, its lock let go). dispatch!
   falls back to the synchronous handler afterwards, and the write guard
   start! or start-test! installed is removed.

   It writes nothing to app state. The devices are closed from one read,
   after the events already queued have run (within 1 s; on the event
   thread, as on Quit, it can't wait for them and doesn't), and only by the
   call that claimed the app, so each is closed once.

   This is intentionally idempotent so UI fixtures can safely call it from
   `finally` blocks after a failed startup or test."
  []
  (let [[app] (reset-vals! *app nil)]
    (when-let [{:keys [renderer]} app]
      (fx/unmount-renderer (state/get-context-atom) renderer))
    (show-clock/stop!)
    (input-mapper/stop-mapper!)
    (gamepad/stop!)
    (stop-streaming!)
    ;; Nothing streams now: the next start loads the add-ons again
    (addons/unload-all!)
    (when app
      (events/flush! 1000)
      (release-devices! (state/get-raw-state)))
    (stop-rig-file!)
    (events/set-exit-fn! nil)
    (events/set-dispatch-fn! nil)
    (state/guard-writes! nil)
    (when (dev-config/dev-mode?)
      (Platform/setImplicitExit false))
    nil))

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
   handler and production root view remain in use. No show clock runs: tests
   tick it by hand (show-clock/tick!), and show time starts from nothing.

   While it runs, a write to app state from any thread but the event thread
   throws (state/guard-writes!): tests write with events/write-state!.

   No rig file is read or written unless the state names one in
   [:rig-file :path] (a test's temp folder): then it is locked, read and
   saved as app/start! does with the real one, so no test reaches the user's
   ~/LaserShowProjects/rig.edn.

   No add-on is loaded unless opts has :addons-dir (a test's temp folder):
   then its jars load as app/start! loads the real folder's, before the
   output runtime opens, for :addons-platform ({:os :arch}, default this
   machine's) and without classpath descriptors. Either way :addons/loaded
   says which folder Settings → Add-ons shows and installs into: that one,
   else output.addons/addons-dir (a temp folder in tests, test_runner.clj).
   initial-state nil is the starter state."
  ([]
   (start-test! (starter-state)))
  ([initial-state]
   (start-test! initial-state {}))
  ([initial-state {:keys [addons-dir addons-platform]}]
   (stop!)
   (let [initial-state (or initial-state (starter-state))
         path (get-in initial-state [:rig-file :path])
         rig (if path (load-rig initial-state path) {:state initial-state})
         loaded-addons (if addons-dir
                         (load-addons {:dir addons-dir :platform addons-platform :classpath-loader nil})
                         {:addons [] :dir (.getAbsolutePath (addons/addons-dir))})]
     (state/init-state! (:state rig))
     (show-time/reset-runtime!)
     (output-runtime/open!)
     (let [app (create-app)]
       (reset! *app app)
       (events/set-dispatch-fn! (:handler app))
       (state/guard-writes! {:allowed? events/on-event-thread? :strict? true})
       (dispatch-drivers-loaded! loaded-addons)
       (after-rig-loaded! rig)
       app))))




;; Main Entry Point

(defn -main
  "Main entry point for the application.
   
   Starts the GUI application."
  [& _args]
  (start!))
