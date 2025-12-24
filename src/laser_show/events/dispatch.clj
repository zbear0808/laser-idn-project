(ns laser-show.events.dispatch
  "Event system for the Laser Show application.
   Handles state transitions based on dispatched events.
   
   Events update state through service layer (for business logic)
   or directly through state.atoms (for simple operations).
   All events flow through middleware for logging, validation, and error handling."
  (:require [laser-show.state.atoms :as state]
            [laser-show.state.clipboard]
            [laser-show.services.grid-service :as grid-service]
            [laser-show.services.animation-service :as animation-service]
            [laser-show.events.middleware :as middleware]))

;; ============================================================================
;; Core Dispatch Handler
;; ============================================================================

(defn- dispatch-handler
  "Core dispatch logic - processes events and calls state/service functions.
   This is the raw handler WITHOUT middleware.
   
   Parameters:
   - event: Vector of [event-id & args]
   
   Returns: Result of the event handler, or nil for unknown events."
  [event]
  (let [[event-id & args] event]
    (case event-id
      ;; Grid Events - routed through grid-service for business logic
      :grid/select-cell (apply grid-service/select-cell! args)
      :grid/trigger-cell (apply grid-service/trigger-cell! args)
      :grid/stop-active (grid-service/stop-playback!)
      :grid/clear-cell (apply grid-service/clear-cell! args)
      :grid/set-preset (let [[col row preset-id] args]
                         (grid-service/set-cell-preset! col row preset-id))
      :grid/set-selected-preset (let [[preset-id] args]
                                  (when-let [selected (grid-service/get-selected-cell)]
                                    (grid-service/set-cell-preset! (first selected) (second selected) preset-id)))
      :grid/move-cell (let [[from-col from-row to-col to-row] args]
                        (grid-service/move-cell! from-col from-row to-col to-row))
      
      ;; Transport Events - use grid-service for coordination
      :transport/stop (grid-service/stop-playback!)
      :transport/play-pause (if (grid-service/playing?)
                              (grid-service/stop-playback!)
                              (when-let [active-cell (grid-service/get-active-cell)]
                                (let [[c r] active-cell]
                                  (grid-service/trigger-cell! c r))))
      
      ;; Clipboard Events - use grid-service for business logic
      :clipboard/copy-cell (let [[[col row]] args
                                 cell (grid-service/get-cell col row)]
                             (laser-show.state.clipboard/copy-cell-assignment! (:preset-id cell)))
      :clipboard/copy-selected (when-let [selected (grid-service/get-selected-cell)]
                                 (let [cell (grid-service/get-cell (first selected) (second selected))]
                                   (laser-show.state.clipboard/copy-cell-assignment! (:preset-id cell))))
      :clipboard/paste-cell (let [[[col row]] args]
                              (when-let [preset-id (laser-show.state.clipboard/paste-cell-assignment)]
                                (grid-service/set-cell-preset! col row preset-id)))
      :clipboard/paste-to-selected (when-let [selected (grid-service/get-selected-cell)]
                                     (when-let [preset-id (laser-show.state.clipboard/paste-cell-assignment)]
                                       (grid-service/set-cell-preset! (first selected) (second selected) preset-id)))
      
      ;; IDN Events
      :idn/set-connection-status (let [[connected? target engine] args]
                                   (state/set-idn-connection! connected? target engine))
      
      ;; Timing Events - use animation-service for BPM coordination
      :timing/set-bpm (let [[bpm] args] (animation-service/set-bpm! bpm))
      :timing/tap (animation-service/tap-tempo!)
      :timing/clear-taps (animation-service/reset-tap-tempo!)
      
      ;; Playback Events - use animation-service for playback coordination
      :playback/trigger (animation-service/retrigger!)
      :playback/set-trigger-time (let [[time-ms] args] (state/set-trigger-time! time-ms))
      
      ;; UI Component Events
      :ui/set-component (let [[component-key component] args]
                          (state/set-ui-component! component-key component))
      
      ;; Logging Events
      :logging/set-enabled (let [[enabled?] args] (state/set-logging-enabled! enabled?))
      
      ;; Default - unknown events return nil (middleware will log these)
      nil)))

;; ============================================================================
;; Public Dispatch Function (with middleware)
;; ============================================================================

(def dispatch!
  "Main dispatch function with middleware applied.
   
   All events flow through middleware in this order:
   1. wrap-logging - records event to internal log
   2. wrap-console-logging - prints event to console (excludes :tick)
   3. wrap-error-handling - catches and logs exceptions
   4. wrap-validation - validates event structure
   5. wrap-history - records event for history
   6. dispatch-handler - processes the event
   
   Usage: (dispatch! [:grid/trigger-cell 0 0])
   
   Parameters:
   - event: Vector of [event-id & args]"
  (middleware/create-dispatcher
    dispatch-handler
    {:logging true           ; Log all events to console
     :validation true        ; Validate event structure
     :history true           ; Record event history
     :error-handling true})) ; Catch and log errors
