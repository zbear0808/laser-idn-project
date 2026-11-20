(ns laser-show.events.rig-handlers-test
  "The rig strip's events (plans/2026-11-19-zones/04 §6): moving a card,
   the middle laser and the split width. Undoable, never an unsaved change
   (the rig file saves itself, 09), and never an engine restart."
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

(deftest reorder-is-undoable-and-saves-itself-test
  (let [clean (history/mark-clean (rig-state))
        moved (:state (pipeline clean {:event/type :rig/move-projector :projector-id :c :target :a :side :before}))
        nudged (:state (pipeline moved {:event/type :rig/nudge-projector :projector-id :c :delta 1}))
        undone (:state (pipeline nudged {:event/type :history/undo}))
        undone-twice (:state (pipeline undone {:event/type :history/undo}))]
    (is (false? (get-in moved [:project :dirty?])) "not an unsaved change: the rig file saves itself (zones plan ZR)")
    (is (= [:a :c :b] (order nudged)))
    (is (= ["Move Projector" "Move Projector"] (mapv :description (get-in nudged [:history :undo-stack]))))
    (is (= [:c :a :b] (order undone)))
    (is (= [] (order undone-twice)))
    (is (false? (get-in undone-twice [:project :dirty?])))
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


;; The rig file's set-up and report (plans/2026-11-19-zones/09 §6)

(def ^:private show-hardware
  "A show made with Laser A (the same unit as :a here) and Laser D, which
   isn't here."
  {:projectors {:a {:name "Laser A (show)" :host "10.0.0.1" :enabled? true :zone-groups [:graphics]
                    :stable-id [:host "10.0.0.1" 0] :safety {:limit [-0.8 -0.8 0.8 0.8] :blocked []}}
                :d {:name "Laser D" :host "10.0.0.4" :enabled? true :zone-groups [] :stable-id [:host "10.0.0.4" 0]}}
   :rig {:order [:d :a] :middle :both :split-width 0.75}})

(defn- asking-to-set-up
  "rig-state with every laser off, and the set-up question open for a show."
  [hardware]
  (-> (rig-state)
      (update :projectors update-vals #(assoc % :enabled? false))
      (assoc-in [:ui :dialogs :rig-report] {:open? true :mode :set-up :file "friday.zip"
                                            :hardware hardware :chains {} :pins {:a :a :d :d}})
      (history/mark-clean)))

(deftest set-up-is-one-undo-step-test
  (let [asked (assoc-in (asking-to-set-up show-hardware) [:rig-file :path] "/tmp/never-written/rig.edn")
        effects (pipeline asked {:event/type :rig/set-up-from-show})
        s (:state effects)]
    (is (= "Laser A (show)" (get-in s [:projectors :a :name])))
    (is (contains? (:projectors s) :d) "Laser D comes into the rig")
    (is (= [:d :a] (get-in s [:rig :order])))
    (is (false? (get-in s [:ui :dialogs :rig-report :open?])) "the question closes")
    (is (= :report (get-in s [:ui :dialogs :rig-report :mode])) "and what doesn't fit is the report's from now on")
    (is (= ["Set Up Rig From Show"] (mapv :description (get-in s [:history :undo-stack]))) "one undo step")
    (is (false? (get-in s [:project :dirty?])) "the rig's: not an unsaved change")
    (is (some? (:rig/write effects)) "the rig file follows")
    (let [undone (:state (pipeline s {:event/type :history/undo}))]
      (is (= (:projectors asked) (:projectors undone)) "Undo takes it all back")))
  (testing "called bare, or with the question gone: nothing"
    (let [s (rig-state)]
      (is (identical? s (handle s {:event/type :rig/set-up-from-show}))))))

(deftest set-up-asks-before-widening-a-live-laser-test
  (let [;; Laser A turned on and streaming since the question came up, with a small zone
        live (-> (asking-to-set-up (assoc-in show-hardware [:projectors :a :safety] {:limit [-1.0 -1.0 1.0 1.0] :blocked []}))
                 (assoc-in [:projectors :a :enabled?] true)
                 (assoc-in [:projectors :a :safety] {:limit [-0.5 -0.5 0.5 0.5] :blocked []})
                 (assoc-in [:backend :streaming :running?] true))
        asked (handle live {:event/type :rig/set-up-from-show})]
    (is (= (:projectors live) (:projectors asked)) "nothing changes yet")
    (is (= "Allow light in a larger area?" (get-in asked [:ui :dialogs :confirm :title])))
    (is (= {:event/type :rig/set-up-from-show :confirmed? true} (get-in asked [:ui :dialogs :confirm :confirm-event])))
    (is (= [-1.0 -1.0 1.0 1.0] (get-in (handle asked {:event/type :rig/set-up-from-show :confirmed? true})
                                       [:projectors :a :safety :limit]))
        "confirmed, it applies")))


;; The report's buttons

(defn- reporting
  "rig-state (all on) with friday.zip's show open: its Graphics group, with
   no laser here, used by a cue; a cue pinned to Laser D, which isn't here;
   and Laser A's own, larger, zone."
  []
  (-> (rig-state)
      (assoc-in [:projectors :a :zone-groups] [:group-gfx])
      (assoc-in [:projectors :a :safety] {:limit [-0.5 -0.5 0.5 0.5] :blocked []})
      (assoc-in [:rig-file :group-copy] {:group-gfx {:id :group-gfx :name "Graphics" :color "#000000"}})
      (assoc-in [:chains :cue-chains] {[0 0] {:items [] :destination-zone {:zone-group-id :graphics}}
                                       [1 0] {:items [] :destination-zone {:zone-group-id :auto.laser/d}}})
      (assoc-in [:ui :dialogs :rig-report] {:open? true :mode :report :file "friday.zip"
                                            :hardware show-hardware :chains {} :pins {:a :a :d :d}})
      (history/mark-clean)))

(deftest use-show-setup-asks-before-widening-a-live-laser-test
  (let [s (reporting)
        asked (handle s {:event/type :rig/request-use-show-setup :projector-id :a})]
    (is (= {:open? true :title "Replace A's safety zone with the show's?" :message "This changes where A can draw."
            :confirm-label "Replace" :confirm-event {:event/type :rig/use-show-setup :projector-id :a}}
           (select-keys (get-in asked [:ui :dialogs :confirm]) [:open? :title :message :confirm-label :confirm-event]))
        "Use the show's… asks first")
    (testing "off the air, Replace applies: one undo step, never unsaved"
      (let [effects (pipeline s {:event/type :rig/use-show-setup :projector-id :a})
            used (:state effects)]
        (is (= [-0.8 -0.8 0.8 0.8] (get-in used [:projectors :a :safety :limit])))
        (is (= ["Use Show's Setup"] (mapv :description (get-in used [:history :undo-stack]))))
        (is (false? (get-in used [:project :dirty?])))))
    (testing "while A outputs, the larger area asks again (Z1's rule, D-28), and only then applies"
      (let [live (assoc-in s [:backend :streaming :running?] true)
            again (handle live {:event/type :rig/use-show-setup :projector-id :a})]
        (is (= [-0.5 -0.5 0.5 0.5] (get-in again [:projectors :a :safety :limit])) "nothing yet")
        (is (= "Allow light in a larger area?" (get-in again [:ui :dialogs :confirm :title])))
        (is (= {:event/type :rig/use-show-setup :projector-id :a :confirmed? true}
               (get-in again [:ui :dialogs :confirm :confirm-event])))
        (is (= [-0.8 -0.8 0.8 0.8] (get-in (handle again {:event/type :rig/use-show-setup :projector-id :a :confirmed? true})
                                           [:projectors :a :safety :limit])))))
    (testing "a shrinking zone on a live laser applies at once"
      (let [live (-> s
                     (assoc-in [:backend :streaming :running?] true)
                     (assoc-in [:projectors :a :safety] {:limit [-1.0 -1.0 1.0 1.0] :blocked []}))]
        (is (= [-0.8 -0.8 0.8 0.8] (get-in (handle live {:event/type :rig/use-show-setup :projector-id :a})
                                           [:projectors :a :safety :limit])))))
    (testing "a show's zone that can't be read is refused, and not even asked about"
      (let [bad (assoc-in s [:ui :dialogs :rig-report :hardware :projectors :a :safety] {:limit "x"})]
        (is (identical? bad (handle bad {:event/type :rig/request-use-show-setup :projector-id :a})))
        (is (identical? bad (handle bad {:event/type :rig/use-show-setup :projector-id :a :confirmed? true})))))))

(deftest report-buttons-test
  (let [s (reporting)
        run (fn [state event] (:state (pipeline state event)))
        last-label #(:description (peek (get-in % [:history :undo-stack])))]
    (testing "Use this rig's \"Graphics\": the show's group on its lasers; the rig's"
      (let [adopted (run s {:event/type :rig/adopt-group :group-id :graphics :from :group-gfx})]
        (is (= [:group-gfx :graphics] (get-in adopted [:projectors :a :zone-groups])))
        (is (= "Use Rig's Group" (last-label adopted)))
        (is (false? (get-in adopted [:project :dirty?])))))
    (testing "Pin them to… and Send them to All: the show's, unsaved"
      (let [repinned (run s {:event/type :rig/repin :from :auto.laser/d :to :auto.laser/b})]
        (is (= :auto.laser/b (get-in repinned [:chains :cue-chains [1 0] :destination-zone :zone-group-id])))
        (is (= "Re-pin Cues" (last-label repinned)))
        (is (true? (get-in repinned [:project :dirty?]))))
      (is (= :all (get-in (run s {:event/type :rig/repin :from :auto.laser/d :to :all})
                          [:chains :cue-chains [1 0] :destination-zone :zone-group-id]))))
    (testing "Add them to this show"
      (let [added (run s {:event/type :rig/add-groups-to-show :group-ids [:group-gfx]})]
        (is (= "Graphics" (get-in added [:zone-groups :group-gfx :name])))
        (is (= "Add Rig's Groups" (last-label added)))
        (is (true? (get-in added [:project :dirty?])))))
    (testing "Import 1 new laser…: Laser D comes in, and its cue reaches it"
      (let [imported (run s {:event/type :rig/import-new-lasers})]
        (is (= "Laser D" (get-in imported [:projectors :d :name])))
        (is (= (get-in s [:projectors :a]) (get-in imported [:projectors :a])) "Laser A is left as it is")
        (is (= "Import Lasers" (last-label imported)))))
    (testing "Choose lasers…: the report closes and the group opens in its tab"
      (let [chosen (run s {:event/type :rig/choose-lasers :group-id :graphics})]
        (is (false? (get-in chosen [:ui :dialogs :rig-report :open?])))
        (is (= [:zones :graphics] [(get-in chosen [:ui :active-tab]) (get-in chosen [:zone-group-ui :selected-group])]))))
    (testing "What doesn't fit (n) reopens it"
      (let [closed (assoc-in s [:ui :dialogs :rig-report :open?] false)]
        (is (= {:open? true :mode :report} (select-keys (get-in (run closed {:event/type :rig/open-report}) [:ui :dialogs :rig-report]) [:open? :mode])))
        (is (= :set-up (get-in (run (update closed :projectors update-vals #(assoc % :enabled? false)) {:event/type :rig/open-report})
                               [:ui :dialogs :rig-report :mode]))
            "on a rig with no laser on, as the set-up question")))
    (testing "called bare, every button does nothing"
      (let [bare (rig-state)]
        (doseq [type [:rig/adopt-group :rig/repin :rig/add-groups-to-show :rig/import-new-lasers :rig/use-show-setup
                      :rig/request-use-show-setup :rig/open-report :rig/choose-lasers]]
          (is (identical? bare (handle bare {:event/type type})) (str type)))))))
