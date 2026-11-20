(ns laser-show.events.rig-handlers-test
  "The rig strip's events (plans/2026-11-19-zones/04 §6): moving a card,
   the middle laser and the split width. Undoable, unsaved, and never an
   engine restart."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.routing.rig :as rig]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]))

(defn- rig-state []
  (assoc (domains/build-initial-state)
         :projectors {:a {:name "A" :host "10.0.0.1" :enabled? true :zone-groups []}
                      :b {:name "B" :host "10.0.0.2" :enabled? true :zone-groups []}
                      :c {:name "C" :host "10.0.0.3" :enabled? true :zone-groups []}}))

(defn- handle [state event]
  (:state (handlers/handle-event (assoc event :state state))))

(defn- pipeline [state event]
  (events/handle-event-pipeline (assoc event :state state)))

(defn- order [state] (get-in state [:rig :order]))

(deftest move-before-and-after-a-card-test
  (let [s (rig-state)
        move #(order (handle s {:event/type :rig/move-projector :projector-id %1 :target %2 :side %3}))]
    (is (= [:c :a :b] (move :c :a :before)) "dropped on a card's left half: before it")
    (is (= [:b :a :c] (move :a :b :after)) "on its right half: after it")
    (is (= [:b :c :a] (move :a :c :after)))
    (is (= [:a :c :b] (move :b :c :after)))
    (testing "nothing to do: an unknown card or target, a card on itself, a bad side, or the place it has"
      (doseq [[id target side] [[:x :a :before] [:a :x :before] [:a :a :after] [:a :b :up] [:a :b :before] [:b :a :after]]]
        (is (= [] (move id target side)) (pr-str [id target side]))))))

(deftest first-move-writes-the-whole-order-test
  (let [s (handle (rig-state) {:event/type :rig/move-projector :projector-id :c :target :b :side :before})]
    (is (= [:a :c :b] (order s)) "every card's place is fixed, not only the moved one's")
    (is (= [:a :c :b :z] (rig/effective-order (order s) (assoc (:projectors s) :z {:host "10.0.0.0"})))
        "so a laser found later lands after the placed ones, though its IP comes first")))

(deftest nudge-at-the-ends-does-nothing-test
  (let [s (rig-state)
        nudge #(handle s {:event/type :rig/nudge-projector :projector-id %1 :delta %2})]
    (is (= [:b :a :c] (order (nudge :a 1))))
    (is (= [:a :c :b] (order (nudge :c -1))))
    (is (identical? s (nudge :a -1)) "at the left end")
    (is (identical? s (nudge :c 1)) "at the right end")
    (is (identical? s (nudge :x 1)) "an unknown card")
    (is (identical? s (nudge :a 0)))
    (is (identical? s (handle s {:event/type :rig/nudge-projector})) "called bare")))

(deftest set-middle-ignores-unknown-values-test
  (let [s (rig-state)]
    (doseq [m [:split :both :left :right :none]]
      (is (= m (get-in (handle s {:event/type :rig/set-middle :middle m}) [:rig :middle]))))
    (doseq [m [:sideways nil "both"]]
      (is (identical? s (handle s {:event/type :rig/set-middle :middle m})) (pr-str m)))))

(deftest split-width-is-clamped-test
  (let [s (rig-state)
        width #(get-in (handle s {:event/type :rig/set-split-width :split-width %}) [:rig :split-width])]
    (is (= 0.6 (width 0.6)))
    (is (= 0.5 (width 0.2)) "0.5 at least")
    (is (= 1.0 (width 1.4)) "1.0 at most")
    (is (= 0.75 (width 3/4)))
    (doseq [bad ["wide" ##NaN nil]]
      (is (identical? s (handle s {:event/type :rig/set-split-width :split-width bad})) (pr-str bad)))))

(deftest split-width-drag-is-one-undo-step-test
  (let [dragged (reduce (fn [s [w t]] (:state (pipeline s {:event/type :rig/set-split-width :split-width w :time t})))
                        (history/mark-clean (rig-state))
                        [[0.7 1000] [0.65 1100] [0.6 1200] [0.55 1300]])]
    (is (= 0.55 (get-in dragged [:rig :split-width])))
    (is (= ["Change Split Width"] (mapv :description (get-in dragged [:history :undo-stack])))
        "a drag of the slider is one step")
    (is (= 0.75 (get-in (:state (pipeline dragged {:event/type :history/undo})) [:rig :split-width])))))

(deftest reorder-is-undoable-and-unsaved-test
  (let [clean (history/mark-clean (rig-state))
        moved (:state (pipeline clean {:event/type :rig/move-projector :projector-id :c :target :a :side :before}))
        nudged (:state (pipeline moved {:event/type :rig/nudge-projector :projector-id :c :delta 1}))
        undone (:state (pipeline nudged {:event/type :history/undo}))
        undone-twice (:state (pipeline undone {:event/type :history/undo}))]
    (is (true? (get-in moved [:project :dirty?])) "an unsaved change")
    (is (= [:a :c :b] (order nudged)))
    (is (= ["Move Projector" "Move Projector"] (mapv :description (get-in nudged [:history :undo-stack]))))
    (is (= [:c :a :b] (order undone)))
    (is (= [] (order undone-twice)))
    (is (false? (get-in undone-twice [:project :dirty?])) "back to the saved order")
    (testing "the middle laser too"
      (is (= ["Change Middle Laser"]
             (mapv :description (get-in (:state (pipeline clean {:event/type :rig/set-middle :middle :both}))
                                        [:history :undo-stack])))))))

(deftest reorder-requests-no-engine-refresh-test
  (let [streaming (assoc-in (rig-state) [:backend :streaming :running?] true)]
    (doseq [event [{:event/type :rig/move-projector :projector-id :c :target :a :side :before}
                   {:event/type :rig/nudge-projector :projector-id :a :delta 1}
                   {:event/type :rig/set-middle :middle :both}
                   {:event/type :rig/set-split-width :split-width 0.6}]]
      (let [effects (pipeline streaming event)]
        (is (not= (:rig streaming) (get-in effects [:state :rig])) (str (:event/type event)))
        (is (nil? (:multi-engine/refresh effects))
            (str (:event/type event) ": the engines read the rig live, no restart"))))))

(deftest drop-marker-test
  (let [s (rig-state)
        over (handle s {:event/type :rig/drag-over :projector-id :b :side :after})]
    (is (= {:projector-id :b :side :after} (get-in over [:rig-ui :drop])) "the bar shows where the card would go")
    (is (nil? (get-in (handle over {:event/type :rig/drag-exit}) [:rig-ui :drop])))
    (is (nil? (get-in (handle over {:event/type :rig/move-projector :projector-id :a :target :b :side :after})
                      [:rig-ui :drop]))
        "a drop clears it")
    (is (identical? over (handle over {:event/type :rig/drag-over :projector-id :b :side :after})) "the same: no change")
    (is (empty? (get-in (pipeline (history/mark-clean s) {:event/type :rig/drag-over :projector-id :b :side :after})
                        [:state :history :undo-stack]))
        "never an undo step")))
