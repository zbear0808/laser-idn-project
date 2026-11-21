(ns laser-show.events.zone-patterns-handlers-test
  "The zone patterns' and the pattern pads' events (zones plan Z5, 07 §6,
   §7.3): a cue's pattern from its chip, a new pattern cell, a pad's press on
   the beat or the bar, the STEP pad, and a cue's pattern edited as a chain."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.zone-patterns :as zone-patterns]
            [laser-show.routing.zone-patterns :as zp]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.templates :as templates]))

(defn- fresh
  "A new show, its starter cues, saved, at 120 BPM and playing."
  []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-effects)
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:playback :playing?] true)
      (history/mark-clean)))

(defn- run
  "state after event through the real pipeline (the history and the
   unsaved flag included), and what it dispatches, as the agent runs them."
  [state event]
  (let [effects (events/handle-event-pipeline (assoc event :state state :time 1000))
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run s next-event)
      s)))

(defn- patterns [state col row] (get-in state [:chains :cue-chains [col row] :zone-patterns]))

(defn- clock [beats & {:as more}] (merge {:beats beats :running? true :now-ms 10000} more))

(defn- schedule [state pad clock & {:as more}]
  (:state (zone-patterns/handle (merge {:event/type :pattern-pads/schedule :pad pad :clock clock :state state} more))))

(defn- pads [state] (select-keys (:pattern-pads state) [:style :pending]))


;; A cue's pattern (07 §7.1)

(deftest set-cue-pattern-adds-a-preset-test
  (let [s (run (fresh) {:event/type :zone-patterns/set-cue-pattern :col 0 :row 0 :style :alternate})
        [p :as ps] (patterns s 0 0)]
    (is (= 1 (count ps)) "a cue keeps one pattern (v1)")
    (is (= (:params (zp/preset-instance :alternate :cue)) (:params p)) "the style's preset, on the cue's clock")
    (is (= :zone-pattern (:effect-id p)))
    (is (uuid? (:id p)))
    (is (true? (:enabled? p)))
    (is (= "Change Zone Pattern" (history/undo-label (:history s))) "an undo step")
    (is (true? (get-in s [:project :dirty?])) "unsaved")
    (testing "None takes it away"
      (is (nil? (patterns (run s {:event/type :zone-patterns/set-cue-pattern :col 0 :row 0 :style nil}) 0 0))))
    (testing "an empty cell has no cue to give one to; a style that isn't one changes nothing"
      (let [s0 (fresh)]
        (is (= (:chains s0) (:chains (run s0 {:event/type :zone-patterns/set-cue-pattern :col 7 :row 3 :style :chase}))))
        (is (= (:chains s0) (:chains (run s0 {:event/type :zone-patterns/set-cue-pattern :col 0 :row 0 :style :spin}))))))))

(deftest set-cue-pattern-keeps-zones-test
  (let [s (-> (fresh)
              (run {:event/type :zone-patterns/set-cue-pattern :col 0 :row 0 :style :alternate})
              (assoc-in [:chains :cue-chains [0 0] :zone-patterns 0 :params :zones] [:zone/a :zone/c])
              (assoc-in [:chains :cue-chains [0 0] :zone-patterns 0 :params :rate] 2.0))
        before (first (patterns s 0 0))
        after (first (patterns (run s {:event/type :zone-patterns/set-cue-pattern :col 0 :row 0 :style :chase}) 0 0))]
    (is (= :chase (get-in after [:params :style])) "the style changes")
    (is (= (dissoc (:params before) :style) (dissoc (:params after) :style)) "the zones, the rate and the rest stay, as a pad switches it")
    (is (= (:id before) (:id after)) "the same pattern")))

(deftest new-cell-test
  (let [s (run (fresh) {:event/type :zone-patterns/new-cell :col 2 :row 1 :style :chase})
        cell (get-in s [:chains :effect-chains [2 1]])
        [fx] (:items cell)]
    (is (= (:params (zp/preset-instance :chase :cell)) (:params fx)) "the style's preset, counting the show's beats")
    (is (false? (:active? cell)) "it starts off: a pattern moves pictures at once")
    (is (some? (:id fx)))
    (is (= "New Zone Pattern" (history/undo-label (:history s))))
    (is (true? (get-in s [:ui :dialogs :effect-chain-editor :open?])) "its editor opens")
    (is (= [2 1] ((juxt :col :row) (get-in s [:ui :dialogs :effect-chain-editor]))))
    (testing "a Map cycle"
      (is (= :zone-map-cycle (get-in (run (fresh) {:event/type :zone-patterns/new-cell :col 2 :row 1 :style :map-cycle})
                                     [:chains :effect-chains [2 1] :items 0 :effect-id]))))
    (testing "a cell that has effects is left as it is (the Master at [0 3])"
      (let [s0 (fresh)]
        (is (= (get-in s0 [:chains :effect-chains [0 3]])
               (get-in (run s0 {:event/type :zone-patterns/new-cell :col 0 :row 3 :style :chase}) [:chains :effect-chains [0 3]])))))))


