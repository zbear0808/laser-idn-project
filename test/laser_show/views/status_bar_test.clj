(ns laser-show.views.status-bar-test
  "Unit tests for status bar view components."
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.views.status-bar :as status-bar]
            [clojure.string :as str]))

(def format-us #'laser-show.views.status-bar/format-us)

(deftest format-us-test
  (testing "formats microseconds with 5-digit width and right alignment"
    (is (= "    0µs" (format-us 0)))
    (is (= "    0µs" (format-us nil)))
    (is (= "   42µs" (format-us 42)))
    (is (= "  350µs" (format-us 350)))
    (is (= " 1200µs" (format-us 1200)))
    (is (= "15000µs" (format-us 15000)))
    (is (= 7 (count (format-us 42))))
    (is (= 7 (count (format-us 15000))))))

(deftest status-item-test
  (testing "renders default status-item without fixed width"
    (let [rendered (status-bar/status-item {:label "Test" :value "123"})
          [_ val-label] (:children rendered)]
      (is (= :h-box (:fx/type rendered)))
      (is (= "123" (:text val-label)))
      (is (nil? (:min-width val-label)))
      (is (nil? (:pref-width val-label)))))

  (testing "renders status-item with value-width and monospace font"
    (let [rendered (status-bar/status-item {:label "Base"
                                           :value "   42µs"
                                           :value-width 56
                                           :monospace? true})
          [_ val-label] (:children rendered)]
      (is (= 56 (:min-width val-label)))
      (is (= 56 (:pref-width val-label)))
      (is (= :center-left (:alignment val-label)))
      (is (str/includes? (:style val-label) "Consolas")))))

