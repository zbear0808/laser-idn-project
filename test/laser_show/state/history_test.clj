(ns laser-show.state.history-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.state.history :as history]))

(def item-id #uuid "11111111-1111-1111-1111-111111111111")
(def group-id #uuid "22222222-2222-2222-2222-222222222222")

(def sample-state
  {:config {:grid {:cols 8 :rows 4}
            :preview {:grid-layout [2 2]}}
   :timing {:bpm 120.0
            :global-clock {:accumulated-beats 4.0}}
   :playback {:playing? true
              :active-cues {[0 0] {:trigger-time 100}}}
   :grid {:size [8 4]
          :selected-cell [0 0]}
   :ui {:active-tab :grid
        :preview {:frame {:points []}}}
   :backend {:idn {:connected? true}}
   :projectors {:proj-1 {:name "Main" :enabled? true}}
   :virtual-projectors {}
   :zone-groups {:all {:id :all :name "All"}}
   :chains {:cue-chains {[0 0] {:items [{:id item-id
                                         :type :preset
                                         :params {:size 1.0}}]}}
            :effect-chains {[1 0] {:active? false
                                   :items [{:id group-id
                                            :type :group
                                            :collapsed? false
                                            :items []}]}}
            :projector-effects {}}
   :history {:undo-stack []
             :redo-stack []
             :transaction nil}})

(defn- doc [state] (history/extract-document-state state))

(defn- edit-content [state] (assoc-in state [:chains :cue-chains [0 0] :items 0 :params :size] 2.0))

(deftest extract-document-state-test
  (testing "extracts only document fields and ignores runtime data"
    (let [d (doc sample-state)]
      (is (= {:cols 8 :rows 4} (get-in d [:config :grid])))
      (is (= 120.0 (get-in d [:timing :bpm])))
      (is (= [8 4] (get-in d [:grid :size])))
      (is (= "Main" (get-in d [:projectors :proj-1 :name])))
      (is (= 1 (count (get-in d [:chains :cue-chains [0 0] :items]))))
      ;; Runtime fields must NOT be in document state
      (is (nil? (:playback d)))
      (is (nil? (:backend d)))
      (is (nil? (:ui d)))
      (is (nil? (get-in d [:timing :global-clock]))))))

(deftest undoable-changed?-policy-test
  (let [changed? #(history/undoable-changed? (doc sample-state) (doc (% sample-state)))]
    (testing "live and preference changes are not undoable"
      (is (false? (changed? #(assoc-in % [:timing :bpm] 128.0))))
      (is (false? (changed? #(assoc-in % [:chains :effect-chains [1 0] :active?] true))))
      (is (false? (changed? #(assoc-in % [:chains :effect-chains [1 0] :items 0 :collapsed?] true))))
      (is (false? (changed? #(assoc-in % [:config :preview :grid-layout] [3 3])))))
    (testing "content changes are undoable"
      (is (true? (changed? edit-content)))
      (is (true? (changed? #(assoc-in % [:projectors :proj-1 :enabled?] false))))
      (is (true? (changed? #(assoc-in % [:grid :size] [4 4]))))
      (is (true? (changed? #(assoc-in % [:config :input :trigger-map] {{:note 1} {:type :trigger-cue}}))))
      (is (true? (changed? #(update-in % [:chains :cue-chains] dissoc [0 0])))))
    (testing "selected keyframe is live once a keyframe modulator exists"
      (let [with-mod (assoc-in sample-state [:chains :cue-chains [0 0] :items 0 :keyframe-modulator]
                               {:enabled? true :selected-keyframe 0 :keyframes []})]
        (is (false? (history/undoable-changed?
                     (doc with-mod)
                     (doc (assoc-in with-mod [:chains :cue-chains [0 0] :items 0 :keyframe-modulator :selected-keyframe] 2)))))
        (is (true? (history/undoable-changed?
                    (doc with-mod)
                    (doc (assoc-in with-mod [:chains :cue-chains [0 0] :items 0 :keyframe-modulator :enabled?] false)))))))))

(deftest persisted-changed?-policy-test
  (let [changed? #(history/persisted-changed? (doc sample-state) (doc (% sample-state)))]
    (is (false? (changed? #(assoc-in % [:timing :bpm] 128.0))) "BPM is live")
    (is (false? (changed? #(assoc-in % [:chains :effect-chains [1 0] :active?] true))) "effect on/off is live")
    (is (true? (changed? #(assoc-in % [:config :preview :grid-layout] [3 3]))) "prefs mark unsaved")
    (is (true? (changed? edit-content)))))

(deftest restore-document-keeps-live-and-prefs-test
  (let [snapshot-doc (doc sample-state)
        current (-> sample-state
                    (assoc-in [:timing :bpm] 140.0)
                    (assoc-in [:chains :effect-chains [1 0] :active?] true)
                    (assoc-in [:config :preview :grid-layout] [3 3])
                    edit-content)
        restored (history/restore-document current snapshot-doc)]
    (testing "content is restored"
      (is (= 1.0 (get-in restored [:chains :cue-chains [0 0] :items 0 :params :size]))))
    (testing "live state and prefs are kept"
      (is (= 140.0 (get-in restored [:timing :bpm])))
      (is (true? (get-in restored [:chains :effect-chains [1 0] :active?])))
      (is (= [3 3] (get-in restored [:config :preview :grid-layout]))))
    (testing "runtime state is untouched and dirty is not set"
      (is (= true (get-in restored [:playback :playing?])))
      (is (= true (get-in restored [:backend :idn :connected?])))
      (is (= 4.0 (get-in restored [:timing :global-clock :accumulated-beats])))
      (is (nil? (get-in restored [:project :dirty?]))))))

(deftest restore-document-grafts-live-item-fields-by-id-test
  (let [snapshot-doc (doc sample-state)
        ;; Since the snapshot: the group was collapsed, then moved behind a new item
        current (-> sample-state
                    (assoc-in [:chains :effect-chains [1 0] :items]
                              [{:id #uuid "33333333-3333-3333-3333-333333333333" :type :effect}
                               {:id group-id :type :group :collapsed? true :items []}]))
        restored (history/restore-document current snapshot-doc)]
    (is (= 1 (count (get-in restored [:chains :effect-chains [1 0] :items]))) "content restored")
    (is (true? (get-in restored [:chains :effect-chains [1 0] :items 0 :collapsed?]))
        "collapse state follows the item by :id")))

(deftest mark-clean-and-derive-dirty-test
  (let [clean (history/mark-clean sample-state)]
    (is (false? (get-in clean [:project :dirty?])))
    (testing "with a baseline, dirty means 'differs from saved'"
      (is (true? (history/derive-dirty clean (edit-content clean))))
      (is (false? (history/derive-dirty clean (assoc-in clean [:timing :bpm] 99.0))))
      (is (true? (history/derive-dirty clean (assoc-in clean [:config :preview :grid-layout] [1 1]))))
      (let [edited (edit-content clean)
            reverted (assoc-in edited [:chains :cue-chains [0 0] :items 0 :params :size] 1.0)]
        (is (false? (history/derive-dirty edited reverted)) "back to the saved content is clean")))
    (testing "without a baseline: already dirty, or changed this event"
      (is (true? (history/derive-dirty sample-state (edit-content sample-state))))
      (is (false? (history/derive-dirty sample-state sample-state)))
      (let [dirty (assoc-in sample-state [:project :dirty?] true)]
        (is (true? (history/derive-dirty dirty dirty)))))))

(deftest push-and-pop-undo-test
  (testing "pushing snapshot clears redo stack and enables undo"
    (let [h0 {:undo-stack [] :redo-stack [{:description "Old Redo"}] :transaction nil}
          s1 (history/make-snapshot {:state sample-state :description "Edit"})
          h1 (history/push-undo-snapshot h0 s1)]
      (is (true? (history/can-undo? h1)))
      (is (false? (history/can-redo? h1)))
      (is (= "Edit" (history/undo-label h1)))
      (is (= 1 (count (:undo-stack h1))))))

  (testing "pop-undo pops from undo, pushes onto redo, and returns snapshot"
    (let [s1 (history/make-snapshot {:state sample-state :description "Edit"})
          h1 (history/push-undo-snapshot {:undo-stack [] :redo-stack []} s1)
          res (history/pop-undo h1 (edit-content sample-state) {:active-tab :grid})]
      (is (some? res))
      (let [{:keys [history snapshot]} res]
        (is (= 0 (count (:undo-stack history))))
        (is (= 1 (count (:redo-stack history))))
        (is (= "Edit" (:description snapshot)))
        (is (= 1.0 (get-in snapshot [:document-state :chains :cue-chains [0 0] :items 0 :params :size])))))))

(deftest coalesce-push-test
  (let [snap #(history/make-snapshot {:state sample-state :description %})
        push (fn [h desc now] (history/push-undo-snapshot h (snap desc) 50 {:coalesce-key [:nudge] :now now}))
        h0 {:undo-stack [] :redo-stack []}]
    (testing "same key within the window merges into one step"
      (let [h (-> h0 (push "a" 1000) (push "b" 1500) (push "c" 2400))]
        (is (= 1 (count (:undo-stack h))))
        (is (= "a" (history/undo-label h)) "keeps the oldest baseline")))
    (testing "a pause longer than the window starts a new step"
      (is (= 2 (count (:undo-stack (-> h0 (push "a" 1000) (push "b" 2500)))))))
    (testing "never merges when redo is not empty"
      (let [h (assoc (push h0 "a" 1000) :redo-stack [(snap "r")])]
        (is (= 2 (count (:undo-stack (push h "b" 1100)))))))
    (testing "no key never merges"
      (is (= 2 (count (:undo-stack (-> h0
                                       (history/push-undo-snapshot (snap "a"))
                                       (history/push-undo-snapshot (snap "b"))))))))))

(deftest transaction-lifecycle-test
  (testing "transaction captures baseline on begin, and commits on mouse-up only if changed"
    (let [h0 {:undo-stack [] :redo-stack [] :transaction nil}
          h1 (history/begin-transaction h0 sample-state {:label "Drag Slider" :source-id [:slider :size]})]
      (is (some? (:transaction h1)))
      (is (= "Drag Slider" (get-in h1 [:transaction :label])))
      (let [h-no-change (history/commit-transaction h1 sample-state)]
        (is (nil? (:transaction h-no-change)))
        (is (false? (history/can-undo? h-no-change))))
      (testing "live changes alone don't create a step"
        (is (false? (history/can-undo? (history/commit-transaction h1 (assoc-in sample-state [:timing :bpm] 150.0))))))
      (let [h-committed (history/commit-transaction h1 (edit-content sample-state))]
        (is (nil? (:transaction h-committed)))
        (is (true? (history/can-undo? h-committed)))
        (is (= "Drag Slider" (history/undo-label h-committed))))))

  (testing "abort-transaction returns baseline and clears transaction"
    (let [h1 (history/begin-transaction {} sample-state {:label "Drag Slider" :source-id [:slider :size]})
          {:keys [history baseline]} (history/abort-transaction h1)]
      (is (nil? (:transaction history)))
      (is (some? baseline)))))

(deftest transaction-source-scoping-test
  (let [h1 (history/begin-transaction {} sample-state {:label "A" :source-id [:slider :a]})
        edited (edit-content sample-state)]
    (testing "any mouse-up commits the open transaction (a missed mouse-up can't leave it stuck)"
      (is (nil? (:transaction (history/commit-transaction h1 edited))))
      (is (= "A" (history/undo-label (history/commit-transaction h1 edited)))))
    (testing "begin from the same source keeps the open transaction"
      (is (= (:transaction h1) (:transaction (history/begin-transaction h1 edited {:label "A" :source-id [:slider :a]})))))
    (testing "begin from a new source commits the open one first"
      (let [h2 (history/begin-transaction h1 edited {:label "B" :source-id [:slider :b]})]
        (is (= "A" (history/undo-label h2)))
        (is (= "B" (get-in h2 [:transaction :label])))))))

(deftest stale-transaction-test
  (let [h (history/begin-transaction {} sample-state {:label "A" :source-id :x :now 0})]
    (is (not (history/stale-transaction? h 1000)))
    (is (history/stale-transaction? h (inc history/stale-transaction-ms)))
    (testing "activity keeps a long drag fresh"
      (let [touched (history/touch-transaction h 50000)]
        (is (not (history/stale-transaction? touched 100000)))
        (is (history/stale-transaction? touched (+ 50001 history/stale-transaction-ms)))))
    (is (not (history/stale-transaction? {} (inc history/stale-transaction-ms))))))

(deftest restore-effect-active-for-moved-and-new-cells-test
  (let [effect-item {:id #uuid "55555555-5555-5555-5555-555555555555" :effect-id :scale}
        base (assoc-in sample-state [:chains :effect-chains [1 0]] {:active? false :items [effect-item]})
        snapshot-doc (doc base)]
    (testing "a cell moved and switched on stays on after undoing the move"
      (let [current (-> base
                        (update-in [:chains :effect-chains] dissoc [1 0])
                        (assoc-in [:chains :effect-chains [2 0]] {:active? true :items [effect-item]}))
            restored (history/restore-document current snapshot-doc)]
        (is (true? (get-in restored [:chains :effect-chains [1 0] :active?])))))
    (testing "a cleared cell comes back switched off"
      (let [on (assoc-in base [:chains :effect-chains [1 0] :active?] true)
            current (update-in on [:chains :effect-chains] dissoc [1 0])
            restored (history/restore-document current (doc on))]
        (is (false? (get-in restored [:chains :effect-chains [1 0] :active?])))))
    (testing "an entry holding only :active? equals no entry"
      (is (false? (history/undoable-changed?
                   (doc sample-state)
                   (doc (assoc-in sample-state [:chains :effect-chains [7 7]] {:active? true}))))))))

(deftest restore-clamps-selected-keyframe-test
  (let [kf (fn [n sel] {:enabled? true :selected-keyframe sel
                        :keyframes (vec (for [i (range n)] {:position (/ i 4.0) :params {}}))})
        with-kf (fn [s m] (assoc-in s [:chains :cue-chains [0 0] :items 0 :keyframe-modulator] m))
        snapshot-doc (doc (with-kf sample-state (kf 2 0)))]
    (testing "when the keyframes changed, the snapshot's own selection is used"
      (let [restored (history/restore-document (with-kf sample-state (kf 4 3)) snapshot-doc)]
        (is (= 0 (get-in restored [:chains :cue-chains [0 0] :items 0 :keyframe-modulator :selected-keyframe])))))
    (testing "an out-of-range selection is clamped"
      (let [restored (history/restore-document (with-kf sample-state (kf 4 3))
                                               (doc (with-kf sample-state (kf 2 5))))]
        (is (= 1 (get-in restored [:chains :cue-chains [0 0] :items 0 :keyframe-modulator :selected-keyframe])))))
    (testing "current selection is kept when the keyframes are unchanged"
      (let [restored (history/restore-document (with-kf sample-state (kf 2 1)) snapshot-doc)]
        (is (= 1 (get-in restored [:chains :cue-chains [0 0] :items 0 :keyframe-modulator :selected-keyframe])))))))

(deftest duplicate-ids-keep-snapshot-live-fields-test
  (let [dup [{:id group-id :type :group :collapsed? true :items []}
             {:id group-id :type :group :collapsed? false :items []}]
        snap (assoc-in sample-state [:chains :effect-chains [1 0] :items] dup)
        current (assoc-in snap [:chains :effect-chains [1 0] :items]
                          (mapv #(assoc % :collapsed? true) dup))
        restored (history/restore-document current (doc snap))]
    (is (= [true false] (mapv :collapsed? (get-in restored [:chains :effect-chains [1 0] :items]))))))

(deftest backfill-history-test
  (let [h (-> {:undo-stack [] :redo-stack []}
              (history/push-undo-snapshot (history/make-snapshot {:state sample-state :description "a"}))
              (assoc :redo-stack [(history/make-snapshot {:state sample-state :description "r"})]))
        add-p #(assoc-in % [:projectors :new] {:name "New"})
        h' (history/backfill-history h add-p)]
    (is (every? #(= "New" (get-in % [:document-state :projectors :new :name]))
                (concat (:undo-stack h') (:redo-stack h'))))
    (is (= "New" (get-in (history/backfill-baseline (history/mark-clean sample-state) add-p)
                         [:project :saved-document :projectors :new :name])))))