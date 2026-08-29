(ns laser-show.input.router
  "Event router for dispatching input events to registered handlers.
   Supports pattern-based subscriptions and multiple handlers per event type."
  (:require [clojure.tools.logging :as log]
            [laser-show.input.events :as events]))


;; Router State


(defonce !router-state
  (atom {:handlers {}           ; Map of handler-id -> {:pattern handler-fn}
         :global-handlers {}    ; Handlers that receive all events
         :enabled? true          ; Global enable/disable
         :event-log []          ; Recent events for debugging
         :log-enabled false     ; Whether to log events
         :log-errors true       ; Whether to log handler errors
         :max-log-size 100}))   ; Max events to keep in log


;; Handler Registration


(defn register-handler!
  "Registers a handler function for events matching a pattern.
   - handler-id: Unique keyword identifying this handler
   - pattern: Map of event keys to match (e.g., {:type :note-on :channel 0})
              Use nil or {} to match all events
   - handler-fn: Function called with (handler-fn event)
   Returns the handler-id."
  [handler-id pattern handler-fn]
  (swap! !router-state assoc-in [:handlers handler-id] 
         {:pattern (or pattern {})
          :handler handler-fn})
  handler-id)

(defn register-global-handler!
  "Registers a handler that receives ALL events regardless of pattern.
   Useful for logging, debugging, or event recording.
   - handler-id: Unique keyword identifying this handler
   - handler-fn: Function called with (handler-fn event)"
  [handler-id handler-fn]
  (swap! !router-state assoc-in [:global-handlers handler-id] handler-fn)
  handler-id)

(defn unregister-handler!
  "Removes a handler by its ID."
  [handler-id]
  (swap! !router-state 
         (fn [state]
           (-> state
               (update :handlers dissoc handler-id)
               (update :global-handlers dissoc handler-id)))))

(defn clear-handlers!
  "Removes all registered handlers."
  []
  (swap! !router-state assoc :handlers {} :global-handlers {}))


;; Event Dispatch


(defn- log-event!
  "Adds event to the event log if logging is enabled."
  [event]
  (when (:log-enabled @!router-state)
    (swap! !router-state 
           (fn [state]
             (let [max-size (:max-log-size state)
                   new-log (conj (:event-log state) event)]
               (assoc state :event-log 
                      (if (> (count new-log) max-size)
                        (vec (take-last max-size new-log))
                        new-log)))))))

(defn dispatch!
  "Dispatches an event to all matching handlers.
   Returns the event (useful for chaining)."
  [event]
  (let [state @!router-state
        log-errors? (:log-errors state)]
    (when (and event (:enabled? state))
      (log-event! event)
      
      ;; Call global handlers first
      (doseq [[_id handler-fn] (:global-handlers state)]
        (try
          (handler-fn event)
          (catch Exception e
            (when log-errors?
              (log/error "Error in global handler:" (.getMessage e))))))
      
      ;; Call pattern-matched handlers
      (doseq [[_id {:keys [pattern handler]}] (:handlers state)]
        (when (events/matches? event pattern)
          (try
            (handler event)
            (catch Exception e
              (when log-errors?
                (log/error "Error in handler:" (.getMessage e)))))))))
  event)

(defn dispatch-many!
  "Dispatches multiple events in sequence."
  [events]
  (doseq [event events]
    (dispatch! event)))


;; Router Control


(defn enable!
  "Enables event routing."
  []
  (swap! !router-state assoc :enabled? true))

(defn disable!
  "Disables event routing (events will be ignored)."
  []
  (swap! !router-state assoc :enabled? false))




;; Event Logging / Debugging


(defn enable-logging!
  "Enables event logging for debugging."
  []
  (swap! !router-state assoc :log-enabled true))

(defn disable-logging!
  "Disables event logging."
  []
  (swap! !router-state assoc :log-enabled false))

(defn enable-error-logging!
  "Enables error logging for handler exceptions."
  []
  (swap! !router-state assoc :log-errors true))

(defn disable-error-logging!
  "Disables error logging for handler exceptions (useful in tests)."
  []
  (swap! !router-state assoc :log-errors false))

(defn get-event-log
  "Returns the recent event log."
  []
  (:event-log @!router-state))

(defn clear-event-log!
  "Clears the event log."
  []
  (swap! !router-state assoc :event-log []))


;; Convenience Macros and Functions


(defn on-note!
  "Registers a handler for note-on events on a specific channel/note.
   If note is nil, handles all notes on the channel.
   If channel is nil, handles all channels."
  [handler-id channel note handler-fn]
  (let [pattern (cond-> {:type :note-on}
                  channel (assoc :channel channel)
                  note (assoc :note note))]
    (register-handler! handler-id pattern handler-fn)))

(defn on-control!
  "Registers a handler for control change events on a specific channel/control.
   If control is nil, handles all controls on the channel.
   If channel is nil, handles all channels."
  [handler-id channel control handler-fn]
  (let [pattern (cond-> {:type :control-change}
                  channel (assoc :channel channel)
                  control (assoc :control control))]
    (register-handler! handler-id pattern handler-fn)))

(defn on-trigger!
  "Registers a handler for trigger events with a specific ID."
  [handler-id trigger-id handler-fn]
  (register-handler! handler-id {:type :trigger :id trigger-id} handler-fn))

(defn on-any!
  "Registers a handler for any event from a specific source.
   source can be :midi, :osc, :keyboard, or nil for all sources."
  [handler-id source handler-fn]
  (let [pattern (if source {:source source} {})]
    (register-handler! handler-id pattern handler-fn)))


;; Handler Info


(defn list-handlers
  "Returns a list of all registered handler IDs and their patterns."
  []
  (let [{:keys [handlers global-handlers]} @!router-state]
    {:handlers (update-vals handlers :pattern)
     :global-handlers (keys global-handlers)}))

(defn handler-count
  "Returns the total number of registered handlers."
  []
  (let [{:keys [handlers global-handlers]} @!router-state]
    (+ (count handlers)
       (count global-handlers))))
