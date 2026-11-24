(ns laser-show.ui-test.safety-editor-test
  "The safety zone editor in the projector panel (zones plan Z1): drag the
   limit, add and remove blocked areas, type the numbers, ask before growing
   a live projector's area, and Identify's reason when it can't run."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.keys :as keys])
  (:import [javafx.event Event]
           [javafx.scene Node]
           [javafx.scene.input KeyCode MouseButton MouseEvent]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- app-state [] (state/get-raw-state))

(defn- zone [] (get-in (app-state) [:projectors :a :safety]))

(defn- with-projector!
  "Projector :a, selected, with zone; :running? sets the stream on (the
   projector then counts as outputting: enabled, with a host)."
  [zone & {:keys [running?]}]
  (events/write-state! #(-> %
                            (assoc :projectors {:a {:name "Fake 1" :host "127.0.0.1" :port 7255 :service-id 1
                                                    :enabled? true :zone-groups [:all] :safety zone
                                                    :output-config {:color-bit-depth 8 :xy-bit-depth 16}}})
                            (assoc-in [:projector-ui :active-projector] :a)
                            (assoc-in [:backend :streaming :running?] (boolean running?)))))

(defn- px
  "The canvas pixel of model x (the editor's 260 px canvas, 10 px margin)."
  [x]
  (+ 10.0 (* (/ (+ x 1.0) 2.0) 240.0)))

(defn- py [y]
  (+ 10.0 (* (/ (- 1.0 y) 2.0) 240.0)))

(defn- fire-canvas!
  "Fire one primary-button mouse event at the canvas's local (x, y), as a
   real mouse sends it (headless, a robot drag loses its button)."
  [event-type x y]
  (let [^Node node (ui/query "#safety-canvas")]
    (ui/on-fx-thread
     #(let [scene (.localToScene node (double x) (double y))
            screen (.localToScreen node (double x) (double y))]
        (Event/fireEvent node
                         (MouseEvent. event-type (.getX scene) (.getY scene) (.getX screen) (.getY screen)
                                      MouseButton/PRIMARY 1 false false false false
                                      (not= event-type MouseEvent/MOUSE_RELEASED) false false
                                      false false false nil))))
    (ui/sync-fx!)))

(defn- fire-press-and-drag!
  "Press on the canvas at (x0, y0) and drag to (x1, y1) in four moves,
   still holding the button."
  [[x0 y0] [x1 y1]]
  (fire-canvas! MouseEvent/MOUSE_PRESSED x0 y0)
  (doseq [i (range 1 5)]
    (fire-canvas! MouseEvent/MOUSE_DRAGGED (+ x0 (* (- x1 x0) (/ i 4.0))) (+ y0 (* (- y1 y0) (/ i 4.0))))))

(defn- fire-canvas-drag!
  "Press on the canvas at (x0, y0), drag to (x1, y1) and release."
  [from [x1 y1 :as to]]
  (fire-press-and-drag! from to)
  (fire-canvas! MouseEvent/MOUSE_RELEASED x1 y1))

