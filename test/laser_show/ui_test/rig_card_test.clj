(ns laser-show.ui-test.rig-card-test
  "A rig card's load section (plans/2026-11-19-zones/05 §7, §9): its
   thumbnail of what the laser really sent, its load meter and what the fit
   guard did, amber below its minimum fps; and the inspector's Fit guard
   settings. The lasers stream to the fake DAC, the show clock ticked from
   the test (fixtures/with-clock)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.events.core :as events]
            [laser-show.idn.fake-dac :as dac]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene Node]))

(def ^:dynamic *dac* nil)

(defn- with-fake-dac [f]
  (let [fake (dac/start!)]
    (try
      (binding [*dac* fake] (f))
      (finally
        (output-runtime/drain! 2000)
        (dac/stop! fake)))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app with-fake-dac)

(defn- laser
  "Laser A on the fake DAC, on, at 25,000 pts/s."
  [& {:as opts}]
  (merge {:name "Laser A" :host "127.0.0.1" :port (:port *dac*) :service-id 1 :enabled? true :zone-groups []
          :exclude-from-all? false :scan-rate 25000 :output-config {:color-bit-depth 8 :xy-bit-depth 16}}
         opts))

(defn- circle-cue
  "A cue of one circle of n points (a dark one and n - 1 lit), on All."
  [n]
  {:destination-zone {:zone-group-id :all}
   :items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true :effects []
            :params {:num-points (dec n)}}]})

