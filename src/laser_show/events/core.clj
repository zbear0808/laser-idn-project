(ns laser-show.events.core
  "Event handling core - wraps pure handlers with co-effects and effects.
   
   This module provides:
   - Wrapped event handler with fx/wrap-co-effects and fx/wrap-effects
   - Effect implementations for side-effects (IDN streaming, timing, etc.)
   - The main event-handler to pass to fx/create-app
   - dispatch! for programmatic event dispatch
   
   Architecture:
   
   Event Flow:
   1. UI dispatches event map {:event/type :grid/trigger-cell :col 0 :row 0}
   2. Event is queued to agent (returns immediately, non-blocking)
   3. Agent thread: wrap-co-effects injects :state and :time into event
   4. Agent thread: Pure handler processes event, returns effects map
   5. Agent thread: wrap-effects executes side effects (:state update, etc.)
   6. State atom watch triggers re-render on FX thread
   
   Threading:
   - UI callbacks (inline fns) run briefly on FX thread, then delegate to agent
   - Co-effects, handlers, and effects all run on agent thread (NOT FX thread)
   - State updates (atom swap) are thread-safe
   - Re-rendering happens on FX thread via atom watch
   
   Benefits:
   - Pure handlers are easy to test
   - Side effects are isolated and explicit
   - State updates preserve cljfx context memoization
   - Heavy event processing doesn't block UI rendering"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.state.core :as state]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.events.handlers :as handlers]
            [laser-show.backend.streaming-engine :as streaming-engine]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.common.util :as u]
            [laser-show.idn.hello :as idn-hello]))

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

;; Effects (handle side effects FROM events)

(defn- effect-state
  "Effect that updates application state.
   Preserves cljfx context memoization."
  [new-state _dispatch]
  (state/reset-state! new-state))

(defn- effect-dispatch
  "Effect that dispatches another event.
   Useful for event chaining."
  [event dispatch]
  (dispatch event))

(defn- effect-dispatch-later
  "Effect that dispatches an event after a delay.
   Args: {:event event-map :delay-ms milliseconds}"
  [{:keys [event delay-ms]} dispatch]
  (future
    (Thread/sleep delay-ms)
    (dispatch event)))

(defn- effect-timing-calculate-bpm
  "Effect that calculates BPM from tap times and dispatches result."
  [_ dispatch]
  (let [state (state/get-raw-state)
        tap-times (get-in state [:timing :tap-times] [])
        tap-count (count tap-times)]
    (when (>= tap-count 2)
      ;; Calculate average interval between taps
      (let [recent-taps (take-last 8 tap-times)  ;; Use last 8 taps max
            intervals (mapv - (rest recent-taps) recent-taps)
            avg-interval (/ (reduce + intervals) (count intervals))
            bpm (/ 60000.0 avg-interval)
            ;; Clamp to reasonable range
            clamped-bpm (u/clamp bpm 40.0 300.0)]
        (dispatch {:event/type :timing/set-bpm :bpm clamped-bpm})))))

(defn- effect-idn-start-streaming
  "Effect that starts IDN streaming to a target.
   Creates a streaming engine with a frame provider that generates
   the same frames shown in the preview window."
  [{:keys [host port]} dispatch]
  (future
    (try
      (log/info (format "Starting IDN streaming to %s:%d..." host (or port 7255)))
      
      ;; Create frame provider - this returns the same frames as preview
      (let [frame-provider (frame-service/create-frame-provider)
            ;; Create streaming engine
            engine (streaming-engine/create-engine 
                     host 
                     frame-provider
                     :port (or port 7255)
                     :fps streaming-engine/DEFAULT_FPS)]
        ;; Start the engine
        (streaming-engine/start! engine)
        
        (log/info "IDN streaming started successfully")
        (dispatch {:event/type :idn/connected 
                   :engine engine
                   :target host}))
      (catch Exception e
        (log/error "IDN streaming failed:" (.getMessage e))
        (dispatch {:event/type :idn/connection-failed
                   :error (.getMessage e)})))))