;; The pattern pads (07 §7.3, spec §9)

(deftest press-reads-the-clock-then-schedules-test
  (is (= {:show-clock/read {:on-read {:event/type :pattern-pads/schedule :pad :chase :bar? false :input-time 1234}}}
         (zone-patterns/handle {:event/type :pattern-pads/press :pad :chase :input-time 1234 :src :input :state (fresh)}))
      "no handler reads the clock: the inline effect hands it the beats")
  (testing "the effect continues the press with the clock: here no clock thread runs, so at once"
    (state/init-state! (fresh))
    (events/event-handler {:event/type :pattern-pads/press :pad :chase})
    (is (= {:style :chase :pending nil} (pads (state/get-raw-state)))))
  (testing "an unknown pad: nothing"
    (let [s0 (fresh)]
      (is (= {:state s0} (zone-patterns/handle {:event/type :pattern-pads/press :pad :spin :state s0}))))))

(deftest press-within-50-ms-switches-now-test
  (is (= {:style :off :pending nil} (pads (schedule (fresh) :off (clock 16.04)))) "40 ms after beat 16")
  (let [s (schedule (fresh) :off (clock 16.5))]
    (is (nil? (get-in s [:pattern-pads :style])) "As set until it lands")
    (is (= {:style :off :at-beat 17.0 :bar? false} (dissoc (get-in s [:pattern-pads :pending]) :id)) "otherwise the next beat")
    (is (some? (get-in s [:pattern-pads :pending :id])) "named, for the commit")
    (testing "As set's pad is nil, as the pads' style is"
      (is (= {:style nil :pending nil} (pads (schedule s :as-set (clock 16.6)))) "pressing the active one cancels the wait"))))

(deftest shift-waits-for-the-bar-test
  (is (= 20.0 (get-in (schedule (fresh) :random (clock 17.0) :bar? true) [:pattern-pads :pending :at-beat])) "Shift: the next bar")
  (let [s (assoc-in (fresh) [:config :pattern-pads :random :bar?] true)]
    (is (= 20.0 (get-in (schedule s :random (clock 17.0)) [:pattern-pads :pending :at-beat])) "the pad's Switch on the bar, for a key too")
    (is (= 18.0 (get-in (schedule s :chase (clock 17.5)) [:pattern-pads :pending :at-beat])) "another pad's isn't")))

(deftest no-clock-switches-at-once-test
  (doseq [[label s c] [["the clock thread off" (fresh) (clock 16.5 :running? false)]
                       ["the transport stopped" (assoc-in (fresh) [:playback :playing?] false) (clock 16.5)]
                       ["no tempo" (assoc-in (fresh) [:timing :bpm] 0.0) (clock 16.5)]]]
    (testing label
      (is (= {:style :swap :pending nil} (pads (schedule s :swap c)))))))

(deftest input-time-backs-the-beats-off-test
  (is (= {:style :chase :pending nil} (pads (schedule (fresh) :chase (clock 16.32 :now-ms 10150) :input-time 10000)))
      "16.32 now, 16.02 when the key was played: within 50 ms of beat 16")
  (is (= 17.0 (get-in (schedule (fresh) :chase (clock 16.32 :now-ms 10000)) [:pattern-pads :pending :at-beat])) "a pad press: from now"))

(deftest pattern-switch-is-live-test
  (let [s0 (fresh)]
    (doseq [[label event] [["at once" {:event/type :pattern-pads/schedule :pad :chase :clock (clock 16.04)}]
                           ["waiting" {:event/type :pattern-pads/schedule :pad :chase :clock (clock 16.5)}]
                           ["a due commit" {:event/type :show-clock/pattern-due :id :x :style :off}]]]
      (testing label
        (let [s (run (assoc-in s0 [:pattern-pads :pending] (when (= "a due commit" label) {:id :x :style :off :at-beat 1.0 :bar? false}))
                     event)]
          (is (not= (pads s0) (pads s)))
          (is (empty? (get-in s [:history :undo-stack])) "no undo step")
          (is (false? (get-in s [:project :dirty?])) "not unsaved"))))
    (testing "the clock's report lands it only while it is the one waiting"
      (let [w (schedule s0 :off (clock 16.5))
            id (get-in w [:pattern-pads :pending :id])]
        (is (= {:style :off :pending nil} (pads (run w {:event/type :show-clock/pattern-due :id id :style :off}))))
        (is (= (pads w) (pads (run w {:event/type :show-clock/pattern-due :id :older :style :chase}))) "one replaced since: nothing")))))

