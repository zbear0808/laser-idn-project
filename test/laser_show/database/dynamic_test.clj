(ns laser-show.database.dynamic-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.database.dynamic :as dyn]))

;; Reset state before each test
(defn reset-fixture [f]
  (dyn/reset-all-dynamic-state!)
  (f))

(use-fixtures :each reset-fixture)

;; ============================================================================
;; Timing State Tests
;; ============================================================================

(deftest test-bpm-operations
  (testing "Get default BPM"
    (is (= 120.0 (dyn/get-bpm))))
  
  (testing "Set BPM"
    (dyn/set-bpm! 140.0)
    (is (= 140.0 (dyn/get-bpm))))
  
  (testing "BPM is stored as double"
    (dyn/set-bpm! 130)
    (is (= 130.0 (dyn/get-bpm)))))

(deftest test-tap-tempo-operations
  (testing "Initial tap times are empty"
    (is (empty? (dyn/get-tap-times))))
  
  (testing "Add tap time"
    (dyn/clear-tap-times!)
    (dyn/add-tap-time! 1000)
    (is (= [1000] (dyn/get-tap-times))))
  
  (testing "Add multiple tap times"
    (dyn/clear-tap-times!)
    (dyn/add-tap-time! 1000)
    (dyn/add-tap-time! 2000)
    (dyn/add-tap-time! 3000)
    (is (= [1000 2000 3000] (dyn/get-tap-times))))
  
  (testing "Clear tap times"
    (dyn/clear-tap-times!)
    (dyn/add-tap-time! 1000)
    (dyn/clear-tap-times!)
    (is (empty? (dyn/get-tap-times)))))

(deftest test-beat-position
  (testing "Initial beat position is 0.0"
    (is (= 0.0 (dyn/get-beat-position))))
  
  (testing "Update beat position"
    (dyn/update-beat-position! 0.5)
    (is (= 0.5 (dyn/get-beat-position)))))

;; ============================================================================
;; Playback State Tests
;; ============================================================================

(deftest test-playback-operations
  (testing "Initially not playing"
    (is (false? (dyn/is-playing?))))
  
  (testing "Start playback"
    (let [animation {:type :circle}]
      (dyn/start-playback! animation)
      (is (true? (dyn/is-playing?)))
      (is (= animation (dyn/get-current-animation)))
      (is (pos? (:animation-start-time @dyn/!playback)))))
  
  (testing "Stop playback"
    (dyn/start-playback! {:type :square})
    (dyn/stop-playback!)
    (is (false? (dyn/is-playing?)))
    (is (nil? (dyn/get-current-animation)))))

(deftest test-active-cell-operations
  (testing "Initial active cell is [nil nil]"
    (is (= [nil nil] (dyn/get-active-cell))))
  
  (testing "Set active cell"
    (dyn/set-active-cell! 2 3)
    (is (= [2 3] (dyn/get-active-cell))))
  
  (testing "Clear active cell"
    (dyn/set-active-cell! 2 3)
    (dyn/set-active-cell! nil nil)
    (is (= [nil nil] (dyn/get-active-cell)))))

;; ============================================================================
;; Streaming State Tests
;; ============================================================================

(deftest test-streaming-operations
  (testing "Initially not streaming"
    (is (false? (dyn/is-streaming?))))
  
  (testing "Get empty engines initially"
    (is (empty? (dyn/get-streaming-engines))))
  
  (testing "Add streaming engine"
    (let [engine {:id :engine-1}]
      (dyn/add-streaming-engine! :proj-1 engine)
      (is (= {:proj-1 engine} (dyn/get-streaming-engines)))))
  
  (testing "Remove streaming engine"
    (dyn/add-streaming-engine! :proj-1 {:id :engine-1})
    (dyn/add-streaming-engine! :proj-2 {:id :engine-2})
    (dyn/remove-streaming-engine! :proj-1)
    (is (= {:proj-2 {:id :engine-2}} (dyn/get-streaming-engines)))))

;; ============================================================================
;; Input State Tests
;; ============================================================================

(deftest test-input-operations
  (testing "MIDI is enabled by default"
    (is (true? (dyn/midi-enabled?))))
  
  (testing "Disable MIDI"
    (dyn/enable-midi! false)
    (is (false? (dyn/midi-enabled?))))
  
  (testing "OSC is disabled by default"
    (is (false? (dyn/osc-enabled?))))
  
  (testing "Enable OSC"
    (dyn/enable-osc! true)
    (is (true? (dyn/osc-enabled?)))))

;; ============================================================================
;; UI State Tests
;; ============================================================================

(deftest test-ui-operations
  (testing "Get default grid size"
    (is (= [8 4] (dyn/get-grid-size))))
  
  (testing "Set grid size"
    (dyn/set-grid-size! 10 5)
    (is (= [10 5] (dyn/get-grid-size))))
  
  (testing "Selected preset is initially nil"
    (is (nil? (dyn/get-selected-preset))))
  
  (testing "Set selected preset"
    (dyn/set-selected-preset! :circle)
    (is (= :circle (dyn/get-selected-preset)))))

;; ============================================================================
;; State Reset Tests
;; ============================================================================

(deftest test-state-reset
  (testing "Reset clears all dynamic state"
    ;; Modify various state
    (dyn/set-bpm! 150.0)
    (dyn/start-playback! {:type :triangle})
    (dyn/set-active-cell! 3 4)
    (dyn/set-grid-size! 12 6)
    
    ;; Reset
    (dyn/reset-all-dynamic-state!)
    
    ;; Verify all reset
    (is (= 120.0 (dyn/get-bpm)))
    (is (false? (dyn/is-playing?)))
    (is (= [nil nil] (dyn/get-active-cell)))
    (is (= [8 4] (dyn/get-grid-size)))))
