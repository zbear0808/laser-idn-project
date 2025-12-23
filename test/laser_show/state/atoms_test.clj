(ns laser-show.state.atoms-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.state.atoms :as state]))

;; Reset state before each test
(defn reset-fixture [f]
  (state/reset-all!)
  (f))

(use-fixtures :each reset-fixture)

;; ============================================================================
;; Timing State Tests
;; ============================================================================

(deftest test-bpm-operations
  (testing "Get default BPM"
    (is (= state/default-bpm (state/get-bpm))))
  
  (testing "Set BPM"
    (state/set-bpm! 140.0)
    (is (= 140.0 (state/get-bpm))))
  
  (testing "BPM is stored as double"
    (state/set-bpm! 130)
    (is (= 130.0 (state/get-bpm)))))

(deftest test-tap-tempo-operations
  (testing "Initial tap times are empty"
    (is (empty? (state/get-tap-times))))
  
  (testing "Add tap time"
    (state/clear-tap-times!)
    (state/add-tap-time! 1000)
    (is (= [1000] (state/get-tap-times))))
  
  (testing "Add multiple tap times"
    (state/clear-tap-times!)
    (state/add-tap-time! 1000)
    (state/add-tap-time! 2000)
    (state/add-tap-time! 3000)
    (is (= [1000 2000 3000] (state/get-tap-times))))
  
  (testing "Clear tap times"
    (state/clear-tap-times!)
    (state/add-tap-time! 1000)
    (state/clear-tap-times!)
    (is (empty? (state/get-tap-times)))))

(deftest test-beat-position
  (testing "Initial beat position is 0.0"
    (is (= 0.0 (state/get-beat-position))))
  
  (testing "Update beat position"
    (state/update-beat-position! 0.5)
    (is (= 0.5 (state/get-beat-position)))))

;; ============================================================================
;; Playback State Tests
;; ============================================================================

(deftest test-playback-operations
  (testing "Initially not playing"
    (is (false? (state/playing?))))
  
  (testing "Start playback"
    (state/start-playback!)
    (is (true? (state/playing?)))
    (is (pos? (state/get-trigger-time))))
  
  (testing "Stop playback"
    (state/start-playback!)
    (state/stop-playback!)
    (is (false? (state/playing?)))))

(deftest test-trigger-time-operations
  (testing "Initial trigger time is 0"
    (state/reset-all!)
    (is (= 0 (state/get-trigger-time))))
  
  (testing "Trigger updates trigger-time"
    (let [before (System/currentTimeMillis)]
      (state/trigger!)
      (let [after (System/currentTimeMillis)
            trigger-time (state/get-trigger-time)]
        (is (>= trigger-time before))
        (is (<= trigger-time after)))))
  
  (testing "Set trigger time manually"
    (state/set-trigger-time! 12345)
    (is (= 12345 (state/get-trigger-time)))))

(deftest test-active-cell-operations
  (testing "Initial active cell is nil"
    (is (nil? (state/get-active-cell))))
  
  (testing "Set active cell"
    (state/set-active-cell! 2 3)
    (is (= [2 3] (state/get-active-cell))))
  
  (testing "Clear active cell"
    (state/set-active-cell! 2 3)
    (state/set-active-cell! nil nil)
    (is (nil? (state/get-active-cell)))))

(deftest test-trigger-cell
  (testing "Trigger cell sets active cell, playing, and trigger time"
    (state/reset-all!)
    (let [before (System/currentTimeMillis)]
      (state/trigger-cell! 3 2)
      (let [after (System/currentTimeMillis)]
        (is (= [3 2] (state/get-active-cell)))
        (is (true? (state/playing?)))
        (is (>= (state/get-trigger-time) before))
        (is (<= (state/get-trigger-time) after))))))

;; ============================================================================
;; Grid State Tests
;; ============================================================================

(deftest test-grid-cell-operations
  (testing "Get initial grid cells"
    (let [cells (state/get-grid-cells)]
      (is (= :circle (:preset-id (get cells [0 0]))))
      (is (= :spinning-square (:preset-id (get cells [1 0]))))))
  
  (testing "Get specific cell"
    (is (= {:preset-id :circle} (state/get-cell 0 0)))
    (is (nil? (state/get-cell 0 1))))
  
  (testing "Set cell preset"
    (state/set-cell-preset! 0 1 :wave)
    (is (= {:preset-id :wave} (state/get-cell 0 1))))
  
  (testing "Clear cell"
    (state/set-cell-preset! 0 2 :star)
    (state/clear-cell! 0 2)
    (is (nil? (state/get-cell 0 2)))))

(deftest test-grid-selection
  (testing "Initial selected cell is nil"
    (is (nil? (state/get-selected-cell))))
  
  (testing "Set selected cell"
    (state/set-selected-cell! 1 2)
    (is (= [1 2] (state/get-selected-cell))))
  
  (testing "Clear selected cell"
    (state/set-selected-cell! 1 2)
    (state/clear-selected-cell!)
    (is (nil? (state/get-selected-cell)))))

