(ns laser-show.ui-test.projectors-layout-test
  "The Projectors tab's layout (plans/2026-11-19-zones/13): at 1200 × 800 a
   selected laser's settings, test pattern and safety zone show without
   scrolling; each inspector column scrolls on its own; the divider between
   the rig strip and the inspector stays where it is dragged, for the
   session."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene Node Parent]
           [javafx.scene.control Labeled ScrollPane]
           [javafx.scene.layout Region]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- app-state [] (state/get-raw-state))

(defn- laser [n & {:as more}]
  (merge {:name (str "Laser " n) :host (str "10.0.0." n) :port 7255 :service-id 1 :enabled? true
          :zone-groups [] :exclude-from-all? false
          :output-config {:color-bit-depth 8 :xy-bit-depth 16}}
         more))

(defn- with-lasers!
  "Lasers :a and :b, :a selected, its safety zone a limit and one blocked
   area."
  []
  (events/write-state! #(-> %
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

(defn- eventually
  "Wait up to 2 s for pred, then go on either way: the assertions after it
   say what failed."
  [pred]
  (try (ui/wait-for! 2000 pred) (catch clojure.lang.ExceptionInfo _ nil)))

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

(def ^:private setting-up
  "What setting up a laser takes, in the inspector's three columns."
  ["#projector-config-title" "#projector-name-field" "#projector-scan-rate-field" "#projector-tag-graphics"
   "#projector-exclude-from-all" "#projector-fit-guard"
   "#projector-calibration-button" "#projector-identify-button" "#projector-add-effect-menu" "#projector-remove-button"
   "#safety-canvas" "#safety-limit-x0" "#safety-blocked-0-x0" "#safety-add-blocked-button" "#safety-reset-button"])

(deftest setting-up-a-laser-needs-no-scrolling-test
  (let [robot (ui/robot)]
    (with-lasers!)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#safety-canvas")))
    (testing "at 1200 × 800, the default window: the settings, the test pattern, the chain and the whole safety zone, with nothing scrolled"
      (at-window-size! 1200 800)
      (eventually #(every? shown? setting-up))
      (doseq [selector (concat setting-up ["#rig-card-a" "#rig-card-b"])]
        (is (shown? selector) selector)))
    (testing "at 1440 × 900, also Copy to… and the strip's first rows"
      (at-window-size! 1440 900)
      (eventually #(every? shown? ["#safety-copy-menu" "#rig-cell-auto-left-b"]))
      (doseq [selector (concat setting-up ["#safety-copy-menu" "#safety-mirror-menu" "#rig-card-a"
                                           "#rig-cell-all-a" "#rig-cell-auto-left-b"])]
        (is (shown? selector) selector)))))

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
    (events/write-state! #(-> %
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

(defn- height [selector]
  (ui/on-fx-thread #(.getHeight ^Region (ui/query selector))))

(defn- near? [a b] (< (Math/abs (- (double a) (double b))) 2.0))

(deftest a-card-that-grows-is-never-cut-test
  (testing "a status that wraps to three lines and a driver's line make a card taller: the strip follows it, and goes back"
    (with-lasers!)
    (ui/click! (ui/robot) "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#safety-canvas")))
    (at-window-size! 1200 800)
    (eventually #(shown? "#rig-card-a"))
    (let [before (height "#rig-strip-scroll")]
      (is (shown? "#rig-card-a"))
      (events/write-state! #(-> %
                                (assoc-in [:backend :streaming :running?] true)
                                (assoc-in [:backend :streaming :status]
                                          {:engines 2 :wanted 2 :pending? false
                                           :projectors {:a {:state :streaming :fps 58.7 :scan-limited? true
                                                            :warning "Interlock open"
                                                            :driver-info {:buffer-ms 40 :firmware "4.1.2" :scan-rate 30303}}}})))
      (ui/wait-for! #(some? (ui/query "#projector-driver-info-a")))
      (eventually #(and (< (+ before 30) (height "#rig-strip-scroll")) (shown? "#rig-card-a")))
      (is (< (+ before 30) (height "#rig-strip-scroll")) "the strip grew with the card")
      (is (shown? "#rig-card-a") "and the divider doesn't cut it")
      (events/write-state! #(-> %
                                (assoc-in [:backend :streaming :running?] false)
                                (assoc-in [:backend :streaming :status] nil)))
      (eventually #(near? before (height "#rig-strip-scroll")))
      (is (near? before (height "#rig-strip-scroll")) "the inspector gets its room back"))))

(deftest dragged-divider-stays-for-the-session-test
  (let [robot (ui/robot)
        divider #(ui/query "#projectors-split > .split-pane-divider")
        stored #(get-in (app-state) [:rig-ui :divider])]
    (with-lasers!)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (divider)))
    (at-window-size! 1200 800)
    (let [before (height "#rig-strip-scroll")]
      (testing "dragged 80 px down, the strip is 80 px taller"
        (let [let-go! (ui/fire-drag! (divider) 0 80 :hold? true)]
          (ui/wait-for! #(near? (+ before 80) (height "#rig-strip-scroll")))
          (is (nil? (stored)) "nothing kept while the mouse holds it")
          (let-go!))
        (ui/wait-for! #(some? (stored)))
        (is (near? (+ before 80) (height "#rig-strip-scroll")))
        (is (empty? (get-in (app-state) [:history :undo-stack])) "nothing to undo")
        (is (false? (get-in (app-state) [:project :dirty?])) "and the show isn't unsaved"))
      (let [dragged (height "#rig-strip-scroll")]
        (testing "another laser selected: it stays"
          (ui/click! robot "#rig-card-b")
          (ui/wait-for! #(= "CONFIGURE: Laser B" (ui/node-text "#projector-config-title")))
          (ui/sync-fx!)
          (is (near? dragged (height "#rig-strip-scroll"))))
        (testing "another tab, and back: where it was left"
          (ui/click! robot "#grid-tab")
          (ui/wait-for! #(nil? (ui/query "#projectors-split")))
          (ui/click! robot "#projectors-tab")
          (ui/wait-for! #(some? (ui/query "#projectors-split")))
          (eventually #(near? dragged (height "#rig-strip-scroll")))
          (is (near? dragged (height "#rig-strip-scroll"))))))))
