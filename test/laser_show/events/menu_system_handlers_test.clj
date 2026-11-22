(ns laser-show.events.menu-system-handlers-test
  "Tests for File > Exit and Help menu handlers that return non-:state effects."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [laser-show.events.core :as events]
   [laser-show.events.handlers.menu :as menu]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains]))

(use-fixtures :each (fn [f]
                      (state/init-state! (domains/build-initial-state))
                      (events/set-exit-fn! nil)
                      (try (f) (finally (events/set-exit-fn! nil)))))

(defn- with-dev-mode [f]
  (let [old (System/getProperty "laser-show.dev")]
    (System/setProperty "laser-show.dev" "true")
    (try (f)
         (finally (if old
                    (System/setProperty "laser-show.dev" old)
                    (System/clearProperty "laser-show.dev"))))))

(deftest exit-handler-test
  (is (= {:system/exit true}
         (menu/handle {:event/type :file/exit :state (state/get-raw-state)}))))

(deftest exit-effect-is-registered-test
  (testing "the wired pipeline knows :system/exit; with no application running it is ignored"
    (is (nil? (events/event-handler {:event/type :file/exit})))
    (with-dev-mode
      #(is (nil? (events/event-handler {:event/type :file/exit}))))))

(deftest exit-effect-calls-the-registered-exit-fn-test
  (let [exits (atom 0)]
    (events/set-exit-fn! #(swap! exits inc))
    (events/event-handler {:event/type :file/exit})
    (is (= 1 @exits))
    (testing "a dirty project asks first and does not exit"
      (state/swap-state! assoc-in [:project :dirty?] true)
      (events/event-handler {:event/type :file/exit})
      (is (= 1 @exits))
      (is (true? (get-in (state/get-raw-state) [:ui :dialogs :unsaved-confirm :open?]))))
    (testing "Don't Save then exits"
      (events/event-handler {:event/type :file/confirm-discard})
      (is (= 2 @exits)))))

(deftest documentation-handler-test
  (let [result (menu/handle {:event/type :help/documentation :state (state/get-raw-state)})]
    (is (= #{:system/open-url} (set (keys result))))
    (is (re-find #"^https://" (:system/open-url result)))))

(deftest reload-handlers-return-effects-only-test
  (testing "no :state, so the reload can't be overwritten by a stale snapshot"
    (is (= {:dev/reload :styles}
           (menu/handle {:event/type :help/reload-styles :state (state/get-raw-state)})))
    (is (= {:dev/reload :app}
           (menu/handle {:event/type :help/reload-app :state (state/get-raw-state)})))))

(deftest reload-styles-pipeline-keeps-trigger-test
  (testing "the reload trigger bumped by the effect survives the event"
    (events/event-handler {:event/type :help/reload-styles})
    ;; tools.namespace comes in with the test runner; without it the effect
    ;; only logs, and the trigger stays unset.
    (if (try (requiring-resolve 'clojure.tools.namespace.find/find-namespaces-in-dir)
             (catch Throwable _ nil))
      (is (= 1 (get-in (state/get-raw-state) [:styles :reload-trigger])))
      (is (nil? (get-in (state/get-raw-state) [:styles :reload-trigger]))))))

(deftest dev-reloaded-test
  (let [s0 (state/get-raw-state)
        reloaded #(:state (events/handle-event-pipeline {:event/type :dev/reloaded :kind %1 :state %2 :time 1234}))
        unchanged-history-and-dirty (fn [s]
                                      (is (= (:history s0) (:history s)) "no undo entry")
                                      (is (= (get-in s0 [:project :dirty?]) (get-in s [:project :dirty?])) "not dirty")
                                      (is (not (get-in s [:project :dirty?]))))]
    (testing ":styles bumps the reload trigger"
      (let [s (reloaded :styles s0)]
        (is (= 1 (get-in s [:styles :reload-trigger])))
        (is (= 2 (get-in (reloaded :styles s) [:styles :reload-trigger])))
        (unchanged-history-and-dirty s)))
    (testing ":app sets the reload timestamp to the event's :time"
      (let [s (reloaded :app s0)]
        (is (= 1234 (get-in s [:ui :last-reload-timestamp])))
        (is (= (:styles s0) (:styles s)))
        (unchanged-history-and-dirty s)))
    (testing "through the wired pipeline: :time is the co-effect's"
      (let [t0 (System/currentTimeMillis)]
        (events/event-handler {:event/type :dev/reloaded :kind :app})
        (is (<= t0 (get-in (state/get-raw-state) [:ui :last-reload-timestamp]) (System/currentTimeMillis)))))))
