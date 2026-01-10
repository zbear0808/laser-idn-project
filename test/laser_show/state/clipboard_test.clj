(ns laser-show.state.clipboard-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [laser-show.state.core :as state]
            [laser-show.state.clipboard :as clipboard]))

(defn init-state-fixture [f]
  "Initialize state before each test"
  (state/init-state! {:ui {:clipboard nil}})
  (f))

(use-fixtures :each init-state-fixture)

(deftest cell-assignment-copy-paste-test
  (testing "copy cell assignment"
    (clipboard/clear-clipboard!)
    (is (clipboard/copy-cell-assignment! :circle))
    (is (not (clipboard/clipboard-empty?)))
    (is (clipboard/clipboard-has-type? :cell-assignment))
    (is (clipboard/can-paste-cell-assignment?)))
  
  (testing "paste cell assignment"
    (is (= :circle (clipboard/paste-cell-assignment))))
  
  (testing "copy nil returns false"
    (clipboard/clear-clipboard!)
    (is (nil? (clipboard/copy-cell-assignment! nil)))
    (is (clipboard/clipboard-empty?))))

(deftest clipboard-description-test
  (testing "empty clipboard description"
    (clipboard/clear-clipboard!)
    (is (= "Empty" (clipboard/get-clipboard-description))))
  
  (testing "cell assignment description"
    (clipboard/copy-cell-assignment! :spinning-square)
    (is (= "Preset: spinning-square" (clipboard/get-clipboard-description)))))

