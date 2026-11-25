(ns laser-show.ui-test.calibration-editor-test
  "The Projectors tab's calibration editor (views/tabs/projectors.clj,
   follow-up N15): its edits name the effect by its id and the laser as the
   chain's owner, never the effect's place, so a reorder or a delete of the
   chain's effects queued ahead of an input (in one FX turn, before the
   editor renders it) can't move it onto another effect. A typed value and a
   curve point land on their own effect where it is now, one undo step
   each, and on nothing once the effect or the laser is gone. And the RGB
   Curves' channel stays with its effect through a reorder."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.event ActionEvent Event]
           [javafx.scene Node]
           [javafx.scene.canvas Canvas]
           [javafx.scene.control Button TextField]
           [javafx.scene.input MouseButton MouseEvent]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- chain [] (get-in (state/get-raw-state) [:chains :projector-effects :a :items]))
(defn- effect [id] (some #(when (= id (:id %)) %) (chain)))
(defn- undo-depth [] (count (get-in (state/get-raw-state) [:history :undo-stack])))

(defn- node
  "The first node matching selector in any window."
  ^Node [selector]
  (first (ui/query-all selector)))

(defn- decimate [id] {:id id :effect-id :decimate :enabled? true :params {:kpps 30 :fps 60}})

(defn- curves [id]
  {:id id :effect-id :rgb-curves :enabled? true
   :params {:r-curve-points [[0.0 0.0] [1.0 1.0]]
            :g-curve-points [[0.0 0.0] [1.0 1.0]]
            :b-curve-points [[0.0 0.0] [1.0 1.0]]}})

(defn- show-chain!
  "Laser A on the Projectors tab, selected, with items as its calibration
   chain and the effect id selected in it; once ready? holds."
  [items id ready?]
  (events/write-state! #(-> %
                            (assoc :projectors {:a {:name "Laser A" :host "10.0.0.1" :port 7255 :service-id 1
                                                    :enabled? true :zone-groups [] :exclude-from-all? false
                                                    :output-config {:color-bit-depth 8 :xy-bit-depth 16}}})
                            (assoc-in [:projector-ui :active-projector] :a)
                            (assoc-in [:chains :projector-effects :a :items] items)
                            (assoc-in [:list-ui [:projector-effects :a]] {:selected-ids #{id} :last-selected-id id})))
  (events/dispatch! {:event/type :ui/set-active-tab :tab-id :projectors})
  (ui/wait-for! ready?))

(defn- set-chain!
  "What the chain's list sends for a reorder or a delete: laser A's new
   items. FX thread, so what follows in the same turn is queued behind it."
  [items]
  (events/dispatch! {:event/type :chain/set-items :domain :projector-effects :entity-key :a :items items}))

(defn- type-into!
  "Type text into field and press Enter (commit-field commits on its
   action). FX thread."
  [^TextField field text]
  (.setText field text)
  (.fireEvent field (ActionEvent.)))

(defn- curve-canvas
  "The selected RGB Curves' canvas (the safety zone's is the right column's
   other one)."
  ^Canvas []
  (some #(when (not= "safety-canvas" (.getId ^Node %)) %) (ui/query-all "#projector-column-right Canvas")))

(defn- click-canvas!
  "A primary click (press, release) at canvas-local (x, y), away from the
   curve's points: it adds one there. FX thread."
  [^Canvas canvas x y]
  (let [scene (.localToScene canvas (double x) (double y))
        screen (.localToScreen canvas (double x) (double y))]
    (doseq [type [MouseEvent/MOUSE_PRESSED MouseEvent/MOUSE_RELEASED]]
      (Event/fireEvent canvas (MouseEvent. type (.getX scene) (.getY scene) (.getX screen) (.getY screen)
                                           MouseButton/PRIMARY 1 false false false false
                                           (= type MouseEvent/MOUSE_PRESSED) false false false false false nil)))))

(defn- tab-active? [selector] (ui/has-style-class? selector "tab-btn-active"))

(deftest a-typed-value-behind-a-reorder-lands-on-its-effect-test
  (let [[a b] (repeatedly 2 random-uuid)]
    (show-chain! [(decimate a) (decimate b)] a #(some? (node "#param-kpps-field")))
    (let [before (undo-depth)
          field (node "#param-kpps-field")]
      ;; The list's reorder, then A's Scan rate typed, in one turn
      (ui/on-fx-thread #(do (set-chain! (vec (reverse (chain))))
                            (type-into! field "40")))
      (is (ui/wait-for! #(= 40 (get-in (effect a) [:params :kpps]))) "A's, now second")
      (is (= [b a] (mapv :id (chain))))
      (is (= 30 (get-in (effect b) [:params :kpps])) "B's, in A's place, as it was")
      (is (= (+ before 2) (undo-depth)) "the reorder's undo step, and the edit's"))))

(deftest a-curve-point-behind-a-reorder-lands-on-its-effect-test
  (let [[c1 c2] (repeatedly 2 random-uuid)]
    (show-chain! [(curves c1) (curves c2)] c1 #(some? (curve-canvas)))
    (let [before (undo-depth)
          canvas (curve-canvas)]
      ;; The list's reorder, then a click on C1's red curve, in one turn
      (ui/on-fx-thread #(do (set-chain! (vec (reverse (chain))))
                            (click-canvas! canvas 140 200)))
      (is (ui/wait-for! #(= 3 (count (get-in (effect c1) [:params :r-curve-points])))) "a point on C1's, now second")
      (is (= [c2 c1] (mapv :id (chain))))
      (is (= (curves c2) (effect c2)) "C2, in C1's place, as it was")
      (is (= (+ before 2) (undo-depth)) "the reorder's undo step, and the point's"))))

(deftest late-edits-for-a-removed-effect-or-laser-do-nothing-test
  (let [[a b] (repeatedly 2 random-uuid)]
    (testing "A deleted, then its Scan rate typed, in one turn"
      (show-chain! [(decimate a) (decimate b)] a #(some? (node "#param-kpps-field")))
      (let [before (undo-depth)
            field (node "#param-kpps-field")]
        (ui/on-fx-thread #(do (set-chain! [(decimate b)])
                              (type-into! field "40")))
        (is (ui/wait-for! #(= [b] (mapv :id (chain)))))
        (is (events/flush!))
        (is (= [(decimate b)] (chain)) "B, in A's place, exactly as it was")
        (is (= (inc before) (undo-depth)) "the delete's undo step, and no other")))
    (testing "the laser removed, then A's Scan rate typed, in one turn"
      (show-chain! [(decimate a) (decimate b)] a #(some? (node "#param-kpps-field")))
      (let [before (undo-depth)
            field (node "#param-kpps-field")]
        (ui/on-fx-thread #(do (events/dispatch! {:event/type :projectors/remove :projector-id :a})
                              (type-into! field "40")))
        (is (ui/wait-for! #(nil? (get-in (state/get-raw-state) [:projectors :a]))))
        (is (events/flush!))
        (is (nil? (get-in (state/get-raw-state) [:chains :projector-effects :a])) "no chain made again for it")
        (is (= (inc before) (undo-depth)) "the remove's undo step, and no other")))))

(deftest the-curves-channel-stays-with-its-effect-test
  (let [[c1 c2] (repeatedly 2 random-uuid)
        select! (fn [id] (events/dispatch! {:event/type :list/select-item :component-id [:projector-effects :a]
                                            :item-id id :mode :single}))
        rows #(ui/query-all "#list-editor-projector-effects-a .chain-item")
        selected-row? (fn [i] (contains? (set (.getStyleClass ^Node (nth (rows) i))) "chain-item-selected"))]
    (show-chain! [(curves c1) (curves c2)] c1 #(some? (node "#g-tab")))
    (let [green (node "#g-tab")]
      (ui/on-fx-thread #(.fire ^Button green)))
    (ui/wait-for! #(tab-active? "#g-tab"))
    (testing "C1's Green, after the list moves C1 below C2"
      (events/dispatch! {:event/type :chain/set-items :domain :projector-effects :entity-key :a
                         :items [(effect c2) (effect c1)]})
      (ui/wait-for! #(and (= [c2 c1] (mapv :id (chain))) (selected-row? 1)))
      (is (tab-active? "#g-tab") "still C1's channel")
      (is (not (tab-active? "#r-tab"))))
    (testing "and C2, now where C1 was, shows its own: Red"
      (select! c2)
      (ui/wait-for! #(selected-row? 0))
      (is (tab-active? "#r-tab"))
      (is (not (tab-active? "#g-tab"))))))
