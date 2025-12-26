(ns laser-show.common.timing-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.common.timing :as timing]))

;; ============================================================================
;; High-Resolution Time Tests
;; ============================================================================

(deftest nanotime-test
  (testing "nanotime returns increasing values"
    (let [t1 (timing/nanotime)
          _ (Thread/sleep 1)
          t2 (timing/nanotime)]
      (is (< t1 t2) "Time should increase"))))

(deftest nanotime-us-test
  (testing "nanotime-us returns microseconds"
    (let [t1 (timing/nanotime-us)
          _ (Thread/sleep 1)
          t2 (timing/nanotime-us)]
      (is (< t1 t2) "Microsecond time should increase"))))

(deftest nanotime-ms-test
  (testing "nanotime-ms returns milliseconds"
    (let [t1 (timing/nanotime-ms)
          _ (Thread/sleep 10)
          t2 (timing/nanotime-ms)]
      (is (>= (- t2 t1) 10) "Should measure at least 10ms"))))

;; ============================================================================
;; Precise Sleep Tests
;; ============================================================================

(deftest precise-sleep-until-accuracy-test
  (testing "precise-sleep-until accuracy within 1ms"
    (let [start (timing/nanotime)
          target (+ start 10000000) ; 10ms from now
          _ (timing/precise-sleep-until target)
          actual (timing/nanotime)
          error-us (quot (- actual target) 1000)]
      (is (< (Math/abs error-us) 1000)
          (str "Sleep error: " error-us "μs, expected <1000μs")))))

(deftest precise-sleep-nanos-test
  (testing "precise-sleep-nanos sleeps for correct duration"
    (let [start (timing/nanotime)
          duration 5000000 ; 5ms
          _ (timing/precise-sleep-nanos duration)
          elapsed (- (timing/nanotime) start)
          error (- elapsed duration)]
      (is (< (Math/abs error) 1000000)
          (str "Sleep error: " (quot error 1000) "μs")))))

(deftest precise-sleep-us-test
  (testing "precise-sleep-us sleeps for microseconds"
    (let [start (timing/nanotime)
          duration-us 1000 ; 1ms
          _ (timing/precise-sleep-us duration-us)
          elapsed-us (quot (- (timing/nanotime) start) 1000)]
      (is (>= elapsed-us duration-us)
          "Should sleep at least the requested duration"))))

(deftest precise-sleep-ms-test
  (testing "precise-sleep-ms sleeps for milliseconds"
    (let [start (timing/nanotime)
          duration-ms 5
          _ (timing/precise-sleep-ms duration-ms)
          elapsed-ms (quot (- (timing/nanotime) start) 1000000)]
      (is (>= elapsed-ms duration-ms)
          "Should sleep at least 5ms"))))

;; ============================================================================
;; Measurement Tests
;; ============================================================================

