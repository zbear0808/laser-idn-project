(ns laser-show.events.middleware
  "Event middleware for the Laser Show application.
   
   Middleware functions wrap event dispatch to add cross-cutting concerns:
   - Logging: Record all events for debugging
   - Validation: Ensure events have valid structure
   - History: Track events for undo/redo support
   - Timing: Measure event processing time
   
   Middleware is applied in order: first middleware wraps outermost,
   so events pass through middleware in reverse order.
   
   Usage:
   (def dispatch-with-middleware
     (-> handle-event
         (wrap-logging)
         (wrap-validation)
         (wrap-history)))
   
   (dispatch-with-middleware [:grid/select-cell 0 0])")

;; ============================================================================
;; Event History (for undo/redo support)
;; ============================================================================

(defonce ^{:doc "Atom containing event history for undo/redo support."}
  !event-history 
  (atom {:events []
         :position 0
         :max-size 100}))

(defn get-event-history
  "Get all recorded events.
   Returns: Vector of event records."
  []
  (:events @!event-history))

(defn get-history-position
  "Get current position in event history.
   Returns: Index of current position."
  []
  (:position @!event-history))

(defn clear-event-history!
  "Clear all event history."
  []
  (swap! !event-history assoc :events [] :position 0))

(defn- add-to-history!
  "Add an event to history, truncating future events if we've gone back."
  [event-record]
  (swap! !event-history
         (fn [{:keys [events position max-size]}]
           (let [truncated (subvec events 0 position)
                 new-events (conj truncated event-record)
                 ;; Keep only max-size events
                 trimmed (if (> (count new-events) max-size)
                           (subvec new-events (- (count new-events) max-size))
                           new-events)]
             {:events trimmed
              :position (count trimmed)
              :max-size max-size}))))

(defn can-undo?
  "Check if undo is available.
   Returns: true if we can go back in history."
  []
  (> (:position @!event-history) 0))

(defn can-redo?
  "Check if redo is available.
   Returns: true if we can go forward in history."
  []
  (let [{:keys [events position]} @!event-history]
    (< position (count events))))

(defn get-undo-event
  "Get the event that would be undone (for preview).
   Returns: Event record or nil."
  []
  (let [{:keys [events position]} @!event-history]
    (when (> position 0)
      (nth events (dec position)))))

(defn get-redo-event
  "Get the event that would be redone (for preview).
   Returns: Event record or nil."
  []
  (let [{:keys [events position]} @!event-history]
    (when (< position (count events))
      (nth events position))))

;; ============================================================================
;; Undo/Redo Implementation
;; ============================================================================

(def ^:private inverse-events
  "Map of event types to their inverse events.
   For events that modify state, defines how to reverse them."
  {:grid/set-preset     :grid/clear-cell
   :grid/clear-cell     :grid/set-preset
   :grid/select-cell    :grid/select-cell
   :grid/trigger-cell   :grid/stop-active
   :grid/stop-active    nil  ; No automatic inverse
   :timing/set-bpm      :timing/set-bpm})

(defonce ^{:doc "Atom containing state snapshots for undo/redo."}
  !state-snapshots
  (atom {:snapshots []
         :position 0
         :max-size 50}))

(defn snapshot-state!
  "Take a snapshot of the current state for undo/redo.
   Parameters:
   - state-fn: A function that returns the current state to snapshot"
  [state-fn]
  (let [current-state (state-fn)]
    (swap! !state-snapshots
           (fn [{:keys [snapshots position max-size]}]
             (let [truncated (subvec snapshots 0 position)
                   new-snapshots (conj truncated current-state)
                   trimmed (if (> (count new-snapshots) max-size)
                             (subvec new-snapshots (- (count new-snapshots) max-size))
                             new-snapshots)]
               {:snapshots trimmed
                :position (count trimmed)
                :max-size max-size})))))

(defn get-snapshot-count
  "Get the number of state snapshots available."
  []
  (count (:snapshots @!state-snapshots)))

(defn clear-snapshots!
  "Clear all state snapshots."
  []
  (reset! !state-snapshots {:snapshots [] :position 0 :max-size 50}))

(defn undo!
  "Move back one step in history.
   Parameters:
   - restore-fn: Function to restore state from snapshot (fn [snapshot] ...)
   Returns: The snapshot that was restored, or nil if cannot undo."
  [restore-fn]
  (let [{:keys [snapshots position]} @!state-snapshots]
    (when (> position 1)  ; Need at least 2 snapshots (current + previous)
      (let [prev-position (- position 2)  ; -2 because position points to next slot
            prev-snapshot (nth snapshots prev-position)]
        (swap! !state-snapshots assoc :position (inc prev-position))
        (when restore-fn
          (restore-fn prev-snapshot))
        prev-snapshot))))

(defn redo!
  "Move forward one step in history.
   Parameters:
   - restore-fn: Function to restore state from snapshot (fn [snapshot] ...)
   Returns: The snapshot that was restored, or nil if cannot redo."
  [restore-fn]
  (let [{:keys [snapshots position]} @!state-snapshots]
    (when (< position (count snapshots))
      (let [next-snapshot (nth snapshots position)]
        (swap! !state-snapshots update :position inc)
        (when restore-fn
          (restore-fn next-snapshot))
        next-snapshot))))

(defn can-undo-snapshot?
  "Check if undo is available (snapshot-based).
   Returns: true if we can go back."
  []
  (> (:position @!state-snapshots) 1))

(defn can-redo-snapshot?
  "Check if redo is available (snapshot-based).
   Returns: true if we can go forward."
  []
  (let [{:keys [snapshots position]} @!state-snapshots]
    (< position (count snapshots))))

(defn move-history-position!
  "Move the history position without restoring state.
   Parameters:
   - direction: :back or :forward"
  [direction]
  (swap! !event-history
         (fn [{:keys [events position] :as state}]
           (case direction
             :back (if (> position 0)
                     (assoc state :position (dec position))
                     state)
             :forward (if (< position (count events))
                        (assoc state :position (inc position))
                        state)
             state))))

;; ============================================================================
;; Event Log (for debugging)
;; ============================================================================

(defonce ^{:doc "Atom containing recent events for debugging."}
  !event-log
  (atom {:enabled true
         :events []
         :max-size 1000}))

(defn enable-event-logging!
  "Enable or disable event logging.
   Parameters:
   - enabled: true to enable, false to disable"
  [enabled]
  (swap! !event-log assoc :enabled enabled))

(defn event-logging-enabled?
  "Check if event logging is enabled.
   Returns: true if enabled."
  []
  (:enabled @!event-log))

(defn get-event-log
  "Get all logged events.
   Returns: Vector of event log entries."
  []
  (:events @!event-log))

(defn clear-event-log!
  "Clear the event log."
  []
  (swap! !event-log assoc :events []))

(defn- log-event!
  "Add an event to the log."
  [event-entry]
  (when (:enabled @!event-log)
    (swap! !event-log
           (fn [{:keys [events max-size] :as state}]
             (let [new-events (conj events event-entry)
                   trimmed (if (> (count new-events) max-size)
                             (subvec new-events (- (count new-events) max-size))
                             new-events)]
               (assoc state :events trimmed))))))

;; ============================================================================
;; Event Validation
;; ============================================================================

(def ^:private event-schemas
  "Schema definitions for known events.
   Each entry is a map with:
   - :args - expected argument count
   - :validator - optional function that validates the args (receives args vector)"
  {;; Grid Events
   :grid/select-cell {:args 2 :validator (fn [[col row]] (and (integer? col) (integer? row)))}
   :grid/trigger-cell {:args 2 :validator (fn [[col row]] (and (integer? col) (integer? row)))}
   :grid/stop-active {:args 0}
   :grid/clear-cell {:args 2 :validator (fn [[col row]] (and (integer? col) (integer? row)))}
   :grid/set-preset {:args 3 :validator (fn [[col row preset-id]] 
                                          (and (integer? col) (integer? row) (keyword? preset-id)))}
   :grid/set-selected-preset {:args 1 :validator (fn [[preset-id]] (keyword? preset-id))}
   :grid/move-cell {:args 4 :validator (fn [[fc fr tc tr]] 
                                         (and (integer? fc) (integer? fr) 
                                              (integer? tc) (integer? tr)))}
   
   ;; Transport Events
   :transport/stop {:args 0}
   :transport/play-pause {:args 0}
   
   ;; Timing Events
   :timing/set-bpm {:args 1 :validator (fn [[bpm]] (number? bpm))}
   :timing/tap {:args 0}  ; tap-tempo! takes no args - it uses current time internally
   :timing/clear-taps {:args 0}
   
   ;; Playback Events
   :playback/trigger {:args 0}
   :playback/set-trigger-time {:args 1 :validator (fn [[ts]] (number? ts))}
   
   ;; Clipboard Events
   :clipboard/copy-cell {:args 1 :validator (fn [[coords]] (and (vector? coords) (= 2 (count coords))))}
   :clipboard/copy-selected {:args 0}
   :clipboard/paste-cell {:args 1 :validator (fn [[coords]] (and (vector? coords) (= 2 (count coords))))}
   :clipboard/paste-to-selected {:args 0}
   
   ;; UI Events
   :ui/set-component {:args 2 :validator (fn [[key _component]] (keyword? key))}
   
   ;; IDN Events
   :idn/set-connection-status {:args 3 :validator (fn [[connected? _target _engine]] (boolean? connected?))}
   
   ;; Logging Events
   :logging/set-enabled {:args 1 :validator (fn [[enabled?]] (boolean? enabled?))}})

(defn validate-event
  "Validate an event against its schema.
   Parameters:
   - event: Event vector [event-id & args]
   Returns: {:valid? true/false :errors [...]}"
  [[event-id & args :as event]]
  (if-let [schema (get event-schemas event-id)]
    (let [expected-args (:args schema)
          actual-args (count args)
          validator (:validator schema)
          arg-count-ok? (= expected-args actual-args)
          validator-ok? (if (and arg-count-ok? validator)
                          (validator args)
                          true)
          errors (cond-> []
                   (not arg-count-ok?) 
                   (conj (str "Expected " expected-args " args, got " actual-args))
                   (not validator-ok?)
                   (conj "Argument validation failed"))]
      {:valid? (empty? errors)
       :errors errors})
    ;; Unknown events are considered valid (extensibility)
    {:valid? true :errors []}))

;; ============================================================================
;; Middleware Functions
;; ============================================================================

(defn wrap-logging
  "Middleware that logs all events.
   Parameters:
   - handler: The next handler function
   Returns: Wrapped handler function"
  [handler]
  (fn [event]
    (let [start-time (System/currentTimeMillis)
          result (handler event)
          end-time (System/currentTimeMillis)
          duration (- end-time start-time)]
      (log-event! {:event event
                   :timestamp start-time
                   :duration-ms duration
                   :success (not (instance? Exception result))})
      result)))

(defn wrap-console-logging
  "Middleware that prints events to console.
   Parameters:
   - handler: The next handler function
   - opts: Options map
     - :exclude - Set of event-ids to exclude from logging (default #{:tick})
   Returns: Wrapped handler function"
  ([handler]
   (wrap-console-logging handler {:exclude #{:tick}}))
  ([handler {:keys [exclude] :or {exclude #{:tick}}}]
   (fn [event]
     (let [[event-id] event]
       (when-not (contains? exclude event-id)
         (println "Event:" event)))
     (handler event))))

(defn wrap-validation
  "Middleware that validates events before processing.
   Invalid events are logged but not processed.
   Parameters:
   - handler: The next handler function
   Returns: Wrapped handler function"
  [handler]
  (fn [event]
    (let [{:keys [valid? errors]} (validate-event event)]
      (if valid?
        (handler event)
        (do
          (println "Invalid event:" event "Errors:" errors)
          nil)))))

(defn wrap-history
  "Middleware that records events to history for undo/redo.
   Parameters:
   - handler: The next handler function
   - opts: Options map
     - :exclude - Set of event-ids to exclude from history
   Returns: Wrapped handler function"
  ([handler]
   (wrap-history handler {:exclude #{:tick :ui/set-component}}))
  ([handler {:keys [exclude] :or {exclude #{:tick :ui/set-component}}}]
   (fn [event]
     (let [[event-id] event
           result (handler event)]
       (when-not (contains? exclude event-id)
         (add-to-history! {:event event
                           :timestamp (System/currentTimeMillis)}))
       result))))

(defn wrap-error-handling
  "Middleware that catches and logs errors.
   Parameters:
   - handler: The next handler function
   Returns: Wrapped handler function"
  [handler]
  (fn [event]
    (try
      (handler event)
      (catch Exception e
        (println "Error processing event:" event)
        (println "Exception:" (.getMessage e))
        (log-event! {:event event
                     :timestamp (System/currentTimeMillis)
                     :error (.getMessage e)})
        nil))))

(defn wrap-timing
  "Middleware that measures event processing time.
   Prints a warning if processing takes longer than threshold.
   Parameters:
   - handler: The next handler function
   - threshold-ms: Warning threshold in milliseconds (default 16ms for 60fps)"
  ([handler]
   (wrap-timing handler 16))
  ([handler threshold-ms]
   (fn [event]
     (let [start (System/nanoTime)
           result (handler event)
           end (System/nanoTime)
           duration-ms (/ (- end start) 1000000.0)]
       (when (> duration-ms threshold-ms)
         (println "Slow event:" (first event) "took" duration-ms "ms"))
       result))))

;; ============================================================================
;; Middleware Composition
;; ============================================================================

(defn compose-middleware
  "Compose multiple middleware functions.
   Middleware is applied in order: first middleware wraps outermost.
   
   Parameters:
   - handler: The base handler function
   - middlewares: Sequence of middleware functions
   
   Returns: Fully wrapped handler function"
  [handler & middlewares]
  (reduce (fn [h mw] (mw h)) handler middlewares))

(defn create-dispatcher
  "Create a dispatcher with standard middleware.
   
   Parameters:
   - handler: The base event handler function
   - opts: Options map
     - :logging - Enable console logging (default true)
     - :validation - Enable validation (default true)
     - :history - Enable history tracking (default true)
     - :error-handling - Enable error handling (default true)
     - :timing - Enable timing warnings (default false)
   
   Returns: Dispatcher function"
  [handler & [{:keys [logging validation history error-handling timing]
               :or {logging true
                    validation true
                    history true
                    error-handling true
                    timing false}}]]
  (cond-> handler
    timing (wrap-timing)
    history (wrap-history)
    validation (wrap-validation)
    error-handling (wrap-error-handling)
    logging (wrap-console-logging)
    true (wrap-logging)))
