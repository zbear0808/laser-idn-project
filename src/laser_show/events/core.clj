(ns laser-show.events.core
  "Event handling core - wraps pure handlers with co-effects and effects.
   
   This module provides:
   - Wrapped event handler with fx/wrap-co-effects and fx/wrap-effects
   - Effect implementations for side-effects (IDN streaming, timing, etc.)
   - The main event-handler to pass to fx/create-app
   
   Architecture:
   
   Event Flow:
   1. UI dispatches event map {:event/type :grid/trigger-cell :col 0 :row 0}
   2. wrap-co-effects injects :state and :time into event
   3. Pure handler processes event, returns effects map
   4. wrap-effects executes side effects (:state update, :idn/*, etc.)
   
   Benefits:
   - Pure handlers are easy to test
   - Side effects are isolated and explicit
   - State updates preserve cljfx context memoization"
  (:require [cljfx.api :as fx]
            [laser-show.state.core :as state]
            [laser-show.events.handlers :as handlers]
            [laser-show.backend.streaming-engine :as streaming-engine]
            [laser-show.services.frame-service :as frame-service]))

;; ============================================================================
;; Co-effects (inject data INTO events)
;; Co-effect functions take NO arguments and return data to inject.
;; ============================================================================

(defn- co-effect-state
  "Co-effect that injects current state into event."
  []
  (state/get-state))

(defn- co-effect-time
  "Co-effect that injects current timestamp into event."
  []
  (System/currentTimeMillis))

;; ============================================================================
;; Effects (handle side effects FROM events)
;; ============================================================================

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
  (let [state (state/get-state)
        tap-times (get-in state [:timing :tap-times] [])
        tap-count (count tap-times)]
    (when (>= tap-count 2)
      ;; Calculate average interval between taps
      (let [recent-taps (take-last 8 tap-times)  ;; Use last 8 taps max
            intervals (mapv - (rest recent-taps) recent-taps)
            avg-interval (/ (reduce + intervals) (count intervals))
            bpm (/ 60000.0 avg-interval)
            ;; Clamp to reasonable range
            clamped-bpm (max 40.0 (min 300.0 bpm))]
        (dispatch {:event/type :timing/set-bpm :bpm clamped-bpm})))))

(defn- effect-idn-start-streaming
  "Effect that starts IDN streaming to a target.
   Creates a streaming engine with a frame provider that generates
   the same frames shown in the preview window."
  [{:keys [host port]} dispatch]
  (future
    (try
      (println (format "Starting IDN streaming to %s:%d..." host (or port 7255)))
      
      ;; Create frame provider - this returns the same frames as preview
      (let [frame-provider (frame-service/create-frame-provider)
            ;; Create streaming engine
            engine (streaming-engine/create-engine 
                     host 
                     frame-provider
                     :port (or port 7255)
                     :fps 30)]
        ;; Start the engine
        (streaming-engine/start! engine)
        
        (println "IDN streaming started successfully")
        (dispatch {:event/type :idn/connected 
                   :engine engine
                   :target host}))
      (catch Exception e
        (println "IDN streaming failed:" (.getMessage e))
        (dispatch {:event/type :idn/connection-failed
                   :error (.getMessage e)})))))

(defn- effect-idn-stop-streaming
  "Effect that stops IDN streaming gracefully."
  [_ _dispatch]
  (println "Stopping IDN streaming...")
  (when-let [engine (get-in (state/get-state) [:backend :idn :streaming-engine])]
    (when (and engine (not= engine :placeholder-engine))
      (try
        (streaming-engine/stop! engine)
        (println "IDN streaming stopped")
        (catch Exception e
          (println "Error stopping IDN streaming:" (.getMessage e)))))))

(defn- effect-save-project
  "Effect that saves the project to disk."
  [{:keys [folder]} dispatch]
  ;; TODO: Implement actual project saving
  (future
    (try
      ;; Save logic here
      (dispatch {:event/type :project/mark-clean})
      (catch Exception e
        (println "Failed to save project:" (.getMessage e))))))

(defn- effect-load-project
  "Effect that loads a project from disk."
  [{:keys [folder]} dispatch]
  ;; TODO: Implement actual project loading
  (future
    (try
      ;; Load logic here
      (dispatch {:event/type :project/set-folder :folder folder})
      (catch Exception e
        (println "Failed to load project:" (.getMessage e))))))

;; ============================================================================
;; Wrapped Event Handler
;; ============================================================================

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
   - :project/load - load project from disk"
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
         :project/load effect-load-project})))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn dispatch!
  "Dispatch an event directly, bypassing the wrapped handler.
   
   This is useful for:
   - REPL testing
   - Non-UI event sources (MIDI, OSC, keyboard)
   - Imperative event handlers in UI (drag/drop, context menus)
   
   Usage:
   (dispatch! {:event/type :grid/trigger-cell :col 0 :row 0})"
  [event]
  ;; Manually inject co-effects and process effects
  (let [enriched-event (assoc event 
                               :state (state/get-state)
                               :time (System/currentTimeMillis))
        effects (handlers/handle-event enriched-event)]
    ;; Apply state effect if present
    (when-let [new-state (:state effects)]
      (state/reset-state! new-state))
    ;; Could also handle other effects here
    effects))

(defn dispatch-sync!
  "Dispatch an event and wait for effects to complete.
   Mainly for testing."
  [event]
  (dispatch! event))

;; ============================================================================
;; Event Helpers
;; ============================================================================

(defn grid-trigger-event
  "Create a grid trigger event."
  [col row]
  {:event/type :grid/trigger-cell :col col :row row})

(defn grid-select-event
  "Create a grid select event."
  [col row]
  {:event/type :grid/select-cell :col col :row row})

(defn timing-set-bpm-event
  "Create a set BPM event."
  [bpm]
  {:event/type :timing/set-bpm :bpm bpm})

(defn ui-tab-event
  "Create a tab change event."
  [tab]
  {:event/type :ui/set-active-tab :tab tab})

(comment
  ;; Test dispatching events
  (require '[laser-show.state.core :as state]
           '[laser-show.state.domains :as domains])
  
  ;; Initialize state first
  (state/init-state! (domains/build-initial-state))
  
  ;; Dispatch events
  (dispatch! {:event/type :timing/set-bpm :bpm 140.0})
  (state/get-in-state [:timing :bpm]) ;; => 140.0
  
  (dispatch! {:event/type :grid/trigger-cell :col 0 :row 0})
  (state/get-in-state [:playback :active-cell]) ;; => [0 0]
  
  (dispatch! {:event/type :transport/stop})
  (state/get-in-state [:playback :playing?]) ;; => false
  )
