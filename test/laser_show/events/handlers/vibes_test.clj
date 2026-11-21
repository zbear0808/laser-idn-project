(ns laser-show.events.handlers.vibes-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.history :as history-handlers]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.templates :as templates]
            [laser-show.vibes.library :as library]
            [laser-show.vibes.rotation :as rotation])
  (:import [javafx.scene.input MouseButton MouseEvent TouchEvent TouchPoint TouchPoint$State]))

(defn- starter []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:chains :effect-chains [0 3]] {:items [{:effect-id :scale}] :active? false})))

(defn- run
  "state after event through the pipeline, then each event it dispatches,
   as the agent would."
  [state event]
  (let [effects (events/handle-event-pipeline (merge {:time 0} event {:state state}))
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run s next-event)
      s)))

(defn- undo-count [state] (count (get-in state [:history :undo-stack])))

(deftest vibe-domain-is-routed-test
  (let [s0 (starter)
        s1 (:state (handlers/handle-event {:event/type :vibe/press :role :hit :time 1000 :state s0}))]
    (is (some? (get-in s1 [:vibe-runtime :voices :hit])))
    (doseq [[type extra] [[:vibe/release {:role :hit}] [:vibe/select {:vibe :vibe-2}] [:vibe/release-all {}]]]
      (is (not= s1 (:state (handlers/handle-event (merge {:event/type type :time 1500 :state s1} extra))))
          (str type)))
    (is (= {} (handlers/handle-event {:event/type :vibe/unknown :state s0})))
    (testing "the trigger-group domain is gone (V7)"
      (is (= {} (handlers/handle-event {:event/type :trigger-group/toggle-lock :group-id :g :state s0}))))))

