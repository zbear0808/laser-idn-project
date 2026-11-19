(ns laser-show.css.theme-test
  "The theme's derived colours are real colours."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.css.theme :as theme]))

(deftest computed-colors-have-no-nil-test
  (testing "every computed colour is a hex colour"
    (doseq [[k v] theme/computed-colors]
      (is (and (string? v) (re-matches #"#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?" v))
          (str k " is " (pr-str v)))))
  (testing "a cell with a cue is the border grey, and lighter on hover"
    (is (= "#505050" (:cell-content theme/computed-colors)))
    (is (= "#656565" (:cell-content-hover theme/computed-colors)))))