(defn- effect-idn-stop-streaming
  "Effect that stops IDN streaming gracefully."
  [_ _dispatch]
  (log/info "Stopping IDN streaming...")
  (when-let [engine (get-in (state/get-raw-state) [:backend :idn :streaming-engine])]
    (when (and engine (not= engine :placeholder-engine))
      (try
        (streaming-engine/stop! engine)
        (log/info "IDN streaming stopped")
        (catch Exception e
          (log/error "Error stopping IDN streaming:" (.getMessage e)))))))

(defn- effect-save-project
  "Effect that saves the project to disk."
  [{:keys [folder]} dispatch]
  ;; TODO: Implement actual project saving
  (future
    (try
      ;; Save logic here
      (dispatch {:event/type :project/mark-clean})
      (catch Exception e
        (log/error "Failed to save project:" (.getMessage e))))))

(defn- effect-load-project
  "Effect that loads a project from disk."
  [{:keys [folder]} dispatch]
  ;; TODO: Implement actual project loading
  (future
    (try
      ;; Load logic here
      (dispatch {:event/type :project/set-folder :folder folder})
      (catch Exception e
        (log/error "Failed to load project:" (.getMessage e))))))

;; Clipboard Effects (for effect chain editor)

(defn- effect-clipboard-copy-effects
  "Effect that copies effects to clipboard as a chain."
  [effects _dispatch]
  (clipboard/copy-effect-chain! {:effects effects :active true}))

(defn- effect-clipboard-paste-effects
  "Effect that pastes effects from clipboard into a chain.
   Dispatches :effects/insert-pasted with the effects to insert."
  [{:keys [col row insert-pos]} dispatch]
  (when-let [effects (clipboard/get-effects-to-paste)]
    (dispatch {:event/type :effects/insert-pasted
               :col col
               :row row
               :insert-pos insert-pos
               :effects effects})))

(defn- effect-clipboard-paste-projector-effects
  "Effect that pastes effects from clipboard into a projector chain.
   Dispatches :projectors/insert-pasted-effects with the effects to insert."
  [{:keys [projector-id insert-pos]} dispatch]
  (when-let [effects (clipboard/get-effects-to-paste)]
    (dispatch {:event/type :projectors/insert-pasted-effects
               :projector-id projector-id
               :insert-pos insert-pos
               :effects effects})))

;; Projector Scanning Effects

(defn- effect-projectors-scan
  "Effect that scans the network for IDN devices using IDN-Hello protocol.
   Uses broadcast UDP to discover devices, then queries each device for
   available services/outputs via Service Map Request.
   
   Each device may have multiple laser output services (multi-head DACs)."
  [{:keys [broadcast-address]} dispatch]
  (future
    (try
      (log/info (format "Scanning network for IDN devices (broadcast: %s)..." broadcast-address))
      ;; Use service-aware discovery to enumerate outputs per device
      (let [discovered (idn-hello/discover-devices-with-services broadcast-address 3000 1000)
            ;; Transform discovered devices to the format expected by UI
            devices (mapv (fn [device]
                            {:address (:address device)
                             :host-name (let [name (:host-name device)]
                                          (if (str/blank? name)
                                            (:address device)
                                            name))
                             :unit-id (:unit-id device)
                             :port (or (:port device) 7255)
                             :status (:status device)
                             :protocol-version (:protocol-version device)
                             ;; Include discovered services (laser outputs)
                             :services (vec (:services device))
                             :relays (vec (:relays device))})
                          discovered)]
        (log/info (format "Found %d IDN device(s)" (count devices)))
        (doseq [device devices]
          (log/debug (format "  Device %s: %d service(s)"
                            (:address device)
                            (count (:services device)))))
        (dispatch {:event/type :projectors/scan-complete
                   :devices devices}))
      (catch Exception e
        (log/error "Network scan failed:" (.getMessage e))
        (log/debug e "Network scan stack trace")
        (dispatch {:event/type :projectors/scan-failed
                   :error (.getMessage e)})))))

;; Wrapped Event Handler

