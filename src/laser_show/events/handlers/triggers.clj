(ns laser-show.events.handlers.triggers
  "Internal trigger orchestration.

   Trigger inputs are resolved here and then translated into the existing
   domain events so all input sources share the same playback behavior.
   Vibe mode's role bindings become :vibe/* events (handlers/vibes.clj), a
   zone map pad's a :zone-map/press (handlers/zone_maps.clj), a pattern
   pad's a :pattern-pads/press and the Zone step key's a :pattern-pads/step
   (handlers/zone_patterns.clj).

   A press keeps what it played as its key's activation, for every source:
   [:trigger-runtime :activations], by the key as lookup-key names it (MIDI
   {:source :channel :note}, OSC, gamepad and keyboard {:source :id}). The
   key's release ends that, whatever the key is bound to by then, and a
   release with none (a learn caught the press, it played nothing, or it
   was retired) ends nothing. A source that can't send its releases any
   more retires its keys' activations (:trigger/retire): a MIDI input
   disconnected or MIDI switched off, the OSC server stopped, moved to
   another port or OSC switched off (handlers/input.clj), and MIDI's All
   Notes Off, which says every key is up (handlers/input_mapper.clj). The
   keyboard's and the gamepad's releases still come when they are switched
   off or lose their window or controller, and New or Open forgets every
   activation (state.persistent/reset-session)."
  (:refer-clojure :exclude [bound?])
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

(defn- configured-action [state input-event]
  (or (definition-action state input-event)
      (legacy-action state input-event)))

(defn- pressed? [input-event]
  (or (input-events/note-on? input-event)
      (input-events/trigger? input-event)))

(defn- released? [input-event]
  (or (input-events/note-off? input-event)
      (input-events/trigger-release? input-event)))

(defn- activation
  "What the press of key played, until its release: its activation."
  [state key]
  (get-in state [:trigger-runtime :activations key]))

(defn- resolve-action
  "The action an input event plays: a press, the key's binding now; a
   release, what the key's press played (its activation), whatever the key
   is bound to now. A learn can send a release without ever having fired a
   press: then, as for a press that played nothing or was retired, there is
   nothing to let go, even if the key now has a binding."
  [state input-event]
  (if (released? input-event)
    (some->> (lookup-key input-event) (activation state))
    (configured-action state input-event)))

(defn bound?
  "Does this router event resolve to an action? A release belongs to the
   action of the matching press, even if its binding has since changed."
  [state input-event]
  (boolean (resolve-action state input-event)))

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

(def ^:private vibe-action?
  "Vibe mode's bindings: {:type :vibe :role r} and {:type :vibe-select :vibe id}."
  #{:vibe :vibe-select})

(def ^:private quiet-action?
  "These bindings skip generic held-input and active-trigger bookkeeping.
   A keyboard press still keeps its action for the matching release."
  #{:vibe :vibe-select :zone-map :pattern-pad :zone-step})

(defn- vibe-event
  "A role hears its press and its release, timed by the input (the
   pipeline's :time is when the event was handled); a vibe-select binding
   selects its vibe on press."
  [{:keys [type role vibe]} input-event]
  (let [press? (pressed? input-event)
        at (:timestamp input-event)]
    (case type
      :vibe {:event/type (if press? :vibe/press :vibe/release)
             :role role
             :input-time at}
      :vibe-select (when press?
                     {:event/type :vibe/select
                      :vibe vibe
                      :input-time at}))))

(defn- action-event [action input-event]
  (let [{:keys [type target]} action]
    (cond
      (vibe-action? type)
      (vibe-event action input-event)

      ;; A zone map pad's key presses the pad, timed by the input; its
      ;; release does nothing (zones plan Z4)
      (= :zone-map type)
      (when (pressed? input-event)
        {:event/type :zone-map/press :map target :src :input :input-time (:timestamp input-event)})

      ;; A pattern pad's key presses the pad, the Zone step key steps the
      ;; patterns that step on it; their releases do nothing (zones plan Z5)
      (= :pattern-pad type)
      (when (pressed? input-event)
        {:event/type :pattern-pads/press :pad target :src :input :input-time (:timestamp input-event)})

      (= :zone-step type)
      (when (pressed? input-event)
        {:event/type :pattern-pads/step :src :input :input-time (:timestamp input-event)})

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
        :tap-tempo {:event/type :timing/tap-tempo :input-time (:timestamp input-event)}
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
    (let [state' (assoc-in state [:trigger-runtime :last-input-time] time)
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

(defn- run-action [state input-event action time]
  (let [next-state (if (quiet-action? (:type action))
                     state
                     (update-runtime state input-event (or action {}) time))]
    (if-let [event (and action (action-event action input-event))]
      {:state next-state
       :dispatch (assoc event :source-trigger action :source-input input-event)}
      {:state next-state})))

(defn- as-release
  "The release of input-event's key, a press."
  [input-event]
  (case (:type input-event)
    :note-on (assoc input-event :type :note-off)
    :trigger (assoc input-event :type :trigger-release :state :released)
    input-event))

(defn- holds?
  "Does action, played by input-event (a press), hold something until its
   key's release: a momentary cell, a vibe role?"
  [action input-event]
  (some? (action-event action (as-release input-event))))

(defn handle-input
  "A press plays what its key is bound to now and keeps it as the key's
   activation; the key's release ends that, whatever the key is bound to by
   then, and with no activation ends nothing (resolve-action). A press of a
   key whose activation still holds something (a press again, no release
   between: a repeat) plays nothing, and the release ends the first press's
   action. A press after one that held nothing (a toggled cue, a tap; an
   OSC address that never sends a release) plays as a new one."
  [{:keys [state input-event time]}]
  (let [key (lookup-key input-event)
        held (some->> key (activation state))]
    (cond
      (released? input-event)
      (cond
        held (run-action (update-in state [:trigger-runtime :activations] dissoc key) input-event held time)
        ;; The press played nothing: only its bookkeeping goes
        (contains? (get-in state [:trigger-runtime :held-inputs]) key) (run-action state input-event nil time)
        :else {})

      (and held (holds? held input-event))
      {}

      :else
      (let [action (configured-action state input-event)]
        (run-action (cond
                      (and key action) (assoc-in state [:trigger-runtime :activations key] action)
                      held (update-in state [:trigger-runtime :activations] dissoc key)
                      :else state)
                    input-event action time)))))

(defn- key-release
  "The release of key (a lookup-key) at t, as its source would send it."
  [{:keys [source channel note id] :as key} t]
  (if (contains? key :note)
    {:type :note-off :source source :channel channel :note note :velocity 0.0 :timestamp t}
    {:type :trigger-release :source source :id id :state :released :timestamp t}))

(defn- forget-holds
  "trigger-runtime without what the keys of source hold: their activations,
   held inputs and active triggers."
  [runtime source]
  (let [mine? #(= source (:source %))]
    (cond-> runtime
      (:activations runtime) (update :activations #(into {} (remove (comp mine? key)) %))
      (:held-inputs runtime) (update :held-inputs #(into #{} (remove mine?) %))
      (:active-triggers runtime) (update :active-triggers #(into {} (remove (comp mine? :input val)) %)))))

(defn- handle-retire
  "Retire the activations of source's keys, whose releases can't come any
   more (handlers/input.clj: its device disconnected or switched off, the
   OSC server moved; MIDI's All Notes Off, handlers/input_mapper.clj). What
   each holds lets go now, quietly: a momentary cell's release, a vibe role
   let go of as :vibe/release-all does (so it neither switches nor locks
   nor goes on to the next vibe). Nothing else plays: a toggled cue stays
   on, a tap taps nothing, and a release of one of those keys that comes
   later ends nothing. The source's held inputs and active triggers go too.
   {} (no commit) when nothing of source is held."
  [{:keys [state source time]}]
  (let [runtime (:trigger-runtime state)
        forgotten (forget-holds runtime source)]
    (if (= runtime forgotten)
      {}
      (let [retired (filter #(= source (:source (key %))) (:activations runtime))
            roles (into #{} (keep (fn [[_ {:keys [type role]}]] (when (= :vibe type) role))) retired)
            releases (keep (fn [[k action]]
                             (when-not (= :vibe (:type action))
                               (let [release (key-release k time)]
                                 (some-> (action-event action release)
                                         (assoc :source-trigger action :source-input release)))))
                           retired)
            events (cond-> (vec releases)
                     (seq roles) (conj {:event/type :vibe/release-all :only :input :roles roles}))]
        (cond-> {:state (assoc state :trigger-runtime forgotten)}
          (seq events) (assoc :dispatch-n events))))))

(defn handle-fire
  [{:keys [state trigger-id input-event time]}]
  (if-let [definition (get-in state [:triggers :definitions trigger-id])]
    ;; Firing a definition directly does not put a physical key down.
    (let [event (or input-event (:match definition))]
      (run-action state event (configured-action state event) time))
    {:state state}))

(defn handle
  [{:keys [event/type] :as event}]
  (case type
    :trigger/input (handle-input event)
    :trigger/fire (handle-fire event)
    :trigger/retire (handle-retire event)
    :trigger/tap-tempo
    {:dispatch {:event/type :timing/tap-tempo}}
    {}))
