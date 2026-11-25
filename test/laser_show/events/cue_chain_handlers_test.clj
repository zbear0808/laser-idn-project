(ns laser-show.events.cue-chain-handlers-test
  "Tests for cue chain event handlers focusing on complex curve and spatial logic.
   
   Effect-level operations (curve points, spatial params) now use :chain/* events
   from chain.clj. Cue-chain specific operations (add-preset, etc.) use :cue-chain/*."
  (:require
   [clojure.test :refer [deftest is testing]]
   [laser-show.animation.effects :as registry]
   [laser-show.animation.effects.intensity]
   [laser-show.animation.effects.shape]
   [laser-show.events.core :as events]
   [laser-show.events.handlers.history :as history-handlers]
   [laser-show.state.history :as history]
   [laser-show.events.handlers.cue-chain :as cue-chain]
   [laser-show.events.handlers.chain :as chain]
   [laser-show.events.handlers.list :as list]
   [laser-show.views.components.list :as list-comp]
   [laser-show.views.dialogs.cue-chain-editor :as cue-chain-editor]
   [laser-show.state.domains :refer [build-initial-state]]))


;; Test Fixtures


(def base-state
  "Base state using actual app initial state structure."
  (build-initial-state))

(def sample-state-with-item
  "State with a single cue chain item."
  ;; FLATTENED: Dialog fields live alongside :open?, not under :data
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]]
                {:items [{:preset-id :circle
                          :params {:size 100}
                          :effects []}]})
      (assoc-in [:ui :dialogs :cue-chain-editor] {:open? false :col 0 :row 0 :selected-paths #{}})
      (assoc-in [:cue-chain-editor :cell] [0 0])
      (assoc-in [:cue-chain-editor :selected-paths] #{})
      (assoc-in [:cue-chain-editor :clipboard] nil)))

(def sample-state-with-effect
  "State with a cue chain item that has an RGB curves effect."
  ;; FLATTENED: Dialog fields live alongside :open?, not under :data
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]]
                {:items [{:preset-id :circle
                          :params {:size 100}
                          :effects [{:id (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174000")
                                     :effect-id :rgb-curves
                                     :enabled? true
                                     :params {:r-curve-points [[0.0 0.0] [1.0 1.0]]
                                              :g-curve-points [[0.0 0.0] [1.0 1.0]]
                                              :b-curve-points [[0.0 0.0] [1.0 1.0]]}}]}]})
      (assoc-in [:ui :dialogs :cue-chain-editor] {:open? false :col 0 :row 0})
      (assoc-in [:cue-chain-editor :cell] [0 0])))

(def sample-state-with-spatial-effect
  "State with a cue chain item that has a spatial effect."
  ;; FLATTENED: Dialog fields live alongside :open?, not under :data
  (-> base-state
      (assoc-in [:chains :cue-chains [0 0]]
                {:items [{:preset-id :circle
                          :effects [{:id (java.util.UUID/fromString "123e4567-e89b-12d3-a456-426614174001")
                                     :effect-id :translate
                                     :enabled? true
                                     :params {:x 0.0 :y 0.0}}]}]})
      (assoc-in [:ui :dialogs :cue-chain-editor] {:open? false :col 0 :row 0})
      (assoc-in [:cue-chain-editor :cell] [0 0])))


;; Curve Point Addition Tests (using chain/handle with :chain/* events)


(deftest handle-add-curve-point-basic
  (testing "Adding curve point to middle of curve"
    (let [;; For cue chain item effects, effect-path is relative to items
          ;; Full path: items -> [0] -> :effects -> [0]
          ;; So effect-path = [0 :effects 0]
          event {:event/type :chain/add-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r
                 :x 0.5
                 :y 0.25
                 :state sample-state-with-effect}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      (is (= 3 (count new-points)))
      (is (= [0.0 0.0] (first new-points)))
      (is (= [0.5 0.25] (second new-points)))
      (is (= [1.0 1.0] (last new-points)))
      ;; Should maintain sort order
      (is (= new-points (sort-by first new-points))))))

(deftest handle-add-curve-point-sorting
  (testing "Added curve point is sorted by x value"
    (let [event {:event/type :chain/add-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :g
                 :x 0.8
                 :y 0.4
                 :state sample-state-with-effect}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :g-curve-points])]
      (is (= [[0.0 0.0] [0.8 0.4] [1.0 1.0]] new-points)))))

(deftest handle-add-curve-point-multiple
  (testing "Adding multiple curve points maintains sort order"
    (let [state1 (chain/handle {:event/type :chain/add-curve-point
                                :domain :cue-chains
                                :entity-key [0 0]
                                :effect-path [0 :effects 0]
                                :channel :b :x 0.4 :y 0.2
                                :state sample-state-with-effect})
          state2 (chain/handle {:event/type :chain/add-curve-point
                                :domain :cue-chains
                                :entity-key [0 0]
                                :effect-path [0 :effects 0]
                                :channel :b :x 0.2 :y 0.4
                                :state (:state state1)})
          final-points (get-in state2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :b-curve-points])]
      (is (= [[0.0 0.0] [0.2 0.4] [0.4 0.2] [1.0 1.0]] final-points)))))


;; Curve Point Update Tests


(deftest handle-update-curve-point-middle
  (testing "Updating middle curve point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          event {:event/type :chain/update-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 1 :x 0.55 :y 0.4
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      (is (= [[0.0 0.0] [0.55 0.4] [1.0 1.0]] new-points)))))

(deftest handle-update-curve-point-corner-left
  (testing "Updating left corner point only changes Y"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          event {:event/type :chain/update-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 0 :x 0.2 :y 0.4
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; X should remain 0.0, only Y changes
      (is (= [0.0 0.4] (first new-points))))))

(deftest handle-update-curve-point-corner-right
  (testing "Updating right corner point only changes Y"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          event {:event/type :chain/update-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 2 :x 0.8 :y 0.6
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; X should remain 1.0, only Y changes
      (is (= [1.0 0.6] (last new-points))))))

(deftest handle-update-curve-point-resorting
  (testing "Updating point X maintains sorted order"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.25 0.25] [0.5 0.5] [0.75 0.75] [1.0 1.0]])
          event {:event/type :chain/update-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 1 :x 0.6 :y 0.25
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; Point should move to maintain sort order
      (is (= [[0.0 0.0] [0.5 0.5] [0.6 0.25] [0.75 0.75] [1.0 1.0]] new-points)))))


;; Curve Point Removal Tests


(deftest handle-remove-curve-point-middle
  (testing "Removing middle curve point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.25 0.25] [0.5 0.5] [0.75 0.75] [1.0 1.0]])
          event {:event/type :chain/remove-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 2
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      (is (= [[0.0 0.0] [0.25 0.25] [0.75 0.75] [1.0 1.0]] new-points)))))

(deftest handle-remove-curve-point-corner-left
  (testing "Cannot remove left corner point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          event {:event/type :chain/remove-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 0
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; Points should remain unchanged
      (is (= [[0.0 0.0] [0.5 0.5] [1.0 1.0]] new-points)))))

(deftest handle-remove-curve-point-corner-right
  (testing "Cannot remove right corner point"
    (let [initial-state (assoc-in sample-state-with-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points]
                                  [[0.0 0.0] [0.5 0.5] [1.0 1.0]])
          event {:event/type :chain/remove-curve-point
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :channel :r :point-idx 2
                 :state initial-state}
          result (chain/handle event)
          new-points (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :r-curve-points])]
      ;; Points should remain unchanged
      (is (= [[0.0 0.0] [0.5 0.5] [1.0 1.0]] new-points)))))


;; Spatial Parameter Update Tests


(deftest handle-update-spatial-params-center
  (testing "Updating center point spatial parameters"
    (let [param-map {:center {:x :x :y :y}}
          event {:event/type :chain/update-spatial-params
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :point-id :center :x 0.5 :y -0.3
                 :param-map param-map
                 :state sample-state-with-spatial-effect}
          result (chain/handle event)]
      (is (= 0.5 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x])))
      (is (= -0.3 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y]))))))

(deftest handle-update-spatial-params-multiple-points
  (testing "Updating with multiple point mappings"
    (let [initial-state (assoc-in sample-state-with-spatial-effect
                                  [:chains :cue-chains [0 0] :items 0 :effects 0 :params]
                                  {:x1 0.0 :y1 0.0 :x2 1.0 :y2 1.0})
          param-map {:p1 {:x :x1 :y :y1}
                     :p2 {:x :x2 :y :y2}}
          event1 {:event/type :chain/update-spatial-params
                  :domain :cue-chains
                  :entity-key [0 0]
                  :effect-path [0 :effects 0]
                  :point-id :p1 :x -0.5 :y 0.5
                  :param-map param-map
                  :state initial-state}
          result1 (chain/handle event1)
          event2 {:event/type :chain/update-spatial-params
                  :domain :cue-chains
                  :entity-key [0 0]
                  :effect-path [0 :effects 0]
                  :point-id :p2 :x 0.8 :y -0.8
                  :param-map param-map
                  :state (:state result1)}
          result2 (chain/handle event2)]
      (is (= -0.5 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x1])))
      (is (= 0.5 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y1])))
      (is (= 0.8 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x2])))
      (is (= -0.8 (get-in result2 [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y2]))))))

(deftest handle-update-spatial-params-unknown-point
  (testing "Updating unknown point ID does nothing"
    (let [param-map {:center {:x :x :y :y}}
          event {:event/type :chain/update-spatial-params
                 :domain :cue-chains
                 :entity-key [0 0]
                 :effect-path [0 :effects 0]
                 :point-id :nonexistent :x 0.5 :y -0.3
                 :param-map param-map
                 :state sample-state-with-spatial-effect}
          result (chain/handle event)]
      ;; State should remain unchanged
      (is (= 0.0 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :x])))
      (is (= 0.0 (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects 0 :params :y]))))))


;; Add Preset Tests (still using cue-chain/handle)


(deftest handle-add-preset-to-empty-cell
  (testing "Adding preset to empty cell creates cell"
    ;; FLATTENED: Dialog fields live alongside :open?, not under :data
    (let [event {:event/type :cue-chain/add-preset
                 :col 0
                 :row 0
                 :item-id :circle
                 :state {:chains {:cue-chains {}}
                         :ui {:dialogs {:cue-chain-editor {:open? false}}}
                         :project {:dirty? false}}}
          result (cue-chain/handle event)
          items (get-in result [:state :chains :cue-chains [0 0] :items])
          ;; Process the dispatch event to apply selection
          dispatch-result (when-let [dispatch-event (:dispatch result)]
                            (list/handle (assoc dispatch-event :state (:state result))))
          final-state (if dispatch-result (:state dispatch-result) (:state result))
          new-item-id (:id (first items))
          selected-ids (get-in final-state [:list-ui [:cue-chain 0 0] :selected-ids])]
      (is (= 1 (count items)))
      (is (= :circle (:preset-id (first items))))
      (is (history/derive-dirty (:state event) (:state result)))
      ;; Should auto-select the new preset (ID-based selection in list-ui)
      (is (= #{new-item-id} selected-ids)))))

(deftest handle-add-preset-to-existing-cell
  (testing "Adding preset to cell with content appends to list"
    (let [event {:event/type :cue-chain/add-preset
                 :col 0
                 :row 0
                 :item-id :wave
                 :state sample-state-with-item}
          result (cue-chain/handle event)
          items (get-in result [:state :chains :cue-chains [0 0] :items])
          ;; Process the dispatch event to apply selection
          dispatch-result (when-let [dispatch-event (:dispatch result)]
                            (list/handle (assoc dispatch-event :state (:state result))))
          final-state (if dispatch-result (:state dispatch-result) (:state result))
          new-item-id (:id (second items))
          selected-ids (get-in final-state [:list-ui [:cue-chain 0 0] :selected-ids])]
      (is (= 2 (count items)))
      (is (= :circle (:preset-id (first items))))
      (is (= :wave (:preset-id (second items))))
      ;; Should auto-select the newly added preset (ID-based selection in list-ui)
      (is (= #{new-item-id} selected-ids)))))


;; The effect bank (reactivity plan 17): a click carries the effect's id, and
;; the handler reads its definition when it runs

(deftest add-effect-from-bank-reads-the-registry-test
  (let [scale (registry/get-effect :scale)
        state (assoc-in sample-state-with-item [:chains :cue-chains [0 0] :items 0 :id] :circle)
        add (fn [event]
              (cue-chain/handle (merge {:event/type :cue-chain/add-effect-from-bank :col 0 :row 0 :owner-id :circle
                                        :state state}
                                       event)))]
    (try
      (registry/register-effect! (assoc-in scale [:parameters 0 :default] 7.0))
      (let [{:keys [state dispatch]} (add {:item-id :scale :item scale})
            [added & more] (get-in state [:chains :cue-chains [0 0] :items 0 :effects])]
        (is (= :scale (:effect-id added)))
        (is (= {:x-scale 7.0 :y-scale 1.0 :uniform? false} (:params added))
            "the definition registered when the click is handled, not one the event carries")
        (is (nil? more))
        (is (= {:event/type :list/select-item :component-id [:item-effects 0 0] :item-id (:id added) :mode :single}
               dispatch)))
      (finally
        (registry/register-effect! scale)))
    (is (= {:state state} (add {:item-id :no-such-effect})) "an id no effect has: nothing")))


;; Remove Item Tests (using cue-chain/handle with :cue-chain/set-item-effects)
;; Note: Removing effects from cue chain items is done via the list component
;; which calls :cue-chain/set-item-effects with the updated effects array.


(def ^:private with-effect
  "sample-state-with-effect, its item Circle by its id."
  (assoc-in sample-state-with-effect [:chains :cue-chains [0 0] :items 0 :id] :circle))

(deftest handle-set-item-effects
  (testing "Setting item effects replaces the effects array"
    (let [new-effects [{:id (java.util.UUID/randomUUID) :effect-id :rotate :enabled? true :params {}}]
          event {:event/type :cue-chain/set-item-effects
                 :col 0 :row 0
                 :item-id :circle
                 :items new-effects
                 :state with-effect}
          result (cue-chain/handle event)]
      (is (= 1 (count (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects]))))
      (is (= :rotate (:effect-id (first (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects])))))
      (is (history/derive-dirty (:state event) (:state result))))))

(deftest handle-set-item-effects-empty
  (testing "Setting empty effects array clears effects"
    (let [event {:event/type :cue-chain/set-item-effects
                 :col 0 :row 0
                 :item-id :circle
                 :items []
                 :state with-effect}
          result (cue-chain/handle event)]
      (is (empty? (get-in result [:state :chains :cue-chains [0 0] :items 0 :effects])))
      (is (history/derive-dirty (:state event) (:state result))))))


;; Targets (zones plan Z4): one :target on the cue, its items and groups


(def ^:private with-items
  "The cue at [0 0]: Circle, then the group Pair holding Star."
  (assoc-in sample-state-with-item [:chains :cue-chains [0 0] :items]
            [{:type :preset :id :circle :preset-id :circle :params {:size 100} :effects []}
             {:type :group :id :pair :name "Pair" :items [{:type :preset :id :star :preset-id :star :params {}}]}]))

(defn- set-item-target [state item-id target]
  (cue-chain/handle {:event/type :cue-chain/set-item-target :col 0 :row 0 :item-id item-id :target target :state state}))

(deftest set-item-target-test
  (testing "an item's own target, in normal form"
    (let [result (set-item-target with-items :circle :zone/a)]
      (is (= :zone/a (get-in result [:state :chains :cue-chains [0 0] :items 0 :target])))
      (is (history/derive-dirty with-items (:state result)) "an edit")
      (is (= [:zone/a :zone/b] (get-in (set-item-target with-items :circle [:zone/b :zone/a :zone/b])
                                       [:state :chains :cue-chains [0 0] :items 0 :target])))))
  (testing "a group and its child, each its own"
    (let [group (:state (set-item-target with-items :pair [:zone/a :zone/b]))
          child (:state (set-item-target group :star :graphics))]
      (is (= [:zone/a :zone/b] (get-in child [:chains :cue-chains [0 0] :items 1 :target])))
      (is (= :graphics (get-in child [:chains :cue-chains [0 0] :items 1 :items 0 :target])))))
  (testing "from a picker's choice (:fx/event)"
    (is (= :graphics (get-in (cue-chain/handle {:event/type :cue-chain/set-item-target :col 0 :row 0 :item-id :circle
                                                 :fx/event {:id :graphics :name "Graphics"} :state with-items})
                             [:state :chains :cue-chains [0 0] :items 0 :target]))))
  (testing "the item where it is when the choice is handled (follow-up R2): one made before a reorder sets its own item"
    (let [reordered (update-in with-items [:chains :cue-chains [0 0] :items] (comp vec reverse))]
      (is (= [[:pair nil] [:circle :zone/b]]
             (map (juxt :id :target) (get-in (set-item-target reordered :circle :zone/b) [:state :chains :cue-chains [0 0] :items]))))))
  (testing "an item that isn't there, in another cue or none: nothing ({})"
    (is (= {} (set-item-target with-items :gone :zone/a)))
    (is (= {} (cue-chain/handle {:event/type :cue-chain/set-item-target :col 1 :row 0 :item-id :circle :target :zone/a
                                 :state with-items})))
    (is (= {} (cue-chain/handle {:event/type :cue-chain/set-item-target :state base-state})))))

(deftest inherit-removes-the-target-test
  (let [with-target (assoc-in with-items [:chains :cue-chains [0 0] :items 0 :target] :zone/a)]
    (doseq [target [nil [] :inherit]]
      (let [item (get-in (set-item-target with-target :circle target) [:state :chains :cue-chains [0 0] :items 0])]
        (is (not (contains? item :target)) (str (pr-str target) ": no key, it inherits"))))
    (is (not (contains? (get-in (cue-chain/handle {:event/type :cue-chain/set-item-target :col 0 :row 0 :item-id :circle
                                                   :fx/event {:id :inherit :name "Inherit"} :state with-target})
                                [:state :chains :cue-chains [0 0] :items 0])
                        :target))
        "the picker's Inherit")))

(deftest set-cue-target-test
  (let [set-target #(cue-chain/handle (merge {:event/type :cue-chain/set-target :col 0 :row 0 :state sample-state-with-item} %))]
    (is (= :zone/b (get-in (set-target {:target :zone/b}) [:state :chains :cue-chains [0 0] :target])))
    (is (= [:graphics :zone/a] (get-in (set-target {:target [:zone/a :graphics]}) [:state :chains :cue-chains [0 0] :target])))
    (is (= :crowd (get-in (set-target {:fx/event {:id :crowd}}) [:state :chains :cue-chains [0 0] :target])) "from a picker")
    (let [cleared (set-target {:target nil :state (assoc-in sample-state-with-item [:chains :cue-chains [0 0] :target] :zone/a)})]
      (is (not (contains? (get-in cleared [:state :chains :cue-chains [0 0]]) :target)) "nil: none, it inherits"))
    (is (= {:state sample-state-with-item} (set-target {:col 4})) "a cell with no cue: nothing")
    (is (= {:state base-state} (cue-chain/handle {:event/type :cue-chain/set-target :state base-state})))))


;; A preset's parameters (follow-up N10): by the item's id, as its target


(def ^:private presets
  "The cue at [0 0]: Circle, Square, then the group Pair holding Star."
  (assoc-in sample-state-with-item [:chains :cue-chains [0 0] :items]
            [{:type :preset :id :circle :preset-id :circle :params {:radius 0.5 :red 1.0 :green 1.0 :blue 1.0} :effects []}
             {:type :preset :id :square :preset-id :square :params {:size 0.5 :red 1.0 :green 1.0 :blue 1.0} :effects []}
             {:type :group :id :pair :name "Pair" :items [{:type :preset :id :star :preset-id :star :params {:spikes 5}}]}]))

(defn- preset-edit
  "What the preset editor sends for item-id of the cue at [col row], of
   type (:cue-chain/update-preset-param, -param-from-text, update-preset-color)."
  [state type item-id & {:as more}]
  (cue-chain/handle (merge {:event/type type :col 0 :row 0 :item-id item-id :state state} more)))

(defn- params-of
  "The params of the preset item-id of the cue at [0 0] in state."
  [state item-id]
  (let [items (get-in state [:chains :cue-chains [0 0] :items])]
    (:params (some #(when (= item-id (:id %)) %) (tree-seq map? #(concat (:items %)) {:items items})))))

(deftest preset-edits-find-their-item-test
  (let [reordered (update-in presets [:chains :cue-chains [0 0] :items] (fn [[c s p]] [s p c]))]
    (testing "a value, typed text and a colour, each on its item"
      (is (= 0.3 (:radius (params-of (:state (preset-edit presets :cue-chain/update-preset-param :circle :param-key :radius :fx/event 0.3)) :circle))))
      (is (= 0.25 (:radius (params-of (:state (preset-edit presets :cue-chain/update-preset-param-from-text :circle :param-key :radius
                                                           :min 0.1 :max 1.0 :is-int? false :text "0.25"))
                                      :circle))))
      (is (= {:red 0.0 :green 0.5 :blue 1.0}
             (select-keys (params-of (:state (preset-edit presets :cue-chain/update-preset-color :circle :color [0.0 0.5 1.0])) :circle)
                          [:red :green :blue]))))
    (testing "one in a group, and an :int parameter's number whole (follow-up N1)"
      (is (= 7 (:spikes (params-of (:state (preset-edit presets :cue-chain/update-preset-param :star :param-key :spikes :fx/event 6.7)) :star)))))
    (testing "made before a reorder, handled after it: its own item, where it is now; the others as they were"
      (doseq [[type more] [[:cue-chain/update-preset-param {:param-key :radius :fx/event 0.3}]
                           [:cue-chain/update-preset-param-from-text {:param-key :radius :min 0.1 :max 1.0 :is-int? false :text "0.3"}]
                           [:cue-chain/update-preset-color {:color [0.0 0.5 1.0]}]]]
        (let [after (:state (apply preset-edit reordered type :circle (mapcat identity more)))]
          (is (not= (params-of reordered :circle) (params-of after :circle)) (str type ": Circle, now last"))
          (is (= (params-of reordered :square) (params-of after :square)) (str type ": Square, now first, as it was"))
          (is (= (update-in reordered [:chains :cue-chains [0 0] :items 2] dissoc :params)
                 (update-in after [:chains :cue-chains [0 0] :items 2] dissoc :params))
              (str type ": nothing else")))))
    (testing "an item that is gone, another cue's, a group or none: nothing ({})"
      (let [deleted (update-in presets [:chains :cue-chains [0 0] :items] (comp vec rest))]
        (doseq [[type more] [[:cue-chain/update-preset-param {:param-key :radius :fx/event 0.3}]
                             [:cue-chain/update-preset-param-from-text {:param-key :radius :min 0.1 :max 1.0 :is-int? false :text "0.3"}]
                             [:cue-chain/update-preset-color {:color [0.0 0.5 1.0]}]]]
          (is (= {} (apply preset-edit deleted type :circle (mapcat identity more))) (str type ": Circle deleted, Square in its place"))
          (is (= {} (apply preset-edit presets type :circle :col 1 (mapcat identity more))) (str type ": another cell"))
          (is (= {} (apply preset-edit presets type :pair (mapcat identity more))) (str type ": a group"))
          (is (= {} (apply preset-edit presets type nil (mapcat identity more))) (str type ": no item named")))))
    (testing "what changes nothing: nothing ({})"
      (is (= {} (preset-edit presets :cue-chain/update-preset-param :circle :param-key :radius :fx/event 0.5)) "the value it has")
      (is (= {} (preset-edit presets :cue-chain/update-preset-param-from-text :circle :param-key :radius
                             :min 0.1 :max 1.0 :is-int? false :text "abc"))
          "text that isn't a number")
      (is (= {} (preset-edit presets :cue-chain/update-preset-color :circle :fx/event (javafx.event.ActionEvent.)))
          "a colour edit with no colour"))
    (testing "through the pipeline: one undo step, Edit Preset"
      (let [s0 (history/mark-clean presets)
            after (:state (events/handle-event-pipeline {:event/type :cue-chain/update-preset-param :col 0 :row 0 :item-id :circle
                                                         :param-key :radius :fx/event 0.3 :state s0}))]
        (is (= 0.3 (:radius (params-of after :circle))))
        (is (= "Edit Preset" (history/undo-label (:history after))))))))


;; An item's effects (follow-up N14): its effects list's changes and the
;; effect bank's adds name the item by its id, as its target and its
;; preset's parameters do (R2, N10)


(def ^:private fx-a {:id :fx-a :effect-id :intensity :enabled? true :params {:amount 1.0}})
(def ^:private fx-b {:id :fx-b :effect-id :scale :enabled? true :params {:x-scale 1.0 :y-scale 1.0 :uniform? false}})
(def ^:private fx-c {:id :fx-c :effect-id :intensity :enabled? true :params {:amount 0.5}})

(def ^:private with-item-effects
  "The cue at [0 0]: Circle with the effects A and B, Square with C, then
   the group Pair holding Star, which has none; saved."
  (-> sample-state-with-item
      (assoc-in [:chains :cue-chains [0 0] :items]
                [{:type :preset :id :circle :preset-id :circle :params {} :effects [fx-a fx-b]}
                 {:type :preset :id :square :preset-id :square :params {} :effects [fx-c]}
                 {:type :group :id :pair :name "Pair" :effects []
                  :items [{:type :preset :id :star :preset-id :star :params {} :effects []}]}])
      (history/mark-clean)))

(defn- effects-of
  "The ids of the effects of the item or group item-id of the cue at [0 0]
   in state."
  [state item-id]
  (let [items (get-in state [:chains :cue-chains [0 0] :items])]
    (some #(when (= item-id (:id %)) (mapv :id (:effects %))) (tree-seq map? #(concat (:items %)) {:items items}))))

(defn- run
  "state after event and what it dispatches inline, through the pipeline
   (events/handle-event-pipeline), as the agent runs them."
  [state event]
  (let [effects (events/handle-event-pipeline (assoc event :state state :time 1000))
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run s next-event)
      s)))

(defn- item-effects-event
  "An input in the item effects list of the cue at [0 0] while it shows the
   effects of item-id, with the address the cue chain editor gives it."
  [type item-id & {:as more}]
  (merge {:event/type type :component-id [:item-effects 0 0]
          :items-path {:domain :cue-chains :entity-key [0 0] :owner-id item-id}
          :on-change-event :cue-chain/set-item-effects
          :on-change-params {:col 0 :row 0 :item-id item-id}}
         more))

(deftest item-effects-find-their-item-test
  (let [reordered (update-in with-item-effects [:chains :cue-chains [0 0] :items] (fn [[c s p]] [s p c]))
        set-effects (fn [state item-id items & {:as more}]
                      (cue-chain/handle (merge {:event/type :cue-chain/set-item-effects :col 0 :row 0 :item-id item-id
                                                :items items :state state}
                                               more)))
        add (fn [state owner-id & {:as more}]
              (cue-chain/handle (merge {:event/type :cue-chain/add-effect-from-bank :col 0 :row 0 :owner-id owner-id
                                        :item-id :rotation :state state}
                                       more)))]
    (testing "the list's change: the effects of its own item, a group's, an item's in a group"
      (is (= [:fx-b :fx-a] (effects-of (:state (set-effects with-item-effects :circle [fx-b fx-a])) :circle)))
      (is (= [:fx-c] (effects-of (:state (set-effects with-item-effects :pair [fx-c])) :pair)))
      (is (= [:fx-c] (effects-of (:state (set-effects with-item-effects :star [fx-c])) :star))))
    (testing "the bank's add: on its own item, the new effect selected"
      (let [{:keys [state dispatch]} (add with-item-effects :star)
            [added] (get-in state [:chains :cue-chains [0 0] :items 2 :items 0 :effects])]
        (is (= :rotation (:effect-id added)))
        (is (= {:event/type :list/select-item :component-id [:item-effects 0 0] :item-id (:id added) :mode :single}
               dispatch))))
    (testing "made before a reorder, handled after it: its own item, where it is now; the others as they were"
      (let [changed (:state (set-effects reordered :circle [fx-b fx-a]))
            added (:state (add reordered :circle))]
        (is (= [:fx-b :fx-a] (effects-of changed :circle)) "Circle's, now last")
        (is (= [:fx-a :fx-b] (pop (effects-of added :circle))) "Circle's, and one more")
        (is (= :rotation (:effect-id (peek (get-in added [:chains :cue-chains [0 0] :items 2 :effects])))))
        (doseq [s [changed added]]
          (is (= [:fx-c] (effects-of s :square)) "Square's, now first, as they were")
          (is (= [] (effects-of s :star))))))
    (testing "an item that is gone, another cue's or none: nothing ({})"
      (let [deleted (update-in with-item-effects [:chains :cue-chains [0 0] :items] (comp vec rest))]
        (is (= {} (set-effects deleted :circle [fx-b fx-a])) "Circle deleted, Square in its place")
        (is (= {} (add deleted :circle)))
        (is (= {} (set-effects with-item-effects :circle [] :col 1)) "another cell")
        (is (= {} (add with-item-effects :circle :col 1)))
        (is (= {} (set-effects with-item-effects nil [])) "no item named")
        (is (= {} (add with-item-effects nil)))))
    (testing "through the pipeline, as the editor sends them, behind a reorder: one undo step each"
      (let [s0 (assoc-in reordered [:list-ui [:item-effects 0 0]] {:selected-ids #{:fx-a} :last-selected-id :fx-a})
            dropped (run s0 (item-effects-event :list/perform-drop :circle :dragging-ids #{:fx-a} :target-id :fx-b
                                                :drop-position :after))
            deleted (run s0 (item-effects-event :list/delete-selected :circle))
            added (run s0 {:event/type :cue-chain/add-effect-from-bank :col 0 :row 0 :owner-id :circle :item-id :rotation})]
        (is (= [:fx-b :fx-a] (effects-of dropped :circle)))
        (is (= "Reorder Items" (history/undo-label (:history dropped))))
        (is (= [:fx-b] (effects-of deleted :circle)))
        (is (= "Delete Item(s)" (history/undo-label (:history deleted))))
        (is (= 3 (count (effects-of added :circle))))
        (is (= "Add Effect" (history/undo-label (:history added))))
        (doseq [s [dropped deleted added]]
          (is (= 1 (count (get-in s [:history :undo-stack]))) "one undo step")
          (is (= [:fx-c] (effects-of s :square)) "Square's as they were")))
      (testing "; for an item deleted since: nothing, and no undo step"
        (let [gone (-> reordered
                       (update-in [:chains :cue-chains [0 0] :items] #(filterv (fn [i] (not= :circle (:id i))) %))
                       (assoc-in [:list-ui [:item-effects 0 0]] {:selected-ids #{:fx-a} :last-selected-id :fx-a}))]
          (doseq [event [(item-effects-event :list/perform-drop :circle :dragging-ids #{:fx-a} :target-id :fx-b
                                             :drop-position :after)
                         (item-effects-event :list/delete-selected :circle)
                         {:event/type :cue-chain/add-effect-from-bank :col 0 :row 0 :owner-id :circle :item-id :rotation}]]
            (is (= {} (events/handle-event-pipeline (assoc event :state gone :time 1000))) (str (:event/type event)))))))))


;; The selected effect's editor (follow-up N14): its edits name the effect
;; by its instance and its item by its id (:effect-instance-id, :owner-id),
;; which the pipeline finds where they are (chain-helpers/
;; wrap-effect-instance-target)


(defn- effect-of
  "The effect effect-id of the item item-id of the cue at [0 0] in state."
  [state item-id effect-id]
  (let [items (get-in state [:chains :cue-chains [0 0] :items])
        item (some #(when (= item-id (:id %)) %) (tree-seq map? #(concat (:items %)) {:items items}))]
    (some #(when (= effect-id (:id %)) %) (:effects item))))

(deftest item-effect-edits-find-their-effect-test
  ;; The editor rendered A at [0 :effects 0] (Circle first, A its first
  ;; effect) and B at [0 :effects 1]. Since, Circle is last with its
  ;; effects [B A], and Square's C is where A was
  (let [moved (-> with-item-effects
                  (update-in [:chains :cue-chains [0 0] :items] (fn [[c s p]] [s p (update c :effects (comp vec reverse))]))
                  (history/mark-clean))
        now {:fx-a [:chains :cue-chains [0 0] :items 2 :effects 1]
             :fx-b [:chains :cue-chains [0 0] :items 2 :effects 0]}
        edit (fn [type effect-id rendered-at & {:as more}]
               (merge {:event/type type :domain :cue-chains :entity-key [0 0] :effect-path rendered-at
                       :effect-instance-id effect-id :owner-id :circle}
                      more))
        edits [(edit :chain/update-param :fx-a [0 :effects 0] :param-key :amount :value 0.4)
               (edit :chain/update-param-from-text :fx-a [0 :effects 0] :param-key :amount :min 0.0 :max 3.0
                     :is-int? false :text "0.4")
               (edit :keyframe/toggle-enabled :fx-a [0 :effects 0] :enabled? true)
               (edit :chain/set-clamp-after :fx-b [0 :effects 1] :value false)]]
    (testing "each lands on its own effect, where it is now, and on nothing else: one undo step"
      (doseq [event edits]
        (let [after (run moved event)
              id (:effect-instance-id event)]
          (is (not= (effect-of moved :circle id) (effect-of after :circle id)) (str (:event/type event) ": its effect"))
          (is (= (:chains moved) (:chains (assoc-in after (now id) (get-in moved (now id)))))
              (str (:event/type event) ": nothing else (Square's C as it was)"))
          (is (= 1 (count (get-in after [:history :undo-stack]))) (str (:event/type event) ": one undo step"))
          (is (= (if (= :chain/set-clamp-after (:event/type event))
                   "Change Transform Clamping"
                   (history-handlers/format-event-description (:event/type event)))
                 (history/undo-label (:history after)))
              "labelled as before"))))
    (testing "its UI mode is kept under its id, so it stays with the effect (follow-up N15)"
      (is (= :numeric (get-in (run moved (edit :chain/set-ui-mode :fx-a [0 :effects 0] :mode :numeric))
                              [:ui :dialogs :cue-chain-editor :ui-modes :fx-a]))))
    (testing "once its effect is gone, or its item, or another item is named: nothing ({})"
      (let [no-a (update-in moved [:chains :cue-chains [0 0] :items 2 :effects] #(filterv (fn [e] (not= :fx-a (:id e))) %))
            no-circle (update-in moved [:chains :cue-chains [0 0] :items] (comp vec butlast))
            a-edits (conj (pop edits) (edit :chain/set-ui-mode :fx-a [0 :effects 0] :mode :numeric))]
        (doseq [[s events why] [[no-a a-edits "A deleted, C where it was rendered"]
                                [no-circle edits "Circle deleted"]
                                [moved (map #(assoc % :owner-id :square) edits) "Square named"]]
                event events]
          (is (= {} (events/handle-event-pipeline (assoc event :state s :time 1000)))
              (str (:event/type event) ": " why)))))
    (testing "one that names no instance goes by its place, as before"
      (is (= 0.4 (get-in (run moved {:event/type :chain/update-param :domain :cue-chains :entity-key [0 0]
                                     :effect-path [0 :effects 0] :param-key :amount :value 0.4})
                         [:chains :cue-chains [0 0] :items 0 :effects 0 :params :amount]))
          "Square's C, at that place"))))

(deftest list-editor-component-rendering-test
  (testing "list-editor component description can be constructed with context menu"
    (let [items [{:id (java.util.UUID/randomUUID) :preset-id :circle}
                 {:type :group :id (java.util.UUID/randomUUID) :name "Group" :items []}]
          desc {:fx/type list-comp/list-editor
                :items items
                :component-id [:test-list 0 0]
                :get-context-menu (fn [item]
                                    {:fx/type :context-menu
                                     :items [{:fx/type :menu-item :text "Action"}]})
                :get-group-context-menu (fn [group]
                                          {:fx/type :context-menu
                                           :items [{:fx/type :menu-item :text "Group Action"}]})
                :header-label "TEST"}]
      (is (map? desc))
      (is (= list-comp/list-editor (:fx/type desc))))))

(deftest set-name-clears-given-dialog-flag-test
  (let [s0 {:ui {:dialogs {:timeline-cue-editor {:editing-name? true}
                           :cue-chain-editor {:editing-name? true}}}}]
    (testing "the timeline dialog passes its own :dialog-id"
      (let [s1 (:state (cue-chain/handle {:event/type :cue-chain/set-name
                                          :col 0 :row 0 :name "Intro"
                                          :dialog-id :timeline-cue-editor
                                          :state s0}))]
        (is (= "Intro" (get-in s1 [:chains :cue-chains [0 0] :name])))
        (is (false? (get-in s1 [:ui :dialogs :timeline-cue-editor :editing-name?])))
        (is (true? (get-in s1 [:ui :dialogs :cue-chain-editor :editing-name?])))))
    (testing "without :dialog-id it clears the cue chain editor's flag"
      (let [s1 (:state (cue-chain/handle {:event/type :cue-chain/set-name
                                          :col 0 :row 0 :name "Intro"
                                          :state s0}))]
        (is (false? (get-in s1 [:ui :dialogs :cue-chain-editor :editing-name?])))))))