(deftest step-pulses-and-blackout-ignores-test
  (let [s (run (fresh) {:event/type :pattern-pads/step :src :pad})]
    (is (= {:id 1} (get-in s [:playback :pulses :zone-step])) "a press of the Zone step key, as a cue start pulses :cue")
    (is (= {:id 2} (get-in (run s {:event/type :pattern-pads/step :src :input :input-time 5}) [:playback :pulses :zone-step])))
    (is (empty? (get-in s [:history :undo-stack])) "a press, no edit")
    (is (false? (get-in s [:project :dirty?]))))
  (testing "during blackout no press pulses (vibe Phase 4's invariant 9)"
    (let [dark (assoc-in (fresh) [:vibe-runtime :blackout?] true)]
      (is (= (:playback dark) (:playback (run dark {:event/type :pattern-pads/step})))))))

(deftest stop-commits-the-pending-switch-test
  (let [w (schedule (fresh) :off (clock 16.5))]
    (doseq [type [:transport/stop :transport/retrigger]]
      (is (= {:style :off :pending nil} (pads (run w {:event/type type}))) (str type ": the beat it waits for belongs to the old count")))
    (is (= {:style nil :pending nil} (pads (run (fresh) {:event/type :transport/stop}))) "nothing waiting: As set stays")))

(deftest pad-pointer-and-settings-test
  (let [mouse (fn [shift?] (javafx.scene.input.MouseEvent. javafx.scene.input.MouseEvent/MOUSE_PRESSED 1.0 1.0 1.0 1.0
                                                          javafx.scene.input.MouseButton/PRIMARY 1 (boolean shift?) false false false
                                                          true false false false false true nil))]
    (is (= {:event/type :pattern-pads/press :pad :chase :bar? false :src :pad}
           (:dispatch (zone-patterns/handle {:event/type :pattern-pads/pointer :pad :chase :fx/event (mouse false) :state (fresh)}))))
    (is (true? (get-in (zone-patterns/handle {:event/type :pattern-pads/pointer :pad :chase :fx/event (mouse true) :state (fresh)})
                       [:dispatch :bar?]))
        "Shift: the bar")
    (is (= {:event/type :pattern-pads/step :src :pad}
           (:dispatch (zone-patterns/handle {:event/type :pattern-pads/pointer :pad :step :fx/event (mouse false) :state (fresh)})))
        "STEP's press presses the Zone step key: at once, no beat to wait for"))
  (let [s (run (fresh) {:event/type :pattern-pads/set-pad-bar :pad :random :bar? true})]
    (is (= {:random {:bar? true}} (get-in s [:config :pattern-pads])))
    (is (= "Change Pad Timing" (history/undo-label (:history s))))
    (is (= {} (get-in (run s {:event/type :pattern-pads/set-pad-bar :pad :random :bar? false}) [:config :pattern-pads]))))
  (let [k1 {:source :midi :channel 0 :note 40}
        k2 {:source :midi :channel 0 :note 41}
        s (-> (fresh)
              (assoc-in [:config :input :trigger-map k1] {:type :pattern-pad :target :chase})
              (assoc-in [:config :input :trigger-map k2] {:type :zone-step})
              (history/mark-clean))]
    (is (= {k2 {:type :zone-step}} (get-in (run s {:event/type :pattern-pads/clear-control :pad :chase}) [:config :input :trigger-map]))
        "its key goes, STEP's stays")
    (is (= {k1 {:type :pattern-pad :target :chase}} (get-in (run s {:event/type :pattern-pads/clear-control :pad :step}) [:config :input :trigger-map]))
        "STEP's control")
    (is (= "Clear Control" (history/undo-label (:history (run s {:event/type :pattern-pads/clear-control :pad :chase})))))))


;; A cue's pattern is a one-item chain (07 §4.5)

(deftest cue-pattern-domain-edits-params-test
  (let [s (run (fresh) {:event/type :zone-patterns/set-cue-pattern :col 0 :row 0 :style :chase})
        edited (run s {:event/type :chain/update-param :domain :cue-zone-patterns :entity-key [0 0]
                       :effect-path [0] :param-key :rate :value 2.0})]
    (is (= 2.0 (get-in (patterns edited 0 0) [0 :params :rate])) "params, modulators and keyframes edit it with no new event")
    (is (= (get-in (patterns s 0 0) [0 :params :zones]) (get-in (patterns edited 0 0) [0 :params :zones])))
    (is (seq (get-in edited [:history :undo-stack])) "an undo step")
    (testing "a modulator on its Offset"
      (let [m (run s {:event/type :modulator/toggle :domain :cue-zone-patterns :entity-key [0 0] :effect-path [0]
                      :param-key :offset :param-spec {:min -64.0 :max 64.0} :current-value 0.0 :fx/event true})]
        (is (= :sine (get-in (patterns m 0 0) [0 :params :offset :type])))))
    (testing "the cue's items are untouched"
      (is (= (get-in s [:chains :cue-chains [0 0] :items]) (get-in edited [:chains :cue-chains [0 0] :items]))))))
