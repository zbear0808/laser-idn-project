(ns laser-show.timeline.timecode-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.timeline.timecode :as tc]))

(deftest timecode-examples-test
  (is (= 32.0 (tc/beats->seconds 64.0 120.0)))
  (is (= 64.0 (tc/seconds->beats 32.0 120.0)))
  (testing "plan §7.3"
    (is (= {:h 1 :m 0 :s 5 :f 12 :drop? false} (tc/seconds->tc 3605.5 25)))
    (is (= "01:00:05:12" (tc/format-tc (tc/seconds->tc 3605.5 25))))
    (is (= "00:01:00;02" (tc/format-tc (tc/seconds->tc (/ 1800 29.97) 29.97)))
        "frame 1800 at 29.97 DF: ;00 and ;01 are skipped")
    (is (= "00:10:00;00" (tc/format-tc (tc/seconds->tc (/ 17982 29.97) 29.97)))
        "every 10th minute keeps ;00")
    (is (= {:seconds 3605.0 :approx? false}
           (tc/show-seconds {:timeline/tc-start 3600.0 :timeline/tempo-bpm 120.0} 10.0 140.0)))
    (is (= {:seconds 15.0 :approx? true} (tc/show-seconds {} 32.0 128.0)))))

(deftest drop-frame-roundtrip-test
  (doseq [frame (range 0 (* 11 60 30) 7)]
    (let [secs (/ frame 29.97)
          t (tc/seconds->tc secs 29.97)]
      (is (< (Math/abs (- secs (tc/tc->seconds t 29.97))) 1e-6) (pr-str frame t))
      (when (and (zero? (:s t)) (not (zero? (mod (:m t) 10))))
        (is (>= (:f t) 2) (str "frame numbers 0 and 1 don't exist at " (tc/format-tc t)))))))

(deftest non-drop-roundtrip-test
  (doseq [fps [24 25 30]
          frame (range 0 (* 2 3600 fps) 997)]
    (let [secs (/ frame (double fps))]
      (is (< (Math/abs (- secs (tc/tc->seconds (tc/seconds->tc secs fps) fps))) 1e-6)))))

(deftest parse-and-clock-test
  (is (= {:h 1 :m 2 :s 3 :f 4 :drop? false} (tc/parse-tc "01:02:03:04" 25)))
  (is (nil? (tc/parse-tc "01:02:03:30" 25)) "frame out of range")
  (is (nil? (tc/parse-tc "nope" 25)))
  (is (= "01:05" (tc/format-clock 65.9)))
  (is (= "1:00:05" (tc/format-clock 3605.0))))
