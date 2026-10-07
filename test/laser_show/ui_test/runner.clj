(ns laser-show.ui-test.runner
  "Orchestration layer for UI integration tests.
   Boots the cljfx application, runs test functions, and tears down."
  (:require [laser-show.app :as app]
            [laser-show.ui-test.driver :as driver])
  (:import [java.io File]))

(def ^:private test-results-dir "test-results")

(defn run-test!
  "Boots the application, runs the 0-arity test function, and tears down."
  [test-fn]
  (.mkdirs (File. test-results-dir))
  (try
    (app/start!)
    (driver/wait!) ;; Allow initial render to complete
    (test-fn)
    (finally
      (app/stop!))))