(deftest test-grid-move-cell
  (testing "Move cell from one position to another"
    (state/set-cell-preset! 5 5 :test-preset)
    (state/move-cell! 5 5 6 6)
    (is (nil? (state/get-cell 5 5)))
    (is (= {:preset-id :test-preset} (state/get-cell 6 6)))))

;; ============================================================================
;; Streaming State Tests
;; ============================================================================

(deftest test-streaming-operations
  (testing "Initially not streaming"
    (is (false? (state/streaming?))))
  
  (testing "Get empty engines initially"
    (is (empty? (state/get-streaming-engines))))
  
  (testing "Add streaming engine"
    (let [engine {:id :engine-1}]
      (state/add-streaming-engine! :proj-1 engine)
      (is (= {:proj-1 engine} (state/get-streaming-engines)))))
  
  (testing "Remove streaming engine"
    (state/add-streaming-engine! :proj-1 {:id :engine-1})
    (state/add-streaming-engine! :proj-2 {:id :engine-2})
    (state/remove-streaming-engine! :proj-1)
    (is (= {:proj-2 {:id :engine-2}} (state/get-streaming-engines)))))

;; ============================================================================
;; IDN State Tests
;; ============================================================================

(deftest test-idn-operations
  (testing "Initially not connected"
    (is (false? (state/idn-connected?))))
  
  (testing "Set IDN connection"
    (state/set-idn-connection! true "192.168.1.100:7255" {:engine :test})
    (is (true? (state/idn-connected?)))
    (is (= "192.168.1.100:7255" (state/get-idn-target)))))

;; ============================================================================
;; Input State Tests
;; ============================================================================

(deftest test-input-operations
  (testing "MIDI is enabled by default"
    (is (true? (state/midi-enabled?))))
  
  (testing "Disable MIDI"
    (state/enable-midi! false)
    (is (false? (state/midi-enabled?))))
  
  (testing "OSC is disabled by default"
    (is (false? (state/osc-enabled?))))
  
  (testing "Enable OSC"
    (state/enable-osc! true)
    (is (true? (state/osc-enabled?)))))

;; ============================================================================
;; UI State Tests
;; ============================================================================

(deftest test-ui-operations
  (testing "Get default grid size"
    (is (= [8 4] (state/get-grid-size))))
  
  (testing "Set grid size"
    (state/set-grid-size! 10 5)
    (is (= [10 5] (state/get-grid-size))))
  
  (testing "Selected preset is initially nil"
    (is (nil? (state/get-selected-preset))))
  
  (testing "Set selected preset"
    (state/set-selected-preset! :circle)
    (is (= :circle (state/get-selected-preset))))
  
  (testing "Clipboard operations"
    (is (nil? (state/get-clipboard)))
    (state/set-clipboard! {:preset-id :test})
    (is (= {:preset-id :test} (state/get-clipboard)))))

;; ============================================================================
;; Effects State Tests
;; ============================================================================

(deftest test-effects-operations
  (testing "Initially no active effects"
    (is (empty? (state/get-active-effects))))
  
  (testing "Set effect at position"
    (state/set-effect-at! 0 0 {:type :hue-shift :params {:amount 0.5}})
    (is (= {:type :hue-shift :params {:amount 0.5}} (state/get-effect-at 0 0))))
  
  (testing "Clear effect at position"
    (state/set-effect-at! 1 1 {:type :test})
    (state/clear-effect-at! 1 1)
    (is (nil? (state/get-effect-at 1 1)))))

;; ============================================================================
;; Logging State Tests
;; ============================================================================

(deftest test-logging-operations
  (testing "Logging initially disabled"
    (is (false? (state/logging-enabled?))))
  
  (testing "Enable logging"
    (state/set-logging-enabled! true)
    (is (true? (state/logging-enabled?))))
  
  (testing "Get log path"
    (is (= state/default-log-path (state/get-log-path)))))

;; ============================================================================
;; State Reset Tests
;; ============================================================================

(deftest test-state-reset
  (testing "Reset clears all runtime state"
    ;; Modify various state
    (state/set-bpm! 150.0)
    (state/start-playback!)
    (state/set-active-cell! 3 4)
    (state/set-grid-size! 12 6)
    (state/set-idn-connection! true "test:123" nil)
    (state/set-logging-enabled! true)
    
    ;; Reset
    (state/reset-all!)
    
    ;; Verify all reset
    (is (= state/default-bpm (state/get-bpm)))
    (is (false? (state/playing?)))
    (is (nil? (state/get-active-cell)))
    (is (= [8 4] (state/get-grid-size)))
    (is (false? (state/idn-connected?)))
    (is (false? (state/logging-enabled?)))))
