(ns laser-show.profiling.frame-profiler-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.profiling.frame-profiler :as profiler])
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]))

(deftest recording-an-idn-sample-allocates-only-the-sample-test
  (testing "every engine records one sample per frame, at the same moment as the others,
            so recording doesn't copy the 1000 kept (that was about 10 KB per call)"
    (let [mx (ManagementFactory/getThreadMXBean)]
      (when (and (instance? ThreadMXBean mx) (.isThreadAllocatedMemorySupported ^ThreadMXBean mx))
        (dotimes [i 3000] (profiler/record-idn-streaming! (double i)))
        (let [before (.getCurrentThreadAllocatedBytes ^ThreadMXBean mx)
              _ (dotimes [i 1000] (profiler/record-idn-streaming! (double i)))
              per-call (/ (- (.getCurrentThreadAllocatedBytes ^ThreadMXBean mx) before) 1000.0)]
          (is (< per-call 512) (str per-call " bytes allocated per call")))))))

(deftest idn-samples-keep-the-last-1000-test
  (dotimes [i 1500] (profiler/record-idn-streaming! (double i)))
  (let [samples (profiler/get-idn-samples)]
    (is (vector? samples))
    (is (= 1000 (count samples)))
    (is (= (map double (range 500 1500)) (map :idn-time-us samples)) "the newest 1000, oldest first")
    (is (every? :timestamp-ms samples)))
  (is (= [1497.0 1498.0 1499.0] (mapv :idn-time-us (profiler/get-idn-samples 3))))
  (testing "from several threads at once, none is lost"
    (let [threads 4
          each 250]
      (->> (range threads)
           (mapv (fn [t] (future (dotimes [i each] (profiler/record-idn-streaming! (+ 10000.0 (* t each) i))))))
           (run! deref))
      (is (= (set (map double (range 10000 (+ 10000 (* threads each)))))
             (set (map :idn-time-us (profiler/get-idn-samples))))))))
