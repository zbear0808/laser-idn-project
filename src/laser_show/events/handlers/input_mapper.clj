(ns laser-show.events.handlers.input-mapper
  "High-performance generic input mapper.
   
   Listens to all global input events from the generic router and:
   1. Continuous events (MIDI CC, OSC floats) -> updates fast input store (`laser-show.input.store`);
      MIDI's All Notes Off and All Sound Off (CC 123, 120) also let go of
      every vibe role (:vibe/release-all)
   2. Discrete events (Note ON, Key pressed) -> performs O(1) lookup in `[:config :input :trigger-map]`
      and invokes the mapped action, unless it is a press marked
      :learn-only?: one the router offered to a learn attempt that binds a
      control (input.router, reactivity plan 24), or the keyboard's learn
      press. That one is the learn's, whenever the learn finishes.
   
   State updates bypass cljfx's event queue to ensure the lowest possible latency
   and avoid blocking the UI."
  (:require [clojure.tools.logging :as log]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]
            [laser-show.input.events :as input-events]))

(defonce ^:private *dispatch-fn (atom nil))

(defn set-dispatch-fn!
  "Sets the application event dispatcher used for discrete trigger events."
  [dispatch-fn]
  (reset! *dispatch-fn dispatch-fn))


;; --- State Update Helpers ---

(defn store-keys
  "The input store keys an input event writes, for this mapper and a
   fader's learn (input.learn, handlers/input.clj): a MIDI control change's [:midi
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

(def ^:private all-notes-off-controls
  "MIDI's All Sound Off (120) and All Notes Off (123): a controller's way to
   say every key is up."
  #{120 123})

(defn- all-notes-off?
  "Is event a MIDI control change that says every key is up?"
  [{:keys [source control] :as event}]
  (and (= :midi source) (contains? all-notes-off-controls control) (input-events/control-change? event)))


;; --- Main Event Processor ---

(defn- process-input-event!
  "Process a single input event.
   Must be fast and non-blocking."
  [event]
  (cond
    ;; Continuous Events. All Notes Off and All Sound Off also let go of
    ;; every vibe role, keys and pads alike (beyond-vibe engine/mod.rs:291)
    (input-events/control-change? event)
    (let [val (double (or (:value event) 0.0))]
      (run! #(update-input-value! % val) (store-keys event))
      (when-let [dispatch (and (all-notes-off? event) @*dispatch-fn)]
        (dispatch {:event/type :vibe/release-all :input-time (:timestamp event)})))

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
      ;; A learn's press (the router's mark, keyboard plan K-15) mustn't
      ;; also fire the key's old binding. A release still goes through, so
      ;; a key held when the learn started lets go of what it holds.
      (when-not (and (or (input-events/note-on? event) (input-events/trigger? event))
                     (:learn-only? event))
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
