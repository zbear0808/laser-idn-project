(ns laser-show.input.mtc-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.input.mtc :as mtc]
            [laser-show.timeline.timecode :as tc]))

(defn qf-bytes
  "The 8 quarter-frame data bytes (pieces 0..7) for a time value."
  [{:keys [h m s f]} fps]
  (let [rate (.indexOf ^java.util.List mtc/rate-codes fps)]
    [(bit-or 0x00 (bit-and f 0xF))
     (bit-or 0x10 (bit-shift-right f 4))
     (bit-or 0x20 (bit-and s 0xF))
     (bit-or 0x30 (bit-shift-right s 4))
     (bit-or 0x40 (bit-and m 0xF))
     (bit-or 0x50 (bit-shift-right m 4))
     (bit-or 0x60 (bit-and h 0xF))
     (bit-or 0x70 (bit-shift-right h 4) (bit-shift-left rate 1))]))

(defn- qf-ns [fps] (long (/ 1e9 (* 4.0 (double fps)))))

(defn feed
  "Feed bytes one quarter-frame apart starting at t0-ns. Returns the decoder."
  [decoder bytes fps t0-ns]
  (reduce (fn [d [i b]] (mtc/step-quarter-frame d b (+ t0-ns (* i (qf-ns fps)))))
          decoder
          (map-indexed vector bytes)))

(def ^:private ms 1000000)

(deftest quarter-frame-sequence-decodes-test
  (testing "plan §7.1: 01:00:05:12 at 25 fps"
    (is (= [0x0C 0x10 0x25 0x30 0x40 0x50 0x61 0x72] (qf-bytes {:h 1 :m 0 :s 5 :f 12} 25)))
    (let [d (feed mtc/initial-decoder [0x0C 0x10 0x25 0x30 0x40 0x50 0x61 0x72] 25 0)]
      (is (= {:h 1 :m 0 :s 5 :f 14 :drop? false} (:tc d)) "the start frame + 2")
      (is (= 25 (:fps d)))
      (is (= :forward (:direction d)))
      (let [at-7 (* 7 (qf-ns 25))
            p (mtc/position d at-7 nil)]
        (is (= :running (:state p)))
        (is (< (Math/abs (- (+ 3605.48 (/ 1.75 25)) (:seconds p))) 1e-9)
            "piece 7 arrives 1.75 frames after the value's frame")
        (is (< (Math/abs (- (+ 3605.48 (/ 1.75 25) 0.05) (:seconds (mtc/position d (+ at-7 (* 50 ms)) nil)))) 1e-6)
            "extrapolates between messages")))))

(deftest all-rates-decode-test
  (doseq [[fps t] [[24 {:h 2 :m 59 :s 1 :f 23}]
                   [25 {:h 0 :m 0 :s 0 :f 0}]
                   [29.97 {:h 23 :m 9 :s 0 :f 2}]
                   [30 {:h 10 :m 30 :s 45 :f 29}]]]
    (testing (str fps " fps")
      (let [d (feed mtc/initial-decoder (qf-bytes t fps) fps 0)]
        (is (= fps (:fps d)))
        (is (= (:drop? (:tc d)) (= fps 29.97)))
        (is (< (Math/abs (- (+ (tc/tc->seconds (assoc t :drop? (= fps 29.97)) fps) (/ 2.0 fps))
                            (tc/tc->seconds (:tc d) fps)))
               1e-9)
            "two frames later")))))

(deftest consecutive-sequences-test
  (let [d (-> mtc/initial-decoder
              (feed (qf-bytes {:h 0 :m 0 :s 1 :f 0} 25) 25 0)
              (feed (qf-bytes {:h 0 :m 0 :s 1 :f 2} 25) 25 (* 8 (qf-ns 25))))]
    (is (= {:h 0 :m 0 :s 1 :f 4 :drop? false} (:tc d)))))

