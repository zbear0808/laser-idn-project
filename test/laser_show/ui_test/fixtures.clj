(ns laser-show.ui-test.fixtures
  "Fixtures shared by the TestFX UI test namespaces. Every UI test namespace
   uses both:

     (use-fixtures :once fixtures/with-toolkit)
     (use-fixtures :each fixtures/with-app)

   with-app mounts a fresh app for each test, so any test can run on its own.
   On the first failure or error in a test it saves a screenshot to
   test-results/<namespace>/<test>.png (CI uploads test-results/)."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [clojure.tools.logging :as log]
            [laser-show.app :as app]
            [laser-show.ui-test.testfx :as ui]))

(defn with-toolkit
  "Once fixture: registers TestFX's primary stage, closes the stages after."
  [tests]
  (ui/init-toolkit!)
  (try
    (tests)
    (finally
      (ui/cleanup-toolkit!))))

(defn- screenshot-path []
  (when-let [v (first t/*testing-vars*)]
    (let [{:keys [ns name]} (meta v)]
      (str "test-results/" (str/replace (str (ns-name ns)) "." "/")
           "/" name ".png"))))

(defn- screenshot-on-failure
  "Wrap clojure.test/report so the first :fail/:error in a test captures the
   UI while it is still mounted."
  [report taken?]
  (fn [m]
    (when (and (#{:fail :error} (:type m)) (not @taken?))
      (reset! taken? true)
      (when-let [path (screenshot-path)]
        (try
          (ui/screenshot! nil path)
          (println "UI screenshot:" path)
          (catch Throwable e
            (log/warn e "Could not capture failure screenshot")))))
    (report m)))

(defn with-app
  "Each fixture: mounts the app with app/start-test! (starter state, no
   hardware or network services) and unmounts it after the test."
  [tests]
  (app/start-test!)
  (try
    (ui/sync-fx!)
    (binding [t/report (screenshot-on-failure t/report (atom false))]
      (tests))
    (finally
      (app/stop!)
      (ui/sync-fx!))))
