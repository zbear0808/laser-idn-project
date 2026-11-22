(ns laser-show.ui-test.app-lifecycle-test
  "The app's events and writes while it runs
   (plans/2026-11-20-state-integrity/02-direct-state-writes.md): every app
   instance's events run on one thread, events/event-thread-name (W3 A),
   and tests write app state on it with events/write-state! (W5 A)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.app :as app]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.timing :as timing]
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
