(ns laser-show.ui-test.cue-load-test
  "A cue too heavy for a laser (plans/2026-11-19-zones/05 §7, §9): ⚠ on its
   cell while it plays, its tooltip naming the laser, and a line in its
   editor, in both editors, naming the laser and the item with the most
   points. Nothing streams: the warnings are the show clock's estimate from
   each laser's scan rate, the clock ticked from the test
   (fixtures/with-clock)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.timeline.shows :as shows]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.show-editor :as show-editor]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene Node]
           [javafx.scene.control Label]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(def ^:private ring-id #uuid "00000000-0000-0000-0000-0000000000a1")

(defn- laser! []
  (events/write-state! assoc :projectors {:a {:name "Laser A" :host "10.0.0.1" :enabled? true :zone-groups []
                                              :exclude-from-all? false :scan-rate 25000}}))

(def ^:private ring
  "A circle of 3,000 points (a dark one and 2,999 lit)."
  {:id ring-id :type :preset :preset-id :circle :enabled? true :effects [] :params {:num-points 2999}})

(defn- play!
  "cue at cell, playing."
  [[col row :as cell] cue]
  (events/write-state! assoc-in [:chains :cue-chains cell] cue)
  (events/dispatch! {:event/type :grid/trigger-cell :col col :row row})
  (ui/wait-for! #(some? (get-in (state/get-raw-state) [:playback :active-cues cell]))))

(defn- cell-tooltip
  "The tooltip of the cue cell at col, row (on the label that carries its
   menu)."
  [col row]
  (ui/on-fx-thread #(let [^Label label (some-> ^Node (ui/query (str "#grid-cell-" col "-" row)) .getParent)]
                      (some-> label .getTooltip .getText))))

(def ^:private line-end "on Laser A (25 kpps). Most points: Circle (3,000). Simplify it, or lower Laser A's minimum.")

(deftest heavy-cue-shows-a-warning-on-its-cell-test
  (laser!)
  (play! [7 3] {:destination-zone {:zone-group-id :all}
                :items [ring {:id (random-uuid) :type :preset :preset-id :square :enabled? true :effects []}]})
  (is (not (ui/visible? "#grid-cell-warning-7-3")))
  (fixtures/with-clock
    (fn [_]
      (ui/wait-for! #(ui/visible? "#grid-cell-warning-7-3"))
      (is (= "⚠" (ui/node-text "#grid-cell-warning-7-3")))
      (is (= "Too heavy for Laser A: about 8 fps (minimum 20)" (cell-tooltip 7 3)))
      (testing "a light cue gets none"
        (play! [6 3] {:destination-zone {:zone-group-id :all} :items [(assoc ring :id (random-uuid) :params {:num-points 99})]})
        (ui/wait-for! #(some? (get-in (state/get-raw-state) [:backend :streaming :cue-warnings [7 3]])))
        (Thread/sleep 400)
        (is (not (ui/visible? "#grid-cell-warning-6-3")))))))

(deftest warning-goes-when-the-cue-stops-test
  (laser!)
  (play! [7 3] {:destination-zone {:zone-group-id :all} :items [ring]})
  (fixtures/with-clock
    (fn [_]
      (ui/wait-for! #(ui/visible? "#grid-cell-warning-7-3"))
      (events/dispatch! {:event/type :grid/trigger-cell :col 7 :row 3})
      (ui/wait-for! #(nil? (get-in (state/get-raw-state) [:playback :active-cues [7 3]])))
      (ui/wait-for! #(not (ui/visible? "#grid-cell-warning-7-3")))
      (is (nil? (get-in (state/get-raw-state) [:backend :streaming :cue-warnings])) "while it plays only (Q-Z3-3 A)"))))

(deftest cue-editor-names-the-laser-and-the-heaviest-item-test
  (laser!)
  (play! [7 3] {:destination-zone {:zone-group-id :all} :items [ring]})
  (fixtures/with-clock
    (fn [_]
      (ui/wait-for! #(ui/visible? "#grid-cell-warning-7-3"))
      (events/dispatch! {:event/type :cue-chain/open-editor :col 7 :row 3})
      (ui/wait-for! #(ui/visible? "#cue-load-warning"))
      (ui/wait-for! #(str/ends-with? (str (ui/node-text "#cue-load-warning")) line-end))
      (is (= (str "⚠ ~3,000 points: about 8 fps " line-end) (ui/node-text "#cue-load-warning"))
          "open, its render counts each item's points: the circle's")
      (testing "gone when the cue stops"
        (events/dispatch! {:event/type :grid/trigger-cell :col 7 :row 3})
        (ui/wait-for! #(not (ui/visible? "#cue-load-warning")))))))

(deftest timeline-editor-shows-the-same-line-test
  (laser!)
  (let [id (random-uuid)]
    (show-editor/open-show! id {:editor-type :timeline
                                :destination-zone {:zone-group-id :all}
                                :tracks [{:id :t :type :track}]
                                :items [(assoc ring :track-id :t :timeline/start 0.0 :timeline/duration 64.0)]})
    (events/dispatch! {:event/type :shows/play :show-id id})
    (ui/wait-for! #(some? (get-in (state/get-raw-state) [:playback :active-cues (shows/play-key id)])))
    (fixtures/with-clock
      (fn [_]
        (ui/wait-for! #(some? (get-in (state/get-raw-state) [:backend :streaming :cue-warnings (shows/play-key id)])))
        (ui/wait-for! #(ui/visible? "#cue-load-warning"))
        (ui/wait-for! #(str/ends-with? (str (ui/node-text "#cue-load-warning")) line-end))
        (is (= (str "⚠ ~3,000 points: about 8 fps " line-end) (ui/node-text "#cue-load-warning")))))))

(deftest decimate-that-keeps-the-count-is-named-test
  (testing "Q-D-4 B: Decimate at its 25 kpps in a 12 kpps laser's calibration chain keeps the cue at 833 points, 14 fps"
    (laser!)
    (events/write-state! #(-> %
                              (assoc-in [:projectors :a :scan-rate] 12000)
                              (assoc-in [:chains :projector-effects :a :items]
                                        [{:id (random-uuid) :effect-id :decimate :enabled? true :params {:kpps 25 :fps 30}}])))
    (play! [7 3] {:destination-zone {:zone-group-id :all} :items [ring]})
    (fixtures/with-clock
      (fn [_]
        (let [advice "Laser A's Decimate keeps ~830 points: set its scan rate to Laser A's 12 kpps, or lower Laser A's minimum."]
          (ui/wait-for! #(ui/visible? "#grid-cell-warning-7-3"))
          (is (= (str "Too heavy for Laser A: about 14 fps (minimum 20)\n" advice) (cell-tooltip 7 3)) "the cell's tooltip")
          (events/dispatch! {:event/type :cue-chain/open-editor :col 7 :row 3})
          (ui/wait-for! #(ui/visible? "#cue-load-warning"))
          (is (= (str "⚠ ~830 points: about 14 fps on Laser A (12 kpps). " advice) (ui/node-text "#cue-load-warning"))
              "the editor's line"))))))
