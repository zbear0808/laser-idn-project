(ns laser-show.views.components.preview-test
  "Unit tests for preview.clj helper functions."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest testing is]]
            [laser-show.dev-config :as dev-config]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.views.components.preview :as preview]))

;; Access the private function via var
(def get-zone-filtered-points
  #'laser-show.views.components.preview/get-zone-filtered-points)

(deftest get-zone-filtered-points-test
  
  (testing "nil zone-id returns all combined points (master view)"
    (let [frame-data {:points [1 2 3]}
          result (get-zone-filtered-points frame-data nil)]
      (is (= [1 2 3] result))))
  
  (testing "specific zone-id returns points for that zone"
    (let [frame-data {:zone-frames {:left [:a :b]
                                    :right [:c :d]}}
          result (get-zone-filtered-points frame-data :left)]
      (is (= [:a :b] result)
          "Should return points for :left zone directly")))
  
  (testing "zone not in zone-frames returns empty"
    (let [frame-data {:zone-frames {:all [:points]}}
          result (get-zone-filtered-points frame-data :left)]
      (is (= [] result)
          "Zone :left not present in zone-frames returns empty")))
  
  (testing "zone :all is looked up directly (not a wildcard)"
    (let [frame-data {:zone-frames {:all [:all-points]
                                    :left [:left-points]}}
          result (get-zone-filtered-points frame-data :all)]
      (is (= [:all-points] result)
          ":all is a regular zone-id, looked up directly")))
  
  (testing "empty zone-frames handles gracefully"
    (let [frame-data {:zone-frames {}}
          result (get-zone-filtered-points frame-data :left)]
      (is (= [] result))))
  
  (testing "missing :zone-frames key handles gracefully"
    (let [frame-data {:points [1 2 3]}
          result (get-zone-filtered-points frame-data :left)]
      (is (= [] result)
          "Returns empty vector when :zone-frames key is missing")))
  
  (testing "zone with points from multiple cues (pre-aggregated)"
    (let [frame-data {:zone-frames {:left [:a :b :c :d]}}  ;; Already aggregated
          result (get-zone-filtered-points frame-data :left)]
      (is (= [:a :b :c :d] result)
          "zone-frames already contains aggregated points from all cues"))))

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
