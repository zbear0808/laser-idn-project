(ns laser-show.events.handlers.triggers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.triggers :as triggers]
            [laser-show.input.devices :as devices]
            [laser-show.input.events :as input-events]
            [laser-show.input.fixtures :as fixtures]
            [laser-show.input.gamepad :as gamepad]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.templates :as templates]
            [laser-show.timeline.shows :as shows]))

(deftest legacy-cue-trigger-dispatches-grid-event
  (let [input {:type :trigger :source :keyboard :id :space :timestamp 1000}
        state {:config {:input {:trigger-map
                                {{:source :keyboard :id :space}
                                 {:type :trigger-cue :target [2 1]}}}}
               :trigger-runtime {}}
        result (triggers/handle {:event/type :trigger/input
                                 :state state
                                 :input-event input
                                 :time 1000})]
    (is (= :grid/trigger-cell (:event/type (:dispatch result))))
    (is (= 2 (:col (:dispatch result))))
    (is (= 1 (:row (:dispatch result))))
    (is (= #{{:source :keyboard :id :space}}
           (get-in result [:state :trigger-runtime :held-inputs])))))

(deftest bound-agrees-with-handle-input-test
  (let [state {:triggers {:definitions {:stop {:match {:source :keyboard :state :pressed :id #{:a :b}}
                                                :action {:type :stop-all}}
                                       :off {:match {:source :keyboard :id #{:c :d}}
                                             :enabled? false :action {:type :stop-all}}}}
               :config {:input {:trigger-map {{:source :keyboard :id :b} {:type :tap-tempo}
                                              {:source :keyboard :id :c} {:type :tap-tempo}}}}}]
    (doseq [[id expected] [[:a :transport/stop]   ; definition alone
                           [:b :transport/stop]   ; definition wins over the map
                           [:c :timing/tap-tempo] ; map wins over a disabled definition
                           [:d nil]               ; disabled definition alone
                           [:e nil]]]             ; unbound
      (testing (str id)
        (let [event (input-events/trigger :keyboard id :pressed)]
          (is (= (some? expected) (triggers/bound? state event)))
          (is (= expected (get-in (triggers/handle-input {:state state :input-event event :time 1})
                                  [:dispatch :event/type]))))))))

(deftest trigger-release-removes-held-input
  (let [input {:type :trigger-release :source :keyboard :id :space :timestamp 1100}
        state {:config {:input {:trigger-map {}}}
               :trigger-runtime {:held-inputs #{{:source :keyboard :id :space}}}}
        result (triggers/handle {:event/type :trigger/input
                                 :state state
                                 :input-event input
                                 :time 1100})]
    (is (empty? (get-in result [:state :trigger-runtime :held-inputs])))))

(def ^:private note-key {:source :midi :channel 0 :note 60})

(def ^:private P {:type :preset :preset-id :circle})

(defn- momentary-state [action-type]
  {:config {:input {:trigger-map
                    {note-key {:type action-type :target [0 0] :behavior :momentary}}}}
   :chains {:cue-chains {[0 0] {:items [P]}}}
   :playback {:active-cues {}}
   :trigger-runtime {}})

(defn- input [state type]
  (triggers/handle {:event/type :trigger/input
                    :state state
                    :input-event {:type type :source :midi :channel 0 :note 60 :timestamp 1}
                    :time 1}))

(defn- routed
  "state after the router takes input-event, then the handlers take the
   event it dispatches, if any."
  [state input-event]
  (let [{s :state dispatch :dispatch} (triggers/handle {:event/type :trigger/input :state state
                                                        :input-event input-event :time 1})]
    (cond dispatch (:state (handlers/handle-event (assoc dispatch :state s)))
          s s
          :else state)))

(defn- note
  ([type] (note type 60))
  ([type n] {:type type :source :midi :channel 0 :note n :timestamp 1}))

(defn- grid-outcome
  "What the grid and playback show of state, without the input bookkeeping;
   each run (a random uuid) as :run."
  [state]
  {:selected (get-in state [:grid :selected-cell])
   :intents (update-vals (get-in state [:playback :active-cues] {}) #(cond-> % (uuid? (:run %)) (assoc :run :run)))
   :active-cell (get-in state [:playback :active-cell])
   :playing? (get-in state [:playback :playing?])
   :pulse (get-in state [:playback :pulses :cue])})

(defn- keyboard-edge [state id edge]
  (let [at (if (= :pressed edge) 1000 1400)]
    (triggers/handle-input {:state state :time at
                            :input-event (assoc (input-events/trigger :keyboard id edge) :timestamp at)})))

(defn- no-trigger-holds? [{:keys [trigger-runtime]}]
  (every? empty? ((juxt :activations :held-inputs :active-triggers) trigger-runtime)))

(deftest keyboard-release-balances-press-only-definition-test
  (let [state {:chains {:cue-chains {[0 0] {:items [P]}}}
               :triggers {:definitions {:cue {:match {:source :keyboard :id :j :type :trigger :state :pressed}
                                              :action {:type :trigger-cue :target [0 0]}
                                              :behavior :momentary}}}
               :config {:input {:trigger-map {{:source :keyboard :id :j} {:type :vibe :role :hit}}}}}
        press (keyboard-edge state :j :pressed)
        playing (:state (handlers/handle-event (assoc (:dispatch press) :state (:state press))))
        release (keyboard-edge playing :j :released)
        stopped (:state (handlers/handle-event (assoc (:dispatch release) :state (:state release))))]
    (is (contains? (get-in playing [:playback :active-cues]) [0 0]))
    (is (triggers/bound? playing (input-events/trigger :keyboard :j :released)))
    (is (= [:grid/release-cell :cue] [(get-in release [:dispatch :event/type])
                                      (get-in release [:dispatch :source-trigger :trigger-id])]))
    (is (not (contains? (get-in stopped [:playback :active-cues]) [0 0])))
    (is (no-trigger-holds? stopped))))

(deftest keyboard-release-survives-remap-or-removal-test
  (let [effect {:type :trigger-effect :behavior :momentary :target [2 1]}
        vibe {:type :vibe :role :hit}
        state {:config {:input {:trigger-map {{:source :keyboard :id :a} effect
                                              {:source :keyboard :id :b} vibe}}}}
        a-down (:state (keyboard-edge state :a :pressed))
        b-down (:state (keyboard-edge a-down :b :pressed))
        changed (assoc-in b-down [:config :input :trigger-map] {{:source :keyboard :id :a} vibe})
        a-up (keyboard-edge changed :a :released)
        b-up (keyboard-edge (:state a-up) :b :released)]
    (is (= {:event/type :effects/toggle-cell :col 2 :row 1 :fx/event false :source-trigger effect}
           (dissoc (:dispatch a-up) :source-input)) "release the effect, not the new role")
    (is (= {:event/type :vibe/release :role :hit :input-time 1400 :source-trigger vibe}
           (dissoc (:dispatch b-up) :source-input)) "release the role even though it is no longer mapped")
    (is (no-trigger-holds? (:state b-up)))
    (is (= :vibe/press (get-in (keyboard-edge (:state b-up) :a :pressed) [:dispatch :event/type]))
        "the next press uses the new binding")))

(deftest keyboard-release-without-activation-test
  (let [key {:source :keyboard :id :a}
        bound {:config {:input {:trigger-map {key {:type :vibe :role :accent}}}}}
        unbound-down (:state (keyboard-edge {} :a :pressed))]
    (doseq [[label state] [["learn captured the press" bound]
                           ["no action matched the press" (merge unbound-down bound)]]]
      (testing label
        (let [release (keyboard-edge state :a :released)]
          (is (false? (triggers/bound? state (input-events/trigger :keyboard :a :released))))
          (is (nil? (:dispatch release)))
          (is (no-trigger-holds? (:state release))))))))

(deftest new-or-open-forgets-previous-shows-keyboard-activations-test
  (let [key {:source :keyboard :id :a}
        show (assoc-in (domains/build-initial-state) [:config :input :trigger-map]
                       {key {:type :trigger-cue :behavior :momentary :target [0 0]}
                        note-key {:type :tap-tempo}})
        midi-held (:state (input show :note-on))
        both-held (:state (keyboard-edge midi-held :a :pressed))
        files (persistent/project-files both-held)]
    (is (= (persistent/project-files show) files) "holds don't change the saved show")
    (doseq [next-show [(persistent/new-project-state both-held)
                       (persistent/apply-loaded-project both-held files "held.zip")]]
      (is (no-trigger-holds? next-show) "every source's holds were dropped (reactivity plan 26)")
      (is (nil? (:dispatch (keyboard-edge next-show :a :released))))
      (is (nil? (:dispatch (input next-show :note-off)))))))

(deftest momentary-cue-press-and-release
  (let [state (momentary-state :trigger-cue)
        pressed (input state :note-on)
        press (:dispatch pressed)
        release (:dispatch (input (:state pressed) :note-off))]
    (is (= :grid/activate-cell (:event/type press)))
    (is (= :grid/release-cell (:event/type release)))
    (is (nil? (:dispatch (input state :note-off))) "a release whose press nobody saw ends nothing")
    (testing "press then release through the routed grid handler starts then stops the cue"
      (let [started (:state (handlers/handle-event (assoc press :state state)))
            stopped (:state (handlers/handle-event (assoc release :state started)))]
        (is (contains? (get-in started [:playback :active-cues]) [0 0]))
        (is (not (contains? (get-in stopped [:playback :active-cues]) [0 0])))))
    (testing "press on an already playing cue keeps it playing (no toggle-off)"
      (let [started (:state (handlers/handle-event (assoc press :state state)))
            again (:state (handlers/handle-event (assoc press :state started)))]
        (is (contains? (get-in again [:playback :active-cues]) [0 0]))))
    (testing "a name and an empty group aren't a cue: a press leaves the selection and playback alone (plan 10 §7.3)"
      (let [draft (-> state
                      (assoc-in [:config :input :trigger-map note-key :target] [2 1])
                      (assoc-in [:chains :cue-chains] {[2 1] {:name "Draft" :items [{:type :group :items []}]}})
                      (assoc-in [:grid :selected-cell] [0 0]))]
        (is (= :grid/activate-cell (get-in (input draft :note-on) [:dispatch :event/type])))
        (is (= (grid-outcome draft) (grid-outcome (routed draft (note :note-on)))))))
    (testing "held twice, emptied, let go twice: a release ends the intent that is there (plan 10 §7.5)"
      ;; Two keys on the cell: a held key's own repeat plays nothing (plan 26),
      ;; so the second momentary press comes from another key
      (let [run #(get-in % [:playback :active-cues [0 0] :run])
            two-keys (-> state
                         (assoc-in [:chains :cue-chains [0 0] :trigger-mode] :toggle)
                         (assoc-in [:config :input :trigger-map (assoc note-key :note 61)]
                                   {:type :trigger-cue :target [0 0] :behavior :momentary}))
            s1 (routed two-keys (note :note-on))
            s2 (routed s1 (note :note-on 61))
            emptied (:state (handlers/handle-event {:event/type :chain/set-items :domain :cue-chains
                                                    :entity-key [0 0] :items [] :state s2}))
            let-go (routed emptied (note :note-off))
            let-go-again (routed let-go (note :note-off 61))]
        (is (every? uuid? [(run s1) (run s2)]))
        (is (not= (run s1) (run s2)) "a momentary press starts it again, whatever its :toggle says")
        (is (= [{:id 1} {:id 2}] (map #(get-in % [:playback :pulses :cue]) [s1 s2])))
        (is (= {[0 0] {:run (run s2)}} (get-in emptied [:playback :active-cues])) "its items gone, its intent not")
        (is (= {} (get-in let-go [:playback :active-cues])) "the release ends it, though the cell has no cue now")
        (is (= (:playback let-go) (:playback let-go-again)) "the second release does nothing")
        (is (= {:id 2} (get-in let-go-again [:playback :pulses :cue])) "nor does either pulse")
        (testing "cleared first: the release has nothing to end"
          (let [cleared (:state (handlers/handle-event {:event/type :grid/clear-cell :col 0 :row 0 :state s2}))]
            (is (= {} (get-in cleared [:playback :active-cues])))
            (is (= (:playback cleared) (:playback (routed cleared (note :note-off)))))))))))

(deftest momentary-effect-press-and-release
  (let [state (momentary-state :trigger-effect)
        pressed (input state :note-on)
        press (:dispatch pressed)
        release (:dispatch (input (:state pressed) :note-off))]
    (is (= [:effects/toggle-cell true] [(:event/type press) (:fx/event press)]))
    (is (= [:effects/toggle-cell false] [(:event/type release) (:fx/event release)]))))

(deftest non-momentary-release-does-nothing
  (let [state (assoc-in (momentary-state :trigger-cue)
                        [:config :input :trigger-map note-key :behavior] :retrigger)]
    (is (= :grid/trigger-cell (:event/type (:dispatch (input state :note-on)))))
    (is (nil? (:dispatch (input state :note-off)))))
  (testing "a bound MIDI note and OSC button press A as a click on A does, by what A holds when handled (plan 10 §7)"
    (let [A [2 1]
          sources {:midi [(midi/midi-msg->event (fixtures/note-on 0 60 100))
                          (midi/midi-msg->event (fixtures/note-off 0 60))]
                   :osc [(first (osc/osc-msg->events (fixtures/osc-msg "/1/push1" (float 1.0)) {}))
                         (first (osc/osc-msg->events (fixtures/osc-msg "/1/push1" (float 0.0)) {}))]}
          show (fn [cue]
                 {:config {:cue {:trigger-mode :default}
                           :input {:trigger-map {note-key {:type :trigger-cue :target A}
                                                 {:source :osc :id "/1/push1"} {:type :trigger-cue :target A}}}}
                  :chains {:cue-chains (cond-> {[0 0] {:items [P]}} cue (assoc A cue))}
                  :grid {:selected-cell [0 0]}
                  :playback {:active-cues {} :active-cell nil :playing? false}
                  :trigger-runtime {}})]
      (doseq [[what cue plays?] [["a preset" {:items [P]} true]
                                 ["a preset in a disabled group" {:items [{:type :group :enabled? false :items [P]}]} true]
                                 ["a timeline without clips" {:editor-type :timeline :items []} true]
                                 ["a name and an empty group" {:name "Draft" :items [{:type :group :items []}]} false]
                                 ["no cue" nil false]]]
        (testing what
          (let [s0 (show cue)
                clicked (grid-outcome (:state (handlers/handle-event {:event/type :grid/trigger-cell :col 2 :row 1 :state s0})))]
            (is (= (if plays?
                     {:selected A :intents {A (cond-> {:run :run} (:editor-type cue) (assoc :timeline? true :start-beat 0.0))}
                      :active-cell A :playing? true :pulse {:id 1}}
                     (assoc (grid-outcome s0) :selected A))
                   clicked))
            (doseq [[source [on off]] sources]
              (testing (name source)
                (let [pressed (routed s0 on)]
                  (is (= clicked (grid-outcome pressed)))
                  (is (nil? (:dispatch (triggers/handle {:event/type :trigger/input :state pressed
                                                         :input-event off :time 2})))
                      "its release does nothing"))))))))))

(deftest start-timeline-action-emits-play-test
  (let [state (fn [action] {:config {:input {:trigger-map {{:source :keyboard :id :t} action}}}
                            :trigger-runtime {}})
        fire (fn [action] (some-> (triggers/handle {:event/type :trigger/input
                                                    :state (state action)
                                                    :input-event {:type :trigger :source :keyboard :id :t :timestamp 1}
                                                    :time 1})
                                  :dispatch
                                  (select-keys [:event/type :show-id :col :row])
                                  not-empty))
        u #uuid "00000000-0000-0000-0000-0000000000a1"]
    (is (= {:event/type :timeline/play :show-id u} (fire {:type :start-timeline :target [:show u]})))
    (is (= {:event/type :timeline/stop :show-id u} (fire {:type :stop-timeline :target [:show u]})))
    (is (nil? (fire {:type :start-timeline :target [2 1]})) "a grid cell holds no timeline: nothing")
    (is (nil? (fire {:type :stop-timeline :target [2 1]})))
    (is (nil? (fire {:type :start-timeline :target :some-id})) "nor does any other target")))

(deftest show-targets-play-and-toggle-test
  (let [u #uuid "00000000-0000-0000-0000-0000000000a1"
        k (shows/play-key u)
        bound (fn [action] {:config {:input {:trigger-map {note-key action}}}
                            :chains {:shows {u {:name "One More Time" :editor-type :timeline :items []}}}
                            :playback {:active-cues {}}
                            :trigger-runtime {}})
        dispatched #(some-> % :dispatch (dissoc :source-trigger :source-input))]
    (testing ":toggle-show: a press toggles the show's run, its release does nothing"
      (let [s (bound {:type :toggle-show :target k})
            pressed (input s :note-on)]
        (is (= {:event/type :shows/toggle-run :show-id u} (dispatched pressed)))
        (is (some? (get-in pressed [:dispatch :source-trigger])) "a trigger's: no undo step")
        (is (nil? (dispatched (input (:state pressed) :note-off))))
        (testing "through the routed handlers: starts it from beat 0, then stops it"
          (let [started (routed s (note :note-on))
                stopped (routed (routed started (note :note-off)) (note :note-on))]
            (is (= {:timeline? true :start-beat 0.0} (dissoc (get-in started [:playback :active-cues k]) :run)))
            (is (not (contains? (get-in stopped [:playback :active-cues]) k)))))))
    (testing "a momentary :toggle-show plays while held"
      (let [s (bound {:type :toggle-show :target k :behavior :momentary})
            pressed (input s :note-on)]
        (is (= {:event/type :timeline/play :show-id u :from 0.0} (dispatched pressed)))
        (is (= {:event/type :timeline/stop :show-id u} (dispatched (input (:state pressed) :note-off))))
        (is (nil? (dispatched (input s :note-off))) "a release whose press nobody saw ends nothing")))
    (testing "a :toggle-show at a cell, or no target: nothing"
      (doseq [target [[0 0] nil :x]]
        (is (nil? (dispatched (input (bound {:type :toggle-show :target target}) :note-on))))
        (is (nil? (dispatched (input (bound {:type :toggle-show :target target :behavior :momentary}) :note-on))))))))


;; Vibe mode's role bindings

(def ^:private pad-key {:source :midi :channel 0 :note 36})

(defn- pad-state [action]
  {:config {:input {:trigger-map {pad-key action}}}
   :trigger-runtime {:held-inputs #{} :active-triggers {}}})

(defn- pad [state type timestamp]
  (triggers/handle {:event/type :trigger/input
                    :state state
                    :input-event {:type type :source :midi :channel 0 :note 36 :timestamp timestamp}
                    :time 99999}))

(deftest vibe-action-maps-press-and-release-test
  (let [state (pad-state {:type :vibe :role :hit})
        pressed (pad state :note-on 1000)
        press (:dispatch pressed)
        release (:dispatch (pad (:state pressed) :note-off 1090))]
    (is (= {:event/type :vibe/press :role :hit :input-time 1000} (select-keys press [:event/type :role :input-time])))
    (is (= {:event/type :vibe/release :role :hit :input-time 1090} (select-keys release [:event/type :role :input-time]))
        "every role hears its release")
    (is (= {:type :vibe :role :hit} (:source-trigger press)) "so history skips it"))
  (testing "a binding that selects a vibe does so on its press"
    (let [state (pad-state {:type :vibe-select :vibe :vibe-2})
          pressed (pad state :note-on 1000)]
      (is (= {:event/type :vibe/select :vibe :vibe-2 :input-time 1000}
             (select-keys (:dispatch pressed) [:event/type :vibe :input-time])))
      (is (nil? (:dispatch (pad (:state pressed) :note-off 1090)))))))

(defn- bookkeeping
  "The trigger runtime but the keys' activations: the held inputs, active
   triggers and the last input time."
  [state]
  (dissoc (:trigger-runtime state) :activations))

(deftest vibe-action-skips-runtime-bookkeeping-test
  (let [state (pad-state {:type :vibe :role :hit})
        pressed (:state (pad state :note-on 1000))
        released (:state (pad pressed :note-off 1090))]
    (is (= (bookkeeping state) (bookkeeping pressed)) "probe v8: no held input, tap or active trigger")
    (is (= {pad-key {:type :vibe :role :hit}} (get-in pressed [:trigger-runtime :activations]))
        "only what the key holds, for its release")
    (is (= (bookkeeping state) (bookkeeping released)))
    (is (empty? (get-in released [:trigger-runtime :activations]))))
  (testing "other bindings keep it"
    (is (= #{pad-key} (get-in (pad (pad-state {:type :tap-tempo}) :note-on 1000) [:state :trigger-runtime :held-inputs])))))

(deftest vibe-action-carries-the-input-timestamp-test
  (state/init-state! (-> (domains/build-initial-state)
                         (templates/apply-starter-cue-chains)
                         (templates/apply-starter-vibes)
                         (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
                         (assoc-in [:config :input :trigger-map pad-key] {:type :vibe :role :hit})))
  (let [input (fn [type timestamp]
                (events/event-handler {:event/type :trigger/input
                                       :input-event {:type type :source :midi :channel 0 :note 36 :timestamp timestamp}
                                       :time timestamp})
                (state/get-raw-state))
        pressed (input :note-on 1000)
        cell (get-in pressed [:vibe-runtime :voices :hit :cell])]
    (is (= 1000 (get-in pressed [:vibe-runtime :held :hit :at]))
        "probe v2: the pipeline's :time is when the event was handled; the press is timed by the input")
    (is (nil? (get-in (input :note-off 1400) [:playback :active-cues cell]))
        "held 400 ms by the input's clock (a few by the handler's): a hold, so it is cut")))

(deftest zone-map-action-presses-the-map-test
  (let [state (pad-state {:type :zone-map :target :map/odd-even})
        press (pad state :note-on 1000)
        release (pad (:state press) :note-off 1090)]
    (is (= {:event/type :zone-map/press :map :map/odd-even :src :input}
           (select-keys (:dispatch press) [:event/type :map :src]))
        "zones plan Z4: a learned key presses the map's pad")
    (is (nil? (:dispatch release)) "its release does nothing")
    (is (= (bookkeeping state) (bookkeeping (:state press)) (bookkeeping (:state release)))
        "no bookkeeping, as for a vibe key")))

(deftest zone-map-action-carries-the-input-time-test
  (is (= 1000 (get-in (pad (pad-state {:type :zone-map :target :map/unison}) :note-on 1000) [:dispatch :input-time]))
      "handled at 99999: the press counts from when it was played"))

(deftest tap-tempo-binding-taps-at-the-input-time-test
  (let [state (pad-state {:type :tap-tempo})
        tap (:dispatch (pad state :note-on 1000))]
    (is (= {:event/type :timing/tap-tempo :input-time 1000} (select-keys tap [:event/type :input-time]))
        "handled at 99999: a bound key taps at its input's time, as HIT does (06 §4)")
    (is (nil? (:dispatch (pad (:state (pad state :note-on 1000)) :note-off 1090))) "its release taps nothing")
    (is (not (contains? (:trigger-runtime (:state (pad state :note-on 1000))) :tap-history))
        "the trigger runtime keeps no taps of its own")))

(deftest pattern-pad-action-presses-test
  (let [state (pad-state {:type :pattern-pad :target :chase})
        press (pad state :note-on 1000)]
    (is (= {:event/type :pattern-pads/press :pad :chase :src :input :input-time 1000}
           (select-keys (:dispatch press) [:event/type :pad :src :input-time]))
        "zones plan Z5: a learned key presses the pattern pad, timed by the input")
    (is (nil? (:dispatch (pad (:state press) :note-off 1090))) "its release does nothing")
    (is (= (bookkeeping state) (bookkeeping (:state press))) "no bookkeeping, as for a vibe key")
    (is (= :as-set (get-in (pad (pad-state {:type :pattern-pad :target :as-set}) :note-on 1000) [:dispatch :pad])))))

(deftest zone-step-action-steps-test
  (let [state (pad-state {:type :zone-step})
        press (pad state :note-on 1000)]
    (is (= {:event/type :pattern-pads/step :src :input :input-time 1000}
           (select-keys (:dispatch press) [:event/type :src :input-time]))
        "the Zone step key steps the patterns that step on it")
    (is (nil? (:dispatch (pad (:state press) :note-off 1090))))
    (is (= (bookkeeping state) (bookkeeping (:state press))))
    (testing "the CYCLE pad's key presses CYCLE"
      (is (= :cycle (get-in (pad (pad-state {:type :zone-map :target :cycle}) :note-on 1000) [:dispatch :map]))))))


;; A key's release ends what its press played, whatever its source and
;; whatever it is bound to by then (reactivity plan 26)

(def ^:private a1 {:type :trigger-cue :target [0 0] :behavior :momentary})
(def ^:private b1 {:type :trigger-cue :target [1 0] :behavior :momentary})
(def ^:private note-60 {:source :midi :channel 0 :note 60})
(def ^:private push {:source :osc :id "/1/push1"})

(defn- world
  "The starter show (cues A1-H1, the vibes; HIT taps no tempo and plays D1
   only, so a role never plays a cell a binding below does) with effect
   cells at B4 and C4, and bindings."
  [bindings]
  (let [fx-cell (fn [effect-id] {:items [{:type :effect :effect-id effect-id :id (random-uuid) :enabled? true}]})]
    (-> (domains/build-initial-state)
        (templates/apply-starter-cue-chains)
        (templates/apply-starter-vibes)
        (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
        (assoc-in [:config :vibes :library :vibe-1 :hit :cells] [[3 0]])
        (assoc-in [:chains :effect-chains [1 3]] (fx-cell :scale))
        (assoc-in [:chains :effect-chains [2 3]] (fx-cell :rotation))
        (assoc-in [:config :input :trigger-map] bindings))))

(defn- played
  "App state after events, in turn, through the wired pipeline, each with
   what it continues with (events/event-handler). The device worker is
   stood in for: nothing opens or closes."
  [state & events]
  (state/init-state! state)
  (with-redefs [devices/submit! (constantly true)]
    (run! events/event-handler events))
  (state/get-raw-state))

(defn- key-event
  "What the input mapper sends the app for a router event: :trigger/input."
  [input-event]
  {:event/type :trigger/input :input-event input-event :time (:timestamp input-event)})

(defn- midi-note
  "MIDI note n on channel 1 (0 inside) at velocity, as the device delivers
   it (midi-clj's message through midi/midi-msg->event): velocity 0 is a
   note-off."
  [n velocity]
  (key-event (midi/midi-msg->event (fixtures/note-on 0 n velocity))))

(defn- osc-button
  "An OSC button at address at v (1.0 down, 0.0 up), as the server delivers it."
  [address v]
  (key-event (first (osc/osc-msg->events (fixtures/osc-msg address (float v)) {}))))

(defn- pad-button
  "Gamepad 0's button going down or up, as the poll thread sends it."
  [button down?]
  (key-event (first (gamepad/diff-controller-states {0 {:buttons {button (not down?)} :axes {}}}
                                                    {0 {:buttons {button down?} :axes {}}}))))

(defn- keyboard [id edge] (key-event (input-events/trigger :keyboard id edge)))

(defn- playing [s cell] (get-in s [:playback :active-cues cell]))
(defn- effect-on? [s cell] (true? (get-in s [:chains :effect-chains cell :active?])))
(defn- held-role [s role] (get-in s [:vibe-runtime :held role :src]))
(defn- activations [s] (get-in s [:trigger-runtime :activations]))

(deftest midi-release-after-its-binding-is-cleared-ends-what-it-played-test
  (let [pressed (played (world {note-60 a1}) (midi-note 60 100))
        cleared (played pressed {:event/type :input/remove-trigger :trigger-key note-60})
        released (played cleared (midi-note 60 0))]
    (is (some? (playing pressed [0 0])))
    (is (= {note-60 a1} (activations pressed)) "the press keeps what it played, by the key")
    (is (empty? (get-in cleared [:config :input :trigger-map])))
    (is (some? (playing cleared [0 0])) "held still")
    (is (nil? (playing released [0 0])) "the note-off (a note-on at velocity 0) lets go of A1, though the note is bound to nothing now")
    (is (empty? (activations released)))))

(deftest midi-release-after-rebinding-ends-the-original-not-the-new-binding-test
  (let [s0 (played (world {note-60 a1}) {:event/type :grid/activate-cell :col 1 :row 0})
        b1-run (playing s0 [1 0])
        pressed (played s0 (midi-note 60 100))
        released (played (assoc-in pressed [:config :input :trigger-map note-60] b1) (midi-note 60 0))]
    (is (some? b1-run) "B1 plays, from a click")
    (is (some? (playing pressed [0 0])))
    (is (nil? (playing released [0 0])) "A1, which the press played, lets go")
    (is (= b1-run (playing released [1 0])) "B1, the note's binding now, plays on")
    (testing "the next press plays the new binding"
      (let [again (played released (midi-note 60 100))]
        (is (not= b1-run (playing again [1 0])) "B1 started again")
        (is (= {note-60 b1} (activations again)))))))

(deftest osc-release-after-rebinding-ends-the-original-test
  (let [fx-b4 {:type :trigger-effect :target [1 3] :behavior :momentary}
        fx-c4 {:type :trigger-effect :target [2 3] :behavior :momentary}
        s0 (played (world {push fx-b4}) {:event/type :effects/toggle-cell :col 2 :row 3})
        pressed (played s0 (osc-button "/1/push1" 1.0))
        released (played (assoc-in pressed [:config :input :trigger-map push] fx-c4) (osc-button "/1/push1" 0.0))]
    (is (effect-on? s0 [2 3]) "C4 on, from a click")
    (is (effect-on? pressed [1 3]))
    (is (not (effect-on? released [1 3])) "B4, which the press switched on, goes off")
    (is (effect-on? released [2 3]) "C4, the button's binding now, stays on")
    (testing "cleared instead: B4 goes off all the same"
      (let [cleared (played pressed {:event/type :input/remove-trigger :trigger-key push})]
        (is (not (effect-on? (played cleared (osc-button "/1/push1" 0.0)) [1 3])))))))

(deftest gamepad-release-after-rebinding-ends-the-original-role-test
  (let [button {:source :gamepad :id [0 :a]}
        pressed (played (world {button {:type :vibe :role :hit}}) (pad-button :a true))
        rebound (assoc-in pressed [:config :input :trigger-map button] {:type :vibe :role :accent})
        released (played rebound (pad-button :a false))]
    (is (= :input (held-role pressed :hit)))
    (is (nil? (held-role released :hit)) "HIT, which the press held, lets go")
    (is (nil? (held-role released :accent)) "ACCENT, the button's binding now, was never pressed")
    (testing "the gamepad switched off, or its controller gone: the poll thread sends the held button's release (input/gamepad.clj), which ends HIT too"
      (let [[release] (gamepad/diff-controller-states {0 {:buttons {:a true} :axes {}}} {})
            off (played rebound {:event/type :input/gamepad-disable} (key-event release))]
        (is (= {:type :trigger-release :source :gamepad :id [0 :a]} (select-keys release [:type :source :id])))
        (is (nil? (held-role off :hit)))
        (is (nil? (held-role off :accent)))
        (is (empty? (activations off)))))))

(deftest a-repeated-press-while-held-keeps-the-first-activation-test
  (let [pressed (played (world {note-60 a1}) (midi-note 60 100))
        a1-run (playing pressed [0 0])
        again (played (assoc-in pressed [:config :input :trigger-map note-60] b1) (midi-note 60 100))
        released (played again (midi-note 60 0))]
    (is (= a1-run (playing again [0 0])) "a second note-on, no note-off between, starts A1 again no more")
    (is (nil? (playing again [1 0])) "nor plays the new binding")
    (is (= {note-60 a1} (activations again)) "one activation, the first press's")
    (is (nil? (playing released [0 0])) "the release lets go of A1")
    (is (nil? (playing released [1 0])))
    (is (empty? (activations released))))
  (testing "a press that holds nothing (a toggled cue; an OSC address that never sends a release) plays each time"
    (let [bang (key-event (first (osc/osc-msg->events (fixtures/osc-msg "/go") {})))
          on (played (world {{:source :osc :id "/go"} {:type :trigger-cue :target [2 0]}}) bang)
          off (played on bang)]
      (is (some? (playing on [2 0])))
      (is (nil? (playing off [2 0])) "the second bang toggles C1 off"))))

(defn- disconnected
  "state after the device worker said the MIDI inputs it was asked to
   disconnect are: the answer to its latest request, else (stale? true) to
   an earlier one (input.devices)."
  [state stale?]
  (let [latest (random-uuid)]
    (played (assoc-in state [:backend :input :midi :requests :connections] latest)
            {:event/type :input/midi-disconnected :request-id (if stale? (random-uuid) latest) :connected-devices #{}})))

(deftest a-source-that-cannot-send-its-releases-retires-its-holds-test
  (let [hit-key {:source :midi :channel 0 :note 36}
        toggle-key {:source :midi :channel 0 :note 62}
        h {:source :keyboard :id :h}
        j {:source :keyboard :id :j}
        held (played (world {note-60 a1                                   ; MIDI: A1 while held
                             hit-key {:type :vibe :role :hit}             ; MIDI: HIT
                             toggle-key {:type :trigger-cue :target [2 0]} ; MIDI: C1 toggled on
                             push {:type :trigger-effect :target [1 3] :behavior :momentary} ; OSC: B4 while held
                             h b1                                         ; keyboard: B1 while held
                             j {:type :vibe :role :accent}})              ; keyboard: ACCENT
                     (midi-note 60 100) (midi-note 36 100) (midi-note 62 100) (osc-button "/1/push1" 1.0)
                     (keyboard :h :pressed) (keyboard :j :pressed))
        midi-gone? (fn [s]
                     (is (nil? (playing s [0 0])) "A1 lets go, as its note-off would have")
                     (is (nil? (held-role s :hit)) "so does HIT")
                     (is (some? (playing s [2 0])) "C1, toggled on, isn't toggled off: nothing is played")
                     (is (some? (playing s [1 0])) "the keyboard's B1 holds on: its release still comes")
                     (is (= :input (held-role s :accent)) "and its ACCENT")
                     (is (effect-on? s [1 3]) "OSC's B4 too")
                     (is (= #{push h j} (set (keys (activations s)))) "MIDI's activations are gone"))]
    (is (every? #(some? (playing held %)) [[0 0] [1 0] [2 0]]))
    (is (= [:input :input] [(held-role held :hit) (held-role held :accent)]))
    (is (effect-on? held [1 3]))
    (is (= 6 (count (activations held))))
    (testing "the MIDI inputs disconnected (the device worker's answer to the latest request)"
      (let [s (disconnected held false)]
        (midi-gone? s)
        (testing "and a note-off that comes later ends nothing, whatever the note is bound to now"
          (let [later (played (assoc-in s [:config :input :trigger-map note-60] {:type :trigger-cue :target [5 0] :behavior :momentary})
                              {:event/type :grid/activate-cell :col 5 :row 0}
                              (midi-note 60 0) (midi-note 36 0))]
            (is (some? (playing later [5 0])))
            (is (= :input (held-role later :accent)))))))
    (testing "an earlier disconnect's answer, superseded: nothing lets go"
      (let [s (disconnected held true)]
        (is (some? (playing s [0 0])))
        (is (= :input (held-role s :hit)))
        (is (= 6 (count (activations s))))))
    (testing "MIDI switched off: its note-offs can't come in"
      (midi-gone? (played held {:event/type :input/midi-disable})))
    (doseq [event [{:event/type :input/osc-stop-server} {:event/type :input/osc-disable}]]
      (testing (str (:event/type event) ": OSC's holds only")
        (let [s (played held event)]
          (is (not (effect-on? s [1 3])) "B4 goes off")
          (is (some? (playing s [0 0])) "MIDI's A1 holds on")
          (is (= [:input :input] [(held-role s :hit) (held-role s :accent)]))
          (is (= #{note-60 hit-key toggle-key h j} (set (keys (activations s))))))))
    (testing "nothing of the source held: no commit"
      (let [none (played (world {}) (midi-note 60 100))]
        (is (= {} (events/handle-event-pipeline {:event/type :trigger/retire :source :osc :state none :time 0})))))))

(defn- osc-start
  "state after the port is set to port and Start Server is pressed: the
   device worker asked (nothing opens here), and its request's id."
  [state port]
  (let [asked (played state {:event/type :input/osc-set-port :port port} {:event/type :input/osc-start-server})]
    [asked (get-in asked [:backend :input :osc :requests :server])]))

(defn- osc-started
  "state after the device worker's answer to request id: it listens on port."
  [state id port]
  (played state {:event/type :input/osc-started :request-id id :server-running? true :port port}))

(defn- osc-listening
  "state after Start Server on port, and the device worker's answer."
  [state port]
  (let [[asked id] (osc-start state port)]
    (osc-started asked id port)))

(deftest an-osc-server-moved-to-another-port-retires-its-holds-test
  ;; Reactivity follow-up B5: its controls send to the port it left
  (let [b4 {:type :trigger-effect :target [1 3] :behavior :momentary}
        listening (osc-listening (world {push b4 note-60 a1}) 9000)
        held (played listening (osc-button "/1/push1" 1.0) (midi-note 60 100))
        osc-port #(get-in % [:backend :input :osc :listening-port])]
    (is (= 9000 (osc-port listening)))
    (is (effect-on? held [1 3]))
    (is (= #{push note-60} (set (keys (activations held)))))
    (testing "Start again on the port it listens on: the button's release still comes there"
      (let [s (osc-listening held 9000)]
        (is (effect-on? s [1 3]))
        (is (= #{push note-60} (set (keys (activations s)))))))
    (testing "moved to another port: OSC's holds let go, as their releases can't come in"
      (let [s (osc-listening held 9100)]
        (is (= 9100 (osc-port s)))
        (is (not (effect-on? s [1 3])) "B4 goes off")
        (is (some? (playing s [0 0])) "MIDI's A1 holds on")
        (is (= #{note-60} (set (keys (activations s)))))
        (testing "and the button's release, if it still comes, ends nothing"
          (let [s (played s {:event/type :effects/toggle-cell :col 1 :row 3} (osc-button "/1/push1" 0.0))]
            (is (effect-on? s [1 3]) "B4, on again from a click, stays on")))))
    (testing "an earlier Start's answer, superseded, retires nothing; the latest one, on the new port, does"
      (let [[asked first-id] (osc-start held 9100)
            [asked latest-id] (osc-start asked 9100)
            s (osc-started asked first-id 9100)]
        (is (effect-on? s [1 3]))
        (is (= 9000 (osc-port s)) "still the port of the last answer it took")
        (let [s (osc-started s latest-id 9100)]
          (is (= 9100 (osc-port s)))
          (is (not (effect-on? s [1 3]))))))))

(deftest new-or-open-retires-every-hold-test
  (let [bindings {note-60 a1
                  push {:type :trigger-effect :target [1 3] :behavior :momentary}
                  {:source :gamepad :id [0 :a]} {:type :vibe :role :hit}
                  {:source :keyboard :id :h} b1}
        held (played (world bindings) (midi-note 60 100) (osc-button "/1/push1" 1.0) (pad-button :a true) (keyboard :h :pressed))
        files (persistent/project-files held)]
    (is (= 4 (count (activations held))))
    (doseq [[label next-show] [["New" (persistent/new-project-state held)]
                               ["Open" (persistent/apply-loaded-project held files "held.zip")]]]
      (testing label
        (is (every? empty? ((juxt :activations :held-inputs :active-triggers) (:trigger-runtime next-show)))
            "no key holds anything in the new show")
        (testing "and the old show's keys' releases play nothing in it"
          (let [s (played (world {note-60 b1
                                  push {:type :trigger-effect :target [2 3] :behavior :momentary}
                                  {:source :keyboard :id :h} b1})
                          {:event/type :grid/activate-cell :col 1 :row 0}
                          {:event/type :effects/toggle-cell :col 2 :row 3})
                s (played (merge s (select-keys next-show [:trigger-runtime]))
                          (midi-note 60 0) (osc-button "/1/push1" 0.0) (pad-button :a false) (keyboard :h :released))]
            (is (some? (playing s [1 0])) "B1 plays on")
            (is (effect-on? s [2 3]) "C4 stays on")))))))
