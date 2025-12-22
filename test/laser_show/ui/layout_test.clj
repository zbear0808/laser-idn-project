(ns laser-show.ui.layout-test
  "Unit tests for UI layout coordinate mapping functions."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.ui.layout :as layout]))

;; ============================================================================
;; Grid Helper Tests
;; ============================================================================

(deftest grid-cell-count-test
  (testing "Calculates cell count correctly"
    (is (= 32 (layout/grid-cell-count {:cols 8 :rows 4})))
    (is (= 16 (layout/grid-cell-count {:cols 4 :rows 4})))
    (is (= 1 (layout/grid-cell-count {:cols 1 :rows 1})))
    (is (= 32 (layout/grid-cell-count {})))))

(deftest make-grid-constraints-test
  (testing "Generates MIG layout constraints"
    (let [result (layout/make-grid-constraints {:cols 3 :rows 2 :gap 5 :insets 10})]
      (is (re-find #"gap 5" (:layout result)))
      (is (re-find #"insets 10" (:layout result)))
      (is (= 3 (count (re-seq #"\[" (:cols result)))))
      (is (= 2 (count (re-seq #"\[" (:rows result))))))))

;; ============================================================================
;; Coordinate Mapping Tests
;; ============================================================================

(deftest laser-to-screen-test
  (testing "Maps laser coordinates to screen coordinates"
    (is (= 200 (layout/laser-to-screen 0 400)))
    (is (< (layout/laser-to-screen -32767 400) 1))
    (is (> (layout/laser-to-screen 32767 400) 399))
    (is (> (layout/laser-to-screen 16383 400) 200))
    (is (< (layout/laser-to-screen -16383 400) 200))))

(deftest screen-to-laser-test
  (testing "Maps screen coordinates to laser coordinates"
    (is (= 0 (layout/screen-to-laser 200 400)))
    (is (= -32767 (layout/screen-to-laser 0 400)))
    (is (= 32767 (layout/screen-to-laser 400 400)))))

(deftest laser-screen-round-trip-test
  (testing "Round trip preserves approximate value"
    (doseq [laser-coord [0 16383 -16383 32767 -32767]]
      (let [screen-coord (layout/laser-to-screen laser-coord 400)
            back-to-laser (layout/screen-to-laser screen-coord 400)]
        (is (< (Math/abs (- laser-coord back-to-laser)) 200))))))

(deftest normalized-to-screen-test
  (testing "Maps normalized coordinates to screen"
    (is (= 200 (layout/normalized-to-screen 0 400)))
    (is (= 0 (layout/normalized-to-screen -1 400)))
    (is (= 400 (layout/normalized-to-screen 1 400)))
    (is (= 300 (layout/normalized-to-screen 0.5 400)))))

(deftest screen-to-normalized-test
  (testing "Maps screen coordinates to normalized"
    (is (= 0.0 (double (layout/screen-to-normalized 200 400))))
    (is (= -1.0 (double (layout/screen-to-normalized 0 400))))
    (is (= 1.0 (double (layout/screen-to-normalized 400 400))))))

(deftest normalized-to-laser-test
  (testing "Maps normalized to laser coordinates"
    (is (= 0 (layout/normalized-to-laser 0)))
    (is (= 32767 (layout/normalized-to-laser 1)))
    (is (= -32767 (layout/normalized-to-laser -1)))
    (is (instance? Short (layout/normalized-to-laser 0.5)))))

(deftest laser-to-normalized-test
  (testing "Maps laser to normalized coordinates"
    (is (= 0.0 (layout/laser-to-normalized 0)))
    (is (< (Math/abs (- 1.0 (layout/laser-to-normalized 32767))) 0.0001))
    (is (< (Math/abs (- -1.0 (layout/laser-to-normalized -32767))) 0.0001))))

(deftest normalized-laser-round-trip-test
  (testing "Round trip preserves value"
    (doseq [normalized [-1.0 -0.5 0.0 0.5 1.0]]
      (let [laser-coord (layout/normalized-to-laser normalized)
            back (layout/laser-to-normalized laser-coord)]
        (is (< (Math/abs (- normalized back)) 0.001))))))
