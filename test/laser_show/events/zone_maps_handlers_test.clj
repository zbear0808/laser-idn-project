(ns laser-show.events.zone-maps-handlers-test
  "The zones, the show default and the zone maps' events (zones plan Z4, 06
   §6)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.zone-maps :as zone-maps]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]))

(defn- fresh [] (history/mark-clean (domains/build-initial-state)))

(defn- run
  "state after event through the real pipeline (the history and the
   unsaved flag included), as the agent runs it."
  [state event]
  (or (:state (events/handle-event-pipeline (assoc event :state state :time 1000))) state))

(deftest set-default-target-test
  (let [s (run (fresh) {:event/type :zones/set-default-target :target [:zone/b :zone/a]})]
    (is (= [:zone/a :zone/b] (get-in s [:config :default-target])) "in normal form")
    (is (= "Change Show Target" (history/undo-label (:history s))) "an undo step")
    (is (true? (get-in s [:project :dirty?])) "unsaved")
    (is (nil? (get-in (run s {:event/type :zones/set-default-target :target nil}) [:config :default-target]))
        "none: All")
    (is (= :graphics (get-in (zone-maps/handle {:event/type :zones/set-default-target :fx/event {:id :graphics} :state (fresh)})
                             [:state :config :default-target]))
        "from a picker")))


;; A switch (06 §6, §8.4; spec §9)

(defn- playing
  "A new show, playing, at 120 BPM."
  []
  (-> (fresh)
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:playback :playing?] true)))

(defn- clock [beats & {:as more}] (merge {:beats beats :running? true :now-ms 10000} more))

(defn- schedule [state map-id clock & {:as more}]
  (:state (zone-maps/handle (merge {:event/type :zone-map/schedule :map map-id :clock clock :state state} more))))

(defn- zone-map [state] (:zone-map state))

(deftest press-reads-the-clock-then-schedules-test
  (is (= {:show-clock/read {:on-read {:event/type :zone-map/schedule :map :map/odd-even :bar? false :input-time 1234}}}
         (zone-maps/handle {:event/type :zone-map/press :map :map/odd-even :input-time 1234 :src :input :state (playing)}))
      "the handler reads no clock: the inline effect hands it the beats")
  (testing "the effect continues the event with the clock: here no clock thread runs, so at once"
    (state/init-state! (playing))
    (events/event-handler {:event/type :zone-map/press :map :map/odd-even})
    (is (= {:active :map/odd-even :pending nil} (zone-map (state/get-raw-state)))))
  (testing "an unknown map: nothing"
    (is (= {:state (playing)} (zone-maps/handle {:event/type :zone-map/press :map :map/gone :state (playing)})))
    (is (= {:state (playing)} (zone-maps/handle {:event/type :zone-map/press :state (playing)})))))

(deftest press-within-50-ms-switches-now-test
  (is (= {:active :map/odd-even :pending nil} (zone-map (schedule (playing) :map/odd-even (clock 16.04)))) "40 ms after beat 16")
  (let [s (schedule (playing) :map/odd-even (clock 16.5))]
    (is (= :map/left-right (:active (zone-map s))))
    (is (= {:map :map/odd-even :at-beat 17.0 :bar? false} (dissoc (:pending (zone-map s)) :id)) "otherwise the next beat")
    (is (some? (:id (:pending (zone-map s)))) "named, for the commit")))

(deftest shift-and-pad-setting-wait-for-the-bar-test
  (is (= {:map :map/unison :at-beat 20.0 :bar? true}
         (dissoc (:pending (zone-map (schedule (playing) :map/unison (clock 17.0) :bar? true))) :id))
      "Shift: the next bar")
  (let [s (assoc-in (playing) [:config :zone-map-pads :map/unison :bar?] true)]
    (is (= 20.0 (get-in (schedule s :map/unison (clock 17.0)) [:zone-map :pending :at-beat])) "the pad's setting, for a key too")
    (is (= 17.0 (get-in (schedule s :map/odd-even (clock 16.5)) [:zone-map :pending :at-beat])) "another pad's isn't")))

(deftest no-clock-switches-at-once-test
  (doseq [[label state clock] [["the clock thread off" (playing) (clock 16.5 :running? false)]
                               ["the transport stopped" (assoc-in (playing) [:playback :playing?] false) (clock 16.5)]
                               ["no tempo" (assoc-in (playing) [:timing :bpm] 0.0) (clock 16.5)]]]
    (testing label
      (is (= {:active :map/odd-even :pending nil} (zone-map (schedule state :map/odd-even clock)))))))

(deftest input-time-backs-the-beats-off-test
  (testing "a key's press counts from when it was played: 150 ms late at 120 BPM is 0.3 beat"
    (is (= {:active :map/odd-even :pending nil}
           (zone-map (schedule (playing) :map/odd-even (clock 16.32 :now-ms 10150) :input-time 10000)))
        "16.32 now, 16.02 when played: within 50 ms of beat 16")
    (is (= 17.0 (get-in (schedule (playing) :map/odd-even (clock 16.32 :now-ms 10000)) [:zone-map :pending :at-beat]))
        "a pad press: from now")))

