(ns laser-show.services.ilda-player-test
  (:require [clojure.test :refer :all]
            [laser-show.services.ilda-player :as player]
            [laser-show.backend.ilda :as ilda]))

;; Mock frames
(def mock-frames
  [{:points [[0.0 0.0 1.0 0.0 0.0]]}
   {:points [[0.5 0.5 0.0 1.0 0.0]]}])

(deftest playback-control-test
  (testing "Loading and State"
    (with-redefs [ilda/read-ilda-file (constantly mock-frames)]
      (player/load-file! "dummy.ild")
      (let [state @player/playback-state]
        (is (= "dummy.ild" (:file-path state)))
        (is (= 2 (:frame-count state)))
        (is (false? (:playing? state))))))

  (testing "Play/Pause/Stop"
    (with-redefs [ilda/read-ilda-file (constantly mock-frames)
                  player/now-ms (constantly 1000)]
      (player/load-file! "dummy.ild")

      (player/play!)
      (is (true? (:playing? @player/playback-state)))
      (is (false? (:paused? @player/playback-state)))

      (player/pause!)
      (is (false? (:playing? @player/playback-state)))
      (is (true? (:paused? @player/playback-state)))

      (player/play!)
      (is (true? (:playing? @player/playback-state)))
      (is (false? (:paused? @player/playback-state)))

      (player/stop!)
      (is (false? (:playing? @player/playback-state)))
      (is (false? (:paused? @player/playback-state))))))

(deftest frame-timing-test
  (testing "Frame advancement"
    (with-redefs [ilda/read-ilda-file (constantly mock-frames)]
      (let [mock-time (atom 1000)]
        (with-redefs [player/now-ms (fn [] @mock-time)]
          (player/load-file! "dummy.ild")
          (player/set-fps! 10) ;; 100ms per frame

          (player/play!) ;; start-time-ms = 1000

          ;; Frame 0 at 0ms elapsed (time 1000)
          (is (= [[0.0 0.0 1.0 0.0 0.0]] (player/get-current-frame)))

          ;; Frame 0 at 50ms elapsed (time 1050)
          (reset! mock-time 1050)
          (is (= [[0.0 0.0 1.0 0.0 0.0]] (player/get-current-frame)))

          ;; Frame 1 at 100ms elapsed (time 1100)
          (reset! mock-time 1100)
          (is (= [[0.5 0.5 0.0 1.0 0.0]] (player/get-current-frame)))

          ;; Loop back to Frame 0 at 200ms elapsed (time 1200)
          (reset! mock-time 1200)
          (is (= [[0.0 0.0 1.0 0.0 0.0]] (player/get-current-frame)))

          ;; Pause at 250ms elapsed (time 1250) -> effectively 50ms into loop (frame 0)
          (reset! mock-time 1250)
          (player/pause!)
          (is (nil? (player/get-current-frame))) ;; Not playing

          ;; Advance time while paused (should not affect elapsed)
          (reset! mock-time 2000)

          ;; Resume
          (player/play!)

          ;; Should resume from 250ms elapsed -> 50ms into loop -> frame 0
          (is (= [[0.0 0.0 1.0 0.0 0.0]] (player/get-current-frame)))

          ;; Advance 50ms (total effective elapsed 300ms -> 100ms into loop -> frame 1)
          (swap! mock-time + 50)
          (is (= [[0.5 0.5 0.0 1.0 0.0]] (player/get-current-frame))))))))
