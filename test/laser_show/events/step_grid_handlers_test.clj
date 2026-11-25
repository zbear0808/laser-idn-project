(ns laser-show.events.step-grid-handlers-test
  "The step grid's events (zones plan Z6, 08 §6): each edit reads and writes
   the zone pattern the window shows, found by its id where it is now in its
   cell (reactivity plan 05), a cue's or an effects cell's alike, as one
   undo step \"Edit Step Grid\" (a drag too), and does nothing while the
   pattern's keyframes are on (08 invariant 9), once the window shows
   another, or once the pattern is gone. A drag is one press, by its id: its
   step ends with it, with its window's close, or with any other undoable
   edit, and nothing of an ended press reaches a later one (reactivity plan
   16). Every event runs through the real pipeline, as the agent runs it."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is testing]]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.modulation :as mod]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.step-grid :as sg]
            [laser-show.routing.zone-patterns :as zp]
            [laser-show.state.core :as state-core]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.templates :as templates]
            [laser-show.subs :as subs]
            [laser-show.views.components.step-grid :as grid-view]))

(defn- fresh
  "A new show, its starter cues and effects, saved, at 120 BPM."
  []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-effects)
      (assoc-in [:timing :bpm] 120.0)
      (history/mark-clean)))

(defn- handle
  "The pipeline's effects for event on state, at its :time (1000 unless it
   says)."
  [state event]
  (events/handle-event-pipeline (assoc event :state state :time (or (:time event) 1000))))

(defn- run
  "state after event through the pipeline, and what it dispatches."
  [state event]
  (let [effects (handle state event)
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run s next-event)
      s)))

(def ^:private A :zone/a)
(def ^:private B :zone/b)
(def ^:private C :zone/c)

