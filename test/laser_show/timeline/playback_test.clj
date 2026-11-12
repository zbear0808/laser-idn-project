(ns laser-show.timeline.playback-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.cue-timing :as ct]
            [laser-show.timeline.playback :as playback]))

(deftest clip-timing-ctx-test
  (testing "plan §7.2: BPM 120, absolute beat 18, clip start 16"
    (let [ctx (playback/clip-timing-ctx {:accumulated-beats 18.0 :effective-beats 18.0 :phase-offset 0.0
                                         :accumulated-ms 9000.0 :time-ms 9000.0 :trigger-time 12345}
                                        {:timeline/start 16.0} 120.0)]
      (is (= {:accumulated-beats 2.0 :effective-beats 2.0 :phase-offset 0.0
              :accumulated-ms 1000.0 :time-ms 1000.0 :trigger-time 0 :bpm 120.0
              :window-beats 18.0}
             ctx))
      (is (= 1000 (playback/clip-elapsed-ms ctx)))))
  (testing "the window position stays absolute (looping cues pass :window-beats)"
    (is (= 2.0 (:window-beats (playback/clip-timing-ctx {:effective-beats 18.5 :window-beats 2.0}
                                                        {:timeline/start 0.0} 120.0)))))
  (testing "before the clip starts, local time is 0"
    (is (= 0.0 (:effective-beats (playback/clip-timing-ctx {:effective-beats 3.0} {:timeline/start 8.0} 120.0))))))

(deftest cue-bpm-locked-vs-follow-test
  (is (= 100.0 (playback/cue-bpm {:editor-type :timeline :timeline/tempo-bpm 100.0} 140.0)))
  (is (= 140.0 (playback/cue-bpm {:editor-type :timeline} 140.0)) "follows the global BPM")
  (is (= 140.0 (playback/cue-bpm {:editor-type :cue-chain :timeline/tempo-bpm 100.0} 140.0))
      "only timeline cues lock their tempo"))

(deftest timeline-cfg-test
  (is (= {:length 6.0} (playback/timeline-cfg {:items [{:timeline/start 2.0 :timeline/duration 4.0}]})))
  (is (= {:length 32.0} (playback/timeline-cfg {:timeline/length 32.0 :items []}))))

(deftest editor-and-renderer-agree-test
  (testing "for timeline cues the playhead (accumulated beats) is the rendered position (effective beats)"
    (let [rnd (java.util.Random. 11)]
      (dotimes [_ 50]
        (let [bpm (+ 60.0 (* 120.0 (.nextDouble rnd)))
              t0 (get-in (ct/activate-cue {} [0 0] 0 (* 10 (.nextDouble rnd)) {:timeline? true :start-beat 0.0})
                         [:active-cues [0 0]])
              steps (reductions + (repeatedly 20 #(+ 5 (.nextInt rnd 40))))
              t (reduce (fn [t now] (ct/update-cue-timing t now bpm 4.0 {:enabled? false} {:length 1000.0}))
                        t0 steps)
              ctx (ct/get-cue-timing-context t bpm)]
          (is (= (:accumulated-beats t) (:effective-beats ctx))))))))

;; MTC chase (Phase 5)

(def ^:private chasing-cue
  {:editor-type :timeline :timeline/chase :mtc :timeline/tc-start 3600.0 :timeline/tempo-bpm 120.0
   :timeline/length 64.0 :items []})

(defn- chase [playback pos]
  (playback/apply-chase playback {[0 0] chasing-cue} pos 140.0 1000))

(defn- beats [pb] (get-in pb [:active-cues [0 0] :accumulated-beats]))

(deftest apply-chase-examples-test
  (testing "plan §7.2"
    (is (nil? (beats (chase {} {:state :running :seconds 3599.0}))) "before beat 0: inactive")
    (let [pb (chase {} {:state :running :seconds 3605.48})]
      (is (< (Math/abs (- 10.96 (beats pb))) 1e-9))
      (is (true? (get-in pb [:active-cues [0 0] :timeline?])))
      (is (true? (:playing? pb)))
      (is (= 11.0 (beats (chase pb {:state :freewheel :seconds 3605.5}))) "freewheel keeps it active")
      (is (nil? (beats (chase pb {:state :stopped :seconds 3605.5}))) "stopped: deactivated")
      (is (false? (:playing? (chase pb {:state :stopped :seconds 3605.5})))))
    (is (= 40.0 (beats (chase {} {:state :running :seconds 3620.0}))) "located to 01:00:20:00, then running")
    (is (nil? (beats (chase (chase {} {:state :running :seconds 3620.0}) {:state :running :seconds 3632.0})))
        "beat 64 = the end: deactivated")))

(deftest apply-chase-follows-global-bpm-without-lock-test
  (is (< (Math/abs (- (* 5.0 (/ 140.0 60.0))
                      (beats (playback/apply-chase {} {[0 0] (dissoc chasing-cue :timeline/tempo-bpm)}
                                                   {:state :running :seconds 3605.0} 140.0 0))))
         1e-9)))

(deftest apply-chase-ignores-other-cues-test
  (let [pb {:playing? true :active-cues {[1 0] {:accumulated-beats 3.0}}}
        cues {[1 0] (dissoc chasing-cue :timeline/chase)
              [2 0] {:editor-type :cue-chain :timeline/chase :mtc :items []}}]
    (is (= pb (playback/apply-chase pb cues {:state :running :seconds 3605.0} 120.0 0)))
    (is (= pb (playback/apply-chase pb cues {:state :stopped :seconds nil} 120.0 0)))))