(deftest drag-the-limit-in-test
  (with-projector! {:limit [-0.8 -0.8 0.8 0.8] :blocked []})
  (ui/click! (ui/robot) "#projectors-tab")
  (ui/wait-for! #(some? (ui/query "#safety-canvas")))
  (fire-canvas-drag! [(px 0.8) (py 0.0)] [(- (px 0.8) 40.0) (py 0.0)])
  (ui/wait-for! #(< (get-in (zone) [:limit 2]) 0.5))
  (is (< 0.4 (get-in (zone) [:limit 2]) 0.5) "the right edge followed the mouse")
  (is (= [-0.8 -0.8] (subvec (:limit (zone)) 0 2)) "the other edges stayed")
  (testing "the field shows it"
    (ui/wait-for! #(= (format "%.2f" (get-in (zone) [:limit 2])) (ui/node-text "#safety-limit-x1"))))
  (testing "the drag is one press, one step: Undo puts the edge back, Redo drags it in again (reactivity plan 16)"
    (ui/wait-for! #(nil? (get-in (app-state) [:history :transaction])))
    (is (= ["Change Safety Zone"] (mapv :description (get-in (app-state) [:history :undo-stack]))))
    (let [dragged (get-in (zone) [:limit 2])]
      (events/dispatch! {:event/type :edit/undo})
      (ui/wait-for! #(= 0.8 (get-in (zone) [:limit 2])))
      (events/dispatch! {:event/type :edit/redo})
      (ui/wait-for! #(= dragged (get-in (zone) [:limit 2]))))))

(deftest escape-puts-back-the-zone-test
  (with-projector! {:limit [-0.8 -0.8 0.8 0.8] :blocked []})
  (ui/click! (ui/robot) "#projectors-tab")
  (ui/wait-for! #(some? (ui/query "#safety-canvas")))
  (fire-press-and-drag! [(px 0.8) (py 0.0)] [(- (px 0.8) 40.0) (py 0.0)])
  (ui/wait-for! #(< (get-in (zone) [:limit 2]) 0.5))
  (is (ui/on-fx-thread #(keys/claims? (ui/query "#safety-canvas") :escape)) "Escape cancels the drag")
  (ui/press! (ui/robot) KeyCode/ESCAPE)
  (ui/wait-for! #(= 0.8 (get-in (zone) [:limit 2])))
  (is (not (ui/on-fx-thread #(keys/claims? (ui/query "#safety-canvas") :escape))) "no drag left to cancel")
  (fire-canvas! MouseEvent/MOUSE_RELEASED (- (px 0.8) 40.0) (py 0.0))
  (is (= [-0.8 -0.8 0.8 0.8] (:limit (zone))) "the zone from before the drag, and the release changes nothing")
  (is (not (get-in (app-state) [:ui :dialogs :confirm :open?])) "it didn't ask"))

(deftest drag-a-blocked-area-test
  (with-projector! {:limit [-0.9 -0.9 0.9 0.9] :blocked [[-0.2 -0.2 0.2 0.2]]})
  (ui/click! (ui/robot) "#projectors-tab")
  (ui/wait-for! #(some? (ui/query "#safety-canvas")))
  (fire-canvas-drag! [(px 0.0) (py 0.0)] [(+ (px 0.0) 24.0) (py 0.0)])
  (ui/wait-for! #(> (get-in (zone) [:blocked 0 0]) -0.15))
  (let [[x0 y0 x1 y1] (get-in (zone) [:blocked 0])]
    (is (< (Math/abs x0) 1e-6) "it moved right by 24 px (0.2): from -0.2 to 0")
    (is (< (Math/abs (- 0.4 (- x1 x0))) 1e-6) "its size stayed")
    (is (= [-0.2 0.2] [y0 y1]))))

(deftest add-and-remove-a-blocked-area-test
  (let [robot (ui/robot)]
    (with-projector! {:limit [-0.8 -0.6 0.8 1.0] :blocked []})
    (ui/click! robot "#projectors-tab")
    (ui/click! robot "#safety-add-blocked-button")
    (ui/wait-for! #(= 1 (count (:blocked (zone)))))
    (ui/wait-for! #(some? (ui/query "#safety-remove-blocked-0")))
    (ui/click! robot "#safety-remove-blocked-0")
    (ui/wait-for! #(empty? (:blocked (zone))))))

(deftest number-fields-commit-test
  (let [robot (ui/robot)]
    (with-projector! {:limit [-0.8 -0.8 0.8 0.8] :blocked []})
    (ui/click! robot "#projectors-tab")
    (ui/fill! robot "#safety-limit-x1" "0.5")
    (ui/press! robot KeyCode/ENTER)
    (ui/wait-for! #(= 0.5 (get-in (zone) [:limit 2])))
    (testing "a number outside -1..1 is refused, and the field shows the stored value"
      (ui/fill! robot "#safety-limit-x1" "3")
      (ui/press! robot KeyCode/ENTER)
      (ui/wait-for! #(= "0.50" (ui/node-text "#safety-limit-x1")))
      (is (= 0.5 (get-in (zone) [:limit 2]))))))

(deftest growing-while-streaming-asks-test
  (let [robot (ui/robot)]
    (with-projector! {:limit [-0.8 -0.8 0.8 0.8] :blocked []} :running? true)
    (ui/click! robot "#projectors-tab")
    (ui/fill! robot "#safety-limit-x1" "0.95")
    (ui/press! robot KeyCode/ENTER)
    (ui/wait-for! #(some #{"Allow light in a larger area?"} (ui/window-titles)))
    (is (= 0.8 (get-in (zone) [:limit 2])) "nothing changes before the answer")
    (ui/assert-text! "#confirm-message" "Fake 1 is on. Light can reach places it couldn't before.")
    (ui/click! robot "#confirm-ok")
    (ui/wait-for! #(= 0.95 (get-in (zone) [:limit 2])))))

(deftest cancelled-field-shows-the-stored-zone-test
  (let [robot (ui/robot)]
    (with-projector! {:limit [-0.8 -0.8 0.8 0.8] :blocked []} :running? true)
    (ui/click! robot "#projectors-tab")
    (ui/fill! robot "#safety-limit-x1" "0.95")
    (ui/press! robot KeyCode/ENTER)
    (ui/wait-for! #(some #{"Allow light in a larger area?"} (ui/window-titles)))
    (ui/click! robot "#confirm-cancel")
    (ui/wait-for! #(not (some #{"Allow light in a larger area?"} (ui/window-titles))))
    (ui/wait-for! #(= "0.80" (ui/node-text "#safety-limit-x1")))
    (is (= 0.8 (get-in (zone) [:limit 2])))))

(deftest identify-needs-stream-test
  (with-projector! nil)
  (ui/click! (ui/robot) "#projectors-tab")
  (ui/assert-disabled! "#projector-identify-button")
  (ui/assert-text! "#projector-identify-note" "Turn on Stream to see it on the laser")
  (testing "a turned-off projector says so"
    (events/write-state! #(-> % (assoc-in [:backend :streaming :running?] true)
                              (assoc-in [:projectors :a :enabled?] false)))
    (ui/wait-for! #(= "Fake 1 is turned off" (ui/node-text "#projector-identify-note"))))
  (testing "streaming, on: Identify can run"
    (events/write-state! assoc-in [:projectors :a :enabled?] true)
    (ui/wait-for! #(ui/enabled? "#projector-identify-button"))))

(deftest locked-coordinate-allows-shrinking-and-restores-a-refused-widening-test
  ;; plans/2026-11-23-cljfx-reactivity/13 §7: the show lock lets a zone get
  ;; smaller and refuses a larger one; the field shows what is stored
  (let [robot (ui/robot)
        notice-id #(get-in (app-state) [:ui :notice :id])
        undo-depth #(count (get-in (app-state) [:history :undo-stack]))]
    (with-projector! {:limit [-0.8 -0.8 0.8 0.8] :blocked []} :running? false)
    (ui/click! robot "#projectors-tab")
    (ui/assert-text! "#safety-limit-x0" "-0.80")
    (events/dispatch! {:event/type :vibe/set-show-lock :locked? true})
    (ui/wait-for! #(true? (get-in (app-state) [:ui :show-lock?])))
    (testing "the left edge in, to -0.6: smaller, so it goes through"
      (let [depth (undo-depth)]
        (ui/fill! robot "#safety-limit-x0" "-0.6")
        (ui/press! robot KeyCode/ENTER)
        (ui/wait-for! #(= [-0.6 -0.8 0.8 0.8] (:limit (zone))))
        (ui/assert-text! "#safety-limit-x0" "-0.60")
        (is (= (inc depth) (undo-depth)) "one undo step")
        (is (nil? (get-in (app-state) [:ui :show-lock-notice])))))
    (testing "out to -0.9: larger, refused, and the field shows the zone"
      (let [depth (undo-depth)
            before (notice-id)]
        (ui/fill! robot "#safety-limit-x0" "-0.9")
        (ui/press! robot KeyCode/ENTER)
        (ui/wait-for! #(and (some? (notice-id)) (not= before (notice-id))
                            (= {:text "Larger safety zone refused" :event :projectors/set-safety}
                               (get-in (app-state) [:ui :show-lock-notice]))))
        (is (= [-0.6 -0.8 0.8 0.8] (:limit (zone))))
        (ui/assert-text! "#safety-limit-x0" "-0.60")
        (is (= depth (undo-depth)) "no undo step")))))
