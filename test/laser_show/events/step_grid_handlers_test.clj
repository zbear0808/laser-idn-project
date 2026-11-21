(ns laser-show.events.step-grid-handlers-test
  "The step grid's events (zones plan Z6, 08 §6): each edit reads and writes
   a zone pattern's params where chain-config puts them, a cue's or an
   effects cell's alike, as one undo step \"Edit Step Grid\" (a drag too),
   and does nothing while the pattern's keyframes are on (08 invariant 9).
   Every event runs through the real pipeline, as the agent runs it."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.routing.zone-patterns :as zp]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.templates :as templates])
  (:import [javafx.scene.input MouseButton MouseEvent]))

(defn- fresh
  "A new show, its starter cues and effects, saved, at 120 BPM."
  []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-effects)
      (assoc-in [:timing :bpm] 120.0)
      (history/mark-clean)))

(defn- run
  "state after event through the pipeline, and what it dispatches."
  [state event]
  (let [effects (events/handle-event-pipeline (assoc event :state state :time 1000))
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run s next-event)
      s)))

(def ^:private A :zone/a)
(def ^:private B :zone/b)
(def ^:private C :zone/c)

(def ^:private cue-target {:domain :cue-zone-patterns :entity-key [0 0] :effect-path [0]})
(def ^:private cell-target {:domain :effect-chains :entity-key [2 1] :effect-path [0]})

