(ns laser-show.ui-test.projectors-layout-test
  "The Projectors tab's layout (plans/2026-11-19-zones/13): each inspector
   column scrolls on its own, and FIT GUARD's rows show whole."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene Node Parent]
           [javafx.scene.control Labeled ScrollPane]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- laser [n & {:as more}]
  (merge {:name (str "Laser " n) :host (str "10.0.0." n) :port 7255 :service-id 1 :enabled? true
          :zone-groups [] :exclude-from-all? false
          :output-config {:color-bit-depth 8 :xy-bit-depth 16}}
         more))

(defn- with-lasers!
  "Lasers :a and :b, :a selected, its safety zone a limit and one blocked
   area."
  []
  (state/swap-state! #(-> %
                          (assoc :projectors {:a (laser 1 :name "Laser A"
                                                        :safety {:limit [-0.8 -0.6 0.8 0.9]
                                                                 :blocked [[-0.3 -0.6 0.3 -0.2]]})
                                              :b (laser 2 :name "Laser B")})
                          (assoc-in [:projector-ui :active-projector] :a))))

(defn- at-window-size!
  "The main window at w × h, laid out."
  [w h]
  (ui/set-size! (ui/main-window) w h)
  (ui/wait-for! #(ui/on-fx-thread (fn [] (let [scene (.getScene (ui/main-window))]
                                           (and (== w (.getWidth scene)) (== h (.getHeight scene)))))))
  (ui/sync-fx!))

(defn- inside?
  "Is rect [x0 y0 x1 y1] inside area (a pixel and a half of slack, for a
   control's outer edge)?"
  [[x0 y0 x1 y1] [a0 b0 a1 b1]]
  (and (<= (- a0 1.5) x0) (<= (- b0 1.5) y0) (<= x1 (+ a1 1.5)) (<= y1 (+ b1 1.5))))

(defn- scroll-panes
  "The scroll panes around node, nearest first: each one's viewport in the
   scene and whether it is scrolled down."
  [^Node node]
  (ui/on-fx-thread
   #(loop [^Node p (.getParent node) acc []]
      (if p
        (recur (.getParent p)
               (if (instance? ScrollPane p)
                 (let [^ScrollPane pane p
                       ^Node viewport (.lookup ^Parent pane ".viewport")
                       b (.localToScene viewport (.getLayoutBounds viewport))]
                   (conj acc {:viewport [(.getMinX b) (.getMinY b) (.getMaxX b) (.getMaxY b)]
                              :scrolled? (> (.getVvalue pane) (.getVmin pane))}))
                 acc))
        acc))))

(defn- shown?
  "Is the node found by selector in view without scrolling: inside the
   window above the status bar (ui/scene-bounds), inside the viewport of
   every scroll pane around it, and none of them scrolled?"
  [selector]
  (when-let [node (ui/query selector)]
    (let [width (ui/on-fx-thread #(.getWidth (.getScene (ui/main-window))))
          [_ status-top _ _] (ui/scene-bounds (ui/query "#status-bar"))
          bounds (ui/scene-bounds node)
          panes (scroll-panes node)]
      (and (inside? bounds [0.0 0.0 width status-top])
           (every? #(inside? bounds (:viewport %)) panes)
           (not-any? :scrolled? panes)))))

(deftest fit-guard-shows-whole-test
  (testing "FIT GUARD's boxes, \"never below\" and % show whole, not cut to …"
    (with-lasers!)
    (ui/click! (ui/robot) "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#projector-fit-guard")))
    (at-window-size! 1200 800)
    (doseq [field ["#projector-fit-point-floor" "#projector-fit-dim-floor"]]
      (let [row (ui/on-fx-thread #(.getParent (ui/query field)))
            labeled (filterv #(instance? Labeled %) (ui/on-fx-thread #(vec (.getChildrenUnmodifiable ^Parent row))))]
        (is (= 3 (count labeled)) "the box, never below, %")
        (doseq [^Labeled l labeled]
          (let [text (ui/on-fx-thread #(.getText l))]
            (ui/wait-for! #(some? (ui/shown-text l)))
            (is (= text (ui/shown-text l)) text)))))))

(defn- with-curves-selected!
  "Laser :a's chain is RGB Curves, selected: its editor tops the right
   column, above the safety zone."
  []
  (let [id #uuid "00000000-0000-0000-0000-0000000000c1"]
    (state/swap-state! #(-> %
                            (assoc-in [:chains :projector-effects :a :items]
                                      [{:id id :effect-id :rgb-curves :enabled? true
                                        :params {:r-curve-points [[0.0 0.0] [1.0 1.0]]
                                                 :g-curve-points [[0.0 0.0] [1.0 1.0]]
                                                 :b-curve-points [[0.0 0.0] [1.0 1.0]]}}])
                            (assoc-in [:list-ui [:projector-effects :a]] {:selected-ids #{id} :last-selected-id id})))))

(deftest each-column-scrolls-on-its-own-test
  (let [robot (ui/robot)
        top #(second (ui/scene-bounds (ui/query %)))
        scrolled? (fn [selector] (ui/on-fx-thread #(let [^ScrollPane pane (ui/query selector)]
                                                     (> (.getVvalue pane) (.getVmin pane)))))]
    (with-lasers!)
    (with-curves-selected!)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#projector-column-right")))
    (at-window-size! 1200 800)
    (ui/wait-for! #(shown? "#projector-config-title"))
    (let [title (top "#projector-config-title")
          chain (top "#projector-add-effect-menu")]
      (is (not (shown? "#safety-add-blocked-button")) "the curves' editor takes the right column's top")
      (ui/scroll-into-view! "#safety-add-blocked-button")
      (ui/wait-for! #(scrolled? "#projector-column-right"))
      (is (not (scrolled? "#projector-column-left")))
      (is (not (scrolled? "#projector-column-middle")))
      (is (== title (top "#projector-config-title")) "the settings stay where they were")
      (is (== chain (top "#projector-add-effect-menu")) "and so does the calibration chain"))))
