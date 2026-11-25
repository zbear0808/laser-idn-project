(ns laser-show.ui-test.preset-param-editor-test
  "The cue chain editor's preset parameters (views/components/
   preset_param_editor.clj, follow-up N10): each edit names its item by its
   id and the cue by its cell, never the item's place, so a reorder or a
   delete queued ahead of it (in one FX turn, before the editor renders
   it) can't move it onto another item. A typed value, a slider's and a
   colour land on their own preset where it is now, and on nothing once it
   is gone."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.event ActionEvent Event]
           [javafx.scene Node]
           [javafx.scene.control ColorPicker Slider TextField]
           [javafx.scene.input MouseButton MouseEvent]
           [javafx.scene.paint Color]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(def ^:private white {:red 1.0 :green 1.0 :blue 1.0})

(defn- items [] (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :items]))
(defn- params-of [id] (:params (some #(when (= id (:id %)) %) (items))))
(defn- colour-of [id] (select-keys (params-of id) [:red :green :blue]))
(defn- undo-depth [] (count (get-in (state/get-raw-state) [:history :undo-stack])))

(defn- node
  "The first node matching selector in any window."
  ^Node [selector]
  (first (ui/query-all selector)))

(defn- open-on-circle!
  "The cue at [0 0] with Circle then Square, Circle selected, in the cue
   chain editor once Circle's parameters show. Returns their ids."
  []
  (let [circle (random-uuid)
        square (random-uuid)]
    (events/write-state! #(-> %
                              (assoc-in [:chains :cue-chains [0 0]]
                                        {:items [{:type :preset :id circle :preset-id :circle :enabled? true :effects []
                                                  :params (assoc white :radius 0.5 :num-points 64)}
                                                 {:type :preset :id square :preset-id :square :enabled? true :effects []
                                                  :params (assoc white :size 0.5 :num-points 16)}]})
                              (assoc-in [:list-ui [:cue-chain 0 0]] {:selected-ids #{circle} :last-selected-id circle})))
    (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
    (ui/wait-for! #(some? (node "#param-radius-field")))
    [circle square]))

(defn- set-items!
  "What the chain list sends for the cue's items: a reorder, or a delete.
   FX thread, so what follows in the same turn is queued behind it."
  [new-items]
  (events/dispatch! {:event/type :chain/set-items :domain :cue-chains :entity-key [0 0] :items new-items}))

(defn- type-into!
  "Type text into field and press Enter (commit-field commits on its
   action). FX thread."
  [^TextField field text]
  (.setText field text)
  (.fireEvent field (ActionEvent.)))

(defn- pick!
  "What a click on a square of the picker's palette does: its value, then
   its action. FX thread."
  [^ColorPicker picker ^Color color]
  (.setValue picker color)
  (.fireEvent picker (ActionEvent.)))

(defn- click-track!
  "A primary click (press and release) on slider's track at fraction of its
   width. FX thread."
  [^Slider slider fraction]
  (let [^Node track (.lookup slider ".track")
        b (.getLayoutBounds track)
        x (* fraction (.getWidth b))
        y (/ (.getHeight b) 2.0)
        scene (.localToScene track x y)
        screen (.localToScreen track x y)]
    (doseq [type [MouseEvent/MOUSE_PRESSED MouseEvent/MOUSE_RELEASED]]
      (Event/fireEvent track (MouseEvent. type (.getX scene) (.getY scene) (.getX screen) (.getY screen)
                                          MouseButton/PRIMARY 1 false false false false
                                          (= type MouseEvent/MOUSE_PRESSED) false false false false false nil)))))

(deftest preset-edits-behind-a-reorder-land-on-their-item-test
  (let [[circle square] (open-on-circle!)
        square-params (params-of square)]
    (testing "a typed Radius, queued behind the reorder the chain list sends"
      (let [[c s] (items)
            field (node "#param-radius-field")]
        (ui/on-fx-thread #(do (set-items! [s c])
                              (type-into! field "0.3")))
        (is (ui/wait-for! #(= 0.3 (:radius (params-of circle)))) "Circle's, now second")
        (is (= [square circle] (mapv :id (items))))
        (is (= square-params (params-of square)) "Square's, in Circle's place, as they were")))
    (testing "a colour, queued behind the reorder back"
      (let [[s c] (items)
            picker (node "#param-color-picker")]
        (ui/on-fx-thread #(do (set-items! [c s])
                              (pick! picker Color/RED)))
        (is (ui/wait-for! #(= {:red 1.0 :green 0.0 :blue 0.0} (colour-of circle))) "Circle's, first again")
        (is (= [circle square] (mapv :id (items))))
        (is (= white (colour-of square)) "Square stays white")))
    (testing "a slider's Radius, queued behind another reorder"
      (let [[c s] (items)
            slider (node "#param-radius-slider")]
        (ui/on-fx-thread #(do (set-items! [s c])
                              (click-track! slider 0.8)))
        (is (ui/wait-for! #(let [r (:radius (params-of circle))] (and (number? r) (< 0.6 r))))
            "Circle's: 0.8 of 0.1 to 1.0")
        (is (= [square circle] (mapv :id (items))))
        (is (= square-params (params-of square)) "Square's as they were")))))

(deftest a-deleted-presets-late-edit-does-nothing-test
  (let [[_circle square] (open-on-circle!)
        [_ s] (items)
        field (node "#param-radius-field")
        before (undo-depth)]
    ;; The delete the chain list sends, then Circle's typed Radius, in one turn
    (ui/on-fx-thread #(do (set-items! [s])
                          (type-into! field "0.3")))
    (is (ui/wait-for! #(= [square] (mapv :id (items)))))
    (is (events/flush!))
    (is (= [s] (items)) "Square, in Circle's place, exactly as it was")
    (is (= (inc before) (undo-depth)) "the delete's undo step, and no other")))
