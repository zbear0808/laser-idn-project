(ns laser-show.input.learn
  "The runtime side of a learn attempt (reactivity plan 24): its listener in
   the router's one learn slot (input.router/replace-learn-listener!), the
   private gate a capture is delivered to, the waiter on that gate and, for
   a fader's learn, the first value it saw of each control. Nothing else
   makes them, and none of it is app state.

   handlers/input.clj owns the attempt itself, as plain data
   ([:ui :input-learn :active], with its :learn-id). After an event that
   changes which attempt is active, its :input/sync-learn effect
   (handlers.input/wrap-learn-lifetime) runs sync!: the slot goes from the
   attempt before to the next one, only if it still has the one before. The
   listener of an attempt delivers the first input it takes to its own gate,
   once, and never unregisters anything; the waiter dispatches it, through
   the dispatch fn of the effect that armed it, as :input/learn-captured
   with the attempt's identity, or :input/learn-ended with :reason :timeout
   after deadline-ms, or :failed. Retired (sync! to another attempt, or
   retire! at app stop), its waiter wakes and dispatches nothing. Whatever
   the waiter sends still has to pass the handler's checks."
  (:require [clojure.tools.logging :as log]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.events :as input-events]
            [laser-show.input.router :as router]))

(def deadline-ms
  "How long an attempt waits for its input: then it ends (:reason :timeout)."
  30000)

(def ^:private fader-travel
  "How far (0..1) a control must move for a fader's learn to take it: about
   10 of a MIDI CC's 127 steps, more than a fader's noise."
  0.08)

(defonce ^{:private true :doc "The :learn-id of each attempt whose waiter still waits."}
  !waiting
  (atom #{}))

(defn waiting
  "The ids of the attempts whose waiter still waits."
  []
  @!waiting)

(defn deliver-capture!
  "The one place a listener hands on what it took: event to gate, as
   {:captured event}, once (a gate already delivered keeps what it has)."
  [gate event]
  (deliver gate {:captured event}))

(defn wait-for-capture
  "The waiter's wait: what gate gets within ms ({:captured event}, or
   ::cancelled once retired), else ::timeout."
  [gate ms]
  (deref gate ms ::timeout))

(defn- takes?
  "Does an attempt that captures capture (from source) take event? Settings'
   identify (:observe) any input of its source, releases too; a press learn
   a note or a button pressed."
  [{:keys [capture source]} event]
  (case capture
    :observe (= source (:source event))
    :press (or (input-events/note-on? event) (input-events/trigger? event))
    false))

(defn- fader-offer
  "A fader's learn's listener: it keeps the first value it sees for each
   input store key an event writes (input-mapper/store-keys: a CC, an axis,
   an OSC value; a note or a button writes none), and hands on the first
   event whose key has moved fader-travel or more from it, with that key as
   :store-key."
  [gate]
  (let [!first (atom {})]
    (fn [event]
      (when-let [ks (seq (input-mapper/store-keys event))]
        (let [v (max 0.0 (min 1.0 (double (or (:value event) 0.0))))
              seen (swap! !first (fn [m] (reduce #(cond-> %1 (not (contains? %1 %2)) (assoc %2 v)) m ks)))]
          (when-let [k (some #(when (>= (Math/abs (- v (double (get seen %)))) fader-travel) %) ks)]
            (deliver-capture! gate (assoc event :store-key k))))))))

(defn- listener
  "The listener of attempt ({:learn-id :document-id :capture :source}) and
   its gate: {:learn-id :binding? (it binds a control: anything but
   identify) :offer :gate :attempt}."
  [{:keys [learn-id capture] :as attempt}]
  (let [gate (promise)]
    {:learn-id learn-id
     :binding? (not= :observe capture)
     :offer (if (= :continuous capture)
              (fader-offer gate)
              (fn [event]
                (when (takes? attempt event)
                  (deliver-capture! gate event))))
     :gate gate
     :attempt attempt}))

(defn- outcome
  "The event the waiter of attempt sends for result, or nil (retired)."
  [{:keys [learn-id document-id]} result]
  (let [identity (cond-> {:learn-id learn-id} (some? document-id) (assoc :document-id document-id))]
    (cond
      (= ::cancelled result) nil
      (= ::timeout result) (assoc identity :event/type :input/learn-ended :reason :timeout)
      (and (map? result) (map? (:captured result))) (assoc identity :event/type :input/learn-captured
                                                           :input-event (:captured result))
      :else (assoc identity :event/type :input/learn-ended :reason :failed))))

(defn- start-waiter!
  "The waiter of l, a listener now in the slot: it waits on l's gate (at
   most deadline-ms), then dispatches its outcome through dispatch."
  [{:keys [gate attempt]} dispatch]
  (let [id (:learn-id attempt)]
    (swap! !waiting conj id)
    (future
      (try
        (let [result (try
                       (wait-for-capture gate deadline-ms)
                       (catch Throwable t
                         (log/error t "Input learn: the wait failed")
                         ::failed))]
          ;; Later input goes nowhere now, whatever the wait ended with
          (deliver gate ::cancelled)
          (when-let [event (outcome attempt result)]
            (dispatch event)))
        (catch Throwable t
          (log/error t "Input learn: could not dispatch the attempt's outcome"))
        (finally
          (swap! !waiting disj id))))))

(defn sync!
  "The :input/sync-learn effect: {:retire L :arm A} puts A's listener (A:
   {:learn-id :document-id :capture :source}, nil for none) in the learn
   slot where L's is (nil: the slot is empty), only if L's is still there.
   Then L's waiter wakes and sends nothing, and A's waiter starts, sending
   through dispatch. From that exchange on, A's listener takes the input. A
   change for a slot L doesn't have (repeated, stale) does nothing. true
   when it exchanged."
  [{:keys [retire arm]} dispatch]
  (let [next-listener (when arm (listener arm))]
    (if-let [{:keys [displaced]} (router/replace-learn-listener! retire next-listener)]
      (do (some-> displaced :gate (deliver ::cancelled))
          (when next-listener
            (start-waiter! next-listener dispatch))
          true)
      (do (log/debug "Input learn: the slot isn't" retire "'s; nothing exchanged")
          false))))

(defn retire!
  "Retire the attempt in the learn slot, if any (app/stop!, a test): its
   listener goes, and its waiter wakes and sends nothing. The same exchange
   as sync!'s, to none; nothing waits for an event."
  []
  (when-let [id (router/learn-listener-id)]
    (sync! {:retire id} nil)))