(def ^:private cue-grid-id #uuid "00000000-0000-0000-0000-0000000006a1")
(def ^:private cell-grid-id #uuid "00000000-0000-0000-0000-0000000006b2")

(def ^:private cue-target {:domain :cue-zone-patterns :entity-key [0 0] :effect-instance-id cue-grid-id})
(def ^:private cell-target {:domain :effect-chains :entity-key [2 1] :effect-instance-id cell-grid-id})

(defn- grid-pattern
  "A step grid over A, B, C with steps, as an instance."
  [steps & {:as more}]
  (-> (zp/preset-instance :steps :cue)
      (assoc :id cue-grid-id)
      (update :params merge {:zones [A B C] :steps steps} more)))

(defn- with-grids
  "fresh, with a grid on the cue at A1 and one in the effects cell B3 ([2 1]),
   saved."
  ([] (with-grids [[] [] [] []]))
  ([steps]
   (-> (fresh)
       (assoc-in [:chains :cue-chains [0 0] :zone-patterns] [(grid-pattern steps)])
       (assoc-in [:chains :effect-chains [2 1]] {:active? true :items [(assoc (grid-pattern steps) :id cell-grid-id)]})
       (history/mark-clean))))

(def ^:private session
  "The window's session (:editor-id) open opens it in."
  #uuid "00000000-0000-0000-0000-0000000016f0")

(defn- open
  "state with the step grid window open on target, as its openers ask, in
   session (or the one given)."
  ([state target] (open state target session))
  ([state target editor-id]
   (run state {:event/type :step-grid/open :target target :editor-id editor-id})))

;; Presses (reactivity plan 16): each has its own id, which its start, its
;; drags and its end carry, as the grid body sends them

(def ^:private g1 #uuid "00000000-0000-0000-0000-0000000016f1")
(def ^:private g2 #uuid "00000000-0000-0000-0000-0000000016f2")
(def ^:private g3 #uuid "00000000-0000-0000-0000-0000000016f3")

(defn- stroke
  "Press id's event of type on target's grid in session (or :editor-id
   given): :step-grid/paint-start or :paint on [row step], :paint-end with
   no cell."
  ([type target id] {:event/type type :editor-id session :target target :history/gesture-id id})
  ([type target id [row step]] (assoc (stroke type target id) :row row :step step)))

(defn- press [state target id cell] (run state (stroke :step-grid/paint-start target id cell)))
(defn- drag-to [state target id cell] (run state (stroke :step-grid/paint target id cell)))
(defn- release [state target id] (run state (stroke :step-grid/paint-end target id)))

(defn- owner-items
  "The patterns of target's cell: a cue's :zone-patterns, a cell's :items."
  [state {:keys [domain entity-key]}]
  (get-in state (if (= :cue-zone-patterns domain)
                  [:chains :cue-chains entity-key :zone-patterns]
                  [:chains :effect-chains entity-key :items])))

(defn- instance-at
  "target's instance, by its id, wherever it is in its cell."
  [state {:keys [effect-instance-id] :as target}]
  (let [items (vec (owner-items state target))]
    (some->> (chains/find-path-by-id items effect-instance-id) (chains/get-item-at-path items))))

(defn- params-at [state target] (:params (instance-at state target)))

(defn- steps-at [state target] (:steps (params-at state target)))

(defn- undo-depth [state] (count (get-in state [:history :undo-stack])))

(defn- undo-label [state] (history/undo-label (:history state)))


;; One click, one key: one undo step (08 invariant 1)

(deftest toggle-is-one-undo-step-test
  (let [s0 (open (with-grids) cue-target)
        s (run s0 {:event/type :step-grid/toggle :target cue-target :row A :step 1})]
    (is (= [[] [A] [] []] (steps-at s cue-target)))
    (is (= 1 (undo-depth s)))
    (is (= "Edit Step Grid" (undo-label s)))
    (is (true? (get-in s [:project :dirty?])) "unsaved")
    (is (= [[] [] [] []] (steps-at (run s {:event/type :edit/undo}) cue-target)) "undo puts it back")
    (testing "a row by its index, as the cursor names it"
      (is (= [[] [] [C] []] (steps-at (run s0 {:event/type :step-grid/toggle :target cue-target :row 2 :step 2}) cue-target))))
    (testing "Space or Enter: the cell under the cursor"
      (let [s (-> s0
                  (run {:event/type :step-grid/cursor :target cue-target :row 1 :step 3})
                  (run {:event/type :step-grid/toggle :target cue-target}))]
        (is (= [[] [] [] [B]] (steps-at s cue-target)))
        (is (= 1 (undo-depth s)) "opening a grid and moving the cursor are no undo steps")))
    (testing "outside the grid: nothing, and no undo step"
      (let [s (run s0 {:event/type :step-grid/toggle :target cue-target :row A :step 9})]
        (is (= (steps-at s0 cue-target) (steps-at s cue-target)))
        (is (= 0 (undo-depth s)))))))

(deftest drag-paint-is-one-undo-step-test
  (let [s0 (open (with-grids (vec (repeat 8 []))) cue-target)
        pressed (press s0 cue-target g1 [0 1])
        dragged (-> pressed
                    (drag-to cue-target g1 [0 4])
                    (drag-to cue-target g1 [0 5]))]
    (testing "pressing an unlit cell lights it, and every cell the drag crosses (Z6-Q3 A)"
      (is (= [[] [A] [A] [A] [A] [A] [] []] (steps-at dragged cue-target)) "steps 1 to 5 of row A, the jump from 1 to 4 too")
      (is (= g1 (history/gesture-id (:history dragged))) "one transaction, the press's, while the button is down"))
    (testing "the press's cell again, or its start again: no effect at all (a queued duplicate)"
      (is (= {} (handle dragged (stroke :step-grid/paint cue-target g1 [0 5]))))
      (is (= {} (handle pressed (stroke :step-grid/paint-start cue-target g1 [0 1])))))
    (let [released (release dragged cue-target g1)]
      (is (nil? (get-in released [:history :transaction])))
      (is (= 1 (undo-depth released)) "one drag, one undo step")
      (is (= "Edit Step Grid" (undo-label released)))
      (is (true? (get-in released [:project :dirty?])))
      (is (= (vec (repeat 8 [])) (steps-at (run released {:event/type :edit/undo}) cue-target)) "undo reverts the whole drag")
      (is (= released (release released cue-target g1)) "a second end does nothing")
      (testing "pressing a lit cell clears it, and every cell crossed"
        (let [cleared (-> released
                          (press cue-target g2 [0 2])
                          (drag-to cue-target g2 [0 4])
                          (release cue-target g2))]
          (is (= [[] [A] [] [] [] [A] [] []] (steps-at cleared cue-target)))
          (is (= 2 (undo-depth cleared))))))
    (testing "a press outside the grid paints nothing (the body sends none: it only presses a cell)"
      (is (= {} (handle s0 (stroke :step-grid/paint-start cue-target g1 [0 8]))) "past step 8")
      (is (= {} (handle s0 (stroke :step-grid/paint-start cue-target g1 [3 1]))) "below the rows"))
    (testing "a drag or an end with no press before it does nothing"
      (is (= {} (handle s0 (stroke :step-grid/paint cue-target g1 [0 2]))))
      (is (= {} (handle s0 (stroke :step-grid/paint-end cue-target g1)))))
    (testing "nor a press without its id, or from another session of the window"
      (is (= {} (handle s0 (dissoc (stroke :step-grid/paint-start cue-target g1 [0 1]) :history/gesture-id))))
      (is (= {} (handle s0 (assoc (stroke :step-grid/paint-start cue-target g1 [0 1]) :editor-id (random-uuid))))))))


;; A cue's pattern and a cell's alike

(deftest edits-reach-a-cue-pattern-and-a-cell-pattern-test
  (doseq [target [cue-target cell-target]]
    (testing (str (:domain target))
      (let [s0 (open (with-grids [[A] [] [B] []]) target)
            other (if (= target cue-target) cell-target cue-target)
            step (fn [event] (run s0 (assoc event :target target)))]
        (is (= [[A] [A] [B] []] (steps-at (step {:event/type :step-grid/toggle :row A :step 1}) target)))
        (is (= [[A] [] [B] [] [] [] [] []] (steps-at (step {:event/type :step-grid/set-length :n 8}) target)))
        (is (= [A B C :zone/d] (:zones (params-at (step {:event/type :step-grid/add-row}) target))) "+ Row: the next zone letter")
        (is (= [[] [] [] []] (steps-at (step {:event/type :step-grid/clear}) target)))
        (is (= [[] [A] [] [B]] (steps-at (step {:event/type :step-grid/rotate :by 1}) target)))
        (is (= (steps-at s0 other) (steps-at (step {:event/type :step-grid/clear}) other)) "the other grid stays")))))

(deftest row-and-step-menus-test
  (let [s0 (open (with-grids [[A B] [B] [A C] []]) cue-target)
        edit #(run s0 (assoc % :target cue-target))]
    (is (= [A B C :graphics] (:zones (params-at (edit {:event/type :step-grid/add-row :ref :graphics}) cue-target))))
    (is (= [A B C] (:zones (params-at (edit {:event/type :step-grid/add-row :ref A}) cue-target))) "a row twice: refused")
    (is (= [[B] [B] [C] []] (steps-at (edit {:event/type :step-grid/remove-row :row A}) cue-target)))
    (is (= [[:zone/d B] [B] [:zone/d C] []] (steps-at (edit {:event/type :step-grid/set-row :row 0 :ref :zone/d}) cue-target)) "Change ▸")
    (is (= [B A C] (:zones (params-at (edit {:event/type :step-grid/move-row :row A :delta 1}) cue-target))) "Move down")
    (is (= [[A B] [A B] [A C] [A]] (steps-at (edit {:event/type :step-grid/fill-row :row A :every 1}) cue-target)) "Fill every step")
    (is (= [[A B] [B] [A C] []] (steps-at (edit {:event/type :step-grid/fill-row :row A :every 2}) cue-target)) "Every 2nd")
    (is (= [[B] [B] [C] []] (steps-at (edit {:event/type :step-grid/clear-row :row A}) cue-target)) "Clear row")
    (is (= [[A B] [] [A C] []] (steps-at (edit {:event/type :step-grid/clear-step :step 1}) cue-target)) "Clear step")
    (is (= [[A B] [A B C] [A C] []] (steps-at (edit {:event/type :step-grid/fill-step :step 1}) cue-target)) "Light every row")
    (doseq [s [(edit {:event/type :step-grid/remove-row :row A}) (edit {:event/type :step-grid/fill-step :step 1})]]
      (is (= "Edit Step Grid" (undo-label s))))))


;; Keyframes (08 invariant 9)

(deftest keyframed-pattern-is-not-edited-test
  (let [s0 (-> (with-grids [[A] [] [B] []])
               (assoc-in [:chains :cue-chains [0 0] :zone-patterns 0 :keyframe-modulator]
                         {:enabled? true :period 4.0 :keyframes [{:position 0.0 :params {}} {:position 0.5 :params {}}]})
               (history/mark-clean)
               (open cue-target))]
    (doseq [event [{:event/type :step-grid/toggle :row A :step 1}
                   {:event/type :step-grid/set-length :n 16}
                   {:event/type :step-grid/add-row}
                   {:event/type :step-grid/start-from :style :chase}
                   {:event/type :step-grid/clear}
                   {:event/type :step-grid/rotate :by 1}]]
      (let [s (run s0 (assoc event :target cue-target))]
        (is (= (params-at s0 cue-target) (params-at s cue-target)) (str (:event/type event) ": nothing"))
        (is (= 0 (undo-depth s)))))
    (testing "nor by a drag"
      (let [s (-> s0 (press cue-target g1 [0 1]) (release cue-target g1))]
        (is (= (params-at s0 cue-target) (params-at s cue-target)))
        (is (nil? (get-in s [:history :transaction])))))
    (testing "and opening it makes no grid of a keyframed pattern"
      (let [chase-id #uuid "00000000-0000-0000-0000-0000000006c3"
            target (assoc cue-target :effect-instance-id chase-id)
            s1 (-> (fresh)
                   (assoc-in [:chains :cue-chains [0 0] :zone-patterns]
                             [(assoc (zp/preset-instance :chase :cue) :id chase-id
                                     :keyframe-modulator {:enabled? true :keyframes []})]))
            s (run s1 {:event/type :step-grid/open :target target})]
        (is (= :chase (:style (params-at s target))))
        (is (true? (get-in s [:ui :dialogs :step-grid-editor :open?])) "the window opens all the same")))))


;; Opening the window

(deftest open-makes-a-grid-from-the-pattern-test
  (let [alt-id #uuid "00000000-0000-0000-0000-0000000006d4"
        alt-target (assoc cue-target :effect-instance-id alt-id)
        s0 (-> (fresh)
               (assoc-in [:chains :cue-chains [0 0] :zone-patterns]
                         [(-> (zp/preset-instance :alternate :cue)
                              (assoc :id alt-id)
                              (update :params merge {:zones [A B C :zone/d] :rate 1.5}))])
               (history/mark-clean))
        s (run s0 {:event/type :step-grid/open :target alt-target})
        p (params-at s alt-target)]
    (is (= {:open? true :target alt-target :cursor [0 0]} (select-keys (get-in s [:ui :dialogs :step-grid-editor]) [:open? :target :cursor])))
    (is (= :steps (:style p)) "it becomes a grid")
    (is (= [[A C] [B :zone/d] [A C] [B :zone/d] [A C] [B :zone/d] [A C] [B :zone/d]] (:steps p))
        "of its own style's first 8 steps, so it plays the same for them")
    (is (= [1.5 :cue] [(:rate p) (:clock p)]) "at its own rate, on its own clock")
    (is (false? (:follow-pads? p)) "a grid doesn't follow the pads (Z6-Q2 A)")
    (is (= 1 (undo-depth s)))
    (is (= "Edit Step Grid" (undo-label s)))
    (testing "opening a grid again changes nothing"
      (let [again (run (run s {:event/type :ui/close-dialog :dialog-id :step-grid-editor}) {:event/type :step-grid/open :target alt-target})]
        (is (= p (params-at again alt-target)))
        (is (= 1 (undo-depth again)))))
    (testing "an Open for a pattern that has gone opens nothing, and leaves the one in its place alone"
      (let [q (assoc (zp/preset-instance :chase :cue) :id #uuid "00000000-0000-0000-0000-0000000006d5")
            s1 (-> s0 (assoc-in [:chains :cue-chains [0 0] :zone-patterns] [q]) (history/mark-clean))
            s (run s1 {:event/type :step-grid/open :target alt-target})]
        (is (= [q] (get-in s [:chains :cue-chains [0 0] :zone-patterns])) "Q is made no grid")
        (is (not (get-in s [:ui :dialogs :step-grid-editor :open?])))
        (is (= 0 (undo-depth s)))))
    (testing "a cue with no pattern gets a new grid when its chip asks for its first: 8 steps at 2 a beat over A and B (Z6-Q1 A)"
      (let [empty-cue (-> (fresh) (assoc-in [:chains :cue-chains [1 0] :zone-patterns] []) (history/mark-clean))
            first-grid {:event/type :step-grid/open :target {:domain :cue-zone-patterns :entity-key [1 0]} :create-first? true}
            s (run empty-cue first-grid)
            [fx :as fxs] (get-in s [:chains :cue-chains [1 0] :zone-patterns])
            target {:domain :cue-zone-patterns :entity-key [1 0] :effect-instance-id (:id fx)}]
        (is (= 1 (count fxs)))
        (is (= (:params (zp/preset-instance :steps :cue)) (:params fx)))
        (is (uuid? (:id fx)))
        (is (= {:open? true :target target :cursor [0 0]}
               (select-keys (get-in s [:ui :dialogs :step-grid-editor]) [:open? :target :cursor]))
            "the window names the pattern put in")
        (is (= 1 (undo-depth s)) "one undo step")
        (is (= "Edit Step Grid" (undo-label s)))
        (let [undone (run s {:event/type :edit/undo})
              redone (run undone {:event/type :edit/redo})]
          (is (= [] (get-in undone [:chains :cue-chains [1 0] :zone-patterns])) "undo takes it away")
          (is (nil? (sg/locate undone target)) "and the window's pattern is gone")
          (is (false? (get-in undone [:project :dirty?])))
          (is (= fx (:instance (sg/locate redone target))) "redo puts it back, and the window finds it again"))
        (testing "only once: a request that comes after the cue has a pattern does nothing"
          (let [q (assoc (zp/preset-instance :chase :cue) :id #uuid "00000000-0000-0000-0000-0000000006d6")
                with-q (-> empty-cue (assoc-in [:chains :cue-chains [1 0] :zone-patterns] [q]) (history/mark-clean))]
            (doseq [[why state] [["its own grid" s] ["another pattern, Q" with-q]]]
              (let [again (run state first-grid)]
                (is (= (:chains state) (:chains again)) why)
                (is (= (get-in state [:ui :dialogs :step-grid-editor]) (get-in again [:ui :dialogs :step-grid-editor])) why)
                (is (= (undo-depth state) (undo-depth again)) why)))))
        (testing "nor for a cell with no cue, while the show lock is on, with an id, or without :create-first?"
          (is (nil? (get-in empty-cue [:chains :cue-chains [7 7]])) "H8 has no cue")
          (doseq [[why state event] [["no cue" empty-cue (assoc-in first-grid [:target :entity-key] [7 7])]
                                     ["locked" (assoc-in empty-cue [:ui :show-lock?] true) first-grid]
                                     ["an id asked for too" empty-cue (assoc-in first-grid [:target :effect-instance-id] (random-uuid))]
                                     ["an ordinary Open of the cue's place" empty-cue (dissoc first-grid :create-first?)]]]
            (let [s (run state event)]
              (is (= (:chains state) (:chains s)) why)
              (is (not (get-in s [:ui :dialogs :step-grid-editor :open?])) why)
              (is (= 0 (undo-depth s)) why))))))
    (testing "Every laser becomes zones, and the window says which (S-4)"
      (let [chase-id #uuid "00000000-0000-0000-0000-0000000006e5"
            target (assoc cue-target :effect-instance-id chase-id)
            s1 (-> (fresh)
                   (assoc :projectors {:p1 {:name "L1" :host "10.0.0.1" :enabled? true :zone-groups []}
                                       :p2 {:name "L2" :host "10.0.0.2" :enabled? true :zone-groups []}
                                       :p3 {:name "L3" :host "10.0.0.3" :enabled? true :zone-groups []}})
                   (assoc-in [:chains :cue-chains [0 0] :zone-patterns] [(assoc (zp/preset-instance :chase :cue) :id chase-id)]))
            s (run s1 {:event/type :step-grid/open :target target})]
        (is (= [A B C] (:zones (params-at s target))))
        (is (= [[A] [B] [C] [A] [B] [C] [A] [B]] (steps-at s target)))
        (is (= [A B C] (get-in s [:ui :dialogs :step-grid-editor :every-laser])))))
    (testing "a target that names no zone pattern opens nothing"
      (let [s1 (fresh)
            master-id (:id (get-in s1 [:chains :effect-chains [0 3] :items 0]))
            cycle-id #uuid "00000000-0000-0000-0000-0000000006e6"
            s1 (assoc-in s1 [:chains :effect-chains [5 2]] {:active? false :items [(assoc (zp/cycle-instance) :id cycle-id)]})]
        (is (uuid? master-id))
        (doseq [[why target] [["the Master is a point effect" {:domain :effect-chains :entity-key [0 3] :effect-instance-id master-id}]
                              ["a Map cycle isn't a zone pattern" {:domain :effect-chains :entity-key [5 2] :effect-instance-id cycle-id}]
                              ["a place, not an id" {:domain :effect-chains :entity-key [5 2] :effect-path [0]}]]]
          (let [s (run s1 {:event/type :step-grid/open :target target})]
            (is (not (get-in s [:ui :dialogs :step-grid-editor :open?])) why)
            (is (= (:chains s1) (:chains s)) why)))))))


;; Starting points, copy and paste

(deftest start-from-is-one-undo-step-test
  (let [grids #(open (with-grids) cue-target)
        s0 (run (grids) {:event/type :step-grid/set-length :target cue-target :n 16})
        s (run s0 {:event/type :step-grid/start-from :target cue-target :style :chase})]
    (is (= (vec (take 16 (cycle [[A] [B] [C]]))) (steps-at s cue-target)) "the grid's length of Chase over its rows")
    (is (= (inc (undo-depth s0)) (undo-depth s)))
    (is (= "Edit Step Grid" (undo-label s)))
    (is (= (steps-at s0 cue-target) (steps-at (run s {:event/type :edit/undo}) cue-target)))
    (is (= [[B] [A C] [B] [A C]] (steps-at (run (grids) {:event/type :step-grid/start-from :target cue-target :style :center-out}) cue-target)))
    (is (= (vec (repeat 4 [A B C])) (steps-at (run (grids) {:event/type :step-grid/start-from :target cue-target :style :all-on}) cue-target)))
    (is (= [[] [] [] []] (steps-at (run (grids) {:event/type :step-grid/start-from :target cue-target :style :swap}) cue-target))
        "not a starting point: nothing")))

(deftest paste-from-another-grid-test
  (let [s0 (-> (with-grids)
               (assoc-in [:chains :effect-chains [2 1] :items 0 :params] (:params (grid-pattern [[C] [A C] [] [B] [A]] :zones [C A :graphics])))
               (history/mark-clean)
               (open cell-target))
        copy-effects (handle s0 {:event/type :step-grid/copy :target cell-target})
        copied (run s0 {:event/type :step-grid/copy :target cell-target})]
    (is (= {:type :step-grid :grid {:zones [C A :graphics] :steps [[C] [C A] [] [] [A]]} :copied-at 1000}
           (get-in copied [:ui :clipboard]))
        "Copy: the rows and the steps, in the one clipboard (keyboard plan Step 4a)")
    (is (= (get-in copied [:ui :clipboard]) (:clipboard/write copy-effects)) "and on the OS clipboard")
    (is (= 0 (undo-depth copied)) "copying is no edit")
    (is (= (:chains s0) (:chains copied)))
    (let [pasted (-> copied (open cue-target) (run {:event/type :step-grid/paste :target cue-target}))
          p (params-at pasted cue-target)]
      (is (= [A B C :graphics] (:zones p)) "its rows, then the ones it lacked")
      (is (= [[C] [A C] [] [] [A]] (:steps p)) "each ref where it was: rows matched by ref")
      (is (= 1 (undo-depth pasted)))
      (is (= "Edit Step Grid" (undo-label pasted))))
    (is (= (params-at s0 cue-target) (params-at (-> s0 (open cue-target) (run {:event/type :step-grid/paste :target cue-target})) cue-target))
        "nothing copied: nothing pasted")))

(deftest step-grid-paste-with-an-empty-slot-asks-for-the-system-clipboard-test
  ;; Keyboard plan Step 4a (D8 A): with nothing copied in this app, Paste
  ;; reads the OS clipboard first, then pastes the grid it found
  (let [s0 (-> (with-grids) (assoc-in [:ui :clipboard] nil) (open cue-target))
        effects (handle s0 {:event/type :step-grid/paste :target cue-target})]
    (is (= {:clipboard/read {:then {:event/type :step-grid/paste :target cue-target}}}
           (select-keys effects [:clipboard/read])))
    (is (= s0 (or (:state effects) s0)) "nothing pasted yet")
    (let [clip {:type :step-grid :grid {:zones [C] :steps [[C] [] [C]]} :copied-at 5}
          pasted (run s0 {:event/type :step-grid/paste :target cue-target :clipboard/system clip})]
      (is (= [[C] [] [C]] (steps-at pasted cue-target)))
      (is (= clip (get-in pasted [:ui :clipboard])) "the slot keeps the grid it read")
      (is (= "Edit Step Grid" (undo-label pasted))))
    (let [nothing (run s0 {:event/type :step-grid/paste :target cue-target :clipboard/system nil})]
      (is (= (params-at s0 cue-target) (params-at nothing cue-target)))
      (is (= "Nothing to paste here" (get-in nothing [:ui :notice :text]))))))

(deftest step-grid-paste-ignores-a-cue-clip-test
  (let [s0 (-> (with-grids)
               (assoc-in [:ui :clipboard] {:type :cue-chain-items :copied-at 1
                                           :items [{:type :preset :preset-id :circle}]})
               (open cue-target))
        s (run s0 {:event/type :step-grid/paste :target cue-target})]
    (is (= (params-at s0 cue-target) (params-at s cue-target)))
    (is (= 0 (undo-depth s)))
    (is (nil? (get-in s [:ui :notice])) "the Paste button was off: no notice")))


;; The cursor

(deftest cursor-moves-and-stays-in-the-grid-test
  (let [s0 (run (with-grids) {:event/type :step-grid/open :target cue-target})
        at #(get-in % [:ui :dialogs :step-grid-editor :cursor])
        move (fn [s m] (run s {:event/type :step-grid/cursor :target cue-target :move m}))]
    (is (= [0 0] (at s0)))
    (is (= [0 1] (at (move s0 [0 1]))) "→")
    (is (= [1 1] (at (-> s0 (move [0 1]) (move [1 0])))) "↓")
    (is (= [0 0] (at (move s0 [-1 -1]))) "it stops at the top-left")
    (is (= [2 3] (at (reduce move s0 (repeat 9 [1 1])))) "and at the bottom-right")
    (is (= [0 3] (at (run s0 {:event/type :step-grid/cursor :target cue-target :to :last}))) "End")
    (is (= [0 0] (at (run (move s0 [0 2]) {:event/type :step-grid/cursor :target cue-target :to :first}))) "Home")
    (is (= [2 1] (at (run s0 {:event/type :step-grid/cursor :target cue-target :row 2 :step 1}))) "a click")
    (is (= 0 (undo-depth (move s0 [0 1]))) "UI only")
    (testing "only the window's grid's keys move it: none without a target, or with another"
      (is (= [0 0] (at (run s0 {:event/type :step-grid/cursor :move [0 1]}))))
      (is (= [0 0] (at (run s0 {:event/type :step-grid/cursor :target cell-target :move [0 1]})))))
    (testing "a shorter grid takes the cursor with it"
      (let [s (-> s0 (move [0 3]) (run {:event/type :step-grid/set-length :target cue-target :n 2}))]
        (is (= [0 1] (at s)))))))


;; The window keeps its pattern (reactivity plan 05): it follows the
;; pattern's id, never the place it had in its cell

(def ^:private P #uuid "00000000-0000-0000-0000-0000000005a1")
(def ^:private Q #uuid "00000000-0000-0000-0000-0000000005b2")
(def ^:private G #uuid "00000000-0000-0000-0000-0000000005c3")

(defn- cell-key [domain] (if (= :cue-zone-patterns domain) [0 0] [2 1]))

(defn- target-of
  "The window's target on the pattern id in domain's cell: the cue A1's
   patterns, or the effects cell B3's items."
  [domain id]
  {:domain domain :entity-key (cell-key domain) :effect-instance-id id})

(defn- two-grids
  "fresh, saved, with grids P and Q over A, B, C, in that order, among
   domain's patterns: the cue A1's, or the effects cell B3's."
  [domain p-steps q-steps]
  (let [items [(assoc (grid-pattern p-steps) :id P) (assoc (grid-pattern q-steps) :id Q)]]
    (-> (fresh)
        (cond->
          (= :cue-zone-patterns domain) (assoc-in [:chains :cue-chains [0 0] :zone-patterns] items)
          (= :effect-chains domain) (assoc-in [:chains :effect-chains [2 1]] {:active? true :items items}))
        (history/mark-clean))))

(defn- set-items
  "What the effects list or the pattern list sends when it reorders,
   groups or removes: domain's cell's items, all of them."
  [state domain items]
  (run state {:event/type :chain/set-items :domain domain :entity-key (cell-key domain) :items items}))

(defn- rate-control
  "The Rate control in the window's real toolbar (views.components.step-grid)
   on target: its :param-spec and the templates its edits are built from,
   :on-change-event and :modulator-event-base."
  [state target]
  (let [ctx (fx/create-context state state-core/cache-factory)
        desc (grid-view/toolbar {:fx/context ctx :target target :grid (fx/sub-ctx ctx subs/step-grid target)})]
    (some #(when (and (map? %) (= :rate (:param-key %))) %) (tree-seq coll? seq desc))))

(defn- rate-edit
  "What the window's Rate slider sends for value (its static change)."
  [state target value]
  (assoc (:on-change-event (rate-control state target)) :param-key :rate :fx/event value))

(deftest edits-follow-the-open-pattern-test
  (testing "the toolbar's controls name the window's pattern, and the window"
    (let [s (open (two-grids :effect-chains [[] []] [[B] [C]]) (target-of :effect-chains P))
          {:keys [on-change-event modulator-event-base]} (rate-control s (target-of :effect-chains P))]
      (is (= {:event/type :chain/update-param :domain :effect-chains :entity-key [2 1] :effect-instance-id P
              :dialog-id :step-grid-editor}
             on-change-event))
      (is (= (dissoc on-change-event :event/type) modulator-event-base))))
  (doseq [domain [:cue-zone-patterns :effect-chains]]
    (testing (str domain)
      (let [p-target (target-of domain P)
            q-target (target-of domain Q)
            s0 (open (two-grids domain [[] []] [[B] [C]]) p-target)
            p (instance-at s0 p-target)
            q (instance-at s0 q-target)]
        (testing "example 2: P goes into a group behind Q; the keys and the toolbar still edit P"
          (let [moved (set-items s0 domain [q {:type :group :id G :items [p]}])
                depth (undo-depth moved)
                s (-> moved
                      (run {:event/type :step-grid/cursor :target p-target :move [0 1]})
                      (run {:event/type :step-grid/toggle :target p-target}))]
            (is (= [1 :items 0] (:effect-path (sg/locate moved p-target))) "found where it is now")
            (is (= [0 1] (get-in s [:ui :dialogs :step-grid-editor :cursor])))
            (is (= [[] [A]] (steps-at s p-target)))
            (is (= q (get-in (vec (owner-items s q-target)) [0])) "Q, in P's old place, as it was")
            (is (= (inc depth) (undo-depth s)) "one undo step")
            (is (= {:zones [A B C] :steps [[] [A]]}
                   (get-in (run s {:event/type :step-grid/copy :target p-target}) [:ui :clipboard :grid]))
                "Copy takes P's grid")
            (let [rated (run s (rate-edit s p-target 3.0))]
              (is (= 3.0 (:rate (params-at rated p-target))) "a Rate change edits P")
              (is (= q (instance-at rated q-target)) "and only P")
              (is (= (inc (undo-depth s)) (undo-depth rated))))
            (let [lengthened (run s {:event/type :step-grid/set-length :target p-target :n 4})]
              (is (= [[] [A] [] []] (steps-at lengthened p-target)) "the toolbar's own edits too")
              (is (= q (instance-at lengthened q-target))))))
        (testing "a modulator on P's Rate, after a reorder: its settings' writes edit P where it is now"
          (let [moved (set-items s0 domain [q p])
                {spec :param-spec base :modulator-event-base} (rate-control moved p-target)
                on (run moved (assoc base :event/type :modulator/toggle :param-key :rate :param-spec spec :fx/event true))
                cfg (:rate (params-at on p-target))
                period-edit (assoc base :event/type :modulator/update-param :param-key :rate :current-value cfg
                                   :mod-param-key :period :fx/event 2.0)
                period (run on period-edit)]
            (is (mod/modulator-config? cfg) "∿ on")
            (is (= q (instance-at on q-target)))
            (is (nil? (:dispatch (handle on period-edit)))
                "it writes P itself, by its instance, in the one turn (reactivity plan 07): no follow-up")
            (is (= 2.0 (get-in (params-at period p-target) [:rate :period])))
            (is (= q (instance-at period q-target)))))
        (testing "example 3: a drag after a reorder paints P, one undo step; undo keeps the reorder"
          (let [s0 (open (two-grids domain [[] [] []] [[] [] []]) p-target)
                q (instance-at s0 q-target)
                moved (set-items s0 domain [q (instance-at s0 p-target)])
                released (-> moved
                             (press p-target g1 [0 0])
                             (drag-to p-target g1 [0 2])
                             (release p-target g1))
                undone (run released {:event/type :edit/undo})]
            (is (= [[A] [A] [A]] (steps-at released p-target)))
            (is (= q (instance-at released q-target)))
            (is (nil? (get-in released [:history :transaction])))
            (is (= (inc (undo-depth moved)) (undo-depth released)))
            (is (= [[] [] []] (steps-at undone p-target)) "undo: P's three rests")
            (is (= [Q P] (mapv :id (owner-items undone p-target))) "and the reorder stays")))
        (testing "P removed between the press and the drag: the drag paints nothing, Q in its place neither"
          (let [s0 (open (two-grids domain [[] [] []] [[] [] []]) p-target)
                pressed (press s0 p-target g1 [0 0])
                q (instance-at pressed q-target)
                removed (set-items pressed domain [q])
                dragged (drag-to removed p-target g1 [0 2])
                released (release dragged p-target g1)]
            (is (= [[A] [] []] (steps-at pressed p-target)) "the press painted P")
            (is (= [q] (owner-items dragged q-target)) "Q as it was")
            (is (= removed dragged))
            (is (nil? (get-in removed [:history :transaction])) "the removal ended the press (reactivity plan 16)")
            (is (= removed released) "so its release does nothing")
            (is (= [q] (owner-items released q-target)))))
        (testing "example 5: P gone, Q in its place: the keys, a press and the toolbar do nothing"
          (let [removed (set-items s0 domain [q])]
            (is (nil? (sg/locate removed p-target)))
            (doseq [event [{:event/type :step-grid/toggle :target p-target :row A :step 0}
                           {:event/type :step-grid/clear :target p-target}
                           {:event/type :step-grid/cursor :target p-target :move [0 1]}
                           {:event/type :step-grid/copy :target p-target}
                           (rate-edit s0 p-target 3.0)]]
              (let [s (run removed event)]
                (is (= (:chains removed) (:chains s)) (str (:event/type event)))
                (is (= (:history removed) (:history s)) (str (:event/type event) ": no undo step"))
                (is (= (get-in removed [:ui :clipboard]) (get-in s [:ui :clipboard])))
                (is (= (get-in removed [:project :dirty?]) (get-in s [:project :dirty?])))))
            (is (= {} (handle removed (rate-edit s0 p-target 3.0))) "the toolbar's edit: no effect at all")
            (is (= {} (handle removed (stroke :step-grid/paint-start p-target g1 [0 0]))) "a press paints nothing")
            (is (= [[] [A]] (steps-at (-> removed
                                          (set-items domain [q p])
                                          (run {:event/type :step-grid/toggle :target p-target :row A :step 1}))
                                      p-target))
                "P back in its cell: the window edits it again")))
        (testing "the window on Q now: P's old events do nothing"
          (let [retargeted (open (set-items s0 domain [q p]) q-target)]
            (doseq [event [{:event/type :step-grid/toggle :target p-target :row A :step 0}
                           (rate-edit s0 p-target 3.0)]]
              (is (= (:chains retargeted) (:chains (run retargeted event))) (str (:event/type event))))))
        (testing "another kind under P's id: nothing"
          (let [other (set-items s0 domain [(assoc p :effect-id :scale) q])]
            (is (nil? (sg/locate other p-target)))
            (doseq [event [{:event/type :step-grid/toggle :target p-target :row A :step 0}
                           (rate-edit s0 p-target 3.0)]]
              (is (= (:chains other) (:chains (run other event))) (str (:event/type event))))))
        (testing "P's keyframes on: neither the grid nor the toolbar edits it"
          (let [kf (assoc-in s0 (conj (:path (sg/locate s0 p-target)) :keyframe-modulator) {:enabled? true :keyframes []})]
            (doseq [event [{:event/type :step-grid/toggle :target p-target :row A :step 0}
                           (rate-edit s0 p-target 3.0)]]
              (is (= (:chains kf) (:chains (run kf event))) (str (:event/type event))))
            (is (= {} (handle kf (rate-edit s0 p-target 3.0))))
            (is (= {} (handle kf (stroke :step-grid/paint-start p-target g1 [0 0]))) "nor a press")))
        (testing "the show lock on, P moved: the grid's edits and the toolbar's are refused as P's"
          (let [locked (-> s0 (set-items domain [q p]) (assoc-in [:ui :show-lock?] true))]
            (let [s (run locked {:event/type :step-grid/toggle :target p-target :row A :step 0})]
              (is (= (:chains locked) (:chains s)))
              (is (= "Edit Step Grid refused" (get-in s [:ui :show-lock-notice :text]))))
            (let [s (run locked (rate-edit s0 p-target 3.0))]
              (is (= (:chains locked) (:chains s)))
              (is (= "Zone pattern edit refused" (get-in s [:ui :show-lock-notice :text]))))))))))

(deftest parked-paste-rechecks-grid-target-test
  (let [domain :effect-chains
        p-target (target-of domain P)
        q-target (target-of domain Q)
        clip {:type :step-grid :grid {:zones [A] :steps [[A]]} :copied-at 5}
        s0 (-> (two-grids domain [[] [] []] [[B] [] []])
               (assoc-in [:ui :clipboard] nil)
               (open p-target))
        then (get-in (handle s0 {:event/type :step-grid/paste :target p-target}) [:clipboard/read :then])
        reply (assoc then :clipboard/system clip)
        p (instance-at s0 p-target)
        q (instance-at s0 q-target)
        unchanged? (fn [why before]
                     (let [effects (handle before reply)
                           after (run before reply)]
                       (is (= (:chains before) (:chains after)) why)
                       (is (nil? (get-in after [:ui :clipboard])) (str why ": the clip isn't kept"))
                       (is (= (undo-depth before) (undo-depth after)) (str why ": no undo step"))
                       (is (= (get-in before [:project :dirty?]) (get-in after [:project :dirty?])) why)
                       (is (nil? (get-in after [:ui :notice])) (str why ": no notice"))
                       (is (not-any? #{:clipboard/read :clipboard/write} (keys effects)) (str why ": no clipboard effect"))))]
    (is (= {:event/type :step-grid/paste :target p-target} then) "the read resumes the paste on the window's pattern")
    (testing "example 4: P and Q swap places before the reply; P takes the grid"
      (let [moved (set-items s0 domain [q p])
            s (run moved reply)]
        (is (= [[A]] (steps-at s p-target)))
        (is (= q (instance-at s q-target)) "Q as it was")
        (is (= clip (get-in s [:ui :clipboard])) "the slot keeps the clip it read")
        (is (= (inc (undo-depth moved)) (undo-depth s)))))
    (testing "the window shows Q by the time the reply comes: neither grid nor clipboard changes"
      (unchanged? "retargeted" (open (set-items s0 domain [q p]) q-target)))
    (testing "example 5: P gone from its cell, into another: neither Q nor that P changes"
      (let [gone (-> (set-items s0 domain [q])
                     (assoc-in [:chains :effect-chains [3 1]] {:active? true :items [p]}))]
        (unchanged? "gone" gone)
        (testing "only P back in its own cell makes the window's paste land, there"
          (let [back (set-items gone domain [q p])
                s (run back reply)]
            (is (= [[A]] (steps-at s p-target)))
            (is (= q (instance-at s q-target)))
            (is (= p (get-in s [:chains :effect-chains [3 1] :items 0])) "the P in another cell as it was")))))
    (testing "a Paste for a pattern the window doesn't show doesn't ask the OS clipboard"
      (let [retargeted (open s0 q-target)
            effects (handle retargeted {:event/type :step-grid/paste :target p-target})]
        (is (not (contains? effects :clipboard/read)))
        (is (= retargeted (or (:state effects) retargeted)))))))


;; A press's step ends with the press (reactivity plan 16)

(def ^:private modulated-id #uuid "00000000-0000-0000-0000-0000000016f9")

(defn- with-keyframed-amount
  "state with an Intensity in the effects cell F4 ([5 3]) whose amount has an
   inactive modulator while its keyframes are on: what 7b320b3's Modulate
   switches in one edit (history-handlers-test's fixture)."
  [state]
  (assoc-in state [:chains :effect-chains [5 3]]
            {:active? false
             :items [{:id modulated-id :effect-id :intensity :enabled? true
                      :params {:amount {:type :triangle :period 7.0 :min 0.2 :max 0.8 :value 0.6 :active? false}}
                      :keyframe-modulator {:enabled? true :keyframes [{:position 0.0 :params {:amount 0.3}}
                                                                      {:position 1.0 :params {:amount 0.9}}]}}]}))

(defn- labels [state] (mapv :description (get-in state [:history :undo-stack])))

(def ^:private rename {:event/type :cue-chain/set-name :col 1 :row 0 :name "After"})

(def ^:private modulate
  {:event/type :modulator/toggle :domain :effect-chains :entity-key [5 3] :effect-instance-id modulated-id
   :param-key :amount :param-spec {:min 0.0 :max 1.0} :fx/event true})

(deftest close-or-interrupt-paint-preserves-undo-order-test
  (let [rests (vec (repeat 5 []))
        painted [[] [A] [A] [A] []]
        s0 (-> (with-grids rests) (with-keyframed-amount) (history/mark-clean) (open cue-target))
        name-of #(get-in % [:chains :cue-chains [1 0] :name])
        modulating? #(true? (get-in % [:chains :effect-chains [5 3] :items 0 :params :amount :active?]))
        stroke! #(-> % (press cue-target g1 [0 1]) (drag-to cue-target g1 [0 3]))
        close {:event/type :ui/close-dialog :dialog-id :step-grid-editor :editor-id session}]
    (doseq [[why act label value-of]
            [["released, then a rename"
              #(-> % stroke! (release cue-target g1) (run rename)) "Rename Cue" name-of]
             ["closed before the release, then a rename"
              #(-> % stroke! (run close) (run rename)) "Rename Cue" name-of]
             ["renamed while still held, before the deadline; then the press's late drag and release"
              #(-> % stroke! (run (assoc rename :time 30000)) (drag-to cue-target g1 [0 4]) (release cue-target g1))
              "Rename Cue" name-of]
             ["Modulate (one edit since 7b320b3) while still held"
              #(-> % stroke! (run modulate) (release cue-target g1)) "Toggle Modulation" modulating?]]]
      (testing why
        (let [s (act s0)
              u1 (run s {:event/type :edit/undo})
              u2 (run u1 {:event/type :edit/undo})
              r1 (run u2 {:event/type :edit/redo})
              r2 (run r1 {:event/type :edit/redo})
              before (value-of s0)
              after (value-of s)]
          (is (not= before after) "the second edit applied")
          (is (= ["Edit Step Grid" label] (labels s)) "the paint, then the other edit: two steps")
          (is (= painted (steps-at s cue-target)))
          (is (nil? (get-in s [:history :transaction])))
          (testing "the first Undo takes back only the second edit; the paint stays"
            (is (= [painted before] [(steps-at u1 cue-target) (value-of u1)])))
          (testing "the second takes back the paint: the saved show"
            (is (= [rests before] [(steps-at u2 cue-target) (value-of u2)]))
            (is (false? (get-in u2 [:project :dirty?]))))
          (testing "Redo paints, then makes the second edit"
            (is (= [painted before] [(steps-at r1 cue-target) (value-of r1)]))
            (is (= [painted after] [(steps-at r2 cue-target) (value-of r2)]))))))
    (testing "the close hid the window and ended its session in the same state"
      (let [closed (-> s0 stroke! (run close))]
        (is (false? (get-in closed [:ui :dialogs :step-grid-editor :open?])))
        (is (nil? (get-in closed [:ui :dialogs :step-grid-editor :editor-id])))
        (is (= cue-target (get-in closed [:ui :dialogs :step-grid-editor :target])) "its target stays for a reopen")
        (is (= ["Edit Step Grid"] (labels closed)))))
    (testing "Undo while the first stroke is held restores the grid and leaves one Redo"
      (let [undone (run (stroke! s0) {:event/type :edit/undo})]
        (is (= rests (steps-at undone cue-target)))
        (is (= 1 (count (get-in undone [:history :redo-stack]))))
        (is (nil? (get-in undone [:history :transaction])))
        (is (= undone (-> undone (drag-to cue-target g1 [0 4]) (release cue-target g1))) "the press's later events do nothing")
        (is (= painted (steps-at (run undone {:event/type :edit/redo}) cue-target)))))
    (testing "a rename in a run of its own after the deadline has its own step too"
      (let [later (run (stroke! s0) (assoc rename :time (+ 1000 history/stale-transaction-ms)))]
        (is (= ["Edit Step Grid" "Rename Cue"] (labels later)))
        (is (nil? (get-in later [:history :transaction])))))))

(deftest retired-paint-cannot-end-or-edit-successor-test
  (let [s0 (open (with-grids (vec (repeat 5 []))) cue-target)
        session-2 #uuid "00000000-0000-0000-0000-0000000016fa"
        first-press (-> s0 (press cue-target g1 [0 1]) (drag-to cue-target g1 [0 2]))
        closed (run first-press {:event/type :ui/close-dialog :dialog-id :step-grid-editor :editor-id session})
        reopened (open closed cue-target session-2)
        second-press (run reopened (assoc (stroke :step-grid/paint-start cue-target g2 [1 0]) :editor-id session-2))
        unchanged? (fn [state event]
                     (let [effects (handle state event)]
                       (and (= state (or (:state effects) state))
                            (not (contains? effects :history/gesture-alarm)))))]
    (testing "two presses on one pattern across a reopen: each its own step"
      (is (= ["Edit Step Grid"] (labels second-press)) "the first press's, sealed by the close")
      (is (= g2 (history/gesture-id (:history second-press))))
      (is (= [[B] [A] [A] [] []] (steps-at second-press cue-target))))
    (testing "the first press's late paint, end, abort, commit and wake-up, and the old session's close, do nothing"
      (doseq [event [(stroke :step-grid/paint cue-target g1 [0 4])
                     (stroke :step-grid/paint-end cue-target g1)
                     (assoc (stroke :step-grid/paint cue-target g1 [0 4]) :editor-id session-2)
                     (assoc (stroke :step-grid/paint-end cue-target g2) :editor-id session)
                     {:event/type :history/abort-transaction :history/gesture-id g1}
                     {:event/type :history/commit-transaction :history/gesture-id g1}
                     {:event/type :history/gesture-timeout :history/gesture-id g1 :time 5000}
                     {:event/type :ui/close-dialog :dialog-id :step-grid-editor :editor-id session}]]
        (is (unchanged? second-press event) (pr-str (dissoc event :target)))))
    (testing "closing a different window leaves the press alone"
      (is (= g2 (history/gesture-id (:history (run second-press {:event/type :ui/close-dialog :dialog-id :zone-pattern-editor}))))))
    (testing "a slider's press after it seals it; the grid press's events then do nothing"
      (let [slider (run second-press {:event/type :history/begin-transaction :label "Adjust Rate"
                                      :source-id [:slider :rate] :history/gesture-id g3})]
        (is (= g3 (history/gesture-id (:history slider))))
        (is (= ["Edit Step Grid" "Edit Step Grid"] (labels slider)))
        (doseq [event [(assoc (stroke :step-grid/paint cue-target g2 [1 3]) :editor-id session-2)
                       (assoc (stroke :step-grid/paint-end cue-target g2) :editor-id session-2)]]
          (is (unchanged? slider event) (str (:event/type event))))))
    (testing "a second pattern beside the press's: reorder or delete it, and the press's queued sample touches neither"
      (let [p-target (target-of :effect-chains P)
            q-target (target-of :effect-chains Q)
            s (-> (two-grids :effect-chains [[] [] []] [[B] [] []]) (open p-target) (press p-target g1 [0 0]))
            p (instance-at s p-target)
            q (instance-at s q-target)]
        (doseq [[why items] [["reordered" [q p]] ["deleted" [q]]]]
          (let [changed (set-items s :effect-chains items)
                sample (stroke :step-grid/paint p-target g1 [0 2])]
            (is (nil? (get-in changed [:history :transaction])) (str why ": the edit ended the press"))
            (is (= {} (handle changed sample)) why)
            (is (= q (instance-at (run changed sample) q-target)) why)))))))

(deftest editor-target-and-gesture-undo-integration-test
  ;; 00-overview's "05 editor -> 16 history" check: open A, reorder it,
  ;; paint, close before the mouse-up, receive clock beats, then edit B
  (let [a (target-of :effect-chains P)
        b (target-of :effect-chains Q)
        s0 (open (two-grids :effect-chains [[] [] []] [[] [] []]) a)
        moved (set-items s0 :effect-chains [(instance-at s0 b) (instance-at s0 a)])
        stroke! #(-> % (press a g1 [0 0]) (drag-to a g1 [0 2]))
        beats (fn [s from to]
                (reduce (fn [s t] (run s {:event/type :show-clock/status :time t
                                          :status {:beat-index (mod (quot t 500) 4) :frame-stats {}
                                                   :mtc {:state :stopped :fps nil :text nil} :cue-warnings nil}}))
                        s (range from to 500)))
        edit-b #(-> % (open b #uuid "00000000-0000-0000-0000-0000000016fb")
                    (run {:event/type :step-grid/toggle :target b :row A :step 1 :time 126000}))]
    (doseq [[why paint!] [["closed before the mouse-up"
                           #(run (stroke! %) {:event/type :ui/close-dialog :dialog-id :step-grid-editor :editor-id session})]
                          ["never released nor closed: its deadline seals it" stroke!]]]
      (testing why
        (let [painted (paint! moved)
              beaten (beats painted 1500 126000)
              edited (edit-b beaten)
              u1 (run edited {:event/type :edit/undo})
              u2 (run u1 {:event/type :edit/undo})]
          (is (nil? (get-in beaten [:history :transaction])) "125 s of beats kept no press open")
          (is (= [[A] [A] [A]] (steps-at edited a)) "only A was painted")
          (is (= [[] [A] []] (steps-at edited b)) "B has only its own edit")
          (is (= ["Modify Chain" "Edit Step Grid" "Edit Step Grid"] (labels edited)))
          (is (= [[[A] [A] [A]] [[] [] []]] [(steps-at u1 a) (steps-at u1 b)]) "undoing B's edit keeps A's paint")
          (is (= [[[] [] []] [[] [] []]] [(steps-at u2 a) (steps-at u2 b)]) "the next Undo takes the paint back")
          (is (= [Q P] (mapv :id (owner-items u2 a))) "and the reorder stays"))))
    (testing "A deleted mid-drag: the deletion is a step of its own, not folded into the paint's"
      (let [deleted (-> moved stroke! (set-items :effect-chains [(instance-at moved b)]) (release a g1))
            u1 (run deleted {:event/type :edit/undo})
            u2 (run u1 {:event/type :edit/undo})]
        (is (= ["Modify Chain" "Edit Step Grid" "Modify Chain"] (labels deleted)))
        (is (= [[A] [A] [A]] (steps-at u1 a)) "the first Undo brings A back as painted")
        (is (= [[] [] []] (steps-at u2 a)) "the second takes the paint back")
        (is (= [Q P] (mapv :id (owner-items u2 a))))))))
