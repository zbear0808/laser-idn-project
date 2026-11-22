(ns laser-show.events.list-handlers-test
  "Unit tests for list event handlers.
   
   Tests the hierarchical list UI state management including
   selection, drag-drop, and rename operations."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.list :as list-handlers]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]))


;; Selection Tests


(deftest select-item-single-test
  (testing "Single click replaces selection"
    (let [state {:list-ui {:test-list {:selected-ids #{:a :b}
                                       :last-selected-id :b}}}
          event {:event/type :list/select-item
                 :component-id :test-list
                 :item-id :c
                 :mode :single
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:c} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :c (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest select-item-ctrl-toggle-on-test
  (testing "Ctrl+click adds to selection"
    (let [state {:list-ui {:test-list {:selected-ids #{:a}
                                       :last-selected-id :a}}}
          event {:event/type :list/select-item
                 :component-id :test-list
                 :item-id :b
                 :mode :ctrl
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a :b} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :b (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest select-item-ctrl-toggle-off-test
  (testing "Ctrl+click removes from selection"
    (let [state {:list-ui {:test-list {:selected-ids #{:a :b}
                                       :last-selected-id :b}}}
          event {:event/type :list/select-item
                 :component-id :test-list
                 :item-id :b
                 :mode :ctrl
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :b (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest select-item-shift-range-test
  (testing "Shift+click uses pre-computed range"
    (let [state {:list-ui {:test-list {:selected-ids #{:a}
                                       :last-selected-id :a}}}
          event {:event/type :list/select-item
                 :component-id :test-list
                 :item-id :d
                 :mode :shift
                 :selected-ids-override #{:a :b :c :d}
                 :last-id-override :a
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a :b :c :d} (get-in result [:state :list-ui :test-list :selected-ids])))
      ;; Shift preserves last-selected-id from override
      (is (= :a (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest select-item-ctrl-shift-range-test
  (testing "Ctrl+Shift+click applies unioned range and preserves anchor"
    (let [state {:list-ui {:test-list {:selected-ids #{:z :a}
                                       :last-selected-id :a}}}
          event {:event/type :list/select-item
                 :component-id :test-list
                 :item-id :d
                 :mode :shift
                 :selected-ids-override #{:z :a :b :c :d}
                 :last-id-override :a
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:z :a :b :c :d} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :a (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest select-item-shift-state-items-range-test
  (let [items [{:id :item-1} {:id :item-2} {:id :item-3} {:id :item-4}
               {:id :item-5} {:id :item-6} {:id :item-7} {:id :item-8}
               {:id :item-9} {:id :item-10}]
        base-state {:chains {:cue-chains {[0 0] {:items items}}}
                    :list-ui {[:cue-chain 0 0] {:selected-ids #{:item-10}
                                                :last-selected-id :item-10}}}]
    (testing "Shift+click bottom-to-top resolves range from state items"
      (let [event {:event/type :list/select-item
                   :component-id [:cue-chain 0 0]
                   :item-id :item-2
                   :mode :shift
                   :items-path [:chains :cue-chains [0 0] :items]
                   :state base-state}
            result (list-handlers/handle event)]
        (is (= #{:item-2 :item-3 :item-4 :item-5 :item-6 :item-7 :item-8 :item-9 :item-10}
               (get-in result [:state :list-ui [:cue-chain 0 0] :selected-ids])))
        ;; Anchor remains :item-10
        (is (= :item-10 (get-in result [:state :list-ui [:cue-chain 0 0] :last-selected-id])))))

    (testing "Shift+click top-to-bottom resolves range from state items"
      (let [top-anchor-state (assoc-in base-state [:list-ui [:cue-chain 0 0]]
                                       {:selected-ids #{:item-1}
                                        :last-selected-id :item-1})
            event {:event/type :list/select-item
                   :component-id [:cue-chain 0 0]
                   :item-id :item-5
                   :mode :shift
                   :items-path [:chains :cue-chains [0 0] :items]
                   :state top-anchor-state}
            result (list-handlers/handle event)]
        (is (= #{:item-1 :item-2 :item-3 :item-4 :item-5}
               (get-in result [:state :list-ui [:cue-chain 0 0] :selected-ids])))
        (is (= :item-1 (get-in result [:state :list-ui [:cue-chain 0 0] :last-selected-id])))))

    (testing "Ctrl+Shift+click unions range with existing selection"
      (let [multi-state (assoc-in base-state [:list-ui [:cue-chain 0 0]]
                                  {:selected-ids #{:extra :item-10}
                                   :last-selected-id :item-10})
            event {:event/type :list/select-item
                   :component-id [:cue-chain 0 0]
                   :item-id :item-8
                   :mode :ctrl-shift
                   :items-path [:chains :cue-chains [0 0] :items]
                   :state multi-state}
            result (list-handlers/handle event)]
        (is (= #{:extra :item-8 :item-9 :item-10}
               (get-in result [:state :list-ui [:cue-chain 0 0] :selected-ids])))
        (is (= :item-10 (get-in result [:state :list-ui [:cue-chain 0 0] :last-selected-id])))))))


(deftest select-item-shift-with-all-ids-test
  (let [all-ids [:id-1 :id-2 :id-3 :id-4 :id-5 :id-6 :id-7 :id-8]
        base-state {:list-ui {:my-list {:selected-ids #{:id-2}
                                        :last-selected-id :id-2}}}]
    (testing "Shift+click top-to-bottom using explicit :all-ids vector"
      (let [event {:event/type :list/select-item
                   :component-id :my-list
                   :item-id :id-6
                   :mode :shift
                   :all-ids all-ids
                   :state base-state}
            result (list-handlers/handle event)]
        (is (= #{:id-2 :id-3 :id-4 :id-5 :id-6}
               (get-in result [:state :list-ui :my-list :selected-ids])))
        (is (= :id-2 (get-in result [:state :list-ui :my-list :last-selected-id])))))

    (testing "Shift+click bottom-to-top using explicit :all-ids vector"
      (let [bottom-state (assoc-in base-state [:list-ui :my-list]
                                   {:selected-ids #{:id-7}
                                    :last-selected-id :id-7})
            event {:event/type :list/select-item
                   :component-id :my-list
                   :item-id :id-3
                   :mode :shift
                   :all-ids all-ids
                   :state bottom-state}
            result (list-handlers/handle event)]
        (is (= #{:id-3 :id-4 :id-5 :id-6 :id-7}
               (get-in result [:state :list-ui :my-list :selected-ids])))
        (is (= :id-7 (get-in result [:state :list-ui :my-list :last-selected-id])))))))


(deftest select-item-shift-fallback-to-component-id-test
  (let [items [{:id :cue-1} {:id :cue-2} {:id :cue-3} {:id :cue-4} {:id :cue-5}]
        base-state {:chains {:cue-chains {[1 2] {:items items}}}
                    :list-ui {[:cue-chain 1 2] {:selected-ids #{:cue-5}
                                                :last-selected-id :cue-5}}}]
    (testing "Shift+click resolves range directly from component-id in state without items-path or all-ids"
      (let [event {:event/type :list/select-item
                   :component-id [:cue-chain 1 2]
                   :item-id :cue-2
                   :mode :shift
                   :state base-state}
            result (list-handlers/handle event)]
        (is (= #{:cue-2 :cue-3 :cue-4 :cue-5}
               (get-in result [:state :list-ui [:cue-chain 1 2] :selected-ids])))
        (is (= :cue-5 (get-in result [:state :list-ui [:cue-chain 1 2] :last-selected-id])))))))


(deftest select-item-cue-chain-clears-item-effects-test
  (testing "Selecting in cue-chain clears item-effects selection"
    (let [state {:list-ui {[:cue-chain 0 0] {:selected-ids #{:preset-1}
                                              :last-selected-id :preset-1}
                           [:item-effects 0 0] {:selected-ids #{:effect-1 :effect-2}
                                                :last-selected-id :effect-2}}}
          event {:event/type :list/select-item
                 :component-id [:cue-chain 0 0]
                 :item-id :preset-2
                 :mode :single
                 :state state}
          result (list-handlers/handle event)]
      ;; Cue chain selection updated
      (is (= #{:preset-2} (get-in result [:state :list-ui [:cue-chain 0 0] :selected-ids])))
      ;; Item effects selection cleared
      (is (= #{} (get-in result [:state :list-ui [:item-effects 0 0] :selected-ids])))
      (is (nil? (get-in result [:state :list-ui [:item-effects 0 0] :last-selected-id]))))))


(deftest select-all-test
  (testing "Select all sets all IDs"
    (let [state {:list-ui {:test-list {:selected-ids #{}}}}
          event {:event/type :list/select-all
                 :component-id :test-list
                 :all-ids [:a :b :c :d]
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a :b :c :d} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :a (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest select-all-state-items-test
  (testing "Select all resolves IDs from items-path in state"
    (let [state {:chains {:cue-chains {[0 0] {:items [{:id :x} {:id :y} {:id :z}]}}}
                 :list-ui {[:cue-chain 0 0] {:selected-ids #{}}}}
          event {:event/type :list/select-all
                 :component-id [:cue-chain 0 0]
                 :items-path [:chains :cue-chains [0 0] :items]
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:x :y :z} (get-in result [:state :list-ui [:cue-chain 0 0] :selected-ids])))
      (is (= :x (get-in result [:state :list-ui [:cue-chain 0 0] :last-selected-id]))))))


(deftest clear-selection-test
  (testing "Clear selection empties selection and cancels rename"
    (let [state {:list-ui {:test-list {:selected-ids #{:a :b}
                                       :last-selected-id :b
                                       :renaming-id :a}}}
          event {:event/type :list/clear-selection
                 :component-id :test-list
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (nil? (get-in result [:state :list-ui :test-list :last-selected-id])))
      (is (nil? (get-in result [:state :list-ui :test-list :renaming-id]))))))


;; Drag-Drop Tests


(deftest start-drag-selected-item-test
  (testing "Dragging selected item drags all selected"
    (let [state {:list-ui {:test-list {:selected-ids #{:a :b}
                                       :last-selected-id :b}}}
          event {:event/type :list/start-drag
                 :component-id :test-list
                 :item-id :a
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a :b} (get-in result [:state :list-ui :test-list :dragging-ids])))
      (is (= #{:a :b} (get-in result [:state :list-ui :test-list :selected-ids]))))))


(deftest start-drag-unselected-item-test
  (testing "Dragging unselected item selects and drags only that item"
    (let [state {:list-ui {:test-list {:selected-ids #{:a :b}
                                       :last-selected-id :b}}}
          event {:event/type :list/start-drag
                 :component-id :test-list
                 :item-id :c
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:c} (get-in result [:state :list-ui :test-list :dragging-ids])))
      (is (= #{:c} (get-in result [:state :list-ui :test-list :selected-ids])))
      (is (= :c (get-in result [:state :list-ui :test-list :last-selected-id]))))))


(deftest update-drop-target-test
  (testing "Update drop target sets target and position"
    (let [state {:list-ui {:test-list {}}}
          event {:event/type :list/update-drop-target
                 :component-id :test-list
                 :target-id :b
                 :drop-position :before
                 :state state}
          result (list-handlers/handle event)]
      (is (= :b (get-in result [:state :list-ui :test-list :drop-target-id])))
      (is (= :before (get-in result [:state :list-ui :test-list :drop-position]))))))


(deftest clear-drag-test
  (testing "Clear drag resets drag state"
    (let [state {:list-ui {:test-list {:dragging-ids #{:a}
                                       :drop-target-id :b
                                       :drop-position :before}}}
          event {:event/type :list/clear-drag
                 :component-id :test-list
                 :state state}
          result (list-handlers/handle event)]
      (is (nil? (get-in result [:state :list-ui :test-list :dragging-ids])))
      (is (nil? (get-in result [:state :list-ui :test-list :drop-target-id])))
      (is (nil? (get-in result [:state :list-ui :test-list :drop-position]))))))


(deftest perform-drop-no-items-test
  (testing "Perform drop with no items just clears drag state"
    (let [state {:list-ui {:test-list {:dragging-ids #{:a}
                                       :drop-target-id :b
                                       :drop-position :before}}
                 :chains {:effect-chains {[0 0] {:items []}}}}
          event {:event/type :list/perform-drop
                 :component-id :test-list
                 :dragging-ids #{:a}
                 :target-id :b
                 :drop-position :before
                 :on-change-event :chain/set-items
                 :on-change-params {:domain :effect-chains :entity-key [0 0]}
                 :items-key :items
                 :state state}
          result (list-handlers/handle event)]
      ;; Drag state cleared
      (is (nil? (get-in result [:state :list-ui :test-list :dragging-ids])))
      ;; No dispatch since no items
      (is (nil? (:dispatch result))))))


(deftest perform-drop-valid-items-test
  (testing "Perform drop with valid items dispatches change event"
    (let [id-a (random-uuid)
          id-b (random-uuid)
          id-c (random-uuid)
          item-a {:id id-a :effect-id :test :enabled? true}
          item-b {:id id-b :effect-id :test :enabled? true}
          item-c {:id id-c :effect-id :test :enabled? true}
          state {:list-ui {:test-list {:dragging-ids #{id-a}
                                       :drop-target-id id-c
                                       :drop-position :before}}
                 :chains {:effect-chains {[0 0] {:items [item-a item-b item-c]}}}}
          event {:event/type :list/perform-drop
                 :component-id :test-list
                 :dragging-ids #{id-a}
                 :target-id id-c
                 :drop-position :before
                 :on-change-event :chain/set-items
                 :on-change-params {:domain :effect-chains :entity-key [0 0]}
                 :items-key :items
                 :state state}
          result (list-handlers/handle event)]
      ;; Drag state cleared
      (is (nil? (get-in result [:state :list-ui :test-list :dragging-ids])))
      ;; Dispatch event generated
      (is (= :chain/set-items (get-in result [:dispatch :event/type])))
      (is (= :effect-chains (get-in result [:dispatch :domain])))
      (is (= [0 0] (get-in result [:dispatch :entity-key])))
      ;; Items reordered - item-a moved before item-c
      (let [new-items (get-in result [:dispatch :items])]
        (is (= 3 (count new-items)))
        ;; Order should be: b, a, c (a moved from position 0 to before c)
        (is (= [id-b id-a id-c] (mapv :id new-items)))))))


;; Rename Tests


(deftest start-rename-test
  (testing "Start rename sets renaming-id"
    (let [state {:list-ui {:test-list {}}}
          event {:event/type :list/start-rename
                 :component-id :test-list
                 :item-id :a
                 :state state}
          result (list-handlers/handle event)]
      (is (= :a (get-in result [:state :list-ui :test-list :renaming-id]))))))


(deftest cancel-rename-test
  (testing "Cancel rename clears renaming-id"
    (let [state {:list-ui {:test-list {:renaming-id :a}}}
          event {:event/type :list/cancel-rename
                 :component-id :test-list
                 :state state}
          result (list-handlers/handle event)]
      (is (nil? (get-in result [:state :list-ui :test-list :renaming-id]))))))


;; Edge Cases


(deftest empty-component-state-test
  (testing "Handlers work with missing component state"
    (let [state {:list-ui {}}
          event {:event/type :list/select-item
                 :component-id :new-list
                 :item-id :a
                 :mode :single
                 :state state}
          result (list-handlers/handle event)]
      (is (= #{:a} (get-in result [:state :list-ui :new-list :selected-ids]))))))


(deftest perform-drop-with-explicit-items-path-test
  (testing "Perform drop uses explicit items-path when provided"
    (let [id-a (random-uuid)
          id-b (random-uuid)
          item-a {:id id-a :effect-id :test :enabled? true}
          item-b {:id id-b :effect-id :test :enabled? true}
          state {:list-ui {:test-list {:dragging-ids #{id-a}}}
                 :custom {:path {:items [item-a item-b]}}}
          event {:event/type :list/perform-drop
                 :component-id :test-list
                 :dragging-ids #{id-a}
                 :target-id id-b
                 :drop-position :after
                 :on-change-event :custom/set-items
                 :on-change-params {}
                 :items-key :items
                 :items-path [:custom :path :items]
                 :state state}
          result (list-handlers/handle event)]
      ;; Should dispatch with items from custom path
      (is (= :custom/set-items (get-in result [:dispatch :event/type])))
      (let [new-items (get-in result [:dispatch :items])]
        ;; item-a moved after item-b, so order is b, a
        (is (= [id-b id-a] (mapv :id new-items)))))))

(deftest a-list-without-a-type-copies-nothing-test
  ;; 4b: a list's type comes from its component id (clipboard/list-type),
  ;; and the timeline's track sidebar has none: an event's :clipboard-type
  ;; is ignored
  (let [state {:chains {:cue-chains {[0 0] {:tracks [{:id :t1 :type :track :name "T1"}]}}}
               :list-ui {[:timeline-tracks 0 0] {:selected-ids #{:t1}}}}
        result (list-handlers/handle {:event/type :list/copy-selected
                                      :component-id [:timeline-tracks 0 0]
                                      :items-path [:chains :cue-chains [0 0] :tracks]
                                      :clipboard-type :timeline-tracks
                                      :state state})]
    (is (= {:state state} result) "no clip, no OS write")))


;; Paste into a list of effects (a projector's calibration chain)


(deftest paste-effects-into-an-item-effects-list-test
  (let [state {:chains {:projector-effects {:p1 {:items [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                                         :effect-id :rgb-curves :enabled? true :params {}}]}}}
               :list-ui {}}
        paste (fn [clip] (list-handlers/handle {:event/type :list/paste-items
                                                :component-id [:projector-effects :p1]
                                                :items-path [:chains :projector-effects :p1 :items]
                                                :on-change-event :chain/set-items
                                                :on-change-params {:domain :projector-effects :entity-key :p1}
                                                :state (assoc-in state [:ui :clipboard] clip)}))]
    (testing "4b: a clip of a type nothing writes (:effect-chain) pastes nothing"
      (is (nil? (:dispatch (paste {:type :effect-chain :copied-at 1
                                   :effects [{:effect-id :axis-transform :enabled? true :params {}}]})))))
    (testing "effects copied from another list of effects paste too"
      (is (= [:rgb-curves :corner-pin]
             (mapv :effect-id (:items (:dispatch (paste (clipboard/list-clip :item-effects [{:effect-id :corner-pin :enabled? true :params {}}] 1))))))))
    (testing "a cue's presets never land in a list of effects"
      (is (nil? (:dispatch (paste (clipboard/list-clip :cue-chain-items [{:type :preset :preset-id :circle :id #uuid "00000000-0000-0000-0000-000000000002"}] 1))))))))


;; Copy and paste through the one clipboard (keyboard plan Phase 4, Step 4a)


(def ^:private id-a #uuid "00000000-0000-0000-0000-0000000000aa")
(def ^:private id-b #uuid "00000000-0000-0000-0000-0000000000bb")

(defn- cue-state
  "Cue [0 0] holding a circle (A) and a square (B), A selected; the slot
   holds clip."
  [clip]
  {:chains {:cue-chains {[0 0] {:items [{:id id-a :type :preset :preset-id :circle :effects []}
                                        {:id id-b :type :preset :preset-id :square :effects []}]}}}
   :list-ui {[:cue-chain 0 0] {:selected-ids #{id-a} :last-selected-id id-a}}
   :ui {:clipboard clip}})

(defn- copy-event [state]
  {:event/type :list/copy-selected :component-id [:cue-chain 0 0]
   :items-path [:chains :cue-chains [0 0] :items]
   :state state :time 1763650000000})

(defn- paste-event [state & {:as more}]
  (merge {:event/type :list/paste-items :component-id [:cue-chain 0 0]
          :items-path [:chains :cue-chains [0 0] :items]
          :on-change-event :chain/set-items
          :on-change-params {:domain :cue-chains :entity-key [0 0]}
          :state state :time 1763650000001 :fx/event :an-action-event}
         more))

(def ^:private triangle-clip
  {:type :cue-chain-items :copied-at 5
   :items [{:id #uuid "00000000-0000-0000-0000-0000000000cc" :type :preset :preset-id :triangle :effects []}]})

(defn- pasted-presets [result] (mapv :preset-id (get-in result [:dispatch :items])))

(deftest copy-selected-writes-the-slot-and-asks-for-an-os-write-test
  (let [result (list-handlers/handle (copy-event (cue-state nil)))
        clip (get-in result [:state :ui :clipboard])]
    (is (= :cue-chain-items (:type clip)))
    (is (= [:circle] (mapv :preset-id (:items clip))))
    (is (not= id-a (:id (first (:items clip)))) "a copy, with its own id")
    (is (= 1763650000000 (:copied-at clip)) ":copied-at is the event's :time")
    (is (= clip (:clipboard/write result)) "the OS clipboard gets the same clip")
    (is (= #{:state :clipboard/write} (set (keys result))))))

(deftest copy-selected-with-nothing-selected-does-nothing-test
  (let [state (assoc-in (cue-state triangle-clip) [:list-ui [:cue-chain 0 0] :selected-ids] #{})
        result (list-handlers/handle (copy-event state))]
    (is (= {:state state} result) "no clip, no OS write: the slot keeps what it had")))

(deftest paste-with-a-clip-reads-only-the-slot-test
  (let [result (list-handlers/handle (paste-event (cue-state triangle-clip)))]
    (is (not (contains? result :clipboard/read)))
    (is (= [:circle :triangle :square] (pasted-presets result)) "after the selection")
    (is (= "Paste Item(s)" (get-in result [:dispatch :history/description])))))

(deftest paste-with-an-empty-slot-asks-for-the-system-clipboard-test
  (let [event (paste-event (cue-state nil))
        result (list-handlers/handle event)
        then (get-in result [:clipboard/read :then])]
    (is (= #{:clipboard/read} (set (keys result))) "nothing else: no state, no paste yet")
    (is (= (dissoc event :state :time :fx/event) then) "the paste, without its co-effects")
    (is (not-any? #(contains? then %) [:state :time :fx/event]))))

(deftest paste-after-the-read-adopts-and-pastes-test
  (let [result (list-handlers/handle (paste-event (cue-state nil) :clipboard/system triangle-clip))]
    (is (= triangle-clip (get-in result [:state :ui :clipboard])) "the slot takes the read clip")
    (is (= [:circle :triangle :square] (pasted-presets result)))
    (is (not (contains? result :clipboard/read)) "one read, never two")))

(deftest paste-after-a-read-that-found-nothing-says-so-test
  (doseq [found [nil {:type :effects-cell :data {:items []} :copied-at 1}]]
    (let [result (list-handlers/handle (paste-event (cue-state nil) :clipboard/system found))]
      (is (nil? (:dispatch result)) "nothing pasted")
      (is (= "Nothing to paste here" (get-in result [:state :ui :notice :text])))
      (is (= :ui/clear-notice (get-in result [:dispatch-later :event :event/type])))
      (is (= found (get-in result [:state :ui :clipboard])) "a clip read is kept, even one this list doesn't take"))))

(deftest paste-keeps-a-clip-copied-during-the-read-test
  (let [mine {:type :cue-chain-items :copied-at 9
              :items [{:id #uuid "00000000-0000-0000-0000-0000000000dd" :type :preset :preset-id :star :effects []}]}
        result (list-handlers/handle (paste-event (cue-state mine) :clipboard/system triangle-clip))]
    (is (= mine (get-in result [:state :ui :clipboard])) "the copy made meanwhile wins")
    (is (= [:circle :star :square] (pasted-presets result)))))

(deftest paste-of-a-clip-this-list-does-not-take-does-nothing-test
  (let [state (cue-state {:type :item-effects :copied-at 1 :effects [{:effect-id :scale :params {}}]})]
    (is (= {:state state} (list-handlers/handle (paste-event state))) "no read, no notice, no paste")))


;; Each list copies and pastes its own kind, from its id (keyboard plan
;; Phase 4, Step 4b; probes p7, p13)


(def ^:private fx-id #uuid "00000000-0000-0000-0000-0000000000ee")

(defn- lists-state
  "cue-state, with cue [0 0]'s first item holding a :scale effect (selected
   in its effects list) and effects cell [0 0] a :rotate."
  [clip]
  (-> (cue-state clip)
      (assoc-in [:chains :cue-chains [0 0] :items 0 :effects] [{:id fx-id :effect-id :scale :enabled? true :params {}}])
      (assoc-in [:chains :effect-chains [0 0]] {:items [{:id #uuid "00000000-0000-0000-0000-0000000000ef"
                                                         :effect-id :rotate :enabled? true :params {}}]
                                                :active? true})
      (assoc-in [:list-ui [:item-effects 0 0]] {:selected-ids #{fx-id} :last-selected-id fx-id})))

(defn- effect-list-paste [component-id items-path on-change]
  (merge {:event/type :list/paste-items :component-id component-id :items-path items-path
          ;; what every list but a projector's sent before 4b: ignored now
          :clipboard-type :cue-chain-items}
         on-change))

(def ^:private item-fx-paste
  (effect-list-paste [:item-effects 0 0] [:chains :cue-chains [0 0] :items 0 :effects]
                     {:on-change-event :cue-chain/set-item-effects :on-change-params {:col 0 :row 0 :item-path [0]}}))

(def ^:private fx-chain-paste
  (effect-list-paste [:effect-chain 0 0] [:chains :effect-chains [0 0] :items]
                     {:on-change-event :chain/set-items :on-change-params {:domain :effect-chains :entity-key [0 0]}}))

(deftest copy-takes-its-type-from-the-list-test
  (let [s (lists-state nil)
        copy (fn [component-id items-path]
               (get-in (list-handlers/handle {:event/type :list/copy-selected :component-id component-id
                                              :items-path items-path :clipboard-type :cue-chain-items :state s})
                       [:state :ui :clipboard]))]
    (is (= :cue-chain-items (:type (copy [:cue-chain 0 0] [:chains :cue-chains [0 0] :items]))))
    (let [clip (copy [:item-effects 0 0] [:chains :cue-chains [0 0] :items 0 :effects])]
      (is (= :item-effects (:type clip)) "an item's effects copy as effects, whatever the event says")
      (is (= [:scale] (mapv :effect-id (:effects clip)))))))

(deftest paste-refuses-another-lists-kind-test
  ;; p7, p13: before 4b a cue's preset landed in effect chain [0 0] and in
  ;; item 0's effects, and an effect among the cue's items
  (let [cue-items (list-handlers/handle (copy-event (lists-state nil)))
        clip (get-in cue-items [:state :ui :clipboard])]
    (doseq [paste [item-fx-paste fx-chain-paste]]
      (let [s (lists-state clip)]
        (is (= {:state s} (list-handlers/handle (assoc paste :state s)))
            (str "cue items into " (:component-id paste) ": no paste, no read"))))
    (let [s (lists-state nil)
          fx-clip (get-in (list-handlers/handle {:event/type :list/copy-selected :component-id [:item-effects 0 0]
                                                 :items-path [:chains :cue-chains [0 0] :items 0 :effects]
                                                 :state s})
                          [:state :ui :clipboard])
          s (assoc-in s [:ui :clipboard] fx-clip)]
      (is (= {:state s} (list-handlers/handle (paste-event s))) "effects into the cue list: nothing"))))

(deftest paste-between-effect-lists-test
  (let [s (lists-state nil)
        fx-clip (get-in (list-handlers/handle {:event/type :list/copy-selected :component-id [:item-effects 0 0]
                                               :items-path [:chains :cue-chains [0 0] :items 0 :effects]
                                               :state s})
                        [:state :ui :clipboard])
        result (list-handlers/handle (assoc fx-chain-paste :state (assoc-in s [:ui :clipboard] fx-clip)))]
    (is (= [:rotate :scale] (mapv :effect-id (get-in result [:dispatch :items])))
        "an item's effect pastes into the effects grid's chain")))


;; D9 A: a copied cell pastes into a list (Step 4b; probe p6)


(deftest paste-a-copied-cell-into-a-cue-list-test
  ;; p6: before Step 4b a list's ⌘V of a copied cell pasted nothing (1 → 1)
  (let [cell {:items [{:id #uuid "00000000-0000-0000-0000-0000000000c1" :type :preset :preset-id :triangle :effects []}
                      {:id #uuid "00000000-0000-0000-0000-0000000000c2" :type :group :name "G"
                       :items [{:id #uuid "00000000-0000-0000-0000-0000000000c3" :type :preset :preset-id :star :effects []}]}]
              :destination-zone {:zone-group-id :all}}
        result (list-handlers/handle (paste-event (cue-state {:type :cue-chain :data cell :copied-at 3})))
        pasted (get-in result [:dispatch :items])]
    (is (= [:circle :triangle nil :square] (mapv :preset-id pasted)) "the cell's items, after the selection")
    (is (= :star (get-in pasted [2 :items 0 :preset-id])) "a group comes with its items")
    (is (not-any? (set (map :id (:items cell))) (map :id pasted)) "with ids of their own")
    (is (= "Paste Item(s)" (get-in result [:dispatch :history/description])))))

(deftest paste-a-copied-effects-cell-into-an-effect-list-test
  (let [cell {:items [{:id #uuid "00000000-0000-0000-0000-0000000000d1" :effect-id :hue-shift :enabled? true :params {}}]
              :active? true}]
    (doseq [paste [item-fx-paste fx-chain-paste]]
      (let [result (list-handlers/handle (assoc paste :state (lists-state {:type :effects-cell :data cell :copied-at 4})))
            pasted (get-in result [:dispatch :items])]
        (is (= :hue-shift (:effect-id (last pasted))) (str "the cell's effects into " (:component-id paste)))
        (is (not= (:id (first (:items cell))) (:id (last pasted))) "with an id of its own")))))

(deftest paste-a-timeline-cell-into-a-list-does-nothing-test
  (let [cell {:editor-type :timeline :tracks [{:id #uuid "00000000-0000-0000-0000-0000000000a1" :type :track}]
              :items [{:id #uuid "00000000-0000-0000-0000-0000000000a2" :type :preset :preset-id :circle
                       :track-id #uuid "00000000-0000-0000-0000-0000000000a1" :timeline/start 0.0 :timeline/duration 4.0}]}
        state (cue-state {:type :cue-chain :data cell :copied-at 3})]
    (is (= {:state state} (list-handlers/handle (paste-event state))) "a timeline's clips aren't a cue list's items")
    (testing "and an effects cell's effects never land in a cue list, nor a cue cell's items in an effect list"
      (let [s (cue-state {:type :effects-cell :data {:items [{:id fx-id :effect-id :scale}]} :copied-at 1})]
        (is (= {:state s} (list-handlers/handle (paste-event s)))))
      (let [s (lists-state {:type :cue-chain :data {:items [{:id id-a :type :preset :preset-id :circle}]} :copied-at 1})]
        (is (= {:state s} (list-handlers/handle (assoc fx-chain-paste :state s))))))))


(deftest paste-regenerates-ids-at-every-depth-test
  ;; p15: before Step 4b a pasted item's effects kept their ids, in a group too
  (let [e-id #uuid "00000000-0000-0000-0000-0000000000e1"
        g-id #uuid "00000000-0000-0000-0000-0000000000e2"
        p-id #uuid "00000000-0000-0000-0000-0000000000e3"
        clip {:type :cue-chain-items :copied-at 1
              :items [{:id g-id :type :group :name "G"
                       :items [{:id p-id :type :preset :preset-id :star
                                :effects [{:id e-id :effect-id :scale :params {}}]}]}]}
        pasted (get-in (list-handlers/handle (paste-event (cue-state clip))) [:dispatch :items 1])]
    (is (= :star (get-in pasted [:items 0 :preset-id])))
    (is (not-any? #{g-id p-id e-id} [(:id pasted) (get-in pasted [:items 0 :id]) (get-in pasted [:items 0 :effects 0 :id])])
        "the group, its preset and the preset's effect all get new ids")))


;; Cut is one step (keyboard plan Phase 4, Step 4c)


(defn- cut-event [state]
  {:event/type :list/cut-selected :component-id [:cue-chain 0 0]
   :items-path [:chains :cue-chains [0 0] :items]
   :on-change-event :chain/set-items
   :on-change-params {:domain :cue-chains :entity-key [0 0]}
   :state state :time 1763650000002})

(deftest cut-selected-is-one-state-update-test
  ;; p16: before Step 4c ⌘X dispatched a copy, then a delete, and the undo
  ;; label was "Delete Item(s)"
  (let [state (cue-state triangle-clip)
        result (list-handlers/handle (cut-event state))
        clip (get-in result [:state :ui :clipboard])]
    (is (= #{:state :clipboard/write :dispatch} (set (keys result))))
    (is (= [:circle] (mapv :preset-id (:items clip))) "the cut item is in the slot, before the delete runs")
    (is (= 1763650000002 (:copied-at clip)))
    (is (= clip (:clipboard/write result)) "and on its way to the OS clipboard")
    (is (= {:selected-ids #{} :last-selected-id nil :renaming-id nil}
           (get-in result [:state :list-ui [:cue-chain 0 0]])) "the selection cleared in the same state")
    (is (= {:event/type :chain/set-items :domain :cue-chains :entity-key [0 0]
            :items [{:id id-b :type :preset :preset-id :square :effects []}]
            :history/description "Cut Item(s)"}
           (:dispatch result)))))

(deftest cut-with-nothing-to-cut-does-nothing-test
  (let [state (assoc-in (cue-state triangle-clip) [:list-ui [:cue-chain 0 0] :selected-ids] #{})]
    (is (= {:state state} (list-handlers/handle (cut-event state))) "nothing selected"))
  (let [state (-> (cue-state triangle-clip)
                  (assoc-in [:list-ui [:timeline-tracks 0 0]] {:selected-ids #{id-a}}))]
    (is (= {:state state} (list-handlers/handle (assoc (cut-event state) :component-id [:timeline-tracks 0 0])))
        "a list without a type: nothing copied, so nothing deleted")))

(deftest cut-selected-is-one-undo-step-test
  (let [s0 (-> (domains/build-initial-state)
               (merge (select-keys (cue-state nil) [:list-ui]))
               (assoc-in [:chains :cue-chains [0 0]] (get-in (cue-state nil) [:chains :cue-chains [0 0]]))
               (assoc :history {:undo-stack [] :redo-stack [] :transaction nil}))
        effects (events/handle-event-pipeline (cut-event s0))
        s1 (:state effects)
        s2 (:state (events/handle-event-pipeline (assoc (:dispatch effects) :state s1)))]
    (is (= [:square] (mapv :preset-id (get-in s2 [:chains :cue-chains [0 0] :items]))))
    (is (= [:circle] (mapv :preset-id (get-in s2 [:ui :clipboard :items]))))
    (is (= 1 (count (get-in s2 [:history :undo-stack]))) "one undo entry")
    (is (= "Cut Item(s)" (history/undo-label (:history s2))))
    (let [s3 (:state (events/handle-event-pipeline {:event/type :edit/undo :state s2}))]
      (is (= [:circle :square] (mapv :preset-id (get-in s3 [:chains :cue-chains [0 0] :items])))
          "one undo brings the items back")
      (is (= [:circle] (mapv :preset-id (get-in s3 [:ui :clipboard :items]))) "and the clip stays"))))
