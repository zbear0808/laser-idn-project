(ns laser-show.events.handlers.triggers-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.triggers :as triggers]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]))

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

(defn- momentary-state [action-type]
  {:config {:input {:trigger-map
                    {note-key {:type action-type :target [0 0] :behavior :momentary}}}}
   :chains {:cue-chains {[0 0] {:items []}}}
   :playback {:active-cues {}}
   :trigger-runtime {}})

(defn- input [state type]
  (triggers/handle {:event/type :trigger/input
                    :state state
                    :input-event {:type type :source :midi :channel 0 :note 60 :timestamp 1}
                    :time 1}))

(deftest momentary-cue-press-and-release
  (let [state (momentary-state :trigger-cue)
        press (:dispatch (input state :note-on))
        release (:dispatch (input state :note-off))]
    (is (= :grid/activate-cell (:event/type press)))
    (is (= :grid/release-cell (:event/type release)))
    (testing "press then release through the routed grid handler starts then stops the cue"
      (let [started (:state (handlers/handle-event (assoc press :state state)))
            stopped (:state (handlers/handle-event (assoc release :state started)))]
        (is (contains? (get-in started [:playback :active-cues]) [0 0]))
        (is (not (contains? (get-in stopped [:playback :active-cues]) [0 0])))))
    (testing "press on an already playing cue keeps it playing (no toggle-off)"
      (let [started (:state (handlers/handle-event (assoc press :state state)))
            again (:state (handlers/handle-event (assoc press :state started)))]
        (is (contains? (get-in again [:playback :active-cues]) [0 0]))))))

(deftest momentary-effect-press-and-release
  (let [state (momentary-state :trigger-effect)
        press (:dispatch (input state :note-on))
        release (:dispatch (input state :note-off))]
    (is (= [:effects/toggle-cell true] [(:event/type press) (:fx/event press)]))
    (is (= [:effects/toggle-cell false] [(:event/type release) (:fx/event release)]))))

(deftest non-momentary-release-does-nothing
  (let [state (assoc-in (momentary-state :trigger-cue)
                        [:config :input :trigger-map note-key :behavior] :retrigger)]
    (is (= :grid/trigger-cell (:event/type (:dispatch (input state :note-on)))))
    (is (nil? (:dispatch (input state :note-off))))))

(deftest start-timeline-action-emits-play-test
  (let [state (fn [action] {:config {:input {:trigger-map {{:source :keyboard :id :t} action}}}
                            :trigger-runtime {}})
        fire (fn [action] (some-> (triggers/handle {:event/type :trigger/input
                                                    :state (state action)
                                                    :input-event {:type :trigger :source :keyboard :id :t :timestamp 1}
                                                    :time 1})
                                  :dispatch
                                  (select-keys [:event/type :col :row])
                                  not-empty))]
    (is (= {:event/type :timeline/play :col 2 :row 1} (fire {:type :start-timeline :target [2 1]})))
    (is (= {:event/type :timeline/stop :col 2 :row 1} (fire {:type :stop-timeline :target [2 1]})))
    (is (nil? (fire {:type :start-timeline :target :some-id})) "non-cell targets do nothing")))


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
        press (:dispatch (pad state :note-on 1000))
        release (:dispatch (pad state :note-off 1090))]
    (is (= {:event/type :vibe/press :role :hit :input-time 1000} (select-keys press [:event/type :role :input-time])))
    (is (= {:event/type :vibe/release :role :hit :input-time 1090} (select-keys release [:event/type :role :input-time]))
        "every role hears its release")
    (is (= {:type :vibe :role :hit} (:source-trigger press)) "so history skips it"))
  (testing "a binding that selects a vibe does so on its press"
    (let [state (pad-state {:type :vibe-select :vibe :vibe-2})]
      (is (= {:event/type :vibe/select :vibe :vibe-2 :input-time 1000}
             (select-keys (:dispatch (pad state :note-on 1000)) [:event/type :vibe :input-time])))
      (is (nil? (:dispatch (pad state :note-off 1090)))))))

(deftest vibe-action-skips-runtime-bookkeeping-test
  (let [state (pad-state {:type :vibe :role :hit})]
    (is (identical? state (:state (pad state :note-on 1000))) "probe v8: no held input, tap or active trigger")
    (is (identical? state (:state (pad state :note-off 1090)))))
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

(deftest tap-tempo-binding-taps-at-the-input-time-test
  (let [state (pad-state {:type :tap-tempo})
        tap (:dispatch (pad state :note-on 1000))]
    (is (= {:event/type :timing/tap-tempo :input-time 1000} (select-keys tap [:event/type :input-time]))
        "handled at 99999: a bound key taps at its input's time, as HIT does (06 §4)")
    (is (nil? (:dispatch (pad state :note-off 1090))) "its release taps nothing")
    (is (not (contains? (:trigger-runtime (:state (pad state :note-on 1000))) :tap-history))
        "the trigger runtime keeps no taps of its own")))
