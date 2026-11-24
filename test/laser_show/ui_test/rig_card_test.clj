(ns laser-show.ui-test.rig-card-test
  "A rig card's load section (plans/2026-11-19-zones/05 §7, §9): its
   thumbnail of the frame its driver was passed in its last call that
   returned normally, with a caption saying what that is (reactivity plan
   18), its load meter and what the fit guard did, amber below its minimum
   fps; and the inspector's Fit guard settings. The lasers stream to a
   recording driver in memory (laser-show.output.recording-driver), the show
   clock ticked from the test (fixtures/with-clock); no socket. The
   thumbnail's own samples are driven by hand where a test says so
   (with-sampled-thumbnails)."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.show-time :as show-time]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.common.timing :as timing]
            [laser-show.events.core :as events]
            [laser-show.output.driver :as driver]
            [laser-show.output.recording-driver :as rd]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.preview :as preview])
  (:import [javafx.animation AnimationTimer]
           [javafx.scene Node Scene]
           [javafx.scene.canvas Canvas]
           [javafx.scene.layout StackPane]
           [javafx.stage Stage]))

(def ^:dynamic *rec* nil)

(defn- with-recording-driver
  "Each test streams to its own recording driver, registered, with its
   descriptor in app state as startup puts it; afterwards the output runtime
   is drained and opened again, and the driver unregistered."
  [f]
  (let [rec (rd/recording-driver {:id :test/card})]
    (driver/register! (:driver rec))
    (try
      (events/dispatch! {:event/type :addons/loaded :addons [] :drivers (driver/descriptors)})
      (ui/wait-for! #(contains? (get-in (state/get-raw-state) [:backend :output :drivers]) :test/card))
      (binding [*rec* rec] (f))
      (finally
        (output-runtime/drain! 2000)
        (output-runtime/open!)
        (driver/unregister! :test/card)))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app with-recording-driver)

(defn- laser
  "Laser A on the recording driver, on, at 25,000 pts/s."
  [& {:as opts}]
  (merge {:name "Laser A" :driver :test/card :driver-config {} :enabled? true :zone-groups []
          :exclude-from-all? false :scan-rate 25000}
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
  (events/write-state! assoc-in [:chains :cue-chains [7 3]] cue)
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

(defn- meter-shown?
  "Is laser A's load meter there and shown?"
  []
  (and (some? (ui/query "#rig-card-load-a")) (ui/visible? "#rig-card-meter-a")))

(deftest thumbnail-draws-the-last-driver-frame-test
  (let [robot (ui/robot)]
    (events/write-state! assoc :projectors {:a (laser :safety {:limit [-1.0 -1.0 0.0 1.0] :blocked []})})
    (play! (circle-cue 200))
    (ui/click! robot "#projectors-tab")
    (fixtures/with-clock
      (fn [_]
        (stream! robot)
        (ui/assert-text! "#rig-card-return-a" "Driver frame")
        (is (= "Last successful driver call: frame" (said "#rig-card-thumbnail-a")))
        (let [^javafx.scene.control.Label caption (ui/query "#rig-card-return-a")]
          (is (= "Frame passed to the last driver call that returned normally. Device delivery is not reported."
                 (ui/on-fx-thread #(some-> caption .getTooltip .getText)))))
        (ui/wait-for! #(seq (lit-columns "#rig-card-thumbnail-a")))
        (let [cols (lit-columns "#rig-card-thumbnail-a")]
          (is (some #(< % 20) cols) "the circle's left half, as the driver was passed it")
          (is (not-any? #(> % 26) cols) "after its safety zone, which keeps the right half dark"))
        (testing "what it shows is a frame the driver was passed, and returned from"
          (let [frame (get-in (output-runtime/thumbnail-observation :a) [:last-ok :frame])]
            (is (seq frame))
            (is (some #(identical? frame %) @(:frames *rec*)))
            (is (every? (fn [[x]] (<= (double x) 1e-9)) frame) "inside its limit, x up to 0")))))))

(deftest thumbnail-says-not-streaming-test
  (let [robot (ui/robot)]
    (events/write-state! assoc :projectors {:a (laser)})
    (ui/click! robot "#projectors-tab")
    (ui/assert-text! "#rig-card-return-a" "Not streaming")
    (is (= "Not streaming" (said "#rig-card-thumbnail-a")))
    (is (empty? (lit-columns "#rig-card-thumbnail-a")) "nothing drawn but its outline")
    (is (not (meter-shown?)) "and no meter (Q-Z3-2 A)")
    (testing "streaming, then stopped: it says so again, with its outline only and no meter"
      (play! (circle-cue 200))
      (fixtures/with-clock
        (fn [_]
          (stream! robot)
          (ui/assert-text! "#rig-card-return-a" "Driver frame")
          (ui/wait-for! meter-shown?)
          (ui/click! robot "#stream-btn")
          (ui/assert-text! "#rig-card-return-a" "Not streaming")
          (is (not (meter-shown?)) "the meter goes with it, before the last status catches up too")
          (is (= "Not streaming" (said "#rig-card-thumbnail-a")))
          (is (empty? (lit-columns "#rig-card-thumbnail-a"))))))))


;; The thumbnail's samples, by hand (reactivity plan 18)

(defn- with-sampled-thumbnails
  "Call (f m) with every clock widget's timer made meanwhile driven by hand,
   m's (pulse! ms) delivering one pulse at ms to each started one on the FX
   thread (:timers), what a thumbnail reads set by the test ((observe! id
   observation), else :off), the clock it reads it on too ((at-ms! ms)),
   and its reads (:reads, the ids) and paints (:paints, [limit width height
   points]) counted."
  [f]
  (let [timers (atom #{})
        reads (atom [])
        paints (atom [])
        observations (atom {})
        now (atom 0)
        draw! @#'preview/draw-thumbnail!]
    (with-redefs-fn {#'clock-view/pulse-timer
                     (fn [handle!]
                       (proxy [AnimationTimer] []
                         (handle [pulse-ns] (handle! pulse-ns))
                         (start [] (swap! timers conj this))
                         (stop [] (swap! timers disj this))))
                     #'output-runtime/thumbnail-observation
                     (fn [id] (swap! reads conj id) (get @observations id :off))
                     #'preview/draw-thumbnail!
                     (fn [^Canvas canvas points limit]
                       (swap! paints conj [limit (.getWidth canvas) (.getHeight canvas) points])
                       (draw! canvas points limit))
                     #'timing/nanotime
                     (fn ^long [] (long @now))}
      (fn []
        (f {:timers timers :reads reads :paints paints
            :observe! (fn [id observation] (swap! observations assoc id observation))
            :at-ms! (fn [ms] (reset! now (* (long ms) 1000000)))
            :pulse! (fn [ms] (ui/on-fx-thread #(doseq [^AnimationTimer t @timers]
                                                 (.handle t (* (long ms) 1000000)))))})))))

(defn- returned
  "An engine's observation of a driver call that returned frame at ms."
  ([frame ms] (returned frame ms :normal))
  ([frame ms source]
   {:run-token 1 :session-token 2 :knowledge :returned
    :last-ok {:frame frame :at-ns (* (long ms) 1000000) :source source}}))

(def ^:private left-line [[-0.8 0.0 1.0 1.0 1.0] [-0.2 0.0 1.0 1.0 1.0]])

(def ^:private right-line [[0.2 0.0 1.0 1.0 1.0] [0.8 0.0 1.0 1.0 1.0]])

(deftest thumbnail-expires-and-recovers-with-a-frozen-tick-test
  ;; One tick and one app status, never changed; the card's own samples and
  ;; the clock of the returns move on their own (plan 18, example 3)
  (let [robot (ui/robot)
        frozen {:engines 1 :wanted 1 :pending? false :projectors {:a {:state :streaming :fps 60.0}}}]
    (events/write-state! #(-> %
                              (assoc :projectors {:a (laser)})
                              (assoc-in [:backend :streaming :running?] true)
                              (assoc-in [:backend :streaming :status] frozen)))
    (let [tick (:tick (show-time/publish! {:at-ns (System/nanoTime) :playing? false :cues []}))]
      (with-sampled-thumbnails
        (fn [{:keys [pulse! observe! at-ms! timers paints]}]
          (observe! :a (returned left-line 150))
          (ui/click! robot "#projectors-tab")
          (ui/wait-for! #(seq @timers))
          (let [sample! (fn [ms]
                          (at-ms! ms)
                          (pulse! ms)
                          [(ui/node-text "#rig-card-return-a") (said "#rig-card-thumbnail-a")
                           (lit-columns "#rig-card-thumbnail-a")])
                left? (fn [cols] (and (seq cols) (every? #(< % 22) cols)))
                right? (fn [cols] (and (seq cols) (every? #(> % 22) cols)))]
            (let [[caption text cols] (sample! 200)]
              (is (= "Driver frame" caption) "50 ms after the return")
              (is (= "Last successful driver call: frame" text))
              (is (left? cols) (pr-str cols)))
            (let [painted (count @paints)
                  [caption _ cols] (sample! 300)]
              (is (= "Driver frame" caption) "150 ms")
              (is (left? cols))
              (is (= painted (count @paints)) "the same frame, still fresh: not painted again"))
            (let [[caption text cols] (sample! 400)]
              (is (= "Stale" caption) "250 ms: expired, with no tick or prop change")
              (is (= "No recent successful driver return" text))
              (is (empty? cols) "the outline only"))
            (observe! :a (returned [] 480 :recovery))
            (let [[caption text cols] (sample! 500)]
              (is (= "Empty (retry)" caption) "the recovery's empty frame returned at 480")
              (is (= "Last successful driver call: empty recovery frame" text))
              (is (empty? cols)))
            (testing "after it, a new frame returns, still with no new tick"
              (observe! :a (returned right-line 590))
              (let [[caption _ cols] (sample! 600)]
                (is (= "Driver frame" caption))
                (is (right? cols) (pr-str cols)))))
          (is (= tick (:tick (show-time/latest))) "the tick never moved")
          (is (= frozen (get-in (state/get-raw-state) [:backend :streaming :status])) "nor the status"))))))

(deftest thumbnail-sampler-stops-with-its-node-test
  (let [robot (ui/robot)
        l1 [-1.0 -1.0 0.0 1.0]
        l2 [-0.5 -0.5 0.5 0.5]
        thumb (fn [id limit size k]
                {:fx/type fx/ext-recreate-on-key-changed
                 :key k
                 :desc {:fx/type preview/output-thumbnail :projector-id id :limit limit :size size}})]
    (with-sampled-thumbnails
      (fn [{:keys [pulse! observe! at-ms! timers reads paints]}]
        (observe! :a (returned left-line 0))
        (observe! :b (assoc (returned right-line 0) :run-token 3 :session-token 4))
        ;; The returns stay fresh: the pulses' times only space the samples
        (at-ms! 10)
        (testing "on a row of its own, in a window of its own"
          (let [base (clock-view/running-timers)
                pane (ui/on-fx-thread #(StackPane.))
                ^Stage stage (ui/on-fx-thread #(doto (Stage.) (.setScene (Scene. pane 120.0 80.0)) (.show)))
                component (atom (ui/on-fx-thread #(fx/create-component (thumb :a l1 44 1))))
                put! (fn [] (ui/on-fx-thread #(.setAll (.getChildren ^StackPane pane)
                                                       ^"[Ljavafx.scene.Node;" (into-array Node [(fx/instance @component)]))))
                advance! (fn [desc]
                           (ui/on-fx-thread #(swap! component fx/advance-component desc))
                           (put!))]
            (put!)
            (try
              (is (= 1 (count @timers)) "one timer for the row")
              (is (= (inc base) (clock-view/running-timers)))
              (testing "pulses at 0, 16, 99, 100, 116 and 200 ms: three samples, one paint"
                (doseq [ms [0 16 99 100 116 200]]
                  (pulse! ms))
                (is (= [:a :a :a] @reads))
                (is (= 1 (count @paints)) "the fresh frame doesn't change"))
              (testing "a new limit, then a new size: the next sample paints with them"
                (advance! (thumb :a l2 44 1))
                (pulse! 250)
                (is (= 3 (count @reads)) "50 ms after the last sample: none")
                (pulse! 300)
                (is (= [l2 44.0 44.0] (subvec (peek @paints) 0 3)))
                (advance! (thumb :a l2 50 1))
                (pulse! 400)
                (is (= [l2 50.0 50.0] (subvec (peek @paints) 0 3))))
              (testing "pointed at another projector: the next sample reads that one"
                (advance! (thumb :b l2 50 1))
                (pulse! 500)
                (is (= :b (peek @reads)))
                (is (identical? right-line (peek (peek @paints)))))
              (testing "its window hidden, nothing reads; shown again, the next pulse samples"
                (ui/on-fx-thread #(.hide stage))
                (let [n (count @reads)]
                  (doseq [ms [600 700 800]]
                    (pulse! ms))
                  (is (= n (count @reads)))
                  (ui/on-fx-thread #(.show stage))
                  (pulse! 810)
                  (is (= (inc n) (count @reads)))))
              (testing "replaced: one timer still, which samples once a pulse"
                (advance! (thumb :b l2 50 2))
                (is (= 1 (count @timers)) "no second painter")
                (is (= (inc base) (clock-view/running-timers)))
                (let [n (count @reads)]
                  (pulse! 900)
                  (is (= (inc n) (count @reads)))))
              (testing "deleted: its timer stops, and nothing reads"
                (ui/on-fx-thread #(fx/delete-component @component))
                (ui/on-fx-thread #(.clear (.getChildren ^StackPane pane)))
                (is (empty? @timers))
                (is (= base (clock-view/running-timers)))
                (let [n (count @reads)]
                  (pulse! 1000)
                  (pulse! 1100)
                  (is (= n (count @reads)))))
              (finally
                (ui/on-fx-thread #(.close stage))))))
        (testing "in its card: the tab's content goes, and comes back with a new sample of the current owner"
          (ui/click! robot "#projectors-tab")
          (ui/wait-for! #(ui/visible? "#rig-strip"))
          (let [base (clock-view/running-timers)]
            (events/write-state! assoc :projectors {:a (laser)})
            (ui/wait-for! #(= (inc base) (clock-view/running-timers)))
            (pulse! 2000)
            (ui/assert-text! "#rig-card-return-a" "Driver frame")
            (is (identical? left-line (peek (peek @paints))))
            (ui/click! robot "#grid-tab")
            (ui/wait-for! #(nil? (ui/query "#rig-card-return-a")))
            (let [n (count (filter #{:a} @reads))]
              (pulse! 2100)
              (is (= n (count (filter #{:a} @reads))) "no card, no reads"))
            (observe! :a (assoc (returned right-line 0) :run-token 5 :session-token 6))
            (ui/click! robot "#projectors-tab")
            (ui/wait-for! #(some? (ui/query "#rig-card-return-a")))
            (pulse! 2101)
            (is (= :a (peek @reads)))
            (is (identical? right-line (peek (peek @paints))) "the owner it has now")
            (events/write-state! update :projectors dissoc :a)
            (ui/wait-for! #(= base (clock-view/running-timers)))))))))

(deftest card-turns-amber-below-its-minimum-test
  (let [robot (ui/robot)]
    (events/write-state! assoc :projectors {:a (laser)})
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
    (events/write-state! assoc :projectors {:a (laser)})
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

(deftest decimated-laser-meters-what-it-draws-test
  (let [robot (ui/robot)]
    (events/write-state! #(-> %
                              (assoc :projectors {:a (laser)})
                              (assoc-in [:chains :projector-effects :a :items]
                                        [{:id (random-uuid) :effect-id :decimate :enabled? true :params {:kpps 25 :fps 30}}])))
    (play! (circle-cue 3000))
    (ui/click! robot "#projectors-tab")
    (fixtures/with-clock
      (fn [_]
        (stream! robot)
        (ui/wait-for! #(= "67%" (ui/node-text "#rig-card-load-pct-a")))
        (is (not-any? #(ui/has-style-class? "#rig-card-load-a .load-meter" %) ["load-meter-near" "load-meter-over"])
            "green: the 833 points Decimate left, of its 1,250, not the 3,000 it is asked for (Q-D-1 B)")
        (is (= "−72% pts" (ui/node-text "#rig-card-load-note-a")) "the whole cut, the guard's and Decimate's")
        (is (not (ui/has-style-class? "#rig-card-a" "rig-card-low")) "30 fps: not low")
        (is (false? (:low? (status))))))))

(deftest fit-guard-settings-apply-test
  (let [robot (ui/robot)
        fit #(get-in (state/get-raw-state) [:projectors :a :fit])]
    (events/write-state! #(-> %
                              (assoc :projectors {:a (laser)})
                              (assoc-in [:projector-ui :active-projector] :a)))
    (play! (circle-cue 3000))
    (ui/click! robot "#projectors-tab")
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
      (ui/uncheck! robot "#projector-fit-reduce")
      (ui/wait-for! #(false? (:reduce-points? (fit))))
      (ui/assert-disabled! "#projector-fit-point-floor")
      (ui/check! robot "#projector-fit-dim")
      (ui/wait-for! #(true? (:dim? (fit))))
      (ui/assert-enabled! "#projector-fit-dim-floor")
      (ui/fill! robot "#projector-fit-dim-floor" "70")
      (ui/submit! robot "#projector-fit-dim-floor")
      (ui/wait-for! #(= 0.7 (:dim-floor (fit)))))))
