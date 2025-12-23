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
    (is (false? (dyn/playing?))))
  
  (testing "Start playback"
    (dyn/start-playback!)
    (is (true? (dyn/playing?)))
    (is (pos? (dyn/get-trigger-time))))
  
  (testing "Stop playback"
    (dyn/start-playback!)
    (dyn/stop-playback!)
    (is (false? (dyn/playing?)))))

(deftest test-trigger-time-operations
  (testing "Initial trigger time is 0"
    (dyn/reset-all-dynamic-state!)
    (is (= 0 (dyn/get-trigger-time))))
  
  (testing "Trigger updates trigger-time"
    (let [before (System/currentTimeMillis)]
      (dyn/trigger!)
      (let [after (System/currentTimeMillis)
            trigger-time (dyn/get-trigger-time)]
        (is (>= trigger-time before))
        (is (<= trigger-time after)))))
  
  (testing "Set trigger time manually"
    (dyn/set-trigger-time! 12345)
    (is (= 12345 (dyn/get-trigger-time)))))

(deftest test-active-cell-operations
  (testing "Initial active cell is nil"
    (is (nil? (dyn/get-active-cell))))
  
  (testing "Set active cell"
    (dyn/set-active-cell! 2 3)
    (is (= [2 3] (dyn/get-active-cell))))
  
  (testing "Clear active cell"
    (dyn/set-active-cell! 2 3)
    (dyn/set-active-cell! nil nil)
    (is (nil? (dyn/get-active-cell)))))

(deftest test-trigger-cell
  (testing "Trigger cell sets active cell, playing, and trigger time"
    (dyn/reset-all-dynamic-state!)
    (let [before (System/currentTimeMillis)]
      (dyn/trigger-cell! 3 2)
      (let [after (System/currentTimeMillis)]
        (is (= [3 2] (dyn/get-active-cell)))
        (is (true? (dyn/playing?)))
        (is (>= (dyn/get-trigger-time) before))
        (is (<= (dyn/get-trigger-time) after))))))

;; ============================================================================
;; Grid State Tests
;; ============================================================================

(deftest test-grid-cell-operations
  (testing "Get initial grid cells"
    (let [cells (dyn/get-grid-cells)]
      (is (= :circle (:preset-id (get cells [0 0]))))
      (is (= :spinning-square (:preset-id (get cells [1 0]))))))
  
  (testing "Get specific cell"
    (is (= {:preset-id :circle} (dyn/get-cell 0 0)))
    (is (nil? (dyn/get-cell 0 1))))
  
  (testing "Set cell preset"
    (dyn/set-cell-preset! 0 1 :wave)
    (is (= {:preset-id :wave} (dyn/get-cell 0 1))))
  
  (testing "Clear cell"
    (dyn/set-cell-preset! 0 2 :star)
    (dyn/clear-cell! 0 2)
    (is (nil? (dyn/get-cell 0 2)))))

(deftest test-grid-selection
  (testing "Initial selected cell is nil"
    (is (nil? (dyn/get-selected-cell))))
  
  (testing "Set selected cell"
    (dyn/set-selected-cell! 1 2)
    (is (= [1 2] (dyn/get-selected-cell))))
  
  (testing "Clear selected cell"
    (dyn/set-selected-cell! 1 2)
    (dyn/clear-selected-cell!)
    (is (nil? (dyn/get-selected-cell)))))

(deftest test-grid-move-cell
  (testing "Move cell from one position to another"
    (dyn/set-cell-preset! 5 5 :test-preset)
    (dyn/move-cell! 5 5 6 6)
    (is (nil? (dyn/get-cell 5 5)))
    (is (= {:preset-id :test-preset} (dyn/get-cell 6 6)))))

;; ============================================================================
;; Streaming State Tests
;; ============================================================================

(deftest test-streaming-operations
  (testing "Initially not streaming"
    (is (false? (dyn/streaming?))))
  
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
;; IDN State Tests
;; ============================================================================

(deftest test-idn-operations
  (testing "Initially not connected"
    (is (false? (dyn/idn-connected?))))
  
  (testing "Set IDN connection"
    (dyn/set-idn-connection! true "192.168.1.100:7255" {:engine :test})
    (is (true? (dyn/idn-connected?)))
    (is (= "192.168.1.100:7255" (dyn/get-idn-target)))))

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
    (is (= :circle (dyn/get-selected-preset))))
  
  (testing "Clipboard operations"
    (is (nil? (dyn/get-clipboard)))
    (dyn/set-clipboard! {:preset-id :test})
    (is (= {:preset-id :test} (dyn/get-clipboard)))))

;; ============================================================================
;; Effects State Tests
;; ============================================================================

(deftest test-effects-operations
  (testing "Initially no active effects"
    (is (empty? (dyn/get-active-effects))))
  
  (testing "Set effect at position"
    (dyn/set-effect-at! 0 0 {:type :hue-shift :params {:amount 0.5}})
    (is (= {:type :hue-shift :params {:amount 0.5}} (dyn/get-effect-at 0 0))))
  
  (testing "Clear effect at position"
    (dyn/set-effect-at! 1 1 {:type :test})
    (dyn/clear-effect-at! 1 1)
    (is (nil? (dyn/get-effect-at 1 1)))))

;; ============================================================================
;; Logging State Tests
;; ============================================================================

(deftest test-logging-operations
  (testing "Logging initially disabled"
    (is (false? (dyn/logging-enabled?))))
  
  (testing "Enable logging"
    (dyn/set-logging-enabled! true)
    (is (true? (dyn/logging-enabled?))))
  
  (testing "Get log path"
    (is (= "idn-packets.log" (dyn/get-log-path)))))

;; ============================================================================
;; State Reset Tests
;; ============================================================================

(deftest test-state-reset
  (testing "Reset clears all dynamic state"
    ;; Modify various state
    (dyn/set-bpm! 150.0)
    (dyn/start-playback!)
    (dyn/set-active-cell! 3 4)
    (dyn/set-grid-size! 12 6)
    (dyn/set-idn-connection! true "test:123" nil)
    (dyn/set-logging-enabled! true)
    
    ;; Reset
    (dyn/reset-all-dynamic-state!)
    
    ;; Verify all reset
    (is (= 120.0 (dyn/get-bpm)))
    (is (false? (dyn/playing?)))
    (is (nil? (dyn/get-active-cell)))
    (is (= [8 4] (dyn/get-grid-size)))
    (is (false? (dyn/idn-connected?)))
    (is (false? (dyn/logging-enabled?)))))
