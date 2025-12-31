(ns laser-show.state.clipboard-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.state.clipboard :as clipboard]))

(deftest clipboard-types-test
  (testing "valid clipboard types"
    (is (clipboard/valid-clipboard-type? :cue))
    (is (clipboard/valid-clipboard-type? :zone))
    (is (clipboard/valid-clipboard-type? :projector))
    (is (clipboard/valid-clipboard-type? :effect))
    (is (clipboard/valid-clipboard-type? :cell-assignment))
    (is (not (clipboard/valid-clipboard-type? :invalid)))))

(deftest clipboard-empty-test
  (testing "clipboard starts empty"
    (clipboard/clear-clipboard!)
    (is (clipboard/clipboard-empty?))
    (is (nil? (clipboard/get-clipboard)))))

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

(deftest clipboard-type-checking-test
  (testing "clipboard type checking"
    (clipboard/clear-clipboard!)
    (clipboard/copy-cell-assignment! :wave)
    (is (clipboard/clipboard-has-type? :cell-assignment))
    (is (not (clipboard/clipboard-has-type? :cue)))
    (is (not (clipboard/clipboard-has-type? :zone)))))


