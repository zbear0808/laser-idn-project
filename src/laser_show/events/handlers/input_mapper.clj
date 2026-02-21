(ns laser-show.events.handlers.input-mapper
  "High-performance generic input mapper.
   
   Listens to all global input events from the generic router and:
   1. Continuous events (MIDI CC, OSC floats) -> updates `[:backend :input :values]`
   2. Discrete events (Note ON, Key pressed) -> performs O(1) lookup in `[:config :input :trigger-map]`
      and invokes the mapped action.
   
   State updates bypass cljfx's event queue to ensure the lowest possible latency
   and avoid blocking the UI."
  (:require [clojure.tools.logging :as log]
            [laser-show.state.core :as state]
            [laser-show.input.router :as router]
            [laser-show.input.events :as input-events]
            [laser-show.events.handlers.grid :as grid-handlers]))


;; --- State Update Helpers ---

(defn- update-input-value!
  "Updates a continuous input value in global state.
   e.g. source-key `[:midi 0 10]` -> value `0.5`"
  [source-key value]
  ;; Use fast swap! without ui updating
  (state/assoc-in-state! [:backend :input :values source-key] value))


;; --- Trigger Action Handlers ---

;; Fast-path action dispatch to bypass regular fx dispatch overhead.
;; Because these happen outside the UI event loop, they update state directly.

(defn- handle-trigger-action!
  "Executes the mapped action for a triggered input."
  [action]
  (let [st (state/get-raw-state)
        type (:type action)]
    (case type
      :trigger-cue
      (let [[col row] (:target action)]
        ;; Use grid handler's cell-clicked logic but adapt it for direct state manip
        ;; We don't have the standard `{:state state}` return format here easily because
        ;; we are outside the event loop, so we execute it via swap-state!
        (state/swap-state!
         (fn [s]
           (let [res (grid-handlers/handle {:event/type :grid/cell-clicked
                                            :col col
                                            :row row
                                            :has-content? true
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
    (let [val (:value event)
          ch (:channel event)
          cc (:control event)]
      (update-input-value! [:midi ch cc] val))

    ;; OSC continuous events could be added here
    ;; ...

    ;; Discrete Triggers (Note ON, Keys)
    (or (input-events/note-on? event)
        (input-events/trigger? event))
    (let [st (state/get-raw-state)
          trigger-map (get-in st [:config :input :trigger-map] {})]

      ;; Construct lookup key
      (let [lookup-key (cond
                         (input-events/note-on? event)
                         {:source :midi :channel (:channel event) :note (:note event)}

                         (input-events/trigger? event)
                         {:source (:source event) :id (:id event)})]

        (when-let [action (get trigger-map lookup-key)]
          (handle-trigger-action! action))))))

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
