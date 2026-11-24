(ns laser-show.input.router
  "Event router for dispatching input events to registered handlers.
   Supports pattern-based subscriptions and multiple handlers per event type.

   It also has one slot for the learn attempt listening now (:learn-listener,
   reactivity plan 24): laser-show.input.learn fills and empties it,
   replace-learn-listener!, only for the attempt that has it. An input is
   offered to that listener first, from the same snapshot as the handlers
   it goes to next; while the attempt binds a control (anything but
   Settings' identify), a press reaches those handlers marked :learn-only?,
   so the input mapper doesn't play the key's old binding."
  (:require [clojure.tools.logging :as log]
            [laser-show.input.events :as events]
            [laser-show.input.store :as store]))

;; Continuous Input Store Integration

(defn set-continuous-value!
  "Sets a continuous input value in the fast store."
  [source-key value]
  (store/set-continuous-value! source-key value))

(defn get-continuous-value
  "Gets a continuous input value from the fast store."
  ([source-key]
   (store/get-continuous-value source-key))
  ([source-key default-val]
   (store/get-continuous-value source-key default-val)))

(defn get-all-continuous-values
  "Returns all continuous input values as a snapshot map."
  []
  (store/get-all-continuous-values))

(defn clear-continuous-values!
  "Clears all continuous input values."
  []
  (store/clear-continuous-values!))



;; Router State


(defonce !router-state
  (atom {:handlers {}           ; Map of handler-id -> {:pattern handler-fn}
         :global-handlers {}    ; Handlers that receive all events
         :learn-listener nil    ; The learn attempt listening now: {:learn-id :binding? :offer}, input.learn's
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
  "Removes all registered handlers. The learn slot is its attempt's
   (replace-learn-listener!)."
  []
  (swap! !router-state assoc :handlers {} :global-handlers {}))


;; The learn slot (reactivity plan 24)


(defn replace-learn-listener!
  "Put next-listener ({:learn-id :binding? :offer}, or nil for none) in the
   learn slot, only if the attempt in it now is expected-id's (nil: the
   slot is empty). {:displaced listener} (the one taken out, or nil) when
   it did; nil, changing nothing, when another attempt has the slot. The
   other handlers stay as they are."
  [expected-id next-listener]
  (let [owns? #(= expected-id (:learn-id (:learn-listener %)))
        [old _] (swap-vals! !router-state #(if (owns? %) (assoc % :learn-listener next-listener) %))]
    (when (owns? old)
      {:displaced (:learn-listener old)})))

(defn learn-listener-id
  "The :learn-id of the learn attempt in the slot, or nil."
  []
  (:learn-id (:learn-listener @!router-state)))


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

(defn- offered
  "event, after the learn listener of the snapshot (if any) was offered it:
   a press marked :learn-only? while that listener's attempt binds a
   control, whether or not it took the press, and whatever happens to the
   attempt meanwhile."
  [{:keys [learn-listener log-errors]} event]
  (if-let [{:keys [offer binding?]} learn-listener]
    (do (try
          (offer event)
          (catch Exception e
            (when log-errors
              (log/error "Error in the learn listener:" (.getMessage e)))))
        (cond-> event
          (and binding? (or (events/note-on? event) (events/trigger? event)))
          (assoc :learn-only? true)))
    event))

(defn dispatch!
  "Dispatches an event to all matching handlers, after the learn listener
   (offered). Every one of them is the one registered when it was called.
   Returns the event (useful for chaining)."
  [event]
  (let [state @!router-state
        log-errors? (:log-errors state)]
    (when (and event (:enabled? state))
      (log-event! event)
      (let [routed (offered state event)]
        ;; Call global handlers first
        (doseq [[_id handler-fn] (:global-handlers state)]
          (try
            (handler-fn routed)
            (catch Exception e
              (when log-errors?
                (log/error "Error in global handler:" (.getMessage e))))))

        ;; Call pattern-matched handlers
        (doseq [[_id {:keys [pattern handler]}] (:handlers state)]
          (when (events/matches? routed pattern)
            (try
              (handler routed)
              (catch Exception e
                (when log-errors?
                  (log/error "Error in handler:" (.getMessage e))))))))))
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
