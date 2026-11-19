(ns laser-show.ui-test.status-bar-test
  "The status bar: what plays, by cell and name, and the notice a failure
   leaves, which goes on a click or after 6 s."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest status-bar-names-what-plays-test
  (let [robot (ui/robot)]
    (ui/assert-visible! "#status-bar")
    (ui/assert-no-node! "#status-playing")
    (ui/click! robot "#grid-cell-1-0")
    (ui/assert-text! "#status-playing" "A2 square")
    (ui/click! robot "#grid-cell-0-0")
    (ui/assert-text! "#status-playing" "A1 circle · A2 square")
    (testing "nothing plays while Stop holds the cues"
      (events/dispatch! {:event/type :transport/stop})
      (ui/wait-for! #(nil? (ui/query "#status-playing"))))
    (testing "Play brings them back"
      (events/dispatch! {:event/type :transport/play})
      (ui/assert-text! "#status-playing" "A1 circle · A2 square"))))

(defn- fail-to-open! [file-name]
  (events/dispatch! {:event/type :file/open-result :file-path (str "test-output/" file-name)}))

(deftest failed-open-shows-a-notice-until-clicked-test
  (let [robot (ui/robot)]
    (ui/assert-no-node! "#status-notice")
    (fail-to-open! "no-such-show.zip")
    (ui/assert-text! "#status-notice-text" "Could not open no-such-show.zip")
    (ui/click! robot "#status-notice")
    (ui/wait-for! #(nil? (ui/query "#status-notice")))
    (is (nil? (get-in (state/get-raw-state) [:ui :notice])))))

(deftest notice-goes-by-itself-after-6-s-test
  (fail-to-open! "first.zip")
  (ui/assert-text! "#status-notice-text" "Could not open first.zip")
  (Thread/sleep 3000)
  (testing "a later failure replaces it, and the first one's timer leaves it"
    (fail-to-open! "second.zip")
    (ui/assert-text! "#status-notice-text" "Could not open second.zip")
    (Thread/sleep 4000)
    (is (= "Could not open second.zip" (ui/node-text "#status-notice-text"))
        "7 s after the first, 4 s after the second"))
  (testing "its own timer takes it away"
    (ui/wait-for! 5000 #(nil? (ui/query "#status-notice")))))
