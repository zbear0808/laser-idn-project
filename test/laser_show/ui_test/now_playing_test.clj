(ns laser-show.ui-test.now-playing-test
  "The Shows tab's now-playing bar (plans/2026-11-25-timeline-shows 07 §4.8,
   §8): its times follow the show clock, the seek bar seeks once on
   release, timecode disables the transport, and HELD while Stop holds the
   output. app/start-test! runs no show clock: these tests tick it by hand,
   at most 1 s apart (cue timing takes a longer gap for a stall)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.timeline.shows :as shows]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.gesture :as gesture])
  (:import [javafx.scene Node]
           [javafx.scene.control Slider]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(def ^:private u #uuid "00000000-0000-0000-0000-0000000005d1")
(def ^:private v #uuid "00000000-0000-0000-0000-0000000005d2")

(def ^:private a-show
  "64 beats at a locked 120 BPM: 32 s."
  {:name "One More Time" :editor-type :timeline :show/info {:artist "Daft Punk"}
   :timeline/tempo-bpm 120.0 :timeline/length 64.0 :tracks [] :items []})

(defn- intent [id] (get-in (state/get-raw-state) [:playback :active-cues (shows/play-key id)]))

(defn- open-shows! [robot library]
  (events/write-state! assoc-in [:chains :shows] library)
  (ui/click! robot "#shows-tab")
  (ui/wait-for! #(= :shows (get-in (state/get-raw-state) [:ui :active-tab])))
  (ui/assert-visible! "#now-playing"))

(defn- ticker
  "tick! at ms after now on a fake clock."
  []
  (let [t0 (System/nanoTime)]
    (fn [ms] (show-clock/tick! (+ t0 (* (long ms) 1000000))))))

(defn- slider ^Slider [] (ui/query "#now-playing-seek"))
(defn- slider-value [] (ui/on-fx-thread #(.getValue (slider))))

(defn- play-u!
  "⏯ with nothing played yet plays the first show the table shows: u."
  [robot]
  (ui/assert-text! "#now-playing-empty" "Nothing playing")
  (ui/click! robot "#now-playing-toggle")
  (ui/wait-for! #(some? (intent u)))
  (ui/assert-text! "#now-playing-title" "One More Time"))

(deftest now-playing-follows-the-clock-test
  (let [robot (ui/robot)
        tick! (ticker)
        elapsed #(ui/node-text "#now-playing-elapsed")]
    (open-shows! robot {u a-show})
    (play-u! robot)
    (ui/assert-text! "#now-playing-artist" "Daft Punk")
    (tick! 0)
    (doseq [[ms text remaining tc] [[1000 "00:01" "-00:31" "00:00:01:00"]
                                    [2000 "00:02" "-00:30" "00:00:02:00"]]]
      (tick! ms)
      (ui/wait-for! #(= text (elapsed)))
      (is (= text (elapsed)) (str ms " ms at 120 BPM"))
      (is (= remaining (ui/node-text "#now-playing-remaining")))
      (is (= tc (ui/node-text "#now-playing-tc"))))
    (ui/wait-for! #(< (Math/abs (- 4.0 (slider-value))) 1e-6))
    (is (< (Math/abs (- 4.0 (slider-value))) 1e-6) "the thumb at the show's position, in beats")
    (testing "paused: it stays"
      (ui/click! robot "#now-playing-toggle")
      (ui/wait-for! #(:paused? (intent u)))
      (tick! 3000)
      (tick! 3500)
      (ui/sync-fx!)
      (is (= "00:02" (elapsed))))
    (testing "stopped: back to 0, ⏹ disabled, ⏯ plays it again"
      (ui/click! robot "#now-playing-stop")
      (ui/wait-for! #(nil? (intent u)))
      (tick! 4000)
      (ui/wait-for! #(= "00:00" (elapsed)))
      (ui/assert-disabled! "#now-playing-stop")
      (is (= u (get-in (state/get-raw-state) [:ui :shows :manual])) "the bar still names it"))))

(deftest seek-release-moves-the-show-test
  (let [robot (ui/robot)
        tick! (ticker)]
    (open-shows! robot {u a-show})
    (play-u! robot)
    (tick! 0)
    (tick! 1000)
    (ui/wait-for! #(< (Math/abs (- 2.0 (slider-value))) 1e-6))
    (let [thumb ^Node (ui/on-fx-thread #(.lookup (slider) ".thumb"))]
      (testing "a drag: the thumb is the user's, the clock doesn't move it, nothing is sent"
        (let [release! (ui/fire-drag! thumb 80 0 :hold? true)
              dragged (slider-value)]
          (is (some? (ui/on-fx-thread #(gesture/capture (slider)))) "pressed")
          (is (> dragged 2.5) "dragged to the right")
          (tick! 2000)
          (ui/wait-for! #(= "00:02" (ui/node-text "#now-playing-elapsed")))
          (is (= dragged (slider-value)) "ticks don't move the thumb while it is held")
          (is (nil? (:seek (intent u))) "no seek during the drag")
          (release!)
          (testing "the release seeks the show to the thumb, once"
            (ui/wait-for! #(some? (:seek (intent u))))
            (is (< (Math/abs (- dragged (get-in (intent u) [:seek :beat]))) 1e-6))
            (is (nil? (ui/on-fx-thread #(gesture/capture (slider))))))))
      (testing "a drag that loses the focus sends nothing"
        (let [seek (:seek (intent u))
              release! (ui/fire-drag! thumb -40 0 :hold? true)]
          (is (some? (ui/on-fx-thread #(gesture/capture (slider)))))
          (ui/wait-for! #(ui/on-fx-thread (fn [] (.isFocused (slider)))))
          (ui/on-fx-thread #(.requestFocus (ui/query "#now-playing-edit")))
          (ui/wait-for! #(nil? (ui/on-fx-thread (fn [] (gesture/capture (slider))))))
          (release!)
          (events/flush!)
          (is (= seek (:seek (intent u))) "the same seek as before"))))))

(deftest timecode-show-disables-transport-test
  (let [robot (ui/robot)]
    (open-shows! robot {u a-show v (assoc a-show :name "Strobe" :timeline/chase :mtc)})
    (ui/assert-visible! "#now-playing-toggle")
    (is (not (ui/visible? "#now-playing-tc-chip")))
    (events/write-state! update :playback assoc
                         :active-cues {(shows/play-key v) {:chasing? true :timeline? true}}
                         :playing? true)
    (ui/assert-text! "#now-playing-title" "Strobe")
    (ui/assert-visible! "#now-playing-tc-chip")
    (is (not (ui/visible? "#now-playing-toggle")) "the TC chip stands in for ⏯")
    (doseq [id ["#now-playing-seek" "#now-playing-prev" "#now-playing-next" "#now-playing-stop"]]
      (is (not (ui/enabled? id)) (str id " is disabled")))
    (ui/assert-enabled! "#now-playing-edit")))

(deftest held-chip-while-stopped-test
  (let [robot (ui/robot)]
    (open-shows! robot {u a-show})
    (play-u! robot)
    (is (not (ui/visible? "#now-playing-held")))
    (events/dispatch! {:event/type :transport/stop})
    (ui/wait-for! #(false? (get-in (state/get-raw-state) [:playback :playing?])))
    (ui/assert-visible! "#now-playing-held")
    (is (some? (intent u)) "Stop holds the show")
    (events/dispatch! {:event/type :transport/play})
    (ui/wait-for! #(not (ui/visible? "#now-playing-held")))
    (is (not (ui/visible? "#now-playing-held")))))