(deftest document-roots-stay-identical-test
  (testing "invariant 7: HIT and ACCENT leave every document root as it was"
    (reduce (fn [s event]
              (let [s' (run s event)]
                (is (history/document-roots-identical? s s') (pr-str event))
                s'))
            (-> (starter) (assoc-in [:config :vibes :settings :hit-taps-tempo?] false) (history/mark-clean))
            [{:event/type :vibe/press :role :hit :input-time 1000}
             {:event/type :vibe/release :role :hit :input-time 1090}
             {:event/type :vibe/press :role :accent :input-time 1100}
             {:event/type :vibe/release :role :accent :input-time 1500}
             {:event/type :vibe/press :role :hit :input-time 1600}
             {:event/type :vibe/release :role :hit :input-time 2000}]))
  (testing "a tap of the tempo changes the taps, not the document"
    (let [s0 (history/mark-clean (starter))
          s1 (run s0 {:event/type :vibe/press :role :hit :input-time 1000})]
      (is (= [1000] (get-in s1 [:timing :tap :taps])) "at the press's own time")
      (is (= 0 (undo-count s1)))
      (is (false? (get-in s1 [:project :dirty?]))))))

(deftest fx-set-switch-is-live-not-undoable-test
  (let [s0 (-> (starter)
               (assoc-in [:config :vibes :library :vibe-2 :fx] [[0 3]])
               (assoc-in [:config :vibes :library :vibe-1 :flash :fx] [[0 3]])
               (history/mark-clean))
        doc history/extract-document-state]
    (doseq [[what events on?] [["a vibe switch" [{:event/type :vibe/select :vibe :vibe-2 :input-time 1000}] true]
                               ["a flash" [{:event/type :vibe/press :role :flash :input-time 1000}] true]
                               ["blackout" [{:event/type :vibe/select :vibe :vibe-2 :input-time 1000}
                                            {:event/type :vibe/press :role :blackout :input-time 1100}] false]]]
      (let [s (reduce run s0 events)]
        (testing what
          (is (= on? (get-in s [:chains :effect-chains [0 3] :active?])))
          (is (false? (history/undoable-changed? (doc s0) (doc s))) "not undoable")
          (is (false? (history/persisted-changed? (doc s0) (doc s))) "not an unsaved change")
          (is (= 0 (undo-count s)))
          (is (false? (get-in s [:project :dirty?]))))))))


;; Auto-advance (Phase 6, plans/2026-11-18-vibe-mode/06 §7, "What the handlers
;; do"): 45 s, then a gap of 2 beats at 120 BPM

(defn- rt [state & ks] (get-in state (into [:vibe-runtime] ks)))

(defn- press-release
  "state after role's press at t and its release 50 ms later."
  [state role t]
  (-> state
      (run {:event/type :vibe/press :role role :input-time t})
      (run {:event/type :vibe/release :role role :input-time (+ t 50)})))

(defn- auto-on
  "The starter vibes with AUTO on, and Shapes entered by a HIT at 1000."
  []
  (-> (starter)
      (run {:event/type :vibe/set-auto :auto? true})
      (press-release :hit 1000)))

(def ^:private arm {:event/type :vibe/arm :from :vibe-1})
(def ^:private gap {:event/type :vibe/advance :reason :gap :from :vibe-1})

(defn- armed
  "auto-on, armed by the show clock's report 46 s in."
  []
  (run (auto-on) (assoc arm :time 46000)))

(deftest arm-is-idempotent-test
  (let [s0 (auto-on)
        s1 (armed)]
    (is (= [:vibe-1 :active 1000] [(rt s0 :current) (rt s0 :rotation) (rt s0 :entered-at)]))
    (is (= :armed (rt s1 :rotation)) "Shapes has had its 45 s")
    (is (= s1 (run s1 (assoc arm :time 46100))) "the same again: unchanged")
    (testing "a report that no longer holds changes nothing"
      (is (= s0 (run s0 (assoc arm :time 45999))) "early")
      (is (= s0 (run s0 (assoc arm :from :vibe-2 :time 46000))) "for a vibe that doesn't play")
      (is (= :active (rt (-> s0 (run {:event/type :vibe/set-auto :auto? false}) (run (assoc arm :time 46000))) :rotation))
          "with AUTO off"))))

(deftest stale-advance-is-ignored-test
  (let [s0 (armed)
        s1 (run s0 (assoc gap :time 50000))]
    (is (= [:vibe-2 :active 50000] [(rt s1 :current) (rt s1 :rotation) (rt s1 :entered-at)])
        "the next vibe, its 45 s from now")
    (is (= s1 (run s1 (assoc gap :time 50250))) "invariant 2: the report again, its :from is stale")
    (let [three (-> s0
                    (assoc-in [:config :vibes :library :vibe-3]
                              (assoc (get-in s0 [:config :vibes :library :vibe-2]) :id :vibe-3 :name "Build"))
                    (update-in [:config :vibes :order] conj :vibe-3))]
      (is (= three (run three {:event/type :vibe/advance :reason :flash :from :vibe-2 :time 50000}))
          "a FLASH release's change for a vibe that no longer plays (with three vibes, its next isn't this one)"))
    (testing "the performer tapped VIBE first"
      (let [tapped (press-release s0 :vibe 49000)]
        (is (= :vibe-2 (rt tapped :current)))
        (is (= tapped (run tapped (assoc gap :time 50000))))))
    (testing "no longer armed for a gap: a key went down, a HIT came, AUTO went off"
      (let [held (run s0 {:event/type :vibe/press :role :accent :input-time 49000})]
        (is (= held (run held (assoc gap :time 50000))) "ACCENT held"))
      (let [hit (press-release s0 :hit 49500)]
        (is (= hit (run hit (assoc gap :time 50000))) "half a second after a HIT"))
      (let [off (run s0 {:event/type :vibe/set-auto :auto? false})]
        (is (= :active (rt off :rotation)) "AUTO off calls the armed change off")
        (is (= off (run off (assoc gap :time 50000))))))))

(deftest flash-release-while-armed-advances-test
  (let [s1 (run (armed) {:event/type :vibe/press :role :flash :input-time 47000})]
    (testing "beyond-vibe phrase_gap_waits_for_flash_release: FLASH held defers the gap"
      (is (some? (get-in s1 [:playback :active-cues [6 0]])))
      (is (nil? (rotation/due s1 60000 120.0))))
    (let [s2 (run s1 {:event/type :vibe/release :role :flash :input-time 60000})]
      (is (= [:vibe-2 :active 60000] [(rt s2 :current) (rt s2 :rotation) (rt s2 :entered-at)])
          "and its release changes the vibe")
      (is (nil? (get-in s2 [:playback :active-cues [6 0]])) "the flash let go of"))
    (testing "not while HIT or ACCENT is held"
      (doseq [role [:hit :accent]]
        (let [s (-> s1
                    (run {:event/type :vibe/press :role role :input-time 59000})
                    (run {:event/type :vibe/release :role :flash :input-time 60000}))]
          (is (= [:vibe-1 :armed] [(rt s :current) (rt s :rotation)]) (str (name role) " held")))))
    (testing "nor before the vibe is armed, nor with AUTO off"
      (is (= [:vibe-1 :active] ((juxt :current :rotation) (:vibe-runtime (press-release (auto-on) :flash 5000)))))
      (is (= :vibe-1 (rt (-> s1
                             (run {:event/type :vibe/set-auto :auto? false})
                             (run {:event/type :vibe/release :role :flash :input-time 60000}))
                         :current))))
    (testing "nor when release-all lets go of it: a stuck key mustn't change the look"
      (is (= :vibe-1 (rt (run s1 {:event/type :vibe/release-all :input-time 60000}) :current))))
    (testing "what the solo flash kept waiting doesn't start: it would end with Shapes' look at once"
      (let [s (-> (starter)
                  (assoc-in [:config :vibes :library :vibe-1 :hit] {:cells [[0 0] [1 0]] :select :sequence :mode :sustain})
                  (run {:event/type :vibe/set-auto :auto? true})
                  (press-release :accent 1000)
                  (run (assoc arm :time 46000))
                  (run {:event/type :vibe/press :role :flash :input-time 47000})
                  (press-release :hit 48000))
            s' (run s {:event/type :vibe/release :role :flash :input-time 60000})]
        (is (= {:hit {:mode :sustain}} (rt s :duck)) "a :sustain HIT pressed during the solo flash")
        (is (= :vibe-2 (rt s' :current)))
        (is (nil? (rt s' :bags [:vibe-1 :hit])) "no cell of Shapes' HIT picked, to be stopped at once")
        (is (empty? (rt s' :duck)))))))

(deftest manual-next-restarts-the-timer-test
  (let [s0 (armed)
        tapped (press-release s0 :vibe 47000)]
    (is (= [:vibe-2 :active 47050] [(rt tapped :current) (rt tapped :rotation) (rt tapped :entered-at)])
        "a VIBE tap while armed: the next vibe, its 45 s from the tap")
    (is (nil? (rotation/due tapped 92049 120.0)))
    (is (= {:event/type :vibe/arm :from :vibe-2} (rotation/due tapped 92050 120.0)))
    (testing "the strip's select too"
      (let [s (run s0 {:event/type :vibe/select :vibe :vibe-2 :input-time 48000})]
        (is (= [:vibe-2 :active 48000] [(rt s :current) (rt s :rotation) (rt s :entered-at)]))))))

(deftest unlock-restarts-the-timer-test
  (let [hold (fn [s t] (-> s
                           (run {:event/type :vibe/press :role :vibe :input-time t})
                           (run {:event/type :vibe/release :role :vibe :input-time (+ t 700)})))
        locked (hold (armed) 47000)]
    (is (= [:vibe-1 :locked] [(rt locked :current) (rt locked :rotation)]) "a hold while armed locks")
    (is (nil? (rotation/due locked 1000000 120.0)))
    (let [s (hold locked 100000)]
      (is (= [:vibe-1 :active 100700] [(rt s :current) (rt s :rotation) (rt s :entered-at)]) "unlocked: its 45 s from now")
      (is (nil? (rotation/due s 145699 120.0)))
      (is (= arm (rotation/due s 145700 120.0))))))

(deftest auto-switch-is-session-only-test
  (let [s0 (history/mark-clean (starter))
        s1 (run s0 {:event/type :vibe/set-auto :auto? true})
        s2 (run s1 {:event/type :vibe/set-auto :auto? false})]
    (is (= [true false] [(rt s1 :auto?) (rt s2 :auto?)]))
    (doseq [s [s1 s2]]
      (is (history/document-roots-identical? s0 s) "invariant 4: no document root changes")
      (is (= 0 (undo-count s)) "and there is no undo step")
      (is (false? (get-in s [:project :dirty?]))))
    (testing "New and Open forget it: the saved :mode again"
      (is (nil? (rt (persistent/new-project-state s1) :auto?)))
      (is (nil? (rt (persistent/apply-loaded-project s1 (persistent/project-files s1) "x.zip") :auto?))))))

(deftest auto-defaults-from-the-saved-setting-test
  (let [saved (fn [mode] (-> (starter)
                             (assoc-in [:config :vibes :settings :rotation :mode] mode)
                             (press-release :hit 1000)))
        armed? #(= :armed (rt (run % (assoc arm :time 46000)) :rotation))]
    (is (= {:mode :off :seconds 45 :pause-beats 2.0} (get-in (starter) [:config :vibes :settings :rotation]))
        "a new project's: off (V4 A)")
    (is (nil? (rt (saved :off) :auto?)) "nil until the switch is used")
    (is (not (armed? (saved :off))))
    (is (armed? (saved :auto)) "a project saved with :auto")
    (is (not (armed? (run (saved :auto) {:event/type :vibe/set-auto :auto? false}))) "the switch wins")
    (testing "turned on, the vibe's time counts from then"
      (let [on (run (saved :off) {:event/type :vibe/set-auto :auto? true :time 20000})]
        (is (= 20000 (rt on :entered-at)))
        (is (not (armed? on)))
        (is (= :armed (rt (run on (assoc arm :time 65000)) :rotation)))))))

(deftest unblackout-restarts-the-timer-test
  (let [dark (run (armed) {:event/type :vibe/press :role :blackout :input-time 47000})
        back (run dark {:event/type :vibe/press :role :blackout :input-time 60000})]
    (is (nil? (rotation/due dark 1000000 120.0)) "nothing changes by itself during blackout")
    (is (= [:vibe-1 :active 60000] [(rt back :current) (rt back :rotation) (rt back :entered-at)]))
    (is (nil? (rotation/due back 104999 120.0)))
    (is (= arm (rotation/due back 105000 120.0)))))

(deftest auto-advance-pulses-vibe-as-a-tap-does-test
  (let [s0 (armed)]
    (is (nil? (get-in s0 [:playback :pulses :vibe])) "the first HIT made Shapes current: no change, no pulse")
    (is (= {:id 1} (get-in (run s0 (assoc gap :time 50000)) [:playback :pulses :vibe]))
        "a phrase gap's change, so a Fade in fades the new look in")
    (is (= {:id 1} (get-in (press-release s0 :flash 47000) [:playback :pulses :vibe])) "and a FLASH release's")))

(deftest a-hit-that-plays-nothing-holds-the-gap-off-test
  (let [s0 (assoc-in (armed) [:config :vibes :library :vibe-1 :hit :cells] [])
        s1 (press-release s0 :hit 49500)]
    (is (= {:role :hit :why :empty-pool} (rt s1 :problem)) "it plays no cue (it moves effects only, say)")
    (is (= 49500 (rt s1 :last-hit-at)) "but it is a HIT")
    (is (nil? (rotation/due s1 50000 120.0)) "so the gap starts again")
    (is (= gap (rotation/due s1 50500 120.0)))))

(deftest hit-taps-the-tempo-as-the-tap-button-does-test
  (let [times [1000 1500 1505 2000 2500 3000]
        s0 (assoc-in (starter) [:timing :bpm] 90.0)
        by-hit (reduce (fn [s t] (-> s
                                     (run {:event/type :vibe/press :role :hit :input-time t})
                                     (run {:event/type :vibe/release :role :hit :input-time (+ t 2)})))
                       s0 times)
        by-button (reduce #(run %1 {:event/type :timing/tap-tempo :time %2}) s0 times)]
    (is (= 120.0 (get-in by-hit [:timing :bpm]) (get-in by-button [:timing :bpm]))
        "invariant 6: one algorithm, which leaves out the bounce at 1505 for both")
    (is (= (get-in by-button [:timing :tap]) (get-in by-hit [:timing :tap])))))


;; The Perform tab's pads (Phase 2)

(defn- mouse
  "A mouse event of type as JavaFX delivers it: the primary button unless
   :button, :synthesized? when JavaFX made it from a touch, :popup? for a
   press that opens the context menu (a Ctrl-click on macOS)."
  [type & {:keys [button synthesized? popup?] :or {button MouseButton/PRIMARY}}]
  (MouseEvent. type 10.0 10.0 10.0 10.0 button 1 false false false false
               (= MouseButton/PRIMARY button) false (= MouseButton/SECONDARY button)
               (boolean synthesized?) (boolean popup?) true nil))

(def ^:private touch-kinds
  {:pressed [TouchEvent/TOUCH_PRESSED TouchPoint$State/PRESSED]
   :moved [TouchEvent/TOUCH_MOVED TouchPoint$State/MOVED]
   :stationary [TouchEvent/TOUCH_STATIONARY TouchPoint$State/STATIONARY]
   :released [TouchEvent/TOUCH_RELEASED TouchPoint$State/RELEASED]})

(defn- touch
  "The event a pad gets for the touch point id (in state) that went down on
   it, in an event set with the other points, given as id state pairs."
  [id state & others]
  (let [point (fn [[id state]] (TouchPoint. (int id) (second (touch-kinds state)) 10.0 10.0 10.0 10.0 nil nil))
        this (point [id state])]
    (TouchEvent. (first (touch-kinds state)) this (into [this] (map point) (partition 2 others))
                 1 false false false false)))

(defn- pointer
  "What a pad's pointer event dispatches (nil: nothing)."
  [state role e]
  (:dispatch (handlers/handle-event {:event/type :vibe/pointer :role role :fx/event e :state state})))

(defn- pad-presses
  "[state sent]: the state after the pads' pointer events ([role event]
   pairs) and the presses and releases they sent, through the pipeline."
  [state role-events]
  (reduce (fn [[s sent] [role e]]
            (if-let [event (pointer s role e)]
              [(run s event) (conj sent [(:event/type event) role])]
              [s sent]))
          [state []]
          role-events))

(deftest pointer-presses-with-the-primary-button-only-test
  (let [s (starter)
        held (assoc-in s [:vibe-runtime :held :hit] {:at 1000 :src :pad})]
    (is (= {:event/type :vibe/press :role :hit :src :pad} (pointer s :hit (mouse MouseEvent/MOUSE_PRESSED))))
    (is (= {:event/type :vibe/release :role :hit :src :pad} (pointer held :hit (mouse MouseEvent/MOUSE_RELEASED))))
    (testing "a right-click (it opens the pad's menu) neither presses nor releases"
      (doseq [type [MouseEvent/MOUSE_PRESSED MouseEvent/MOUSE_RELEASED MouseEvent/MOUSE_CLICKED]]
        (is (nil? (pointer held :hit (mouse type :button MouseButton/SECONDARY))) (str type))))
    (testing "nor does a Ctrl-click's press (the right-click of a one-button mouse on macOS)"
      (is (nil? (pointer s :hit (mouse MouseEvent/MOUSE_PRESSED :popup? true)))))
    (testing "the mouse's other events do nothing"
      (doseq [type [MouseEvent/MOUSE_CLICKED MouseEvent/MOUSE_DRAGGED MouseEvent/MOUSE_ENTERED MouseEvent/MOUSE_EXITED]]
        (is (nil? (pointer held :hit (mouse type))) (str type))))))

(deftest one-touch-gives-one-press-and-one-release-test
  (testing "one finger on HIT: its touch point counts, the mouse events JavaFX makes from it don't"
    (let [[s sent] (pad-presses (starter)
                                [[:hit (touch 1 :pressed)]
                                 [:hit (mouse MouseEvent/MOUSE_PRESSED :synthesized? true)]
                                 [:hit (touch 1 :moved)]
                                 [:hit (mouse MouseEvent/MOUSE_DRAGGED :synthesized? true)]
                                 [:hit (touch 1 :released)]
                                 [:hit (mouse MouseEvent/MOUSE_RELEASED :synthesized? true)]
                                 [:hit (mouse MouseEvent/MOUSE_CLICKED :synthesized? true)]])]
      (is (= [[:vibe/press :hit] [:vibe/release :hit]] sent))
      (is (= 1 (get-in s [:vibe-runtime :started])) "one cue picked")))
  (testing "two fingers: HIT held while ACCENT is tapped; each pad hears its own finger once"
    (let [[s sent] (pad-presses (starter)
                                [[:hit (touch 1 :pressed)]
                                 [:hit (mouse MouseEvent/MOUSE_PRESSED :synthesized? true)]
                                 [:accent (touch 2 :pressed 1 :stationary)]
                                 [:hit (touch 1 :stationary 2 :pressed)]
                                 [:accent (touch 2 :released 1 :stationary)]
                                 [:hit (touch 1 :stationary 2 :released)]])]
      (is (= [[:vibe/press :hit] [:vibe/press :accent] [:vibe/release :accent]] sent))
      (is (= :pad (get-in s [:vibe-runtime :held :hit :src])) "HIT is still down")
      (is (nil? (get-in s [:vibe-runtime :held :accent]))))))

(deftest pad-release-lets-go-only-of-a-pad-hold-test
  (let [up (mouse MouseEvent/MOUSE_RELEASED)
        s (starter)]
    (is (some? (pointer (assoc-in s [:vibe-runtime :held :hit] {:at 1000 :src :pad}) :hit up)))
    (is (nil? (pointer (assoc-in s [:vibe-runtime :held :hit] {:at 1000 :src :input}) :hit up))
        "a key pressed HIT after the pad: the key's release ends that hold")
    (is (nil? (pointer s :hit up)) "nothing held: the press opened the menu, or a focus loss let go")))

(deftest pad-release-ends-a-press-that-played-nothing-test
  (testing "a pad's press that played nothing holds its pulse only, and the pad's release still lets it go"
    (let [s0 (assoc-in (starter) [:config :vibes :library :vibe-1 :hit :cells] [])
          [s sent] (pad-presses s0 [[:hit (mouse MouseEvent/MOUSE_PRESSED)] [:hit (mouse MouseEvent/MOUSE_RELEASED)]])]
      (is (= [[:vibe/press :hit] [:vibe/release :hit]] sent))
      (is (= {:id 1 :held? false} (get-in s [:playback :pulses :hit])))
      (is (nil? (pointer (assoc-in s0 [:playback :pulses :hit] {:id 1 :held? true :src :input}) :hit
                         (mouse MouseEvent/MOUSE_RELEASED)))
          "a key's press that played nothing: its own release ends it, not the pad's"))))

(deftest window-focus-lost-releases-pad-holds-only-test
  (let [s1 (-> (starter)
               (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
               (run {:event/type :vibe/press :role :hit :src :pad :input-time 1000})
               (run {:event/type :vibe/press :role :accent :input-time 1000}))
        hit-cell (get-in s1 [:vibe-runtime :voices :hit :cell])
        focus (fn [focused?] (handlers/handle-event {:event/type :vibe/window-focus :fx/event focused? :state s1}))]
    (is (= {:dispatch {:event/type :vibe/release-all :only :pad}} (focus false)))
    (is (= {} (focus true)) "focus coming back changes nothing")
    (let [s2 (run s1 {:event/type :vibe/window-focus :fx/event false :input-time 2000})]
      (is (nil? (get-in s2 [:vibe-runtime :held :hit])) "the pointer's hold ended")
      (is (nil? (get-in s2 [:playback :active-cues hit-cell])) "held for a second: cut")
      (is (= :input (get-in s2 [:vibe-runtime :held :accent :src])) "a MIDI hold doesn't end on focus loss")
      (is (some? (get-in s2 [:playback :active-cues (get-in s2 [:vibe-runtime :voices :accent :cell])]))))))

(deftest clear-control-removes-only-that-role-test
  (let [accent {:source :midi :channel 0 :note 37}
        cell {:source :midi :channel 0 :note 40}
        s0 (-> (starter)
               (assoc-in [:config :input :trigger-map]
                         {{:source :midi :channel 0 :note 36} {:type :vibe :role :hit}
                          {:source :keyboard :id :h} {:type :vibe :role :hit}
                          accent {:type :vibe :role :accent}
                          cell {:type :trigger-cue :target [0 0]}})
               (history/mark-clean))
        s1 (run s0 {:event/type :vibe/clear-control :role :hit})]
    (is (= {accent {:type :vibe :role :accent} cell {:type :trigger-cue :target [0 0]}}
           (get-in s1 [:config :input :trigger-map])))
    (is (= "Clear Control" (history/undo-label (:history s1))) "an undoable edit, labelled")
    (is (true? (get-in s1 [:project :dirty?])))))


;; The Vibes tab's edits (Phase 3)

(def ^:private edits
  "Each edit event, with what changes the starter vibes, and its undo label."
  [[{:event/type :vibe/add} "Add Vibe"]
   [{:event/type :vibe/duplicate :vibe :vibe-1} "Duplicate Vibe"]
   [{:event/type :vibe/remove :vibe :vibe-2} "Delete Vibe"]
   [{:event/type :vibe/move :vibe :vibe-2 :to 0} "Reorder Vibes"]
   [{:event/type :vibe/rename :vibe :vibe-1 :name "Calm"} "Rename Vibe"]
   [{:event/type :vibe/set-color :vibe :vibe-1 :color "#1ABC9C"} "Change Vibe"]
   [{:event/type :vibe/set-energy :vibe :vibe-1 :energy 4} "Change Vibe"]
   [{:event/type :vibe/toggle-cell :vibe :vibe-1 :slot :hit :col 7 :row 0} "Edit Vibe Cues"]
   [{:event/type :vibe/toggle-fx :vibe :vibe-1 :slot :fx :col 0 :row 3} "Edit Vibe Effects"]
   [{:event/type :vibe/set-option :vibe :vibe-1 :role :hit :key :mode :value :gate} "Change Vibe Option"]
   [{:event/type :vibe/make-from-rows} "Make Vibes From Rows"]])

(deftest every-edit-event-is-one-undo-step-test
  (let [s0 (history/mark-clean (starter))]
    (doseq [[event label] edits]
      (testing (str (:event/type event))
        (let [s1 (run s0 event)
              s2 (run s1 {:event/type :edit/undo})]
          (is (not= (get-in s0 [:config :vibes]) (get-in s1 [:config :vibes])) "it edits")
          (is (= 1 (undo-count s1)))
          (is (= label (history/undo-label (:history s1))))
          (is (true? (get-in s1 [:project :dirty?])))
          (is (= (get-in s0 [:config :vibes]) (get-in s2 [:config :vibes])) "invariant 1: undo restores the vibes exactly")
          (is (false? (get-in s2 [:project :dirty?]))))))))

(deftest selection-events-are-not-undoable-test
  (let [s0 (history/mark-clean (starter))
        s1 (-> s0
               (run {:event/type :vibe/select-for-edit :vibe :vibe-2})
               (run {:event/type :vibe/set-slot :slot :bed}))]
    (is (= {:selected :vibe-2 :slot :bed :wizard nil} (get-in s1 [:ui :vibes])))
    (is (= 0 (undo-count s1)))
    (is (false? (get-in s1 [:project :dirty?])))
    (is (history/document-roots-identical? s0 s1) "invariant 1: clicking around the editor makes no undo step")
    (testing "an unknown vibe or slot changes nothing"
      (is (= s1 (-> s1
                    (run {:event/type :vibe/select-for-edit :vibe :gone})
                    (run {:event/type :vibe/set-slot :slot :loud})))))))

(deftest edit-events-return-state-without-arguments-test
  (testing "the history-label test calls every labelled event with none (events/history_handlers_test.clj)"
    (let [s0 (starter)]
      (doseq [type (filter #(= "vibe" (namespace %)) (keys history-handlers/event-descriptions))]
        (let [result (handlers/handle-event {:event/type type :state s0})]
          (is (map? (:state result)) (str type))
          (when-not (#{:vibe/add :vibe/make-from-rows} type)
            (is (= (get-in s0 [:config :vibes]) (get-in result [:state :config :vibes])) (str type " changed nothing"))))))))

(deftest picker-clicks-leave-empty-cells-out-test
  (let [s0 (starter)
        click (fn [state type slot col row] (run state {:event/type type :vibe :vibe-1 :slot slot :col col :row row}))]
    (is (= s0 (click s0 :vibe/toggle-cell :hit 0 2)) "an empty cue cell has nothing to play")
    (is (= s0 (click s0 :vibe/toggle-cell :bed 0 2)))
    (is (= s0 (click s0 :vibe/toggle-fx :fx 5 3)) "nor an empty effect cell")
    (testing "a cell in the pool comes out, whatever it holds"
      (let [s (assoc-in s0 [:config :vibes :library :vibe-1 :hit :cells] [[0 0] [0 2]])]
        (is (= [[0 0]] (get-in (click s :vibe/toggle-cell :hit 0 2) [:config :vibes :library :vibe-1 :hit :cells])))))
    (is (= [0 0] (get-in (run s0 {:event/type :vibe/toggle-cell :vibe :vibe-2 :slot :bed :cell [0 0]})
                         [:config :vibes :library :vibe-2 :bed]))
        "or given as :cell")))

(deftest new-vibes-are-selected-test
  (let [selected #(get-in % [:ui :vibes :selected])]
    (is (= :vibe-3 (selected (run (starter) {:event/type :vibe/add}))))
    (is (= :vibe-3 (selected (run (starter) {:event/type :vibe/duplicate :vibe :vibe-2}))))
    (is (= :vibe-3 (selected (run (starter) {:event/type :vibe/make-from-rows}))) "the first one made")
    (testing "deleting the vibe being edited selects its neighbour"
      (is (= :vibe-2 (selected (-> (starter)
                                   (run {:event/type :vibe/select-for-edit :vibe :vibe-1})
                                   (run {:event/type :vibe/remove :vibe :vibe-1})))))
      (is (= :vibe-1 (selected (-> (starter)
                                   (run {:event/type :vibe/select-for-edit :vibe :vibe-2})
                                   (run {:event/type :vibe/remove :vibe :vibe-2})))))
      (is (= :vibe-2 (selected (-> (starter)
                                   (run {:event/type :vibe/select-for-edit :vibe :vibe-2})
                                   (run {:event/type :vibe/remove :vibe :vibe-1}))))
          "another vibe: the selection stays"))))

(deftest editing-the-playing-vibe-plays-the-edit-test
  (let [on? #(true? (get-in % [:chains :effect-chains [0 3] :active?]))
        s0 (run (starter) {:event/type :vibe/press :role :hit :input-time 1000})
        s1 (run s0 {:event/type :vibe/toggle-fx :vibe :vibe-1 :slot :fx :cell [0 3]})]
    (is (= :vibe-1 (get-in s0 [:vibe-runtime :current])))
    (is (on? s1) "added to the FX set of the vibe that plays: on at once")
    (let [s2 (run s1 {:event/type :vibe/remove :vibe :vibe-1})]
      (is (not (on? s2)) "the vibe deleted: its look goes")
      (is (= :vibe-2 (get-in s2 [:vibe-runtime :current]))))
    (testing "an undo runs no handler: the cell stays on until the next switch"
      (let [s2 (run s1 {:event/type :edit/undo})]
        (is (= [] (get-in s2 [:config :vibes :library :vibe-1 :fx])))
        (is (on? s2))
        (is (not (on? (run s2 {:event/type :vibe/select :vibe :vibe-2}))))))))


;; Tap reactions (Phase 4, plans/2026-11-18-vibe-mode/04 §7)

(deftest add-reaction-uses-the-first-empty-effect-cell-test
  (let [s0 (history/mark-clean (starter))
        s1 (run s0 {:event/type :vibe/add-reaction :vibe :vibe-2 :kind :punch-size})
        cell (get-in s1 [:chains :effect-chains [1 3]])
        punch {:type :envelope :active? true :source :hit :min 1.0 :max 1.25
               :attack 0.0 :release 0.5 :curve :exp :hold? false}]
    (is (= "Lines · Punch size" (:name cell)) "[0 3] is taken: the next of the bottom row")
    (is (false? (:active? cell)) "vibe-2 isn't playing")
    (is (= [{:effect-id :scale :enabled? true :params {:x-scale punch :y-scale punch :uniform? false}}]
           (mapv #(dissoc % :id) (:items cell))))
    (is (uuid? (get-in cell [:items 0 :id])))
    (is (= [[1 3]] (get-in s1 [:config :vibes :library :vibe-2 :fx])))
    (testing "no such vibe or kind, or no free cell: nothing"
      (is (= (:chains s0) (:chains (run s0 {:event/type :vibe/add-reaction :vibe :gone :kind :punch-size}))))
      (is (= (:chains s0) (:chains (run s0 {:event/type :vibe/add-reaction :vibe :vibe-2 :kind :nope}))))
      (let [full (update-in s0 [:chains :effect-chains] merge (into {} (for [c (range 8) r (range 4)] [[c r] {:items []}])))]
        (is (= (:config full) (:config (run full {:event/type :vibe/add-reaction :vibe :vibe-2 :kind :flip}))))))))

(deftest add-reaction-to-the-current-vibe-switches-it-on-test
  (let [s0 (run (starter) {:event/type :vibe/select :vibe :vibe-1 :input-time 1000})
        s1 (run s0 {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :scatter})]
    (is (true? (get-in s1 [:chains :effect-chains [1 3] :active?])) "playing: on at once")
    (is (= [[1 3]] (get-in s1 [:vibe-runtime :fx-on])))
    (is (false? (get-in (run s1 {:event/type :vibe/select :vibe :vibe-2 :input-time 2000})
                        [:chains :effect-chains [1 3] :active?]))
        "leaving the vibe switches it off")
    (testing "the Strobe goes into the flash FX, off until FLASH is held"
      (let [s (run s0 {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :strobe})]
        (is (= [[1 3]] (get-in s [:config :vibes :library :vibe-1 :flash :fx])))
        (is (false? (get-in s [:chains :effect-chains [1 3] :active?])))
        (is (true? (get-in (run s {:event/type :vibe/press :role :flash :input-time 1500})
                           [:chains :effect-chains [1 3] :active?])))))
    (testing "during blackout it waits for blackout to end"
      (let [s (-> s0
                  (run {:event/type :vibe/press :role :blackout :input-time 1100})
                  (run {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :flip}))]
        (is (false? (get-in s [:chains :effect-chains [1 3] :active?])))
        (is (true? (get-in (run s {:event/type :vibe/press :role :blackout :input-time 1200})
                           [:chains :effect-chains [1 3] :active?])))))))

(deftest add-reaction-is-one-undo-step-test
  (let [s0 (history/mark-clean (starter))
        s1 (run s0 {:event/type :vibe/add-reaction :vibe :vibe-2 :kind :fade-in})]
    (is (= 1 (undo-count s1)))
    (is (= "Add Tap Reaction" (history/undo-label (:history s1))))
    (is (true? (get-in s1 [:project :dirty?])))
    (let [undone (run s1 {:event/type :history/undo})]
      (is (nil? (get-in undone [:chains :effect-chains [1 3]])))
      (is (= [] (get-in undone [:config :vibes :library :vibe-2 :fx]))))))

(deftest add-reaction-is-an-edit-like-the-others-test
  (testing "what plays follows it through engine/reconcile, as it follows every edit of the vibes: after an undo
            took the vibe that played away (an undo runs no handler), its look is left, the first vibe's entered,
            and the new reaction is on in it"
    (let [s0 (-> (starter)
                 (run {:event/type :vibe/add})
                 (run {:event/type :vibe/select :vibe :vibe-3 :input-time 1000})
                 (run {:event/type :edit/undo}))
          s1 (run s0 {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :punch-size})]
      (is (= :vibe-3 (get-in s0 [:vibe-runtime :current])))
      (is (nil? (get-in s0 [:config :vibes :library :vibe-3])))
      (is (= :vibe-1 (get-in s1 [:vibe-runtime :current])))
      (is (true? (get-in s1 [:chains :effect-chains [1 3] :active?])))
      (is (= [[1 3]] (get-in s1 [:vibe-runtime :fx-on])) "the FX set's, so leaving Shapes switches it off"))))


;; Zone reactions (vibe Phase 8b, plans/2026-11-19-zones/11 §12)

(def ^:private three-lasers
  "p1, p2 and p3, on and in All, left to right."
  {:p1 {:name "Laser 1" :host "10.0.0.1" :zone-groups [] :enabled? true}
   :p2 {:name "Laser 2" :host "10.0.0.2" :zone-groups [] :enabled? true}
   :p3 {:name "Laser 3" :host "10.0.0.3" :zone-groups [] :enabled? true}})

(defn- playing
  "starter on projectors, with Shapes playing (selected), saved."
  [projectors]
  (-> (starter)
      (assoc :projectors projectors)
      (run {:event/type :vibe/select :vibe :vibe-1 :input-time 1000})
      (history/mark-clean)))

(defn- new-cell
  "The effect cell a reaction made on starter takes: [0 3] is taken."
  [state]
  (get-in state [:chains :effect-chains [1 3]]))

(deftest add-zone-reaction-test
  (let [s0 (playing three-lasers)]
    (doseq [[kind cell-name slot on? zones step-on] [[:zone-hop "Shapes · Zone hop" [:fx] true [:lane/lasers] :hit]
                                                      [:zone-swap "Shapes · Zone swap" [:fx] true [:zone/a :zone/b] :accent]
                                                      [:zone-shift "Shapes · Zone shift" [:fx] true
                                                       [:zone/a :zone/b :zone/c :zone/d] :accent]
                                                      [:zone-chase "Shapes · Zone chase" [:flash :fx] false [:lane/lasers]
                                                       :clock]]]
      (testing (name kind)
        (let [s1 (run s0 {:event/type :vibe/add-reaction :vibe :vibe-1 :kind kind})
              cell (new-cell s1)
              fx (get-in cell [:items 0])]
          (is (= cell-name (:name cell)) "named after the vibe, in the first empty effect cell")
          (is (= [:zone-pattern] (mapv :effect-id (:items cell))))
          (is (uuid? (:id fx)))
          (is (= [zones step-on] [(get-in fx [:params :zones]) (get-in fx [:params :step-on])]))
          (is (= [[1 3]] (get-in s1 (into [:config :vibes :library :vibe-1] slot))) "its FX set, or its flash FX")
          (is (= on? (:active? cell)) (if on? "Shapes plays: on at once" "the chase waits for FLASH"))
          (is (= 1 (undo-count s1)) "one undo step")
          (is (= "Add Tap Reaction" (history/undo-label (:history s1))))
          (is (true? (get-in s1 [:project :dirty?])))
          (let [undone (run s1 {:event/type :history/undo})]
            (is (nil? (new-cell undone)) "undone: gone")
            (is (= [] (get-in undone (into [:config :vibes :library :vibe-1] slot)))))
          (testing "the show lock refuses it, as every vibe edit: only the notice changes"
            (let [locked (assoc-in s0 [:ui :show-lock?] true)
                  s (run locked {:event/type :vibe/add-reaction :vibe :vibe-1 :kind kind})]
              (is (= (:chains locked) (:chains s)))
              (is (= (:config locked) (:config s)))
              (is (= 0 (undo-count s)))
              (is (= :vibe/add-reaction (get-in s [:ui :show-lock-notice :event]))))))))
    (testing "the chase comes on while FLASH is held"
      (let [held (-> s0
                     (run {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :zone-chase})
                     (run {:event/type :vibe/press :role :flash :input-time 1500}))]
        (is (true? (:active? (new-cell held))))
        (is (false? (:active? (new-cell (run held {:event/type :vibe/release :role :flash :input-time 2500})))))))
    (testing "for a vibe that doesn't play: off until it does"
      (let [s1 (run s0 {:event/type :vibe/add-reaction :vibe :vibe-2 :kind :zone-swap})]
        (is (= "Lines · Zone swap" (:name (new-cell s1))))
        (is (false? (:active? (new-cell s1))))
        (is (true? (:active? (new-cell (run s1 {:event/type :vibe/select :vibe :vibe-2 :input-time 2000})))))))))

(deftest greyed-zone-reaction-changes-nothing-test
  (testing "§12.4: with no laser on and in All the hop and the chase have nowhere to go, so nothing is made"
    (let [s0 (playing {})]
      (doseq [kind [:zone-hop :zone-chase]]
        (let [s1 (run s0 {:event/type :vibe/add-reaction :vibe :vibe-1 :kind kind})]
          (is (= (:chains s0) (:chains s1)) (name kind))
          (is (= (:config s0) (:config s1)))
          (is (= 0 (undo-count s1)) "no undo step")
          (is (false? (get-in s1 [:project :dirty?])))))
      (testing "the swap and the shift take zones, which every rig has"
        (is (= "Shapes · Zone swap" (:name (new-cell (run s0 {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :zone-swap})))))))))

(deftest zone-reaction-that-can-reach-the-crowd-stays-off-test
  (testing "V10 A, as Z5 built it: a map of yours sends B to the crowd, so a Zone swap (A ⇄ B) can reach it"
    (let [s0 (-> (playing (assoc three-lasers :p5 {:name "Scanner" :host "10.0.0.5" :zone-groups [:crowd] :enabled? true
                                                   :tags #{:crowd-scanning} :exclude-from-all? true}))
                 (assoc-in [:zone-maps :map/sweep] {:name "Crowd sweep" :lanes [:auto/left :crowd] :overrides {}}))
          s1 (run s0 {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :zone-swap})
          re-entered #(-> %
                          (run {:event/type :vibe/select :vibe :vibe-2 :input-time 2000})
                          (run {:event/type :vibe/select :vibe :vibe-1 :input-time 3000}))]
      (is (= [[1 3]] (get-in s1 [:config :vibes :library :vibe-1 :fx])) "in the FX set")
      (is (false? (:active? (new-cell s1))) "but the vibe leaves it off")
      (is (some #(re-find #"^Shapes: D2 \(Zone pattern\) can send pictures to the crowd" (:text %)) (library/lint s1))
          "the Vibes tab's lint says why, where the reaction was made")
      (is (= {:why :audience-fx :cell [1 3]} (get-in (re-entered s1) [:vibe-runtime :problem]))
          "and Perform's status line, when the vibe switches its FX set on")
      (is (false? (:active? (new-cell (re-entered s1)))))
      (is (true? (:active? (new-cell (run s1 {:event/type :vibe/set-option :vibe :vibe-1 :key :allow-audience? :value true}))))
          "Allow audience cells: on at once")
      (testing "the hop's Every laser can't reach the scanner, which is out of All: on"
        (let [s (run s0 {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :zone-hop})]
          (is (= [:lane/lasers] (get-in (new-cell s) [:items 0 :params :zones])))
          (is (true? (:active? (new-cell s))))
          (is (nil? (get-in (re-entered s) [:vibe-runtime :problem])))))))
  (testing "a scanner left in All: the hop leaves it out of its places and comes on; the swap's zones reach it (Unison
            sends every zone to All), so the swap stays off"
    (let [s0 (playing (update three-lasers :p2 assoc :tags #{:crowd-scanning}))
          hop (run s0 {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :zone-hop})
          swap (run s0 {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :zone-swap})]
      (is (= [:auto.laser/p1 :auto.laser/p3] (get-in (new-cell hop) [:items 0 :params :zones])))
      (is (true? (:active? (new-cell hop))))
      (is (false? (:active? (new-cell swap))))
      (is (some #(re-find #"^Shapes: D2 \(Zone pattern\) can send pictures to the crowd" (:text %)) (library/lint swap))))))


;; The faders (Phase 5, plans/2026-11-18-vibe-mode/05)

(defn- fader [slot lo hi default]
  {:type :macro :active? true :slot slot :min lo :max hi :default default})

(defn- full-grid [state]
  (update-in state [:chains :effect-chains] merge (into {} (for [c (range 8) r (range 4)] [[c r] {:items []}]))))

(deftest add-master-uses-the-first-empty-effect-cell-test
  (let [s0 (history/mark-clean (starter))
        s1 (run s0 {:event/type :vibe/add-master})
        cell (get-in s1 [:chains :effect-chains [1 3]])]
    (is (= "Master" (:name cell)) "[0 3] is taken: the next of the bottom row, as a tap reaction's")
    (is (true? (:active? cell)) "on at once: full until fader 1 moves")
    (is (= [{:effect-id :intensity :enabled? true :params {:amount (fader :slider-1 0.0 1.0 1.0)}}]
           (mapv #(dissoc % :id) (:items cell))))
    (is (uuid? (get-in cell [:items 0 :id])))
    (is (= (:config s0) (:config s1)) "in no vibe: the Master is every vibe's")
    (is (= 1 (undo-count s1)))
    (is (= "Add Master Intensity" (history/undo-label (:history s1))))
    (is (true? (get-in s1 [:project :dirty?])))
    (testing "one Master: again, nothing"
      (is (= (:chains s1) (:chains (run s1 {:event/type :vibe/add-master})))))
    (testing "no free cell: nothing"
      (let [full (full-grid s0)]
        (is (= (:chains full) (:chains (run full {:event/type :vibe/add-master}))))))
    (testing "undone: gone"
      (is (nil? (get-in (run s1 {:event/type :history/undo}) [:chains :effect-chains [1 3]]))))))

(deftest add-morph-puts-the-cell-in-the-fx-set-test
  (let [s0 (history/mark-clean (starter))
        s1 (run s0 {:event/type :vibe/add-morph :vibe :vibe-2 :kind :size})
        cell (get-in s1 [:chains :effect-chains [1 3]])
        size (fader :slider-2 0.5 1.5 0.5)]
    (is (= "Lines · Morph: Size" (:name cell)))
    (is (false? (:active? cell)) "Lines isn't playing: its FX set switches it on when it does")
    (is (= [{:effect-id :scale :enabled? true :params {:x-scale size :y-scale size :uniform? false}}]
           (mapv #(dissoc % :id) (:items cell))))
    (is (= [[1 3]] (get-in s1 [:config :vibes :library :vibe-2 :fx])))
    (is (= [] (get-in s1 [:config :vibes :library :vibe-1 :fx])))
    (is (= 1 (undo-count s1)))
    (is (= "Add Morph" (history/undo-label (:history s1))))
    (testing "Spin and Colour"
      (let [s (-> s1
                  (run {:event/type :vibe/add-morph :vibe :vibe-2 :kind :spin})
                  (run {:event/type :vibe/add-morph :vibe :vibe-2 :kind :colour}))]
        (is (= [[1 3] [2 3] [3 3]] (get-in s [:config :vibes :library :vibe-2 :fx])))
        (is (= [:rotation {:angle (fader :slider-2 0.0 360.0 0.0)}]
               ((juxt :effect-id :params) (get-in s [:chains :effect-chains [2 3] :items 0]))))
        (is (= [:hue-shift {:degrees (fader :slider-2 -180.0 180.0 0.5)}]
               ((juxt :effect-id :params) (get-in s [:chains :effect-chains [3 3] :items 0]))))))
    (testing "the vibe that plays: on at once (engine/reconcile), off when it is left"
      (let [playing (run s0 {:event/type :vibe/select :vibe :vibe-1 :input-time 1000})
            s (run playing {:event/type :vibe/add-morph :vibe :vibe-1 :kind :spin})]
        (is (true? (get-in s [:chains :effect-chains [1 3] :active?])))
        (is (= [[1 3]] (get-in s [:vibe-runtime :fx-on])))
        (is (false? (get-in (run s {:event/type :vibe/select :vibe :vibe-2 :input-time 2000})
                            [:chains :effect-chains [1 3] :active?])))
        (testing "during blackout it waits for blackout to end"
          (let [dark (-> playing
                         (run {:event/type :vibe/press :role :blackout :input-time 1100})
                         (run {:event/type :vibe/add-morph :vibe :vibe-1 :kind :size}))]
            (is (false? (get-in dark [:chains :effect-chains [1 3] :active?])))
            (is (true? (get-in (run dark {:event/type :vibe/press :role :blackout :input-time 1200})
                               [:chains :effect-chains [1 3] :active?])))))))
    (testing "no such vibe or kind, or no free cell: nothing"
      (is (= (:chains s0) (:chains (run s0 {:event/type :vibe/add-morph :vibe :gone :kind :size}))))
      (is (= (:chains s0) (:chains (run s0 {:event/type :vibe/add-morph :vibe :vibe-1 :kind :nope}))))
      (let [full (full-grid s0)]
        (is (= (:config full) (:config (run full {:event/type :vibe/add-morph :vibe :vibe-1 :kind :size}))))))
    (testing "undone: the cell and its place in the FX set go"
      (let [undone (run s1 {:event/type :history/undo})]
        (is (nil? (get-in undone [:chains :effect-chains [1 3]])))
        (is (= [] (get-in undone [:config :vibes :library :vibe-2 :fx])))))))

(deftest clear-control-clears-a-fader-test
  (let [hit-key {:source :midi :channel 0 :note 36}
        s0 (-> (starter)
               (assoc-in [:config :input :macro-map] {[:midi 0 1] :slider-1 [:osc "/fader/2"] :slider-2})
               (assoc-in [:config :input :trigger-map] {hit-key {:type :vibe :role :hit}})
               (history/mark-clean))
        s1 (run s0 {:event/type :vibe/clear-control :slot :slider-1})]
    (is (= {[:osc "/fader/2"] :slider-2} (get-in s1 [:config :input :macro-map])))
    (is (= {hit-key {:type :vibe :role :hit}} (get-in s1 [:config :input :trigger-map])) "the roles keep their keys")
    (is (= 1 (undo-count s1)))
    (is (= "Clear Control" (history/undo-label (:history s1))))
    (is (= {hit-key {:type :vibe :role :hit}}
           (get-in (run s0 {:event/type :vibe/clear-control :slot :slider-2}) [:config :input :trigger-map])))
    (is (= {} (get-in (run s0 {:event/type :vibe/clear-control :role :hit}) [:config :input :trigger-map]))
        "a role's Clear as before")
    (testing "nothing learned for it: nothing, and no undo step"
      (let [s2 (run s1 {:event/type :vibe/clear-control :slot :slider-1})]
        (is (= (:config s1) (:config s2)))
        (is (= (undo-count s1) (undo-count s2)))))
    (testing "a project from before the faders (no :macro-map): nothing"
      (let [old (update-in s0 [:config :input] dissoc :macro-map)]
        (is (= (:config old) (:config (run old {:event/type :vibe/clear-control :slot :slider-1}))))))))

(deftest starter-project-reactions-find-a-free-cell-test
  (let [fresh (persistent/new-project-state (domains/build-initial-state))
        master (get-in fresh [:chains :effect-chains [0 3]])
        s1 (run fresh {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :punch-size})]
    (is (= "Master" (:name master)) "a new project's Master, at [0 3]")
    (is (= "Shapes · Punch size" (get-in s1 [:chains :effect-chains [1 3] :name])) "a tap reaction: the next cell")
    (is (= master (get-in s1 [:chains :effect-chains [0 3]])) "the Master as it was")
    (testing "a Morph next, and then the reactions fill the grid from the bottom row up, around the Master"
      (let [s2 (run s1 {:event/type :vibe/add-morph :vibe :vibe-1 :kind :size})
            s (reduce run s2 (repeat 29 {:event/type :vibe/add-reaction :vibe :vibe-2 :kind :flip}))]
        (is (= "Shapes · Morph: Size" (get-in s2 [:chains :effect-chains [2 3] :name])))
        (is (= 32 (count (get-in s [:chains :effect-chains]))) "the grid is full")
        (is (= master (get-in s [:chains :effect-chains [0 3]])))
        (is (= (:chains s) (:chains (run s {:event/type :vibe/add-reaction :vibe :vibe-1 :kind :flip}))) "full: nothing")))))


;; Safety (plans/2026-11-18-vibe-mode/07)

(deftest clear-control-lets-go-of-a-held-role-test
  (testing "the key that holds HIT is cleared: its release would reach nothing, so HIT lets go now (04 §11)"
    (let [hit {:source :midi :channel 0 :note 36}
          accent {:source :midi :channel 0 :note 37}
          s0 (-> (starter)
                 (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
                 (assoc-in [:config :input :trigger-map] {hit {:type :vibe :role :hit} accent {:type :vibe :role :accent}}))
          held (-> s0
                   (run {:event/type :vibe/press :role :hit :input-time 1000})
                   (run {:event/type :vibe/press :role :accent :input-time 1000}))
          cell (rt held :voices :hit :cell)
          s (run held {:event/type :vibe/clear-control :role :hit :time 1500})]
      (is (nil? (rt s :held :hit)))
      (is (false? (get-in s [:playback :pulses :hit :held?])) "and its pulse")
      (is (nil? (get-in s [:playback :active-cues cell])) "cut, as release-all lets go")
      (is (= {:at 1000 :src :input} (rt s :held :accent)) "ACCENT keeps its key and its hold")
      (testing "a pad's hold of HIT stays: the pad's release still comes"
        (let [pad (run s0 {:event/type :vibe/press :role :hit :src :pad :time 1000})]
          (is (= :pad (rt (run pad {:event/type :vibe/clear-control :role :hit}) :held :hit :src))))))))

(deftest unticking-allow-audience-stops-what-plays-test
  (testing "Allow audience cells unticked: an audience cue stops at once, whichever role plays it (03 §10)"
    (let [crowd {:items [{:type :preset :preset-id :circle}] :target :crowd}
          s0 (-> (starter)
                 (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
                 (assoc-in [:chains :cue-chains [0 1]] crowd)
                 (assoc-in [:chains :cue-chains [1 1]] crowd)
                 (update-in [:config :vibes :library :vibe-1] merge
                            {:allow-audience? true
                             :hit {:cells [[0 1]] :select :shuffle :mode :sustain :beats 1.0}
                             :accent {:cells [[4 0]] :select :shuffle :mode :sustain :beats 2.0}
                             :flash {:cue [1 1] :fx [[0 3]] :solo? false}}))
          playing (-> s0
                      (run {:event/type :vibe/press :role :hit :input-time 1000})
                      (run {:event/type :vibe/release :role :hit :input-time 1050})
                      (run {:event/type :vibe/press :role :accent :input-time 1100})
                      (run {:event/type :vibe/release :role :accent :input-time 1150})
                      (run {:event/type :vibe/press :role :flash :input-time 1200}))
          active #(set (keys (get-in % [:playback :active-cues])))]
      (is (= #{[0 1] [4 0] [1 1]} (active playing)) "HIT and the FLASH cue reach the crowd, ACCENT doesn't")
      (let [s (run playing {:event/type :vibe/set-option :vibe :vibe-1 :key :allow-audience? :value false :time 1300})]
        (is (= #{[4 0]} (active s)) "the two audience cues stop; ACCENT plays on")
        (is (nil? (rt s :voices :hit)))
        (is (true? (get-in s [:chains :effect-chains [0 3] :active?])) "the flash's effect cell stays on while FLASH is held")
        (is (= :input (rt s :held :flash :src)))
        (let [let-go (run s {:event/type :vibe/release :role :flash :input-time 1400})]
          (is (false? (get-in let-go [:chains :effect-chains [0 3] :active?])) "and goes off with FLASH")
          (is (= #{[4 0]} (active let-go))))
        (testing "ticked again: nothing starts by itself, the next press may pick them"
          (is (= #{[4 0]} (active (run s {:event/type :vibe/set-option :vibe :vibe-1 :key :allow-audience? :value true})))))))))
