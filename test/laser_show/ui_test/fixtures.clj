(ns laser-show.ui-test.fixtures
  "Fixtures shared by the TestFX UI test namespaces. Every UI test namespace
   uses both:

     (use-fixtures :once fixtures/with-toolkit)
     (use-fixtures :each fixtures/with-app)

   with-app mounts a fresh app for each test, so any test can run on its own."
  (:require [laser-show.app :as app]
            [laser-show.ui-test.testfx :as ui]))

(defn with-toolkit
  "Once fixture: registers TestFX's primary stage, closes the stages after."
  [tests]
  (ui/init-toolkit!)
  (try
    (tests)
    (finally
      (ui/cleanup-toolkit!))))

(defn with-app
  "Each fixture: mounts the app with app/start-test! (starter state, no
   hardware or network services) and unmounts it after the test."
  [tests]
  (app/start-test!)
  (try
    (ui/sync-fx!)
    (tests)
    (finally
      (app/stop!)
      (ui/sync-fx!))))
