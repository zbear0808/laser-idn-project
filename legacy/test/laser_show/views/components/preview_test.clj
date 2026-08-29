(ns laser-show.views.components.preview-test
  "Unit tests for preview.clj helper functions."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.views.components.preview]))

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
