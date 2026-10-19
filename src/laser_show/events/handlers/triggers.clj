(ns laser-show.events.handlers.triggers
  "Internal trigger orchestration.

   Trigger inputs are resolved here and then translated into the existing
   domain events so all input sources share the same playback behavior."
  (:require [laser-show.input.events :as input-events]
            [laser-show.triggers.matcher :as matcher]))

(defn- lookup-key [event]
  (cond
    (or (input-events/note-on? event)
        (input-events/note-off? event))
    {:source (:source event) :channel (:channel event) :note (:note event)}

    (or (input-events/trigger? event)
        (input-events/trigger-release? event))
    {:source (:source event) :id (:id event)}

    :else nil))

(defn- legacy-action [state event]
  (when-let [key (lookup-key event)]
    (get-in state [:config :input :trigger-map key])))

(defn- definition-action [state event]
  (some (fn [[id definition]]
          (when (and (not (false? (:enabled? definition)))
                     (matcher/matches? event (or (:match definition)
                                                 (:input definition))))
            (assoc (:action definition)
                   :trigger-id id
                   :behavior (:behavior definition))))
        (get-in state [:triggers :definitions] {})))

(defn- action-event [action input-event]
  (let [{:keys [type target]} action]
    (when (or (input-events/note-on? input-event)
              (input-events/trigger? input-event)
              (= :momentary (:behavior action)))
    (case type
      :trigger-cue (when (vector? target)
                     {:event/type :grid/trigger-cell
                      :col (first target)
                      :row (second target)})
      :trigger-effect (when (vector? target)
                        {:event/type :effects/toggle-cell
                         :col (first target)
                         :row (second target)})
      :stop-all {:event/type :transport/stop}
      :blackout {:event/type :transport/stop}
      :tap-tempo {:event/type :timing/tap-tempo}
      :start-timeline (when target
                        {:event/type :timeline/start :timeline-id target})
      :stop-timeline (when target
                       {:event/type :timeline/stop :timeline-id target})
      :apply-preset (when target
                      {:event/type :preset/apply :preset-id target})
      nil))))

(defn- update-runtime [state input-event action time]
  (let [key (lookup-key input-event)
        pressed? (or (input-events/note-on? input-event)
                     (input-events/trigger? input-event))
        trigger-id (or (:trigger-id action) key)]
    (let [state' (-> state
                     (assoc-in [:trigger-runtime :last-input-time] time)
                     (update-in [:trigger-runtime :tap-history]
                                (fn [history]
                                  (vec (take-last 8 (conj (or history []) time))))))
          state'' (if key
                    (update-in state' [:trigger-runtime :held-inputs]
                               (if pressed?
                                 (fnil conj #{})
                                 (fnil disj #{}))
                               key)
                    state')]
      (if trigger-id
        (if pressed?
          (assoc-in state'' [:trigger-runtime :active-triggers trigger-id]
                    {:started-at time
                     :input key
                     :behavior (:behavior action :retrigger)})
          (update-in state'' [:trigger-runtime :active-triggers] dissoc trigger-id))
        state''))))

(defn handle-input
  [{:keys [state input-event time]}]
  (let [action (or (definition-action state input-event)
                   (legacy-action state input-event))
        next-state (update-runtime state input-event (or action {}) time)]
    (if-let [event (and action (action-event action input-event))]
      {:state next-state
       :dispatch (assoc event :source-trigger action :source-input input-event)}
      {:state next-state})))

(defn handle-fire
  [{:keys [state trigger-id input-event time]}]
  (if-let [definition (get-in state [:triggers :definitions trigger-id])]
    (handle-input {:state state
                   :input-event (or input-event (:match definition))
                   :time time})
    {:state state}))

(defn handle
  [{:keys [event/type] :as event}]
  (case type
    :trigger/input (handle-input event)
    :trigger/fire (handle-fire event)
    :trigger/tap-tempo
    {:dispatch {:event/type :timing/tap-tempo}}
    {}))
