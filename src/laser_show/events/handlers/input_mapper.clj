(ns laser-show.events.handlers.input-mapper
  "High-performance generic input mapper.
   
   Listens to all global input events from the generic router and:
   1. Continuous events (MIDI CC, OSC floats) -> updates fast input store (`laser-show.input.store`)
   2. Discrete events (Note ON, Key pressed) -> performs O(1) lookup in `[:config :input :trigger-map]`
      and invokes the mapped action, unless a learn is waiting for an input
      ([:backend :input :learning-target]): that one is the learn's.
   
   State updates bypass cljfx's event queue to ensure the lowest possible latency
   and avoid blocking the UI."
  (:require [clojure.tools.logging :as log]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]
            [laser-show.input.events :as input-events]
            [laser-show.state.core :as state]))

(defonce ^:private *dispatch-fn (atom nil))

(defn set-dispatch-fn!
  "Sets the application event dispatcher used for discrete trigger events."
  [dispatch-fn]
  (reset! *dispatch-fn dispatch-fn))


;; --- State Update Helpers ---

(defn- update-input-value!
  "Updates a continuous input value in the fast store.
   e.g. source-key `[:midi 0 10]` -> value `0.5`"
  [source-key value]
  (store/set-continuous-value! source-key value))

(defn- learning?
  "Is a learn waiting for an input? It catches the press itself
   (handlers/input.clj), so the press mustn't also fire the key's old
   binding (keyboard plan K-15), nor its release."
  []
  (some? (get-in (state/get-raw-state) [:backend :input :learning-target])))


;; --- Main Event Processor ---

(defn- process-input-event!
  "Process a single input event.
   Must be fast and non-blocking."
  [event]
  (cond
    ;; Continuous Events
    (input-events/control-change? event)
    (let [{:keys [source channel control path value]} event
          val (double (or value 0.0))]
      (case source
        :midi
        (update-input-value! [:midi (or channel 0) control] val)

        :osc
        (do
          (when path
            (update-input-value! [:osc path] val))
          (when (some? control)
            (update-input-value! [:osc (or channel 0) control] val)))

        ;; Default / other sources
        (do
          (when path
            (update-input-value! [:input path] val))
          (when (some? control)
            (update-input-value! [source (or channel 0) control] val)))))

    ;; Discrete triggers are resolved by the normal application event pipeline.
    (or (input-events/note-on? event)
        (input-events/note-off? event)
        (input-events/trigger? event)
        (input-events/trigger-release? event))
    (do
      ;; Unmapped OSC addresses arrive as triggers carrying their raw value;
      ;; keep it available to modulators under [:osc path].
      (when (and (= :osc (:source event)) (:path event) (number? (:value event)))
        (update-input-value! [:osc (:path event)]
                             (max 0.0 (min 1.0 (double (:value event))))))
      (when-not (learning?)
        (if-let [dispatch @*dispatch-fn]
          (dispatch {:event/type :trigger/input
                     :input-event event
                     :time (:timestamp event)})
          (log/warn "No application dispatch function configured for input trigger"
                    event))))))

;; --- Lifecycle ---

(defn start-mapper!
  "Registers the global input mapper with the generic event router."
  []
  (router/register-global-handler! ::generalized-mapper process-input-event!)
  (log/info "Generalized input mapper initialized."))

(defn stop-mapper!
  "Unregisters the global input mapper."
  []
  (router/unregister-handler! ::generalized-mapper))
