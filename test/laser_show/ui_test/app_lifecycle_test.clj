(ns laser-show.ui-test.app-lifecycle-test
  "The app's events and writes while it runs
   (plans/2026-11-20-state-integrity/02-direct-state-writes.md): every app
   instance's events run on one thread, events/event-thread-name (W3 A),
   tests write app state on it with events/write-state! (W5 A), the Link
   connection comes in by event, stop! writes nothing and closes each device
   once (W4 A), and while the app runs a direct write from another thread
   throws (W2 A). Once stopped, an app instance's late work (an event still
   running, a scan, a learn, the rig writer's results, Exit coming back)
   changes nothing in the app started after it (reactivity plan 19)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging.test :as log-test]
            [laser-show.app :as app]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.dev :as dev]
            [laser-show.events.handlers.input :as input]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.events.helpers :as h]
            [laser-show.input.link :as link]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.output.discovery :as discovery]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [javafx.application Platform]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- current-handler
  "The mounted app's event handler (cljfx's, on its agent)."
  []
  (:handler @@#'app/*app))

(defn- slowed
  "Call f while timing/handle sleeps ms before each event of type slow-type,
   recording every timing event's thread into threads."
  [slow-type ms threads f]
  (let [handle timing/handle]
    (with-redefs [timing/handle (fn [event]
                                  (swap! threads assoc (:event/type event) (.getName (Thread/currentThread)))
                                  (when (= slow-type (:event/type event))
                                    (Thread/sleep (long ms)))
                                  (handle event))]
      (f))))

(deftest old-and-new-app-share-the-event-thread-test
  (let [old-handler (current-handler)
        threads (atom {})]
    (app/start-test!)
    (slowed :timing/set-bpm 200 threads
            (fn []
              ;; The new app's event in flight, then a late event for the
              ;; old app and its queue's marker
              (events/dispatch! {:event/type :timing/set-bpm :bpm 99.0})
              (Thread/sleep 50)
              (old-handler {:event/type :timing/link-peers-changed :peers 3})
              (let [p (promise)]
                (old-handler {:event/type :dev/flush :promise p})
                (is (deref p 2000 false) "the old app's queue is still handled")
                (is (= 99.0 (get-in (state/get-raw-state) [:timing :bpm]))
                    "after the new app's event, not alongside it"))))
    (is (= {:timing/set-bpm events/event-thread-name} @threads)
        "the new app's event ran on the event thread; the old app's never reached its handler")
    (is (not= 3 (get-in (state/get-raw-state) [:backend :link :link-peers])) "a stopped app's event changes nothing")))

(deftest write-state-lands-after-the-event-in-flight-test
  (let [threads (atom {})]
    (slowed :timing/set-bpm 200 threads
            (fn []
              (events/dispatch! {:event/type :timing/set-bpm :bpm 128.0})
              (Thread/sleep 50)
              ;; While set-bpm's handler runs on its snapshot
              (events/write-state! assoc-in [:chains :cue-chains [0 0] :name] "Written meanwhile")
              (is (= "Written meanwhile" (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :name]))
                  "written when write-state! returns")))
    (is (events/flush!))
    (is (= "Written meanwhile" (get-in (state/get-raw-state) [:chains :cue-chains [0 0] :name]))
        "the event's write-back didn't undo it")
    (is (= 128.0 (get-in (state/get-raw-state) [:timing :bpm])) "and the event's own change landed")))