(defn- grid-pattern
  "A step grid over A, B, C with steps, as an instance."
  [steps & {:as more}]
  (-> (zp/preset-instance :steps :cue)
      (assoc :id #uuid "00000000-0000-0000-0000-0000000006a1")
      (update :params merge {:zones [A B C] :steps steps} more)))

(defn- with-grids
  "fresh, with a grid on the cue at A1 and one in the effects cell B3 ([2 1]),
   saved."
  ([] (with-grids [[] [] [] []]))
  ([steps]
   (-> (fresh)
       (assoc-in [:chains :cue-chains [0 0] :zone-patterns] [(grid-pattern steps)])
       (assoc-in [:chains :effect-chains [2 1]] {:active? true :items [(assoc (grid-pattern steps) :id #uuid "00000000-0000-0000-0000-0000000006b2")]})
       (history/mark-clean))))

(defn- params-at [state {:keys [domain entity-key effect-path]}]
  (get-in state (-> (if (= :cue-zone-patterns domain)
                      [:chains :cue-chains entity-key :zone-patterns]
                      [:chains :effect-chains entity-key :items])
                    (into effect-path)
                    (conj :params))))

(defn- steps-at [state target] (:steps (params-at state target)))

(defn- undo-depth [state] (count (get-in state [:history :undo-stack])))

(defn- undo-label [state] (history/undo-label (:history state)))


;; One click, one key: one undo step (08 invariant 1)

(deftest toggle-is-one-undo-step-test
  (let [s0 (with-grids)
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
                  (run {:event/type :step-grid/open :target cue-target})
                  (run {:event/type :step-grid/cursor :target cue-target :row 1 :step 3})
                  (run {:event/type :step-grid/toggle :target cue-target}))]
        (is (= [[] [] [] [B]] (steps-at s cue-target)))
        (is (= 1 (undo-depth s)) "opening a grid and moving the cursor are no undo steps")))
    (testing "outside the grid: nothing, and no undo step"
      (let [s (run s0 {:event/type :step-grid/toggle :target cue-target :row A :step 9})]
        (is (= (steps-at s0 cue-target) (steps-at s cue-target)))
        (is (= 0 (undo-depth s)))))))

(defn- mouse
  "A primary-button MouseEvent of type at the grid body's (x, y)."
  [type x y]
  (MouseEvent. type (double x) (double y) (double x) (double y) MouseButton/PRIMARY 1
               false false false false
               (not= type MouseEvent/MOUSE_RELEASED) false false
               false false true nil))

(defn- pointer [state type x y]
  (run state {:event/type :step-grid/pointer :target cue-target :cell-w 20.0 :cell-h 10.0 :fx/event (mouse type x y)}))

(deftest drag-paint-is-one-undo-step-test
  (let [s0 (with-grids (vec (repeat 8 [])))
        dragged (-> s0
                    (pointer MouseEvent/MOUSE_PRESSED 25.0 5.0)
                    (pointer MouseEvent/MOUSE_DRAGGED 32.0 6.0)
                    (pointer MouseEvent/MOUSE_DRAGGED 95.0 4.0)
                    (pointer MouseEvent/MOUSE_DRAGGED 105.0 5.0))]
    (testing "pressing an unlit cell lights it, and every cell the drag crosses (Z6-Q3 A)"
      (is (= [[] [A] [A] [A] [A] [A] [] []] (steps-at dragged cue-target)) "steps 1 to 5 of row A, the jump from 1 to 4 too")
      (is (some? (get-in dragged [:history :transaction])) "one transaction while the button is down"))
    (let [released (pointer dragged MouseEvent/MOUSE_RELEASED 105.0 5.0)]
      (is (nil? (get-in released [:history :transaction])))
      (is (= 1 (undo-depth released)) "one drag, one undo step")
      (is (= "Edit Step Grid" (undo-label released)))
      (is (true? (get-in released [:project :dirty?])))
      (is (= (vec (repeat 8 [])) (steps-at (run released {:event/type :edit/undo}) cue-target)) "undo reverts the whole drag")
      (testing "pressing a lit cell clears it, and every cell crossed"
        (let [cleared (-> released
                          (pointer MouseEvent/MOUSE_PRESSED 45.0 5.0)
                          (pointer MouseEvent/MOUSE_DRAGGED 85.0 5.0)
                          (pointer MouseEvent/MOUSE_RELEASED 85.0 5.0))]
          (is (= [[] [A] [] [] [] [A] [] []] (steps-at cleared cue-target)))
          (is (= 2 (undo-depth cleared))))))
    (testing "a press outside the grid, or with another button, paints nothing"
      (is (= (steps-at s0 cue-target) (steps-at (pointer s0 MouseEvent/MOUSE_PRESSED 165.0 5.0) cue-target)) "past step 8")
      (is (= (steps-at s0 cue-target) (steps-at (pointer s0 MouseEvent/MOUSE_PRESSED 25.0 35.0) cue-target)) "below the rows")
      (let [right (MouseEvent. MouseEvent/MOUSE_PRESSED 25.0 5.0 25.0 5.0 MouseButton/SECONDARY 1
                               false false false false false false true false true true nil)]
        (is (= (steps-at s0 cue-target)
               (steps-at (run s0 {:event/type :step-grid/pointer :target cue-target :cell-w 20.0 :cell-h 10.0 :fx/event right}) cue-target)))))
    (testing "a drag with no press before it does nothing"
      (is (= s0 (pointer s0 MouseEvent/MOUSE_DRAGGED 45.0 5.0))))))


;; A cue's pattern and a cell's alike

(deftest edits-reach-a-cue-pattern-and-a-cell-pattern-test
  (doseq [target [cue-target cell-target]]
    (testing (str (:domain target))
      (let [s0 (with-grids [[A] [] [B] []])
            other (if (= target cue-target) cell-target cue-target)
            step (fn [event] (run s0 (assoc event :target target)))]
        (is (= [[A] [A] [B] []] (steps-at (step {:event/type :step-grid/toggle :row A :step 1}) target)))
        (is (= [[A] [] [B] [] [] [] [] []] (steps-at (step {:event/type :step-grid/set-length :n 8}) target)))
        (is (= [A B C :zone/d] (:zones (params-at (step {:event/type :step-grid/add-row}) target))) "+ Row: the next zone letter")
        (is (= [[] [] [] []] (steps-at (step {:event/type :step-grid/clear}) target)))
        (is (= [[] [A] [] [B]] (steps-at (step {:event/type :step-grid/rotate :by 1}) target)))
        (is (= (steps-at s0 other) (steps-at (step {:event/type :step-grid/clear}) other)) "the other grid stays")))))

(deftest row-and-step-menus-test
  (let [s0 (with-grids [[A B] [B] [A C] []])
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
               (history/mark-clean))]
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
      (let [s (-> s0 (pointer MouseEvent/MOUSE_PRESSED 25.0 5.0) (pointer MouseEvent/MOUSE_RELEASED 25.0 5.0))]
        (is (= (params-at s0 cue-target) (params-at s cue-target)))
        (is (nil? (get-in s [:history :transaction])))))
    (testing "and opening it makes no grid of a keyframed pattern"
      (let [s1 (-> (fresh)
                   (assoc-in [:chains :cue-chains [0 0] :zone-patterns]
                             [(assoc (zp/preset-instance :chase :cue) :id #uuid "00000000-0000-0000-0000-0000000006c3"
                                     :keyframe-modulator {:enabled? true :keyframes []})]))
            s (run s1 {:event/type :step-grid/open :target cue-target})]
        (is (= :chase (:style (params-at s cue-target))))
        (is (true? (get-in s [:ui :dialogs :step-grid-editor :open?])) "the window opens all the same")))))


;; Opening the window

