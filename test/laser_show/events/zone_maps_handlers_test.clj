(ns laser-show.events.zone-maps-handlers-test
  "The zones, the show default and the zone maps' events (zones plan Z4, 06
   §6)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.zone-maps :as zone-maps]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.vibes.library :as library]))

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

(defn- zone-map
  "The map in use and the switch waiting (zones plan Z5's :cycle-paused? has
   tests of its own, below)."
  [state]
  (select-keys (:zone-map state) [:active :pending]))

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


;; Zones and maps on the Zone Groups tab (06 §6, §7.5)

(defn- with-sweep
  "A show with the custom map Crowd sweep: lanes Left and Crowd, and C on
   Center."
  []
  (history/mark-clean
   (assoc-in (fresh) [:zone-maps :map/custom-1] {:name "Crowd sweep" :lanes [:auto/left :crowd]
                                                 :overrides {:zone/c :auto/center}})))

(deftest map-editing-is-undoable-test
  (let [s1 (run (fresh) {:event/type :zone-maps/create})
        id (get-in s1 [:ui :zone-maps :selected])]
    (testing "a new map: Left / Right's lanes, yours, selected"
      (is (= :map/custom-1 id))
      (is (= {:name "New map" :lanes [:auto/left :auto/right] :overrides {}} (get-in s1 [:zone-maps id])))
      (is (= "New Zone Map" (history/undo-label (:history s1))))
      (is (true? (get-in s1 [:project :dirty?])) "unsaved"))
    (testing "renamed, a lane set, one added and one taken out, an override: each an edit"
      (let [s2 (run s1 {:event/type :zone-maps/rename :map id :name " Crowd sweep "})
            s3 (run s2 {:event/type :zone-maps/set-lane :map id :index 1 :target :crowd})
            s4 (run s3 {:event/type :zone-maps/add-lane :map id})
            s5 (run s4 {:event/type :zone-maps/remove-lane :map id :index 2})
            s6 (run s5 {:event/type :zone-maps/set-override :map id :zone :zone/c :target :auto/center})]
        (is (= [:auto/left :crowd :all] (get-in s4 [:zone-maps id :lanes])) "a new lane is All")
        (is (= {:name "Crowd sweep" :lanes [:auto/left :crowd] :overrides {:zone/c :auto/center}} (get-in s6 [:zone-maps id])))
        (is (every? #(= "Edit Zone Map" (history/undo-label (:history %))) [s2 s3 s4 s5 s6]))
        (testing "a blank name, a zone for a lane, a lane that isn't there: nothing"
          (is (= (:zone-maps s6) (:zone-maps (run s6 {:event/type :zone-maps/rename :map id :name "  "}))))
          (is (= (:zone-maps s6) (:zone-maps (run s6 {:event/type :zone-maps/set-lane :map id :index 0 :target :zone/b}))))
          (is (= (:zone-maps s6) (:zone-maps (run s6 {:event/type :zone-maps/remove-lane :map id :index 5})))))
        (testing "an override moves to another zone, or goes"
          (is (= {:zone/d :auto/center}
                 (get-in (run s6 {:event/type :zone-maps/set-override :map id :from :zone/c :zone :zone/d :target :auto/center})
                         [:zone-maps id :overrides])))
          (is (= {} (get-in (run s6 {:event/type :zone-maps/set-override :map id :zone :zone/c :target nil})
                            [:zone-maps id :overrides]))))
        (testing "an undo takes back one edit"
          (is (= (:zone-maps s5) (:zone-maps (run s6 {:event/type :edit/undo})))))
        (testing "a built-in map isn't edited, only duplicated"
          (is (= (:zone-maps s6) (:zone-maps (run s6 {:event/type :zone-maps/set-lane :map :map/odd-even :index 0 :target :graphics}))))
          (let [d (run s6 {:event/type :zone-maps/duplicate :map :map/odd-even})
                d-id (get-in d [:ui :zone-maps :selected])]
            (is (= :map/custom-2 d-id))
            (is (= {:name "Odd / Even copy" :lanes [:auto/odd :auto/even] :overrides {}} (get-in d [:zone-maps d-id])))
            (is (= "Duplicate Zone Map" (history/undo-label (:history d))))
            (is (= "Crowd sweep copy" (get-in (run s6 {:event/type :zone-maps/duplicate :map id}) [:zone-maps :map/custom-2 :name])))))
        (testing "a zone's label"
          (let [l (run s6 {:event/type :zones/set-label :zone :zone/a :label "  Beams "})]
            (is (= {:zone/a "Beams"} (get-in l [:zones :labels])))
            (is (= "Rename Zone" (history/undo-label (:history l))))
            (is (= {} (get-in (run l {:event/type :zones/set-label :zone :zone/a :label ""}) [:zones :labels])) "blank: no label")))
        (testing "selecting a map is the tab's, no edit"
          (let [sel (run s6 {:event/type :zone-maps/select :map :map/unison})]
            (is (= :map/unison (get-in sel [:ui :zone-maps :selected])))
            (is (= (history/undo-label (:history s6)) (history/undo-label (:history sel))))))))))

(deftest delete-map-asks-first-test
  (let [s (-> (with-sweep)
              (assoc-in [:config :input :trigger-map {:source :midi :channel 0 :note 48}] {:type :zone-map :target :map/custom-1})
              (assoc-in [:config :input :trigger-map {:source :midi :channel 0 :note 49}] {:type :zone-map :target :map/custom-1})
              (assoc-in [:config :zone-map-pads :map/custom-1 :bar?] true))
        asked (run s {:event/type :zone-maps/request-delete :map :map/custom-1})
        dialog (get-in asked [:ui :dialogs :confirm])
        message #(get-in (run % {:event/type :zone-maps/request-delete :map :map/custom-1}) [:ui :dialogs :confirm :message])]
    (is (= (history/extract-document-state s) (history/extract-document-state asked)) "asking deletes nothing")
    (is (:open? dialog))
    (is (= "Delete \"Crowd sweep\"?" (:title dialog)))
    (is (= "Its 2 learned keys and its pad setting go too." (:message dialog)))
    (is (= {:event/type :zone-maps/delete :map :map/custom-1} (:confirm-event dialog)))
    (is (:danger? dialog))
    (testing "what else goes with it"
      (is (= "Nothing else uses it." (message (with-sweep))))
      (is (= "It's the zone map in use: the show switches to Left / Right." (message (assoc-in (with-sweep) [:zone-map :active] :map/custom-1))))
      (is (= "Its learned key goes too." (message (assoc-in (with-sweep) [:config :input :trigger-map {:source :midi :channel 0 :note 48}]
                                                            {:type :zone-map :target :map/custom-1}))))
      (is (= "Its pad setting goes too." (message (assoc-in (with-sweep) [:config :zone-map-pads :map/custom-1 :bar?] true)))))
    (testing "a built-in map has no Delete"
      (is (not (get-in (run s {:event/type :zone-maps/request-delete :map :map/odd-even}) [:ui :dialogs :confirm :open?]))))
    (testing "yes deletes it, in one undo step"
      (let [deleted (run asked (:confirm-event dialog))]
        (is (nil? (get-in deleted [:zone-maps :map/custom-1])))
        (is (= "Delete Zone Map" (history/undo-label (:history deleted))))))))

(deftest delete-active-map-falls-back-test
  (let [k48 {:source :midi :channel 0 :note 48}
        k50 {:source :midi :channel 0 :note 50}
        s (-> (with-sweep)
              (assoc-in [:config :input :trigger-map k48] {:type :zone-map :target :map/custom-1})
              (assoc-in [:config :input :trigger-map k50] {:type :zone-map :target :map/odd-even})
              (assoc-in [:config :zone-map-pads :map/custom-1 :bar?] true)
              (assoc-in [:zone-map :active] :map/custom-1)
              (assoc-in [:ui :zone-maps :selected] :map/custom-1))
        deleted (run s {:event/type :zone-maps/delete :map :map/custom-1})
        tm (get-in deleted [:config :input :trigger-map])]
    (is (nil? (get-in deleted [:zone-maps :map/custom-1])))
    (is (= :map/left-right (get-in deleted [:zone-map :active])) "it was in use: Left / Right")
    (is (not (contains? tm k48)) "its key goes")
    (is (contains? tm k50) "another map's stays")
    (is (nil? (get-in deleted [:config :zone-map-pads :map/custom-1])) "its pad setting goes")
    (is (nil? (get-in deleted [:ui :zone-maps :selected])))
    (testing "a switch waiting for it goes"
      (let [p (run (assoc-in (with-sweep) [:zone-map :pending] {:id 1 :map :map/custom-1 :at-beat 8.0 :bar? false})
                   {:event/type :zone-maps/delete :map :map/custom-1})]
        (is (nil? (get-in p [:zone-map :pending])))
        (is (= :map/left-right (get-in p [:zone-map :active])))))
    (testing "a built-in map can't be deleted"
      (is (= (history/extract-document-state s)
             (history/extract-document-state (run s {:event/type :zone-maps/delete :map :map/odd-even})))))
    (testing "undo brings the map, its key and its setting back, not the switch: it is live"
      (let [undone (run deleted {:event/type :edit/undo})]
        (is (= (:zone-maps s) (:zone-maps undone)))
        (is (= (get-in s [:config :input :trigger-map]) (get-in undone [:config :input :trigger-map])))
        (is (= {:bar? true} (get-in undone [:config :zone-map-pads :map/custom-1])))
        (is (= :map/left-right (get-in undone [:zone-map :active])))))))


;; The Map cycle against the map pads (zones plan Z5, 07 §8.6, Z5-Q2 A)

(defn- with-cycle
  "A playing show with a Map cycle on in B2 ([1 1]), Unison in use."
  []
  (-> (playing)
      (assoc-in [:chains :effect-chains [1 1]] {:active? true :items [(assoc (zone-patterns/cycle-instance) :id :c1)]})
      (assoc-in [:zone-map :active] :map/unison)
      (history/mark-clean)))

(defn- paused? [state] (true? (get-in state [:zone-map :cycle-paused?])))

(deftest map-switch-pauses-a-running-cycle-test
  (testing "a pad's switch that lands while the cycle runs pauses it on that map"
    (let [s (run (with-cycle) {:event/type :zone-map/schedule :map :map/odd-even :clock (clock 16.04)})]
      (is (= :map/odd-even (get-in s [:zone-map :active])))
      (is (paused? s))
      (is (empty? (get-in s [:history :undo-stack])) "live: no undo step")
      (is (false? (get-in s [:project :dirty?])) "not unsaved")))
  (testing "so does one that waited, when the clock's report lands it"
    (let [waiting (run (with-cycle) {:event/type :zone-map/schedule :map :map/odd-even :clock (clock 16.5)})
          id (get-in waiting [:zone-map :pending :id])]
      (is (not (paused? waiting)) "not yet: it hasn't landed")
      (is (paused? (run waiting {:event/type :show-clock/zone-map-due :id id :map :map/odd-even})))))
  (testing "Use now, and Stop landing a waiting switch"
    (is (paused? (run (with-cycle) {:event/type :zone-maps/use-now :map :map/left-right})))
    (is (paused? (run (run (with-cycle) {:event/type :zone-map/schedule :map :map/odd-even :clock (clock 16.5)})
                      {:event/type :transport/stop}))))
  (testing "pressing the map that is active pauses on it too: the cycle's pick was in use"
    (let [s (run (with-cycle) {:event/type :zone-map/schedule :map :map/unison :clock (clock 16.04)})]
      (is (= :map/unison (get-in s [:zone-map :active])))
      (is (paused? s)))))

(deftest cycle-pad-resumes-test
  (let [paused (run (with-cycle) {:event/type :zone-maps/use-now :map :map/left-right})]
    (is (paused? paused))
    (testing "CYCLE reads the clock, as a map pad does"
      (is (= {:show-clock/read {:on-read {:event/type :zone-map/schedule :map :cycle :bar? false :input-time nil}}}
             (zone-maps/handle {:event/type :zone-map/press :map :cycle :state paused}))))
    (testing "on the beat: at once within 50 ms, it resumes and the active map stays"
      (let [s (run paused {:event/type :zone-map/schedule :map :cycle :clock (clock 14.0)})]
        (is (not (paused? s)))
        (is (= :map/left-right (get-in s [:zone-map :active])))
        (is (empty? (get-in s [:history :undo-stack])))))
    (testing "else it waits for the beat, and blinks; its due report resumes it"
      (let [waiting (run paused {:event/type :zone-map/schedule :map :cycle :clock (clock 13.5)})
            id (get-in waiting [:zone-map :pending :id])]
        (is (= {:map :cycle :at-beat 14.0 :bar? false} (dissoc (get-in waiting [:zone-map :pending]) :id)))
        (is (paused? waiting))
        (is (not (paused? (run waiting {:event/type :show-clock/zone-map-due :id id :map :cycle}))))))
    (testing "a learned key presses it too"
      (is (= :cycle (get-in (zone-maps/handle {:event/type :zone-map/press :map :cycle :input-time 5 :state paused})
                            [:show-clock/read :on-read :map]))))))

(deftest switching-a-cycle-cell-on-resumes-test
  (let [paused (-> (run (with-cycle) {:event/type :zone-maps/use-now :map :map/left-right})
                   (assoc-in [:chains :effect-chains [1 1] :active?] false)
                   (assoc-in [:chains :effect-chains [0 1]] {:active? false :items [{:id :sc :effect-id :scale :enabled? true :params {}}]}))]
    (is (paused? (run paused {:event/type :effects/toggle-cell :col 0 :row 1})) "another cell: still paused")
    (is (not (paused? (run paused {:event/type :effects/toggle-cell :col 1 :row 1}))) "the cycle's cell on: it carries on")
    (is (not (paused? (run paused {:event/type :effects/toggle-cell :col 1 :row 1 :fx/event true}))) "from its check box too")
    (let [on (assoc-in paused [:chains :effect-chains [1 1] :active?] true)]
      (is (paused? (run on {:event/type :effects/toggle-cell :col 1 :row 1})) "switched off, nothing resumes"))
    (testing "a vibe switching it on resumes it too"
      (let [s (-> paused
                  (assoc-in [:config :vibes] {:order [:v1 :v2] :next-id 3
                                              :library {:v1 (assoc (library/new-vibe :v1 1 "One") :fx [])
                                                        :v2 (assoc (library/new-vibe :v2 2 "Two") :fx [[1 1]] :allow-audience? true)}})
                  (assoc-in [:vibe-runtime :current] :v1))]
        (is (not (paused? (run s {:event/type :vibe/select :vibe :v2}))))))))

(deftest no-cycle-nothing-to-pause-test
  (let [s (run (playing) {:event/type :zone-map/schedule :map :map/odd-even :clock (clock 16.04)})]
    (is (= :map/odd-even (get-in s [:zone-map :active])))
    (is (not (paused? s)) "nothing ran, nothing paused"))
  (testing "CYCLE with no cycle on does nothing"
    (is (= (:zone-map (playing)) (:zone-map (run (playing) {:event/type :zone-map/schedule :map :cycle :clock (clock 16.5)}))))
    (is (= (:zone-map (playing)) (:zone-map (run (playing) {:event/type :zone-map/schedule :map :cycle :clock (clock 16.04)}))))))