(def event-handler
  "Main event handler wrapped with co-effects and effects.
   
   This is the handler to pass to fx/create-app.
   
   Co-effects inject:
   - :state - current application state
   - :time - current timestamp
   
   Effects handle:
   - :state - update application state
   - :dispatch - dispatch another event
   - :dispatch-later - dispatch event after delay
   - :timing/calculate-bpm - calculate BPM from taps
   - :idn/start-streaming - start IDN streaming
   - :idn/stop-streaming - stop IDN streaming
   - :project/save - save project to disk
   - :project/load - load project from disk
   - :clipboard/copy-effects - copy effects to clipboard
   - :clipboard/paste-effects - paste effects from clipboard (effects grid)
   - :clipboard/paste-projector-effects - paste effects from clipboard (projector chain)
   - :projectors/scan - scan network for IDN devices"
  (-> handlers/handle-event
      
      ;; Inject co-effects into events
      (fx/wrap-co-effects
        {:state co-effect-state
         :time co-effect-time})
      
      ;; Handle effects from events
      (fx/wrap-effects
        {:state effect-state
         :dispatch effect-dispatch
         :dispatch-later effect-dispatch-later
         :timing/calculate-bpm effect-timing-calculate-bpm
         :idn/start-streaming effect-idn-start-streaming
         :idn/stop-streaming effect-idn-stop-streaming
         :project/save effect-save-project
         :project/load effect-load-project
         ;; Clipboard effects for effect chain editor
         :clipboard/copy-effects effect-clipboard-copy-effects
         :clipboard/paste-effects effect-clipboard-paste-effects
         :clipboard/paste-projector-effects effect-clipboard-paste-projector-effects
         ;; Projector scanning effects
         :projectors/scan effect-projectors-scan})))

;; Convenience Functions

(defonce ^:private *dispatch-fn (atom nil))

(defn set-dispatch-fn!
  "Set the dispatch function to use for dispatch!
   
   Called by app.clj after creating the app to enable dispatch!
   to route through the app's async event handler."
  [dispatch-fn]
  (reset! *dispatch-fn dispatch-fn))

(defn dispatch!
  "Dispatch an event through the app's async event handler (agent thread).
   
   When the app is running, events are queued to the agent and processed
   asynchronously with full co-effects injection and effects execution.
   The call returns immediately (non-blocking).
   
   Fallback: Before app initialization (or in tests when app not started),
   processes synchronously on the calling thread with basic effect handling.
   
   Use cases:
   - Imperative event handlers in UI (drag/drop, context menus)
   - Non-UI event sources (MIDI, OSC, keyboard)
   - REPL testing (uses fallback when app not initialized)
   
   Usage:
   (dispatch! {:event/type :grid/trigger-cell :col 0 :row 0})"
  [event]
  (if-let [dispatch @*dispatch-fn]
    ;; Use app's dispatch when available (async via agent)
    (do
      (log/debug "dispatch! using app dispatch-fn for event:" (:event/type event))
      (dispatch event))
    ;; Fallback: Manually inject co-effects and process effects
    ;; (used during testing or before app is initialized)
    (let [_ (log/debug "dispatch! using FALLBACK for event:" (:event/type event))
          enriched-event (assoc event
                               :state (state/get-raw-state)
                               :time (System/currentTimeMillis))
          effects (handlers/handle-event enriched-event)]
      (log/debug "dispatch! fallback - effects keys:" (keys effects))
      ;; Apply state effect if present
      (when-let [new-state (:state effects)]
        (log/debug "dispatch! fallback - applying :state effect")
        (state/reset-state! new-state))
      
      ;; Handle clipboard effects (for keyboard shortcuts in effect chain editor)
      (when-let [effects-to-copy (:clipboard/copy-effects effects)]
        (effect-clipboard-copy-effects effects-to-copy nil))
      
      (when-let [paste-params (:clipboard/paste-effects effects)]
        (effect-clipboard-paste-effects paste-params dispatch!))
      
      (when-let [paste-params (:clipboard/paste-projector-effects effects)]
        (effect-clipboard-paste-projector-effects paste-params dispatch!))
      
      ;; Handle projector scanning effect (for auto-scan on startup)
      (when-let [scan-params (:projectors/scan effects)]
        (effect-projectors-scan scan-params dispatch!))
      
      ;; Handle dispatch effect (for event chaining)
      (when-let [event-to-dispatch (:dispatch effects)]
        (log/debug "dispatch! fallback - processing :dispatch effect:" (:event/type event-to-dispatch))
        (try
          (dispatch! event-to-dispatch)
          (catch Exception e
            (log/error e "dispatch! fallback - error dispatching nested event"))))
      
      effects)))
