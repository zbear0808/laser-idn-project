(ns laser-show.state.subscriptions-test
  "Tests for state queries and subscription helpers.
   These tests verify the query functions that provide data for UI subscriptions."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [laser-show.state.core :as state]
            [laser-show.state.queries :as queries]
            [laser-show.state.domains :as domains]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn reset-state-fixture
  "Initialize and reset state before and after each test."
  [f]
  ;; Ensure state is initialized
  (when-not (state/initialized?)
    (let [initial-state (require 'laser-show.state.domains)
          domains-ns (find-ns 'laser-show.state.domains)]
      (state/init-state! ((ns-resolve domains-ns 'build-initial-state)))))
  (state/reset-all!)
  (f)
  (state/reset-all!))

(use-fixtures :each reset-state-fixture)

;; ============================================================================
;; Grid Cell Query Tests
;; ============================================================================

(deftest active-cell-preset-test
  (testing "active-cell returns nil when nothing active"
    (is (nil? (queries/active-cell))))
  
  (testing "active-cell returns preset of active cell"
    (state/set-cell-preset! 1 1 :test-preset)
    (state/trigger-cell! 1 1)
    (is (= [1 1] (queries/active-cell)))
    (is (= :test-preset (queries/cell-preset 1 1)))))

(deftest grid-cell-query-test
  (testing "empty cell"
    (is (nil? (queries/cell 5 3))))
  
  (testing "cell with preset"
    (state/set-cell-preset! 1 1 :my-preset)
    (let [cell (queries/cell 1 1)]
      (is (= :my-preset (:preset-id cell)))))
  
  (testing "active cell"
    (state/set-cell-preset! 3 3 :preset)
    (state/trigger-cell! 3 3)
    (is (= [3 3] (queries/active-cell)))))

(deftest all-grid-cells-test
  (testing "returns cells map"
    (state/set-cell-preset! 0 0 :cell-a)
    (state/set-cell-preset! 1 0 :cell-b)
    (let [all-cells (queries/grid-cells)]
      (is (map? all-cells))
      (is (= :cell-a (:preset-id (get all-cells [0 0]))))
      (is (= :cell-b (:preset-id (get all-cells [1 0])))))))

;; ============================================================================
;; Effect Cell Query Tests
;; ============================================================================

(deftest effect-cell-query-test
  (testing "empty effect cell"
    (is (nil? (queries/effect-cell 5 3))))
  
  (testing "cell with single effect"
    (state/add-effect-to-cell! 1 1 {:effect-id :scale :params {:factor 1.5}})
    (let [cell (queries/effect-cell 1 1)]
      (is (some? cell))
      (is (= 1 (count (:effects cell))))
      (is (= :scale (:effect-id (first (:effects cell)))))))
  
  (testing "cell with multiple effects"
    (state/add-effect-to-cell! 2 2 {:effect-id :scale :params {:factor 1.5}})
    (state/add-effect-to-cell! 2 2 {:effect-id :rotate :params {:angle 45}})
    (state/add-effect-to-cell! 2 2 {:effect-id :color :params {:hue 0.5}})
    (let [cell (queries/effect-cell 2 2)]
      (is (= 3 (count (:effects cell))))
      (is (= :scale (:effect-id (first (:effects cell)))))))
  
  (testing "cell active state"
    (state/add-effect-to-cell! 3 3 {:effect-id :scale :params {:factor 1.0}})
    (let [cell (queries/effect-cell 3 3)]
      (is (boolean? (:active cell))))))

;; ============================================================================
;; Playback Query Tests
;; ============================================================================

(deftest playback-query-test
  (testing "initial playback state"
    (is (false? (queries/playing?)))
    (is (nil? (queries/active-cell))))
  
  (testing "playing state"
    (state/set-cell-preset! 1 1 :test-preset)
    (state/trigger-cell! 1 1)
    (is (true? (queries/playing?)))
    (is (= [1 1] (queries/active-cell)))
    (is (number? (queries/trigger-time)))))

;; ============================================================================
;; Timing Query Tests
;; ============================================================================

(deftest timing-query-test
  (testing "default timing"
    (is (= 120.0 (queries/bpm))))
  
  (testing "timing after BPM change"
    (state/set-timing-bpm! 60.0)
    (is (= 60.0 (queries/bpm)))))

;; ============================================================================
;; Project Query Tests
;; ============================================================================

(deftest project-query-test
  (testing "initial project state"
    (is (nil? (queries/project-folder)))
    (is (false? (queries/has-project?))))
  
  (testing "project status with folder"
    (state/set-project-folder! "/path/to/project")
    (state/mark-project-dirty!)
    (is (= "/path/to/project" (queries/project-folder)))
    (is (true? (queries/has-project?)))
    (is (true? (queries/project-dirty?)))))

;; ============================================================================
;; Connection Query Tests
;; ============================================================================

(deftest idn-query-test
  (testing "initial IDN status"
    (is (false? (queries/idn-connected?)))
    (is (nil? (queries/idn-target))))
  
  (testing "connected IDN status"
    (state/set-idn-connection! true "192.168.1.100" nil)
    (is (true? (queries/idn-connected?)))
    (is (= "192.168.1.100" (queries/idn-target)))))

(deftest streaming-query-test
  (testing "initial streaming status"
    (is (false? (queries/streaming-running?)))))

;; ============================================================================
;; UI State Query Tests
;; ============================================================================

(deftest selection-query-test
  (testing "initial selection state"
    (is (nil? (queries/selected-cell)))
    (is (nil? (queries/selected-preset)))))

(deftest drag-query-test
  (testing "no drag in progress"
    (let [drag (queries/drag)]
      (is (or (nil? drag) (false? (:active? drag))))))
  
  (testing "drag in progress"
    (state/start-drag! :grid-cell :main-grid [1 2] {:preset-id :test})
    (let [drag (queries/drag)]
      (is (some? drag))
      (is (= :grid-cell (:source-type drag)))
      (is (= [1 2] (:source-key drag))))))

;; ============================================================================
;; Entity Query Tests
;; ============================================================================

(deftest projector-query-test
  (testing "projector queries"
    (state/add-projector! :proj-1 {:host "192.168.1.100"})
    (is (= {:host "192.168.1.100"} (queries/projector :proj-1)))
    (is (contains? (set (queries/projector-ids)) :proj-1))))

(deftest zone-query-test
  (testing "zone queries"
    (state/add-zone! :zone-1 {:name "Zone 1"})
    (is (= "Zone 1" (:name (queries/zone :zone-1))))
    (is (contains? (set (queries/zone-ids)) :zone-1))))

(deftest cue-query-test
  (testing "cue queries"
    (state/add-cue! :cue-1 {:name "Cue 1" :preset-id :circle})
    (is (= "Cue 1" (:name (queries/cue :cue-1))))
    (is (= :circle (:preset-id (queries/cue :cue-1))))
    (is (contains? (set (queries/cue-ids)) :cue-1))))