(deftest due-commit-matches-the-id-test
  (let [s (schedule (playing) :map/odd-even (clock 16.5))
        id (get-in s [:zone-map :pending :id])
        due #(:state (zone-maps/handle {:event/type :show-clock/zone-map-due :id %1 :map %2 :state s}))]
    (is (= {:active :map/odd-even :pending nil} (zone-map (run s {:event/type :show-clock/zone-map-due :id id :map :map/odd-even})))
        "the clock's report lands it")
    (is (= (zone-map s) (zone-map (run s {:event/type :show-clock/zone-map-due :id :older :map :map/unison})))
        "a report for a switch replaced since changes nothing")
    (is (= (zone-map (run s {:event/type :show-clock/zone-map-due :id id :map :map/odd-even}))
           (zone-map (run (run s {:event/type :show-clock/zone-map-due :id id :map :map/odd-even})
                          {:event/type :show-clock/zone-map-due :id id :map :map/odd-even})))
        "twice is once")
    (is (fn? due))))

(deftest stop-and-retrigger-commit-the-pending-switch-test
  (testing "their restart puts the clock back to beat 0: the pending beat belongs to the old count"
    (let [s (schedule (playing) :map/odd-even (clock 16.5))]
      (doseq [type [:transport/stop :transport/retrigger]]
        (is (= {:active :map/odd-even :pending nil} (zone-map (run s {:event/type type}))) (str type)))
      (is (= {:active :map/left-right :pending nil} (zone-map (run (playing) {:event/type :transport/stop})))
          "nothing pending: the map stays"))))

(deftest switch-is-live-test
  (let [s0 (playing)]
    (doseq [[label event] [["a press at once" {:event/type :zone-map/schedule :map :map/odd-even :clock (clock 16.04)}]
                           ["a pending one" {:event/type :zone-map/schedule :map :map/odd-even :clock (clock 16.5)}]
                           ["Use now" {:event/type :zone-maps/use-now :map :map/unison}]]]
      (testing label
        (let [s (run s0 event)]
          (is (not= (zone-map s0) (zone-map s)))
          (is (empty? (get-in s [:history :undo-stack])) "no undo step")
          (is (false? (get-in s [:project :dirty?])) "not unsaved"))))
    (testing "Use now: at once, from the editor"
      (is (= {:active :map/unison :pending nil} (zone-map (run (schedule s0 :map/odd-even (clock 16.5)) {:event/type :zone-maps/use-now :map :map/unison}))))
      (is (= (zone-map s0) (zone-map (run s0 {:event/type :zone-maps/use-now :map :map/gone}))) "no such map: nothing"))))

(deftest pad-pointer-presses-with-shift-for-the-bar-test
  (let [mouse (fn [shift?] (javafx.scene.input.MouseEvent. javafx.scene.input.MouseEvent/MOUSE_PRESSED 1.0 1.0 1.0 1.0
                                                          javafx.scene.input.MouseButton/PRIMARY 1 (boolean shift?) false false false
                                                          true false false false false true nil))]
    (is (= {:event/type :zone-map/press :map :map/odd-even :bar? false :src :pad}
           (:dispatch (zone-maps/handle {:event/type :zone-map/pointer :map :map/odd-even :fx/event (mouse false) :state (playing)}))))
    (is (true? (get-in (zone-maps/handle {:event/type :zone-map/pointer :map :map/odd-even :fx/event (mouse true) :state (playing)})
                       [:dispatch :bar?]))
        "Shift: the bar")
    (is (= {:state (playing)}
           (zone-maps/handle {:event/type :zone-map/pointer :map :map/odd-even
                              :fx/event (javafx.scene.input.MouseEvent. javafx.scene.input.MouseEvent/MOUSE_RELEASED 1.0 1.0 1.0 1.0
                                                                        javafx.scene.input.MouseButton/PRIMARY 1 false false false false
                                                                        false false false false false true nil)
                              :state (playing)}))
        "a release does nothing")))

(deftest pad-setting-and-control-test
  (let [s (run (playing) {:event/type :zone-map/set-pad-bar :map :map/unison :bar? true})]
    (is (true? (get-in s [:config :zone-map-pads :map/unison :bar?])))
    (is (= "Change Pad Timing" (history/undo-label (:history s))))
    (is (= {} (get-in (run s {:event/type :zone-map/set-pad-bar :map :map/unison :bar? false}) [:config :zone-map-pads])) "off: no setting"))
  (let [key {:source :midi :channel 0 :note 37}
        s (-> (playing)
              (assoc-in [:config :input :trigger-map key] {:type :zone-map :target :map/odd-even})
              (assoc-in [:config :input :trigger-map {:source :midi :channel 0 :note 38}] {:type :zone-map :target :map/unison})
              (history/mark-clean))
        cleared (run s {:event/type :zone-map/clear-control :map :map/odd-even})]
    (is (= {{:source :midi :channel 0 :note 38} {:type :zone-map :target :map/unison}} (get-in cleared [:config :input :trigger-map]))
        "its key goes, another pad's stays")
    (is (= "Clear Control" (history/undo-label (:history cleared))))))
