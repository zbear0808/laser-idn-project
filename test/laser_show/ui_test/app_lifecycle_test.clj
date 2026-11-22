(ns laser-show.ui-test.app-lifecycle-test
  "The app's events and writes while it runs
   (plans/2026-11-20-state-integrity/02-direct-state-writes.md): every app
   instance's events run on one thread, events/event-thread-name (W3 A),
   tests write app state on it with events/write-state! (W5 A), the Link
   connection comes in by event, stop! writes nothing and closes each device
   once (W4 A), and while the app runs a direct write from another thread
   throws (W2 A)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.app :as app]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.dev :as dev]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.input.link :as link]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures])
  (:import [javafx.application Platform]))

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
    (slowed :timing/link-peers-changed 200 threads
            (fn []
              ;; A late event for the old app, then the new app's own
              (old-handler {:event/type :timing/link-peers-changed :peers 1})
              (Thread/sleep 50)
              (events/dispatch! {:event/type :timing/set-bpm :bpm 99.0})
              (is (events/flush!))))
    (is (= 99.0 (get-in (state/get-raw-state) [:timing :bpm]))
        "the old app's write-back ran before the new app's event, not alongside it")
    (is (= {:timing/link-peers-changed events/event-thread-name
            :timing/set-bpm events/event-thread-name}
           @threads))))

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
