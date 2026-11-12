(ns laser-show.events.handlers.triggers
  "Internal trigger orchestration.

   Trigger inputs are resolved here and then translated into the existing
   domain events so all input sources share the same playback behavior."
  (:require [clojure.tools.logging :as log]
            [laser-show.input.events :as input-events]
            [laser-show.triggers.matcher :as matcher]))

(defn- cell? [target]
  (and (vector? target) (= 2 (count target)) (every? int? target)))

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

(defn- pressed? [input-event]
  (or (input-events/note-on? input-event)
      (input-events/trigger? input-event)))

(defn- momentary-event
  "Momentary cells play while held: press starts, release stops."
  [{:keys [type target]} input-event]
  (when (vector? target)
    (let [press? (pressed? input-event)
          [col row] target]
      (case type
        :trigger-cue {:event/type (if press? :grid/activate-cell :grid/release-cell)
                      :col col
                      :row row}
        :trigger-effect {:event/type :effects/toggle-cell
                         :col col
                         :row row
                         :fx/event press?}
        nil))))

(defn- action-event [action input-event]
  (let [{:keys [type target]} action]
    (cond
      (and (= :momentary (:behavior action))
           (#{:trigger-cue :trigger-effect} type))
      (momentary-event action input-event)

      (pressed? input-event)
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
        :start-timeline (if (cell? target)
                          {:event/type :timeline/play :col (first target) :row (second target)}
                          (log/debug "start-timeline needs a [col row] target, got" (pr-str target)))
        :stop-timeline (if (cell? target)
                         {:event/type :timeline/stop :col (first target) :row (second target)}
                         (log/debug "stop-timeline needs a [col row] target, got" (pr-str target)))
        :apply-preset (when target
                        {:event/type :preset/apply :preset-id target})
        nil)

      :else nil)))

(defn- update-runtime [state input-event action time]
  (let [key (lookup-key input-event)
        press? (pressed? input-event)
        trigger-id (or (:trigger-id action) key)]
    (let [state' (-> state
                     (assoc-in [:trigger-runtime :last-input-time] time)
                     (update-in [:trigger-runtime :tap-history]
                                (fn [history]
                                  (vec (take-last 8 (conj (or history []) time))))))
          state'' (if key
                    (update-in state' [:trigger-runtime :held-inputs]
                               (if press?
                                 (fnil conj #{})
                                 (fnil disj #{}))
                               key)
                    state')]
      (if trigger-id
        (if press?
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

(defn- handle-group-toggle-lock
  [{:keys [state group-id]}]
  (let [current (get-in state [:trigger-runtime :active-groups group-id])
        next-state (if (= :locked (:state current)) :active :locked)]
    {:state (assoc-in state [:trigger-runtime :active-groups group-id]
                      (assoc (or current {}) :state next-state))}))

(defn- handle-tick
  [{:keys [state time]}]
  (let [settings (get-in state [:triggers :settings] {})
        duration-ms (* 1000 (long (or (:group-duration-secs settings) 45)))
        groups (get-in state [:trigger-runtime :active-groups] {})
        updated (reduce-kv
                 (fn [result group-id group]
                   (if (and (= :active (:state group))
                            (:entered-at group)
                            (>= (- time (:entered-at group)) duration-ms))
                     (assoc result group-id (assoc group :state :armed))
                     (assoc result group-id group)))
                 {}
                 groups)]
    {:state (assoc-in state [:trigger-runtime :active-groups] updated)}))

(defn handle
  [{:keys [event/type] :as event}]
  (case type
    :trigger/input (handle-input event)
    :trigger/fire (handle-fire event)
    :trigger-group/toggle-lock (handle-group-toggle-lock event)
    :trigger/tick (handle-tick event)
    :trigger/tap-tempo
    {:dispatch {:event/type :timing/tap-tempo}}
    {}))