(deftest a-direct-write-while-the-app-runs-throws-test
  (let [e (try (state/swap-state! assoc-in [:timing :bpm] 99.0)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "the write from the test thread is refused")
    (is (re-find #"outside the event pipeline" (ex-message e)))
    (is (= (.getName (Thread/currentThread)) (:thread (ex-data e)))))
  (is (not= 99.0 (get-in (state/get-raw-state) [:timing :bpm])) "and not written")
  (events/write-state! assoc-in [:timing :bpm] 99.0)
  (is (= 99.0 (get-in (state/get-raw-state) [:timing :bpm])) "write-state! writes on the event thread"))

(deftest link-connection-survives-an-event-in-flight-test
  (let [threads (atom {})]
    (with-redefs [link/connect-carabiner! (fn [ls _ _] (assoc ls :carabiner-connected? true :listener ::listener))]
      (slowed :show-clock/status 200 threads
              (fn []
                ;; What the show clock sends a few times a second
                (events/dispatch! {:event/type :show-clock/status :status {:beat-index 1}})
                (Thread/sleep 50)
                (@#'app/connect-link!)
                (is (events/flush!)))))
    (let [link-state (get-in (state/get-raw-state) [:backend :link])]
      (try
        (is (true? (:carabiner-connected? link-state)) "the event's write-back didn't take the connection away")
        (is (= ::listener (:listener link-state)))
        (is (true? (:auto-connect? link-state)) "the other link keys are kept")
        (finally
          ;; Nothing for the fixture's stop! to disconnect
          (events/write-state! update-in [:backend :link] #(-> % (assoc :carabiner-connected? false) (dissoc :listener))))))))

(deftest stop-writes-nothing-and-closes-each-device-once-test
  (let [calls (atom {})
        count! #(swap! calls update % (fnil inc 0))
        handler (current-handler)
        threads (atom {})]
    (events/write-state! assoc-in [:backend :link :carabiner-connected?] true)
    (let [before (state/get-raw-state)]
      (try
        (with-redefs [midi/disconnect-all (fn [m]
                                            (count! :midi)
                                            ;; Closing the ports takes a moment
                                            (Thread/sleep 300)
                                            m)
                      osc/stop-server (fn [o] (count! :osc) o)
                      link/disconnect-carabiner! (fn [l] (count! :link) (assoc l :carabiner-connected? false))]
          ;; An event in flight that writes neither [:backend :input] nor
          ;; [:backend :link] (:show-clock/status writes the MTC status)
          (slowed :timing/set-bpm 200 threads
                  (fn []
                    (events/dispatch! {:event/type :timing/set-bpm :bpm 128.0})
                    (Thread/sleep 50)
                    (app/stop!)
                    ;; The old app's queue, the event in flight written back
                    (let [p (promise)]
                      (handler {:event/type :dev/flush :promise p})
                      (is (deref p 2000 false)))
                    (app/stop!))))
        (let [after (state/get-raw-state)]
          (is (= {:midi 1 :osc 1 :link 1} @calls) "each device closed once")
          (is (identical? (get-in before [:backend :input]) (get-in after [:backend :input])) "nothing written")
          (is (identical? (get-in before [:backend :link]) (get-in after [:backend :link])) "nothing written"))
        (finally
          ;; Nothing left connected for the fixture's stop!
          (events/write-state! assoc-in [:backend :link :carabiner-connected?] false))))))

(deftest stop-on-the-event-thread-does-not-wait-test
  (let [flushes (atom [])
        markers (atom 0)
        stopped (promise)
        flush events/flush!
        dev-handle dev/handle]
    (with-redefs [events/flush! (fn [& args]
                                  (let [result (apply flush args)]
                                    (swap! flushes conj [(.getName (Thread/currentThread)) result])
                                    result))
                  dev/handle (fn [event]
                               (when (= :dev/flush (:event/type event)) (swap! markers inc))
                               (dev-handle event))]
      (events/set-exit-fn! #(do (app/stop!) (deliver stopped true)))
      ;; File > Exit on a clean project: :system/exit, on the event thread
      (events/dispatch! {:event/type :file/exit})
      (is (deref stopped 3000 false) "Quit stopped the app"))
    (is (= [[events/event-thread-name false]] @flushes) "flush! returned false at once on the event thread")
    (is (zero? @markers) "and queued no :dev/flush")
    (is (nil? @@#'app/*app))))

(deftest flush-does-not-wait-on-the-fx-thread-or-forever-test
  (testing "on the FX thread: false at once"
    (let [result (promise)]
      (Platform/runLater #(deliver result (events/flush!)))
      (is (false? (deref result 1000 ::timeout)))))
  (testing "an event in flight longer than the timeout: false after the timeout"
    (let [threads (atom {})]
      (slowed :timing/set-bpm 1000 threads
              (fn []
                (events/dispatch! {:event/type :timing/set-bpm :bpm 128.0})
                (let [t0 (System/currentTimeMillis)
                      result (events/flush! 100)
                      ms (- (System/currentTimeMillis) t0)]
                  (is (false? result))
                  (is (< ms 900) (str ms " ms")))
                (is (events/flush!) "then the queue empties"))))))


;; A stopped app's late work (reactivity plan 19)


(defn- starter
  "The starter show app/start-test! mounts, for a test to build on."
  []
  (#'app/starter-state))

(defn- holding
  "(f entered gate) while :timing/set-bpm's handler, once it has its
   event's state, waits for gate (5 s at most); entered is delivered then.
   The gate opens after f in any case."
  [f]
  (let [entered (promise)
        gate (promise)
        handle timing/handle]
    (with-redefs [timing/handle (fn [event]
                                  (when (= :timing/set-bpm (:event/type event))
                                    (deliver entered true)
                                    (deref gate 5000 nil))
                                  (handle event))]
      (try
        (f entered gate)
        (finally (deliver gate true))))))

(defn- handled!
  "Wait until the queue of the app whose handler this is has handled what
   it was given before."
  [handler]
  (let [p (promise)]
    (handler {:event/type :dev/flush :promise p})
    (is (deref p 5000 false) "the stopped app's queue is still handled")))

(defn- dropped?
  "Was an event of type dropped as a stopped app's, within 3 s (inside
   log-test/with-log)?"
  [type]
  (let [pattern (re-pattern (java.util.regex.Pattern/quote (str "dropped " type)))]
    (try
      (ui/wait-for! #(log-test/logged? 'laser-show.events.core :info pattern))
      (catch clojure.lang.ExceptionInfo _ false))))

(deftest retired-app-cannot-commit-after-restart-test
  (events/write-state! assoc-in [:chains :cue-chains [0 0] :name] "Old show")
  (let [old-show (h/document-id (state/get-raw-state))
        old-handler (current-handler)]
    (holding
     (fn [entered gate]
       ;; An event of this app has its state and is held there, while the
       ;; app is replaced: stop!'s wait for it runs out
       (events/dispatch! {:event/type :timing/set-bpm :bpm 99.0})
       (is (deref entered 2000 false))
       (app/start-test!)
       (let [started (state/get-raw-state)]
         (is (not= old-show (h/document-id started)) "a new show")
         (deliver gate true)
         (handled! old-handler)
         (is (events/flush!))
         (let [s (state/get-raw-state)]
           (is (= (h/document-id started) (h/document-id s)) "the new show is still the one open")
           (is (not= "Old show" (get-in s [:chains :cue-chains [0 0] :name])) "the old one didn't come back")
           (is (not= 99.0 (get-in s [:timing :bpm])) "nor did the old event's own change")))))))

(def ^:private unit "0A 1B 2C 3D")

(defn- laser-at
  "The starter show, on a rig of one laser (the unit's first output) at host."
  [host]
  (assoc-in (starter) [:projectors :projector-10-0-0-5-1]
            {:name "Laser L" :host host :port 7255 :service-id 1 :unit-id unit :stable-id [:unit unit 1]
             :enabled? false :zone-groups [] :exclude-from-all? false :tags #{}
             :output-config {:color-bit-depth 8 :xy-bit-depth 16}}))

(deftest retired-scan-completion-is-ignored-test
  (log-test/with-log
    (let [scanning (promise)
          gate (promise)
          found {:devices [{:address "10.0.0.5" :host-name "DAC" :unit-id unit :port 7255 :status {}
                            :protocol-version {:major 1 :minor 0} :services [{:service-id 1 :name "Head 1"}]
                            :relays [] :driver :idn}]
                 :warnings []}]
      (app/start-test! (laser-at "10.0.0.5"))
      (with-redefs [discovery/scan (fn [_] (deliver scanning true) (deref gate 5000 nil) found)]
        (let [old-handler (current-handler)]
          ;; Scan, held in discovery while the app is replaced by one whose
          ;; laser has moved to .6
          (events/dispatch! {:event/type :projectors/scan-network})
          (is (deref scanning 2000 false))
          (let [old-scan (get-in (state/get-raw-state) [:projector-ui :scan-id])]
            (app/start-test! (laser-at "10.0.0.6"))
            (let [started (state/get-raw-state)
                  unchanged! (fn [why]
                               (let [s (state/get-raw-state)]
                                 (is (= "10.0.0.6" (get-in s [:projectors :projector-10-0-0-5-1 :host]))
                                     (str why ": the new rig's laser stays where it is"))
                                 (is (= (:projector-ui started) (:projector-ui s)) "no devices, Scan as it was")
                                 (is (= (:history started) (:history s)) "nothing put into undo history")
                                 (is (nil? (get-in s [:ui :notice])) "and no notice")))]
              (deliver gate true)
              (is (dropped? :projectors/scan-complete) "the old app's scan comes back to it")
              (handled! old-handler)
              (is (events/flush!))
              (unchanged! "the stopped app's scan")
              (testing "its result is the old scan's for the new app too"
                (events/dispatch! (assoc found :event/type :projectors/scan-complete :scan-id old-scan))
                (is (events/flush!))
                (unchanged! "an old scan's result")))))))))

(deftest retired-app-learn-capture-commits-nothing-test
  (log-test/with-log
    (let [target {:type :trigger-cue :target [0 0]}
          old-handler (current-handler)]
      (events/dispatch! {:event/type :input/start-learn :target-action target})
      (is (events/flush!))
      (let [capture @@#'input/!pending-learn]
        (try
          ;; The app replaced by one learning the same cell before the old
          ;; learn's input comes
          (app/start-test! (assoc-in (starter) [:backend :input :learning-target] target))
          (let [started (state/get-raw-state)]
            (deliver capture {:type :note-on :source :midi :channel 0 :note 60 :velocity 100})
            (is (dropped? :input/learn-captured) "the old learn's input comes back to the old app")
            (handled! old-handler)
            (is (events/flush!))
            (let [s (state/get-raw-state)]
              (is (= (get-in started [:config :input :trigger-map]) (get-in s [:config :input :trigger-map]))
                  "nothing bound in the new show")
              (is (= target (get-in s [:backend :input :learning-target])) "whose own learn still waits")
              (is (false? (get-in s [:project :dirty?])))
              (is (= (:history started) (:history s)) "no undo step")))
          (finally
            (events/dispatch! {:event/type :input/cancel-learn})
            (events/flush!)))))))

(defn- with-temp-rig
  "(f path) with path a rig.edn in a new temp folder, deleted after an app
   without a rig file took over (so it is let go of, and not written)."
  [f]
  (let [dir (.toFile (Files/createTempDirectory "app-lifecycle-test" (make-array FileAttribute 0)))]
    (try
      (f (str (io/file dir "rig.edn")))
      (finally
        (app/start-test!)
        (doseq [^File file (reverse (file-seq dir))] (.delete file))))))

(deftest retired-app-rig-results-and-exit-do-nothing-test
  (with-temp-rig
    (fn [path]
      (let [exits (atom 0)
            on-rig #(assoc-in (starter) [:rig-file :path] path)]
        (app/start-test! (on-rig))
        (let [old-handler (current-handler)]
          (events/set-exit-fn! #(swap! exits inc))
          ;; A rig change: its write goes to the rig writer
          (events/dispatch! {:event/type :projectors/add-manual :name "Laser L" :host "10.0.0.5"})
          (is (events/flush!))
          (holding
           (fn [entered gate]
             ;; The event thread held, so what the rig writer reports to
             ;; this app waits in its queue while the app is replaced:
             ;; stop! lets go of the rig file once it is written, and the
             ;; new app takes it
             (events/dispatch! {:event/type :timing/set-bpm :bpm 99.0})
             (is (deref entered 2000 false))
             (app/start-test! (on-rig))
             (events/set-exit-fn! #(swap! exits inc))
             (let [started (state/get-raw-state)]
               (is (= ["Laser L"] (map :name (vals (:projectors started)))) "the new app reads the rig as written")
               (deliver gate true)
               ;; And Exit coming back to the old app, as its wait for the
               ;; rig writer sends it
               (old-handler {:event/type :file/exit :force? true :rig-waited? true})
               (handled! old-handler)
               (is (events/flush!))
               (let [s (state/get-raw-state)]
                 (is (= (:rig-file started) (:rig-file s)) "the new app's rig file status is its own")
                 (is (nil? (get-in s [:ui :notice])) "no notice")
                 (is (zero? @exits) "and the old app's Exit didn't end the new one"))))))))))
