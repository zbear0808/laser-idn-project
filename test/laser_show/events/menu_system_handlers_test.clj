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
                      (f)))

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
  (testing "the wired pipeline knows :system/exit (dev mode keeps the JVM alive)"
    (with-dev-mode
      #(is (nil? (events/event-handler {:event/type :file/exit}))))))

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
