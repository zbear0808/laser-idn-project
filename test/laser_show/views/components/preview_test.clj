(ns laser-show.views.components.preview-test
  "Unit tests for preview.clj helper functions."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest testing is]]
            [laser-show.dev-config :as dev-config]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.views.components.preview :as preview]))

(deftest cell-points-test
  (let [preview {:points [:every-point]
                 :ref-frames {:auto/left [:left] :graphics [:graphics]}
                 :projector-frames {:p1 (delay {:frame [:p1-draws]}) :p2 (delay nil)}}]
    (is (= [:every-point] (preview/cell-points preview {:target nil})) "the master cell: everything")
    (is (= [:every-point] (preview/cell-points preview {})))
    (is (= [:left] (preview/cell-points preview {:target :auto/left})) "a group: what is sent to it")
    (is (= [] (preview/cell-points preview {:target :auto/right})) "nothing sent to it")
    (is (= [:p1-draws] (preview/cell-points preview {:target :auto.laser/p1}))
        "a laser: what it draws, as its engine composes it")
    (is (= [] (preview/cell-points preview {:target :auto.laser/p2})) "a laser nothing reaches")
    (is (= [] (preview/cell-points preview {:target :auto.laser/p9})) "or that isn't in the routing")
    (is (= [:graphics] (preview/cell-points preview {:zone-group-id :graphics})) "an old cell reads as its target")
    (is (= [] (preview/cell-points nil {:target :auto/left})) "no preview yet")))

(deftest frame-stats-label-test
  (testing "shows average frame latency from the stats the frame service provides"
    (is (= "123µs/frame" (preview/frame-stats-label {:avg-latency-us 123.4
                                                     :p95-latency-us 200}))))
  (testing "blank before any stats exist"
    (is (= "" (preview/frame-stats-label nil)))
    (is (= "" (preview/frame-stats-label {})))))

(deftest canvas-side-test
  (testing "square canvases, as big as the pane lets every cell be: here the width decides"
    (is (= 197 (preview/canvas-side 2 2 420 627 20)) "1200×800, 2x2")
    (is (= 400 (preview/canvas-side 1 1 420 627 20)) "one cell"))
  (testing "a wide, short pane: the height decides, with the zone label above each canvas"
    (is (= 117 (preview/canvas-side 2 2 700 300 20)))
    (is (= 137 (preview/canvas-side 2 2 700 300 0)) "no labels"))
  (testing "before the pane is laid out, the 400×400 the config used to give"
    (is (= 175 (preview/canvas-side 2 2 nil nil 20))))
  (testing "never negative"
    (is (= 0 (preview/canvas-side 3 2 10 10 20)))))

(deftest frame-stats-only-in-dev-mode-test
  (let [header-ids (fn [dev?]
                     (with-redefs [dev-config/dev-mode? (constantly dev?)]
                       (->> (preview/preview-grid-header
                             {:fx/context (fx/create-context
                                           (assoc-in (domains/build-initial-state)
                                                     [:backend :streaming :frame-stats] {:avg-latency-us 123})
                                           state/cache-factory)})
                            :children
                            (keep :id)
                            set)))]
    (is (contains? (header-ids true) "preview-frame-stats"))
    (is (not (contains? (header-ids false) "preview-frame-stats")) "µs per frame is for developers")
    (is (contains? (header-ids false) "preview-layout-selector"))))
