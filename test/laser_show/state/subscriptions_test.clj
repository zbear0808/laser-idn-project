(ns laser-show.state.subscriptions-test
  "Tests for state subscriptions."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [laser-show.state.subscriptions :as subs]
            [laser-show.state.atoms :as state]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn reset-state-fixture
  "Reset all state before and after each test."
  [f]
  (state/reset-all!)
  (f)
  (state/reset-all!))

(use-fixtures :each reset-state-fixture)

;; ============================================================================
;; Grid Cell Subscription Tests
;; ============================================================================

(deftest active-cell-preset-test
  (testing "active-cell-preset returns nil when nothing active"
    (is (nil? (subs/active-cell-preset))))
  
  (testing "active-cell-preset returns preset of active cell"
    (state/set-cell-preset! 1 1 :test-preset)
    (state/trigger-cell! 1 1)
    (is (= :test-preset (subs/active-cell-preset)))))

(deftest grid-cell-display-test
  (testing "empty cell display"
    (let [display (subs/grid-cell-display 5 3)]
      (is (nil? (:preset-id display)))
      (is (false? (:active? display)))
      (is (false? (:selected? display)))
      (is (false? (:has-content? display)))))
  
  (testing "cell with preset"
    (state/set-cell-preset! 1 1 :my-preset)
    (let [display (subs/grid-cell-display 1 1)]
      (is (= :my-preset (:preset-id display)))
      (is (true? (:has-content? display)))))
  
  (testing "selected cell"
    (state/set-selected-cell! 2 2)
    (let [display (subs/grid-cell-display 2 2)]
      (is (true? (:selected? display)))))
  
  (testing "active cell"
    (state/set-cell-preset! 3 3 :preset)
    (state/trigger-cell! 3 3)
    (let [display (subs/grid-cell-display 3 3)]
      (is (true? (:active? display))))))

(deftest all-grid-cells-display-test
  (testing "returns display data for all cells"
    (let [all-displays (subs/all-grid-cells-display)
          [cols rows] (state/get-grid-size)]
      ;; Should have entry for every cell
      (is (= (* cols rows) (count all-displays)))
      ;; Each entry should be keyed by [col row]
      (is (contains? all-displays [0 0]))
      (is (contains? all-displays [(dec cols) (dec rows)])))))

;; ============================================================================
;; Effect Cell Subscription Tests
;; ============================================================================

(deftest effect-cell-display-test
  (testing "empty effect cell"
    (let [display (subs/effect-cell-display 5 3)]
      (is (false? (:has-effect? display)))
      (is (zero? (:effect-count display)))
      (is (nil? (:first-effect-id display)))
      (is (nil? (:display-text display)))))
  
  (testing "cell with single effect"
    (state/add-effect-to-cell! 1 1 {:effect-id :scale :params {:factor 1.5}})
    (let [display (subs/effect-cell-display 1 1)]
      (is (true? (:has-effect? display)))
      (is (= 1 (:effect-count display)))
      (is (= :scale (:first-effect-id display)))
      (is (= "scale" (:display-text display)))))
  
  (testing "cell with multiple effects"
    (state/add-effect-to-cell! 2 2 {:effect-id :scale :params {:factor 1.5}})
    (state/add-effect-to-cell! 2 2 {:effect-id :rotate :params {:angle 45}})
    (state/add-effect-to-cell! 2 2 {:effect-id :color :params {:hue 0.5}})
    (let [display (subs/effect-cell-display 2 2)]
      (is (= 3 (:effect-count display)))
      (is (= :scale (:first-effect-id display)))
      (is (= "scale +2" (:display-text display)))))
  
  (testing "cell with modulated effect"
    (state/add-effect-to-cell! 3 3 {:effect-id :scale :params {:factor 1.0 :modulator {:type :sine}}})
    (let [display (subs/effect-cell-display 3 3)]
      (is (true? (:modulated? display))))))

;; ============================================================================
;; Playback Subscription Tests
;; ============================================================================

(deftest playback-status-test
  (testing "initial playback status"
    (let [status (subs/playback-status)]
      (is (false? (:playing? status)))
      (is (nil? (:active-cell status)))
      (is (nil? (:preset-id status)))))
  
  (testing "playing status"
    (state/set-cell-preset! 1 1 :test-preset)
    (state/trigger-cell! 1 1)
    (let [status (subs/playback-status)]
      (is (true? (:playing? status)))
      (is (= [1 1] (:active-cell status)))
      (is (= :test-preset (:preset-id status)))
      (is (number? (:elapsed-ms status))))))

;; ============================================================================
;; Timing Subscription Tests
;; ============================================================================

(deftest timing-info-test
  (testing "default timing info"
    (let [info (subs/timing-info)]
      (is (= 120.0 (:bpm info)))
      (is (number? (:beat-position info)))
      (is (= 500.0 (:ms-per-beat info)))))
  
  (testing "timing info after BPM change"
    (state/set-bpm! 60.0)
    (let [info (subs/timing-info)]
      (is (= 60.0 (:bpm info)))
      (is (= 1000.0 (:ms-per-beat info))))))

;; ============================================================================
;; Project Subscription Tests
;; ============================================================================

(deftest project-status-test
  (testing "initial project status"
    (let [status (subs/project-status)]
      (is (false? (:has-project? status)))
      (is (nil? (:folder status)))
      (is (false? (:dirty? status)))))
  
  (testing "project status with folder"
    (state/set-project-folder! "/path/to/project")
    (state/mark-project-dirty!)
    (let [status (subs/project-status)]
      (is (true? (:has-project? status)))
      (is (= "/path/to/project" (:folder status)))
      (is (true? (:dirty? status))))))

;; ============================================================================
;; Connection Subscription Tests
;; ============================================================================

(deftest idn-status-test
  (testing "initial IDN status"
    (let [status (subs/idn-status)]
      (is (false? (:connected? status)))
      (is (nil? (:target status)))))
  
  (testing "connected IDN status"
    (state/set-idn-connection! true "192.168.1.100" nil)
    (let [status (subs/idn-status)]
      (is (true? (:connected? status)))
      (is (= "192.168.1.100" (:target status))))))

(deftest streaming-status-test
  (testing "initial streaming status"
    (let [status (subs/streaming-status)]
      (is (false? (:streaming? status)))
      (is (zero? (:engine-count status))))))

;; ============================================================================
;; UI State Subscription Tests
;; ============================================================================

(deftest selection-state-test
  (testing "initial selection state"
    (let [sel (subs/selection-state)]
      (is (nil? (:selected-cell sel)))
      (is (nil? (:selected-preset sel)))))
  
  (testing "selection state with selected cell"
    (state/set-selected-cell! 2 3)
    (state/set-selected-preset! :my-preset)
    (let [sel (subs/selection-state)]
      (is (= [2 3] (:selected-cell sel)))
      (is (= :my-preset (:selected-preset sel))))))

(deftest drag-state-test
  (testing "no drag in progress"
    (is (nil? (subs/drag-state))))
  
  (testing "drag in progress"
    (state/start-drag! :grid-cell :main-grid [1 2] {:preset-id :test})
    (let [drag (subs/drag-state)]
      (is (some? drag))
      (is (= :grid-cell (:source-type drag)))
      (is (= [1 2] (:source-key drag))))))