(deftest open-makes-a-grid-from-the-pattern-test
  (let [s0 (-> (fresh)
               (assoc-in [:chains :cue-chains [0 0] :zone-patterns]
                         [(-> (zp/preset-instance :alternate :cue)
                              (assoc :id #uuid "00000000-0000-0000-0000-0000000006d4")
                              (update :params merge {:zones [A B C :zone/d] :rate 1.5}))])
               (history/mark-clean))
        s (run s0 {:event/type :step-grid/open :target cue-target})
        p (params-at s cue-target)]
    (is (= {:open? true :target cue-target :cursor [0 0]} (select-keys (get-in s [:ui :dialogs :step-grid-editor]) [:open? :target :cursor])))
    (is (= :steps (:style p)) "it becomes a grid")
    (is (= [[A C] [B :zone/d] [A C] [B :zone/d] [A C] [B :zone/d] [A C] [B :zone/d]] (:steps p))
        "of its own style's first 8 steps, so it plays the same for them")
    (is (= [1.5 :cue] [(:rate p) (:clock p)]) "at its own rate, on its own clock")
    (is (false? (:follow-pads? p)) "a grid doesn't follow the pads (Z6-Q2 A)")
    (is (= 1 (undo-depth s)))
    (is (= "Edit Step Grid" (undo-label s)))
    (testing "opening a grid again changes nothing"
      (let [again (run (run s {:event/type :ui/close-dialog :dialog-id :step-grid-editor}) {:event/type :step-grid/open :target cue-target})]
        (is (= p (params-at again cue-target)))
        (is (= 1 (undo-depth again)))))
    (testing "a cue with no pattern gets a new grid: 8 steps at 2 a beat over A and B (Z6-Q1 A)"
      (let [s (run (fresh) {:event/type :step-grid/open :target {:domain :cue-zone-patterns :entity-key [1 0] :effect-path [0]}})
            [fx :as fxs] (get-in s [:chains :cue-chains [1 0] :zone-patterns])]
        (is (= 1 (count fxs)))
        (is (= (:params (zp/preset-instance :steps :cue)) (:params fx)))
        (is (uuid? (:id fx)))))
    (testing "Every laser becomes zones, and the window says which (S-4)"
      (let [s1 (-> (fresh)
                   (assoc :projectors {:p1 {:name "L1" :host "10.0.0.1" :enabled? true :zone-groups []}
                                       :p2 {:name "L2" :host "10.0.0.2" :enabled? true :zone-groups []}
                                       :p3 {:name "L3" :host "10.0.0.3" :enabled? true :zone-groups []}})
                   (assoc-in [:chains :cue-chains [0 0] :zone-patterns] [(assoc (zp/preset-instance :chase :cue) :id #uuid "00000000-0000-0000-0000-0000000006e5")]))
            s (run s1 {:event/type :step-grid/open :target cue-target})]
        (is (= [A B C] (:zones (params-at s cue-target))))
        (is (= [[A] [B] [C] [A] [B] [C] [A] [B]] (steps-at s cue-target)))
        (is (= [A B C] (get-in s [:ui :dialogs :step-grid-editor :every-laser])))))
    (testing "a target that holds no zone pattern opens nothing"
      (let [s (run (fresh) {:event/type :step-grid/open :target {:domain :effect-chains :entity-key [0 3] :effect-path [0]}})]
        (is (not (get-in s [:ui :dialogs :step-grid-editor :open?])) "the Master is a point effect")))))


;; Starting points, copy and paste

(deftest start-from-is-one-undo-step-test
  (let [s0 (run (with-grids) {:event/type :step-grid/set-length :target cue-target :n 16})
        s (run s0 {:event/type :step-grid/start-from :target cue-target :style :chase})]
    (is (= (vec (take 16 (cycle [[A] [B] [C]]))) (steps-at s cue-target)) "the grid's length of Chase over its rows")
    (is (= (inc (undo-depth s0)) (undo-depth s)))
    (is (= "Edit Step Grid" (undo-label s)))
    (is (= (steps-at s0 cue-target) (steps-at (run s {:event/type :edit/undo}) cue-target)))
    (is (= [[B] [A C] [B] [A C]] (steps-at (run (with-grids) {:event/type :step-grid/start-from :target cue-target :style :center-out}) cue-target)))
    (is (= (vec (repeat 4 [A B C])) (steps-at (run (with-grids) {:event/type :step-grid/start-from :target cue-target :style :all-on}) cue-target)))
    (is (= [[] [] [] []] (steps-at (run (with-grids) {:event/type :step-grid/start-from :target cue-target :style :swap}) cue-target))
        "not a starting point: nothing")))

(deftest paste-from-another-grid-test
  (let [s0 (-> (with-grids)
               (assoc-in [:chains :effect-chains [2 1] :items 0 :params] (:params (grid-pattern [[C] [A C] [] [B] [A]] :zones [C A :graphics])))
               (history/mark-clean))
        copied (run s0 {:event/type :step-grid/copy :target cell-target})]
    (is (= {:zones [C A :graphics] :steps [[C] [C A] [] [] [A]]} (get-in copied [:ui :step-grid :clipboard])) "Copy: the rows and the steps")
    (is (= 0 (undo-depth copied)) "copying is no edit")
    (is (= (:chains s0) (:chains copied)))
    (let [pasted (run copied {:event/type :step-grid/paste :target cue-target})
          p (params-at pasted cue-target)]
      (is (= [A B C :graphics] (:zones p)) "its rows, then the ones it lacked")
      (is (= [[C] [A C] [] [] [A]] (:steps p)) "each ref where it was: rows matched by ref")
      (is (= 1 (undo-depth pasted)))
      (is (= "Edit Step Grid" (undo-label pasted))))
    (is (= (params-at s0 cue-target) (params-at (run s0 {:event/type :step-grid/paste :target cue-target}) cue-target))
        "nothing copied: nothing pasted")))


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
    (testing "a shorter grid takes the cursor with it"
      (let [s (-> s0 (move [0 3]) (run {:event/type :step-grid/set-length :target cue-target :n 2}))]
        (is (= [0 1] (at s)))))))
