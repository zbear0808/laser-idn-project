(ns laser-show.ui-test.app-lifecycle-test
  "Every app instance's events run on one thread, events/event-thread-name
   (plans/2026-11-20-state-integrity/02-direct-state-writes.md, W3 A)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.app :as app]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- current-handler
  "The mounted app's event handler (cljfx's, on its agent)."
  []
  (:handler @@#'app/*app))

(defn- settle!
  "Wait (up to 3 s) until handler's agent has handled everything sent to it
   so far: true, or false on a timeout."
  [handler]
  (not= ::timeout (deref (future (handler {:event/type :timing/marker :fx/sync true})) 3000 ::timeout)))

(deftest old-and-new-app-share-the-event-thread-test
  (let [old-handler (current-handler)
        threads (atom {})
        handle timing/handle]
    (app/start-test!)
    (with-redefs [timing/handle (fn [event]
                                  (swap! threads assoc (:event/type event) (.getName (Thread/currentThread)))
                                  (when (= :timing/link-peers-changed (:event/type event))
                                    (Thread/sleep 200))
                                  (handle event))]
      ;; A late event for the old app, then the new app's own
      (old-handler {:event/type :timing/link-peers-changed :peers 1})
      (Thread/sleep 50)
      (events/dispatch! {:event/type :timing/set-bpm :bpm 99.0})
      (is (settle! old-handler))
      (is (settle! (current-handler))))
    (is (= 99.0 (get-in (state/get-raw-state) [:timing :bpm]))
        "the old app's write-back ran before the new app's event, not alongside it")
    (is (= {:timing/link-peers-changed events/event-thread-name
            :timing/set-bpm events/event-thread-name}
           (dissoc @threads :timing/marker)))))
