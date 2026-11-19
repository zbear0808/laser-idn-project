(ns laser-show.events.handlers.vibes-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.history :as history-handlers]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.templates :as templates])
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
      (is (= 1 (count (get-in s1 [:timing :tap-times]))))
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