(deftest measure-nanos-test
  (testing "measure-nanos captures execution time"
    (let [{:keys [result nanos]} (timing/measure-nanos #(Thread/sleep 5))]
      (is (nil? result))
      (is (>= nanos 5000000) "Should measure at least 5ms"))))

(deftest measure-us-test
  (testing "measure-us returns microseconds"
    (let [{:keys [us]} (timing/measure-us #(Thread/sleep 5))]
      (is (>= us 5000) "Should measure at least 5000μs"))))

(deftest measure-ms-test
  (testing "measure-ms returns milliseconds"
    (let [{:keys [ms]} (timing/measure-ms #(Thread/sleep 5))]
      (is (>= ms 5) "Should measure at least 5ms"))))

;; ============================================================================
;; Conversion Tests
;; ============================================================================

(deftest nanos-micros-conversion-test
  (testing "nanosecond to microsecond conversion"
    (is (= 1000 (timing/nanos->micros 1000000)))
    (is (= 1000000 (timing/micros->nanos 1000)))))

(deftest nanos-millis-conversion-test
  (testing "nanosecond to millisecond conversion"
    (is (= 1 (timing/nanos->millis 1000000)))
    (is (= 1000000 (timing/millis->nanos 1)))))

(deftest micros-millis-conversion-test
  (testing "microsecond to millisecond conversion"
    (is (= 1 (timing/micros->millis 1000)))
    (is (= 1000 (timing/millis->micros 1)))))

;; ============================================================================
;; FPS Conversion Tests
;; ============================================================================

(deftest fps-interval-nanos-test
  (testing "FPS to nanosecond interval conversion"
    (is (= 33333333 (timing/fps->interval-nanos 30)))
    (is (= 16666666 (timing/fps->interval-nanos 60)))))

(deftest fps-interval-us-test
  (testing "FPS to microsecond interval conversion"
    (is (= 33333 (timing/fps->interval-us 30)))
    (is (= 16666 (timing/fps->interval-us 60)))))

(deftest fps-interval-ms-test
  (testing "FPS to millisecond interval conversion"
    (is (< (Math/abs (- (timing/fps->interval-ms 30) 33.333)) 0.01))
    (is (< (Math/abs (- (timing/fps->interval-ms 60) 16.666)) 0.01))))

(deftest interval-fps-roundtrip-test
  (testing "FPS conversions round-trip correctly"
    (is (< (Math/abs (- (timing/interval-nanos->fps (timing/fps->interval-nanos 30)) 30)) 0.01))
    (is (< (Math/abs (- (timing/interval-us->fps (timing/fps->interval-us 60)) 60)) 0.01))
    (is (< (Math/abs (- (timing/interval-ms->fps (timing/fps->interval-ms 120)) 120)) 0.01))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest precise-timing-loop-test
  (testing "precise-sleep-until maintains consistent intervals"
    (let [fps 30
          interval-nanos (timing/fps->interval-nanos fps)
          num-frames 10
          timestamps (atom [])]
      
      ;; Simulate streaming loop
      (loop [next-frame-time (+ (timing/nanotime) interval-nanos)
             frame-count 0]
        (when (< frame-count num-frames)
          (swap! timestamps conj (timing/nanotime))
          (timing/precise-sleep-until next-frame-time)
          (recur (+ next-frame-time interval-nanos) (inc frame-count))))
      
      ;; Calculate intervals between frames
      (let [intervals (map (fn [[t1 t2]] (- t2 t1))
                          (partition 2 1 @timestamps))
            mean (/ (reduce + intervals) (count intervals))
            deviations (map #(Math/abs (- % mean)) intervals)
            max-jitter (apply max deviations)]
        
        ;; Max jitter should be less than 1ms (1,000,000 nanoseconds)
        (is (< max-jitter 1000000)
            (str "Max jitter: " (quot max-jitter 1000) "μs, expected <1000μs"))))))

;; ============================================================================
;; Performance Comparison Test (for documentation)
;; ============================================================================

(deftest ^:performance sleep-comparison-test
  (testing "Compare Thread/sleep vs precise-sleep"
    (println "\n=== Sleep Precision Comparison ===")
    
    ;; Test Thread/sleep precision
    (let [results (atom [])]
      (dotimes [_ 10]
        (let [start (timing/nanotime)
              _ (Thread/sleep 10)
              elapsed (- (timing/nanotime) start)
              error (- elapsed 10000000)]
          (swap! results conj error)))
      (let [avg-error-us (quot (/ (reduce + @results) (count @results)) 1000)]
        (println (format "Thread/sleep(10ms) average error: %dμs" avg-error-us))))
    
    ;; Test precise-sleep precision
    (let [results (atom [])]
      (dotimes [_ 10]
        (let [start (timing/nanotime)
              _ (timing/precise-sleep-ms 10)
              elapsed (- (timing/nanotime) start)
              error (- elapsed 10000000)]
          (swap! results conj error)))
      (let [avg-error-us (quot (/ (reduce + @results) (count @results)) 1000)]
        (println (format "precise-sleep-ms(10ms) average error: %dμs" avg-error-us))))
    
    (println "=================================\n")))
