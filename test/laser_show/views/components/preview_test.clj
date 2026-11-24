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

(deftest thumbnail-freshness-boundary-test
  ;; A rig card's thumbnail of an observation (output-runtime/
  ;; thumbnail-observation) at a time on its clock: [caption points]
  (let [a [[0.0 0.0 0.0 0.0 0.0] [0.5 0.0 1.0 1.0 1.0]]
        dark [[0.0 0.0 0.0 0.0 0.0] [0.5 0.0 0.0 0.0 0.0]]
        returned (fn [frame at-ns & {:keys [source] :or {source :normal}}]
                   {:run-token 1 :session-token 2 :knowledge :returned
                    :last-ok {:frame frame :at-ns at-ns :source source}})
        shown (fn [observation now-ns]
                ((juxt :caption :points) (preview/thumbnail-presentation observation now-ns)))
        t0 1000000000]
    (testing "fresh from age 0 up to 250 ms, not at it"
      (is (= ["Driver frame" a] (shown (returned a t0) t0)))
      (is (= ["Driver frame" a] (shown (returned a t0) (+ t0 249999999))))
      (is (= ["Stale" nil] (shown (returned a t0) (+ t0 250000000))) "it expires at 250 ms"))
    (testing "a stamp that is missing or in the future is never fresh"
      (is (= ["Unknown" nil] (shown (returned a nil) t0)))
      (is (= ["Unknown" nil] (shown (returned a (inc t0)) t0)))
      (is (= ["Unknown" nil] (shown nil t0)) "no observation at all"))
    (testing "an empty frame that returned is known: a regular one, or the recovery"
      (is (= ["Empty frame" nil] (shown (returned [] t0) t0)))
      (is (= ["Empty (retry)" nil] (shown (returned [] t0 :source :recovery) t0))))
    (testing "after an error nothing is known, whatever A it kept"
      (is (= ["Unknown" nil] (shown (assoc (returned a t0) :knowledge :unknown) t0)))
      (is (= ["Unknown" nil] (shown {:run-token 1 :session-token nil :knowledge :unknown :last-ok nil} t0)) "opening")
      (is (= ["Unknown" nil] (shown :unknown t0)) "an output with no record"))
    (testing "an all-dark frame is still a frame"
      (is (= ["Driver frame" dark] (shown (returned dark t0) t0))))
    (is (= ["Not streaming" nil] (shown :off t0)))
    (testing "the canvas's text says what it shows, never that a device drew it"
      (is (= ["Last successful driver call: frame"
              "Last successful driver call: empty frame"
              "Last successful driver call: empty recovery frame"
              "No recent successful driver return"
              "Output unknown: no current driver result"
              "Not streaming"]
             (map #(:text (apply preview/thumbnail-presentation %))
                  [[(returned a t0) t0] [(returned [] t0) t0] [(returned [] t0 :source :recovery) t0]
                   [(returned a t0) (+ t0 250000000)] [:unknown t0] [:off t0]]))))
    (testing "the very frame, and whose it is"
      (is (identical? a (:points (preview/thumbnail-presentation (returned a t0) t0))))
      (is (= [1 2] (:owner (preview/thumbnail-presentation (returned a t0) t0))))
      (is (nil? (:owner (preview/thumbnail-presentation :off t0)))))))

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
