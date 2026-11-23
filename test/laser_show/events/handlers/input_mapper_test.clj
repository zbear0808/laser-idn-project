(ns laser-show.events.handlers.input-mapper-test
  "The input mapper: discrete inputs go to the trigger map as :trigger/input,
   continuous ones to the input store, and a learn keeps the press it
   catches (K-15) but not the releases. A control learned for a fader writes
   its own key only: the render derives the fader from it
   (plans/2026-11-18-vibe-mode/05 §11)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.fixtures :as fixtures]
            [laser-show.input.gamepad :as gamepad]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.input.router :as router]
            [laser-show.input.store :as store]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.faders :as faders]))

(defn- input [type & {:as m}]
  (merge {:type type :source :midi :channel 0 :timestamp 1000} m))

(def ^:private discrete
  [(input :note-on :note 36 :velocity 1.0)
   (input :note-off :note 36 :velocity 0.0)
   (input :trigger :source :osc :id "/hit" :state :pressed)
   (input :trigger-release :source :osc :id "/hit" :state :released)])

(deftest mapper-ignores-discrete-events-while-learning-test
  (let [sent (atom [])
        learning! #(state/swap-state! assoc-in [:backend :input :learning-target] %)]
    (state/init-state! (domains/build-initial-state))
    (input-mapper/set-dispatch-fn! #(swap! sent conj %))
    (input-mapper/start-mapper!)
    (try
      (run! router/dispatch! discrete)
      (is (= 4 (count @sent)) "not learning: each goes to the trigger map")
      (is (= {:event/type :trigger/input :input-event (first discrete) :time 1000} (first @sent)))
      (learning! {:type :vibe :role :hit})
      (run! router/dispatch! discrete)
      (is (= [:note-off :trigger-release] (mapv (comp :type :input-event) (drop 4 @sent)))
          "learning: the learn catches the presses (handlers/input.clj), and their old bindings don't fire; the releases still reach theirs")
      (testing "a fader still moves while a learn waits"
        (router/dispatch! (input :control-change :control 7 :value 0.25))
        (is (= 0.25 (store/get-continuous-value [:midi 0 7]))))
      (learning! nil)
      (router/dispatch! (first discrete))
      (is (= 7 (count @sent)) "learned: inputs reach the trigger map again")
      (testing "a keyboard press already claimed by learn still cannot perform if the learn finished first"
        (let [press (input :trigger :source :keyboard :id :j :state :pressed)]
          (router/dispatch! (assoc press :learn-only? true))
          (is (= 7 (count @sent)))
          (router/dispatch! press)
          (is (= press (:input-event (last @sent))))))
      (finally
        (input-mapper/stop-mapper!)
        (input-mapper/set-dispatch-fn! nil)
        (store/clear-continuous-values!)))))

(deftest a-key-held-when-a-learn-starts-still-lets-go-test
  (testing "HIT's key held, a learn for ACCENT starts, the key is let go: so is HIT (03 §10, Found, not done)"
    (let [hit-key {:source :midi :channel 0 :note 36}
          app-state #(state/get-raw-state)]
      (state/init-state! (-> (domains/build-initial-state)
                             (templates/apply-starter-cue-chains)
                             (templates/apply-starter-vibes)
                             (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
                             (assoc-in [:config :input :trigger-map] {hit-key {:type :vibe :role :hit}})))
      (input-mapper/set-dispatch-fn! events/event-handler)
      (input-mapper/start-mapper!)
      (try
        (router/dispatch! (input :note-on :note 36 :velocity 1.0 :timestamp 1000))
        (let [cell (get-in (app-state) [:vibe-runtime :voices :hit :cell])]
          (is (= :input (get-in (app-state) [:vibe-runtime :held :hit :src])))
          (is (some? (get-in (app-state) [:playback :active-cues cell])))
          (events/event-handler {:event/type :input/start-learn :target-action {:type :vibe :role :accent}})
          (is (= {:type :vibe :role :accent} (get-in (app-state) [:backend :input :learning-target])))
          (router/dispatch! (input :note-off :note 36 :timestamp 2000))
          (is (nil? (get-in (app-state) [:vibe-runtime :held :hit])) "the role is let go")
          (is (false? (get-in (app-state) [:playback :pulses :hit :held?])) "and its pulse")
          (is (nil? (get-in (app-state) [:playback :active-cues cell])) "held a second: its cue stops, as a release does")
          (is (= {:type :vibe :role :accent} (get-in (app-state) [:backend :input :learning-target]))
              "the learn still waits for its press")
          (is (= {hit-key {:type :vibe :role :hit}} (get-in (app-state) [:config :input :trigger-map]))))
        (finally
          (events/event-handler {:event/type :input/cancel-learn})
          (input-mapper/stop-mapper!)
          (input-mapper/set-dispatch-fn! nil))))))


;; The faders: the macro map ([:config :input :macro-map])

(defn- with-macro-map
  "(f sent) with the mapper started on app state whose macro map is m (none
   at all for ::none, as in a project saved before it), the store empty
   before and after; sent holds what the mapper dispatches to the app."
  [m f]
  (state/init-state! (cond-> (domains/build-initial-state)
                       (= ::none m) (update-in [:config :input] dissoc :macro-map)
                       (not= ::none m) (assoc-in [:config :input :macro-map] m)))
  (store/clear-continuous-values!)
  (let [sent (atom [])]
    (input-mapper/set-dispatch-fn! #(swap! sent conj %))
    (input-mapper/start-mapper!)
    (try
      (f sent)
      (finally
        (input-mapper/stop-mapper!)
        (input-mapper/set-dispatch-fn! nil)
        (store/clear-continuous-values!)))))

(defn- cc
  "A MIDI CC as the device delivers it: midi-clj's message, through midi/midi-msg->event."
  [channel control value-127]
  (midi/midi-msg->event (fixtures/control-change channel control value-127)))

(defn- written [] (store/get-all-continuous-values))

(deftest bound-cc-moves-its-fader-test
  (with-macro-map {[:midi 0 1] :slider-1}
    (fn [sent]
      (router/dispatch! (cc 0 1 64))
      (is (= {[:midi 0 1] (/ 64 127.0)} (written)) "CC 1, channel 0: its own key, no stored copy for the fader (05 §11)")
      (is (= {:slider-1 (/ 64 127.0)} (faders/fader-values (written) {[:midi 0 1] :slider-1}))
          "which the render reads the fader from")
      (router/dispatch! (cc 0 1 0))
      (is (= {:slider-1 0.0} (faders/fader-values (written) {[:midi 0 1] :slider-1})) "down to 0: the fader follows")
      (is (empty? @sent) "invariant 1: a fader never visits the event agent"))))

(deftest cc-reads-no-app-state-test
  (testing "the mapper looks up no binding for a CC on the controller's thread: the render does, once a tick"
    (with-macro-map {[:midi 0 1] :slider-1}
      (fn [_]
        (let [reads (atom 0)
              get-raw-state state/get-raw-state]
          (with-redefs [state/get-raw-state (fn [] (swap! reads inc) (get-raw-state))]
            (run! #(router/dispatch! (cc 0 1 %)) (range 0 128 8)))
          (is (= 0 @reads))
          (is (= {[:midi 0 1] (/ 120 127.0)} (written))))))))

(deftest unbound-cc-writes-only-its-own-key-test
  (with-macro-map {[:midi 0 1] :slider-1}
    (fn [_]
      (router/dispatch! (cc 0 2 64))
      (router/dispatch! (cc 1 1 64))
      (is (= {[:midi 0 2] (/ 64 127.0) [:midi 1 1] (/ 64 127.0)} (written))
          "CC 2 on channel 0, and CC 1 on another channel: no fader moves"))))

(deftest gamepad-axis-can-be-a-macro-test
  (with-macro-map {[:gamepad 0 :trigger-left] :slider-2}
    (fn [_]
      (let [moves (gamepad/diff-controller-states {0 {:buttons {} :axes {:trigger-left 0.0}}}
                                                  {0 {:buttons {} :axes {:trigger-left 0.8}}})]
        (is (= 1 (count moves)) "the poller's one control change")
        (run! router/dispatch! moves)
        (is (= {[:gamepad 0 :trigger-left] 0.8} (written)))
        (is (= {:slider-2 0.8} (faders/fader-values (written) {[:gamepad 0 :trigger-left] :slider-2})))))))

(deftest unmapped-osc-value-can-be-a-macro-test
  (with-macro-map {[:osc "/fader/1"] :slider-1}
    (fn [_]
      (let [events (osc/osc-msg->events (fixtures/osc-msg "/fader/1" (float 0.3)) {})
            v (double (float 0.3))]
        (is (= [:trigger] (mapv :type events)) "an unmapped address is a trigger carrying its value")
        (run! router/dispatch! events)
        (is (= {[:osc "/fader/1"] v} (written)))
        (is (= {:slider-1 v} (faders/fader-values (written) {[:osc "/fader/1"] :slider-1})))))))

(deftest missing-macro-map-is-empty-test
  (testing "invariant 7: a project saved before faders has no :macro-map (the load merges [:config] shallowly)"
    (with-macro-map ::none
      (fn [_]
        (is (nil? (get-in (state/get-raw-state) [:config :input :macro-map])))
        (router/dispatch! (cc 0 1 64))
        (run! router/dispatch! (osc/osc-msg->events (fixtures/osc-msg "/fader/1" (float 0.3)) {}))
        (is (= #{[:midi 0 1] [:osc "/fader/1"]} (set (keys (written)))) "every control writes its own key, no fader")))))

(deftest store-keys-name-what-the-mapper-writes-test
  (testing "store-keys, which a fader's learn watches, are the keys the mapper writes"
    (doseq [event (concat [(cc 3 7 100)
                           (assoc (input :control-change :source :osc :control 5 :value 0.5) :path "/xy")
                           (input :control-change :source :osc :control 6 :value 0.5)
                           (input :control-change :source :keyboard :path "/k" :value 0.5)]
                          (gamepad/diff-controller-states {} {1 {:buttons {} :axes {:right-x 0.9}}})
                          (osc/osc-msg->events (fixtures/osc-msg "/fader/2" (float 0.6)) {})
                          (osc/osc-msg->events (fixtures/osc-msg "/fader/2" (float 0.0)) {})
                          (osc/osc-msg->events (fixtures/osc-msg "/fader1" (float 0.4)) osc/default-mappings)
                          (osc/osc-msg->events (fixtures/osc-msg "/go") {})
                          discrete
                          [(midi/midi-msg->event (fixtures/note-on 0 36 100))])]
      (with-macro-map {}
        (fn [_]
          (router/dispatch! event)
          (is (= (set (input-mapper/store-keys event)) (set (keys (written)))) (pr-str event)))))
    (is (= [] (input-mapper/store-keys (midi/midi-msg->event (fixtures/note-on 0 36 100)))) "a note writes none")
    (is (= [[:gamepad 1 :right-x]] (input-mapper/store-keys (first (gamepad/diff-controller-states {} {1 {:buttons {} :axes {:right-x 0.9}}}))))
        "a gamepad's axis")))


;; All Notes Off and All Sound Off (plans/2026-11-18-vibe-mode/07 §7,
;; invariant 4): a controller's way to say every key is up

(defn- with-held-roles
  "(f) with the mapper on app state where a key (note 36) holds HIT and a
   pad holds ACCENT, every event run through the real handlers."
  [f]
  (state/init-state! (-> (domains/build-initial-state)
                         (templates/apply-starter-cue-chains)
                         (templates/apply-starter-vibes)
                         (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
                         (assoc-in [:config :input :trigger-map] {{:source :midi :channel 0 :note 36} {:type :vibe :role :hit}})))
  (store/clear-continuous-values!)
  (input-mapper/set-dispatch-fn! events/event-handler)
  (input-mapper/start-mapper!)
  (try
    (router/dispatch! (midi/midi-msg->event (fixtures/note-on 0 36 100)))
    (events/event-handler {:event/type :vibe/press :role :accent :src :pad})
    (f)
    (finally
      (input-mapper/stop-mapper!)
      (input-mapper/set-dispatch-fn! nil)
      (store/clear-continuous-values!))))

(defn- held [] (get-in (state/get-raw-state) [:vibe-runtime :held]))

(deftest all-notes-off-releases-everything-test
  (with-held-roles
    (fn []
      (is (= {:hit :input :accent :pad} (update-vals (held) :src)))
      (router/dispatch! (cc 0 123 0))
      (is (empty? (held)) "CC 123 lets go of every role, the key's and the pad's")
      (is (not-any? #(get-in (state/get-raw-state) [:playback :pulses % :held?]) [:hit :accent]) "and their pulses")
      (is (empty? (get-in (state/get-raw-state) [:playback :active-cues])) "held: cut, as a long hold's release")
      (is (= 0.0 (store/get-continuous-value [:midi 0 123])) "the store write stays"))))

(deftest all-sound-off-releases-everything-test
  (with-held-roles
    (fn []
      (router/dispatch! (cc 3 120 0))
      (is (empty? (held)) "CC 120, on any channel"))))

(deftest ordinary-cc-does-not-release-test
  (with-held-roles
    (fn []
      (doseq [control [1 7 64 119 121 122 124 127]]
        (router/dispatch! (cc 0 control 0)))
      (is (= #{:hit :accent} (set (keys (held)))) "a fader or a pedal lets go of nothing")
      (testing "nor a control 123 from another source than MIDI"
        (router/dispatch! (input :control-change :source :osc :control 123 :value 0.0))
        (is (= #{:hit :accent} (set (keys (held)))))))))