(deftest reverse-direction-detected-test
  (let [d (feed mtc/initial-decoder (rseq (qf-bytes {:h 0 :m 1 :s 0 :f 10} 30)) 30 0)]
    (is (= :reverse (:direction d)))
    (is (= {:h 0 :m 1 :s 0 :f 10 :drop? false} (:tc d)))
    (let [t-last (* 7 (qf-ns 30))
          s0 (:seconds (mtc/position d t-last nil))
          s1 (:seconds (mtc/position d (+ t-last (* 50 ms)) nil))]
      (is (< (Math/abs (- 0.05 (- s0 s1))) 1e-6) "runs backwards"))))

(deftest full-frame-sysex-locates-test
  (let [data [0x7F 0x7F 0x01 0x01 (bit-or 0x20 1) 0 20 0 0xF7]
        d (mtc/step-full-frame mtc/initial-decoder data (* 1000 ms))
        p (mtc/position d (* 2000 ms) nil)]
    (is (mtc/full-frame? data))
    (is (= {:h 1 :m 0 :s 20 :f 0 :drop? false} (:tc d)))
    (is (= 25 (:fps d)))
    (is (= :stopped (:state p)) "a locate alone isn't playing")
    (is (= 3620.0 (:seconds p)) "but the position is known")
    (testing "signed bytes (as a Java byte[]) decode the same"
      (is (= (:tc d) (:tc (mtc/step-full-frame mtc/initial-decoder (byte-array (map unchecked-byte data)) 0)))))
    (testing "other SysEx is ignored"
      (is (= mtc/initial-decoder (mtc/step-full-frame mtc/initial-decoder [0x7E 0x7F 0x06 0x01 0xF7] 0))))))

(deftest incomplete-sequence-reports-nothing-test
  (let [bytes (qf-bytes {:h 1 :m 0 :s 5 :f 12} 25)]
    (is (nil? (:seconds (mtc/position (feed mtc/initial-decoder (take 7 bytes) 25 0) 0 nil))))
    (is (= :stopped (:state (mtc/position (feed mtc/initial-decoder (take 7 bytes) 25 0) (* 10 ms) nil))))
    (testing "starting mid-sequence: complete only after 8 consecutive pieces"
      (let [d (feed mtc/initial-decoder (concat (drop 4 bytes) bytes) 25 0)]
        (is (= 14 (:f (:tc d))))
        (is (nil? (:tc (feed mtc/initial-decoder (concat (drop 4 bytes) (take 7 bytes)) 25 0))))))
    (testing "a missing piece restarts the count"
      (is (nil? (:tc (feed mtc/initial-decoder (concat (take 3 bytes) (drop 4 bytes)) 25 0)))))))

(deftest freewheel-and-stop-thresholds-test
  (let [d (feed mtc/initial-decoder (qf-bytes {:h 0 :m 0 :s 10 :f 0} 25) 25 0)
        last-qf (* 7 (qf-ns 25))
        at (fn [after-ms] (mtc/position d (+ last-qf (* after-ms ms)) nil))]
    (is (= :running (:state (at 50))))
    (is (= :freewheel (:state (at 300))))
    (is (> (:seconds (at 300)) (:seconds (at 50))) "freewheel keeps advancing")
    (is (= :stopped (:state (at 600))))
    (is (= (:seconds (at 600)) (:seconds (at 5000))) "stopped holds the last position")
    (is (= :freewheel (:state (mtc/position d (+ last-qf (* 600 ms)) {:freewheel-ms 1000}))))
    (testing "after a long gap the old value isn't reported until a fresh sequence"
      (let [later (+ last-qf (* 3000 ms))
            d2 (feed d (take 3 (qf-bytes {:h 0 :m 5 :s 0 :f 0} 25)) 25 later)
            p (mtc/position d2 (+ later (* 10 ms)) nil)]
        (is (= :stopped (:state p)))
        (is (< (Math/abs (- (:seconds (at 600)) (:seconds p))) 1e-9))
        (let [d3 (feed d (qf-bytes {:h 0 :m 5 :s 0 :f 0} 25) 25 later)]
          (is (= :running (:state (mtc/position d3 (+ later (* 8 (qf-ns 25))) nil))))
          (is (< 300.0 (:seconds (mtc/position d3 (+ later (* 8 (qf-ns 25))) nil)) 300.1)))))))
