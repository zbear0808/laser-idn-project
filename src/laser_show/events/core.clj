(ns laser-show.events.core
  "Event handling core - wraps pure handlers with co-effects and effects.
   
   This module provides:
   - handle-event-pipeline, and ordered-pipeline (it with :state first), the
     co-effects and effects maps that app/create-app gives to fx/create-app,
     with instance-handler, the pipeline for one app instance
     (new-instance!, retire!)
   - Effect implementations for side-effects (IDN streaming, timing, etc.)
   - event-handler, the same pipeline run synchronously (tests, fallback)
   - dispatch! for programmatic event dispatch
   - flush! and write-state!, for tests and the REPL
   - event-executor and on-event-thread?: the one event thread
   
   Architecture:
   
   Event Flow:
   1. UI dispatches event map {:event/type :grid/trigger-cell :col 0 :row 0}
   2. Event is queued to agent (returns immediately, non-blocking)
   3. Agent thread: co-effects inject :state and :time into event
   4. Agent thread: Pure handler processes event, returns effects map
   5. Agent thread: effects execute, :state first (ordered-pipeline), then
      the others in the map's order
   6. State atom watch triggers re-render on FX thread
   
   Threading (with the app running):
   - UI callbacks (inline fns) run briefly on FX thread, then delegate to agent
   - Co-effects, handlers and effects run on the agent, on the one event
     thread (laser-show-events, event-executor), one event at a time
   - An effect that continues the event (:dispatch, :project/save's
     :project/mark-clean and its :on-saved, ...) runs the follow-up inline
     through run-inline, in the same turn
   - Effects that work later on another thread (futures: scan, learn,
     :dispatch-later, :rig/flush's wait; the FX thread: file chooser; the
     output runtime's status thread; the rig writer; the input device
     worker, input.devices, which owns the MIDI inputs and the OSC server)
     only do their side effect there. Their result goes through the
     dispatch fn the effect was given, which queues it on the agent behind the
     current event
   - The streaming lifecycle effects only queue a command on the output
     runtime's own thread (backend/output_runtime.clj), which owns every engine
   - Nothing running on the agent may block waiting for an event it
     dispatched: that event only runs after the current one
   - Each mounted app is an app instance (new-instance!, see App instances
     below): once app/stop! retires it, its late events (a scan, a learn,
     the rig writer's results, Exit coming back) are dropped as they come,
     an effect of an event of it still running does nothing, and it never
     writes into the context of the app started after it
   - Nothing writes app state outside the pipeline: an event's :state, or an
     effect of the same turn that runs after it (:output/write-status,
     :dev/swap-state), on the event thread. Start-up's init-state! runs
     before any agent exists. While an app runs, a write from any thread
     other than the event thread is refused (state/guard-writes!): it throws
     in dev mode and in app/start-test!, and is logged at ERROR in
     production. Tests and the REPL use write-state! and flush!. The
     clipboard ([:ui :clipboard]) is written only in its handlers' :state;
     :clipboard/write and :clipboard/read talk to the OS clipboard on the FX
     thread, and nothing waits for them. The show clock's thread sends what
     it learns as events (:show-clock/*), and views that move every frame
     draw its newest tick without app state. In dev mode effect-state warns
     when any write landed between an event's snapshot and its write-back
   - State updates (atom swap) are atomic
   - Re-rendering happens on FX thread via atom watch
   
   Without an app (tests, REPL), event-handler and dispatch! run everything
   synchronously on the calling thread, and later callbacks on theirs.
   
   Benefits:
   - Pure handlers are easy to test
   - Side effects are isolated and explicit
   - State updates preserve cljfx context memoization
   - Heavy event processing doesn't block UI rendering"
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [laser-show.dev-config :as dev-config]
            [laser-show.output.addons :as addons]
            [laser-show.output.discovery :as discovery]
            [laser-show.state.core :as state]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.rig-store :as rig-store]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.history :as history-handlers]
            [laser-show.events.handlers.rig-autosave :as rig-autosave]
            [laser-show.events.handlers.step-grid :as step-grid-handlers]
            [laser-show.events.handlers.timeline :as timeline-handlers]
            [laser-show.events.safety-guard :as safety-guard]
            [laser-show.events.show-lock :as show-lock]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.events.handlers.output-sync :as output-sync]
            [laser-show.common.util :as u]
            [laser-show.input.devices :as devices]
            [laser-show.input.link :as link]
            [laser-show.animation.show-time :as show-time])
  (:import [javafx.stage FileChooser FileChooser$ExtensionFilter Stage]
           [javafx.application Platform]
           [javafx.scene.input Clipboard ClipboardContent]
           [java.awt Desktop Desktop$Action]
           [java.io File]
           [java.net URI]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

;; Co-effects (inject data INTO events)
;; Co-effect functions take NO arguments and return data to inject.

(defn- co-effect-state
  "Co-effect that injects current state into event.
   
   Uses get-raw-state (not get-state) because:
   - This runs on agent thread, not FX thread
   - fx/sub-val subscription tracking is not needed for events
   - Direct state access is thread-safe via swap!"
  []
  (state/get-raw-state))

(defn- co-effect-time
  "Co-effect that injects current timestamp into event."
  []
  (System/currentTimeMillis))

;; App instances
;;
;; Each mounted app (app/create-app) is an app instance, {:id n :epoch e}:
;; e is the epoch of the context init-state! made for it (state/epoch).
;; cljfx runs an app's events as actions of an agent of its own, on
;; event-executor; instance-handler, the app's :event-handler, puts the
;; instance on that agent's metadata, so the event's effects and the events
;; they continue inline know whose they are (current-instance). Once
;; app/stop! retires it (retire!), an event of it is dropped as it comes,
;; an effect of one still running does nothing, and nothing it writes
;; reaches a context of another epoch (effect-state): a late result of the
;; app before can't change the app started after it. Without an app (unit
;; tests, the REPL) there is no instance, and events run as before.

(defonce ^{:private true :doc "How many app instances were made: the newest one's :id."}
  !instances
  (atom 0))

(defonce ^{:private true :doc "The live app instance (new-instance!), or nil."}
  !live-instance
  (atom nil))

(defn- live?
  "Is instance the live app instance?"
  [instance]
  (identical? instance @!live-instance))

(defn- current-instance
  "The app instance whose event is being handled on this thread: the one on
   the agent running it (instance-handler), nil without an app."
  []
  (some-> *agent* meta ::instance))

(defn- retired
  "The app instance of the event being handled on this thread when it is
   retired, else nil (it is live, or there is no app)."
  []
  (when-let [instance (current-instance)]
    (when-not (live? instance)
      instance)))

(defn- dropped
  "Log what of a retired app instance was dropped. nil."
  [instance what]
  (log/info "App instance" (:id instance) "was stopped: dropped" what))

;; Effects (handle side effects FROM events)
;;
;; Every effect gets a dispatch fn. It is the way to CALL BACK LATER: from a
;; future, a timer or the FX thread. With the app running it queues the event on
;; the cljfx agent (behind the current one); tests and the no-app fallback get a
;; synchronous one. An effect that CONTINUES the current event (a follow-up that
;; must run now, before any other event) uses run-inline instead.

(declare event-handler)

(defn- run-inline
  "Run event now, on this thread, as part of the current turn. Its effects get
   the same dispatch. Nothing for a retired app instance's event (logged)."
  [event dispatch]
  (if-let [instance (retired)]
    (dropped instance (:event/type event))
    (event-handler event dispatch)))

(defn- changed-paths
  "Paths (at most depth+1 keys long) where maps a and b differ."
  [a b depth]
  (mapcat (fn [k]
            (let [va (get a k)
                  vb (get b k)]
              (cond
                (= va vb) nil
                (and (pos? depth) (map? va) (map? vb)) (map #(into [k] %) (changed-paths va vb (dec depth)))
                :else [[k]])))
          (distinct (concat (keys a) (keys b)))))

(defn- check-lost-update!
  "Dev mode: report a write that landed between an event's state snapshot and
   its write-back (that write is lost). No writer is exempt: the show clock
   reports through events too."
  [event-type snapshot replaced]
  (when-not (identical? snapshot replaced)
    (when-let [paths (seq (changed-paths snapshot replaced 1))]
      (log/warn "Possible lost update:" event-type
                "wrote back a state snapshot, but another writer changed" (vec paths)
                "in the meantime"))))

(defn- wrap-lost-update-check
  "Dev mode only: attach the event's :state co-effect (the snapshot its handler
   started from) to the :state effect as metadata, for effect-state to check."
  [handler]
  (fn [event]
    (let [result (handler event)
          new-state (:state result)]
      (if (and (instance? clojure.lang.IObj new-state)
               (some? (:state event))
               (dev-config/dev-mode?))
        (assoc result :state (vary-meta new-state assoc
                                         ::snapshot (:state event)
                                         ::event-type (:event/type event)))
        result))))

(def ^:private not-written
  "What an app instance's write that found another epoch's context logs."
  "state not written: init-state! has made another context since")

(defn- effect-state
  "Effect that updates application state.
   Preserves cljfx context memoization. An app instance's event writes only
   into the context the instance was made for (state/swap-state-in-epoch!,
   checked and written in one step): once init-state! has made another (a
   restart), it writes nothing (logged)."
  [new-state _dispatch]
  (let [{::keys [snapshot event-type]} (meta new-state)
        new-state (cond-> new-state snapshot (vary-meta dissoc ::snapshot ::event-type))
        instance (current-instance)]
    (if-let [[replaced _] (if instance
                            (state/swap-state-in-epoch! (:epoch instance) (constantly new-state))
                            (state/reset-state-vals! new-state))]
      (when snapshot
        (check-lost-update! event-type snapshot replaced))
      (log/info "App instance" (:id instance) not-written))))

(defn- swap-app-state!
  "(apply state/swap-state! f args), but for an app instance's event only
   into the context it was made for, as effect-state writes."
  [f & args]
  (if-let [instance (current-instance)]
    (when-not (apply state/swap-state-in-epoch! (:epoch instance) f args)
      (log/info "App instance" (:id instance) not-written))
    (apply state/swap-state! f args)))

(defn- effect-dispatch
  "Effect that continues with another event, inline in the current turn."
  [event dispatch]
  (run-inline event dispatch))

(defn- effect-dispatch-later
  "Effect that dispatches an event after a delay (from a future).
   Args: {:event event-map :delay-ms milliseconds}"
  [{:keys [event delay-ms]} dispatch]
  (future
    (Thread/sleep delay-ms)
    (dispatch event)))

;; A press's deadline (reactivity plan 16): one wake-up, for the press whose
;; transaction is open, so a missed mouse-up is sealed even while nothing
;; else happens. Its slot belongs to the app's dispatcher: app/stop! retires
;; it (dispose-gesture-alarm!).

(defonce ^{:private true
           :doc "The armed wake-up: {:gesture-id :token :cancel}, or nil."}
  !gesture-alarm
  (atom nil))

(defonce ^{:private true
           :doc "The one daemon thread the wake-ups wait on."}
  gesture-alarm-executor
  (delay (Executors/newSingleThreadScheduledExecutor
          (reify ThreadFactory
            (newThread [_ r]
              (doto (Thread. ^Runnable r "laser-show-gesture-alarm")
                (.setDaemon true)))))))

(defn schedule-gesture-wake!
  "Run task once, delay-ms from now, on the wake-ups' thread; returns a fn
   that cancels it. :history/gesture-alarm's scheduler (tests replace it
   with a fake one)."
  [delay-ms ^Runnable task]
  (let [f (.schedule ^ScheduledExecutorService @gesture-alarm-executor task (long (max 0 delay-ms)) TimeUnit/MILLISECONDS)]
    #(.cancel f false)))

(defn- effect-gesture-alarm
  "Effect that keeps the open press's one wake-up. {:cancel-id G} cancels
   G's, and only G's (a late cancel can't cancel the one that took its
   place); {:arm {:gesture-id H :delay-ms ms}} replaces whatever is armed
   with H's. Once due, a wake-up still in its slot dispatches
   {:event/type :history/gesture-timeout :history/gesture-id H} through
   dispatch, the dispatcher this effect was given; one cancelled or
   replaced meanwhile dispatches nothing. It reads and writes no app state."
  [{:keys [cancel-id arm]} dispatch]
  (when (some? cancel-id)
    (let [[old _] (swap-vals! !gesture-alarm #(if (= cancel-id (:gesture-id %)) nil %))]
      (when (and (= cancel-id (:gesture-id old)) (:cancel old))
        ((:cancel old)))))
  (when-let [{:keys [gesture-id delay-ms]} arm]
    (let [token (Object.)
          wake (fn []
                 (let [[old _] (swap-vals! !gesture-alarm #(if (identical? token (:token %)) nil %))]
                   (when (identical? token (:token old))
                     (dispatch {:event/type :history/gesture-timeout :history/gesture-id gesture-id}))))
          [old _] (swap-vals! !gesture-alarm (constantly {:gesture-id gesture-id :token token}))
          _ (when-let [cancel (:cancel old)] (cancel))
          cancel (schedule-gesture-wake! delay-ms wake)
          kept (swap! !gesture-alarm #(if (identical? token (:token %)) (assoc % :cancel cancel) %))]
      (when-not (identical? token (:token kept))
        (cancel)))))

(defn dispose-gesture-alarm!
  "Cancel the armed wake-up, if any: app/stop! retires it with its app."
  []
  (let [[old _] (reset-vals! !gesture-alarm nil)]
    (when-let [cancel (:cancel old)]
      (cancel))))

(defn- effect-save-project
  "Effect that writes a project zip (persistent/write-project!: staged
   beside the file, read back, then moved onto it).
   Args: {:file-path path :files {filename data} :on-saved event :on-failed event}
   The handler changed nothing yet. Only once the file is written and
   checked does it continue with :project/mark-clean, with :file-path and
   :on-saved as its :then (if given), so what waited for the save runs
   after the file and the saved status are there. On failure, it continues
   with :on-failed with :file-path."
  [{:keys [file-path files on-saved on-failed]} dispatch]
  (if (persistent/write-project! file-path files)
    (run-inline (cond-> {:event/type :project/mark-clean :file-path file-path}
                  on-saved (assoc :then on-saved))
                dispatch)
    (when on-failed
      (run-inline (assoc on-failed :file-path file-path) dispatch))))

(defn- effect-load-project
  "Effect that reads a project zip.
   Args: {:file-path path :on-loaded event :on-failed event}
   Continues with :on-loaded with :file-path and :contents (read-project's:
   read strictly and checked), or :on-failed when there is no show to open."
  [{:keys [file-path on-loaded on-failed]} dispatch]
  (if-let [contents (persistent/read-project file-path)]
    (run-inline (assoc on-loaded :file-path file-path :contents contents) dispatch)
    (when on-failed
      (run-inline (assoc on-failed :file-path file-path) dispatch))))

(defn- effect-rig-write
  "Effect that hands the rig to the rig writer (state.rig-store/submit!): it
   writes the newest one half a second after the last change (at once with
   :now?), on its own thread, so a drag never waits for the disk. The result
   comes back later through dispatch, as :rig/written (with :then, the event
   to continue with) or :rig/write-failed.
   Args: {:path :doc :seq :then :now?}"
  [{:keys [path doc seq then now?]} dispatch]
  (rig-store/submit! path doc
                     (fn [{:keys [at error]}]
                       (dispatch (if error
                                   {:event/type :rig/write-failed :path path :seq seq :error error}
                                   (cond-> {:event/type :rig/written :path path :seq seq :at at}
                                     then (assoc :then then)))))
                     (if now? 0 rig-store/write-delay-ms)))

(defn- effect-rig-flush
  "Effect that waits, off the event thread (in a future), for the rig writer
   to finish the rigs it was given (state.rig-store/flush!: the pending one
   written at once, after the one it may be writing), at most
   rig-store/flush-ms, then dispatches :then. Those writes' results
   (:rig/written, :rig/write-failed) are dispatched before it, unless the
   wait ran out.
   Args: {:then event}"
  [{:keys [then]} dispatch]
  (future
    (try
      (rig-store/flush! rig-store/flush-ms)
      (catch Throwable t
        (log/warn "Could not wait for the rig file:" (.getMessage t))))
    (when then
      (dispatch then))))

;; The OS clipboard. JavaFX's Clipboard works only on the FX thread, so both
;; effects hand their call to it with Platform/runLater and never wait: the
;; event agent goes on with the next event at once.

(defn- put-system-clipboard-text!
  "Put text on the OS clipboard. FX thread only."
  [^String text]
  (.setContent (Clipboard/getSystemClipboard) (doto (ClipboardContent.) (.putString text))))

(defn- system-clipboard-text
  "The OS clipboard's text, or nil. FX thread only."
  []
  (let [cb (Clipboard/getSystemClipboard)]
    (when (.hasString cb) (.getString cb))))

(defn- effect-clipboard-write
  "Effect that puts a clip's EDN (clipboard/->system-text, serialized here)
   on the OS clipboard, on the FX thread. Nothing waits for it and no event
   follows; a failure is logged."
  [clip _dispatch]
  (let [text (clipboard/->system-text clip)]
    (try
      (Platform/runLater
       (fn []
         (try
           (put-system-clipboard-text! text)
           (catch Throwable t
             (log/warn "Could not put the copy on the system clipboard:" (.getMessage t))))))
      (catch Throwable t
        (log/warn "Could not put the copy on the system clipboard:" (.getMessage t))))))

(defn- effect-clipboard-read
  "Effect that reads the OS clipboard on the FX thread and parses it
   (clipboard/<-system-text), then dispatches :then with :clipboard/system,
   the clip or nil, through dispatch (later, FX thread). The agent doesn't
   wait for it.
   Args: {:then event}"
  [{:keys [then]} dispatch]
  (let [read-clip #(try
                     (clipboard/<-system-text (system-clipboard-text))
                     (catch Throwable t
                       (log/warn "Could not read the system clipboard:" (.getMessage t))
                       nil))]
    (try
      (Platform/runLater #(dispatch (assoc then :clipboard/system (read-clip))))
      (catch Throwable t
        (log/warn "Could not read the system clipboard:" (.getMessage t))
        (dispatch (assoc then :clipboard/system nil))))))

;; Projector Scanning Effects

(defn- effect-projectors-scan
  "Scan for IDN and driver outputs off the event agent, and queue one result,
   with the :scan-id of the Scan it answers (handlers/projector.clj) when
   it was given one."
  [{:keys [scan-id] :as opts} dispatch]
  (future
    (let [result (try (discovery/scan (dissoc opts :scan-id))
                      (catch Exception e
                        (log/error e "Device scan failed")
                        {:error (ex-message e)}))]
      (dispatch (cond-> (assoc result :event/type (if (contains? result :error)
                                                    :projectors/scan-failed :projectors/scan-complete))
                  scan-id (assoc :scan-id scan-id))))))

;; Link Effects

(defn- effect-link-sync-to-downbeat
  "Effect that queries Link's beat phase against the show clock's beats and
   continues with a phase correction."
  [_ dispatch]
  (link/sync-to-downbeat! #(run-inline % dispatch) (show-time/global-beats)))

;; Show Clock Effects

(defn- effect-show-clock-read
  "Effect that continues :on-read with :clock, what the show clock has now:
   {:beats (the global clock's) :running? (a clock thread runs) :now-ms
   (the wall clock, the one an input's :timestamp is on)}, so a handler that
   times something on the beat stays pure (zones plan Z4 SQ-3). With
   :chase-cell, :clock is {:chase-id k} instead, the timecode episode the
   clock chases at that cell now, nil for none (one read of its runtime,
   show-time/chase-id), for :show-clock/apply-chase (reactivity plan 15)."
  [{:keys [on-read] :as request} dispatch]
  (when on-read
    (run-inline (assoc on-read :clock (if (contains? request :chase-cell)
                                        {:chase-id (show-time/chase-id (:chase-cell request))}
                                        {:beats (show-time/global-beats)
                                         :running? ((:running? show-time/frame-signal))
                                         :now-ms (System/currentTimeMillis)}))
                dispatch)))

;; Input Learn Effects

(defn- effect-input-await-learn
  "Effect that waits (off the event thread) for a learn capture, then dispatches
   :on-captured with :input-event. A nil delivery means learn was cancelled."
  [{:keys [promise on-captured]} dispatch]
  (future
    (try
      (when-let [input-event @promise]
        (dispatch (assoc on-captured :input-event input-event)))
      (catch Throwable t
        (log/error "Input learn failed:" (.getMessage t))))))

;; Input Device Effects

(defn- device-request
  "The effect that hands its value, a request of kind op with its
   :request-id, to the device worker (input.devices/submit!): it lists the
   MIDI inputs or opens or closes a port off the event thread, then
   dispatches its result, plain data, through dispatch."
  [op]
  (fn [request dispatch]
    (devices/submit! (assoc request :op op) dispatch)))

;; Add-on Effects

(defn- effect-addons-install
  "Effect that installs an add-on jar (laser-show.output.addons/install!:
   checked, then copied into dir as <name>.jar.new, which loads at the next
   start), in a future. Then dispatches :addons/installed {:file :replaced?
   :descriptor} or :addons/install-failed {:file-path :error}.
   Args: {:file-path the jar :dir the add-ons folder (default addons-dir)}"
  [{:keys [file-path dir]} dispatch]
  (future
    (dispatch
     (try
       (let [{:keys [^File file replaced? descriptor]} (addons/install! file-path (or dir (addons/addons-dir)))]
         {:event/type :addons/installed
          :file (.getAbsolutePath file)
          :replaced? replaced?
          :descriptor descriptor})
       (catch Throwable t
         (log/warn "Could not install add-on" file-path ":" (.getMessage t))
         {:event/type :addons/install-failed
          :file-path file-path
          :error (or (ex-message t) (.getName (class t)))})))))

;; System Effects

(defonce ^{:private true
           :doc "The function that ends the application (see set-exit-fn!)."}
  !exit-fn
  (atom nil))

(defn- effect-system-exit
  "Effect that exits the application through the function app/start! registered
   with set-exit-fn!. With none registered (unit tests, or before the app is
   started) it only logs, so a stray exit event can't kill the JVM."
  [_ _dispatch]
  (if-let [exit-fn @!exit-fn]
    (exit-fn)
    (log/warn "Exit requested, but no application is running; ignoring")))

(defn- effect-system-open-url
  "Effect that opens a URL in the system browser (off the agent thread)."
  [url _dispatch]
  (future
    (try
      (if (and (Desktop/isDesktopSupported)
               (.isSupported (Desktop/getDesktop) Desktop$Action/BROWSE))
        (.browse (Desktop/getDesktop) (URI. url))
        (log/warn "Opening URLs is not supported on this platform. URL:" url))
      (catch Throwable t
        (log/error "Could not open URL" url ":" (.getMessage t))))))

(defn- desktop-open!
  "Open dir in the OS's file manager through java.awt.Desktop: true, or
   false when this platform has no Desktop OPEN."
  [^File dir]
  (if (and (Desktop/isDesktopSupported)
           (.isSupported (Desktop/getDesktop) Desktop$Action/OPEN))
    (do (.open (Desktop/getDesktop) dir) true)
    false))

(defn- xdg-open!
  "Open dir with xdg-open (Linux desktops without Desktop OPEN): true when
   it ran and exited with 0 within 5 s."
  [^File dir]
  (let [process (.start (ProcessBuilder. ^java.util.List ["xdg-open" (.getAbsolutePath dir)]))]
    (and (.waitFor process 5 TimeUnit/SECONDS)
         (zero? (.exitValue process)))))

(def ^:private folder-opener
  "The platform calls :system/open-folder makes."
  {:desktop-open desktop-open!
   :xdg-open xdg-open!
   :os #(:os (addons/current-platform))})

(defonce ^{:private true
           :doc "Stand-ins for folder-opener's calls (see set-folder-opener!)."}
  !folder-opener
  (atom nil))

(defn set-folder-opener!
  "Replace some of the platform calls :system/open-folder makes, for tests:
   {:desktop-open (fn [dir] bool) :xdg-open (fn [dir] bool) :os (fn [] os)};
   nil puts the real ones back."
  [opener]
  (reset! !folder-opener opener))

(defn- try-open
  "(open dir), false when it throws (logged)."
  [how open dir]
  (try
    (boolean (open dir))
    (catch Throwable t
      (log/warn "Could not open" (.getAbsolutePath ^File dir) "with" how ":" (.getMessage t))
      false)))

(defn- effect-system-open-folder
  "Effect that opens a folder in the OS's file manager, off the agent
   thread (plans/2026-11-19-output-drivers/01 §4.4): it makes the folder if
   it is missing, then tries Desktop/open when OPEN is supported, then
   xdg-open on Linux. If none of that works it dispatches :on-failed with
   :path, the folder's full path, for a notice.
   Args: {:path :on-failed}. Returns the future."
  [{:keys [path on-failed]} dispatch]
  (future
    (let [dir (io/file (or path (addons/addons-dir)))
          {:keys [desktop-open xdg-open os]} (merge folder-opener @!folder-opener)
          opened? (try
                    (Files/createDirectories (.toPath dir) (make-array FileAttribute 0))
                    (or (try-open "Desktop" desktop-open dir)
                        (and (= :linux (os)) (try-open "xdg-open" xdg-open dir)))
                    (catch Throwable t
                      (log/warn "Could not make the folder" (.getAbsolutePath dir) ":" (.getMessage t))
                      false))]
      (when-not opened?
        (log/info "Could not open" (.getAbsolutePath dir) "; showing its path")
        (when on-failed
          (dispatch (assoc on-failed :path (.getAbsolutePath dir))))))))

(def ^:private dev-reload-fns
  {:styles 'laser-show.css.reload/reload-all-styles!
   :app 'laser-show.dev.reload/reload-app-code!})

(defn- effect-dev-reload
  "Effect that runs a dev-only reload (:styles or :app).
   These namespaces need tools.namespace (only on the :dev classpath), so a
   missing dependency is logged instead of thrown."
  [kind _dispatch]
  (try
    (let [result ((requiring-resolve (dev-reload-fns kind)))]
      (if (:success? result)
        (log/info "Dev reload" kind "complete:" result)
        (log/error "Dev reload" kind "failed:" (:error result))))
    (catch Throwable t
      (log/warn "Dev reload" kind "is unavailable (needs the :dev alias / tools.namespace):"
                (.getMessage t)))))

(defn- effect-dev-deliver
  "Effect that delivers events/flush!'s promise: every event queued before
   its :dev/flush has been handled."
  [p _dispatch]
  (deliver p true))

(defn- effect-dev-swap-state
  "Effect that does events/write-state!'s write, (apply state/swap-state! f
   args), on the event thread in this turn.
   Args: {:f :args}"
  [{:keys [f args]} _dispatch]
  (apply swap-app-state! f args))

;; Main-window effects

(defn main-stage
  "The app's main window: the showing stage whose scene root is
   #main-layout (views/root), or nil. Not any first stage: under TestFX
   another stage shows first. FX thread."
  ^Stage []
  (u/seek #(and (instance? Stage %)
                (= "main-layout" (some-> ^Stage % .getScene .getRoot .getId)))
          (Stage/getWindows)))

(defn- effect-window-set-fullscreen
  "Request fullscreen on the FX thread. The stage listener reports changes;
   if the request is refused or no stage exists, report that here instead."
  [on? dispatch]
  (let [report! #(dispatch {:event/type :view/fullscreen-changed :fullscreen? (boolean %)})]
    (try
      (Platform/runLater
       (fn []
         (try
           (if-let [stage (main-stage)]
             (do (.setFullScreen stage (boolean on?))
                 ;; The property listener reports transitions. A platform
                 ;; can refuse the request without changing the property.
                 (when (not= on? (.isFullScreen stage))
                   (report! (.isFullScreen stage))))
             (do (log/warn "Fullscreen: no main window")
                 (report! false)))
            (catch Exception t
             (log/warn "Could not change fullscreen:" (.getMessage t))
             (report! false)))))
      (catch IllegalStateException t
        (log/warn "Could not change fullscreen:" (.getMessage t))
        (report! false)))))

(defn- effect-show-file-chooser
  "Effect that shows a JavaFX FileChooser dialog.
   
   Parameters:
   - :title - Dialog title (string)
   - :mode - :open or :save (defaults to :open)
   - :initial-directory - Starting directory path (string)
   - :initial-file-name - Default filename for save mode (string)
   - :extension-filters - Vector of {:description string :extensions [\"*.ext\"]}
   - :on-result - Event map to dispatch with result, will have :file-path added
   
   The dialog runs on the JavaFX thread and dispatches the result event
   with :file-path set to the selected file path (or nil if cancelled)."
  [{:keys [title mode initial-directory initial-file-name extension-filters on-result]} dispatch]
  (Platform/runLater
   (fn []
     (try
       (let [chooser (FileChooser.)
              stage (main-stage)]

         ;; Set title
         (when title
           (.setTitle chooser title))

         ;; Set initial directory
         (when initial-directory
           (let [dir (File. initial-directory)]
             (when (.exists dir)
               (.setInitialDirectory chooser dir))))

         ;; Set initial filename (for save mode)
         (when initial-file-name
           (.setInitialFileName chooser initial-file-name))

         ;; Add extension filters
         (doseq [{:keys [description extensions]} extension-filters]
           (let [exts (into-array String extensions)
                 filter (FileChooser$ExtensionFilter. description exts)]
             (.add (.getExtensionFilters chooser) filter)))

         ;; Show dialog based on mode
         (let [selected-file (case mode
                               :save (.showSaveDialog chooser stage)
                               (.showOpenDialog chooser stage))
               file-path (when selected-file
                           (.getAbsolutePath selected-file))]

           (log/debug "File chooser result:" file-path)

           ;; Dispatch result event with file path
           (when on-result
             (dispatch (assoc on-result :file-path file-path)))))

       (catch Exception e
         (log/error "Error showing file chooser:" (.getMessage e))
         ;; Dispatch result with nil to indicate failure/cancellation
         (when on-result
           (dispatch (assoc on-result :file-path nil))))))))

;; Output Effects

(defn- effect-output-sync
  "Effect that queues a sync of the streaming engines with the projector
   snapshot the handler read ({:projectors …}), on the output lifecycle thread.
   The runtime publishes :output/status through dispatch."
  [{:keys [projectors]} dispatch]
  (output-runtime/submit-sync! projectors dispatch))

(defn- effect-output-write-status
  "Effect that stores the output runtime's status at its path (an atomic
   update of that path only). It runs after the event's :state
   (state-first), so a write-back can't undo it."
  [status _dispatch]
  (swap-app-state! assoc-in output-runtime/status-path status))

(defn- effect-output-stop
  "Effect that queues a stop of every streaming engine, on the output
   lifecycle thread."
  [_ dispatch]
  (output-runtime/submit-stop! dispatch))


;; Wrapped Event Handler

(defn- fx-event-placeholders
  "Paths in event whose value is the keyword :fx/event: a top-level key
   (other than :state and :fx/event), or a key one level inside :updates."
  [event]
  ;; identical?, as keywords are interned: = would realize the head of a lazy seq
  (let [placeholder? #(identical? :fx/event %)
        updates (:updates event)]
    (concat (for [[k v] event
                  :when (and (not (#{:state :fx/event} k)) (placeholder? v))]
              [k])
            (when (map? updates)
              (for [[k v] updates
                    :when (placeholder? v)]
                [:updates k])))))

(defn- with-fx-event
  "Pipeline step: cljfx gives a map event its value under the :fx/event key
   only, so a view may write the keyword :fx/event as a value (at the top
   level, or one level inside :updates) and the handler sees the value there.
   :state is never looked at. A placeholder in an event without :fx/event
   (dispatched by hand) stays as it is, with a warning."
  [handler]
  (fn [event]
    (let [paths (fx-event-placeholders event)]
      (cond
        (empty? paths)
        (handler event)

        (contains? event :fx/event)
        (handler (reduce #(assoc-in %1 %2 (:fx/event event)) event paths))

        :else
        (do (log/warn "Event" (:event/type event) "has :fx/event placeholders at" (vec paths)
                      "but no :fx/event value; they stay as they are")
            (handler event))))))

(def handle-event-pipeline
  "Event handler pipeline: the show lock (innermost: while it is on, the
   events it refuses change [:ui] only, events/show_lock.clj), the step
   grid window's control target (just outside the lock: the window's
   parameter controls name its pattern, found where it is now before the
   lock classifies it, handlers/step_grid.clj), timeline
   invariants enforced on changed cue chains (so history sees normalized
   state), undo history for undoable document edits, then the
   unsaved-changes flag derived for every event
   (see handlers/history.clj), then the safety versions: a change to a
   projector's safety zone (undo included) moves its versions on
   (safety-guard/wrap-safety-versions), then the output sync: a change to
   what the engines are built from (undo included) requests a refresh while
   streaming (handlers/output_sync.clj), and the rig autosave: a change to
   the rig (undo included) requests a write of the rig file
   (handlers/rig_autosave.clj). Then, in dev mode only, the lost-update check
   tags the :state effect with the event's snapshot. Outermost, with-fx-event
   puts a map event's :fx/event value where its view wrote the placeholder.

   app/create-app gives it, with :state first (ordered-pipeline) and for
   one app instance (instance-handler), to fx/create-app together with
   co-effects and effects, so it runs on the cljfx agent."
  (-> handlers/handle-event
      show-lock/wrap-show-lock
      step-grid-handlers/wrap-step-grid-control-target
      timeline-handlers/wrap-normalize-timelines
      history-handlers/wrap-history
      history-handlers/wrap-dirty
      safety-guard/wrap-safety-versions
      output-sync/wrap-output-sync
      rig-autosave/wrap-rig-autosave
      wrap-lost-update-check
      with-fx-event))

(defn- state-first
  "Wrap handler so its :state effect runs before its other effects: the
   result's :state entry, then the other entries in the map's own order, as
   a seq of map entries (cljfx's wrap-effects iterates it like the map). A
   write inside the turn (an inline follow-up, :output/write-status) then
   can't be undone by the event's write-back. A result without :state (nil
   and {} too) is returned unchanged."
  [handler]
  (fn [event]
    (let [result (handler event)]
      (if-let [entry (find result :state)]
        (cons entry (dissoc result :state))
        result))))

(def ordered-pipeline
  "handle-event-pipeline with its :state effect first (state-first). What
   event-handler runs, and an app's instance-handler while it is live."
  (state-first handle-event-pipeline))

(def co-effects
  "Co-effects injected into every event:
   - :state - current application state
   - :time - current timestamp"
  {:state co-effect-state
   :time co-effect-time})

(defn- guard-all
  "effects with each one doing nothing (logged) for an event of a retired
   app instance, but :dev/deliver: events/flush!'s promise is delivered
   anyway."
  [effects]
  (into {}
        (map (fn [[k consumer]]
               [k (if (= :dev/deliver k)
                    consumer
                    (fn [value dispatch]
                      (if-let [instance (retired)]
                        (dropped instance (str "its " k))
                        (consumer value dispatch))))]))
        effects))

(def effects
  "Effects handled for every event. Those marked (inline) continue the current
   event through run-inline; those marked (later) call back through dispatch
   from another thread, which with the app running queues on the agent.
   Each does nothing (logged) for an event of a retired app instance, but
   :dev/deliver (guard-all).
   - :state - update application state
   - :dispatch - continue with another event (inline)
   - :dispatch-later - dispatch event after delay (later, future)
   - :history/gesture-alarm - keep the open press's one wake-up: cancel
     its, arm the next; it dispatches :history/gesture-timeout (later,
     wake-up thread)
   - :link/sync-to-downbeat - align phase to Link's downbeat (inline)
   - :show-clock/read - continue :on-read with the show clock's beats, or
     with :chase-cell its timecode episode there (inline)
   - :input/await-learn - wait for a learn capture, then dispatch the binding (later, future)
   - :input/midi-list, :input/midi-connect, :input/midi-disconnect,
     :input/osc-start, :input/osc-stop - list the MIDI inputs, connect or
     disconnect one (or all), start or stop the OSC server, on the device
     worker (input.devices); its result comes back (later, input-devices
     thread)
   - :multi-engine/start, :multi-engine/refresh - queue a sync of the engines
     with {:projectors …} on the output thread; :output/status comes back
     later through dispatch (later, output-status thread)
   - :multi-engine/stop - queue a stop of every engine on the output thread
   - :output/write-status - store the output status at its path only
   - :project/save - write project zip to disk, then :project/mark-clean (inline)
   - :project/load - read project zip from disk, then :on-loaded (inline)
   - :rig/write - hand the rig file's contents to the rig writer; :rig/written
     or :rig/write-failed comes back (later, rig-writer thread)
   - :rig/flush - wait for the rig writer to finish what it was given (at
     most rig-store/flush-ms), then dispatch :then (later, future)
   - :addons/install - check an add-on jar and copy it into the add-ons
     folder as <name>.jar.new; :addons/installed or :addons/install-failed
     comes back (later, future)
   - :fx/show-file-chooser - show file open/save dialog (later, FX thread)
   - :window/set-fullscreen - set fullscreen on the main window (FX thread)
   - :system/exit - end the application through the fn registered with set-exit-fn!
   - :system/open-url - open a URL in the system browser
   - :system/open-folder - make a folder and open it in the file manager
     (Desktop, then xdg-open on Linux); :on-failed with its :path comes back
     when neither can (later, future)
   - :dev/reload - reload :styles or :app code (dev only)
   - :dev/deliver - deliver events/flush!'s promise (:dev/flush)
   - :dev/swap-state - events/write-state!'s write: (apply swap-state! f
     args) on the event thread, after this turn's :state (:dev/write-state)
   - :clipboard/write - put a clip's EDN on the OS clipboard (FX thread, no
     follow-up)
   - :clipboard/read - read the OS clipboard, then dispatch :then with
     :clipboard/system (later, FX thread)
   - :projectors/scan - scan for IDN devices and every driver's (later, future)"
  (guard-all
   {:state effect-state
    :dispatch effect-dispatch
    :dispatch-later effect-dispatch-later
    :link/sync-to-downbeat effect-link-sync-to-downbeat
    :show-clock/read effect-show-clock-read
    :input/await-learn effect-input-await-learn
    ;; Input devices (on the device worker)
    :input/midi-list (device-request :midi/list)
    :input/midi-connect (device-request :midi/connect)
    :input/midi-disconnect (device-request :midi/disconnect)
    :input/osc-start (device-request :osc/start)
    :input/osc-stop (device-request :osc/stop)
    ;; Streaming engines (queued on the output runtime)
    :multi-engine/start effect-output-sync
    :multi-engine/stop effect-output-stop
    :multi-engine/refresh effect-output-sync
    :output/write-status effect-output-write-status
    ;; Project persistence
    :project/save effect-save-project
    :project/load effect-load-project
    ;; The rig file
    :rig/write effect-rig-write
    :rig/flush effect-rig-flush
    ;; Add-ons
    :addons/install effect-addons-install
    ;; File chooser dialog
    :fx/show-file-chooser effect-show-file-chooser
    ;; The main window
    :window/set-fullscreen effect-window-set-fullscreen
    ;; System
    :system/exit effect-system-exit
    :system/open-url effect-system-open-url
    :system/open-folder effect-system-open-folder
    :dev/reload effect-dev-reload
    ;; Tooling: events/flush! and events/write-state!
    :dev/deliver effect-dev-deliver
    :dev/swap-state effect-dev-swap-state
    ;; The OS clipboard
    :clipboard/write effect-clipboard-write
    :clipboard/read effect-clipboard-read
    ;; Projector scanning effects
    :projectors/scan effect-projectors-scan}))

(def event-handler
  "The ordered pipeline wrapped with co-effects and effects, run
   synchronously on the calling thread (tests, and dispatch! without an app)."
  (-> ordered-pipeline
      (fx/wrap-co-effects co-effects)
      (fx/wrap-effects effects)))

;; The app's handler

(defn new-instance!
  "A new app instance, the live one from now on (the one before it is
   retired): {:id n :epoch e}, e the epoch of the context now (state/epoch),
   the only context its events write. app/create-app makes one for each
   app, after init-state!."
  []
  (reset! !live-instance {:id (swap! !instances inc) :epoch (state/epoch)}))

(defn retire!
  "Retire the live app instance (app/stop!): from now on its events are
   dropped as they come, and an effect of one still running does nothing
   (logged)."
  []
  (reset! !live-instance nil)
  nil)

(defn instance-handler
  "The event handler of app instance (new-instance!): app/create-app's
   :event-handler, so cljfx's handler for the app, the app's one dispatcher,
   runs it for every event, on the app's agent. It puts the instance on that
   agent's metadata, where the event's effects find it (current-instance),
   then runs ordered-pipeline while the instance is live. Once it is
   retired, an event is dropped as it comes (logged), but for
   events/flush!'s marker, delivered: every event queued before it has been
   handled, by being dropped."
  [instance]
  (fn [{:keys [event/type] :as event}]
    (when-let [a *agent*]
      (when-not (identical? instance (::instance (meta a)))
        (alter-meta! a assoc ::instance instance)))
    (cond
      (live? instance) (ordered-pipeline event)
      (= :dev/flush type) (do (deliver (:promise event) true) nil)
      :else (dropped instance type))))

;; The event thread

(def event-thread-name
  "The name of the one thread every app instance's events run on."
  "laser-show-events")

(defonce ^{:private true
           :doc "The thread event-executor runs on (set when it starts)."}
  !event-thread
  (atom nil))

(defonce ^{:doc "A single-thread executor with one daemon thread,
   event-thread-name. app/create-app gives it to cljfx as :fx/executor, so
   every app instance's agent runs its events here, one at a time: an old
   app's late event can't run alongside the new app's. defonce, so a dev
   code reload keeps the thread."}
  event-executor
  (Executors/newSingleThreadExecutor
   (reify ThreadFactory
     (newThread [_ r]
       (let [t (doto (Thread. ^Runnable r ^String event-thread-name)
                 (.setDaemon true))]
         (reset! !event-thread t)
         t)))))

(defn on-event-thread?
  "Is this the event thread (event-executor's)?"
  []
  (identical? (Thread/currentThread) @!event-thread))

;; Convenience Functions

(defonce ^:private *dispatch-fn (atom nil))

(defn set-dispatch-fn!
  "Set the dispatch function to use for dispatch!
   
   Called by app.clj after creating the app to enable dispatch!
   to route through the app's async event handler."
  [dispatch-fn]
  (reset! *dispatch-fn dispatch-fn))

(defn set-exit-fn!
  "Set the function the :system/exit effect calls (nil to clear it).
   app/start! registers the real one; tests register a recording fn so they
   can see an exit request without ending the JVM."
  [exit-fn]
  (reset! !exit-fn exit-fn))

(defn dispatch!
  "Dispatch an event through the app's async event handler (agent thread).
   
   When the app is running, events are queued to the agent and processed
   asynchronously with full co-effects injection and effects execution.
   The call returns immediately (non-blocking).
   
   Fallback: Before app initialization (or in tests when app not started),
   runs the event through event-handler synchronously on the calling thread,
   with every effect.
   
   Use cases:
   - Imperative event handlers in UI (drag/drop, context menus)
   - Non-UI event sources (MIDI, OSC, keyboard)
   - REPL testing (uses fallback when app not initialized)
   
   Usage:
   (dispatch! {:event/type :grid/trigger-cell :col 0 :row 0})"
  [event]
  (if-let [dispatch @*dispatch-fn]
    (dispatch event)
    (do
      (when-not (= :drag (:interaction/type event))
        (log/debug "dispatch! using FALLBACK for event:" (:event/type event)))
      (event-handler event))))

(defn flush!
  "Wait until every event queued before this call has been handled: true,
   or false when that took longer than timeout-ms (default 2000). It queues
   :dev/flush with a promise, which the event delivers when it runs. On the
   event thread or the FX thread it returns false at once: the event thread
   is busy with the event in flight, and the FX thread may be what that
   event waits for. Without an app the fallback is synchronous, so true at
   once. For tests and the REPL: (dispatch! event), then (flush!)."
  ([] (flush! 2000))
  ([timeout-ms]
   (if (or (on-event-thread?) (Platform/isFxApplicationThread))
     false
     (let [p (promise)]
       (dispatch! {:event/type :dev/flush :promise p})
       (deref p timeout-ms false)))))

(defn write-state!
  "(apply state/swap-state! f args) on the event thread, as the next event,
   then wait for it with flush!: for tests and the REPL, where a direct
   write while an event is in flight would be lost to its write-back. Like a
   direct write it adds no undo step, leaves the unsaved flag, the output
   sync and the rig file alone (:dev/write-state returns no :state, so no
   middleware sees it). Without an app it writes at once. Throws ex-info
   when the write wasn't handled within flush!'s timeout (or this is the
   event or FX thread, where flush! can't wait)."
  [f & args]
  (dispatch! {:event/type :dev/write-state :f f :args args})
  (when-not (flush!)
    (throw (ex-info "events/write-state!: the write was queued, but not handled within 2000 ms (or this is the event or FX thread)"
                    {:thread (.getName (Thread/currentThread))})))
  nil)
