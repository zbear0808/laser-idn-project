(ns laser-show.events.handlers.input-mapper
  "High-performance generic input mapper.
   
   Listens to all global input events from the generic router and:
   1. Continuous events (MIDI CC, OSC floats) -> updates fast input store (`laser-show.input.store`)
   2. Discrete events (Note ON, Key pressed) -> performs O(1) lookup in `[:config :input :trigger-map]`
      and invokes the mapped action, unless it is a press and a learn is
      waiting for one ([:backend :input :learning-target]): that one is the learn's.
   
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

(defn store-keys
  "The input store keys an input event writes, for this mapper and a
   fader's learn (handlers/input.clj): a MIDI control change's [:midi
   channel control]; an OSC one's [:osc path] and [:osc channel control];
   another source's [:input path] and [source channel control] (a gamepad
   axis is [:gamepad controller axis]); and the value an unmapped OSC
   address carries, [:osc path]. [] for anything else: a note or a button
   writes none."
  [{:keys [type source channel control path value] :as event}]
  (cond
    (input-events/control-change? event)
    (case source
      :midi [[:midi (or channel 0) control]]
      :osc (cond-> [] path (conj [:osc path]) (some? control) (conj [:osc (or channel 0) control]))
      (cond-> [] path (conj [:input path]) (some? control) (conj [source (or channel 0) control])))

    (and (#{:note-on :note-off :trigger :trigger-release} type) (= :osc source) path (number? value))
    [[:osc path]]

    :else []))

(defn- update-input-value!
  "Updates a continuous input value in the fast store. A fader's slot isn't
   written here: the render derives it from the control learned for it
   (vibes.faders/with-faders), so the mapper reads no app state for a CC.
   e.g. source-key `[:midi 0 10]` -> value `0.5`"
  [source-key value]
  (store/set-continuous-value! source-key value))

(defn- learning?
  "Is a learn waiting for an input? It catches the press itself
   (handlers/input.clj), so the press mustn't also fire the key's old
   binding (keyboard plan K-15). A release still goes through, so a key
   held when the learn started lets go of what it holds; a role's release
   with nothing held changes nothing (vibes.engine/release)."
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
    (let [val (double (or (:value event) 0.0))]
      (run! #(update-input-value! % val) (store-keys event)))

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
      (when-not (and (or (input-events/note-on? event) (input-events/trigger? event))
                     (learning?))
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
