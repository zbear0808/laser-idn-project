(ns laser-show.ui-test.e2e-test
  "Headless/local TestFX smoke tests for the production cljfx view."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.app :as app]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures
  :once
  (fn [tests]
    (ui/init-toolkit!)
    (try
      (tests)
      (finally
        (ui/cleanup-toolkit!)))))

(use-fixtures
  :each
  (fn [tests]
    (app/start-test!)
    (try
      (ui/sync-fx!)
      (tests)
      (finally
        (app/stop!)
        (ui/sync-fx!)))))

(deftest user-can-type-and-switch-tabs
  (let [robot (ui/robot)]
    (ui/fill! robot "#bpm-input" "140")
    (is (= "140" (ui/node-text "#bpm-input")))
    (is (some? (ui/query "#settings-tab")))
    (ui/click! robot "#settings-tab")))
