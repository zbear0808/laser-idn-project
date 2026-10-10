(ns laser-show.events.handlers.input-mapper
  "High-performance generic input mapper.
   
   Listens to all global input events from the generic router and:
   1. Continuous events (MIDI CC, OSC floats) -> updates fast input store (`laser-show.input.store`)
   2. Discrete events (Note ON, Key pressed) -> performs O(1) lookup in `[:config :input :trigger-map]`
      and invokes the mapped action.
   
   State updates bypass cljfx's event queue to ensure the lowest possible latency
   and avoid blocking the UI."
  (:require [clojure.tools.logging :as log]
            [laser-show.state.core :as state]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]
            [laser-show.input.events :as input-events]
            [laser-show.events.handlers.grid :as grid-handlers]
            [laser-show.events.handlers.effects :as effects-handlers]))


;; --- State Update Helpers ---

(defn- update-input-value!
  "Updates a continuous input value in the fast store.
   e.g. source-key `[:midi 0 10]` -> value `0.5`"
  [source-key value]
  (store/set-continuous-value! source-key value))


;; --- Trigger Action Handlers ---

;; Fast-path action dispatch to bypass regular fx dispatch overhead.
;; Because these happen outside the UI event loop, they update state directly.

(defn- handle-trigger-action!
  "Executes the mapped action for a triggered input."
  [action]
  (let [type (:type action)]
    (case type
      :trigger-cue
      (let [[col row] (:target action)]
        (state/swap-state!
         (fn [s]
           (let [res (grid-handlers/handle {:event/type :grid/trigger-cell
                                            :col col
                                            :row row
                                            :state s})]
             (:state res s)))))

      :trigger-effect
      (let [[col row] (:target action)]
        (state/swap-state!
         (fn [s]
           (let [res (effects-handlers/handle {:event/type :effects/toggle-cell
                                               :col col
                                               :row row
                                               :state s})]
             (:state res s)))))

      ;; Default
      (log/warn "Unknown input trigger action:" action))))


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

    ;; Discrete Triggers (Note ON, Keys)
    (or (input-events/note-on? event)
        (input-events/trigger? event))
    (let [st (state/get-raw-state)
          trigger-map (get-in st [:config :input :trigger-map] {})
          lookup-key (cond
                       (input-events/note-on? event)
                       {:source (:source event) :channel (:channel event) :note (:note event)}

                       (input-events/trigger? event)
                       {:source (:source event) :id (:id event)})]
      (when-let [action (get trigger-map lookup-key)]
        (handle-trigger-action! action)))))

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