(defn- play!
  "cue at [7 3], playing."
  [cue]
  (state/swap-state! assoc-in [:chains :cue-chains [7 3]] cue)
  (events/dispatch! {:event/type :grid/trigger-cell :col 7 :row 3})
  (ui/wait-for! #(some? (get-in (state/get-raw-state) [:playback :active-cues [7 3]]))))

(defn- status [] (get-in (state/get-raw-state) [:backend :streaming :status :projectors :a]))

(defn- stream! [robot]
  (ui/click! robot "#stream-btn")
  (ui/wait-for! #(= :streaming (:state (status)))))

(defn- said
  "What a canvas says it shows (its accessible text)."
  [selector]
  (ui/on-fx-thread #(.getAccessibleText ^Node (ui/query selector))))

(defn- lit-columns
  "The columns of a canvas's lit pixels (brighter than its grey outline)."
  [selector]
  (let [^Node node (ui/query selector)
        width (long (ui/on-fx-thread #(.getWidth (.getLayoutBounds node))))]
    (into #{} (keep-indexed (fn [i argb]
                              (when (or (> (bit-and (bit-shift-right argb 16) 0xFF) 100)
                                        (> (bit-and (bit-shift-right argb 8) 0xFF) 100)
                                        (> (bit-and argb 0xFF) 100))
                                (mod i width))))
          (ui/node-pixels selector))))

(deftest thumbnail-draws-what-the-laser-sent-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc :projectors {:a (laser :safety {:limit [-1.0 -1.0 0.0 1.0] :blocked []})})
    (play! (circle-cue 200))
    (ui/click! robot "#projectors-tab")
    (fixtures/with-clock
      (fn [_]
        (stream! robot)
        (ui/wait-for! #(= "Laser output" (said "#rig-card-thumbnail-a")))
        (ui/wait-for! #(seq (lit-columns "#rig-card-thumbnail-a")))
        (let [cols (lit-columns "#rig-card-thumbnail-a")]
          (is (some #(< % 20) cols) "the circle's left half, as the laser draws it")
          (is (not-any? #(> % 26) cols) "after its safety zone, which keeps the right half dark"))))))

(deftest thumbnail-says-not-streaming-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc :projectors {:a (laser)})
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(= "Not streaming" (said "#rig-card-thumbnail-a")))
    (ui/assert-text! "#rig-card-not-streaming-a" "Not streaming")
    (is (empty? (lit-columns "#rig-card-thumbnail-a")) "nothing drawn but its outline")
    (is (nil? (ui/query "#rig-card-load-a")) "and no meter (Q-Z3-2 A)")
    (testing "streaming, then stopped: it says so again"
      (play! (circle-cue 200))
      (fixtures/with-clock
        (fn [_]
          (stream! robot)
          (ui/wait-for! #(= "Laser output" (said "#rig-card-thumbnail-a")))
          (ui/wait-for! #(nil? (ui/query "#rig-card-not-streaming-a")))
          (ui/click! robot "#stream-btn")
          (ui/wait-for! #(= "Not streaming" (said "#rig-card-thumbnail-a")))
          (ui/assert-text! "#rig-card-not-streaming-a" "Not streaming"))))))

(deftest card-turns-amber-below-its-minimum-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc :projectors {:a (laser)})
    (play! (circle-cue 3000))
    (ui/click! robot "#projectors-tab")
    (fixtures/with-clock
      (fn [_]
        (stream! robot)
        (ui/wait-for! #(ui/has-style-class? "#rig-card-a" "rig-card-low"))
        (ui/assert-text! "#rig-card-load-pct-a" "240%")
        (is (ui/has-style-class? "#rig-card-load-a .load-meter" "load-meter-over") "red past 100%")
        (ui/assert-text! "#rig-card-load-note-a" "min 20 fps · −40% pts")
        (is (str/includes? (str (ui/on-fx-thread #(.getStyle ^Node (ui/query "#projector-status-a")))) "bold")
            "its status line bold amber")
        (is (true? (:low? (status))))))))

(deftest thinned-laser-shows-its-cut-test
  (let [robot (ui/robot)]
    (state/swap-state! assoc :projectors {:a (laser)})
    (play! (circle-cue 2000))
    (ui/click! robot "#projectors-tab")
    (fixtures/with-clock
      (fn [_]
        (stream! robot)
        (ui/wait-for! #(= "−38% pts" (ui/node-text "#rig-card-load-note-a")))
        (is (= "160%" (ui/node-text "#rig-card-load-pct-a")) "2,000 points over its 1,250")
        (is (not (ui/has-style-class? "#rig-card-a" "rig-card-low"))
            "thinned back up to its minimum: not low (Q-Z3-5 A)")
        (is (not (ui/visible? "#low-fps-chip")) "and no chip")))))

(deftest fit-guard-settings-apply-test
  (let [robot (ui/robot)
        fit #(get-in (state/get-raw-state) [:projectors :a :fit])]
    (state/swap-state! #(-> %
                            (assoc :projectors {:a (laser)})
                            (assoc-in [:projector-ui :active-projector] :a)))
    (play! (circle-cue 3000))
    (ui/click! robot "#projectors-tab")
    (ui/scroll-into-view! "#projector-fit-guard")
    (ui/assert-text! "#projector-fit-min-fps" "20")
    (ui/assert-disabled! "#projector-fit-dim-floor")
    (fixtures/with-clock
      (fn [_]
        (stream! robot)
        (ui/wait-for! #(ui/has-style-class? "#rig-card-a" "rig-card-low"))
        (testing "a minimum of 5: no longer low, on the next ticks, and no engine restarts"
          (let [started (:started-ms (get (output-runtime/registry) :a))]
            (ui/fill! robot "#projector-fit-min-fps" "5")
            (ui/submit! robot "#projector-fit-min-fps")
            (ui/wait-for! #(= 5 (:min-fps (fit))))
            (ui/wait-for! #(not (ui/has-style-class? "#rig-card-a" "rig-card-low")))
            (is (some? started))
            (is (= started (:started-ms (get (output-runtime/registry) :a))))))
        (testing "a number outside 5-60 is clamped"
          (ui/fill! robot "#projector-fit-min-fps" "100")
          (ui/submit! robot "#projector-fit-min-fps")
          (ui/wait-for! #(= 60 (:min-fps (fit))))
          (ui/assert-text! "#projector-fit-min-fps" "60"))))
    (testing "fewer points off, then dim on, its floor in percent"
      (ui/scroll-into-view! "#projector-fit-reduce")
      (ui/uncheck! robot "#projector-fit-reduce")
      (ui/wait-for! #(false? (:reduce-points? (fit))))
      (ui/assert-disabled! "#projector-fit-point-floor")
      (ui/check! robot "#projector-fit-dim")
      (ui/wait-for! #(true? (:dim? (fit))))
      (ui/assert-enabled! "#projector-fit-dim-floor")
      (ui/fill! robot "#projector-fit-dim-floor" "70")
      (ui/submit! robot "#projector-fit-dim-floor")
      (ui/wait-for! #(= 0.7 (:dim-floor (fit)))))))
